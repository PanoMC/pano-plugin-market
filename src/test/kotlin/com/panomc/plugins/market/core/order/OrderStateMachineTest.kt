package com.panomc.plugins.market.core.order

import com.panomc.plugins.market.core.order.OrderEffect.*
import com.panomc.plugins.market.core.order.OrderTransition.Move
import com.panomc.plugins.market.core.order.OrderTransition.NoOp
import com.panomc.plugins.market.core.order.OrderTransition.Rejected
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.spi.payment.ReviewReason
import com.panomc.plugins.market.util.OrderStatus
import com.panomc.plugins.market.util.OrderStatus.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `OrderStateMachine.decide` against the table of 00 section 7.1 (O1 - O12), exhaustively: every one of the 9 statuses
 * against every event type. Allowed pairs are listed once, in [expected]; every other pair must be `Rejected`.
 */
class OrderStateMachineTest {
    private val now = 1_000_000L

    /** One representative event per type, each in the form that is valid for its row of the table. */
    private val events: Map<String, OrderEvent> = linkedMapOf(
        "Create" to OrderEvent.Create(OrderSource.STOREFRONT),
        "Paid" to OrderEvent.Paid(11, OrderActor.GATEWAY),
        "NeedsReview" to OrderEvent.NeedsReview(ReviewReason.UNDERPAID, 11),
        "ReviewAccepted" to OrderEvent.ReviewAccepted(),
        "ReviewRejected" to OrderEvent.ReviewRejected(refund = true),
        "Expire" to OrderEvent.Expire(now),
        "Cancel" to OrderEvent.Cancel(OrderActor.ADMIN),
        "Fail" to OrderEvent.Fail(OrderActor.ADMIN),
        "LatePayment" to OrderEvent.LatePayment(11),
        "RefundPartial" to OrderEvent.RefundSucceeded(refundedTotal = 400, totalPrice = 1000),
        "RefundFull" to OrderEvent.RefundSucceeded(refundedTotal = 1000, totalPrice = 1000),
        "DisputeOpened" to OrderEvent.DisputeOpened,
        "DisputeWon" to OrderEvent.DisputeWon
    )

    /** The table: event name to (status to result status). `null` result = `NoOp`. */
    private val expected: Map<String, Map<OrderStatus, OrderStatus?>> = mapOf(
        "Paid" to mapOf(PENDING to COMPLETED, COMPLETED to null),
        "NeedsReview" to mapOf(PENDING to REVIEW),
        "ReviewAccepted" to mapOf(REVIEW to COMPLETED),
        "ReviewRejected" to mapOf(REVIEW to CANCELLED),
        "Expire" to mapOf(PENDING to EXPIRED),
        "Cancel" to mapOf(PENDING to CANCELLED, CANCELLED to null),
        "Fail" to mapOf(PENDING to FAILED, FAILED to null),
        "LatePayment" to mapOf(EXPIRED to REVIEW, CANCELLED to REVIEW, FAILED to REVIEW),
        "RefundPartial" to mapOf(COMPLETED to PARTIALLY_REFUNDED, PARTIALLY_REFUNDED to PARTIALLY_REFUNDED),
        "RefundFull" to mapOf(COMPLETED to REFUNDED, PARTIALLY_REFUNDED to REFUNDED),
        "DisputeOpened" to mapOf(COMPLETED to CHARGEBACK, PARTIALLY_REFUNDED to CHARGEBACK, REFUNDED to CHARGEBACK),
        "DisputeWon" to mapOf(CHARGEBACK to COMPLETED)
    )

    private fun state(
        status: OrderStatus,
        reservation: ReservationState = ReservationState.HELD,
        expiresAt: Long? = now - 1,
        processing: Boolean = false,
        paidAmount: Long? = null,
        before: OrderStatus? = COMPLETED,
        reason: ReviewReason? = null
    ) = OrderState(status, reservation, expiresAt, processing, paidAmount, before, reason)

    @Test
    fun `all 9 statuses times every event type equal the table and every other pair is rejected`() {
        assertEquals(9, OrderStatus.values().size)
        var checked = 0
        for (status in OrderStatus.values()) {
            for ((name, event) in events) {
                val result = OrderStateMachine.decide(state(status), event)
                val allowed = expected[name]
                val label = "$status x $name"
                if (allowed != null && allowed.containsKey(status)) {
                    val to = allowed.getValue(status)
                    if (to == null) assertEquals(NoOp, result, label)
                    else {
                        assertTrue(result is Move, "$label: $result")
                        assertEquals(to, (result as Move).to, label)
                        assertTrue(result.effects.isNotEmpty(), "$label has effects")
                    }
                } else {
                    assertTrue(result is Rejected, "$label must be rejected, was $result")
                    assertEquals(OrderStateMachine.INVALID_ORDER_TRANSITION, (result as Rejected).errorCode, label)
                }
                checked++
            }
        }
        assertEquals(9 * events.size, checked)
    }

    @Test
    fun `O1 create is a move to PENDING from no state and rejected on every existing order`() {
        assertEquals(
            Move(PENDING, listOf(ReserveStockAndLimits, HoldCredits, SetExpiresAt)),
            OrderStateMachine.create(OrderSource.STOREFRONT)
        )
        for (source in OrderSource.values()) {
            val effects = OrderStateMachine.create(source).effects
            if (source == OrderSource.RENEWAL) assertEquals(listOf(SetExpiresAt), effects, "a renewal reserves nothing")
            else assertEquals(listOf(ReserveStockAndLimits, HoldCredits, SetExpiresAt), effects, source.name)
        }
        for (status in OrderStatus.values()) {
            assertTrue(OrderStateMachine.decide(state(status), OrderEvent.Create(OrderSource.PANEL)) is Rejected, status.name)
        }
    }

    @Test
    fun `O2 paid effects are exact and ordered`() {
        val result = OrderStateMachine.decide(state(PENDING), OrderEvent.Paid(11, OrderActor.GATEWAY))
        assertEquals(
            Move(
                COMPLETED,
                listOf(
                    CommitReservation, CaptureCreditHold, ApplyRedemptions,
                    StampPaid(11, keepPaidAmount = false), ClearExpiry, CancelOpenAttempts,
                    AccrueCreatorEarning, GrantCashback, CreditGrantingLines, GrantEntitlements,
                    QueueGrantDeliveries, SubscriptionOnOrderPaid, IssueInvoice,
                    QueueMail("ORDER_CONFIRMATION"), QueueWebhook("order.paid"), AdvanceGoalProgress, StartShipping
                )
            ),
            result
        )
    }

    @Test
    fun `O2 admin mark paid has no attempt and a renewal without a reservation skips commit capture and redemptions`() {
        val admin = OrderStateMachine.decide(state(PENDING), OrderEvent.Paid(null, OrderActor.ADMIN)) as Move
        assertTrue(StampPaid(null) in admin.effects)

        val renewal = OrderStateMachine.decide(state(PENDING, reservation = ReservationState.NONE), OrderEvent.Paid(5, OrderActor.SYSTEM)) as Move
        assertTrue(CommitReservation !in renewal.effects)
        assertTrue(CaptureCreditHold !in renewal.effects)
        assertTrue(ApplyRedemptions !in renewal.effects)
        assertEquals(StampPaid(5), renewal.effects.first())
    }

    @Test
    fun `same status mark paid is a no-op and paid on REVIEW says use review`() {
        assertEquals(NoOp, OrderStateMachine.decide(state(COMPLETED), OrderEvent.Paid(null, OrderActor.ADMIN)))
        assertEquals(
            Rejected(OrderStateMachine.INVALID_ORDER_TRANSITION, "review"),
            OrderStateMachine.decide(state(REVIEW), OrderEvent.Paid(null, OrderActor.ADMIN))
        )
        assertEquals(
            Rejected(OrderStateMachine.INVALID_ORDER_TRANSITION, "review"),
            OrderStateMachine.decide(state(REVIEW), OrderEvent.Paid(11, OrderActor.GATEWAY))
        )
        assertEquals(
            Rejected(OrderStateMachine.INVALID_ORDER_TRANSITION, "review"),
            OrderStateMachine.decide(state(REVIEW), OrderEvent.Cancel(OrderActor.ADMIN))
        )
        assertEquals(
            Rejected(OrderStateMachine.INVALID_ORDER_TRANSITION),
            OrderStateMachine.decide(state(REFUNDED), OrderEvent.Paid(null, OrderActor.ADMIN))
        )
    }

    @Test
    fun `O3 review keeps the reservation, pauses expiry and records the payment`() {
        assertEquals(
            Move(REVIEW, listOf(SetReviewReason(ReviewReason.UNDERPAID), ClearExpiry, PanelAlert, RecordPayment(11))),
            OrderStateMachine.decide(state(PENDING), OrderEvent.NeedsReview(ReviewReason.UNDERPAID, 11))
        )
        assertEquals(
            Move(REVIEW, listOf(SetReviewReason(ReviewReason.FRAUD_REVIEW), ClearExpiry, PanelAlert)),
            OrderStateMachine.decide(state(PENDING), OrderEvent.NeedsReview(ReviewReason.FRAUD_REVIEW))
        )
    }

    @Test
    fun `O4 accept of a held order is exactly O2 with the received amount kept`() {
        val accept = OrderStateMachine.decide(state(REVIEW, reason = ReviewReason.UNDERPAID, paidAmount = 900), OrderEvent.ReviewAccepted()) as Move
        val paid = (OrderStateMachine.decide(state(PENDING), OrderEvent.Paid(null, OrderActor.ADMIN)) as Move).effects
        assertEquals(COMPLETED, accept.to)
        assertEquals(paid.map { if (it is StampPaid) StampPaid(null, keepPaidAmount = true) else it }, accept.effects)
    }

    @Test
    fun `O4 accept of a released order re-reserves first, force is written to the timeline, amount mismatch rewrites the tender`() {
        val released = state(REVIEW, reservation = ReservationState.RELEASED, reason = ReviewReason.LATE)
        val plain = (OrderStateMachine.decide(released, OrderEvent.ReviewAccepted(force = false)) as Move).effects
        assertEquals(ReReserve(false), plain.first())
        assertTrue(RecordForceOverride !in plain)
        assertTrue(RewriteTender !in plain)
        assertTrue(CommitReservation in plain)

        val forced = (OrderStateMachine.decide(released, OrderEvent.ReviewAccepted(force = true)) as Move).effects
        assertEquals(listOf(ReReserve(true), RecordForceOverride), forced.take(2))

        val mismatch = (OrderStateMachine.decide(
            released.copy(reviewReason = ReviewReason.AMOUNT_MISMATCH), OrderEvent.ReviewAccepted()
        ) as Move).effects
        assertEquals(listOf(ReReserve(false), RewriteTender), mismatch.take(2))

        val heldMismatch = (OrderStateMachine.decide(
            state(REVIEW, reason = ReviewReason.AMOUNT_MISMATCH), OrderEvent.ReviewAccepted()
        ) as Move).effects
        assertEquals(RewriteTender, heldMismatch.first())
        assertTrue(heldMismatch.none { it is ReReserve })
    }

    @Test
    fun `O5 reject releases a held reservation and refunds only money that was received, never a credit refund`() {
        val withMoney = OrderStateMachine.decide(state(REVIEW, paidAmount = 1000), OrderEvent.ReviewRejected(refund = true))
        assertEquals(
            Move(
                CANCELLED,
                listOf(
                    ReleaseReservation, CloseOpenAttempts(PaymentStatus.CANCELLED), CancelAtGatewayAfterCommit,
                    CreateRefundForPaidAmount, SubscriptionOnClosedUnpaid
                )
            ),
            withMoney
        )
        val noMoney = (OrderStateMachine.decide(state(REVIEW, paidAmount = 0), OrderEvent.ReviewRejected(refund = true)) as Move).effects
        assertTrue(CreateRefundForPaidAmount !in noMoney)
        val noRefund = (OrderStateMachine.decide(state(REVIEW, paidAmount = 1000), OrderEvent.ReviewRejected(refund = false)) as Move).effects
        assertTrue(CreateRefundForPaidAmount !in noRefund)
        val released = (OrderStateMachine.decide(
            state(REVIEW, reservation = ReservationState.RELEASED, paidAmount = 1000), OrderEvent.ReviewRejected(refund = true)
        ) as Move).effects
        assertTrue(ReleaseReservation !in released, "an already released hold is not released again")
    }

    @Test
    fun `O6 expire effects are exact`() {
        assertEquals(
            Move(
                EXPIRED,
                listOf(
                    ReleaseReservation, CloseOpenAttempts(PaymentStatus.EXPIRED), CancelAtGatewayAfterCommit,
                    SubscriptionOnClosedUnpaid
                )
            ),
            OrderStateMachine.decide(state(PENDING), OrderEvent.Expire(now))
        )
        // A renewal order never held anything.
        assertEquals(
            Move(EXPIRED, listOf(CloseOpenAttempts(PaymentStatus.EXPIRED), CancelAtGatewayAfterCommit, SubscriptionOnClosedUnpaid)),
            OrderStateMachine.decide(state(PENDING, reservation = ReservationState.NONE), OrderEvent.Expire(now))
        )
    }

    @Test
    fun `O6 expire waits for the deadline, for a PROCESSING attempt and for a missing expiry`() {
        assertEquals(NoOp, OrderStateMachine.decide(state(PENDING, expiresAt = now + 1), OrderEvent.Expire(now)))
        assertEquals(NoOp, OrderStateMachine.decide(state(PENDING, expiresAt = null), OrderEvent.Expire(now)))
        assertEquals(NoOp, OrderStateMachine.decide(state(PENDING, processing = true), OrderEvent.Expire(now)))
        assertTrue(OrderStateMachine.decide(state(PENDING, expiresAt = now), OrderEvent.Expire(now)) is Move, "expiresAt <= now")
        // Orders in REVIEW have no expiry and are never expired.
        assertTrue(OrderStateMachine.decide(state(REVIEW, expiresAt = null), OrderEvent.Expire(now)) is Rejected)
    }

    @Test
    fun `O7 cancel by the admin is allowed even with a PROCESSING attempt, the buyer is refused`() {
        val effects = listOf(
            ReleaseReservation, CloseOpenAttempts(PaymentStatus.CANCELLED), CancelAtGatewayAfterCommit, SubscriptionOnClosedUnpaid
        )
        assertEquals(Move(CANCELLED, effects), OrderStateMachine.decide(state(PENDING), OrderEvent.Cancel(OrderActor.BUYER)))
        assertEquals(
            Move(CANCELLED, effects),
            OrderStateMachine.decide(state(PENDING, processing = true), OrderEvent.Cancel(OrderActor.ADMIN))
        )
        assertEquals(
            Rejected(OrderStateMachine.ORDER_NOT_CANCELLABLE),
            OrderStateMachine.decide(state(PENDING, processing = true), OrderEvent.Cancel(OrderActor.BUYER))
        )
    }

    @Test
    fun `O7 cancel by the buyer is idempotent on CANCELLED and refused with ORDER_NOT_CANCELLABLE elsewhere`() {
        assertEquals(NoOp, OrderStateMachine.decide(state(CANCELLED), OrderEvent.Cancel(OrderActor.BUYER)))
        for (status in OrderStatus.values().filter { it != PENDING && it != CANCELLED }) {
            assertEquals(
                Rejected(OrderStateMachine.ORDER_NOT_CANCELLABLE),
                OrderStateMachine.decide(state(status), OrderEvent.Cancel(OrderActor.BUYER)),
                status.name
            )
        }
    }

    @Test
    fun `O8 fail by the admin closes attempts, a gateway final failure leaves them alone`() {
        assertEquals(
            Move(
                FAILED,
                listOf(ReleaseReservation, CloseOpenAttempts(PaymentStatus.FAILED), CancelAtGatewayAfterCommit, SubscriptionOnClosedUnpaid)
            ),
            OrderStateMachine.decide(state(PENDING), OrderEvent.Fail(OrderActor.ADMIN))
        )
        assertEquals(
            Move(FAILED, listOf(ReleaseReservation, SubscriptionOnClosedUnpaid)),
            OrderStateMachine.decide(state(PENDING), OrderEvent.Fail(OrderActor.GATEWAY))
        )
    }

    @Test
    fun `O9 late payment on a released order opens a review with the reason and records the payment`() {
        for (status in listOf(EXPIRED, CANCELLED, FAILED)) {
            assertEquals(
                Move(REVIEW, listOf(SetReviewReason(ReviewReason.LATE), RecordPayment(11), PanelAlert)),
                OrderStateMachine.decide(state(status, reservation = ReservationState.RELEASED), OrderEvent.LatePayment(11)),
                status.name
            )
        }
        assertEquals(
            Move(REVIEW, listOf(SetReviewReason(ReviewReason.AMOUNT_MISMATCH), RecordPayment(3), PanelAlert)),
            OrderStateMachine.decide(
                state(EXPIRED, reservation = ReservationState.RELEASED), OrderEvent.LatePayment(3, ReviewReason.AMOUNT_MISMATCH)
            )
        )
    }

    @Test
    fun `O10 refund effects are exact and the target depends on refundedTotal equal to totalPrice`() {
        val effects = listOf(
            RevokeDeliveries(onDispute = false), RestockItems, ReverseCreatorEarning, ReverseCashback, ClawbackGrantedCredits,
            ReturnCreditsToLedger, IssueCreditNote, QueueMail("ORDER_REFUNDED"), QueueWebhook("order.refunded"),
            SubscriptionOnOrderRefunded
        )
        assertEquals(Move(PARTIALLY_REFUNDED, effects), OrderStateMachine.decide(state(COMPLETED), OrderEvent.RefundSucceeded(400, 1000)))
        assertEquals(Move(REFUNDED, effects), OrderStateMachine.decide(state(COMPLETED), OrderEvent.RefundSucceeded(1000, 1000)))
        assertEquals(
            Move(REFUNDED, effects), OrderStateMachine.decide(state(PARTIALLY_REFUNDED), OrderEvent.RefundSucceeded(1000, 1000))
        )
        assertEquals(
            Move(PARTIALLY_REFUNDED, effects), OrderStateMachine.decide(state(PARTIALLY_REFUNDED), OrderEvent.RefundSucceeded(999, 1000))
        )
    }

    @Test
    fun `O10 refund totals outside 1 to totalPrice are rejected`() {
        for ((refunded, total) in listOf(0L to 1000L, -5L to 1000L, 1001L to 1000L, 1L to 0L)) {
            assertEquals(
                Rejected(OrderStateMachine.INVALID_REFUND_TOTAL),
                OrderStateMachine.decide(state(COMPLETED), OrderEvent.RefundSucceeded(refunded, total)),
                "$refunded of $total"
            )
        }
    }

    @Test
    fun `O11 dispute effects are exact and remember the status before the dispute`() {
        for (from in listOf(COMPLETED, PARTIALLY_REFUNDED, REFUNDED)) {
            assertEquals(
                Move(
                    CHARGEBACK,
                    listOf(
                        SaveStatusBeforeDispute(from), RevokeDeliveries(onDispute = true), BlockBuyer, RunChargebackActions,
                        ReverseCreatorEarning, ReverseCashback, ClawbackGrantedCredits, SubscriptionOnOrderChargeback,
                        QueueWebhook("order.chargeback")
                    )
                ),
                OrderStateMachine.decide(state(from), OrderEvent.DisputeOpened),
                from.name
            )
        }
    }

    @Test
    fun `O12 dispute won returns to the saved status and only from CHARGEBACK`() {
        for (before in listOf(COMPLETED, PARTIALLY_REFUNDED, REFUNDED)) {
            assertEquals(
                Move(before, listOf(QueueWebhook("order.chargeback.won"))),
                OrderStateMachine.decide(state(CHARGEBACK, before = before), OrderEvent.DisputeWon),
                before.name
            )
        }
        // A chargeback without a usable saved status cannot be reverted automatically.
        for (bad in listOf(null, PENDING, CHARGEBACK, REVIEW)) {
            assertTrue(OrderStateMachine.decide(state(CHARGEBACK, before = bad), OrderEvent.DisputeWon) is Rejected, "before=$bad")
        }
    }

    @Test
    fun `terminal states take no automatic event except late payment`() {
        for (status in listOf(EXPIRED, CANCELLED, FAILED)) {
            for ((name, event) in events) {
                val result = OrderStateMachine.decide(state(status), event)
                if (name == "LatePayment" || (name == "Cancel" && status == CANCELLED) || (name == "Fail" && status == FAILED)) continue
                assertTrue(result is Rejected, "$status x $name -> $result")
            }
        }
    }

    @Test
    fun `decide is deterministic and results compare structurally`() {
        val s = state(PENDING)
        val e = OrderEvent.Paid(11, OrderActor.GATEWAY)
        assertEquals(OrderStateMachine.decide(s, e), OrderStateMachine.decide(s.copy(), OrderEvent.Paid(11, OrderActor.GATEWAY)))
    }
}
