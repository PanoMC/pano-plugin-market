package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.*
import io.vertx.sqlclient.SqlClient

/** Payment attempts (01 section 6.2). */
abstract class MarketPaymentDao : MarketDao<MarketPayment>(MarketPayment::class.java) {
    /** The new id, or `null` on a unique-key duplicate. */
    abstract suspend fun add(payment: MarketPayment, sqlClient: SqlClient): Long?

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketPayment?

    /** The attempt with this merchant reference (`uq_reference`). */
    abstract suspend fun getByReference(reference: String, sqlClient: SqlClient): MarketPayment?

    /** The attempt behind this notify / return token (`uq_token`). */
    abstract suspend fun getByToken(token: String, sqlClient: SqlClient): MarketPayment?

    /** The attempt of a gateway transaction (`uq_provider_txn`). */
    abstract suspend fun getByProviderTransaction(providerId: String, gatewayTransactionId: String, sqlClient: SqlClient): MarketPayment?

    /** Every attempt of an order, oldest first (`idx_order`). */
    abstract suspend fun getByOrderId(orderId: Long, sqlClient: SqlClient): List<MarketPayment>
}
