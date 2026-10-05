package com.panomc.plugins.market.core.order

import com.panomc.plugins.market.core.order.OrderEffect.*
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.spi.payment.ReviewReason
import com.panomc.plugins.market.util.OrderStatus

/** Who causes an order event (`market_order_event.actorType`). */
enum class OrderActor { BUYER, ADMIN, GATEWAY, SYSTEM }

/**
 * What the machine needs to know about an order (06 section 11). [reviewReason] is an addition to the documented
 * tuple: O4 rewrites the tender only when the review was opened for `AMOUNT_MISMATCH`.
 */
data class OrderState(
    val status: OrderStatus,
    val reservationState: ReservationState = ReservationState.NONE,
    val expiresAt: Long? = null,
    /** An attempt is `PROCESSING`: money may be on its way. */
    val hasProcessingAttempt: Boolean = false,
    val paidAmount: Long? = null,
    val statusBeforeDispute: OrderStatus? = null,
    val reviewReason: ReviewReason? = null
)

/** `OrderEvent`: every trigger of the table in 00 section 7.1. */
sealed class OrderEvent {
    /** O1. Has no source state: [OrderStateMachine.create] decides it; on an existing order it is always rejected. */
    data class Create(val source: OrderSource, val actor: OrderActor = OrderActor.BUYER) : OrderEvent()

    /** O2: verified payment, free / credits order, bank transfer approved or admin "mark paid" ([attemptId] `null`). */
    data class Paid(val attemptId: Long?, val actor: OrderActor) : OrderEvent()

    /** O3: the provider or market's own checks want a human. */
    data class NeedsReview(
        val reason: ReviewReason,
        /** The attempt whose money triggered the review, recorded as `paymentId` / `paidAmount`. */
        val attemptId: Long? = null,
        val actor: OrderActor = OrderActor.GATEWAY
    ) : OrderEvent()

    /** O4. */
    data class ReviewAccepted(val force: Boolean = false) : OrderEvent()

    /** O5. [refund]: create a refund for the money received. */
    data class ReviewRejected(val refund: Boolean = false) : OrderEvent()

    /** O6, from the expiry job. */
    data class Expire(val now: Long) : OrderEvent()

    /** O7. */
    data class Cancel(val actor: OrderActor) : OrderEvent()

    /** O8: admin, or the gateway's final rejection once the order window is over. */
    data class Fail(val actor: OrderActor) : OrderEvent()

    /** O9: a payment arrived for a released order. */
    data class LatePayment(val attemptId: Long?, val reason: ReviewReason = ReviewReason.LATE) : OrderEvent()

    /** O10: a refund reached `SUCCEEDED`; [refundedTotal] already includes it. */
    data class RefundSucceeded(val refundedTotal: Long, val totalPrice: Long) : OrderEvent()

    /** O11: a dispute in state `OPENED` (an `INQUIRY` is informational and never an event). */
    data object DisputeOpened : OrderEvent()

    /** O12. */
    data object DisputeWon : OrderEvent()
}

/** Result of `decide`: a pure value, the service applies it. */
sealed class OrderTransition {
    /** Not allowed; [errorCode] is the API error, [use] the panel hint (`review`) of 06 section 11. */
    data class Rejected(val errorCode: String, val use: String? = null) : OrderTransition()

    /** Nothing to do (already there, or not due yet). */
    data object NoOp : OrderTransition()

    /** `UPDATE ... SET status = :to WHERE id = :id AND status = :from`, then [effects] in order. */
    data class Move(val to: OrderStatus, val effects: List<OrderEffect>) : OrderTransition()
}

/**
 * Order state machine (00 section 7.1, 06 section 11), pure: no clock, no database. `OrderService` applies a `Move`
 * under the locks of 06 section 13.2; zero updated rows means re-read and decide once more.
 */
object OrderStateMachine {
    const val INVALID_ORDER_TRANSITION = "INVALID_ORDER_TRANSITION"
    const val ORDER_NOT_CANCELLABLE = "ORDER_NOT_CANCELLABLE"
    const val INVALID_REFUND_TOTAL = "INVALID_REFUND_TOTAL"

    const val WEBHOOK_PAID = "order.paid"
    const val WEBHOOK_REFUNDED = "order.refunded"
    const val WEBHOOK_CHARGEBACK = "order.chargeback"
    const val WEBHOOK_CHARGEBACK_WON = "order.chargeback.won"
    const val MAIL_ORDER_CONFIRMATION = "ORDER_CONFIRMATION"
    const val MAIL_ORDER_REFUNDED = "ORDER_REFUNDED"

    private val RELEASED = setOf(OrderStatus.EXPIRED, OrderStatus.CANCELLED, OrderStatus.FAILED)
    private val PAID_STATES = setOf(OrderStatus.COMPLETED, OrderStatus.PARTIALLY_REFUNDED, OrderStatus.REFUNDED)
    private val DISPUTABLE = PAID_STATES

    /** O1: the only decision without a source state. A renewal reserves nothing (09 section 8.1). */
    fun create(source: OrderSource): OrderTransition.Move = OrderTransition.Move(
        OrderStatus.PENDING,
        if (source == OrderSource.RENEWAL) listOf(SetExpiresAt)
        else listOf(ReserveStockAndLimits, HoldCredits, SetExpiresAt)
    )

    fun decide(state: OrderState, event: OrderEvent): OrderTransition {
        val status = state.status
        return when (event) {
            is OrderEvent.Create -> reject(state, event)

            is OrderEvent.Paid -> when (status) {
                OrderStatus.PENDING -> OrderTransition.Move(
                    OrderStatus.COMPLETED, paidEffects(event.attemptId, keepPaidAmount = false, held = held(state))
                )

                OrderStatus.COMPLETED -> OrderTransition.NoOp
                else -> reject(state, event)
            }

            is OrderEvent.NeedsReview ->
                if (status == OrderStatus.PENDING) OrderTransition.Move(OrderStatus.REVIEW, reviewEffects(event.reason, event.attemptId))
                else reject(state, event)

            is OrderEvent.ReviewAccepted ->
                if (status == OrderStatus.REVIEW) OrderTransition.Move(OrderStatus.COMPLETED, acceptEffects(state, event.force))
                else reject(state, event)

            is OrderEvent.ReviewRejected ->
                if (status == OrderStatus.REVIEW) OrderTransition.Move(OrderStatus.CANCELLED, rejectEffects(state, event.refund))
                else reject(state, event)

            is OrderEvent.Expire ->
                if (status != OrderStatus.PENDING) reject(state, event)
                else {
                    val due = state.expiresAt != null && state.expiresAt <= event.now
                    if (!due || state.hasProcessingAttempt) OrderTransition.NoOp
                    else OrderTransition.Move(OrderStatus.EXPIRED, closeUnpaid(state, PaymentStatus.EXPIRED, gateway = true))
                }

            is OrderEvent.Cancel -> when {
                status == OrderStatus.CANCELLED -> OrderTransition.NoOp
                status != OrderStatus.PENDING -> reject(state, event)
                event.actor == OrderActor.BUYER && state.hasProcessingAttempt ->
                    OrderTransition.Rejected(ORDER_NOT_CANCELLABLE)

                else -> OrderTransition.Move(OrderStatus.CANCELLED, closeUnpaid(state, PaymentStatus.CANCELLED, gateway = true))
            }

            is OrderEvent.Fail -> when (status) {
                OrderStatus.FAILED -> OrderTransition.NoOp
                OrderStatus.PENDING -> OrderTransition.Move(
                    OrderStatus.FAILED,
                    // A gateway-reported final failure already closed its own attempt: attempts stay as they are.
                    if (event.actor == OrderActor.GATEWAY) closeUnpaid(state, null, gateway = false)
                    else closeUnpaid(state, PaymentStatus.FAILED, gateway = true)
                )

                else -> reject(state, event)
            }

            is OrderEvent.LatePayment ->
                if (status in RELEASED) OrderTransition.Move(
                    OrderStatus.REVIEW, listOf(SetReviewReason(event.reason), RecordPayment(event.attemptId), PanelAlert)
                )
                else reject(state, event)

            is OrderEvent.RefundSucceeded -> when {
                status != OrderStatus.COMPLETED && status != OrderStatus.PARTIALLY_REFUNDED -> reject(state, event)
                event.refundedTotal < 1 || event.refundedTotal > event.totalPrice ->
                    OrderTransition.Rejected(INVALID_REFUND_TOTAL)

                else -> OrderTransition.Move(
                    if (event.refundedTotal == event.totalPrice) OrderStatus.REFUNDED else OrderStatus.PARTIALLY_REFUNDED,
                    refundEffects()
                )
            }

            is OrderEvent.DisputeOpened ->
                if (status in DISPUTABLE) OrderTransition.Move(OrderStatus.CHARGEBACK, disputeEffects(status))
                else reject(state, event)

            is OrderEvent.DisputeWon -> {
                val before = state.statusBeforeDispute
                if (status == OrderStatus.CHARGEBACK && before != null && before in DISPUTABLE)
                    OrderTransition.Move(before, listOf(QueueWebhook(WEBHOOK_CHARGEBACK_WON)))
                else reject(state, event)
            }
        }
    }

    private fun reject(state: OrderState, event: OrderEvent): OrderTransition.Rejected {
        // From REVIEW the only way out is the review endpoint (06 section 11).
        val viaReview = state.status == OrderStatus.REVIEW &&
            (event is OrderEvent.Paid || event is OrderEvent.Cancel || event is OrderEvent.Fail)
        if (event is OrderEvent.Cancel && event.actor == OrderActor.BUYER) return OrderTransition.Rejected(ORDER_NOT_CANCELLABLE)
        return OrderTransition.Rejected(INVALID_ORDER_TRANSITION, if (viaReview) "review" else null)
    }

    /** The reservation exists (or is brought back by a re-reserve): commit, capture and redemptions apply. */
    private fun held(state: OrderState) =
        state.reservationState == ReservationState.HELD || state.reservationState == ReservationState.RELEASED

    /** O2 and, after its own steps, O4. */
    private fun paidEffects(attemptId: Long?, keepPaidAmount: Boolean, held: Boolean): List<OrderEffect> = buildList {
        if (held) {
            add(CommitReservation)
            add(CaptureCreditHold)
            add(ApplyRedemptions)
        }
        add(StampPaid(attemptId, keepPaidAmount))
        add(ClearExpiry)
        add(CancelOpenAttempts)
        add(AccrueCreatorEarning)
        add(GrantCashback)
        add(CreditGrantingLines)
        add(GrantEntitlements)
        add(QueueGrantDeliveries)
        add(SubscriptionOnOrderPaid)
        add(IssueInvoice)
        add(QueueMail(MAIL_ORDER_CONFIRMATION))
        add(QueueWebhook(WEBHOOK_PAID))
        add(AdvanceGoalProgress)
        add(StartShipping)
    }

    /** O3: reservation stays `HELD`, expiry paused, panel alert. */
    private fun reviewEffects(reason: ReviewReason, attemptId: Long?): List<OrderEffect> =
        listOf(SetReviewReason(reason), ClearExpiry, PanelAlert) + listOfNotNull(attemptId?.let { RecordPayment(it) })

    /** O4: re-reserve a released hold, rewrite the tender of an `AMOUNT_MISMATCH`, then everything of O2. */
    private fun acceptEffects(state: OrderState, force: Boolean): List<OrderEffect> = buildList {
        val released = state.reservationState == ReservationState.RELEASED
        if (released) {
            add(ReReserve(force))
            if (force) add(RecordForceOverride)
        }
        if (state.reviewReason == ReviewReason.AMOUNT_MISMATCH) add(RewriteTender)
        addAll(paidEffects(null, keepPaidAmount = true, held = held(state)))
    }

    /** O5: release when `HELD`, close open attempts, refund the money received (never the credit hold). */
    private fun rejectEffects(state: OrderState, refund: Boolean): List<OrderEffect> = buildList {
        if (state.reservationState == ReservationState.HELD) add(ReleaseReservation)
        add(CloseOpenAttempts(PaymentStatus.CANCELLED))
        add(CancelAtGatewayAfterCommit)
        if (refund && (state.paidAmount ?: 0) > 0) add(CreateRefundForPaidAmount)
        add(SubscriptionOnClosedUnpaid)
    }

    /** O6 - O8: release, close the open attempts ([attempts] `null` = leave them), best-effort gateway cancel. */
    private fun closeUnpaid(state: OrderState, attempts: PaymentStatus?, gateway: Boolean): List<OrderEffect> = buildList {
        if (state.reservationState == ReservationState.HELD) add(ReleaseReservation)
        if (attempts != null) add(CloseOpenAttempts(attempts))
        if (gateway) add(CancelAtGatewayAfterCommit)
        add(SubscriptionOnClosedUnpaid)
    }

    /** O10 (21 section 3). The service applies the configuration switches (`revokeOnRefund`, restock). */
    private fun refundEffects(): List<OrderEffect> = listOf(
        RevokeDeliveries(onDispute = false),
        RestockItems,
        ReverseCreatorEarning,
        ReverseCashback,
        ClawbackGrantedCredits,
        ReturnCreditsToLedger,
        IssueCreditNote,
        QueueMail(MAIL_ORDER_REFUNDED),
        QueueWebhook(WEBHOOK_REFUNDED),
        SubscriptionOnOrderRefunded
    )

    /** O11 (21 section 5). */
    private fun disputeEffects(from: OrderStatus): List<OrderEffect> = listOf(
        SaveStatusBeforeDispute(from),
        RevokeDeliveries(onDispute = true),
        BlockBuyer,
        RunChargebackActions,
        ReverseCreatorEarning,
        ReverseCashback,
        ClawbackGrantedCredits,
        SubscriptionOnOrderChargeback,
        QueueWebhook(WEBHOOK_CHARGEBACK)
    )
}
