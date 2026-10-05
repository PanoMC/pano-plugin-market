package com.panomc.plugins.market.core.time

import java.security.SecureRandom

/**
 * The single source of random identifiers (17 section 4 S2): public ids, tokens, gateway references, UUIDs.
 * Tests replace it with a deterministic implementation that keeps the same alphabets and lengths.
 */
interface Ids {
    /** 20 Crockford base32 characters (100 random bits), see [PUBLIC_ID_REGEX]. */
    fun publicId(): String

    /** `bytes` random bytes as lower-case hex (`2 * bytes` characters). */
    fun hexToken(bytes: Int): String

    /** 20 characters of `[A-Z0-9]`, sent to a gateway as the merchant reference, see [REFERENCE_REGEX]. */
    fun reference(): String

    /** Random (version 4) UUID in canonical lower-case form. */
    fun uuid(): String

    companion object {
        /** Crockford base32: digits and letters without I, L, O, U. */
        const val CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        const val PUBLIC_ID_LENGTH = 20
        const val REFERENCE_LENGTH = 20

        val PUBLIC_ID_REGEX = Regex("^[0-9A-HJKMNP-TV-Z]{20}$")
        val REFERENCE_REGEX = Regex("^[A-Z0-9]{20}$")
        val UUID_REGEX = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

        internal const val HEX = "0123456789abcdef"
        internal const val REFERENCE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    }
}

/** Production implementation, backed by [SecureRandom]. */
class SecureIds(private val random: SecureRandom = SecureRandom()) : Ids {
    override fun publicId(): String {
        val out = CharArray(Ids.PUBLIC_ID_LENGTH)
        for (i in out.indices) out[i] = Ids.CROCKFORD_ALPHABET[random.nextInt(32)] // 5 bits per character
        return String(out)
    }

    override fun hexToken(bytes: Int): String {
        require(bytes > 0) { "bytes must be positive" }
        val raw = ByteArray(bytes)
        random.nextBytes(raw)
        return hex(raw)
    }

    override fun reference(): String {
        val out = CharArray(Ids.REFERENCE_LENGTH)
        for (i in out.indices) out[i] = Ids.REFERENCE_ALPHABET[random.nextInt(Ids.REFERENCE_ALPHABET.length)]
        return String(out)
    }

    override fun uuid(): String {
        val raw = ByteArray(16)
        random.nextBytes(raw)
        raw[6] = ((raw[6].toInt() and 0x0f) or 0x40).toByte() // version 4
        raw[8] = ((raw[8].toInt() and 0x3f) or 0x80).toByte() // IETF variant
        val h = hex(raw)
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    private fun hex(raw: ByteArray): String {
        val out = CharArray(raw.size * 2)
        for (i in raw.indices) {
            val v = raw[i].toInt() and 0xff
            out[i * 2] = Ids.HEX[v ushr 4]
            out[i * 2 + 1] = Ids.HEX[v and 0x0f]
        }
        return String(out)
    }
}
