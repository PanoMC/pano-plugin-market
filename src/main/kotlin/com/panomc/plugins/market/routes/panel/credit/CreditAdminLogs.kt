package com.panomc.plugins.market.routes.panel.credit

import com.panomc.platform.db.model.PluginActivityLog
import com.panomc.plugins.market.util.MoneyUtil
import io.vertx.core.json.JsonObject

// Credit activity log rows (MK-093; 04 section 10). [amount] is what was actually moved, in credits.

/** `GRANTED_MARKET_CREDITS (username, amount)`: [username] is the buyer who got the credits. */
class GrantedMarketCreditsLog(userId: Long, username: String, pluginId: String, targetUsername: String, amount: Long) :
    PluginActivityLog(
        userId = userId, pluginId = pluginId,
        details = JsonObject().put("username", username).put("targetUsername", targetUsername).put("amount", MoneyUtil.toDecimal(amount))
    )

/** `REVOKED_MARKET_CREDITS (username, amount, shortfall)`. */
class RevokedMarketCreditsLog(userId: Long, username: String, pluginId: String, targetUsername: String, amount: Long, shortfall: Long) :
    PluginActivityLog(
        userId = userId, pluginId = pluginId,
        details = JsonObject().put("username", username).put("targetUsername", targetUsername).put("amount", MoneyUtil.toDecimal(amount)).put("shortfall", MoneyUtil.toDecimal(shortfall))
    )
