package com.panomc.plugins.market.routes.panel.mail

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

// Mail activity log rows (MK-146; 04 section 10, 12 section 4.5 step 5). The recipient is never logged.

/** `RESENT_MARKET_ORDER_MAIL (orderId, kind)`. */
class ResentMarketOrderMailLog(userId: Long, username: String, pluginId: String, orderId: Long, kind: String) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = JsonObject().put("username", username).put("orderId", orderId).put("kind", kind))

/** `RETRIED_MARKET_MAIL (mailId, kind)`. */
class RetriedMarketMailLog(userId: Long, username: String, pluginId: String, mailId: Long, kind: String) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = JsonObject().put("username", username).put("mailId", mailId).put("kind", kind))

/** `SENT_MARKET_TEST_MAIL`. */
class SentMarketTestMailLog(userId: Long, username: String, pluginId: String) :
    PluginActivityLog(userId = userId, pluginId = pluginId, details = JsonObject().put("username", username))
