package com.panomc.plugins.market.routes.api.checkout

import com.panomc.platform.error.BadRequest
import com.panomc.plugins.market.error.InvalidCart
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

    /** 11 PT-2: a quantity violation is 400 `INVALID_CART` whose `lineErrors` name the line by its position in `items`. */
    private fun badQuantity(json: String, index: Int) {
        val e = assertThrows(InvalidCart::class.java) { parse(json) }
        val extras = JsonObject(e.encode())

        assertEquals("INVALID_CART", e.getErrorCode())
        assertEquals(400, e.getStatusCode())
        assertEquals(listOf("items[$index]"), extras.getJsonObject("lineErrors").fieldNames().toList())
        assertEquals(listOf(QUANTITY_OUT_OF_RANGE), extras.getJsonObject("lineErrors").getJsonArray("items[$index]").list)
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

    // ---------------------------------------------------------------------------------------------- quantity (06 section 2.1, PT-2)

    private fun one(quantity: String?) = "{\"items\":[{\"productId\":1" + (if (quantity == null) "" else ",\"quantity\":$quantity") + "}]}"

    @Test
    fun `a quantity outside 1 to 999, missing, null, text or with a fraction is refused, an order is never created for another number`() {
        for (quantity in listOf("0", "-1", "-3", "1000", "5000", "99999999999", "1.5", "0.5", "\"2\"", "null", "true", "[]", "{}", "1e400")) {
            badQuantity(one(quantity), 0)
        }

        badQuantity(one(null), 0)
        badQuantity("{\"items\":[{\"productId\":1,\"quantity\":1},{\"productId\":2,\"quantity\":0}]}", 1)
        badQuantity("{\"items\":[{\"productId\":1,\"quantity\":1},{\"productId\":2}]}", 1)
    }

    @Test
    fun `the quantities 1 and 999 and a whole number written with a zero fraction are read as sent`() {
        assertEquals(1, parse(one("1")).input.items!!.single().quantity)
        assertEquals(999, parse(one("999")).input.items!!.single().quantity)
        assertEquals(2, parse(one("2.0")).input.items!!.single().quantity)
    }

    @Test
    fun `equal lines may not add up to more than 999, different lines may`() {
        badQuantity("{\"items\":[{\"productId\":1,\"quantity\":600},{\"productId\":1,\"quantity\":400}]}", 1)
        badQuantity("{\"items\":[{\"productId\":1,\"quantity\":999},{\"productId\":1,\"quantity\":1}]}", 1)
        badQuantity("{\"items\":[{\"productId\":1,\"quantity\":500},{\"productId\":1,\"variantId\":0,\"quantity\":500}]}", 1)

        assertEquals(
            listOf(500, 499),
            parse("{\"items\":[{\"productId\":1,\"quantity\":500},{\"productId\":1,\"quantity\":499}]}").input.items!!.map { it.quantity },
            "999 in total is fine"
        )
        assertEquals(
            2,
            parse("{\"items\":[{\"productId\":1,\"quantity\":999},{\"productId\":1,\"variantId\":3,\"quantity\":999}]}").input.items!!.size,
            "another variant is another line"
        )
        assertEquals(
            2,
            parse("{\"items\":[{\"productId\":1,\"quantity\":999},{\"productId\":1,\"quantity\":999,\"fieldValues\":{\"k\":\"v\"}}]}").input.items!!.size,
            "other field values are another line"
        )
    }

    @Test
    fun `a request without items (the server cart) and a body that is not an array are left to the parser`() {
        assertNull(parse("{}").input.items)
        assertNull(parse("{\"items\":null}").input.items)

        bad("{\"items\":5}", "items")
        bad("{\"items\":[5]}", "items[0]")
        bad("{\"items\":[{\"productId\":\"x\",\"quantity\":1}]}", "items[0]")
    }

    @Test
    fun `the quote parser still clamps, only the order refuses`() {
        val lines = parseQuoteInput(JsonObject("{\"items\":[{\"productId\":1,\"quantity\":5000},{\"productId\":2,\"quantity\":0},{\"productId\":3}]}")).items!!

        assertEquals(listOf(999, 1, 1), lines.map { it.quantity })
    }

    // ------------------------------------------------------------------------------------------- order locale (06 section 5.4)

    private val installed = listOf(InstalledLocale("tr", listOf("tr-tr")), InstalledLocale("en-US"), InstalledLocale("ru"))

    private fun locale(body: String? = null, user: String? = null, accept: String? = null, site: String = "en-US") =
        OrderLocale.resolve(body, user, accept, installed, site)

    @Test
    fun `the body locale is used when it is an installed platform locale, spelled as installed`() {
        assertEquals("ru", locale(body = "ru"))
        assertEquals("tr", locale(body = "tr", user = "ru", accept = "en"))
        assertEquals("en-US", locale(body = "EN-us"), "the installed spelling comes back")
        assertEquals("tr", locale(body = "tr-TR"), "a derivative of an installed locale")
        assertEquals("ru", locale(body = " ru "))
    }

    @Test
    fun `a body locale that is not installed falls back to the stored locale of the user`() {
        assertEquals("ru", locale(body = "de", user = "ru", accept = "tr"))
        assertEquals("tr", locale(body = "../x", user = "tr"))
        assertEquals("en-US", locale(body = "<script>", user = "en-US", site = "tr"))
        assertEquals("ru", locale(user = "ru", accept = "tr"), "no body locale at all")
    }

    @Test
    fun `a stored user locale that is no longer installed is skipped`() {
        assertEquals("tr", locale(user = "de", accept = "tr-TR,tr;q=0.9"))
        assertEquals("en-US", locale(user = "de"))
    }

    @Test
    fun `Accept-Language picks the first installed match, highest weight first`() {
        assertEquals("ru", locale(accept = "de-DE,de;q=0.9,ru;q=0.8,en;q=0.7"))
        assertEquals("tr", locale(accept = "ru;q=0.5,tr;q=0.9"))
        assertEquals("tr", locale(accept = "tr-TR"), "a derivative")
        assertEquals("tr", locale(accept = "tr-CY"), "the same language of another region")
        assertEquals("en-US", locale(accept = "en-GB,en;q=0.9"), "the same language of another region")
        assertEquals("ru", locale(accept = "ru, tr"), "equal weights keep their order")
        assertEquals("tr", locale(accept = "ru;q=0, tr;q=0.1"), "q=0 is not acceptable")
        assertEquals("ru", locale(accept = "*, ru"))
    }

    @Test
    fun `the site default is the last resort and a client string never reaches the order`() {
        assertEquals("en-US", locale())
        assertEquals("tr", locale(site = "tr"))
        assertEquals("tr", locale(body = "de", user = "fr", accept = "de, fr;q=0.8, *;q=0.1", site = "tr"))
        assertEquals("ru", locale(accept = "x".repeat(2000) + ",ru", site = "ru"), "an oversized header is ignored")

        for (hostile in listOf("../x", "<b>", "tr\u0000", "en-US'; DROP TABLE x;--", "a".repeat(200), "", " ")) {
            val result = locale(body = hostile, user = hostile, accept = hostile)

            assertTrue(result in setOf("tr", "en-US", "ru"), "'$hostile' ended up as '$result'")
        }
    }

    @Test
    fun `the weights of an Accept-Language header are read the way browsers send them`() {
        assertEquals(listOf("fr-ch", "fr", "en", "de"), OrderLocale.acceptedTags("fr-CH, fr;q=0.9, en;q=0.8, de;q=0.7, *;q=0.5"))
        assertEquals(listOf("b", "a"), OrderLocale.acceptedTags("a;q=0.2,b;q=0.3"))
        assertEquals(listOf("a"), OrderLocale.acceptedTags("a;q=0.5;level=1, b;q=0"))
        assertEquals(emptyList<String>(), OrderLocale.acceptedTags(null))
        assertEquals(emptyList<String>(), OrderLocale.acceptedTags("  "))
        assertEquals(emptyList<String>(), OrderLocale.acceptedTags("*"))
    }

    @Test
    fun `the parser leaves the order locale to the route and never stores the body locale`() {
        val request = parse("{\"locale\":\"tr\"}")

        assertNull(request.orderLocale)
        assertEquals("tr", request.input.locale, "the body value is the quote's, the order uses the resolved one")
        assertEquals("ru", request.copy(orderLocale = "ru").orderLocale)
    }

    @Test
    fun `the route is a registered public mutating endpoint on its path`() {
        assertTrue(CheckoutAPI::class.java.isAnnotationPresent(com.panomc.platform.annotation.Endpoint::class.java), "registered by the router")
        assertTrue(MarketPublicMutationApi::class.java.isAssignableFrom(CheckoutAPI::class.java), "PUB-M: CSRF with a session cookie")
        assertEquals("Idempotency-Key", CheckoutAPI.IDEMPOTENCY_HEADER)
        assertTrue(CHECKOUT_KEYS.containsAll(listOf("acceptLegal", "legalTextId", "expectedTotal", "hideFromBroadcast")))
    }
}
