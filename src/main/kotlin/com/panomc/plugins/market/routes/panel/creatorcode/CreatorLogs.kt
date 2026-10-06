package com.panomc.plugins.market.routes.panel.creatorcode

import com.panomc.platform.db.model.PluginActivityLog
import com.panomc.plugins.market.util.MoneyUtil
import io.vertx.core.json.JsonObject

// Creator payout activity log rows (MK-114; 21 section 7.4, 04 section 10). [amount] is base-currency money.

/** `CREATED_MARKET_CREATOR_PAYOUT (username, name, amount, method)`: [name] is the creator code the payout is for. */
class CreatedMarketCreatorPayoutLog(userId: Long, username: String, pluginId: String, name: String, amount: Long, method: String) :
    PluginActivityLog(
        userId = userId, pluginId = pluginId,
        details = JsonObject().put("username", username).put("name", name).put("amount", MoneyUtil.toDecimal(amount)).put("method", method)
    )

/** `CANCELLED_MARKET_CREATOR_PAYOUT (username, name, amount)`. */
class CancelledMarketCreatorPayoutLog(userId: Long, username: String, pluginId: String, name: String, amount: Long) :
    PluginActivityLog(
        userId = userId, pluginId = pluginId,
        details = JsonObject().put("username", username).put("name", name).put("amount", MoneyUtil.toDecimal(amount))
    )
