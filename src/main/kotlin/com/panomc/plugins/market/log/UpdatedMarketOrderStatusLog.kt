package com.panomc.plugins.market.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

class UpdatedMarketOrderStatusLog(
    userId: Long,
    username: String,
    pluginId: String,
    orderId: Long,
    status: String,
) : PluginActivityLog(
    userId = userId,
    pluginId = pluginId,
    details = JsonObject().put("username", username).put("orderId", orderId).put("status", status)
)
