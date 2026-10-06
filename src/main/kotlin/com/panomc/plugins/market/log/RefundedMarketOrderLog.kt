package com.panomc.plugins.market.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

/** An admin refunded an order (`POST /orders/:id/refunds`); `manual` marks money that was returned outside the gateway (21 section 3.6). */
class RefundedMarketOrderLog(
    userId: Long,
    username: String,
    pluginId: String,
    orderId: Long,
    refundId: Long,
    amount: Double,
    manual: Boolean
) : PluginActivityLog(
    userId = userId,
    pluginId = pluginId,
    details = JsonObject().put("username", username).put("orderId", orderId).put("refundId", refundId).put("amount", amount).put("manual", manual)
)
