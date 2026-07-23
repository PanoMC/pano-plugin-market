package com.panomc.plugins.market.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

class UpdatedMarketProductLog(
    userId: Long,
    username: String,
    pluginId: String,
    name: String,
    changes: JsonObject? = null
) : PluginActivityLog(
    userId = userId,
    pluginId = pluginId,
    details = JsonObject().put("name", name).put("username", username).apply {
        if (changes != null) {
            put("changes", changes)
        }
    }
)
