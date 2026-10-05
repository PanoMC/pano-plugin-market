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

            val answers = gateway.sendWebhook("payment.succeeded", data, id = event, copies = 2, concurrent = true)

            assertEquals(listOf(200, 200), answers.map { it.statusCode() }, "both deliveries are answered 200")
            awaitOrder(publicId, "COMPLETED")
            assertEquals("SUCCEEDED", attemptStatus(reference))
            assertEquals(1L, orderEvents(publicId, "PAYMENT_SUCCEEDED"), "one PAYMENT_SUCCEEDED timeline row")
            val orderId = orderRow(publicId).getLong("id")
            assertEquals(1L, db.count("market_order_event", "`orderId` = ? AND `toStatus` = 'COMPLETED'", orderId), "one transition to COMPLETED (O2)")

            val rows = db.sql("SELECT `status`, `duplicateCount`, `verified` FROM `pano_market_payment_event` WHERE `providerId` = 'fake' AND `eventKey` = ?", "e:$event")
            assertEquals(1, rows.size, "the event key holds one row (uq_event)")
            assertEquals("PROCESSED", rows[0].getString("status"))
            assertEquals(1, rows[0].getInteger("duplicateCount"), "the second delivery is counted on the row that holds the key")
            assertEquals(1, rows[0].getInteger("verified"))

            // one set of side effects: no delivery, mail or webhook row exists twice for this order
            for (table in listOf("market_delivery", "market_mail_outbox", "market_webhook_delivery")) {
                if (tableExists(table)) assertEquals(0L, duplicates(table, orderId), "no duplicated rows in $table")
            }

            E2eRace.Round<Unit>(emptyList(), 0) // the two deliveries are fired together by the gateway helper; nothing else to measure
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

    private fun tableExists(table: String): Boolean =
        db.long("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?", "pano_$table") == 1L

    /** Rows of [table] that name [orderId] more than once with the same business key. */
    private fun duplicates(table: String, orderId: Long): Long {
        val columns = db.sql("SELECT column_name AS c FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ?", "pano_$table").map { it.getString("c") }
        val key = listOf("idempotencyKey", "dedupeKey", "eventKey", "key").firstOrNull { it in columns } ?: return 0L
        val order = if ("orderId" in columns) "`orderId`" else return 0L

        return db.sql("SELECT COUNT(*) AS n FROM (SELECT `$key` FROM `pano_$table` WHERE $order = ? GROUP BY `$key` HAVING COUNT(*) > 1) d", orderId).first().getLong("n")
    }
}
