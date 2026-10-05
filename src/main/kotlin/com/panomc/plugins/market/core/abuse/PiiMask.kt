package com.panomc.plugins.market.core.abuse

/** Masking of personal data for callers without the PII tier (11 sections 14.5, 15 rule 3). Pure. */
object PiiMask {
    private const val MASKED = "***"

    /** `john@example.com` -> `j***@e***.com`; the top-level label stays. */
    fun email(raw: String?): String? {
        if (raw == null) return null
        val at = raw.lastIndexOf('@')
        if (at < 0) return MASKED
        val local = raw.substring(0, at)
        val domain = raw.substring(at + 1)
        val maskedLocal = if (local.isEmpty()) MASKED else firstChar(local) + MASKED
        if (domain.isEmpty()) return "$maskedLocal@$MASKED"
        val dot = domain.lastIndexOf('.')
        val maskedDomain = if (dot <= 0) firstChar(domain) + MASKED else firstChar(domain) + MASKED + domain.substring(dot)
        return "$maskedLocal@$maskedDomain"
    }

    /**
     * `203.0.113.57` -> `203.0.113.x`; IPv6 keeps the first three groups (`2001:db8:1::x`); a CIDR keeps its prefix
     * length (`203.0.113.0/24` -> `203.0.113.x/24`).
     */
    fun ip(raw: String?): String? {
        if (raw == null) return null
        val r = IpRange.parse(raw) ?: return MASKED
        val masked = if (r.isV4) {
            val o = r.octets()
            "${o[0]}.${o[1]}.${o[2]}.x"
        } else {
            val g = r.groups()
            "${g[0].toString(16)}:${g[1].toString(16)}:${g[2].toString(16)}::x"
        }
        return if (r.isSingle) masked else "$masked/${r.prefix}"
    }

    private fun firstChar(s: String): String = s.substring(0, s.offsetByCodePoints(0, 1))
}
