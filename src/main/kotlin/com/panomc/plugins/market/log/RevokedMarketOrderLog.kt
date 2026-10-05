package com.panomc.plugins.market.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

/** An admin revoked the goods of an order without a refund (`POST /orders/:id/revoke`). */
class RevokedMarketOrderLog(
    userId: Long,
    username: String,
    pluginId: String,
    orderId: Long,
    count: Int
) : PluginActivityLog(
    userId = userId,
    pluginId = pluginId,
    details = JsonObject().put("username", username).put("orderId", orderId).put("count", count)
)
