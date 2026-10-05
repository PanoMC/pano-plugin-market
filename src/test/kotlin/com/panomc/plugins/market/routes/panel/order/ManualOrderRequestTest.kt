package com.panomc.plugins.market.routes.panel.order

import com.panomc.plugins.market.error.InvalidCart
import com.panomc.plugins.market.error.RequestValueException
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The body of `POST /api/panel/market/orders` and `/orders/quote` (04 section 7, 11 section 4.1 PT-1, PT-4): what is read, what is refused. */
class ManualOrderRequestTest {
    private fun body(vararg pairs: Pair<String, Any?>): JsonObject {
        val o = JsonObject().put("playerUsername", "Steve").put("items", JsonArray().add(JsonObject().put("productId", 5).put("quantity", 2)))

        for ((k, v) in pairs) if (v == null) o.remove(k) else o.put(k, v)

        return o
    }

    @Test
    fun `a minimal body takes the defaults of the contract`() {
        val r = parseManualOrderRequest(body(), "key-0000000000000001", "hash", "tr")

        assertEquals("Steve", r.playerUsername)
        assertEquals(1, r.items.size)
        assertEquals(5L, r.items.single().productId)
        assertEquals(2, r.items.single().quantity)
        assertFalse(r.markPaid)
        assertTrue(r.runDeliveries)
        assertTrue(r.sendMail)
        assertFalse(r.force)
        assertNull(r.priceOverride)
        assertNull(r.shippingPrice)
        assertEquals("key-0000000000000001", r.idempotencyKey)
        assertEquals("hash", r.bodyHash)
        assertEquals("tr", r.orderLocale)
    }

    @Test
    fun `every field of the contract is read`() {
        val r = parseManualOrderRequest(
            body(
                "recipientUsername" to "Alex", "email" to "a@b.co", "priceOverride" to 12.5, "markPaid" to true, "paymentLabel" to "Cash", "runDeliveries" to false,
                "sendMail" to false, "note" to "n", "force" to true, "shippingAddress" to JsonObject().put("country", "TR"), "shippingMethodId" to 3, "shippingPrice" to 4
            )
        )

        assertEquals("Alex", r.recipientUsername)
        assertEquals("a@b.co", r.email)
        assertEquals(1250L, r.priceOverride, "money x 100 from the number's text")
        assertTrue(r.markPaid)
        assertEquals("Cash", r.paymentLabel)
        assertFalse(r.runDeliveries)
        assertFalse(r.sendMail)
        assertEquals("n", r.note)
        assertTrue(r.force)
        assertEquals("TR", r.shippingAddress!!.getString("country"))
        assertEquals(3L, r.shippingMethodId)
        assertEquals(400L, r.shippingPrice)
    }

    @Test
    fun `an unknown key, a missing required key and a value of the wrong type are refused`() {
        assertThrows(RequestValueException::class.java) { parseManualOrderRequest(body("couponCode" to "X")) }
        assertThrows(RequestValueException::class.java) { parseManualOrderRequest(body("playerUsername" to null)) }
        assertThrows(RequestValueException::class.java) { parseManualOrderRequest(body("items" to null)) }
        assertThrows(RequestValueException::class.java) { parseManualOrderRequest(body("items" to "x")) }
        assertThrows(RequestValueException::class.java) { parseManualOrderRequest(body("markPaid" to "yes")) }
        assertThrows(RequestValueException::class.java) { parseManualOrderRequest(body("force" to 1)) }
        assertThrows(RequestValueException::class.java) { parseManualOrderRequest(body("note" to 5)) }
        assertThrows(RequestValueException::class.java) { parseManualOrderRequest(body("shippingAddress" to "x")) }
        assertThrows(RequestValueException::class.java) { parseManualOrderRequest(body("playerUsername" to "x".repeat(65))) }
    }

    @Test
    fun `an amount has at most two decimals and is never negative`() {
        for (bad in listOf<Any>(-1, 1.005, "5", 1e30)) {
            assertThrows(RequestValueException::class.java, { parseManualOrderRequest(body("priceOverride" to bad)) }, "$bad")
            assertThrows(RequestValueException::class.java, { parseManualOrderRequest(body("shippingPrice" to bad)) }, "$bad")
        }

        assertEquals(0L, parseManualOrderRequest(body("priceOverride" to 0)).priceOverride)
        assertEquals(1999L, parseManualOrderRequest(body("priceOverride" to 19.99)).priceOverride)
    }

    @Test
    fun `a quantity outside 1 to 999 or not integral is INVALID_CART naming the line`() {
        for (quantity in listOf<Any?>(0, 1000, -1, 1.5, "2", null)) {
            val items = JsonArray().add(JsonObject().put("productId", 5).put("quantity", 1)).add(JsonObject().put("productId", 6).put("quantity", quantity))
            val e = assertThrows(InvalidCart::class.java, { parseManualOrderRequest(body("items" to items)) }, "$quantity")

            assertTrue(e.encode().contains("items[1]"), e.encode())
        }

        assertEquals(999, parseManualOrderRequest(body("items" to JsonArray().add(JsonObject().put("productId", 5).put("quantity", 999)))).items.single().quantity)
    }

    @Test
    fun `an empty items array parses, the service answers EMPTY_CART`() {
        assertTrue(parseManualOrderRequest(body("items" to JsonArray())).items.isEmpty())
    }
}
