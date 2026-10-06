package com.panomc.plugins.market.routes.panel.refund

import com.panomc.plugins.market.core.refund.RefundMath
import com.panomc.plugins.market.error.RequestValueException
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The wire format of the refund request and the refund preview query (04 section 7): money is a decimal with at most two places, credits likewise. */
class RefundRequestParsingTest {
    @Test
    fun `money is a number or a numeric text with at most two decimals and becomes minor units`() {
        assertEquals(400L, parseMoney(4, "amount"))
        assertEquals(450L, parseMoney(4.5, "amount"))
        assertEquals(1999L, parseMoney("19.99", "amount"))
        assertEquals(1000L, parseMoney(" 10 ", "amount"))
        assertEquals(10L, parseMoney(0.1, "amount"), "no floating point noise")
        assertEquals(-100L, parseMoney(-1, "amount"), "sign checks belong to the split, which answers INVALID_REFUND_AMOUNT")
    }

    @Test
    fun `a value that is not a number or has a third decimal is a 400 naming the field`() {
        for (bad in listOf<Any?>("abc", "", true, JsonObject(), Double.NaN, Double.POSITIVE_INFINITY, "1.005", 0.001)) {
            val e = assertThrows(RequestValueException::class.java) { parseMoney(bad, "gatewayAmount") }

            assertEquals("gatewayAmount", e.field)
        }

        assertThrows(RequestValueException::class.java) { parseMoney("99999999999999999999", "amount") }
    }

    @Test
    fun `the body maps to the input and unset fields stay unset`() {
        val body = JsonObject()
            .put("items", JsonArray().add(JsonObject().put("orderItemId", 7).put("quantity", 2)))
            .put("reason", "  duplicate order ")
            .put("revoke", false)
            .put("revokeFirst", true)
            .put("cascadeUpgrade", true)
            .put("restock", true)
            .put("manual", true)
            .put("gatewayAmount", "5.50")
            .put("creditAmount", 2)
        val input = parseRefundInput { body.getValue(it) }

        assertNull(input.amount)
        assertEquals(listOf(RefundMath.ItemRequest(7, 2)), input.items)
        assertEquals("duplicate order", input.reason)
        assertEquals(false, input.revoke)
        assertTrue(input.revokeFirst && input.restock && input.manual)
        assertEquals(true, input.cascadeUpgrade)
        assertEquals(550L, input.gatewayAmount)
        assertEquals(200L, input.creditAmount)

        val empty = parseRefundInput { null }

        assertNull(empty.amount)
        assertNull(empty.items)
        assertNull(empty.revoke)
        assertNull(empty.cascadeUpgrade)
        assertFalse(empty.revokeFirst || empty.restock || empty.manual)
    }

    @Test
    fun `the preview query carries the same fields as text`() {
        val query = mapOf("amount" to "15.00", "items" to """[{"orderItemId":3,"quantity":1}]""", "manual" to "true", "revoke" to "false")
        val input = parseRefundInput { query[it] }

        assertEquals(1500L, input.amount)
        assertEquals(listOf(RefundMath.ItemRequest(3, 1)), input.items)
        assertTrue(input.manual)
        assertEquals(false, input.revoke)
    }

    @Test
    fun `malformed items and flags are 400s`() {
        assertThrows(RequestValueException::class.java) { parseRefundInput { if (it == "items") "not json" else null } }
        assertThrows(RequestValueException::class.java) { parseRefundInput { if (it == "items") JsonArray().add("x") else null } }
        assertThrows(RequestValueException::class.java) { parseRefundInput { if (it == "items") JsonArray().add(JsonObject().put("orderItemId", 1)) else null } }
        assertThrows(RequestValueException::class.java) { parseRefundInput { if (it == "manual") "maybe" else null } }
        assertThrows(RequestValueException::class.java) { parseRefundInput { if (it == "revoke") 1 else null } }
    }

    @Test
    fun `the same request has the same hash and any change of the body changes it`() {
        val base = com.panomc.plugins.market.service.RefundInput(amount = 300, reason = "a")
        val same = com.panomc.plugins.market.service.RefundInput(amount = 300, reason = " a ")

        assertEquals(base.hash(1), same.hash(1))
        assertTrue(base.hash(1) != base.hash(2), "the order is part of the request")
        assertTrue(base.hash(1) != com.panomc.plugins.market.service.RefundInput(amount = 301, reason = "a").hash(1))
        assertTrue(base.hash(1) != com.panomc.plugins.market.service.RefundInput(amount = 300, reason = "a", manual = true).hash(1))
        assertEquals(64, base.hash(1).length)
    }
}
