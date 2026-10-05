package com.panomc.plugins.market.routes.panel.invoice

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketCreditAccountDao
import com.panomc.plugins.market.db.dao.MarketInvoiceDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderEventDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketRedemptionDao
import com.panomc.plugins.market.db.dao.MarketRefundDao
import com.panomc.plugins.market.db.dao.MarketRefundItemDao
import com.panomc.plugins.market.db.dao.MarketSequenceDao
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.i18n.MarketFormat
import com.panomc.plugins.market.i18n.MarketI18n
import com.panomc.plugins.market.i18n.MarketI18nFactory
import com.panomc.plugins.market.pdf.InvoiceDocuments
import com.panomc.plugins.market.pdf.InvoiceMailAttachments
import com.panomc.plugins.market.pdf.InvoiceTextsFactory
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.service.InvoiceService
import com.panomc.plugins.market.service.InvoiceSite
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import java.io.File
import java.util.concurrent.Callable

/** Everything the invoice slice needs on one plugin instance's beans: the numbering service, the PDF store, the endpoint logic and the mail seam. */
internal class InvoiceWiring(
    val service: InvoiceService,
    val documents: InvoiceDocuments,
    val endpoints: InvoiceEndpoints,
    val mailAttachments: InvoiceMailAttachments
)

private object InvoiceWiringHolder

@Volatile
private var cachedWiring: Pair<MarketPlugin, InvoiceWiring>? = null

@Volatile
private var cachedI18n: Pair<MarketPlugin, Pair<MarketI18n, MarketFormat>>? = null

/** One per plugin instance. */
internal fun invoiceWiring(plugin: MarketPlugin): InvoiceWiring {
    cachedWiring?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(InvoiceWiringHolder) {
        cachedWiring?.takeIf { it.first === plugin }?.second ?: buildWiring(plugin).also { cachedWiring = plugin to it }
    }
}

/** The numbering service (what `InvoiceEffects` of the order transitions calls). */
internal fun invoiceService(plugin: MarketPlugin): InvoiceService = invoiceWiring(plugin).service

/** The server-side translator and formatter of the plugin (12 section 2): one per plugin instance, shared by the invoice and mail code. */
internal fun marketI18n(plugin: MarketPlugin): Pair<MarketI18n, MarketFormat> {
    cachedI18n?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(InvoiceWiringHolder) {
        cachedI18n?.takeIf { it.first === plugin }?.second ?: run {
            val i18n = MarketI18nFactory.create(plugin, SystemClock)

            (i18n to MarketI18nFactory.format(i18n) { currentConfig(plugin) }).also { cachedI18n = plugin to it }
        }
    }
}

private fun buildWiring(plugin: MarketPlugin): InvoiceWiring {
    val context = plugin.applicationContext
    val databaseManager = { context.getBean(DatabaseManager::class.java) }
    val platformConfig = { runCatching { context.getBean(ConfigManager::class.java).config }.getOrNull() }
    val orders = context.getBean(MarketOrderDao::class.java)
    val invoices = context.getBean(MarketInvoiceDao::class.java)
    val clock = SystemClock

    // the seller logo is the website logo of the platform (the file the mail templates use); none, or `invoiceShowLogo = false`, is no logo
    fun logoFile(): Pair<File, String>? {
        if (!currentConfig(plugin).invoiceShowLogo) return null

        val platform = platformConfig() ?: return null
        val info = platform.filePaths.websiteLogoFile ?: return null
        val file = File(platform.fileUploadsFolder + File.separator + info.path)

        return if (file.isFile && file.length() in 1..MAX_LOGO_BYTES) file to info.hash else null
    }

    val service = InvoiceService(
        config = { currentConfig(plugin) }, clock = clock, orders = orders, orderEvents = context.getBean(MarketOrderEventDao::class.java),
        refunds = context.getBean(MarketRefundDao::class.java), refundItems = context.getBean(MarketRefundItemDao::class.java), invoices = invoices,
        sequences = context.getBean(MarketSequenceDao::class.java),
        site = { InvoiceSite(platformConfig()?.websiteName.orEmpty(), platformConfig()?.websiteUrl.orEmpty().trim().trimEnd('/')) },
        defaultLocale = { platformConfig()?.locale?.takeIf { it.isNotBlank() } ?: InvoiceService.DEFAULT_LOCALE },
        logoHash = { logoFile()?.second }
    )

    val vertx = context.getBean(Vertx::class.java)
    val documents = InvoiceDocuments(
        base = plugin.pluginDataFolder.toPath().resolve("invoices"), invoices = invoices, clock = clock,
        texts = { snapshot, locale ->
            val (i18n, format) = marketI18n(plugin)

            InvoiceTextsFactory.build(snapshot, locale, i18n, format, currentConfig(plugin).creditName)
        },
        logo = { logoFile()?.first?.readBytes() },
        onWorker = { block -> vertx.executeBlocking(Callable { block() }).coAwait() },
        sqlClient = { databaseManager().getSqlClient() }
    )

    val db = MarketDb({ databaseManager().getSqlClient() as Pool }, clock)
    val locks = Locks(orders, context.getBean(MarketOrderItemDao::class.java), context.getBean(MarketRedemptionDao::class.java), context.getBean(MarketCreditAccountDao::class.java))
    val endpoints = InvoiceEndpoints(
        db = db, locks = locks, orders = orders, invoices = invoices, service = service, documents = documents, config = { currentConfig(plugin) }, clock = clock,
        site = { InvoiceSite(platformConfig()?.websiteName.orEmpty(), platformConfig()?.websiteUrl.orEmpty().trim().trimEnd('/')) },
        defaultLocale = { platformConfig()?.locale?.takeIf { it.isNotBlank() } ?: InvoiceService.DEFAULT_LOCALE }
    )

    return InvoiceWiring(service, documents, endpoints, InvoiceMailAttachments(invoices, documents) { currentConfig(plugin).mailAttachInvoice })
}

private const val MAX_LOGO_BYTES = 4L * 1024 * 1024
