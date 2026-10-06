package com.panomc.plugins.market.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

/** An admin opened a manual chargeback (`POST /orders/:id/disputes`, 21 section 5.1). */
class OpenedMarketDisputeLog(
    userId: Long,
    username: String,
    pluginId: String,
    orderId: Long,
    disputeId: Long
) : PluginActivityLog(
    userId = userId,
    pluginId = pluginId,
    details = JsonObject().put("username", username).put("orderId", orderId).put("disputeId", disputeId)
)

/** An admin resolved a dispute `WON`, `LOST` or `CLOSED` (`PUT /disputes/:id`). */
class UpdatedMarketDisputeLog(
    userId: Long,
    username: String,
    pluginId: String,
    orderId: Long,
    disputeId: Long,
    status: String
) : PluginActivityLog(
    userId = userId,
    pluginId = pluginId,
    details = JsonObject().put("username", username).put("orderId", orderId).put("disputeId", disputeId).put("status", status)
)

/** An admin ran the chargeback actions that were held back (`POST /orders/:id/chargeback-actions`, 11 section 10). */
class RanMarketChargebackActionsLog(
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
