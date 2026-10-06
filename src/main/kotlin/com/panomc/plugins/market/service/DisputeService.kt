package com.panomc.plugins.market.service

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.abuse.BlockValue
import com.panomc.plugins.market.core.credit.Clawback
import com.panomc.plugins.market.core.delivery.DeliveryError
import com.panomc.plugins.market.core.order.OrderEffect
import com.panomc.plugins.market.core.order.OrderEvent
import com.panomc.plugins.market.core.order.OrderState
import com.panomc.plugins.market.core.order.OrderStateMachine
import com.panomc.plugins.market.core.order.OrderTransition
import com.panomc.plugins.market.core.refund.DisputeEffect
import com.panomc.plugins.market.core.refund.DisputeEvent
import com.panomc.plugins.market.core.refund.DisputeOrderFacts
import com.panomc.plugins.market.core.refund.DisputeSource
import com.panomc.plugins.market.core.refund.DisputeStateMachine
import com.panomc.plugins.market.core.refund.DisputeTransition
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.core.webhook.EventPayloads
import com.panomc.plugins.market.db.dao.MarketBlockDao
import com.panomc.plugins.market.db.dao.MarketCreditTxDao
import com.panomc.plugins.market.db.dao.MarketDeliveryDao
import com.panomc.plugins.market.db.dao.MarketDisputeDao
import com.panomc.plugins.market.db.dao.MarketEntitlementDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderEventDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketPaymentDao
import com.panomc.plugins.market.db.model.BlockSource
import com.panomc.plugins.market.db.model.BlockType
import com.panomc.plugins.market.db.model.CreditSystemKey
import com.panomc.plugins.market.db.model.CreditTxType
import com.panomc.plugins.market.db.model.DeliverySourceType
import com.panomc.plugins.market.db.model.DeliveryStatus
import com.panomc.plugins.market.db.model.DisputeOrigin
import com.panomc.plugins.market.db.model.DisputeRecordStatus
import com.panomc.plugins.market.db.model.MarketBlock
import com.panomc.plugins.market.db.model.MarketCreditTx
import com.panomc.plugins.market.db.model.MarketDispute
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderEvent
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.OrderActorType
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.db.tx.OrderChangedException
import com.panomc.plugins.market.db.tx.OrderChild
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.error.InvalidOrderTransition
import com.panomc.plugins.market.error.InvalidState
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.routes.api.payment.InboundEventContext
import com.panomc.plugins.market.routes.api.payment.PaymentEventSink
import com.panomc.plugins.market.spi.payment.DisputeState
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

// ================================================================================================================ ports

/**
 * A panel alert of the dispute flow (`DISPUTE_INQUIRY`, `DISPUTE_ON_UNPAID_ORDER`, `CHARGEBACK_OPENED`, `CLAWBACK_SHORTFALL`, `UPGRADE_SUCCESSORS_REVOKED`,
 * `CHARGEBACK_ACTIONS_HELD`, `DISPUTE_AMBIGUOUS`): raised after the transaction that found it committed. The panel notification itself is MK-172's; until it
 * is wired the alerts are logged.
 */
fun interface DisputeAlerts {
    suspend fun alert(orderId: Long, code: String, data: JsonObject)

    companion object {
        val LOG_ONLY = DisputeAlerts { orderId, code, data -> LoggerFactory.getLogger(DisputeAlerts::class.java).warn("dispute alert {} on order {}: {}", code, orderId, data.encode()) }
    }
}

/**
 * What O11 and O12 hand to the services of other areas (21 sections 5.2 and 5.3), all inside the dispute transaction: they only write rows. [NONE] does nothing;
 * [StandardDisputeEffects] is the production composition. A failure of one of them rolls the whole transition back (the gateway redelivers the event, the retry
 * job applies it again).
 */
interface DisputeEffects {
    /** O11 step 2 (21 section 7.3): the creator earning of the order is reversed in full. */
    suspend fun creatorReversal(conn: SqlConnection, order: MarketOrder)

    /** O11 step 4 (`SubscriptionService.onOrderChargeback`, 09 section 10.4); the subscription slices install the real one. */
    suspend fun subscription(conn: SqlConnection, order: MarketOrder, dispute: MarketDispute)

    /** The store webhooks `order.chargeback` ([won] false, O11) and `order.chargeback.won` ([won] true, O12). */
    suspend fun webhook(conn: SqlConnection, order: MarketOrder, dispute: MarketDispute, won: Boolean)

    companion object {
        val NONE: DisputeEffects = object : DisputeEffects {
            override suspend fun creatorReversal(conn: SqlConnection, order: MarketOrder) = Unit
            override suspend fun subscription(conn: SqlConnection, order: MarketOrder, dispute: MarketDispute) = Unit
            override suspend fun webhook(conn: SqlConnection, order: MarketOrder, dispute: MarketDispute, won: Boolean) = Unit
        }
    }
}

/**
 * The production composition of [DisputeEffects]: the creator reversal of the refund effects (the earning is reversed in full, a `PAID` one stays `PAID` and shows
 * as a negative available balance, 21 section 7.3) and the webhook queue. Every collaborator is optional so a host without one still handles disputes.
 */
class StandardDisputeEffects(
    private val refundEffects: RefundEffects = RefundEffects.NONE,
    private val webhooks: WebhookService? = null,
    private val subscriptionEnding: suspend (SqlConnection, MarketOrder, MarketDispute) -> Unit = { _, _, _ -> }
) : DisputeEffects {
    override suspend fun creatorReversal(conn: SqlConnection, order: MarketOrder) = refundEffects.creatorReversal(conn, order, order.totalPrice, true)

    override suspend fun subscription(conn: SqlConnection, order: MarketOrder, dispute: MarketDispute) = subscriptionEnding(conn, order, dispute)

    override suspend fun webhook(conn: SqlConnection, order: MarketOrder, dispute: MarketDispute, won: Boolean) {
        val queue = webhooks ?: return

        queue.emitOrderDispute(conn, order.id, dispute.id, won, DisputeService.disputeJson(dispute))
    }
}

// ================================================================================================================ the service

private class DisputeAlert(val orderId: Long, val code: String, val data: JsonObject)

/** One transaction's side data: the alerts to raise after the commit. */
private class DisputeTx(val conn: SqlConnection) {
    val alerts = ArrayList<DisputeAlert>()
}

/** What a dispute fact says: from a provider event or from the panel; [targetDisputeId] names the row of a panel `PUT`. */
private class DisputeInput(
    val state: DisputeState,
    val source: DisputeSource,
    val providerId: String? = null,
    val gatewayDisputeId: String? = null,
    val attempt: MarketPayment? = null,
    val reportedAmount: Long? = null,
    val reportedCurrency: String? = null,
    val reason: String? = null,
    val occurredAt: Long? = null,
    val actorUserId: Long? = null,
    val targetDisputeId: Long? = null
)

private sealed class DisputeTarget {
    class Row(val row: MarketDispute?) : DisputeTarget()

    data object Ambiguous : DisputeTarget()
}

/**
 * Disputes (MK-112; 21 sections 4 and 5, 07 section 8.5, 11 section 10, 08 sections 11 and 12, 00 section 7.1 O11 / O12):
 *
 * - the dispute rows and their state machine ([DisputeStateMachine], one conditional `UPDATE ... WHERE status = :from` per move): inbound `DisputeUpdated`
 *   ([onDisputeUpdated]), the panel's manual chargeback ([open]) and resolution ([resolve]);
 * - **O11** (one transaction under `Locks.forOrder(RELEASE)`): `statusBeforeDispute`, `CHARGEBACK`, the units leave the product's `soldCount`; `REVOKE` rows for
 *   every item following the upgrade successors; the block list (recipient, payer account, e-mail); the configured chargeback actions (held `NEEDS_CONFIRMATION`
 *   for a guest order whose payer and recipient differ); creator earning in full; cashback reversal and top-up clawback with policy `ALLOW_DEBT` (a debt may
 *   remain, the credit-paid orders made since are revoked newest first); the subscription hook; unsent refunds cancelled; the store webhook;
 * - **O12** (a dispute won): the status goes back to `statusBeforeDispute`, the chargeback blocks of this order are removed (or handed to another disputed order),
 *   `soldCount` comes back, the webhook `order.chargeback.won`. Nothing is re-granted: the panel's re-run and a manual credit grant do that;
 * - [runChargebackActions]: the held chargeback actions are planned again (`attemptGroup + 1`) with the recipient as target.
 *
 * O11 and O12 are applied here, not through `OrderService.transition` (whose O10 to O12 effects stop with `EffectNotOwnedYet`): the pure machines decide
 * ([OrderStateMachine] for the legality and the target status, [DisputeStateMachine] for the dispute), this class writes. The other orders the transition
 * reaches (upgrade successors, credit-paid orders) are locked by row, ascending id, after the order of the dispute.
 */
class DisputeService(
    private val db: MarketDb,
    private val locks: Locks,
    private val clock: Clock,
    private val config: () -> MarketConfig,
    private val orders: MarketOrderDao,
    private val orderItems: MarketOrderItemDao,
    private val orderEvents: MarketOrderEventDao,
    private val payments: MarketPaymentDao,
    private val disputes: MarketDisputeDao,
    private val blocks: MarketBlockDao,
    private val deliveries: MarketDeliveryDao,
    private val entitlements: MarketEntitlementDao,
    private val creditTxs: MarketCreditTxDao,
    private val credits: CreditService,
    private val deliveryService: DeliveryService,
    private val entitlementService: EntitlementService,
    private val refundService: RefundService,
    private val effects: DisputeEffects = DisputeEffects.NONE,
    private val alerts: DisputeAlerts = DisputeAlerts.LOG_ONLY
) {
    private fun table(name: String) = "`${orders.prefix()}$name`"

    // ============================================================================================================ entry points

    /**
     * An inbound `DisputeUpdated` for [attempt] (21 section 5.1): the row is found by `(providerId, gatewayDisputeId)`; an event without an id targets the order's
     * single `INQUIRY` / `OPEN` row, else the last id-less row of the provider (a replay, a second cycle), else a new row. The machine decides; a replay is a no-op.
     */
    suspend fun onDisputeUpdated(event: PaymentEvent.DisputeUpdated, attempt: MarketPayment, @Suppress("UNUSED_PARAMETER") eventKey: String?, @Suppress("UNUSED_PARAMETER") requestHash: String?) {
        val input = DisputeInput(
            state = event.state, source = DisputeSource.GATEWAY, providerId = attempt.providerId, gatewayDisputeId = event.gatewayDisputeId?.takeIf { it.isNotBlank() }?.take(191),
            attempt = attempt, reportedAmount = event.amount?.amount, reportedCurrency = event.amount?.currency, reason = event.reason?.trim()?.takeIf { it.isNotEmpty() }?.take(255),
            occurredAt = event.occurredAt
        )

        run(attempt.orderId, input)
    }

    /**
     * `POST /orders/:id/disputes` (21 section 5.1, `origin = MANUAL`): a chargeback the gateway did not report. [amount] defaults to what the attempt took. A
     * single `INQUIRY` row of the order is promoted to `OPEN`; a `MANUAL` row that is still `OPEN` answers itself (a double click opens one dispute).
     * 400 `INVALID_ORDER_TRANSITION` for an order that is not paid.
     */
    suspend fun open(orderId: Long, amount: Long?, reason: String?, actorUserId: Long?): MarketDispute {
        if (amount != null && amount <= 0L) throw RequestValueException("amount", "OUT_OF_RANGE")

        val text = reason?.trim()?.takeIf { it.isNotEmpty() }

        if (text != null && text.length > REASON_MAX) throw RequestValueException("reason", "TOO_LONG")

        val id = run(orderId, DisputeInput(DisputeState.OPENED, DisputeSource.PANEL, reportedAmount = amount, reason = text, actorUserId = actorUserId))
            ?: throw IllegalStateException("a manual dispute of order $orderId produced no row")

        return db.tx { disputes.getById(id, it)!! }
    }

    /** `PUT /disputes/:id` (21 section 5.1): the panel resolves a dispute `WON`, `LOST` or `CLOSED`. 409 `INVALID_STATE`; a missing dispute is a [NoSuchElementException]. */
    suspend fun resolve(disputeId: Long, status: DisputeRecordStatus, actorUserId: Long?): MarketDispute {
        val state = when (status) {
            DisputeRecordStatus.WON -> DisputeState.WON
            DisputeRecordStatus.LOST -> DisputeState.LOST
            DisputeRecordStatus.CLOSED -> DisputeState.CLOSED
            else -> throw InvalidState(status.name)
        }
        val orderId = db.tx { disputes.getById(disputeId, it)?.orderId } ?: throw NoSuchElementException("dispute $disputeId does not exist")

        run(orderId, DisputeInput(state, DisputeSource.PANEL, actorUserId = actorUserId, targetDisputeId = disputeId))

        return db.tx { disputes.getById(disputeId, it)!! }
    }

    /**
     * `POST /orders/:id/chargeback-actions` (08 section 12, 11 section 10): the chargeback actions of a dispute whose rows were held `NEEDS_CONFIRMATION` are
     * planned again as `attemptGroup + 1` with the recipient of the order as the target. Answers how many rows were created (`0` when nothing is held).
     */
    suspend fun runChargebackActions(orderId: Long): Int = db.txRestartingOnOrderChange { conn ->
        locks.forOrder(conn, orderId, OrderLockScope.PAYMENT) { locked ->
            val order = locked.order
            val rows = deliveries.getByOrderId(orderId, conn).filter { it.sourceType == DeliverySourceType.CHARGEBACK_ACTION && it.sourceId != null }
            var created = 0

            for ((disputeId, ofDispute) in rows.groupBy { it.sourceId!! }.toSortedMap()) {
                val effective = ofDispute.groupBy { Triple(it.actionId, it.serverId, it.unitIndex) }.values.map { group -> group.maxByOrNull { it.attemptGroup }!! }
                val held = effective.any { it.status == DeliveryStatus.CANCELLED && it.lastErrorCode == DeliveryError.NEEDS_CONFIRMATION }

                if (!held) continue

                val group = ofDispute.maxOf { it.attemptGroup } + 1
                val inserted = deliveryService.insertPlanned(conn, deliveryService.planChargebackActions(conn, order, disputeId, group, confirmed = true))

                created += inserted.size
            }

            created
        }
    }

    // ============================================================================================================ one fact, one transaction

    private suspend fun run(orderId: Long, input: DisputeInput): Long? {
        val result = db.txRestartingOnOrderChange { conn ->
            locks.forOrder(conn, orderId, OrderLockScope.RELEASE, cashback = true) { locked ->
                val t = DisputeTx(conn)

                locks.children(conn, orderId, OrderChild.REFUND)

                t to handle(t, locked.order.id, input)
            }
        }

        raise(result.first)

        return result.second
    }

    /** The row [input] ended up on (`null` when the fact matched no row and created none). */
    private suspend fun handle(t: DisputeTx, orderId: Long, input: DisputeInput): Long? {
        val conn = t.conn
        val order = orders.getById(orderId, conn) ?: throw OrderChangedException(orderId, "the row is gone")
        val rows = disputes.getByOrderId(orderId, conn)

        if (input.gatewayDisputeId != null && input.providerId != null) {
            val owner = disputes.getByProviderDispute(input.providerId, input.gatewayDisputeId, conn)

            if (owner != null && owner.orderId != orderId) {
                logger.warn("dispute {} of provider {} belongs to order {}, an event for order {} is ignored", input.gatewayDisputeId, input.providerId, owner.orderId, orderId)

                return null
            }
        }

        val existing = when (val target = target(rows, input)) {
            DisputeTarget.Ambiguous -> {
                timeline(conn, orderId, OrderEventType.NOTE, actorOf(input), input.actorUserId, "DISPUTE_EVENT_AMBIGUOUS", JsonObject().put("state", input.state.name))
                t.alerts += DisputeAlert(orderId, "DISPUTE_AMBIGUOUS", JsonObject().put("state", input.state.name))

                return null
            }

            is DisputeTarget.Row -> target.row
        }

        if (input.targetDisputeId != null && existing == null) throw NoSuchElementException("dispute ${input.targetDisputeId} does not exist")

        // a manual chargeback that was already opened by hand answers itself
        if (input.source == DisputeSource.PANEL && input.state == DisputeState.OPENED && existing == null) {
            rows.firstOrNull { it.origin == DisputeOrigin.MANUAL && it.status == DisputeRecordStatus.OPEN }?.let { return it.id }
        }

        val facts = DisputeOrderFacts(order.status, order.disputeStatus, otherOpenDisputes = rows.any { it.id != existing?.id && it.status == DisputeRecordStatus.OPEN })

        return when (val decision = DisputeStateMachine.decide(existing?.status, DisputeEvent(input.state, input.source), facts)) {
            is DisputeTransition.Rejected ->
                throw if (decision.errorCode == DisputeStateMachine.INVALID_ORDER_TRANSITION) InvalidOrderTransition() else InvalidState(existing?.status?.name ?: "NONE")

            DisputeTransition.NoOp -> existing?.id

            is DisputeTransition.Move -> move(t, order, existing, decision, input)
        }
    }

    /** The row a fact is about: the panel's id, the gateway's id, else the open row of the order (see [onDisputeUpdated]). */
    private fun target(rows: List<MarketDispute>, input: DisputeInput): DisputeTarget {
        if (input.targetDisputeId != null) return DisputeTarget.Row(rows.firstOrNull { it.id == input.targetDisputeId })

        if (input.gatewayDisputeId != null && input.providerId != null) {
            return DisputeTarget.Row(rows.firstOrNull { it.providerId == input.providerId && it.gatewayDisputeId == input.gatewayDisputeId })
        }

        val inquiries = rows.filter { it.status == DisputeRecordStatus.INQUIRY }

        // the panel's manual chargeback promotes the one inquiry of the order, else it is a row of its own
        if (input.source == DisputeSource.PANEL) return DisputeTarget.Row(inquiries.singleOrNull())

        val open = rows.filter { it.status == DisputeRecordStatus.INQUIRY || it.status == DisputeRecordStatus.OPEN }

        return when {
            open.size == 1 -> DisputeTarget.Row(open.single())
            open.size > 1 -> DisputeTarget.Ambiguous
            // nothing is open: a resolved row of this provider without an id is the same dispute again (a replayed WON, a second cycle)
            else -> DisputeTarget.Row(rows.lastOrNull { it.providerId == input.providerId && it.gatewayDisputeId == null })
        }
    }

    private suspend fun move(t: DisputeTx, order: MarketOrder, existing: MarketDispute?, decision: DisputeTransition.Move, input: DisputeInput): Long {
        val conn = t.conn
        val now = clock.now()
        val id: Long

        if (decision.from == null) {
            id = disputes.add(
                MarketDispute(
                    orderId = order.id, paymentId = input.attempt?.id ?: order.paymentId, providerId = input.providerId, gatewayDisputeId = input.gatewayDisputeId, status = decision.to,
                    origin = if (input.source == DisputeSource.PANEL) DisputeOrigin.MANUAL else DisputeOrigin.GATEWAY, amount = amountOf(order, input), currency = order.currency,
                    reason = input.reason, openedAt = input.occurredAt ?: now, resolvedAt = null, createdBy = input.actorUserId, createdAt = now, updatedAt = now
                ),
                conn
            ) ?: throw IllegalStateException("dispute of order ${order.id} already exists (${input.providerId}, ${input.gatewayDisputeId})")
        } else {
            id = existing!!.id

            val moved = conn.preparedQuery("UPDATE ${table("market_dispute")} SET `status` = ?, `updatedAt` = ? WHERE `id` = ? AND `status` = ?")
                .execute(Tuple.of(decision.to.name, now, id, decision.from.name)).coAwait().rowCount()

            if (moved != 1) throw IllegalStateException("dispute $id moved under the order lock")
        }

        for (effect in decision.effects) {
            when (effect) {
                DisputeEffect.StampOpened -> if (existing != null) setDispute(conn, id, linkedMapOf("openedAt" to (input.occurredAt ?: now), "resolvedAt" to null))

                DisputeEffect.StampResolved -> setDispute(conn, id, linkedMapOf("resolvedAt" to now))

                is DisputeEffect.NotifyOrder -> when (effect.event) {
                    OrderEvent.DisputeOpened -> opened(t, orders.getById(order.id, conn)!!, disputes.getById(id, conn)!!, input)
                    OrderEvent.DisputeWon -> won(t, orders.getById(order.id, conn)!!, disputes.getById(id, conn)!!, input)
                    else -> throw IllegalStateException("a dispute never notifies the order of ${effect.event}")
                }

                is DisputeEffect.SetOrderDisputeStatus -> conn.preparedQuery("UPDATE ${table("market_order")} SET `disputeStatus` = ?, `updatedAt` = GREATEST(?, `updatedAt` + 1) WHERE `id` = ?")
                    .execute(Tuple.of(effect.status.name, now, order.id)).coAwait()

                is DisputeEffect.Timeline -> timeline(
                    conn, order.id, effect.type, actorOf(input), input.actorUserId, null,
                    JsonObject().put("disputeId", id).put("status", decision.to.name).put("origin", if (input.source == DisputeSource.PANEL) DisputeOrigin.MANUAL.name else DisputeOrigin.GATEWAY.name)
                        .put("amount", amountOf(order, input)).put("gatewayDisputeId", input.gatewayDisputeId)
                )

                is DisputeEffect.PanelAlert -> t.alerts += DisputeAlert(order.id, effect.code, JsonObject().put("disputeId", id))

                DisputeEffect.CancelUnsentRefunds -> {
                    val cancelled = refundService.cancelUnsentForChargeback(conn, order.id)

                    if (cancelled > 0) timeline(conn, order.id, OrderEventType.NOTE, OrderActorType.SYSTEM, null, "REFUNDS_CANCELLED_BY_CHARGEBACK", JsonObject().put("disputeId", id).put("count", cancelled))
                }

                DisputeEffect.RemoveChargebackBlocks -> removeBlocks(conn, order.id)
            }
        }

        return id
    }

    /** The disputed amount (x100, order currency): the gateway's figure in the order's currency, the panel's figure, else what the attempt took. */
    private fun amountOf(order: MarketOrder, input: DisputeInput): Long {
        val reported = input.reportedAmount

        if (reported != null && reported > 0L && (input.reportedCurrency == null || input.reportedCurrency.equals(order.currency, ignoreCase = true))) return reported

        val attempt = input.attempt

        if (attempt != null) {
            val paid = attempt.paidAmount

            if (paid != null && paid > 0L && (attempt.paidCurrency.isNullOrBlank() || attempt.paidCurrency.equals(order.currency, ignoreCase = true))) return paid

            if (attempt.amount > 0L) return attempt.amount
        }

        return maxOf(order.paidAmount, order.gatewayAmount)
    }

    private suspend fun setDispute(conn: SqlClient, id: Long, columns: Map<String, Any?>) {
        val sets = LinkedHashMap<String, Any?>(columns)

        sets["updatedAt"] = clock.now()

        val values = ArrayList<Any?>(sets.values).also { it += id }

        conn.preparedQuery("UPDATE ${table("market_dispute")} SET ${sets.keys.joinToString(", ") { "`$it` = ?" }} WHERE `id` = ?").execute(Tuple.from(values)).coAwait()
    }

    // ============================================================================================================ O11

    /** O11 (21 section 5.2): the order is paid, the dispute row exists (its id keys the credit, delivery and webhook rows). */
    private suspend fun opened(t: DisputeTx, order: MarketOrder, dispute: MarketDispute, input: DisputeInput) {
        val conn = t.conn
        val now = clock.now()
        val decision = OrderStateMachine.decide(
            OrderState(order.status, order.reservationState, order.expiresAt, false, order.paidAmount, order.statusBeforeDispute, null), OrderEvent.DisputeOpened
        )

        if (decision !is OrderTransition.Move) throw IllegalStateException("order ${order.id} (${order.status}) cannot take a dispute")

        val moved = conn.preparedQuery(
            "UPDATE ${table("market_order")} SET `status` = ?, `statusBeforeDispute` = ?, `updatedAt` = GREATEST(?, `updatedAt` + 1) WHERE `id` = ? AND `status` = ?"
        ).execute(Tuple.of(decision.to.name, order.status.name, now, order.id, order.status.name)).coAwait().rowCount()

        if (moved != 1) throw OrderChangedException(order.id, "the status moved under the lock")

        orderEvents.add(
            MarketOrderEvent(
                orderId = order.id, type = OrderEventType.STATUS_CHANGED, fromStatus = order.status.name, toStatus = decision.to.name, actorType = actorOf(input),
                actorUserId = input.actorUserId, createdAt = now, updatedAt = now
            ),
            conn
        )

        val items = orderItems.getByOrderIds(listOf(order.id), conn)

        // the units leave the sold count (17 section 7 I17 counts COMPLETED and PARTIALLY_REFUNDED orders only); O12 gives them back
        adjustSold(conn, order, items, -1)

        for (effect in decision.effects) {
            when (effect) {
                is OrderEffect.SaveStatusBeforeDispute -> Unit

                is OrderEffect.RevokeDeliveries -> if (config().revokeOnChargeback) revokeAll(t, order, items, dispute)

                is OrderEffect.BlockBuyer -> if (config().autoBlockOnChargeback) autoBlock(conn, order)

                is OrderEffect.RunChargebackActions -> planActions(t, orders.getById(order.id, conn)!!, dispute)

                is OrderEffect.ReverseCreatorEarning -> effects.creatorReversal(conn, orders.getById(order.id, conn)!!)

                is OrderEffect.ReverseCashback -> credits.reverseCashback(orders.getById(order.id, conn)!!, null, dispute.id, order.refundedTotal, conn)

                is OrderEffect.ClawbackGrantedCredits -> if (config().revokeOnChargeback) clawback(t, orders.getById(order.id, conn)!!, items, dispute)

                is OrderEffect.SubscriptionOnOrderChargeback -> effects.subscription(conn, orders.getById(order.id, conn)!!, dispute)

                is OrderEffect.QueueWebhook -> effects.webhook(conn, orders.getById(order.id, conn)!!, disputes.getById(dispute.id, conn)!!, false)

                else -> Unit
            }
        }

        t.alerts += DisputeAlert(order.id, "CHARGEBACK_OPENED", JsonObject().put("disputeId", dispute.id))
    }

    /** Every item of the order, all units not revoked yet (08 section 11.2), and the upgrade successors of every item (21 section 5.4, always cascading). */
    private suspend fun revokeAll(t: DisputeTx, order: MarketOrder, items: List<MarketOrderItem>, dispute: MarketDispute) {
        val conn = t.conn

        revokeOrder(conn, order, items, END_REASON)

        // the chain of every item: 1 to 2 to 3, each live or superseded link takes its own order's items with it
        val byOrder = sortedMapOf<Long, MutableSet<Long>>()

        for (item in items) {
            for (entitlement in entitlements.getByOrderItemId(item.id, conn)) {
                var current = entitlement
                var hops = 0

                while (current.replacedById != null && hops++ < MAX_UPGRADE_HOPS) {
                    current = entitlements.getById(current.replacedById!!, conn) ?: break

                    if (current.orderId != order.id) byOrder.getOrPut(current.orderId) { LinkedHashSet() } += current.orderItemId
                }
            }
        }

        if (byOrder.isEmpty()) return

        lockOrders(conn, byOrder.keys)

        for ((orderId, itemIds) in byOrder) {
            val other = orders.getById(orderId, conn) ?: continue
            val otherItems = orderItems.getByOrderIds(listOf(orderId), conn)

            entitlementService.revoke(conn, deliveryService, other, otherItems, itemIds.sorted().associateWith { null }, END_REASON, DeliveryError.ORDER_REVOKED)
            deliveryService.refreshFulfillment(conn, orderId)
            timeline(conn, orderId, OrderEventType.NOTE, OrderActorType.SYSTEM, null, "UPGRADE_CASCADE_REVOKED", JsonObject().put("disputeId", dispute.id).put("orderId", order.id))
        }

        t.alerts += DisputeAlert(order.id, "UPGRADE_SUCCESSORS_REVOKED", JsonObject().put("disputeId", dispute.id).put("orders", JsonArray(byOrder.keys.toList())))
    }

    private suspend fun revokeOrder(conn: SqlConnection, order: MarketOrder, items: List<MarketOrderItem>, endReason: String) {
        val targets = LinkedHashMap<Long, IntRange?>()

        for (item in items) {
            if (item.kind == OrderItemKind.BUNDLE_CHILD || item.kind == OrderItemKind.CREDIT_TOPUP) continue

            targets[item.id] = null
        }

        if (targets.isEmpty()) return

        entitlementService.revoke(conn, deliveryService, order, items, targets, endReason, DeliveryError.ORDER_REVOKED)
        deliveryService.refreshFulfillment(conn, order.id)
    }

    /** The order rows other than the one of the dispute, locked by row in ascending id order (the lock order has no order-to-order step; this is the one place it is needed). */
    private suspend fun lockOrders(conn: SqlClient, ids: Collection<Long>) {
        if (ids.isEmpty()) return

        val sorted = ids.toSortedSet().toList()

        conn.preparedQuery("SELECT `id` FROM ${table("market_order")} WHERE `id` IN (${sorted.joinToString(", ") { "?" }}) ORDER BY `id` FOR UPDATE").execute(Tuple.from(sorted)).coAwait()
    }

    /** `soldCount += sign x (quantity - refundedQuantity)` per product of a non-test order (the rule of the refund service, `credit top-up` lines excepted). */
    private suspend fun adjustSold(conn: SqlClient, order: MarketOrder, items: List<MarketOrderItem>, sign: Int) {
        if (order.testMode) return

        val sold = sortedMapOf<Long, Long>()

        for (item in items) {
            val productId = item.productId ?: continue

            if (item.kind == OrderItemKind.CREDIT_TOPUP) continue

            val units = maxOf(0, item.quantity - item.refundedQuantity)

            if (units > 0) sold.merge(productId, units.toLong(), Long::plus)
        }

        for ((productId, units) in sold) {
            conn.preparedQuery("UPDATE ${table("market_product")} SET `soldCount` = GREATEST(`soldCount` + ?, 0) WHERE `id` = ?").execute(Tuple.of(sign * units, productId)).coAwait()
        }
    }

    // ---- the block list (11 section 10 step 1)

    /**
     * `autoBlockOnChargeback`: `PLAYER` = the recipient's name (where the goods went), `USER` = the payer's account when there is one, `EMAIL` = the order e-mail
     * (exact, never the domain). A guest's typed payer name is never blocked. An existing row stays as it is, except an expired one: that would not block anybody,
     * so it becomes this chargeback's row. No `IP` row is created.
     */
    private suspend fun autoBlock(conn: SqlConnection, order: MarketOrder) {
        val now = clock.now()
        val created = ArrayList<String>()

        suspend fun put(type: BlockType, core: com.panomc.plugins.market.core.abuse.BlockType, raw: String?) {
            val value = BlockValue.normalize(core, raw) ?: return
            val reason = "Chargeback on order #${order.id}"
            val id = blocks.add(
                MarketBlock(type = type, value = value, reason = reason, source = BlockSource.CHARGEBACK, orderId = order.id, createdBy = null, expiresAt = null, createdAt = now, updatedAt = now),
                conn
            )

            if (id != null) {
                created += type.name

                return
            }

            val revived = conn.preparedQuery(
                "UPDATE ${table("market_block")} SET `reason` = ?, `source` = 'CHARGEBACK', `orderId` = ?, `createdBy` = NULL, `expiresAt` = NULL, `updatedAt` = ? " +
                    "WHERE `type` = ? AND `value` = ? AND `expiresAt` IS NOT NULL AND `expiresAt` <= ?"
            ).execute(Tuple.of(reason, order.id, now, type.name, value, now)).coAwait().rowCount()

            if (revived > 0) created += type.name
        }

        put(BlockType.PLAYER, com.panomc.plugins.market.core.abuse.BlockType.PLAYER, order.recipientUsername.ifBlank { order.playerUsername })

        if (order.userId != null) put(BlockType.USER, com.panomc.plugins.market.core.abuse.BlockType.USER, order.userId.toString())

        if (!order.email.isNullOrBlank()) put(BlockType.EMAIL, com.panomc.plugins.market.core.abuse.BlockType.EMAIL, order.email)

        if (created.isNotEmpty()) timeline(conn, order.id, OrderEventType.BLOCK_CREATED, OrderActorType.SYSTEM, null, null, JsonObject().put("types", JsonArray(created)))
    }

    /**
     * O12 (11 section 10): the rows `source = CHARGEBACK` of this order are deleted, unless another order with `disputeStatus` `OPEN` or `LOST` has the same
     * player, user or e-mail: then the row stays and points to that order.
     */
    private suspend fun removeBlocks(conn: SqlConnection, orderId: Long) {
        val rows = conn.preparedQuery("SELECT `id`, `type`, `value` FROM ${table("market_block")} WHERE `source` = 'CHARGEBACK' AND `orderId` = ? ORDER BY `id` FOR UPDATE")
            .execute(Tuple.of(orderId)).coAwait().toList()
        val removed = ArrayList<String>()
        val now = clock.now()

        for (row in rows) {
            val type = BlockType.valueOf(row.getString("type"))
            val value = row.getString("value")
            val match = when (type) {
                BlockType.PLAYER -> "LOWER(COALESCE(NULLIF(`recipientUsername`, ''), `playerUsername`)) = ?" to value
                BlockType.USER -> "`userId` = ?" to (value.toLongOrNull() ?: -1L)
                BlockType.EMAIL -> "LOWER(`email`) = ?" to value
                BlockType.IP -> null
            }
            val other = match?.let {
                conn.preparedQuery("SELECT `id` FROM ${table("market_order")} WHERE `id` <> ? AND `disputeStatus` IN ('OPEN', 'LOST') AND ${it.first} ORDER BY `id` LIMIT 1")
                    .execute(Tuple.of(orderId, it.second)).coAwait().firstOrNull()?.getLong("id")
            }

            if (other != null) {
                conn.preparedQuery("UPDATE ${table("market_block")} SET `orderId` = ?, `updatedAt` = ? WHERE `id` = ?").execute(Tuple.of(other, now, row.getLong("id"))).coAwait()
            } else {
                conn.preparedQuery("DELETE FROM ${table("market_block")} WHERE `id` = ?").execute(Tuple.of(row.getLong("id"))).coAwait()

                removed += type.name
            }
        }

        if (removed.isNotEmpty()) timeline(conn, orderId, OrderEventType.BLOCK_REMOVED, OrderActorType.SYSTEM, null, null, JsonObject().put("types", JsonArray(removed)))
    }

    // ---- chargeback actions (08 section 12, 11 section 10 step 2)

    private suspend fun planActions(t: DisputeTx, order: MarketOrder, dispute: MarketDispute) {
        val planned = deliveryService.planChargebackActions(t.conn, order, dispute.id)

        if (planned.isEmpty()) return

        deliveryService.insertPlanned(t.conn, planned)

        if (planned.any { it.status == DeliveryStatus.CANCELLED && it.lastErrorCode == DeliveryError.NEEDS_CONFIRMATION }) {
            timeline(t.conn, order.id, OrderEventType.NOTE, OrderActorType.SYSTEM, null, "CHARGEBACK_ACTIONS_HELD", JsonObject().put("disputeId", dispute.id))
            t.alerts += DisputeAlert(order.id, "CHARGEBACK_ACTIONS_HELD", JsonObject().put("disputeId", dispute.id))
        }
    }

    // ---- credits (07 section 8.5)

    /**
     * Top-up clawback of a chargeback: every credit-granting line of the order, the whole grant not taken back yet, from the account that received it, policy
     * `ALLOW_DEBT` (the buyer must not keep the goods and the money). When the balance before did not cover the amount the shortfall is written to the timeline and
     * the credit-paid orders made since are looked at ([spentOrders]).
     */
    private suspend fun clawback(t: DisputeTx, order: MarketOrder, items: List<MarketOrderItem>, dispute: MarketDispute) {
        val conn = t.conn
        val recipient = order.recipientUserId ?: order.userId ?: return
        var uncovered = 0L

        for (request in topUpClawbacks(items, creditTxs.getByOrderId(order.id, conn))) {
            val result = credits.post(
                Posting(
                    CreditTxType.REVOKE, "dispute:${dispute.id}:clawback:${request.itemId}", recipient, request.amount, AccountRef.User(recipient),
                    AccountRef.System(CreditSystemKey.REVOKED), PostingPolicy.ALLOW_DEBT, orderId = order.id
                ),
                conn
            )

            if (result.replayed) continue

            val balanceBefore = result.userBalance + result.tx.amount
            val gap = request.amount - maxOf(balanceBefore, 0L)

            if (gap > 0L) {
                uncovered += gap

                timeline(
                    conn, order.id, OrderEventType.CLAWBACK_SHORTFALL, OrderActorType.SYSTEM, null, null,
                    JsonObject().put("disputeId", dispute.id).put("orderItemId", request.itemId).put("uncovered", gap)
                )
            }
        }

        if (uncovered > 0L) spentOrders(t, order, dispute, recipient, uncovered)
    }

    /**
     * 07 section 8.5 steps 1 and 2: the credit-paid orders of [holder] paid since the top-up (newest first) are listed in a panel alert and, with
     * `revokeCreditOrdersOnTopUpChargeback`, revoked until the credits that paid them reach [uncovered] (`REVOKE` deliveries, no refund rows: the credits they
     * were paid with are the ones that were clawed back).
     */
    private suspend fun spentOrders(t: DisputeTx, topUp: MarketOrder, dispute: MarketDispute, holder: Long, uncovered: Long) {
        val conn = t.conn
        val candidates = conn.preparedQuery(
            "SELECT `id` FROM ${table("market_order")} WHERE `userId` = ? AND `creditAmount` > 0 AND `id` <> ? AND `status` IN ('COMPLETED', 'PARTIALLY_REFUNDED') AND `paidAt` >= ? " +
                "ORDER BY `paidAt` DESC, `id` DESC"
        ).execute(Tuple.of(holder, topUp.id, topUp.paidAt ?: 0L)).coAwait().map { it.getLong("id") }
        val revoked = ArrayList<Long>()

        if (config().revokeCreditOrdersOnTopUpChargeback && candidates.isNotEmpty()) {
            lockOrders(conn, candidates)

            var remaining = uncovered

            for (id in candidates) {
                if (remaining <= 0L) break

                val other = orders.getById(id, conn) ?: continue

                // read again under the lock
                if (other.status != OrderStatus.COMPLETED && other.status != OrderStatus.PARTIALLY_REFUNDED) continue

                revokeOrder(conn, other, orderItems.getByOrderIds(listOf(id), conn), END_REASON)
                timeline(conn, id, OrderEventType.NOTE, OrderActorType.SYSTEM, null, "CREDIT_ORDER_REVOKED", JsonObject().put("disputeId", dispute.id).put("orderId", topUp.id))

                revoked += id
                remaining -= other.creditAmount
            }
        }

        t.alerts += DisputeAlert(
            topUp.id, "CLAWBACK_SHORTFALL",
            JsonObject().put("disputeId", dispute.id).put("uncovered", uncovered).put("creditOrders", JsonArray(candidates)).put("revoked", JsonArray(revoked))
        )
    }

    // ============================================================================================================ O12

    /** O12 (21 section 5.3): the order goes back to `statusBeforeDispute`; nothing is re-granted. */
    private suspend fun won(t: DisputeTx, order: MarketOrder, dispute: MarketDispute, input: DisputeInput) {
        val conn = t.conn
        val now = clock.now()
        val decision = OrderStateMachine.decide(
            OrderState(order.status, order.reservationState, order.expiresAt, false, order.paidAmount, order.statusBeforeDispute, null), OrderEvent.DisputeWon
        )

        if (decision !is OrderTransition.Move) throw InvalidOrderTransition()

        val moved = conn.preparedQuery("UPDATE ${table("market_order")} SET `status` = ?, `updatedAt` = GREATEST(?, `updatedAt` + 1) WHERE `id` = ? AND `status` = ?")
            .execute(Tuple.of(decision.to.name, now, order.id, order.status.name)).coAwait().rowCount()

        if (moved != 1) throw OrderChangedException(order.id, "the status moved under the lock")

        orderEvents.add(
            MarketOrderEvent(
                orderId = order.id, type = OrderEventType.STATUS_CHANGED, fromStatus = order.status.name, toStatus = decision.to.name, actorType = actorOf(input),
                actorUserId = input.actorUserId, createdAt = now, updatedAt = now
            ),
            conn
        )

        adjustSold(conn, order, orderItems.getByOrderIds(listOf(order.id), conn), +1)

        for (effect in decision.effects) {
            if (effect is OrderEffect.QueueWebhook) effects.webhook(conn, orders.getById(order.id, conn)!!, disputes.getById(dispute.id, conn)!!, true)
        }
    }

    // ============================================================================================================ helpers

    private fun actorOf(input: DisputeInput) = if (input.source == DisputeSource.PANEL) OrderActorType.ADMIN else OrderActorType.GATEWAY

    private suspend fun timeline(conn: SqlClient, orderId: Long, type: OrderEventType, actor: OrderActorType, actorUserId: Long?, message: String?, data: JsonObject) {
        val now = clock.now()

        orderEvents.add(MarketOrderEvent(orderId = orderId, type = type, actorType = actor, actorUserId = actorUserId, message = message, data = data.encode(), createdAt = now, updatedAt = now), conn)
    }

    private suspend fun raise(t: DisputeTx) {
        for (a in t.alerts) {
            try {
                alerts.alert(a.orderId, a.code, a.data)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("raising alert {} failed: {}", a.code, e.javaClass.simpleName)
            }
        }
    }

    companion object {
        /** `endReason` of an entitlement a chargeback ends (also the upgrade successors and the credit-paid orders it takes with it). */
        const val END_REASON = "CHARGEBACK"

        const val REASON_MAX = 255

        private const val MAX_UPGRADE_HOPS = 16

        private val logger = LoggerFactory.getLogger(DisputeService::class.java)

        /** `data.dispute` of the store webhooks `order.chargeback` and `order.chargeback.won` (08 section 15.4). */
        fun disputeJson(d: MarketDispute): JsonObject = JsonObject()
            .put("id", d.id).put("status", d.status.name).put("amount", EventPayloads.money(d.amount)).put("currency", d.currency).put("reason", d.reason)
            .put("gatewayDisputeId", d.gatewayDisputeId)

        /**
         * The clawbacks of a chargeback (07 section 8.5): every credit-granting line, the whole grant of its `TOPUP` / `GIFT` transaction minus what earlier
         * clawbacks (`amount + shortfall`) took, so a replayed or second dispute never takes more than was granted (invariant O8).
         */
        internal fun topUpClawbacks(items: List<MarketOrderItem>, txs: List<MarketCreditTx>): List<Clawback.Request> {
            val granting = items.filter { (it.creditAmount ?: 0L) > 0L }

            if (granting.isEmpty()) return emptyList()

            val clawItems = granting.mapNotNull { item ->
                val grant = txs.firstOrNull { it.idempotencyKey == "orderitem:${item.id}:topup" || it.idempotencyKey == "orderitem:${item.id}:gift" } ?: return@mapNotNull null
                val already = txs.filter { it.type == CreditTxType.REVOKE && it.idempotencyKey.endsWith(":clawback:${item.id}") }.sumOf { it.amount + it.shortfall }

                Clawback.Item(item.id, grant.amount, already, maxOf(1, item.quantity), item.lineTotal)
            }

            return Clawback.compute(clawItems, Clawback.Basis.Everything)
        }
    }
}

// ================================================================================================================ event routing

/**
 * Where the inbound events that are not attempt events go (02 section 7.4): a [PaymentEvent.RefundUpdated] to the refund service (MK-111, 21 section 4), a
 * [PaymentEvent.DisputeUpdated] to the dispute service (MK-112, 21 section 5), everything else to [next] (the subscription sink, then the sink a slice installed:
 * until then such an event makes its request `FAILED`, replayable, never dropped). A refund or dispute event whose target is not an attempt (`attempt == null`,
 * a subscription target) cannot be placed on an order and goes to [next] too.
 */
class PaymentEventRouter(
    private val refunds: () -> RefundService,
    private val disputes: () -> DisputeService,
    private val next: PaymentEventSink
) : PaymentEventSink {
    override suspend fun apply(event: PaymentEvent, attempt: MarketPayment?, context: InboundEventContext) {
        when {
            event is PaymentEvent.RefundUpdated && attempt != null -> refunds().onRefundUpdated(event, attempt, context.eventKey, context.requestHash)

            event is PaymentEvent.DisputeUpdated && attempt != null -> disputes().onDisputeUpdated(event, attempt, context.eventKey, context.requestHash)

            else -> next.apply(event, attempt, context)
        }
    }
}
