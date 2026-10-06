package com.panomc.plugins.market.job

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.order.OrderActor
import com.panomc.plugins.market.core.payment.PaymentAttemptEvent
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketSubscriptionDao
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.MarketSubscription
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.db.model.RemoteCancelState
import com.panomc.plugins.market.db.model.SubscriptionMode
import com.panomc.plugins.market.db.model.SubscriptionStatus
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.routes.api.payment.InboundEventContext
import com.panomc.plugins.market.service.AttemptFacts
import com.panomc.plugins.market.service.PaymentEventMapper
import com.panomc.plugins.market.service.PaymentService
import com.panomc.plugins.market.service.SubscriptionEventSink
import com.panomc.plugins.market.service.SubscriptionService
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.common.TestModeSupport
import com.panomc.plugins.market.spi.payment.CancelSubscriptionRequest
import com.panomc.plugins.market.spi.payment.CancelSubscriptionResult
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.QuerySubscriptionRequest
import com.panomc.plugins.market.spi.payment.RecurringChargeResult
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory

/**
 * The clock of the subscriptions (09 section 11): cadence 60 s on `MarketScheduler`, steps A to F in this order, each step takes at most [batch] rows ordered by
 * its due column, one row at a time in its own transaction(s) and its own `catch (Throwable)` (a failing row is logged and skipped, it never stops the batch).
 * No timer exists per subscription: every due time is a column, so the first tick after a restart does everything that is overdue, oldest first, and every step
 * is a conditional transition under the row lock, so a second run (or a webhook for the same subscription at the same moment) has one effect.
 *
 * - **A `chargeDue`** (09 section 8.3): `MERCHANT` rows with `nextChargeAt <= now`. tx1 writes the intent ([SubscriptionService.prepareCharge]: renewal row, order,
 *   attempt, 15 minute lease), the provider is called outside any transaction (30 s), tx2 is `PaymentService.applyEvent` of what it answered. The only call that is
 *   not idempotent, so it is never repeated for an attempt: an attempt that has not settled is asked about, never charged again.
 * - **B** (09 section 8.6): `MANUAL` rows get their renewal order and the reminder `subscriptionReminderDays` ahead; `GATEWAY` / `MERCHANT` rows the upcoming-charge notice.
 * - **C `periodOver`**, **D `graceOver`**: the pure state machine decides ([SubscriptionService.periodOver], [SubscriptionService.graceOver]).
 * - **E** (09 sections 10.3 and 11): the remote-cancel queue and the poll of `GATEWAY` rows (`querySubscription`, the answers go through [events]).
 * - **F** (hourly): a `PENDING` row whose order was released 30 days ago is closed. The renewal orders of ended subscriptions are cancelled after each step that ended one.
 */
class SubscriptionJob(
    private val clock: Clock,
    private val db: MarketDb,
    private val subs: SubscriptionService,
    private val subscriptions: MarketSubscriptionDao,
    private val payments: PaymentService,
    private val config: () -> MarketConfig,
    private val sqlClient: suspend () -> SqlClient,
    /** Applies the events a poll of a gateway returned (`SubscriptionUpdated`, `SubscriptionRenewed`, `SubscriptionPaymentFailed`). */
    private val events: SubscriptionEventSink,
    private val batch: Int = BATCH,
    private val callTimeoutMs: Long = CALL_TIMEOUT_MS
) {
    private fun table(name: String) = "`${subscriptions.prefix()}$name`"

    /** The last hourly run of step F and the renewal-order sweep; `0` = never (so the first tick runs them). */
    @Volatile
    private var lastCleanupAt: Long = 0L

    /** Rows handled by this call (every step counted). */
    suspend fun runOnce(): Int {
        val client = sqlClient()
        var handled = 0

        handled += step("charge") { chargeDue(client) }
        handled += step("prepare") { prepare(client) }
        handled += step("periodOver") { periodOver(client) }
        handled += step("graceOver") { graceOver(client) }
        handled += step("remote") { remote(client) }

        val now = clock.now()

        if (now - lastCleanupAt >= CLEANUP_EVERY_MS) {
            lastCleanupAt = now
            handled += step("pendingCleanup") { pendingCleanup(client) }
            handled += step("renewalCleanup") { cleanup(null) }
        }

        return handled
    }

    /** A step that throws must not stop the ones after it (the scheduler would otherwise skip all of them until its next slot). */
    private suspend fun step(name: String, body: suspend () -> Int): Int = try {
        body()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        logger.warn("subscription step {} failed: {}", name, t.toString())

        0
    }

    /** One row of a step: its failure is logged and the batch goes on. */
    private suspend fun row(step: String, id: Long, body: suspend () -> Unit): Boolean = try {
        body()

        true
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        logger.warn("subscription {} failed in step {}, skipped: {}", id, step, t.toString())

        false
    }

    // ================================================================================================== A: merchant charges

    private suspend fun chargeDue(client: SqlClient): Int {
        val now = clock.now()
        val due = (subscriptions.getDueForCharge(SubscriptionStatus.ACTIVE, now, batch, client) + subscriptions.getDueForCharge(SubscriptionStatus.PAST_DUE, now, batch, client))
            .filter { it.mode == SubscriptionMode.MERCHANT }
            .sortedWith(compareBy({ it.nextChargeAt }, { it.id }))
            .take(batch)
        var handled = 0

        for (subscription in due) if (row("charge", subscription.id) { chargeOne(subscription.id) }) handled++

        return handled
    }

    /**
     * One charge of a `MERCHANT` subscription (09 section 8.3), or the admin retry of it ([admin], 09 section 9.3): tx1, the provider call, tx2. Answers what tx1
     * decided; what the provider said is in the database (the attempt, the renewal, the subscription).
     */
    suspend fun chargeOne(subscriptionId: Long, admin: Boolean = false): SubscriptionService.ChargePreparation {
        val client = sqlClient()
        val row = subscriptions.getById(subscriptionId, client) ?: return SubscriptionService.ChargePreparation.Skipped(SubscriptionService.SKIP_GONE)
        // the call runs in the environment of the subscription (what its attempt row says), not in the provider's current one: while the store is in test mode a
        // live subscription must still be charged live, and it never reaches a sandbox (the provider's own mode stays in the facts, it decides S7)
        val handle = payments.providerHandle(row.providerId, row.testMode, client)
        // a provider whose keys decide the environment cannot be told to charge live: when its keys are test keys a live subscription waits like for an unavailable provider
        val sandboxed = handle != null && !row.testMode && handle.testMode && handle.caps.testMode == TestModeSupport.DERIVED
        val facts = SubscriptionService.ProviderFacts(handle != null && !sandboxed, handle?.testMode ?: false, handle?.caps?.statusQuery == true)
        val prepared = db.txRestartingOnOrderChange { conn -> subs.prepareCharge(conn, subscriptionId, facts, admin) }

        when (prepared) {
            is SubscriptionService.ChargePreparation.Skipped -> if (prepared.ended) cleanup(subscriptionId)

            is SubscriptionService.ChargePreparation.Charge -> call(prepared, handle ?: error("a charge was prepared for a provider that is not there"), client)

            is SubscriptionService.ChargePreparation.InFlight -> resolveInFlight(prepared, client)
        }

        return prepared
    }

    /** The call of `chargeRecurring` (outside any transaction, 30 s) and tx2: what the provider answered goes through the normal payment path. */
    private suspend fun call(prepared: SubscriptionService.ChargePreparation.Charge, handle: PaymentService.ProviderHandle, client: SqlClient) {
        val request = payments.recurringChargeRequest(prepared.order, prepared.attempt, subs.viewOf(prepared.subscription), prepared.stored, prepared.idempotencyKey, client)
        val result: RecurringChargeResult = try {
            withTimeout(callTimeoutMs) { handle.provider.chargeRecurring(handle.ctx, request) }
        } catch (e: TimeoutCancellationException) {
            // the outcome is unknown: nothing is written, the lease runs out and the attempt is asked about (never charged again)
            logger.warn("chargeRecurring of subscription {} timed out, the outcome is unknown", prepared.subscription.id)

            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderException) {
            return failed(prepared, e)
        } catch (t: Throwable) {
            logger.warn("chargeRecurring of subscription {} failed unexpectedly, the outcome is unknown: {}", prepared.subscription.id, t.javaClass.simpleName)

            return
        }

        if (result.events.isEmpty()) {
            logger.warn("chargeRecurring of subscription {} answered nothing, the outcome is unknown", prepared.subscription.id)

            return
        }

        for (event in result.events) {
            val mapped = PaymentEventMapper.attemptEvent(event) ?: continue
            var facts = subs.factsOf(event)

            // a charge that is not settled yet is asked about at the first slot of the payment schedule (60 s), the reconcile job does the rest
            if (event is PaymentEvent.Pending) facts = facts.withNextQuery(clock.now() + FIRST_QUERY_MS)

            payments.applyEvent(prepared.order.id, prepared.attempt.id, mapped, facts, OrderActor.GATEWAY)

            // a pending charge is settled by the gateway's notification or the reconcile job: the lease is released, no second charge is scheduled
            if (event is PaymentEvent.Pending) db.txRestartingOnOrderChange { conn -> subs.settlePendingCharge(conn, prepared.subscription.id) }
        }
    }

    /**
     * A `ProviderException` of the call (09 section 8.3): a refusal of the gateway is a decline; `UNSUPPORTED` turns the subscription manual; a technical error
     * (configuration, authentication, address, unreachable, rate limit, bad request, unknown) retries in an hour and is no decline; `INTERNAL` leaves the outcome unknown.
     */
    private suspend fun failed(prepared: SubscriptionService.ChargePreparation.Charge, e: ProviderException) {
        val outcome = when (e.code) {
            ProviderErrorCode.GATEWAY_REJECTED -> null
            ProviderErrorCode.UNSUPPORTED -> AttemptFacts.RECURRING_UNSUPPORTED
            ProviderErrorCode.CONFIGURATION, ProviderErrorCode.AUTHENTICATION, ProviderErrorCode.IP_NOT_ALLOWED, ProviderErrorCode.GATEWAY_UNREACHABLE,
            ProviderErrorCode.RATE_LIMITED, ProviderErrorCode.INVALID_REQUEST, ProviderErrorCode.NOT_FOUND -> AttemptFacts.RECURRING_TECHNICAL

            ProviderErrorCode.INTERNAL -> {
                logger.warn("chargeRecurring of subscription {} failed internally, the outcome is unknown", prepared.subscription.id)

                return
            }
        }
        val facts = AttemptFacts(failureCode = e.code.name, failureMessage = PaymentService.PAYMENT_FAILED_TEXT, adminMessage = e.adminMessage?.take(PaymentService.ADMIN_MESSAGE_MAX), recurringOutcome = outcome)

        payments.applyEvent(prepared.order.id, prepared.attempt.id, PaymentAttemptEvent.Failed(false), facts, OrderActor.SYSTEM)
    }

    /**
     * An attempt of this period has not settled (09 section 8.3): when the provider has `statusQuery` it is asked after the commit (`RECONCILE`) and what it reports
     * is applied; a `CREATED` attempt older than 15 minutes for which the query **answers** `unknown()` is closed `EXPIRED` (`UNKNOWN_OUTCOME`) and counts as a failure.
     * A query that got no answer (the provider threw, timed out or is not there) closes nothing: the first charge may well have gone through while the gateway is
     * down, and a failure would schedule a second charge with a new key. The attempt stays and the question is asked again a lease later ([SubscriptionService.askAgainLater]).
     * Without `statusQuery` it stays: the panel shows "outcome unknown" and offers the retry.
     */
    private suspend fun resolveInFlight(prepared: SubscriptionService.ChargePreparation.InFlight, client: SqlClient) {
        val answer = payments.reconcileQuery(prepared.order, prepared.attempt, client)

        if (answer is PaymentService.ReconcileQuery.Applied || answer is PaymentService.ReconcileQuery.Unsupported) return

        val current = client.preparedQuery("SELECT `status`, `createdAt` FROM ${table("market_payment")} WHERE `id` = ?").execute(Tuple.of(prepared.attempt.id)).coAwait().firstOrNull() ?: return

        if (current.getString("status") != PaymentStatus.CREATED.name) return

        if (answer is PaymentService.ReconcileQuery.Unknown && clock.now() - current.getLong("createdAt") >= UNKNOWN_OUTCOME_AFTER_MS) {
            payments.applyEvent(
                prepared.order.id, prepared.attempt.id, PaymentAttemptEvent.Expired, AttemptFacts(failureCode = UNKNOWN_OUTCOME, failureMessage = PaymentService.PAYMENT_FAILED_TEXT), OrderActor.SYSTEM
            )

            return
        }

        // no answer, or too early to call it unknown: the attempt stays open and nothing else asks about a `CREATED` attempt
        db.txRestartingOnOrderChange { conn -> subs.askAgainLater(conn, prepared.subscription.id, prepared.attempt.id) }
    }

    // ================================================================================================== B: manual renewals and notices

    private suspend fun prepare(client: SqlClient): Int {
        val now = clock.now()
        val c = config()
        val lead = maxOf(c.subscriptionReminderDays, 1).toLong() * DAY_MS
        var handled = 0

        // MANUAL: the renewal order and the reminder at `currentPeriodEnd - lead` (the lead is cut to half the period, 09 section 8.6); one that has its order is not selected
        val manual = client.preparedQuery(
            "SELECT s.`id` FROM ${table("market_subscription")} s WHERE s.`status` = 'ACTIVE' AND s.`mode` = 'MANUAL' AND s.`cancelAtPeriodEnd` = 0 AND s.`currentPeriodEnd` IS NOT NULL " +
                "AND s.`currentPeriodEnd` <= ? AND s.`currentPeriodEnd` - LEAST(?, (s.`currentPeriodEnd` - COALESCE(s.`currentPeriodStart`, 0)) DIV 2) <= ? " +
                "AND NOT EXISTS (SELECT 1 FROM ${table("market_subscription_renewal")} r WHERE r.`subscriptionId` = s.`id` AND r.`periodIndex` = GREATEST(s.`cycleCount`, 1) AND r.`orderId` IS NOT NULL) " +
                "ORDER BY s.`currentPeriodEnd`, s.`id` LIMIT $batch"
        ).execute(Tuple.of(now + 31 * DAY_MS, lead, now)).coAwait().map { it.getLong("id") }

        for (id in manual) {
            if (row("prepare", id) { db.txRestartingOnOrderChange { conn -> subs.prepareManual(conn, id) } }) handled++
        }

        // GATEWAY / MERCHANT: the upcoming-charge notice, once per period
        if (c.subscriptionReminderDays > 0) {
            val notice = client.preparedQuery(
                "SELECT s.`id` FROM ${table("market_subscription")} s WHERE s.`status` = 'ACTIVE' AND s.`mode` IN ('GATEWAY', 'MERCHANT') AND s.`cancelAtPeriodEnd` = 0 " +
                    "AND s.`reminderSentAt` IS NULL AND s.`currentPeriodEnd` IS NOT NULL AND s.`currentPeriodEnd` > ? AND s.`currentPeriodEnd` - ? <= ? ORDER BY s.`currentPeriodEnd`, s.`id` LIMIT $batch"
            ).execute(Tuple.of(now, c.subscriptionReminderDays.toLong() * DAY_MS, now)).coAwait().map { it.getLong("id") }

            for (id in notice) {
                if (row("notice", id) { db.txRestartingOnOrderChange { conn -> subs.notifyUpcoming(conn, id) } }) handled++
            }
        }

        return handled
    }

    // ================================================================================================== C and D: the period and the grace

    private suspend fun periodOver(client: SqlClient): Int {
        val now = clock.now()
        val due = (subscriptions.getPeriodEnded(SubscriptionStatus.ACTIVE, now, batch, client) + subscriptions.getPeriodEnded(SubscriptionStatus.PAUSED, now, batch, client))
            .sortedWith(compareBy({ it.currentPeriodEnd }, { it.id }))
            .take(batch)
        var handled = 0

        for (subscription in due) {
            val ok = row("periodOver", subscription.id) {
                var outcome = db.txRestartingOnOrderChange { conn -> subs.periodOver(conn, subscription.id, polled = false, providerUnavailable = unavailable(subscription, client)) }

                // a gateway row: ask the gateway first, apply what it says, then judge again (09 section 11, step C)
                if (outcome is SubscriptionService.StepOutcome.PollFirst) {
                    poll(subscriptions.getById(subscription.id, client) ?: subscription, client)
                    outcome = db.txRestartingOnOrderChange { conn -> subs.periodOver(conn, subscription.id, polled = true, providerUnavailable = false) }
                }

                if (outcome is SubscriptionService.StepOutcome.Applied && outcome.ended) cleanup(subscription.id)
            }

            if (ok) handled++
        }

        return handled
    }

    private suspend fun unavailable(subscription: MarketSubscription, client: SqlClient): Boolean =
        subscription.mode == SubscriptionMode.GATEWAY && payments.providerHandle(subscription.providerId, null, client) == null

    private suspend fun graceOver(client: SqlClient): Int {
        val now = clock.now()
        val due = client.preparedQuery(
            "SELECT `id` FROM ${table("market_subscription")} WHERE `status` = 'PAST_DUE' AND `graceEndsAt` IS NOT NULL AND `graceEndsAt` <= ? ORDER BY `graceEndsAt`, `id` LIMIT $batch"
        ).execute(Tuple.of(now)).coAwait().map { it.getLong("id") }
        var handled = 0

        for (id in due) {
            val ok = row("graceOver", id) {
                val outcome = db.txRestartingOnOrderChange { conn -> subs.graceOver(conn, id) }

                if (outcome is SubscriptionService.StepOutcome.Applied && outcome.ended) cleanup(id)
            }

            if (ok) handled++
        }

        return handled
    }

    // ================================================================================================== E: remote queue and gateway poll

    private suspend fun remote(client: SqlClient): Int {
        val due = subscriptions.getDueForQuery(clock.now(), batch, client)
        var handled = 0

        for (subscription in due) {
            val ok = row("remote", subscription.id) {
                when {
                    subscription.remoteCancelState == RemoteCancelState.PENDING -> remoteCancel(subscription, client)

                    subscription.mode == SubscriptionMode.GATEWAY && !subscription.status.isClosed() -> poll(subscription, client)

                    // nothing left to ask: a stale `nextQueryAt` is cleared
                    else -> db.txRestartingOnOrderChange { conn -> subs.scheduleNextPoll(conn, subscription, supported = false) }
                }
            }

            if (ok) handled++
        }

        return handled
    }

    private fun SubscriptionStatus.isClosed() = this == SubscriptionStatus.EXPIRED || this == SubscriptionStatus.CANCELLED || this == SubscriptionStatus.COMPLETED

    /** `cancelSubscription(atPeriodEnd = false)` for a subscription that ended locally while the gateway still bills it (09 section 10.3). */
    private suspend fun remoteCancel(subscription: MarketSubscription, client: SqlClient) {
        val handle = payments.providerHandle(subscription.providerId, subscription.testMode, client)
        var error: String? = null
        val done = if (handle == null) {
            error = "PROVIDER_UNAVAILABLE"

            false
        } else {
            try {
                val request = CancelSubscriptionRequest(subs.viewOf(subscription), atPeriodEnd = false, reason = REMOTE_CANCEL_REASON, storedMethod = subs.storedMethodFor(subscription))

                when (val result = withTimeout(callTimeoutMs) { handle.provider.cancelSubscription(handle.ctx, request) }) {
                    is CancelSubscriptionResult.Cancelled, is CancelSubscriptionResult.Scheduled -> true

                    is CancelSubscriptionResult.LocalOnly -> false.also { error = "LOCAL_ONLY" }

                    is CancelSubscriptionResult.BuyerActionRequired -> false.also { error = "BUYER_ACTION_REQUIRED" }

                    is CancelSubscriptionResult.Failed -> false.also { error = result.message }
                }
            } catch (e: TimeoutCancellationException) {
                error = "TIMEOUT"

                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderException) {
                error = e.code.name

                false
            } catch (t: Throwable) {
                error = t.javaClass.simpleName

                false
            }
        }

        db.txRestartingOnOrderChange { conn -> subs.settleRemoteCancel(conn, subscriptions.getById(subscription.id, conn) ?: subscription, done, error) }
    }

    /** `querySubscription` of a gateway row (09 section 11, step E): the events it returns are applied like inbound ones, then the next poll is scheduled. */
    private suspend fun poll(subscription: MarketSubscription, client: SqlClient) {
        val handle = payments.providerHandle(subscription.providerId, subscription.testMode, client)

        if (handle == null) {
            db.txRestartingOnOrderChange { conn -> subs.scheduleNextPoll(conn, subscription, supported = true) }

            return
        }

        val result = try {
            withTimeout(callTimeoutMs) { handle.provider.querySubscription(handle.ctx, QuerySubscriptionRequest(subs.viewOf(subscription))) }
        } catch (e: TimeoutCancellationException) {
            logger.warn("querySubscription of subscription {} timed out", subscription.id)

            return db.txRestartingOnOrderChange { conn -> subs.scheduleNextPoll(conn, subscription, supported = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderException) {
            logger.warn("querySubscription of subscription {} failed: {}", subscription.id, e.code)

            return db.txRestartingOnOrderChange { conn -> subs.scheduleNextPoll(conn, subscription, supported = e.code != ProviderErrorCode.UNSUPPORTED) }
        }

        if (!result.unsupported) {
            for (event in result.events) {
                try {
                    events.apply(event, null, InboundEventContext(-1, subscription.providerId, null, null, clock.now()))
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    logger.warn("an event of the poll of subscription {} could not be applied: {}", subscription.id, t.toString())
                }
            }
        }

        db.txRestartingOnOrderChange { conn -> subs.scheduleNextPoll(conn, subscriptions.getById(subscription.id, conn) ?: subscription, supported = !result.unsupported) }
    }

    // ================================================================================================== F: pending rows, renewal orders of ended subscriptions

    private suspend fun pendingCleanup(client: SqlClient): Int {
        val released = clock.now() - SubscriptionService.PENDING_TIMEOUT_MS
        val due = client.preparedQuery(
            "SELECT s.`id` FROM ${table("market_subscription")} s JOIN ${table("market_order")} o ON o.`id` = s.`initialOrderId` " +
                "WHERE s.`status` = 'PENDING' AND o.`status` IN ('EXPIRED', 'CANCELLED', 'FAILED') AND o.`updatedAt` <= ? ORDER BY o.`updatedAt`, s.`id` LIMIT $batch"
        ).execute(Tuple.of(released)).coAwait().map { it.getLong("id") }
        var handled = 0

        for (id in due) {
            if (row("pendingCleanup", id) { db.txRestartingOnOrderChange { conn -> subs.closePendingAfterTimeout(conn, id) } }) handled++
        }

        return handled
    }

    /** The unpaid renewal orders of ended subscriptions are cancelled (O7), of [subscriptionId] or of all. */
    private suspend fun cleanup(subscriptionId: Long?): Int = subs.cancelClosedRenewalOrders(db, { after -> payments.runAfterCommit(after) }, subscriptionId)

    private fun AttemptFacts.withNextQuery(at: Long): AttemptFacts = AttemptFacts(
        gatewayTransactionId = gatewayTransactionId, gatewayRefs = gatewayRefs, providerData = providerData, methodDetail = methodDetail, nextQueryAt = at
    )

    companion object {
        const val BATCH = 50
        const val CALL_TIMEOUT_MS = 30_000L
        const val UNKNOWN_OUTCOME = "UNKNOWN_OUTCOME"
        const val REMOTE_CANCEL_REASON = "subscription ended"
        private const val DAY_MS = 86_400_000L
        private const val FIRST_QUERY_MS = 60_000L
        private const val UNKNOWN_OUTCOME_AFTER_MS = 15 * 60_000L
        private const val CLEANUP_EVERY_MS = 3_600_000L

        private val logger = LoggerFactory.getLogger(SubscriptionJob::class.java)
    }
}
