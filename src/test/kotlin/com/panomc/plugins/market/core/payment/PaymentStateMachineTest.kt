package com.panomc.plugins.market.core.payment

import com.panomc.plugins.market.core.order.OrderActor
import com.panomc.plugins.market.core.order.OrderEffect
import com.panomc.plugins.market.core.order.OrderEvent
import com.panomc.plugins.market.core.order.OrderState
import com.panomc.plugins.market.core.order.OrderStateMachine
import com.panomc.plugins.market.core.order.OrderTransition
import com.panomc.plugins.market.core.payment.PaymentAttemptEvent.*
import com.panomc.plugins.market.core.payment.PaymentEffect.*
import com.panomc.plugins.market.core.payment.PaymentTransition.Move
import com.panomc.plugins.market.core.payment.PaymentTransition.NoOp
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.db.model.PaymentStatus.*
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.spi.payment.PriceAuthority
import com.panomc.plugins.market.spi.payment.ReviewReason
import com.panomc.plugins.market.util.OrderStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `PaymentStateMachine` (00 section 7.2, 06 section 9.4) and the amount / tender checks of 00 section 6.9. */
class PaymentStateMachineTest {
    private val eur = "EUR"
    private val plain = ProviderMoneyPolicy()
    private val moreOk = ProviderMoneyPolicy(buyerMayPayMore = true)

    private fun attempt(status: PaymentStatus, amount: Long = 1000, credit: Long = 0, testMode: Boolean = false, currency: String = eur) =
        AttemptState(7, status, amount, currency, credit, testMode)

    private fun order(
        status: OrderStatus = OrderStatus.PENDING,
        gateway: Long = 1000,
        credit: Long = 0,
        windowOver: Boolean = false,
        paymentAttemptId: Long? = null
    ) = OrderTender(status, gateway, credit, windowOver, paymentAttemptId)

    private val paidOrders = listOf(OrderStatus.COMPLETED, OrderStatus.PARTIALLY_REFUNDED, OrderStatus.REFUNDED, OrderStatus.CHARGEBACK)

    private fun decide(a: AttemptState, o: OrderTender, e: PaymentAttemptEvent, p: ProviderMoneyPolicy = plain) =
        PaymentStateMachine.decide(a, o, e, p)

    private val closed = listOf(ClearStartPayload, StampClosed)
    private val statuses = PaymentStatus.values().toList()
    private val open = listOf(CREATED, PENDING, PROCESSING)

    // ---- non-money events: the table of 00 section 7.2, status by status ----

    @Test
    fun `Started moves CREATED to PENDING and nothing else`() {
        for (s in statuses) {
            val r = decide(attempt(s), order(), Started)
            if (s == CREATED) assertEquals(Move(PENDING, emptyList()), r) else assertEquals(NoOp, r, s.name)
        }
    }

    @Test
    fun `StartFailed moves CREATED to FAILED and closes it`() {
        for (s in statuses) {
            val r = decide(attempt(s), order(), StartFailed)
            if (s == CREATED) assertEquals(Move(FAILED, closed), r) else assertEquals(NoOp, r, s.name)
        }
    }

    @Test
    fun `Pending moves CREATED and PENDING to PROCESSING, never leaves a settled state`() {
        for (s in statuses) {
            val r = decide(attempt(s), order(), Pending)
            if (s == CREATED || s == PENDING) assertEquals(Move(PROCESSING, emptyList()), r, s.name) else assertEquals(NoOp, r, s.name)
        }
    }

    @Test
    fun `Failed Cancelled Expired and Replaced close an open attempt to their own status and leave the order pending`() {
        val cases = listOf(Failed(false) to FAILED, Cancelled to CANCELLED, Replaced to CANCELLED, Expired to EXPIRED)
        for ((event, target) in cases) {
            for (s in statuses) {
                val r = decide(attempt(s), order(), event)
                if (s in open) assertEquals(Move(target, closed), r, "$event on $s") else assertEquals(NoOp, r, "$event on $s")
            }
        }
    }

    @Test
    fun `a final failure after the order window closes the order too (O8), before it the order stays PENDING`() {
        assertEquals(
            Move(FAILED, closed + NotifyOrder(OrderEvent.Fail(OrderActor.GATEWAY))),
            decide(attempt(PENDING), order(windowOver = true), Failed(final = true))
        )
        assertEquals(Move(FAILED, closed), decide(attempt(PENDING), order(windowOver = false), Failed(final = true)))
        assertEquals(Move(FAILED, closed), decide(attempt(PENDING), order(windowOver = true), Failed(final = false)))
        // The order has moved on already: nothing to tell it.
        assertEquals(Move(FAILED, closed), decide(attempt(PENDING), order(OrderStatus.EXPIRED, windowOver = true), Failed(final = true)))
    }

    @Test
    fun `events on an attempt that already SUCCEEDED change nothing`() {
        val done = attempt(SUCCEEDED)
        val all = listOf(
            Started, StartFailed, Pending, Succeeded(1000, eur), NeedsReview(ReviewReason.OTHER), Failed(true), Cancelled, Expired, Replaced
        )
        for (o in OrderStatus.values()) for (e in all) {
            assertEquals(NoOp, decide(done, order(o), e), "$e with order $o")
        }
    }

    // ---- Succeeded: amount check passes, tender matches ----

    @Test
    fun `Succeeded with a matching amount and tender completes a PENDING order (O2) from every open or late attempt status`() {
        for (s in listOf(CREATED, PENDING, PROCESSING, FAILED, CANCELLED, EXPIRED, REVIEW)) {
            assertEquals(
                Move(
                    SUCCEEDED,
                    listOf(RecordPaid, ClearStartPayload, StampClosed, CancelOtherOpenAttempts, NotifyOrder(OrderEvent.Paid(7, OrderActor.GATEWAY)))
                ),
                decide(attempt(s), order(), Succeeded(1000, eur)),
                s.name
            )
        }
    }

    @Test
    fun `a late Succeeded on FAILED CANCELLED or EXPIRED against a still pending order is O2, against a released order O9 LATE`() {
        for (s in listOf(FAILED, CANCELLED, EXPIRED)) {
            for (o in listOf(OrderStatus.EXPIRED, OrderStatus.CANCELLED, OrderStatus.FAILED)) {
                assertEquals(
                    Move(SUCCEEDED, listOf(RecordPaid, ClearStartPayload, StampClosed, NotifyOrder(OrderEvent.LatePayment(7, ReviewReason.LATE)))),
                    decide(attempt(s), order(o), Succeeded(1000, eur)),
                    "$s on order $o"
                )
            }
        }
    }

    @Test
    fun `Succeeded against an order in REVIEW records the payment on the order and alerts the panel`() {
        val expected = Move(SUCCEEDED, listOf(RecordPaid, ClearStartPayload, StampClosed, RecordPaymentOnOrder(7), PanelAlert))
        assertEquals(expected, decide(attempt(PROCESSING), order(OrderStatus.REVIEW), Succeeded(1000, eur)))
        // The order's own attempt (corrected success after an underpaid review) and a different one are both recorded.
        assertEquals(expected, decide(attempt(REVIEW), order(OrderStatus.REVIEW, paymentAttemptId = 7), Succeeded(1000, eur)))
        assertEquals(expected, decide(attempt(PROCESSING), order(OrderStatus.REVIEW, paymentAttemptId = 3), Succeeded(1000, eur)))
    }

    @Test
    fun `money arriving on a REVIEW order is refunded by a later rejection (the order machine sees the recorded amount)`() {
        // NeedsReview(FRAUD_REVIEW) first: no money, the order has no paidAmount.
        val noMoney = OrderState(OrderStatus.REVIEW, reservationState = ReservationState.HELD, paidAmount = null, reviewReason = ReviewReason.FRAUD_REVIEW)
        val before = OrderStateMachine.decide(noMoney, OrderEvent.ReviewRejected(refund = true)) as OrderTransition.Move
        assertFalse(OrderEffect.CreateRefundForPaidAmount in before.effects)
        // The gateway then confirms the payment: the machine tells the service to record it on the order ...
        val paid = decide(attempt(PROCESSING), order(OrderStatus.REVIEW), Succeeded(1000, eur)) as Move
        assertTrue(RecordPaymentOnOrder(7) in paid.effects)
        // ... after which the order carries paidAmount 1000 and the rejection refunds it.
        val after = OrderStateMachine.decide(noMoney.copy(paidAmount = 1000), OrderEvent.ReviewRejected(refund = true)) as OrderTransition.Move
        assertEquals(OrderStatus.CANCELLED, after.to)
        assertTrue(OrderEffect.CreateRefundForPaidAmount in after.effects)
    }

    @Test
    fun `a second Succeeded on an already paid order flags the attempt as duplicate and never touches the order`() {
        for (o in paidOrders) {
            for (s in listOf(CREATED, PENDING, PROCESSING, FAILED, CANCELLED, EXPIRED, REVIEW)) {
                // Another attempt carries the order's payment, or the payment is not recorded at all.
                for (carried in listOf(3L, null)) {
                    val r = decide(attempt(s), order(o, paymentAttemptId = carried), Succeeded(1000, eur)) as Move
                    assertEquals(SUCCEEDED, r.to)
                    assertEquals(listOf(RecordPaid, ClearStartPayload, StampClosed, FlagDuplicate), r.effects, "$s on order $o carried by $carried")
                    assertTrue(r.effects.none { it is NotifyOrder })
                }
            }
        }
    }

    @Test
    fun `the order's own attempt, settled after an accepted review, is not a duplicate`() {
        // O3 (attempt REVIEW, order.paymentId = 7) -> admin accepts (O4, the attempt stays REVIEW) -> the gateway confirms.
        for (o in paidOrders) {
            val r = decide(attempt(REVIEW), order(o, paymentAttemptId = 7), Succeeded(1000, eur)) as Move
            assertEquals(Move(SUCCEEDED, listOf(RecordPaid, ClearStartPayload, StampClosed)), r, o.name)
        }
        // The same attempt id on a paid order is only exempt for the attempt that carries the payment.
        val other = decide(attempt(REVIEW), order(OrderStatus.COMPLETED, paymentAttemptId = 8), Succeeded(1000, eur)) as Move
        assertTrue(FlagDuplicate in other.effects)
    }

    @Test
    fun `money that fails the amount check on a paid order alerts the panel unless it is the order's own attempt`() {
        for (o in paidOrders) {
            assertEquals(
                Move(REVIEW, listOf(RecordPaid, RecordReviewReason(ReviewReason.UNDERPAID), PanelAlert)),
                decide(attempt(PENDING), order(o, paymentAttemptId = 3), Succeeded(900, eur)),
                o.name
            )
            assertEquals(
                Move(REVIEW, listOf(RecordPaid, RecordReviewReason(ReviewReason.UNDERPAID))),
                decide(attempt(PENDING), order(o, paymentAttemptId = 7), Succeeded(900, eur)),
                o.name
            )
        }
    }

    // ---- tender mismatch ----

    @Test
    fun `a superseded attempt that paid but whose tender differs goes SUCCEEDED and the order to REVIEW AMOUNT_MISMATCH with no capture`() {
        // The buyer re-tendered through /pay: the order now wants 800 gateway + 200 credits, the old attempt was 1000 + 0.
        val newTender = order(gateway = 800, credit = 200)
        for (s in listOf(CREATED, PENDING, PROCESSING, CANCELLED, EXPIRED, FAILED)) {
            val r = decide(attempt(s, amount = 1000, credit = 0), newTender, Succeeded(1000, eur)) as Move
            assertEquals(SUCCEEDED, r.to, s.name)
            assertEquals(
                listOf(
                    RecordPaid, ClearStartPayload, StampClosed, CancelOtherOpenAttempts,
                    NotifyOrder(OrderEvent.NeedsReview(ReviewReason.AMOUNT_MISMATCH, 7))
                ),
                r.effects,
                s.name
            )
            assertTrue(r.effects.none { it is NotifyOrder && it.event is OrderEvent.Paid })
        }
    }

    @Test
    fun `tender mismatch on the credit part alone, or on the gateway amount alone, is enough`() {
        val a = attempt(PENDING, amount = 1000, credit = 100)
        assertTrue(PaymentStateMachine.tenderMatches(a, order(gateway = 1000, credit = 100)))
        assertFalse(PaymentStateMachine.tenderMatches(a, order(gateway = 1000, credit = 0)))
        assertFalse(PaymentStateMachine.tenderMatches(a, order(gateway = 900, credit = 100)))
        val r = decide(a, order(gateway = 1000, credit = 0), Succeeded(1000, eur)) as Move
        assertTrue(NotifyOrder(OrderEvent.NeedsReview(ReviewReason.AMOUNT_MISMATCH, 7)) in r.effects)
    }

    @Test
    fun `tender mismatch against a released order is O9 with AMOUNT_MISMATCH and against an order in REVIEW only a record`() {
        val a = attempt(CANCELLED, amount = 1000, credit = 0)
        val r = decide(a, order(OrderStatus.EXPIRED, gateway = 800, credit = 200), Succeeded(1000, eur)) as Move
        assertEquals(
            listOf(RecordPaid, ClearStartPayload, StampClosed, NotifyOrder(OrderEvent.LatePayment(7, ReviewReason.AMOUNT_MISMATCH))),
            r.effects
        )
        val inReview = decide(a, order(OrderStatus.REVIEW, gateway = 800, credit = 200), Succeeded(1000, eur)) as Move
        assertEquals(listOf(RecordPaid, ClearStartPayload, StampClosed, RecordPaymentOnOrder(7), PanelAlert), inReview.effects)
    }

    // ---- amount check (00 section 6.9) ----

    @Test
    fun `checkAmount equal passes, less is UNDERPAID`() {
        val a = attempt(PENDING)
        assertEquals(AmountCheck.Ok, PaymentStateMachine.checkAmount(a, 1000, eur, plain))
        assertEquals(AmountCheck.Failed(ReviewReason.UNDERPAID), PaymentStateMachine.checkAmount(a, 999, eur, plain))
        assertEquals(AmountCheck.Failed(ReviewReason.UNDERPAID), PaymentStateMachine.checkAmount(a, 0, eur, moreOk))
        assertEquals(AmountCheck.Failed(ReviewReason.UNDERPAID), PaymentStateMachine.checkAmount(a, 999, eur, moreOk))
    }

    @Test
    fun `checkAmount more is OVERPAID unless the provider declares buyerMayPayMore`() {
        val a = attempt(PENDING)
        assertEquals(AmountCheck.Failed(ReviewReason.OVERPAID), PaymentStateMachine.checkAmount(a, 1001, eur, plain))
        assertEquals(AmountCheck.OkOverpaid, PaymentStateMachine.checkAmount(a, 1001, eur, moreOk))
        assertEquals(AmountCheck.Ok, PaymentStateMachine.checkAmount(a, 1000, eur, moreOk))
    }

    @Test
    fun `checkAmount a currency mismatch fails first, even with the right number`() {
        val a = attempt(PENDING)
        assertEquals(AmountCheck.Failed(ReviewReason.CURRENCY_MISMATCH), PaymentStateMachine.checkAmount(a, 1000, "USD", plain))
        assertEquals(AmountCheck.Failed(ReviewReason.CURRENCY_MISMATCH), PaymentStateMachine.checkAmount(a, 5000, "USD", moreOk))
        assertEquals(AmountCheck.Failed(ReviewReason.CURRENCY_MISMATCH), PaymentStateMachine.checkAmount(a, 1, "USD", plain))
    }

    @Test
    fun `checkAmount a provider whose price authority is not MARKET is exempt`() {
        val a = attempt(PENDING)
        for (authority in listOf(PriceAuthority.GATEWAY_ADDS_TAX, PriceAuthority.GATEWAY_CATALOG)) {
            val p = ProviderMoneyPolicy(priceAuthority = authority)
            assertEquals(AmountCheck.Ok, PaymentStateMachine.checkAmount(a, 1, eur, p), authority.name)
            assertEquals(AmountCheck.Ok, PaymentStateMachine.checkAmount(a, 99_999, "USD", p), authority.name)
        }
        val r = decide(a, order(), Succeeded(1200, eur), ProviderMoneyPolicy(priceAuthority = PriceAuthority.GATEWAY_ADDS_TAX)) as Move
        assertEquals(SUCCEEDED, r.to)
    }

    @Test
    fun `a failed amount check puts the attempt in REVIEW and the pending order in REVIEW with the matching reason`() {
        val cases = listOf(
            Succeeded(900, eur) to ReviewReason.UNDERPAID,
            Succeeded(1100, eur) to ReviewReason.OVERPAID,
            Succeeded(1000, "USD") to ReviewReason.CURRENCY_MISMATCH
        )
        for ((event, reason) in cases) {
            assertEquals(
                Move(
                    REVIEW,
                    listOf(RecordPaid, RecordReviewReason(reason), CancelOtherOpenAttempts, NotifyOrder(OrderEvent.NeedsReview(reason, 7)))
                ),
                decide(attempt(PROCESSING), order(), event),
                reason.name
            )
        }
    }

    @Test
    fun `a failed amount check against a released order is O9 with the amount reason, against a review order it is recorded, against a paid order no order move`() {
        assertEquals(
            Move(REVIEW, listOf(RecordPaid, RecordReviewReason(ReviewReason.UNDERPAID), NotifyOrder(OrderEvent.LatePayment(7, ReviewReason.UNDERPAID)))),
            decide(attempt(CANCELLED), order(OrderStatus.CANCELLED), Succeeded(900, eur))
        )
        assertEquals(
            Move(REVIEW, listOf(RecordPaid, RecordReviewReason(ReviewReason.UNDERPAID), RecordPaymentOnOrder(7), PanelAlert)),
            decide(attempt(PENDING), order(OrderStatus.REVIEW), Succeeded(900, eur))
        )
        assertEquals(
            Move(REVIEW, listOf(RecordPaid, RecordReviewReason(ReviewReason.OVERPAID), PanelAlert)),
            decide(attempt(PENDING), order(OrderStatus.COMPLETED), Succeeded(1100, eur))
        )
    }

    @Test
    fun `an overpayment by a buyerMayPayMore provider is accepted like an exact payment`() {
        val r = decide(attempt(PENDING), order(), Succeeded(1150, eur), moreOk) as Move
        assertEquals(SUCCEEDED, r.to)
        assertTrue(NotifyOrder(OrderEvent.Paid(7, OrderActor.GATEWAY)) in r.effects)
    }

    // ---- environment mismatch, NeedsReview ----

    @Test
    fun `a success whose stated environment differs from the attempt becomes REVIEW OTHER`() {
        for ((attemptTest, eventTest) in listOf(false to true, true to false)) {
            assertEquals(
                Move(
                    REVIEW,
                    listOf(RecordPaid, RecordReviewReason(ReviewReason.OTHER), CancelOtherOpenAttempts, NotifyOrder(OrderEvent.NeedsReview(ReviewReason.OTHER, 7)))
                ),
                decide(attempt(PENDING, testMode = attemptTest), order(), Succeeded(1000, eur, eventTestMode = eventTest))
            )
        }
        // The same environment, or none stated, is not a mismatch.
        assertEquals(SUCCEEDED, (decide(attempt(PENDING, testMode = true), order(), Succeeded(1000, eur, true)) as Move).to)
        assertEquals(SUCCEEDED, (decide(attempt(PENDING, testMode = true), order(), Succeeded(1000, eur, null)) as Move).to)
        // A NeedsReview with a mismatching environment is reported as OTHER.
        val r = decide(attempt(PENDING, testMode = false), order(), NeedsReview(ReviewReason.FRAUD_REVIEW, eventTestMode = true)) as Move
        assertTrue(RecordReviewReason(ReviewReason.OTHER) in r.effects)
    }

    @Test
    fun `NeedsReview without money on an order already in REVIEW records only the reason`() {
        assertEquals(
            Move(REVIEW, listOf(RecordReviewReason(ReviewReason.FRAUD_REVIEW))),
            decide(attempt(PROCESSING), order(OrderStatus.REVIEW), NeedsReview(ReviewReason.FRAUD_REVIEW))
        )
    }

    @Test
    fun `NeedsReview moves every unsettled attempt to REVIEW with the provider reason, SUCCEEDED and REVIEW are untouched`() {
        for (s in statuses) {
            val r = decide(attempt(s), order(), NeedsReview(ReviewReason.FRAUD_REVIEW))
            if (s == SUCCEEDED || s == REVIEW) assertEquals(NoOp, r, s.name)
            else assertEquals(
                Move(
                    REVIEW,
                    listOf(
                        RecordReviewReason(ReviewReason.FRAUD_REVIEW), CancelOtherOpenAttempts,
                        NotifyOrder(OrderEvent.NeedsReview(ReviewReason.FRAUD_REVIEW, 7))
                    )
                ),
                r,
                s.name
            )
        }
    }

    @Test
    fun `a Succeeded on an attempt already in REVIEW after a failed check still re-evaluates and can settle it`() {
        // 00 section 7.2: Succeeded is applied "from any non-SUCCEEDED state" (a corrected event after a review).
        val r = decide(attempt(REVIEW), order(OrderStatus.REVIEW), Succeeded(1000, eur)) as Move
        assertEquals(SUCCEEDED, r.to)
    }

    // ---- exhaustive Succeeded matrix against an independent oracle ----

    @Test
    fun `every attempt status times order status times tender times amount outcome matches the oracle`() {
        var cases = 0
        // carried: the attempt id the order already carries (the attempt under test is 7).
        for (s in statuses) for (o in OrderStatus.values()) for (tenderOk in listOf(true, false)) for (paid in listOf(900L, 1000L, 1100L)) for (carried in listOf(null, 7L, 8L)) {
            val a = attempt(s, amount = 1000, credit = 0)
            val ord = order(o, gateway = if (tenderOk) 1000 else 600, credit = if (tenderOk) 0 else 400, paymentAttemptId = carried)
            val r = decide(a, ord, Succeeded(paid, eur), plain)
            cases++
            // A SUCCEEDED attempt never changes; a replayed bad payment on an attempt already in REVIEW is a no-op.
            if (s == SUCCEEDED || (s == REVIEW && paid != 1000L)) {
                assertEquals(NoOp, r, "$s/$o/$tenderOk/$paid")
                continue
            }
            r as Move
            if (paid != 1000L) {
                assertEquals(REVIEW, r.to, "$s/$o/$tenderOk/$paid/$carried")
                // Money on an order in REVIEW is recorded on it; a paid order alerts unless the attempt is its own.
                assertEquals(o == OrderStatus.REVIEW, RecordPaymentOnOrder(7) in r.effects, "$s/$o/$paid/$carried record")
                val alert = (o == OrderStatus.REVIEW) || (o in paidOrders && carried != 7L)
                assertEquals(alert, PanelAlert in r.effects, "$s/$o/$paid/$carried alert")
                continue
            }
            assertEquals(SUCCEEDED, r.to, "$s/$o/$tenderOk/$paid/$carried")
            val notify = r.effects.filterIsInstance<NotifyOrder>().map { it.event }
            val expectedNotify: List<OrderEvent> = when (o) {
                OrderStatus.PENDING ->
                    if (tenderOk) listOf(OrderEvent.Paid(7, OrderActor.GATEWAY)) else listOf(OrderEvent.NeedsReview(ReviewReason.AMOUNT_MISMATCH, 7))

                OrderStatus.EXPIRED, OrderStatus.CANCELLED, OrderStatus.FAILED ->
                    listOf(OrderEvent.LatePayment(7, if (tenderOk) ReviewReason.LATE else ReviewReason.AMOUNT_MISMATCH))

                else -> emptyList()
            }
            assertEquals(expectedNotify, notify, "$s/$o/$tenderOk")
            val duplicate = o in paidOrders && carried != 7L
            assertEquals(duplicate, FlagDuplicate in r.effects, "$s/$o/$carried duplicate flag")
            assertEquals(o == OrderStatus.REVIEW, RecordPaymentOnOrder(7) in r.effects, "$s/$o/$carried record on order")
            assertEquals(o == OrderStatus.REVIEW, PanelAlert in r.effects, "$s/$o/$carried alert")
            // Never both: a duplicate does not also move the order.
            assertFalse(duplicate && notify.isNotEmpty())
        }
        assertEquals(8 * 9 * 2 * 3 * 3, cases)
    }
}
