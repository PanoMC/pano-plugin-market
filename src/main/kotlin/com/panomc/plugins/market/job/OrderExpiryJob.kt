package com.panomc.plugins.market.job

import com.panomc.plugins.market.core.order.OrderActor
import com.panomc.plugins.market.core.order.OrderEvent
import com.panomc.plugins.market.core.order.OrderTimings
import com.panomc.plugins.market.core.payment.PaymentAttemptEvent
import com.panomc.plugins.market.core.payment.ProviderMoneyPolicy
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketPaymentDao
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.db.tx.OrderChild
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.service.AfterCommit
import com.panomc.plugins.market.service.AttemptFacts
import com.panomc.plugins.market.service.OrderService
import com.panomc.plugins.market.service.PaymentService
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Expires what nobody paid (06 section 12), cadence 30 s on `MarketScheduler`, batch [BATCH] per step. [runOnce] returns the number of attempts and
 * orders it moved.
 *
 * Step 1, attempts, so that orders are not held up by dead attempts: `CREATED` / `PENDING` attempts past their `expiresAt` (a `CREATED` attempt of
 * `free` / `credits` is skipped, `PaymentReconcileJob` re-drives it) and `PROCESSING` attempts past `expiresAt` plus their grace
 * (`OrderTimings.processingGraceMs`: 0 for a buyer notice, 24 h for a provider that reported `Pending`, 14 d for `longPending`). One transaction per
 * attempt under `Locks.forOrder(PAYMENT)`; the conditions are checked again under the lock and the attempt machine's `Expired` event does the write.
 *
 * Step 2, orders: `PENDING` orders past `expiresAt`. One transaction per order under `Locks.forOrder(RELEASE)`; the order is left alone while one of
 * its attempts is `PROCESSING`, `SUCCEEDED` or `REVIEW` (an event is being applied) or `CREATED` and younger than 2 minutes (a start call is in
 * flight); otherwise `OrderService.transition(Expire)` is O6 (stock, codes, credit hold released, open attempts `EXPIRED`). After the commit the
 * gateway is told (`cancelPayment`, failures ignored).
 *
 * Safe to run twice and to be killed mid-row: every write is a conditional update inside one transaction, and a row that another call changed
 * meanwhile is found changed under the lock and left alone (it is counted once, by the call that moved it). Every row runs in its own
 * `catch (Throwable)`: one bad row never stops the tick.
 */
class OrderExpiryJob(
    private val clock: Clock,
    private val db: MarketDb,
    private val locks: Locks,
    private val orders: MarketOrderDao,
    private val paymentDao: MarketPaymentDao,
    private val orderService: OrderService,
    private val payments: PaymentService,
    private val sqlClient: suspend () -> SqlClient,
    private val batch: Int = BATCH
) {
    private fun table(name: String) = "`${orders.prefix()}$name`"

    /** Attempts and orders moved by this call. */
    suspend fun runOnce(): Int {
        val client = sqlClient()

        // a step that fails as a whole (a LinkageError out of a provider, a query error) never keeps the other one from running
        return step("attempts") { expireAttempts(client) } + step("orders") { expireOrders(client) }
    }

    private suspend fun step(name: String, block: suspend () -> Int): Int = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        logger.warn("the {} step of the expiry job failed: {}", name, t.toString())

        0
    }

    // ===================================================================================== step 1: attempts

    private class Candidate(val id: Long, val orderId: Long, val grace: Long)

    private suspend fun candidates(client: SqlClient, now: Long): List<Candidate> {
        val found = LinkedHashMap<Long, Candidate>()

        // CREATED / PENDING: expiresAt <= now. The built-ins' CREATED attempts are re-driven, not expired.
        client.preparedQuery(
            "SELECT `id`, `orderId` FROM ${table("market_payment")} WHERE `status` IN ('CREATED', 'PENDING') AND `expiresAt` IS NOT NULL AND `expiresAt` <= ? " +
                "AND NOT (`status` = 'CREATED' AND `providerId` IN (?, ?)) ORDER BY `id` LIMIT ?"
        ).execute(Tuple.of(now, OrderTimings.FREE_PROVIDER, OrderTimings.CREDITS_PROVIDER, batch)).coAwait()
            .forEach { found[it.getLong("id")] = Candidate(it.getLong("id"), it.getLong("orderId"), 0L) }

        // PROCESSING: the grace depends on the provider, so each provider that has a PROCESSING attempt past its expiry gets its own cut-off
        val providers = client.preparedQuery(
            "SELECT DISTINCT `providerId` FROM ${table("market_payment")} WHERE `status` = 'PROCESSING' AND `expiresAt` IS NOT NULL AND `expiresAt` <= ?"
        ).execute(Tuple.of(now)).coAwait().map { it.getString("providerId") }

        for (providerId in providers) {
            val grace = graceOf(providerId, client)

            client.preparedQuery(
                "SELECT `id`, `orderId` FROM ${table("market_payment")} WHERE `status` = 'PROCESSING' AND `providerId` = ? AND `expiresAt` IS NOT NULL AND `expiresAt` <= ? ORDER BY `id` LIMIT ?"
            ).execute(Tuple.of(providerId, now - grace, batch)).coAwait()
                .forEach { found[it.getLong("id")] = Candidate(it.getLong("id"), it.getLong("orderId"), grace) }
        }

        return found.values.take(batch * 2)
    }

    /**
     * The grace of a `PROCESSING` attempt of [providerId]. A `bank-transfer` attempt is `PROCESSING` only because of the buyer's notice (the provider
     * has no inbound events and no status query, `Pending(AWAITING_BANK)` comes from the notice route), and a notice never earns a grace (06 section 9.1).
     */
    private suspend fun graceOf(providerId: String, client: SqlClient): Long {
        if (providerId == OrderTimings.BANK_TRANSFER_PROVIDER) return OrderTimings.processingGraceMs(longPending = false, buyerNotice = true)

        // a provider that is gone (plugin stopped, license lapsed) gets the short grace: its money can still arrive later and becomes O9
        val longPending = try {
            payments.traitsOf(providerId, client)?.longPending ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            false
        }

        return OrderTimings.processingGraceMs(longPending, buyerNotice = false)
    }

    private suspend fun expireAttempts(client: SqlClient): Int {
        val now = clock.now()
        var moved = 0

        for (candidate in candidates(client, now)) {
            try {
                if (expireAttempt(candidate)) moved++
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logger.warn("expiring attempt {} failed unexpectedly: {}", candidate.id, t.toString())
            }
        }

        return moved
    }

    private suspend fun expireAttempt(candidate: Candidate): Boolean {
        val after = ArrayList<AfterCommit>()

        val changed = db.txRestartingOnOrderChange { conn ->
            after.clear()

            locks.forOrder(conn, candidate.orderId, OrderLockScope.PAYMENT) { locked ->
                locks.children(conn, candidate.orderId, OrderChild.PAYMENT)

                val attempt = paymentDao.getById(candidate.id, conn) ?: return@forOrder false
                val now = clock.now()
                val builtIn = attempt.providerId == OrderTimings.FREE_PROVIDER || attempt.providerId == OrderTimings.CREDITS_PROVIDER
                val due = attempt.expiresAt != null && when (attempt.status) {
                    PaymentStatus.CREATED -> !builtIn && attempt.expiresAt <= now
                    PaymentStatus.PENDING -> attempt.expiresAt <= now
                    PaymentStatus.PROCESSING -> attempt.expiresAt + candidate.grace <= now
                    else -> false
                }

                if (!due) return@forOrder false

                payments.applyIn(conn, locked, candidate.id, PaymentAttemptEvent.Expired, AttemptFacts.NONE, ProviderMoneyPolicy(), OrderActor.SYSTEM, after).changed
            }
        }

        if (after.isNotEmpty()) payments.runAfterCommit(after, sqlClient())

        return changed
    }

    // ======================================================================================= step 2: orders

    private suspend fun expireOrders(client: SqlClient): Int {
        val now = clock.now()
        // an order with an attempt that is being resolved is never a candidate (it would otherwise sit in the first rows of every batch and starve the rest)
        val ids = client.preparedQuery(
            "SELECT o.`id` FROM ${table("market_order")} o WHERE o.`status` = 'PENDING' AND o.`expiresAt` IS NOT NULL AND o.`expiresAt` <= ? " +
                "AND NOT EXISTS (SELECT 1 FROM ${table("market_payment")} p WHERE p.`orderId` = o.`id` AND p.`status` IN ('PROCESSING', 'SUCCEEDED', 'REVIEW')) " +
                "ORDER BY o.`expiresAt`, o.`id` LIMIT ?"
        ).execute(Tuple.of(now, batch)).coAwait().map { it.getLong("id") }
        var moved = 0

        for (id in ids) {
            try {
                if (expireOrder(id)) moved++
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logger.warn("expiring order {} failed unexpectedly: {}", id, t.toString())
            }
        }

        return moved
    }

    private suspend fun expireOrder(orderId: Long): Boolean {
        val after = ArrayList<AfterCommit>()

        val moved = db.txRestartingOnOrderChange { conn ->
            after.clear()

            locks.forOrder(conn, orderId, OrderLockScope.RELEASE) { locked ->
                val order = orders.getById(orderId, conn) ?: return@forOrder false
                val now = clock.now()

                // re-checked under the lock: a payment event, a /pay or the owner's cancel may have won the race
                if (order.status != OrderStatus.PENDING || order.expiresAt == null || order.expiresAt > now) return@forOrder false

                val attempts = locks.children(conn, orderId, OrderChild.PAYMENT).let { paymentDao.getByOrderId(orderId, conn) }
                val busy = attempts.any {
                    it.status == PaymentStatus.PROCESSING || it.status == PaymentStatus.SUCCEEDED || it.status == PaymentStatus.REVIEW ||
                        (it.status == PaymentStatus.CREATED && !OrderTimings.createdAttemptSettled(now, it.createdAt))
                }

                if (busy) return@forOrder false

                val result = orderService.transition(conn, locked, OrderEvent.Expire(now))

                after += result.after

                result.moved
            }
        }

        if (after.isNotEmpty()) payments.runAfterCommit(after, sqlClient())

        return moved
    }

    companion object {
        const val BATCH = 50

        private val logger = LoggerFactory.getLogger(OrderExpiryJob::class.java)
    }
}
