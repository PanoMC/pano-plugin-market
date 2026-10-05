package com.panomc.plugins.market.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

// Store webhook endpoints (MK-106). The name only: never the URL, a secret or a header value.

private fun details(username: String, name: String) = JsonObject().put("username", username).put("name", name)

class CreatedMarketWebhookLog(userId: Long, username: String, name: String, pluginId: String) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = details(username, name))

class UpdatedMarketWebhookLog(userId: Long, username: String, name: String, pluginId: String) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = details(username, name))

class DeletedMarketWebhookLog(userId: Long, username: String, name: String, pluginId: String) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = details(username, name))

class TestedMarketWebhookLog(userId: Long, username: String, name: String, pluginId: String) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = details(username, name))
