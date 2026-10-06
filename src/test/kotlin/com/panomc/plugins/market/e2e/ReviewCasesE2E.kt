package com.panomc.plugins.market.e2e

import com.panomc.plugins.market.e2e.mc.FakeMcServer
import com.panomc.plugins.market.e2e.support.E2eBuyer
import com.panomc.plugins.market.e2e.support.E2eClient
import com.panomc.plugins.market.e2e.support.E2eResponse
import com.panomc.plugins.market.e2e.support.E2eTestBase
import com.panomc.plugins.market.mc.core.wire.AdminActor
import com.panomc.plugins.market.mc.core.wire.AdminOp
import com.panomc.plugins.market.mc.core.wire.AdminTarget
import com.panomc.plugins.market.mc.core.wire.MarketAdminMessage
import com.panomc.plugins.market.mc.core.wire.MarketAdminRequest
import com.panomc.plugins.market.mc.core.wire.MarketPurchaseMessage
import com.panomc.plugins.market.mc.core.wire.MarketPurchaseRequest
import com.panomc.plugins.market.mc.core.wire.MarketRequest
import com.panomc.plugins.market.mc.core.wire.PlayerRef
import com.panomc.plugins.market.support.Await
import com.panomc.plugins.market.support.FakePayGateway
import com.panomc.plugins.pano.core.platform.PlatformMessageResponse
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Row
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The revision-2 scenarios of 17 section 9.11, V-01 to V-17 (V-18 is `ResourceIsolationE2E`), over HTTP against the isolated instance: every case that the
 * review added to the catalogue, each with the state the spec's "must hold" column names. Money is moved through the fake gateway, the panel routes and the
 * signed inbound events; time travel is by row rewind only. The base class drains the queues and runs I1 to I22 after every scenario (so I3b / I11b / I22 are
 * proven on the state each scenario leaves behind).
 *
 * Global state a scenario changes (settings, the bank-transfer method, the start kind of the fake provider, the fake gateway's refund mode, chargeback
 * actions) is put back in a `finally`. Scenarios that need a live (non-test) order use the bank transfer under `testMode = false`, because the fake
 * provider is a test-mode method and a test order never earns a creator commission.
 */
class ReviewCasesE2E : E2eTestBase() {
    override val tag = "rv"

    private val sequence = AtomicInteger()
    private val day = 86_400_000L
    private val servers = ArrayList<FakeMcServer>()

    @AfterEach
    fun closeFakeServers() {
        servers.forEach { runCatching { it.close() } }
        servers.clear()
    }

    // --- fixtures ------------------------------------------------------------------------------------------------------

    private fun unique(): Int = sequence.incrementAndGet()

    private fun slug(prefix: String) = "e2e-rv-$prefix-${System.currentTimeMillis().toString(36)}-${unique()}"

    private fun node(): String = "essentials.e2erv${System.currentTimeMillis().toString(36)}${unique()}"

    private fun action(id: String, type: String, value: Any, phase: String = "GRANT"): JsonObject =
        JsonObject().put("id", id).put("type", type).put("phase", phase).put("value", value)

    /** A raw game node written to the Pano permission tables (`via = PANO`), taken back by the automatic inverse. */
    private fun permissionActions(node: String): String =
        JsonArray().add(action("a1", "PERMISSION", JsonArray().add(node)).put("via", "PANO")).encode()

    /** A product of its own: [price], optionally a [creditPrice], [stock] and a raw permission [node]. */
    private fun product(
        price: String, node: String? = null, creditPrice: String? = null, stock: Int? = null, extra: Map<String, String> = emptyMap(), actions: String? = null
    ): Long {
        val s = slug("p")

        return catalog.product(
            key = s, slug = s, name = "Review product $s", price = price, creditPrice = creditPrice, stock = stock,
            actions = actions ?: node?.let { permissionActions(it) }, extra = extra
        )
    }

    private fun holdsNode(userId: Long, node: String): Boolean =
        db.count("permission_node", "`holderType` = 'USER' AND `holderId` = ? AND `node` = ? AND `active` = 1", userId, node) > 0

    private fun orderId(publicId: String): Long = orderRow(publicId).getLong("id")

    private fun grant(userId: Long, amount: Number) {
        admin.post(
            "/api/panel/market/credits/accounts/$userId/grant", JsonObject().put("amount", amount).put("note", "e2e review case"), mapOf("Idempotency-Key" to idempotencyKey())
        ).ok()
    }

    private fun revokeCredits(userId: Long, amount: Number): E2eResponse = admin.post(
        "/api/panel/market/credits/accounts/$userId/revoke", JsonObject().put("amount", amount).put("note", "e2e review case"), mapOf("Idempotency-Key" to idempotencyKey())
    )

    /** The balance in minor units (x100); held credits are not in it. */
    private fun balance(userId: Long): Long = db.long("SELECT `balance` FROM `pano_market_credit_account` WHERE `userId` = ?", userId) ?: 0L

    private fun creditTx(orderId: Long, type: String): Long = db.count("market_credit_tx", "`orderId` = ? AND `type` = ?", orderId, type)

    private fun deliveryRows(orderId: Long, phase: String? = null): List<Row> =
        if (phase == null) db.sql("SELECT * FROM `pano_market_delivery` WHERE `orderId` = ? ORDER BY `id`", orderId)
        else db.sql("SELECT * FROM `pano_market_delivery` WHERE `orderId` = ? AND `phase` = ? ORDER BY `id`", orderId, phase)

    private fun awaitDeliveries(orderId: Long, phase: String, atLeast: Int = 1): List<Row> = Await.untilValue(120_000, 500, "the $phase rows of order $orderId are settled") {
        deliveryRows(orderId, phase).takeIf { rows -> rows.size >= atLeast && rows.none { it.getString("status") in setOf("PENDING", "SCHEDULED", "SENDING", "SENT", "WAITING_SERVER") } }
    }

    private fun entitlements(orderId: Long): List<Row> = db.sql("SELECT * FROM `pano_market_entitlement` WHERE `orderId` = ? ORDER BY `id`", orderId)

    private fun refundRows(orderId: Long): List<Row> = db.sql("SELECT * FROM `pano_market_refund` WHERE `orderId` = ? ORDER BY `id`", orderId)

    private fun refundCalls() = gateway.requests(FakePayGateway.Op.REFUND)

    private fun blocks(orderId: Long): List<Row> = db.sql("SELECT * FROM `pano_market_block` WHERE `orderId` = ? ORDER BY `id`", orderId)

    private fun reviewOrder(publicId: String, decision: String, refund: Boolean? = null): E2eResponse {
        val body = JsonObject().put("decision", decision)

        refund?.let { body.put("refund", it) }

        return admin.post("/api/panel/market/orders/${orderId(publicId)}/review", body)
    }

    private fun refund(orderId: Long, body: JsonObject, key: String = idempotencyKey()): E2eResponse =
        admin.post("/api/panel/market/orders/$orderId/refunds", body, mapOf("Idempotency-Key" to key))

    private fun cancelOrder(client: E2eClient, publicId: String, token: String? = null) {
        val answer = client.post("/api/market/orders/$publicId/cancel", JsonObject(), token?.let { mapOf("X-Order-Token" to it) } ?: emptyMap())

        assertTrue(answer.status == 200 || answer.status == 409, "cancel of $publicId answered ${answer.status} ${answer.error}")
    }

    private fun payOrder(client: E2eClient, body: JsonObject, amount: BigDecimal? = null): Pair<String, Long> {
        val publicId = publicIdOf(checkout(client, body).ok())

        payViaFake(publicId, amount)
        awaitOrder(publicId, "COMPLETED")

        return publicId to orderId(publicId)
    }

    private fun bankTransferOn() {
        val accounts = JsonArray().add(JsonObject().put("bank", "E2E Bank").put("holder", "E2E Store").put("iban", "DE89370400440532013000").put("currency", "EUR"))

        admin.post(
            "/api/panel/market/payment-methods/bank-transfer",
            JsonObject().put("settings", JsonObject().put("accounts", accounts.encode()).put("instructions", "Transfer the exact amount."))
        ).ok()
        admin.post("/api/panel/market/payment-methods/bank-transfer/toggle", JsonObject().put("enabled", true)).ok()
    }

    private fun bankTransferOff() {
        admin.post("/api/panel/market/payment-methods/bank-transfer/toggle", JsonObject().put("enabled", false))
    }

    /** A live paid order: the bank transfer placed and approved under `testMode = false` (the instance has no live gateway, nothing can charge anyone). */
    private fun liveOrder(buyer: E2eBuyer, body: JsonObject): Pair<String, Long> {
        bankTransferOn()
        try {
            return session.withSettings(JsonObject().put("testMode", false)) {
                val id = publicIdOf(checkout(buyer.client, body, method = "bank-transfer").ok())

                admin.post("/api/panel/market/orders/${orderId(id)}/bank-transfer", JsonObject().put("decision", "APPROVE")).ok()
                awaitOrder(id, "COMPLETED")
                assertEquals(0L, orderRow(id).getLong("testMode"), "the bank transfer order is a live order")

                id to orderId(id)
            }
        } finally {
            bankTransferOff()
        }
    }

    private fun dispute(reference: String, state: String, disputeId: String?, amount: String): List<Int> {
        val data = JsonObject().put("reference", reference).put("state", state).put("amount", amount).put("currency", "EUR").put("reason", "fraudulent")

        disputeId?.let { data.put("disputeId", it) }

        return gateway.sendWebhook("dispute.updated", data).map { it.statusCode() }
    }

    private fun paidTotal(publicId: String): String = decimal(orderRow(publicId).getLong("totalPrice")).movePointLeft(2).toPlainString()

    private fun lastNotificationId(): Long = db.long("SELECT COALESCE(MAX(`id`), 0) FROM `pano_panel_notification`") ?: 0L

    /** Panel notifications created after [afterId] whose details name [orderId] (the notification rows are one per administrator). */
    private fun notificationsFor(orderId: Long, afterId: Long): List<Row> =
        db.sql("SELECT `id`, `type`, `details` FROM `pano_panel_notification` WHERE `id` > ? ORDER BY `id`", afterId)
            .filter { it.getString("details").replace(" ", "").contains("\"orderId\":$orderId") }

    private fun storeHook(name: String, vararg events: String): Long = admin.post(
        "/api/panel/market/webhooks",
        JsonObject().put("name", "E2E review sink $name").put("url", "${gateway.baseUrl}/hooks/$name").put("events", JsonArray(events.toList())).put("format", "JSON").put("signing", "NONE")
    ).ok().obj().getLong("id")

    private fun hookEvents(name: String, event: String, orderId: Long): Int = gateway.hooks(name).map { JsonObject(it.bodyText()) }.count {
        it.getString("event") == event && it.getJsonObject("data")?.getJsonObject("order")?.getLong("id") == orderId
    }

    private fun hookName(prefix: String) = prefix + System.nanoTime().toString(36).takeLast(8)

    // --- V-01 ----------------------------------------------------------------------------------------------------------

    /** The pay-less attack of 06 test 59, first direction: held 80 of 100, attempt A = 20.00, `/pay useCredits = 0` re-tenders to 100.00, A is paid. */
    private class Attack(val buyer: E2eBuyer, val publicId: String, val orderId: Long, val node: String, val referenceA: String, val referenceB: String)

    private fun payLessAttack(): Attack {
        val node = node()
        val buyer = buyer()

        grant(buyer.userId, 80)

        val publicId = publicIdOf(checkout(buyer.client, cart(line(product("100.00", node))).put("useCredits", 80)).ok())
        val referenceA = referenceOf(publicId)

        assertEquals(0, gateway.payments.getValue(referenceA).amount.compareTo(BigDecimal("20.00")), "attempt A asks the gateway for 20.00")
        assertEquals(0L, balance(buyer.userId), "the 80 credits are held")

        val retender = buyer.client.post("/api/market/orders/$publicId/pay", JsonObject().put("paymentMethodId", "fake").put("useCredits", 0)).ok()

        assertNotNull(retender.obj().getJsonObject("payment"))

        val referenceB = referenceOf(publicId)

        assertNotEquals(referenceA, referenceB, "the re-tender started a second attempt")
        assertEquals(0, gateway.payments.getValue(referenceB).amount.compareTo(BigDecimal("100.00")), "attempt B asks for the whole 100.00")
        assertEquals("CANCELLED", attemptStatus(referenceA), "A is superseded")
        assertEquals(8000L, balance(buyer.userId), "the hold was released by the re-tender")
        assertEquals(0L, orderRow(publicId).getLong("creditAmount"))

        // the buyer pays the cheaper, superseded attempt A
        assertEquals(listOf(200), gateway.pay(referenceA, BigDecimal("20.00")).map { it.statusCode() })
        awaitOrder(publicId, "REVIEW")

        return Attack(buyer, publicId, orderId(publicId), node, referenceA, referenceB)
    }

    @Test
    fun `V-01 paying the superseded cheaper attempt after a pay-less re-tender goes to review, nothing is delivered, nothing captured, accept re-holds the credits`() {
        val attack = payLessAttack()
        val row = orderRow(attack.publicId)

        assertEquals("AMOUNT_MISMATCH", row.getString("reviewReason"))
        assertEquals("SUCCEEDED", attemptStatus(attack.referenceA), "the paid attempt itself is SUCCEEDED")
        assertEquals("CANCELLED", attemptStatus(attack.referenceB), "the newer attempt is cancelled")
        assertEquals(0, deliveryRows(attack.orderId).size, "nothing is delivered for an order in review")
        assertFalse(holdsNode(attack.buyer.userId, attack.node), "the rank was not granted")
        assertEquals(0L, creditTx(attack.orderId, "CAPTURE"), "nothing is captured")
        assertEquals(8000L, balance(attack.buyer.userId), "the credits the buyer released stay released")
        assertEquals("REVIEW", order(attack.buyer.client, attack.publicId).getString("status"))

        // ACCEPT with enough balance: the tender is rewritten to A's snapshot, the 80 credits are held again and captured, the order completes
        reviewOrder(attack.publicId, "ACCEPT").ok()
        awaitOrder(attack.publicId, "COMPLETED")

        val done = orderRow(attack.publicId)

        assertEquals(8000L, done.getLong("creditAmount"))
        assertEquals(8000L, done.getLong("creditValue"))
        assertEquals(2000L, done.getLong("gatewayAmount"))
        assertEquals(10_000L, done.getLong("totalPrice"))
        assertEquals("COMMITTED", done.getString("reservationState"))
        assertEquals(2L, creditTx(attack.orderId, "HOLD"), "the first hold, and the one of the accept (re-held once)")
        assertEquals(1L, creditTx(attack.orderId, "RELEASE"), "the first hold was released by the re-tender")
        assertEquals(1L, creditTx(attack.orderId, "CAPTURE"), "captured once")
        assertEquals(0L, balance(attack.buyer.userId), "the buyer paid with the credits he had committed to")
        Await.until(120_000, 500, "the rank is granted") { holdsNode(attack.buyer.userId, attack.node) }
    }

    @Test
    fun `V-01 accept without enough balance answers 400 INSUFFICIENT_CREDITS and the order stays in review untouched`() {
        val attack = payLessAttack()

        assertEquals(200, revokeCredits(attack.buyer.userId, 50).status, "the buyer spends 50 of the 80 meanwhile")
        assertEquals(3000L, balance(attack.buyer.userId))

        val refused = reviewOrder(attack.publicId, "ACCEPT")

        assertEquals(400, refused.status)
        assertEquals("INSUFFICIENT_CREDITS", refused.error)

        val after = orderRow(attack.publicId)

        assertEquals("REVIEW", after.getString("status"))
        assertEquals(0L, after.getLong("creditAmount"), "the tender was not rewritten")
        assertEquals(10_000L, after.getLong("totalPrice"))
        assertEquals(3000L, balance(attack.buyer.userId))
        assertEquals(0, deliveryRows(attack.orderId).size)

        // the balance is restored: the same accept now works (and leaves a consistent order for the invariants)
        grant(attack.buyer.userId, 50)
        reviewOrder(attack.publicId, "ACCEPT").ok()
        awaitOrder(attack.publicId, "COMPLETED")
        assertEquals(0L, balance(attack.buyer.userId))
    }

    @Test
    fun `V-01 mirror case - A is the whole 100, a re-tender to 80 credits, paying A goes to review, the 80 credits stay held and are released by a reject`() {
        val node = node()
        val buyer = buyer()

        grant(buyer.userId, 80)

        val publicId = publicIdOf(checkout(buyer.client, cart(line(product("100.00", node)))).ok())
        val referenceA = referenceOf(publicId)

        assertEquals(0, gateway.payments.getValue(referenceA).amount.compareTo(BigDecimal("100.00")))
        assertEquals(8000L, balance(buyer.userId), "nothing is held yet")

        buyer.client.post("/api/market/orders/$publicId/pay", JsonObject().put("paymentMethodId", "fake").put("useCredits", 80)).ok()

        val referenceB = referenceOf(publicId)

        assertEquals(0, gateway.payments.getValue(referenceB).amount.compareTo(BigDecimal("20.00")), "attempt B asks for 20.00")
        assertEquals(0L, balance(buyer.userId), "the re-tender holds the 80 credits")

        assertEquals(listOf(200), gateway.pay(referenceA, BigDecimal("100.00")).map { it.statusCode() })
        awaitOrder(publicId, "REVIEW")

        val row = orderRow(publicId)

        assertEquals("AMOUNT_MISMATCH", row.getString("reviewReason"))
        assertEquals(0, deliveryRows(row.getLong("id")).size, "nothing is delivered")
        assertEquals(0L, creditTx(row.getLong("id"), "CAPTURE"), "the held credits are not captured")
        assertEquals(1L, creditTx(row.getLong("id"), "HOLD"))
        assertEquals(0L, balance(buyer.userId), "the 80 credits stay held")

        // REJECT with a refund: only the money received comes back, the held credits are released, no credit refund
        reviewOrder(publicId, "REJECT", refund = true).ok()
        awaitOrder(publicId, "CANCELLED")

        val refunds = refundRows(row.getLong("id"))

        assertEquals(1, refunds.size)
        assertEquals(10_000L, refunds.single().getLong("gatewayAmount"), "the 100.00 that was received")
        assertEquals(0L, refunds.single().getLong("creditAmount"), "never a credit refund")
        assertEquals(8000L, balance(buyer.userId), "the credits are back, none was spent")
    }

    // --- V-02 ----------------------------------------------------------------------------------------------------------

    /** A credit-paid monthly subscription (6.00 / 6 credits, a raw rank): the order, its subscription row, and the renewal order once step B has prepared it. */
    private class CreditSub(val buyer: E2eBuyer, val publicId: String, val orderId: Long, val subscriptionId: Long, val node: String)

    private fun subscriptionOfOrder(publicId: String): Row = db.sql(
        "SELECT s.* FROM `pano_market_subscription` s JOIN `pano_market_order` o ON o.`subscriptionId` = s.`id` WHERE o.`publicId` = ? AND o.`source` <> 'RENEWAL'", publicId
    ).singleOrNull() ?: throw AssertionError("order $publicId has no subscription")

    private fun creditSubscription(initialCredits: Int = 100): CreditSub {
        val node = node()
        val buyer = buyer()
        val productId = product(
            "6.00", actions = permissionActions(node),
            extra = mapOf("billingMode" to "SUBSCRIPTION", "periodUnit" to "MONTH", "periodCount" to "1")
        )

        // 07 F5 / 09 section 4.1 sell a subscription with a credit price in full with credits, 04 section 5 (and the real ProductRules) refuse a creditPrice on a
        // SUBSCRIPTION product (MUST_BE_ZERO): the product API cannot build the state V-02 is about, so the price is written to the row (open seam in evidence/E2E-12.md: the spec contradiction, ProductRules is not this slice's)
        db.sql("UPDATE `pano_market_product` SET `creditPrice` = 600 WHERE `id` = ?", productId)

        grant(buyer.userId, initialCredits)

        val publicId = publicIdOf(checkout(buyer.client, cart(line(productId)).put("payWithCredits", true), method = null).ok())

        awaitOrder(publicId, "COMPLETED")

        val sub = Await.untilValue(30_000, 500, "the subscription of $publicId is ACTIVE") { subscriptionOfOrder(publicId).takeIf { it.getString("status") == "ACTIVE" } }

        assertEquals("MANUAL", sub.getString("mode"), "credits are never deducted automatically: the subscription is MANUAL")
        assertEquals(initialCredits * 100L - 600L, balance(buyer.userId), "the first period cost 6.00 credits")

        return CreditSub(buyer, publicId, orderId(publicId), sub.getLong("id"), node)
    }

    /** Moves the period end of the subscription one day into the future, so step B of the subscription job prepares the renewal order. */
    private fun prepareRenewal(sub: CreditSub): Row {
        val row = db.sql("SELECT * FROM `pano_market_subscription` WHERE `id` = ?", sub.subscriptionId).single()
        val delta = row.getLong("currentPeriodEnd") - (System.currentTimeMillis() + day)

        // both ends move together: the lead of the reminder is cut to half the period (09 section 8.6)
        if (delta > 0) {
            db.rewind("market_subscription", sub.subscriptionId, "currentPeriodStart", delta)
            db.rewind("market_subscription", sub.subscriptionId, "currentPeriodEnd", delta)
        }

        return Await.untilValue(150_000, 1000, "the renewal order of subscription ${sub.subscriptionId} exists") {
            db.sql("SELECT * FROM `pano_market_order` WHERE `subscriptionId` = ? AND `source` = 'RENEWAL' ORDER BY `id`", sub.subscriptionId).firstOrNull()
        }
    }

    @Test
    fun `V-02 a renewal of a credit-paid subscription carries no credits, expiring it or cancelling the subscription mints nothing, paying it with credits posts a real hold`() {
        // (1) the renewal order expires unpaid
        val first = creditSubscription()
        val renewal = prepareRenewal(first)
        val renewalId = renewal.getLong("id")

        assertEquals("PENDING", renewal.getString("status"))
        assertEquals(0L, renewal.getLong("creditAmount"), "a renewal order is created credit-free")
        assertEquals(0, db.count("market_credit_tx", "`orderId` = ?", renewalId).toInt(), "and no HOLD tx exists for it")

        val before = balance(first.buyer.userId)

        db.rewind("market_order", renewalId, "expiresAt", 10 * day)
        Await.untilValue(150_000, 1000, "the renewal order is EXPIRED or closed") {
            db.string("SELECT `status` FROM `pano_market_order` WHERE `id` = ?", renewalId).takeIf { it == "EXPIRED" || it == "CANCELLED" }
        }
        assertEquals(0L, creditTx(renewalId, "RELEASE"), "expiring an order that holds nothing posts no RELEASE")
        assertEquals(before, balance(first.buyer.userId), "no minted credits: subscribe, let it lapse, collect is impossible")

        // (2) the subscription is cancelled while a renewal order is open
        val second = creditSubscription()
        val secondRenewal = prepareRenewal(second)

        assertEquals("PENDING", secondRenewal.getString("status"))
        assertEquals(0L, secondRenewal.getLong("creditAmount"))

        val beforeCancel = balance(second.buyer.userId)

        second.buyer.client.post("/api/market/me/subscriptions/${second.subscriptionId}/cancel", JsonObject().put("atPeriodEnd", false)).ok()
        Await.until(60_000, 500, "the open renewal order is cancelled") { orderStatus(secondRenewal.getString("publicId")) in setOf("CANCELLED", "EXPIRED") }
        assertEquals(0L, creditTx(secondRenewal.getLong("id"), "RELEASE"), "cancelling posts no RELEASE for a renewal that held nothing")
        assertEquals(beforeCancel, balance(second.buyer.userId), "no credits appear when the subscription is cancelled")

        // (3) the renewal is paid with credits: a real hold, then the capture, the period moves on
        val third = creditSubscription()
        val thirdRenewal = prepareRenewal(third)
        val periodBefore = db.sql("SELECT * FROM `pano_market_subscription` WHERE `id` = ?", third.subscriptionId).single()
        val balanceBefore = balance(third.buyer.userId)

        assertEquals(0L, thirdRenewal.getLong("creditAmount"))

        third.buyer.client.post("/api/market/orders/${thirdRenewal.getString("publicId")}/pay", JsonObject().put("paymentMethodId", "credits")).ok()
        awaitOrder(thirdRenewal.getString("publicId"), "COMPLETED")

        val paid = orderRow(thirdRenewal.getString("publicId"))

        assertEquals(600L, paid.getLong("creditAmount"), "the hold is real")
        assertEquals(1L, creditTx(thirdRenewal.getLong("id"), "HOLD"))
        assertEquals(1L, creditTx(thirdRenewal.getLong("id"), "CAPTURE"))
        assertEquals(balanceBefore - 600L, balance(third.buyer.userId), "the balance went down by the 6.00 credits")

        val periodAfter = Await.untilValue(60_000, 500, "the subscription counted the renewal") {
            db.sql("SELECT * FROM `pano_market_subscription` WHERE `id` = ?", third.subscriptionId).single().takeIf { it.getInteger("cycleCount") == periodBefore.getInteger("cycleCount") + 1 }
        }

        assertTrue(periodAfter.getLong("currentPeriodEnd") > periodBefore.getLong("currentPeriodEnd"), "the period moved on")
        assertEquals("ACTIVE", periodAfter.getString("status"))
    }

    // --- V-03 ----------------------------------------------------------------------------------------------------------

    private fun quote(client: E2eClient, body: JsonObject): JsonObject = client.post("/api/market/checkout/quote", body).ok().obj().getJsonObject("quote")

    private fun option(quote: JsonObject, methodId: String): JsonObject =
        quote.getJsonArray("paymentMethods").map { it as JsonObject }.firstOrNull { it.getString("id") == methodId }
            ?: throw AssertionError("the quote lists no payment method $methodId")

    private fun guestBody(productId: Long, username: String, quantity: Int = 1): JsonObject =
        cart(line(productId, quantity)).put("guest", JsonObject().put("username", username).put("email", "${username.lowercase()}@example.com"))

    private fun guestName(): String = "Guest_" + System.nanoTime().toString(36).takeLast(8)

    @Test
    fun `V-03 a test-mode method is refused to a visitor, a guest and a user without PAY, a PAY holder and the admin buy and the order is flagged testMode`() {
        val vip = product("10.00")

        // the quote lists the method as unavailable with the reason TEST_MODE for a visitor and for a registered user without the node
        val visitor = visitor("v03-visitor")

        assertEquals(false, option(quote(visitor, cart(line(vip))), "fake").getBoolean("available"))
        assertEquals("TEST_MODE", option(quote(visitor, cart(line(vip))), "fake").getString("unavailableReason"))

        val plain = buyer(canPay = false)
        val plainOption = option(quote(plain.client, cart(line(vip))), "fake")

        assertEquals(false, plainOption.getBoolean("available"))
        assertEquals("TEST_MODE", plainOption.getString("unavailableReason"))

        // checkout: guest and plain user refused, nothing created
        val guestRefused = checkout(visitor("v03-guest"), guestBody(vip, guestName()))

        assertEquals(400, guestRefused.status)
        assertEquals("PAYMENT_METHOD_UNAVAILABLE", guestRefused.error)
        assertEquals("TEST_MODE", guestRefused.json!!.getString("reason"))

        val ordersBefore = db.count("market_order", "`userId` = ?", plain.userId)
        val plainRefused = checkout(plain.client, cart(line(vip)))

        assertEquals(400, plainRefused.status)
        assertEquals("PAYMENT_METHOD_UNAVAILABLE", plainRefused.error)
        assertEquals("TEST_MODE", plainRefused.json!!.getString("reason"))
        assertEquals(ordersBefore, db.count("market_order", "`userId` = ?", plain.userId), "the refusal created no order")

        // /pay on an open order of the plain user (placed with a method that is not a test-mode one) is refused as well
        bankTransferOn()
        try {
            val open = publicIdOf(checkout(plain.client, cart(line(vip)), method = "bank-transfer").ok())
            val payRefused = plain.client.post("/api/market/orders/$open/pay", JsonObject().put("paymentMethodId", "fake"))

            assertEquals(400, payRefused.status)
            assertEquals("PAYMENT_METHOD_UNAVAILABLE", payRefused.error)
            assertEquals("TEST_MODE", payRefused.json!!.getString("reason"))
            cancelOrder(plain.client, open)
        } finally {
            bankTransferOff()
        }

        // the holder of PAY (the harness's paying buyer) buys, the order is a test order
        val payer = buyer()
        val publicId = publicIdOf(checkout(payer.client, cart(line(vip))).ok())

        assertEquals(1L, orderRow(publicId).getLong("testMode"), "an order paid through a test-mode method is flagged testMode")
        payViaFake(publicId)
        awaitOrder(publicId, "COMPLETED")
        assertEquals(1L, orderRow(publicId).getLong("testMode"))

        // the admin (umbrella node) buys too: flagged in the panel view as well
        val adminOrder = publicIdOf(checkout(admin, cart(line(vip))).ok())
        val adminRow = orderRow(adminOrder)

        assertEquals(1L, adminRow.getLong("testMode"))

        val panelView = admin.get("/api/panel/market/orders/${adminRow.getLong("id")}").ok().obj()

        assertEquals(true, (panelView.getJsonObject("order") ?: panelView).getValue("testMode").let { it == true || (it as? Number)?.toInt() == 1 }, "the panel order carries testMode")
        cancelOrder(admin, adminOrder)
    }

    // --- V-04 ----------------------------------------------------------------------------------------------------------

    private fun fakeServer(awaitReady: Boolean = true, version: String? = null): FakeMcServer {
        val n = unique()
        val server = FakeMcServer(session, "v${n}x", Files.createTempDirectory("fake-mc-rv-$n-")).also { servers += it }

        server.launch(version)

        if (awaitReady) Await.until(60_000, 200, "server ${server.serverId} is READY") { server.view().getString("marketState") == "READY" }

        return server
    }

    /** A product that runs `give` on grant and `clear` on revoke on [server] (a COMMAND action has no automatic inverse; the admin writes the REVOKE one). */
    private fun commandProduct(server: FakeMcServer, price: String = "2.00"): Long {
        val target = JsonArray().add(server.serverId)
        val actions = JsonArray()
            .add(action("a1", "COMMAND", JsonArray().add("give {username} diamond"), "GRANT").put("serverMode", "FIXED").put("targetServers", target).put("requiresOnline", false))
            .add(action("a2", "COMMAND", JsonArray().add("clear {username} diamond"), "REVOKE").put("serverMode", "FIXED").put("targetServers", target).put("requiresOnline", false))

        return product(price, actions = actions.encode())
    }

    private fun panelOrder(orderId: Long): JsonObject = admin.get("/api/panel/market/orders/$orderId").ok().obj()

    @Test
    fun `V-04 a refund while the target server is offline settles the money, leaves the order PARTIAL with revokePending and an alert, and revokeFirst holds the gateway call`() {
        val server = fakeServer()
        val buyer = buyer()
        val productId = commandProduct(server)
        val (publicId, orderId) = payOrder(buyer.client, cart(line(productId)))

        awaitDeliveries(orderId, "GRANT")
        Await.until(60_000, 200, "the order is FULFILLED") { orderRow(publicId).getString("fulfillmentStatus") == "FULFILLED" }
        assertEquals(listOf("give ${buyer.username} diamond"), server.platform.console)

        // the server goes away: the component stops pulling
        server.stop()

        val notificationMark = lastNotificationId()
        val refundView = refund(orderId, JsonObject().put("amount", 2.0).put("revoke", true)).ok().obj().getJsonObject("refund")

        assertEquals("SUCCEEDED", refundView.getString("status"), "without revokeFirst the money goes first")
        assertEquals("REFUNDED", orderStatus(publicId))

        val revoke = Await.untilValue(120_000, 500, "the REVOKE row waits for the server") { deliveryRows(orderId, "REVOKE").singleOrNull()?.takeIf { it.getString("status") == "WAITING_SERVER" } }

        assertEquals("PARTIAL", orderRow(publicId).getString("fulfillmentStatus"), "a refunded order is never shown as revoked while the buyer still has the goods")
        assertEquals(1, panelOrder(orderId).getInteger("revokePending"))
        assertEquals(0, panelOrder(orderId).getInteger("revokeFailed"))

        // the alert: a REVOKE row open for more than 10 minutes raises MARKET_DELIVERY_WAITING for this order (the sweep runs every 5 minutes)
        db.rewind("market_delivery", revoke.getLong("id"), "createdAt", 11 * 60_000L)
        Await.untilValue(420_000, 5000, "the urgent notification for order $orderId") { notificationsFor(orderId, notificationMark).firstOrNull() }

        // the server syncs again: the undo is confirmed and the order is REVOKED
        server.start()
        awaitDeliveries(orderId, "REVOKE")
        Await.until(120_000, 500, "the order is REVOKED") { orderRow(publicId).getString("fulfillmentStatus") == "REVOKED" }
        assertEquals(0, panelOrder(orderId).getInteger("revokePending"))
        assertEquals(listOf("give ${buyer.username} diamond", "clear ${buyer.username} diamond"), server.platform.console)

        // revokeFirst: the gateway is called only after every REVOKE row is CONFIRMED
        val second = payOrder(buyer.client, cart(line(productId)))

        awaitDeliveries(second.second, "GRANT")
        server.stop()

        val calls = refundCalls().size
        val held = refund(second.second, JsonObject().put("amount", 2.0).put("revoke", true).put("revokeFirst", true)).ok().obj().getJsonObject("refund")

        assertEquals("REQUESTED", held.getString("status"), "the refund waits for the undo")
        assertEquals(calls, refundCalls().size, "the gateway was not called")
        assertEquals("COMPLETED", orderStatus(second.first))
        assertEquals(1, deliveryRows(second.second, "REVOKE").size, "the REVOKE row is planned at once")

        server.start()
        awaitDeliveries(second.second, "REVOKE")
        Await.until(180_000, 1000, "the refund went through after the undo") { refundRows(second.second).single().getString("status") == "SUCCEEDED" }
        assertEquals(calls + 1, refundCalls().size, "the gateway is called once the REVOKE row is CONFIRMED")
        assertEquals("REFUNDED", orderStatus(second.first))
    }

    // --- V-05 ----------------------------------------------------------------------------------------------------------

    @Test
    fun `V-05 an asynchronous refund in flight reduces the remainder of a second refund and a confirmed gateway-side refund is never FAILED`() {
        val buyer = buyer()
        val (publicId, orderId) = payOrder(buyer.client, cart(line(product("10.00"))))
        val reference = referenceOf(publicId)

        gateway.refundMode = FakePayGateway.RefundMode.PENDING
        try {
            val first = refund(orderId, JsonObject().put("amount", 6.00)).ok().obj().getJsonObject("refund")

            assertEquals("PENDING", first.getString("status"))

            // the in-flight 6.00 counts: a second 6.00 does not fit in the remaining 4.00
            val second = refund(orderId, JsonObject().put("amount", 6.00))

            assertEquals(400, second.status)
            assertEquals("INVALID_REFUND_AMOUNT", second.error)
            assertEquals(4.0, second.obj().getDouble("max"), 0.0001, "the maximum subtracts the refund that is still PENDING")
            assertEquals(1, refundRows(orderId).size)

            // a refund made at the gateway's dashboard arrives for what is left (a gateway cannot refund more than it captured): booked, confirmed, never FAILED
            val dashboardId = "dash_${System.nanoTime()}"
            val dashboard = JsonObject().put("reference", reference).put("state", "SUCCEEDED").put("amount", "4.00").put("currency", "EUR").put("refundId", dashboardId)

            assertEquals(listOf(200), gateway.sendWebhook("refund.updated", dashboard).map { it.statusCode() })

            val rows = refundRows(orderId)

            assertEquals(2, rows.size, "the panel refund and the gateway one are both booked")

            val gatewayRow = rows.single { it.getString("origin") == "GATEWAY" }

            assertEquals("SUCCEEDED", gatewayRow.getString("status"), "a refund the gateway confirmed is never FAILED")
            assertEquals(dashboardId, gatewayRow.getString("gatewayRefundId"))
            assertEquals(400L, orderRow(publicId).getLong("refundedTotal"), "only the confirmed 4.00 is in the order books; the 6.00 is still in flight")
            assertEquals("PARTIALLY_REFUNDED", orderStatus(publicId))

            // the panel refund is confirmed too: both stay booked, the order is refunded in full and never over-refunded
            val panelRow = rows.single { it.getString("origin") != "GATEWAY" }
            val confirmation = JsonObject().put("reference", reference).put("state", "SUCCEEDED").put("amount", "6.00").put("currency", "EUR").put("refundId", panelRow.getString("gatewayRefundId"))

            assertEquals(listOf(200), gateway.sendWebhook("refund.updated", confirmation).map { it.statusCode() })
            awaitOrder(publicId, "REFUNDED")

            val after = refundRows(orderId)

            assertEquals(setOf("SUCCEEDED"), after.map { it.getString("status") }.toSet(), "no refund row is FAILED: ${after.map { it.getString("status") }}")
            assertEquals(1000L, orderRow(publicId).getLong("refundedTotal"))
        } finally {
            gateway.refundMode = FakePayGateway.RefundMode.SUCCEEDED
        }
    }

    // --- V-06 ----------------------------------------------------------------------------------------------------------

    @Test
    fun `V-06 a RefundUpdated without ids after a panel refund is matched to the open refund and not booked twice`() {
        val buyer = buyer()
        val (publicId, orderId) = payOrder(buyer.client, cart(line(product("10.00"))))
        val reference = referenceOf(publicId)

        gateway.refundMode = FakePayGateway.RefundMode.PENDING
        try {
            assertEquals("PENDING", refund(orderId, JsonObject().put("amount", 10.00)).ok().obj().getJsonObject("refund").getString("status"))
            assertEquals(1, refundRows(orderId).size)

            // the gateway's notification carries the reference, the state and the amount, but no refund id
            val noIds = JsonObject().put("reference", reference).put("state", "SUCCEEDED").put("amount", "10.00").put("currency", "EUR")

            assertEquals(listOf(200), gateway.sendWebhook("refund.updated", noIds).map { it.statusCode() })
            awaitOrder(publicId, "REFUNDED")

            val rows = refundRows(orderId)

            assertEquals(1, rows.size, "matched to the open row, no second one")
            assertEquals("SUCCEEDED", rows.single().getString("status"))
            assertNotEquals("GATEWAY", rows.single().getString("origin"), "it stays the panel's own refund")
            assertEquals(1000L, orderRow(publicId).getLong("refundedTotal"), "booked once")

            // the same notification delivered again (a new event id) is no second refund
            assertEquals(listOf(200), gateway.sendWebhook("refund.updated", noIds).map { it.statusCode() })
            assertEquals(1, refundRows(orderId).size)
            assertEquals(1000L, orderRow(publicId).getLong("refundedTotal"))
            assertEquals(1L, orderEvents(publicId, "REFUND_SUCCEEDED"))
        } finally {
            gateway.refundMode = FakePayGateway.RefundMode.SUCCEEDED
        }
    }

    // --- V-07 ----------------------------------------------------------------------------------------------------------

    @Test
    fun `V-07 an inquiry changes nothing, OPENED is one O11, WON delivered twice is one O12 and creates no second block`() {
        val name = hookName("v07")
        val endpointId = storeHook(name, "order.chargeback", "order.chargeback.won")

        try {
            val node = node()
            val buyer = buyer()
            val (publicId, orderId) = payOrder(buyer.client, cart(line(product("10.00", node))))
            val reference = referenceOf(publicId)
            val total = paidTotal(publicId)
            val disputeId = "dp_${System.nanoTime()}"

            Await.until(120_000, 1000, "the rank was granted") { holdsNode(buyer.userId, node) }

            val grants = deliveryRows(orderId, "GRANT").size

            // INQUIRY: a row, a timeline entry, nothing else
            assertEquals(listOf(200), dispute(reference, "INQUIRY", disputeId, total))
            assertEquals("COMPLETED", orderStatus(publicId), "an inquiry never moves the order")
            assertEquals("INQUIRY", db.string("SELECT `status` FROM `pano_market_dispute` WHERE `orderId` = ?", orderId))
            assertEquals(0, blocks(orderId).size, "no block")
            assertEquals(0, deliveryRows(orderId, "REVOKE").size, "no revoke")
            assertEquals(grants, deliveryRows(orderId).size, "no delivery row of any kind")
            assertTrue(holdsNode(buyer.userId, node))
            assertEquals(0, hookEvents(name, "order.chargeback", orderId), "no O11 webhook")

            // OPENED (delivered twice): one O11
            assertEquals(listOf(200), dispute(reference, "OPENED", disputeId, total))
            assertEquals(listOf(200), dispute(reference, "OPENED", disputeId, total))
            assertEquals("CHARGEBACK", orderStatus(publicId))
            assertEquals(1L, db.count("market_dispute", "`orderId` = ?", orderId), "one dispute row for the one id")
            awaitDeliveries(orderId, "REVOKE")
            assertFalse(holdsNode(buyer.userId, node))
            assertEquals(1L, orderEvents(publicId, "DISPUTE_OPENED"), "one O11")

            val blockCount = blocks(orderId).size

            assertTrue(blockCount > 0, "the chargeback blocked the buyer")
            Await.until(60_000, 500, "the order.chargeback webhook reached the sink") { hookEvents(name, "order.chargeback", orderId) >= 1 }
            assertEquals(1, hookEvents(name, "order.chargeback", orderId))

            // WON (delivered twice): one O12, the blocks of the order are removed once, nothing is created again
            assertEquals(listOf(200), dispute(reference, "WON", disputeId, total))
            assertEquals("COMPLETED", orderStatus(publicId))
            assertEquals(0, blocks(orderId).size)
            assertEquals(listOf(200), dispute(reference, "WON", disputeId, total))
            assertEquals("COMPLETED", orderStatus(publicId))
            assertEquals(0, blocks(orderId).size, "the second WON created no block")
            assertEquals(1L, orderEvents(publicId, "DISPUTE_CLOSED"), "one O12")
            assertEquals(1L, db.count("market_dispute", "`orderId` = ?", orderId))
            Await.until(60_000, 500, "the order.chargeback.won webhook reached the sink") { hookEvents(name, "order.chargeback.won", orderId) >= 1 }
            assertEquals(1, hookEvents(name, "order.chargeback.won", orderId))
            assertEquals(1, hookEvents(name, "order.chargeback", orderId), "and no second O11 webhook")
            assertEquals(grants, deliveryRows(orderId, "GRANT").size, "nothing is granted again")
        } finally {
            admin.delete("/api/panel/market/webhooks/$endpointId")
        }
    }

    // --- V-08 ----------------------------------------------------------------------------------------------------------

    @Test
    fun `V-08 a guest gift chargeback blocks the recipient and the e-mail, never the victim name, and the ban rows wait for confirmation`() {
        val server = fakeServer()
        val victim = guestName()
        val attacker = buyer(canPay = false)
        val banJson = JsonArray().add(
            JsonObject().put("id", "cb1").put("type", "COMMAND").put("value", JsonArray().add("ban {username} Chargeback")).put("serverMode", "FIXED")
                .put("targetServers", JsonArray().add(server.serverId))
        ).encode()

        session.withSettings(JsonObject().put("chargebackActions", banJson).put("autoBlockOnChargeback", true)) {
            // the victim's guest checkout gifts a stock-free product to the attacker's account; paid live (a guest cannot use a test-mode method)
            bankTransferOn()
            val (publicId, orderId) = try {
                session.withSettings(JsonObject().put("testMode", false)) {
                    val body = guestBody(product("10.00"), victim).put("recipientUsername", attacker.username)
                    val id = publicIdOf(checkout(visitor("v08-guest"), body, method = "bank-transfer").ok())

                    admin.post("/api/panel/market/orders/${orderId(id)}/bank-transfer", JsonObject().put("decision", "APPROVE")).ok()
                    awaitOrder(id, "COMPLETED")

                    id to orderId(id)
                }
            } finally {
                bankTransferOff()
            }
            val row = orderRow(publicId)

            assertNull(row.getValue("userId"), "a guest order has no payer account")
            assertEquals("g:${victim.lowercase()}", row.getString("buyerKey"))
            assertEquals(attacker.userId, row.getLong("recipientUserId"))

            val dispute = admin.post("/api/panel/market/orders/$orderId/disputes", JsonObject().put("reason", "e2e guest gift chargeback")).ok().obj().getLong("id")

            assertNotNull(dispute)
            assertEquals("CHARGEBACK", orderStatus(publicId))

            val blockRows = blocks(orderId)
            val playerValues = blockRows.filter { it.getString("type") == "PLAYER" }.map { it.getString("value").lowercase() }

            assertEquals(listOf(attacker.username.lowercase()), playerValues, "the recipient (where the goods went) is blocked")
            assertTrue(blockRows.any { it.getString("type") == "EMAIL" && it.getString("value").equals("${victim.lowercase()}@example.com", ignoreCase = true) }, "the order e-mail is blocked")
            assertTrue(blockRows.none { it.getString("type") == "PLAYER" && it.getString("value").equals(victim, ignoreCase = true) }, "the victim's typed name is never blocked")
            assertTrue(blockRows.none { it.getString("type") == "USER" }, "a guest has no account to block")
            assertEquals(setOf("CHARGEBACK"), blockRows.map { it.getString("source") }.toSet())

            // the ban rows exist but are CANCELLED (NEEDS_CONFIRMATION): nothing was offered to any server
            val rows = db.sql("SELECT * FROM `pano_market_delivery` WHERE `orderId` = ? AND `sourceType` = 'CHARGEBACK_ACTION' ORDER BY `id`", orderId)

            assertTrue(rows.isNotEmpty(), "the chargeback action rows were planned")
            assertEquals(setOf("CANCELLED"), rows.map { it.getString("status") }.toSet())
            assertEquals(setOf("NEEDS_CONFIRMATION"), rows.map { it.getString("lastErrorCode") }.toSet())
            assertTrue(server.platform.console.none { it.startsWith("ban") }, "no ban reached the console: ${server.platform.console}")

            // the admin confirms: the ban is aimed at the recipient
            admin.post("/api/panel/market/orders/$orderId/chargeback-actions", JsonObject()).ok()
            Await.until(120_000, 500, "the ban reached the server") { server.platform.console.contains("ban ${attacker.username} Chargeback") }
            assertTrue(server.platform.console.none { it.contains(victim) }, "never the victim")

            admin.put("/api/panel/market/disputes/$dispute", JsonObject().put("status", "WON")).ok()
            assertEquals("COMPLETED", orderStatus(publicId))
            assertEquals(0, blocks(orderId).size)
        }
    }

    // --- V-09 ----------------------------------------------------------------------------------------------------------

    @Test
    fun `V-09 top-up then spend then chargeback records the debt, revokes the credit-paid order and refuses a hold until the debt is repaid`() {
        admin.post("/api/panel/market/settings/credits", JsonObject().put("creditTopUpEnabled", true)).ok()
        // the buyer block of the chargeback would answer BUYER_BLOCKED before the hold is tried: it is off here, V-07 / V-08 cover the blocks
        try {
            session.withSettings(JsonObject().put("autoBlockOnChargeback", false)) { topUpSpendChargeback() }
        } finally {
            admin.post("/api/panel/market/settings/credits", JsonObject().put("creditTopUpEnabled", false))
        }
    }

    private fun topUpSpendChargeback() {
        run {
            val s = slug("pack")
            val pack = catalog.product(s, s, "100 credits $s", price = "100.00", extra = mapOf("kind" to "CREDIT_PACK", "creditAmount" to "100.00"))
            val node = node()
            val goods = product("5.00", node, creditPrice = "100.00")
            val buyer = buyer()

            // top-up 100 by card
            val (topUpId, topUpOrder) = payOrder(buyer.client, cart(line(pack)))

            Await.until(30_000, 250, "the top-up credits arrive") { balance(buyer.userId) == 10_000L }

            // spend them all: a credit-paid order
            val spent = publicIdOf(checkout(buyer.client, cart(line(goods)).put("payWithCredits", true), method = null).ok())

            awaitOrder(spent, "COMPLETED")
            assertEquals(0L, balance(buyer.userId))
            Await.until(120_000, 1000, "the rank was granted") { holdsNode(buyer.userId, node) }

            // chargeback on the top-up
            assertEquals(listOf(200), dispute(referenceOf(topUpId), "OPENED", "dp_${System.nanoTime()}", paidTotal(topUpId)))
            assertEquals("CHARGEBACK", orderStatus(topUpId))

            val clawback = db.sql("SELECT * FROM `pano_market_credit_tx` WHERE `orderId` = ? AND `type` = 'REVOKE'", topUpOrder).singleOrNull()

            assertNotNull(clawback, "the top-up was clawed back through a REVOKE transaction")
            assertEquals(10_000L, clawback!!.getLong("amount"))
            assertEquals(-10_000L, balance(buyer.userId), "ALLOW_DEBT: the balance is the debt")
            assertEquals(1L, orderEvents(topUpId, "CLAWBACK_SHORTFALL"), "the shortfall is on the timeline")

            // the credit-paid order is revoked too (revokeCreditOrdersOnTopUpChargeback)
            awaitDeliveries(orderId(spent), "REVOKE")
            Await.until(60_000, 500, "the credit-paid order is revoked") { entitlements(orderId(spent)).all { it.getString("status") == "REVOKED" } }
            assertFalse(holdsNode(buyer.userId, node), "the goods paid with the charged-back credits were taken back")

            // a later hold fails until the debt is repaid
            val cheap = product("1.00", creditPrice = "1.00")
            val refused = checkout(buyer.client, cart(line(cheap)).put("payWithCredits", true), method = null)

            assertEquals(400, refused.status, "a hold against a negative balance fails: ${refused.error}")
            assertEquals("INSUFFICIENT_CREDITS", refused.error)

            grant(buyer.userId, 101)
            assertEquals(100L, balance(buyer.userId))

            val repaid = publicIdOf(checkout(buyer.client, cart(line(cheap)).put("payWithCredits", true), method = null).ok())

            awaitOrder(repaid, "COMPLETED")
        }
    }

    // --- V-10 ----------------------------------------------------------------------------------------------------------

    private fun tierCategory(): Long = admin.multipart(
        "POST", "/api/panel/market/categories", mapOf("name" to "E2E rv tiers ${slug("cat")}", "tiered" to "true", "upgradeMode" to "DIFFERENCE", "status" to "ACTIVE")
    ).ok().obj().getLong("id")

    private class Tiers(val tier1: Long, val tier2: Long, val node1: String, val node2: String)

    private fun tiers(): Tiers {
        val category = tierCategory()
        val node1 = node()
        val node2 = node()
        val t1 = product("10.00", node1, extra = mapOf("categoryId" to category.toString(), "tierRank" to "1"))
        val t2 = product("25.00", node2, extra = mapOf("categoryId" to category.toString(), "tierRank" to "2"))

        return Tiers(t1, t2, node1, node2)
    }

    /** Buys tier 1, then the upgrade to tier 2: (tier-1 order, upgrade order). */
    private fun upgradeChain(buyer: E2eBuyer, tiers: Tiers): Pair<Pair<String, Long>, Pair<String, Long>> {
        val lower = payOrder(buyer.client, cart(line(tiers.tier1)))

        Await.until(120_000, 1000, "tier 1 was granted") { holdsNode(buyer.userId, tiers.node1) }

        val upper = payOrder(buyer.client, cart(line(tiers.tier2)))

        Await.until(120_000, 1000, "tier 2 was granted") { holdsNode(buyer.userId, tiers.node2) }
        Await.until(30_000, 250, "tier 1 is marked UPGRADED") { entitlements(lower.second).single().getString("status") == "UPGRADED" }

        return lower to upper
    }

    @Test
    fun `V-10 charging back the tier-1 order after an upgrade revokes the successor, and the refund path needs a cascade decision`() {
        // chargeback: the successor financed by the disputed payment is revoked too
        val tiers = tiers()
        val buyer = buyer()
        val (lower, upper) = upgradeChain(buyer, tiers)

        assertEquals(listOf(200), dispute(referenceOf(lower.first), "OPENED", "dp_${System.nanoTime()}", paidTotal(lower.first)))
        assertEquals("CHARGEBACK", orderStatus(lower.first))
        awaitDeliveries(lower.second, "REVOKE")
        awaitDeliveries(upper.second, "REVOKE")
        assertFalse(holdsNode(buyer.userId, tiers.node2), "the successor tier is revoked: its price was financed by the disputed payment")
        assertFalse(holdsNode(buyer.userId, tiers.node1))

        val successor = entitlements(upper.second).single()

        assertEquals("REVOKED", successor.getString("status"))
        assertEquals("CHARGEBACK", successor.getString("endReason"))
        assertEquals("COMPLETED", orderStatus(upper.first), "no money moves on the successor order")

        // refund path (an admin refund of a fresh chain): the preview warns, the request must decide
        val second = buyer()
        val (lower2, upper2) = upgradeChain(second, tiers)
        val preview = admin.get("/api/panel/market/orders/${lower2.second}/refund-preview").ok().obj()
        val warning = preview.getJsonArray("warnings").map { it as JsonObject }.firstOrNull { it.getString("code") == "UPGRADE_DEPENDENT" }

        assertNotNull(warning, "the preview reports the dependent successor")
        assertEquals(upper2.second, warning!!.getLong("successorOrderId"))

        val undecided = refund(lower2.second, JsonObject().put("amount", 10.0))

        assertEquals(400, undecided.status)
        assertEquals("CASCADE_DECISION_REQUIRED", undecided.error)
        assertEquals(0, refundRows(lower2.second).size, "the refused request wrote nothing")

        val decided = refund(lower2.second, JsonObject().put("amount", 10.0).put("cascadeUpgrade", true)).ok().obj().getJsonObject("refund")

        assertEquals("SUCCEEDED", decided.getString("status"))
        awaitDeliveries(upper2.second, "REVOKE")
        assertFalse(holdsNode(second.userId, tiers.node2), "cascadeUpgrade revoked the successor")
        assertEquals("REVOKED", entitlements(upper2.second).single().getString("status"))
        assertEquals("REFUND", entitlements(upper2.second).single().getString("endReason"))
    }

    // --- V-11 ----------------------------------------------------------------------------------------------------------

    private fun creatorCode(creator: E2eBuyer): Pair<Long, String> {
        val code = "RV" + System.currentTimeMillis().toString(36).uppercase() + unique()
        val id = admin.post(
            "/api/panel/market/creator-codes",
            JsonObject().put("creator", creator.username).put("code", code).put("discount", 0).put("unit", "PERCENT").put("commissionPercent", 10)
        ).ok().obj().getLong("id")

        return id to code
    }

    private fun payout(codeId: Long, amount: Double, key: String = idempotencyKey()): E2eResponse = admin.post(
        "/api/panel/market/creator-codes/$codeId/payouts", JsonObject().put("amount", amount).put("method", "MANUAL").put("note", "paid outside the store"), mapOf("Idempotency-Key" to key)
    )

    private fun reportOf(codeId: Long): JsonObject =
        admin.get("/api/panel/market/creator-codes/report").ok().obj().getJsonArray("creators").map { it as JsonObject }.single { it.getLong("id") == codeId }

    @Test
    fun `V-11 an earning cannot be paid out while PENDING and the reversal of a PAID earning makes available negative and blocks payouts`() {
        val creator = buyer()
        val (codeId, code) = creatorCode(creator)

        // earning one: a hold of one day, PENDING
        val first = session.withSettings(JsonObject().put("creatorEarningHoldDays", 1)) { liveOrder(buyer(canPay = false), cart(line(product("20.00"))).put("creatorCode", code)) }
        val pending = db.sql("SELECT * FROM `pano_market_creator_earning` WHERE `orderId` = ?", first.second).single()

        assertEquals("PENDING", pending.getString("state"))
        assertTrue(pending.getLong("amount") > 0)
        assertEquals(0.0, reportOf(codeId).getDouble("available"), 0.0001, "nothing is payable before the hold ends")

        val early = payout(codeId, 1.0)

        assertEquals(400, early.status)
        assertEquals("INVALID_PAYOUT_AMOUNT", early.error)
        assertEquals(0.0, early.obj().getDouble("available"), 0.0001)
        assertEquals(0L, db.count("market_creator_payout", "`creatorCodeId` = ?", codeId), "the refused payout wrote nothing")

        // earning two: no hold, AVAILABLE; it is paid out in full
        val second = session.withSettings(JsonObject().put("creatorEarningHoldDays", 0)) { liveOrder(buyer(canPay = false), cart(line(product("30.00"))).put("creatorCode", code)) }
        val available = db.sql("SELECT * FROM `pano_market_creator_earning` WHERE `orderId` = ?", second.second).single()

        assertEquals("AVAILABLE", available.getString("state"))

        val amount = available.getLong("amount") / 100.0

        assertEquals(amount, reportOf(codeId).getDouble("available"), 0.0001)
        payout(codeId, amount).ok()
        assertEquals("PAID", db.string("SELECT `state` FROM `pano_market_creator_earning` WHERE `orderId` = ?", second.second))
        assertEquals(0.0, reportOf(codeId).getDouble("available"), 0.0001)

        // the PAID earning's order is charged back: the reversal shows up as a negative available and blocks every further payout
        admin.post("/api/panel/market/orders/${second.second}/disputes", JsonObject().put("reason", "e2e creator chargeback")).ok()

        val reversed = db.sql("SELECT * FROM `pano_market_creator_earning` WHERE `orderId` = ?", second.second).single()

        assertEquals(available.getLong("amount"), reversed.getLong("reversedAmount"), "reversed in full")
        assertEquals("PAID", reversed.getString("state"), "a paid row stays PAID, nothing is clawed back automatically")
        assertEquals(-amount, reportOf(codeId).getDouble("available"), 0.0001, "available is negative")

        val blocked = payout(codeId, 0.01)

        assertEquals(400, blocked.status)
        assertEquals("INVALID_PAYOUT_AMOUNT", blocked.error)
        assertTrue(blocked.obj().getDouble("available") < 0.0, "the answer shows the negative available")
        assertEquals(1L, db.count("market_creator_payout", "`creatorCodeId` = ?", codeId), "still the one payout")
    }

    // --- V-12 ----------------------------------------------------------------------------------------------------------

    private class OpenGuestOrder(val client: E2eClient, val publicId: String, val token: String?)

    /**
     * One L4 block (17 section 9.11 V-12, 11 section 21 S-HOARD) for the dimension that [request] holds constant: three open bank-transfer orders of
     * `request(1..3)` pass, the fourth is 429 TOO_MANY_REQUESTS with `retryAfter >= 1`, a control order that differs only in that dimension (`request(0)`)
     * still passes (so it is that dimension and not a global limit), and after one of the three is cancelled the next order of the full subject passes.
     * Every opened order is cancelled at the end. [expectedIp] is the stored `clientIp` of the opened orders (`null` = the IP dimension is switched off).
     *
     * The 429 `Retry-After` header that 11 section 11 requires is NOT asserted: the real code sets only the body's `retryAfter` (open seam in evidence/E2E-12.md).
     */
    private fun l4Block(label: String, goods: Long, request: (Int) -> Pair<JsonObject, Map<String, String>>, expectedIp: String?) {
        val opened = ArrayList<OpenGuestOrder>()

        fun open(n: Int, name: String): E2eResponse {
            val (body, headers) = request(n)

            return checkout(visitor("v12-$label-$name"), body, method = "bank-transfer", headers = headers)
        }

        fun keep(answer: E2eResponse, checkIp: Boolean = true) {
            val publicId = publicIdOf(answer)

            opened += OpenGuestOrder(visitor("v12-cancel"), publicId, answer.obj().getString("orderToken"))
            // the control order differs in the counted dimension (for the IP block: its own address), so only the held-constant orders are checked
            if (checkIp) assertEquals(expectedIp, orderRow(publicId).getString("clientIp"), "$label: the stored address of the order")
        }

        try {
            for (n in 1..3) keep(open(n, "$n").ok())

            val fourth = open(4, "4")

            assertEquals(429, fourth.status, "L4 by $label: ${fourth.error}")
            assertEquals("TOO_MANY_REQUESTS", fourth.error)
            assertTrue(fourth.obj().getInteger("retryAfter") >= 1)

            // a subject that differs only in the counted dimension is not affected
            keep(open(0, "control").ok(), checkIp = false)

            // one is cancelled: the full subject may order again
            val first = opened.first()

            cancelOrder(first.client, first.publicId, first.token)
            keep(open(5, "5").ok())
        } finally {
            opened.forEach { cancelOrder(it.client, it.publicId, it.token) }
        }
    }

    /** True when the instance's config lists a loopback address under `server.trusted-proxies` (the file holds what the JVM booted with). */
    private fun loopbackIsTrustedProxy(): Boolean {
        val file = session.env.dir?.let { java.io.File(it, "config.conf") }?.takeIf { it.isFile } ?: return false
        val list = Regex("""(?m)^\s*trusted-proxies\s*=\s*\[([^\]]*)]""").find(file.readText())?.groupValues?.get(1) ?: return false

        return list.contains("\"127.0.0.1\"") || list.contains("\"::1\"")
    }

    @Test
    fun `V-12 a guest hoarding unpaid orders from one address is stopped by L4 on the IP dimension alone`() {
        // the address reaches market only through X-Forwarded-For of a trusted proxy (11 section 2): without server.trusted-proxies holding 127.0.0.1 the
        // IP dimension is off and cannot be proven over HTTP (the harness gap of evidence/E2E-04.md), so the test is skipped, never passed
        Assumptions.assumeTrue(loopbackIsTrustedProxy(), "the instance does not list 127.0.0.1 in server.trusted-proxies")

        bankTransferOn()
        try {
            val goods = product("5.00", stock = 100)
            val ip = "203.0.113.${(1..254).random()}"
            val otherIp = "198.51.100.${(1..254).random()}"

            // every other dimension differs per order: own payer name, own e-mail, no gift
            l4Block("ip", goods, { n -> guestBody(goods, guestName()) to mapOf("X-Forwarded-For" to if (n == 0) otherIp else ip) }, expectedIp = ip)
        } finally {
            bankTransferOff()
        }
    }

    @Test
    fun `V-12 a guest hoarding unpaid orders for one recipient is stopped by L4 and a bank-transfer notice does not extend the expiry`() {
        bankTransferOn()
        try {
            val goods = product("5.00", stock = 100)

            // L4 by recipient: three payer names and three e-mails (every guest has its own), one recipient. The loopback peer carries no trusted address (the
            // IP dimension is skipped, the stored clientIp is NULL), so only the recipient dimension can refuse the fourth order
            val recipient = buyer(canPay = false)
            val otherRecipient = buyer(canPay = false)

            l4Block("recipient", goods, { n ->
                guestBody(goods, guestName()).put("recipientUsername", if (n == 0) otherRecipient.username else recipient.username) to emptyMap()
            }, expectedIp = null)

            // L4 by e-mail: three payer names, one guest e-mail, no gift (nothing is counted on a recipient), IP skipped as above
            val sharedEmail = "v12-shared-${UUID.randomUUID().toString().take(8)}@example.com"

            l4Block("e-mail", goods, { n ->
                val name = guestName()
                val body = guestBody(goods, name)

                if (n != 0) body.getJsonObject("guest").put("email", sharedEmail)
                body to emptyMap()
            }, expectedIp = null)

            // a bank-transfer order with 50 units of a stock-limited product: line error MAX_QUANTITY
            val bulk = checkout(visitor("v12-bulk"), guestBody(goods, guestName(), quantity = 50), method = "bank-transfer")

            assertEquals(400, bulk.status)
            assertEquals("INVALID_CART", bulk.error)
            assertTrue(bulk.obj().getJsonObject("lineErrors").encode().contains("MAX_QUANTITY"), "line error MAX_QUANTITY: ${bulk.json?.encode()}")

            // the bank-transfer notice does not extend the order: one hour, the buyer notifies, 61 minutes later it is EXPIRED and the stock is back
            session.withSettings(JsonObject().put("bankTransferExpiryHours", 1)) {
                val limited = product("5.00", stock = 5)
                val buyer = buyer(canPay = false)
                val id = publicIdOf(checkout(buyer.client, cart(line(limited)), method = "bank-transfer").ok())
                val oid = orderId(id)

                assertEquals(4L, productStock(limited), "the stock is reserved")
                buyer.client.post("/api/market/orders/$id/bank-transfer/notify", JsonObject().put("senderName", "Ada").put("note", "paid")).ok()
                assertEquals("PENDING", orderStatus(id), "the order itself stays PENDING (the notice moves the attempt to PROCESSING)")

                db.rewind("market_order", oid, "expiresAt", 2 * 3_600_000L)
                db.sql("UPDATE `pano_market_payment` SET `expiresAt` = `expiresAt` - 7200000 WHERE `orderId` = ? AND `expiresAt` IS NOT NULL", oid)
                awaitOrder(id, "EXPIRED", 180_000)
                assertEquals(5L, productStock(limited), "the stock is back")
                assertEquals("EXPIRED", db.string("SELECT `status` FROM `pano_market_payment` WHERE `orderId` = ? ORDER BY `id` DESC LIMIT 1", oid), "the notice did not extend the attempt")

                // a later approval is a LATE payment: the order goes to review
                admin.post("/api/panel/market/orders/$oid/bank-transfer", JsonObject().put("decision", "APPROVE"))
                assertEquals("REVIEW", orderStatus(id))
                assertEquals("LATE", orderRow(id).getString("reviewReason"))
                reviewOrder(id, "REJECT", refund = false).ok()
            }
        } finally {
            bankTransferOff()
        }
    }

    // --- V-13 ----------------------------------------------------------------------------------------------------------

    @Test
    fun `V-13 a per-customer coupon limit is counted on the recipient, so a second guest gift to the same player is refused`() {
        val (couponId, code) = catalog.freshCoupon(10, customerRedeemLimit = 1)
        val recipient = buyer(canPay = false)
        val goods = product("10.00")

        bankTransferOn()
        try {
            val first = guestName()
            val one = checkout(visitor("v13-abc"), guestBody(goods, first).put("recipientUsername", recipient.username).put("couponCode", code), method = "bank-transfer").ok()

            assertEquals(1L, db.long("SELECT `usedCount` FROM `pano_market_coupon` WHERE `id` = ?", couponId))

            val second = checkout(visitor("v13-xyz"), guestBody(goods, guestName()).put("recipientUsername", recipient.username).put("couponCode", code), method = "bank-transfer")

            assertEquals(400, second.status)
            assertEquals("INVALID_COUPON", second.error)
            assertEquals("CODE_LIMIT_REACHED", second.obj().getString("reason"))
            assertEquals(1L, db.long("SELECT `usedCount` FROM `pano_market_coupon` WHERE `id` = ?", couponId), "the refused order consumed nothing")

            // a different recipient is fine: the limit is the customer's, not the payer's
            val other = buyer(canPay = false)
            val third = checkout(visitor("v13-other"), guestBody(goods, guestName()).put("recipientUsername", other.username).put("couponCode", code), method = "bank-transfer").ok()

            cancelOrder(visitor("v13-cancel"), publicIdOf(one), one.obj().getString("orderToken"))
            cancelOrder(visitor("v13-cancel2"), publicIdOf(third), third.obj().getString("orderToken"))
        } finally {
            bankTransferOff()
        }
    }

    // --- V-14 ----------------------------------------------------------------------------------------------------------

    private fun setStartKind(kind: String) {
        admin.post("/api/panel/market/payment-methods/fake", JsonObject().put("settings", JsonObject().put("startKind", kind))).ok()
    }

    @Test
    fun `V-14 a gateway Html start result is served from the attempt page in a sandbox without allow-same-origin and sets and needs no cookie`() {
        val buyer = buyer()

        setStartKind("HTML")
        try {
            val answer = checkout(buyer.client, cart(line(product("10.00")))).ok()
            val payment = answer.obj().getJsonObject("payment")
            val publicId = publicIdOf(answer)

            assertEquals("HTML", payment.getString("kind"))

            val url = payment.getString("url")

            assertTrue(url.startsWith("/api/market/payments/attempts/") && url.endsWith("/page"), "market serves the page itself: $url")

            // the same page for the buyer (with his session) and for a visitor (without any): it does not depend on a cookie
            val asBuyer = buyer.client.get(url)
            val asVisitor = visitor("v14-browser").get(url)

            for (page in listOf(asBuyer, asVisitor)) {
                assertEquals(200, page.status)

                val csp = page.header("Content-Security-Policy").orEmpty()

                assertTrue(csp.startsWith("sandbox allow-scripts allow-forms allow-top-navigation allow-popups"), "sandbox first: $csp")
                assertFalse(csp.contains("allow-same-origin"), "the document never runs with the site's origin, so a fetch from it carries no cookie: $csp")
                assertTrue(csp.contains("default-src 'none'"), csp)
                assertEquals("no-store", page.header("Cache-Control"))
                assertEquals("nosniff", page.header("X-Content-Type-Options"))
                assertEquals("no-referrer", page.header("Referrer-Policy"))
                assertNull(page.header("Set-Cookie"), "the attempt page sets no cookie")
                assertTrue(page.text.contains("Fake gateway payment"))
            }

            assertEquals(asBuyer.text, asVisitor.text, "the document is the same for everybody")

            // the order view hands the page url, never the gateway's markup, to the order page
            val view = order(buyer.client, publicId).getJsonObject("payment").getJsonObject("start")

            assertEquals("HTML", view.getString("kind"))
            assertFalse(view.encode().contains("<html"), "the html itself is not part of the order view")
            assertEquals(404, visitor("v14-unknown").get("/api/market/payments/attempts/${"0".repeat(32)}/page").status)
            cancelOrder(buyer.client, publicId)
        } finally {
            setStartKind("REDIRECT")
        }
    }

    // --- V-15 ----------------------------------------------------------------------------------------------------------

    @Test
    fun `V-15 a free-amount credit top-up is paid and posted, refused below the minimum, for a guest and with items, a gift credits the recipient and the server cart is untouched`() {
        admin.post("/api/panel/market/settings/credits", JsonObject().put("creditTopUpEnabled", true).put("creditTopUpFreeAmount", true)).ok()
        try {
            val alice = buyer()
            val bob = buyer()
            val filler = product("3.00")

            // the server cart holds an item that the top-up must leave alone
            alice.client.post("/api/market/me/cart/items", line(filler)).ok()

            val before = alice.client.get("/api/market/me/cart").ok().obj().getJsonObject("cart").getJsonArray("items").size()

            assertEquals(1, before)

            // paid: the TOPUP is posted to the buyer
            val publicId = publicIdOf(checkout(alice.client, JsonObject().put("creditTopUp", 20.0)).ok())

            payViaFake(publicId)
            awaitOrder(publicId, "COMPLETED")
            Await.until(30_000, 250, "the top-up arrives") { balance(alice.userId) == 2000L }
            assertEquals(1L, creditTx(orderId(publicId), "TOPUP"), "exactly one TOPUP row")
            assertEquals(1, alice.client.get("/api/market/me/cart").ok().obj().getJsonObject("cart").getJsonArray("items").size(), "the server cart is untouched")

            // below the minimum
            val below = checkout(alice.client, JsonObject().put("creditTopUp", 0.5))

            assertEquals(400, below.status)
            assertEquals("INVALID_CREDIT_AMOUNT", below.error)
            assertEquals("BELOW_MINIMUM", below.obj().getString("reason"))
            assertNotNull(below.obj().getValue("min"))
            assertNotNull(below.obj().getValue("max"))

            // a guest needs an account
            val guest = checkout(
                visitor("v15-guest"), JsonObject().put("creditTopUp", 20.0).put("guest", JsonObject().put("username", guestName()).put("email", "guest@example.com"))
            )

            assertEquals(401, guest.status)
            assertEquals("NOT_LOGGED_IN", guest.error)

            // items next to a top-up
            val withItems = checkout(alice.client, cart(line(filler)).put("creditTopUp", 20.0))

            assertEquals(400, withItems.status)
            assertEquals("BAD_REQUEST", withItems.error)

            // a gift top-up credits the recipient
            val gift = publicIdOf(checkout(alice.client, JsonObject().put("creditTopUp", 15.0).put("recipientUsername", bob.username)).ok())

            payViaFake(gift)
            awaitOrder(gift, "COMPLETED")
            Await.until(30_000, 250, "the gift top-up arrives") { balance(bob.userId) == 1500L }
            assertEquals(2000L, balance(alice.userId), "the payer's own balance did not change")
            assertEquals(bob.userId, db.long("SELECT `userId` FROM `pano_market_credit_tx` WHERE `orderId` = ? AND `type` = 'TOPUP'", orderId(gift)), "the TOPUP is on the recipient's account")
            assertEquals(1, alice.client.get("/api/market/me/cart").ok().obj().getJsonObject("cart").getJsonArray("items").size(), "the server cart is still untouched")
        } finally {
            admin.post("/api/panel/market/settings/credits", JsonObject().put("creditTopUpFreeAmount", false).put("creditTopUpEnabled", false))
        }
    }

    // --- V-16 ----------------------------------------------------------------------------------------------------------

    private fun widgets(): JsonObject =
        visitor("v16").get("/api/market/widgets?include=recentBuyers,topSupporters,goals,stats").ok().obj()

    @Test
    fun `V-16 widgets - stats is absent while moduleStats is off, sidebars echoes moduleSidebars and an unknown sidebar id is refused on save`() {
        session.withSettings(JsonObject().put("moduleStats", false).put("moduleSidebars", JsonArray().add("home"))) {
            val off = widgets()

            assertFalse(off.containsKey("stats"), "stats is absent while the module is off: ${off.fieldNames()}")
            assertEquals(listOf("home"), off.getJsonArray("sidebars").map { it.toString() })
        }

        session.withSettings(JsonObject().put("moduleStats", true).put("moduleSidebars", JsonArray().add("home").add("profile"))) {
            val on = widgets()

            assertTrue(on.containsKey("stats"), "stats is present once the module is on")
            assertEquals(listOf("home", "profile"), on.getJsonArray("sidebars").map { it.toString() }, "sidebars echoes moduleSidebars")
            assertNotNull(on.getJsonObject("stats").getValue("ordersTotal"))
        }

        // an unknown host sidebar id is refused on save and nothing changes
        val before = admin.get("/api/panel/market/settings").ok().obj().let { it.getJsonObject("settings") ?: it }.getJsonArray("moduleSidebars")
        val refused = admin.post("/api/panel/market/settings", JsonObject().put("moduleSidebars", JsonArray().add("home").add("nowhere")))

        assertEquals(400, refused.status)
        assertEquals("INVALID_SETTINGS", refused.error)
        assertTrue(refused.obj().getJsonObject("fieldErrors").containsKey("moduleSidebars"), refused.json?.encode() ?: "")
        assertEquals(before, admin.get("/api/panel/market/settings").ok().obj().let { it.getJsonObject("settings") ?: it }.getJsonArray("moduleSidebars"), "the refused save changed nothing")
    }

    // --- V-17 ----------------------------------------------------------------------------------------------------------

    private fun <R : PlatformMessageResponse> ask(server: FakeMcServer, request: MarketRequest, type: Class<R>): R {
        val answer = CompletableFuture<R?>()

        server.link.request(request, type) { answer.complete(it) }

        return answer.get(40, TimeUnit.SECONDS) ?: throw AssertionError("${request.javaClass.simpleName} got no answer")
    }

    private fun adminOp(server: FakeMcServer, actor: AdminActor, target: String, amount: Double, operationId: String = UUID.randomUUID().toString()) =
        ask(server, MarketAdminRequest(server.componentVersion, operationId = operationId, op = AdminOp.GIVE_CREDITS, actor = actor, target = AdminTarget(target), amount = amount, productId = null, quantity = null, note = "e2e v17"), MarketAdminMessage::class.java)

    @Test
    fun `V-17 a FakeMcServer - a COMMAND delivery to CONFIRMED behind a version mismatch, an in-game credit purchase, an admin command with and without the linked permission`() {
        // a component of an old version is refused, the sale goes on and the row waits
        val server = fakeServer(awaitReady = false, version = "0.0.1-v17-old")
        val required = server.requiredVersion()

        assertNotEquals(required, "0.0.1-v17-old")
        Await.until(60_000, 200, "marketState is VERSION_MISMATCH") { server.view().getString("marketState") == "VERSION_MISMATCH" }

        val buyer = buyer()
        val commandId = commandProduct(server)
        val (publicId, orderId) = payOrder(buyer.client, cart(line(commandId)))

        Await.untilValue(120_000, 200, "the delivery waits for the server") { deliveryRows(orderId, "GRANT").singleOrNull()?.takeIf { it.getString("status") == "WAITING_SERVER" } }
        assertEquals(emptyList<String>(), server.platform.console, "nothing was handed to a component that Pano refused")

        // the plugin is updated: the version Pano asks for, the row is delivered and confirmed
        server.restart(required)
        Await.until(60_000, 200, "marketState is READY") { server.view().getString("marketState") == "READY" }
        awaitDeliveries(orderId, "GRANT")
        assertEquals("CONFIRMED", deliveryRows(orderId, "GRANT").single().getString("status"))
        Await.until(60_000, 200, "the order is FULFILLED") { orderRow(publicId).getString("fulfillmentStatus") == "FULFILLED" }
        assertEquals(listOf("give ${buyer.username} diamond"), server.platform.console)

        // an in-game purchase with credits: debited, delivered, a replay is the same order
        val priced = product("2.00", creditPrice = "4.00", actions = JsonArray().add(
            action("a1", "COMMAND", JsonArray().add("give {username} emerald"), "GRANT").put("serverMode", "FIXED").put("targetServers", JsonArray().add(server.serverId)).put("requiresOnline", false)
        ).encode())

        grant(buyer.userId, 10)

        val operationId = UUID.randomUUID().toString()
        val request = MarketPurchaseRequest(server.componentVersion, operationId = operationId, player = PlayerRef(buyer.username, null), productId = priced, quantity = 1, confirmLegalTextId = null)
        val bought = ask(server, request, MarketPurchaseMessage::class.java)

        assertTrue(bought.accepted, bought.reason)
        assertEquals(true, bought.ok, bought.code)
        assertEquals(6.0, bought.balance!!, 0.0001)
        assertEquals(600L, balance(buyer.userId))
        assertEquals("INGAME", orderRow(bought.orderPublicId!!).getString("source"))

        server.component.runtime.syncSoon()
        Await.until(120_000, 200, "the purchased item is delivered") { server.platform.console.contains("give ${buyer.username} emerald") }

        val replay = ask(server, request, MarketPurchaseMessage::class.java)

        assertEquals(bought.orderPublicId, replay.orderPublicId)
        assertEquals(600L, balance(buyer.userId), "a replay debits nothing")

        // an admin command: without the linked Pano permission nothing is posted, with it the credits arrive
        val target = buyer()
        val plain = buyer(canPay = false)
        val before = balance(target.userId)
        val refused = adminOp(server, AdminActor(false, plain.username, null), target.username, 25.0)

        assertEquals(false, refused.ok)
        assertEquals("NO_PERMISSION", refused.code)
        assertEquals(before, balance(target.userId))

        val allowed = adminOp(server, AdminActor(false, buyer().username, null), target.username, 7.5)

        assertEquals(true, allowed.ok, allowed.code)
        assertEquals(before + 750L, balance(target.userId))
    }
}
