package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.*
import io.vertx.sqlclient.SqlClient

/** Credit ledger transactions (01 section 7.2). Rows are never updated or deleted. */
abstract class MarketCreditTxDao : MarketDao<MarketCreditTx>(MarketCreditTx::class.java) {
    /** The new id, or `null` when the idempotency key exists already (`uq_idem`): a replay returns the existing row. */
    abstract suspend fun add(tx: MarketCreditTx, sqlClient: SqlClient): Long?

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketCreditTx?

    /** The transaction posted under this idempotency key (`uq_idem`). */
    abstract suspend fun getByIdempotencyKey(idempotencyKey: String, sqlClient: SqlClient): MarketCreditTx?

    /** The newest [limit] transactions of a user, newest first (`idx_user`). */
    abstract suspend fun getByUserId(userId: Long, limit: Int, sqlClient: SqlClient): List<MarketCreditTx>

    /** Every transaction of an order, oldest first (`idx_order`). */
    abstract suspend fun getByOrderId(orderId: Long, sqlClient: SqlClient): List<MarketCreditTx>
}
