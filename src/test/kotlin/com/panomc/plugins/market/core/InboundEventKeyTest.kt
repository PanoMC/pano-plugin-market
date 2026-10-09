package com.panomc.plugins.market.core

import com.panomc.plugins.market.core.abuse.Redactor
import com.panomc.plugins.market.routes.api.payment.InboundEventKey
import com.panomc.plugins.market.routes.api.payment.StoredRequestCodec
import com.panomc.plugins.market.spi.common.InboundKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import com.panomc.plugins.market.util.MarketPaths

/**
 * The key rules of 02 section 7.3 (steps 2 and 5) and 01 section 6.3 for inbound `market_payment_event` rows (17 section 11.1 `InboundEventKeyTest`):
 * a stored request starts with `r:<uuid>`, a provider key becomes `e:<key>`, a superseded row keeps its key with `:<id>`, and the request hash is a
 * diagnostics value over `kind 0x00 channel 0x00 attemptToken-or-empty 0x00 method 0x00 rawQuery 0x00 body`, never a de-duplication key.
 */
class InboundEventKeyTest {
    private val uuid = "00000000-0000-4000-8000-000000000007"

    @Test
    fun `a stored request is keyed r colon uuid, a provider key e colon key`() {
        assertEquals("r:$uuid", InboundEventKey.received(uuid))
        assertTrue(InboundEventKey.isReceivedKey(InboundEventKey.received(uuid)))
        assertFalse(InboundEventKey.isProviderKey(InboundEventKey.received(uuid)))

        assertEquals("e:evt_1Abc", InboundEventKey.provider("evt_1Abc"))
        assertEquals("e:WH-0123456789", InboundEventKey.provider("WH-0123456789"))
        assertTrue(InboundEventKey.isProviderKey(InboundEventKey.provider("evt_1Abc")))

        // a forged request can never occupy the key of a genuine delivery: the two namespaces cannot meet
        assertNotEquals(InboundEventKey.received("evt_1Abc"), InboundEventKey.provider("evt_1Abc"))
    }

    @Test
    fun `a blank provider key is refused`() {
        for (blank in listOf("", " ", "\t")) {
            val e = runCatching { InboundEventKey.provider(blank) }.exceptionOrNull()

            assertTrue(e is IllegalArgumentException, "'$blank' -> $e")
        }
    }

    @Test
    fun `a long provider key is stored as its SHA-256 so that the superseded suffix always fits the column`() {
        val long = "k".repeat(100)
        val stored = InboundEventKey.provider(long)

        assertEquals("e:sha256:e37c7cb78ccb30f0e2036576d681d619949c8a9fb885c91a07da6b845788a9ce", stored)
        assertEquals(stored, InboundEventKey.provider(long), "the same key always maps to the same stored key")
        assertNotEquals(stored, InboundEventKey.provider("k".repeat(101)))

        // the longest key that is kept literal, and the longest superseded form of any key, fit VARCHAR(128)
        val literal = InboundEventKey.provider("x".repeat(InboundEventKey.MAX_PROVIDER_KEY))

        assertEquals(2 + InboundEventKey.MAX_PROVIDER_KEY, literal.length)
        assertTrue(InboundEventKey.superseded(literal, Long.MAX_VALUE).length <= InboundEventKey.COLUMN_LENGTH)
        assertTrue(InboundEventKey.superseded(stored, Long.MAX_VALUE).length <= InboundEventKey.COLUMN_LENGTH)

        // no literal key can produce the hashed form
        assertEquals("e:sha256:" + InboundEventKey.sha256Hex("sha256:abc".toByteArray()), InboundEventKey.provider("sha256:abc"))
        assertNotEquals("e:sha256:abc", InboundEventKey.provider("sha256:abc"))
    }

    @Test
    fun `a superseded row keeps its key with the row id appended`() {
        assertEquals("e:evt_1:42", InboundEventKey.superseded("e:evt_1", 42))
        assertEquals("r:$uuid:7", InboundEventKey.superseded("r:$uuid", 7))
        assertNotEquals(InboundEventKey.provider("evt_1"), InboundEventKey.superseded(InboundEventKey.provider("evt_1"), 42), "the key is free for the redelivery")
    }

    @Test
    fun `the request hash is the SHA-256 of kind, channel, token, method, query and body joined by NUL (one fixed vector)`() {
        val hash = InboundEventKey.requestHash(InboundKind.WEBHOOK, "default", null, "POST", "a=1&b=2", "{\"id\":\"evt_1\"}".toByteArray(StandardCharsets.UTF_8))

        assertEquals("35a6ec915c4c0bde7552472bce4f35cdb8b66c972c9c2f35827089d4677f5fa4", hash)
        assertEquals(64, hash.length)

        // an empty token / query / body (Paymentwall pingbacks are GETs with an empty body)
        assertEquals(
            "bdfd889ab9c2701c28cf8b91f4808ec6c276fed90781f9f6581cffa829c82a8a",
            InboundEventKey.requestHash(InboundKind.NOTIFY, "default", "0123456789abcdef0123456789abcdef01234567", "GET", null, ByteArray(0))
        )
        assertEquals(
            InboundEventKey.requestHash(InboundKind.NOTIFY, "default", "0123456789abcdef0123456789abcdef01234567", "GET", "", ByteArray(0)),
            InboundEventKey.requestHash(InboundKind.NOTIFY, "default", "0123456789abcdef0123456789abcdef01234567", "GET", null, ByteArray(0)),
            "no query and an empty query hash alike"
        )
        // the query is hashed as it was sent, undecoded
        assertEquals(
            "4cbb45bc10f521a2d9917ab854e68cd86c15a53bf688060958beae7609db7739",
            InboundEventKey.requestHash(InboundKind.RETURN, "default", "0123456789abcdef0123456789abcdef01234567", "GET", "x=%C3%BC", ByteArray(0))
        )
    }

    @Test
    fun `every part of the request changes the hash, the parts cannot be shifted into each other`() {
        val base = InboundEventKey.requestHash(InboundKind.WEBHOOK, "default", null, "POST", "a=1", "body".toByteArray())

        assertNotEquals(base, InboundEventKey.requestHash(InboundKind.NOTIFY, "default", "t", "POST", "a=1", "body".toByteArray()))
        assertNotEquals(base, InboundEventKey.requestHash(InboundKind.WEBHOOK, "other", null, "POST", "a=1", "body".toByteArray()))
        assertNotEquals(base, InboundEventKey.requestHash(InboundKind.WEBHOOK, "default", null, "GET", "a=1", "body".toByteArray()))
        assertNotEquals(base, InboundEventKey.requestHash(InboundKind.WEBHOOK, "default", null, "POST", "a=2", "body".toByteArray()))
        assertNotEquals(base, InboundEventKey.requestHash(InboundKind.WEBHOOK, "default", null, "POST", "a=1", "body2".toByteArray()))
        // a byte moved from the query into the body is another request (the NUL separator keeps the parts apart)
        assertNotEquals(
            InboundEventKey.requestHash(InboundKind.WEBHOOK, "default", null, "POST", "a", "b".toByteArray()),
            InboundEventKey.requestHash(InboundKind.WEBHOOK, "default", null, "POST", "ab", ByteArray(0))
        )
    }

    @Test
    fun `two requests with an equal hash and no provider key are both applied, the hash identifies nothing`() {
        // Mollie posts the identical body id=tr_x for every state change: the hash is equal, the facts differ
        val a = InboundEventKey.requestHash(InboundKind.WEBHOOK, "default", null, "POST", null, "id=tr_x".toByteArray())
        val b = InboundEventKey.requestHash(InboundKind.WEBHOOK, "default", null, "POST", null, "id=tr_x".toByteArray())

        assertEquals(a, b)

        // each of the two stored requests gets its own random key, so the unique key never holds one of them back
        val keys = setOf(InboundEventKey.received("00000000-0000-4000-8000-000000000001"), InboundEventKey.received("00000000-0000-4000-8000-000000000002"))

        assertEquals(2, keys.size)
        assertTrue(keys.none { it.contains(a) }, "the hash is not part of any key")
    }

    // ------------------------------------------------------------------------------------------------ the stored raw request

    @Test
    fun `a stored request keeps headers, query, form and body verbatim and settles to a redacted form`() {
        val headers = mapOf("content-type" to listOf("application/json"), "x-signature" to listOf("t=1,v1=abcdef0123456789secret"), "authorization" to listOf("Bearer top-secret-token"))
        val secret = "abcdef0123456789secret"
        val call = com.panomc.plugins.market.routes.api.payment.InboundCall(
            InboundKind.WEBHOOK, "fake", "default", null, null, null, "POST", "${MarketPaths.SITE_ROOT}/payments/fake/webhook", "a=1&sig=$secret", mapOf("a" to listOf("1")), headers,
            "application/json", "{\"card\":\"4111111111111111\",\"k\":\"$secret\"}".toByteArray(), null, "203.0.113.5", 1L
        )
        val stored = StoredRequestCodec.headersJson(call)
        val parsed = StoredRequestCodec.parse(stored, StoredRequestCodec.bodyText(call.body))

        // verbatim while the row can be replayed
        assertEquals(headers, parsed.headers)
        assertEquals("a=1&sig=$secret", parsed.rawQuery)
        assertNull(parsed.form)
        assertTrue(call.body.contentEquals(parsed.body))

        // settled: reserved keys gone, sensitive headers and secret values removed, the card number masked
        val redactor = Redactor(setOf(secret))
        val settledHeaders = StoredRequestCodec.settledHeaders(stored, redactor)!!
        val settledBody = StoredRequestCodec.settledBody(StoredRequestCodec.bodyText(call.body), stored, redactor, 65536)!!

        assertFalse(settledHeaders.contains(secret), settledHeaders)
        assertFalse(settledHeaders.contains(":query"), settledHeaders)
        assertFalse(settledHeaders.contains("top-secret-token"), settledHeaders)
        assertFalse(settledBody.contains(secret), settledBody)
        assertFalse(settledBody.contains("4111111111111111"), settledBody)
        assertTrue(settledBody.contains("1111"), settledBody)
    }

    @Test
    fun `a body that is not UTF-8 survives as Base64 and a multipart form is kept`() {
        val binary = byteArrayOf(0x01, 0x02, 0xff.toByte(), 0xfe.toByte(), 0x00, 0x7f)
        val call = com.panomc.plugins.market.routes.api.payment.InboundCall(
            InboundKind.NOTIFY, "fake", "default", "0123456789abcdef0123456789abcdef01234567", null, null, "POST", "/p", null, emptyMap(), mapOf("content-type" to listOf("application/octet-stream")),
            "application/octet-stream", binary, mapOf("field" to listOf("one", "two")), "198.51.100.1", 1L
        )
        val headers = StoredRequestCodec.headersJson(call)
        val parsed = StoredRequestCodec.parse(headers, StoredRequestCodec.bodyText(binary))

        assertTrue(binary.contentEquals(parsed.body), "binary body must round trip")
        assertEquals(mapOf("field" to listOf("one", "two")), parsed.form)
        assertEquals("[binary 6 bytes]", StoredRequestCodec.settledBody(StoredRequestCodec.bodyText(binary), headers, Redactor(), 100))
    }

    @Test
    fun `a settled body is cut to the byte limit, never inside a character`() {
        val text = "é".repeat(100) // two bytes each

        assertEquals(200, text.toByteArray().size)
        assertEquals("é".repeat(50), StoredRequestCodec.truncateUtf8(text, 100))
        assertEquals("é".repeat(49), StoredRequestCodec.truncateUtf8(text, 99), "99 bytes would end inside a character")
        assertEquals(text, StoredRequestCodec.truncateUtf8(text, 200))
        assertEquals("", StoredRequestCodec.truncateUtf8(text, 1))
    }
}
