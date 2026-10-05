package com.panomc.plugins.market.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

// Shipping zones, methods and carriers (MK-131). Names and provider ids only, never settings or secrets.

private fun details(username: String, name: String?) = JsonObject().put("username", username).also { if (name != null) it.put("name", name) }

class CreatedMarketShippingZoneLog(userId: Long, username: String, name: String, pluginId: String) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = details(username, name))

class UpdatedMarketShippingZoneLog(userId: Long, username: String, name: String, pluginId: String) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = details(username, name))

class DeletedMarketShippingZoneLog(userId: Long, username: String, name: String, pluginId: String) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = details(username, name))

class SortedMarketShippingZonesLog(userId: Long, username: String, pluginId: String) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = details(username, null))

class CreatedMarketShippingMethodLog(userId: Long, username: String, name: String, pluginId: String) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = details(username, name))

class UpdatedMarketShippingMethodLog(userId: Long, username: String, name: String, pluginId: String) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = details(username, name))

class DeletedMarketShippingMethodLog(userId: Long, username: String, name: String, pluginId: String) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = details(username, name))

class SortedMarketShippingMethodsLog(userId: Long, username: String, pluginId: String) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = details(username, null))

class UpdatedMarketShippingCarrierLog(userId: Long, username: String, carrierId: String, pluginId: String) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = details(username, carrierId))

class ToggledMarketShippingCarrierLog(userId: Long, username: String, carrierId: String, enabled: Boolean, pluginId: String) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = details(username, carrierId).put("enabled", enabled))

class RevealedMarketShippingSecretLog(userId: Long, username: String, carrierId: String, pluginId: String) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = details(username, carrierId))

class RanMarketShippingActionLog(userId: Long, username: String, carrierId: String, action: String, pluginId: String) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = details(username, carrierId).put("action", action))
