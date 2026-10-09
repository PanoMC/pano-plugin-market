package com.panomc.plugins.market.e2e

import com.panomc.plugins.market.e2e.support.E2eBuyer
import com.panomc.plugins.market.e2e.support.E2eClient
import com.panomc.plugins.market.e2e.support.E2eResponse
import com.panomc.plugins.market.e2e.support.E2eTestBase
import com.panomc.plugins.market.support.Await
import com.panomc.plugins.market.support.FakePayGateway
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import com.panomc.plugins.market.util.MarketPaths

/**
 * Payment failure, retry, review and the trust rules of the inbound routes (17 section 9.3): F-01 to F-18. Time travel is by row rewind only
 * (`market_payment.nextQueryAt`, `market_order.expiresAt`, `market_block.expiresAt`); settings and providers a scenario changes are global state of the one
 * instance and are always put back in a `finally`.
 */
class PaymentFlowE2E : E2eTestBase() {
    override val tag = "pay"

    /** Checks out one fresh VIP as a new buyer and returns (client, publicId, reference). */
    private fun pending(): Triple<E2eBuyer, String, String> = pendingOf(catalog.fresh("VIP").id)

    private fun pendingOf(productId: Long): Triple<E2eBuyer, String, String> {
        val buyer = buyer()
        val publicId = publicIdOf(checkout(buyer.client, cart(line(productId))).ok())

        return Triple(buyer, publicId, referenceOf(publicId))
    }

    /** The path (and query) of the return URL the gateway was given for [reference]. */
    private fun returnPath(reference: String, outcome: String): String {
        val url = gateway.payments[reference]!!.returnSuccess ?: error("the gateway got no return URL")
        val uri = URI.create(url)

        assertTrue(uri.path.endsWith("/success"), "the success return URL ends in /success: ${uri.path}")

        return (uri.rawPath.removeSuffix("/success") + "/" + outcome) + (uri.rawQuery?.let { "?$it" } ?: "")
    }

    @Test
    fun `F-01 browser return is not proof`() {
        val (buyer, publicId, reference) = pending()

        assertEquals("pending", gateway.payments[reference]!!.status)

        val back = visitor("browser").get(returnPath(reference, "success"))

        assertEquals(303, back.status, "the return only ever redirects")
        assertEquals("/store/order/$publicId", URI.create(back.header("Location")!!).path, "Location was ${back.header("Location")}")
        assertEquals("PENDING", orderStatus(publicId), "the order stays PENDING while the gateway says pending")
        assertEquals("PENDING", order(buyer.client, publicId).getString("status"))
    }

    @Test
    fun `F-02 return triggers a status query`() {
        val (_, publicId, reference) = pending()

        gateway.setStatus(reference, "paid") // paid at the gateway, no webhook is sent

        val back = visitor("browser").get(returnPath(reference, "success"))

        assertEquals(303, back.status)
        awaitOrder(publicId, "COMPLETED")
        assertEquals("SUCCEEDED", attemptStatus(reference))
        assertTrue(gateway.requests(FakePayGateway.Op.QUERY).any { it.path.endsWith("/$reference") }, "the provider asked the gateway")
    }

    @Test
    fun `F-04 invalid signature`() {
        val (_, publicId, reference) = pending()
        val data = JsonObject().put("reference", reference).put("amount", gateway.payments[reference]!!.amount.toPlainString()).put("currency", "EUR")

        for (signature in listOf(FakePayGateway.Signature.INVALID, FakePayGateway.Signature.MISSING, FakePayGateway.Signature.STALE)) {
            val rejected = rejectedEvents()
            val answers = gateway.sendWebhook("payment.succeeded", data, signature = signature)

            assertEquals(listOf(400), answers.map { it.statusCode() }, "$signature is refused with 400")
            assertEquals(rejected + 1, rejectedEvents(), "$signature leaves one REJECTED, unverified event row")
            assertEquals("PENDING", orderStatus(publicId), "$signature changes nothing")
        }

        assertTrue(attemptStatus(reference) != "SUCCEEDED", "the attempt did not succeed")
    }

    private fun rejectedEvents(): Long = db.count("market_payment_event", "`providerId` = 'fake' AND `status` = 'REJECTED' AND `verified` = 0")

    @Test
    fun `F-08 failed then retry with a new attempt`() {
        val (buyer, publicId, first) = pending()
        val failed = gateway.sendWebhook(
            "payment.failed", JsonObject().put("reference", first).put("code", "card_declined").put("message", "The card was declined").put("final", true)
        )

        assertEquals(listOf(200), failed.map { it.statusCode() })
        assertEquals("FAILED", attemptStatus(first))
        assertEquals("card_declined", db.string("SELECT `failureCode` FROM `pano_market_payment` WHERE `reference` = ?", first))
        assertEquals("PENDING", orderStatus(publicId))
        assertEquals(true, order(buyer.client, publicId).getBoolean("canRetryPayment"))

        val retry = buyer.client.post("${MarketPaths.SITE_ROOT}/orders/$publicId/pay", JsonObject().put("paymentMethodId", "fake")).ok()

        assertNotNull(retry.obj().getJsonObject("payment"), "the retry answers a PaymentStart")

        val second = referenceOf(publicId)
        assertNotEquals(first, second, "a new attempt has a new reference")
        assertNotEquals(
            db.string("SELECT `token` FROM `pano_market_payment` WHERE `reference` = ?", first),
            db.string("SELECT `token` FROM `pano_market_payment` WHERE `reference` = ?", second), "and a new token"
        )
        assertEquals("FAILED", attemptStatus(first), "the old attempt stays closed")
        assertEquals(2L, db.count("market_payment", "`orderId` = (SELECT `id` FROM `pano_market_order` WHERE `publicId` = ?)", publicId))

        gateway.pay(second)
        awaitOrder(publicId, "COMPLETED")
        assertEquals("SUCCEEDED", attemptStatus(second))
        assertEquals("FAILED", attemptStatus(first))
    }

    // ---- shared helpers of the scenarios below ------------------------------------------------------------------------

    private val day = 86_400_000L

    private fun orderId(publicId: String): Long = orderRow(publicId).getLong("id")

    private fun paymentRow(reference: String) = db.sql("SELECT * FROM `pano_market_payment` WHERE `reference` = ?", reference).single()

    private fun reviewOrder(publicId: String, decision: String, refund: Boolean? = null, force: Boolean? = null): E2eResponse {
        val body = JsonObject().put("decision", decision)

        refund?.let { body.put("refund", it) }
        force?.let { body.put("force", it) }

        return admin.post("${MarketPaths.PANEL_ROOT}/orders/${orderId(publicId)}/review", body)
    }

    /** The `STATUS_CHANGED` rows of the order that moved it to `COMPLETED` (O2 ran once when this is 1). */
    private fun orderCompletions(publicId: String): Long =
        db.count("market_order_event", "`type` = 'STATUS_CHANGED' AND `toStatus` = 'COMPLETED' AND `orderId` = ?", orderId(publicId))

    /** `PAYMENT_SUCCEEDED` timeline rows whose data says `duplicate: true` (a second attempt of an already paid order). */
    private fun duplicateSuccessRows(publicId: String): Long =
        db.sql("SELECT `data` FROM `pano_market_order_event` WHERE `type` = 'PAYMENT_SUCCEEDED' AND `orderId` = ?", orderId(publicId))
            .count { JsonObject(it.getString("data")).getBoolean("duplicate", false) }.toLong()

    private fun stockOf(productId: Long): Long? = productStock(productId)

    private fun deliveries(publicId: String) = db.sql("SELECT `status` FROM `pano_market_delivery` WHERE `orderId` = ?", orderId(publicId)).map { it.getString("status") }

    private fun entitlementStates(publicId: String): List<String> =
        db.sql("SELECT `status` FROM `pano_market_entitlement` WHERE `orderId` = ?", orderId(publicId)).map { it.getString("status") }

    /** Makes the reconcile / expiry jobs see [publicId] as past its time: the order and its open attempts are moved two hours into the past. */
    private fun expire(publicId: String, attempts: Boolean = true) {
        val id = orderId(publicId)

        db.rewind("market_order", id, "expiresAt", 2 * 3_600_000L)

        // with [attempts] off the open attempt keeps its own deadline, so the order's expiry (and not the attempt job) closes it and cancels it at the gateway
        if (attempts) db.sql("UPDATE `pano_market_payment` SET `expiresAt` = `expiresAt` - 7200000 WHERE `orderId` = ? AND `expiresAt` IS NOT NULL", id)
    }

    private fun grantCredits(buyer: E2eBuyer, amount: Int) {
        admin.post(
            "${MarketPaths.PANEL_ROOT}/credits/accounts/${buyer.userId}/grant", JsonObject().put("amount", amount).put("note", "e2e payment flow"),
            mapOf("Idempotency-Key" to idempotencyKey())
        ).ok()
    }

    private fun creditTx(publicId: String, type: String): Long = db.count("market_credit_tx", "`orderId` = ? AND `type` = ?", orderId(publicId), type)

    private fun creditBalance(buyer: E2eBuyer): Long = db.long("SELECT `balance` FROM `pano_market_credit_account` WHERE `userId` = ?", buyer.userId) ?: 0L

    /** The `data.status` the fake plugin's provider answers in `GET /payment-providers` for [id]. */
    private fun providerState(id: String): String? =
        admin.get("${MarketPaths.PANEL_ROOT}/payment-providers", log = false).ok().obj().getJsonArray("items").map { it as JsonObject }.firstOrNull { it.getString("id") == id }
            ?.getString("state")

    private fun eventRow(eventId: String) =
        db.sql("SELECT * FROM `pano_market_payment_event` WHERE `providerId` = 'fake' AND `direction` = 'IN' AND `eventKey` = ?", "e:$eventId").firstOrNull()

    /** The stored inbound row whose raw body carries [eventId]: a `DEFERRED` row has no provider key yet (its key is `r:<uuid>` until a provider reads the event). */
    private fun eventRowByBody(eventId: String) =
        db.sql("SELECT * FROM `pano_market_payment_event` WHERE `providerId` = 'fake' AND `direction` = 'IN' AND `body` LIKE ?", "%$eventId%").firstOrNull()

    /** Posts one signed event to `/api/market/payments/<providerId>/webhook` (the route of that provider, not the fake gateway's configured target). */
    private fun webhookTo(providerId: String, type: String, data: JsonObject, eventId: String = gateway.nextEventId()): HttpResponse<String> {
        val body = gateway.eventBody(type, data, eventId)
        val request = HttpRequest.newBuilder(URI.create("$baseUrl${MarketPaths.SITE_ROOT}/payments/$providerId/webhook")).header("Content-Type", "application/json")
            .header("X-Fake-Signature", gateway.signatureHeader(body, FakePayGateway.Signature.VALID)!!).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build()

        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun succeededData(reference: String, amount: String, currency: String): JsonObject =
        JsonObject().put("reference", reference).put("amount", amount).put("currency", currency)

    private fun setProviderSettings(extra: JsonObject) {
        admin.post(
            "${MarketPaths.PANEL_ROOT}/payment-methods/fake",
            JsonObject().put("settings", JsonObject().put("gatewayUrl", gateway.baseUrl).put("secret", gateway.secret).also { s -> extra.fieldNames().forEach { s.put(it, extra.getValue(it)) } })
        ).ok()
    }

    /** `capabilities.buyerMayPayMore` of the `fake` provider as the panel lists it. */
    private fun buyerMayPayMore(): Boolean? =
        admin.get("${MarketPaths.PANEL_ROOT}/payment-providers", log = false).ok().obj().getJsonArray("items").map { it as JsonObject }.first { it.getString("id") == "fake" }
            .getJsonObject("capabilities")?.getBoolean("buyerMayPayMore")

    /** True when the REFUND request body asks the gateway to give back [amount] of payment [gatewayPaymentId]. */
    private fun refundRequestFor(gatewayPaymentId: String, amount: BigDecimal): Boolean = gateway.requests(FakePayGateway.Op.REFUND).any {
        val body = JsonObject(it.bodyText())

        body.getString("paymentId") == gatewayPaymentId && body.getString("amount")?.let { a -> BigDecimal(a).compareTo(amount) == 0 } == true
    }

    // ---- F-03 ---------------------------------------------------------------------------------------------------------

    @Test
    fun `F-03 missing webhook is reconciled`() {
        val (_, publicId, reference) = pending()

        gateway.setStatus(reference, "paid") // paid at the gateway; no webhook, no browser return

        val queries = gateway.requests(FakePayGateway.Op.QUERY).size

        // the reconcile job picks an attempt up once its nextQueryAt is due: make it due
        db.sql("UPDATE `pano_market_payment` SET `nextQueryAt` = ? WHERE `reference` = ?", System.currentTimeMillis() - 1_000, reference)

        awaitOrder(publicId, "COMPLETED", 120_000)

        assertEquals("SUCCEEDED", attemptStatus(reference))
        assertTrue(gateway.requests(FakePayGateway.Op.QUERY).size > queries, "the reconcile job asked the gateway")
        assertEquals(1L, orderEvents(publicId, "PAYMENT_SUCCEEDED"), "the order was paid exactly once")
    }

    // ---- F-05 / F-06 / F-07 -------------------------------------------------------------------------------------------

    @Test
    fun `F-05 underpaid goes to review, accept completes, reject refunds`() {
        val (buyer, publicId, reference) = pending()
        val expected = gateway.payments[reference]!!.amount
        val received = expected - BigDecimal("0.01")

        assertEquals(listOf(200), gateway.pay(reference, received).map { it.statusCode() })
        awaitOrder(publicId, "REVIEW")

        assertEquals("REVIEW", attemptStatus(reference))
        val row = orderRow(publicId)
        assertEquals("UNDERPAID", row.getString("reviewReason"))
        assertEquals("HELD", row.getString("reservationState"), "the reservation stays held while the order waits for a decision")
        assertEquals(0, deliveries(publicId).size, "nothing is delivered for an order in review")
        assertEquals("REVIEW", order(buyer.client, publicId).getString("status"))

        // ACCEPT: all the effects of a paid order (O2)
        reviewOrder(publicId, "ACCEPT").ok()
        awaitOrder(publicId, "COMPLETED")

        assertEquals("COMPLETED", orderStatus(publicId))
        assertNotNull(orderRow(publicId).getValue("paidAt"), "paidAt is set")
        assertEquals("COMMITTED", orderRow(publicId).getString("reservationState"))
        assertEquals("SUCCEEDED", attemptStatus(reference), "the reviewed attempt is SUCCEEDED once accepted")
        Await.until(60_000, 250, "deliveries confirmed") { deliveries(publicId).let { it.isNotEmpty() && it.all { s -> s == "CONFIRMED" } } }
        assertEquals(listOf("ACTIVE"), entitlementStates(publicId))

        // a second order: REJECT with refund=true cancels it and refunds exactly what was received
        val (_, second, secondRef) = pending()
        val secondReceived = gateway.payments[secondRef]!!.amount - BigDecimal("0.01")

        gateway.pay(secondRef, secondReceived)
        awaitOrder(second, "REVIEW")

        reviewOrder(second, "REJECT", refund = true).ok()
        awaitOrder(second, "CANCELLED")

        val refunds = db.sql("SELECT * FROM `pano_market_refund` WHERE `orderId` = ?", orderId(second))

        assertEquals(1, refunds.size, "one refund row")
        assertEquals(secondReceived.movePointRight(2).toLong(), refunds.single().getLong("amount"), "the refund is for the amount that was received")
        val gatewayPayment = gateway.payments[secondRef]!!.id

        Await.until(60_000, 250, "the refund of this payment and amount reaches the gateway") { refundRequestFor(gatewayPayment, secondReceived) }
    }

    @Test
    fun `F-06 overpaid goes to review, buyerMayPayMore completes`() {
        // the setting is global state of the shared instance and is merged key by key: start from the declared default, never from what an earlier run left
        setProviderSettings(JsonObject().put("buyerMayPayMore", false))
        assertEquals(false, buyerMayPayMore(), "the fake provider starts without buyerMayPayMore")

        val (_, publicId, reference) = pending()
        val expected = gateway.payments[reference]!!.amount

        gateway.pay(reference, expected + BigDecimal("5.00"))
        awaitOrder(publicId, "REVIEW")

        assertEquals("REVIEW", attemptStatus(reference))
        assertEquals("OVERPAID", orderRow(publicId).getString("reviewReason"))

        // leave nothing in review behind: reject it (no money back requested by this scenario's assertion)
        reviewOrder(publicId, "REJECT", refund = true).ok()
        awaitOrder(publicId, "CANCELLED")

        // the provider declares buyerMayPayMore: the same overpayment completes the order and records what was paid
        setProviderSettings(JsonObject().put("buyerMayPayMore", true))

        try {
            assertEquals(true, buyerMayPayMore(), "the provider now declares buyerMayPayMore")

            val (_, second, secondRef) = pending()
            val paid = gateway.payments[secondRef]!!.amount + BigDecimal("5.00")

            gateway.pay(secondRef, paid)
            awaitOrder(second, "COMPLETED")

            assertEquals("SUCCEEDED", attemptStatus(secondRef))
            assertEquals(paid.movePointRight(2).toLong(), paymentRow(secondRef).getLong("paidAmount"), "the amount actually paid is recorded")
            assertEquals(paid.movePointRight(2).toLong(), orderRow(second).getLong("paidAmount"))
        } finally {
            // the settings form keeps the stored value of every key it does not carry (SettingsCodec.applyForm), so the key is written back explicitly
            setProviderSettings(JsonObject().put("buyerMayPayMore", false))
        }

        assertEquals(false, buyerMayPayMore(), "buyerMayPayMore is put back for the next scenario")
    }

    @Test
    fun `F-07 wrong currency goes to review`() {
        val (_, publicId, reference) = pending()
        val amount = gateway.payments[reference]!!.amount.toPlainString()

        assertEquals("EUR", gateway.payments[reference]!!.currency)
        assertEquals(listOf(200), gateway.sendWebhook("payment.succeeded", succeededData(reference, amount, "USD")).map { it.statusCode() })
        awaitOrder(publicId, "REVIEW")

        assertEquals("REVIEW", attemptStatus(reference))
        assertEquals("CURRENCY_MISMATCH", orderRow(publicId).getString("reviewReason"))

        reviewOrder(publicId, "REJECT", refund = false).ok()
        awaitOrder(publicId, "CANCELLED")
    }

    // ---- F-09 / F-10 --------------------------------------------------------------------------------------------------

    @Test
    fun `F-09 provider error at start keeps the order payable`() {
        val vip = catalog.fresh("VIP")
        val buyer = buyer()
        val before = db.count("market_payment")

        gateway.failNext(FakePayGateway.Op.CREATE, 500)

        val failed = checkout(buyer.client, cart(line(vip.id)))

        assertEquals(502, failed.status, "the provider failed: ${failed.error}")
        assertEquals("PAYMENT_PROVIDER_ERROR", failed.error)
        val view = failed.details.getJsonObject("order") ?: throw AssertionError("the error answer carries no order")
        val publicId = view.getString("publicId")

        assertEquals("PENDING", orderStatus(publicId))
        assertEquals(before + 1, db.count("market_payment"), "one attempt was written")
        val reference = referenceOf(publicId)

        assertEquals("FAILED", attemptStatus(reference))
        assertNotNull(paymentRow(reference).getValue("failureCode"), "the attempt carries a failure code")
        assertEquals("HELD", orderRow(publicId).getString("reservationState"), "the reservation is kept so the buyer can try again")
        assertEquals(true, order(buyer.client, publicId).getBoolean("canRetryPayment"))

        // the buyer pays with the other method (a new attempt through /pay) and completes the order
        val retry = buyer.client.post("${MarketPaths.SITE_ROOT}/orders/$publicId/pay", JsonObject().put("paymentMethodId", "fake-eur")).ok().obj()

        assertNotNull(retry.getJsonObject("payment"))
        val second = referenceOf(publicId)

        assertNotEquals(reference, second)
        assertEquals("fake-eur", paymentRow(second).getString("providerId"))
        gateway.setStatus(second, "paid")
        // the events of an attempt arrive on the route of the provider that created it (fake-eur), signed with the one shared secret
        val answer = webhookTo("fake-eur", "payment.succeeded", succeededData(second, gateway.payments[second]!!.amount.toPlainString(), "EUR"))

        assertEquals(200, answer.statusCode(), "answer ${answer.body()}")
        awaitOrder(publicId, "COMPLETED")
        assertEquals("FAILED", attemptStatus(reference), "the failed attempt stays closed")
    }

    @Test
    fun `F-10 provider timeout at start answers within 35 seconds`() {
        val vip = catalog.fresh("VIP")
        val buyer = buyer()

        // the provider's own request deadline (15 s by default) must fire well before the platform's 30 s start deadline: a platform timeout
        // leaves the attempt CREATED (06 section 9.2 step 5), a provider error closes it FAILED (step 4) and both answer 502, so the race is removed here
        setProviderSettings(JsonObject().put("timeoutMs", 3_000))
        gateway.hang(FakePayGateway.Op.CREATE)

        val publicId: String
        val started = System.currentTimeMillis()

        try {
            val failed = checkout(buyer.client, cart(line(vip.id)))
            val took = System.currentTimeMillis() - started

            assertTrue(took < 35_000, "the answer took ${took} ms")
            assertEquals(502, failed.status, "answer ${failed.status} ${failed.error}")
            assertEquals("PAYMENT_PROVIDER_ERROR", failed.error)
            publicId = failed.details.getJsonObject("order").getString("publicId")
        } finally {
            gateway.release(FakePayGateway.Op.CREATE)
            setProviderSettings(JsonObject().put("timeoutMs", 15_000))
        }

        val reference = referenceOf(publicId)

        assertEquals("PENDING", orderStatus(publicId))
        assertEquals("FAILED", attemptStatus(reference), "failureCode ${paymentRow(reference).getValue("failureCode")}")
        assertNotNull(paymentRow(reference).getValue("failureCode"))

        // late success of the abandoned attempt (00 section 7.2): FAILED -> SUCCEEDED and the order, still PENDING, completes
        val amount = db.long("SELECT `amount` FROM `pano_market_payment` WHERE `reference` = ?", reference)!!
        val answers = gateway.sendWebhook("payment.succeeded", succeededData(reference, BigDecimal(amount).movePointLeft(2).toPlainString(), "EUR"))

        assertTrue(answers.all { it.statusCode() == 200 }, "answers ${answers.map { it.statusCode() }}")
        awaitOrder(publicId, "COMPLETED")
        assertEquals("SUCCEEDED", attemptStatus(reference))
    }

    // ---- F-11 / F-12 --------------------------------------------------------------------------------------------------

    @Test
    fun `F-11 order expiry releases everything`() {
        val last = catalog.fresh("LAST")
        val (couponId, code) = catalog.freshCoupon(50, redeemLimit = 3, customerRedeemLimit = 1)
        val buyer = buyer()

        grantCredits(buyer, 5)
        val balanceBefore = creditBalance(buyer)

        val publicId = publicIdOf(checkout(buyer.client, cart(line(last.id)).put("couponCode", code).put("useCredits", 1)).ok())
        val reference = referenceOf(publicId)
        val coupon = { db.long("SELECT `usedCount` FROM `pano_market_coupon` WHERE `id` = ?", couponId) }

        assertEquals(0L, stockOf(last.id), "the stock is reserved")
        assertEquals(1L, coupon(), "the coupon use is counted")
        assertEquals(1L, creditTx(publicId, "HOLD"), "the credits are held")
        assertTrue(creditBalance(buyer) < balanceBefore)

        expire(publicId, attempts = false)
        awaitOrder(publicId, "EXPIRED", 120_000)

        assertEquals("RELEASED", orderRow(publicId).getString("reservationState"))
        assertEquals(1L, stockOf(last.id), "the stock is back")
        assertEquals(0L, coupon(), "the coupon use is back")
        assertEquals("RELEASED", db.string("SELECT `state` FROM `pano_market_redemption` WHERE `orderId` = ? AND `kind` = 'COUPON'", orderId(publicId)))
        assertEquals(1L, creditTx(publicId, "RELEASE"), "the credits were released")
        assertEquals(balanceBefore, creditBalance(buyer), "the buyer has the credits back")
        Await.until(30_000, 250, "the gateway got a cancel") { gateway.requests(FakePayGateway.Op.CANCEL).any { it.path.contains(reference) } }
    }

    @Test
    fun `F-12 late payment on an expired order goes to review`() {
        val first = catalog.fresh("LAST")
        val (_, firstId, firstRef) = pendingOf(first.id)

        expire(firstId)
        awaitOrder(firstId, "EXPIRED", 120_000)
        assertEquals(1L, stockOf(first.id))

        gateway.pay(firstRef)
        awaitOrder(firstId, "REVIEW")

        assertEquals("LATE", orderRow(firstId).getString("reviewReason"))
        assertEquals(0, deliveries(firstId).size, "nothing was delivered")

        // ACCEPT re-reserves the stock and completes the order
        reviewOrder(firstId, "ACCEPT").ok()
        awaitOrder(firstId, "COMPLETED")
        assertEquals(0L, stockOf(first.id), "the stock was reserved again")
        assertEquals("COMMITTED", orderRow(firstId).getString("reservationState"))

        // the stock is gone before the late payment: ACCEPT answers 409 OUT_OF_STOCK and the order stays in review
        val second = catalog.fresh("LAST")
        val (_, secondId, secondRef) = pendingOf(second.id)

        expire(secondId)
        awaitOrder(secondId, "EXPIRED", 120_000)

        val other = buyer()
        val otherId = publicIdOf(checkout(other.client, cart(line(second.id))).ok())

        payViaFake(otherId)
        awaitOrder(otherId, "COMPLETED")
        assertEquals(0L, stockOf(second.id))

        gateway.pay(secondRef)
        awaitOrder(secondId, "REVIEW")

        val refused = reviewOrder(secondId, "ACCEPT")

        assertEquals(409, refused.status)
        assertEquals("OUT_OF_STOCK", refused.error)
        assertEquals("REVIEW", orderStatus(secondId), "the order stays in review")

        reviewOrder(secondId, "REJECT", refund = true).ok()
        awaitOrder(secondId, "CANCELLED")
    }

    // ---- F-13 ---------------------------------------------------------------------------------------------------------

    @Test
    fun `F-13 buyer cancels`() {
        val last = catalog.fresh("LAST")
        val buyer = buyer()
        val publicId = publicIdOf(checkout(buyer.client, cart(line(last.id))).ok())

        assertEquals(0L, stockOf(last.id))
        buyer.client.post("${MarketPaths.SITE_ROOT}/orders/$publicId/cancel").ok()

        assertEquals("CANCELLED", orderStatus(publicId))
        assertEquals("RELEASED", orderRow(publicId).getString("reservationState"))
        assertEquals(1L, stockOf(last.id), "the stock is back")

        // a completed order cannot be cancelled
        val vip = catalog.fresh("VIP")
        val paid = publicIdOf(checkout(buyer.client, cart(line(vip.id))).ok())

        payViaFake(paid)
        awaitOrder(paid, "COMPLETED")
        val completed = buyer.client.post("${MarketPaths.SITE_ROOT}/orders/$paid/cancel")

        assertEquals(409, completed.status)
        assertEquals("ORDER_NOT_CANCELLABLE", completed.error)
        assertEquals("COMPLETED", orderStatus(paid))

        // nor one whose attempt is PROCESSING (the gateway says the buyer notified a transfer / a confirmation is awaited)
        val processing = publicIdOf(checkout(buyer.client, cart(line(vip.id))).ok())
        val reference = referenceOf(processing)

        assertEquals(listOf(200), gateway.sendWebhook("payment.pending", JsonObject().put("reference", reference).put("reason", "AWAITING_CONFIRMATIONS")).map { it.statusCode() })
        Await.until(30_000, 250, "attempt PROCESSING") { attemptStatus(reference) == "PROCESSING" }

        val refused = buyer.client.post("${MarketPaths.SITE_ROOT}/orders/$processing/cancel")

        assertEquals(409, refused.status, "answer ${refused.status} ${refused.error}")
        assertEquals("PENDING", orderStatus(processing))
        assertEquals("PROCESSING", attemptStatus(reference))

        // leave no open attempt behind
        gateway.pay(reference)
        awaitOrder(processing, "COMPLETED")
    }

    // ---- F-14 ---------------------------------------------------------------------------------------------------------

    @Test
    fun `F-14 duplicate payment on a paid order is refunded automatically`() {
        val (buyer, publicId, first) = pending()

        buyer.client.post("${MarketPaths.SITE_ROOT}/orders/$publicId/pay", JsonObject().put("paymentMethodId", "fake")).ok()
        val second = referenceOf(publicId)

        assertNotEquals(first, second)

        // the gateway pays both attempts: the first (closed by the retry, a late success) completes the order, the second is the duplicate
        assertEquals(listOf(200), gateway.pay(first).map { it.statusCode() })
        awaitOrder(publicId, "COMPLETED")
        assertEquals(listOf(200), gateway.pay(second).map { it.statusCode() })
        Await.until(30_000, 250, "the second attempt is SUCCEEDED") { attemptStatus(second) == "SUCCEEDED" }

        assertEquals("COMPLETED", orderStatus(publicId))
        assertEquals(1L, orderCompletions(publicId), "the order completed once (one O2 transition)")
        assertEquals(1L, orderEvents(publicId, "PAYMENT_SUCCEEDED") - duplicateSuccessRows(publicId), "one PAYMENT_SUCCEEDED row for the order's own payment")
        assertEquals(1L, duplicateSuccessRows(publicId), "the duplicate attempt's row is flagged duplicate")
        assertEquals(1L, paymentRow(second).getLong("duplicate"), "the second attempt is flagged duplicate")
        assertEquals(0L, paymentRow(first).getLong("duplicate"))
        Await.until(30_000, 250, "the automatic refund exists") { db.count("market_refund", "`orderId` = ? AND `origin` = 'SYSTEM'", orderId(publicId)) == 1L }
        assertEquals(1L, db.count("market_refund", "`orderId` = ?", orderId(publicId)), "exactly one refund")

        val secondPaid = paymentRow(second).getLong("paidAmount")
        val refundRow = db.sql("SELECT * FROM `pano_market_refund` WHERE `orderId` = ?", orderId(publicId)).single()

        assertEquals("SYSTEM", refundRow.getString("origin"))
        assertEquals(secondPaid, refundRow.getLong("amount"), "the automatic refund is for what the second attempt paid")
        assertEquals(paymentRow(second).getLong("id"), refundRow.getLong("paymentId"), "the refund row points at the second attempt")

        val secondAmount = BigDecimal(secondPaid).movePointLeft(2)
        val secondGatewayId = gateway.payments[second]!!.id
        val firstGatewayId = gateway.payments[first]!!.id

        Await.until(60_000, 250, "the refund of the second attempt reaches the gateway") { refundRequestFor(secondGatewayId, secondAmount) }
        assertFalse(
            gateway.requests(FakePayGateway.Op.REFUND).any { JsonObject(it.bodyText()).getString("paymentId") == firstGatewayId },
            "no refund request names the first (legitimate) attempt's gateway payment"
        )
        assertEquals("COMPLETED", orderStatus(publicId), "refunding the duplicate does not touch the order")
    }

    @Test
    fun `F-14b duplicate payment with the setting off raises a panel alert`() {
        session.withSettings(JsonObject().put("autoRefundDuplicatePayments", false)) {
            val (buyer, publicId, first) = pending()

            buyer.client.post("${MarketPaths.SITE_ROOT}/orders/$publicId/pay", JsonObject().put("paymentMethodId", "fake")).ok()
            val second = referenceOf(publicId)

            gateway.pay(first)
            awaitOrder(publicId, "COMPLETED")
            gateway.pay(second)
            Await.until(30_000, 250, "the second attempt is SUCCEEDED") { attemptStatus(second) == "SUCCEEDED" }

            assertEquals(1L, paymentRow(second).getLong("duplicate"))
            assertEquals(0L, db.count("market_refund", "`orderId` = ?", orderId(publicId)), "no refund without the setting")
            val alerts = db.sql("SELECT `data` FROM `pano_market_order_event` WHERE `orderId` = ? AND `type` = 'NOTE' AND `message` = 'DUPLICATE_PAYMENT'", orderId(publicId))

            assertEquals(1, alerts.size, "one panel alert event on the order")
            assertEquals("AUTO_REFUND_OFF", JsonObject(alerts.single().getString("data")).getString("why"))
        }
    }

    // ---- F-15 ---------------------------------------------------------------------------------------------------------

    @Test
    fun `F-15 provider unavailable defers events and a replay completes them`() {
        val (_, publicId, reference) = pending()
        val amount = gateway.payments[reference]!!.amount.toPlainString()
        val eventId = gateway.nextEventId()

        admin.put("/api/v1/panel/addons/pano-plugin-market-fake", JsonObject().put("status", false)).ok()

        try {
            Await.until(60_000, 250, "provider fake is not ACTIVE") { providerState("fake") != "ACTIVE" }

            val answers = gateway.sendWebhook("payment.succeeded", succeededData(reference, amount, "EUR"), id = eventId)

            assertEquals(listOf(503), answers.map { it.statusCode() }, "the webhook of a stopped provider is answered 503")
            assertEquals("UNAVAILABLE", providerState("fake"), "the method state is UNAVAILABLE")
        } finally {
            admin.put("/api/v1/panel/addons/pano-plugin-market-fake", JsonObject().put("status", true)).ok()
            Await.until(90_000, 500, "provider fake is ACTIVE again") { providerState("fake") == "ACTIVE" && providerState("fake-eur") == "ACTIVE" }
        }

        val row = eventRowByBody(eventId) ?: throw AssertionError("the deferred event was not stored")

        assertEquals("DEFERRED", row.getString("status"))
        assertEquals("PENDING", orderStatus(publicId))

        val replayed = admin.post("${MarketPaths.PANEL_ROOT}/payment-events/${row.getLong("id")}/replay", JsonObject()).ok().obj()

        assertEquals("PROCESSED", replayed.getString("status"))
        awaitOrder(publicId, "COMPLETED")
        assertEquals("PROCESSED", eventRow(eventId)!!.getString("status"))
        assertEquals("SUCCEEDED", attemptStatus(reference))
    }

    // ---- F-16 / F-17 --------------------------------------------------------------------------------------------------

    @Test
    fun `F-16 unknown target is skipped, not an error`() {
        val (_, publicId, reference) = pending()
        val eventId = gateway.nextEventId()
        val orders = db.count("market_order")

        val answers = gateway.sendWebhook("payment.succeeded", succeededData("NOSUCHREF0001", "1.00", "EUR"), id = eventId)

        assertEquals(listOf(200), answers.map { it.statusCode() })
        assertEquals("OK", answers.single().body())

        val row = eventRow(eventId) ?: throw AssertionError("the event was not stored")

        assertEquals("PROCESSED", row.getString("status"))
        assertEquals(1L, row.getLong("verified"), "the signature was verified")
        assertEquals(orders, db.count("market_order"), "nothing was created")
        assertEquals("PENDING", orderStatus(publicId), "the other order is untouched")
        assertEquals("PENDING", attemptStatus(reference).let { if (it == "CREATED") "PENDING" else it })
    }

    @Test
    fun `F-17 raw body passthrough with BOM, odd whitespace and key order`() {
        val (_, publicId, reference) = pending()
        val amount = gateway.payments[reference]!!.amount.toPlainString()
        val eventId = gateway.nextEventId()
        // reversed key order, tabs and newlines between tokens, a non-ASCII string value, preceded by a UTF-8 byte order mark
        val text = "\uFEFF{\n\t\"data\" :\t{ \"currency\":\"EUR\",\t\"amount\" : \"$amount\" ,\n \"reference\":\"$reference\", \"note\":\"ödeme ✓\" },\r\n  \"type\"\t:\"payment.succeeded\" ,\n\"id\":\"$eventId\" }\n"
        val body = text.toByteArray(Charsets.UTF_8)

        assertEquals(0xEF, body[0].toInt() and 0xFF, "the body starts with the UTF-8 BOM")

        val signature = gateway.signatureHeader(body, FakePayGateway.Signature.VALID)!!
        val answer = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("$baseUrl${MarketPaths.SITE_ROOT}/payments/fake/webhook")).header("Content-Type", "application/json").header("X-Fake-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
            HttpResponse.BodyHandlers.ofString()
        )

        val row = eventRow(eventId)

        assertNotNull(row, "the event was stored (answer ${answer.statusCode()})")
        assertEquals(1L, row!!.getLong("verified"), "the signature over the exact bytes verified (answer ${answer.statusCode()} status ${row.getString("status")})")
        assertEquals(200, answer.statusCode(), "the event was accepted")
        awaitOrder(publicId, "COMPLETED")
        assertEquals("SUCCEEDED", attemptStatus(reference))
        assertEquals("PROCESSED", eventRow(eventId)!!.getString("status"))

        // the same bytes with one byte changed fail verification (the signature is not satisfied by a re-encoded body)
        val changed = body.copyOf().also { it[it.size - 2] = 'x'.code.toByte() }
        val tampered = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("$baseUrl${MarketPaths.SITE_ROOT}/payments/fake/webhook")).header("Content-Type", "application/json").header("X-Fake-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(changed)).build(),
            HttpResponse.BodyHandlers.ofString()
        )

        assertEquals(400, tampered.statusCode(), "a changed body fails the signature")
    }

    // ---- F-18 ---------------------------------------------------------------------------------------------------------

    /** True when the instance's config lists a loopback address under `server.trusted-proxies` (the file is only rewritten on shutdown, so it holds what the JVM booted with). */
    private fun loopbackIsTrustedProxy(): Boolean {
        val file = session.env.dir?.let { java.io.File(it, "config.conf") }?.takeIf { it.isFile } ?: return false
        // Pano rewrites the list over several lines once it has booted with it: read from the (uncommented) key to the closing bracket
        val list = Regex("""(?m)^\s*trusted-proxies\s*=\s*\[([^\]]*)]""").find(file.readText())?.groupValues?.get(1) ?: return false

        return list.contains("\"127.0.0.1\"") || list.contains("\"::1\"")
    }

    @Test
    fun `F-18 blocked buyer`() {
        val vip = catalog.fresh("VIP")
        val buyer = buyer()
        val created = ArrayList<Long>()

        fun block(type: String, value: String, expiresAt: Long? = null): Long {
            val body = JsonObject().put("type", type).put("value", value).put("reason", "e2e F-18")

            expiresAt?.let { body.put("expiresAt", it) }

            return admin.post("${MarketPaths.PANEL_ROOT}/blocks", body).ok().obj().getLong("id").also { created += it }
        }

        var ipEnforcementUnproven = false

        fun assertBlocked(what: String, headers: Map<String, String> = emptyMap()) {
            val quote = buyer.client.post("${MarketPaths.SITE_ROOT}/checkout/quote", cart(line(vip.id)), headers).ok().obj().getJsonObject("quote")
            val messages = quote.getJsonArray("messages")?.map { (it as JsonObject).getString("code") } ?: emptyList()

            assertTrue("BUYER_BLOCKED" in messages, "$what: the quote says BUYER_BLOCKED, messages=$messages")

            val before = db.count("market_order", "`userId` = ?", buyer.userId)
            val refused = checkout(buyer.client, cart(line(vip.id)), headers = headers)

            assertEquals(403, refused.status, "$what: ${refused.error}")
            assertEquals("BUYER_BLOCKED", refused.error)
            assertEquals(before, db.count("market_order", "`userId` = ?", buyer.userId), "$what: no order was written")
        }

        fun assertNotBlocked(what: String, headers: Map<String, String> = emptyMap()) {
            val quote = buyer.client.post("${MarketPaths.SITE_ROOT}/checkout/quote", cart(line(vip.id)), headers).ok().obj().getJsonObject("quote")
            val messages = quote.getJsonArray("messages")?.map { (it as JsonObject).getString("code") } ?: emptyList()

            assertFalse("BUYER_BLOCKED" in messages, "$what: the quote has no BUYER_BLOCKED, messages=$messages")
        }

        try {
            // PLAYER
            val player = block("PLAYER", buyer.username)

            assertBlocked("PLAYER")
            admin.delete("${MarketPaths.PANEL_ROOT}/blocks/$player").ok()
            created.remove(player)
            assertNotBlocked("PLAYER removed")

            // EMAIL
            val email = block("EMAIL", "${buyer.username}@example.com")

            assertBlocked("EMAIL")
            admin.delete("${MarketPaths.PANEL_ROOT}/blocks/$email").ok()
            created.remove(email)
            assertNotBlocked("EMAIL removed")

            // IP (17 section 9.3, 9.3 "likewise"): a block on a public address applies to quote and checkout when the instance honours X-Forwarded-For from the
            // loopback peer (`server.trusted-proxies` holding 127.0.0.1). Without that, ClientIpResolver never judges a loopback peer and the block cannot hit
            // from the test JVM; that case is reported as an aborted (skipped) scenario below, never as a pass.
            val blockedIp = "203.0.113.77"
            val ip = block("IP", blockedIp)

            if (loopbackIsTrustedProxy()) {
                assertBlocked("IP $blockedIp", mapOf("X-Forwarded-For" to blockedIp))
                assertNotBlocked("another forwarded address", mapOf("X-Forwarded-For" to "203.0.113.78"))
                assertNotBlocked("no forwarded header (loopback peer, never judged)")
            } else {
                assertNotBlocked("IP $blockedIp without a trusted proxy", mapOf("X-Forwarded-For" to blockedIp))
                ipEnforcementUnproven = true
            }

            assertTrue(admin.get("${MarketPaths.PANEL_ROOT}/blocks?search=$blockedIp").ok().obj().toString().contains(blockedIp), "the IP block is stored and listed")
            admin.delete("${MarketPaths.PANEL_ROOT}/blocks/$ip").ok()
            created.remove(ip)

            if (loopbackIsTrustedProxy()) assertNotBlocked("IP removed", mapOf("X-Forwarded-For" to blockedIp))

            // an expired block no longer applies: written with a future expiry, then rewound into the past
            val expiring = block("PLAYER", buyer.username, System.currentTimeMillis() + 3_600_000L)

            assertBlocked("PLAYER before it expires")
            db.rewind("market_block", expiring, "expiresAt", 2 * 3_600_000L)
            assertNotBlocked("expired PLAYER block")

            val ok = checkout(buyer.client, cart(line(vip.id))).ok()

            assertEquals("PENDING", ok.obj().getJsonObject("order").getString("status"))
            payViaFake(publicIdOf(ok))
            awaitOrder(publicIdOf(ok), "COMPLETED")
        } finally {
            created.forEach { runCatching { admin.delete("${MarketPaths.PANEL_ROOT}/blocks/$it") } }
            db.sql("DELETE FROM `pano_market_block` WHERE `reason` = 'e2e F-18'")
        }

        Assumptions.assumeFalse(
            ipEnforcementUnproven,
            "IP block enforcement not provable here: the instance does not list 127.0.0.1 in server.trusted-proxies (scripts/e2e-instance.sh must write it, see evidence/E2E-04.md); PLAYER, EMAIL and expiry were asserted"
        )
    }
}
