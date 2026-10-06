package com.panomc.plugins.market.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

// Order and money activity logs of 04 section 10 (MK-160). Never an address, an e-mail or a token in the details.

/** `PUT /orders/:id/note`: the note text itself is not logged. */
class UpdatedMarketOrderNoteLog(userId: Long, username: String, pluginId: String, orderId: Long) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = JsonObject().put("username", username).put("orderId", orderId))

/** `GET /orders/export` (11 section 15): [rows] written, the [filters] used and whether PII columns were part of the file. */
class ExportedMarketOrdersLog(userId: Long, username: String, pluginId: String, rows: Int, filters: JsonObject, pii: Boolean) :
    PluginActivityLog(
        userId = userId, pluginId = pluginId,
        details = JsonObject().put("username", username).put("rows", rows).put("filters", filters).put("pii", pii)
    )

/** `POST /refunds/:refundId/retry`. */
class RetriedMarketRefundLog(userId: Long, username: String, pluginId: String, orderId: Long, refundId: Long) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = JsonObject().put("username", username).put("orderId", orderId).put("refundId", refundId))

/** `POST /refunds/:refundId/cancel`. */
class CancelledMarketRefundLog(userId: Long, username: String, pluginId: String, orderId: Long, refundId: Long) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = JsonObject().put("username", username).put("orderId", orderId).put("refundId", refundId))

/** `POST /payment-events/:eventId/replay`. */
class ReplayedMarketPaymentEventLog(userId: Long, username: String, pluginId: String, eventId: Long, providerId: String) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = JsonObject().put("username", username).put("eventId", eventId).put("providerId", providerId))
