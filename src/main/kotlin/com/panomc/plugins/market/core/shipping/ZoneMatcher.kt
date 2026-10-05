package com.panomc.plugins.market.core.shipping

import com.panomc.plugins.market.spi.common.Address
import java.text.Normalizer
import java.util.Locale

/** `regions[]` entry of a zone: only addresses of [country] in one of [states] match (empty = no restriction). */
class ZoneRegion(val country: String, val states: List<String>)

/** A shipping zone as the matcher sees it (10 section 4); [countries] may be `["*"]`. */
class Zone(
    val id: Long,
    val countries: List<String>,
    val regions: List<ZoneRegion>? = null,
    val postalPatterns: List<String>? = null,
    val position: Int = 0,
    val active: Boolean = true
)

/** Zone matching (10 section 4.1). Pure. */
object ZoneMatcher {
    const val WILDCARD = "*"

    private val TURKISH = mapOf(
        'İ' to 'i', 'I' to 'i', 'ı' to 'i', 'Ş' to 's', 'ş' to 's', 'Ğ' to 'g', 'ğ' to 'g', 'Ü' to 'u', 'ü' to 'u',
        'Ö' to 'o', 'ö' to 'o', 'Ç' to 'c', 'ç' to 'c'
    )
    private val SPACES = Regex("\\s+")
    private val MARKS = Regex("\\p{Mn}+")

    /**
     * Comparison key of a state / city: the Turkish letters are mapped to their ASCII twin, then lower-cased, other
     * accents are removed, the text is trimmed and inner spaces collapse (`İSTANBUL` = `istanbul` = `Istanbul`).
     */
    fun norm(s: String): String {
        val mapped = buildString { s.forEach { append(TURKISH[it] ?: it) } }
        val folded = Normalizer.normalize(mapped.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(MARKS, "")

        return folded.trim().replace(SPACES, " ")
    }

    /** First `ACTIVE` zone (by `position`, `id`) that matches [address], or `null`. */
    fun match(zones: List<Zone>, address: Address): Zone? {
        val country = address.country?.trim()?.uppercase(Locale.ROOT) ?: return null
        val region = (address.state ?: address.city)?.let { norm(it) }
        val postal = address.postalCode?.replace(" ", "")?.replace("-", "")

        for (zone in ordered(zones)) {
            if (!zone.active) continue
            if (country !in zone.countries && WILDCARD !in zone.countries) continue

            val entry = zone.regions?.firstOrNull { it.country == country }
            if (entry != null && entry.states.isNotEmpty()) {
                if (region == null || region !in entry.states.map { norm(it) }) continue
            }

            val patterns = zone.postalPatterns
            if (!patterns.isNullOrEmpty()) {
                if (postal == null || patterns.none { matches(it, postal) }) continue
            }

            return zone
        }

        return null
    }

    /**
     * `"34*"` is a prefix (case-insensitive); `"1000-1999"` needs both bounds and [postal] to be digits of the same
     * length and `from <= postal <= to`; any other pattern is an exact match. [postal] has spaces and hyphens removed.
     */
    fun matches(pattern: String, postal: String): Boolean {
        if (pattern.endsWith("*")) return postal.startsWith(pattern.dropLast(1), ignoreCase = true)

        if ('-' in pattern) {
            val parts = pattern.split('-')
            if (parts.size != 2) return false

            val (from, to) = parts
            if (!isDigits(from) || !isDigits(to) || !isDigits(postal)) return false
            if (from.length != to.length || postal.length != from.length || from.length > 18) return false

            return postal.toLong() in from.toLong()..to.toLong()
        }

        return postal.equals(pattern, ignoreCase = true)
    }

    /**
     * The id of the first earlier `ACTIVE` zone that has no regions and no postal patterns and whose countries contain
     * `"*"` or every country of [zone]: [zone] can then never match (the panel warns). `null` when it is reachable.
     */
    fun shadowedBy(zones: List<Zone>, zone: Zone): Long? {
        for (earlier in ordered(zones)) {
            if (earlier.id == zone.id) return null
            if (!earlier.active) continue
            if (!earlier.regions.isNullOrEmpty() || !earlier.postalPatterns.isNullOrEmpty()) continue

            if (WILDCARD in earlier.countries || (WILDCARD !in zone.countries && earlier.countries.containsAll(zone.countries))) {
                return earlier.id
            }
        }

        return null
    }

    private fun ordered(zones: List<Zone>) = zones.sortedWith(compareBy({ it.position }, { it.id }))

    private fun isDigits(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' }
}
