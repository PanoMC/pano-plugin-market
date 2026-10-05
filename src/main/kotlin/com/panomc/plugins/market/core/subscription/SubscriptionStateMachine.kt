package com.panomc.plugins.market.core.subscription

import com.panomc.plugins.market.core.subscription.SubEffect.*
import com.panomc.plugins.market.db.model.MarketSubscription
import com.panomc.plugins.market.db.model.RemoteCancelState
import com.panomc.plugins.market.db.model.SubscriptionMode
import com.panomc.plugins.market.db.model.SubscriptionStatus
import com.panomc.plugins.market.spi.payment.GatewaySubscriptionStatus

/** Who asks for a cancel (the remote and local effects differ only in the end reason). */
enum class CancelActor { BUYER, ADMIN }

/**
 * What the machine needs to know about a subscription (09 section 2: `SubState`). A subset of the row; build it with
 * [of] from the row read under the subscription lock.
 */
data class SubState(
    val status: SubscriptionStatus,
    val mode: SubscriptionMode,
    val cycleCount: Int = 0,
    val maxCycles: Int? = null,
    val currentPeriodEnd: Long? = null,
    val cancelAtPeriodEnd: Boolean = false,
    val cancelRequestedAt: Long? = null,
    /** The provisional reason of a scheduled cancel, or the final reason of an ended row. */
    val endReason: SubscriptionEndReason? = null,
    val graceEndsAt: Long? = null,
    val nextChargeAt: Long? = null,
    val remoteCancelState: RemoteCancelState = RemoteCancelState.NONE,
    val gatewaySubscriptionId: String? = null
) {
    companion object {
        fun of(row: MarketSubscription): SubState = SubState(
            status = row.status,
            mode = row.mode,
            cycleCount = row.cycleCount,
            maxCycles = row.maxCycles,
            currentPeriodEnd = row.currentPeriodEnd,
            cancelAtPeriodEnd = row.cancelAtPeriodEnd,
            cancelRequestedAt = row.cancelRequestedAt,
            endReason = SubscriptionEndReason.parse(row.endReason),
            graceEndsAt = row.graceEndsAt,
            nextChargeAt = row.nextChargeAt,
            remoteCancelState = row.remoteCancelState,
            gatewaySubscriptionId = row.gatewaySubscriptionId
        )
    }
}

/**
 * `SubEvent`: every trigger of the table in 09 section 6. Fields beyond the documented tuple are facts the service
 * has to look up outside the row (provider state, attempts in flight); each has a neutral default.
 */
sealed class SubEvent {
    /** S2: the initial order reached O2 / O4. */
    data object Activated : SubEvent()

    /**
     * 09 section 8.4: a renewal order was paid. [modeAfter] is the mode decided by `ModeResolver.atRenewal`
     * (`null` = unchanged); it decides whether the next charge / query is scheduled.
     */
    data class RenewalPaid(val modeAfter: SubscriptionMode? = null) : SubEvent()

    /**
     * 09 section 9.1. [technical]: the charge never reached a decision (provider unreachable, configuration, ...).
     * [final]: no further automatic try. [attempts]: `renewal.attempts`, the number of charges of this period so far.
     */
    data class RenewalFailed(val final: Boolean = false, val technical: Boolean = false, val attempts: Int = 1) : SubEvent()

    /**
     * Step C (09 section 11). [polled]: the gateway was already polled for this row in this run. [providerUnavailable]:
     * the provider is `UNAVAILABLE` / `INCOMPATIBLE` in the registry. [attemptInFlight]: the renewal order has an open attempt.
     */
    data class PeriodOver(
        val polled: Boolean = false,
        val providerUnavailable: Boolean = false,
        val attemptInFlight: Boolean = false
    ) : SubEvent()

    /**
     * Step D (09 section 9.4). [attemptProcessing]: the renewal order has an attempt in `PROCESSING`.
     * [providerUnavailable]: `renewal.lastError = "PROVIDER_UNAVAILABLE"` or the last failure was technical.
     */
    data class GraceOver(val attemptProcessing: Boolean = false, val providerUnavailable: Boolean = false) : SubEvent()

    /**
     * 09 section 10.1, applied in tx2 after the provider answered. [remoteConfirmed]: the provider answered
     * `Cancelled` (sets `remoteCancelState = DONE` on a scheduled cancel).
     */
    data class CancelRequested(
        val actor: CancelActor,
        val atPeriodEnd: Boolean = true,
        val remoteConfirmed: Boolean = false
    ) : SubEvent()

    /** 09 section 10.2. [providerCanResume]: `capabilities.recurringResume` (only read for `GATEWAY`). */
    data class ResumeRequested(val providerCanResume: Boolean = true) : SubEvent()

    /** 09 section 7: a `SubscriptionUpdated` status event. */
    data class GatewayStatus(val status: GatewaySubscriptionStatus, val endsAt: Long? = null) : SubEvent()

    /** O10: a full refund of the current-period order with `revoke = 1`. Other refunds never reach the machine. */
    data object Refunded : SubEvent()

    /** O11 (09 section 10.4). [revokeOnChargeback]: the dispute flow writes `REVOKE` deliveries itself. */
    data class Chargeback(val revokeOnChargeback: Boolean = true) : SubEvent()

    /** `PlayerEventListener.onDelete` (01 section 13). */
    data object UserDeleted : SubEvent()

    /** The buyer matches `market_block` (09 sections 8.3, 10.4). */
    data object BuyerBlocked : SubEvent()

    /** O5 on the initial order. */
    data object InitialOrderRejected : SubEvent()

    /** Step F: the initial order was released 30 days ago and the row is still `PENDING`. */
    data object PendingTimeout : SubEvent()
}

/** The row of 09 section 6 a step implements, plus the few steps the table does not number. */
enum class SubRule {
    S1, S2, S3, S4, S5, S6, S7, S8, S9, S10, S11, S12, S13, S14,

    /** 09 section 8.4 on an `ACTIVE` row: the next period starts, no status change. */
    RENEWED,

    /** A recorded failure that changes no status (09 section 9.1: `PAST_DUE` stays, or a gateway final failure inside the paid period). */
    FAILURE_RECORDED,

    /** 09 section 9.1 "technical failure": retry in one hour, nothing else changes. */
    TECHNICAL_FAILURE,

    /** 09 section 7: the gateway confirms a cancel that is already scheduled locally. */
    REMOTE_CONFIRMED,

    /** 09 section 7: the gateway still bills a closed subscription, the cancel goes back in the queue. */
    REMOTE_CANCEL_REQUEUED
}

/** One conditional status update plus its effects. [from] is `null` only for S1 (no source state). */
data class SubStep(
    val rule: SubRule,
    val from: SubscriptionStatus?,
    val to: SubscriptionStatus,
    val effects: List<SubEffect>
)

/** Result of `decide`: a pure value, the service applies it. `decide` never throws. */
sealed class SubTransition {
    /**
     * Not applicable (wrong state, not due, already done). [reason] is a stable code (see the constants of
     * [SubscriptionStateMachine]); the caller logs it at debug level, or maps it to an API answer where the spec says so.
     */
    data class Ignored(val reason: String) : SubTransition()

    /**
     * Step C on a `GATEWAY` row (09 section 11): poll the gateway first, apply the returned events, then call
     * `decide` again with `PeriodOver(polled = true)`.
     */
    data object PollFirst : SubTransition()

    /**
     * One or more steps applied in order inside one transaction: `UPDATE ... WHERE id = ? AND status = :from` for
     * each, then its effects. Two steps occur where the spec says "S12 then S4" and "S4 first, then S6".
     */
    data class Apply(val steps: List<SubStep>) : SubTransition() {
        init {
            require(steps.isNotEmpty()) { "Apply needs at least one step" }
        }

        /** Status after the last step. */
        val to: SubscriptionStatus get() = steps.last().to
        val rules: List<SubRule> get() = steps.map { it.rule }
        val effects: List<SubEffect> get() = steps.flatMap { it.effects }
    }
}

/**
 * Subscription state machine (00 section 7.5, 09 sections 6 and 7), pure: no clock, no database, no provider. The
 * service applies an `Apply` under the subscription row lock (`Locks.orderWithSubscription`); zero updated rows means
 * re-read and decide once more. `decide` is total over `status x SubEvent`: any other pair is `Ignored`.
 *
 * Due comparisons are `<= now` only (a clock that moved backwards fires nothing early, 09 section 15 item 8). The
 * finite-plan checks use `cycleCount >= maxCycles` rather than `==`: gateway renewals are accepted past the maximum
 * ("money that arrived is recorded", 09 section 8.2), and the plan must still complete.
 *
 * Terminal rows (`EXPIRED`, `CANCELLED`, `COMPLETED`) ignore every event except a gateway `ACTIVE` / `PAST_DUE`
 * status, which puts the remote cancel back in the queue. `UserDeleted` on a terminal row is `Ignored` here: the
 * service still scrubs the personal data of terminal rows when a user is deleted (01 section 13).
 */
object SubscriptionStateMachine {
    // Ignored reasons (stable codes).
    const val ALREADY_ACTIVE = "ALREADY_ACTIVE"
    const val NOT_ACTIVATED = "NOT_ACTIVATED"
    const val NOT_PENDING = "NOT_PENDING"
    const val NOT_PAST_DUE = "NOT_PAST_DUE"
    const val NOT_DUE = "NOT_DUE"
    const val NOT_APPLICABLE = "NOT_APPLICABLE"
    const val NO_CHANGE = "NO_CHANGE"
    const val NOT_A_SUBSCRIPTION_FAILURE = "NOT_A_SUBSCRIPTION_FAILURE"
    const val RENEWAL_SLACK = "RENEWAL_SLACK"
    const val PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE"
    const val WAITING_FOR_PAYMENT = "WAITING_FOR_PAYMENT"
    const val COMPLETION_FOLLOWS = "COMPLETION_FOLLOWS"
    const val ALREADY_CANCEL_SCHEDULED = "ALREADY_CANCEL_SCHEDULED"
    const val REMOTE_CANCEL_PENDING = "REMOTE_CANCEL_PENDING"

    /** Order accept refused: the subscription was closed (09 section 4.4, API `INVALID_ORDER_TRANSITION`). */
    const val SUBSCRIPTION_CLOSED = "SUBSCRIPTION_CLOSED"

    /** API 409 codes of 09 section 14. */
    const val SUBSCRIPTION_NOT_CANCELLABLE = "SUBSCRIPTION_NOT_CANCELLABLE"
    const val SUBSCRIPTION_NOT_RESUMABLE = "SUBSCRIPTION_NOT_RESUMABLE"

    // Names the effects refer to (09 section 12).
    const val WEBHOOK_STARTED = "subscription.started"
    const val WEBHOOK_RENEWED = "subscription.renewed"
    const val WEBHOOK_CANCELLED = "subscription.cancelled"
    const val WEBHOOK_EXPIRED = "subscription.expired"
    const val MAIL_PAYMENT_FAILED = "SUBSCRIPTION_PAYMENT_FAILED"
    const val MAIL_CANCELLED = "SUBSCRIPTION_CANCELLED"
    const val MAIL_REASON_CHARGE_FAILED = "CHARGE_FAILED"
    const val MAIL_REASON_NOT_RENEWED = "NOT_RENEWED"
    const val ORDER_EVENT_STARTED = "SUBSCRIPTION_STARTED"
    const val ORDER_EVENT_RENEWED = "SUBSCRIPTION_RENEWED"
    const val ORDER_EVENT_PAST_DUE = "SUBSCRIPTION_PAST_DUE"
    const val ORDER_EVENT_CHARGE_FAILED = "SUBSCRIPTION_CHARGE_FAILED"
    const val ORDER_EVENT_CANCEL_REQUESTED = "SUBSCRIPTION_CANCEL_REQUESTED"
    const val ORDER_EVENT_RESUMED = "SUBSCRIPTION_RESUMED"

    private val DAY_MS = SubscriptionTimings.DAY_MS

    /** S1: the only decision without a source state (O1 with a subscription item, 09 section 4.3). */
    fun create(): SubTransition.Apply = single(SubRule.S1, null, SubscriptionStatus.PENDING, emptyList())

    fun decide(sub: SubState, event: SubEvent, now: Long, cfg: SubConfig): SubTransition = when (event) {
        SubEvent.Activated -> onActivated(sub)
        is SubEvent.RenewalPaid -> onRenewalPaid(sub, event)
        is SubEvent.RenewalFailed -> onRenewalFailed(sub, event, now, cfg)
        is SubEvent.PeriodOver -> onPeriodOver(sub, event, now, cfg)
        is SubEvent.GraceOver -> onGraceOver(sub, event, now)
        is SubEvent.CancelRequested -> onCancelRequested(sub, event, now)
        is SubEvent.ResumeRequested -> onResumeRequested(sub, event, now)
        is SubEvent.GatewayStatus -> onGatewayStatus(sub, event, now, cfg)
        SubEvent.Refunded -> onEndedByMoney(sub, SubscriptionEndReason.REFUND, undoHandledByCaller = true)
        is SubEvent.Chargeback -> onEndedByMoney(sub, SubscriptionEndReason.CHARGEBACK, event.revokeOnChargeback)
        SubEvent.UserDeleted -> onUserDeleted(sub)
        SubEvent.BuyerBlocked -> onBuyerBlocked(sub, now)
        SubEvent.InitialOrderRejected -> onPendingClosed(sub, SubscriptionEndReason.ADMIN_CANCEL)
        SubEvent.PendingTimeout -> onPendingClosed(sub, SubscriptionEndReason.PAYMENT_FAILED)
    }

    // ---------------------------------------------------------------- S2

    private fun onActivated(sub: SubState): SubTransition = when (sub.status) {
        SubscriptionStatus.PENDING -> single(
            SubRule.S2, SubscriptionStatus.PENDING, SubscriptionStatus.ACTIVE,
            listOf(ActivateFromPayment, AddOrderEvent(ORDER_EVENT_STARTED), QueueWebhook(WEBHOOK_STARTED))
        )

        SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE, SubscriptionStatus.PAUSED -> ignored(ALREADY_ACTIVE)
        SubscriptionStatus.EXPIRED, SubscriptionStatus.CANCELLED, SubscriptionStatus.COMPLETED -> ignored(SUBSCRIPTION_CLOSED)
    }

    // ---------------------------------------------------------------- S5, 09 section 8.4

    private fun onRenewalPaid(sub: SubState, event: SubEvent.RenewalPaid): SubTransition {
        val status = sub.status
        if (status == SubscriptionStatus.PENDING) return ignored(NOT_ACTIVATED)
        if (status.isTerminal) return ignored(SUBSCRIPTION_CLOSED)

        val mode = event.modeAfter ?: sub.mode
        val paidCycles = sub.cycleCount + 1
        val maxCycles = sub.maxCycles
        val scheduleCharge = mode == SubscriptionMode.MERCHANT && !sub.cancelAtPeriodEnd &&
                (maxCycles == null || paidCycles < maxCycles)
        val scheduleQuery = mode == SubscriptionMode.GATEWAY
        val effects = buildList<SubEffect> {
            add(ApplyRenewal(mode, scheduleCharge, scheduleQuery))
            add(QueueRenewDeliveries)
            add(AddOrderEvent(ORDER_EVENT_RENEWED))
            add(QueueWebhook(WEBHOOK_RENEWED))
            // 09 section 15 item 5: money arrived after a cancel, so the gateway did not stop; stop it again.
            if (mode == SubscriptionMode.GATEWAY && sub.cancelAtPeriodEnd) add(QueueRemoteCancel)
        }
        val rule = if (status == SubscriptionStatus.ACTIVE) SubRule.RENEWED else SubRule.S5
        return single(rule, status, SubscriptionStatus.ACTIVE, effects)
    }

    // ---------------------------------------------------------------- S4, 09 section 9.1

    private fun onRenewalFailed(sub: SubState, event: SubEvent.RenewalFailed, now: Long, cfg: SubConfig): SubTransition {
        val status = sub.status
        if (status == SubscriptionStatus.PENDING) return ignored(NOT_ACTIVATED)
        if (status.isTerminal) return ignored(SUBSCRIPTION_CLOSED)
        if (status == SubscriptionStatus.PAUSED) return ignored(NOT_APPLICABLE)

        return when (sub.mode) {
            // A failed manual payment attempt only updates renewal.lastError; it is not a subscription failure.
            SubscriptionMode.MANUAL -> ignored(NOT_A_SUBSCRIPTION_FAILURE)
            SubscriptionMode.MERCHANT -> merchantFailure(sub, event, now, cfg)
            SubscriptionMode.GATEWAY -> gatewayFailure(sub, event, now, cfg)
        }
    }

    private fun merchantFailure(sub: SubState, event: SubEvent.RenewalFailed, now: Long, cfg: SubConfig): SubTransition {
        val status = sub.status
        if (event.technical) {
            return single(
                SubRule.TECHNICAL_FAILURE, status, status,
                listOf(RecordRenewalError, AddOrderEvent(ORDER_EVENT_CHARGE_FAILED), SetNextChargeAt(now + SubscriptionTimings.TECHNICAL_RETRY_MS))
            )
        }
        val graceEnds = if (status == SubscriptionStatus.ACTIVE) graceEndsFor(sub, now, cfg) else sub.graceEndsAt
        val next = if (event.final) null else RetrySchedule.nextRetryAt(event.attempts, now, graceEnds)
        val recorded = listOf(RecordRenewalError, RecordFailure, AddOrderEvent(ORDER_EVENT_CHARGE_FAILED))
        if (status == SubscriptionStatus.PAST_DUE) {
            return single(SubRule.FAILURE_RECORDED, status, status, recorded + SetNextChargeAt(next))
        }
        return single(
            SubRule.S4, SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE,
            recorded + listOf(
                SetGraceEndsAt(graceEnds ?: graceEndsFor(sub, now, cfg)),
                SetNextChargeAt(next),
                QueueMail(MAIL_PAYMENT_FAILED, MAIL_REASON_CHARGE_FAILED),
                AddOrderEvent(ORDER_EVENT_PAST_DUE)
            )
        )
    }

    private fun gatewayFailure(sub: SubState, event: SubEvent.RenewalFailed, now: Long, cfg: SubConfig): SubTransition {
        // Technical failures belong to merchant-initiated charges; a gateway reports declines only.
        if (event.technical) return ignored(NOT_APPLICABLE)
        val status = sub.status
        val recorded = gatewayRecordedFailure()
        val periodOver = sub.currentPeriodEnd.let { it == null || now >= it }

        if (!event.final) {
            if (status == SubscriptionStatus.PAST_DUE) return single(SubRule.FAILURE_RECORDED, status, status, recorded)
            return single(SubRule.S4, status, SubscriptionStatus.PAST_DUE, pastDueEffects(sub, now, cfg, MAIL_REASON_CHARGE_FAILED, recorded))
        }

        // The gateway gave up (09 section 9.1, "final = true").
        if (periodOver) {
            val expire = SubStep(
                SubRule.S6, SubscriptionStatus.PAST_DUE, SubscriptionStatus.EXPIRED,
                endEffects(sub, SubscriptionStatus.EXPIRED, SubscriptionEndReason.PAYMENT_FAILED, RenewalDisposition.FAILED, queueRemote = true)
            )
            if (status == SubscriptionStatus.PAST_DUE) {
                return SubTransition.Apply(listOf(expire.copy(effects = recorded + expire.effects)))
            }
            val pastDue = SubStep(
                SubRule.S4, SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE,
                pastDueEffects(sub, now, cfg, MAIL_REASON_CHARGE_FAILED, recorded)
            )
            return SubTransition.Apply(listOf(pastDue, expire))
        }
        // The paid period is not over: the buyer keeps access, nothing is billed any more.
        if (status == SubscriptionStatus.ACTIVE && !sub.cancelAtPeriodEnd) {
            return single(
                SubRule.S8, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE,
                scheduleCancelEffects(sub, SubscriptionEndReason.PAYMENT_FAILED, now, prefix = recorded)
            )
        }
        return single(SubRule.FAILURE_RECORDED, status, status, recorded)
    }

    // ---------------------------------------------------------------- S4 / S9 / S13 / S14, step C

    private fun onPeriodOver(sub: SubState, event: SubEvent.PeriodOver, now: Long, cfg: SubConfig): SubTransition {
        val status = sub.status
        if (status == SubscriptionStatus.PENDING) return ignored(NOT_ACTIVATED)
        if (status.isTerminal) return ignored(SUBSCRIPTION_CLOSED)
        if (status == SubscriptionStatus.PAST_DUE) return ignored(NOT_APPLICABLE)
        val end = sub.currentPeriodEnd
        if (end == null || end > now) return ignored(NOT_DUE)

        // First match wins (09 section 11, step C).
        if (sub.cancelAtPeriodEnd) {
            val reason = sub.endReason ?: SubscriptionEndReason.GATEWAY_ENDED
            return single(
                SubRule.S9, status, SubscriptionStatus.CANCELLED,
                endEffects(sub, SubscriptionStatus.CANCELLED, reason, RenewalDisposition.SKIPPED)
            )
        }
        if (status == SubscriptionStatus.PAUSED) {
            return single(
                SubRule.S13, status, SubscriptionStatus.CANCELLED,
                endEffects(sub, SubscriptionStatus.CANCELLED, SubscriptionEndReason.GATEWAY_ENDED, RenewalDisposition.SKIPPED, queueRemote = true)
            )
        }
        if (isComplete(sub)) {
            return single(
                SubRule.S14, status, SubscriptionStatus.COMPLETED,
                endEffects(sub, SubscriptionStatus.COMPLETED, SubscriptionEndReason.COMPLETED, RenewalDisposition.SKIPPED)
            )
        }
        if (sub.mode == SubscriptionMode.MANUAL) {
            return single(
                SubRule.S4, status, SubscriptionStatus.PAST_DUE,
                pastDueEffects(sub, now, cfg, MAIL_REASON_NOT_RENEWED, emptyList())
            )
        }
        // MERCHANT / GATEWAY: step A charges at the boundary and gateways bill up to hours after it.
        if (now < end + SubscriptionTimings.RENEWAL_SLACK_MS) return ignored(RENEWAL_SLACK)
        if (sub.mode == SubscriptionMode.GATEWAY) {
            if (event.providerUnavailable) return ignored(PROVIDER_UNAVAILABLE)
            if (!event.polled) return SubTransition.PollFirst
            return single(
                SubRule.S4, status, SubscriptionStatus.PAST_DUE,
                pastDueEffects(sub, now, cfg, MAIL_REASON_NOT_RENEWED, emptyList())
            )
        }
        // MERCHANT, still unpaid after the slack: attempt in flight, technical failures, provider unavailable or a lost
        // nextChargeAt. Keep the schedule, or charge now when it is lost and nothing is in flight.
        val extra = if (sub.nextChargeAt == null && !event.attemptInFlight) listOf(SetNextChargeAt(now)) else emptyList()
        return single(
            SubRule.S4, status, SubscriptionStatus.PAST_DUE,
            pastDueEffects(sub, now, cfg, MAIL_REASON_NOT_RENEWED, extra)
        )
    }

    // ---------------------------------------------------------------- S6, step D

    private fun onGraceOver(sub: SubState, event: SubEvent.GraceOver, now: Long): SubTransition {
        val status = sub.status
        if (status.isTerminal) return ignored(SUBSCRIPTION_CLOSED)
        if (status == SubscriptionStatus.PENDING) return ignored(NOT_ACTIVATED)
        if (status != SubscriptionStatus.PAST_DUE) return ignored(NOT_PAST_DUE)

        val graceEnds = sub.graceEndsAt
        // A PAST_DUE row without a grace end is corrupt; expiring it is the safe side (access must not last forever).
        if (graceEnds != null) {
            if (graceEnds > now) return ignored(NOT_DUE)
            val lastRetryPending = sub.nextChargeAt.let { it != null && it <= graceEnds }
            val waiting = event.attemptProcessing || lastRetryPending
            if (waiting && now < graceEnds + SubscriptionTimings.PROCESSING_WAIT_CAP_MS) return ignored(WAITING_FOR_PAYMENT)
        }
        val reason = if (event.providerUnavailable) SubscriptionEndReason.PROVIDER_UNAVAILABLE else SubscriptionEndReason.PAYMENT_FAILED
        return single(
            SubRule.S6, status, SubscriptionStatus.EXPIRED,
            endEffects(sub, SubscriptionStatus.EXPIRED, reason, RenewalDisposition.FAILED, queueRemote = true)
        )
    }

    // ---------------------------------------------------------------- S7 / S8, 09 section 10.1

    private fun onCancelRequested(sub: SubState, event: SubEvent.CancelRequested, now: Long): SubTransition {
        val reason = if (event.actor == CancelActor.BUYER) SubscriptionEndReason.BUYER_CANCEL else SubscriptionEndReason.ADMIN_CANCEL
        return when (sub.status) {
            SubscriptionStatus.PENDING, SubscriptionStatus.EXPIRED, SubscriptionStatus.CANCELLED, SubscriptionStatus.COMPLETED ->
                ignored(SUBSCRIPTION_NOT_CANCELLABLE)

            // The paid period is over or suspended: always immediate, whatever atPeriodEnd says.
            SubscriptionStatus.PAST_DUE, SubscriptionStatus.PAUSED -> cancelNow(sub, reason)

            SubscriptionStatus.ACTIVE ->
                if (!event.atPeriodEnd) cancelNow(sub, reason)
                else if (sub.cancelAtPeriodEnd) ignored(ALREADY_CANCEL_SCHEDULED)
                else single(
                    SubRule.S8, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE,
                    scheduleCancelEffects(sub, reason, now, markRemoteDone = event.remoteConfirmed)
                )
        }
    }

    private fun cancelNow(sub: SubState, reason: SubscriptionEndReason): SubTransition = single(
        SubRule.S7, sub.status, SubscriptionStatus.CANCELLED,
        listOf<SubEffect>(AddOrderEvent(ORDER_EVENT_CANCEL_REQUESTED)) +
                endEffects(sub, SubscriptionStatus.CANCELLED, reason, RenewalDisposition.SKIPPED, cancelledWebhook = true)
    )

    // ---------------------------------------------------------------- S10, 09 section 10.2

    private fun onResumeRequested(sub: SubState, event: SubEvent.ResumeRequested, now: Long): SubTransition {
        val end = sub.currentPeriodEnd
        // A GATEWAY row is resumable only while the remote state is untouched (NONE: the gateway scheduled the cancel
        // and can take it back). DONE is final; PENDING / FAILED mean step E still has to cancel at the gateway, and
        // S10 would leave that queue in place so the resumed subscription would be cancelled remotely right after.
        val resumable = sub.status == SubscriptionStatus.ACTIVE && sub.cancelAtPeriodEnd &&
                end != null && now < end &&
                sub.endReason == SubscriptionEndReason.BUYER_CANCEL &&
                (sub.mode != SubscriptionMode.GATEWAY || (sub.remoteCancelState == RemoteCancelState.NONE && event.providerCanResume))
        return if (resumable) SubTransition.Apply(listOf(resumeStep(sub))) else ignored(SUBSCRIPTION_NOT_RESUMABLE)
    }

    private fun resumeStep(sub: SubState): SubStep {
        val end = sub.currentPeriodEnd
        val maxCycles = sub.maxCycles
        val effects = buildList<SubEffect> {
            add(SetCancelAtPeriodEnd(false))
            add(SetCancelRequestedAt(null))
            add(SetEndReason(null))
            // A finished plan is not charged again: step A would clear it anyway, so do not schedule it.
            if (sub.mode == SubscriptionMode.MERCHANT && end != null && (maxCycles == null || sub.cycleCount < maxCycles)) {
                add(SetNextChargeAt(end))
            }
            if (sub.mode == SubscriptionMode.MANUAL) add(RestoreSkippedRenewal)
            add(AddOrderEvent(ORDER_EVENT_RESUMED))
        }
        return SubStep(SubRule.S10, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE, effects)
    }

    // ---------------------------------------------------------------- 09 section 7

    private fun onGatewayStatus(sub: SubState, event: SubEvent.GatewayStatus, now: Long, cfg: SubConfig): SubTransition {
        val gw = event.status
        return when (sub.status) {
            SubscriptionStatus.PENDING -> ignored(NOT_ACTIVATED)

            // The gateway is still billing a closed subscription: back into the remote cancel queue.
            SubscriptionStatus.EXPIRED, SubscriptionStatus.CANCELLED, SubscriptionStatus.COMPLETED ->
                if (gw != GatewaySubscriptionStatus.ACTIVE && gw != GatewaySubscriptionStatus.PAST_DUE) ignored(SUBSCRIPTION_CLOSED)
                else if (sub.remoteCancelState == RemoteCancelState.PENDING) ignored(REMOTE_CANCEL_PENDING)
                else single(SubRule.REMOTE_CANCEL_REQUEUED, sub.status, sub.status, listOf(QueueRemoteCancel))

            SubscriptionStatus.ACTIVE -> gatewayOnActive(sub, gw, now, cfg)
            SubscriptionStatus.PAST_DUE -> gatewayOnPastDue(sub, gw)
            SubscriptionStatus.PAUSED -> gatewayOnPaused(sub, gw, now, cfg)
        }
    }

    private fun gatewayOnActive(sub: SubState, gw: GatewaySubscriptionStatus, now: Long, cfg: SubConfig): SubTransition = when (gw) {
        // S10 is "gateway ACTIVE after CANCEL_SCHEDULED": the gateway took the scheduled cancel back. It applies only
        // when nothing is queued. With a PENDING or FAILED remote cancel the gateway was simply never told to stop, so
        // ACTIVE is its normal state and not a resume; undoing the local cancel there would keep billing the buyer who
        // cancelled (BuyerBlocked and a renewal after a cancel create exactly this state, 09 sections 10.1 and 15 item 5).
        GatewaySubscriptionStatus.ACTIVE -> when {
            !sub.cancelAtPeriodEnd -> ignored(NO_CHANGE)
            sub.remoteCancelState == RemoteCancelState.NONE -> SubTransition.Apply(listOf(resumeStep(sub)))
            sub.remoteCancelState == RemoteCancelState.DONE -> ignored(NO_CHANGE)
            else -> ignored(REMOTE_CANCEL_PENDING)
        }

        GatewaySubscriptionStatus.PAST_DUE ->
            single(SubRule.S4, SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE, pastDueEffects(sub, now, cfg, MAIL_REASON_CHARGE_FAILED, gatewayRecordedFailure()))

        GatewaySubscriptionStatus.PAUSED ->
            single(SubRule.S11, SubscriptionStatus.ACTIVE, SubscriptionStatus.PAUSED, listOf(SetNextChargeAt(null)))

        GatewaySubscriptionStatus.CANCEL_SCHEDULED ->
            if (sub.cancelAtPeriodEnd) ignored(ALREADY_CANCEL_SCHEDULED)
            else single(
                SubRule.S8, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE,
                scheduleCancelEffects(sub, sub.endReason ?: SubscriptionEndReason.BUYER_CANCEL, now)
            )

        GatewaySubscriptionStatus.CANCELLED -> gatewayCancelledOnActive(sub, now)

        GatewaySubscriptionStatus.ENDED ->
            if (isComplete(sub)) ignored(COMPLETION_FOLLOWS) else gatewayCancelledOnActive(sub, now)
    }

    /** Gateway `CANCELLED` on an `ACTIVE` row: paid time left => S8 and the remote cancel is done, else S7. */
    private fun gatewayCancelledOnActive(sub: SubState, now: Long): SubTransition {
        // "kept" when a cancel was requested here first, else the gateway ended it.
        val reason = if (sub.cancelRequestedAt != null) sub.endReason ?: SubscriptionEndReason.GATEWAY_ENDED else SubscriptionEndReason.GATEWAY_ENDED
        val end = sub.currentPeriodEnd
        if (end != null && now < end) {
            if (sub.cancelAtPeriodEnd) {
                return if (sub.remoteCancelState != RemoteCancelState.DONE) {
                    single(SubRule.REMOTE_CONFIRMED, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE, listOf(MarkRemoteCancelDone))
                } else ignored(ALREADY_CANCEL_SCHEDULED)
            }
            return single(
                SubRule.S8, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE,
                scheduleCancelEffects(sub, reason, now, markRemoteDone = true)
            )
        }
        return single(
            SubRule.S7, SubscriptionStatus.ACTIVE, SubscriptionStatus.CANCELLED,
            endEffects(sub, SubscriptionStatus.CANCELLED, reason, RenewalDisposition.SKIPPED, cancelledWebhook = true)
        )
    }

    private fun gatewayOnPastDue(sub: SubState, gw: GatewaySubscriptionStatus): SubTransition = when (gw) {
        GatewaySubscriptionStatus.ACTIVE, GatewaySubscriptionStatus.PAST_DUE,
        GatewaySubscriptionStatus.PAUSED, GatewaySubscriptionStatus.CANCEL_SCHEDULED -> ignored(NO_CHANGE)

        GatewaySubscriptionStatus.CANCELLED -> single(
            SubRule.S7, SubscriptionStatus.PAST_DUE, SubscriptionStatus.CANCELLED,
            endEffects(sub, SubscriptionStatus.CANCELLED, SubscriptionEndReason.GATEWAY_ENDED, RenewalDisposition.SKIPPED, cancelledWebhook = true)
        )

        GatewaySubscriptionStatus.ENDED -> single(
            SubRule.S6, SubscriptionStatus.PAST_DUE, SubscriptionStatus.EXPIRED,
            endEffects(sub, SubscriptionStatus.EXPIRED, SubscriptionEndReason.PAYMENT_FAILED, RenewalDisposition.FAILED)
        )
    }

    private fun gatewayOnPaused(sub: SubState, gw: GatewaySubscriptionStatus, now: Long, cfg: SubConfig): SubTransition = when (gw) {
        GatewaySubscriptionStatus.ACTIVE ->
            single(SubRule.S12, SubscriptionStatus.PAUSED, SubscriptionStatus.ACTIVE, emptyList())

        // S12 then S4.
        GatewaySubscriptionStatus.PAST_DUE -> {
            val resumed = SubStep(SubRule.S12, SubscriptionStatus.PAUSED, SubscriptionStatus.ACTIVE, emptyList())
            val pastDue = SubStep(
                SubRule.S4, SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE,
                pastDueEffects(sub, now, cfg, MAIL_REASON_CHARGE_FAILED, gatewayRecordedFailure())
            )
            SubTransition.Apply(listOf(resumed, pastDue))
        }

        GatewaySubscriptionStatus.PAUSED, GatewaySubscriptionStatus.CANCEL_SCHEDULED -> ignored(NO_CHANGE)

        GatewaySubscriptionStatus.CANCELLED, GatewaySubscriptionStatus.ENDED -> single(
            SubRule.S7, SubscriptionStatus.PAUSED, SubscriptionStatus.CANCELLED,
            endEffects(sub, SubscriptionStatus.CANCELLED, SubscriptionEndReason.GATEWAY_ENDED, RenewalDisposition.SKIPPED, cancelledWebhook = true)
        )
    }

    // ---------------------------------------------------------------- S7 by refund / chargeback / deletion / block

    private fun onEndedByMoney(sub: SubState, reason: SubscriptionEndReason, undoHandledByCaller: Boolean): SubTransition = when (sub.status) {
        SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE, SubscriptionStatus.PAUSED -> single(
            SubRule.S7, sub.status, SubscriptionStatus.CANCELLED,
            endEffects(sub, SubscriptionStatus.CANCELLED, reason, RenewalDisposition.SKIPPED, undoHandledByCaller = undoHandledByCaller, queueRemote = true)
        )

        SubscriptionStatus.PENDING -> ignored(NOT_ACTIVATED)
        SubscriptionStatus.EXPIRED, SubscriptionStatus.CANCELLED, SubscriptionStatus.COMPLETED -> ignored(SUBSCRIPTION_CLOSED)
    }

    private fun onUserDeleted(sub: SubState): SubTransition = when (sub.status) {
        SubscriptionStatus.PENDING -> pendingClosed(sub, SubscriptionEndReason.ADMIN_CANCEL, clearPii = true)

        SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE, SubscriptionStatus.PAUSED -> single(
            SubRule.S7, sub.status, SubscriptionStatus.CANCELLED,
            endEffects(sub, SubscriptionStatus.CANCELLED, SubscriptionEndReason.ADMIN_CANCEL, RenewalDisposition.SKIPPED, sendEndedMail = false, clearPii = true, queueRemote = true)
        )

        SubscriptionStatus.EXPIRED, SubscriptionStatus.CANCELLED, SubscriptionStatus.COMPLETED -> ignored(SUBSCRIPTION_CLOSED)
    }

    /**
     * A blocked buyer: `ACTIVE` ends at the period end (S8, `ADMIN_CANCEL`; for `GATEWAY` the gateway is told to stop
     * billing), `PAST_DUE` / `PAUSED` have no paid time left and end now (S7). 09 sections 8.3 and 10.4.
     */
    private fun onBuyerBlocked(sub: SubState, now: Long): SubTransition = when (sub.status) {
        SubscriptionStatus.ACTIVE ->
            if (sub.cancelAtPeriodEnd) ignored(ALREADY_CANCEL_SCHEDULED)
            else single(
                SubRule.S8, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE,
                scheduleCancelEffects(sub, SubscriptionEndReason.ADMIN_CANCEL, now, queueRemote = true)
            )

        SubscriptionStatus.PAST_DUE, SubscriptionStatus.PAUSED -> single(
            SubRule.S7, sub.status, SubscriptionStatus.CANCELLED,
            endEffects(sub, SubscriptionStatus.CANCELLED, SubscriptionEndReason.ADMIN_CANCEL, RenewalDisposition.SKIPPED, queueRemote = true, cancelledWebhook = true)
        )

        SubscriptionStatus.PENDING -> ignored(NOT_ACTIVATED)
        SubscriptionStatus.EXPIRED, SubscriptionStatus.CANCELLED, SubscriptionStatus.COMPLETED -> ignored(SUBSCRIPTION_CLOSED)
    }

    // ---------------------------------------------------------------- S3

    private fun onPendingClosed(sub: SubState, reason: SubscriptionEndReason): SubTransition = when (sub.status) {
        SubscriptionStatus.PENDING -> pendingClosed(sub, reason, clearPii = false)
        SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE, SubscriptionStatus.PAUSED -> ignored(NOT_PENDING)
        SubscriptionStatus.EXPIRED, SubscriptionStatus.CANCELLED, SubscriptionStatus.COMPLETED -> ignored(SUBSCRIPTION_CLOSED)
    }

    private fun pendingClosed(sub: SubState, reason: SubscriptionEndReason, clearPii: Boolean): SubTransition {
        val effects = buildList<SubEffect> {
            add(ClosePending(reason, clearPii))
            // A gateway id may be known (09 section 4.3); then the gateway must stop billing.
            if (sub.mode == SubscriptionMode.GATEWAY && sub.gatewaySubscriptionId != null) add(QueueRemoteCancel)
        }
        return single(SubRule.S3, SubscriptionStatus.PENDING, SubscriptionStatus.CANCELLED, effects)
    }

    // ---------------------------------------------------------------- shared pieces

    private fun isComplete(sub: SubState): Boolean {
        val max = sub.maxCycles
        return max != null && sub.cycleCount >= max
    }

    /** `graceEndsAt = max(now, currentPeriodEnd) + subscriptionGraceDays` (S4). */
    private fun graceEndsFor(sub: SubState, now: Long, cfg: SubConfig): Long = maxOf(now, sub.currentPeriodEnd ?: now) + cfg.graceMs

    private fun gatewayRecordedFailure(): List<SubEffect> =
        listOf(RecordRenewalError, RecordFailure, AddOrderEvent(ORDER_EVENT_CHARGE_FAILED))

    /** The effects of S4: [extra] first, then the grace end, the mail (once per period) and the order event. */
    private fun pastDueEffects(sub: SubState, now: Long, cfg: SubConfig, mailReason: String, extra: List<SubEffect>): List<SubEffect> =
        extra + listOf(
            SetGraceEndsAt(graceEndsFor(sub, now, cfg)),
            QueueMail(MAIL_PAYMENT_FAILED, mailReason),
            AddOrderEvent(ORDER_EVENT_PAST_DUE)
        )

    /**
     * The shared ending (09 section 10.5) as an effect list. The cancelled webhook goes first when the cancellation
     * was recorded for the first time by this ending (S8 already fired it otherwise); the remote cancel is queued
     * last because the ending clears `nextQueryAt` and the queue sets it again.
     */
    private fun endEffects(
        sub: SubState,
        status: SubscriptionStatus,
        reason: SubscriptionEndReason,
        renewalRow: RenewalDisposition,
        undoHandledByCaller: Boolean = false,
        sendEndedMail: Boolean = true,
        clearPii: Boolean = false,
        queueRemote: Boolean = false,
        cancelledWebhook: Boolean = false
    ): List<SubEffect> = buildList {
        if (cancelledWebhook && !sub.cancelAtPeriodEnd) add(QueueWebhook(WEBHOOK_CANCELLED))
        add(EndSubscription(status, reason, renewalRow, undoHandledByCaller, sendEndedMail, clearPii))
        if (queueRemote && sub.mode == SubscriptionMode.GATEWAY) add(QueueRemoteCancel)
    }

    /** S8: cancel at period end (09 section 6). */
    private fun scheduleCancelEffects(
        sub: SubState,
        reason: SubscriptionEndReason,
        now: Long,
        markRemoteDone: Boolean = false,
        queueRemote: Boolean = false,
        prefix: List<SubEffect> = emptyList()
    ): List<SubEffect> = buildList {
        addAll(prefix)
        add(SetCancelAtPeriodEnd(true))
        // tx1 of a cancel already wrote cancelRequestedAt; keep the earliest.
        add(SetCancelRequestedAt(sub.cancelRequestedAt ?: now))
        add(SetEndReason(reason))
        add(SetNextChargeAt(null))
        if (sub.mode == SubscriptionMode.MANUAL) add(SkipPendingRenewal)
        if (markRemoteDone) add(MarkRemoteCancelDone)
        add(QueueMail(MAIL_CANCELLED))
        add(QueueWebhook(WEBHOOK_CANCELLED))
        add(AddOrderEvent(ORDER_EVENT_CANCEL_REQUESTED))
        if (queueRemote && sub.mode == SubscriptionMode.GATEWAY) add(QueueRemoteCancel)
    }

    private fun single(rule: SubRule, from: SubscriptionStatus?, to: SubscriptionStatus, effects: List<SubEffect>): SubTransition.Apply =
        SubTransition.Apply(listOf(SubStep(rule, from, to, effects)))

    private fun ignored(reason: String): SubTransition.Ignored = SubTransition.Ignored(reason)

    private val SubscriptionStatus.isTerminal: Boolean
        get() = this == SubscriptionStatus.EXPIRED || this == SubscriptionStatus.CANCELLED || this == SubscriptionStatus.COMPLETED
}
