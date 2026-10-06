package com.panomc.plugins.market.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

// In-game admin actions (19 section 8, 04 section 10): the actor is the Pano user linked to the in-game account; console operations are not logged here.

/** `/panomarket credits give`: [targetUsername] got [amount] credits on [serverId]. */
class GrantedMarketCreditsIngameLog(userId: Long, username: String, pluginId: String, targetUsername: String, amount: Long, serverId: Long?) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = ingame(username, targetUsername, amount, serverId))

/** `/panomarket credits take`. */
class RevokedMarketCreditsIngameLog(userId: Long, username: String, pluginId: String, targetUsername: String, amount: Long, serverId: Long?) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = ingame(username, targetUsername, amount, serverId))

/** `/panomarket credits set`: the balance of [targetUsername] was set to [amount]. */
class SetMarketCreditsIngameLog(userId: Long, username: String, pluginId: String, targetUsername: String, amount: Long, serverId: Long?) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = ingame(username, targetUsername, amount, serverId))

/** `/panomarket grant`: [productId] was granted to [targetUsername] as order [orderId]. */
class GrantedMarketProductIngameLog(userId: Long, username: String, pluginId: String, targetUsername: String, productId: Long, orderId: Long, serverId: Long?) :
    PluginActivityLog(
        userId = userId, pluginId = pluginId,
        details = JsonObject().put("username", username).put("targetUsername", targetUsername).put("productId", productId).put("orderId", orderId).put("serverId", serverId)
    )

private fun ingame(username: String, targetUsername: String, amount: Long, serverId: Long?) =
    JsonObject().put("username", username).put("targetUsername", targetUsername).put("amount", amount / 100.0).put("serverId", serverId)
