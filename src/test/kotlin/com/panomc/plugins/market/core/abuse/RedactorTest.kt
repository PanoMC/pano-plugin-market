package com.panomc.plugins.market.core.abuse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URLEncoder

/** 11 section 8.4: secret values, Luhn digit runs, sensitive keys, headers. */
class RedactorTest {
    private val secrets = listOf("sk_live_4eC39HqLyjWDarjtT1zdp7dc", "whsec_a/b+c=d", "pa\"ss\\word/é€ end", "tok en&x=y")
    private val redactor = Redactor(secrets.toSet())

    @Test
    fun `every configured secret is removed from a log line`() {
        for (s in secrets) {
            val out = redactor.redact("calling gateway with key $s now")
            assertFalse(out.contains(s), out)
            assertTrue(out.contains("[REDACTED]"), out)
        }
        assertEquals("a [REDACTED] b [REDACTED] c", Redactor(setOf("secret123")).redact("a secret123 b secret123 c"))
    }

    @Test
    fun `url encoded forms are removed too`() {
        for (s in secrets) {
            val forms = listOf(
                URLEncoder.encode(s, "UTF-8"),
                URLEncoder.encode(s, "UTF-8").replace("+", "%20"),
                URLEncoder.encode(s, "UTF-8").replace(Regex("%[0-9A-F]{2}")) { it.value.lowercase() } // lower-case hex digits
            )
            for (encoded in forms) {
                val out = redactor.redact("POST /pay?key=$encoded&x=1")
                assertFalse(out.contains(encoded), "$encoded -> $out")
            }
        }
        // the form-encoded body of a request
        val body = "api_key=" + URLEncoder.encode(secrets[2], "UTF-8") + "&amount=1000"
        assertEquals("api_key=[REDACTED]&amount=1000", redactor.redact(body))
    }

    @Test
    fun `json escaped forms are removed too`() {
        for (s in secrets) {
            val escaped = io.vertx.core.json.JsonObject().put("v", s).encode().removePrefix("{\"v\":\"").removeSuffix("\"}")
            val out = redactor.redact("""{"token":"$escaped","n":1}""")
            assertFalse(out.contains(escaped), out)
            assertEquals("""{"token":"[REDACTED]","n":1}""", out)
        }
        // forward slashes and non ASCII escaped the way other serialisers do
        assertEquals("""{"k":"[REDACTED]"}""", redactor.redact("""{"k":"whsec_a\/b+c=d"}"""))
        assertEquals("""{"k":"[REDACTED]"}""", redactor.redact("""{"k":"pa\"ss\\word\/é€ end"}"""))
    }

    @Test
    fun `short secrets are ignored and a secret containing another is replaced as a whole`() {
        assertEquals("pin 12345 stays", Redactor(setOf("12345")).redact("pin 12345 stays"))
        assertEquals("[REDACTED] ok", Redactor(setOf("123456")).redact("123456 ok"))
        val out = Redactor(setOf("abcdef", "abcdefghij")).redact("x abcdefghij y")
        assertEquals("x [REDACTED] y", out)
    }

    @Test
    fun `plus adds values of the provider state`() {
        val r = Redactor(setOf("firstsecret")).plus(setOf("oauth-token-value", "tiny"))
        assertEquals("[REDACTED] [REDACTED] tiny", r.redact("firstsecret oauth-token-value tiny"))
    }

    @Test
    fun `text without secrets is returned unchanged`() {
        val text = "order 1234 paid 12.50 EUR via stripe, ref ABC123"
        assertEquals(text, redactor.redact(text))
        assertEquals("", redactor.redact(""))
        assertEquals(text, Redactor.NONE.redact(text))
    }

    // ---- card numbers

    @Test
    fun `a luhn valid card number keeps only its last four digits`() {
        assertEquals("card ************1111 ok", Redactor.NONE.redact("card 4111111111111111 ok"))
        assertEquals("************1111", Redactor.NONE.redact("4111 1111 1111 1111"))
        assertEquals("************1111", Redactor.NONE.redact("4111-1111-1111-1111"))
        assertEquals("************4444", Redactor.NONE.redact("5555555555554444"))
        assertEquals("***********0005", Redactor.NONE.redact("378282246310005")) // 15 digits
        assertEquals("*********2222", Redactor.NONE.redact("4222222222222")) // 13 digits
        assertEquals("***************0001", Redactor.NONE.redact("6011000000000000001")) // 19 digits
    }

    @Test
    fun `digit runs that fail luhn or have the wrong length are left alone`() {
        assertEquals("4111111111111112", Redactor.NONE.redact("4111111111111112"))
        assertEquals("order 123456789012", Redactor.NONE.redact("order 123456789012")) // 12 digits
        val twenty = "41111111111111111111"
        assertEquals(twenty, Redactor.NONE.redact(twenty)) // 20 digits: not a card, and not cut into pieces
        assertEquals("1700000000000", Redactor.NONE.redact("1700000000000")) // a 13 digit epoch that fails Luhn
        assertEquals("2026-10-05 12:30:00", Redactor.NONE.redact("2026-10-05 12:30:00"))
    }

    @Test
    fun `luhn check matches the known vectors`() {
        for (ok in listOf("4111111111111111", "5500005555555559", "378282246310005", "6011111111111117", "3530111333300000")) assertTrue(Redactor.luhn(ok), ok)
        for (bad in listOf("4111111111111112", "1234567812345678", "0000000000000001")) assertFalse(Redactor.luhn(bad), bad)
    }

    @Test
    fun `a card number inside json and between other text is masked`() {
        val out = Redactor.NONE.redact("""{"note":"paid with 4111 1111 1111 1111, thanks"}""")
        assertEquals("""{"note":"paid with ************1111, thanks"}""", out)
    }

    // ---- sensitive keys

    @Test
    fun `json values of sensitive keys are redacted in any case`() {
        for (key in listOf("cvv", "cvc", "cvv2", "password", "card_number", "cardNumber", "pan", "identityNumber", "tckn", "CVV", "Password", "CardNumber")) {
            assertEquals("""{"a":1,"$key":"[REDACTED]","b":2}""", Redactor.NONE.redact("""{"a":1,"$key":"some value","b":2}"""), key)
        }
        assertEquals("""{"cvv": "[REDACTED]"}""", Redactor.NONE.redact("""{"cvv": 123}"""))
        assertEquals("""{"tckn":"[REDACTED]"}""", Redactor.NONE.redact("""{"tckn":12345678901}"""))
        assertEquals("""{"password":"[REDACTED]","x":"y"}""", Redactor.NONE.redact("""{"password":"he said \"hi\" \\","x":"y"}"""))
        assertEquals("""{"pan":"[REDACTED]"}""", Redactor.NONE.redact("""{"pan":null}"""))
    }

    @Test
    fun `form values of sensitive keys are redacted`() {
        assertEquals("amount=1000&cvv=[REDACTED]&name=a", Redactor.NONE.redact("amount=1000&cvv=123&name=a"))
        assertEquals("card_number=[REDACTED]&x=1", Redactor.NONE.redact("card_number=4111111111111111&x=1"))
        assertEquals("password=[REDACTED]", Redactor.NONE.redact("password=hunter2"))
        assertEquals("payment[cvc]=[REDACTED]&b=2", Redactor.NONE.redact("payment[cvc]=987&b=2"))
        assertEquals("a=1&identityNumber=[REDACTED]", Redactor.NONE.redact("a=1&identityNumber=11111111110"))
    }

    @Test
    fun `keys that merely contain a sensitive name are left alone`() {
        assertEquals("""{"japan":"x","company":"y","panel":"z","passwords_count":3}""", Redactor.NONE.redact("""{"japan":"x","company":"y","panel":"z","passwords_count":3}"""))
        assertEquals("japan=1&company=2&span=3", Redactor.NONE.redact("japan=1&company=2&span=3"))
    }

    // ---- headers and urls

    @Test
    fun `sensitive header values are redacted whatever the case and other headers keep their value`() {
        val headers = mapOf(
            "Authorization" to "Bearer sk_live_4eC39HqLyjWDarjtT1zdp7dc",
            "PROXY-AUTHORIZATION" to "Basic abc",
            "Cookie" to "sid=1",
            "set-cookie" to "sid=2",
            "X-Api-Key" to "k",
            "Content-Type" to "application/json",
            "X-Echo" to "key=sk_live_4eC39HqLyjWDarjtT1zdp7dc"
        )
        val out = redactor.redactHeaders(headers)
        for (name in listOf("Authorization", "PROXY-AUTHORIZATION", "Cookie", "set-cookie", "X-Api-Key")) assertEquals("[REDACTED]", out[name], name)
        assertEquals("application/json", out["Content-Type"])
        assertEquals("key=[REDACTED]", out["X-Echo"], "secret values are removed from the other headers")
    }

    @Test
    fun `multi valued headers are handled the same way`() {
        val out = redactor.redactHeaders(mapOf("authorization" to listOf("a", "b"), "x-a" to listOf("1", "whsec_a/b+c=d")))
        assertEquals(listOf("[REDACTED]", "[REDACTED]"), out["authorization"])
        assertEquals(listOf("1", "[REDACTED]"), out["x-a"])
    }

    @Test
    fun `path tokens keep six characters and sensitive query values go`() {
        assertEquals("/api/market/payments/stripe/notify/3f9a1c…", Redactor.NONE.redactUrl("/api/market/payments/stripe/notify/3f9a1c7e5b2d4f6a8c0e1b3d5f7a9c2e"))
        assertEquals("/api/market/payments/stripe/webhook", Redactor.NONE.redactUrl("/api/market/payments/stripe/webhook"))
        assertEquals("/pay?token=[REDACTED]&x=1", Redactor.NONE.redactUrl("/pay?token=abcdef&x=1"))
        assertEquals("/pay?Signature=[REDACTED]&ref=ABC", Redactor.NONE.redactUrl("/pay?Signature=zzz&ref=ABC"))
        assertEquals("/a/[REDACTED]/b?x=1", redactor.redactUrl("/a/whsec_a/b+c=d/b?x=1"))
        assertEquals("/hook/[REDACTED]", Redactor(setOf("opaque-secret-value")).redactUrl("/hook/opaque-secret-value"))
    }

    @Test
    fun `redactOrNull passes null through`() {
        assertEquals(null, redactor.redactOrNull(null))
        assertEquals("[REDACTED]", Redactor(setOf("abcdefgh")).redactOrNull("abcdefgh"))
    }
}
