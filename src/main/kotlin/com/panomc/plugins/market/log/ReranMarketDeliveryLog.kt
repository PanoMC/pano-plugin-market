package com.panomc.plugins.market.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

/** An admin re-ran deliveries of an order (`POST /orders/:id/deliveries/rerun`, 08 section 14.1): the number created, and for a repeated grant its credit amount. */
class ReranMarketDeliveryLog(
    userId: Long,
    username: String,
    pluginId: String,
    orderId: Long,
    count: Int,
    duplicateGrant: Boolean,
    credits: Double

) : PluginActivityLog(
    userId = userId,
    pluginId = pluginId,
    details = JsonObject().put("username", username).put("orderId", orderId).put("count", count).apply {
        if (duplicateGrant) put("duplicateGrant", true).put("credits", credits)
    }
)
