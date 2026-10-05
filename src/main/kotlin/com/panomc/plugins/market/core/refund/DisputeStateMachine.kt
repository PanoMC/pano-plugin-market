package com.panomc.plugins.market.core.refund

import com.panomc.plugins.market.core.order.OrderEvent
import com.panomc.plugins.market.db.model.DisputeRecordStatus
import com.panomc.plugins.market.db.model.DisputeStatus
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.spi.payment.DisputeState
import com.panomc.plugins.market.util.OrderStatus

/** Where a dispute fact comes from: a provider event (`DisputeUpdated`) or the panel (`POST /orders/:id/disputes`, `PUT /disputes/:id`). */
enum class DisputeSource { GATEWAY, PANEL }

/**
 * A dispute fact. [state] is the SPI `DisputeState`: `OPENED` is also what the panel's manual chargeback sends (the row then has
 * `origin = MANUAL`), `WON` / `LOST` / `CLOSED` are also the panel's `PUT {status}`.
 */
data class DisputeEvent(val state: DisputeState, val source: DisputeSource = DisputeSource.GATEWAY)

/**
 * What the machine needs to know about the order under its lock. [disputeStatus] is the order's own column (`NONE, OPEN, WON,
 * LOST`); [otherOpenDisputes] is "another dispute row of this order is in `OPEN`".
 */
data class DisputeOrderFacts(
    val status: OrderStatus,
    val disputeStatus: DisputeStatus = DisputeStatus.NONE,
    val otherOpenDisputes: Boolean = false
)

/** What the service writes besides the new status, in the same transaction (value objects: decisions compare structurally). */
sealed interface DisputeEffect {
    /** `openedAt` (the event's time, else now); a re-opened row also clears `resolvedAt`. */
    data object StampOpened : DisputeEffect

    /** `resolvedAt = now`. */
    data object StampResolved : DisputeEffect

    /** Feed this event to `OrderStateMachine.decide` in the same transaction: [OrderEvent.DisputeOpened] is O11, [OrderEvent.DisputeWon] O12. */
    data class NotifyOrder(val event: OrderEvent) : DisputeEffect

    /** `market_order.disputeStatus`. */
    data class SetOrderDisputeStatus(val status: DisputeStatus) : DisputeEffect

    /** A `market_order_event` row. */
    data class Timeline(val type: OrderEventType) : DisputeEffect

    /** Panel alert; [code] is `DISPUTE_INQUIRY` or `DISPUTE_ON_UNPAID_ORDER`. */
    data class PanelAlert(val code: String) : DisputeEffect

    /** O11 (21 section 5.2 step 4): refunds of the order in `REQUESTED` are cancelled (`RefundEvent.ChargebackOpened`). */
    data object CancelUnsentRefunds : DisputeEffect

    /** O12 (21 section 5.3): the block rows created by this order's chargeback are removed (11 section 10). */
    data object RemoveChargebackBlocks : DisputeEffect
}

sealed class DisputeTransition {
    /** A panel action that the state does not allow: 409 `INVALID_STATE` (or the order machine's `INVALID_ORDER_TRANSITION`). */
    data class Rejected(val errorCode: String) : DisputeTransition()

    /** A replay, an out-of-order event, or a combination the table does not list: nothing changes. */
    data object NoOp : DisputeTransition()

    /** [from] is `null` when the row does not exist yet (insert it in [to]); else one conditional status update. */
    data class Move(val from: DisputeRecordStatus?, val to: DisputeRecordStatus, val effects: List<DisputeEffect>) : DisputeTransition()
}

/**
 * Dispute state machine (21 section 5.1, 00 section 7.1 O11 / O12), pure.
 *
 * | From | Trigger | To | Order |
 * |---|---|---|---|
 * | - | `INQUIRY` | `INQUIRY` | none: timeline `DISPUTE_INQUIRY` and a panel alert. **Never revokes, blocks or bans** |
 * | -, `INQUIRY` | `OPENED`, panel manual chargeback | `OPEN` | **O11** |
 * | `INQUIRY` | `CLOSED`, `WON` | `CLOSED` | none |
 * | `OPEN` | `WON`, `CLOSED` (also panel) | `WON`, `CLOSED` | **O12** (a closed open dispute means the merchant kept the money) |
 * | `OPEN` | `LOST` (also panel) | `LOST` | `disputeStatus = LOST`, the order stays `CHARGEBACK` (terminal) |
 * | `WON`, `LOST`, `CLOSED` | the same state again | unchanged | none |
 * | `WON`, `CLOSED` | `OPENED` (same id, second chargeback cycle) | `OPEN` | O11 again only if the order is not `CHARGEBACK` |
 *
 * Only one O11 can be pending per order: an `OPENED` on an order that is already `CHARGEBACK` records the row and a timeline
 * entry, nothing else. O12 runs only when this dispute holds the chargeback: the order is `CHARGEBACK`, its `disputeStatus` is
 * `OPEN` (a lost dispute is final) and no other dispute row of the order is still open.
 *
 * Decided here (not in the table). A provider event the table does not list is a [DisputeTransition.NoOp]; the panel gets
 * [DisputeTransition.Rejected] `INVALID_STATE`, except for the same state again (a replayed `PUT`), which is a no-op. Two
 * gaps are closed on the side of the money: a provider `LOST` for a row that was never opened (`INQUIRY` or no row) is an `OPEN`
 * followed by `LOST` in one step, so O11 still runs (the money is gone, ignoring the event would leave the goods with the buyer);
 * a provider `WON` / `CLOSED` for no row at all inserts the row in that state and touches nothing else (nothing was ever taken).
 * An `OPENED` for an order that is not paid ([OrderStatus.COMPLETED], [OrderStatus.PARTIALLY_REFUNDED], [OrderStatus.REFUNDED])
 * and not already `CHARGEBACK` records the row, a timeline entry and the alert `DISPUTE_ON_UNPAID_ORDER` for a provider event, and is
 * refused with `INVALID_ORDER_TRANSITION` for the panel (O11 has no source state there).
 */
object DisputeStateMachine {
    const val INVALID_STATE = "INVALID_STATE"
    const val INVALID_ORDER_TRANSITION = "INVALID_ORDER_TRANSITION"
    const val ALERT_INQUIRY = "DISPUTE_INQUIRY"
    const val ALERT_UNPAID_ORDER = "DISPUTE_ON_UNPAID_ORDER"

    private val DISPUTABLE = setOf(OrderStatus.COMPLETED, OrderStatus.PARTIALLY_REFUNDED, OrderStatus.REFUNDED)

    /**
     * The row a provider event or panel call leads to. [current] is `null` for a row that does not exist yet.
     * A same-state replay of a resolved row is a no-op for both sources.
     */
    fun decide(current: DisputeRecordStatus?, event: DisputeEvent, order: DisputeOrderFacts): DisputeTransition {
        val panel = event.source == DisputeSource.PANEL
        fun refuse(): DisputeTransition = if (panel) DisputeTransition.Rejected(INVALID_STATE) else DisputeTransition.NoOp
        return when (event.state) {
            DisputeState.INQUIRY -> when {
                panel -> DisputeTransition.Rejected(INVALID_STATE)
                current == null -> DisputeTransition.Move(
                    null, DisputeRecordStatus.INQUIRY,
                    listOf(DisputeEffect.StampOpened, DisputeEffect.Timeline(OrderEventType.DISPUTE_INQUIRY), DisputeEffect.PanelAlert(ALERT_INQUIRY))
                )
                else -> DisputeTransition.NoOp
            }

            DisputeState.OPENED -> when (current) {
                null, DisputeRecordStatus.INQUIRY, DisputeRecordStatus.WON, DisputeRecordStatus.CLOSED -> opened(current, order, panel)
                DisputeRecordStatus.OPEN, DisputeRecordStatus.LOST -> refuse()
            }

            DisputeState.WON, DisputeState.CLOSED -> {
                val to = if (event.state == DisputeState.WON) DisputeRecordStatus.WON else DisputeRecordStatus.CLOSED
                when (current) {
                    null -> if (panel) DisputeTransition.Rejected(INVALID_STATE) else DisputeTransition.Move(null, to, closedWithoutOpening())
                    DisputeRecordStatus.INQUIRY -> DisputeTransition.Move(current, DisputeRecordStatus.CLOSED, closedInquiry())
                    DisputeRecordStatus.OPEN -> DisputeTransition.Move(current, to, resolvedEffects(order))
                    to -> DisputeTransition.NoOp
                    else -> refuse()
                }
            }

            DisputeState.LOST -> when (current) {
                null -> if (panel) DisputeTransition.Rejected(INVALID_STATE) else openedThenLost(null, order)
                DisputeRecordStatus.INQUIRY -> if (panel) DisputeTransition.Rejected(INVALID_STATE) else openedThenLost(current, order)
                DisputeRecordStatus.OPEN -> DisputeTransition.Move(current, DisputeRecordStatus.LOST, lostEffects())
                DisputeRecordStatus.LOST -> DisputeTransition.NoOp
                else -> refuse()
            }
        }
    }

    // ---------------------------------------------------------------- OPEN (O11)

    private fun opened(current: DisputeRecordStatus?, order: DisputeOrderFacts, panel: Boolean): DisputeTransition {
        val ready = order.status in DISPUTABLE
        if (!ready && order.status != OrderStatus.CHARGEBACK && panel) return DisputeTransition.Rejected(INVALID_ORDER_TRANSITION)
        return DisputeTransition.Move(current, DisputeRecordStatus.OPEN, openedEffects(order))
    }

    /** O11 when the order can take it; a second dispute on a `CHARGEBACK` order, or one on an unpaid order, only records. */
    private fun openedEffects(order: DisputeOrderFacts): List<DisputeEffect> = buildList {
        add(DisputeEffect.StampOpened)
        when {
            order.status in DISPUTABLE -> {
                add(DisputeEffect.NotifyOrder(OrderEvent.DisputeOpened))
                add(DisputeEffect.SetOrderDisputeStatus(DisputeStatus.OPEN))
                add(DisputeEffect.Timeline(OrderEventType.DISPUTE_OPENED))
                add(DisputeEffect.CancelUnsentRefunds)
            }

            order.status == OrderStatus.CHARGEBACK -> add(DisputeEffect.Timeline(OrderEventType.DISPUTE_OPENED))

            else -> {
                add(DisputeEffect.Timeline(OrderEventType.DISPUTE_OPENED))
                add(DisputeEffect.PanelAlert(ALERT_UNPAID_ORDER))
            }
        }
    }

    // ---------------------------------------------------------------- resolved

    /** A resolved dispute that was never `OPEN` and has no row yet: nothing was taken, nothing is given back. */
    private fun closedWithoutOpening(): List<DisputeEffect> =
        listOf(DisputeEffect.StampOpened, DisputeEffect.StampResolved, DisputeEffect.Timeline(OrderEventType.DISPUTE_CLOSED))

    /** An inquiry that ended (`CLOSED` / `WON`): informational, the order is untouched. */
    private fun closedInquiry(): List<DisputeEffect> =
        listOf(DisputeEffect.StampResolved, DisputeEffect.Timeline(OrderEventType.DISPUTE_CLOSED))

    /** `OPEN` to `WON` / `CLOSED`: O12 when this dispute holds the chargeback (21 section 5.3). */
    private fun resolvedEffects(order: DisputeOrderFacts): List<DisputeEffect> = buildList {
        add(DisputeEffect.StampResolved)
        if (holdsChargeback(order)) {
            add(DisputeEffect.NotifyOrder(OrderEvent.DisputeWon))
            add(DisputeEffect.SetOrderDisputeStatus(DisputeStatus.WON))
            add(DisputeEffect.RemoveChargebackBlocks)
        }
        add(DisputeEffect.Timeline(OrderEventType.DISPUTE_CLOSED))
    }

    private fun holdsChargeback(order: DisputeOrderFacts) =
        order.status == OrderStatus.CHARGEBACK && order.disputeStatus == DisputeStatus.OPEN && !order.otherOpenDisputes

    /** `OPEN` to `LOST`: the order stays `CHARGEBACK`, terminal. */
    private fun lostEffects(): List<DisputeEffect> = listOf(
        DisputeEffect.StampResolved,
        DisputeEffect.SetOrderDisputeStatus(DisputeStatus.LOST),
        DisputeEffect.Timeline(OrderEventType.DISPUTE_CLOSED)
    )

    /** A provider `LOST` for a row that was never opened: O11 first (when the order can take it), then the loss. */
    private fun openedThenLost(current: DisputeRecordStatus?, order: DisputeOrderFacts): DisputeTransition {
        val effects = buildList {
            add(DisputeEffect.StampOpened)
            if (order.status in DISPUTABLE) {
                add(DisputeEffect.NotifyOrder(OrderEvent.DisputeOpened))
                add(DisputeEffect.Timeline(OrderEventType.DISPUTE_OPENED))
                add(DisputeEffect.CancelUnsentRefunds)
            } else if (order.status != OrderStatus.CHARGEBACK) {
                add(DisputeEffect.Timeline(OrderEventType.DISPUTE_OPENED))
                add(DisputeEffect.PanelAlert(ALERT_UNPAID_ORDER))
            } else {
                add(DisputeEffect.Timeline(OrderEventType.DISPUTE_OPENED))
            }
            add(DisputeEffect.StampResolved)
            add(DisputeEffect.SetOrderDisputeStatus(DisputeStatus.LOST))
            add(DisputeEffect.Timeline(OrderEventType.DISPUTE_CLOSED))
        }
        return DisputeTransition.Move(current, DisputeRecordStatus.LOST, effects)
    }
}
