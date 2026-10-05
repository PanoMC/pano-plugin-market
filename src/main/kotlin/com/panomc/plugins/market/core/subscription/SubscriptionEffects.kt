package com.panomc.plugins.market.core.subscription

import com.panomc.plugins.market.db.model.SubscriptionMode
import com.panomc.plugins.market.db.model.SubscriptionStatus

/** `market_subscription.endReason` (01 section 10.1). The enum names are the column values. */
enum class SubscriptionEndReason {
    BUYER_CANCEL, ADMIN_CANCEL, PAYMENT_FAILED, GATEWAY_ENDED, COMPLETED, REFUND, CHARGEBACK, PROVIDER_UNAVAILABLE;

    companion object {
        /** Unknown or blank values read as `null` (a provisional reason may be absent). */
        fun parse(value: String?): SubscriptionEndReason? = values().firstOrNull { it.name == value }
    }
}

/** What happens to the open renewal row when a subscription ends (09 section 10.5 step 2). */
enum class RenewalDisposition {
    /** S6: the unpaid renewal failed. */
    FAILED,

    /** Every other ending: the renewal is skipped. */
    SKIPPED
}

/**
 * What the service must do in the same transaction when [SubscriptionStateMachine] returns a transition (09 section
 * 6). Value objects without lambdas, so two decisions compare structurally. The machine names the effects and fixes
 * their order; `SubscriptionService` owns the SQL, the locks and the I/O. The effect list of a step is applied in
 * order after the conditional `UPDATE ... SET status = :to WHERE id = :id AND status = :from`.
 */
sealed interface SubEffect {
    /** `graceEndsAt = at` (S4, frozen at entry). */
    data class SetGraceEndsAt(val at: Long) : SubEffect

    /** `cancelAtPeriodEnd` flag (S8 sets, S10 clears). */
    data class SetCancelAtPeriodEnd(val flag: Boolean) : SubEffect

    /** `cancelRequestedAt` (`null` clears it). */
    data class SetCancelRequestedAt(val at: Long?) : SubEffect

    /** `endReason`; before the end it is the provisional reason of a scheduled cancel (`null` clears it). */
    data class SetEndReason(val reason: SubscriptionEndReason?) : SubEffect

    /** `nextChargeAt` (`null` clears it: no automatic charge). Only meaningful for `MERCHANT` rows. */
    data class SetNextChargeAt(val at: Long?) : SubEffect

    /** `failCount += 1`, `lastFailureAt = now`. */
    data object RecordFailure : SubEffect

    /** The renewal row of `periodIndex = cycleCount` gets `lastError` = the failure code (row created when missing). */
    data object RecordRenewalError : SubEffect

    /** `remoteCancelState = DONE`: the gateway reported the cancel. */
    data object MarkRemoteCancelDone : SubEffect

    /** `remoteCancelState = PENDING`, `remoteCancelAttempts = 0`, `nextQueryAt = now` (09 section 10.3). */
    data object QueueRemoteCancel : SubEffect

    /**
     * 09 section 4.4 steps 1 to 6: mode from `ModeResolver.atActivation`, provider, price, gateway or stored-method
     * data, `cycleCount = 1`, the first period, `nextChargeAt` / `nextQueryAt`, and the entitlement gets
     * `subscriptionId` with `expiresAt = NULL`.
     */
    data object ActivateFromPayment : SubEffect

    /**
     * 09 section 8.4 steps 2 to 5: renewal row `PAID`, `cycleCount += 1`, the period of the renewal row,
     * `graceEndsAt = NULL`, `failCount = 0`, `reminderSentAt = NULL`, `providerId` / [mode] / stored method as
     * decided. [scheduleCharge]: `nextChargeAt = currentPeriodEnd` (else `NULL`); [scheduleQuery]:
     * `nextQueryAt = currentPeriodEnd + 1 h`.
     */
    data class ApplyRenewal(val mode: SubscriptionMode, val scheduleCharge: Boolean, val scheduleQuery: Boolean) : SubEffect

    /** `RENEW` deliveries of the renewal order's item (09 section 8.4 step 6). */
    data object QueueRenewDeliveries : SubEffect

    /**
     * 09 section 10.5: the shared ending. Sets `status`, `endedAt`, `endReason`, `cancelledAt` (for `CANCELLED`),
     * clears `nextChargeAt`, `graceEndsAt` and the stored method; settles the open renewal row ([renewalRow]) and its
     * order; ends the entitlement and queues the `EXPIRE` deliveries unless [undoHandledByCaller] (the refund flow
     * wrote `REVOKE` instead); writes order event `SUBSCRIPTION_ENDED` and webhook `subscription.expired`; queues mail
     * `SUBSCRIPTION_ENDED` when [sendEndedMail]; with [clearPii] also `userId`, `email` and the stored method become `NULL`.
     */
    data class EndSubscription(
        val status: SubscriptionStatus,
        val reason: SubscriptionEndReason,
        val renewalRow: RenewalDisposition,
        val undoHandledByCaller: Boolean = false,
        val sendEndedMail: Boolean = true,
        val clearPii: Boolean = false
    ) : SubEffect

    /** S3: `PENDING -> CANCELLED` with `endedAt` and [reason]; no deliveries, mail or webhook; [clearPii] as above. */
    data class ClosePending(val reason: SubscriptionEndReason, val clearPii: Boolean = false) : SubEffect

    /** S8 on a `MANUAL` row: the pending renewal order is cancelled (O7, actor `SYSTEM`) and its renewal row is `SKIPPED`. */
    data object SkipPendingRenewal : SubEffect

    /** S10 on a `MANUAL` row: a `SKIPPED` renewal row returns to `PENDING` without an order (the job recreates it). */
    data object RestoreSkippedRenewal : SubEffect

    /** `market_mail_outbox` row of [kind] (12 section 12.2), [reason] is `SUBSCRIPTION_PAYMENT_FAILED`'s `reason` param. */
    data class QueueMail(val kind: String, val reason: String? = null) : SubEffect

    /** Store webhook, e.g. `subscription.cancelled` (09 section 12.1). */
    data class QueueWebhook(val event: String) : SubEffect

    /** `market_order_event` of [type] on the initial order (09 section 12.3; the renewal event goes on the renewal order). */
    data class AddOrderEvent(val type: String) : SubEffect
}
