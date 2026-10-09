package com.panomc.plugins.market.spi.payment

import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.common.settingsSchema
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import com.panomc.plugins.market.util.MarketPaths

class StartPaymentResultTest {
    private val page = "https://shop.example${MarketPaths.SITE_ROOT}/payments/attempts/tok/page"

    private fun succeeded() = PaymentEvent.Succeeded(PaymentTarget.Attempt(5), PaymentTestData.eur(1000))

    @Test
    fun `redirect maps to REDIRECT with the gateway url`() {
        val r = StartPaymentResult.Redirect("https://gateway.example/pay/1")
        r.gatewayTransactionId = "tx1"
        r.expiresAt = 1_700_000_000_000
        val json = r.toPaymentStartJson("en-US")
        assertEquals("REDIRECT", json.getString("kind"))
        assertEquals("https://gateway.example/pay/1", json.getString("url"))
        assertEquals(1_700_000_000_000, json.getLong("expiresAt"))
        assertEquals("tx1", r.gatewayTransactionId)
    }

    @Test
    fun `the provider expiry wins over the default and a missing one is null`() {
        val r = StartPaymentResult.Redirect("https://g.example/x")
        assertNull(r.toPaymentStartJson("en-US").getLong("expiresAt"))
        assertEquals(42L, r.toPaymentStartJson("en-US", defaultExpiresAt = 42L).getLong("expiresAt"))
        r.expiresAt = 7
        assertEquals(7L, r.toPaymentStartJson("en-US", defaultExpiresAt = 42L).getLong("expiresAt"))
    }

    @Test
    fun `form post and html point at market's attempt page and never inline the document`() {
        val form = StartPaymentResult.FormPost("https://acs.example/3ds", mapOf("a" to "1")).also { it.acceptCharset = "UTF-8" }
        val formJson = form.toPaymentStartJson("tr", attemptPageUrl = page)
        assertEquals("FORM_POST", formJson.getString("kind"))
        assertEquals(page, formJson.getString("url"))
        assertFalse(formJson.encode().contains("acs.example"))
        assertEquals("UTF-8", form.acceptCharset)

        val html = StartPaymentResult.Html("<form action=https://acs.example>secret-markup</form>")
        val htmlJson = html.toPaymentStartJson("tr", attemptPageUrl = page)
        assertEquals("HTML", htmlJson.getString("kind"))
        assertEquals(page, htmlJson.getString("url"))
        assertFalse(htmlJson.encode().contains("secret-markup"))

        assertThrows<IllegalArgumentException> { form.toPaymentStartJson("tr") }
        assertThrows<IllegalArgumentException> { html.toPaymentStartJson("tr") }
    }

    @Test
    fun `iframe carries url scripts height allow and resizer`() {
        val r = StartPaymentResult.Iframe("https://pay.example/frame").also {
            it.scripts = listOf("https://pay.example/iframeResizer.js")
            it.heightPx = 600
            it.allow = "payment"
            it.resizer = IframeResizer.IFRAME_RESIZER
        }
        val iframe = r.toPaymentStartJson("en-US").getJsonObject("iframe")
        assertEquals("IFRAME", r.toPaymentStartJson("en-US").getString("kind"))
        assertEquals("https://pay.example/frame", iframe.getString("url"))
        assertEquals(JsonArray(listOf("https://pay.example/iframeResizer.js")), iframe.getJsonArray("scripts"))
        assertEquals(600, iframe.getInteger("heightPx"))
        assertEquals("payment", iframe.getString("allow"))
        assertEquals("IFRAME_RESIZER", iframe.getString("resizer"))

        val bare = StartPaymentResult.Iframe("https://pay.example/frame").toPaymentStartJson("en-US").getJsonObject("iframe")
        assertEquals("NONE", bare.getString("resizer"))
        assertNull(bare.getInteger("heightPx"))
        assertEquals(JsonArray(), bare.getJsonArray("scripts"))
    }

    @Test
    fun `embedded resolves field labels help and options to plain strings in the request locale`() {
        val schema = settingsSchema {
            text("phone") {
                label = LocalizedText.of("Phone", "tr" to "Telefon")
                help = LocalizedText.of("With country code", "tr" to "Ulke kodu ile")
                required = true
            }
            select("operator") {
                label = LocalizedText.of("Operator", "tr" to "Operator")
                option("a", LocalizedText.of("Alpha", "tr" to "Alfa"))
                option("b", LocalizedText.of("Beta"))
            }
        }
        val r = StartPaymentResult.Embedded(JsonObject().put("hint", "x")).also {
            it.fields = schema.fields
            it.component = "market:checkout:payment:fake"
            it.scripts = listOf("https://pay.example/sdk.js")
        }
        val tr = r.toPaymentStartJson("tr-TR").getJsonObject("embedded")
        assertEquals("market:checkout:payment:fake", tr.getString("component"))
        assertEquals("x", tr.getJsonObject("props").getString("hint"))
        val fields = tr.getJsonArray("fields")
        assertEquals("Telefon", fields.getJsonObject(0).getString("label"))
        assertEquals("Ulke kodu ile", fields.getJsonObject(0).getString("help"))
        assertTrue(fields.getJsonObject(0).getBoolean("required"))
        val options = fields.getJsonObject(1).getJsonArray("options")
        assertEquals("Alfa", options.getJsonObject(0).getString("label"))
        assertEquals("Beta", options.getJsonObject(1).getString("label"))

        val en = r.toPaymentStartJson("en-US").getJsonObject("embedded").getJsonArray("fields")
        assertEquals("Phone", en.getJsonObject(0).getString("label"))
        assertEquals("EMBEDDED", r.toPaymentStartJson("en-US").getString("kind"))
    }

    @Test
    fun `instructions escape plain text turn newlines into br and resolve labels`() {
        val r = StartPaymentResult.Instructions(
            LocalizedText.of("Pay <b>now</b> & \"quickly\"\nthen wait\r\nplease", "tr" to "Simdi ode"),
            listOf(
                InstructionField(LocalizedText.of("IBAN", "tr" to "IBAN no"), "TR00 0000"),
                InstructionField(LocalizedText.of("Reference"), "ABC").also { it.copyable = false }
            )
        )
        val json = r.toPaymentStartJson("en-US").getJsonObject("instructions")
        assertEquals("Pay &lt;b&gt;now&lt;/b&gt; &amp; &quot;quickly&quot;<br>then wait<br>please", json.getString("body"))
        assertTrue(json.getBoolean("buyerConfirms"))
        assertEquals("IBAN", json.getJsonArray("fields").getJsonObject(0).getString("label"))
        assertTrue(json.getJsonArray("fields").getJsonObject(0).getBoolean("copyable"))
        assertFalse(json.getJsonArray("fields").getJsonObject(1).getBoolean("copyable"))
        assertEquals("TR00 0000", json.getJsonArray("fields").getJsonObject(0).getString("value"))

        val tr = r.toPaymentStartJson("tr").getJsonObject("instructions")
        assertEquals("Simdi ode", tr.getString("body"))
        assertEquals("IBAN no", tr.getJsonArray("fields").getJsonObject(0).getString("label"))
        r.buyerConfirms = false
        assertFalse(r.toPaymentStartJson("tr").getJsonObject("instructions").getBoolean("buyerConfirms"))
    }

    @Test
    fun `instructions body goes through the sanitiser hook after escaping`() {
        val r = StartPaymentResult.Instructions(LocalizedText.of("a\nb"), emptyList())
        val seen = mutableListOf<String>()
        val json = r.toPaymentStartJson("en-US", sanitizeHtml = { seen.add(it); it.uppercase() })
        assertEquals(listOf("a<br>b"), seen)
        assertEquals("A<BR>B", json.getJsonObject("instructions").getString("body"))
    }

    @Test
    fun `completed maps to COMPLETED and keeps the paid event`() {
        val ev = succeeded()
        val r = StartPaymentResult.Completed(ev)
        val json = r.toPaymentStartJson("en-US", defaultExpiresAt = 9)
        assertEquals("COMPLETED", json.getString("kind"))
        assertEquals(9L, json.getLong("expiresAt"))
        assertTrue(r.event === ev)
        assertEquals(PaymentTestData.eur(1000), r.event.paid)
        assertFalse(json.containsKey("url"))
    }

    @Test
    fun `every variant has its own kind and none is missed`() {
        val kinds = listOf(
            StartPaymentResult.Redirect("https://a.example"), StartPaymentResult.FormPost("https://a.example", emptyMap()),
            StartPaymentResult.Iframe("https://a.example"), StartPaymentResult.Html("<p/>"),
            StartPaymentResult.Embedded(JsonObject()), StartPaymentResult.Instructions(LocalizedText.of("x"), emptyList()),
            StartPaymentResult.Completed(succeeded())
        ).map { it.kind }
        assertEquals(listOf("REDIRECT", "FORM_POST", "IFRAME", "HTML", "EMBEDDED", "INSTRUCTIONS", "COMPLETED"), kinds)
        assertEquals(StartPaymentResult::class.sealedSubclasses.size, kinds.size)
    }

    @Test
    fun `common optional result data is settable on every variant`() {
        val r: StartPaymentResult = StartPaymentResult.Redirect("https://a.example")
        r.gatewayRefs = mapOf("session" to "cs_1")
        r.providerData = JsonObject().put("k", "v")
        r.gatewayTransactionId = "t"
        assertEquals("cs_1", r.gatewayRefs["session"])
        assertEquals("v", r.providerData!!.getString("k"))
    }

    @Test
    fun `urls that a browser would execute or resolve against the site are refused`() {
        for (bad in listOf("javascript:alert(1)", "data:text/html,x", "/relative", "//evil.example/x", "ftp://a.example/x", "", "https://", "not a url")) {
            assertThrows<IllegalArgumentException>("Redirect($bad)") { StartPaymentResult.Redirect(bad) }
            assertThrows<IllegalArgumentException>("FormPost($bad)") { StartPaymentResult.FormPost(bad, emptyMap()) }
            assertThrows<IllegalArgumentException>("Iframe($bad)") { StartPaymentResult.Iframe(bad) }
        }
        assertThrows<IllegalArgumentException> { StartPaymentResult.Iframe("https://a.example").scripts = listOf("javascript:x") }
        assertThrows<IllegalArgumentException> { StartPaymentResult.Embedded(JsonObject()).scripts = listOf("/local.js") }
        StartPaymentResult.Redirect("http://127.0.0.1:18189/pay")
    }

    @Test
    fun `html csp origins must be plain https origins`() {
        val html = StartPaymentResult.Html("<p/>")
        html.scriptOrigins = listOf("https://js.stripe.com", "https://cdn.example:8443")
        html.frameOrigins = listOf("https://hooks.example")
        html.connectOrigins = listOf("https://api.example")
        html.formActionOrigins = listOf("https://acs.example")
        html.inlineScript = true
        assertEquals(listOf("https://js.stripe.com", "https://cdn.example:8443"), html.scriptOrigins)
        for (bad in listOf("http://a.example", "https://a.example/path", "https://a.example?x=1", "https://a.example#f", "*", "https://u:p@a.example", "a.example", "'self'", "https://")) {
            assertThrows<IllegalArgumentException>(bad) { html.scriptOrigins = listOf(bad) }
            assertThrows<IllegalArgumentException>(bad) { html.frameOrigins = listOf(bad) }
            assertThrows<IllegalArgumentException>(bad) { html.connectOrigins = listOf(bad) }
            assertThrows<IllegalArgumentException>(bad) { html.formActionOrigins = listOf(bad) }
        }
        assertEquals(listOf("https://js.stripe.com", "https://cdn.example:8443"), html.scriptOrigins, "a refused value leaves the old list")
    }
}
