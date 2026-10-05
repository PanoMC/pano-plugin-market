package com.panomc.plugins.market.routes.panel.shipping

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.abuse.Redactor
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketProviderStateDao
import com.panomc.plugins.market.db.dao.MarketShippingCarrierDao
import com.panomc.plugins.market.db.dao.MarketShippingMethodDao
import com.panomc.plugins.market.db.dao.MarketShippingRateDao
import com.panomc.plugins.market.db.dao.MarketShippingZoneDao
import com.panomc.plugins.market.db.dao.MarketThrottleDao
import com.panomc.plugins.market.db.model.ProviderStateKind
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.provider.ManualShippingProvider
import com.panomc.plugins.market.provider.ProviderContextImpl
import com.panomc.plugins.market.provider.ProviderLogImpl
import com.panomc.plugins.market.provider.ProviderStateStoreImpl
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.provider.StoredProviderSettings
import com.panomc.plugins.market.routes.panel.settings.payment.providerLookup
import com.panomc.plugins.market.routes.panel.settings.payment.siteInfoOf
import com.panomc.plugins.market.service.ShippingAdminService
import com.panomc.plugins.market.service.ShippingService
import com.panomc.plugins.market.spi.common.SiteInfo
import com.panomc.plugins.market.db.dao.MarketAddressDao
import com.panomc.plugins.market.db.dao.MarketCurrencyRateDao
import com.panomc.plugins.market.service.ShippingContexts
import com.panomc.plugins.market.spi.common.ProviderContext
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.shipping.ShipmentLookup
import com.panomc.plugins.market.spi.shipping.ShipmentView
import com.panomc.plugins.market.spi.shipping.ShippingContext
import com.panomc.plugins.market.spi.shipping.ShippingProvider
import com.panomc.plugins.market.spi.shipping.ShippingUrls
import io.vertx.core.Vertx
import io.vertx.ext.web.client.WebClient
import io.vertx.sqlclient.Pool

/** The built-in shipping providers (03 section 7): one instance for the lifetime of the plugin. */
internal val BUILT_IN_SHIPPING_PROVIDERS: List<ShippingProvider> by lazy { listOf(ManualShippingProvider()) }

/**
 * The context of the settings hooks, `listServices`, `balance` and actions: there is no shipment in play, so the
 * lookups answer "none"; the webhook URL carries the install token of the carrier row when there is one.
 */
private class SettingsShippingContext(private val base: ProviderContext, token: String?) : ShippingContext, ProviderContext by base {
    override val urls: ShippingUrls = object : ShippingUrls {
        override fun webhook(channel: String): String =
            "${base.site.baseUrl}/api/market/shipping/${base.providerId}/webhook/${token ?: "{installToken}"}" +
                if (channel == com.panomc.plugins.market.spi.MarketSpi.DEFAULT_CHANNEL) "" else "/$channel"
    }

    override val shipments: ShipmentLookup = object : ShipmentLookup {
        override suspend fun byMerchantReference(reference: String): ShipmentView? = null
        override suspend fun byCarrierReference(reference: String): ShipmentView? = null
        override suspend fun byTrackingNumber(trackingNumber: String): ShipmentView? = null
    }
}

@Volatile
private var cached: Pair<MarketPlugin, ShippingAdminService>? = null

@Volatile
private var cachedQuoter: Pair<MarketPlugin, ShippingService>? = null

private object ShippingAdminServiceHolder

/** The service of the panel routes, built once per plugin instance from the Spring beans. */
internal fun shippingAdminService(plugin: MarketPlugin): ShippingAdminService {
    cached?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(ShippingAdminServiceHolder) {
        cached?.takeIf { it.first === plugin }?.second ?: buildService(plugin).also { cached = plugin to it }
    }
}

/**
 * The shipping quoter of checkout (MK-132), one per plugin instance: its quote cache and circuit breaker live in memory, and the
 * admin service clears the cache of a carrier whose settings were saved.
 */
internal fun shippingService(plugin: MarketPlugin): ShippingService {
    cachedQuoter?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(ShippingAdminServiceHolder) {
        cachedQuoter?.takeIf { it.first === plugin }?.second ?: buildQuoter(plugin).also { cachedQuoter = plugin to it }
    }
}

private class Wiring(val db: MarketDb, val cipher: SecretCipher, val contexts: ShippingContexts, val site: () -> SiteInfo)

private fun wiringOf(plugin: MarketPlugin): Wiring {
    val context = plugin.applicationContext
    val databaseManager by lazy { context.getBean(DatabaseManager::class.java) }
    val db = MarketDb({ databaseManager.getSqlClient() as Pool }, SystemClock)
    val cipher = SecretCipher.load(plugin.pluginDataFolder.toPath())
    val stateDao = context.getBean(MarketProviderStateDao::class.java)
    val vertx = context.getBean(Vertx::class.java)
    val http by lazy { context.getBean(WebClient::class.java) }

    val site = {
        val config = runCatching { context.getBean(ConfigManager::class.java).config }.getOrNull()

        siteInfoOf(config?.websiteUrl.orEmpty(), config?.websiteName.orEmpty())
    }

    val contexts = ShippingContexts { provider, settings: ProviderSettings, testMode ->
        val secrets = (settings as? StoredProviderSettings)?.valuesOf(provider.settingsSchema().secretKeys) ?: emptySet()
        val log = ProviderLogImpl(provider.id, Redactor(secrets))
        val state = ProviderStateStoreImpl(ProviderStateKind.SHIPPING, provider.id, stateDao, db, cipher, SystemClock)

        SettingsShippingContext(ProviderContextImpl(provider.id, settings, testMode, http, vertx, log, state, site(), SystemClock), null)
    }

    return Wiring(db, cipher, contexts, site)
}

private fun buildQuoter(plugin: MarketPlugin): ShippingService {
    val context = plugin.applicationContext
    val wiring = wiringOf(plugin)

    return ShippingService(
        clock = SystemClock,
        zones = context.getBean(MarketShippingZoneDao::class.java),
        methods = context.getBean(MarketShippingMethodDao::class.java),
        rates = context.getBean(MarketShippingRateDao::class.java),
        carriers = context.getBean(MarketShippingCarrierDao::class.java),
        currencyRates = context.getBean(MarketCurrencyRateDao::class.java),
        addresses = context.getBean(MarketAddressDao::class.java),
        lookup = providerLookup(plugin),
        cipher = wiring.cipher,
        contexts = wiring.contexts
    )
}

private fun buildService(plugin: MarketPlugin): ShippingAdminService {
    val context = plugin.applicationContext
    val wiring = wiringOf(plugin)

    return ShippingAdminService(
        db = wiring.db,
        clock = SystemClock,
        zones = context.getBean(MarketShippingZoneDao::class.java),
        methods = context.getBean(MarketShippingMethodDao::class.java),
        rates = context.getBean(MarketShippingRateDao::class.java),
        carriers = context.getBean(MarketShippingCarrierDao::class.java),
        throttles = context.getBean(MarketThrottleDao::class.java),
        lookup = providerLookup(plugin),
        cipher = wiring.cipher,
        contexts = wiring.contexts,
        site = wiring.site,
        onQuoteCacheInvalidate = { providerId -> shippingService(plugin).invalidate(providerId) }
    )
}
