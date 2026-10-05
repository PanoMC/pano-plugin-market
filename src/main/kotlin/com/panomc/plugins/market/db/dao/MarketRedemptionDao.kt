package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.MarketRedemption
import com.panomc.plugins.market.db.model.RedemptionKind
import com.panomc.plugins.market.db.model.RedemptionState
import io.vertx.sqlclient.SqlClient

abstract class MarketRedemptionDao : MarketDao<MarketRedemption>(MarketRedemption::class.java) {
    /** The new id, or `null` when `(kind, refId, orderId)` already has a row (`uq_kind_ref_order`). */
    abstract suspend fun add(redemption: MarketRedemption, sqlClient: SqlClient): Long?

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketRedemption?

    abstract suspend fun get(kind: RedemptionKind, refId: Long, orderId: Long, sqlClient: SqlClient): MarketRedemption?

    abstract suspend fun getByOrderId(orderId: Long, sqlClient: SqlClient): List<MarketRedemption>

    /** Moves a row `from` to `to`; `false` when it was not in `from` (guarded, so a state change happens once). */
    abstract suspend fun transition(id: Long, from: RedemptionState, to: RedemptionState, sqlClient: SqlClient): Boolean

    /** Rows of one code or discount in `HELD` or `APPLIED` (the number its `usedCount` mirrors). */
    abstract suspend fun countActive(kind: RedemptionKind, refId: Long, sqlClient: SqlClient): Long

    /**
     * Per-customer limit count (01 section 3.5): `HELD + APPLIED` rows where `buyerKey = ?`, or `email` matches
     * (when given), or `recipientKey` is one of [recipientKeys].
     */
    abstract suspend fun countForCustomer(
        kind: RedemptionKind,
        refId: Long,
        buyerKey: String,
        email: String?,
        recipientKeys: List<String>,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun deleteByOrderId(orderId: Long, sqlClient: SqlClient): Int
}
