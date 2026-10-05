package com.panomc.plugins.market.mc.core.feature

import java.util.concurrent.ConcurrentHashMap

/** Text helpers shared by every platform: legacy colour codes in, `§` text out. */
object ChatFormat {
    private const val CODES = "0123456789abcdefklmnor"

    /** `&a` -> `§a` for the legacy colour and format codes only; any other `&` stays a literal `&`. */
    fun colorize(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '&' && i + 1 < text.length && CODES.indexOf(text[i + 1].lowercaseChar()) >= 0) {
                sb.append('§').append(text[i + 1].lowercaseChar())
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** A value that is inserted into a message: no colour symbol, no control character (it must never act as formatting). */
    fun plainValue(value: String): String {
        val sb = StringBuilder(value.length)
        for (ch in value) {
            if (ch == '§' || ch.code < 0x20 || ch.code == 0x7f) continue
            sb.append(ch)
        }
        return sb.toString()
    }

    /** A line Pano rendered (a broadcast): both `&` and `§` codes work, control characters are removed, one line, at most 256 characters. */
    fun fromPano(text: String): String {
        val oneLine = text.replace(Regex("[\\r\\n]+"), " ")
        val clean = StringBuilder(oneLine.length)
        for (ch in oneLine) if (ch.code >= 0x20 && ch.code != 0x7f) clean.append(ch)
        val colored = colorize(clean.toString())
        return if (colored.length > 256) colored.substring(0, 256) else colored
    }

    /** Removes every colour code (`§x`), e.g. for the log. */
    fun strip(text: String): String = text.replace(Regex("§[0-9a-fk-orA-FK-OR]"), "")
}

/**
 * The strings the component shows (19 section 9). Three layers per locale, first hit wins:
 * 1. the local override files `<data folder>/lang/<locale>.yml` (local presentation, the admin edits these);
 * 2. the `texts` of `MARKET_CONFIG` (rendered by Pano from market's locale files);
 * 3. the lang files bundled in the jar (`mc/lang/<locale>.yml`).
 * The locale is the player's when the platform knows it and a text exists for it, else the first `locales` entry of the
 * local config, else the site's default locale (the first one Pano sent), else `en-US`; the last resort is the key.
 */
class Messages(
    private val bundled: (String) -> Map<String, String>?,
    private val overrides: (String) -> Map<String, String>? = { null },
    private val panoTexts: () -> Map<String, Map<String, String>> = { emptyMap() },
    private val configuredLocales: () -> List<String> = { emptyList() }
) {
    private val bundledCache = ConcurrentHashMap<String, Map<String, String>>()
    private val overrideCache = ConcurrentHashMap<String, Map<String, String>>()

    /** The message `key` for [locale] with `{name}` placeholders replaced by [args] (values are made safe). */
    fun text(key: String, locale: String?, vararg args: Pair<String, Any?>): String {
        val template = find(key, locale) ?: key
        var out = ChatFormat.colorize(template)
        for ((name, value) in args) out = out.replace("{$name}", ChatFormat.plainValue(value?.toString() ?: ""))
        return out
    }

    fun has(key: String, locale: String?): Boolean = find(key, locale) != null

    private fun find(key: String, locale: String?): String? {
        for (candidate in candidates(locale)) {
            overrideFor(candidate)[key]?.let { return it }
            panoFor(candidate)[key]?.let { return it }
            bundledFor(candidate)[key]?.let { return it }
        }
        return null
    }

    private fun candidates(player: String?): List<String> {
        val pano = panoTexts()
        val out = ArrayList<String>()
        fun add(l: String?) {
            val n = normalize(l ?: return) ?: return
            if (out.none { it.equals(n, true) }) out.add(n)
        }
        add(player)
        configuredLocales().forEach(::add)
        pano.keys.firstOrNull()?.let(::add)
        add("en-US")
        return out
    }

    private fun panoFor(locale: String): Map<String, String> {
        val pano = panoTexts()
        pano.entries.firstOrNull { sameLocale(it.key, locale) }?.let { return it.value }
        val lang = locale.substringBefore('-')
        return pano.entries.firstOrNull { it.key.substringBefore('-').substringBefore('_').equals(lang, true) }?.value ?: emptyMap()
    }

    private fun bundledFor(locale: String): Map<String, String> =
        bundledCache.computeIfAbsent(locale) { l -> bundled(l) ?: bundled(l.substringBefore('-'))?.takeIf { it.isNotEmpty() } ?: languageMatch(l) ?: emptyMap() }

    private fun languageMatch(l: String): Map<String, String>? {
        val lang = l.substringBefore('-')
        return KNOWN.firstOrNull { it.substringBefore('-').equals(lang, true) }?.let { bundled(it) }
    }

    private fun overrideFor(locale: String): Map<String, String> = overrideCache.computeIfAbsent(locale) { l -> overrides(l) ?: emptyMap() }

    private fun sameLocale(a: String, b: String) = normalize(a)?.equals(normalize(b), true) ?: false

    companion object {
        /** The bundled lang files (`src/mc/resources/mc/lang`). */
        val KNOWN = listOf("en-US", "tr", "ru")

        /** At most three locales of `MARKET_CONFIG.texts` are used (19 section 7.1). */
        const val MAX_PANO_LOCALES = 3

        /** `tr_TR` / `tr-tr` / `TR` -> `tr-TR` / `tr` / `tr`; `null` for nonsense. */
        fun normalize(raw: String): String? {
            val s = raw.trim().replace('_', '-')
            if (s.isEmpty() || s.length > 16) return null
            val parts = s.split('-')
            if (parts[0].length !in 2..3 || !parts[0].all { it.isLetter() }) return null
            val lang = parts[0].lowercase()
            return if (parts.size == 1) lang else lang + "-" + parts.drop(1).joinToString("-") { if (it.length == 2) it.uppercase() else it }
        }
    }
}

/** Flattens a [MiniYaml] map into `key -> text` (values are rendered with `toString`). */
fun flatTexts(map: Map<String, Any?>): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    fun walk(prefix: String, value: Any?) {
        when (value) {
            is Map<*, *> -> value.forEach { (k, v) -> walk(if (prefix.isEmpty()) k.toString() else "$prefix.$k", v) }
            null -> Unit
            else -> out[prefix] = value.toString()
        }
    }
    walk("", map)
    return out
}
