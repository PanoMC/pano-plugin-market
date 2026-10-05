package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.*
import io.vertx.sqlclient.SqlClient

/** Durable counters and locks (01 section 12, 11 section 12.1). Subjects are truncated to 191 characters. */
abstract class MarketThrottleDao : MarketDao<MarketThrottle>(MarketThrottle::class.java) {
    /**
     * Records one failure with the single `INSERT ... ON DUPLICATE KEY UPDATE` of 11 section 12.1: a window older than
     * [windowMs] restarts at 1, the lock is set when the new count reaches [threshold]. Returns `lockedUntil` when the
     * row is locked after this call, else `null`.
     */
    abstract suspend fun fail(scope: String, subject: String, threshold: Int, windowMs: Long, lockMs: Long, now: Long, sqlClient: SqlClient): Long?

    abstract suspend fun get(scope: String, subject: String, sqlClient: SqlClient): MarketThrottle?

    /** `lockedUntil` when the subject is locked at [now], else `null`. */
    abstract suspend fun lockedUntil(scope: String, subject: String, now: Long, sqlClient: SqlClient): Long?

    /** The latest `lockedUntil` over [subjects] that is still in the future, one `SELECT ... IN (...)`; `null` when none. */
    abstract suspend fun lockedUntilAny(scope: String, subjects: List<String>, now: Long, sqlClient: SqlClient): Long?

    /** Deletes the row; `true` when it existed. */
    abstract suspend fun reset(scope: String, subject: String, sqlClient: SqlClient): Boolean

    /** Housekeeping: rows whose window started before [windowBefore] and whose lock is null or past [now]. */
    abstract suspend fun purge(windowBefore: Long, now: Long, sqlClient: SqlClient): Int
}
