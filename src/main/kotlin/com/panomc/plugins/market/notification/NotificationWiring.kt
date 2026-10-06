package com.panomc.plugins.market.notification

import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketThrottleDao
import com.panomc.plugins.market.routes.panel.server.mcSyncService
import com.panomc.plugins.market.runtime.MarketRuntime
import com.panomc.plugins.market.runtime.beans
import com.panomc.plugins.market.service.ThrottleService

private object NotificationWiringHolder

@Volatile
private var cachedAlerts: Pair<MarketPlugin, MarketAlerts>? = null

/**
 * The alerts of the plugin instance (MK-172): one [MarketAlerts] shared by the order, refund and dispute services and the delivery sweep, so the in-memory
 * half of the throttle is one map. The sink touches host classes only after `MarketRuntime.capabilities.notifications` said they exist (checked on every
 * alert, so a host that lacks `NotificationTypeRegistry` never loads `NotificationManager` through market).
 */
internal fun marketAlerts(plugin: MarketPlugin): MarketAlerts {
    cachedAlerts?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(NotificationWiringHolder) {
        cachedAlerts?.takeIf { it.first === plugin }?.second ?: buildAlerts(plugin).also { cachedAlerts = plugin to it }
    }
}

private fun buildAlerts(plugin: MarketPlugin): MarketAlerts {
    val context = plugin.beans
    val databaseManager = { context.getBean(com.panomc.platform.db.DatabaseManager::class.java) }
    val throttle = ThrottleService(context.getBean(MarketThrottleDao::class.java), { databaseManager().getSqlClient() }, SystemClock)
    var sink: PanelNotificationSink? = null

    return MarketAlerts(
        clock = SystemClock,
        enabled = { MarketRuntime.capabilities.notifications },
        sink = { sink ?: HostPanelNotificationSink(plugin).also { sink = it } },
        throttle = throttle
    )
}

/** The 5-minute sweep of 08 section 8.5 on the beans of the plugin. */
internal fun deliveryAlertSweep(plugin: MarketPlugin): DeliveryAlertSweep {
    val context = plugin.beans

    return DeliveryAlertSweep(
        clock = SystemClock,
        alerts = marketAlerts(plugin),
        enabled = { MarketRuntime.capabilities.notifications },
        prefix = { context.getBean(MarketThrottleDao::class.java).prefix() },
        client = { context.getBean(com.panomc.platform.db.DatabaseManager::class.java).getSqlClient() },
        servers = { mcSyncService(plugin).servers() }
    )
}
