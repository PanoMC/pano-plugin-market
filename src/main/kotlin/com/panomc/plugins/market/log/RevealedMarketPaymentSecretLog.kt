package com.panomc.plugins.market.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

// Records the catalog method id (under `name`, matching the locale placeholder) whose secrets were
// revealed — never the settings/secret values themselves. Provides an audit trail for the
// password-gated disclosure of live gateway secrets.
class RevealedMarketPaymentSecretLog(
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
