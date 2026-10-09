package com.panomc.plugins.market.service

import com.panomc.platform.model.Error
import com.panomc.plugins.market.core.order.OrderActor
import com.panomc.plugins.market.core.order.OrderEvent
import com.panomc.plugins.market.core.order.OrderStateMachine
import com.panomc.plugins.market.core.order.OrderTransition
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderEventDao
import com.panomc.plugins.market.db.dao.MarketPaymentDao
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderEvent
import com.panomc.plugins.market.db.model.OrderActorType
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.db.tx.OrderChild
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.error.InvalidCoupon
import com.panomc.plugins.market.error.InvalidCreatorCode
import com.panomc.plugins.market.error.InvalidGiftCode
import com.panomc.plugins.market.error.InvalidOrderTransition
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.json.JsonObject

/** `decision` of `POST /orders/:id/review` (04 section 7). */
enum class ReviewDecision { ACCEPT, REJECT }

/** The answer of [OrderReviewService.review] / [OrderReviewService.setStatus]: the order as it is now and whether this call moved it. */
class OrderChange(val order: MarketOrder, val from: OrderStatus, val moved: Boolean)

/**
 * A re-reserve that a checkout code refused (06 section 7.4: a coupon, creator code or gift of the released order is gone or used up). The code and
 * extras are the checkout error's; the HTTP status is 409, because the order is not wrong, the world moved (04 section 7 `POST /orders/:id/review`).
 */
class ReReserveRefused(private val inner: Error) : Error("RE_RESERVE_REFUSED", 409) {
    override fun encode(extras: Map<String, Any?>): String = inner.encode(extras)
}

/**
 * The panel's order decisions on top of the state machine (MK-079; 00 section 7.1 O3 to O9, 06 section 11): the review endpoint (O4 / O5) and
 * `PUT /orders/:id/status` (mark paid O2, cancel O7, fail O8), each one transaction under `Locks.forOrder`, nothing written outside
 * [OrderService.transition]. There is no free-form status write: whatever the machine rejects stays rejected.
 *
 * Every decision of this class runs under the `RELEASE` lock set, except mark paid (`COMMIT`: it can only end in O2). `cashback` adds the payer's
 * `ISSUANCE` account to the lock set: an accept can re-tender the credit part, and a completed order may pay a cashback (06 section 13.2).
 * The after-commit steps ([AfterCommit]: the gateway cancel of attempts the decision closed, the panel alert) run through [runAfter] and never
 * fail the decision.
 */
class OrderReviewService(
    private val db: MarketDb,
    private val locks: Locks,
    private val orders: MarketOrderDao,
    private val payments: MarketPaymentDao,
    private val events: MarketOrderEventDao,
    private val clock: Clock,
    private val orderService: OrderService,
    private val cashback: () -> Boolean,
    private val runAfter: suspend (List<AfterCommit>) -> Unit
) {
    /**
     * O4 (`ACCEPT`) or O5 (`REJECT`) on an order in `REVIEW`. Accepting a released order re-reserves it first: 409 `OUT_OF_STOCK`,
     * `PURCHASE_LIMIT_REACHED` or (as [ReReserveRefused]) `INVALID_COUPON` / `INVALID_CREATOR_CODE` / `INVALID_GIFT_CODE`, 400 `INSUFFICIENT_CREDITS`
     * when the credits cannot be held again; the order stays in `REVIEW` and nothing of the attempt is kept. [force] completes without re-reserving
     * codes and limits (stock is still decremented, clamped at 0) and writes the override to the timeline. [refund] (reject only) requests the
     * refund of the money received; the credit hold of the order is released, never refunded. Any other state: 400 `INVALID_ORDER_TRANSITION`.
     */
    suspend fun review(orderId: Long, decision: ReviewDecision, refund: Boolean, force: Boolean, note: String?, adminUserId: Long): OrderChange {
        val event = when (decision) {
            ReviewDecision.ACCEPT -> OrderEvent.ReviewAccepted(force)
            ReviewDecision.REJECT -> OrderEvent.ReviewRejected(refund)
        }

        return apply(orderId, event, OrderLockScope.RELEASE, cashback = decision == ReviewDecision.ACCEPT, adminUserId, note) { reason(it) }
    }

    /**
     * `PUT /orders/:id/status` (04 section 7, 06 section 11): `COMPLETED` is "mark paid" (O2 with actor `ADMIN`, `paidAmount = gatewayAmount`, no
     * `paymentId`, so a `manual` payment; the method the buyer chose is written to the timeline), `FAILED` is O8, `CANCELLED` is O7. The current status is a no-op.
     * `REFUNDED` and `PARTIALLY_REFUNDED` are refused with `use: "refunds"`, an order in `REVIEW` with `use: "review"`, everything else with
     * `INVALID_ORDER_TRANSITION`.
     */
    suspend fun setStatus(orderId: Long, status: OrderStatus, note: String?, adminUserId: Long): OrderChange {
        val order = db.tx { conn -> orders.getById(orderId, conn) } ?: throw NoSuchElementException("order $orderId does not exist")

        if (order.status == status) return OrderChange(order, order.status, moved = false)

        return when (status) {
            OrderStatus.COMPLETED ->
                apply(orderId, OrderEvent.Paid(null, OrderActor.ADMIN), OrderLockScope.COMMIT, cashback = cashback(), adminUserId, note, markPaid = true) { statusUse(it) }

            OrderStatus.FAILED ->
                apply(orderId, OrderEvent.Fail(OrderActor.ADMIN), OrderLockScope.RELEASE, cashback = false, adminUserId, note) { statusUse(it) }

            OrderStatus.CANCELLED ->
                apply(orderId, OrderEvent.Cancel(OrderActor.ADMIN), OrderLockScope.RELEASE, cashback = false, adminUserId, note) { statusUse(it) }

            OrderStatus.REFUNDED, OrderStatus.PARTIALLY_REFUNDED -> throw InvalidOrderTransition(use = "refunds")

            else -> throw InvalidOrderTransition(use = if (order.status == OrderStatus.REVIEW) "review" else null)
        }
    }

    private suspend fun apply(
        orderId: Long,
        event: OrderEvent,
        scope: OrderLockScope,
        cashback: Boolean,
        adminUserId: Long,
        note: String?,
        markPaid: Boolean = false,
        refused: (OrderTransition.Rejected) -> InvalidOrderTransition
    ): OrderChange {
        val after = ArrayList<AfterCommit>()
        val message = note?.trim()?.takeIf { it.isNotEmpty() }?.take(NOTE_MAX)

        val change = try {
            db.txRestartingOnOrderChange { conn ->
                after.clear()

                locks.forOrder(conn, orderId, scope, cashback = cashback) { locked ->
                    locks.children(conn, orderId, OrderChild.PAYMENT)

                    val hadAttempts = markPaid && payments.getByOrderId(orderId, conn).isNotEmpty()
                    val result = orderService.transition(conn, locked, event, actorUserId = adminUserId, message = message)

                    when (val decision = result.decision) {
                        is OrderTransition.Rejected -> throw refused(decision)

                        is OrderTransition.NoOp -> OrderChange(orders.getById(orderId, conn)!!, result.from, moved = false)

                        is OrderTransition.Move -> {
                            after += result.after

                            // "mark paid": no gateway attempt paid this order, so it carries no `paymentId` and, by I11, it is a manual payment (06 section 11 keeps the
                            // method of an order that had attempts; the invariant has no room for a paid order without both, so the method that was chosen goes to the timeline)
                            if (markPaid) markManual(conn, locked.order, hadAttempts)

                            OrderChange(orders.getById(orderId, conn)!!, result.from, moved = true)
                        }
                    }
                }
            }
        } catch (e: InvalidCoupon) {
            throw ReReserveRefused(e)
        } catch (e: InvalidCreatorCode) {
            throw ReReserveRefused(e)
        } catch (e: InvalidGiftCode) {
            throw ReReserveRefused(e)
        }

        if (after.isNotEmpty()) runAfter(after)

        return change
    }

    private suspend fun markManual(conn: io.vertx.sqlclient.SqlConnection, before: MarketOrder, hadAttempts: Boolean) {
        orderService.updateOrder(conn, before.id, linkedMapOf("paymentMethodId" to MANUAL_METHOD, "paymentLabel" to MANUAL_LABEL))

        val now = clock.now()

        events.add(
            MarketOrderEvent(
                orderId = before.id, type = OrderEventType.NOTE, actorType = OrderActorType.ADMIN, message = MARKED_PAID_NOTE,
                data = JsonObject().put("previousMethodId", before.paymentMethodId).put("previousLabel", before.paymentLabel).put("hadAttempts", hadAttempts).encode(),
                createdAt = now, updatedAt = now
            ),
            conn
        )
    }

    /** The review endpoint names no panel hint: the reason says what the order is. */
    private fun reason(rejected: OrderTransition.Rejected): InvalidOrderTransition =
        InvalidOrderTransition(reason = if (rejected.errorCode == OrderStateMachine.INVALID_ORDER_TRANSITION) NOT_IN_REVIEW else rejected.errorCode)

    private fun statusUse(rejected: OrderTransition.Rejected): InvalidOrderTransition =
        InvalidOrderTransition(use = rejected.use)

    companion object {
        const val NOT_IN_REVIEW = "NOT_IN_REVIEW"
        const val MANUAL_METHOD = "manual"
        const val MANUAL_LABEL = "Manual payment"
        const val MARKED_PAID_NOTE = "MARKED_PAID"

        /** `market_order_event.message` holds 512 characters. */
        const val NOTE_MAX = 512
    }
}
