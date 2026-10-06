package com.panomc.plugins.market.log

import com.panomc.platform.db.model.PluginActivityLog
import io.vertx.core.json.JsonObject

/**
 * An admin cancelled a subscription (`POST /subscriptions/:id/cancel`, 09 section 10.1). [atPeriodEnd] is what the admin asked for (kept in the details,
 * the text of the log does not depend on it).
 */
class CancelledMarketSubscriptionLog(
    userId: Long,
    username: String,
    pluginId: String,
    subscriptionId: Long,
    atPeriodEnd: Boolean
) : PluginActivityLog(
    userId = userId,
    pluginId = pluginId,
    details = JsonObject().put("username", username).put("subscriptionId", subscriptionId).put("atPeriodEnd", atPeriodEnd)
)

/** An admin retried the charge of a failed renewal now (`POST /subscriptions/:id/retry`, 09 section 9.3). */
class RetriedMarketSubscriptionChargeLog(
    userId: Long,
    username: String,
    pluginId: String,
    subscriptionId: Long
) : PluginActivityLog(
    userId = userId,
    pluginId = pluginId,
    details = JsonObject().put("username", username).put("subscriptionId", subscriptionId)
)
