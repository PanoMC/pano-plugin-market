package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.*
import io.vertx.sqlclient.SqlClient

/** Deliveries (01 section 9.1). `payload` is immutable after insert. */
abstract class MarketDeliveryDao : MarketDao<MarketDelivery>(MarketDelivery::class.java) {
    /** The new id, or `null` when the idempotency key exists already (`uq_idem`). */
    abstract suspend fun add(delivery: MarketDelivery, sqlClient: SqlClient): Long?

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketDelivery?

    abstract suspend fun getByIdempotencyKey(idempotencyKey: String, sqlClient: SqlClient): MarketDelivery?

    /** Every delivery of an order, oldest first (`idx_order`). */
    abstract suspend fun getByOrderId(orderId: Long, sqlClient: SqlClient): List<MarketDelivery>

    /** Every delivery of an order item, oldest first (`idx_item`). */
    abstract suspend fun getByOrderItemId(orderItemId: Long, sqlClient: SqlClient): List<MarketDelivery>

    /** Rows of [status] whose `nextAttemptAt` is at or before [now], oldest schedule first (`idx_due`). */
    abstract suspend fun getDue(status: DeliveryStatus, now: Long, limit: Int, sqlClient: SqlClient): List<MarketDelivery>

    /** Rows of one server in [status] (`idx_server`), oldest first. */
    abstract suspend fun getByServerAndStatus(serverId: Long, status: DeliveryStatus, limit: Int, sqlClient: SqlClient): List<MarketDelivery>

    /**
     * Moves a row from [from] to [to] only while it is still in [from] (compare and set). Returns `true` when the row
     * moved. Sets `nextAttemptAt`, `lastErrorCode` and `lastError` as given and bumps `updatedAt`.
     */
    abstract suspend fun transition(
        id: Long, from: DeliveryStatus, to: DeliveryStatus, now: Long,
        nextAttemptAt: Long?, lastErrorCode: String?, lastError: String?, sqlClient: SqlClient
    ): Boolean
}
