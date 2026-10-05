package com.panomc.plugins.market.core.refund

import com.panomc.plugins.market.core.order.OrderEvent
import com.panomc.plugins.market.core.refund.DisputeEffect.CancelUnsentRefunds
import com.panomc.plugins.market.core.refund.DisputeEffect.NotifyOrder
import com.panomc.plugins.market.core.refund.DisputeEffect.PanelAlert
import com.panomc.plugins.market.core.refund.DisputeEffect.RemoveChargebackBlocks
import com.panomc.plugins.market.core.refund.DisputeEffect.SetOrderDisputeStatus
import com.panomc.plugins.market.core.refund.DisputeEffect.StampOpened
import com.panomc.plugins.market.core.refund.DisputeEffect.StampResolved
import com.panomc.plugins.market.core.refund.DisputeEffect.Timeline
import com.panomc.plugins.market.core.refund.DisputeTransition.Move
import com.panomc.plugins.market.core.refund.DisputeTransition.NoOp
import com.panomc.plugins.market.core.refund.DisputeTransition.Rejected
import com.panomc.plugins.market.db.model.DisputeRecordStatus
import com.panomc.plugins.market.db.model.DisputeRecordStatus.CLOSED
import com.panomc.plugins.market.db.model.DisputeRecordStatus.INQUIRY
import com.panomc.plugins.market.db.model.DisputeRecordStatus.LOST
import com.panomc.plugins.market.db.model.DisputeRecordStatus.OPEN
import com.panomc.plugins.market.db.model.DisputeRecordStatus.WON
import com.panomc.plugins.market.db.model.DisputeStatus
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.spi.payment.DisputeState
import com.panomc.plugins.market.util.OrderStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `DisputeStateMachine` (21 section 5.1, 00 section 7.1 O11 / O12): the table, V-07 / RD-D9 / RD-D10 and a sweep of every input (RD-U3). */
class DisputeStateMachineTest {
    private val paid = DisputeOrderFacts(OrderStatus.COMPLETED)
    private val chargeback = DisputeOrderFacts(OrderStatus.CHARGEBACK, DisputeStatus.OPEN)
    private val o11 = NotifyOrder(OrderEvent.DisputeOpened)
    private val o12 = NotifyOrder(OrderEvent.DisputeWon)

    private fun gw(state: DisputeState) = DisputeEvent(state, DisputeSource.GATEWAY)
    private fun panel(state: DisputeState) = DisputeEvent(state, DisputeSource.PANEL)

    private val opened = listOf(
        StampOpened, o11, SetOrderDisputeStatus(DisputeStatus.OPEN), Timeline(OrderEventType.DISPUTE_OPENED), CancelUnsentRefunds
    )
    private val wonByHolder = listOf(
        StampResolved, o12, SetOrderDisputeStatus(DisputeStatus.WON), RemoveChargebackBlocks, Timeline(OrderEventType.DISPUTE_CLOSED)
    )
    private val lost = listOf(StampResolved, SetOrderDisputeStatus(DisputeStatus.LOST), Timeline(OrderEventType.DISPUTE_CLOSED))

    // ---------------------------------------------------------------- the table of 21 section 5.1

    @Test
    fun `an inquiry is recorded with a timeline entry and an alert and touches nothing else`() {
        val r = DisputeStateMachine.decide(null, gw(DisputeState.INQUIRY), paid)
        assertEquals(
            Move(null, INQUIRY, listOf(StampOpened, Timeline(OrderEventType.DISPUTE_INQUIRY), PanelAlert("DISPUTE_INQUIRY"))),
            r
        )
        // the same inquiry again, or an inquiry on a row that moved on: nothing
        for (s in listOf(INQUIRY, OPEN, WON, LOST, CLOSED)) assertEquals(NoOp, DisputeStateMachine.decide(s, gw(DisputeState.INQUIRY), paid), s.name)
    }

    @Test
    fun `OPENED from nothing or an inquiry opens the dispute and runs O11`() {
        assertEquals(Move(null, OPEN, opened), DisputeStateMachine.decide(null, gw(DisputeState.OPENED), paid))
        assertEquals(Move(INQUIRY, OPEN, opened), DisputeStateMachine.decide(INQUIRY, gw(DisputeState.OPENED), paid))
        // the panel's manual chargeback is the same transition
        assertEquals(Move(null, OPEN, opened), DisputeStateMachine.decide(null, panel(DisputeState.OPENED), paid))
        for (status in listOf(OrderStatus.COMPLETED, OrderStatus.PARTIALLY_REFUNDED, OrderStatus.REFUNDED)) {
            val r = DisputeStateMachine.decide(null, gw(DisputeState.OPENED), DisputeOrderFacts(status)) as Move
            assertTrue(o11 in r.effects, status.name)
        }
    }

    @Test
    fun `an inquiry that ends is closed without touching the order`() {
        val closedInquiry = listOf(StampResolved, Timeline(OrderEventType.DISPUTE_CLOSED))
        for (state in listOf(DisputeState.CLOSED, DisputeState.WON)) {
            assertEquals(Move(INQUIRY, CLOSED, closedInquiry), DisputeStateMachine.decide(INQUIRY, gw(state), paid), state.name)
            assertEquals(Move(INQUIRY, CLOSED, closedInquiry), DisputeStateMachine.decide(INQUIRY, panel(state), paid), state.name)
        }
    }

    @Test
    fun `an open dispute that is won or closed runs O12 when it holds the chargeback`() {
        assertEquals(Move(OPEN, WON, wonByHolder), DisputeStateMachine.decide(OPEN, gw(DisputeState.WON), chargeback))
        assertEquals(Move(OPEN, CLOSED, wonByHolder), DisputeStateMachine.decide(OPEN, gw(DisputeState.CLOSED), chargeback))
        assertEquals(Move(OPEN, WON, wonByHolder), DisputeStateMachine.decide(OPEN, panel(DisputeState.WON), chargeback))
        assertEquals(Move(OPEN, CLOSED, wonByHolder), DisputeStateMachine.decide(OPEN, panel(DisputeState.CLOSED), chargeback))
    }

    @Test
    fun `an open dispute that is lost keeps the order in CHARGEBACK and only sets disputeStatus LOST`() {
        assertEquals(Move(OPEN, LOST, lost), DisputeStateMachine.decide(OPEN, gw(DisputeState.LOST), chargeback))
        assertEquals(Move(OPEN, LOST, lost), DisputeStateMachine.decide(OPEN, panel(DisputeState.LOST), chargeback))
        val effects = (DisputeStateMachine.decide(OPEN, gw(DisputeState.LOST), chargeback) as Move).effects
        assertFalse(effects.any { it is NotifyOrder }, "the order is not moved by a lost dispute")
    }

    @Test
    fun `a resolved row stays as it is when the same state comes again`() {
        val pairs = listOf(WON to DisputeState.WON, LOST to DisputeState.LOST, CLOSED to DisputeState.CLOSED)
        for ((status, state) in pairs) {
            for (order in listOf(paid, chargeback, DisputeOrderFacts(OrderStatus.CHARGEBACK, DisputeStatus.LOST))) {
                assertEquals(NoOp, DisputeStateMachine.decide(status, gw(state), order), "$status gateway")
                assertEquals(NoOp, DisputeStateMachine.decide(status, panel(state), order), "$status panel replay")
            }
        }
    }

    @Test
    fun `a re-opened dispute of the same id runs O11 again only if the order is not CHARGEBACK`() {
        for (s in listOf(WON, CLOSED)) {
            assertEquals(Move(s, OPEN, opened), DisputeStateMachine.decide(s, gw(DisputeState.OPENED), paid), s.name)
            // the order is still CHARGEBACK (another dispute holds it): the row opens, the order is not touched twice
            val r = DisputeStateMachine.decide(s, gw(DisputeState.OPENED), chargeback) as Move
            assertEquals(OPEN, r.to)
            assertEquals(listOf(StampOpened, Timeline(OrderEventType.DISPUTE_OPENED)), r.effects)
        }
        // a lost dispute is final
        assertEquals(NoOp, DisputeStateMachine.decide(LOST, gw(DisputeState.OPENED), paid))
        assertEquals(NoOp, DisputeStateMachine.decide(OPEN, gw(DisputeState.OPENED), paid))
    }

    // ---------------------------------------------------------------- order guards

    @Test
    fun `a second open dispute on a CHARGEBACK order is a row and a timeline entry, nothing else`() {
        val r = DisputeStateMachine.decide(null, gw(DisputeState.OPENED), chargeback)
        assertEquals(Move(null, OPEN, listOf(StampOpened, Timeline(OrderEventType.DISPUTE_OPENED))), r)
        val panelOpen = DisputeStateMachine.decide(null, panel(DisputeState.OPENED), chargeback)
        assertEquals(r, panelOpen)
    }

    @Test
    fun `O12 only runs for the dispute that holds the chargeback`() {
        // the order is not CHARGEBACK (the dispute was recorded on an unpaid order, or O12 already ran)
        val notHeld = listOf(
            DisputeOrderFacts(OrderStatus.COMPLETED),
            DisputeOrderFacts(OrderStatus.CHARGEBACK, DisputeStatus.LOST), // a lost dispute is final
            DisputeOrderFacts(OrderStatus.CHARGEBACK, DisputeStatus.WON),
            DisputeOrderFacts(OrderStatus.CHARGEBACK, DisputeStatus.OPEN, otherOpenDisputes = true) // another one is still open
        )
        for (o in notHeld) {
            val r = DisputeStateMachine.decide(OPEN, gw(DisputeState.WON), o) as Move
            assertEquals(WON, r.to)
            assertEquals(listOf(StampResolved, Timeline(OrderEventType.DISPUTE_CLOSED)), r.effects, o.toString())
        }
    }

    @Test
    fun `an opened dispute on an unpaid order is recorded with an alert, and the panel may not open it`() {
        for (status in listOf(OrderStatus.PENDING, OrderStatus.REVIEW, OrderStatus.CANCELLED, OrderStatus.EXPIRED, OrderStatus.FAILED)) {
            val order = DisputeOrderFacts(status)
            assertEquals(
                Move(null, OPEN, listOf(StampOpened, Timeline(OrderEventType.DISPUTE_OPENED), PanelAlert("DISPUTE_ON_UNPAID_ORDER"))),
                DisputeStateMachine.decide(null, gw(DisputeState.OPENED), order), status.name
            )
            assertEquals(Rejected("INVALID_ORDER_TRANSITION"), DisputeStateMachine.decide(null, panel(DisputeState.OPENED), order), status.name)
        }
    }

    // ---------------------------------------------------------------- gaps closed on the side of the money

    @Test
    fun `a provider LOST for a row that was never opened opens it first, so O11 still runs`() {
        val expected = listOf(
            StampOpened, o11, Timeline(OrderEventType.DISPUTE_OPENED), CancelUnsentRefunds,
            StampResolved, SetOrderDisputeStatus(DisputeStatus.LOST), Timeline(OrderEventType.DISPUTE_CLOSED)
        )
        assertEquals(Move(null, LOST, expected), DisputeStateMachine.decide(null, gw(DisputeState.LOST), paid))
        assertEquals(Move(INQUIRY, LOST, expected), DisputeStateMachine.decide(INQUIRY, gw(DisputeState.LOST), paid))
        // on an order that already is CHARGEBACK the order machine is not asked again
        val again = DisputeStateMachine.decide(null, gw(DisputeState.LOST), chargeback) as Move
        assertFalse(again.effects.any { it is NotifyOrder })
        assertTrue(SetOrderDisputeStatus(DisputeStatus.LOST) in again.effects)
        // on an unpaid order: the alert
        val unpaid = DisputeStateMachine.decide(null, gw(DisputeState.LOST), DisputeOrderFacts(OrderStatus.PENDING)) as Move
        assertTrue(PanelAlert("DISPUTE_ON_UNPAID_ORDER") in unpaid.effects)
        assertFalse(unpaid.effects.any { it is NotifyOrder })
    }

    @Test
    fun `a provider WON or CLOSED for no row inserts it resolved and changes nothing else`() {
        for ((state, to) in listOf(DisputeState.WON to WON, DisputeState.CLOSED to CLOSED)) {
            assertEquals(
                Move(null, to, listOf(StampOpened, StampResolved, Timeline(OrderEventType.DISPUTE_CLOSED))),
                DisputeStateMachine.decide(null, gw(state), paid), state.name
            )
        }
    }

    @Test
    fun `the panel cannot do what the table does not allow, a provider is simply ignored`() {
        val unlisted = listOf(
            Triple(OPEN, DisputeState.OPENED, true), Triple(LOST, DisputeState.OPENED, true), Triple(WON, DisputeState.LOST, true),
            Triple(CLOSED, DisputeState.WON, true), Triple(WON, DisputeState.CLOSED, true), Triple(LOST, DisputeState.WON, true),
            Triple(INQUIRY, DisputeState.LOST, true), Triple(INQUIRY, DisputeState.INQUIRY, true), Triple(OPEN, DisputeState.INQUIRY, true)
        )
        for ((status, state, _) in unlisted) {
            assertEquals(Rejected("INVALID_STATE"), DisputeStateMachine.decide(status, panel(state), chargeback), "panel $status $state")
        }
        // for the provider: no change for the same unlisted pairs except the LOST-on-inquiry gap, which is closed
        for ((status, state, _) in unlisted) {
            if (status == INQUIRY && state == DisputeState.LOST) continue
            assertEquals(NoOp, DisputeStateMachine.decide(status, gw(state), chargeback), "gateway $status $state")
        }
        // a panel PUT without a row, a panel inquiry
        assertEquals(Rejected("INVALID_STATE"), DisputeStateMachine.decide(null, panel(DisputeState.WON), paid))
        assertEquals(Rejected("INVALID_STATE"), DisputeStateMachine.decide(null, panel(DisputeState.LOST), paid))
        assertEquals(Rejected("INVALID_STATE"), DisputeStateMachine.decide(null, panel(DisputeState.INQUIRY), paid))
    }

    // ---------------------------------------------------------------- V-07, RD-D9, RD-D10: histories with the order following along

    /** Applies the order-side effects a transition names, the way the service would, and counts O11 / O12. */
    private class World(var row: DisputeRecordStatus?, var order: DisputeOrderFacts) {
        var o11 = 0
        var o12 = 0
        var blockRemovals = 0
        var alerts = 0

        fun apply(event: DisputeEvent): DisputeTransition {
            val t = DisputeStateMachine.decide(row, event, order)
            if (t is Move) {
                for (e in t.effects) when (e) {
                    is NotifyOrder -> when (e.event) {
                        OrderEvent.DisputeOpened -> { o11++; order = order.copy(status = OrderStatus.CHARGEBACK, disputeStatus = DisputeStatus.OPEN) }
                        OrderEvent.DisputeWon -> { o12++; order = order.copy(status = OrderStatus.COMPLETED, disputeStatus = DisputeStatus.WON) }
                        else -> error("unexpected order event ${e.event}")
                    }
                    is SetOrderDisputeStatus -> order = order.copy(disputeStatus = e.status)
                    RemoveChargebackBlocks -> blockRemovals++
                    is PanelAlert -> alerts++
                    else -> Unit
                }
                row = t.to
            }
            return t
        }
    }

    @Test
    fun `V-07 an inquiry, then OPENED, then WON delivered twice gives one O11 and one O12`() {
        val w = World(null, paid)
        w.apply(gw(DisputeState.INQUIRY))
        assertEquals(INQUIRY, w.row)
        assertEquals(0, w.o11, "an inquiry never runs O11")
        assertEquals(paid, w.order)
        assertEquals(1, w.alerts)

        w.apply(gw(DisputeState.OPENED))
        assertEquals(OPEN, w.row)
        assertEquals(1, w.o11)
        assertEquals(OrderStatus.CHARGEBACK, w.order.status)

        assertEquals(NoOp, w.apply(gw(DisputeState.OPENED)), "a repeated OPENED")
        w.apply(gw(DisputeState.WON))
        assertEquals(NoOp, w.apply(gw(DisputeState.WON)), "a repeated WON")
        assertEquals(WON, w.row)
        assertEquals(1, w.o11)
        assertEquals(1, w.o12)
        assertEquals(1, w.blockRemovals, "the block rows are removed once")
        assertEquals(OrderStatus.COMPLETED, w.order.status)
        assertEquals(DisputeStatus.WON, w.order.disputeStatus)
    }

    @Test
    fun `RD-D10 OPENED twice then WON twice without a dispute id is one row, one O11, one O12`() {
        // the service resolves an id-less event to the order's single INQUIRY / OPEN row, so `row` is that one row throughout
        val w = World(null, paid)
        w.apply(gw(DisputeState.OPENED))
        w.apply(gw(DisputeState.OPENED))
        w.apply(gw(DisputeState.WON))
        w.apply(gw(DisputeState.WON))
        assertEquals(WON, w.row)
        assertEquals(1, w.o11)
        assertEquals(1, w.o12)
    }

    @Test
    fun `RD-D9 an inquiry alone leaves the order unchanged and plans nothing`() {
        val t = DisputeStateMachine.decide(null, gw(DisputeState.INQUIRY), paid) as Move
        assertEquals(INQUIRY, t.to)
        assertFalse(t.effects.any { it is NotifyOrder || it is CancelUnsentRefunds || it is SetOrderDisputeStatus || it is RemoveChargebackBlocks })
    }

    @Test
    fun `a second chargeback cycle after a win opens again and loses for good`() {
        val w = World(null, paid)
        w.apply(gw(DisputeState.OPENED))
        w.apply(gw(DisputeState.WON))
        w.apply(gw(DisputeState.OPENED)) // re-opened
        assertEquals(OPEN, w.row)
        assertEquals(2, w.o11)
        w.apply(gw(DisputeState.LOST))
        assertEquals(LOST, w.row)
        assertEquals(OrderStatus.CHARGEBACK, w.order.status)
        assertEquals(DisputeStatus.LOST, w.order.disputeStatus)
        // nothing moves a lost dispute any more, not even a later WON of a replayed event
        assertEquals(NoOp, w.apply(gw(DisputeState.WON)))
        assertEquals(1, w.o12)
        assertEquals(OrderStatus.CHARGEBACK, w.order.status)
    }

    @Test
    fun `two disputes on one order, the second one won while the first is lost, never restores the order`() {
        // first dispute opens and is lost: the order is CHARGEBACK with disputeStatus LOST
        val w1 = World(null, paid)
        w1.apply(gw(DisputeState.OPENED))
        w1.apply(gw(DisputeState.LOST))
        // a second dispute row (own id) opened while the order is CHARGEBACK: recorded only; its WON must not run O12
        val second = World(null, w1.order)
        second.apply(gw(DisputeState.OPENED))
        assertEquals(0, second.o11)
        second.apply(gw(DisputeState.WON))
        assertEquals(0, second.o12)
        assertEquals(OrderStatus.CHARGEBACK, second.order.status)
        assertEquals(DisputeStatus.LOST, second.order.disputeStatus)
    }

    // ---------------------------------------------------------------- the sweep

    @Test
    fun `every input keeps the invariants of the table`() {
        val currents = listOf<DisputeRecordStatus?>(null) + DisputeRecordStatus.values().toList()
        val facts = OrderStatus.values().flatMap { st ->
            DisputeStatus.values().flatMap { ds -> listOf(false, true).map { other -> DisputeOrderFacts(st, ds, other) } }
        }
        var inputs = 0
        var moves = 0
        for (current in currents) for (state in DisputeState.values()) for (source in DisputeSource.values()) for (order in facts) {
            inputs++
            val event = DisputeEvent(state, source)
            val t = DisputeStateMachine.decide(current, event, order)
            val tag = "$current $event $order"
            val paidOrder = order.status in setOf(OrderStatus.COMPLETED, OrderStatus.PARTIALLY_REFUNDED, OrderStatus.REFUNDED)
            when (t) {
                is Rejected -> {
                    assertEquals(DisputeSource.PANEL, source, "only the panel is refused: $tag")
                    assertTrue(t.errorCode == "INVALID_STATE" || t.errorCode == "INVALID_ORDER_TRANSITION", tag)
                }
                NoOp -> Unit
                is Move -> {
                    moves++
                    assertEquals(current, t.from, tag)
                    val notified = t.effects.filterIsInstance<NotifyOrder>().map { it.event }
                    // an inquiry never reaches the order: no O11, no revoke, no block, no refund cancel
                    if (state == DisputeState.INQUIRY) {
                        assertTrue(notified.isEmpty() && CancelUnsentRefunds !in t.effects && t.to == INQUIRY, tag)
                    }
                    // O11 only for a paid order and only when the row ends OPEN (or LOST by the gap rule); never twice per order
                    if (OrderEvent.DisputeOpened in notified) {
                        assertTrue(paidOrder, tag)
                        assertTrue(t.to == OPEN || t.to == LOST, tag)
                        assertTrue(state == DisputeState.OPENED || state == DisputeState.LOST, tag)
                        assertTrue(CancelUnsentRefunds in t.effects, tag)
                        assertEquals(1, notified.size, tag)
                    }
                    if (CancelUnsentRefunds in t.effects) assertTrue(OrderEvent.DisputeOpened in notified, tag)
                    // O12 only OPEN -> WON / CLOSED of the holder
                    if (OrderEvent.DisputeWon in notified) {
                        assertEquals(OPEN, current, tag)
                        assertTrue(t.to == WON || t.to == CLOSED, tag)
                        assertEquals(OrderStatus.CHARGEBACK, order.status, tag)
                        assertEquals(DisputeStatus.OPEN, order.disputeStatus, tag)
                        assertFalse(order.otherOpenDisputes, tag)
                        assertTrue(RemoveChargebackBlocks in t.effects, tag)
                    }
                    if (RemoveChargebackBlocks in t.effects) assertTrue(OrderEvent.DisputeWon in notified, tag)
                    // a resolved row is stamped; an open one is not
                    if (t.to == WON || t.to == LOST || t.to == CLOSED) assertTrue(StampResolved in t.effects, tag)
                    if (t.to == OPEN) assertTrue(StampOpened in t.effects && StampResolved !in t.effects, tag)
                    // a lost dispute never moves again, whoever asks
                    assertTrue(current != LOST, tag)
                }
            }
            // a lost row is final for every input
            if (current == LOST) assertTrue(t !is Move, tag)
        }
        assertEquals(6 * 5 * 2 * 9 * 4 * 2, inputs)
        assertTrue(moves > 500, "only $moves moves in the sweep")
    }
}
