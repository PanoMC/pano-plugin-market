package com.panomc.plugins.market.routes.panel.shipment

import com.panomc.platform.db.DatabaseManager
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.SecureIds
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketCreditAccountDao
import com.panomc.plugins.market.db.dao.MarketMailOutboxDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderEventDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketRedemptionDao
import com.panomc.plugins.market.db.dao.MarketShipmentDao
import com.panomc.plugins.market.db.dao.MarketShipmentEventDao
import com.panomc.plugins.market.db.dao.MarketShipmentItemDao
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.routes.api.order.webhookService
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.routes.panel.settings.payment.paymentWiring
import com.panomc.plugins.market.service.FulfilmentDeps
import com.panomc.plugins.market.service.MailOutboxService
import io.vertx.sqlclient.Pool

/**
 * The fulfilment half of the shipping service on the plugin's beans (MK-133): the lock set, the order / shipment / event DAOs, the mail outbox
 * and the store webhook writer, and `<pluginData>/labels` for label files. `shippingService(plugin)` hands it to the one `ShippingService` of the
 * plugin instance, so checkout, the panel routes and (MK-134) the tracking job share one object.
 */
internal fun fulfilmentDeps(plugin: MarketPlugin): FulfilmentDeps {
    val context = plugin.applicationContext
    val databaseManager = { context.getBean(DatabaseManager::class.java) }
    val orders = context.getBean(MarketOrderDao::class.java)
    val orderItems = context.getBean(MarketOrderItemDao::class.java)
    val orderEvents = context.getBean(MarketOrderEventDao::class.java)
    val locks = Locks(orders, orderItems, context.getBean(MarketRedemptionDao::class.java), context.getBean(MarketCreditAccountDao::class.java))
    val site = paymentWiring(plugin).site

    return FulfilmentDeps(
        db = MarketDb({ databaseManager().getSqlClient() as Pool }, SystemClock), locks = locks, orders = orders, orderItems = orderItems, orderEvents = orderEvents,
        shipments = context.getBean(MarketShipmentDao::class.java), shipmentItems = context.getBean(MarketShipmentItemDao::class.java),
        shipmentEvents = context.getBean(MarketShipmentEventDao::class.java),
        mail = MailOutboxService({ currentConfig(plugin) }, SystemClock, context.getBean(MarketMailOutboxDao::class.java), orderEvents),
        webhooks = webhookService(plugin), ids = SecureIds(), config = { currentConfig(plugin) },
        labelsDir = plugin.pluginDataFolder.toPath().resolve("labels"), siteUrl = { site().baseUrl }
    )
}
