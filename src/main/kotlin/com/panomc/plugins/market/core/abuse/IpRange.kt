package com.panomc.plugins.market.core.abuse

/**
 * An IPv4 / IPv6 address or CIDR range (11 section 9.1, 2 rule 4). Pure: no `InetAddress`, so parsing never resolves a
 * name. Host bits are zeroed on parse; [canonical] is the stored text of a block value.
 *
 * An IPv4-mapped IPv6 address (`::ffff:1.2.3.4`, no prefix) is the IPv4 address, so a dual-stack socket and a
 * block entry written in dotted form match each other.
 */
class IpRange private constructor(private val bytes: ByteArray, val prefix: Int) {
    val isV4: Boolean get() = bytes.size == 4
    val bits: Int get() = bytes.size * 8
    val isSingle: Boolean get() = prefix == bits

    fun contains(ip: String): Boolean {
        val other = parse(ip) ?: return false
        if (!other.isSingle || other.bytes.size != bytes.size) return false
        return prefixEquals(bytes, other.bytes, prefix)
    }

    /** Canonical text: dotted quad / compressed lower-case IPv6; the `/prefix` only when it is not `/32` or `/128`. */
    fun canonical(): String {
        val addr = if (isV4) v4Text(bytes) else v6Text(bytes)
        return if (isSingle) addr else "$addr/$prefix"
    }

    /** The address part only. */
    fun addressText(): String = if (isV4) v4Text(bytes) else v6Text(bytes)

    internal fun groups(): IntArray = IntArray(8) { ((bytes[it * 2].toInt() and 0xFF) shl 8) or (bytes[it * 2 + 1].toInt() and 0xFF) }

    internal fun octets(): IntArray = IntArray(bytes.size) { bytes[it].toInt() and 0xFF }

    override fun equals(other: Any?): Boolean = other is IpRange && prefix == other.prefix && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode() * 31 + prefix

    override fun toString(): String = canonical()

    companion object {
        /** Address or CIDR; null when it is neither. */
        fun parse(raw: String?): IpRange? {
            if (raw == null) return null
            val text = raw.trim()
            if (text.isEmpty()) return null
            val slash = text.indexOf('/')
            val addrText = if (slash >= 0) text.substring(0, slash) else text
            val prefixText = if (slash >= 0) text.substring(slash + 1) else null
            var addr = parseAddress(addrText) ?: return null
            var prefix = addr.size * 8
            if (prefixText != null) {
                if (prefixText.isEmpty() || prefixText.length > 3 || !prefixText.all { it in '0'..'9' }) return null
                prefix = prefixText.toInt()
                if (prefix > addr.size * 8) return null
            } else if (addr.size == 16 && isMapped(addr)) {
                addr = addr.copyOfRange(12, 16)
                prefix = 32
            }
            return IpRange(zeroHostBits(addr, prefix), prefix)
        }

        /** A single address (no `/`); null otherwise. */
        fun parseAddressOnly(raw: String?): IpRange? = if (raw == null || raw.contains('/')) null else parse(raw)

        /**
         * Rate-limit / throttle key: IPv4 as dotted quad, IPv6 by its `/64` prefix (`2001:db8:1:2::/64`). Null for an
         * invalid address.
         */
        fun bucketKey(ip: String?): String? {
            val a = parseAddressOnly(ip) ?: return null
            if (a.isV4) return a.canonical()
            return IpRange(zeroHostBits(a.bytes, 64), 64).canonical()
        }

        private fun isMapped(b: ByteArray): Boolean {
            for (i in 0 until 10) if (b[i].toInt() != 0) return false
            return b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()
        }

        private fun parseAddress(t: String): ByteArray? = if (t.contains(':')) parseV6(t) else parseV4(t)

        private fun parseV4(t: String): ByteArray? {
            val parts = t.split('.')
            if (parts.size != 4) return null
            val out = ByteArray(4)
            for ((i, p) in parts.withIndex()) {
                if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
                if (p.length > 1 && p[0] == '0') return null
                val v = p.toInt()
                if (v > 255) return null
                out[i] = v.toByte()
            }
            return out
        }

        private fun parseV6(t: String): ByteArray? {
            if (t.length > 45 || t.contains('%')) return null
            val dbl = t.indexOf("::")
            if (dbl >= 0 && t.indexOf("::", dbl + 1) >= 0) return null
            val head = if (dbl >= 0) t.substring(0, dbl) else t
            val tail = if (dbl >= 0) t.substring(dbl + 2) else ""
            if (head.contains('.')) return null
            val h = if (head.isEmpty()) emptyList() else groupsOf(head) ?: return null
            val tl = if (tail.isEmpty()) emptyList() else groupsOf(tail) ?: return null
            // a dotted quad is only allowed as the very last element
            val all = h + tl
            if (dbl < 0 && all.size != 8) return null
            if (dbl >= 0 && all.size > 7) return null
            val groups = IntArray(8)
            h.forEachIndexed { i, g -> groups[i] = g }
            tl.forEachIndexed { i, g -> groups[8 - tl.size + i] = g }
            val out = ByteArray(16)
            for (i in 0 until 8) {
                out[i * 2] = (groups[i] shr 8).toByte()
                out[i * 2 + 1] = (groups[i] and 0xFF).toByte()
            }
            return out
        }

        private fun groupsOf(part: String): List<Int>? {
            val items = part.split(':')
            val out = ArrayList<Int>()
            for ((i, item) in items.withIndex()) {
                if (item.contains('.')) {
                    if (i != items.size - 1) return null
                    val v4 = parseV4(item) ?: return null
                    out.add(((v4[0].toInt() and 0xFF) shl 8) or (v4[1].toInt() and 0xFF))
                    out.add(((v4[2].toInt() and 0xFF) shl 8) or (v4[3].toInt() and 0xFF))
                } else {
                    if (item.isEmpty() || item.length > 4) return null
                    var v = 0
                    for (c in item) {
                        val d = Character.digit(c, 16)
                        if (d < 0 || c.code > 127) return null
                        v = v * 16 + d
                    }
                    out.add(v)
                }
            }
            return out
        }

        private fun zeroHostBits(b: ByteArray, prefix: Int): ByteArray {
            val out = b.copyOf()
            for (i in out.indices) {
                val keep = (prefix - i * 8).coerceIn(0, 8)
                val mask = if (keep == 0) 0 else (0xFF shl (8 - keep)) and 0xFF
                out[i] = (out[i].toInt() and mask).toByte()
            }
            return out
        }

        private fun prefixEquals(a: ByteArray, b: ByteArray, prefix: Int): Boolean {
            for (i in a.indices) {
                val keep = (prefix - i * 8).coerceIn(0, 8)
                if (keep == 0) break
                val mask = (0xFF shl (8 - keep)) and 0xFF
                if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
            }
            return true
        }

        private fun v4Text(b: ByteArray): String = b.joinToString(".") { (it.toInt() and 0xFF).toString() }

        /** RFC 5952: lower-case, leading zeros dropped, the longest run (>= 2) of zero groups becomes `::`. */
        private fun v6Text(b: ByteArray): String {
            val g = IntArray(8) { ((b[it * 2].toInt() and 0xFF) shl 8) or (b[it * 2 + 1].toInt() and 0xFF) }
            var bestStart = -1
            var bestLen = 0
            var i = 0
            while (i < 8) {
                if (g[i] == 0) {
                    var j = i
                    while (j < 8 && g[j] == 0) j++
                    if (j - i > bestLen) {
                        bestStart = i
                        bestLen = j - i
                    }
                    i = j
                } else i++
            }
            if (bestLen < 2) return g.joinToString(":") { it.toString(16) }
            val left = (0 until bestStart).joinToString(":") { g[it].toString(16) }
            val right = (bestStart + bestLen until 8).joinToString(":") { g[it].toString(16) }
            return "$left::$right"
        }
    }
}
