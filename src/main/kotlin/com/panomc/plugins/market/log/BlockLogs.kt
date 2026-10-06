package com.panomc.plugins.market.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

// The block list (MK-151, 11 section 9.4). The value is masked (an e-mail or an IP is personal data); the source says whether a chargeback or an admin put it there.

private fun details(username: String, type: String, maskedValue: String, source: String) =
    JsonObject().put("username", username).put("type", type).put("value", maskedValue).put("source", source)

class CreatedMarketBlockLog(userId: Long, username: String, pluginId: String, type: String, maskedValue: String, source: String) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = details(username, type, maskedValue, source))

class DeletedMarketBlockLog(userId: Long, username: String, pluginId: String, type: String, maskedValue: String, source: String) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = details(username, type, maskedValue, source))
