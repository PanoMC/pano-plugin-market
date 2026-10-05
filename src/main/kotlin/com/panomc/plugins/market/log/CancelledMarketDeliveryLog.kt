package com.panomc.plugins.market.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

/** An admin cancelled a delivery row (`POST /deliveries/:id/cancel`). */
class CancelledMarketDeliveryLog(
    userId: Long,
    username: String,
    pluginId: String,
    orderId: Long?,
    deliveryId: Long
) : PluginActivityLog(
    userId = userId,
    pluginId = pluginId,
    details = JsonObject().put("username", username).put("orderId", orderId).put("deliveryId", deliveryId)
)
