package com.panomc.plugins.market.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

class UpdatedMarketOrderExchangeRateLog(
    userId: Long,
    username: String,
    pluginId: String,
    orderId: Long,
    exchangeRate: Double,
) : PluginActivityLog(
    userId = userId,
    pluginId = pluginId,
    details = JsonObject().put("username", username).put("orderId", orderId).put("exchangeRate", exchangeRate)
)
