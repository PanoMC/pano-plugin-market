package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.*
import io.vertx.sqlclient.SqlClient

/** Refunds (01 section 6.4). */
abstract class MarketRefundDao : MarketDao<MarketRefund>(MarketRefund::class.java) {
    /** The new id, or `null` on a unique-key duplicate. */
    abstract suspend fun add(refund: MarketRefund, sqlClient: SqlClient): Long?

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketRefund?

    /** The refund created under this idempotency key (`uq_idem`). */
    abstract suspend fun getByIdempotencyKey(idempotencyKey: String, sqlClient: SqlClient): MarketRefund?

    /** The refund of a gateway refund id (`uq_provider_refund`). */
    abstract suspend fun getByProviderRefund(providerId: String, gatewayRefundId: String, sqlClient: SqlClient): MarketRefund?

    /** Every refund of an order, oldest first (`idx_order`). */
    abstract suspend fun getByOrderId(orderId: Long, sqlClient: SqlClient): List<MarketRefund>
}
