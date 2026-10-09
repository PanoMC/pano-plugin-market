package com.panomc.plugins.market.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

// Settings activity logs of 04 section 10 (MK-160).

/** `PUT /settings/currencies`: the currency codes written. */
class UpdatedMarketCurrenciesLog(userId: Long, username: String, pluginId: String, currencies: String) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = JsonObject().put("username", username).put("currencies", currencies))

/** `POST /settings/legal`: a new version of the legal text; locale and version only, never the text. */
class UpdatedMarketLegalTextLog(userId: Long, username: String, pluginId: String, locale: String, version: Int) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = JsonObject().put("username", username).put("locale", locale).put("version", version))

/** `PUT /servers/:id/settings`: the per-server override of the `mc*` keys changed (the values are not logged). */
class UpdatedMarketServerSettingsLog(userId: Long, username: String, pluginId: String, serverId: Long) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = JsonObject().put("username", username).put("serverId", serverId))
