package com.panomc.plugins.market.routes.api.checkout

import com.panomc.platform.error.BadRequest
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.routes.base.MarketPublicMutationApi
import com.panomc.plugins.market.service.UseCredits
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `POST /api/market/checkout`: the `Idempotency-Key` header, the body (PT-1: no other keys), the consent total, and the shape of the route (MK-075). */
class CheckoutRequestsTest {
    private val key = "0123456789abcdef"

    private fun parse(json: String, idempotencyKey: String = key) = parseCheckoutRequest(JsonObject(json), idempotencyKey)

    private fun bad(json: String, field: String) {
        val e = assertThrows(RequestValueException::class.java) { parse(json) }

        assertEquals(field, e.field)
    }

    @Test
    fun `the header is required and must be 16 to 64 characters of letters, digits, underscore and dash`() {
        assertEquals(key, idempotencyKeyOf(key))
        assertEquals("a".repeat(64), idempotencyKeyOf("a".repeat(64)))
        assertEquals("1f4b8f0e-2c4d-4e0a-9b7a-8d3c5e6f7a81", idempotencyKeyOf(" 1f4b8f0e-2c4d-4e0a-9b7a-8d3c5e6f7a81 "), "a UUID fits, surrounding blanks are ignored")

        for (header in listOf(null, "", "short", "a".repeat(15), "a".repeat(65), "0123456789abcde!", "0123456789 abcdef", "0123456789abcdef\n0123456789abcdef")) {
            assertThrows(BadRequest::class.java, { idempotencyKeyOf(header) }, "header $header")
        }
    }

    @Test
    fun `a body is read as a CartInput plus the checkout fields`() {
        val request = parse(
            "{\"items\":[{\"productId\":1,\"quantity\":2}],\"acceptLegal\":true,\"legalTextId\":4,\"expectedTotal\":19.99,\"hideFromBroadcast\":true," +
                "\"useCredits\":2.5,\"paymentMethodId\":\"fake\",\"guest\":{\"username\":\"Steve\",\"email\":\"s@x.com\"}}"
        )

        assertEquals(key, request.idempotencyKey)
        assertEquals(1999L, request.expectedTotal)
        assertTrue(request.acceptLegal)
        assertEquals(4L, request.legalTextId)
        assertTrue(request.hideFromBroadcast)
        assertEquals(250L, (request.input.useCredits as UseCredits.Amount).credits)
        assertEquals("fake", request.input.paymentMethodId)
        assertEquals(1, request.input.items!!.size)
        assertEquals("Steve", request.input.guest!!.username)
        assertEquals(64, request.bodyHash.length)
    }

    @Test
    fun `an empty body parses and every checkout field defaults`() {
        val request = parse("{}")

        assertNull(request.expectedTotal)
        assertFalse(request.acceptLegal)
        assertNull(request.legalTextId)
        assertFalse(request.hideFromBroadcast)
        assertNull(request.input.items)
    }

    @Test
    fun `a key outside the contract is refused, no price or total is ever read from the client (PT-1)`() {
        for (key in listOf("price", "total", "totalPrice", "discount", "unitPrice", "fee", "vat", "gatewayAmount", "status")) {
            bad("{\"$key\":1}", key)
        }

        bad("{\"items\":[],\"extra\":true}", "extra")
    }

    @Test
    fun `useCredits MAX is quote only`() {
        bad("{\"useCredits\":\"MAX\"}", "useCredits")
        bad("{\"useCredits\":\"ALL\"}", "useCredits")
        bad("{\"useCredits\":-1}", "useCredits")
    }

    @Test
    fun `expectedTotal is a money amount with at most two decimals`() {
        assertEquals(0L, parse("{\"expectedTotal\":0}").expectedTotal)
        assertEquals(100L, parse("{\"expectedTotal\":1}").expectedTotal)
        assertEquals(1L, parse("{\"expectedTotal\":0.01}").expectedTotal)
        assertEquals(1050L, parse("{\"expectedTotal\":10.5}").expectedTotal)

        bad("{\"expectedTotal\":0.001}", "expectedTotal")
        bad("{\"expectedTotal\":-1}", "expectedTotal")
        bad("{\"expectedTotal\":\"10\"}", "expectedTotal")
        bad("{\"expectedTotal\":1e30}", "expectedTotal")
    }

    @Test
    fun `acceptLegal, hideFromBroadcast and legalTextId are typed`() {
        bad("{\"acceptLegal\":\"yes\"}", "acceptLegal")
        bad("{\"acceptLegal\":1}", "acceptLegal")
        bad("{\"hideFromBroadcast\":\"true\"}", "hideFromBroadcast")
        bad("{\"legalTextId\":0}", "legalTextId")
        bad("{\"legalTextId\":\"3\"}", "legalTextId")
        bad("{\"legalTextId\":1.5}", "legalTextId")
    }

    @Test
    fun `the body hash ignores key order and whitespace and changes with any value`() {
        val a = parse("{\"items\":[{\"productId\":1,\"quantity\":1}],\"currency\":\"EUR\"}")
        val b = parse("{ \"currency\" : \"EUR\", \"items\" : [ { \"quantity\" : 1, \"productId\" : 1 } ] }")
        val c = parse("{\"items\":[{\"productId\":1,\"quantity\":2}],\"currency\":\"EUR\"}")

        assertEquals(a.bodyHash, b.bodyHash)
        assertNotEquals(a.bodyHash, c.bodyHash)
        assertEquals(a.bodyHash, parse("{\"items\":[{\"productId\":1,\"quantity\":1}],\"currency\":\"EUR\"}", "another-key-1234567").bodyHash, "the key is not part of the body")
    }

    @Test
    fun `the route is a registered public mutating endpoint on its path`() {
        assertTrue(CheckoutAPI::class.java.isAnnotationPresent(com.panomc.platform.annotation.Endpoint::class.java), "registered by the router")
        assertTrue(MarketPublicMutationApi::class.java.isAssignableFrom(CheckoutAPI::class.java), "PUB-M: CSRF with a session cookie")
        assertEquals("Idempotency-Key", CheckoutAPI.IDEMPOTENCY_HEADER)
        assertTrue(CHECKOUT_KEYS.containsAll(listOf("acceptLegal", "legalTextId", "expectedTotal", "hideFromBroadcast")))
    }
}
