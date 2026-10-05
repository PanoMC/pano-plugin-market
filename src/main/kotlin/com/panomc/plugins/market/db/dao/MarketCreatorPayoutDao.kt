package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.CreatorPayoutState
import com.panomc.plugins.market.db.model.MarketCreatorPayout
import io.vertx.sqlclient.SqlClient

abstract class MarketCreatorPayoutDao : MarketDao<MarketCreatorPayout>(MarketCreatorPayout::class.java) {
    /** The new id, or `null` when the `idempotencyKey` exists (`uq_idem`): the caller reads it with [getByIdempotencyKey]. */
    abstract suspend fun add(payout: MarketCreatorPayout, sqlClient: SqlClient): Long?

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketCreatorPayout?

    abstract suspend fun getByIdempotencyKey(key: String, sqlClient: SqlClient): MarketCreatorPayout?

    /** Newest first. */
    abstract suspend fun getByCodeId(creatorCodeId: Long, sqlClient: SqlClient): List<MarketCreatorPayout>

    /** Guarded state change (sets `paidBy`, `paidAt` and `creditTxId` when given); `false` when not in `from`. */
    abstract suspend fun transition(
        id: Long,
        from: CreatorPayoutState,
        to: CreatorPayoutState,
        paidBy: Long?,
        paidAt: Long?,
        creditTxId: Long?,
        updatedAt: Long,
        sqlClient: SqlClient
    ): Boolean
}
