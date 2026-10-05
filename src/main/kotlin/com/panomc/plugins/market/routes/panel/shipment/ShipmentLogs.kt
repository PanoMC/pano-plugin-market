package com.panomc.plugins.market.routes.panel.shipment

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

// Fulfilment activity log rows (MK-133; 10 sections 3.5 and 9). Ids and flags only, never a tracking number or an address.

private fun details(username: String, orderId: Long) = JsonObject().put("username", username).put("orderId", orderId)

class CreatedMarketShipmentLog(userId: Long, username: String, pluginId: String, orderId: Long, shipmentId: Long) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = details(username, orderId).put("shipmentId", shipmentId))

class UpdatedMarketShipmentLog(userId: Long, username: String, pluginId: String, orderId: Long, shipmentId: Long, changed: List<String>) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = details(username, orderId).put("shipmentId", shipmentId).put("changed", JsonArray(changed)))

class CancelledMarketShipmentLog(userId: Long, username: String, pluginId: String, orderId: Long, shipmentId: Long, forced: Boolean) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = details(username, orderId).put("shipmentId", shipmentId).put("forced", forced))

class UpdatedMarketOrderShippingAddressLog(userId: Long, username: String, pluginId: String, orderId: Long) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = details(username, orderId))
