package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.*
import io.vertx.sqlclient.SqlClient

/** Store goals (01 section 12). */
abstract class MarketGoalDao : MarketDao<MarketGoal>(MarketGoal::class.java) {
    /** The new id. */
    abstract suspend fun add(goal: MarketGoal, sqlClient: SqlClient): Long

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketGoal?

    /** Every goal by `position`, then id. */
    abstract suspend fun getAll(sqlClient: SqlClient): List<MarketGoal>

    /** Goals in `ACTIVE` status, by `position`, then id. */
    abstract suspend fun getActive(sqlClient: SqlClient): List<MarketGoal>

    /** Writes the configurable columns (not `progress`, `completedAt`, `createdAt`). */
    abstract suspend fun update(goal: MarketGoal, sqlClient: SqlClient): Boolean

    /** Atomic `progress = progress + delta` (the delta may be negative for a reversal, the result never below 0). */
    abstract suspend fun addProgress(id: Long, delta: Long, now: Long, sqlClient: SqlClient): Boolean

    /** Starts a new period: `progress = 0`, `periodStart`, `completedAt = NULL`. */
    abstract suspend fun resetPeriod(id: Long, periodStart: Long, now: Long, sqlClient: SqlClient): Boolean

    /**
     * Rolls a periodic goal into the period that began at [periodStart], once: the reset only happens while the stored `periodStart` is older (or unset), so of
     * several writers that saw the same finished period the first one resets and the others change nothing (`false`) and must not wipe the progress that
     * was added after the roll.
     */
    abstract suspend fun rollPeriod(id: Long, periodStart: Long, now: Long, sqlClient: SqlClient): Boolean

    /** Sets `completedAt` once: `false` when it was set already. */
    abstract suspend fun markCompleted(id: Long, now: Long, sqlClient: SqlClient): Boolean

    abstract suspend fun delete(id: Long, sqlClient: SqlClient): Boolean
}
