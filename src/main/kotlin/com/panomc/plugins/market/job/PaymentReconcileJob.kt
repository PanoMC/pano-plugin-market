package com.panomc.plugins.market.job

import com.panomc.plugins.market.core.order.OrderTimings
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketPaymentDao
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.service.PaymentService
import com.panomc.plugins.market.service.PaymentStartFailed
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * When the next status query of an open attempt is due (02 section 8): at 1, 3, 10 and 30 minutes after the start, then hourly, then every 6 hours,
 * until `expiresAt` plus 24 hours (`longPending`: plus 14 days). Pure.
 *
 * The first query is scheduled by `PaymentService` (60 s after the start); [next] is called when query number [queryCount] is claimed and returns
 * the due time of the following one. The ladder counts claimed queries (the status poll of the order page claims one as well), so it only moves
 * forward; an attempt older than 24 hours is queried every 6 hours instead of hourly.
 */
object ReconcileSchedule {
    const val MINUTE_MS = OrderTimings.MINUTE_MS
    const val HOUR_MS = OrderTimings.HOUR_MS
    const val SLOW_AFTER_MS = OrderTimings.DAY_MS
    const val SLOW_EVERY_MS = 6 * HOUR_MS

    /** The delay after query number 1, 2 and 3: the queries land at 1, 3, 10 and 30 minutes (the first one is 1 minute after the start). */
    private val LADDER_MS = longArrayOf(2 * MINUTE_MS, 7 * MINUTE_MS, 20 * MINUTE_MS)

    /** The provider hint is trusted within these bounds. */
    const val HINT_MIN_MS = 30_000L
    const val HINT_MAX_MS = 6 * HOUR_MS

    /** `expiresAt + 24 h`, or `+ 14 d` for a `longPending` provider; an attempt without `expiresAt` counts from its creation. */
    fun horizonEnd(createdAt: Long, expiresAt: Long?, longPending: Boolean): Long =
        (expiresAt ?: (createdAt + OrderTimings.DAY_MS)) + if (longPending) OrderTimings.LONG_PROCESSING_GRACE_MS else OrderTimings.PROCESSING_GRACE_MS

    /** The due time of the query after number [queryCount] (counted from 1), `null` when the horizon is over. The last slot is the horizon itself. */
    fun next(now: Long, createdAt: Long, expiresAt: Long?, queryCount: Int, longPending: Boolean): Long? {
        val end = horizonEnd(createdAt, expiresAt, longPending)

        if (now >= end) return null

        val delay = when {
            queryCount in 1..LADDER_MS.size -> LADDER_MS[queryCount - 1]
            now - createdAt >= SLOW_AFTER_MS -> SLOW_EVERY_MS
            else -> HOUR_MS
        }

        return minOf(now + delay, end)
    }

    /** The provider's own `pollAgainAfterSeconds`, clamped to [HINT_MIN_MS]..[HINT_MAX_MS]; `null` when it gave none (or a non-positive one). */
    fun hinted(now: Long, pollAgainAfterSeconds: Long?): Long? =
        pollAgainAfterSeconds?.takeIf { it > 0 }?.let { now + (it * 1000L).coerceIn(HINT_MIN_MS, HINT_MAX_MS) }
}

/**
 * The payment half of the background work (02 section 8 and 10 guarantee 1, 06 section 9.2 step 6). [runOnce] does two things and returns the number
 * of attempts it claimed:
 *
 * 1. **Re-drive**: `CREATED` attempts of the built-ins `free` and `credits` older than [REDRIVE_AFTER_MS] (a crash between the commit of checkout and
 *    the start) get `PaymentService.startAttempt` again; both providers are deterministic and side-effect free, so the order completes without the buyer.
 * 2. **Reconcile**: attempts of the other providers that are due (`nextQueryAt <= now`; a `CREATED` attempt older than 2 minutes counts as due once)
 *    are queried with `QueryReason.RECONCILE` and what the gateway reports is applied through `PaymentService.applyEvent`. `unknown()`, a timeout, a
 *    missing provider or a throwing one never fail an attempt; the next slot of [ReconcileSchedule] asks again.
 *
 * Every row is claimed by a conditional update (a compare and swap on `queryCount`, or a short lease on `nextQueryAt`), so two `runOnce` calls at the
 * same moment handle a row once; a crash after the claim just means the query happens at the next slot. Every row runs in its own `catch (Throwable)`
 * (a `LinkageError` included): one bad row never stops the tick.
 */
class PaymentReconcileJob(
    private val clock: Clock,
    private val payments: PaymentService,
    private val orders: MarketOrderDao,
    private val paymentDao: MarketPaymentDao,
    private val sqlClient: suspend () -> SqlClient,
    private val batch: Int = BATCH
) {
    private fun table(name: String) = "`${orders.prefix()}$name`"

    /** Attempts claimed by this call. */
    suspend fun runOnce(): Int {
        val client = sqlClient()

        return redrive(client) + reconcile(client)
    }

    // ================================================================================================== re-drive

    private suspend fun redrive(client: SqlClient): Int {
        val now = clock.now()
        val rows = client.preparedQuery(
            "SELECT `id`, `orderId` FROM ${table("market_payment")} WHERE `status` = 'CREATED' AND `providerId` IN (?, ?) AND `createdAt` <= ? " +
                "AND (`nextQueryAt` IS NULL OR `nextQueryAt` <= ?) ORDER BY `id` LIMIT ?"
        ).execute(Tuple.of(OrderTimings.FREE_PROVIDER, OrderTimings.CREDITS_PROVIDER, now - REDRIVE_AFTER_MS, now, batch)).coAwait()
        var handled = 0

        for (row in rows) {
            val id = row.getLong("id")
            val orderId = row.getLong("orderId")

            try {
                // the lease: a second runOnce (or a crashed one) finds nextQueryAt in the future and leaves the row alone
                val claimed = client.preparedQuery(
                    "UPDATE ${table("market_payment")} SET `nextQueryAt` = ?, `updatedAt` = ? WHERE `id` = ? AND `status` = 'CREATED' AND (`nextQueryAt` IS NULL OR `nextQueryAt` <= ?)"
                ).execute(Tuple.of(now + REDRIVE_LEASE_MS, now, id, now)).coAwait().rowCount()

                if (claimed != 1) continue

                handled++

                try {
                    payments.startAttempt(orderId, id, emptyList(), client)
                } catch (e: PaymentStartFailed) {
                    logger.warn("re-driving attempt {} failed: {}", id, e.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logger.warn("re-driving attempt {} failed unexpectedly: {}", id, t.toString())
            }
        }

        return handled
    }

    // ================================================================================================== reconcile

    private suspend fun reconcile(client: SqlClient): Int {
        val now = clock.now()
        val rows = client.preparedQuery(
            "SELECT `id`, `orderId` FROM ${table("market_payment")} WHERE `providerId` NOT IN (?, ?) AND (" +
                "(`status` IN ('PENDING', 'PROCESSING') AND `nextQueryAt` IS NOT NULL AND `nextQueryAt` <= ?) OR " +
                "(`status` = 'CREATED' AND ((`nextQueryAt` IS NOT NULL AND `nextQueryAt` <= ?) OR " +
                "(`nextQueryAt` IS NULL AND `lastQueriedAt` IS NULL AND `createdAt` <= ?)))) " +
                "ORDER BY COALESCE(`nextQueryAt`, `createdAt`), `id` LIMIT ?"
        ).execute(Tuple.of(OrderTimings.FREE_PROVIDER, OrderTimings.CREDITS_PROVIDER, now, now, now - OrderTimings.CREATED_IN_FLIGHT_MS, batch)).coAwait()
        var handled = 0

        for (row in rows) {
            try {
                if (reconcileOne(row.getLong("id"), client)) handled++
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logger.warn("reconciling attempt {} failed unexpectedly: {}", row.getLong("id"), t.toString())
            }
        }

        return handled
    }

    private suspend fun reconcileOne(id: Long, client: SqlClient): Boolean {
        val seen = paymentDao.getById(id, client) ?: return false

        if (seen.status != PaymentStatus.CREATED && seen.status != PaymentStatus.PENDING && seen.status != PaymentStatus.PROCESSING) return false

        val now = clock.now()

        // the row may have been claimed by another call between the select and this read: only a row that is still due is ours to claim
        if (!due(seen, now)) return false

        val traits = payments.traitsOf(seen.providerId, client)
        val count = seen.queryCount + 1
        // a provider that is gone is asked again later (it may come back with the plugin); one without statusQuery is never asked
        val next = if (traits != null && !traits.statusQuery) null else ReconcileSchedule.next(now, seen.createdAt, seen.expiresAt, count, traits?.longPending ?: false)

        if (!claim(seen, count, next, now, client)) return false

        if (traits == null || !traits.statusQuery) return true

        // the slot is taken: whatever goes wrong from here (an event that cannot be applied, a failing read) stays in this row, the next slot asks again
        try {
            val order = orders.getById(seen.orderId, client) ?: return true
            val attempt = paymentDao.getById(seen.id, client) ?: return true

            val hint = when (val outcome = payments.reconcileQuery(order, attempt, client)) {
                is PaymentService.ReconcileQuery.Applied -> outcome.pollAgainAfterSeconds
                is PaymentService.ReconcileQuery.Unknown -> outcome.pollAgainAfterSeconds
                else -> null
            }

            ReconcileSchedule.hinted(clock.now(), hint)?.let { due -> hintNext(seen, due, client) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.warn("reconciling attempt {} failed, it is asked again at its next slot: {}", seen.id, t.toString())
        }

        return true
    }

    /** The select's own condition, judged again on the row as it is now. */
    private fun due(a: MarketPayment, now: Long): Boolean = when (a.status) {
        PaymentStatus.PENDING, PaymentStatus.PROCESSING -> a.nextQueryAt != null && a.nextQueryAt <= now
        PaymentStatus.CREATED ->
            (a.nextQueryAt != null && a.nextQueryAt <= now) || (a.nextQueryAt == null && a.lastQueriedAt == null && a.createdAt <= now - OrderTimings.CREATED_IN_FLIGHT_MS)
        else -> false
    }

    /** The compare and swap: only the call that still sees [seen]'s `queryCount`, `nextQueryAt` and status wins the slot. */
    private suspend fun claim(seen: MarketPayment, count: Int, next: Long?, now: Long, client: SqlClient): Boolean =
        client.preparedQuery(
            "UPDATE ${table("market_payment")} SET `nextQueryAt` = ?, `lastQueriedAt` = ?, `queryCount` = ?, `updatedAt` = ? WHERE `id` = ? AND `status` = ? AND `queryCount` = ? AND `nextQueryAt` <=> ?"
        ).execute(Tuple.of(next, now, count, now, seen.id, seen.status.name, seen.queryCount, seen.nextQueryAt)).coAwait().rowCount() == 1

    /** The gateway asked to be polled at a given time: moves the next slot of a still open attempt that has one (never past the horizon). */
    private suspend fun hintNext(seen: MarketPayment, due: Long, client: SqlClient) {
        val end = ReconcileSchedule.horizonEnd(seen.createdAt, seen.expiresAt, payments.traitsOf(seen.providerId, client)?.longPending ?: false)

        client.preparedQuery(
            "UPDATE ${table("market_payment")} SET `nextQueryAt` = ? WHERE `id` = ? AND `status` IN ('CREATED', 'PENDING', 'PROCESSING') AND `nextQueryAt` IS NOT NULL"
        ).execute(Tuple.of(minOf(due, end), seen.id)).coAwait()
    }

    companion object {
        const val BATCH = 50

        /** A `CREATED` free / credits attempt older than this is started again (06 section 9.2 step 6). */
        const val REDRIVE_AFTER_MS = 30_000L

        /** How long a re-drive claim keeps another call away (a crashed re-drive is tried again after this). */
        const val REDRIVE_LEASE_MS = 60_000L

        private val logger = LoggerFactory.getLogger(PaymentReconcileJob::class.java)
    }
}
