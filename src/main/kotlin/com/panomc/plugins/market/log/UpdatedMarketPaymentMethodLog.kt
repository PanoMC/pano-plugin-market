package com.panomc.plugins.market.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

// Records the catalog method id (under `name`, matching the locale placeholder) — never the
// method's settings, which may contain gateway secrets.
class UpdatedMarketPaymentMethodLog(
    userId: Long,
    username: String,
    methodId: String,
    pluginId: String
) : PluginActivityLog(
    userId = userId,
    pluginId = pluginId,
    details = JsonObject()
        .put("username", username)
        .put("name", methodId)
)
