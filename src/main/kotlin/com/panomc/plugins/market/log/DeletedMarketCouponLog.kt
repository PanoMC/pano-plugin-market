package com.panomc.plugins.market.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

class DeletedMarketCouponLog(
    userId: Long,
    username: String,
    pluginId: String,
    name: String
) : PluginActivityLog(
    userId = userId,
    pluginId = pluginId,
    details = JsonObject().put("name", name).put("username", username)
)
