package com.panomc.plugins.market.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

/** An admin approved the bank transfer of an order (`POST /orders/:id/bank-transfer`, 04 section 8: orderId). */
class ApprovedMarketBankTransferLog(
    userId: Long,
    username: String,
    pluginId: String,
    orderId: Long,
) : PluginActivityLog(
    userId = userId,
    pluginId = pluginId,
    details = JsonObject().put("username", username).put("orderId", orderId)
)

/** An admin rejected the bank transfer of an order (`POST /orders/:id/bank-transfer`, 04 section 8: orderId). */
class RejectedMarketBankTransferLog(
    userId: Long,
    username: String,
    pluginId: String,
    orderId: Long,
) : PluginActivityLog(
    userId = userId,
    pluginId = pluginId,
    details = JsonObject().put("username", username).put("orderId", orderId)
)
