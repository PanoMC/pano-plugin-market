package com.panomc.plugins.market.core.payment

import com.panomc.plugins.market.core.order.OrderActor
import com.panomc.plugins.market.core.order.OrderEvent
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.spi.payment.PriceAuthority
import com.panomc.plugins.market.spi.payment.ReviewReason
import com.panomc.plugins.market.util.OrderStatus

/** The attempt, frozen at insert (06 section 9.2). [attemptId] is the row id, [testMode] the attempt's environment. */
data class AttemptState(
    val attemptId: Long,
    val status: PaymentStatus,
    /** To collect, including the fee: what an amount check expects. */
    val amount: Long,
    val currency: String,
    /** Tender snapshot: credits held when the attempt was created. */
    val creditAmount: Long,
    val testMode: Boolean = false
)

/** The order as seen under its lock (06 section 13.2). */
data class OrderTender(
    val status: OrderStatus,
    val gatewayAmount: Long,
    val creditAmount: Long,
    /** `now >= order.expiresAt`: only relevant for the final failure rule (O8). */
    val windowOver: Boolean = false,
    /**
     * `order.paymentId`: the attempt whose money the order already carries (O3 / O9 / O2), `null` when none. Lets the
     * machine tell the order's own attempt (a late or corrected success after a review) from a SECOND paid attempt.
     */
    val paymentAttemptId: Long? = null
)

/** What the provider's capabilities say about money (00 section 6.9). */
data class ProviderMoneyPolicy(
    val buyerMayPayMore: Boolean = false,
    val priceAuthority: PriceAuthority = PriceAuthority.MARKET
)

/** `PaymentEvent` reduced to what the machine needs; the dispatcher maps the SPI events onto these. */
sealed class PaymentAttemptEvent {
    /** `startPayment` returned a buyer-facing result. */
    data object Started : PaymentAttemptEvent()

    /** `startPayment` threw a provider error. */
    data object StartFailed : PaymentAttemptEvent()

    /** `Pending`, or the buyer's bank transfer notice. */
    data object Pending : PaymentAttemptEvent()

    /**
     * `Succeeded` (also the `Completed` start result). [eventTestMode] is the environment the gateway states
     * (`PaymentEvent.testMode`), `null` = not stated.
     */
    data class Succeeded(val paidAmount: Long, val paidCurrency: String, val eventTestMode: Boolean? = null) : PaymentAttemptEvent()

    data class NeedsReview(val reason: ReviewReason, val eventTestMode: Boolean? = null) : PaymentAttemptEvent()

    /** `final`: the gateway will not accept this payment any more (O8 once the order window is over). */
    data class Failed(val final: Boolean = false) : PaymentAttemptEvent()
    data object Cancelled : PaymentAttemptEvent()

    /** Provider `Expired` or the attempt's own `expiresAt` passed. */
    data object Expired : PaymentAttemptEvent()

    /** A newer attempt replaced this one (`/pay`). */
    data object Replaced : PaymentAttemptEvent()
}

/** What the service writes besides the new status, in the same transaction. */
sealed interface PaymentEffect {
    /** `paidAmount`, `paidCurrency`, `paidAt`, fee and settlement fields. */
    data object RecordPaid : PaymentEffect

    /** `startPayload = NULL`. */
    data object ClearStartPayload : PaymentEffect

    /** `closedAt = now`. */
    data object StampClosed : PaymentEffect

    /** `duplicate = 1`; the service then refunds automatically (`autoRefundDuplicatePayments`) or raises a panel alert. */
    data object FlagDuplicate : PaymentEffect

    /** The newer open attempts of the order are cancelled in the same transaction. */
    data object CancelOtherOpenAttempts : PaymentEffect

    /** The reason is kept on the attempt (provider data) and in the timeline. */
    data class RecordReviewReason(val reason: ReviewReason) : PaymentEffect

    /**
     * The order is already in `REVIEW` and money arrived on [attemptId]: the service records it on the order
     * (`paymentId` / `paidAmount` from this attempt when the order carries none or the order's own attempt was
     * corrected; otherwise it adds to the received total) so that a later review rejection refunds what was really
     * received (06 section 9.4, `OrderState.paidAmount`). Always emitted together with [PanelAlert].
     */
    data class RecordPaymentOnOrder(val attemptId: Long) : PaymentEffect

    /** Panel alert without an order move (money arrived and the order cannot use it). */
    data object PanelAlert : PaymentEffect

    /** Feed this event to `OrderStateMachine.decide` in the same transaction. */
    data class NotifyOrder(val event: OrderEvent) : PaymentEffect
}

sealed class PaymentTransition {
    data class Rejected(val errorCode: String) : PaymentTransition()

    /** Idempotent replay, out-of-order event or not applicable: nothing changes (00 section 7.2). */
    data object NoOp : PaymentTransition()

    data class Move(val to: PaymentStatus, val effects: List<PaymentEffect>) : PaymentTransition()
}

/** Outcome of the amount check of 00 section 6.9. */
sealed class AmountCheck {
    data object Ok : AmountCheck()

    /** Passes, but more was paid than expected (only for `buyerMayPayMore` providers); `paidAmount` is recorded. */
    data object OkOverpaid : AmountCheck()
    data class Failed(val reason: ReviewReason) : AmountCheck()
}

/**
 * Payment attempt state machine (00 section 7.2, 06 section 9.4), pure. The attempt decides its own next status and
 * names the event the order must see ([PaymentEffect.NotifyOrder]); the order machine then decides the order's move.
 *
 * Provider events may arrive any number of times and in any order, so an event that does not apply to the attempt's
 * current status is a [PaymentTransition.NoOp], never an error. [PaymentTransition.Rejected] is reserved for
 * programming errors and is currently never produced.
 */
object PaymentStateMachine {
    private val OPEN = setOf(PaymentStatus.CREATED, PaymentStatus.PENDING, PaymentStatus.PROCESSING)
    private val CLOSED_UNPAID = setOf(PaymentStatus.FAILED, PaymentStatus.CANCELLED, PaymentStatus.EXPIRED)
    private val RELEASED_ORDER = setOf(OrderStatus.EXPIRED, OrderStatus.CANCELLED, OrderStatus.FAILED)
    private val PAID_ORDER = setOf(
        OrderStatus.COMPLETED, OrderStatus.PARTIALLY_REFUNDED, OrderStatus.REFUNDED, OrderStatus.CHARGEBACK
    )

    /**
     * `paid.currency == expected.currency && paid.amount >= expected.amount`; less is `UNDERPAID`, more is accepted
     * only when the provider declares [ProviderMoneyPolicy.buyerMayPayMore] (else `OVERPAID`); a currency
     * difference is `CURRENCY_MISMATCH` (checked first). Providers with `priceAuthority != MARKET` are exempt.
     */
    fun checkAmount(attempt: AttemptState, paidAmount: Long, paidCurrency: String, policy: ProviderMoneyPolicy): AmountCheck {
        if (policy.priceAuthority != PriceAuthority.MARKET) return AmountCheck.Ok
        if (paidCurrency != attempt.currency) return AmountCheck.Failed(ReviewReason.CURRENCY_MISMATCH)
        return when {
            paidAmount < attempt.amount -> AmountCheck.Failed(ReviewReason.UNDERPAID)
            paidAmount == attempt.amount -> AmountCheck.Ok
            policy.buyerMayPayMore -> AmountCheck.OkOverpaid
            else -> AmountCheck.Failed(ReviewReason.OVERPAID)
        }
    }

    /** `attempt.amount == order.gatewayAmount && attempt.creditAmount == order.creditAmount` (00 section 6.9). */
    fun tenderMatches(attempt: AttemptState, order: OrderTender): Boolean =
        attempt.amount == order.gatewayAmount && attempt.creditAmount == order.creditAmount

    fun decide(
        attempt: AttemptState,
        order: OrderTender,
        event: PaymentAttemptEvent,
        policy: ProviderMoneyPolicy = ProviderMoneyPolicy()
    ): PaymentTransition {
        val status = attempt.status
        return when (event) {
            PaymentAttemptEvent.Started ->
                if (status == PaymentStatus.CREATED) PaymentTransition.Move(PaymentStatus.PENDING, emptyList())
                else PaymentTransition.NoOp

            PaymentAttemptEvent.StartFailed ->
                if (status == PaymentStatus.CREATED) PaymentTransition.Move(PaymentStatus.FAILED, closeEffects())
                else PaymentTransition.NoOp

            PaymentAttemptEvent.Pending ->
                if (status == PaymentStatus.CREATED || status == PaymentStatus.PENDING)
                    PaymentTransition.Move(PaymentStatus.PROCESSING, emptyList())
                else PaymentTransition.NoOp

            is PaymentAttemptEvent.Succeeded -> succeeded(attempt, order, event, policy)

            is PaymentAttemptEvent.NeedsReview ->
                if (status == PaymentStatus.SUCCEEDED || status == PaymentStatus.REVIEW) PaymentTransition.NoOp
                else if (environmentMismatch(attempt, event.eventTestMode)) review(attempt, order, ReviewReason.OTHER, recordPaid = false)
                else review(attempt, order, event.reason, recordPaid = false)

            is PaymentAttemptEvent.Failed ->
                if (status in OPEN) {
                    val effects = closeEffects().toMutableList()
                    // O8: a final rejection once the order's window is over closes the order too.
                    if (event.final && order.windowOver && order.status == OrderStatus.PENDING) {
                        effects += PaymentEffect.NotifyOrder(OrderEvent.Fail(OrderActor.GATEWAY))
                    }
                    PaymentTransition.Move(PaymentStatus.FAILED, effects)
                } else PaymentTransition.NoOp

            PaymentAttemptEvent.Cancelled, PaymentAttemptEvent.Replaced ->
                if (status in OPEN) PaymentTransition.Move(PaymentStatus.CANCELLED, closeEffects()) else PaymentTransition.NoOp

            PaymentAttemptEvent.Expired ->
                if (status in OPEN) PaymentTransition.Move(PaymentStatus.EXPIRED, closeEffects()) else PaymentTransition.NoOp
        }
    }

    private fun closeEffects(): List<PaymentEffect> = listOf(PaymentEffect.ClearStartPayload, PaymentEffect.StampClosed)

    private fun environmentMismatch(attempt: AttemptState, eventTestMode: Boolean?) =
        eventTestMode != null && eventTestMode != attempt.testMode

    private fun succeeded(
        attempt: AttemptState,
        order: OrderTender,
        event: PaymentAttemptEvent.Succeeded,
        policy: ProviderMoneyPolicy
    ): PaymentTransition {
        // Any event on an attempt that already succeeded leaves it unchanged (refs are merged by the service).
        if (attempt.status == PaymentStatus.SUCCEEDED) return PaymentTransition.NoOp

        if (environmentMismatch(attempt, event.eventTestMode)) return review(attempt, order, ReviewReason.OTHER, recordPaid = true)

        val check = checkAmount(attempt, event.paidAmount, event.paidCurrency, policy)
        if (check is AmountCheck.Failed) return review(attempt, order, check.reason, recordPaid = true)

        val effects = mutableListOf<PaymentEffect>(PaymentEffect.RecordPaid, PaymentEffect.ClearStartPayload, PaymentEffect.StampClosed)
        val tenderOk = tenderMatches(attempt, order)
        when (order.status) {
            OrderStatus.PENDING -> {
                effects += PaymentEffect.CancelOtherOpenAttempts
                effects += PaymentEffect.NotifyOrder(
                    if (tenderOk) OrderEvent.Paid(attempt.attemptId, OrderActor.GATEWAY)
                    else OrderEvent.NeedsReview(ReviewReason.AMOUNT_MISMATCH, attempt.attemptId)
                )
            }

            // The order is already waiting for a human: the payment is recorded on the order and the panel told.
            OrderStatus.REVIEW -> {
                effects += PaymentEffect.RecordPaymentOnOrder(attempt.attemptId)
                effects += PaymentEffect.PanelAlert
            }

            in RELEASED_ORDER -> effects += PaymentEffect.NotifyOrder(
                OrderEvent.LatePayment(attempt.attemptId, if (tenderOk) ReviewReason.LATE else ReviewReason.AMOUNT_MISMATCH)
            )

            // A SECOND paid attempt is a duplicate; the order's own attempt (accepted out of REVIEW, then settled by
            // the gateway) just becomes SUCCEEDED.
            in PAID_ORDER -> if (order.paymentAttemptId != attempt.attemptId) effects += PaymentEffect.FlagDuplicate

            else -> Unit
        }
        return PaymentTransition.Move(PaymentStatus.SUCCEEDED, effects)
    }

    /** The attempt goes to `REVIEW` and the order is told with [reason] (O3, O9, or only recorded when already in review). */
    private fun review(attempt: AttemptState, order: OrderTender, reason: ReviewReason, recordPaid: Boolean): PaymentTransition {
        if (attempt.status == PaymentStatus.REVIEW) return PaymentTransition.NoOp
        val effects = mutableListOf<PaymentEffect>()
        if (recordPaid) effects += PaymentEffect.RecordPaid
        effects += PaymentEffect.RecordReviewReason(reason)
        when (order.status) {
            OrderStatus.PENDING -> {
                effects += PaymentEffect.CancelOtherOpenAttempts
                effects += PaymentEffect.NotifyOrder(OrderEvent.NeedsReview(reason, attempt.attemptId))
            }

            // Already waiting for a human: money that arrived is recorded on the order and the panel told.
            OrderStatus.REVIEW -> if (recordPaid) {
                effects += PaymentEffect.RecordPaymentOnOrder(attempt.attemptId)
                effects += PaymentEffect.PanelAlert
            }

            in RELEASED_ORDER -> effects += PaymentEffect.NotifyOrder(OrderEvent.LatePayment(attempt.attemptId, reason))
            // Paid order: nothing to move; money (or a suspicious notice) arrived and a human must look at it,
            // unless it is the order's own attempt (already accepted by that human).
            else -> if (order.paymentAttemptId != attempt.attemptId) effects += PaymentEffect.PanelAlert
        }
        return PaymentTransition.Move(PaymentStatus.REVIEW, effects)
    }
}
