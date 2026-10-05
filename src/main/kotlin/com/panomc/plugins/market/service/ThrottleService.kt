package com.panomc.plugins.market.service

import com.panomc.plugins.market.core.abuse.AbuseLimits
import com.panomc.plugins.market.core.abuse.ThrottlePolicy
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketThrottleDao
import io.vertx.sqlclient.SqlClient

/**
 * The durable counters and locks of `market_throttle` (11 section 12.1, MK-152). Every call is one statement (or one read) on a pooled
 * connection: a lock is a row, so it survives a restart and a new pool, and two requests that fail at the same moment both count
 * (`INSERT ... ON DUPLICATE KEY UPDATE`, never read-modify-write). Subjects are cut to 191 characters by the DAO; a [threshold] of 0
 * disables [fail] (no row is written).
 *
 * Used by [CodeGuard] (scopes `COUPON`, `GIFT`), by the checkout order limit L3 (`CHECKOUT`, via [hit] / [countInWindow]) and by the secret
 * reveal of 11 section 8.3 (`REVEAL`, [isLocked] / [fail] / [reset] with 5 failures in 10 minutes and a 10 minute lock).
 */
class ThrottleService(
    private val throttles: MarketThrottleDao,
    private val client: suspend () -> SqlClient,
    private val clock: Clock
) {
    /** `lockedUntil` (epoch ms) when [subject] is locked at this moment, else `null`. */
    suspend fun isLocked(scope: String, subject: String): Long? = throttles.lockedUntil(scope, subject, clock.now(), client())

    /** The latest `lockedUntil` over [subjects] that is still in the future, one `SELECT ... IN (...)`; `null` when none or [subjects] is empty. */
    suspend fun isLockedAny(scope: String, subjects: List<String>): Long? = throttles.lockedUntilAny(scope, subjects, clock.now(), client())

    /**
     * Records one failure of [subject]. The window restarts at [windowMinutes] after its first failure; the subject is locked for [lockMinutes]
     * when the count reaches [threshold]. Returns `lockedUntil` when the subject is locked after this call, else `null`. [threshold] 0 or less: nothing happens.
     */
    suspend fun fail(scope: String, subject: String, threshold: Int, windowMinutes: Int, lockMinutes: Int): Long? {
        if (threshold <= 0) return null

        return throttles.fail(scope, subject, threshold, windowMinutes * 60_000L, lockMinutes * 60_000L, clock.now(), client())
    }

    /** Forgets [subject] (a correct password ends the reveal counter). `true` when a row existed. */
    suspend fun reset(scope: String, subject: String): Boolean = throttles.reset(scope, subject, client())

    /**
     * Counts one event of [subject] in a window of [windowMs] that starts at the first event and never locks (L3: orders created per IP and hour).
     * Returns the count in the current window after this event.
     */
    suspend fun hit(scope: String, subject: String, windowMs: Long): Int {
        val now = clock.now()

        // a threshold no count can reach: the same statement as `fail`, so concurrent events are all counted, but it never sets a lock
        throttles.fail(scope, subject, Int.MAX_VALUE, windowMs, 0, now, client())

        return countInWindow(scope, subject, windowMs)
    }

    /** The events of [subject] in the window that is open now (0 when its window has ended or there is no row). */
    suspend fun countInWindow(scope: String, subject: String, windowMs: Long): Int = windowOf(scope, subject, windowMs)?.first ?: 0

    /** `(count, windowEnd)` of the open window of [subject], `null` when there is none. */
    suspend fun windowOf(scope: String, subject: String, windowMs: Long): Pair<Int, Long>? {
        val row = throttles.get(scope, subject, client()) ?: return null
        val end = row.windowStart + windowMs

        return if (end > clock.now()) row.count to end else null
    }

    /** Housekeeping (11 section 12.1): rows older than two days whose lock is over. Returns the number of rows deleted. */
    suspend fun purge(): Int {
        val now = clock.now()

        return throttles.purge(now - AbuseLimits.THROTTLE_RETENTION_DAYS * 86_400_000L, now, client())
    }

    /** `Retry-After` seconds (at least 1) for a lock that ends at [until]. */
    fun retryAfterSeconds(until: Long): Long = ThrottlePolicy.retryAfterSeconds(until, clock.now()).toLong()
}
