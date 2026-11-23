package com.panomc.plugins.market.i18n

import com.panomc.plugins.market.spi.common.LocalizedText
import java.util.Collections
import java.util.WeakHashMap

/**
 * Server-side text of a provider descriptor for a buyer's locale. [LocalizedText.resolve] only knows the English fallback
 * of a key text (`plugins.<pluginId>.<path>`); the translations live in the provider plugin's own `locales/<locale>.json`.
 * This reads them from the provider's class loader (the same three files [MarketI18n.loadBundles] reads, cached per class
 * loader) with the lookup order of [MarketI18n]: exact locale, same language, then the text's English fallback.
 */
object ProviderTexts {
    private val bundles: MutableMap<ClassLoader, Map<String, Map<String, String>>> = Collections.synchronizedMap(WeakHashMap())

    /** [owner] is the provider (or any class of its plugin) whose `locales/` hold the key's translations. */
    fun resolve(text: LocalizedText, owner: Any, locale: String): String = resolveIn(text, owner.javaClass.classLoader, locale)

    internal fun resolveIn(text: LocalizedText, loader: ClassLoader?, locale: String): String {
        val key = text.key ?: return text.resolve(locale)
        // `plugins.<pluginId>.<path>`: plugin ids hold no dots, the path inside the locale file is the rest
        val path = key.split('.', limit = 3).getOrNull(2) ?: return text.fallback
        if (loader == null) return text.fallback
        val files = bundles.getOrPut(loader) { MarketI18n.loadBundles(loader) }

        return lookup(files, locale, path) ?: text.fallback
    }

    internal fun lookup(files: Map<String, Map<String, String>>, locale: String, path: String): String? {
        val wanted = normalize(locale)
        val byTag = files.mapKeys { normalize(it.key) }

        byTag[wanted]?.get(path)?.let { return it }

        val language = wanted.substringBefore('-')

        return byTag.entries.firstOrNull { it.key != wanted && it.key.substringBefore('-') == language }?.value?.get(path)
    }

    private fun normalize(tag: String): String = tag.trim().replace('_', '-').lowercase()
}
