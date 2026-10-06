package com.panomc.plugins.market.routes.panel.order

import com.panomc.platform.db.DatabaseManager
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketDeliveryDao
import com.panomc.plugins.market.db.dao.MarketDisputeDao
import com.panomc.plugins.market.db.dao.MarketEntitlementDao
import com.panomc.plugins.market.db.dao.MarketInvoiceDao
import com.panomc.plugins.market.db.dao.MarketLegalTextDao
import com.panomc.plugins.market.db.dao.MarketMailOutboxDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderEventDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketPaymentDao
import com.panomc.plugins.market.db.dao.MarketRefundDao
import com.panomc.plugins.market.db.dao.MarketShipmentDao
import com.panomc.plugins.market.db.dao.MarketSubscriptionDao
import com.panomc.plugins.market.routes.api.checkout.PlatformUserDirectory
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.runtime.beans
import com.panomc.plugins.market.service.OrderQueryService
import com.panomc.plugins.market.service.platform.PlatformServerRoster

private object OrderQueryWiringHolder

@Volatile
private var cachedQuery: Pair<MarketPlugin, OrderQueryService>? = null

/** The panel's order reads (list, detail, note, export) on the plugin's beans (MK-170); one per plugin instance. */
internal fun orderQueryService(plugin: MarketPlugin): OrderQueryService {
    cachedQuery?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(OrderQueryWiringHolder) {
        cachedQuery?.takeIf { it.first === plugin }?.second ?: buildOrderQueryService(plugin).also { cachedQuery = plugin to it }
    }
}

private fun buildOrderQueryService(plugin: MarketPlugin): OrderQueryService {
    val context = plugin.beans
    val databaseManager = { context.getBean(DatabaseManager::class.java) }
    val roster = PlatformServerRoster(databaseManager) { context.getBean(com.panomc.platform.server.ServerManager::class.java) }

    return OrderQueryService(
        orders = context.getBean(MarketOrderDao::class.java), orderItems = context.getBean(MarketOrderItemDao::class.java), payments = context.getBean(MarketPaymentDao::class.java),
        refunds = context.getBean(MarketRefundDao::class.java), disputes = context.getBean(MarketDisputeDao::class.java), deliveries = context.getBean(MarketDeliveryDao::class.java),
        shipments = context.getBean(MarketShipmentDao::class.java), orderEvents = context.getBean(MarketOrderEventDao::class.java), invoices = context.getBean(MarketInvoiceDao::class.java),
        mailOutbox = context.getBean(MarketMailOutboxDao::class.java), subscriptions = context.getBean(MarketSubscriptionDao::class.java),
        entitlements = context.getBean(MarketEntitlementDao::class.java), legalTexts = context.getBean(MarketLegalTextDao::class.java),
        users = PlatformUserDirectory(databaseManager), config = { currentConfig(plugin) }, clock = SystemClock,
        serverNames = { client -> roster.snapshot(client).names }
    )
}
