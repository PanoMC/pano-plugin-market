package com.panomc.plugins.market.service

import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.core.time.Ids
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderEventDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketPaymentDao
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderEvent
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.OrderActorType
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.error.CreditsDisabled
import com.panomc.plugins.market.error.PaymentMethodUnavailable
import com.panomc.plugins.market.util.MoneyUtil
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

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
    val attempt: AttemptDraft,
    /** The payer whose server cart the transaction empties (06 section 2.4), `null` = leave every cart alone. */
    val clearCartOfUser: Long?,
    val actorUserId: Long?
)

/** What the order transaction produced. */
class CreatedOrder(val order: MarketOrder, val items: List<MarketOrderItem>, val attempt: MarketPayment)

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
    private val subscriptions: PendingSubscriptions = PendingSubscriptions.UNAVAILABLE
) {
    private fun table(name: String) = "`${orders.prefix()}$name`"

    suspend fun create(conn: SqlConnection, draft: OrderDraft): CreatedOrder {
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
                val subscriptionId = subscriptions.createPending(conn, order, rows.last())

                conn.preparedQuery("UPDATE ${table("market_order")} SET `subscriptionId` = ? WHERE `id` = ?").execute(Tuple.of(subscriptionId, orderId)).coAwait()
            }
        }

        // the redemption rows (HELD), the counters were moved by `ReservationService.reserve`
        redemptions.record(conn, orderId, draft.uses, draft.customer)

        val now = clock.now()

        orderEvents.add(
            MarketOrderEvent(
                orderId = orderId, type = OrderEventType.CREATED, actorType = OrderActorType.BUYER, actorUserId = draft.actorUserId,
                data = JsonObject().put("source", OrderSource.STOREFRONT.name).encode(), createdAt = now, updatedAt = now
            ),
            conn
        )

        draft.clearCartOfUser?.let { cartClear(conn, it) }

        val attempt = insertAttempt(conn, order, draft.attempt)

        return CreatedOrder(orders.getById(orderId, conn)!!, rows, attempt)
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

    private suspend fun insertAttempt(conn: SqlConnection, order: MarketOrder, draft: AttemptDraft): MarketPayment {
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
                        data = JsonObject().put("providerId", draft.providerId).put("paymentId", id).encode(), createdAt = now, updatedAt = now
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
    suspend fun ownerView(order: MarketOrder, sqlClient: SqlClient, start: JsonObject? = null): JsonObject {
        val items = orderItems.getByOrderIds(listOf(order.id), sqlClient).filter { it.kind != com.panomc.plugins.market.db.model.OrderItemKind.BUNDLE_CHILD }
        val attempts = payments.getByOrderId(order.id, sqlClient)
        val newest = attempts.lastOrNull()
        val processing = attempts.any { it.status == PaymentStatus.PROCESSING }
        val pending = order.status == OrderStatus.PENDING

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
            .put("canRetryPayment", pending && !processing)
            .put("refundPending", false)
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
    }
}
