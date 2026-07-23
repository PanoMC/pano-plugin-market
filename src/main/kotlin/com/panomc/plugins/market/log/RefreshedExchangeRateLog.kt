package com.panomc.plugins.market.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

class RefreshedExchangeRateLog(
    userId: Long,
    username: String,
    pluginId: String,
    exchangeRate: Double,
) : PluginActivityLog(
    userId = userId,
    pluginId = pluginId,
    details = JsonObject().put("username", username).put("exchangeRate", exchangeRate)
)
