package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.*
import io.vertx.sqlclient.SqlClient

/** Global store webhooks (01 section 9.3). `secret` and `headers` are ENC columns stored verbatim. */
abstract class MarketWebhookEndpointDao : MarketDao<MarketWebhookEndpoint>(MarketWebhookEndpoint::class.java) {
    abstract suspend fun add(endpoint: MarketWebhookEndpoint, sqlClient: SqlClient): Long

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketWebhookEndpoint?

    /** Every endpoint, oldest first. */
    abstract suspend fun getAll(sqlClient: SqlClient): List<MarketWebhookEndpoint>

    /** Replaces the editable columns of [endpoint] (not the failure counters). Returns `false` when the id is unknown. */
    abstract suspend fun update(endpoint: MarketWebhookEndpoint, now: Long, sqlClient: SqlClient): Boolean

    abstract suspend fun delete(id: Long, sqlClient: SqlClient): Boolean

    /**
     * Records the outcome of one delivery in one statement: a success resets `failureCount`; a failure increments it and,
     * on reaching [disableAfter] consecutive failures, disables the endpoint with `disabledReason`
     * [AUTO_DISABLED_REASON]. Always stores `lastStatusCode` and `lastDeliveryAt`.
     */
    abstract suspend fun recordOutcome(id: Long, success: Boolean, statusCode: Int?, now: Long, disableAfter: Int, sqlClient: SqlClient): Boolean

    companion object {
        const val AUTO_DISABLED_REASON = "AUTO_DISABLED_FAILURES"
        const val DEFAULT_DISABLE_AFTER = 50
    }
}
