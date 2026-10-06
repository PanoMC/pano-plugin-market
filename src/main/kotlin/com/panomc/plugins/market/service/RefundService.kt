package com.panomc.plugins.market.service

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.credit.Cashback
import com.panomc.plugins.market.core.credit.Clawback
import com.panomc.plugins.market.core.credit.CreditMath
import com.panomc.plugins.market.core.credit.CreditPolicy
import com.panomc.plugins.market.core.credit.RefundSplit
import com.panomc.plugins.market.core.delivery.ActionParser
import com.panomc.plugins.market.core.delivery.DeliveryError
import com.panomc.plugins.market.core.money.Conversions
import com.panomc.plugins.market.core.money.Rounding
import com.panomc.plugins.market.core.order.OrderEvent
import com.panomc.plugins.market.core.order.OrderState
import com.panomc.plugins.market.core.order.OrderStateMachine
import com.panomc.plugins.market.core.order.OrderTransition
import com.panomc.plugins.market.core.refund.RefundEffect
import com.panomc.plugins.market.core.refund.RefundEvent
import com.panomc.plugins.market.core.refund.RefundEventMatcher
import com.panomc.plugins.market.core.refund.RefundMath
import com.panomc.plugins.market.core.refund.RefundRowState
import com.panomc.plugins.market.core.refund.RefundStateMachine
import com.panomc.plugins.market.core.refund.RefundTransition
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketCreditTxDao
import com.panomc.plugins.market.db.dao.MarketDeliveryDao
import com.panomc.plugins.market.db.dao.MarketEntitlementDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderEventDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketPaymentDao
import com.panomc.plugins.market.db.dao.MarketRefundDao
import com.panomc.plugins.market.db.dao.MarketRefundItemDao
import com.panomc.plugins.market.db.dao.MarketServerStateDao
import com.panomc.plugins.market.db.model.CreditSystemKey
import com.panomc.plugins.market.db.model.CreditTxType
import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.db.model.DeliveryStatus
import com.panomc.plugins.market.db.model.EntitlementStatus
import com.panomc.plugins.market.db.model.MarketEntitlement
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderEvent
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.MarketRefund
import com.panomc.plugins.market.db.model.MarketRefundItem
import com.panomc.plugins.market.db.model.OrderActorType
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.RedemptionKind
import com.panomc.plugins.market.db.model.RefundOrigin
import com.panomc.plugins.market.db.model.RefundStatus
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.db.tx.OrderChild
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.error.CascadeDecisionRequired
import com.panomc.plugins.market.error.IdempotencyConflict
import com.panomc.plugins.market.error.InvalidOrderTransition
import com.panomc.plugins.market.error.InvalidRefundAmount
import com.panomc.plugins.market.error.InvalidState
import com.panomc.plugins.market.error.RefundNotSupported
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.RefundResult
import com.panomc.plugins.market.spi.payment.RefundState
import com.panomc.plugins.market.spi.payment.RefundSupport
import com.panomc.plugins.market.util.MoneyUtil
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.security.MessageDigest

// ================================================================================================================ the provider side

/** What `provider.refund` / `provider.queryRefund` came back with, in the terms of the refund state machine (21 section 3.3). */
sealed class GatewayAnswer {
    class Succeeded(val gatewayRefundId: String?, val refundedAmount: Long?) : GatewayAnswer()

    class Pending(val gatewayRefundId: String?, val buyerActionUrl: String?) : GatewayAnswer()

    /** The gateway (or the provider) said no: the row is `FAILED`, the admin decides (nothing is retried automatically, it is money). */
    class Failed(val code: String, val message: String?, val retryable: Boolean) : GatewayAnswer()

    /** No answer: timeout, a crash, or `queryRefund` without a result. The row keeps its state. */
    data object Unknown : GatewayAnswer()
}

/** Everything one provider call of a refund needs, read before the call (no transaction is open during it). */
class GatewayCall(
    val order: MarketOrder,
    val items: List<MarketOrderItem>,
    val attempt: MarketPayment,
    val refund: MarketRefund,
    val lines: List<MarketRefundItem>,
    val full: Boolean
)

/** The provider boundary of the refund service: tests use the real [PaymentServiceRefundGateway] on a scripted provider. */
interface RefundGateway {
    /** What the provider behind [providerId] can refund; `null` when none is registered. */
    suspend fun support(providerId: String): RefundSupport?

    suspend fun refund(call: GatewayCall): GatewayAnswer

    suspend fun query(call: GatewayCall): GatewayAnswer
}

/** [RefundGateway] on [PaymentService]: the provider is resolved with its decrypted settings and called under the lock of its attempt. */
class PaymentServiceRefundGateway(private val payments: PaymentService, private val client: suspend () -> SqlClient) : RefundGateway {
    override suspend fun support(providerId: String): RefundSupport? = payments.refundSupportOf(providerId, client())

    override suspend fun refund(call: GatewayCall): GatewayAnswer =
        answer(asking = false) { payments.callRefund(call.order, call.items, call.attempt, call.refund, call.lines, call.full, client()) }

    override suspend fun query(call: GatewayCall): GatewayAnswer = answer(asking = true) { payments.callQueryRefund(call.order, call.attempt, call.refund, client()) }

    /**
     * [asking] is `queryRefund`: a question that errors (the gateway is down, the provider plugin is unloaded or being updated) is no answer, so it is
     * [GatewayAnswer.Unknown] whatever the [ProviderException] says (21 section 3.3 "`Unknown` changes nothing"); only a [RefundResult.Failed] it returns fails a
     * row. A [ProviderException] of `provider.refund` itself is the provider's refusal (tx2 table: `FAILED`, the admin retries).
     */
    private suspend fun answer(asking: Boolean, block: suspend () -> RefundResult): GatewayAnswer = try {
        when (val result = block()) {
            is RefundResult.Succeeded -> GatewayAnswer.Succeeded(result.gatewayRefundId, result.refundedAmount?.amount)
            is RefundResult.Pending -> GatewayAnswer.Pending(result.gatewayRefundId, result.buyerActionUrl)
            is RefundResult.Failed -> GatewayAnswer.Failed(result.code.take(64), result.message?.take(512), result.retryable)
            is RefundResult.Unknown -> GatewayAnswer.Unknown
        }
    } catch (e: PaymentService.RefundCallTimeout) {
        GatewayAnswer.Unknown
    } catch (e: ProviderException) {
        if (asking) {
            LoggerFactory.getLogger(PaymentServiceRefundGateway::class.java).warn("refund query got no answer, the row keeps its state: {}", e.code.name)

            GatewayAnswer.Unknown
        } else {
            GatewayAnswer.Failed(e.code.name, (e.adminMessage ?: e.message)?.take(512), e.retryable)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // a bug or a broken connection after the request left: nobody knows what the gateway did
        LoggerFactory.getLogger(PaymentServiceRefundGateway::class.java).warn("refund call ended in an unknown outcome: {}", e.javaClass.simpleName)

        GatewayAnswer.Unknown
    }
}

// ================================================================================================================ requests and views

/** `POST /orders/:id/refunds` and `GET /orders/:id/refund-preview` (04 section 7). Money is x100 in the order currency, [creditAmount] is credits x100. */
class RefundInput(
    val amount: Long? = null,
    val items: List<RefundMath.ItemRequest>? = null,
    val gatewayAmount: Long? = null,
    val creditAmount: Long? = null,
    val reason: String? = null,
    /** `null` = `MarketConfig.revokeOnRefund`. */
    val revoke: Boolean? = null,
    val revokeFirst: Boolean = false,
    val cascadeUpgrade: Boolean? = null,
    val restock: Boolean = false,
    val manual: Boolean = false
) {
    /** The canonical text of the request, hashed for the `Idempotency-Key` check (the same key with another body is a 409). */
    fun hash(orderId: Long): String {
        val canonical = listOf(
            orderId, amount, items?.sortedBy { it.itemId }?.joinToString(",") { "${it.itemId}x${it.quantity}" }, gatewayAmount, creditAmount, reason?.trim(), revoke,
            revokeFirst, cascadeUpgrade, restock, manual
        ).joinToString("|")

        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/** `UPGRADE_DEPENDENT` (21 section 5.4): the refunded line was upgraded; [successorOrderId] owns the live entitlement that was partly paid with it. */
class UpgradeDependent(val orderItemId: Long, val successorEntitlementId: Long, val successorOrderId: Long, val successorOrderItemId: Long, val deduction: Long)

/** The numbers of a refund before it exists (07 section 7.3, 21 section 3.1): what [RefundService.request] would write for the same input. */
class RefundPreview(
    val split: RefundSplit.Split,
    val limits: RefundSplit.Limits,
    val splitMode: RefundSplit.Mode,
    val manual: Boolean,
    val recommendRevokeFirst: Boolean,
    val warnings: List<JsonObject>
) {
    fun toJson(): JsonObject = JsonObject()
        .put("amount", MoneyUtil.toDecimal(split.amount)).put("gatewayAmount", MoneyUtil.toDecimal(split.gatewayPart))
        .put("creditAmount", MoneyUtil.toDecimal(split.creditPart)).put("creditValue", MoneyUtil.toDecimal(split.creditValuePart))
        .put("split", splitMode.name).put("mode", if (manual) "MANUAL" else "GATEWAY")
        .put("max", MoneyUtil.toDecimal(limits.max)).put("maxGateway", MoneyUtil.toDecimal(limits.maxGateway)).put("maxCredit", MoneyUtil.toDecimal(limits.maxCredit))
        .put("recommendRevokeFirst", recommendRevokeFirst).put("warnings", JsonArray(warnings))
}

/** The answer of [RefundService.request], [RefundService.retry] and [RefundService.cancel]: the row as it is now. [failure] is set when the gateway refused this call. */
class RefundOutcome(val refund: MarketRefund, val replay: Boolean, val failure: GatewayAnswer.Failed?)

/** What [RefundService.reconcile] did in one run. */
class RefundReconcileReport(var released: Int = 0, var sent: Int = 0, var polled: Int = 0, var timedOut: Int = 0, var errors: Int = 0)

private class PendingAlert(val orderId: Long, val code: String, val data: JsonObject)

/** One transaction's side data: alerts to raise after the commit and the refund whose gateway call is due after it. */
private class Tx(val conn: SqlConnection) {
    val alerts = ArrayList<PendingAlert>()
    var send: Long? = null
    var failure: GatewayAnswer.Failed? = null
}

/** What the gateway call or the inbound event told the row besides its state. */
private class Patch(val gatewayRefundId: String? = null, val gatewayRefundedAmount: Long? = null, val buyerActionUrl: String? = null)

private class Plan(
    val order: MarketOrder,
    val items: List<MarketOrderItem>,
    val attempt: MarketPayment?,
    val split: RefundSplit.Split,
    val limits: RefundSplit.Limits,
    val splitWarnings: List<RefundSplit.Warning>,
    val itemRows: List<RefundMath.ItemAmount>,
    val revoke: Boolean,
    val becomesFull: Boolean,
    val dependents: List<UpgradeDependent>,
    val notSupported: Boolean
)

// ================================================================================================================ the service

/**
 * The refund flow (MK-111; 21 sections 2 to 4, 07 section 7, 08 section 11.2):
 *
 * - [preview] and [request] (tx1: lock, idempotency, remainders, split, capability, upgrade dependants, the row and its lines; then credit-only / manual
 *   settles at once, `revokeFirst` plans the `REVOKE` rows and waits, everything else is sent), the gateway call outside any transaction and tx2 ([applyAnswer]);
 * - O10 ([applySucceeded], one transaction under `Locks.forOrder(RELEASE)`): books, lines, status, entitlements and upgrade dependants, revoke, restock, codes,
 *   creator earning, cashback and top-up clawback, subscription, credit note, mail, webhook;
 * - [retry] and [cancel] (the same idempotency key is sent again), the inbound `RefundUpdated` ([onRefundUpdated]: matching by [RefundEventMatcher], then the
 *   state machine) and the work of `RefundReconcileJob` ([reconcile]: `revokeFirst` release and timeout, `queryRefund`, unsent `SYSTEM` rows).
 *
 * Provider calls never run in a transaction. A row moves only through [RefundStateMachine] (`UPDATE ... WHERE status = :from`). A refund the gateway confirmed is
 * never set `FAILED`: its gateway part is always booked, the credit part is clamped and `OVER_REFUND` is raised. A refund of money that is not on the books of the
 * order (the duplicate attempt of a paid order, the money of a rejected review, `paymentId` other than the order's own) only moves `market_payment.refundedAmount`.
 */
class RefundService(
    private val db: MarketDb,
    private val locks: Locks,
    private val clock: Clock,
    private val config: () -> MarketConfig,
    private val orders: MarketOrderDao,
    private val orderItems: MarketOrderItemDao,
    private val orderEvents: MarketOrderEventDao,
    private val payments: MarketPaymentDao,
    private val refunds: MarketRefundDao,
    private val refundItems: MarketRefundItemDao,
    private val deliveries: MarketDeliveryDao,
    private val entitlements: MarketEntitlementDao,
    private val creditTxs: MarketCreditTxDao,
    private val credits: CreditService,
    private val deliveryService: DeliveryService,
    private val entitlementService: EntitlementService,
    private val gateway: RefundGateway,
    private val servers: MarketServerStateDao? = null,
    private val effects: RefundEffects = RefundEffects.NONE,
    private val alerts: RefundAlerts = RefundAlerts.LOG_ONLY,
    /** How long a replay of a request waits for the call of the first one to settle (real time), so that every answer of one key is the same. */
    private val settleWaitMs: Long = SETTLE_WAIT_MS
) {
    private fun table(name: String) = "`${orders.prefix()}$name`"

    // ============================================================================================================ preview

    /** 21 section 3.1: steps 2 to 4 of the request without writing anything, with the warnings of 07 section 7.3. */
    suspend fun preview(orderId: Long, input: RefundInput): RefundPreview = db.tx { conn ->
        val order = orders.getById(orderId, conn) ?: throw NoSuchElementException("order $orderId does not exist")
        val items = orderItems.getByOrderIds(listOf(orderId), conn)
        val plan = plan(conn, order, items, refunds.getByOrderId(orderId, conn), input)

        RefundPreview(plan.split, plan.limits, splitModeOf(input), input.manual, recommendRevokeFirst(conn, plan, input), warnings(conn, plan, input))
    }

    private fun splitModeOf(input: RefundInput) = if (input.gatewayAmount != null || input.creditAmount != null) RefundSplit.Mode.OVERRIDE else RefundSplit.Mode.PROPORTIONAL

    private suspend fun warnings(conn: SqlClient, plan: Plan, input: RefundInput): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        val creditName = config().creditName

        for (w in plan.splitWarnings) {
            out += JsonObject().put("code", w.code.name).also { o ->
                w.gatewayAmount?.let { o.put("gatewayAmount", MoneyUtil.toDecimal(it)) }
                w.creditAmount?.let { o.put("creditAmount", MoneyUtil.toDecimal(it)) }
                w.refundSupport?.let { o.put("refundSupport", it.name) }
                w.forfeitedCredits?.let { o.put("forfeitedCredits", MoneyUtil.toDecimal(it)) }

                if (w.code == RefundSplit.WarningCode.MIXED_PAYMENT_SPLIT) o.put("creditName", creditName)
            }
        }

        val order = plan.order
        val refundedAfter = order.refundedTotal + plan.split.amount
        val txs = creditTxs.getByOrderId(order.id, conn)
        val cashback = txs.firstOrNull { it.idempotencyKey == "order:${order.id}:cashback" }

        if (cashback != null && order.userId != null) {
            val already = txs.filter { it.type == CreditTxType.CASHBACK_REVERSAL }.sumOf { it.amount + it.shortfall }
            val request = Cashback.reversal(cashback.amount, already, refundedAfter, order.totalPrice)

            if (request > 0L) {
                val taken = CreditPolicy.takeAvailable(request, credits.balance(order.userId, conn))

                out += JsonObject().put("code", "CASHBACK_REVERSAL").put("amount", MoneyUtil.toDecimal(request)).put("shortfall", MoneyUtil.toDecimal(taken.shortfall))
            }
        }

        if (plan.revoke) {
            val claw = clawbackRequests(conn, order, plan.items, plan.itemRows.map { MarketRefundItem(orderItemId = it.itemId, quantity = it.quantity, amount = it.amount) }, plan.becomesFull, plan.split.amount, txs)
            val total = claw.sumOf { it.amount }
            val recipient = order.recipientUserId ?: order.userId

            if (total > 0L && recipient != null) {
                val taken = CreditPolicy.takeAvailable(total, credits.balance(recipient, conn))

                out += JsonObject().put("code", "CREDIT_CLAWBACK").put("amount", MoneyUtil.toDecimal(total)).put("shortfall", MoneyUtil.toDecimal(taken.shortfall))
            }
        }

        if (effects.olderSubscriptionPeriod(conn, order)) {
            out += JsonObject().put("code", "OLDER_SUBSCRIPTION_PERIOD").put("subscriptionId", order.subscriptionId)
        }

        for (d in plan.dependents) {
            out += JsonObject().put("code", "UPGRADE_DEPENDENT").put("successorOrderId", d.successorOrderId).put("deduction", MoneyUtil.toDecimal(d.deduction))
        }

        return out
    }

    /** `recommendRevokeFirst` (21 section 3.5): a server a revoke of the refunded lines would have to reach is not ready (never seen, or silent for two minutes). */
    private suspend fun recommendRevokeFirst(conn: SqlClient, plan: Plan, input: RefundInput): Boolean {
        val states = servers ?: return false
        val refunded = revokeScope(plan.items, plan.itemRows.map { it.itemId }, plan.becomesFull)
        val wanted = HashSet<Long>()

        for (item in plan.items.filter { it.id in refunded }) {
            item.targetServerId?.let { wanted += it }

            val actions = ActionParser.parseStored(item.snapshot?.takeIf { it.isNotBlank() }?.let { runCatching { JsonObject(it).getJsonArray("actions")?.encode() }.getOrNull() }).actions

            for (a in actions.filter { it.isServerAction }) wanted += a.targetServers
        }

        if (wanted.isEmpty()) return false

        val now = clock.now()

        return wanted.any { id ->
            val seen = states.getByServerId(id, conn)?.lastSeenAt

            seen == null || now - seen > SERVER_SILENT_MS
        }
    }

    // ============================================================================================================ planning (21 section 3.2 steps 2 to 4)

    private fun amounts(order: MarketOrder) = RefundMath.OrderAmounts(
        totalPrice = order.totalPrice, gatewayAmount = order.gatewayAmount, creditValue = order.creditValue, creditAmount = order.creditAmount,
        refundedTotal = order.refundedTotal, refundedGatewayAmount = order.refundedGatewayAmount, refundedCreditAmount = order.refundedCreditAmount,
        unit = CreditMath.unit(order.currency), anonymised = order.userId == null
    )

    /**
     * Whether a refund of [amount] money and [creditPart] credits takes the last value of the order. Money decides; an order that has no money (credits only,
     * `totalPrice = 0`, 05 row 53, the `Split(0, 0, 0, credits)` of MK-090) is empty when its credits are all back, so a partial credit refund of it is not.
     */
    private fun emptiesOrder(order: MarketOrder, amount: Long, creditPart: Long): Boolean =
        order.refundedTotal + amount >= order.totalPrice && (order.totalPrice > 0L || order.refundedCreditAmount + creditPart >= order.creditAmount)

    /** A refund whose money is not on the books of the order (see the class comment). */
    private fun moneyOnly(order: MarketOrder, refund: MarketRefund): Boolean =
        (refund.paymentId != null && refund.paymentId != order.paymentId) || order.status !in PAID_STATES

    private fun books(order: MarketOrder, rows: List<MarketRefund>) =
        rows.filter { !moneyOnly(order, it) }.map { RefundMath.RefundAmounts(it.id, it.status, it.amount, it.gatewayAmount, it.creditAmount) }

    private suspend fun plan(conn: SqlClient, order: MarketOrder, items: List<MarketOrderItem>, rows: List<MarketRefund>, input: RefundInput, excluding: Long? = null): Plan {
        if (order.status != OrderStatus.COMPLETED && order.status != OrderStatus.PARTIALLY_REFUNDED) throw InvalidOrderTransition(reason = order.status.name)

        val reason = input.reason?.trim()

        if (reason != null && reason.length > REASON_MAX) throw RequestValueException("reason", "TOO_LONG")
        if (input.manual && (reason == null || reason.length < REASON_MIN)) throw RequestValueException("reason", "REQUIRED")

        val unit = CreditMath.unit(order.currency)
        val lines = items.map { RefundMath.Line(it.id, it.quantity, it.lineTotal, it.refundedQuantity, it.refundedAmount) }
        var requested = input.amount
        var itemRows = emptyList<RefundMath.ItemAmount>()

        if (input.items != null) {
            if (input.amount != null) throw RequestValueException("items", "AMOUNT_AND_ITEMS")

            when (val r = RefundMath.itemsAmount(lines, input.items, unit)) {
                is RefundMath.ItemsResult.Invalid -> throw RequestValueException("items", r.problem.name)
                is RefundMath.ItemsResult.Ok -> {
                    itemRows = r.items
                    requested = r.total
                }
            }
        }

        val attempt = order.paymentId?.let { payments.getById(it, conn) }
        val support = attempt?.let { gateway.support(it.providerId) }
        val capability = if (input.manual) RefundSplit.GatewayRefund(support ?: RefundSupport.NONE, true) else support?.let { RefundSplit.GatewayRefund(it) }
        val splitOrder = RefundMath.splitOrder(amounts(order), books(order, rows), excluding)
        val request = RefundSplit.Request(splitModeOf(input), requested, input.gatewayAmount, input.creditAmount)
        // the refund of an older period of a subscription never takes the goods back: they are the subscription's, which is still running (09 section 10.4)
        val revoke = !effects.olderSubscriptionPeriod(conn, order) && (input.revoke ?: config().revokeOnRefund)

        suspend fun finish(split: RefundSplit.Split, limits: RefundSplit.Limits, warnings: List<RefundSplit.Warning>, notSupported: Boolean): Plan {
            val full = emptiesOrder(order, split.amount, split.creditPart)

            return Plan(order, items, attempt, split, limits, warnings, itemRows, revoke, full, dependentsOf(conn, order, items, itemRows, full), notSupported)
        }

        return when (val result = RefundSplit.compute(splitOrder, request, capability)) {
            is RefundSplit.Result.Invalid ->
                throw InvalidRefundAmount(MoneyUtil.toDecimal(result.limits.max), MoneyUtil.toDecimal(result.limits.maxGateway), MoneyUtil.toDecimal(result.limits.maxCredit))

            is RefundSplit.Result.NotSupported -> finish(result.split, result.limits, result.warnings, true)

            is RefundSplit.Result.Ok -> {
                // a gateway part needs the payment attempt the provider took the money on
                val gatewayCall = result.split.gatewayPart > 0L && !input.manual

                if (gatewayCall && attempt == null) finish(result.split, result.limits, result.warnings, true) else finish(result.split, result.limits, result.warnings, false)
            }
        }
    }

    /**
     * An entitlement that was upgraded (21 section 5.4): still `UPGRADED`, or `REVOKED` by a refund (`revokeFirst` took the line back before the money, which
     * turns `UPGRADED` into `REVOKED` and keeps `replacedById`). It is found by its link, not by its status, so the dependant is still there when the refund
     * succeeds, or when a refund that timed out is made again.
     */
    private fun wasUpgraded(e: MarketEntitlement): Boolean =
        e.replacedById != null && (e.status == EntitlementStatus.UPGRADED || (e.status == EntitlementStatus.REVOKED && e.endReason == END_REASON_REFUND))

    /** 21 section 5.4: the refunded lines whose entitlement was upgraded and whose last units go back (an amount-only refund counts when it empties the order). */
    private suspend fun dependentsOf(conn: SqlClient, order: MarketOrder, items: List<MarketOrderItem>, itemRows: List<RefundMath.ItemAmount>, full: Boolean): List<UpgradeDependent> {
        val touched = LinkedHashMap<Long, Long>()

        if (itemRows.isNotEmpty()) {
            val byId = items.associateBy { it.id }

            for (r in itemRows) {
                val item = byId[r.itemId] ?: continue

                if (item.refundedQuantity + r.quantity >= item.quantity) touched[item.id] = r.amount
            }
        } else if (full) {
            for (item in items) touched[item.id] = maxOf(0L, item.lineTotal - item.refundedAmount)
        }

        val out = ArrayList<UpgradeDependent>()

        for ((itemId, deduction) in touched) {
            val upgraded = entitlements.getByOrderItemId(itemId, conn).firstOrNull { wasUpgraded(it) } ?: continue
            val successor = entitlementService.liveSuccessor(conn, upgraded) ?: continue

            out += UpgradeDependent(itemId, successor.id, successor.orderId, successor.orderItemId, deduction)
        }

        return out
    }

    // ============================================================================================================ request (tx1)

    /**
     * 21 section 3.2: the refund of [orderId] under [idempotencyKey] (`panel` origin). A replay (same key, same body) returns the row as it is, after the call of
     * the first request settled; the same key with another body is `IdempotencyConflict`. The gateway call, when due, runs after tx1 committed.
     */
    suspend fun request(orderId: Long, input: RefundInput, idempotencyKey: String, actorUserId: Long?): RefundOutcome {
        val hash = input.hash(orderId)
        val replay = BooleanArray(1)
        val tx = db.txRestartingOnOrderChange { conn ->
            locks.forOrder(conn, orderId, OrderLockScope.RELEASE, cashback = true) { locked ->
                val t = Tx(conn)

                locks.children(conn, orderId, OrderChild.REFUND)

                val existing = refunds.getByIdempotencyKey(idempotencyKey, conn)

                if (existing != null) {
                    if (existing.orderId != orderId || existing.idempotencyHash != hash) throw IdempotencyConflict()

                    replay[0] = true
                    t.send = null

                    return@forOrder t to existing.id
                }

                t to create(t, locked.order.id, locked.items, input, idempotencyKey, hash, actorUserId)
            }
        }
        val (t, refundId) = tx

        raise(t)

        if (replay[0]) return RefundOutcome(awaitSettled(refundId), true, null)

        return settleOutcome(t, refundId, replay = false)
    }

    private suspend fun settleOutcome(t: Tx, refundId: Long, replay: Boolean): RefundOutcome {
        val due = t.send
        val failure = if (due != null) send(due) as? GatewayAnswer.Failed else null

        return RefundOutcome(db.tx { refunds.getById(refundId, it)!! }, replay, failure)
    }

    private suspend fun create(t: Tx, orderId: Long, lockedItems: List<MarketOrderItem>, input: RefundInput, key: String, hash: String, actor: Long?): Long {
        val conn = t.conn
        val order = orders.getById(orderId, conn) ?: throw OrderChangedExceptionFor(orderId)
        val plan = plan(conn, order, lockedItems, refunds.getByOrderId(orderId, conn), input)

        if (plan.notSupported) throw RefundNotSupported()
        if (plan.dependents.isNotEmpty() && input.cascadeUpgrade == null) throw CascadeDecisionRequired()

        val split = plan.split
        val gatewayCall = split.gatewayPart > 0L && !input.manual
        val now = clock.now()
        val revokeFirst = input.revokeFirst && plan.revoke
        val id = refunds.add(
            MarketRefund(
                orderId = orderId, paymentId = if (gatewayCall) plan.attempt?.id else null, providerId = if (gatewayCall) plan.attempt?.providerId else null,
                status = RefundStatus.REQUESTED, origin = RefundOrigin.PANEL, idempotencyKey = key, idempotencyHash = hash, amount = split.amount,
                gatewayAmount = split.gatewayPart, creditAmount = split.creditPart, creditValue = split.creditValuePart, currency = order.currency,
                reason = input.reason?.trim(), revoke = plan.revoke, revokeFirst = revokeFirst, cascadeUpgrade = input.cascadeUpgrade, restock = input.restock,
                initiatedBy = actor, createdAt = now, updatedAt = now
            ),
            conn
        ) ?: throw IdempotencyConflict()

        for (r in plan.itemRows) refundItems.add(MarketRefundItem(refundId = id, orderItemId = r.itemId, quantity = r.quantity, amount = r.amount, createdAt = now, updatedAt = now), conn)

        timeline(
            conn, orderId, OrderEventType.REFUND_REQUESTED, OrderActorType.ADMIN, actor, null,
            JsonObject().put("refundId", id).put("origin", RefundOrigin.PANEL.name).put("amount", split.amount).put("gatewayAmount", split.gatewayPart)
                .put("creditAmount", split.creditPart).put("manual", input.manual).put("revokeFirst", revokeFirst)
        )

        val row = refunds.getById(id, conn)!!

        when {
            revokeFirst -> {
                // 21 section 3.5: the REVOKE rows are planned now, the row waits for them (the reconcile job looks every 60 s)
                val targets = revokeTargets(plan.items, plan.itemRows.map { MarketRefundItem(orderItemId = it.itemId, quantity = it.quantity, amount = it.amount) }, plan.becomesFull)

                if (targets.isNotEmpty()) entitlementService.revoke(conn, deliveryService, order, plan.items, targets, END_REASON_REFUND, DeliveryError.ORDER_REVOKED)

                deliveryService.refreshFulfillment(conn, orderId)
                set(conn, id, linkedMapOf("nextQueryAt" to now + HOLD_RECHECK_MS))
            }

            !gatewayCall -> move(t, row, RefundEvent.Reported(RefundState.SUCCEEDED))

            else -> {
                claim(conn, id, now)
                t.send = id
            }
        }

        return id
    }

    // ============================================================================================================ the gateway call (21 section 3.3)

    private class Snapshot(val call: GatewayCall)

    private suspend fun snapshot(refundId: Long): Snapshot? = db.tx { conn ->
        val refund = refunds.getById(refundId, conn) ?: return@tx null

        if (refund.status != RefundStatus.REQUESTED && refund.status != RefundStatus.PENDING) return@tx null

        val order = orders.getById(refund.orderId, conn) ?: return@tx null
        val attempt = refund.paymentId?.let { payments.getById(it, conn) } ?: return@tx null
        val inFlight = books(order, refunds.getByOrderId(order.id, conn)).filter { it.status == RefundStatus.REQUESTED || it.status == RefundStatus.PENDING }.sumOf { it.gatewayAmount }
        val full = if (moneyOnly(order, refund)) true else RefundMath.isFullGatewayRefund(amounts(order), refund.gatewayAmount, inFlight)

        Snapshot(GatewayCall(order, orderItems.getByOrderIds(listOf(order.id), conn), attempt, refund, refundItems.getByRefundId(refund.id, conn), full))
    }

    /** Sends [refundId] to its provider (outside any transaction) and applies the answer in tx2. Called after the commit that claimed the row; `null` when there was nothing to send. */
    private suspend fun send(refundId: Long): GatewayAnswer? {
        val snap = snapshot(refundId) ?: return null
        val answer = gateway.refund(snap.call)

        applyAnswer(refundId, answer)

        return answer
    }

    /**
     * tx2 (21 section 3.3 table): `Succeeded` -> `SUCCEEDED` and O10, `Pending` -> `PENDING` (query in 5 minutes), `Failed` -> `FAILED`, `Unknown` -> nothing (the row
     * stays `REQUESTED`, the claim of the call runs out after 60 s and the reconcile job takes over). Returns the row, or `null` for an answer that changed nothing.
     */
    suspend fun applyAnswer(refundId: Long, answer: GatewayAnswer): MarketRefund? {
        if (answer is GatewayAnswer.Unknown) return null

        val orderId = db.tx { refunds.getById(refundId, it)?.orderId } ?: return null
        val tx = db.txRestartingOnOrderChange { conn ->
            locks.forOrder(conn, orderId, OrderLockScope.RELEASE, cashback = true) {
                val t = Tx(conn)

                locks.children(conn, orderId, OrderChild.REFUND)

                val row = refunds.getById(refundId, conn) ?: return@forOrder t to null
                val event = when (answer) {
                    is GatewayAnswer.Succeeded -> RefundEvent.Reported(RefundState.SUCCEEDED)
                    is GatewayAnswer.Pending -> RefundEvent.Reported(RefundState.PENDING)
                    is GatewayAnswer.Failed -> RefundEvent.Reported(RefundState.FAILED, answer.code, answer.message)
                    GatewayAnswer.Unknown -> error("unreachable")
                }
                val patch = when (answer) {
                    is GatewayAnswer.Succeeded -> Patch(answer.gatewayRefundId, answer.refundedAmount)
                    is GatewayAnswer.Pending -> Patch(answer.gatewayRefundId, null, answer.buyerActionUrl)
                    else -> Patch()
                }

                move(t, row, event, patch)

                t to refunds.getById(refundId, conn)
            }
        }

        raise(tx.first)

        return tx.second
    }

    // ============================================================================================================ the state machine step

    private fun rowState(refund: MarketRefund, now: Long): RefundRowState {
        if (refund.status != RefundStatus.REQUESTED) return RefundRowState(refund.status)

        val waiting = refund.revokeFirst && refund.queryCount == 0

        return RefundRowState(refund.status, revokeFirstWaiting = waiting, outcomeUnknown = !waiting && !callInFlight(refund, now))
    }

    /**
     * A gateway call of [refund] is running: [claim] wrote `nextQueryAt = updatedAt + 60 s`. A row the reconcile job asked about is scheduled further out
     * (30 minutes or more after its update) and is therefore an unknown outcome the admin can retry or cancel, not a call in flight.
     */
    private fun callInFlight(refund: MarketRefund, now: Long): Boolean =
        refund.status == RefundStatus.REQUESTED && refund.queryCount >= 1 && refund.nextQueryAt != null && refund.nextQueryAt > now && refund.nextQueryAt - refund.updatedAt <= CALL_CLAIM_MS

    /** Applies [event] to [row] (locked): the decision of the pure machine, the conditional status update, then its effects in order. */
    private suspend fun move(t: Tx, row: MarketRefund, event: RefundEvent, patch: Patch = Patch()): Boolean {
        val conn = t.conn
        val now = clock.now()
        val decision = RefundStateMachine.decide(rowState(row, now), event)

        when (decision) {
            is RefundTransition.Rejected -> throw InvalidState(decision.state.name)

            RefundTransition.NoOp -> {
                // a late gateway fact about a settled row still fills the ids it brought
                storeGatewayFacts(conn, row, patch)

                return false
            }

            is RefundTransition.Move -> {
                val sets = LinkedHashMap<String, Any?>()
                var resend = false
                var runOrderEffects = false

                for (effect in decision.effects) {
                    when (effect) {
                        RefundEffect.RunOrderEffects -> runOrderEffects = true
                        RefundEffect.StampCompleted -> sets["completedAt"] = now
                        is RefundEffect.ScheduleQuery -> sets["nextQueryAt"] = now + effect.afterMs
                        RefundEffect.ClearQuery -> sets["nextQueryAt"] = null
                        is RefundEffect.RecordFailure -> {
                            sets["failureCode"] = effect.code?.take(64)
                            sets["failureMessage"] = effect.message?.take(512)
                        }
                        RefundEffect.ClearFailure -> {
                            sets["failureCode"] = null
                            sets["failureMessage"] = null
                        }
                        RefundEffect.ResendToGateway -> resend = true
                        is RefundEffect.PanelAlert -> t.alerts += PendingAlert(row.orderId, effect.code, JsonObject().put("refundId", row.id))
                    }
                }

                if (resend) {
                    sets["queryCount"] = row.queryCount + 1
                    sets["nextQueryAt"] = now + CALL_CLAIM_MS
                }

                patchColumns(conn, row, patch, sets)

                if (!set(conn, row.id, sets, fromStatus = row.status, toStatus = decision.to)) throw IllegalStateException("refund ${row.id} moved under the order lock")

                logMove(conn, row, decision.to, event)

                if (resend) t.send = row.id

                if (runOrderEffects) applySucceeded(t, refunds.getById(row.id, conn)!!)

                return true
            }
        }
    }

    private suspend fun set(conn: SqlClient, id: Long, columns: Map<String, Any?>, fromStatus: RefundStatus? = null, toStatus: RefundStatus? = null): Boolean {
        val sets = LinkedHashMap<String, Any?>(columns)

        if (toStatus != null) sets["status"] = toStatus.name

        sets["updatedAt"] = clock.now()

        val where = if (fromStatus != null) " AND `status` = ?" else ""
        val values = ArrayList<Any?>(sets.values).also {
            it += id

            if (fromStatus != null) it += fromStatus.name
        }

        return conn.preparedQuery("UPDATE ${table("market_refund")} SET ${sets.keys.joinToString(", ") { "`$it` = ?" }} WHERE `id` = ?$where")
            .execute(Tuple.from(values)).coAwait().rowCount() == 1
    }

    /** `gatewayRefundId` (when no other row carries it: `uq_provider_refund`), the gateway-side amount and the buyer action url of a call or an event. */
    private suspend fun patchColumns(conn: SqlClient, row: MarketRefund, patch: Patch, sets: MutableMap<String, Any?>) {
        val id = patch.gatewayRefundId?.takeIf { it.isNotBlank() }

        if (id != null && row.gatewayRefundId == null && row.providerId != null) {
            val owner = refunds.getByProviderRefund(row.providerId, id, conn)

            if (owner == null || owner.id == row.id) sets["gatewayRefundId"] = id.take(191)
        }

        patch.gatewayRefundedAmount?.let { sets["gatewayRefundedAmount"] = it }
        patch.buyerActionUrl?.let { sets["buyerActionUrl"] = it.take(1024) }
    }

    private suspend fun storeGatewayFacts(conn: SqlClient, row: MarketRefund, patch: Patch) {
        val sets = LinkedHashMap<String, Any?>()

        patchColumns(conn, row, patch, sets)

        if (sets.isNotEmpty()) set(conn, row.id, sets)
    }

    /** Claims the right to talk to the gateway about [id] (an in-flight marker for 60 s that the reconcile job and a retry respect). */
    private suspend fun claim(conn: SqlClient, id: Long, now: Long): Boolean =
        conn.preparedQuery("UPDATE ${table("market_refund")} SET `queryCount` = `queryCount` + 1, `nextQueryAt` = ?, `updatedAt` = ? WHERE `id` = ? AND `status` = 'REQUESTED'")
            .execute(Tuple.of(now + CALL_CLAIM_MS, now, id)).coAwait().rowCount() == 1

    private suspend fun logMove(conn: SqlClient, row: MarketRefund, to: RefundStatus, event: RefundEvent) {
        val type = when (to) {
            RefundStatus.FAILED -> OrderEventType.REFUND_FAILED
            RefundStatus.CANCELLED -> OrderEventType.NOTE
            else -> return
        }
        val actor = if (event is RefundEvent.AdminCancel || event is RefundEvent.AdminRetry) OrderActorType.ADMIN else OrderActorType.SYSTEM

        timeline(
            conn, row.orderId, type, actor, null, if (to == RefundStatus.CANCELLED) "REFUND_CANCELLED" else null,
            JsonObject().put("refundId", row.id).put("status", to.name).also { o -> (event as? RefundEvent.Reported)?.failureCode?.let { o.put("code", it) } }
        )
    }

    private suspend fun timeline(conn: SqlClient, orderId: Long, type: OrderEventType, actor: OrderActorType, actorUserId: Long?, message: String?, data: JsonObject) {
        val now = clock.now()

        orderEvents.add(MarketOrderEvent(orderId = orderId, type = type, actorType = actor, actorUserId = actorUserId, message = message, data = data.encode(), createdAt = now, updatedAt = now), conn)
    }

    private suspend fun raise(t: Tx) {
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

    private suspend fun awaitSettled(refundId: Long): MarketRefund {
        val deadline = System.nanoTime() + settleWaitMs * 1_000_000L

        while (true) {
            val row = db.tx { refunds.getById(refundId, it)!! }
            val now = clock.now()

            if (row.status != RefundStatus.REQUESTED || (row.revokeFirst && row.queryCount == 0) || !callInFlight(row, now) || System.nanoTime() > deadline) return row

            delay(SETTLE_POLL_MS)
        }
    }

    // ============================================================================================================ retry and cancel (21 section 3.3)

    /** `POST /refunds/:id/retry`: from `FAILED` or an unknown outcome, the same key and amounts, re-validated against the remainder without this row. */
    suspend fun retry(refundId: Long): RefundOutcome {
        val first = db.tx { refunds.getById(refundId, it) } ?: throw NoSuchElementException("refund $refundId does not exist")
        val t = db.txRestartingOnOrderChange { conn ->
            locks.forOrder(conn, first.orderId, OrderLockScope.RELEASE, cashback = true) { locked ->
                val t = Tx(conn)

                locks.children(conn, first.orderId, OrderChild.REFUND)

                val row = refunds.getById(refundId, conn) ?: throw NoSuchElementException("refund $refundId does not exist")
                val order = orders.getById(first.orderId, conn)!!

                if (RefundStateMachine.decide(rowState(row, clock.now()), RefundEvent.AdminRetry) is RefundTransition.Rejected) throw InvalidState(row.status.name)

                if (!moneyOnly(order, row)) {
                    if (order.status != OrderStatus.COMPLETED && order.status != OrderStatus.PARTIALLY_REFUNDED) throw InvalidOrderTransition(reason = order.status.name)

                    val rem = RefundMath.remaining(amounts(order), books(order, refunds.getByOrderId(order.id, conn)), excludingRefundId = row.id)

                    if (row.amount > rem.total || row.gatewayAmount > rem.gateway || row.creditAmount > rem.credit || row.creditValue > rem.creditValue) {
                        throw InvalidRefundAmount(MoneyUtil.toDecimal(rem.total), MoneyUtil.toDecimal(rem.gateway), MoneyUtil.toDecimal(rem.credit))
                    }
                }

                move(t, row, RefundEvent.AdminRetry)

                t
            }
        }

        raise(t)

        return settleOutcome(t, refundId, replay = false)
    }

    /** `POST /refunds/:id/cancel`: only a row nothing is running for (`REQUESTED`, unsent or `revokeFirst`-waiting) or `FAILED`; a `PENDING` row cannot be cancelled by market. */
    suspend fun cancel(refundId: Long): RefundOutcome {
        val first = db.tx { refunds.getById(refundId, it) } ?: throw NoSuchElementException("refund $refundId does not exist")
        val t = db.txRestartingOnOrderChange { conn ->
            locks.forOrder(conn, first.orderId, OrderLockScope.RELEASE, cashback = true) {
                val t = Tx(conn)

                locks.children(conn, first.orderId, OrderChild.REFUND)

                move(t, refunds.getById(refundId, conn) ?: throw NoSuchElementException("refund $refundId does not exist"), RefundEvent.AdminCancel)

                t
            }
        }

        raise(t)

        return RefundOutcome(db.tx { refunds.getById(refundId, it)!! }, false, null)
    }

    /**
     * O11 step 4 (21 section 5.2), for `DisputeService`: the refunds of the order that nothing is running for (`REQUESTED`, unsent or `revokeFirst`-waiting, or of
     * unknown outcome) become `CANCELLED` (`failureCode = CHARGEBACK`); a call in flight settles itself and `PENDING` rows are the gateway's. The caller holds
     * `Locks.forOrder(RELEASE)` and the refund rows of the order (`Locks.children`) on [conn]; returns how many rows were cancelled.
     */
    suspend fun cancelUnsentForChargeback(conn: SqlConnection, orderId: Long): Int {
        val t = Tx(conn)
        var cancelled = 0

        for (row in refunds.getByOrderId(orderId, conn)) {
            if (row.status == RefundStatus.REQUESTED && move(t, row, RefundEvent.ChargebackOpened)) cancelled++
        }

        return cancelled
    }

    // ============================================================================================================ O10 (21 section 3.4)

    /**
     * One transaction under `Locks.forOrder(RELEASE)`, called once for a row that just reached `SUCCEEDED`. A replay never gets here (the status update is
     * conditional) and every step has its own key besides: the credit rows `refund:<id>`, `refund:<id>:cashback`, `refund:<id>:clawback:<item>`, the invoice,
     * mail and webhook rows of this refund id.
     */
    private suspend fun applySucceeded(t: Tx, refund: MarketRefund) {
        val conn = t.conn
        val order = orders.getById(refund.orderId, conn) ?: throw OrderChangedExceptionFor(refund.orderId)
        val now = clock.now()

        if (moneyOnly(order, refund)) {
            if (refund.paymentId != null) addToPayment(conn, refund)

            timeline(conn, order.id, OrderEventType.REFUND_SUCCEEDED, actorOf(refund), refund.initiatedBy, null, refundData(refund).put("moneyOnly", true))

            return
        }

        val items = orderItems.getByOrderIds(listOf(order.id), conn)
        val itemRows = refundItems.getByRefundId(refund.id, conn)

        // 1. books (07 section 7.2)
        val booking = RefundMath.book(amounts(order), refund.gatewayAmount, refund.creditValue, refund.creditAmount)
        var effective = refund

        if (booking.clamped) {
            set(conn, refund.id, linkedMapOf("amount" to booking.amount, "creditValue" to booking.creditValuePart, "creditAmount" to booking.creditPart))

            effective = refunds.getById(refund.id, conn)!!
        }

        if (booking.creditPart > 0L) {
            val payer = order.userId ?: throw IllegalStateException("refund ${refund.id} returns credits of order ${order.id}, which has no payer")
            val posted = credits.post(
                Posting(
                    CreditTxType.REFUND, "refund:${refund.id}", payer, booking.creditPart, AccountRef.System(CreditSystemKey.ISSUANCE), AccountRef.User(payer),
                    orderId = order.id, refundId = refund.id
                ),
                conn
            )

            set(conn, refund.id, linkedMapOf("creditTxId" to posted.tx.id))
        }

        conn.preparedQuery(
            "UPDATE ${table("market_order")} SET `refundedTotal` = `refundedTotal` + ?, `refundedGatewayAmount` = `refundedGatewayAmount` + ?, `refundedCreditAmount` = `refundedCreditAmount` + ?, " +
                "`updatedAt` = GREATEST(?, `updatedAt` + 1) WHERE `id` = ?"
        ).execute(Tuple.of(booking.amount, booking.gatewayPart, booking.creditPart, now, order.id)).coAwait()

        if (refund.paymentId != null) addToPayment(conn, effective)

        if (booking.clamped || booking.overRefund) {
            timeline(conn, order.id, OrderEventType.NOTE, OrderActorType.SYSTEM, null, "OVER_REFUND", JsonObject().put("refundId", refund.id).put("clamped", booking.clamped).put("overRefund", booking.overRefund))
            t.alerts += PendingAlert(order.id, "OVER_REFUND", JsonObject().put("refundId", refund.id))
        }

        val refundedAfter = order.refundedTotal + booking.amount
        // A refund whose gateway call was in flight (or `PENDING` at the gateway) when a dispute opened settles on a `CHARGEBACK` order: O11 cancels only the rows nothing
        // is running for. The money went back all the same, so it is booked against the status O12 will restore (`statusBeforeDispute`), which then follows it; the
        // order itself stays `CHARGEBACK` (E2E-06 review, 17 section 9.4 R-27: one consistent end).
        val disputed = order.status == OrderStatus.CHARGEBACK && order.statusBeforeDispute != null
        val standing = if (disputed) order.statusBeforeDispute!! else order.status
        val decision = OrderStateMachine.decide(
            OrderState(standing, order.reservationState, order.expiresAt, false, order.paidAmount, order.statusBeforeDispute, null),
            OrderEvent.RefundSucceeded(refundedAfter, order.totalPrice)
        )
        val creditsAfter = order.refundedCreditAmount + booking.creditPart
        val target = when {
            decision is OrderTransition.Move -> decision.to
            // a credits-only order has no money for the machine to count (refundedTotal < 1): its credits say whether it is empty
            standing in LIVE_STATES && order.totalPrice == 0L -> if (creditsAfter >= order.creditAmount) OrderStatus.REFUNDED else OrderStatus.PARTIALLY_REFUNDED
            standing in LIVE_STATES && refundedAfter > order.totalPrice -> OrderStatus.REFUNDED
            else -> null
        }
        val fully = target == OrderStatus.REFUNDED || standing == OrderStatus.REFUNDED

        // 2. lines
        val lineDeltas = applyLines(conn, order, items, itemRows, effective.amount, fully, now)

        // 3. status
        if (disputed) {
            if (target != null && target != standing) {
                val moved = conn.preparedQuery(
                    "UPDATE ${table("market_order")} SET `statusBeforeDispute` = ?, `updatedAt` = GREATEST(?, `updatedAt` + 1) WHERE `id` = ? AND `status` = 'CHARGEBACK' AND `statusBeforeDispute` = ?"
                ).execute(Tuple.of(target.name, now, order.id, standing.name)).coAwait().rowCount()

                if (moved != 1) throw com.panomc.plugins.market.db.tx.OrderChangedException(order.id, "the dispute moved under the lock")

                timeline(
                    conn, order.id, OrderEventType.NOTE, OrderActorType.SYSTEM, null, "REFUND_DURING_DISPUTE",
                    JsonObject().put("refundId", refund.id).put("statusBeforeDispute", target.name)
                )
            }
        } else if (target != null && target != order.status) {
            val moved = conn.preparedQuery("UPDATE ${table("market_order")} SET `status` = ?, `updatedAt` = GREATEST(?, `updatedAt` + 1) WHERE `id` = ? AND `status` = ?")
                .execute(Tuple.of(target.name, now, order.id, order.status.name)).coAwait().rowCount()

            if (moved != 1) throw com.panomc.plugins.market.db.tx.OrderChangedException(order.id, "the status moved under the lock")

            orderEvents.add(
                MarketOrderEvent(
                    orderId = order.id, type = OrderEventType.STATUS_CHANGED, fromStatus = order.status.name, toStatus = target.name, actorType = actorOf(refund),
                    actorUserId = refund.initiatedBy, createdAt = now, updatedAt = now
                ),
                conn
            )
        }

        timeline(conn, order.id, OrderEventType.REFUND_SUCCEEDED, actorOf(refund), refund.initiatedBy, null, refundData(effective))

        // 4. entitlements' price paid and the upgrade dependants (21 section 5.4): found before the revoke below, found by link in any case
        val dependents = dependentsOf(conn, order, items, itemRows.map { RefundMath.ItemAmount(it.orderItemId, it.quantity, it.amount) }, fully)

        reducePricePaid(conn, order, items, lineDeltas.amounts, if (refund.cascadeUpgrade == true) dependents.map { it.orderItemId }.toSet() else emptySet())
        upgradeDependents(conn, order, refund, dependents)

        // 5. revoke (08 section 11.2): not when revokeFirst did it before the money went
        var revoked = false

        if (refund.revoke && !refund.revokeFirst) {
            val targets = revokeTargets(items, itemRows, fully)

            if (targets.isNotEmpty()) {
                entitlementService.revoke(conn, deliveryService, order, items, targets, END_REASON_REFUND, DeliveryError.ORDER_REVOKED)

                deliveryService.refreshFulfillment(conn, order.id)

                revoked = true
            }
        } else if (refund.revoke) {
            revoked = true
        }

        // 6. restock
        if (refund.restock) restock(conn, items, lineDeltas.quantities, now)

        // 7. codes: a fully refunded order gives its coupon uses back, partial refunds keep them
        if (target == OrderStatus.REFUNDED) releaseApplied(conn, order.id, now)

        // 8. creator earning, cashback, top-up clawback
        val after = orders.getById(order.id, conn)!!

        effects.creatorReversal(conn, after, refundedAfter, fully)
        credits.reverseCashback(after, refund.id, null, refundedAfter, conn)

        if (refund.revoke) clawback(conn, after, items, effective, itemRows, fully)

        // 9. subscription
        effects.subscription(conn, after, effective)

        // 10. credit note, mail, webhook (goal progress has no writer yet)
        if (effective.amount > 0L) effects.creditNote(conn, after, orderItems.getByOrderIds(listOf(order.id), conn), refund.id)

        effects.mail(conn, after, effective)
        effects.webhook(conn, after, effective, itemRows, fully, revoked)
    }

    private fun actorOf(refund: MarketRefund) = when (refund.origin) {
        RefundOrigin.PANEL -> OrderActorType.ADMIN
        RefundOrigin.GATEWAY -> OrderActorType.GATEWAY
        RefundOrigin.SYSTEM -> OrderActorType.SYSTEM
    }

    private fun refundData(r: MarketRefund) = JsonObject().put("refundId", r.id).put("origin", r.origin.name).put("amount", r.amount).put("gatewayAmount", r.gatewayAmount)
        .put("creditAmount", r.creditAmount).put("creditValue", r.creditValue)

    /** `market_payment.refundedAmount` of the attempt a refund took money from: what the gateway gave back (its own figure when it differs, `buyerMayPayMore`). */
    private suspend fun addToPayment(conn: SqlClient, refund: MarketRefund) {
        conn.preparedQuery("UPDATE ${table("market_payment")} SET `refundedAmount` = `refundedAmount` + ?, `updatedAt` = ? WHERE `id` = ?")
            .execute(Tuple.of(refund.gatewayRefundedAmount ?: refund.gatewayAmount, clock.now(), refund.paymentId)).coAwait()
    }

    private class LineDeltas(val quantities: Map<Long, Int>, val amounts: Map<Long, Long>)

    /**
     * Step 2: with `market_refund_item` rows the lines they name (a bundle line also its children, `quantity x units per bundle`); an amount-only refund spreads over
     * the lines by largest remainder and counts units only when the order becomes fully refunded, in which case every line ends with all units refunded. The
     * product's `soldCount` follows the units that count as refunded (17 section 7 I17), test orders excluded as at commit.
     */
    private suspend fun applyLines(conn: SqlClient, order: MarketOrder, items: List<MarketOrderItem>, itemRows: List<MarketRefundItem>, amount: Long, fully: Boolean, now: Long): LineDeltas {
        val byId = items.associateBy { it.id }
        val quantities = LinkedHashMap<Long, Int>()
        val amountsOf = LinkedHashMap<Long, Long>()
        val unit = CreditMath.unit(order.currency)

        if (itemRows.isNotEmpty()) {
            for (r in itemRows) {
                val item = byId[r.orderItemId] ?: continue

                quantities.merge(item.id, r.quantity, Int::plus)
                amountsOf.merge(item.id, r.amount, Long::plus)

                if (item.kind == OrderItemKind.BUNDLE && item.quantity > 0) {
                    for (child in items.filter { it.parentItemId == item.id }) {
                        val perBundle = maxOf(1, child.quantity / item.quantity)

                        quantities.merge(child.id, r.quantity * perBundle, Int::plus)
                    }
                }
            }
        } else if (amount > 0L) {
            val lines = items.map { RefundMath.Line(it.id, it.quantity, it.lineTotal, it.refundedQuantity, it.refundedAmount) }
            val u = if (amount % unit == 0L) unit else 1L

            for (share in RefundMath.spread(amount, lines, fully, u).shares) {
                if (share.amount > 0L) amountsOf[share.itemId] = share.amount
                if (share.quantity > 0) quantities[share.itemId] = share.quantity
            }
        }

        if (fully) {
            for (item in items) {
                val open = item.quantity - item.refundedQuantity - (quantities[item.id] ?: 0)

                if (open > 0) quantities.merge(item.id, open, Int::plus)
            }
        }

        val clipped = LinkedHashMap<Long, Int>()

        for ((id, q) in quantities) {
            val item = byId.getValue(id)
            val delta = minOf(q, maxOf(0, item.quantity - item.refundedQuantity))

            if (delta > 0) clipped[id] = delta
        }

        for (item in items.sortedBy { it.id }) {
            val q = clipped[item.id] ?: 0
            val a = amountsOf[item.id] ?: 0L

            if (q == 0 && a == 0L) continue

            conn.preparedQuery("UPDATE ${table("market_order_item")} SET `refundedQuantity` = `refundedQuantity` + ?, `refundedAmount` = `refundedAmount` + ?, `updatedAt` = ? WHERE `id` = ?")
                .execute(Tuple.of(q, a, now, item.id)).coAwait()
        }

        // a charged-back order's units left the sold count at O11 already (and O12 gives back only what is not refunded): they are not taken twice
        if (!order.testMode && order.status != OrderStatus.CHARGEBACK) {
            val sold = sortedMapOf<Long, Long>()

            for (item in items) {
                val productId = item.productId ?: continue

                if (item.kind == OrderItemKind.CREDIT_TOPUP) continue

                val q = clipped[item.id] ?: continue

                sold.merge(productId, q.toLong(), Long::plus)
            }

            for ((productId, q) in sold) {
                conn.preparedQuery("UPDATE ${table("market_product")} SET `soldCount` = GREATEST(`soldCount` - ?, 0) WHERE `id` = ?").execute(Tuple.of(q, productId)).coAwait()
            }
        }

        return LineDeltas(clipped, amountsOf)
    }

    /**
     * `pricePaid -= fromOrder(refundedAmount delta / quantity)`, never below 0 (21 section 3.4 step 4): the amount is in the order currency, `pricePaid` in the
     * base currency, so one conversion by the order's rate. An entitlement that was upgraded (`replacedById`) is dead and its live successor was partly paid with
     * this money (05 section 5.2), so the same per-unit delta comes off the successor too ("a partial refund of the lower tier reduces `pricePaid` along the
     * chain", 21 section 5.4): a later upgrade then credits only what is still paid. Not for a line in [cascaded]: its successor is revoked by this refund.
     */
    private suspend fun reducePricePaid(conn: SqlClient, order: MarketOrder, items: List<MarketOrderItem>, amountDeltas: Map<Long, Long>, cascaded: Set<Long>) {
        if (amountDeltas.isEmpty()) return

        val c = config()
        val conversions = Conversions(
            order.baseCurrency.ifBlank { c.currency.name }, order.currency, order.fxRate, maxOf(1L, MoneyUtil.toMinor(c.creditValue)), c.removeCents, order.displayCurrency, order.displayRate
        )

        for (item in items) {
            val delta = amountDeltas[item.id] ?: continue

            if (delta <= 0L) continue

            val perUnit = Rounding.ratioQ(BigDecimal.valueOf(delta), order.fxRate.multiply(BigDecimal.valueOf(maxOf(item.quantity, 1).toLong())), conversions.bq)

            for (e in entitlements.getByOrderItemId(item.id, conn)) {
                takeFromPricePaid(conn, e.id, perUnit)

                if (e.replacedById != null && item.id !in cascaded) entitlementService.liveSuccessor(conn, e)?.let { takeFromPricePaid(conn, it.id, perUnit) }
            }
        }
    }

    private suspend fun takeFromPricePaid(conn: SqlClient, entitlementId: Long, amount: Long) {
        conn.preparedQuery("UPDATE ${table("market_entitlement")} SET `pricePaid` = GREATEST(`pricePaid` - ?, 0), `updatedAt` = ? WHERE `id` = ?")
            .execute(Tuple.of(amount, clock.now(), entitlementId)).coAwait()
    }

    /**
     * 21 section 5.4: `cascadeUpgrade = true` ends the live successor of an upgraded line as a refund ends it (`endReason REFUND`, its `REVOKE` rows). Without it
     * (`false`, or a gateway / system refund that nobody asked) the successor stays and [reducePricePaid] has taken the refunded value off its `pricePaid`
     * already, so a later upgrade credits only what is still paid. The successor's order is not locked (the lock order has no order-to-order step); its rows are
     * written under their own keys.
     */
    private suspend fun upgradeDependents(conn: SqlConnection, order: MarketOrder, refund: MarketRefund, dependents: List<UpgradeDependent>) {
        if (refund.cascadeUpgrade != true) return

        for (d in dependents) {
            val successorOrder = orders.getById(d.successorOrderId, conn) ?: continue
            val successorItems = orderItems.getByOrderIds(listOf(d.successorOrderId), conn)

            entitlementService.revoke(conn, deliveryService, successorOrder, successorItems, mapOf(d.successorOrderItemId to null), END_REASON_REFUND, DeliveryError.ORDER_REVOKED)
            deliveryService.refreshFulfillment(conn, d.successorOrderId)
            timeline(conn, d.successorOrderId, OrderEventType.NOTE, OrderActorType.SYSTEM, null, "UPGRADE_CASCADE_REVOKED", JsonObject().put("refundId", refund.id).put("orderId", order.id))
        }
    }

    /**
     * The order items a revoke of this refund reaches, the set [revokeTargets] plans rows for: the [named] lines with the children of a bundle line (the revoke of
     * a bundle takes its children with it, under their own item ids), or every line once the refund [empties] the order. Nothing is named by an amount-only
     * refund, so a partial one reaches nothing.
     */
    private fun revokeScope(items: List<MarketOrderItem>, named: Collection<Long>, empties: Boolean): Set<Long> {
        if (empties) return items.map { it.id }.toSet()

        val parents = named.toSet()

        return items.filter { it.id in parents || it.parentItemId?.let { p -> p in parents } == true }.map { it.id }.toSet()
    }

    /** The units a revoke of this refund covers (08 section 11.2): the refunded range of every named line, or every not-yet-revoked unit of every line once the order is empty. */
    private fun revokeTargets(items: List<MarketOrderItem>, itemRows: List<MarketRefundItem>, fully: Boolean): Map<Long, IntRange?> {
        val out = LinkedHashMap<Long, IntRange?>()
        val byId = items.associateBy { it.id }

        for (r in itemRows) {
            val item = byId[r.orderItemId] ?: continue

            if (item.kind == OrderItemKind.CREDIT_TOPUP || r.quantity <= 0) continue

            out[item.id] = item.refundedQuantity until minOf(item.refundedQuantity + r.quantity, item.quantity)
        }

        if (fully) {
            for (item in items) {
                if (item.id in out || item.kind == OrderItemKind.BUNDLE_CHILD || item.kind == OrderItemKind.CREDIT_TOPUP) continue

                out[item.id] = null
            }
        }

        return out
    }

    /** Step 6: `stock + units` for lines that reserved stock (never above what the line reserved); products before variants, ascending id. */
    private suspend fun restock(conn: SqlClient, items: List<MarketOrderItem>, quantities: Map<Long, Int>, now: Long) {
        val back = sortedMapOf<Pair<Boolean, Long>, Long>(compareBy({ it.first }, { it.second }))

        for (item in items.sortedBy { it.id }) {
            val units = minOf(quantities[item.id] ?: 0, item.stockReserved)

            if (units <= 0) continue

            conn.preparedQuery("UPDATE ${table("market_order_item")} SET `stockReserved` = `stockReserved` - ?, `updatedAt` = ? WHERE `id` = ?").execute(Tuple.of(units, now, item.id)).coAwait()

            back.merge((item.variantId != null) to (item.variantId ?: item.productId ?: continue), units.toLong(), Long::plus)
        }

        for ((subject, units) in back) {
            val tableName = if (subject.first) "market_product_variant" else "market_product"

            conn.preparedQuery("UPDATE ${table(tableName)} SET `stock` = `stock` + ?, `updatedAt` = ? WHERE `id` = ? AND `stock` IS NOT NULL").execute(Tuple.of(units, now, subject.second)).coAwait()
        }
    }

    /** Step 7: every `APPLIED` redemption of the order (gift rows excepted: nothing was paid) becomes `RELEASED` and its counter goes down (01 section 3.5). */
    private suspend fun releaseApplied(conn: SqlClient, orderId: Long, now: Long) {
        val rows = conn.preparedQuery("SELECT `id`, `kind`, `refId` FROM ${table("market_redemption")} WHERE `orderId` = ? AND `state` = 'APPLIED' ORDER BY `kind`, `refId` FOR UPDATE")
            .execute(Tuple.of(orderId)).coAwait()

        for (row in rows) {
            val kind = RedemptionKind.valueOf(row.getString("kind"))

            if (kind == RedemptionKind.GIFT) continue

            val moved = conn.preparedQuery("UPDATE ${table("market_redemption")} SET `state` = 'RELEASED', `updatedAt` = ? WHERE `id` = ? AND `state` = 'APPLIED'")
                .execute(Tuple.of(now, row.getLong("id"))).coAwait().rowCount()

            if (moved == 0) continue

            conn.preparedQuery("UPDATE ${table(Locks.tableOf(kind))} SET `usedCount` = `usedCount` - 1 WHERE `id` = ? AND `usedCount` > 0").execute(Tuple.of(row.getLong("refId"))).coAwait()
        }
    }

    private suspend fun clawbackRequests(
        conn: SqlClient, order: MarketOrder, items: List<MarketOrderItem>, itemRows: List<MarketRefundItem>, fully: Boolean, refundAmount: Long,
        txs: List<com.panomc.plugins.market.db.model.MarketCreditTx>
    ): List<Clawback.Request> {
        val granting = items.filter { (it.creditAmount ?: 0L) > 0L }

        if (granting.isEmpty()) return emptyList()

        val clawItems = granting.mapNotNull { item ->
            val grant = txs.firstOrNull { it.idempotencyKey == "orderitem:${item.id}:topup" || it.idempotencyKey == "orderitem:${item.id}:gift" } ?: return@mapNotNull null
            val already = txs.filter { it.type == CreditTxType.REVOKE && it.idempotencyKey.endsWith(":clawback:${item.id}") }.sumOf { it.amount + it.shortfall }

            Clawback.Item(item.id, grant.amount, already, maxOf(1, item.quantity), item.lineTotal)
        }
        val basis = when {
            fully -> Clawback.Basis.Everything
            itemRows.isNotEmpty() -> Clawback.Basis.Items(itemRows.map { Clawback.RefundItem(it.orderItemId, it.quantity, it.amount) })
            else -> Clawback.Basis.Amount(refundAmount, order.totalPrice)
        }

        return Clawback.compute(clawItems, basis)
    }

    /** 07 section 8.5, admin policy `TAKE_AVAILABLE`: a shortfall (credits already spent) does not block the refund, it is written to the timeline. */
    private suspend fun clawback(conn: SqlConnection, order: MarketOrder, items: List<MarketOrderItem>, refund: MarketRefund, itemRows: List<MarketRefundItem>, fully: Boolean) {
        val recipient = order.recipientUserId ?: order.userId ?: return

        for (request in clawbackRequests(conn, order, items, itemRows, fully, refund.amount, creditTxs.getByOrderId(order.id, conn))) {
            val result = credits.post(
                Posting(
                    CreditTxType.REVOKE, "refund:${refund.id}:clawback:${request.itemId}", recipient, request.amount, AccountRef.User(recipient),
                    AccountRef.System(CreditSystemKey.REVOKED), PostingPolicy.TAKE_AVAILABLE, orderId = order.id, refundId = refund.id
                ),
                conn
            )

            if (result.tx.shortfall > 0L) {
                timeline(
                    conn, order.id, OrderEventType.CLAWBACK_SHORTFALL, OrderActorType.SYSTEM, null, null,
                    JsonObject().put("refundId", refund.id).put("orderItemId", request.itemId).put("uncovered", result.tx.shortfall)
                )
            }
        }
    }

    // ============================================================================================================ inbound RefundUpdated (21 section 4)

    /**
     * An inbound `RefundUpdated` for [attempt]: under the order lock the row it belongs to is found by [RefundEventMatcher], moved by the state machine (a state the
     * row already has is a no-op, so a redelivery changes nothing) or inserted as an `origin = GATEWAY` row in the reported state. A snapshot that repeats what is
     * known and an event past what the attempt can still give back write nothing (the second raises `OVER_REFUND`).
     */
    suspend fun onRefundUpdated(event: PaymentEvent.RefundUpdated, attempt: MarketPayment, eventKey: String?, requestHash: String?) {
        val t = db.txRestartingOnOrderChange { conn ->
            locks.forOrder(conn, attempt.orderId, OrderLockScope.RELEASE, cashback = true) { locked ->
                val t = Tx(conn)

                locks.children(conn, attempt.orderId, OrderChild.REFUND)

                val now = clock.now()
                val fresh = payments.getById(attempt.id, conn) ?: attempt
                val order = orders.getById(attempt.orderId, conn)!!
                val rows = refunds.getByOrderId(order.id, conn)
                val match = RefundEventMatcher.match(
                    RefundEventMatcher.Event(
                        state = event.state, amount = event.amount?.amount, refundKey = event.refundKey, gatewayRefundId = event.gatewayRefundId,
                        cumulativeRefunded = event.cumulativeRefunded?.amount, eventKey = eventKey, requestHash = requestHash
                    ),
                    RefundEventMatcher.Attempt(fresh.id, fresh.providerId, fresh.paidAmount ?: 0L, fresh.refundedAmount),
                    rows.map { RefundEventMatcher.RefundRow(it.id, it.paymentId, it.providerId, it.origin, it.status, it.idempotencyKey, it.gatewayRefundId, it.gatewayAmount, it.createdAt, it.completedAt) },
                    now
                )
                val reported = RefundEvent.Reported(event.state, if (event.state == RefundState.FAILED) "GATEWAY_REPORTED" else null)
                val patch = Patch(event.gatewayRefundId, null, event.buyerActionUrl)

                when (match) {
                    is RefundEventMatcher.Match.Existing -> move(t, rows.first { it.id == match.refundId }, reported, patch)

                    is RefundEventMatcher.Match.Insert -> insertGateway(t, order, fresh, match, event, patch, now)

                    is RefundEventMatcher.Match.NoOp -> if (match.alert) {
                        timeline(conn, order.id, OrderEventType.NOTE, OrderActorType.GATEWAY, null, "OVER_REFUND", JsonObject().put("reason", match.reason.name).put("paymentId", fresh.id))
                        t.alerts += PendingAlert(order.id, "OVER_REFUND", JsonObject().put("paymentId", fresh.id))
                    }
                }

                t
            }
        }

        raise(t)
    }

    private suspend fun insertGateway(t: Tx, order: MarketOrder, attempt: MarketPayment, match: RefundEventMatcher.Match.Insert, event: PaymentEvent.RefundUpdated, patch: Patch, now: Long) {
        val conn = t.conn
        val inFlight = books(order, refunds.getByOrderId(order.id, conn)).filter { it.status == RefundStatus.REQUESTED || it.status == RefundStatus.PENDING }.sumOf { it.amount }
        // a partial dashboard refund revokes nothing; one that empties the order follows `revokeOnRefund` (21 section 4)
        val revoke = config().revokeOnRefund && order.refundedTotal + inFlight + match.amount >= order.totalPrice && !effects.olderSubscriptionPeriod(conn, order)
        val insert = RefundStateMachine.insert(RefundOrigin.GATEWAY, event.state)
        val id = refunds.add(
            MarketRefund(
                orderId = order.id, paymentId = attempt.id, providerId = attempt.providerId, status = insert.to, origin = RefundOrigin.GATEWAY, idempotencyKey = match.idempotencyKey,
                amount = match.amount, gatewayAmount = match.amount, currency = order.currency, gatewayRefundId = patch.gatewayRefundId?.take(191),
                buyerActionUrl = patch.buyerActionUrl?.take(1024), revoke = revoke, restock = false, createdAt = now, updatedAt = now
            ),
            conn
        ) ?: return

        for ((itemId, money) in event.lines) {
            refundItems.add(MarketRefundItem(refundId = id, orderItemId = itemId, quantity = 0, amount = money.amount, createdAt = now, updatedAt = now), conn)
        }

        timeline(conn, order.id, OrderEventType.REFUND_REQUESTED, OrderActorType.GATEWAY, null, null, JsonObject().put("refundId", id).put("origin", RefundOrigin.GATEWAY.name).put("amount", match.amount))

        val sets = LinkedHashMap<String, Any?>()
        var runOrderEffects = false

        for (effect in insert.effects) {
            when (effect) {
                RefundEffect.RunOrderEffects -> runOrderEffects = true
                RefundEffect.StampCompleted -> sets["completedAt"] = now
                is RefundEffect.ScheduleQuery -> sets["nextQueryAt"] = now + effect.afterMs
                RefundEffect.ClearQuery -> sets["nextQueryAt"] = null
                is RefundEffect.RecordFailure -> {
                    sets["failureCode"] = "GATEWAY_REPORTED"
                    sets["failureMessage"] = effect.message
                }
                else -> Unit
            }
        }

        if (sets.isNotEmpty()) set(conn, id, sets)

        if (insert.to == RefundStatus.FAILED) logMove(conn, refunds.getById(id, conn)!!, RefundStatus.FAILED, RefundEvent.Reported(event.state, "GATEWAY_REPORTED"))

        if (runOrderEffects) applySucceeded(t, refunds.getById(id, conn)!!)

        if (match.clamped) t.alerts += PendingAlert(order.id, "OVER_REFUND", JsonObject().put("refundId", id))
    }

    // ============================================================================================================ reconcile (21 sections 3.3 and 3.5)

    /**
     * One pass of `RefundReconcileJob`: `revokeFirst` rows whose undo is done are released (the gateway call, or the settlement of a credit-only / manual refund),
     * rows still open after 24 h are cancelled (`REVOKE_TIMEOUT`), `PENDING` and stale `REQUESTED` rows are asked with `queryRefund` on the schedule 5 min, 30 min,
     * then every 6 h for 30 days (the answer is applied like tx2), and `SYSTEM` rows nobody sent yet are sent with their own key. One row failing does not stop the others.
     */
    suspend fun reconcile(limit: Int = RECONCILE_BATCH): RefundReconcileReport {
        val report = RefundReconcileReport()
        val now = clock.now()
        val ids = db.tx { conn ->
            suspend fun ids(sql: String, vararg values: Any?) = conn.preparedQuery(sql).execute(Tuple.from(values.toList())).coAwait().map { it.getLong("id") }
            val t = table("market_refund")

            Triple(
                ids("SELECT `id` FROM $t WHERE `status` = 'REQUESTED' AND `revokeFirst` = 1 AND `queryCount` = 0 AND (`nextQueryAt` IS NULL OR `nextQueryAt` <= ?) ORDER BY `id` LIMIT ?", now, limit),
                ids("SELECT `id` FROM $t WHERE `status` = 'REQUESTED' AND `origin` = 'SYSTEM' AND `revokeFirst` = 0 AND `queryCount` = 0 ORDER BY `id` LIMIT ?", limit),
                ids(
                    "SELECT `id` FROM $t WHERE ((`status` = 'PENDING') OR (`status` = 'REQUESTED' AND `queryCount` >= 1)) AND `nextQueryAt` IS NOT NULL AND `nextQueryAt` <= ? ORDER BY `id` LIMIT ?",
                    now, limit
                )
            )
        }

        for (id in ids.first) guarded(report) { if (release(id, report)) report.released++ }
        for (id in ids.second) guarded(report) { if (sendUnsent(id)) report.sent++ }
        for (id in ids.third) guarded(report) { if (poll(id)) report.polled++ }

        return report
    }

    private suspend fun guarded(report: RefundReconcileReport, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report.errors++

            logger.warn("refund reconcile step failed: {}", e.toString())
        }
    }

    private enum class UndoState { READY, WAITING, BLOCKED }

    private class Undo(val state: UndoState, val deliveryId: Long? = null)

    /** 21 section 3.5: the effective `REVOKE` / `EXPIRE` rows of the refunded lines: all `CONFIRMED` (or `CANCELLED` as `NOTHING_TO_REVOKE`) is ready, any `FAILED` blocks. */
    private suspend fun undoState(conn: SqlClient, order: MarketOrder, refund: MarketRefund, items: List<MarketOrderItem>, itemRows: List<MarketRefundItem>): Undo {
        // the rows tx1 planned: the named lines and the children of a bundle line, every line when the refund empties the order (see revokeScope)
        val itemIds = revokeScope(items, itemRows.map { it.orderItemId }, emptiesOrder(order, refund.amount, refund.creditAmount))
        val rows = deliveries.getByOrderId(refund.orderId, conn).filter { (it.phase == DeliveryPhase.REVOKE || it.phase == DeliveryPhase.EXPIRE) && it.orderItemId in itemIds }
        val effective = rows.groupBy { listOf(it.orderItemId, it.actionId, it.serverId, it.unitIndex, it.phase) }.values.map { group ->
            val live = group.filter { it.status != DeliveryStatus.CANCELLED }

            live.maxByOrNull { it.attemptGroup } ?: group.firstOrNull { it.lastErrorCode == DeliveryError.NOTHING_TO_REVOKE } ?: group.maxByOrNull { it.attemptGroup }!!
        }

        effective.firstOrNull { it.status == DeliveryStatus.FAILED }?.let { return Undo(UndoState.BLOCKED, it.id) }

        val open = effective.any { !(it.status == DeliveryStatus.CONFIRMED || (it.status == DeliveryStatus.CANCELLED && it.lastErrorCode == DeliveryError.NOTHING_TO_REVOKE)) }

        return Undo(if (open) UndoState.WAITING else UndoState.READY)
    }

    private suspend fun release(refundId: Long, report: RefundReconcileReport): Boolean {
        val first = db.tx { refunds.getById(refundId, it) } ?: return false
        val t = db.txRestartingOnOrderChange { conn ->
            locks.forOrder(conn, first.orderId, OrderLockScope.RELEASE, cashback = true) { locked ->
                val t = Tx(conn)

                locks.children(conn, first.orderId, OrderChild.REFUND)

                val row = refunds.getById(refundId, conn) ?: return@forOrder t

                if (row.status != RefundStatus.REQUESTED || !row.revokeFirst || row.queryCount != 0) return@forOrder t

                val now = clock.now()
                val undo = undoState(conn, orders.getById(row.orderId, conn) ?: throw OrderChangedExceptionFor(row.orderId), row, locked.items, refundItems.getByRefundId(row.id, conn))

                when {
                    undo.state == UndoState.READY && (row.gatewayAmount == 0L || row.providerId == null) -> move(t, row, RefundEvent.Reported(RefundState.SUCCEEDED))

                    undo.state == UndoState.READY -> {
                        claim(conn, row.id, now)
                        t.send = row.id
                    }

                    now - row.createdAt >= RefundStateMachine.REVOKE_FIRST_TIMEOUT_MS -> {
                        move(t, row, RefundEvent.RevokeTimeout)

                        report.timedOut++
                    }

                    else -> {
                        if (undo.state == UndoState.BLOCKED) t.alerts += PendingAlert(row.orderId, "REVOKE_FAILED", JsonObject().put("refundId", row.id).put("deliveryId", undo.deliveryId))

                        set(conn, row.id, linkedMapOf("nextQueryAt" to now + HOLD_RECHECK_MS))
                    }
                }

                t
            }
        }

        raise(t)

        val due = t.send

        if (due != null) send(due)

        return due != null || db.tx { refunds.getById(refundId, it)!!.status != RefundStatus.REQUESTED }
    }

    private suspend fun sendUnsent(refundId: Long): Boolean {
        if (!db.tx { conn -> claimIf(conn, refundId) }) return false

        send(refundId)

        return true
    }

    private suspend fun claimIf(conn: SqlConnection, refundId: Long): Boolean {
        val now = clock.now()

        return conn.preparedQuery(
            "UPDATE ${table("market_refund")} SET `queryCount` = `queryCount` + 1, `nextQueryAt` = ?, `updatedAt` = ? WHERE `id` = ? AND `status` = 'REQUESTED' AND `queryCount` = 0 AND `revokeFirst` = 0"
        ).execute(Tuple.of(now + CALL_CLAIM_MS, now, refundId)).coAwait().rowCount() == 1
    }

    private suspend fun poll(refundId: Long): Boolean {
        val now = clock.now()
        val claimed = db.tx { conn ->
            val row = refunds.getById(refundId, conn) ?: return@tx null

            if (row.nextQueryAt == null || row.nextQueryAt > now || (row.status != RefundStatus.PENDING && row.status != RefundStatus.REQUESTED)) return@tx null

            val next = if (now - row.createdAt > QUERY_WINDOW_MS) null else if (now - row.createdAt < FIRST_POLL_SPAN_MS) row.createdAt + FIRST_POLL_SPAN_MS else now + LATER_POLL_MS
            val moved = conn.preparedQuery(
                "UPDATE ${table("market_refund")} SET `queryCount` = `queryCount` + 1, `nextQueryAt` = ?, `updatedAt` = ? WHERE `id` = ? AND `status` = ? AND `nextQueryAt` = ?"
            ).execute(Tuple.of(next, now, refundId, row.status.name, row.nextQueryAt)).coAwait().rowCount() == 1

            if (moved) refundId else null
        } ?: return false
        val snap = snapshot(claimed) ?: return false

        applyAnswer(claimed, gateway.query(snap.call))

        return true
    }

    private fun OrderChangedExceptionFor(orderId: Long) = com.panomc.plugins.market.db.tx.OrderChangedException(orderId, "the row is gone")

    companion object {
        const val SETTLE_WAIT_MS = 35_000L
        private const val SETTLE_POLL_MS = 25L

        /** The in-flight marker of a gateway call (21 section 3.3 "nextQueryAt = now + 60 s"). */
        const val CALL_CLAIM_MS = 60_000L

        /** How often a `revokeFirst` row looks at its undo rows again. */
        const val HOLD_RECHECK_MS = 60_000L

        const val FIRST_POLL_SPAN_MS = 30L * 60 * 1000
        const val LATER_POLL_MS = 6L * 60 * 60 * 1000
        const val QUERY_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
        const val RECONCILE_BATCH = 50
        const val SERVER_SILENT_MS = 2L * 60 * 1000
        const val REASON_MIN = 3
        const val REASON_MAX = 255

        /** `endReason` a revoke for a refund writes (also over the `UPGRADE` of an `UPGRADED` row). */
        private const val END_REASON_REFUND = "REFUND"

        private val PAID_STATES = setOf(OrderStatus.COMPLETED, OrderStatus.PARTIALLY_REFUNDED, OrderStatus.REFUNDED, OrderStatus.CHARGEBACK)
        private val LIVE_STATES = setOf(OrderStatus.COMPLETED, OrderStatus.PARTIALLY_REFUNDED)

        private val logger = LoggerFactory.getLogger(RefundService::class.java)
    }
}
