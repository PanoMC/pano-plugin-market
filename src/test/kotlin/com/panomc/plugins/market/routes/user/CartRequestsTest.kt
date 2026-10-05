package com.panomc.plugins.market.routes.user

import com.panomc.plugins.market.core.cart.CartLineKey
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.routes.user.cart.parseItemPatch
import com.panomc.plugins.market.routes.user.cart.parseMerge
import com.panomc.plugins.market.routes.user.cart.parseReplacement
import com.panomc.plugins.market.routes.user.cart.parseStrictLine
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The pure request parsing of the buyer cart routes (06 section 2.1: structural limits answer 400). */
class CartRequestsTest {
    private fun refused(field: String, block: () -> Unit) {
        val e = assertThrows(RequestValueException::class.java) { block() }

        assertEquals(field, e.field)
    }

    @Test
    fun `a line is read with defaults`() {
        val line = parseStrictLine(JsonObject("""{"productId": 4}"""))

        assertEquals(4L, line.productId)
        assertEquals(0L, line.variantId)
        assertEquals(1, line.quantity)
        assertTrue(line.fieldValues.isEmpty())
        assertNull(line.targetServerId)
    }

    @Test
    fun `a line keeps typed values and the key matches the library`() {
        val line = parseStrictLine(JsonObject("""{"productId": 12, "variantId": 3, "quantity": 2, "fieldValues": {"nick": " Steve ", "amount": 3, "ok": true, "gone": null}, "targetServerId": 5}"""))

        assertEquals(mapOf<String, Any>("nick" to "Steve", "amount" to 3, "ok" to true), line.fieldValues)
        assertEquals(CartLineKey.of(12, 3, mapOf("nick" to "Steve", "amount" to 3, "ok" to true), 5), line.lineKey)
    }

    @Test
    fun `quantity outside 1 to 999 is a bad request on a single write`() {
        refused("item.quantity") { parseStrictLine(JsonObject("""{"productId": 1, "quantity": 0}""")) }
        refused("item.quantity") { parseStrictLine(JsonObject("""{"productId": 1, "quantity": 1000}""")) }
        refused("item.quantity") { parseStrictLine(JsonObject("""{"productId": 1, "quantity": 1.5}""")) }
        refused("item.quantity") { parseStrictLine(JsonObject("""{"productId": 1, "quantity": "2"}""")) }
        assertEquals(999, parseStrictLine(JsonObject("""{"productId": 1, "quantity": 999}""")).quantity)
    }

    @Test
    fun `ids and shapes are checked`() {
        refused("item.productId") { parseStrictLine(JsonObject("""{"quantity": 1}""")) }
        refused("item.productId") { parseStrictLine(JsonObject("""{"productId": 0}""")) }
        refused("item.productId") { parseStrictLine(JsonObject("""{"productId": "1"}""")) }
        refused("item.variantId") { parseStrictLine(JsonObject("""{"productId": 1, "variantId": -1}""")) }
        refused("item.targetServerId") { parseStrictLine(JsonObject("""{"productId": 1, "targetServerId": 0}""")) }
        refused("item.fieldValues") { parseStrictLine(JsonObject("""{"productId": 1, "fieldValues": "x"}""")) }
        refused("item.fieldValues") { parseStrictLine(JsonObject("""{"productId": 1, "fieldValues": {"a": [1]}}""")) }
        refused("item.fieldValues") { parseStrictLine(JsonObject("""{"productId": 1, "fieldValues": {"a": {"b": 1}}}""")) }
        assertEquals(0L, parseStrictLine(JsonObject("""{"productId": 1, "variantId": 0}""")).variantId)
    }

    @Test
    fun `field values keep to 20 keys and 128 characters`() {
        val tooMany = JsonObject().put("productId", 1).put("fieldValues", JsonObject((1..21).associate { "k$it" to "v" }))
        val tooLong = JsonObject().put("productId", 1).put("fieldValues", JsonObject().put("a", "x".repeat(129)))
        val fine = JsonObject().put("productId", 1).put("fieldValues", JsonObject((1..20).associate { "k$it" to "x".repeat(128) }))

        refused("item.fieldValues") { parseStrictLine(tooMany) }
        refused("item.fieldValues") { parseStrictLine(tooLong) }
        assertEquals(20, parseStrictLine(fine).fieldValues.size)
    }

    @Test
    fun `a merge clamps quantities and counts unreadable entries`() {
        val body = JsonObject(
            """{"items": [{"productId": 1, "quantity": 5000}, {"productId": "x"}, 7, {"productId": 2, "quantity": -1}, {"productId": 3, "fieldValues": {"a": [1]}}]}"""
        )
        val request = parseMerge(body)

        assertEquals(listOf(1L to 999, 2L to 1), request.lines.map { it.productId to it.quantity })
        assertEquals(3, request.unreadable)
    }

    @Test
    fun `a merge needs an items array of sane size`() {
        refused("items") { parseMerge(JsonObject("""{}""")) }
        refused("items") { parseMerge(JsonObject("""{"items": {}}""")) }
        refused("items") { parseMerge(JsonObject().put("items", JsonArray((1..501).map { JsonObject().put("productId", it) }))) }
        assertTrue(parseMerge(JsonObject("""{"items": []}""")).lines.isEmpty())
    }

    @Test
    fun `an item patch needs a field and judges quantity`() {
        refused("body") { parseItemPatch(JsonObject("""{}""")) }
        refused("quantity") { parseItemPatch(JsonObject("""{"quantity": 0}""")) }
        refused("quantity") { parseItemPatch(JsonObject("""{"quantity": 1000}""")) }
        refused("targetServerId") { parseItemPatch(JsonObject("""{"targetServerId": 0}""")) }

        val patch = parseItemPatch(JsonObject("""{"quantity": 4, "fieldValues": null, "targetServerId": null}"""))

        assertEquals(4, patch.quantity)
        assertNotNull(patch.fieldValues)
        assertNull(patch.fieldValues!!.value)
        assertNotNull(patch.targetServerId)
        assertNull(patch.targetServerId!!.value)
        assertNull(parseItemPatch(JsonObject("""{"quantity": 2}""")).fieldValues)
        assertEquals(mapOf<String, Any?>("a" to "b"), parseItemPatch(JsonObject("""{"fieldValues": {"a": "b"}}""")).fieldValues!!.value)
    }

    @Test
    fun `a replacement applies only what is present`() {
        val none = parseReplacement(JsonObject("""{}"""))

        assertNull(none.items)
        assertNull(none.currency)
        assertNull(none.couponCode)
        assertNull(none.shippingMethodId)

        val all = parseReplacement(
            JsonObject(
                """{"items": [{"productId": 1, "quantity": 2}, {"productId": 1, "quantity": 3}], "currency": "usd", "couponCode": " summer ",
                    "creatorCode": null, "recipientUsername": "Steve", "giftMessage": "hi", "shippingAddressId": 3, "shippingMethodId": null}"""
            )
        )

        assertEquals(2, all.items!!.size)
        assertEquals("usd", all.currency!!.value)
        assertEquals(" summer ", all.couponCode!!.value)
        assertNull(all.creatorCode!!.value)
        assertEquals("Steve", all.recipientUsername!!.value)
        assertEquals(3L, all.shippingAddressId!!.value)
        assertNull(all.shippingMethodId!!.value)
        assertEquals(emptyList<Any>(), parseReplacement(JsonObject("""{"items": null}""")).items)
    }

    @Test
    fun `a replacement refuses long codes, long messages and bad shapes`() {
        refused("couponCode") { parseReplacement(JsonObject().put("couponCode", "X".repeat(65))) }
        refused("creatorCode") { parseReplacement(JsonObject().put("creatorCode", 5)) }
        refused("giftMessage") { parseReplacement(JsonObject().put("giftMessage", "x".repeat(256))) }
        refused("recipientUsername") { parseReplacement(JsonObject().put("recipientUsername", "x".repeat(65))) }
        refused("shippingAddressId") { parseReplacement(JsonObject().put("shippingAddressId", 0)) }
        refused("items") { parseReplacement(JsonObject().put("items", "x")) }
        refused("items[1]") { parseReplacement(JsonObject("""{"items": [{"productId": 1}, 5]}""")) }
        refused("items[0].quantity") { parseReplacement(JsonObject("""{"items": [{"productId": 1, "quantity": 0}]}""")) }
        assertEquals("X".repeat(64), parseReplacement(JsonObject().put("couponCode", "X".repeat(64))).couponCode!!.value)
    }
}
