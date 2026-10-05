package com.panomc.plugins.market.i18n

import com.panomc.plugins.market.core.time.Clock
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** Source of admin overrides (12 section 2.1 step 1): every translation row of one locale, raw key to value. */
fun interface OverrideSource {
    suspend fun load(locale: String): Map<String, String>
}

/**
 * Server-side translations (12 section 2). Lookup order: DB override, bundled file of the exact locale, bundled file of the
 * same language, `en-US`, the key itself. [t] never returns null and never throws. Plain `{name}` interpolation only.
 */
class MarketI18n(
    /** Flattened bundled files, by locale code, in lookup order for the language match. */
    private val bundles: Map<String, Map<String, String>>,
    private val overrides: OverrideSource,
    private val clock: Clock,
    private val warn: (String) -> Unit = { LoggerFactory.getLogger("Market:I18n").warn(it) },
) {
    private class Cached(val expiresAt: Long, val map: Map<String, String>)

    private val cache = ConcurrentHashMap<String, Cached>()
    private val warned = ConcurrentHashMap.newKeySet<String>()

    suspend fun t(locale: String, key: String, vars: Map<String, Any?> = emptyMap()): String {
        val raw = lookup(locale, key)
        if (raw == null) {
            if (warned.add(key)) warn("Missing translation key '$key' (locale '$locale')")
            return key
        }
        return interpolate(raw, vars)
    }

    /** True when [key] resolves to something other than the key itself. */
    suspend fun has(locale: String, key: String): Boolean = lookup(locale, key) != null

    /** 12 section 2.3: first non-blank candidate, else [default]. */
    fun resolveLocale(default: String, vararg candidates: String?): String =
        candidates.firstOrNull { !it.isNullOrBlank() }?.trim() ?: default

    private suspend fun lookup(locale: String, key: String): String? {
        overrideMap(locale)[key]?.let { return it }
        bundles[locale]?.get(key)?.let { return it }
        val lang = language(locale)
        if (lang.isNotEmpty()) {
            for ((code, map) in bundles) {
                if (code != locale && language(code) == lang) map[key]?.let { return it }
            }
        }
        return bundles[FALLBACK]?.get(key)
    }

    private suspend fun overrideMap(locale: String): Map<String, String> {
        val now = clock.now()
        cache[locale]?.let { if (now < it.expiresAt) return it.map }
        val map = try {
            overrides.load(locale)
                .filterKeys { it.startsWith(PREFIX) }
                .mapKeys { it.key.removePrefix(PREFIX) }
        } catch (e: Exception) {
            LoggerFactory.getLogger("Market:I18n").warn("Could not load translation overrides for '{}'", locale, e)
            emptyMap()
        }
        cache[locale] = Cached(now + CACHE_MS, map)
        return map
    }

    companion object {
        const val PREFIX = "plugins.pano-plugin-market."
        const val FALLBACK = "en-US"
        const val CACHE_MS = 60_000L
        val BUNDLED = listOf("tr", "en-US", "ru")
        private val PLACEHOLDER = Regex("\\{([A-Za-z][A-Za-z0-9_]*)\\}")

        private fun language(code: String): String = code.substringBefore('-').substringBefore('_').lowercase()

        /** Single left-to-right scan: a placeholder with a value is replaced, one without stays as is. */
        fun interpolate(text: String, vars: Map<String, Any?>): String {
            if (vars.isEmpty() || '{' !in text) return text
            return PLACEHOLDER.replace(text) { m ->
                val name = m.groupValues[1]
                if (vars.containsKey(name)) vars[name].toString() else m.value
            }
        }

        /** Parses a locale file and flattens it with `.`; non-string leaves are ignored. */
        fun flatten(json: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            fun walk(o: JsonObject, path: String) {
                for (k in o.fieldNames()) {
                    val p = if (path.isEmpty()) k else "$path.$k"
                    when (val v = o.getValue(k)) {
                        is JsonObject -> walk(v, p)
                        is String -> out[p] = v
                        else -> {}
                    }
                }
            }
            walk(JsonObject(json), "")
            return out
        }

        /** Loads the three bundled files explicitly (no directory listing). A missing or broken file yields an empty map. */
        fun loadBundles(classLoader: ClassLoader): Map<String, Map<String, String>> =
            BUNDLED.associateWith { code ->
                try {
                    classLoader.getResourceAsStream("locales/$code.json")?.use { flatten(it.readBytes().toString(Charsets.UTF_8)) }
                        ?: emptyMap()
                } catch (e: Exception) {
                    LoggerFactory.getLogger("Market:I18n").warn("Could not read locales/{}.json", code, e)
                    emptyMap()
                }
            }
    }
}
