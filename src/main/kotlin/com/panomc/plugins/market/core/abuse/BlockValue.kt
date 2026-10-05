package com.panomc.plugins.market.core.abuse

/** Block list value types and normalisation (11 section 9.1). Pure. */
enum class BlockType { PLAYER, USER, EMAIL, IP }

object BlockValue {
    const val MAX_EMAIL_LENGTH = 254
    const val MIN_IPV4_PREFIX = 8
    const val MIN_IPV6_PREFIX = 16

    private val ADMIN_USERNAME = Regex("^[A-Za-z0-9_.*]{1,32}$")
    private val DOMAIN_LABEL = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$")

    /** The stored text of [raw] for [type], or null when it is invalid (400 `INVALID_BLOCK` reason `VALUE`). */
    fun normalize(type: BlockType, raw: String?): String? {
        if (raw == null) return null
        return when (type) {
            BlockType.PLAYER -> normalizePlayer(raw)
            BlockType.USER -> normalizeUser(raw)
            BlockType.EMAIL -> normalizeEmail(raw)
            BlockType.IP -> normalizeIp(raw)
        }
    }

    fun normalize(type: String, raw: String?): String? =
        BlockType.entries.firstOrNull { it.name == type }?.let { normalize(it, raw) }

    /** True when [raw] is a well-formed CIDR that is broader than the limits (`reason: RANGE_TOO_WIDE`). */
    fun isRangeTooWide(raw: String?): Boolean {
        val r = IpRange.parse(raw) ?: return false
        return tooWide(r)
    }

    /** E-mail rule of 6.2: at most 254 chars, one `@`, no whitespace / control chars / `,` `;` `<` `>`; lower-cased. */
    fun normalizeEmailAddress(raw: String?): String? {
        if (raw == null) return null
        val t = raw.trim().lowercase()
        if (t.isEmpty() || t.length > MAX_EMAIL_LENGTH || hasForbidden(t)) return null
        val at = t.indexOf('@')
        if (at <= 0 || at != t.lastIndexOf('@') || at == t.length - 1) return null
        return if (validDomain(t.substring(at + 1), requireDot = false)) t else null
    }

    /** Domain part of an address (lower-cased), null when [email] has no single `@`. */
    fun domainOf(email: String): String? {
        val at = email.lastIndexOf('@')
        return if (at < 0 || at == email.length - 1) null else email.substring(at + 1).lowercase()
    }

    private fun normalizePlayer(raw: String): String? {
        val t = raw.trim()
        if (!ADMIN_USERNAME.matches(t) || t.none { it.isLetterOrDigit() }) return null
        return t.lowercase()
    }

    private fun normalizeUser(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty() || t.length > 19 || !t.all { it in '0'..'9' }) return null
        val id = t.toLongOrNull() ?: return null
        return if (id > 0) id.toString() else null
    }

    private fun normalizeEmail(raw: String): String? {
        val t = raw.trim().lowercase()
        if (t.startsWith("@")) {
            if (t.length > MAX_EMAIL_LENGTH || hasForbidden(t)) return null
            val domain = t.substring(1)
            return if (validDomain(domain, requireDot = true)) t else null
        }
        return normalizeEmailAddress(t)
    }

    private fun normalizeIp(raw: String): String? {
        val r = IpRange.parse(raw) ?: return null
        if (tooWide(r)) return null
        return r.canonical()
    }

    private fun tooWide(r: IpRange): Boolean = r.prefix < (if (r.isV4) MIN_IPV4_PREFIX else MIN_IPV6_PREFIX)

    private fun hasForbidden(t: String): Boolean =
        t.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F || it in ",;<>" }

    private fun validDomain(domain: String, requireDot: Boolean): Boolean {
        if (domain.isEmpty() || domain.length > 253) return false
        if (requireDot && !domain.contains('.')) return false
        return domain.split('.').all { it.length <= 63 && DOMAIN_LABEL.matches(it) }
    }
}
