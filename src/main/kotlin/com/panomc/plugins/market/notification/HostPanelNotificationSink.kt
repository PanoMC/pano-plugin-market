package com.panomc.plugins.market.notification

import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.notification.NotificationManager
import com.panomc.platform.notification.PanelUserNotificationType
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.permission.ManageMarketPermission

/** The notification object of an [AlertMessage] (the host class with the type's fields). Pure; unknown type names are a programming error. */
internal fun notificationOf(message: AlertMessage): PanelUserNotificationType {
    val f = message.fields

    fun long(key: String) = (f[key] as? Number)?.toLong()

    return when (message.type) {
        AlertTypes.ORDER_REVIEW -> MarketOrderReviewNotification(orderId = long("orderId"), reason = f["reason"] as? String, href = f["href"] as? String)

        AlertTypes.ORDER_ALERT -> MarketOrderAlertNotification(orderId = long("orderId"), code = f["code"] as? String, href = f["href"] as? String)

        AlertTypes.DELIVERY_WAITING -> MarketDeliveryWaitingNotification(
            serverId = long("serverId"), serverName = f["serverName"] as? String, count = long("count"), orderId = long("orderId"), deliveryId = long("deliveryId"),
            urgent = f["urgent"] == true, failed = f["failed"] == true, kind = f["kind"] as? String ?: "WAITING", href = f["href"] as? String
        )

        else -> throw IllegalArgumentException("unknown market notification type ${message.type}")
    }
}

/**
 * The production [PanelNotificationSink]: sends a panel notification to every holder of the audience's nodes (and of the umbrella `MANAGE_MARKET`) and to the
 * administrators, through the platform's `NotificationManager`. It is only created while the host has `NotificationTypeRegistry` (the registry is what lets a
 * plugin type be read back, 15 section 8.1), see [NotificationWiring].
 */
internal class HostPanelNotificationSink(private val plugin: MarketPlugin) : PanelNotificationSink {
    override suspend fun send(message: AlertMessage) {
        val context = plugin.applicationContext
        val permissions = context.getBean(PermissionManager::class.java)
        val databaseManager = context.getBean(DatabaseManager::class.java)
        val sqlClient = databaseManager.getSqlClient()

        val users = linkedSetOf<Long>()

        (message.audience.nodes.map { it.permission } + ManageMarketPermission()).forEach { users.addAll(permissions.getUserIdsWithPermission(it)) }

        val admins = context.getBean(AuthProvider::class.java).getAdminList(sqlClient)

        users.addAll(databaseManager.userDao.getIdsByListOfUsername(admins, sqlClient).map { it.value })

        if (users.isEmpty()) return

        context.getBean(NotificationManager::class.java).sendPanelNotificationToAll(users.toList(), notificationOf(message), sqlClient)
    }
}
