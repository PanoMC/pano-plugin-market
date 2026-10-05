package com.panomc.plugins.market.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

/** An admin accepted or rejected an order in review (`POST /orders/:id/review`, 04 section 8: orderId, decision, force). */
class ReviewedMarketOrderLog(
    userId: Long,
    username: String,
    pluginId: String,
    orderId: Long,
    decision: String,
    force: Boolean,
) : PluginActivityLog(
    userId = userId,
    pluginId = pluginId,
    details = JsonObject().put("username", username).put("orderId", orderId).put("decision", decision).put("force", force)
)
