package com.panomc.plugins.marketfake

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Webhook signature of the fake gateway (17 section 6.3): header `X-Fake-Signature: t=<unix seconds>,v1=<hex>` with
 * `v1 = hex(HMAC-SHA256(secret, "<t>." + rawBody))`. Public so tools and tests can use the same scheme.
 */
object FakeSignature {
    const val HEADER = "x-fake-signature"

    /** Largest accepted distance between the signed time and now. */
    const val TOLERANCE_SECONDS = 300L

    /** Lower-case hex of the HMAC over `"<t>." + body`. */
    fun sign(secret: String, timestampSeconds: Long, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update("$timestampSeconds.".toByteArray(Charsets.UTF_8))
        return mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }

    /** The complete header value. */
    fun header(secret: String, timestampSeconds: Long, body: ByteArray): String =
        "t=$timestampSeconds,v1=${sign(secret, timestampSeconds, body)}"

    /**
     * True when the header is present, well formed, within [TOLERANCE_SECONDS] of [nowMs] and one of its `v1` values
     * matches. The comparison is constant time over the hex text; hex digits compare case-insensitively.
     */
    fun verify(secret: String, header: String?, body: ByteArray, nowMs: Long): Boolean {
        if (header.isNullOrBlank()) return false
        var timestamp: Long? = null
        val candidates = ArrayList<String>()
        for (part in header.split(',')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val name = part.substring(0, eq).trim()
            val value = part.substring(eq + 1).trim()
            when (name) {
                "t" -> if (timestamp == null) timestamp = value.toLongOrNull()
                "v1" -> candidates.add(value)
            }
        }
        val t = timestamp ?: return false
        if (candidates.isEmpty()) return false
        val distance = Math.abs(nowMs / 1000L - t)
        if (distance > TOLERANCE_SECONDS) return false
        val expected = sign(secret, t, body).toByteArray(Charsets.US_ASCII)
        var ok = false
        for (candidate in candidates) {
            // Every candidate is compared (no early exit between them) to keep the timing independent of which one matches.
            if (MessageDigest.isEqual(expected, candidate.lowercase().toByteArray(Charsets.US_ASCII))) ok = true
        }
        return ok
    }
}
