package com.panomc.plugins.market.e2e

import com.panomc.plugins.market.e2e.support.E2eBuyer
import com.panomc.plugins.market.e2e.support.E2eCatalog
import com.panomc.plugins.market.e2e.support.E2eClient
import com.panomc.plugins.market.e2e.support.E2eRace
import com.panomc.plugins.market.e2e.support.E2eResponse
import com.panomc.plugins.market.e2e.support.E2eTestBase
import com.panomc.plugins.market.e2e.support.another
import com.panomc.plugins.market.support.Await
import com.panomc.plugins.market.support.FakePayGateway
import com.panomc.plugins.market.support.HarnessNoConcurrency
import com.panomc.plugins.market.support.InvariantChecker
import com.panomc.plugins.market.support.Race
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Race conditions over HTTP (17 section 9.4), all 28 scenarios: R-01, R-04, R-10 (MK-080), R-02, R-03, R-05 to R-09 and R-11 to R-14 (E2E-05) and R-15 to R-28
 * (E2E-06). Each runs [com.panomc.plugins.market.support.Race.rounds] rounds with fresh fixtures; the harness proves that the requests of a round overlapped
 * ([E2eRace]), except R-02 whose ten deliveries are sequential by definition.
 */
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

            settle()
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

            settle()
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

            settle()
            round
        }
    }

    // ==============================================================================================================================
    // E2E-05: R-02, R-03, R-05 to R-09 and R-11 to R-14 (17 section 9.4), completing the class to the 28 scenarios of the catalogue.
    // ==============================================================================================================================

    @Test
    fun `R-02 double webhook sequential x 10`() {
        // sequential: there is nothing to overlap, so no harness round; the five rounds of the catalogue are five fresh orders
        repeat(Race.rounds) { n ->
            val vip = catalog.fresh("VIP")
            val buyer = buyer()
            val publicId = publicIdOf(checkout(buyer.client, cart(line(vip.id))).ok())
            val reference = referenceOf(publicId)
            val payment = gateway.payments.getValue(reference)
            val event = gateway.nextEventId() + "_" + reference.takeLast(6)

            gateway.setStatus(reference, "paid")

            // the same signed bytes ten times, one after the other, on one connection
            val hook = signed("payment.succeeded", JsonObject().put("reference", reference).put("amount", payment.amount.toPlainString()).put("currency", payment.currency), event)
            val client = E2eClient(baseUrl, "r02-$n").also { it.warm() }
            val answers = (1..10).map { post(client, hook) }

            assertEquals(List(10) { 200 }, answers.map { it.status }, "every one of the ten deliveries is answered 200")
            awaitOrder(publicId, "COMPLETED")
            assertEquals("SUCCEEDED", attemptStatus(reference))
            assertEquals(1L, orderEvents(publicId, "PAYMENT_SUCCEEDED"), "one PAYMENT_SUCCEEDED timeline row")

            val orderId = orderRow(publicId).getLong("id")

            assertEquals(1L, db.count("market_order_event", "`orderId` = ? AND `toStatus` = 'COMPLETED'", orderId), "one transition to COMPLETED (O2)")
            assertEquals(1L, db.count("market_payment", "`orderId` = ? AND `status` = 'SUCCEEDED' AND `duplicate` = 0", orderId), "one SUCCEEDED attempt (I13)")

            // 02 section 7.3 step 5: the first copy holds the key (PROCESSED) and counts the nine that follow, each of them is settled DUPLICATE on its own `r:` row
            val holder = db.sql("SELECT `status`, `duplicateCount`, `verified`, `requestHash` FROM `pano_market_payment_event` WHERE `providerId` = 'fake' AND `eventKey` = ?", "e:$event")

            assertEquals(1, holder.size, "the event key holds one row (uq_event)")
            assertEquals("PROCESSED", holder[0].getString("status"))
            assertEquals(9, holder[0].getInteger("duplicateCount"), "the nine later copies are counted on the row that holds the key")

            val hash = holder[0].getString("requestHash")

            assertEquals(10L, db.count("market_payment_event", "`providerId` = 'fake' AND `direction` = 'IN' AND `requestHash` = ?", hash), "every inbound request is stored: ten rows for the ten copies")
            assertEquals(
                9L, db.count("market_payment_event", "`providerId` = 'fake' AND `direction` = 'IN' AND `requestHash` = ? AND `status` = 'DUPLICATE' AND `verified` = 1 AND `eventKey` LIKE 'r:%'", hash),
                "the other nine are DUPLICATE rows of their own"
            )
            assertEquals(
                0L, db.count("market_payment_event", "`providerId` = 'fake' AND `direction` = 'IN' AND `requestHash` = ? AND `status` NOT IN ('PROCESSED', 'DUPLICATE')", hash),
                "no copy is left RECEIVED / FAILED"
            )

            assertSingleSetOfSideEffects(orderId)
            settle()
        }
    }

    @Test
    fun `R-03 the same fact through several channels`() {
        // Channels fired together (17 section 9.4): the success webhook, the same fact under another event key, two browser returns (each asks the gateway),
        // the buyer's status poll and the panel `POST /payments/:paymentId/query` (MK-171). The gateway reports the payment paid, so every channel carries the
        // same fact; only the state machine can keep it from completing the order more than once.
        E2eRace.rounds("R-03") { _ ->
            val vip = catalog.fresh("VIP")
            val buyer = buyer()
            val publicId = publicIdOf(checkout(buyer.client, cart(line(vip.id))).ok())
            val reference = referenceOf(publicId)
            val orderId = orderRow(publicId).getLong("id")
            val paymentId = db.long("SELECT `id` FROM `pano_market_payment` WHERE `reference` = ?", reference)!!
            val first = paidHook(reference)
            val second = paidHook(reference)
            val returnTarget = returnPath(reference, "success")
            val clients = (0 until 6).map {
                when (it) {
                    3 -> buyer.another(baseUrl, "r03-status")
                    5 -> adminClone("r03-query")
                    else -> E2eClient(baseUrl, "r03-$it")
                }
            }

            val round = E2eRace.round(
                6,
                setup = { i -> i.also { clients[it].warm() } },
                action = { i ->
                    when (i) {
                        0 -> post(clients[0], first)
                        1 -> post(clients[1], second)
                        3 -> clients[3].get("/api/market/orders/$publicId/status")
                        5 -> clients[5].post("/api/panel/market/payments/$paymentId/query", JsonObject())
                        else -> clients[i].get(returnTarget)
                    }
                }
            )
            val answers = round.values()

            assertEquals(listOf(200, 200), listOf(answers[0].status, answers[1].status), "both webhooks are answered 200")
            assertEquals(listOf(303, 303), listOf(answers[2].status, answers[4].status), "a return only ever redirects")
            assertEquals(200, answers[3].status, "the status poll is answered 200: ${answers[3].error}")
            assertEquals(200, answers[5].status, "the panel query is answered 200: ${answers[5].error} ${answers[5].text}")
            assertEquals("SUCCEEDED", answers[5].obj().getString("status"), "the query answers the attempt as the gateway reports it: paid")

            awaitOrder(publicId, "COMPLETED")
            assertEquals("SUCCEEDED", attemptStatus(reference))
            assertEquals(1L, orderEvents(publicId, "PAYMENT_SUCCEEDED"), "one PAYMENT_SUCCEEDED timeline row, whichever channel was first")
            assertEquals(1L, db.count("market_order_event", "`orderId` = ? AND `toStatus` = 'COMPLETED'", orderId), "one transition to COMPLETED: the state machine is the guard, not the event key")
            assertEquals(1L, db.count("market_payment", "`orderId` = ? AND `status` = 'SUCCEEDED' AND `duplicate` = 0", orderId), "one SUCCEEDED attempt (I13)")
            assertEquals(0L, db.count("market_payment", "`orderId` = ? AND `id` <> ?", orderId, paymentId), "no second attempt was created by any channel")

            assertSingleSetOfSideEffects(orderId)
            settle()
            round
        }
    }

    @Test
    fun `R-05 last units at variant level`() {
        E2eRace.rounds("R-05") { _ ->
            val crate = catalog.fresh("VAR")
            val small = db.long("SELECT `id` FROM `pano_market_product_variant` WHERE `productId` = ? ORDER BY `position` LIMIT 1", crate.id)!!
            val clients = buyers.map { it.client }

            assertEquals(2L, variantStock(small), "variant S starts with stock 2")

            val round = E2eRace.round(
                20,
                setup = { i -> clients[i].also { it.warm() } },
                action = { client -> checkout(client, cart(line(crate.id, 1, small)), key = idempotencyKey()) }
            )
            val answers = round.values()

            assertEquals(2, answers.count { it.status == 200 }, "exactly two buyers win: ${answers.map { it.status }}")
            assertEquals(18, answers.count { it.status == 409 && it.error == "OUT_OF_STOCK" }, "the others get 409 OUT_OF_STOCK: ${answers.map { it.status to it.error }}")
            assertEquals(0L, variantStock(small), "the variant stock is 0")
            assertEquals(2L, reservedOfVariant(small), "two units are reserved (the sum of the stockReserved of the order items)")
            assertEquals(2L, db.count("market_order_item", "`variantId` = ?", small), "exactly two orders hold the variant")

            // the winners cancel: both units are back and nothing stays booked (the expiry path is R-04's)
            for ((i, answer) in answers.withIndex()) if (answer.status == 200) buyers[i].client.post("/api/market/orders/${publicIdOf(answer)}/cancel", JsonObject()).ok()

            assertEquals(2L, variantStock(small), "the stock is back after the cancellations")
            assertEquals(0L, reservedOfVariant(small), "and nothing is reserved any more")

            settle()
            round
        }
    }

    @Test
    fun `R-06 coupon global limit`() {
        E2eRace.rounds("R-06") { _ ->
            val product = catalog.fresh("VIP")
            val (couponId, code) = catalog.freshCoupon(10, redeemLimit = 3)
            val clients = buyers.map { it.client }

            val round = E2eRace.round(
                20,
                setup = { i -> clients[i].also { it.warm() } },
                action = { client -> checkout(client, cart(line(product.id)).put("couponCode", code), key = idempotencyKey()) }
            )
            val answers = round.values()
            val refused = answers.filter { it.status != 200 }

            assertEquals(3, answers.count { it.status == 200 }, "exactly three orders take the coupon: ${answers.map { it.status to it.error }}")
            assertEquals(17, refused.count { it.status == 400 && it.error == "INVALID_COUPON" }, "the other 17 are refused: ${refused.map { it.status to it.error }}")
            assertEquals(List(17) { "CODE_LIMIT_REACHED" }, refused.map { it.obj().getString("reason") }, "with the reason of the limit: ${refused.firstOrNull()?.text}")
            assertEquals(3L, db.count("market_redemption", "`kind` = 'COUPON' AND `refId` = ? AND `state` = 'HELD'", couponId), "three HELD redemptions")
            assertEquals(3L, db.long("SELECT `usedCount` FROM `pano_market_coupon` WHERE `id` = ?", couponId), "usedCount is 3")
            assertEquals(3L, db.long("SELECT COUNT(DISTINCT `orderId`) FROM `pano_market_redemption` WHERE `kind` = 'COUPON' AND `refId` = ?", couponId), "on three different orders")

            // the winners cancel (their buyers are shared with other scenarios): the uses go back
            for ((i, answer) in answers.withIndex()) if (answer.status == 200) buyers[i].client.post("/api/market/orders/${publicIdOf(answer)}/cancel", JsonObject()).ok()

            assertEquals(0L, db.count("market_redemption", "`kind` = 'COUPON' AND `refId` = ? AND `state` = 'HELD'", couponId), "no redemption is held after the cancellations")
            assertEquals(0L, db.long("SELECT `usedCount` FROM `pano_market_coupon` WHERE `id` = ?", couponId), "usedCount is back to 0")

            settle()
            round
        }
    }

    @Test
    fun `R-07 coupon per-customer limit`() {
        E2eRace.rounds("R-07") { _ ->
            val product = catalog.fresh("VIP")
            val (couponId, code) = catalog.freshCoupon(10, customerRedeemLimit = 1)
            val owner = buyer()
            val clients = (0 until 8).map { owner.another(baseUrl, "${owner.username}#$it") }

            val round = E2eRace.round(
                8,
                setup = { i -> clients[i].also { it.warm() } },
                action = { client -> checkout(client, cart(line(product.id)).put("couponCode", code), key = idempotencyKey()) }
            )
            val answers = round.values()

            assertEquals(1, answers.count { it.status == 200 }, "exactly one of the eight checkouts succeeds: ${answers.map { it.status to it.error }}")
            assertTrue(answers.filter { it.status != 200 }.all { it.status == 400 && it.error == "INVALID_COUPON" }, "the others are refused for the coupon: ${answers.map { it.status to it.error }}")
            assertEquals(1L, db.count("market_order", "`userId` = ?", owner.userId), "one order of the buyer")
            assertEquals(1L, db.count("market_redemption", "`kind` = 'COUPON' AND `refId` = ? AND `state` = 'HELD'", couponId), "one HELD redemption")
            assertEquals(1L, db.long("SELECT `usedCount` FROM `pano_market_coupon` WHERE `id` = ?", couponId), "usedCount is 1")

            settle()
            round
        }
    }

    @Test
    fun `R-08 double spend of credits, credits only`() {
        E2eRace.rounds("R-08") { _ ->
            // a product without actions: the VIP's credit action would grant credits at the delivery job's tick and move the balance this scenario asserts
            val product = catalog.fresh("LAST", "price" to "80.00", "creditPrice" to "80.00", "stock" to "")
            val owner = buyer()
            val clients = (0 until 2).map { owner.another(baseUrl, "${owner.username}#$it") }
            val holdBefore = holdBalance()
            val spentBefore = spentBalance()

            grant(owner.userId, 100).ok()

            val round = E2eRace.round(
                2,
                setup = { i -> clients[i].also { it.warm() } },
                action = { client -> checkout(client, cart(line(product.id)).put("payWithCredits", true), method = "credits", key = idempotencyKey()) }
            )
            val answers = round.values()

            assertEquals(listOf(200, 400), answers.map { it.status }.sorted(), "one purchase succeeds, the other is refused: ${answers.map { it.status to it.error }}")
            assertEquals("INSUFFICIENT_CREDITS", answers.single { it.status == 400 }.error)

            val orderId = db.long("SELECT `id` FROM `pano_market_order` WHERE `userId` = ?", owner.userId)

            assertEquals(1L, db.count("market_order", "`userId` = ?", owner.userId), "one order")
            assertEquals(2000L, creditBalance(owner.userId), "the balance is 20.00")
            assertEquals(1L, db.count("market_credit_tx", "`orderId` = ? AND `type` = 'HOLD'", orderId), "one HOLD for the order")
            assertEquals(1L, db.count("market_credit_tx", "`orderId` = ? AND `type` = 'CAPTURE'", orderId), "and one CAPTURE: 80.00 went to SPENT")
            assertEquals(8000L, db.long("SELECT COALESCE(SUM(`amount`), 0) FROM `pano_market_credit_tx` WHERE `orderId` = ? AND `type` = 'CAPTURE'", orderId), "of 80.00")
            assertEquals(holdBefore, holdBalance(), "nothing stays on hold")
            assertEquals(spentBefore + 8000L, spentBalance(), "SPENT grew by 80.00, once")

            settle()
            round
        }
    }

    @Test
    fun `R-09 double spend of credits, mixed`() {
        val winners = ArrayList<Pair<E2eBuyer, String>>()

        E2eRace.rounds("R-09") { _ ->
            val product = catalog.fresh("LAST", "price" to "100.00", "stock" to "")
            val owner = buyer()
            val clients = (0 until 5).map { owner.another(baseUrl, "${owner.username}#$it") }

            grant(owner.userId, 100).ok()

            val holdBefore = holdBalance()
            val round = E2eRace.round(
                5,
                setup = { i -> clients[i].also { it.warm() } },
                action = { client -> checkout(client, cart(line(product.id)).put("useCredits", 60), key = idempotencyKey()) }
            )
            val answers = round.values()

            assertEquals(1, answers.count { it.status == 200 }, "exactly one checkout holds the credits: ${answers.map { it.status to it.error }}")
            assertTrue(answers.filter { it.status != 200 }.all { it.status == 400 && it.error == "INSUFFICIENT_CREDITS" }, "the others are refused: ${answers.map { it.status to it.error }}")

            val publicId = publicIdOf(answers.single { it.status == 200 })
            val row = orderRow(publicId)

            assertEquals(6000L, row.getLong("creditAmount"), "the order holds 60.00 of credits")
            assertEquals(1L, db.count("market_order", "`userId` = ?", owner.userId), "one order")
            assertEquals(1L, db.count("market_credit_tx", "`orderId` = ? AND `type` = 'HOLD'", row.getLong("id")), "one HOLD")
            assertEquals(6000L, db.long("SELECT COALESCE(SUM(`amount`), 0) FROM `pano_market_credit_tx` WHERE `orderId` = ? AND `type` = 'HOLD'", row.getLong("id")), "of 60.00")
            assertEquals(4000L, creditBalance(owner.userId), "100.00 - 60.00 held")
            assertEquals(holdBefore + 6000L, holdBalance(), "HOLD holds the 60.00")

            winners += owner to publicId
            settle()
            round
        }

        // the five unpaid orders expire together (one wait for the job instead of one per round): the hold is released and every balance is whole again
        for ((_, publicId) in winners) db.rewind("market_order", orderRow(publicId).getLong("id"), "expiresAt", 2 * 3_600_000L)

        Await.until(90_000, 500, "the five orders expired") { winners.all { orderStatus(it.second) == "EXPIRED" } }

        for ((owner, publicId) in winners) {
            assertEquals(10_000L, creditBalance(owner.userId), "the balance of ${owner.username} is 100.00 again after the expiry")
            assertEquals(1L, db.count("market_credit_tx", "`orderId` = ? AND `type` = 'RELEASE'", orderRow(publicId).getLong("id")), "the hold of $publicId was released once")
            assertEquals(
                6000L, db.long("SELECT COALESCE(SUM(`amount`), 0) FROM `pano_market_credit_tx` WHERE `orderId` = ? AND `type` = 'RELEASE'", orderRow(publicId).getLong("id")),
                "and it released the whole 60.00"
            )
        }

        assertEquals(0L, db.long("SELECT COALESCE(SUM(`balance`), 0) FROM `pano_market_credit_account` WHERE `systemKey` = 'HOLD' AND `balance` < 0"), "the hold is never negative")
        settle()
    }

    @Test
    fun `R-11 per-player limit`() {
        E2eRace.rounds("R-11") { _ ->
            // the product keeps limitPerPlayer = 1 and loses its cooldown (R-12's), so a refusal can only be the limit
            val product = catalog.fresh("LIMITED", "cooldownSeconds" to "")
            val recipient = buyer()
            val gifters = (0 until 5).map { buyer() }
            val own = (0 until 5).map { recipient.another(baseUrl, "${recipient.username}#$it") }

            // actors 0 to 4 buy for themselves (the recipient, five connections of one login), actors 5 to 9 are other buyers sending it as a gift
            val round = E2eRace.round(
                10,
                setup = { i -> i.also { if (it < 5) own[it].warm() else gifters[it - 5].client.warm() } },
                action = { i ->
                    if (i < 5) checkout(own[i], cart(line(product.id)), key = idempotencyKey())
                    else checkout(gifters[i - 5].client, cart(line(product.id)).put("recipientUsername", recipient.username), key = idempotencyKey())
                }
            )
            val answers = round.values()

            assertEquals(1, answers.count { it.status == 200 }, "exactly one of the ten checkouts succeeds: ${answers.map { it.status to it.error }}")
            assertEquals(9, answers.count { it.status == 409 && it.error == "PURCHASE_LIMIT_REACHED" }, "the other nine are refused: ${answers.map { it.status to it.error }}")
            assertEquals(1L, db.long("SELECT COUNT(DISTINCT `orderId`) FROM `pano_market_order_item` WHERE `productId` = ?", product.id), "exactly one order holds the product")

            settle()
            round
        }
    }

    @Test
    fun `R-12 cooldown`() {
        E2eRace.rounds("R-12") { _ ->
            // limitPerPlayer is taken off (R-11's), the cooldown of 3600 s stays
            val product = catalog.fresh("LIMITED", "limitPerPlayer" to "")
            val owner = buyer()
            val clients = (0 until 6).map { owner.another(baseUrl, "${owner.username}#$it") }

            val round = E2eRace.round(
                6,
                setup = { i -> clients[i].also { it.warm() } },
                action = { client -> checkout(client, cart(line(product.id)), key = idempotencyKey()) }
            )
            val answers = round.values()
            val refused = answers.filter { it.status != 200 }

            assertEquals(1, answers.count { it.status == 200 }, "exactly one checkout succeeds: ${answers.map { it.status to it.error }}")
            assertEquals(5, refused.count { it.status == 409 && it.error == "COOLDOWN_ACTIVE" }, "the other five are refused: ${refused.map { it.status to it.error }}")
            assertTrue(refused.all { (it.obj().getInteger("retryAfter") ?: 0) in 1..3600 }, "each tells when to come back: ${refused.map { it.json?.getValue("retryAfter") }}")
            assertEquals(1L, db.long("SELECT COUNT(DISTINCT `orderId`) FROM `pano_market_order_item` WHERE `productId` = ?", product.id), "exactly one order holds the product")

            settle()
            round
        }
    }

    @Test
    fun `R-13 concurrent full refunds`() {
        val ends = java.util.concurrent.ConcurrentHashMap<String, Int>()

        E2eRace.rounds("R-13") { round ->
            // a product without actions: nothing is delivered, so the refund has nothing to revoke first
            val placed = (0 until BATCH).map { place(catalog.fresh("LAST", "stock" to ""), buyers[it]) }

            for (p in placed) {
                payViaFake(p.publicId)
                awaitOrder(p.publicId, "COMPLETED")
            }

            val callsBefore = placed.map { gatewayRefunds(it) }
            val payers = placed.map { Pair(adminClone("r13a-${it.publicId.takeLast(4)}"), adminClone("r13b-${it.publicId.takeLast(4)}")) }

            // actors 2k and 2k + 1 refund order k in full under different keys, the second [sweep] ms after the gate: the offsets walk it from "while the first
            // is being recorded" to "after the first is done"
            val raced = E2eRace.round(
                placed.size * 2,
                setup = { i -> i.also { (if (it % 2 == 0) payers[it / 2].first else payers[it / 2].second).warm() } },
                action = { i ->
                    val k = i / 2

                    if (i % 2 == 0) payers[k].first.post("/api/panel/market/orders/${placed[k].orderId}/refunds", JsonObject(), keyed())
                    else {
                        Thread.sleep(sweep(k, round))
                        payers[k].second.post("/api/panel/market/orders/${placed[k].orderId}/refunds", JsonObject(), keyed())
                    }
                }
            )
            val answers = raced.values()

            for ((k, p) in placed.withIndex()) {
                val own = listOf(answers[2 * k], answers[2 * k + 1])

                assertEquals(listOf(200, 400), own.map { it.status }.sorted(), "${p.publicId}: one refund is accepted, the other is refused, not failed: ${own.map { it.status to it.error }}")

                val refused = own.single { it.status == 400 }

                // the other refund meets either the first one still in flight (nothing left to refund, max 0) or an order that is refunded already
                ends.merge(refused.error.orEmpty(), 1, Int::plus)
                assertTrue(refused.error == "INVALID_REFUND_AMOUNT" || refused.error == "INVALID_ORDER_TRANSITION", "${p.publicId}: refused as ${refused.error}: ${refused.text}")

                if (refused.error == "INVALID_REFUND_AMOUNT") assertEquals(0.0, refused.obj().getDouble("max"), 0.0001, "${p.publicId}: nothing is left to refund")

                val row = orderRow(p.publicId)

                assertEquals("SUCCEEDED", own.single { it.status == 200 }.obj().getJsonObject("refund").getString("status"), "${p.publicId}: the accepted refund is settled")
                assertEquals(1L, db.count("market_refund", "`orderId` = ?", p.orderId), "${p.publicId}: one refund row")
                assertEquals(1L, db.count("market_refund", "`orderId` = ? AND `status` = 'SUCCEEDED'", p.orderId), "${p.publicId}: and it is SUCCEEDED")
                assertEquals(row.getLong("totalPrice"), row.getLong("refundedTotal"), "${p.publicId}: refundedTotal = totalPrice")
                assertEquals("REFUNDED", row.getString("status"))
                assertEquals(callsBefore[k] + 1, gatewayRefunds(p), "${p.publicId}: the gateway was asked once")
            }

            settle()
            raced
        }

        println("e2e race R-13 refusals over ${5 * BATCH} orders: $ends")

        // the outcome of the catalogue (the loser sees the first refund in flight: max 0) must have happened, or the race did not reach it
        if ((ends["INVALID_REFUND_AMOUNT"] ?: 0) == 0) throw HarnessNoConcurrency("R-13")
    }

    @Test
    fun `R-14 refund replay`() {
        E2eRace.rounds("R-14") { _ ->
            val p = place(catalog.fresh("LAST", "stock" to ""), buyers[0])

            payViaFake(p.publicId)
            awaitOrder(p.publicId, "COMPLETED")

            val key = idempotencyKey()
            val before = gatewayRefunds(p)
            val clients = (0 until 5).map { adminClone("r14-$it") }

            val round = E2eRace.round(
                5,
                setup = { i -> i.also { clients[it].warm() } },
                action = { i -> clients[i].post("/api/panel/market/orders/${p.orderId}/refunds", JsonObject(), keyed(key)) }
            )
            val answers = round.values()

            assertEquals(List(5) { 200 }, answers.map { it.status }, "all five requests are answered 200: ${answers.map { it.status to it.error }}")
            assertEquals(1, answers.map { it.obj().getJsonObject("refund").encode() }.toSet().size, "five identical responses: ${answers.map { it.text }.toSet()}")
            assertEquals(1L, db.count("market_refund", "`orderId` = ?", p.orderId), "one refund row")
            assertEquals(1L, db.count("market_refund", "`orderId` = ? AND `idempotencyKey` = ?", p.orderId, key), "under the key")
            assertEquals(before + 1, gatewayRefunds(p), "one call reached the gateway")
            assertEquals("REFUNDED", orderStatus(p.publicId))

            settle()
            round
        }
    }

    // ==============================================================================================================================
    // E2E-06: R-15 to R-28 (17 section 9.4). R-16, R-17, R-18 and R-27 race requests against the scheduler or against each other on a batch of
    // independent orders per round, so one round holds many real interleavings instead of one; every order is judged on its own end state.
    // ==============================================================================================================================

    @Test
    fun `R-15 duplicate refund webhooks return the credit part once`() {
        val before = gateway.refundMode
        gateway.refundMode = FakePayGateway.RefundMode.PENDING

        try {
            E2eRace.rounds("R-15") { _ ->
                // a product without actions: the VIP's credit action would grant 2.50 credits at an unpredictable moment (the delivery job's tick) and move the balance
                val product = catalog.fresh("LAST", "price" to "30.00", "stock" to "")
                val buyer = buyer()

                grant(buyer.userId, 10).ok()

                val publicId = publicIdOf(checkout(buyer.client, cart(line(product.id)).put("useCredits", 10)).ok())
                val order = orderRow(publicId)
                val orderId = order.getLong("id")

                assertEquals(1000L, order.getLong("creditAmount"), "10.00 of the price are paid with credits")
                assertEquals(2000L, order.getLong("gatewayAmount"), "and 20.00 through the gateway")

                val reference = payViaFake(publicId)
                val payment = gateway.payments.getValue(reference)

                awaitOrder(publicId, "COMPLETED")

                val known = gateway.refunds.keys.toSet()
                val requested = admin.post("/api/panel/market/orders/$orderId/refunds", JsonObject(), keyed()).ok().obj().getJsonObject("refund")
                val refundId = requested.getLong("id")
                val gatewayRefund = (gateway.refunds.keys - known).single()

                assertEquals("PENDING", requested.getString("status"), "the gateway answered pending: the refund waits for its confirmation")
                assertEquals(0L, db.count("market_credit_tx", "`userId` = ? AND `type` = 'REFUND'", buyer.userId), "the credit part is not returned before the gateway part is confirmed")

                // the same confirmation twice (one event key) and once more under another key: the state of the refund, not the event key, is the guard
                val shared = gateway.nextEventId()
                val hooks = listOf(shared, shared, gateway.nextEventId()).map { eventId ->
                    signed(
                        "refund.updated",
                        JsonObject().put("reference", reference).put("state", "SUCCEEDED").put("refundId", gatewayRefund).put("amount", payment.amount.toPlainString())
                            .put("currency", payment.currency),
                        eventId
                    )
                }
                val clients = hooks.indices.map { E2eClient(baseUrl, "r15-$it") }
                val round = E2eRace.round(3, setup = { i -> i.also { clients[i].warm() } }, action = { i -> post(clients[i], hooks[i]) })

                assertEquals(listOf(200, 200, 200), round.values().map { it.status }, "every confirmation is answered 200")
                Await.until(30_000, 250, "the refund settled") { db.string("SELECT `status` FROM `pano_market_refund` WHERE `id` = ?", refundId) == "SUCCEEDED" }

                assertEquals(1L, db.count("market_refund", "`orderId` = ?", orderId), "one refund row, whoever confirmed it")
                assertEquals(
                    listOf(1000L), db.sql("SELECT `amount` FROM `pano_market_credit_tx` WHERE `userId` = ? AND `type` = 'REFUND'", buyer.userId).map { it.getLong("amount") },
                    "the credit part came back once (key refund:<id>)"
                )
                assertEquals(1000L, creditBalance(buyer.userId), "granted 10.00, spent 10.00, got 10.00 back")
                assertEquals(3000L, orderRow(publicId).getLong("refundedTotal"), "the order is refunded by its full price")
                assertEquals("REFUNDED", orderStatus(publicId))
                assertEquals(1L, db.count("market_order_event", "`orderId` = ? AND `toStatus` = 'REFUNDED'", orderId), "one transition to REFUNDED (O10)")

                settle()
                round
            }
        } finally {
            gateway.refundMode = before
        }
    }

    @Test
    fun `R-16 payment against expiry`() {
        val outcomes = java.util.concurrent.ConcurrentHashMap<String, Int>()

        E2eRace.rounds("R-16") { _ ->
            val placed = (0 until BATCH).map { place(catalog.fresh("VIP", "stock" to "1"), buyers[it]) }

            // due for the next run of the expiry job (every 30 s on the 5 s tick); the webhooks are released together at the moment the job is seen at work
            // (the first order of the batch turns EXPIRED), so the others are being expired while their success arrives
            placed.forEach { db.rewind("market_order", it.orderId, "expiresAt", 2 * 3_600_000L) }

            val clients = placed.map { E2eClient(baseUrl, "r16-${it.publicId.takeLast(4)}") }
            val round = E2eRace.round(
                placed.size,
                setup = { i ->
                    clients[i].warm()

                    if (i == 0) awaitFirstExpiry(placed.map { it.orderId })

                    i
                },
                action = { i -> post(clients[i], paidHook(placed[i].reference)) }
            )

            assertTrue(round.values().all { it.status == 200 }, "every success webhook is answered 200: ${round.values().map { it.status }}")

            for (p in placed) {
                val status = awaitSettled(p.publicId)

                outcomes.merge(status, 1, Int::plus)
                assertPaidOrLate(p, status, "R-16 ${p.publicId}")
            }

            settle()
            round
        }

        println("e2e race R-16 outcomes over ${5 * BATCH} orders: $outcomes")
    }

    @Test
    fun `R-17 payment against buyer cancel`() {
        val outcomes = java.util.concurrent.ConcurrentHashMap<String, Int>()

        E2eRace.rounds("R-17") { round ->
            val placed = (0 until BATCH).map { place(catalog.fresh("VIP", "stock" to "1"), buyers[it]) }
            val hooks = placed.map { paidHook(it.reference) }
            val webhookClients = placed.map { E2eClient(baseUrl, "r17w-${it.publicId.takeLast(4)}") }

            // actor 2k pays order k, actor 2k + 1 is its buyer cancelling it, [sweep] ms after the gate: the offsets walk the cancel through the window in which
            // the payment is being applied, so some orders see the payment first and some the cancel first
            val raced = E2eRace.round(
                placed.size * 2,
                setup = { i -> i.also { if (it % 2 == 0) webhookClients[it / 2].warm() else placed[it / 2].buyer.client.warm() } },
                action = { i ->
                    if (i % 2 == 0) post(webhookClients[i / 2], hooks[i / 2])
                    else {
                        Thread.sleep(sweep(i / 2, round))
                        placed[i / 2].buyer.client.post("/api/market/orders/${placed[i / 2].publicId}/cancel", JsonObject())
                    }
                }
            )
            val answers = raced.values()

            for ((k, p) in placed.withIndex()) {
                val pay = answers[2 * k]
                val cancel = answers[2 * k + 1]

                assertEquals(200, pay.status, "the success webhook of ${p.publicId} is answered 200")

                val status = awaitSettled(p.publicId)

                outcomes.merge("$status/cancel ${cancel.status}", 1, Int::plus)

                // either the payment was first (the order is COMPLETED and the cancel was refused) or the cancel was (the late payment waits in review)
                when (status) {
                    "COMPLETED" -> {
                        assertEquals(409, cancel.status, "${p.publicId}: a paid order cannot be cancelled")
                        assertEquals("ORDER_NOT_CANCELLABLE", cancel.error)
                    }
                    else -> assertEquals(200, cancel.status, "${p.publicId}: a cancelled order whose payment arrives later is in review")
                }

                assertPaidOrLate(p, status, "R-17 ${p.publicId}")
            }

            settle()
            raced
        }

        println("e2e race R-17 outcomes over ${5 * BATCH} orders: $outcomes")
    }

    @Test
    fun `R-18 two concurrent retries leave one open attempt`() {
        E2eRace.rounds("R-18") { _ ->
            val product = catalog.fresh("VIP")
            val placed = (0 until 4).map { place(product, buyers[it]) }
            val clients = placed.map { p -> (0 until 2).map { p.buyer.another(baseUrl, "r18-${p.publicId.takeLast(4)}#$it") } }

            val round = E2eRace.round(
                placed.size * 2,
                setup = { i -> i.also { clients[it / 2][it % 2].warm() } },
                action = { i -> clients[i / 2][i % 2].post("/api/market/orders/${placed[i / 2].publicId}/pay", JsonObject().put("paymentMethodId", "fake")) }
            )
            val answers = round.values()

            for ((k, p) in placed.withIndex()) {
                val own = listOf(answers[2 * k], answers[2 * k + 1])

                assertEquals(listOf(200, 200), own.map { it.status }, "${p.publicId}: both retries are answered 200: ${own.map { it.status to it.error }}")

                val attempts = db.sql("SELECT `status` FROM `pano_market_payment` WHERE `orderId` = ? ORDER BY `id`", p.orderId).map { it.getString("status") }
                val open = attempts.count { it in setOf("CREATED", "PENDING", "PROCESSING") }

                assertEquals(1, open, "${p.publicId}: at most one non-terminal attempt (I10), exactly the newest one: $attempts")
                assertEquals(attempts.size - 1, attempts.count { it == "CANCELLED" }, "${p.publicId}: every other attempt is CANCELLED: $attempts")
                assertTrue(attempts.size >= 3, "${p.publicId}: the checkout attempt and one attempt per retry: $attempts")

                // the one open attempt is the one that can still be paid
                payViaFake(p.publicId)
                awaitOrder(p.publicId, "COMPLETED")
                assertEquals(1L, db.count("market_payment", "`orderId` = ? AND `status` = 'SUCCEEDED' AND `duplicate` = 0", p.orderId), "${p.publicId}: one SUCCEEDED attempt (I13)")
            }

            settle()
            round
        }
    }

    @Test
    fun `R-19 invoice numbers under load`() {
        E2eRace.rounds("R-19") { _ ->
            val product = catalog.fresh("VIP")
            val placed = (0 until 20).map { place(product, buyers[it]) }
            val ids = placed.map { it.orderId }
            val marks = ids.joinToString(",") { "?" }
            val clients = placed.map { E2eClient(baseUrl, "r19-${it.publicId.takeLast(4)}") }

            val round = E2eRace.round(20, setup = { i -> i.also { clients[i].warm() } }, action = { i -> post(clients[i], paidHook(placed[i].reference)) })

            assertEquals(List(20) { 200 }, round.values().map { it.status }, "all 20 success webhooks are answered 200")

            for (p in placed) awaitOrder(p.publicId, "COMPLETED")

            Await.until(30_000, 250, "20 invoices exist") { db.count("market_invoice", "`orderId` IN ($marks) AND `type` = 'INVOICE'", *ids.toTypedArray()) == 20L }

            val rows = db.sql("SELECT `series`, `sequence` FROM `pano_market_invoice` WHERE `orderId` IN ($marks) AND `type` = 'INVOICE'", *ids.toTypedArray())

            assertEquals(20, rows.size, "one invoice per order")
            assertEquals(20, rows.map { it.getString("series") to it.getLong("sequence") }.toSet().size, "no number twice")

            for ((series, ofSeries) in rows.groupBy { it.getString("series") }) {
                val numbers = ofSeries.map { it.getLong("sequence") }
                val low = numbers.min()
                val high = numbers.max()

                assertEquals(
                    high - low + 1, db.count("market_invoice", "`series` = ? AND `sequence` BETWEEN ? AND ?", series, low, high),
                    "series $series: no number is missing between $low and $high (I15)"
                )
            }

            settle()
            round
        }
    }

    @Test
    fun `R-20 gift code redeemed twice`() {
        E2eRace.rounds("R-20") { _ ->
            val product = catalog.fresh("VIP")
            val code = "GF" + uniqueCode(10)
            val giftId = admin.post(
                "/api/panel/market/gifts", JsonObject().put("code", code).put("type", "PRODUCT").put("productId", product.id).put("redeemLimit", 1)
            ).ok().obj().getLong("id")
            val users = listOf(buyers[0], buyers[1])

            val round = E2eRace.round(
                2,
                setup = { i -> i.also { users[it].client.warm() } },
                action = { i -> users[i].client.post("/api/market/me/gifts/redeem", JsonObject().put("code", code)) }
            )
            val answers = round.values()

            assertEquals(listOf(200, 400), answers.map { it.status }.sorted(), "one redeems, the other is refused: ${answers.map { it.status to it.error }}")

            val refused = answers.single { it.status == 400 }

            assertEquals("INVALID_GIFT_CODE", refused.error)
            assertEquals("CODE_LIMIT_REACHED", refused.obj().getString("reason"), "the reason of the refusal: ${refused.text}")

            assertEquals(1L, db.count("market_order", "`giftId` = ?", giftId), "one order carries the gift")
            assertEquals(1L, db.count("market_redemption", "`kind` = 'GIFT' AND `refId` = ? AND `state` IN ('HELD', 'APPLIED')", giftId), "one live redemption")
            assertEquals(1L, db.long("SELECT `usedCount` FROM `pano_market_gift` WHERE `id` = ?", giftId), "usedCount 1")

            settle()
            round
        }
    }

    @Test
    fun `R-21 credit grant replay`() {
        E2eRace.rounds("R-21") { _ ->
            val buyer = buyer()
            val key = idempotencyKey()
            val clients = (0 until 10).map { adminClone("r21-$it") }

            val round = E2eRace.round(
                10,
                setup = { i -> i.also { clients[it].warm() } },
                action = { i -> grant(buyer.userId, 25, key, clients[i]) }
            )
            val answers = round.values()

            assertEquals(List(10) { 200 }, answers.map { it.status }, "every replay of the grant answers 200: ${answers.map { it.status to it.error }}")
            assertEquals(1L, db.count("market_credit_tx", "`userId` = ? AND `type` = 'GRANT' AND `idempotencyKey` = ?", buyer.userId, "panel:$key"), "one ledger tx")
            assertEquals(2500L, creditBalance(buyer.userId), "the balance rose by 25.00 once")
            assertEquals(1, answers.map { it.obj().getDouble("balance") }.toSet().size, "every answer reports the same balance")

            settle()
            round
        }
    }

    @Test
    fun `R-22 revoke against a credits-only purchase`() {
        val outcomes = java.util.concurrent.ConcurrentHashMap<String, Int>()

        E2eRace.rounds("R-22") { round ->
            // a product without actions: the VIP's credit action would grant 2.50 credits at an unpredictable moment (the delivery job's tick) and move the balances
            val product = catalog.fresh("LAST", "price" to "80.00", "creditPrice" to "80.00", "stock" to "")
            val owners = (0 until BATCH).map { buyer() }
            val revokers = owners.indices.map { adminClone("r22-revoke-$it") }

            owners.forEach { grant(it.userId, 100).ok() }

            // actor 2k revokes 100.00 of buyer k, [sweep] ms after the gate; actor 2k + 1 is the buyer paying 80.00 of it with credits
            val raced = E2eRace.round(
                owners.size * 2,
                setup = { i -> i.also { if (it % 2 == 0) revokers[it / 2].warm() else owners[it / 2].client.warm() } },
                action = { i ->
                    if (i % 2 == 0) {
                        Thread.sleep(sweep(i / 2, round))
                        revokers[i / 2].post("/api/panel/market/credits/accounts/${owners[i / 2].userId}/revoke", JsonObject().put("amount", 100).put("note", "e2e race revoke"), keyed())
                    } else checkout(owners[i / 2].client, cart(line(product.id)).put("payWithCredits", true), method = "credits")
                }
            )
            val answers = raced.values()

            for ((k, owner) in owners.withIndex()) {
                val revoke = answers[2 * k]
                val purchase = answers[2 * k + 1]
                val shortfall = revoke.obj().getDouble("shortfall")
                val balance = creditBalance(owner.userId)

                assertEquals(200, revoke.status, "the revoke of ${owner.username} is answered 200: ${revoke.error}")
                assertTrue(balance >= 0, "the balance of ${owner.username} is never negative: $balance")

                if (purchase.status == 200) {
                    outcomes.merge("purchase first", 1, Int::plus)

                    assertEquals(80.0, shortfall, 0.0001, "the purchase came first: the revoke found 20.00 and reports the shortfall of 80.00")
                    assertEquals(0L, balance, "and left nothing")
                    assertEquals(1L, db.count("market_order", "`userId` = ?", owner.userId), "one order")
                } else {
                    outcomes.merge("revoke first", 1, Int::plus)

                    assertEquals(400, purchase.status)
                    assertEquals("INSUFFICIENT_CREDITS", purchase.error, "the revoke came first: ${purchase.text}")
                    assertEquals(0.0, shortfall, 0.0001, "the revoke took everything, no shortfall")
                    assertEquals(0L, balance)
                    assertEquals(0L, db.count("market_order", "`userId` = ?", owner.userId), "the refused purchase created no order")
                }
            }

            assertEquals(0L, holdBalance(), "nothing stays on hold")

            settle()
            raced
        }

        println("e2e race R-22 outcomes over ${5 * BATCH} pairs: $outcomes")
    }

    @Test
    fun `R-23 stock adjust against checkout`() {
        E2eRace.rounds("R-23") { _ ->
            val product = catalog.fresh("LAST", "stock" to "3")
            val adjuster = adminClone("r23-adjust")

            // buyers of their own: an unpaid order holds one of the three open-order slots of its buyer (L4), and the winners of this race stay unpaid
            val shoppers = (0 until 5).map { buyer() }

            val round = E2eRace.round(
                6,
                setup = { i -> i.also { if (it == 5) adjuster.warm() else shoppers[it].client.warm() } },
                action = { i ->
                    if (i == 5) adjuster.post("/api/panel/market/products/${product.id}/stock", JsonObject().put("mode", "ADJUST").put("value", -1))
                    else checkout(shoppers[i].client, cart(line(product.id)))
                }
            )
            val answers = round.values()
            val adjust = answers[5]
            val checkouts = answers.take(5)
            val won = checkouts.count { it.status == 200 }
            val adjusted = if (adjust.status == 200) 1 else 0

            if (adjusted == 0) assertEquals(400, adjust.status, "an adjustment that would go below zero is refused: ${adjust.text}")

            assertTrue(checkouts.filter { it.status != 200 }.all { it.status == 409 && it.error == "OUT_OF_STOCK" }, "the others are refused with OUT_OF_STOCK: ${checkouts.map { it.status to it.error }}")

            val stock = productStock(product.id)!!

            assertTrue(stock >= 0, "the stock is never negative: $stock")
            assertEquals(3L - adjusted - won, stock, "stock = 3 - adjustment - successful checkouts")
            assertEquals(won.toLong(), reserved(product.id), "one reserved unit per successful checkout")

            // the winners cancel: every unit is back, nothing stays booked
            for ((i, answer) in checkouts.withIndex()) {
                if (answer.status == 200) shoppers[i].client.post("/api/market/orders/${answer.obj().getJsonObject("order").getString("publicId")}/cancel", JsonObject()).ok()
            }

            assertEquals(3L - adjusted, productStock(product.id), "after the cancellations the stock is 3 - adjustment")
            assertEquals(0L, reserved(product.id), "and nothing is booked")

            settle()
            round
        }
    }

    @Test
    fun `R-24 cart line merge`() {
        E2eRace.rounds("R-24") { _ ->
            val product = catalog.fresh("VIP")
            val owner = buyer()
            val clients = (0 until 10).map { owner.another(baseUrl, "${owner.username}#$it") }

            val round = E2eRace.round(
                10,
                setup = { i -> i.also { clients[it].warm() } },
                action = { i -> clients[i].post("/api/market/me/cart/items", JsonObject().put("productId", product.id).put("quantity", 1)) }
            )

            assertEquals(List(10) { 200 }, round.values().map { it.status }, "every add is answered 200: ${round.values().map { it.status to it.error }}")

            val cartId = db.long("SELECT `id` FROM `pano_market_cart` WHERE `userId` = ?", owner.userId)

            assertNotNull(cartId, "one cart")
            assertEquals(1L, db.count("market_cart", "`userId` = ?", owner.userId))
            assertEquals(1L, db.count("market_cart_item", "`cartId` = ?", cartId), "one row for the one line")
            assertEquals(10L, db.long("SELECT `quantity` FROM `pano_market_cart_item` WHERE `cartId` = ?", cartId), "its quantity is 10")

            val view = owner.client.get("/api/market/me/cart").ok().obj().getJsonObject("cart").getJsonArray("items")

            assertEquals(1, view.size())
            assertEquals(10, view.getJsonObject(0).getInteger("quantity"))

            owner.client.delete("/api/market/me/cart").ok()
            settle()
            round
        }
    }

    @Test
    fun `R-25 renewal event twice`() {
        E2eRace.rounds("R-25") { _ ->
            val buyer = buyer()
            val publicId = publicIdOf(checkout(buyer.client, cart(line(catalog.id("SUB"))), method = "fake-eur").ok())
            val reference = referenceOf(publicId)
            val payment = gateway.payments.getValue(reference)
            val gatewaySubscription = "gwsub_" + uniqueCode(12).lowercase()
            val start = System.currentTimeMillis()
            val end = start + PERIOD_MS

            // the first payment starts a gateway-managed subscription (the success carries the gateway's subscription and period)
            gateway.setStatus(reference, "paid")
            assertEquals(
                200,
                post(
                    visitor("r25-first"),
                    signed(
                        "payment.succeeded",
                        JsonObject().put("reference", reference).put("amount", payment.amount.toPlainString()).put("currency", payment.currency)
                            .put("paymentId", "gwpay_" + uniqueCode(12).lowercase())
                            .put("subscription", JsonObject().put("id", gatewaySubscription).put("periodStart", start).put("periodEnd", end)),
                    ),
                    provider = "fake-eur"
                ).status
            )
            awaitOrder(publicId, "COMPLETED")

            val subscription = db.sql("SELECT `id`, `status`, `mode`, `cycleCount` FROM `pano_market_subscription` WHERE `userId` = ?", buyer.userId).single()
            val subscriptionId = subscription.getLong("id")

            assertEquals("ACTIVE", subscription.getString("status"))
            assertEquals("GATEWAY", subscription.getString("mode"), "the gateway charges it")
            assertEquals(1, subscription.getInteger("cycleCount"))

            // the gateway renews the same period: one event delivered twice (one key) and once more under another event key and charge id
            fun renewed(eventId: String, charge: String) = signed(
                "subscription.renewed",
                JsonObject().put("subscriptionId", gatewaySubscription).put("amount", payment.amount.toPlainString()).put("currency", payment.currency)
                    .put("periodStart", end).put("periodEnd", end + PERIOD_MS).put("paymentId", charge),
                eventId
            )

            val first = renewed(gateway.nextEventId(), "gwpay_" + uniqueCode(12).lowercase())
            val other = renewed(gateway.nextEventId(), "gwpay_" + uniqueCode(12).lowercase())
            val hooks = listOf(first, first, other)
            val clients = hooks.indices.map { E2eClient(baseUrl, "r25-$it") }
            val round = E2eRace.round(3, setup = { i -> i.also { clients[i].warm() } }, action = { i -> post(clients[i], hooks[i], provider = "fake-eur") })

            assertEquals(List(3) { 200 }, round.values().map { it.status }, "every delivery is answered 200")
            Await.until(30_000, 250, "the renewal is paid") { db.count("market_subscription_renewal", "`subscriptionId` = ? AND `status` = 'PAID'", subscriptionId) == 1L }

            assertEquals(1L, db.count("market_subscription_renewal", "`subscriptionId` = ?", subscriptionId), "one renewal row for the period (uq_sub_period)")
            assertEquals(1L, db.count("market_order", "`source` = 'RENEWAL' AND `subscriptionId` = ?", subscriptionId), "one renewal order")
            assertEquals(2, db.long("SELECT `cycleCount` FROM `pano_market_subscription` WHERE `id` = ?", subscriptionId)?.toInt(), "the subscription moved one cycle")

            settle()
            round
        }
    }

    @Test
    fun `R-26 creator payout double submit`() {
        // An order of a store in test mode never earns a creator commission (21 section 7.1), and the fake gateway is only usable in test mode, so the earnings
        // come from orders paid entirely with credits while the store is switched to live for the length of this scenario (withSettings puts it back). No
        // gateway is involved in live mode: the fake provider is ineligible there.
        session.withSettings(JsonObject().put("creatorEarningHoldDays", 0).put("testMode", false)) {
            E2eRace.rounds("R-26") { _ ->
                val product = catalog.fresh("VIP")
                val code = "RP" + uniqueCode(10)
                val codeId = admin.post(
                    "/api/panel/market/creator-codes",
                    JsonObject().put("creator", "creator-$code").put("code", code).put("discount", 5).put("unit", "PERCENT").put("commissionPercent", 10)
                ).ok().obj().getLong("id")

                for (k in 0 until 2) {
                    val creditBuyer = buyers[k]

                    grant(creditBuyer.userId, 100).ok()

                    val answer = checkout(creditBuyer.client, cart(line(product.id)).put("payWithCredits", true).put("creatorCode", code), method = "credits").ok()

                    awaitOrder(publicIdOf(answer), "COMPLETED")
                }

                assertEquals(2L, db.count("market_creator_earning", "`creatorCodeId` = ?", codeId), "one earning per paid order")

                val available = availableOf(codeId)

                assertTrue(available > 0.0, "the earnings are available at once (0 hold days): $available")

                val payers = (0 until 2).map { adminClone("r26-$it") }
                val round = E2eRace.round(
                    2,
                    setup = { i -> i.also { payers[it].warm() } },
                    action = { i ->
                        payers[i].post(
                            "/api/panel/market/creator-codes/$codeId/payouts",
                            JsonObject().put("amount", available).put("method", "MANUAL").put("note", "e2e race payout"), keyed()
                        )
                    }
                )
                val answers = round.values()

                assertEquals(listOf(200, 400), answers.map { it.status }.sorted(), "one payout is made, the other is refused: ${answers.map { it.status to it.error }}")
                assertEquals("INVALID_PAYOUT_AMOUNT", answers.single { it.status == 400 }.error)
                assertNotNull(answers.single { it.status == 200 }.obj().getLong("id"), "the winner answers {id}")

                val payouts = db.sql("SELECT `amount`, `state` FROM `pano_market_creator_payout` WHERE `creatorCodeId` = ?", codeId)
                val earned = db.long("SELECT COALESCE(SUM(`amount` - `reversedAmount`), 0) FROM `pano_market_creator_earning` WHERE `creatorCodeId` = ?", codeId)!!
                val paidOut = db.long("SELECT `paidOut` FROM `pano_market_creator_code` WHERE `id` = ?", codeId)!!

                assertEquals(1, payouts.size, "one payout row")
                assertEquals("PAID", payouts[0].getString("state"))
                assertEquals(Math.round(available * 100), payouts[0].getLong("amount"), "for the whole available amount")
                assertEquals(paidOut, payouts[0].getLong("amount"), "paidOut is that payout")
                assertTrue(paidOut <= earned, "paidOut $paidOut never exceeds the earnings $earned")
                assertEquals(0.0, availableOf(codeId), 0.0001, "nothing is left to pay out")

                settle()
                round
            }
        }
    }

    @Test
    fun `R-27 dispute and refund together`() {
        // every end of every order is counted over all rounds: the scenario proves nothing unless both ends happened (refund first, dispute first)
        val ends = java.util.concurrent.ConcurrentHashMap<String, Int>()

        E2eRace.rounds("R-27") { round ->
            // buyers of their own: a chargeback blocks its buyer (autoBlockOnChargeback), so a buyer is never reused
            val placed = (0 until BATCH).map { place(catalog.fresh("VIP"), buyer()) }

            for (p in placed) {
                payViaFake(p.publicId)
                awaitOrder(p.publicId, "COMPLETED")
            }

            // the goods are delivered first (the delivery job runs on the 5 s tick): a chargeback then has something to take back
            for (p in placed) {
                Await.until(60_000, 250, "the deliveries of ${p.publicId} are confirmed") {
                    deliveryRows(p.orderId) == 2L && db.count("market_delivery", "`orderId` = ? AND `status` = 'CONFIRMED'", p.orderId) == 2L
                }
            }

            val disputes = placed.map { signed("dispute.updated", JsonObject().put("reference", it.reference).put("state", "OPENED").put("disputeId", "dp_" + uniqueCode(12).lowercase()).put("reason", "fraudulent")) }
            val webhookClients = placed.map { E2eClient(baseUrl, "r27w-${it.publicId.takeLast(4)}") }
            val refunders = placed.map { adminClone("r27r-${it.publicId.takeLast(4)}") }

            // actor 2k opens the dispute of order k, actor 2k + 1 refunds it in full from the panel. Both directions are swept: on an even order the refund is the
            // late actor ([sweep] ms after the gate: the dispute takes the order lock first, or catches the refund between its two transactions), on an odd one the
            // dispute is ([disputeLag]: 0.3 to 3.5 s, the span a refund needs under the load of a batch for its first transaction, the gateway call and its second
            // transaction), so a round holds dispute-first, refund-in-flight and refund-first orders
            val raced = E2eRace.round(
                placed.size * 2,
                setup = { i -> i.also { if (it % 2 == 0) webhookClients[it / 2].warm() else refunders[it / 2].warm() } },
                action = { i ->
                    val k = i / 2

                    if (i % 2 == 0) {
                        if (k % 2 == 1) Thread.sleep(disputeLag(k, round))

                        post(webhookClients[k], disputes[k])
                    } else {
                        if (k % 2 == 0) Thread.sleep(sweep(k, round))

                        refunders[k].post("/api/panel/market/orders/${placed[k].orderId}/refunds", JsonObject(), keyed())
                    }
                }
            )
            val answers = raced.values()

            for ((k, p) in placed.withIndex()) {
                val dispute = answers[2 * k]
                val refund = answers[2 * k + 1]

                assertEquals(200, dispute.status, "the dispute of ${p.publicId} is answered 200")
                Await.until(30_000, 250, "order ${p.publicId} is charged back") { orderStatus(p.publicId) == "CHARGEBACK" }

                val row = orderRow(p.publicId)
                val before = row.getString("statusBeforeDispute")

                ends.merge("refund ${refund.status}, before $before", 1, Int::plus)

                // when the refund was applied relative to the chargeback: its REFUND_SUCCEEDED row comes after the CHARGEBACK transition when the gateway call was in flight
                val chargedAt = db.long("SELECT MIN(`id`) FROM `pano_market_order_event` WHERE `orderId` = ? AND `type` = 'STATUS_CHANGED' AND `toStatus` = 'CHARGEBACK'", p.orderId)!!
                val refundedAt = db.long("SELECT MIN(`id`) FROM `pano_market_order_event` WHERE `orderId` = ? AND `type` = 'REFUND_SUCCEEDED'", p.orderId)
                val inFlight = refundedAt != null && refundedAt > chargedAt

                assertTrue(before in setOf("COMPLETED", "REFUNDED", "PARTIALLY_REFUNDED"), "${p.publicId}: statusBeforeDispute is $before")

                if (refund.status != 200) {
                    // the dispute came first: the refund meets a charged-back order and is refused for that reason (21 section 3.1), nothing of it was written
                    assertEquals(400, refund.status, "${p.publicId}: a refund that lost is refused, not failed: ${refund.text}")
                    assertEquals("INVALID_ORDER_TRANSITION", refund.error, "${p.publicId}: refused because the order is charged back: ${refund.text}")
                    assertEquals("COMPLETED", before, "${p.publicId}: a refused refund means the dispute came first")
                    assertEquals(0L, row.getLong("refundedTotal"), "${p.publicId}: nothing was refunded")
                    assertEquals(0L, db.count("market_refund", "`orderId` = ? AND `status` = 'SUCCEEDED'", p.orderId), "${p.publicId}: a refused refund left no SUCCEEDED row")
                } else {
                    // the refund went through, before the dispute or while its call was in flight: either way the order was refunded in full, so that is the status a won
                    // dispute gives it back (never COMPLETED with the whole price refunded)
                    assertEquals("REFUNDED", before, "${p.publicId}: a refund that was answered 200 is what the order returns to after the dispute: ${refund.text}")
                    ends.merge(if (inFlight) "  of these: refund settled on the charged-back order" else "  of these: refund settled first", 1, Int::plus)
                    assertEquals(
                        if (inFlight) 1L else 0L, db.count("market_order_event", "`orderId` = ? AND `message` = 'REFUND_DURING_DISPUTE'", p.orderId),
                        "${p.publicId}: a refund that settled on the charged-back order says so on the timeline, one that settled first does not"
                    )
                    assertEquals(row.getLong("totalPrice"), row.getLong("refundedTotal"), "${p.publicId}: the whole price is refunded")
                    assertEquals(1L, db.count("market_refund", "`orderId` = ? AND `status` = 'SUCCEEDED'", p.orderId), "${p.publicId}: one SUCCEEDED refund row")
                    assertEquals(1L, db.count("market_refund", "`orderId` = ?", p.orderId), "${p.publicId}: and no other refund row")
                    assertEquals(
                        db.long("SELECT COALESCE(SUM(`quantity`), 0) FROM `pano_market_order_item` WHERE `orderId` = ?", p.orderId),
                        db.long("SELECT COALESCE(SUM(`refundedQuantity`), 0) FROM `pano_market_order_item` WHERE `orderId` = ?", p.orderId),
                        "${p.publicId}: every unit counts as refunded"
                    )
                }

                assertEquals(1L, db.count("market_dispute", "`orderId` = ?", p.orderId), "${p.publicId}: one dispute row")

                // the two planners of REVOKE rows (O10 step 5 of the refund, O11 step 1 of the chargeback) meet on this order: each unit is taken back once, by whoever came first
                val revokes = db.sql("SELECT `orderItemId`, `actionId`, `unitIndex`, `attemptGroup` FROM `pano_market_delivery` WHERE `orderId` = ? AND `phase` = 'REVOKE'", p.orderId)

                assertEquals(
                    revokes.size, revokes.map { listOf(it.getLong("orderItemId"), it.getString("actionId"), it.getInteger("unitIndex")) }.toSet().size,
                    "${p.publicId}: no REVOKE row twice for an item and action (whatever the attempt group)"
                )
                assertEquals(REVOKES_OF_VIP, revokes.size.toLong(), "${p.publicId}: the REVOKE rows of the delivered VIP actions exist once")
                assertEquals(1, revokes.map { it.getInteger("attemptGroup") }.toSet().size, "${p.publicId}: and all of them belong to one attempt group")
            }

            settle()
            raced
        }

        println("e2e race R-27 outcomes over ${5 * BATCH} orders: $ends")

        // the race is only proven when both directions happened: a refund that finished first (the order returns to REFUNDED) and a dispute that finished first (the refund is refused)
        if (ends.none { (end, _) -> end == "refund 200, before REFUNDED" } || ends.none { (end, _) -> end.startsWith("refund 400, ") }) throw HarnessNoConcurrency("R-27")
    }

    @Test
    fun `R-28 code uniqueness across tables`() {
        E2eRace.rounds("R-28") { _ ->
            val product = catalog.fresh("VIP")
            val code = "RC" + uniqueCode(10)
            val creators = adminClone("r28-creator")
            val coupons = adminClone("r28-coupon")
            val gifts = adminClone("r28-gift")
            val clients = listOf(coupons, creators, gifts)

            val round = E2eRace.round(
                3,
                setup = { i -> i.also { clients[it].warm() } },
                action = { i ->
                    when (i) {
                        0 -> coupons.post("/api/panel/market/coupons", JsonObject().put("name", "Race $code").put("code", code).put("discount", 10).put("unit", "PERCENT"))
                        1 -> creators.post(
                            "/api/panel/market/creator-codes",
                            JsonObject().put("creator", "creator-$code").put("code", code).put("discount", 5).put("unit", "PERCENT").put("commissionPercent", 10)
                        )
                        else -> gifts.post("/api/panel/market/gifts", JsonObject().put("code", code).put("type", "PRODUCT").put("productId", product.id).put("redeemLimit", 1))
                    }
                }
            )
            val answers = round.values()

            assertEquals(listOf(200, 409, 409), answers.map { it.status }.sorted(), "exactly one create wins: ${answers.map { it.status to it.error }}")
            assertEquals(listOf("CODE_ALREADY_EXISTS", "CODE_ALREADY_EXISTS"), answers.filter { it.status == 409 }.map { it.error })

            val rows = listOf("market_coupon", "market_creator_code", "market_gift").sumOf { table -> db.count(table, "`code` = ?", code) }

            assertEquals(1L, rows, "the code exists once over the three tables")

            settle()
            round
        }
    }

    // ---- helpers of R-15 to R-28 -----------------------------------------------------------------------------------------------

    private class Placed(val buyer: E2eBuyer, val product: E2eCatalog.FreshProduct, val publicId: String, val reference: String, val orderId: Long)

    /** A checked-out, unpaid order of [buyer] for one unit of [product] ([customise] may add keys to the body). */
    private fun place(product: E2eCatalog.FreshProduct, buyer: E2eBuyer, method: String = "fake", customise: (JsonObject) -> Unit = {}): Placed {
        val body = cart(line(product.id)).also(customise)
        val publicId = publicIdOf(checkout(buyer.client, body, method = method).ok())

        return Placed(buyer, product, publicId, referenceOf(publicId), orderRow(publicId).getLong("id"))
    }

    /** The delay of the second actor of order [k] in round [round]: 0 to 78 ms, so that a round sweeps the window between the two requests. */
    private fun sweep(k: Int, round: Int): Long = k * 10L + round * 2L

    /** The delay of the dispute of odd order [k] in round [round] of R-27: 0.3 to 3.5 s, the span of a refund's own run time under the load of a batch. */
    private fun disputeLag(k: Int, round: Int): Long = 300L + k * 450L + round * 20L

    private fun keyed(key: String = idempotencyKey()): Map<String, String> = mapOf("Idempotency-Key" to key)

    /** A client of the admin session of its own connection (the platform keeps few sessions per user, so a race actor adopts the login of [admin]). */
    private fun adminClone(label: String): E2eClient = E2eClient(baseUrl, label).also { it.adoptSession(admin) }

    private fun uniqueCode(length: Int): String = UUID.randomUUID().toString().replace("-", "").take(length).uppercase()

    private fun grant(userId: Long, amount: Number, key: String = idempotencyKey(), client: E2eClient = admin): E2eResponse =
        client.post("/api/panel/market/credits/accounts/$userId/grant", JsonObject().put("amount", amount).put("note", "e2e race grant"), keyed(key))

    private fun creditBalance(userId: Long): Long = db.long("SELECT `balance` FROM `pano_market_credit_account` WHERE `userId` = ? AND `type` = 'USER'", userId) ?: 0L

    private fun holdBalance(): Long = db.long("SELECT `balance` FROM `pano_market_credit_account` WHERE `systemKey` = 'HOLD'") ?: 0L

    private fun spentBalance(): Long = db.long("SELECT `balance` FROM `pano_market_credit_account` WHERE `systemKey` = 'SPENT'") ?: 0L

    /** The signed webhook of the fake gateway: the exact bytes and the `X-Fake-Signature` value (the actor that posts it uses its own warmed client). */
    private fun signed(type: String, data: JsonObject, id: String = gateway.nextEventId()): Pair<ByteArray, String> {
        val body = gateway.eventBody(type, data, id)

        return body to checkNotNull(gateway.signatureHeader(body, FakePayGateway.Signature.VALID))
    }

    private fun post(client: E2eClient, hook: Pair<ByteArray, String>, provider: String = "fake"): E2eResponse =
        client.request("POST", "/api/market/payments/$provider/webhook", hook.first, mapOf("X-Fake-Signature" to hook.second), csrf = false, cookiesOn = false)

    /** Marks the payment paid at the gateway and signs its `payment.succeeded`. */
    private fun paidHook(reference: String): Pair<ByteArray, String> {
        val payment = gateway.payments.getValue(reference)

        gateway.setStatus(reference, "paid")

        return signed("payment.succeeded", JsonObject().put("reference", reference).put("amount", payment.amount.toPlainString()).put("currency", payment.currency))
    }

    /** Blocks until the expiry job is at work: one of the orders turned `EXPIRED`. */
    private fun awaitFirstExpiry(orderIds: List<Long>, timeoutMs: Long = 90_000) {
        val marks = orderIds.joinToString(",") { "?" }
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L

        while (db.count("market_order", "`id` IN ($marks) AND `status` = 'EXPIRED'", *orderIds.toTypedArray()) == 0L) {
            check(System.nanoTime() < deadline) { "the expiry job expired none of the orders within ${timeoutMs / 1000} s" }
            Thread.sleep(3)
        }
    }

    /** Waits until the order left `PENDING` / `EXPIRED` for good: it is `COMPLETED` or in `REVIEW`; returns that status. */
    private fun awaitSettled(publicId: String): String {
        Await.until(45_000, 250, "order $publicId is COMPLETED or in REVIEW") { orderStatus(publicId) in setOf("COMPLETED", "REVIEW") }

        return orderStatus(publicId)
    }

    private fun variantStock(variantId: Long): Long? = db.long("SELECT `stock` FROM `pano_market_product_variant` WHERE `id` = ?", variantId)

    private fun reservedOfVariant(variantId: Long): Long = db.long("SELECT COALESCE(SUM(`stockReserved`), 0) FROM `pano_market_order_item` WHERE `variantId` = ?", variantId) ?: 0L

    /** How many refunds the fake gateway took for the payment of [p] (`POST /v1/refunds` that created a refund). */
    private fun gatewayRefunds(p: Placed): Int {
        val id = gateway.payments.getValue(p.reference).id

        return gateway.refunds.values.count { it.paymentId == id }
    }

    /** The path (and query) of the return URL the gateway was given for [reference], with the outcome segment [outcome]. */
    private fun returnPath(reference: String, outcome: String): String {
        val url = gateway.payments[reference]!!.returnSuccess ?: error("the gateway got no return URL")
        val uri = java.net.URI.create(url)

        assertTrue(uri.path.endsWith("/success"), "the success return URL ends in /success: ${uri.path}")

        return (uri.rawPath.removeSuffix("/success") + "/" + outcome) + (uri.rawQuery?.let { "?$it" } ?: "")
    }

    private fun deliveryRows(orderId: Long): Long = db.count("market_delivery", "`orderId` = ?", orderId)

    /**
     * The two consistent ends of a payment that raced the end of its order (17 section 9.4 R-16 / R-17), judged on a VIP of stock 1: paid in time =
     * `COMPLETED` with the reservation committed, the unit sold and the two GRANT actions planned; too late = `REVIEW (LATE)` with the reservation
     * released, the unit back on the shelf and nothing delivered. Never `COMPLETED` with released stock, never delivered while in review.
     */
    private fun assertPaidOrLate(p: Placed, status: String, context: String) {
        val row = orderRow(p.publicId)

        when (status) {
            "COMPLETED" -> {
                assertEquals("COMMITTED", row.getString("reservationState"), "$context: a paid order has committed its reservation")
                assertNotNull(row.getValue("paidAt"), "$context: paidAt is set")
                assertEquals(0L, productStock(p.product.id), "$context: the unit is sold")
                assertEquals(1L, reserved(p.product.id), "$context: and still booked on the order")
                assertEquals(2L, deliveryRows(p.orderId), "$context: the two GRANT actions of the VIP are planned once")
            }
            "REVIEW" -> {
                assertEquals("LATE", row.getString("reviewReason"), "$context: the reason of the review")
                assertEquals("RELEASED", row.getString("reservationState"), "$context: the reservation stays released")
                assertEquals(1L, productStock(p.product.id), "$context: the unit is back")
                assertEquals(0L, reserved(p.product.id), "$context: and not booked")
                assertEquals(0L, deliveryRows(p.orderId), "$context: nothing is delivered while the order is in review")
            }
            else -> throw AssertionError("$context: unexpected end state $status")
        }
    }

    /** `available` of one creator code in the report, in the store currency. */
    private fun availableOf(codeId: Long): Double =
        admin.get("/api/panel/market/creator-codes/report").ok().obj().getJsonArray("creators").map { it as JsonObject }.single { it.getLong("id") == codeId }.getDouble("available")

    /**
     * The end of one round: the queues that act on money, stock and entitlements (deliveries, webhooks, deferred inbound events) are drained and every global
     * invariant I1 to I22 is checked, none skipped. The mail outbox is deliberately not awaited here (no invariant reads it): the mail job works 20 rows per
     * 15 s tick and every send against the instance's dummy SMTP host fails after a DNS lookup, so the backlog of an earlier scenario (R-13 queues about 80 mails)
     * can keep a new row unclaimed for longer than a round may wait. [mailBacklogClaimed] waits for it once per scenario instead, before the base class drain.
     */
    private fun settle() {
        Await.until(30_000, 250, "the queues of the round are drained") {
            val queues = admin.get("/api/panel/market/health", log = false).obj().getJsonObject("queues")

            listOf("deliveriesPending", "webhooksPending", "deferredEvents").all { (queues?.getInteger(it) ?: 0) == 0 }
        }
        runBlocking { InvariantChecker.assertAll(db.pool) }

        val run = InvariantChecker.lastRun

        check(run.ran.isNotEmpty() && run.skipped.isEmpty()) { "invariants ran=${run.ran.size} skipped=${run.skipped}" }
    }

    /** Mails the mail job has not yet claimed once (`PENDING` with no attempt, or `SENDING`): the definition of "drained" of `E2eSession.drainAndCheck`. */
    private fun untriedMails(): Long = db.count("market_mail_outbox", "(`status` = 'PENDING' AND `attempts` = 0) OR `status` = 'SENDING'")

    /** Runs before the base class drain (a subclass `@AfterEach` goes first): gives the mail job the time its own pace needs for the backlog this scenario left. */
    @AfterEach
    fun mailBacklogClaimed() {
        Await.until(240_000, 500, "the mail job claimed every queued mail once (${untriedMails()} unclaimed when the wait began)") { untriedMails() == 0L }
    }

    private companion object {
        /** Orders per round of the scenarios that race a batch (R-16, R-17, R-27): many interleavings per round. */
        const val BATCH = 8

        const val PERIOD_MS = 30L * 86_400_000L

        /** The VIP of the catalogue has two GRANT actions (a permission and a credit action); the inverse the planner derives for each is one REVOKE row. */
        const val REVOKES_OF_VIP = 2L
    }

    private fun reserved(productId: Long): Long = db.long("SELECT COALESCE(SUM(`stockReserved`), 0) FROM `pano_market_order_item` WHERE `productId` = ?", productId) ?: 0L

    /**
     * 17 section 9.4 R-01 "one set of deliveries / mail / webhook": per order no business key appears twice. The expected row count is exact: a
     * table that starts filling must fail here so the slice that fills it states its expected set (and the cardinality check below then bites on
     * it). Delivery (MK-102): the standard VIP product has four GRANT-phase actions (`a1`, `a2`, `r1`, `r2` of `E2eCatalog.grantAndRevoke`: two
     * permission and two credit actions), so the O2 transaction plans exactly four rows, however many copies of the webhook race for it. Mail (MK-142):
     * O2 queues exactly one `ORDER_CONFIRMATION` to the buyer, however many copies of the webhook race for it (E2E-06 states the set; the mail outbox was
     * empty here before MK-142 landed). The webhook subsystem still writes nothing for a VIP purchase: the catalogue seeds no store webhook endpoint.
     */
    private fun assertSingleSetOfSideEffects(orderId: Long) {
        // the confirmation mail is queued by the transition that completed the order, a moment after the status is visible (MAIL_QUEUED follows STATUS_CHANGED)
        Await.until(15_000, 100, "the order confirmation is queued") { db.count("market_mail_outbox", "`orderId` = ?", orderId) >= 1L }

        val sideEffects = mapOf(
            "market_delivery" to Triple("orderId", "`orderItemId`, `actionId`, `unitIndex`, `phase`, `attemptGroup`", 2L),
            "market_mail_outbox" to Triple("orderId", "`kind`, `recipient`", 1L),
            "market_webhook_delivery" to Triple("orderId", "`endpointId`, `event`", 0L)
        )

        for ((table, spec) in sideEffects) {
            val (column, key, expected) = spec
            val row = db.sql("SELECT COUNT(*) AS n, COUNT(DISTINCT $key) AS d FROM `pano_$table` WHERE `$column` = ?", orderId).first()

            assertEquals(expected, row.getLong("n"), "rows of $table for the order (MK-102: VIP has two GRANT actions, one row each; MK-142: one ORDER_CONFIRMATION mail; no webhook endpoint exists)")
            assertEquals(row.getLong("n"), row.getLong("d"), "no business key of $table exists twice for the order")
        }

        assertEquals(listOf("ORDER_CONFIRMATION"), db.sql("SELECT `kind` FROM `pano_market_mail_outbox` WHERE `orderId` = ?", orderId).map { it.getString("kind") }, "the one mail is the order confirmation")
    }
}
