package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.*
import io.vertx.sqlclient.SqlClient

/** The outbound webhook queue and log (01 section 9.4). */
abstract class MarketWebhookDeliveryDao : MarketDao<MarketWebhookDelivery>(MarketWebhookDelivery::class.java) {
    /** The new id, or `null` when the event id exists already (`uq_eventId`). */
    abstract suspend fun add(delivery: MarketWebhookDelivery, sqlClient: SqlClient): Long?

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketWebhookDelivery?

    abstract suspend fun getByEventId(eventId: String, sqlClient: SqlClient): MarketWebhookDelivery?

    /** The newest [limit] rows of an endpoint, newest first (`idx_endpoint`). */
    abstract suspend fun getByEndpointId(endpointId: Long, limit: Int, sqlClient: SqlClient): List<MarketWebhookDelivery>

    abstract suspend fun getByOrderId(orderId: Long, sqlClient: SqlClient): List<MarketWebhookDelivery>

    /** Rows in [status] whose `nextAttemptAt` is at or before [now], oldest schedule first (`idx_due`). */
    abstract suspend fun getDue(status: WebhookDeliveryStatus, now: Long, limit: Int, sqlClient: SqlClient): List<MarketWebhookDelivery>

    /**
     * Stores the outcome of one attempt on a row still in [from] (compare and set): the new status, the attempt count,
     * the next schedule and what the receiver answered. Returns `true` when the row was updated.
     */
    abstract suspend fun markResult(
        id: Long, from: WebhookDeliveryStatus, to: WebhookDeliveryStatus, attempts: Int, nextAttemptAt: Long?,
        statusCode: Int?, error: String?, response: String?, durationMs: Int?, deliveredAt: Long?, now: Long, sqlClient: SqlClient
    ): Boolean
}
