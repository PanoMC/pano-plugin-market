package com.panomc.plugins.market.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

// A wrong password at the secret reveal (11 section 8.3). Records the provider id only, never any secret.
class FailedMarketSecretRevealLog(
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
