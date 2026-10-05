package com.panomc.plugins.market.core.webhook

import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** JSON bodies of 08 section 15.4. */
class EventPayloadsTest {
    private val store = StoreInfo("Test Craft", "https://shop.example.com/")

    private val order = MarketOrder(
        id = 42, userId = 5, playerUsername = "Steve", recipientUsername = "Alex", recipientUserId = 6, email = "steve@example.com",
        publicId = "ABCDEFGHJKMNPQRSTVWX", status = OrderStatus.COMPLETED, source = OrderSource.STOREFRONT, currency = "EUR",
        subtotal = 1000, discountTotal = 100, couponDiscount = 100, vatTotal = 150, totalPrice = 900, creditAmount = 200, gatewayAmount = 700,
        paymentMethodId = "bank", paymentLabel = "Bank", couponCode = "SAVE10", isGift = true, giftMessage = "gg", paidAt = 1_760_000_000_500L,
        createdAt = 1_760_000_000_000L, clientIp = "203.0.113.5", accessToken = "secret-token", billingInfo = """{"name":"X"}""", shippingAddress = """{"street":"Y"}"""
    )

    private val item = MarketOrderItem(
        id = 9, orderId = 42, productId = 3, productName = "VIP", quantity = 2, unitPrice = 450, lineTotal = 900, kind = OrderItemKind.PRODUCT,
        variantName = "Gold", sku = "VIP-G", fieldValues = """{"7":"red"}""", targetServerId = 2, snapshot = """{"slug":"vip","name":"VIP"}"""
    )

    @Test
    fun `the envelope carries the fields of 15_4`() {
        val e = EventPayloads.envelope("evt-1", "order.paid", 1_760_000_001_000L, true, store, JsonObject().put("k", 1))
        assertEquals("evt-1", e.getString("id"))
        assertEquals("order.paid", e.getString("event"))
        assertEquals(1_760_000_001_000L, e.getLong("createdAt"))
        assertEquals(1, e.getInteger("apiVersion"))
        assertTrue(e.getBoolean("testMode"))
        assertEquals("Test Craft", e.getJsonObject("store").getString("name"))
        assertEquals("https://shop.example.com/", e.getJsonObject("store").getString("url"))
        assertEquals(1, e.getJsonObject("data").getInteger("k"))
    }

    @Test
    fun `order paid has order, buyer, recipient and items with decimal money`() {
        val data = EventPayloads.orderPaid(order, listOf(item), store, buyerUuid = "uuid-b", recipientUuid = "uuid-r", serverNames = mapOf(2L to "Survival"))
        val o = data.getJsonObject("order")
        assertEquals(42, o.getLong("id"))
        assertEquals("https://shop.example.com/store/order/ABCDEFGHJKMNPQRSTVWX", o.getString("url"))
        assertEquals("COMPLETED", o.getString("status"))
        assertEquals("STOREFRONT", o.getString("source"))
        assertEquals(0, BigDecimal("9.00").compareTo(BigDecimal(o.getValue("total").toString())))
        assertEquals(0, BigDecimal("2.00").compareTo(BigDecimal(o.getValue("creditAmount").toString())))
        assertEquals("SAVE10", o.getString("couponCode"))
        assertTrue(o.getBoolean("isGift"))
        assertNull(o.getValue("creatorCode"))

        val b = data.getJsonObject("buyer")
        assertEquals("Steve", b.getString("username"))
        assertEquals(5, b.getLong("userId"))
        assertEquals("uuid-b", b.getString("uuid"))
        assertEquals("steve@example.com", b.getString("email"))

        val r = data.getJsonObject("recipient")
        assertEquals("Alex", r.getString("username"))
        assertEquals(6, r.getLong("userId"))
        assertEquals("uuid-r", r.getString("uuid"))
        assertFalse(r.containsKey("email"))

        val i = data.getJsonArray("items").getJsonObject(0)
        assertEquals("VIP", i.getString("productName"))
        assertEquals("vip", i.getString("slug"))
        assertEquals("PRODUCT", i.getString("kind"))
        assertEquals(2, i.getInteger("quantity"))
        assertEquals("red", i.getJsonObject("fieldValues").getString("7"))
        assertEquals("Survival", i.getString("targetServerName"))
        assertNull(i.getValue("expiresAt"))
    }

    @Test
    fun `money is exact and encoded as a json number`() {
        val body = EventPayloads.orderPaid(order, listOf(item), store).encode()
        assertTrue(body.contains(""""total":9.00"""), body)
        assertTrue(body.contains(""""unitPrice":4.50"""), body)
        assertEquals(BigDecimal("0.07"), EventPayloads.money(7))
        assertEquals(BigDecimal("123456789.01"), EventPayloads.money(12_345_678_901))
    }

    @Test
    fun `billing data, addresses, ip and tokens are never included`() {
        val body = EventPayloads.orderPaid(order, listOf(item), store).encode()
        for (secret in listOf("203.0.113.5", "secret-token", "street", "billing", "accessToken", "clientIp", "shippingAddress")) {
            assertFalse(body.contains(secret), secret)
        }
    }

    @Test
    fun `a recipient that is not stored falls back to the buyer`() {
        val self = MarketOrder(id = 1, userId = 5, playerUsername = "Steve", publicId = "P")
        val r = EventPayloads.recipient(self, null)
        assertEquals("Steve", r.getString("username"))
        assertEquals(5, r.getLong("userId"))
    }

    @Test
    fun `bundle children keep their parent and entitlement ends are passed through`() {
        val child = MarketOrderItem(id = 10, orderId = 42, productName = "Kit", kind = OrderItemKind.BUNDLE_CHILD, parentItemId = 9, snapshot = "not json")
        val items = EventPayloads.items(listOf(item, child), expiresAt = mapOf(9L to 1_770_000_000_000L))
        assertEquals(9, items.getJsonObject(1).getLong("parentItemId"))
        assertEquals("BUNDLE_CHILD", items.getJsonObject(1).getString("kind"))
        assertNull(items.getJsonObject(1).getValue("slug"))
        assertEquals(1_770_000_000_000L, items.getJsonObject(0).getLong("expiresAt"))
    }

    @Test
    fun `test ping data and the store url join`() {
        val d = EventPayloads.testPing(7)
        assertEquals("ping", d.getString("message"))
        assertEquals(7, d.getLong("endpointId"))
        assertNull(EventPayloads.orderUrl(store, null))
        assertEquals("https://shop.example.com/store/order/X", EventPayloads.orderUrl(StoreInfo("a", "https://shop.example.com"), "X"))
    }
}
