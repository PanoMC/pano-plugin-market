package com.panomc.plugins.market.routes.panel.invoice

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

// Invoice activity log rows (MK-144; 04 section 10, 12 sections 6.2 and 8.4). Numbers and ids only, never buyer data.

/** `REGENERATED_MARKET_INVOICE (orderId, numbers)`: [numbers] are the document numbers of the order, comma separated. */
class RegeneratedMarketInvoiceLog(userId: Long, username: String, pluginId: String, orderId: Long, numbers: List<String>) :
    PluginActivityLog(
        userId = userId, pluginId = pluginId,
        details = JsonObject().put("username", username).put("orderId", orderId).put("numbers", numbers.joinToString(", "))
    )

/** `UPDATED_MARKET_INVOICE_SEQUENCE (series, nextNumber)`. */
class UpdatedMarketInvoiceSequenceLog(userId: Long, username: String, pluginId: String, series: String, nextNumber: Long) :
    PluginActivityLog(
        userId = userId, pluginId = pluginId,
        details = JsonObject().put("username", username).put("series", series).put("nextNumber", nextNumber)
    )
