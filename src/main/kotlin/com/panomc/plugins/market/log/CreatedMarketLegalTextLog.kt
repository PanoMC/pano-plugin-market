package com.panomc.plugins.market.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

// Locale and version only, never the text itself.
class CreatedMarketLegalTextLog(
    userId: Long,
    username: String,
    locale: String,
    version: Int,
    pluginId: String
) : PluginActivityLog(
    userId = userId,
    pluginId = pluginId,
    details = JsonObject()
        .put("username", username)
        .put("locale", locale)
        .put("version", version)
)
