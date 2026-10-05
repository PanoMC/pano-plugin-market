package com.panomc.plugins.market.spi.common

import io.vertx.core.json.JsonObject

/**
 * Either literal texts per locale ([of]) or a fully qualified i18n key with an English fallback ([key]).
 * Literal texts always contain `en-US`. Locale tags compare case-insensitively and accept `_` for `-`.
 */
class LocalizedText private constructor(val key: String?, val values: Map<String, String>) {
    /**
     * Picks the text for [locale]: exact tag, then the language alone (`tr-TR` -> `tr`), then any text of the same
     * language (`tr` -> `tr-TR`), then `en-US`. For a key text the English fallback is what a server can resolve.
     */
    fun resolve(locale: String): String {
        val wanted = normalize(locale)
        val lookup = values.mapKeys { normalize(it.key) }
        lookup[wanted]?.let { return it }
        val language = wanted.substringBefore('-')
        lookup[language]?.let { return it }
        lookup.entries.firstOrNull { it.key.substringBefore('-') == language }?.let { return it.value }
        return values.getValue(FALLBACK_LOCALE)
    }

    /** The English text (the literal `en-US` text, or the fallback of a key text). */
    val fallback: String get() = values.getValue(FALLBACK_LOCALE)

    /** Wire format (02 section 4): `{default, translations}` for literals, `{key, fallback}` for key texts. */
    fun toJson(): JsonObject =
        if (key != null) JsonObject().put("key", key).put("fallback", fallback)
        else JsonObject().put("default", fallback).put(
            "translations",
            JsonObject().also { json -> values.filterKeys { it != FALLBACK_LOCALE }.forEach { (locale, text) -> json.put(locale, text) } }
        )

    override fun equals(other: Any?): Boolean = other is LocalizedText && other.key == key && other.values == values

    override fun hashCode(): Int = 31 * (key?.hashCode() ?: 0) + values.hashCode()

    override fun toString(): String = if (key != null) "LocalizedText.key($key)" else "LocalizedText($values)"

    companion object {
        const val FALLBACK_LOCALE = "en-US"

        private val LOCALE_TAG = Regex("^[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8})*$")
        private val KEY = Regex("^[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)+$")

        private fun normalize(tag: String): String = tag.trim().replace('_', '-').lowercase()

        fun of(enUS: String, vararg others: Pair<String, String>): LocalizedText {
            val map = LinkedHashMap<String, String>()
            map[FALLBACK_LOCALE] = enUS
            for ((locale, text) in others) {
                require(LOCALE_TAG.matches(locale)) { "Invalid locale tag '$locale'" }
                require(normalize(locale) != normalize(FALLBACK_LOCALE)) { "en-US is the first argument, not a pair" }
                require(map.keys.none { normalize(it) == normalize(locale) }) { "Duplicate locale '$locale'" }
                map[locale] = text
            }
            return LocalizedText(null, map)
        }

        /** [qualifiedKey] is the full i18n key (`plugins.<pluginId>.<key>`, dotted, no spaces). */
        fun key(qualifiedKey: String, fallbackEnUS: String): LocalizedText {
            require(KEY.matches(qualifiedKey)) { "Invalid i18n key '$qualifiedKey'" }
            return LocalizedText(qualifiedKey, mapOf(FALLBACK_LOCALE to fallbackEnUS))
        }
    }
}
