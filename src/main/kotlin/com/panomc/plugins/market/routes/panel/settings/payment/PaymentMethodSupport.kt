package com.panomc.plugins.market.routes.panel.settings.payment

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.abuse.Redactor
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketPaymentMethodDao
import com.panomc.plugins.market.db.dao.MarketProviderStateDao
import com.panomc.plugins.market.db.dao.MarketThrottleDao
import com.panomc.plugins.market.db.model.ProviderStateKind
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.provider.BankTransferProvider
import com.panomc.plugins.market.provider.CreditsProvider
import com.panomc.plugins.market.provider.FreeProvider
import com.panomc.plugins.market.provider.ProviderContextImpl
import com.panomc.plugins.market.provider.ProviderLogImpl
import com.panomc.plugins.market.provider.ProviderLookup
import com.panomc.plugins.market.provider.ProviderRegistry
import com.panomc.plugins.market.provider.ProviderStateStoreImpl
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.provider.StoredProviderSettings
import com.panomc.plugins.market.service.PaymentContexts
import com.panomc.plugins.market.service.PaymentMethodService
import com.panomc.plugins.market.spi.MarketSpi
import com.panomc.plugins.market.spi.common.ProviderContext
import com.panomc.plugins.market.spi.common.SiteInfo
import com.panomc.plugins.market.spi.payment.AttemptUrls
import com.panomc.plugins.market.spi.payment.PaymentAttemptView
import com.panomc.plugins.market.spi.payment.PaymentContext
import com.panomc.plugins.market.spi.payment.PaymentLookup
import com.panomc.plugins.market.spi.payment.PaymentProvider
import com.panomc.plugins.market.spi.payment.PaymentUrls
import com.panomc.plugins.market.spi.payment.SubscriptionView
import com.panomc.plugins.market.spi.common.ProviderSettings
import io.vertx.core.Vertx
import io.vertx.ext.web.client.WebClient
import io.vertx.sqlclient.Pool
import java.net.URI

/** The built-in providers (02 section 12): one instance each for the lifetime of the plugin. */
internal val BUILT_IN_PAYMENT_PROVIDERS: List<PaymentProvider> by lazy { listOf(FreeProvider(), CreditsProvider(), BankTransferProvider()) }

/** `site` of the provider context from the platform's website url / name. Localhost and private hosts are not publicly reachable. */
internal fun siteInfoOf(websiteUrl: String, websiteName: String, defaultLocale: String = "en-US"): SiteInfo {
    val base = websiteUrl.trim().trimEnd('/')
    val host = runCatching { URI(base).host?.lowercase() }.getOrNull().orEmpty()
    val https = base.startsWith("https://", ignoreCase = true)
    val local = host.isEmpty() || host == "localhost" || host.endsWith(".local") || host.endsWith(".localhost") ||
        host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") || host == "::1" || host == "[::1]" ||
        Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(host)

    return SiteInfo(websiteName.ifBlank { host }, base, https, !local, defaultLocale)
}

/**
 * The context of the settings hooks (`validateSettings`, `onSettingsSaved`, `runAction`): a provider can read its settings,
 * call its gateway and use its state store. There is no payment attempt in play here, so the lookups answer "none".
 */
private class SettingsPaymentContext(private val base: ProviderContext) : PaymentContext, ProviderContext by base {
    override val urls: PaymentUrls = object : PaymentUrls {
        override fun webhook(channel: String): String =
            "${base.site.baseUrl}/api/market/payments/${base.providerId}/webhook" + if (channel == MarketSpi.DEFAULT_CHANNEL) "" else "/$channel"

        override fun checkoutPage(): String = "${base.site.baseUrl}/store/checkout"

        override fun forAttempt(attempt: PaymentAttemptView): AttemptUrls {
            val root = "${base.site.baseUrl}/api/market/payments/${base.providerId}"

            return AttemptUrls(
                success = "$root/return/${attempt.token}/success", cancel = "$root/return/${attempt.token}/cancel",
                pending = "$root/return/${attempt.token}/pending", result = "$root/return/${attempt.token}/result",
                notify = "$root/notify/${attempt.token}", orderPage = "${base.site.baseUrl}/store/order/${attempt.orderPublicId}"
            )
        }
    }

    override val payments: PaymentLookup = object : PaymentLookup {
        override suspend fun byId(attemptId: Long): PaymentAttemptView? = null
        override suspend fun byReference(reference: String): PaymentAttemptView? = null
        override suspend fun byGatewayTransactionId(id: String): PaymentAttemptView? = null
        override suspend fun byGatewayRef(name: String, value: String): PaymentAttemptView? = null
        override suspend fun subscriptionByGatewayId(gatewaySubscriptionId: String): SubscriptionView? = null
    }

    override suspend fun <T> withAttemptLock(attemptId: Long, block: suspend () -> T): T = block()
}

@Volatile
private var cached: Pair<MarketPlugin, PaymentMethodService>? = null

@Volatile
private var cachedLookup: Pair<MarketPlugin, ProviderLookup>? = null

/** The provider registry on the real plugin event bus, with the built-ins; one per plugin instance. */
internal fun providerLookup(plugin: MarketPlugin): ProviderLookup {
    cachedLookup?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(PaymentMethodServiceHolder) {
        cachedLookup?.takeIf { it.first === plugin }?.second
            ?: ProviderRegistry.forPlatform(
                plugin.wrapper.pluginManager,
                builtInPayment = { BUILT_IN_PAYMENT_PROVIDERS },
                builtInShipping = { com.panomc.plugins.market.routes.panel.shipping.BUILT_IN_SHIPPING_PROVIDERS }
            )
                .also { cachedLookup = plugin to it }
    }
}

private object PaymentMethodServiceHolder

/** The service of the panel routes, built once per plugin instance from the Spring beans. */
internal fun paymentMethodService(plugin: MarketPlugin): PaymentMethodService {
    cached?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(PaymentMethodServiceHolder) {
        cached?.takeIf { it.first === plugin }?.second ?: buildService(plugin).also { cached = plugin to it }
    }
}

/** The secret cipher and the provider context factory of one plugin instance, shared by the panel service and the quote. */
internal class PaymentWiring(val cipher: SecretCipher, val contexts: PaymentContexts, val site: () -> SiteInfo)

@Volatile
private var cachedWiring: Pair<MarketPlugin, PaymentWiring>? = null

internal fun paymentWiring(plugin: MarketPlugin): PaymentWiring {
    cachedWiring?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(PaymentMethodServiceHolder) {
        cachedWiring?.takeIf { it.first === plugin }?.second ?: buildWiring(plugin).also { cachedWiring = plugin to it }
    }
}

private fun buildWiring(plugin: MarketPlugin): PaymentWiring {
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

    val contexts = PaymentContexts { provider, settings: ProviderSettings, testMode ->
        val secrets = (settings as? StoredProviderSettings)?.valuesOf(provider.settingsSchema().secretKeys) ?: emptySet()
        val log = ProviderLogImpl(provider.id, Redactor(secrets))
        val state = ProviderStateStoreImpl(ProviderStateKind.PAYMENT, provider.id, stateDao, db, cipher, SystemClock)

        SettingsPaymentContext(ProviderContextImpl(provider.id, settings, testMode, http, vertx, log, state, site(), SystemClock))
    }

    return PaymentWiring(cipher, contexts, site)
}

private fun buildService(plugin: MarketPlugin): PaymentMethodService {
    val context = plugin.applicationContext
    val databaseManager by lazy { context.getBean(DatabaseManager::class.java) }
    val db = MarketDb({ databaseManager.getSqlClient() as Pool }, SystemClock)
    val wiring = paymentWiring(plugin)

    return PaymentMethodService(
        db = db,
        clock = SystemClock,
        methods = context.getBean(MarketPaymentMethodDao::class.java),
        throttles = context.getBean(MarketThrottleDao::class.java),
        lookup = providerLookup(plugin),
        cipher = wiring.cipher,
        contexts = wiring.contexts,
        site = wiring.site
    )
}
