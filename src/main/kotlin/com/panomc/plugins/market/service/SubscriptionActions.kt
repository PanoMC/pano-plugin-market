package com.panomc.plugins.market.service

import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.core.subscription.CancelActor
import com.panomc.plugins.market.core.subscription.SubscriptionEndReason
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.core.time.Ids
import com.panomc.plugins.market.core.time.SecureIds
import com.panomc.plugins.market.db.dao.MarketSubscriptionDao
import com.panomc.plugins.market.db.model.MarketSubscription
import com.panomc.plugins.market.db.model.SubscriptionMode
import com.panomc.plugins.market.db.model.SubscriptionStatus
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.error.PaymentProviderError
import com.panomc.plugins.market.error.SubscriptionNotCancellable
import com.panomc.plugins.market.error.SubscriptionNotManageable
import com.panomc.plugins.market.error.SubscriptionNotResumable
import com.panomc.plugins.market.error.SubscriptionNotRetryable
import com.panomc.plugins.market.routes.api.payment.InboundEventContext
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.payment.CancelSubscriptionRequest
import com.panomc.plugins.market.spi.payment.CancelSubscriptionResult
import com.panomc.plugins.market.spi.payment.PortalPurpose
import com.panomc.plugins.market.spi.payment.QuerySubscriptionRequest
import com.panomc.plugins.market.spi.payment.ResumeSubscriptionRequest
import com.panomc.plugins.market.spi.payment.ResumeSubscriptionResult
import com.panomc.plugins.market.spi.payment.SubscriptionPortalRequest
import com.panomc.plugins.market.spi.payment.SubscriptionPortalResult
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** What a cancel answers (09 section 10.1): the subscription, or the address where only the buyer can cancel (`BuyerActionRequired`). */
sealed class CancelOutcome {
    class Done(val row: MarketSubscription) : CancelOutcome()

    class Redirect(val url: String) : CancelOutcome()
}

/**
 * The gateway documents of subscription portals that came back as `Html` (09 section 10.4a): kept in memory for a quarter of an hour for the account that asked,
 * served by the sandboxed page route of the buyer API. A document is never written to the database and never leaves the account it was made for.
 */
class PortalPages(private val clock: Clock, private val ids: Ids = SecureIds(), private val ttlMs: Long = 15 * 60_000L, private val max: Int = 256) {
    private class Page(val userId: Long, val document: String, val expiresAt: Long)

    private val pages = ConcurrentHashMap<String, Page>()

    /** Stores [document] for [userId]; the token is the last part of the page address. */
    fun put(userId: Long, document: String): String {
        val now = clock.now()

        pages.entries.removeIf { it.value.expiresAt <= now }

        // the oldest documents go first when the bound is hit (a flood of portal calls cannot grow the heap)
        while (pages.size >= max) pages.entries.minByOrNull { it.value.expiresAt }?.let { pages.remove(it.key) } ?: break

        return ids.hexToken(TOKEN_BYTES).also { pages[it] = Page(userId, document, now + ttlMs) }
    }

    /** The document of [token] for [userId]; `null` for an unknown or expired token and for another account. */
    fun get(token: String, userId: Long): String? {
        val page = pages[token] ?: return null

        if (page.expiresAt <= clock.now()) return null.also { pages.remove(token) }

        return page.document.takeIf { page.userId == userId }
    }

    companion object {
        const val TOKEN_BYTES = 16
        const val PATH = "/api/market/me/subscriptions/portal-pages/"
    }
}

/**
 * The cancel, resume, portal and retry use cases of a subscription (09 sections 9.3, 10.1, 10.2 and 10.4a): the parts that call the gateway between two
 * transactions. The decisions and the writes are [SubscriptionService]'s (`beginCancel` / `applyCancel`, `beginResume` / `applyResume`); this class owns the order:
 * tx1, the provider call outside any transaction (30 s), tx2. The provider answers `LocalOnly` is a failure for a gateway subscription (a subscription that
 * is ended only here keeps billing the buyer), a failure leaves nothing behind, `BuyerActionRequired` leaves nothing behind and sends the buyer to the gateway.
 */
class SubscriptionActions(
    private val clock: Clock,
    private val db: MarketDb,
    private val subs: SubscriptionService,
    private val subscriptions: MarketSubscriptionDao,
    private val payments: PaymentService,
    private val sqlClient: suspend () -> SqlClient,
    /** Applies the events a gateway retry returned (`SubscriptionUpdated`, `SubscriptionRenewed`, `SubscriptionPaymentFailed`). */
    private val events: SubscriptionEventSink,
    /** Where the gateway returns the buyer to (`{base}/profile/subscriptions`). */
    private val returnUrl: () -> String,
    val portalPages: PortalPages = PortalPages(clock),
    private val callTimeoutMs: Long = CALL_TIMEOUT_MS
) {
    // ================================================================================================== cancel (09 section 10.1)

    /**
     * Buyer ([ownerUserId] set, 404 for another account) or admin cancel. [atPeriodEnd] defaults to `true` at the routes; `PAST_DUE` / `PAUSED` rows are always
     * cancelled now. The answer is the row, or the redirect of a gateway where only the buyer can cancel; a provider that fails answers 502 and changes nothing.
     */
    suspend fun cancel(subscriptionId: Long, actor: CancelActor, ownerUserId: Long?, atPeriodEnd: Boolean, reason: String? = null): CancelOutcome {
        val plan = db.txRestartingOnOrderChange { conn -> subs.beginCancel(conn, subscriptionId, actor, ownerUserId, atPeriodEnd) }

        when (plan) {
            is SubscriptionService.CancelPlan.Unchanged -> return CancelOutcome.Done(plan.row)

            is SubscriptionService.CancelPlan.Requeued -> return CancelOutcome.Done(plan.row)

            is SubscriptionService.CancelPlan.Begin -> Unit
        }

        val remote = if (plan.remote) askGateway(plan, reason) else SubscriptionService.RemoteCancel.None

        if (remote is Redirected) return CancelOutcome.Redirect(remote.url)

        val applied = db.txRestartingOnOrderChange { conn -> subs.applyCancel(conn, subscriptionId, actor, atPeriodEnd, reason, remote as SubscriptionService.RemoteCancel) }

        if (applied.changed) {
            // the stored-method model: the provider may delete the instrument at the gateway, once, and what it says changes nothing (09 section 10.1)
            if (plan.row.mode == SubscriptionMode.MERCHANT) deleteStoredMethod(plan.row, plan.atPeriodEnd, reason)

            // a manual renewal order that was prepared is cancelled, an ended subscription's unpaid renewal order too
            subs.cancelClosedRenewalOrders(db, { after -> payments.runAfterCommit(after) }, subscriptionId)
        }

        return CancelOutcome.Done(applied.row)
    }

    private class Redirected(val url: String)

    /** tx1 is written: tell the gateway. Throws 502 (after withdrawing the intent) when it cannot be done. */
    private suspend fun askGateway(plan: SubscriptionService.CancelPlan.Begin, reason: String?): Any {
        val row = plan.row
        val handle = payments.providerHandle(row.providerId, row.testMode, sqlClient())
            // provider `UNAVAILABLE`: the local change is made now and the remote cancel waits in the queue (09 section 10.3)
            ?: return SubscriptionService.RemoteCancel.Unavailable

        val result = try {
            withTimeout(callTimeoutMs) { handle.provider.cancelSubscription(handle.ctx, CancelSubscriptionRequest(subs.viewOf(row), plan.atPeriodEnd, reason, null)) }
        } catch (e: TimeoutCancellationException) {
            return withdraw(row, ProviderErrorCode.GATEWAY_UNREACHABLE.name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderException) {
            return withdraw(row, e.code.name)
        } catch (t: Throwable) {
            logger.warn("cancelSubscription of subscription {} failed unexpectedly: {}", row.id, t.javaClass.simpleName)

            return withdraw(row, ProviderErrorCode.INTERNAL.name)
        }

        return when (result) {
            is CancelSubscriptionResult.Cancelled -> SubscriptionService.RemoteCancel.Cancelled

            is CancelSubscriptionResult.Scheduled -> SubscriptionService.RemoteCancel.Scheduled

            // only the buyer can cancel, at the gateway: nothing changes here, the state arrives by SubscriptionUpdated
            is CancelSubscriptionResult.BuyerActionRequired -> {
                db.txRestartingOnOrderChange { conn -> subs.withdrawCancel(conn, row.id) }

                Redirected(result.url)
            }

            // a gateway subscription that is ended only here keeps charging the buyer (02 section 8): not acceptable
            is CancelSubscriptionResult.LocalOnly -> withdraw(row, ProviderErrorCode.UNSUPPORTED.name)

            is CancelSubscriptionResult.Failed -> withdraw(row, ProviderErrorCode.GATEWAY_REJECTED.name)
        }
    }

    private suspend fun withdraw(row: MarketSubscription, code: String): Nothing {
        db.txRestartingOnOrderChange { conn -> subs.withdrawCancel(conn, row.id) }

        throw PaymentProviderError(code)
    }

    /** `MERCHANT`: one best-effort `cancelSubscription` after the commit with the stored instrument, its result ignored. */
    private suspend fun deleteStoredMethod(row: MarketSubscription, atPeriodEnd: Boolean, reason: String?) {
        try {
            val handle = payments.providerHandle(row.providerId, row.testMode, sqlClient()) ?: return

            withTimeout(callTimeoutMs) { handle.provider.cancelSubscription(handle.ctx, CancelSubscriptionRequest(subs.viewOf(row), atPeriodEnd, reason, subs.storedMethodFor(row))) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.debug("the provider of subscription {} did not delete the stored method: {}", row.id, t.javaClass.simpleName)
        }
    }

    // ================================================================================================== step E: a cancel whose tx2 never ran

    /**
     * The remote half of a cancel that crashed between tx1 and tx2 (09 section 10.1, last paragraph): the gateway is told again (cancel is idempotent at every
     * gateway) and the result applied. What the buyer asked for is not stored, so it is a cancel at the period end by the actor the provisional end reason names;
     * `PAST_DUE` / `PAUSED` rows end now whatever is asked. A provider that fails or sends the buyer away withdraws the intent (the request that made it got an
     * error), an unavailable provider gives the local change and the queue.
     */
    suspend fun repeatDangling(row: MarketSubscription): Boolean {
        val actor = if (SubscriptionEndReason.parse(row.endReason) == SubscriptionEndReason.ADMIN_CANCEL) CancelActor.ADMIN else CancelActor.BUYER
        val plan = db.txRestartingOnOrderChange { conn -> subs.beginCancel(conn, row.id, actor, null, true) }

        if (plan !is SubscriptionService.CancelPlan.Begin) return false

        val remote = if (plan.remote) {
            try {
                askGateway(plan, null)
            } catch (e: PaymentProviderError) {
                return false
            }
        } else {
            SubscriptionService.RemoteCancel.None
        }

        if (remote is Redirected) return false

        val applied = db.txRestartingOnOrderChange { conn -> subs.applyCancel(conn, row.id, actor, true, null, remote as SubscriptionService.RemoteCancel) }

        if (applied.changed) subs.cancelClosedRenewalOrders(db, { after -> payments.runAfterCommit(after) }, row.id)

        return applied.changed
    }

    // ================================================================================================== resume (09 section 10.2)

    /** Resumes a cancel at the period end of the buyer's own subscription (S10); for `GATEWAY` the provider is asked between two transactions. 502 leaves everything as it was. */
    suspend fun resume(subscriptionId: Long, ownerUserId: Long): MarketSubscription {
        val client = sqlClient()
        val first = subscriptions.getById(subscriptionId, client) ?: throw NotFound()

        if (first.userId != ownerUserId) throw NotFound()

        val handle = if (first.mode == SubscriptionMode.GATEWAY) payments.providerHandle(first.providerId, first.testMode, client) else null
        val plan = db.txRestartingOnOrderChange { conn -> subs.beginResume(conn, subscriptionId, ownerUserId, handle?.caps?.recurringResume == true) }

        if (!plan.remote) return plan.row

        handle ?: throw SubscriptionNotResumable()

        val result = try {
            withTimeout(callTimeoutMs) { handle.provider.resumeSubscription(handle.ctx, ResumeSubscriptionRequest(subs.viewOf(plan.row))) }
        } catch (e: TimeoutCancellationException) {
            throw PaymentProviderError(ProviderErrorCode.GATEWAY_UNREACHABLE.name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderException) {
            throw PaymentProviderError(e.code.name)
        } catch (t: Throwable) {
            logger.warn("resumeSubscription of subscription {} failed unexpectedly: {}", subscriptionId, t.javaClass.simpleName)

            throw PaymentProviderError(ProviderErrorCode.INTERNAL.name)
        }

        return when (result) {
            is ResumeSubscriptionResult.Resumed -> db.txRestartingOnOrderChange { conn -> subs.applyResume(conn, subscriptionId) }

            is ResumeSubscriptionResult.Failed -> throw PaymentProviderError(ProviderErrorCode.GATEWAY_REJECTED.name)

            is ResumeSubscriptionResult.Unsupported -> throw SubscriptionNotResumable()
        }
    }

    // ================================================================================================== portal (09 section 10.4a)

    /**
     * The address of the gateway's hosted page for the buyer's own subscription: `ACTIVE`, `PAST_DUE` or `PAUSED`, mode `GATEWAY`, a provider with
     * `recurringPortal` (else 409 `SUBSCRIPTION_NOT_MANAGEABLE`, also for `Unsupported`). An `Html` document is kept for the account and served by market's
     * sandboxed page route.
     */
    suspend fun portal(subscriptionId: Long, ownerUserId: Long, purpose: PortalPurpose): String {
        val client = sqlClient()
        val row = subscriptions.getById(subscriptionId, client) ?: throw NotFound()

        if (row.userId != ownerUserId) throw NotFound()

        if (row.mode != SubscriptionMode.GATEWAY || row.status !in MANAGEABLE) throw SubscriptionNotManageable()

        val handle = payments.providerHandle(row.providerId, row.testMode, client)?.takeIf { it.caps.recurringPortal } ?: throw SubscriptionNotManageable()
        val result = try {
            withTimeout(callTimeoutMs) { handle.provider.subscriptionPortal(handle.ctx, SubscriptionPortalRequest(subs.viewOf(row), returnUrl(), purpose)) }
        } catch (e: TimeoutCancellationException) {
            throw PaymentProviderError(ProviderErrorCode.GATEWAY_UNREACHABLE.name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderException) {
            throw PaymentProviderError(e.code.name)
        } catch (t: Throwable) {
            logger.warn("subscriptionPortal of subscription {} failed unexpectedly: {}", subscriptionId, t.javaClass.simpleName)

            throw PaymentProviderError(ProviderErrorCode.INTERNAL.name)
        }

        return when (result) {
            is SubscriptionPortalResult.Redirect -> result.url

            is SubscriptionPortalResult.Html -> PortalPages.PATH + portalPages.put(ownerUserId, result.document)

            is SubscriptionPortalResult.Unsupported -> throw SubscriptionNotManageable()
        }
    }

    // ================================================================================================== admin retry (09 section 9.3)

    /**
     * `POST /subscriptions/:id/retry`. `GATEWAY` with `recurringRetry`: the provider is asked to retry (outside any transaction) and what it returns is applied
     * like a `querySubscription` answer; without the capability, `MANUAL` rows and rows the rules of 09 section 9.3 do not allow are 409
     * `SUBSCRIPTION_NOT_RETRYABLE`. `MERCHANT`: [charge] runs the charge of 09 section 8.3 now (`SubscriptionJob.chargeOne(id, admin = true)`: an attempt of
     * unknown outcome is closed first, a closed renewal order is replaced); a declined charge is not an error, it is in the subscription.
     */
    suspend fun retry(subscriptionId: Long, charge: suspend (Long) -> SubscriptionService.ChargePreparation) {
        val client = sqlClient()
        val row = subscriptions.getById(subscriptionId, client) ?: throw NotFound()

        when (row.mode) {
            SubscriptionMode.MANUAL -> throw SubscriptionNotRetryable()

            SubscriptionMode.MERCHANT -> {
                when (val prepared = charge(subscriptionId)) {
                    is SubscriptionService.ChargePreparation.Skipped ->
                        if (prepared.reason in NOT_RETRYABLE) throw SubscriptionNotRetryable()

                    else -> Unit
                }
            }

            SubscriptionMode.GATEWAY -> retryAtGateway(row, client)
        }
    }

    private suspend fun retryAtGateway(row: MarketSubscription, client: SqlClient) {
        if (row.status != SubscriptionStatus.ACTIVE && row.status != SubscriptionStatus.PAST_DUE) throw SubscriptionNotRetryable()

        val handle = payments.providerHandle(row.providerId, row.testMode, client)?.takeIf { it.caps.recurringRetry } ?: throw SubscriptionNotRetryable()
        val result = try {
            withTimeout(callTimeoutMs) { handle.provider.retrySubscriptionCharge(handle.ctx, QuerySubscriptionRequest(subs.viewOf(row))) }
        } catch (e: TimeoutCancellationException) {
            throw PaymentProviderError(ProviderErrorCode.GATEWAY_UNREACHABLE.name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderException) {
            throw if (e.code == ProviderErrorCode.UNSUPPORTED) SubscriptionNotRetryable() else PaymentProviderError(e.code.name)
        } catch (t: Throwable) {
            logger.warn("retrySubscriptionCharge of subscription {} failed unexpectedly: {}", row.id, t.javaClass.simpleName)

            throw PaymentProviderError(ProviderErrorCode.INTERNAL.name)
        }

        if (result.unsupported) throw SubscriptionNotRetryable()

        for (event in result.events) {
            try {
                events.apply(event, null, InboundEventContext(-1, row.providerId, null, null, clock.now()))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logger.warn("an event of the retry of subscription {} could not be applied: {}", row.id, t.toString())
            }
        }
    }

    companion object {
        const val CALL_TIMEOUT_MS = 30_000L

        private val MANAGEABLE = setOf(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE, SubscriptionStatus.PAUSED)

        /** The skips of [SubscriptionService.prepareCharge] that mean "the rules of 09 section 9.3 do not allow a retry". */
        private val NOT_RETRYABLE = setOf(
            SubscriptionService.SKIP_NOT_RETRYABLE, SubscriptionService.SKIP_NOT_CHARGEABLE, SubscriptionService.SKIP_NO_CHARGE_NEEDED, SubscriptionService.SKIP_GONE
        )

        private val logger = LoggerFactory.getLogger(SubscriptionActions::class.java)
    }
}
