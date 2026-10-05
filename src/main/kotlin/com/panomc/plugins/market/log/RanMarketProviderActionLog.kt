package com.panomc.plugins.market.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

// Provider ids and action ids only, never settings or secrets.
class RanMarketProviderActionLog(
    userId: Long,
    username: String, methodId: String, action: String,
    pluginId: String
) : PluginActivityLog(
    userId = userId,
    pluginId = pluginId,
    details = JsonObject()
        .put("username", username)
        .put("name", methodId).put("action", action)
)
