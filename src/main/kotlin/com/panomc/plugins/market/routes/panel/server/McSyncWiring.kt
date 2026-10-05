package com.panomc.plugins.market.routes.panel.server

import com.panomc.platform.PluginManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.server.ServerManager
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketCreditAccountDao
import com.panomc.plugins.market.db.dao.MarketDeliveryDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketRedemptionDao
import com.panomc.plugins.market.db.dao.MarketServerStateDao
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.routes.api.order.deliveryService
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.runtime.beans
import com.panomc.plugins.market.service.McSyncService
import com.panomc.plugins.market.service.PlatformMcServerLink
import io.vertx.sqlclient.Pool

private object McSyncWiringHolder

@Volatile
private var cachedSync: Pair<MarketPlugin, McSyncService>? = null

/**
 * The Pano side of `MARKET_SYNC` on the plugin's beans (MK-103, 08 section 8): one per plugin instance, because the sessions that decide the readiness of a
 * server live in it. Shared by the `MARKET_SYNC` event, `GET /servers` and the `DeliveryJob` steps of the server rows.
 *
 * `marketVersion` is the PF4J descriptor version of the running market jar (exact string comparison with the component's version, 08 section 8.3). The
 * `configHash` of `MARKET_CONFIG` is MC-04's: until it lands the response carries none and the component keeps the configuration it has.
 */
internal fun mcSyncService(plugin: MarketPlugin): McSyncService {
    cachedSync?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(McSyncWiringHolder) {
        cachedSync?.takeIf { it.first === plugin }?.second ?: buildMcSyncService(plugin).also { cachedSync = plugin to it }
    }
}

private fun buildMcSyncService(plugin: MarketPlugin): McSyncService {
    val context = plugin.beans
    val databaseManager = { context.getBean(DatabaseManager::class.java) }
    val orderDao = context.getBean(MarketOrderDao::class.java)
    val locks = Locks(orderDao, context.getBean(MarketOrderItemDao::class.java), context.getBean(MarketRedemptionDao::class.java), context.getBean(MarketCreditAccountDao::class.java))

    return McSyncService(
        db = MarketDb({ databaseManager().getSqlClient() as Pool }, SystemClock), locks = locks, clock = SystemClock, config = { currentConfig(plugin) },
        deliveries = context.getBean(MarketDeliveryDao::class.java), serverStates = context.getBean(MarketServerStateDao::class.java), orders = orderDao,
        orderItems = context.getBean(MarketOrderItemDao::class.java), delivery = deliveryService(plugin),
        link = PlatformMcServerLink(databaseManager) { plugin.applicationContext.getBean(ServerManager::class.java) },
        marketVersion = { plugin.applicationContext.getBean(PluginManager::class.java).getPlugin(plugin.pluginId).descriptor.version },
        storeName = { currentConfig(plugin).storeName }
    )
}
