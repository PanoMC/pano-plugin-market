package com.panomc.plugins.market.i18n

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Translation.Companion.TranslationType
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.time.Clock

/** DB overrides: rows of type PLUGIN of the requested locale (12 section 2.1 step 1). */
class DbOverrideSource(private val databaseManager: () -> DatabaseManager) : OverrideSource {
    override suspend fun load(locale: String): Map<String, String> {
        val db = databaseManager()
        // rows come newest first: the first row per key wins
        val out = LinkedHashMap<String, String>()
        for (row in db.translationDao.getByLocaleCodeAndType(locale, TranslationType.PLUGIN, db.getSqlClient())) {
            out.putIfAbsent(row.key, row.value)
        }
        return out
    }
}

/** Production wiring of [MarketI18n] and [MarketFormat]. */
object MarketI18nFactory {
    fun create(plugin: MarketPlugin, clock: Clock): MarketI18n = MarketI18n(
        bundles = MarketI18n.loadBundles(MarketPlugin::class.java.classLoader),
        overrides = DbOverrideSource { plugin.applicationContext.getBean(DatabaseManager::class.java) },
        clock = clock,
    )

    fun format(i18n: MarketI18n, config: () -> MarketConfig): MarketFormat =
        MarketFormat(i18n, zone = { config().storeTimeZone }, creditName = { config().creditName })
}
