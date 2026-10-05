package com.panomc.plugins.market.core

import com.panomc.plugins.market.routes.api.OrderAccessFacts
import com.panomc.plugins.market.routes.api.OrderAccessRules
import com.panomc.plugins.market.routes.api.OrderRole
import com.panomc.plugins.market.routes.api.OrderViews
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `OrderAccess` (11 section 5.1 and 5.2, 06 section 10.3), the pure part: the shape of a public id, the token (constant-time compare, 90 days,
 * header always, query on `GET` only), the role of a caller, and the allow-list of the `OrderView` per role. The database half (the lookup, the
 * limiter L6) is proven in `PaymentServiceIT`.
 */
class OrderAccessTest {
    private val day = 24L * 60 * 60 * 1000
    private val token = "a1".repeat(20)
    private val facts = OrderAccessFacts(userId = 7, recipientUserId = 9, accessToken = token, createdAt = 1_000_000L)

    // ----------------------------------------------------------------------------------------------- the public id

    @Test
    fun `a public id is 20 Crockford base32 characters and nothing else`() {
        assertTrue(OrderAccessRules.isPublicId("0123456789ABCDEFGHJK"))
        assertTrue(OrderAccessRules.isPublicId("MNPQRSTVWXYZ01234567"))

        val bad = listOf(
            null, "", "0123456789ABCDEFGHJ", "0123456789ABCDEFGHJKM", "0123456789abcdefghjk", "0123456789ABCDEFGHIJ", "0123456789ABCDEFGHLJ",
            "0123456789ABCDEFGHOJ", "0123456789ABCDEFGHUJ", "0123456789ABCDEFGHJ ", " 123456789ABCDEFGHJK", "0123456789ABCDEFGH'--", "../../../etc/passwd0000",
            "0123456789ABCDEFGHJK\n", "1".repeat(1000)
        )

        for (candidate in bad) assertFalse(OrderAccessRules.isPublicId(candidate), "refused: $candidate")
    }

    // --------------------------------------------------------------------------------------------------- the token

    @Test
    fun `the token comes from the header, and from the query only on a GET`() {
        assertEquals("h", OrderAccessRules.presentedToken(isGet = true, header = "h", query = "q"), "the header wins")
        assertEquals("h", OrderAccessRules.presentedToken(isGet = false, header = "h", query = "q"))
        assertEquals("q", OrderAccessRules.presentedToken(isGet = true, header = null, query = "q"), "a mail link on a GET")
        assertNull(OrderAccessRules.presentedToken(isGet = false, header = null, query = "q"), "a mutating call never reads the query")
        assertNull(OrderAccessRules.presentedToken(isGet = true, header = "", query = ""), "a blank value is no token")
        assertEquals("q", OrderAccessRules.presentedToken(isGet = true, header = "", query = "q"))
    }

    @Test
    fun `a token is valid only when it equals the stored one, the order has one and it is younger than 90 days`() {
        val now = facts.createdAt + day

        assertTrue(OrderAccessRules.tokenValid(facts, token, now))
        assertFalse(OrderAccessRules.tokenValid(facts, null, now))
        assertFalse(OrderAccessRules.tokenValid(facts, "", now))
        assertFalse(OrderAccessRules.tokenValid(facts, token.dropLast(1), now), "a prefix")
        assertFalse(OrderAccessRules.tokenValid(facts, token + "0", now), "an extension")
        assertFalse(OrderAccessRules.tokenValid(facts, token.uppercase(), now), "case matters")
        assertFalse(OrderAccessRules.tokenValid(facts, "b2".repeat(20), now), "same length, other value")
        assertFalse(OrderAccessRules.tokenValid(OrderAccessFacts(7, 9, null, facts.createdAt), "anything", now), "an order without a token has no token access")
        assertFalse(OrderAccessRules.tokenValid(OrderAccessFacts(7, 9, "", facts.createdAt), "", now), "an empty stored token is none")
    }

    @Test
    fun `the token expires 90 days after the order was created, to the millisecond`() {
        val limit = facts.createdAt + 90 * day

        assertEquals(90 * day, OrderAccessRules.TOKEN_TTL_MS)
        assertTrue(OrderAccessRules.tokenValid(facts, token, facts.createdAt))
        assertTrue(OrderAccessRules.tokenValid(facts, token, limit - 1), "the last valid millisecond")
        assertFalse(OrderAccessRules.tokenValid(facts, token, limit), "the day it expires is already over")
        assertFalse(OrderAccessRules.tokenValid(facts, token, limit + 1))
    }

    // ---------------------------------------------------------------------------------------------------- the role

    @Test
    fun `the payer's session is the owner, so is a valid token, the recipient's session is the recipient, everyone else is limited`() {
        assertEquals(OrderRole.OWNER, OrderAccessRules.roleOf(facts, sessionUserId = 7, tokenOk = false))
        assertEquals(OrderRole.OWNER, OrderAccessRules.roleOf(facts, sessionUserId = null, tokenOk = true), "a guest with the token")
        assertEquals(OrderRole.OWNER, OrderAccessRules.roleOf(facts, sessionUserId = 123, tokenOk = true), "another user's session with the token")
        assertEquals(OrderRole.RECIPIENT, OrderAccessRules.roleOf(facts, sessionUserId = 9, tokenOk = false))
        assertEquals(OrderRole.OWNER, OrderAccessRules.roleOf(facts, sessionUserId = 9, tokenOk = true), "the recipient who also holds the token")
        assertEquals(OrderRole.LIMITED, OrderAccessRules.roleOf(facts, sessionUserId = 123, tokenOk = false))
        assertEquals(OrderRole.LIMITED, OrderAccessRules.roleOf(facts, sessionUserId = null, tokenOk = false))
    }

    @Test
    fun `a guest order has no user, so no session is its owner`() {
        val guest = OrderAccessFacts(userId = null, recipientUserId = null, accessToken = token, createdAt = 0)

        assertEquals(OrderRole.LIMITED, OrderAccessRules.roleOf(guest, sessionUserId = 7, tokenOk = false), "null never equals a session user")
        assertEquals(OrderRole.LIMITED, OrderAccessRules.roleOf(guest, sessionUserId = null, tokenOk = false))
        assertEquals(OrderRole.OWNER, OrderAccessRules.roleOf(guest, sessionUserId = null, tokenOk = true))
    }

    // ------------------------------------------------------------------------------------------------ the views

    private fun ownerView(): JsonObject = JsonObject()
        .put("publicId", "0123456789ABCDEFGHJK").put("number", 42).put("status", "PENDING").put("fulfillmentStatus", "NONE").put("shippingStatus", "NOT_REQUIRED")
        .put("limited", false).put("createdAt", 1L).put("paidAt", null as Long?).put("expiresAt", 99L).put("currency", "EUR").put("testMode", false)
        .put("totals", JsonObject().put("subtotal", 10.0).put("total", 12.0).put("creditAmount", 1.0))
        .put(
            "items",
            JsonArray().add(
                JsonObject().put("id", 5).put("productId", 6).put("name", "VIP").put("variantName", null as String?).put("imageFileName", "a.png").put("quantity", 2)
                    .put("unitPrice", 6.0).put("lineTotal", 12.0).put("fieldValues", JsonObject().put("nick", "Steve")).put("targetServerName", "lobby")
                    .put("delivery", "PENDING").put("expiresAt", 5L)
            )
        )
        .put("recipientUsername", "Alex").put("isGift", true)
        .put("payment", JsonObject().put("methodId", "fake").put("start", JsonObject().put("kind", "REDIRECT").put("url", "https://gateway.invalid/x")))
        .put("paymentMethods", JsonArray().add(JsonObject().put("id", "fake"))).put("credits", JsonObject().put("balance", 1.0))
        .put("shipping", JsonObject().put("methodName", "Standard")).put("shipments", JsonArray().add(JsonObject().put("status", "SHIPPED").put("shippedAt", 3L).put("deliveredAt", null as Long?).put("trackingNumber", "TRK1").put("trackingUrl", "https://t.example/TRK1")))
        .put("shippingAddress", JsonObject().put("line1", "Street 1")).put("billingInfo", JsonObject().put("taxNumber", "tax-777")).put("email", "a@b.c")
        .put("invoiceAvailable", true).put("canCancel", true).put("canRetryPayment", true).put("refundPending", false).put("buyerActionUrl", "https://x.example")

    @Test
    fun `the owner sees the whole view`() {
        val view = OrderViews.forRole(ownerView(), OrderRole.OWNER)

        assertFalse(view.getBoolean("limited"))
        assertEquals(42, view.getInteger("number"))
        assertTrue(view.containsKey("payment") && view.containsKey("paymentMethods") && view.containsKey("shippingAddress") && view.containsKey("email"))
        assertEquals("TRK1", view.getJsonArray("shipments").getJsonObject(0).getString("trackingNumber"))
    }

    @Test
    fun `the recipient sees the allow-list of 11 section 5-2 and nothing of the payer's money or address`() {
        val view = OrderViews.forRole(ownerView(), OrderRole.RECIPIENT)

        assertTrue(view.getBoolean("limited"))
        assertNull(view.getValue("number"))
        assertNull(view.getValue("totals"), "no totals for the recipient")
        assertEquals("Alex", view.getString("recipientUsername"))
        assertEquals("PENDING", view.getString("status"))
        assertTrue(view.getBoolean("isGift"))

        val item = view.getJsonArray("items").getJsonObject(0)

        assertEquals(setOf("name", "variantName", "imageFileName", "quantity", "delivery", "id", "productId", "fieldValues", "targetServerName", "expiresAt"), item.fieldNames())
        assertFalse(item.containsKey("unitPrice") || item.containsKey("lineTotal"))

        for (key in listOf("expiresAt", "canCancel", "canRetryPayment", "refundPending", "buyerActionUrl", "invoiceAvailable", "payment", "paymentMethods", "credits", "shippingAddress", "billingInfo", "email", "shipping")) {
            assertFalse(view.containsKey(key), "$key is the owner's")
        }

        val shipment = view.getJsonArray("shipments").getJsonObject(0)

        assertEquals(setOf("status", "shippedAt", "deliveredAt"), shipment.fieldNames(), "no tracking number and no tracking URL")
    }

    @Test
    fun `a limited view has the order total only, no names, no field values and no ids`() {
        val view = OrderViews.forRole(ownerView(), OrderRole.LIMITED)

        assertTrue(view.getBoolean("limited"))
        assertNull(view.getValue("number"))
        assertNull(view.getValue("recipientUsername"), "the recipient's name is not a stranger's business")
        assertEquals(setOf("total"), view.getJsonObject("totals").fieldNames())
        assertEquals(12.0, view.getJsonObject("totals").getDouble("total"))
        assertEquals(setOf("name", "variantName", "imageFileName", "quantity", "delivery"), view.getJsonArray("items").getJsonObject(0).fieldNames())

        val everything = view.encode()

        for (secret in listOf("Steve", "lobby", "Street 1", "a@b.c", "tax-777", "gateway.invalid", "TRK1", "t.example", "x.example")) {
            assertFalse(everything.contains(secret), "'$secret' must not leave in a limited view")
        }
    }

    @Test
    fun `cutting the view never changes the owner's object`() {
        val owner = ownerView()
        val before = owner.encode()

        OrderViews.forRole(owner, OrderRole.LIMITED)
        OrderViews.forRole(owner, OrderRole.RECIPIENT)
        OrderViews.forRole(owner, OrderRole.OWNER).put("extra", 1)

        assertEquals(before, owner.encode())
    }
}
