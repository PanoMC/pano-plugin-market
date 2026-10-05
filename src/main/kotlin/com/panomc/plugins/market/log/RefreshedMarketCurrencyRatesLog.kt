package com.panomc.plugins.market.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

/** An admin refreshed the AUTO currency rates from the provider (`POST /settings/currencies/refresh`). */
class RefreshedMarketCurrencyRatesLog(
    userId: Long,
    username: String,
    pluginId: String,
    currencies: String,
) : PluginActivityLog(
    userId = userId,
    pluginId = pluginId,
    details = JsonObject().put("username", username).put("currencies", currencies)
)
