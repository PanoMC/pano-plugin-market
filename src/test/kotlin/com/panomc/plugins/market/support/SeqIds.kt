package com.panomc.plugins.market.support

import com.panomc.plugins.market.core.time.Ids
import java.util.concurrent.atomic.AtomicLong

/**
 * Deterministic [Ids] (17 section 5.5): one counter shared by every method, starting at 1, so no two values of
 * any kind repeat. Alphabets and lengths equal the production formats of [com.panomc.plugins.market.core.time.SecureIds].
 *
 * - `publicId`: the counter in Crockford base32, left-padded with `0` to 20 characters
 * - `reference`: `T` followed by the counter in decimal, left-padded with `0` to 19 digits
 * - `hexToken(n)`: the counter in lower-case hex, left-padded with `0` to `2 * n` characters (the low-order
 *   characters when the counter is wider than the token)
 * - `uuid`: `00000000-0000-4000-8000-<12 hex digits of the counter>`
 */
class SeqIds(start: Long = 0L) : Ids {
    private val counter = AtomicLong(start)

    /** Number of values handed out so far. */
    val issued: Long get() = counter.get()

    private fun next(): Long = counter.incrementAndGet()

    override fun publicId(): String {
        var v = next()
        val out = CharArray(Ids.PUBLIC_ID_LENGTH) { '0' }
        var i = out.size - 1
        while (v > 0 && i >= 0) {
            out[i--] = Ids.CROCKFORD_ALPHABET[(v and 31).toInt()]
            v = v ushr 5
        }
        return String(out)
    }

    override fun hexToken(bytes: Int): String {
        require(bytes > 0) { "bytes must be positive" }
        val hex = java.lang.Long.toHexString(next())
        val width = bytes * 2
        return if (hex.length >= width) hex.takeLast(width) else hex.padStart(width, '0')
    }

    override fun reference(): String = "T" + next().toString().padStart(Ids.REFERENCE_LENGTH - 1, '0')

    override fun uuid(): String = "00000000-0000-4000-8000-" + java.lang.Long.toHexString(next()).padStart(12, '0').takeLast(12)
}
