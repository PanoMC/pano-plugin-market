package com.panomc.plugins.market.service

import com.panomc.plugins.market.core.order.OrderActor
import com.panomc.plugins.market.core.order.OrderEffect
import com.panomc.plugins.market.core.order.OrderEvent
import com.panomc.plugins.market.core.order.OrderState
import com.panomc.plugins.market.core.order.OrderStateMachine
import com.panomc.plugins.market.core.order.OrderTransition
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.core.time.Ids
import com.panomc.plugins.market.db.dao.MarketEntitlementDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderEventDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketPaymentDao
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.dao.MarketRefundDao
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderEvent
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.BillingMode
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.MarketRefund
import com.panomc.plugins.market.db.model.RefundOrigin
import com.panomc.plugins.market.db.model.RefundStatus
import com.panomc.plugins.market.db.model.OrderActorType
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.db.tx.LockedOrder
import com.panomc.plugins.market.db.tx.OrderChangedException
import com.panomc.plugins.market.error.CreditsDisabled
import com.panomc.plugins.market.error.PaymentMethodUnavailable
import com.panomc.plugins.market.error.PurchaseLimitReached
import com.panomc.plugins.market.util.MoneyUtil
import com.panomc.plugins.market.spi.payment.ReviewReason
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The credit hold of an order (06 section 5.3 B10, 07 section 5): `HOLD` posted for the payer under the key
 * `order:<id>:hold`, inside the order transaction, right after the order row exists. It must throw `InsufficientCredits`
 * when the balance does not cover [credits]. The ledger is `CreditService` (MK-091); until it exists [UNAVAILABLE] refuses
 * every order that spends credits (409 `CREDITS_DISABLED`), so no order is ever created with a credit part and no hold behind it (invariant I3b).
 */
fun interface CreditHolds {
    suspend fun hold(conn: SqlConnection, userId: Long, credits: Long, orderId: Long, key: String)

    companion object {
        val UNAVAILABLE = CreditHolds { _, _, _, _, _ -> throw CreditsDisabled() }
    }
}

/**
 * The pending subscription row of an order with a subscription line (06 section 5.3 B11, 09 section 4): created inside the
 * order transaction, answers its id (stored as `market_order.subscriptionId`). `SubscriptionService` (MK-121) implements
 * it; until then [UNAVAILABLE] refuses the order, so a subscription is never sold without its subscription row.
 */
fun interface PendingSubscriptions {
    suspend fun createPending(conn: SqlConnection, order: MarketOrder, item: MarketOrderItem): Long

    companion object {
        val UNAVAILABLE = PendingSubscriptions { _, _, _ -> throw PaymentMethodUnavailable("RECURRING_NOT_SUPPORTED") }
    }
}

/**
 * A [PendingSubscriptions] that also takes the verdict of the checkout's offer table for the chosen payment method (09 section 4.2: `"AUTO"` /
 * `"MANUAL"`, after the provider's own `checkEligibility`, which can downgrade an automatic offer to a manual one). [OrderService] calls this form
 * when the seam implements it and the order draft carries a verdict ([OrderDraft.recurring]); `SubscriptionService` (MK-121) does. A `null` verdict
 * is "not known": the implementation applies the offer table of the capabilities alone.
 */
interface OfferedPendingSubscriptions : PendingSubscriptions {
    suspend fun createPending(conn: SqlConnection, order: MarketOrder, item: MarketOrderItem, recurring: String?): Long

    override suspend fun createPending(conn: SqlConnection, order: MarketOrder, item: MarketOrderItem): Long = createPending(conn, order, item, null)
}

/**
 * The ledger operations of an order after O1 (07 sections 5 and 6.4): capture of the outstanding `HOLD` at O2 / O4 (C3), release at
 * O5 to O8 (C4), a new credit part at `/pay` (C2: release the old part when it was above 0, then hold the new one). All run inside the
 * transition's transaction, under the account locks of `Locks.forOrder`, and throw when they cannot post: the transition then rolls
 * back, so an order is never completed without its capture and never closed without its release. `CreditService` (MK-091)
 * implements it; until then [UNAVAILABLE] refuses (409 `CREDITS_DISABLED`) and nothing is posted. It is only called for an order whose
 * credit part is above 0 (or, for [retender], whose part changes), so orders without credits never meet it.
 */
interface CreditSettlement {
    /** O2 / O4: `CAPTURE` of the outstanding hold of [order]. */
    suspend fun capture(conn: SqlConnection, order: MarketOrder)

    /** O5 to O8: `RELEASE` of the outstanding hold of [order]. */
    suspend fun release(conn: SqlConnection, order: MarketOrder)

    /** `/pay`: the credit part of [order] becomes [newCredits] (`RELEASE(old)` then `HOLD(new)`); [order] still carries the old part. */
    suspend fun retender(conn: SqlConnection, order: MarketOrder, newCredits: Long)

    /**
     * O4 of an order that came through O9 (06 section 7.4, 07 section 5 C5): the hold was released together with the stock, so [credits] are held
     * again (the next hold generation) before they are captured; `InsufficientCredits` (400) when the balance does not cover them, which rolls
     * the accept back and leaves the order in `REVIEW`. The default refuses (409 `CREDITS_DISABLED`): an implementation that does not override
     * it can never complete a released order with a credit part without its hold. `CreditService` (MK-091) overrides it.
     */
    suspend fun rehold(conn: SqlConnection, order: MarketOrder, credits: Long): Unit = throw CreditsDisabled()

    companion object {
        val UNAVAILABLE: CreditSettlement = object : CreditSettlement {
            override suspend fun capture(conn: SqlConnection, order: MarketOrder) = throw CreditsDisabled()

            override suspend fun release(conn: SqlConnection, order: MarketOrder) = throw CreditsDisabled()

            override suspend fun retender(conn: SqlConnection, order: MarketOrder, newCredits: Long) = throw CreditsDisabled()
        }
    }
}

/**
 * The effects of O2 / O4 / O5 to O8 that belong to other slices (06 section 11, the bracketed names): entitlements, deliveries, creator
 * earning, cashback, credit-granting lines, subscriptions, invoice, mail outbox, goal progress, shipping. Each is a service call in the
 * transition's transaction that only writes rows. [apply] is called for every such effect in the order the state machine lists them;
 * an implementation skips the ones the order has nothing for. [PENDING_SLICES] is what runs until those slices land: it logs every effect
 * it was asked for and did nothing (see the open seams in `evidence/MK-076.md`).
 */
fun interface ForeignEffects {
    suspend fun apply(conn: SqlConnection, locked: LockedOrder, effect: OrderEffect)

    companion object {
        private val logger = LoggerFactory.getLogger(ForeignEffects::class.java)

        val PENDING_SLICES = ForeignEffects { _, locked, effect ->
            logger.warn("order {}: {} has no handler yet (its slice is not wired)", locked.order.id, effect::class.simpleName)
        }
    }
}

/** The store webhook `order.paid` of O2 / O4 (08 section 15): a row per listening endpoint, written inside the transition. */
fun interface PaidWebhooks {
    suspend fun orderPaid(conn: SqlConnection, orderId: Long)

    companion object {
        val NONE = PaidWebhooks { _, _ -> }
    }
}

/**
 * The per-player limit of a re-reserve (06 section 7.4: "per-player limit exceeded meanwhile => 409 `PURCHASE_LIMIT_REACHED`"): run under the
 * `RELEASE` locks before a released order is brought back to `HELD` without `force`. [UNAVAILABLE] throws (the accept fails closed and the order
 * stays `REVIEW`) so an `OrderService` built without it can never complete a released order past a limit; [ProductPurchaseLimits] is the real check.
 */
fun interface PurchaseLimitCheck {
    suspend fun check(conn: SqlConnection, locked: LockedOrder)

    companion object {
        val UNAVAILABLE = PurchaseLimitCheck { _, locked ->
            throw IllegalStateException("order ${locked.order.id}: this OrderService was built without a purchase limit check, it cannot re-reserve")
        }
    }
}

/** What decides the fate of the money of a duplicate payment (00 section 7.2): the `autoRefundDuplicatePayments` switch and the refund support of the provider. */
class DuplicateRefundRule(val autoRefund: Boolean, val providerCanRefund: Boolean)

/**
 * The [DuplicateRefundRule] of a payment provider, for the duplicates an accepted review finds (the payment service asks the same two questions for a
 * duplicate that arrives on a paid order). [ALERT_ONLY] (the default of an `OrderService`) never requests a refund: the timeline note and the panel alert say so.
 */
fun interface DuplicateRefundPolicy {
    suspend fun rule(conn: SqlConnection, providerId: String): DuplicateRefundRule

    companion object {
        val ALERT_ONLY = DuplicateRefundPolicy { _, _ -> DuplicateRefundRule(autoRefund = false, providerCanRefund = false) }
    }
}

/**
 * [PurchaseLimitCheck] on `limitPerPlayer`: what the recipient holds or has on hold ([MarketOrderDao.usageByProduct]; the released order itself is
 * not counted) plus this order's own units must stay within the limit of every product that has one. A `TIMED` product the recipient already owns
 * is an extension, never a second holding (as for [RecipientLimitGuard]). The cooldown is a checkout rule and is not judged for a payment that
 * already happened.
 */
class ProductPurchaseLimits(
    private val orders: MarketOrderDao,
    private val products: MarketProductDao,
    private val entitlements: MarketEntitlementDao,
    private val clock: Clock
) : PurchaseLimitCheck {
    override suspend fun check(conn: SqlConnection, locked: LockedOrder) {
        val units = HashMap<Long, Long>()

        for (item in locked.items) {
            val productId = item.productId ?: continue

            units.merge(productId, (item.quantity - item.refundedQuantity).toLong(), Long::plus)
        }

        if (units.isEmpty()) return

        val limited = products.getByIds(units.keys.toList(), conn).filter { it.limitPerPlayer != null }

        if (limited.isEmpty()) return

        val keys = RecipientLimitGuard.recipientKeys(locked.order)
        val usage = orders.usageByProduct(keys, limited.map { it.id }, conn)
        val now = clock.now()
        var owned: Set<Long>? = null

        for (product in limited.sortedBy { it.id }) {
            val limit = product.limitPerPlayer!!
            val used = usage[product.id]?.used ?: 0L

            if (used + (units[product.id] ?: 0L) <= limit) continue

            val ids = owned ?: keys.flatMap { entitlements.getActiveByOwner(it, now, conn) }.map { it.productId }.toSet().also { owned = it }

            if (product.billingMode == BillingMode.TIMED && product.id in ids) continue

            throw PurchaseLimitReached(product.id, limit)
        }
    }
}

/** What runs after the transition's transaction committed: nothing here may take part in it, and none of it may fail the transition. */
sealed interface AfterCommit {
    /** `cancelPayment` at the gateway for attempts the transition closed (providers with `cancelPending`), best effort. */
    class CancelAtGateway(val attempts: List<MarketPayment>) : AfterCommit

    /** A panel alert: an order is waiting for a human (`reason` is the `reviewReason` it was opened with). */
    class PanelAlert(val orderId: Long, val reason: String?) : AfterCommit
}

/** The answer of [OrderService.transition]: what the state machine decided and what must run after the commit. */
class TransitionResult(val decision: OrderTransition, val from: OrderStatus, val after: List<AfterCommit>) {
    /** The order moved to [to]. */
    val moved: Boolean get() = decision is OrderTransition.Move

    val to: OrderStatus get() = (decision as? OrderTransition.Move)?.to ?: from
}

/** An effect that is owned by a slice that has not landed: the transition stops (and rolls back) instead of skipping it. */
class EffectNotOwnedYet(val effect: OrderEffect, val owner: String) :
    IllegalStateException("${effect::class.simpleName} is applied by $owner, which is not wired yet")

/**
 * One order item before the order exists: [build] makes the row once the ids are known. A bundle line comes first and its
 * [BUNDLE_CHILD][com.panomc.plugins.market.db.model.OrderItemKind.BUNDLE_CHILD] rows name it in [parentKey]; [key] is the
 * stock key of the reservation (`Reservation.stockReserved`).
 */
class DraftItem(
    val key: String,
    val parentKey: String?,
    val subscription: Boolean,
    val build: (orderId: Long, parentItemId: Long?, stockReserved: Int) -> MarketOrderItem
)

/** The first payment attempt (06 section 9.2): `CREATED`, written by the order transaction ("tx1"). */
class AttemptDraft(
    val providerId: String,
    val methodLabel: String,
    val expiresAt: Long,
    val testMode: Boolean,
    val clientIp: String?,
    val userAgent: String?
)

/** Everything the order transaction writes, computed under the locks (06 sections 5.3 and 5.4). */
class OrderDraft(
    /** The order row for the given public id and access token. */
    val order: (publicId: String, accessToken: String) -> MarketOrder,
    val items: List<DraftItem>,
    val reservation: Reservation,
    val uses: List<CodeUse>,
    val customer: CustomerKeys,
    /** The first attempt; `null` for a manual order (06 section 14.3: no attempt row), which only [OrderService.createManual] writes. */
    val attempt: AttemptDraft?,
    /** The payer whose server cart the transaction empties (06 section 2.4), `null` = leave every cart alone. */
    val clearCartOfUser: Long?,
    val actorUserId: Long?,
    /** The `CREATED` event of the timeline: the buyer's checkout (default), or the admin of a manual order with its flags in `data`. */
    val created: CreatedEvent = CreatedEvent.STOREFRONT,
    /** `PaymentMethodOption.recurring` of the chosen method (`"AUTO"` / `"MANUAL"`) for an order with a subscription line, else `null` (09 section 4.3). */
    val recurring: String? = null
)

/** The `CREATED` timeline row of an order (06 section 5.3 B11, section 14.3 `audit`). */
class CreatedEvent(val actorType: OrderActorType, val source: OrderSource, val data: JsonObject = JsonObject()) {
    companion object {
        val STOREFRONT = CreatedEvent(OrderActorType.BUYER, OrderSource.STOREFRONT)
    }
}

/** What the order transaction produced. */
class CreatedOrder(val order: MarketOrder, val items: List<MarketOrderItem>, val attempt: MarketPayment)

/** What the transaction of a manual order produced: the order and its items, never an attempt. */
class CreatedManualOrder(val order: MarketOrder, val items: List<MarketOrderItem>)

/**
 * The same `(buyerKey, Idempotency-Key)` was already placed (06 section 5.1): thrown inside the order transaction so that
 * everything it did (stock, counters, holds) is rolled back, then answered by replaying [order]. Internal.
 */
class IdempotentReplay(val order: MarketOrder) : RuntimeException("idempotent replay of order ${order.id}")

/**
 * Orders (06 sections 5.3, 5.4, 9.2, 11). MK-075 lands the creation of an order (the inserts of phase B) and the owner
 * view of an order; the transitions (O2 to O12) arrive with the payment, review, refund and expiry slices, which extend this
 * class and use `Locks.forOrder`.
 *
 * [create] runs on the connection of the checkout's `MarketDb.tx`, after stock, codes and discounts were reserved
 * (`ReservationService.reserve`): the order row (a clash on `uq_buyer_idem` is a replay, one on `uq_publicId` draws a new id),
 * the credit hold, the items (bundle first), the redemption rows, the pending subscription, the `CREATED` event, the cart
 * clear, the first payment attempt with its `PAYMENT_STARTED` event.
 */
class OrderService(
    private val clock: Clock,
    private val ids: Ids,
    private val orders: MarketOrderDao,
    private val orderItems: MarketOrderItemDao,
    private val orderEvents: MarketOrderEventDao,
    private val payments: MarketPaymentDao,
    private val redemptions: RedemptionService,
    private val cartClear: suspend (SqlClient, Long) -> Boolean,
    private val credits: CreditHolds = CreditHolds.UNAVAILABLE,
    private val subscriptions: PendingSubscriptions = PendingSubscriptions.UNAVAILABLE,
    /** The reservation operations of [transition] (commit, release); `null` for a service that only creates orders and shows them. */
    private val reservations: ReservationService? = null,
    private val settlement: CreditSettlement = CreditSettlement.UNAVAILABLE,
    private val foreign: ForeignEffects = ForeignEffects.PENDING_SLICES,
    private val webhooks: PaidWebhooks = PaidWebhooks.NONE,
    /** `market_currency_rate` as `currency -> units per 1 base unit` (the table of the pricing code), for the frozen `exchangeRate`. */
    private val rates: suspend (SqlClient) -> Map<String, BigDecimal> = { emptyMap() },
    /** The stats currency of the store settings: `exchangeRate` is stats units per 1 order-currency unit. */
    private val statsCurrency: () -> String = { "" },
    /** The limit check of a re-reserve (O4 after O9); the default refuses to re-reserve at all. */
    private val limits: PurchaseLimitCheck = PurchaseLimitCheck.UNAVAILABLE,
    /**
     * Where a system refund (`origin = SYSTEM`: rejected review with money received, duplicate payment) is requested; `null` for a service that has
     * no refund table behind it: a review rejection with `refund = true` then fails (and rolls back), a duplicate payment only raises the alert.
     */
    private val refunds: MarketRefundDao? = null,
    /** How the second paying attempt of an accepted review is treated (refund or alert); the default only alerts. */
    private val duplicates: DuplicateRefundPolicy = DuplicateRefundPolicy.ALERT_ONLY,
    /** O3 (MK-142, 12 section 4.1): the "we received your order" mail of a payment that waits for a human, queued in the transition's transaction. */
    private val receivedMails: ReceivedMails = ReceivedMails.NONE
) {
    private fun table(name: String) = "`${orders.prefix()}$name`"

    suspend fun create(conn: SqlConnection, draft: OrderDraft): CreatedOrder {
        val attemptDraft = checkNotNull(draft.attempt) { "an order with a payment attempt needs an attempt draft; a manual order goes through createManual" }
        val (order, rows) = insertWhole(conn, draft)
        val attempt = addAttempt(conn, order, attemptDraft)

        return CreatedOrder(orders.getById(order.id, conn)!!, rows, attempt)
    }

    /**
     * The order transaction of a manual order (06 section 14.3): everything [create] writes except the payment attempt (`markPaid` is O2 by the
     * caller in the same transaction, a pending manual order is paid later from the order page, which writes the attempt then).
     */
    suspend fun createManual(conn: SqlConnection, draft: OrderDraft): CreatedManualOrder {
        check(draft.attempt == null) { "a manual order has no attempt" }

        val (order, rows) = insertWhole(conn, draft)

        return CreatedManualOrder(orders.getById(order.id, conn)!!, rows)
    }

    private suspend fun insertWhole(conn: SqlConnection, draft: OrderDraft): Pair<MarketOrder, List<MarketOrderItem>> {
        val order = insertOrder(conn, draft)
        val orderId = order.id

        // B10: the hold comes right after the order row; nobody can reference an uncommitted row, so this is no lock order violation
        if (order.creditAmount > 0) {
            val userId = checkNotNull(order.userId) { "a credit part needs a logged-in payer" }

            credits.hold(conn, userId, order.creditAmount, orderId, "order:$orderId:hold")
        }

        // B11 items: the bundle line first, then its children
        val itemIds = HashMap<String, Long>()
        val rows = ArrayList<MarketOrderItem>()

        for (draftItem in draft.items.sortedBy { it.parentKey != null }) {
            val parentId = draftItem.parentKey?.let { itemIds.getValue(it) }
            val built = draftItem.build(orderId, parentId, draft.reservation.stockReserved[draftItem.key] ?: 0)
            val id = orderItems.add(built, conn)

            itemIds[draftItem.key] = id
            rows += orderItems.getById(id, conn)!!

            if (draftItem.subscription) {
                val seam = subscriptions
                val subscriptionId = if (seam is OfferedPendingSubscriptions) seam.createPending(conn, order, rows.last(), draft.recurring) else seam.createPending(conn, order, rows.last())

                conn.preparedQuery("UPDATE ${table("market_order")} SET `subscriptionId` = ? WHERE `id` = ?").execute(Tuple.of(subscriptionId, orderId)).coAwait()
            }
        }

        // the redemption rows (HELD), the counters were moved by `ReservationService.reserve`
        redemptions.record(conn, orderId, draft.uses, draft.customer)

        val now = clock.now()

        orderEvents.add(
            MarketOrderEvent(
                orderId = orderId, type = OrderEventType.CREATED, actorType = draft.created.actorType, actorUserId = draft.actorUserId,
                data = draft.created.data.copy().put("source", draft.created.source.name).encode(), createdAt = now, updatedAt = now
            ),
            conn
        )

        draft.clearCartOfUser?.let { cartClear(conn, it) }

        return order to rows
    }

    private suspend fun insertOrder(conn: SqlConnection, draft: OrderDraft): MarketOrder {
        var tries = 0

        while (true) {
            val candidate = draft.order(ids.publicId(), ids.hexToken(ACCESS_TOKEN_BYTES))
            val id = orders.tryAdd(candidate, conn)

            if (id != null) return orders.getById(id, conn)!!

            // a duplicate key: the same request already placed an order (replay), or the public id collided (draw another)
            val key = candidate.idempotencyKey

            if (key != null) orders.getByBuyerAndIdempotencyKey(candidate.buyerKey, key, conn)?.let { throw IdempotentReplay(it) }

            check(++tries < MAX_ID_TRIES) { "no free public id for the order after $MAX_ID_TRIES draws" }
        }
    }

    /**
     * A new attempt in state `CREATED` (06 section 9.2): the tender snapshot of [order] as it is now, a fresh reference and token, the
     * `PAYMENT_STARTED` event. Used by the order transaction of checkout and by `/pay`. [extra] is merged into the event `data` (the old and
     * new credit part of a re-tender).
     */
    suspend fun addAttempt(conn: SqlConnection, order: MarketOrder, draft: AttemptDraft, extra: JsonObject? = null): MarketPayment {
        val now = clock.now()
        var tries = 0

        while (true) {
            val reference = ids.reference()
            val token = ids.hexToken(ACCESS_TOKEN_BYTES)
            val id = payments.add(
                MarketPayment(
                    orderId = order.id, providerId = draft.providerId, methodLabel = draft.methodLabel, status = PaymentStatus.CREATED,
                    reference = reference, token = token, amount = order.gatewayAmount, currency = order.currency, feeAmount = order.paymentFee,
                    creditAmount = order.creditAmount, creditValue = order.creditValue, orderTotal = order.totalPrice, testMode = draft.testMode,
                    clientIp = draft.clientIp, userAgent = draft.userAgent, expiresAt = draft.expiresAt, createdAt = now, updatedAt = now
                ),
                conn
            )

            if (id != null) {
                orderEvents.add(
                    MarketOrderEvent(
                        orderId = order.id, type = OrderEventType.PAYMENT_STARTED, actorType = OrderActorType.BUYER, actorUserId = order.userId,
                        data = JsonObject().put("providerId", draft.providerId).put("paymentId", id).also { event -> extra?.let { event.mergeIn(it) } }.encode(),
                        createdAt = now, updatedAt = now
                    ),
                    conn
                )

                return payments.getById(id, conn)!!
            }

            check(++tries < MAX_ID_TRIES) { "no free attempt reference after $MAX_ID_TRIES draws" }
        }
    }

    // ----- the owner view ---------------------------------------------------------------------------------------------

    /**
     * `OrderView` of 04 section 2 for the owner (the payer, or whoever holds the access token): totals, items, the payment
     * with its stored start, the buyer's own data. [start] is the `PaymentStart` of the newest attempt when it is known.
     * Shipments, the payment method list of a retry and delivery states arrive with their slices (the keys are present, empty).
     */
    suspend fun ownerView(order: MarketOrder, sqlClient: SqlClient, start: JsonObject? = null, retry: RetryView? = null): JsonObject {
        val items = orderItems.getByOrderIds(listOf(order.id), sqlClient).filter { it.kind != com.panomc.plugins.market.db.model.OrderItemKind.BUNDLE_CHILD }
        val attempts = payments.getByOrderId(order.id, sqlClient)
        val newest = attempts.lastOrNull()
        val processing = attempts.any { it.status == PaymentStatus.PROCESSING }
        val pending = order.status == OrderStatus.PENDING
        // 06 section 10.2: PENDING, inside the window, no attempt PROCESSING / REVIEW / SUCCEEDED (the hard cap is the payment service's: [retry])
        val blocked = attempts.any { it.status == PaymentStatus.PROCESSING || it.status == PaymentStatus.REVIEW || it.status == PaymentStatus.SUCCEEDED }
        val canRetry = retry?.canRetry ?: (pending && !blocked && (order.expiresAt == null || clock.now() < order.expiresAt))

        return JsonObject()
            .put("publicId", order.publicId)
            .put("number", order.id)
            .put("status", order.status.name)
            .put("fulfillmentStatus", order.fulfillmentStatus.name)
            .put("shippingStatus", order.shippingStatus.name)
            .put("limited", false)
            .put("createdAt", order.createdAt)
            .put("paidAt", order.paidAt)
            .put("expiresAt", order.expiresAt)
            .put("currency", order.currency)
            .put("testMode", order.testMode)
            .put(
                "totals",
                JsonObject()
                    .put("subtotal", money(order.subtotal))
                    .put("discountTotal", money(order.discountTotal))
                    .put("couponDiscount", money(order.couponDiscount))
                    .put("creatorDiscount", money(order.creatorDiscount))
                    .put("upgradeDiscount", money(order.upgradeDiscount))
                    .put("shippingTotal", money(order.shippingTotal))
                    .put("paymentFee", money(order.paymentFee))
                    .put("vatTotal", money(order.vatTotal))
                    .put("total", money(order.totalPrice))
                    .put("creditAmount", money(order.creditAmount))
                    .put("creditValue", money(order.creditValue))
                    .put("gatewayAmount", money(order.gatewayAmount))
                    .put("refundedTotal", money(order.refundedTotal))
            )
            .put("items", JsonArray(items.map { itemView(it) }))
            .put("recipientUsername", order.recipientUsername)
            .put("isGift", order.isGift)
            .put(
                "payment",
                newest?.let { JsonObject().put("methodId", order.paymentMethodId).put("label", order.paymentLabel).put("status", it.status.name).put("start", start) }
            )
            .put("shipping", order.shippingMethodName?.let { JsonObject().put("methodName", it).put("minDays", shippingDays(order, "minDays")).put("maxDays", shippingDays(order, "maxDays")) })
            .put("shipments", JsonArray())
            .put("shippingAddress", order.shippingAddress?.let { parseObject(it) })
            .put("billingInfo", order.billingInfo?.let { parseObject(it) })
            .put("email", order.email)
            .put("invoiceAvailable", order.invoiceId != null)
            .put("canCancel", pending && order.source != OrderSource.RENEWAL && !processing)
            .put("canRetryPayment", canRetry)
            .put("refundPending", refunds?.getByOrderId(order.id, sqlClient)?.any { it.status == RefundStatus.PENDING } ?: false)
            .also { view ->
                if (canRetry && retry != null) {
                    view.put("paymentMethods", retry.methods)
                    view.put("credits", retry.credits)
                }
            }
    }

    /** The data a retry needs (04 section 2 `OrderView.paymentMethods` / `credits`), present only when the order can be paid again. */
    class RetryView(val canRetry: Boolean, val methods: JsonArray = JsonArray(), val credits: JsonObject? = null)

    // ----- transitions (06 section 11) --------------------------------------------------------------------------------

    /**
     * Applies [event] to the order [locked] holds (06 section 11): the pure machine decides ([OrderStateMachine.decide]), a `Move` is
     * the conditional `UPDATE ... WHERE id = ? AND status = ?` (zero rows can only mean the lock protocol was broken: the use case
     * restarts through [OrderChangedException]), one `STATUS_CHANGED` timeline row, then the effects in the order the machine lists them.
     * The caller holds the locks of its scope (`Locks.forOrder`: `COMMIT` for O2 / O3 / O9, `RELEASE` for O6 / O7 / O8) and the payment
     * rows of the order; everything here runs on its connection and only writes rows. The returned [TransitionResult.after] runs after
     * the commit.
     *
     * The review decisions are applied here (MK-079): O4 re-reserves a released hold (limits, stock, codes, then the credit hold again unless
     * `force`), rewrites the tender of an `AMOUNT_MISMATCH` and then does everything of O2; O5 releases the hold and requests the refund of the
     * money received. The effects of refunds and disputes (O10 to O12) belong to the slices that own those flows and stop the transition with
     * [EffectNotOwnedYet]; the effects of other slices go through [ForeignEffects].
     */
    suspend fun transition(conn: SqlConnection, locked: LockedOrder, event: OrderEvent, actorUserId: Long? = null, message: String? = null): TransitionResult {
        val order = orders.getById(locked.order.id, conn) ?: throw OrderChangedException(locked.order.id, "the row is gone")
        val attempts = payments.getByOrderId(order.id, conn)
        val state = OrderState(
            status = order.status,
            reservationState = order.reservationState,
            expiresAt = order.expiresAt,
            hasProcessingAttempt = attempts.any { it.status == PaymentStatus.PROCESSING },
            paidAmount = order.paidAmount,
            statusBeforeDispute = order.statusBeforeDispute,
            reviewReason = order.reviewReason?.let { name -> ReviewReason.entries.firstOrNull { it.name == name } }
        )
        val decision = OrderStateMachine.decide(state, event)

        if (decision !is OrderTransition.Move) return TransitionResult(decision, order.status, emptyList())

        val now = clock.now()
        val changed = conn.preparedQuery("UPDATE ${table("market_order")} SET `status` = ?, `updatedAt` = GREATEST(?, `updatedAt` + 1) WHERE `id` = ? AND `status` = ?")
            .execute(Tuple.of(decision.to.name, now, order.id, order.status.name)).coAwait().rowCount()

        if (changed != 1) throw OrderChangedException(order.id, "the status moved under the lock")

        orderEvents.add(
            MarketOrderEvent(
                orderId = order.id, type = OrderEventType.STATUS_CHANGED, fromStatus = order.status.name, toStatus = decision.to.name,
                actorType = actorTypeOf(event), actorUserId = actorUserId, message = message, createdAt = now, updatedAt = now
            ),
            conn
        )

        val after = ArrayList<AfterCommit>()
        var reviewReason: String? = order.reviewReason
        val closed = ArrayList<MarketPayment>()
        // the order row as the effects of an accept leave it (the tender rewrite changes the credit part), and whether its credit part is held
        var current = order
        var creditsHeld = order.creditAmount > 0 && order.reservationState == ReservationState.HELD

        for (effect in decision.effects) {
            // a manual order the admin created with `runDeliveries = false` / `sendMail = false` (06 section 14.3) takes no delivery rows / no order mail at O2
            if (skippedByManualFlags(conn, order, effect)) continue

            when (effect) {
                is OrderEffect.CommitReservation -> reservationsOrThrow().commit(conn, locked)

                // the redemptions turn APPLIED inside the reservation commit
                is OrderEffect.ApplyRedemptions -> Unit

                is OrderEffect.CaptureCreditHold -> if (current.creditAmount > 0) {
                    check(creditsHeld) { "order ${order.id}: its credit part is not held, nothing to capture" }

                    settlement.capture(conn, current)
                }

                is OrderEffect.StampPaid -> {
                    stampPaid(conn, order, effect)

                    if (event is OrderEvent.ReviewAccepted) {
                        // every other attempt that brought money is a second payment of a paid order from here on: flagged first, so the order's own attempt can be settled
                        flagOtherPayingAttempts(conn, order, now, after)
                        settleReviewedAttempt(conn, order, now)
                    }
                }

                is OrderEffect.ClearExpiry -> updateOrder(conn, order.id, linkedMapOf("expiresAt" to null))

                is OrderEffect.CancelOpenAttempts -> closed += closeOpenAttempts(conn, order.id, PaymentStatus.CANCELLED)

                is OrderEffect.QueueWebhook ->
                    if (effect.event == OrderStateMachine.WEBHOOK_PAID) webhooks.orderPaid(conn, order.id) else foreign.apply(conn, locked, effect)

                is OrderEffect.SetReviewReason -> {
                    reviewReason = effect.reason.name

                    updateOrder(conn, order.id, linkedMapOf("reviewReason" to effect.reason.name))

                    orderEvents.add(
                        MarketOrderEvent(
                            orderId = order.id, type = OrderEventType.REVIEW_OPENED, actorType = actorTypeOf(event),
                            data = JsonObject().put("reason", effect.reason.name).put("attemptId", attemptOf(event)).encode(), createdAt = now, updatedAt = now
                        ),
                        conn
                    )
                }

                is OrderEffect.RecordPayment -> {
                    val attempt = effect.attemptId?.let { payments.getById(it, conn) }

                    if (attempt != null) updateOrder(conn, order.id, linkedMapOf("paymentId" to attempt.id, "paidAmount" to (attempt.paidAmount ?: 0L)))
                }

                is OrderEffect.PanelAlert -> after += AfterCommit.PanelAlert(order.id, reviewReason)

                is OrderEffect.ReleaseReservation -> {
                    // `release` is idempotent on the reservation state; the credit hold goes back only when the stock did (06 section 7.3)
                    if (reservationsOrThrow().release(conn, locked) && order.creditAmount > 0) settlement.release(conn, order)
                }

                is OrderEffect.CloseOpenAttempts -> closed += closeOpenAttempts(conn, order.id, effect.to)

                is OrderEffect.CancelAtGatewayAfterCommit -> Unit

                is OrderEffect.SubscriptionOnClosedUnpaid -> if (order.subscriptionId != null) foreign.apply(conn, locked, effect)

                is OrderEffect.AccrueCreatorEarning, is OrderEffect.GrantCashback, is OrderEffect.CreditGrantingLines, is OrderEffect.GrantEntitlements,
                is OrderEffect.QueueGrantDeliveries, is OrderEffect.SubscriptionOnOrderPaid, is OrderEffect.IssueInvoice, is OrderEffect.QueueMail,
                is OrderEffect.AdvanceGoalProgress, is OrderEffect.StartShipping -> foreign.apply(conn, locked, effect)

                is OrderEffect.ReReserve -> {
                    if (!effect.force) limits.check(conn, locked)

                    check(reservationsOrThrow().reReserve(conn, locked, effect.force)) { "order ${order.id} was not RELEASED when its re-reserve ran" }

                    // the hold went back with the stock (06 section 7.3): it is placed again before anything is captured. An AMOUNT_MISMATCH accept
                    // re-tenders to the paid attempt's credit part (RewriteTender holds exactly that), so the superseded part is never held
                    if (order.creditAmount > 0 && decision.effects.none { it is OrderEffect.RewriteTender }) {
                        settlement.rehold(conn, order, order.creditAmount)

                        creditsHeld = true
                    }
                }

                is OrderEffect.RecordForceOverride -> orderEvents.add(
                    MarketOrderEvent(
                        orderId = order.id, type = OrderEventType.NOTE, actorType = OrderActorType.ADMIN, actorUserId = actorUserId, message = FORCE_OVERRIDE_NOTE,
                        data = JsonObject().put("override", FORCE_OVERRIDE_NOTE).put("codesAndLimitsReReserved", false).put("stockClampedAtZero", true).encode(),
                        createdAt = now, updatedAt = now
                    ),
                    conn
                )

                is OrderEffect.RewriteTender -> {
                    val tender = rewriteTender(conn, order, creditsHeld)

                    current = tender.order
                    creditsHeld = tender.creditsHeld
                }

                is OrderEffect.CreateRefundForPaidAmount -> requestRefundsForReceivedMoney(conn, order, actorUserId, now)

                is OrderEffect.ReserveStockAndLimits, is OrderEffect.HoldCredits, is OrderEffect.SetExpiresAt ->
                    throw IllegalStateException("${effect::class.simpleName} belongs to order creation, not to a transition")

                is OrderEffect.RevokeDeliveries, is OrderEffect.RestockItems, is OrderEffect.ReverseCreatorEarning, is OrderEffect.ReverseCashback,
                is OrderEffect.ClawbackGrantedCredits, is OrderEffect.ReturnCreditsToLedger, is OrderEffect.IssueCreditNote,
                is OrderEffect.SubscriptionOnOrderRefunded, is OrderEffect.SubscriptionOnOrderChargeback, is OrderEffect.SaveStatusBeforeDispute,
                is OrderEffect.BlockBuyer, is OrderEffect.RunChargebackActions -> throw EffectNotOwnedYet(effect, "the refund and dispute slices")
            }
        }

        // O3 (12 section 4.1): a payment that waits for a human is acknowledged with `ORDER_RECEIVED` (MK-142; its own seam, the machine's effect list stays as it is)
        if (decision.to == OrderStatus.REVIEW && order.status == OrderStatus.PENDING) receivedMails.received(conn, order)

        // the gateway cancel of what this transition closed: every attempt it set to a closed state, once
        if (closed.isNotEmpty()) after += AfterCommit.CancelAtGateway(closed.distinctBy { it.id })

        return TransitionResult(decision, order.status, after)
    }

    /**
     * 06 section 14.3: the flags of a manual order live in the `data` of its `CREATED` row ([ManualFlags]); `QueueGrantDeliveries` is skipped when
     * `runDeliveries` is off, `QueueMail` when `sendMail` is off. Every other order and effect is untouched (and costs no read).
     */
    private suspend fun skippedByManualFlags(conn: SqlConnection, order: MarketOrder, effect: OrderEffect): Boolean {
        if (order.source != OrderSource.PANEL) return false
        if (effect !is OrderEffect.QueueGrantDeliveries && effect !is OrderEffect.QueueMail) return false

        val created = orderEvents.getByOrderId(order.id, conn).firstOrNull { it.type == OrderEventType.CREATED }
        val flags = ManualFlags.of(created?.data)

        return if (effect is OrderEffect.QueueGrantDeliveries) !flags.runDeliveries else !flags.sendMail
    }

    /**
     * `AMOUNT_MISMATCH` accepted (06 section 9.4): the order's tender becomes the snapshot of the attempt that was paid (`creditAmount`,
     * `creditValue`, `paymentFee = feeAmount`, `totalPrice = orderTotal`, `gatewayAmount = amount`) and the credit hold follows it: a held part is
     * re-tendered (`RELEASE(old)`, `HOLD(new)`), a part that is not held is held fresh. `InsufficientCredits` rolls the accept back.
     */
    private suspend fun rewriteTender(conn: SqlConnection, order: MarketOrder, held: Boolean): Tender {
        val attemptId = checkNotNull(order.paymentId) { "order ${order.id}: an AMOUNT_MISMATCH review without the paid attempt" }
        val attempt = checkNotNull(payments.getById(attemptId, conn)) { "order ${order.id}: paid attempt $attemptId is gone" }
        var holding = held

        if (held) {
            if (attempt.creditAmount != order.creditAmount) {
                settlement.retender(conn, order, attempt.creditAmount)

                holding = attempt.creditAmount > 0
            }
        } else {
            // nothing is held (a released order, or an order without a credit part): the paid attempt's part is held fresh, also when it equals the order's old part
            if (attempt.creditAmount > 0) settlement.rehold(conn, order, attempt.creditAmount)

            holding = attempt.creditAmount > 0
        }

        updateOrder(
            conn, order.id,
            linkedMapOf(
                "creditAmount" to attempt.creditAmount, "creditValue" to attempt.creditValue, "paymentFee" to attempt.feeAmount,
                "totalPrice" to attempt.orderTotal, "gatewayAmount" to attempt.amount
            )
        )

        val now = clock.now()

        orderEvents.add(
            MarketOrderEvent(
                orderId = order.id, type = OrderEventType.NOTE, actorType = OrderActorType.ADMIN, message = TENDER_REWRITTEN_NOTE,
                data = JsonObject().put("paymentId", attempt.id).put("creditAmount", attempt.creditAmount).put("totalPrice", attempt.orderTotal)
                    .put("gatewayAmount", attempt.amount).put("previousCreditAmount", order.creditAmount).put("previousTotalPrice", order.totalPrice).encode(),
                createdAt = now, updatedAt = now
            ),
            conn
        )

        return Tender(orders.getById(order.id, conn)!!, holding)
    }

    private class Tender(val order: MarketOrder, val creditsHeld: Boolean)

    /**
     * O4 with several paying attempts (00 section 7.2): while the order sat in `REVIEW` every attempt that brought money was recorded on it. The
     * accept makes the order's own attempt (`paymentId`) the payment of the order; each other attempt with money (`SUCCEEDED` or `REVIEW`) is a second
     * paid attempt on a paid order from now on: flagged `duplicate = 1` and handled like any duplicate (a `SYSTEM` refund request, or a timeline note
     * and a panel alert). The order's `paidAmount` is then the own attempt's money, not the sum.
     */
    private suspend fun flagOtherPayingAttempts(conn: SqlConnection, order: MarketOrder, now: Long, after: MutableList<AfterCommit>) {
        val ownId = order.paymentId ?: return
        val own = payments.getById(ownId, conn) ?: return
        val others = payments.getByOrderId(order.id, conn).filter {
            it.id != ownId && (it.paidAmount ?: 0L) > 0 && !it.duplicate && (it.status == PaymentStatus.SUCCEEDED || it.status == PaymentStatus.REVIEW)
        }

        if (others.isEmpty()) return

        for (other in others) {
            val rows = conn.preparedQuery("UPDATE ${table("market_payment")} SET `duplicate` = 1, `updatedAt` = ? WHERE `id` = ? AND `duplicate` = 0")
                .execute(Tuple.of(now, other.id)).coAwait().rowCount()

            if (rows != 1) continue

            val rule = duplicates.rule(conn, other.providerId)

            onDuplicatePayment(conn, order.id, other.id, rule.autoRefund, rule.providerCanRefund, after)
        }

        if ((own.paidAmount ?: 0L) > 0) updateOrder(conn, order.id, linkedMapOf("paidAmount" to own.paidAmount))
    }

    /**
     * O4: the human accepted the money of the attempt that went to `REVIEW` (underpaid, overpaid, wrong currency, ...) as the payment of the order, so
     * that attempt is `SUCCEEDED` from now on (a completed order is never left pointing at a `REVIEW` attempt). One `SUCCEEDED` attempt per order
     * is a standing invariant (I13): when another attempt of the order already holds that place the row is left as it is.
     */
    private suspend fun settleReviewedAttempt(conn: SqlConnection, order: MarketOrder, now: Long) {
        val attempt = order.paymentId?.let { payments.getById(it, conn) } ?: return

        if (attempt.status != PaymentStatus.REVIEW) return
        if (payments.getByOrderId(order.id, conn).any { it.id != attempt.id && it.status == PaymentStatus.SUCCEEDED && !it.duplicate }) return

        val rows = conn.preparedQuery(
            "UPDATE ${table("market_payment")} SET `status` = ?, `closedAt` = COALESCE(`closedAt`, ?), `startPayload` = NULL, `updatedAt` = ? WHERE `id` = ? AND `status` = ?"
        ).execute(Tuple.of(PaymentStatus.SUCCEEDED.name, now, now, attempt.id, PaymentStatus.REVIEW.name)).coAwait().rowCount()

        if (rows != 1) throw OrderChangedException(order.id, "attempt ${attempt.id} moved under the lock")

        orderEvents.add(
            MarketOrderEvent(
                orderId = order.id, type = OrderEventType.PAYMENT_SUCCEEDED, actorType = OrderActorType.ADMIN,
                data = JsonObject().put("paymentId", attempt.id).put("providerId", attempt.providerId).put("from", PaymentStatus.REVIEW.name)
                    .put("to", PaymentStatus.SUCCEEDED.name).put("accepted", true).put("paidAmount", attempt.paidAmount).encode(),
                createdAt = now, updatedAt = now
            ),
            conn
        )
    }

    /**
     * O5 with `refund = true` (06 section 11): the money the order received is requested back, one `SYSTEM` refund row per attempt that
     * brought money (a duplicate was refunded on its own), in the currency the gateway took it in. The credit part is never refunded: a hold that
     * is still there was released by the same transition. The refund is only requested here; the gateway call is the refund service's.
     */
    private suspend fun requestRefundsForReceivedMoney(conn: SqlConnection, order: MarketOrder, actorUserId: Long?, now: Long) {
        checkNotNull(refunds) { "order ${order.id}: this OrderService was built without the refund table, it cannot request the refund of a rejected review" }
        val money = payments.getByOrderId(order.id, conn).filter {
            (it.paidAmount ?: 0L) > 0 && !it.duplicate && (it.status == PaymentStatus.SUCCEEDED || it.status == PaymentStatus.REVIEW)
        }

        if (money.isEmpty()) {
            // paid money that no attempt row carries cannot be sent back to a gateway automatically: the transition refuses instead of closing the order on it
            check(order.paidAmount <= 0) { "order ${order.id} recorded ${order.paidAmount} as received, but no attempt carries it" }

            return
        }

        for (attempt in money) {
            requestRefund(conn, order, attempt, attempt.paidAmount!!, "sys:reject:${order.id}:${attempt.id}", REASON_REVIEW_REJECTED, actorUserId, now)
        }
    }

    /** A `REQUESTED` refund row (`origin = SYSTEM`) for the money of [attempt] plus its timeline row; replays under the same [key] are harmless. */
    private suspend fun requestRefund(
        conn: SqlConnection, order: MarketOrder, attempt: MarketPayment, amount: Long, key: String, reason: String, actorUserId: Long?, now: Long
    ): Long? {
        val table = checkNotNull(refunds) { "order ${order.id}: this OrderService was built without the refund table" }
        val currency = attempt.paidCurrency ?: attempt.currency
        val id = table.add(
            MarketRefund(
                orderId = order.id, paymentId = attempt.id, providerId = attempt.providerId, status = RefundStatus.REQUESTED, origin = RefundOrigin.SYSTEM,
                idempotencyKey = key, amount = amount, gatewayAmount = amount, currency = currency, reason = reason, revoke = false, restock = false,
                initiatedBy = actorUserId, createdAt = now, updatedAt = now
            ),
            conn
        ) ?: return null

        orderEvents.add(
            MarketOrderEvent(
                orderId = order.id, type = OrderEventType.REFUND_REQUESTED, actorType = if (actorUserId != null) OrderActorType.ADMIN else OrderActorType.SYSTEM,
                actorUserId = actorUserId,
                data = JsonObject().put("refundId", id).put("origin", RefundOrigin.SYSTEM.name).put("reason", reason).put("paymentId", attempt.id)
                    .put("amount", amount).put("currency", currency).encode(),
                createdAt = now, updatedAt = now
            ),
            conn
        )

        return id
    }

    /**
     * A second attempt of a paid order was paid (00 section 7.2, `duplicate = 1` is already written): with `autoRefundDuplicatePayments`, a provider
     * that can refund and a refund table, a `SYSTEM` refund of exactly that attempt's money is requested; in every other case the order timeline gets
     * an alert row saying why and the panel is alerted after the commit. The caller holds the order lock (the scopes of `PaymentService.applyIn`).
     * The refund row carries the duplicate attempt's `paymentId`, so the refund service must not treat it as a refund of the order's own payment (no O10).
     */
    suspend fun onDuplicatePayment(
        conn: SqlConnection, orderId: Long, attemptId: Long, autoRefund: Boolean, providerCanRefund: Boolean, after: MutableList<AfterCommit>
    ) {
        val order = orders.getById(orderId, conn) ?: throw OrderChangedException(orderId, "the row is gone")
        val attempt = payments.getById(attemptId, conn) ?: throw NoSuchElementException("attempt $attemptId does not exist")
        val now = clock.now()
        val paid = attempt.paidAmount ?: 0L
        val why = when {
            paid <= 0 -> "NO_MONEY_RECORDED"
            !autoRefund -> "AUTO_REFUND_OFF"
            !providerCanRefund -> "REFUND_NOT_SUPPORTED"
            refunds == null -> "NO_REFUND_TABLE"
            else -> null
        }

        if (why == null) {
            requestRefund(conn, order, attempt, paid, "sys:dup:${attempt.id}", REASON_DUPLICATE_PAYMENT, null, now)

            return
        }

        orderEvents.add(
            MarketOrderEvent(
                orderId = orderId, type = OrderEventType.NOTE, actorType = OrderActorType.SYSTEM, message = DUPLICATE_PAYMENT_ALERT,
                data = JsonObject().put("alert", DUPLICATE_PAYMENT_ALERT).put("paymentId", attempt.id).put("providerId", attempt.providerId).put("paidAmount", paid)
                    .put("paidCurrency", attempt.paidCurrency ?: attempt.currency).put("refund", "NOT_REQUESTED").put("why", why).encode(),
                createdAt = now, updatedAt = now
            ),
            conn
        )

        after += AfterCommit.PanelAlert(orderId, DUPLICATE_PAYMENT_ALERT)
    }

    private fun attemptOf(event: OrderEvent): Long? = when (event) {
        is OrderEvent.NeedsReview -> event.attemptId
        is OrderEvent.LatePayment -> event.attemptId
        else -> null
    }

    private fun reservationsOrThrow(): ReservationService =
        checkNotNull(reservations) { "this OrderService was built without the reservation service: it cannot move an order" }

    private fun actorTypeOf(event: OrderEvent): OrderActorType = when (event) {
        is OrderEvent.Paid -> actor(event.actor)
        is OrderEvent.NeedsReview -> actor(event.actor)
        is OrderEvent.Cancel -> actor(event.actor)
        is OrderEvent.Fail -> actor(event.actor)
        is OrderEvent.Expire, is OrderEvent.Create -> OrderActorType.SYSTEM
        is OrderEvent.LatePayment -> OrderActorType.GATEWAY
        is OrderEvent.ReviewAccepted, is OrderEvent.ReviewRejected -> OrderActorType.ADMIN
        is OrderEvent.RefundSucceeded, is OrderEvent.DisputeOpened, is OrderEvent.DisputeWon -> OrderActorType.GATEWAY
    }

    private fun actor(actor: OrderActor): OrderActorType = OrderActorType.valueOf(actor.name)

    /** O2: `paymentId`, `paidAt = now`, `paidAmount`, the paying attempt's `testMode`, the frozen `exchangeRate` (06 section 11). */
    private suspend fun stampPaid(conn: SqlConnection, order: MarketOrder, effect: OrderEffect.StampPaid) {
        val attempt = effect.attemptId?.let { payments.getById(it, conn) }
        val sets = linkedMapOf<String, Any?>("paidAt" to clock.now())

        if (attempt != null) sets["paymentId"] = attempt.id
        if (!effect.keepPaidAmount) sets["paidAmount"] = attempt?.paidAmount ?: order.gatewayAmount
        if (attempt != null) sets["testMode"] = attempt.testMode

        statsRate(conn, order)?.let { sets["exchangeRate"] = it }

        updateOrder(conn, order.id, sets)
    }

    /**
     * Stats currency units per 1 order-currency unit, from the rate table the order was priced with (units per 1 base unit); `1.0`
     * when the order is already in the stats currency, `null` when the table cannot say (the stats queries then fall back to the
     * current view rate). The live provider fetch of `ExchangeRateService` is a network call and cannot run in this transaction.
     */
    private suspend fun statsRate(conn: SqlClient, order: MarketOrder): Double? {
        val stats = statsCurrency().trim().uppercase()

        if (stats.isEmpty()) return null
        if (order.currency.equals(stats, ignoreCase = true)) return 1.0

        val table = rates(conn)

        fun perBase(currency: String): BigDecimal? = if (currency.equals(order.baseCurrency, ignoreCase = true)) BigDecimal.ONE else table[currency.uppercase()]

        val toStats = perBase(stats) ?: return null
        val toOrder = perBase(order.currency) ?: return null

        if (toOrder.signum() <= 0) return null

        return toStats.divide(toOrder, 10, RoundingMode.HALF_UP).toDouble()
    }

    /**
     * Sets every attempt of [orderId] that is still `CREATED`, `PENDING` or `PROCESSING` to [to] (the conditional update checks the
     * status it read), clears its stored start and stamps `closedAt`; one timeline row each. Returns the attempts as they were.
     */
    suspend fun closeOpenAttempts(conn: SqlConnection, orderId: Long, to: PaymentStatus, exceptAttemptId: Long? = null): List<MarketPayment> {
        val now = clock.now()
        val open = payments.getByOrderId(orderId, conn).filter { it.id != exceptAttemptId && it.status in OPEN_ATTEMPT }
        val closed = ArrayList<MarketPayment>()

        for (attempt in open) {
            val rows = conn.preparedQuery(
                "UPDATE ${table("market_payment")} SET `status` = ?, `closedAt` = ?, `startPayload` = NULL, `updatedAt` = ? WHERE `id` = ? AND `status` = ?"
            ).execute(Tuple.of(to.name, now, now, attempt.id, attempt.status.name)).coAwait().rowCount()

            if (rows != 1) continue

            closed += attempt

            orderEvents.add(
                MarketOrderEvent(
                    orderId = orderId, type = if (to == PaymentStatus.CANCELLED) OrderEventType.PAYMENT_CANCELLED else OrderEventType.PAYMENT_FAILED,
                    actorType = OrderActorType.SYSTEM, data = JsonObject().put("paymentId", attempt.id).put("status", to.name).encode(), createdAt = now, updatedAt = now
                ),
                conn
            )
        }

        return closed
    }

    /** `/pay`: the ledger moves the credit part of [order] to [newCredits] (07 section 6.4, C2); throws when it cannot, which rolls the whole re-tender back. */
    suspend fun retenderCredits(conn: SqlConnection, order: MarketOrder, newCredits: Long) = settlement.retender(conn, order, newCredits)

    /** `UPDATE market_order SET <sets>, updatedAt = GREATEST(now, updatedAt + 1) WHERE id = ?`: every write to the order grows its version (06 section 13.2). The keys are column names written by this module. */
    suspend fun updateOrder(conn: SqlClient, orderId: Long, sets: Map<String, Any?>) {
        val columns = sets.keys.joinToString(", ") { "`$it` = ?" }
        val values = ArrayList<Any?>(sets.values)

        values += clock.now()
        values += orderId

        conn.preparedQuery("UPDATE ${table("market_order")} SET $columns, `updatedAt` = GREATEST(?, `updatedAt` + 1) WHERE `id` = ?").execute(Tuple.from(values)).coAwait()
    }

    private fun itemView(item: MarketOrderItem): JsonObject {
        val snapshot = item.snapshot?.let { parseObject(it) }

        return JsonObject()
            .put("id", item.id)
            .put("productId", item.productId)
            .put("name", item.productName)
            .put("variantName", item.variantName)
            .put("imageFileName", snapshot?.getString("imageFileName"))
            .put("quantity", item.quantity)
            .put("unitPrice", money(item.unitPrice))
            .put("lineTotal", money(item.lineTotal))
            .put("fieldValues", item.fieldValues?.let { parseObject(it) } ?: JsonObject())
            .put("targetServerName", null as String?)
            .put("delivery", "NONE")
            .put("expiresAt", null as Long?)
    }

    private fun shippingDays(order: MarketOrder, key: String): Int? =
        order.shippingQuote?.let { parseObject(it) }?.getValue(key)?.let { (it as? Number)?.toInt() }

    private fun parseObject(raw: String): JsonObject? = runCatching { JsonObject(raw) }.getOrNull()

    private fun money(amount: Long): Double = MoneyUtil.toDecimal(amount)

    companion object {
        /** 160 bits: the access token of an order and the notify / return token of an attempt are 40 hex characters. */
        const val ACCESS_TOKEN_BYTES = 20
        private const val MAX_ID_TRIES = 3
        private val OPEN_ATTEMPT = setOf(PaymentStatus.CREATED, PaymentStatus.PENDING, PaymentStatus.PROCESSING)

        /** Timeline `NOTE` messages and refund reasons written by the review flow (`market_order_event.message`, `market_refund.reason`). */
        const val FORCE_OVERRIDE_NOTE = "FORCE_OVERRIDE"
        const val TENDER_REWRITTEN_NOTE = "TENDER_REWRITTEN"
        const val DUPLICATE_PAYMENT_ALERT = "DUPLICATE_PAYMENT"
        const val REASON_REVIEW_REJECTED = "REVIEW_REJECTED"
        const val REASON_DUPLICATE_PAYMENT = "DUPLICATE_PAYMENT"
    }
}
