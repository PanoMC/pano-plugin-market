package com.panomc.plugins.market.spi.common

import com.panomc.plugins.market.spi.MarketSpi
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InboundAndProviderTypesTest {
    private fun request(
        body: ByteArray = ByteArray(0),
        headers: Map<String, List<String>> = emptyMap(),
        query: Map<String, List<String>> = emptyMap()
    ) = InboundRequest(InboundKind.WEBHOOK, "default", "POST", "a=1", query, headers, "application/x-www-form-urlencoded", body, "203.0.113.5", 1_000L)

    @Test
    fun `header lookup is case-insensitive and returns the first value`() {
        val r = request(headers = mapOf("x-sig" to listOf("a", "b")))
        assertEquals("a", r.header("X-Sig"))
        assertEquals("a", r.header("x-sig"))
        assertNull(r.header("missing"))
    }

    @Test
    fun `query param and body views`() {
        val r = request(body = """{"a":1}""".toByteArray(), query = mapOf("k" to listOf("v1", "v2")))
        assertEquals("v1", r.queryParam("k"))
        assertNull(r.queryParam("none"))
        assertEquals("""{"a":1}""", r.bodyAsString())
        assertEquals(1, r.bodyAsJson().getInteger("a"))
        assertEquals("café", request(body = "café".toByteArray(Charsets.ISO_8859_1)).bodyAsString(Charsets.ISO_8859_1))
    }

    @Test
    fun `form decodes percent escapes plus signs and repeated names exactly`() {
        val r = request(body = "a=1&b=x%20y+z&a=2&empty=&flag&c=%C3%A9&d=+leading ".toByteArray())
        val form = r.form()
        assertEquals(listOf("1", "2"), form["a"])
        assertEquals(listOf("x y z"), form["b"])
        assertEquals(listOf(""), form["empty"])
        assertEquals(listOf(""), form["flag"])
        assertEquals(listOf("é"), form["c"])
        assertEquals(listOf(" leading "), form["d"])
        assertEquals("1", r.formParam("a"))
        assertNull(r.formParam("zzz"))
        assertEquals("x y z", r.formParam("b"))
    }

    @Test
    fun `empty form and multipart attributes`() {
        assertTrue(request().form().isEmpty())
        val m = request()
        m.formAttributes = mapOf("field" to listOf("value"))
        assertEquals("value", m.formParam("field"))
        assertEquals(0, m.body.size)
    }

    @Test
    fun `http replies`() {
        val text = HttpReply.text("OK")
        assertEquals(200, text.status); assertEquals("text/plain; charset=utf-8", text.contentType); assertArrayEquals("OK".toByteArray(), text.body)
        assertEquals(503, HttpReply.text("busy", 503).status)

        val json = HttpReply.json(JsonObject().put("ok", true), 201)
        assertEquals(201, json.status); assertEquals("application/json", json.contentType); assertEquals("""{"ok":true}""", String(json.body))

        val empty = HttpReply.empty()
        assertEquals(200, empty.status); assertNull(empty.contentType); assertEquals(0, empty.body.size)
        assertEquals(204, HttpReply.empty(204).status)

        val redirect = HttpReply.redirect("https://shop.example/x")
        assertEquals(303, redirect.status); assertEquals("https://shop.example/x", redirect.headers["Location"])
        assertEquals(302, HttpReply.redirect("/y", 302).status)

        val order = HttpReply.toOrderPage()
        assertEquals(303, order.status); assertTrue(order.orderPage)
        assertFalse(redirect.orderPage)

        assertEquals(503, HttpReply.retryLater().status)
        assertEquals(429, HttpReply.retryLater(429).status)
        assertTrue(HttpReply.empty().headers.isEmpty())
    }

    @Test
    fun `redirect refuses header injection and non redirect statuses`() {
        assertThrows<IllegalArgumentException> { HttpReply.redirect("https://x/\r\nSet-Cookie: a=b") }
        assertThrows<IllegalArgumentException> { HttpReply.redirect("https://x/\n") }
        assertThrows<IllegalArgumentException> { HttpReply.redirect("") }
        assertThrows<IllegalArgumentException> { HttpReply.redirect("https://x", 200) }
        assertThrows<IllegalArgumentException> { HttpReply(99, null, ByteArray(0)) }
        assertThrows<IllegalArgumentException> { HttpReply(600, null, ByteArray(0)) }
    }

    @Test
    fun `provider asset limits`() {
        ProviderAsset("image/png", ByteArray(ProviderAsset.MAX_BYTES))
        ProviderAsset("image/svg+xml", ByteArray(10))
        ProviderAsset("image/webp", ByteArray(0))
        assertThrows<IllegalArgumentException> { ProviderAsset("image/png", ByteArray(ProviderAsset.MAX_BYTES + 1)) }
        assertThrows<IllegalArgumentException> { ProviderAsset("image/gif", ByteArray(1)) }
        assertThrows<IllegalArgumentException> { ProviderAsset("text/html", ByteArray(1)) }
    }

    @Test
    fun `descriptor defaults and optional vars`() {
        val d = ProviderDescriptor(LocalizedText.of("Pay"), LocalizedText.of("Pay by card"), "credit-card")
        assertEquals("global", d.region)
        assertEquals(Verification.UNVERIFIED, d.verification)
        assertNull(d.logo); assertNull(d.color); assertNull(d.docsUrl); assertNull(d.checkoutHint)
        assertTrue(d.storefrontNotices.isEmpty())
        d.verification = Verification.SANDBOX
        d.storefrontNotices = listOf(StorefrontNotice(LocalizedText.of("Terms"), "https://gw.example/terms"))
        assertEquals(1, d.storefrontNotices.size)
        assertEquals(listOf("UNVERIFIED", "DOC_SAMPLES", "SANDBOX", "LIVE"), Verification.values().map { it.name })
    }

    @Test
    fun `provider exception carries code and flags`() {
        val cause = RuntimeException("boom")
        val e = ProviderException(ProviderErrorCode.GATEWAY_REJECTED, "rejected", adminMessage = "bad key", retryable = true, cause = cause)
        assertEquals(ProviderErrorCode.GATEWAY_REJECTED, e.code)
        assertEquals("rejected", e.message); assertEquals("bad key", e.adminMessage); assertTrue(e.retryable); assertEquals(cause, e.cause)
        val plain = ProviderException(ProviderErrorCode.CONFIGURATION, "missing")
        assertNull(plain.adminMessage); assertFalse(plain.retryable); assertNull(plain.cause)
        assertEquals(10, ProviderErrorCode.values().size)
    }

    @Test
    fun `address and site info are plain holders`() {
        val a = Address("A", "B", null, null, "a@b.c", "TR", null, "Istanbul", null, null, "L1", null, "34000", null, null, null)
        assertEquals("Istanbul", a.city); assertNull(a.company)
        val s = SiteInfo("Shop", "https://shop.example", true, true, "en-US")
        assertEquals("https://shop.example", s.baseUrl)
    }

    @Test
    fun `spi version constants`() {
        assertEquals(1, MarketSpi.VERSION)
        assertEquals(1, MarketSpi.MIN_SUPPORTED)
        assertTrue(MarketSpi.MIN_SUPPORTED <= MarketSpi.VERSION)
        assertEquals("default", MarketSpi.DEFAULT_CHANNEL)
    }
}
