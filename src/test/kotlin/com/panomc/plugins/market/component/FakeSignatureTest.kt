package com.panomc.plugins.market.component

import com.panomc.plugins.market.support.FakePayGateway
import com.panomc.plugins.marketfake.FakeSignature
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 17 section 6.3: the signature scheme of the fake gateway, pinned to the self-derived vector. */
class FakeSignatureTest {
    private val secret = "fake_secret_0123456789"
    private val t = 1_760_000_000L
    private val body =
        """{"id":"evt_0001","type":"payment.succeeded","data":{"reference":"A1B2C3D4E5F6G7H8J9K0","amount":"12.34","currency":"EUR"}}"""
            .toByteArray(Charsets.UTF_8)
    private val vector = "d1f544c6db826f925b68d210fed0af2b964a5a5a2ecbf402da056c7da7bdbb45"
    private val nowMs = t * 1000L

    @Test
    fun `the published vector is produced by the provider side`() {
        assertEquals(vector, FakeSignature.sign(secret, t, body))
        assertEquals("t=$t,v1=$vector", FakeSignature.header(secret, t, body))
    }

    @Test
    fun `the simulator side signs independently to the same vector`() {
        assertEquals(vector, FakePayGateway.hmac(secret, t, body))
    }

    @Test
    fun `a correct header verifies`() {
        assertTrue(FakeSignature.verify(secret, "t=$t,v1=$vector", body, nowMs))
    }

    @Test
    fun `time window is five minutes either way`() {
        val header = "t=$t,v1=$vector"
        assertTrue(FakeSignature.verify(secret, header, body, nowMs + 300_000L))
        assertTrue(FakeSignature.verify(secret, header, body, nowMs - 300_000L))
        assertFalse(FakeSignature.verify(secret, header, body, nowMs + 301_000L))
        assertFalse(FakeSignature.verify(secret, header, body, nowMs - 301_000L))
        assertFalse(FakeSignature.verify(secret, header, body, nowMs + 3_600_000L))
    }

    @Test
    fun `the timestamp is part of the signed text`() {
        // Same hex, other t: must fail even inside the window.
        assertFalse(FakeSignature.verify(secret, "t=${t + 1},v1=$vector", body, nowMs))
    }

    @Test
    fun `a changed body, secret or one flipped character fails`() {
        val header = "t=$t,v1=$vector"
        assertFalse(FakeSignature.verify(secret, header, body + " ".toByteArray(), nowMs))
        assertFalse(FakeSignature.verify("another_secret", header, body, nowMs))
        val flipped = vector.dropLast(1) + (if (vector.last() == '5') '4' else '5')
        assertFalse(FakeSignature.verify(secret, "t=$t,v1=$flipped", body, nowMs))
        assertFalse(FakeSignature.verify(secret, "t=$t,v1=${vector.dropLast(2)}", body, nowMs))
    }

    @Test
    fun `missing blank and malformed headers fail`() {
        for (header in listOf(null, "", "   ", "garbage", "t=$t", "v1=$vector", "t=abc,v1=$vector", "t=,v1=$vector", "t=$t,v1=", "t=$t,v2=$vector", ",,,", "=,=", "t=$t;v1=$vector")) {
            assertFalse(FakeSignature.verify(secret, header, body, nowMs), "accepted '$header'")
        }
    }

    @Test
    fun `hex compares case-insensitively and any v1 of several may match`() {
        assertTrue(FakeSignature.verify(secret, "t=$t,v1=${vector.uppercase()}", body, nowMs))
        assertTrue(FakeSignature.verify(secret, "t=$t,v1=deadbeef,v1=$vector", body, nowMs))
        assertTrue(FakeSignature.verify(secret, " t=$t , v1=$vector ", body, nowMs))
        assertFalse(FakeSignature.verify(secret, "t=$t,v1=deadbeef,v1=cafebabe", body, nowMs))
    }

    @Test
    fun `the simulator's signature modes behave as named`() {
        val gateway = FakePayGateway(secret)
        gateway.use {
            val data = JsonObject().put("reference", "R1")
            fun accepted(mode: FakePayGateway.Signature): Boolean {
                val request = gateway.inbound("payment.pending", data, "evt_1", mode, nowMs)
                return FakeSignature.verify(secret, request.header(FakeSignature.HEADER), request.body, nowMs)
            }
            assertTrue(accepted(FakePayGateway.Signature.VALID))
            assertFalse(accepted(FakePayGateway.Signature.INVALID))
            assertFalse(accepted(FakePayGateway.Signature.MISSING))
            assertFalse(accepted(FakePayGateway.Signature.STALE))
            assertEquals(null, gateway.inbound("payment.pending", data, "evt_1", FakePayGateway.Signature.MISSING, nowMs).header(FakeSignature.HEADER))
        }
    }
}
