package com.panomc.plugins.market.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

/** An admin created an order by hand (`POST /orders`, 04 section 8: orderId, playerUsername, total). */
class CreatedMarketOrderLog(
    userId: Long,
    username: String,
    pluginId: String,
    orderId: Long,
    playerUsername: String,
    total: Double,
) : PluginActivityLog(
    userId = userId,
    pluginId = pluginId,
    details = JsonObject().put("username", username).put("orderId", orderId).put("playerUsername", playerUsername).put("total", total)
)
