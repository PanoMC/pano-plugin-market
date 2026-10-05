package com.panomc.plugins.market.e2e

import com.panomc.plugins.market.e2e.support.E2eBuyer
import com.panomc.plugins.market.e2e.support.E2eClient
import com.panomc.plugins.market.e2e.support.E2eRace
import com.panomc.plugins.market.e2e.support.E2eResponse
import com.panomc.plugins.market.e2e.support.E2eTestBase
import com.panomc.plugins.market.e2e.support.another
import com.panomc.plugins.market.support.Await
import com.panomc.plugins.market.support.FakePayGateway
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Race conditions over HTTP (17 section 9.4): R-01, R-04, R-10. Each runs [com.panomc.plugins.market.support.Race.rounds] rounds with fresh fixtures. */
class RaceE2E : E2eTestBase() {
    override val tag = "race"

    private val buyers: List<E2eBuyer> by lazy { (1..20).map { buyer() } }

    @Test
    fun `R-01 double webhook concurrent`() {
        E2eRace.rounds("R-01") { _ ->
            val vip = catalog.fresh("VIP")
            val buyer = buyer()
            val publicId = publicIdOf(checkout(buyer.client, cart(line(vip.id))).ok())
            val reference = referenceOf(publicId)
            val payment = gateway.payments[reference]!!
            val event = gateway.nextEventId() + "_" + reference.takeLast(6)
            val data = JsonObject().put("reference", reference).put("amount", payment.amount.toPlainString()).put("currency", payment.currency)

            gateway.setStatus(reference, "paid")

            // the two copies are the same signed bytes, posted by two warmed clients released together (17 section 8.4): the spread of the starts
            // is measured, so a delivery that did not overlap fails the harness check of E2eRace.rounds instead of passing as "concurrent"
            val body = gateway.eventBody("payment.succeeded", data, event)
            val signature = checkNotNull(gateway.signatureHeader(body, FakePayGateway.Signature.VALID))
            val hooks = (1..2).map { E2eClient(baseUrl, "webhook$it") }
            val round = E2eRace.round(
                2,
                setup = { i -> hooks[i].also { it.warm() } },
                action = { hook -> hook.request("POST", "/api/market/payments/fake/webhook", body, mapOf("X-Fake-Signature" to signature), csrf = false, cookiesOn = false) }
            )
            val answers = round.values()

            assertEquals(listOf(200, 200), answers.map { it.status }, "both deliveries are answered 200")
            awaitOrder(publicId, "COMPLETED")
            assertEquals("SUCCEEDED", attemptStatus(reference))
            assertEquals(1L, orderEvents(publicId, "PAYMENT_SUCCEEDED"), "one PAYMENT_SUCCEEDED timeline row")
            val orderId = orderRow(publicId).getLong("id")
            assertEquals(1L, db.count("market_order_event", "`orderId` = ? AND `toStatus` = 'COMPLETED'", orderId), "one transition to COMPLETED (O2)")

            // 02 section 7.3 step 5: one row PROCESSED holds the provider key (uq_event) and counts the second copy, the second copy is settled on its own
            // `r:<uuid>` row as DUPLICATE. Both rows carry the request hash of the identical body (the duplicate has no payment / order: it applied nothing).
            val holder = db.sql("SELECT `status`, `duplicateCount`, `verified`, `requestHash` FROM `pano_market_payment_event` WHERE `providerId` = 'fake' AND `eventKey` = ?", "e:$event")
            assertEquals(1, holder.size, "the event key holds one row (uq_event)")
            assertEquals("PROCESSED", holder[0].getString("status"))
            assertEquals(1, holder[0].getInteger("duplicateCount"), "the second delivery is counted on the row that holds the key")
            assertEquals(1, holder[0].getInteger("verified"))

            val hash = holder[0].getString("requestHash")
            assertEquals(
                2L, db.count("market_payment_event", "`providerId` = 'fake' AND `direction` = 'IN' AND `requestHash` = ?", hash),
                "every inbound request is stored: two rows for the two copies"
            )
            assertEquals(
                1L, db.count("market_payment_event", "`providerId` = 'fake' AND `direction` = 'IN' AND `requestHash` = ? AND `status` = 'DUPLICATE' AND `verified` = 1 AND `eventKey` LIKE 'r:%'", hash),
                "the other copy is settled DUPLICATE on its own row"
            )
            assertEquals(
                0L, db.count("market_payment_event", "`providerId` = 'fake' AND `direction` = 'IN' AND `requestHash` = ? AND `status` NOT IN ('PROCESSED', 'DUPLICATE')", hash),
                "no copy is left RECEIVED / FAILED (applied twice or retried forever)"
            )

            // one set of side effects
            assertSingleSetOfSideEffects(orderId)

            round
        }
    }

    @Test
    fun `R-04 concurrent purchase of the last stock unit`() {
        E2eRace.rounds("R-04") { _ ->
            val last = catalog.fresh("LAST") // stock 1
            val clients = buyers.map { it.client }
            val round = E2eRace.round(
                20,
                setup = { i -> clients[i].also { it.warm() } },
                action = { client -> checkout(client, cart(line(last.id)), key = idempotencyKey()) }
            )
            val answers = round.values()

            assertEquals(1, answers.count { it.status == 200 }, "exactly one buyer wins: ${answers.map { it.status }}")
            assertEquals(19, answers.count { it.status == 409 && it.error == "OUT_OF_STOCK" }, "the others get 409 OUT_OF_STOCK: ${answers.map { it.status to it.error }}")
            assertEquals(0L, productStock(last.id), "stock is 0")
            assertEquals(1L, reserved(last.id), "one unit is reserved (the sum of the stockReserved of the order items)")

            // the winner expires: the unit is back
            val winner = answers.first { it.status == 200 }.obj().getJsonObject("order").getString("publicId")
            db.rewind("market_order", orderRow(winner).getLong("id"), "expiresAt", 2 * 3_600_000L)
            Await.until(45_000, 500, "the winner order expired") { orderStatus(winner) == "EXPIRED" }
            assertEquals(1L, productStock(last.id), "the stock is back after the expiry")
            assertEquals(0L, reserved(last.id), "and nothing is reserved any more")

            round
        }
    }

    @Test
    fun `R-10 same idempotency key concurrent`() {
        E2eRace.rounds("R-10") { _ ->
            val vip = catalog.fresh("VIP")
            val owner = buyer()
            val clients = (0 until 10).map { owner.another(baseUrl, "${owner.username}#$it") }
            val key = idempotencyKey()
            val creates = gateway.requests(FakePayGateway.Op.CREATE).size
            val body = { cart(line(vip.id)) }
            val round = E2eRace.round(
                10,
                setup = { i -> clients[i].also { it.warm() } },
                action = { client -> checkout(client, body(), key = key) }
            )
            val answers: List<E2eResponse> = round.values()

            assertEquals(List(10) { 200 }, answers.map { it.status }, "every replay answers 200: ${answers.map { it.status to (it.error ?: "") }} ${answers.firstOrNull { it.status != 200 }?.text}")
            assertEquals(1, answers.map { it.obj().getJsonObject("order").getString("publicId") }.toSet().size, "one publicId for all ten")
            assertEquals(1L, db.count("market_order", "`idempotencyKey` = ?", key), "one order row")
            assertEquals(creates + 1, gateway.requests(FakePayGateway.Op.CREATE).size, "one attempt created at the gateway")

            val other = checkout(clients[0], cart(line(vip.id, quantity = 2)), key = key)
            assertEquals(409, other.status)
            assertEquals("IDEMPOTENCY_CONFLICT", other.error)

            round
        }
    }

    private fun reserved(productId: Long): Long = db.long("SELECT COALESCE(SUM(`stockReserved`), 0) FROM `pano_market_order_item` WHERE `productId` = ?", productId) ?: 0L

    /**
     * 17 section 9.4 R-01 "one set of deliveries / mail / webhook": per order no business key appears twice. The expected row count is exact: a
     * table that starts filling must fail here so the slice that fills it states its expected set (and the cardinality check below then bites on
     * it). Delivery (MK-102): the standard VIP product has four GRANT-phase actions (`a1`, `a2`, `r1`, `r2` of `E2eCatalog.grantAndRevoke`: two
     * permission and two credit actions), so the O2 transaction plans exactly four rows, however many copies of the webhook race for it. The mail and
     * webhook subsystems still write nothing for a VIP purchase until their slices (MK-11x, MK-14x) land.
     */
    private fun assertSingleSetOfSideEffects(orderId: Long) {
        val sideEffects = mapOf(
            "market_delivery" to Triple("orderId", "`orderItemId`, `actionId`, `unitIndex`, `phase`, `attemptGroup`", 4L),
            "market_mail_outbox" to Triple("orderId", "`kind`, `recipient`", 0L),
            "market_webhook_delivery" to Triple("orderId", "`endpointId`, `event`", 0L)
        )

        for ((table, spec) in sideEffects) {
            val (column, key, expected) = spec
            val row = db.sql("SELECT COUNT(*) AS n, COUNT(DISTINCT $key) AS d FROM `pano_$table` WHERE `$column` = ?", orderId).first()

            assertEquals(expected, row.getLong("n"), "rows of $table for the order (exactly one set)")
            assertEquals(row.getLong("n"), row.getLong("d"), "no business key of $table exists twice for the order")
        }
    }
}
