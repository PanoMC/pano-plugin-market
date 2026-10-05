package com.panomc.plugins.market.core.catalog

import com.panomc.plugins.market.error.RequestValueException
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StockRequestTest {
    private fun refused(field: String, body: JsonObject) {
        assertEquals(field, assertThrows(RequestValueException::class.java) { StockRequest.parse(body) }.field)
    }

    @Test
    fun `SET and ADJUST parse with their value and an optional variant`() {
        val set = StockRequest.parse(JsonObject().put("mode", "SET").put("value", 5))
        assertEquals(StockMode.SET, set.mode)
        assertEquals(5, set.value)
        assertNull(set.variantId)

        val adjust = StockRequest.parse(JsonObject().put("mode", "ADJUST").put("value", -3).put("variantId", 9))
        assertEquals(StockMode.ADJUST, adjust.mode)
        assertEquals(-3, adjust.value)
        assertEquals(9L, adjust.variantId)
    }

    @Test
    fun `null means unlimited for SET and integral doubles are accepted`() {
        assertNull(StockRequest.parse(JsonObject().put("mode", "SET").putNull("value")).value)
        assertEquals(4, StockRequest.parse(JsonObject().put("mode", "SET").put("value", 4.0)).value)
    }

    @Test
    fun `a missing mode or value, an unknown mode and a non-integer are refused`() {
        refused("mode", JsonObject().put("value", 1))
        refused("mode", JsonObject().put("mode", "ADD").put("value", 1))
        refused("value", JsonObject().put("mode", "SET"))
        refused("value", JsonObject().put("mode", "SET").put("value", 1.5))
        refused("value", JsonObject().put("mode", "SET").put("value", "7"))
        refused("value", JsonObject().put("mode", "SET").put("value", 3_000_000_000L))
        refused("variantId", JsonObject().put("mode", "SET").put("value", 1).put("variantId", 1.5))
        refused("variantId", JsonObject().put("mode", "SET").put("value", 1).put("variantId", 0))
    }
}
