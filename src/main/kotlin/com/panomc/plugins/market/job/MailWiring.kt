package com.panomc.plugins.market.job

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.mail.MailManager
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketInvoiceDao
import com.panomc.plugins.market.db.dao.MarketMailOutboxDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderEventDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketRefundDao
import com.panomc.plugins.market.db.dao.MarketRefundItemDao
import com.panomc.plugins.market.mail.MailComposer
import com.panomc.plugins.market.mail.MailContentBuilder
import com.panomc.plugins.market.mail.MailGateway
import com.panomc.plugins.market.mail.MailSendResult
import com.panomc.plugins.market.mail.MailSite
import com.panomc.plugins.market.mail.OutboundMail
import com.panomc.plugins.market.mail.PlatformMailGateway
import com.panomc.plugins.market.mail.UnavailableMailGateway
import com.panomc.plugins.market.routes.panel.invoice.invoiceWiring
import com.panomc.plugins.market.routes.panel.invoice.marketI18n
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.runtime.MarketRuntime
import com.panomc.plugins.market.runtime.beans
import com.panomc.plugins.market.service.MailOutboxService
import io.vertx.sqlclient.SqlClient

/**
 * The production binding of the mail outbox (12 sections 3.2 and 4.3, MK-141 / MK-142): the outbox service, the composer of the order mails on the
 * plugin's i18n and format, the invoice attachments (MK-144) and the gateway. `PlatformMailGateway` (the only class that references `MailOptions`)
 * is built only when the host probe found X-4 ([MarketRuntime.capabilities]); else [UnavailableMailGateway] stands in and the job marks rows
 * `SKIPPED (HOST_TOO_OLD)` before it ever calls it.
 */
internal object MailWiring {
    fun job(plugin: MarketPlugin): MailOutboxJob {
        val context = plugin.beans
        val databaseManager = { context.getBean(DatabaseManager::class.java) }
        val sqlClient: suspend () -> SqlClient = { databaseManager().getSqlClient() }
        val config = { currentConfig(plugin) }
        val platformConfig = { context.getBean(ConfigManager::class.java).config }
        val service = MailOutboxService(config, SystemClock, context.getBean(MarketMailOutboxDao::class.java), context.getBean(MarketOrderEventDao::class.java))
        val (i18n, format) = marketI18n(plugin)
        val site = { MailSite(platformConfig().websiteName, platformConfig().websiteUrl.trim().trimEnd('/')) }
        val composer = MailComposer(
            MailContentBuilder(i18n, format, config, site), context.getBean(MarketOrderDao::class.java), context.getBean(MarketOrderItemDao::class.java),
            context.getBean(MarketRefundDao::class.java), context.getBean(MarketRefundItemDao::class.java), context.getBean(MarketInvoiceDao::class.java),
            invoiceWiring(plugin).mailAttachments, config
        )
        val platform by lazy { PlatformMailGateway(context.getBean(MailManager::class.java), context.getBean(ConfigManager::class.java), sqlClient) }
        val gateway = object : MailGateway {
            override suspend fun send(message: OutboundMail): MailSendResult =
                if (MarketRuntime.capabilities.mail) platform.send(message) else UnavailableMailGateway.send(message)
        }

        return MailOutboxJob(
            config = config, clock = SystemClock, service = service, gateway = gateway, composition = composer, sqlClient = sqlClient,
            mailEnabled = { runCatching { platformConfig().email.enabled }.getOrDefault(false) }
        )
    }
}
