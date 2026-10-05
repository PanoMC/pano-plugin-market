package com.panomc.plugins.market.routes.api.checkout

import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.routes.base.MarketApi
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.MarketPublicMutationApi
import com.panomc.plugins.market.service.UseCredits
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Parsing of the `CartInput` body of `POST /api/market/checkout/quote` (04 section 2) and the shape of the route class. */
class QuoteRequestsTest {
    private fun parse(json: String) = parseQuoteInput(JsonObject(json))

    private fun bad(json: String, field: String? = null) {
        val e = assertThrows(RequestValueException::class.java) { parse(json) }

        if (field != null) assertEquals(field, e.field)
    }

    @Test
    fun `an empty body is an empty input and every field is optional`() {
        val input = parse("{}")

        assertNull(input.items)
        assertNull(input.currency)
        assertNull(input.useCredits)
        assertNull(input.creditTopUp)
        assertNull(input.guest)
        assertFalse(input.payWithCredits)
    }

    @Test
    fun `items are read as cart lines with a clamped quantity and normalised values`() {
        val input = parse("""{"items":[{"productId":4,"quantity":5000,"variantId":2,"fieldValues":{"a":" x ","b":"","c":true},"targetServerId":9},{"productId":5}]}""")
        val lines = input.items!!

        assertEquals(2, lines.size)
        assertEquals(4L, lines[0].productId)
        assertEquals(999, lines[0].quantity)
        assertEquals(2L, lines[0].variantId)
        assertEquals(mapOf("a" to "x", "c" to true), lines[0].fieldValues)
        assertEquals(9L, lines[0].targetServerId)
        assertEquals(1, lines[1].quantity)
        assertEquals(0L, lines[1].variantId)

        assertEquals(emptyList<Any>(), parse("""{"items":[]}""").items, "an explicit empty array is not the server cart")
        assertNull(parse("""{"items":null}""").items, "null means the server cart")
    }

    @Test
    fun `a malformed item list is a 400`() {
        bad("""{"items":"x"}""", "items")
        bad("""{"items":[1]}""", "items[0]")
        bad("""{"items":[{"quantity":1}]}""", "items[0]")
        bad("""{"items":[{"productId":0}]}""", "items[0]")
        bad("""{"items":[{"productId":1,"variantId":-1}]}""", "items[0]")
        bad("""{"items":[{"productId":1,"targetServerId":0}]}""", "items[0]")
        bad("""{"items":[{"productId":1,"fieldValues":{"a":[1]}}]}""", "items[0]")
        bad("""{"items":[{"productId":1,"fieldValues":{"a":"${"x".repeat(129)}"}}]}""", "items[0].fieldValues")

        val many = JsonArray((1..501).map { JsonObject().put("productId", it) })

        assertEquals("items", assertThrows(RequestValueException::class.java) { parseQuoteInput(JsonObject().put("items", many)) }.field)
    }

    @Test
    fun `useCredits is a number of credits or MAX and nothing else`() {
        assertEquals(UseCredits.Max, parse("""{"useCredits":"MAX"}""").useCredits)
        assertEquals(2550L, (parse("""{"useCredits":25.5}""").useCredits as UseCredits.Amount).credits)
        assertEquals(1200L, (parse("""{"useCredits":12}""").useCredits as UseCredits.Amount).credits)
        assertEquals(0L, (parse("""{"useCredits":0}""").useCredits as UseCredits.Amount).credits)
        assertNull(parse("""{"useCredits":null}""").useCredits)

        bad("""{"useCredits":"max"}""", "useCredits")
        bad("""{"useCredits":"5"}""", "useCredits")
        bad("""{"useCredits":-1}""", "useCredits")
        bad("""{"useCredits":1.234}""", "useCredits")
        bad("""{"useCredits":true}""", "useCredits")
        bad("""{"useCredits":1e30}""", "useCredits")
    }

    @Test
    fun `creditsOrNull reads at most two decimals and never a negative`() {
        assertEquals(1000L, creditsOrNull(10))
        assertEquals(1050L, creditsOrNull(10.5))
        assertEquals(1L, creditsOrNull(0.01))
        assertEquals(1000L, creditsOrNull(10.0))
        assertNull(creditsOrNull(10.001))
        assertNull(creditsOrNull(-1))
        assertNull(creditsOrNull("10"))
        assertNull(creditsOrNull(null))
        assertNull(creditsOrNull(Double.NaN))
        assertNull(creditsOrNull(1e30))
    }

    @Test
    fun `creditTopUp keeps an unusable value as a request with no amount`() {
        assertEquals(2500L, parse("""{"creditTopUp":25}""").creditTopUp!!.credits)
        assertNull(parse("""{"creditTopUp":"x"}""").creditTopUp!!.credits)
        assertNull(parse("""{"creditTopUp":-3}""").creditTopUp!!.credits)
        assertNull(parse("""{"creditTopUp":1.005}""").creditTopUp!!.credits)
        assertNull(parse("""{"creditTopUp":null}""").creditTopUp)
    }

    @Test
    fun `scalar fields are typed and bounded`() {
        val input = parse(
            """{"currency":"USD","couponCode":"A","creatorCode":"B","recipientUsername":"Bob","giftMessage":"hi","payWithCredits":true,
                "guest":{"username":"Steve","email":"s@x.de"},"shippingAddress":{"country":"DE"},"billingInfo":{"type":"COMPANY"},
                "shippingAddressId":7,"shippingMethodId":8,"paymentMethodId":"stripe","locale":"tr"}"""
        )

        assertEquals("USD", input.currency)
        assertEquals("A", input.couponCode)
        assertEquals("B", input.creatorCode)
        assertEquals("Bob", input.recipientUsername)
        assertEquals("hi", input.giftMessage)
        assertTrue(input.payWithCredits)
        assertEquals("Steve", input.guest!!.username)
        assertEquals("s@x.de", input.guest!!.email)
        assertEquals("DE", input.shippingAddress!!.getString("country"))
        assertEquals("COMPANY", input.billingInfo!!.getString("type"))
        assertEquals(7L, input.shippingAddressId)
        assertEquals(8L, input.shippingMethodId)
        assertEquals("stripe", input.paymentMethodId)
        assertEquals("tr", input.locale)

        bad("""{"currency":5}""", "currency")
        bad("""{"couponCode":"${"x".repeat(65)}"}""", "couponCode")
        bad("""{"recipientUsername":"${"x".repeat(65)}"}""", "recipientUsername")
        bad("""{"giftMessage":"${"x".repeat(256)}"}""", "giftMessage")
        bad("""{"payWithCredits":"yes"}""", "payWithCredits")
        bad("""{"guest":"Steve"}""", "guest")
        bad("""{"guest":{"username":5}}""", "guest.username")
        bad("""{"shippingAddress":[]}""", "shippingAddress")
        bad("""{"shippingAddressId":"abc"}""", "shippingAddressId")
        bad("""{"paymentMethodId":"${"x".repeat(65)}"}""", "paymentMethodId")
        bad("""{"locale":"${"x".repeat(17)}"}""", "locale")
    }

    @Test
    fun `the quote route is a registered public mutating route`() {
        assertTrue(MarketPublicMutationApi::class.java.isAssignableFrom(QuoteAPI::class.java), "auth class PUB-M")
        assertTrue(MarketApi::class.java.isAssignableFrom(QuoteAPI::class.java))
        assertFalse(MarketPanelApi::class.java.isAssignableFrom(QuoteAPI::class.java))
        assertTrue(QuoteAPI::class.java.declaredMethods.none { it.name == "getRequiresStoreEnabled" }, "the quote keeps the store switch")

        assertTrue(QuoteAPI::class.java.isAnnotationPresent(com.panomc.platform.annotation.Endpoint::class.java), "registered by the router")
    }

}
