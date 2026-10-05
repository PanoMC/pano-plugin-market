package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.CreatorEarningState
import com.panomc.plugins.market.db.model.MarketCreatorEarning
import io.vertx.sqlclient.SqlClient

abstract class MarketCreatorEarningDao : MarketDao<MarketCreatorEarning>(MarketCreatorEarning::class.java) {
    /** The new id, or `null` when the order already has an earning for the code (`uq_order_code`). */
    abstract suspend fun add(earning: MarketCreatorEarning, sqlClient: SqlClient): Long?

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketCreatorEarning?

    abstract suspend fun get(orderId: Long, creatorCodeId: Long, sqlClient: SqlClient): MarketCreatorEarning?

    abstract suspend fun getByCodeId(creatorCodeId: Long, sqlClient: SqlClient): List<MarketCreatorEarning>

    abstract suspend fun getByOrderId(orderId: Long, sqlClient: SqlClient): List<MarketCreatorEarning>

    /** Guarded state change; `false` when the row was not in `from`. */
    abstract suspend fun transition(id: Long, from: CreatorEarningState, to: CreatorEarningState, sqlClient: SqlClient): Boolean

    /** `PENDING` to `AVAILABLE` for every row whose `availableAt <= now`; returns the number flipped. */
    abstract suspend fun releaseDue(now: Long, sqlClient: SqlClient): Int

    /** Atomically adds [delta] to `reversedAmount` (never beyond `amount`); `false` when nothing changed. */
    abstract suspend fun addReversed(id: Long, delta: Long, sqlClient: SqlClient): Boolean

    /** Sets `payoutId` and moves `AVAILABLE` to `PAID`; `false` when the row was not `AVAILABLE`. */
    abstract suspend fun attachPayout(id: Long, payoutId: Long, sqlClient: SqlClient): Boolean

    /** `sum(amount - reversedAmount)` over rows of the code in the given states. */
    abstract suspend fun sumNet(creatorCodeId: Long, states: List<CreatorEarningState>, sqlClient: SqlClient): Long
}
