package com.panomc.plugins.market.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

/** An admin erased the personal data of one order (`POST /orders/:id/anonymize`, 04 section 7: orderId). */
class AnonymizedMarketOrderLog(
    userId: Long,
    username: String,
    pluginId: String,
    orderId: Long
) : PluginActivityLog(
    userId = userId,
    pluginId = pluginId,
    details = JsonObject().put("username", username).put("orderId", orderId)
)
