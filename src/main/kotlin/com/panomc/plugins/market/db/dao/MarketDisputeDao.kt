package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.*
import io.vertx.sqlclient.SqlClient

/** Disputes (01 section 6.5). */
abstract class MarketDisputeDao : MarketDao<MarketDispute>(MarketDispute::class.java) {
    /** The new id, or `null` on a unique-key duplicate. */
    abstract suspend fun add(dispute: MarketDispute, sqlClient: SqlClient): Long?

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketDispute?

    /** The dispute of a gateway dispute id (`uq_provider_dispute`). */
    abstract suspend fun getByProviderDispute(providerId: String, gatewayDisputeId: String, sqlClient: SqlClient): MarketDispute?

    /** Every dispute of an order, oldest first (`idx_order`). */
    abstract suspend fun getByOrderId(orderId: Long, sqlClient: SqlClient): List<MarketDispute>
}
