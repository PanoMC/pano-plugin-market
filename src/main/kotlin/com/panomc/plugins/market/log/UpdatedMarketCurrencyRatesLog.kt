package com.panomc.plugins.market.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

/** An admin saved additional-currency rates (`PUT /settings/currencies`); [currencies] are the codes written. */
class UpdatedMarketCurrencyRatesLog(
    userId: Long,
    username: String,
    pluginId: String,
    currencies: String,
) : PluginActivityLog(
    userId = userId,
    pluginId = pluginId,
    details = JsonObject().put("username", username).put("currencies", currencies)
)
