package com.panomc.plugins.market.service

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.delivery.DeliveryError
import com.panomc.plugins.market.core.order.OrderEffect
import com.panomc.plugins.market.core.subscription.CancelActor
import com.panomc.plugins.market.core.subscription.RenewalDedupe
import com.panomc.plugins.market.core.time.Backoff
import com.panomc.plugins.market.db.model.RemoteCancelState
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.core.subscription.ModeOffer
import com.panomc.plugins.market.core.subscription.ModeResolver
import com.panomc.plugins.market.core.subscription.PeriodCalculator
import com.panomc.plugins.market.core.subscription.RecurringPlan
import com.panomc.plugins.market.core.subscription.SubConfig
import com.panomc.plugins.market.core.subscription.SubEffect
import com.panomc.plugins.market.core.subscription.SubEvent
import com.panomc.plugins.market.core.subscription.SubState
import com.panomc.plugins.market.core.subscription.SubTransition
import com.panomc.plugins.market.core.subscription.SubscriptionEndReason
import com.panomc.plugins.market.core.subscription.SubscriptionStateMachine
import com.panomc.plugins.market.core.subscription.SubscriptionTimings
import com.panomc.plugins.market.core.subscription.toPeriodUnit
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.core.webhook.EventPayloads
import com.panomc.plugins.market.db.dao.MarketEntitlementDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderEventDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketPaymentDao
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.dao.MarketProductVariantDao
import com.panomc.plugins.market.db.dao.MarketSubscriptionDao
import com.panomc.plugins.market.db.dao.MarketSubscriptionRenewalDao
import com.panomc.plugins.market.db.dao.isDuplicateKey
import com.panomc.plugins.market.db.model.MailKind
import com.panomc.plugins.market.db.model.MailRefType
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderEvent
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.MarketSubscription
import com.panomc.plugins.market.db.model.MarketSubscriptionRenewal
import com.panomc.plugins.market.db.model.OrderActorType
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.db.model.PeriodUnit
import com.panomc.plugins.market.db.model.RenewalStatus
import com.panomc.plugins.market.db.model.SubscriptionIntervalUnit
import com.panomc.plugins.market.db.model.SubscriptionMode
import com.panomc.plugins.market.db.model.SubscriptionStatus
import com.panomc.plugins.market.db.tx.LockedOrder
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.OrderChangedException
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.error.InvalidOrderTransition
import com.panomc.platform.error.NotLoggedIn
import com.panomc.plugins.market.error.PaymentMethodUnavailable
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.routes.api.payment.InboundEventContext
import com.panomc.plugins.market.routes.api.payment.PaymentEventSink
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.payment.GatewaySubscriptionState
import com.panomc.plugins.market.spi.payment.GatewaySubscriptionStatus
import com.panomc.plugins.market.spi.payment.IntervalUnit
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.RecurringSupport
import com.panomc.plugins.market.spi.payment.ReviewReason
import com.panomc.plugins.market.spi.payment.SubscriptionPlan
import com.panomc.plugins.market.spi.payment.SubscriptionView
import com.panomc.plugins.market.core.money.Rounding
import com.panomc.plugins.market.core.order.OrderActor
import com.panomc.plugins.market.core.order.OrderEvent
import com.panomc.plugins.market.core.time.Ids
import com.panomc.plugins.market.core.time.SecureIds
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.db.model.PricingMode
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.spi.payment.StoredPaymentMethod
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import org.slf4j.LoggerFactory

/**
 * What [PaymentService] asks of the subscription side of a payment (09 section 2). [planFor] is the plan a start carries (`StartPaymentRequest.subscription`,
 * non-null only for an automatic offer); [pendingPlan] and [onMethodChanged] are the retry of the initial order (`/pay` re-tenders it, the `PENDING` row
 * follows, 09 section 4.3); [onPaid] hands over what a `Succeeded` says about the subscription, inside the transaction that applies it, so the activation of
 * O2 / O4 can read it even when an admin accepts the payment long after, and answers the gateway subscription that must not be kept (09 section 4.4, last
 * paragraph) as an [AfterCommit] step.
 */
interface PaymentSubscriptions {
    suspend fun planFor(order: MarketOrder, sqlClient: SqlClient): SubscriptionPlan?

    /** The plan of the initial order of a subscription whose row is still `PENDING` (the order may be retried); `null` for every other order. */
    suspend fun pendingPlan(order: MarketOrder, sqlClient: SqlClient): PendingPlan?

    /**
     * `/pay` re-tendered the initial order of a `PENDING` subscription: [order] is the order as rewritten (provider, fee, total), [recurring] the offer
     * table's verdict for [providerId] (`"AUTO"` / `"MANUAL"`, `null` = decide from the capabilities). `true` when the row was rewritten.
     */
    suspend fun onMethodChanged(conn: SqlConnection, order: MarketOrder, providerId: String, recurring: String?): Boolean

    /** A success on [order] (a subscription order): stores what it says on the row. The returned step, if any, runs after the commit. */
    suspend fun onPaid(conn: SqlConnection, order: MarketOrder, attempt: MarketPayment, facts: AttemptFacts): AfterCommit?

    /**
     * An attempt of the renewal [order] reached `FAILED` / `EXPIRED` (09 section 9.1), inside the transaction that closed it, with the subscription row and the
     * order locked. [final] is the gateway's `Failed.final`; [facts] carry the failure code and, for a charge market made itself, how the call failed.
     */
    suspend fun onAttemptFailed(conn: SqlConnection, order: MarketOrder, attempt: MarketPayment, final: Boolean, facts: AttemptFacts) {}

    companion object {
        /** A payment service without subscriptions: no plan, nothing recorded. */
        val NONE: PaymentSubscriptions = object : PaymentSubscriptions {
            override suspend fun planFor(order: MarketOrder, sqlClient: SqlClient): SubscriptionPlan? = null

            override suspend fun pendingPlan(order: MarketOrder, sqlClient: SqlClient): PendingPlan? = null

            override suspend fun onMethodChanged(conn: SqlConnection, order: MarketOrder, providerId: String, recurring: String?): Boolean = false

            override suspend fun onPaid(conn: SqlConnection, order: MarketOrder, attempt: MarketPayment, facts: AttemptFacts): AfterCommit? = null
        }
    }
}

/** The recurring plan of a subscription that is still being bought: what the offer table and `checkEligibility` judge, and the plan a start carries at a given price. */
class PendingPlan(val subscriptionId: Long, val productId: Long, val variantId: Long, val productName: String, val recurring: RecurringPlan) {
    /** `StartPaymentRequest.subscription` / `CheckoutSnapshot.subscription` at [price] (the per-period amount including VAT and payment fee). */
    fun at(price: Long): SubscriptionPlan = SubscriptionPlan(
        subscriptionId, CheckoutService.planKey(productId, variantId, price, recurring.currency, recurring), productName, Money(price, recurring.currency),
        recurring.intervalUnit, recurring.intervalCount, recurring.maxCycles
    )
}

/**
 * 09 section 4.4, last paragraph: a gateway subscription that a payment carried but the subscription row does not keep (the `Succeeded` of a duplicate
 * attempt, a late success after the row was closed, a second paying attempt of an order in review) is cancelled at the gateway right after the commit,
 * with [view] built from the event; a failure is written to the order timeline, because the gateway keeps billing the buyer for it.
 */
class CancelSurplusSubscription(val orderId: Long, val subscriptionId: Long, val providerId: String, val view: SubscriptionView) : AfterCommit {
    companion object {
        /** `CancelSubscriptionRequest.reason` of the call. */
        const val REASON = "duplicate subscription"

        /** The order event written when the gateway subscription could not be cancelled. */
        const val FAILED_NOTE = "a gateway subscription of a duplicate payment could not be cancelled, cancel it at the gateway"
    }
}

/** The store webhook writer a subscription transition uses (09 section 12.1); [WebhookService.emit] in production. */
fun interface SubscriptionWebhooks {
    suspend fun emit(conn: SqlConnection, event: String, subjectKey: String, orderId: Long?, data: JsonObject, testMode: Boolean)

    companion object {
        val NONE = SubscriptionWebhooks { _, _, _, _, _, _ -> }
    }
}

/** A renewal effect reached a service that only implements the activation half of the state machine (MK-122 / MK-123 own the rest). */
class SubscriptionEffectNotOwned(val effect: SubEffect, val owner: String) :
    IllegalStateException("${effect::class.simpleName} is applied by $owner, which is not wired yet")

/**
 * Subscriptions (09): the pending row at O1, the activation at O2 / O4, the status events of a gateway-managed subscription, and the shared ending of a
 * subscription. The decisions are [SubscriptionStateMachine]'s (pure); this class owns the SQL, the lock protocol and the effects of a decision:
 *
 * - [createPending] is `SubscriptionService.createPending` of 09 section 4.3, called by [OrderService] inside the checkout transaction after the order
 *   and its item exist. The row starts `PENDING` with the per-period price (`order.totalPrice`, including VAT and payment fee), the interval of the
 *   product (a variant's `periodCount` wins), `maxCycles`, and a provisional `mode` from the offer table of 09 section 4.2.
 * - [onPaid] (hook of [PaymentService]) stores the gateway subscription / stored method of the order's own paying attempt on the pending row (the one a
 *   duplicate, a late or a second paying attempt carries is cancelled at the gateway after the commit, [CancelSurplusSubscription]), [onOrderPaid] (the
 *   `SubscriptionOnOrderPaid` effect of O2 / O4, through [SubscriptionEffects]) activates it with the mode the paying attempt actually delivered.
 * - [pendingPlan] / [onMethodChanged] (hooks of `/pay`): a retry of the initial order re-tenders it and the pending row follows (provider, mode, price).
 * - [onOrderClosedUnpaid] closes a pending row when a review is rejected (O5); an expired, cancelled or failed order leaves it `PENDING` (a late payment
 *   may still arrive, `SubscriptionJob` closes it after 30 days).
 * - [onGatewayEvent] applies a `SubscriptionUpdated` (09 section 7): the gateway's status through the state machine; the period columns are never
 *   moved by a status event.
 * - [reviewReasonFor] / [SubscriptionClosedGuard] (09 section 8.5): a payment for a renewal of a closed subscription goes to review (`LATE`).
 *
 * Lock rule (09 section 2): a mutation of an order that has a subscription holds the credit account, then the subscription row, then the order
 * (`Locks.forOrder` for every scope but `PAYMENT`, `Locks.orderWithSubscription` otherwise); every status change is `UPDATE ... WHERE id = ? AND status = :from`.
 *
 * Not here (later slices): renewal orders and merchant charges (MK-122), cancel, resume, the remote-cancel queue and the endings by refund, chargeback,
 * user deletion and block (MK-123).
 */
class SubscriptionService(
    private val clock: Clock,
    private val config: () -> MarketConfig,
    private val locks: Locks,
    private val subscriptions: MarketSubscriptionDao,
    private val renewals: MarketSubscriptionRenewalDao,
    private val orders: MarketOrderDao,
    private val orderItems: MarketOrderItemDao,
    private val orderEvents: MarketOrderEventDao,
    private val payments: MarketPaymentDao,
    private val products: MarketProductDao,
    private val variants: MarketProductVariantDao,
    private val entitlements: MarketEntitlementDao,
    private val cipher: SecretCipher,
    /** The capabilities a provider answers right now, `null` for `credits`, `free` and a provider that is not registered ([PaymentService.capabilitiesOf]). */
    private val capabilities: suspend (providerId: String, sqlClient: SqlClient) -> PaymentCapabilities?,
    /** The delivery engine for the `EXPIRE` rows of an ending (09 section 10.5 step 4); `null` plans none (a service that cannot end a subscription's actions). */
    private val deliveries: DeliveryService? = null,
    /** The mail outbox of the service mails (09 section 12.2); `null` queues none. */
    private val mail: MailOutboxService? = null,
    private val webhooks: SubscriptionWebhooks = SubscriptionWebhooks.NONE,
    /** Random ids of a renewal order (public id, access token) and of the attempt of a charge (reference, token). */
    private val ids: Ids = SecureIds(),
    /** Whether the buyer matches `market_block` (09 section 8.3): a blocked buyer is not charged again. */
    private val blocks: BuyerBlocks = BuyerBlocks.NONE,
    /**
     * The order service, for the cancel (O7) of a renewal order whose subscription ended (09 section 10.5 step 2). A provider, because the order service
     * is built with this service as its subscription seam; `null` (the default) leaves such an order to expire.
     */
    private val orderService: () -> OrderService? = { null }
) : OfferedPendingSubscriptions, PaymentSubscriptions {
    private fun table(name: String) = "`${subscriptions.prefix()}$name`"

    // ================================================================================================ O1: the pending row (09 section 4.3)

    override suspend fun createPending(conn: SqlConnection, order: MarketOrder, item: com.panomc.plugins.market.db.model.MarketOrderItem, recurring: String?): Long {
        // a subscription needs an account (09 section 1); the quote and the checkout refuse a guest before this point
        val userId = order.userId ?: throw NotLoggedIn()
        val productId = checkNotNull(item.productId) { "a subscription line without a product" }
        val product = products.getById(productId, conn) ?: error("product $productId of a subscription line does not exist")
        val variantId = item.variantId?.takeIf { it != 0L }
        val variant = variantId?.let { variants.getById(it, conn) }
        val unit = intervalUnitOf(product.periodUnit) ?: error("product $productId has no subscription interval")
        val count = variant?.periodCount ?: product.periodCount ?: 1
        val plan = RecurringPlan(order.currency, IntervalUnit.valueOf(unit.name), count, product.subscriptionMaxCycles)
        val mode = provisionalMode(order.paymentMethodId, recurring, plan, conn)
        val now = clock.now()
        val row = MarketSubscription(
            userId = userId, playerUsername = order.playerUsername, ownerKey = order.buyerKey, email = order.email, productId = productId, variantId = variantId ?: 0,
            productName = item.productName, initialOrderId = order.id, initialOrderItemId = item.id, providerId = order.paymentMethodId, mode = mode,
            status = SubscriptionStatus.PENDING, intervalUnit = unit, intervalCount = count, price = order.totalPrice, currency = order.currency,
            maxCycles = product.subscriptionMaxCycles, targetServerId = item.targetServerId, fieldValues = item.fieldValues, testMode = order.testMode,
            createdAt = now, updatedAt = now
        )

        return subscriptions.add(row, conn) ?: error("the pending subscription of order ${order.id} hit uq_provider_sub without a gateway id")
    }

    /**
     * The mode of a pending row (09 section 4.3): the checkout's verdict [recurring] (`"AUTO"` keeps the provider's recurring kind, `"MANUAL"` is manual),
     * or, when the caller has none, the offer table of the capabilities alone (a refusal is 400 `PAYMENT_METHOD_UNAVAILABLE`). `credits` and a provider
     * without capabilities are always manual.
     */
    private suspend fun provisionalMode(providerId: String, recurring: String?, plan: RecurringPlan, sql: SqlClient): SubscriptionMode {
        // 09 section 4.2: `bank-transfer`, `credits` and `free` are always MANUAL (no offer table, no fallback switch)
        if (providerId in ALWAYS_MANUAL) return SubscriptionMode.MANUAL

        val caps = capabilities(providerId, sql) ?: return SubscriptionMode.MANUAL

        return when (recurring) {
            "MANUAL" -> SubscriptionMode.MANUAL
            "AUTO" -> recurringKind(caps)
            else -> when (val offer = ModeResolver.offer(caps, plan, config().subscriptionManualFallback)) {
                is ModeOffer.Auto -> offer.mode
                ModeOffer.Manual -> SubscriptionMode.MANUAL
                is ModeOffer.Unavailable -> throw PaymentMethodUnavailable(offer.reason)
            }
        }
    }

    private fun recurringKind(caps: PaymentCapabilities): SubscriptionMode = when (caps.recurring) {
        RecurringSupport.GATEWAY_MANAGED -> SubscriptionMode.GATEWAY
        RecurringSupport.MERCHANT_INITIATED -> SubscriptionMode.MERCHANT
        RecurringSupport.NONE -> SubscriptionMode.MANUAL
    }

    /**
     * 09 section 4.3: `/pay` re-tendered the initial order while the row is `PENDING` (the buyer picked another method, or retried the same one after the
     * fee changed). Rewrites `providerId`, `mode` and `price` (the re-priced `totalPrice` of [order]); gateway data recorded for the old method goes with
     * it. A row that is not `PENDING` is left alone.
     */
    override suspend fun onMethodChanged(conn: SqlConnection, order: MarketOrder, providerId: String, recurring: String?): Boolean {
        val id = order.subscriptionId ?: return false
        val row = subscriptions.getById(id, conn) ?: return false

        if (row.status != SubscriptionStatus.PENDING) return false

        val plan = RecurringPlan(row.currency, IntervalUnit.valueOf(row.intervalUnit.name), row.intervalCount, row.maxCycles)
        val mode = provisionalMode(providerId, recurring, plan, conn)

        return update(
            conn, id,
            linkedMapOf(
                "providerId" to providerId, "mode" to mode.name, "price" to order.totalPrice, "currency" to order.currency,
                "gatewaySubscriptionId" to null, "gatewayCustomerId" to null, "providerData" to null, "storedMethod" to null, "storedMethodLabel" to null,
                "currentPeriodStart" to null, "currentPeriodEnd" to null
            ),
            whereStatus = SubscriptionStatus.PENDING
        )
    }

    // ================================================================================================ the start request

    override suspend fun pendingPlan(order: MarketOrder, sqlClient: SqlClient): PendingPlan? {
        val id = order.subscriptionId ?: return null

        if (order.source == OrderSource.RENEWAL) return null

        val row = subscriptions.getById(id, sqlClient) ?: return null

        return pendingPlanOf(row)
    }

    /**
     * A renewal order that a buyer pays by hand starts with a plan only when the chosen provider is `MERCHANT_INITIATED` (09 section 8.4, last paragraph), so the
     * provider returns the instrument that replaces a card; a `GATEWAY_MANAGED` provider is used as a plain one-off payment.
     */
    private suspend fun renewalPlanFor(order: MarketOrder, id: Long, sqlClient: SqlClient): SubscriptionPlan? {
        val row = subscriptions.getById(id, sqlClient) ?: return null

        if (row.status.isTerminal || row.mode == SubscriptionMode.GATEWAY) return null

        val caps = capabilities(order.paymentMethodId, sqlClient) ?: return null

        if (caps.recurring != RecurringSupport.MERCHANT_INITIATED) return null

        val plan = RecurringPlan(row.currency, IntervalUnit.valueOf(row.intervalUnit.name), row.intervalCount, row.maxCycles)

        return PendingPlan(row.id, row.productId, row.variantId, row.productName, plan).at(order.totalPrice)
    }

    private fun pendingPlanOf(row: MarketSubscription): PendingPlan? {
        if (row.status != SubscriptionStatus.PENDING) return null

        return PendingPlan(
            row.id, row.productId, row.variantId, row.productName, RecurringPlan(row.currency, IntervalUnit.valueOf(row.intervalUnit.name), row.intervalCount, row.maxCycles)
        )
    }

    /** `StartPaymentRequest.subscription` (09 section 4.2): the plan of the pending row, only for an automatic mode and the provider the row was made for. */
    override suspend fun planFor(order: MarketOrder, sqlClient: SqlClient): SubscriptionPlan? {
        val id = order.subscriptionId ?: return null

        if (order.source == OrderSource.RENEWAL) return renewalPlanFor(order, id, sqlClient)

        val row = subscriptions.getById(id, sqlClient) ?: return null

        if (row.mode == SubscriptionMode.MANUAL || row.providerId != order.paymentMethodId) return null

        return pendingPlanOf(row)?.at(row.price)
    }

    // ================================================================================================ the payment: record, then activate

    /**
     * What a success says about the subscription (09 section 4.4). Only the attempt that is the order's payment writes the `PENDING` row: the first
     * success on the order (its `paymentId` is still empty, or it is this attempt). The success of any other attempt (a duplicate on an order that is paid
     * already, a second paying attempt of an order in review, a late success after the row was closed) leaves the row alone, and the gateway subscription it
     * carries is cancelled after the commit ([CancelSurplusSubscription]); otherwise the buyer would be billed for it every period and its renewals would be
     * skipped as an unknown subscription.
     */
    override suspend fun onPaid(conn: SqlConnection, order: MarketOrder, attempt: MarketPayment, facts: AttemptFacts): AfterCommit? {
        val id = order.subscriptionId ?: return null

        // a renewal order has no pending row to write: what the payment delivered (the instrument of a hand payment) waits for the transition of the order
        if (order.source == OrderSource.RENEWAL) {
            recordRenewalPayment(order, attempt, facts)

            return null
        }

        val row = subscriptions.getById(id, conn) ?: return null
        val gateway = facts.subscription
        val ownPayment = order.paymentId == null || order.paymentId == attempt.id
        val holdsAnother = gateway != null && row.gatewaySubscriptionId != null && row.gatewaySubscriptionId != gateway.gatewaySubscriptionId

        // an `ACTIVE` row is never rewritten by the success of another attempt, a terminal row has nothing to activate
        if (row.status != SubscriptionStatus.PENDING || !ownPayment || holdsAnother) return gateway?.let { surplus(row, attempt, it) }

        val stored = facts.storedMethod
        val sets = linkedMapOf<String, Any?>("providerId" to attempt.providerId)

        if (gateway != null) {
            sets["gatewaySubscriptionId"] = gateway.gatewaySubscriptionId
            gateway.gatewayCustomerId?.let { sets["gatewayCustomerId"] = it }
            gateway.providerData?.let { sets["providerData"] = cipher.encrypt(it.encode()) }
            // the gateway's own period is applied at activation (09 section 4.4 step 4); a pending row shows no period anywhere
            gateway.currentPeriodStart?.let { sets["currentPeriodStart"] = it }
            gateway.currentPeriodEnd?.let { sets["currentPeriodEnd"] = it }
        }

        if (stored != null) {
            val json = JsonObject().put("token", stored.token).put("label", stored.label).put("expiresAt", stored.expiresAt).put("gatewayCustomerId", stored.gatewayCustomerId)

            sets["storedMethod"] = cipher.encrypt(json.encode())
            sets["storedMethodLabel"] = clip(stored.label ?: facts.methodDetail, LABEL_MAX)
            stored.gatewayCustomerId?.let { sets["gatewayCustomerId"] = it }
        }

        try {
            update(conn, id, sets, whereStatus = SubscriptionStatus.PENDING)
        } catch (e: Exception) {
            // uq_provider_sub: the gateway subscription already belongs to another row. The money still counts; the subscription renews manually
            if (!e.isDuplicateKey() || gateway == null) throw e

            logger.warn("subscription {}: the gateway subscription {} of provider {} belongs to another row, activation is manual", id, gateway.gatewaySubscriptionId, attempt.providerId)

            sets.keys.removeAll(setOf("gatewaySubscriptionId", "gatewayCustomerId", "providerData", "currentPeriodStart", "currentPeriodEnd"))
            update(conn, id, sets, whereStatus = SubscriptionStatus.PENDING)
        }

        return null
    }

    /** The cancel of a gateway subscription [row] does not keep; `null` when [gateway] is the very subscription the row holds. */
    private fun surplus(row: MarketSubscription, attempt: MarketPayment, gateway: GatewaySubscriptionState): AfterCommit? {
        if (gateway.gatewaySubscriptionId == row.gatewaySubscriptionId && attempt.providerId == row.providerId) return null

        logger.warn(
            "subscription {}: attempt {} on provider {} carried the gateway subscription {}, which the row does not keep (status {}); it is cancelled after the commit",
            row.id, attempt.id, attempt.providerId, gateway.gatewaySubscriptionId, row.status
        )

        val view = SubscriptionView(
            id = row.id, status = gateway.status.name, gatewaySubscriptionId = gateway.gatewaySubscriptionId, gatewayCustomerId = gateway.gatewayCustomerId,
            price = Money(row.price, row.currency), intervalUnit = IntervalUnit.valueOf(row.intervalUnit.name), intervalCount = row.intervalCount,
            currentPeriodEnd = gateway.currentPeriodEnd, providerData = gateway.providerData, testMode = attempt.testMode
        )

        return CancelSurplusSubscription(row.initialOrderId, row.id, attempt.providerId, view)
    }

    /**
     * `SubscriptionOnOrderPaid` of O2 / O4 (09 section 4.4), inside the order transition after the entitlements and the `GRANT` rows exist. Nothing for an
     * order without a subscription. A row that is `ACTIVE` already is a replay (no-op); a closed one refuses the transition (400 `INVALID_ORDER_TRANSITION`
     * `{reason: SUBSCRIPTION_CLOSED}`: the admin rejects and refunds).
     */
    suspend fun onOrderPaid(conn: SqlConnection, locked: LockedOrder) {
        val orderId = locked.order.id
        val order = orders.getById(orderId, conn) ?: throw OrderChangedException(orderId, "the row is gone")
        val subscriptionId = order.subscriptionId ?: return

        // a paid renewal (09 section 8.4): the next period starts, the renewal order is the one that was paid
        if (order.source == OrderSource.RENEWAL) return onRenewalPaid(conn, order)

        val row = subscriptions.getById(subscriptionId, conn) ?: error("order $orderId names subscription $subscriptionId, which does not exist")

        when (val decision = decide(row, SubEvent.Activated)) {
            is SubTransition.Ignored ->
                if (decision.reason == SubscriptionStateMachine.SUBSCRIPTION_CLOSED) throw InvalidOrderTransition(reason = SubscriptionStateMachine.SUBSCRIPTION_CLOSED)

            is SubTransition.Apply -> apply(conn, row, decision, Context(order, OrderActorType.SYSTEM))

            SubTransition.PollFirst -> error("an activation never polls")
        }
    }

    /**
     * `SubscriptionOnClosedUnpaid` of O5 to O8: only the rejection of a review (O5, the order was `REVIEW`) closes the pending row (S3,
     * `endReason = ADMIN_CANCEL`, a remote cancel is queued when a gateway subscription is known). An expired, cancelled or failed order leaves it
     * `PENDING`: a late payment may still arrive (00 O9), step F of `SubscriptionJob` closes it 30 days later (09 section 4.3).
     */
    suspend fun onOrderClosedUnpaid(conn: SqlConnection, locked: LockedOrder) {
        val subscriptionId = locked.order.subscriptionId ?: return

        // `locked` is the order as it was before the transition: a review that is rejected was in REVIEW
        if (locked.order.status != com.panomc.plugins.market.util.OrderStatus.REVIEW) return

        val row = subscriptions.getById(subscriptionId, conn) ?: return

        when (val decision = decide(row, SubEvent.InitialOrderRejected)) {
            is SubTransition.Apply -> apply(conn, row, decision, Context(locked.order, OrderActorType.ADMIN))
            else -> Unit
        }
    }

    // ================================================================================================ 09 section 8.5: a payment for a closed subscription

    /** [ReviewReason.LATE] when [order] is a renewal and its subscription is terminal (09 section 8.5), else `null`. */
    suspend fun reviewReasonFor(conn: SqlClient, order: MarketOrder): ReviewReason? {
        val id = order.subscriptionId ?: return null

        if (order.source != OrderSource.RENEWAL) return null

        val row = subscriptions.getById(id, conn) ?: return null

        return if (row.status.isTerminal) ReviewReason.LATE else null
    }

    /** The gateway keeps billing a closed subscription: the remote cancel goes (back) in the queue (09 section 8.5). Nothing for a row that is not `GATEWAY`. */
    internal suspend fun requeueRemoteCancel(conn: SqlClient, subscriptionId: Long) {
        conn.preparedQuery(
            "UPDATE ${table("market_subscription")} SET `remoteCancelState` = 'PENDING', `remoteCancelAttempts` = 0, `nextQueryAt` = ?, `updatedAt` = ? " +
                "WHERE `id` = ? AND `mode` = 'GATEWAY' AND `remoteCancelState` <> 'PENDING'"
        ).execute(Tuple.of(clock.now(), clock.now(), subscriptionId)).coAwait()
    }

    // ================================================================================================ 09 section 7: gateway status events

    /** What [onGatewayEvent] did, for the log and the tests. */
    sealed class GatewayOutcome {
        /** The target is not a subscription of this provider (09 section 7: the event is skipped). */
        data object UnknownSubscription : GatewayOutcome()

        /** The machine ignored the event ([reason] is one of its stable codes). */
        data class Ignored(val reason: String) : GatewayOutcome()

        data class Applied(val to: SubscriptionStatus, val rules: List<String>) : GatewayOutcome()
    }

    /**
     * `SubscriptionUpdated` of provider [providerId] on the caller's transaction (09 section 7): the row is found by `uq_provider_sub`, the credit
     * account, the subscription and the order are locked in the order of 00 section 8.3, `gatewayCustomerId` and `providerData` are stored when given
     * (always, whatever the status), then the gateway status runs through the state machine. The period columns never change here.
     */
    suspend fun onGatewayEvent(conn: SqlConnection, providerId: String, event: PaymentEvent.SubscriptionUpdated): GatewayOutcome {
        val state = event.state
        val known = subscriptions.getByGatewaySubscription(providerId, state.gatewaySubscriptionId, conn) ?: return GatewayOutcome.UnknownSubscription.also {
            logger.warn("provider {} sent a status for the subscription {}, which is not one of its subscriptions, skipped", providerId, state.gatewaySubscriptionId)
        }

        return locks.orderWithSubscription(conn, known.initialOrderId) { locked ->
            val row = subscriptions.getById(known.id, conn) ?: return@orderWithSubscription GatewayOutcome.UnknownSubscription
            val sets = linkedMapOf<String, Any?>()

            state.gatewayCustomerId?.let { sets["gatewayCustomerId"] = it }
            state.providerData?.let { sets["providerData"] = cipher.encrypt(it.encode()) }

            if (sets.isNotEmpty()) update(conn, row.id, sets)

            val now = clock.now()
            val decision = SubscriptionStateMachine.decide(SubState.of(row), SubEvent.GatewayStatus(state.status, state.endsAt), now, subConfig())

            when (decision) {
                is SubTransition.Ignored -> {
                    // a closed row that the gateway still bills: the one transition that is not an ignore is decided by the machine above
                    logger.debug("subscription {}: gateway status {} ignored ({})", row.id, state.status, decision.reason)

                    GatewayOutcome.Ignored(decision.reason)
                }

                is SubTransition.Apply -> {
                    apply(conn, row, decision, Context(locked.order, OrderActorType.GATEWAY))

                    GatewayOutcome.Applied(decision.to, decision.rules.map { it.name })
                }

                SubTransition.PollFirst -> GatewayOutcome.Ignored("POLL_FIRST")
            }
        }
    }

    // ================================================================================================ applying a decision

    /**
     * What an applied decision runs against: [order] is the initial order (its timeline carries the subscription events), [renewalOrder] / [renewal] the renewal
     * of the period when the decision is about one (a paid renewal, a failed charge), [failure] what the failed charge reported.
     */
    private class Context(
        val order: MarketOrder,
        val actor: OrderActorType,
        val renewalOrder: MarketOrder? = null,
        val renewal: MarketSubscriptionRenewal? = null,
        val failure: Failure? = null,
        /** The instrument a hand payment of the renewal order delivered (09 section 8.4 step 4). */
        val storedMethod: StoredPaymentMethod? = null
    )

    /** A recorded failure (09 section 9.1): [gateway] = the gateway reported it (`SubscriptionPaymentFailed`, status `PAST_DUE`), else market's own charge. */
    private class Failure(val code: String?, val final: Boolean, val technical: Boolean, val gateway: Boolean, val attemptCount: Int?, val attempts: Int)

    private fun subConfig() = SubConfig(config().subscriptionGraceDays)

    private fun decide(row: MarketSubscription, event: SubEvent): SubTransition = SubscriptionStateMachine.decide(SubState.of(row), event, clock.now(), subConfig())

    /** The steps of [transition] in order: the conditional status update, then the effects of the step (09 section 6). Zero rows means the lock protocol was broken. */
    private suspend fun apply(conn: SqlConnection, row: MarketSubscription, transition: SubTransition.Apply, context: Context) {
        val now = clock.now()

        for (step in transition.steps) {
            val from = step.from ?: error("a step without a source state is the pending row's insert, not an update")

            if (step.to != from && !subscriptions.transition(row.id, from, step.to, now, conn)) {
                throw OrderChangedException(context.order.id, "subscription ${row.id} left $from under its lock")
            }

            for (effect in step.effects) effect(conn, row.id, effect, context)
        }
    }

    private suspend fun effect(conn: SqlConnection, id: Long, effect: SubEffect, context: Context) {
        val now = clock.now()

        when (effect) {
            is SubEffect.SetGraceEndsAt -> update(conn, id, linkedMapOf("graceEndsAt" to effect.at))

            is SubEffect.SetCancelAtPeriodEnd -> update(conn, id, linkedMapOf("cancelAtPeriodEnd" to effect.flag))

            is SubEffect.SetCancelRequestedAt -> update(conn, id, linkedMapOf("cancelRequestedAt" to effect.at))

            is SubEffect.SetEndReason -> update(conn, id, linkedMapOf("endReason" to effect.reason?.name))

            is SubEffect.SetNextChargeAt -> update(conn, id, linkedMapOf("nextChargeAt" to effect.at))

            SubEffect.RecordFailure -> conn.preparedQuery("UPDATE ${table("market_subscription")} SET `failCount` = `failCount` + 1, `lastFailureAt` = ?, `updatedAt` = ? WHERE `id` = ?")
                .execute(Tuple.of(now, now, id)).coAwait().let { }

            SubEffect.RecordRenewalError -> recordRenewalError(conn, id, now, context)

            SubEffect.MarkRemoteCancelDone -> update(conn, id, linkedMapOf("remoteCancelState" to "DONE", "nextQueryAt" to null))

            SubEffect.QueueRemoteCancel -> update(conn, id, linkedMapOf("remoteCancelState" to "PENDING", "remoteCancelAttempts" to 0, "nextQueryAt" to now))

            SubEffect.ActivateFromPayment -> activate(conn, id, context, now)

            is SubEffect.ClosePending -> close(conn, id, effect, now)

            is SubEffect.EndSubscription -> end(conn, id, effect, context, now)

            is SubEffect.QueueMail -> queueMail(conn, id, effect, context)

            is SubEffect.QueueWebhook -> queueWebhook(conn, id, effect.event, context)

            is SubEffect.AddOrderEvent -> addOrderEvent(conn, id, effect.type, context, data = eventData(effect.type, context), onOrder = eventOrder(effect.type, context))

            is SubEffect.ApplyRenewal -> applyRenewal(conn, id, effect, context, now)

            SubEffect.QueueRenewDeliveries -> linkRenewDeliveries(conn, id, context, now)

            SubEffect.SkipPendingRenewal, SubEffect.RestoreSkippedRenewal -> throw SubscriptionEffectNotOwned(effect, "the cancel slice (MK-123)")
        }
    }

    // ----- S2, 09 section 4.4 steps 1 to 6

    private suspend fun activate(conn: SqlConnection, id: Long, context: Context, now: Long) {
        val row = subscriptions.getById(id, conn) ?: error("subscription $id vanished inside its transaction")
        val order = orders.getById(row.initialOrderId, conn) ?: error("the initial order of subscription $id does not exist")
        val payment = order.paymentId?.let { payments.getById(it, conn) }
        val providerId = payment?.providerId ?: row.providerId
        val caps = capabilities(providerId, conn)
        val hasGateway = row.gatewaySubscriptionId != null
        val hasStored = row.storedMethod != null

        // 1. the mode is what the paying attempt delivered, not what the offer promised
        val mode = if (caps != null) ModeResolver.atActivation(caps, hasGateway, hasStored) else if (hasGateway) SubscriptionMode.GATEWAY else SubscriptionMode.MANUAL

        // 4. the first period: the gateway's own when it is sound, else one interval from the anchor (the initial order's `paidAt`, never stored twice)
        val anchor = order.paidAt ?: now
        val calculator = PeriodCalculator.forSubscription(PeriodCalculator.zoneOf(config().storeTimeZone), row.intervalUnit, row.intervalCount)
        val start = row.currentPeriodStart ?: anchor
        var end = row.currentPeriodEnd ?: calculator.boundary(anchor, 1)

        if (end <= start) end = calculator.boundary(anchor, 1)
        if (end <= start) end = calculator.nextBoundary(anchor, start)

        // 5. the next charge (merchant) or the next poll (gateway)
        val finite = row.maxCycles != null && 1 >= row.maxCycles
        val nextCharge = if (mode == SubscriptionMode.MERCHANT && !finite) end else null
        val nextQuery = if (mode == SubscriptionMode.GATEWAY) end + SubscriptionTimings.HOUR_MS else null

        // 6. the entitlement the order created is the subscription's: no expiry, ended only by this service
        val entitlement = entitlements.getByOrderItemId(row.initialOrderItemId, conn).firstOrNull()

        if (entitlement != null) {
            conn.preparedQuery(
                "UPDATE ${table("market_entitlement")} SET `subscriptionId` = ?, `expiresAt` = NULL, `updatedAt` = ? WHERE `id` = ? AND (`subscriptionId` IS NULL OR `expiresAt` IS NOT NULL)"
            ).execute(Tuple.of(id, now, entitlement.id)).coAwait()
        }

        val sets = linkedMapOf<String, Any?>(
            "mode" to mode.name, "providerId" to providerId, "price" to order.totalPrice, "currency" to order.currency, "cycleCount" to 1,
            "currentPeriodStart" to start, "currentPeriodEnd" to end, "nextChargeAt" to nextCharge, "nextQueryAt" to nextQuery, "entitlementId" to entitlement?.id,
            "testMode" to order.testMode
        )

        // the stored method belongs to a merchant-initiated row only; a gateway-managed one is billed by the gateway
        if (mode != SubscriptionMode.MERCHANT) {
            sets["storedMethod"] = null

            if (mode == SubscriptionMode.MANUAL) sets["storedMethodLabel"] = null
        }

        if (mode == SubscriptionMode.MERCHANT && row.storedMethodLabel == null) sets["storedMethodLabel"] = clip(payment?.methodDetail, LABEL_MAX)

        update(conn, id, sets)

        if (ModeResolver.isDowngrade(row.mode, mode)) {
            orderEvents.add(
                MarketOrderEvent(
                    orderId = order.id, type = OrderEventType.NOTE, actorType = OrderActorType.SYSTEM, message = DOWNGRADE_NOTE,
                    data = JsonObject().put("subscriptionId", id).put("provisionalMode", row.mode.name).put("mode", mode.name).encode(), createdAt = now, updatedAt = now
                ),
                conn
            )
        }
    }

    // ----- S3

    private suspend fun close(conn: SqlConnection, id: Long, effect: SubEffect.ClosePending, now: Long) {
        val sets = linkedMapOf<String, Any?>("endedAt" to now, "endReason" to effect.reason.name, "nextChargeAt" to null, "graceEndsAt" to null, "storedMethod" to null)

        if (effect.clearPii) sets.putAll(piiCleared())

        update(conn, id, sets)
    }

    private fun piiCleared(): Map<String, Any?> = linkedMapOf("userId" to null, "email" to null, "storedMethod" to null)

    // ----- 09 section 10.5: the shared ending

    private suspend fun end(conn: SqlConnection, id: Long, effect: SubEffect.EndSubscription, context: Context, now: Long) {
        val row = subscriptions.getById(id, conn) ?: error("subscription $id vanished inside its transaction")

        // 1. the row
        val sets = linkedMapOf<String, Any?>(
            "endedAt" to now, "endReason" to effect.reason.name, "nextChargeAt" to null, "graceEndsAt" to null, "storedMethod" to null, "nextQueryAt" to null
        )

        if (effect.status == SubscriptionStatus.CANCELLED) sets["cancelledAt"] = now
        if (effect.clearPii) sets.putAll(piiCleared())

        update(conn, id, sets)

        // 2. the open renewal row; its order is the renewal slice's (an order that is paid later meets the `LATE` guard)
        renewals.getByPeriod(id, row.cycleCount.coerceAtLeast(1), conn)?.takeIf { it.status == RenewalStatus.PENDING }?.let { renewal ->
            renewals.transition(renewal.id, RenewalStatus.PENDING, if (effect.renewalRow == com.panomc.plugins.market.core.subscription.RenewalDisposition.FAILED) RenewalStatus.FAILED else RenewalStatus.SKIPPED, now, conn)

            // its order is cancelled right after the commit ([cancelClosedRenewalOrders]); a payment that comes first is diverted to review (09 section 8.5)
            if (renewal.orderId != null) logger.debug("subscription {}: the open renewal order {} is cancelled after the commit", id, renewal.orderId)
        }

        // 3 and 4. the entitlement and the EXPIRE rows, unless the refund flow wrote REVOKE
        if (!effect.undoHandledByCaller) {
            row.entitlementId?.let { entitlementId ->
                conn.preparedQuery(
                    "UPDATE ${table("market_entitlement")} SET `status` = 'EXPIRED', `endReason` = 'SUBSCRIPTION_ENDED', `endedAt` = ?, `updatedAt` = ? WHERE `id` = ? AND `status` = 'ACTIVE'"
                ).execute(Tuple.of(now, now, entitlementId)).coAwait()
            }

            planExpire(conn, row)
        }

        // 5. mail, timeline, webhook
        val ended = subscriptions.getById(id, conn)!!

        if (effect.sendEndedMail) queueMail(conn, id, SubEffect.QueueMail(MailKind.SUBSCRIPTION_ENDED.name), context)

        addOrderEvent(conn, id, OrderEventType.SUBSCRIPTION_ENDED.name, context, JsonObject().put("endReason", ended.endReason))
        queueWebhook(conn, id, WEBHOOK_EXPIRED, context)
    }

    /** `EXPIRE` rows of the initial order's line, exactly once per subscription (the delivery key makes a repeat a no-op). */
    private suspend fun planExpire(conn: SqlConnection, row: MarketSubscription) {
        val engine = deliveries ?: return logger.warn("subscription {} ended without a delivery engine: no EXPIRE rows were planned", row.id)
        val order = orders.getById(row.initialOrderId, conn) ?: return
        val item = orderItems.getById(row.initialOrderItemId, conn) ?: return

        engine.planEnd(conn, order, listOf(item), DeliveryError.ENTITLEMENT_ENDED, emptyMap(), mapOf(item.id to (0 until item.quantity)))
        engine.refreshFulfillment(conn, order.id)
    }

    // ----- S4: the gateway declined a renewal (09 section 9.1, GATEWAY column)

    /**
     * `RecordRenewalError` (09 section 9.1): market's own charge writes the failure code on the renewal row (`attempts` was counted when the charge was
     * prepared); a gateway decline creates the row of the period when it is missing and counts the attempt (the gateway's own `attemptCount` when it sent one).
     */
    private suspend fun recordRenewalError(conn: SqlConnection, id: Long, now: Long, context: Context) {
        val failure = context.failure

        if (failure == null || failure.gateway) return recordGatewayDecline(conn, id, now, failure?.attemptCount)

        val row = subscriptions.getById(id, conn) ?: return
        val renewal = context.renewal ?: renewals.getByPeriod(id, row.cycleCount.coerceAtLeast(1), conn) ?: return

        conn.preparedQuery("UPDATE ${table("market_subscription_renewal")} SET `lastError` = ?, `updatedAt` = ? WHERE `id` = ?")
            .execute(Tuple.of((failure.code ?: CHARGE_FAILED).take(ERROR_MAX), now, renewal.id)).coAwait()
    }

    private suspend fun recordGatewayDecline(conn: SqlConnection, id: Long, now: Long, attemptCount: Int? = null) {
        val row = subscriptions.getById(id, conn) ?: return
        val existing = renewals.getByPeriod(id, row.cycleCount.coerceAtLeast(1), conn)
        val renewalId = existing?.id ?: run {
            val order = orders.getById(row.initialOrderId, conn)
            val calculator = PeriodCalculator.forSubscription(PeriodCalculator.zoneOf(config().storeTimeZone), row.intervalUnit, row.intervalCount)
            val anchor = order?.paidAt ?: row.currentPeriodStart ?: now
            val period = calculator.renewalPeriod(anchor, row.currentPeriodEnd ?: now, now)

            renewals.add(
                MarketSubscriptionRenewal(
                    subscriptionId = id, periodIndex = row.cycleCount.coerceAtLeast(1), periodStart = period.start, periodEnd = period.end, status = RenewalStatus.PENDING,
                    amount = row.price, currency = row.currency, createdAt = now, updatedAt = now
                ),
                conn
            ) ?: renewals.getByPeriod(id, row.cycleCount.coerceAtLeast(1), conn)!!.id
        }

        if (attemptCount != null) {
            conn.preparedQuery("UPDATE ${table("market_subscription_renewal")} SET `attempts` = GREATEST(`attempts`, ?), `lastError` = ?, `updatedAt` = ? WHERE `id` = ?")
                .execute(Tuple.of(attemptCount, GATEWAY_DECLINED, now, renewalId)).coAwait()
        } else {
            renewals.recordAttempt(renewalId, null, GATEWAY_DECLINED, now, conn)
        }
    }

    // ----- mail, order timeline, webhooks

    private suspend fun queueMail(conn: SqlConnection, id: Long, effect: SubEffect.QueueMail, context: Context) {
        val outbox = mail ?: return
        val row = subscriptions.getById(id, conn) ?: return
        val recipient = row.email ?: return
        val kind = MailKind.valueOf(effect.kind)
        val order = orders.getById(row.initialOrderId, conn)
        val key = when (kind) {
            MailKind.SUBSCRIPTION_PAYMENT_FAILED -> row.cycleCount.coerceAtLeast(1).toString()
            MailKind.SUBSCRIPTION_CANCELLED -> (row.cancelRequestedAt ?: clock.now()).toString()
            else -> ""
        }
        val params = JsonObject().put("productName", row.productName).put("price", EventPayloads.money(row.price)).put("currency", row.currency)

        when (kind) {
            MailKind.SUBSCRIPTION_PAYMENT_FAILED -> {
                params.put("reason", effect.reason).put("graceEndsAt", row.graceEndsAt).put("methodLabel", row.storedMethodLabel).put("manageUrl", MANAGE_PATH)

                // the pay link of the renewal order, absent when the gateway bills the subscription (09 section 12.2)
                if (row.mode != SubscriptionMode.GATEWAY) {
                    val renewalOrder = context.renewalOrder ?: renewals.getByPeriod(id, row.cycleCount.coerceAtLeast(1), conn)?.orderId?.let { orders.getById(it, conn) }

                    renewalOrder?.publicId?.let { params.put("payUrl", "/store/order/$it") }
                }
            }
            MailKind.SUBSCRIPTION_CANCELLED -> params.put("endsAt", row.currentPeriodEnd)
            MailKind.SUBSCRIPTION_ENDED -> params.put("endReason", row.endReason).put("endedAt", row.endedAt)
            else -> Unit
        }

        outbox.enqueue(
            conn, kind, MailRefType.SUBSCRIPTION, id, refKey = key, orderId = row.initialOrderId, userId = row.userId, recipient = recipient,
            locale = order?.locale ?: DEFAULT_LOCALE, params = params
        )
    }

    private suspend fun addOrderEvent(conn: SqlConnection, id: Long, type: String, context: Context, data: JsonObject = JsonObject(), onOrder: Long? = null) {
        val row = subscriptions.getById(id, conn) ?: return
        val now = clock.now()

        orderEvents.add(
            MarketOrderEvent(
                orderId = onOrder ?: row.initialOrderId, type = OrderEventType.valueOf(type), actorType = context.actor,
                data = data.put("subscriptionId", id).put("status", row.status.name).encode(), createdAt = now, updatedAt = now
            ),
            conn
        )
    }

    /** The timeline row a paid renewal writes goes on the renewal order (09 section 12.3); every other one on the initial order. */
    private fun eventOrder(type: String, context: Context): Long? = if (type == SubscriptionStateMachine.ORDER_EVENT_RENEWED) context.renewalOrder?.id else null

    /** `SUBSCRIPTION_CHARGE_FAILED` carries `{code, final, attempts}` (09 section 12.3). */
    private fun eventData(type: String, context: Context): JsonObject {
        val failure = context.failure

        return if (type == SubscriptionStateMachine.ORDER_EVENT_CHARGE_FAILED && failure != null) {
            JsonObject().put("code", failure.code).put("final", failure.final).put("attempts", failure.attempts)
        } else {
            JsonObject()
        }
    }

    private suspend fun queueWebhook(conn: SqlConnection, id: Long, event: String, context: Context) {
        val row = subscriptions.getById(id, conn) ?: return
        val renewed = context.renewalOrder
        val subject = when (event) {
            WEBHOOK_STARTED -> "sub:$id:started"
            WEBHOOK_RENEWED -> "sub:$id:renewed:${context.renewal?.periodIndex ?: row.cycleCount}"
            WEBHOOK_CANCELLED -> "sub:$id:cancelled:${row.cancelRequestedAt ?: row.endedAt ?: clock.now()}"
            WEBHOOK_EXPIRED -> "sub:$id:ended"
            else -> error("$event is not a subscription event of this slice")
        }
        val data = JsonObject().put("subscription", subscriptionJson(row))

        if (event == WEBHOOK_STARTED || event == WEBHOOK_RENEWED) {
            val order = if (event == WEBHOOK_RENEWED) renewed else orders.getById(row.initialOrderId, conn)

            if (order != null) data.put("order", JsonObject().put("id", order.id).put("publicId", order.publicId).put("total", EventPayloads.money(order.totalPrice)).put("currency", order.currency))
        }

        webhooks.emit(conn, event, subject, if (event == WEBHOOK_RENEWED) renewed?.id ?: row.initialOrderId else row.initialOrderId, data, row.testMode)
    }

    private fun subscriptionJson(s: MarketSubscription): JsonObject = JsonObject()
        .put("id", s.id).put("status", s.status.name).put("mode", s.mode.name).put("productId", s.productId).put("variantId", s.variantId)
        .put("productName", s.productName).put("playerUsername", s.playerUsername).put("userId", s.userId).put("price", EventPayloads.money(s.price))
        .put("currency", s.currency).put("intervalUnit", s.intervalUnit.name).put("intervalCount", s.intervalCount).put("cycleCount", s.cycleCount)
        .put("maxCycles", s.maxCycles).put("currentPeriodStart", s.currentPeriodStart).put("currentPeriodEnd", s.currentPeriodEnd)
        .put("cancelAtPeriodEnd", s.cancelAtPeriodEnd).put("endReason", s.endReason).put("endedAt", s.endedAt).put("providerId", s.providerId)
        .put("testMode", s.testMode)

    // ================================================================================================ 09 section 8: renewal orders

    /** What a charge needs to know about the provider right now (09 section 8.3), read by the caller before the transaction. */
    class ProviderFacts(val available: Boolean, val testMode: Boolean, val canQuery: Boolean = false)

    /** What [prepareCharge] decided (tx1 of 09 section 8.3). */
    sealed class ChargePreparation {
        /** Nothing is charged: [reason] says why. [ended] when the preparation ended or cancelled the subscription (its renewal order is then cancelled after the commit). */
        class Skipped(val reason: String, val ended: Boolean = false) : ChargePreparation()

        /** The intent is written (renewal row, order, attempt `CREATED`, lease on `nextChargeAt`): call `chargeRecurring` now, outside any transaction. */
        class Charge(
            val subscription: MarketSubscription, val order: MarketOrder, val attempt: MarketPayment, val stored: StoredPaymentMethod, val idempotencyKey: String
        ) : ChargePreparation()

        /** An earlier charge of this period has not settled: it is never charged again, [attempt] is asked about (`statusQuery`) after the commit. */
        class InFlight(val subscription: MarketSubscription, val order: MarketOrder, val attempt: MarketPayment) : ChargePreparation()
    }

    private class RenewalBundle(val renewal: MarketSubscriptionRenewal, val order: MarketOrder)

    private fun calculatorOf(row: MarketSubscription): PeriodCalculator =
        PeriodCalculator.forSubscription(PeriodCalculator.zoneOf(config().storeTimeZone), row.intervalUnit, row.intervalCount)

    /** The anchor of the period grid: the `paidAt` of the initial order, never stored twice (09 section 4.4 step 4). */
    private suspend fun anchorOf(conn: SqlClient, row: MarketSubscription, now: Long): Long = orders.getById(row.initialOrderId, conn)?.paidAt ?: row.currentPeriodStart ?: now

    /** The renewal row [order] belongs to (its `idempotencyKey` is `renewal:<renewalId>:<n>`, so an order that was replaced on the row is found too). */
    private suspend fun renewalOf(conn: SqlClient, order: MarketOrder): MarketSubscriptionRenewal? {
        val renewalId = order.idempotencyKey?.takeIf { it.startsWith(IDEM_PREFIX) }?.removePrefix(IDEM_PREFIX)?.substringBefore(':')?.toLongOrNull()

        return renewalId?.let { renewals.getById(it, conn) } ?: order.subscriptionId?.let { id -> renewals.getBySubscriptionId(id, conn).firstOrNull { it.orderId == order.id } }
    }

    /**
     * The renewal row of the subscription's current period (`periodIndex = cycleCount`, 09 section 8) and its open renewal order, both created when they are
     * missing: the row by `INSERT` (a clash on `uq_sub_period` reads the existing row, so one period is prepared once), the order by
     * [createRenewalOrder] when the row has none or the one it had was closed unpaid. [expiresAt] is the window of a new order.
     */
    private suspend fun ensureRenewal(conn: SqlConnection, row: MarketSubscription, now: Long, expiresAt: (MarketSubscriptionRenewal) -> Long): RenewalBundle {
        val index = row.cycleCount.coerceAtLeast(1)
        var renewal = renewals.getByPeriod(row.id, index, conn)

        if (renewal == null) {
            val period = calculatorOf(row).renewalPeriod(anchorOf(conn, row, now), row.currentPeriodEnd ?: now, now)

            renewals.add(
                MarketSubscriptionRenewal(
                    subscriptionId = row.id, periodIndex = index, periodStart = period.start, periodEnd = period.end, status = RenewalStatus.PENDING, amount = row.price,
                    currency = row.currency, createdAt = now, updatedAt = now
                ),
                conn
            )
            renewal = renewals.getByPeriod(row.id, index, conn) ?: error("renewal $index of subscription ${row.id} was not written")
        }

        val existing = renewal.orderId?.let { orders.getById(it, conn) }

        if (existing != null && existing.status !in CLOSED_UNPAID) return RenewalBundle(renewal, existing)

        val order = createRenewalOrder(conn, row, renewal, expiresAt(renewal))

        renewals.attach(renewal.id, order.id, null, now, conn)

        return RenewalBundle(renewals.getById(renewal.id, conn) ?: renewal, order)
    }

    /**
     * `OrderService.createRenewalOrder` of 09 section 8.1 (the order service has no subscription knowledge, so it lives here): the order, its one item and the
     * `CREATED` timeline row, copied from the initial order and item. `totalPrice` is the frozen `market_subscription.price`, the fee part of the initial order is
     * kept, only the VAT split is back-calculated at the product's current rate (05 section 12). A renewal reserves nothing and holds no credits
     * (`creditAmount = 0`, `gatewayAmount = totalPrice`, I22): a buyer who pays it with credits does so through `/pay`, which posts a real `HOLD`.
     * `reservationState` is `HELD` so that O2 and O6 to O8 run the generic path; there is no stock, limit, cooldown, block or coupon check.
     */
    private suspend fun createRenewalOrder(conn: SqlConnection, row: MarketSubscription, renewal: MarketSubscriptionRenewal, expiresAt: Long): MarketOrder {
        val initial = orders.getById(row.initialOrderId, conn) ?: error("the initial order of subscription ${row.id} does not exist")
        val item = orderItems.getById(row.initialOrderItemId, conn) ?: error("the initial item of subscription ${row.id} does not exist")
        val product = products.getById(row.productId, conn)
        val c = config()
        val now = clock.now()
        val price = row.price
        val fee = initial.paymentFee.coerceIn(0L, price)
        val feeVat = if (fee == initial.paymentFee) initial.paymentFeeVatAmount else 0L
        val lineTotal = price - fee
        val market = initial.pricingMode == PricingMode.MARKET
        val bp = if (market) (product?.vatPercent ?: Math.round(c.vatPercent * 100)).coerceIn(0L, 10_000L) else 0L
        val vat = if (market) Rounding.vatInside(lineTotal, bp, Rounding.quantum(initial.currency, c.removeCents)) else 0L
        val basis = if (initial.pricesIncludeVat || !market) lineTotal else lineTotal - vat
        val key = "$IDEM_PREFIX${renewal.id}:${nextOrderNumber(conn, renewal.id)}"
        var tries = 0

        while (true) {
            val candidate = MarketOrder(
                userId = row.userId, playerUsername = row.playerUsername, totalPrice = price, currency = initial.currency, paymentMethodId = row.providerId,
                paymentLabel = initial.paymentLabel, status = OrderStatus.PENDING, createdAt = now, updatedAt = now, publicId = ids.publicId(),
                accessToken = ids.hexToken(ACCESS_TOKEN_BYTES), source = OrderSource.RENEWAL, buyerKey = initial.buyerKey, idempotencyKey = key,
                email = row.email ?: initial.email, locale = initial.locale, recipientUsername = row.playerUsername, recipientUserId = row.userId,
                recipientKey = initial.buyerKey, reservationState = ReservationState.HELD, expiresAt = expiresAt, baseCurrency = initial.baseCurrency,
                fxRate = initial.fxRate, displayCurrency = initial.displayCurrency, displayRate = initial.displayRate, pricingMode = initial.pricingMode,
                pricesIncludeVat = initial.pricesIncludeVat, subtotal = lineTotal, paymentFee = fee, paymentFeeVatPercent = initial.paymentFeeVatPercent,
                paymentFeeVatAmount = feeVat, vatTotal = vat + feeVat, creditAmount = 0, creditValue = 0, gatewayAmount = price, testMode = row.testMode,
                billingInfo = initial.billingInfo, subscriptionId = row.id
            )
            val id = orders.tryAdd(candidate, conn)

            if (id != null) {
                orderItems.add(
                    MarketOrderItem(
                        orderId = id, productId = item.productId, productName = row.productName, quantity = 1, unitPrice = basis, createdAt = now, updatedAt = now,
                        kind = OrderItemKind.PRODUCT, variantId = item.variantId, variantName = item.variantName, sku = item.sku, listUnitPrice = item.listUnitPrice,
                        vatPercent = bp, vatAmount = vat, lineTotal = lineTotal, creditUnitPrice = item.creditUnitPrice, fieldValues = item.fieldValues,
                        targetServerId = item.targetServerId, snapshot = item.snapshot, physical = false, stockReserved = 0
                    ),
                    conn
                )
                orderEvents.add(
                    MarketOrderEvent(
                        orderId = id, type = OrderEventType.CREATED, actorType = OrderActorType.SYSTEM,
                        data = JsonObject().put("source", OrderSource.RENEWAL.name).put("subscriptionId", row.id).put("periodIndex", renewal.periodIndex).encode(),
                        createdAt = now, updatedAt = now
                    ),
                    conn
                )

                return orders.getById(id, conn) ?: error("renewal order $id was just inserted")
            }

            // a clash on the key is the same order written before (a replay), anything else is a public id that was taken
            orders.getByBuyerAndIdempotencyKey(initial.buyerKey, key, conn)?.let { return it }

            check(++tries < MAX_ID_TRIES) { "no free public id for the renewal order of subscription ${row.id}" }
        }
    }

    /** `n` of the idempotency key `renewal:<renewalId>:<n>`: the orders this row had so far, plus one (09 section 8.1). */
    private suspend fun nextOrderNumber(conn: SqlClient, renewalId: Long): Int =
        conn.preparedQuery("SELECT COUNT(*) AS `n` FROM ${table("market_order")} WHERE `idempotencyKey` LIKE ?")
            .execute(Tuple.of("$IDEM_PREFIX$renewalId:%")).coAwait().first().getInteger("n") + 1

    /** A new payment attempt for [order] that market itself makes (a merchant charge, the attempt of a gateway renewal), `CREATED`, with its timeline row. */
    private suspend fun insertAttempt(conn: SqlConnection, row: MarketSubscription, order: MarketOrder, providerId: String, label: String, testMode: Boolean, extra: JsonObject = JsonObject()): MarketPayment {
        val now = clock.now()
        var tries = 0

        while (true) {
            val id = payments.add(
                MarketPayment(
                    orderId = order.id, subscriptionId = row.id, providerId = providerId, methodLabel = label, status = PaymentStatus.CREATED, reference = ids.reference(),
                    token = ids.hexToken(ACCESS_TOKEN_BYTES), amount = order.gatewayAmount, currency = order.currency, feeAmount = order.paymentFee, creditAmount = 0,
                    creditValue = 0, orderTotal = order.totalPrice, testMode = testMode, expiresAt = order.expiresAt, createdAt = now, updatedAt = now
                ),
                conn
            )

            if (id != null) {
                orderEvents.add(
                    MarketOrderEvent(
                        orderId = order.id, type = OrderEventType.PAYMENT_STARTED, actorType = OrderActorType.SYSTEM,
                        data = extra.put("providerId", providerId).put("paymentId", id).put("subscriptionId", row.id).encode(), createdAt = now, updatedAt = now
                    ),
                    conn
                )

                return payments.getById(id, conn) ?: error("attempt $id was just inserted")
            }

            check(++tries < MAX_ID_TRIES) { "no free attempt reference for the renewal order ${order.id}" }
        }
    }

    // ================================================================================================ 09 section 8.3: merchant charge, tx1

    /**
     * tx1 of a `MERCHANT` charge (09 section 8.3), run by `SubscriptionJob` step A (or by an admin retry, [admin], 09 section 9.3) inside
     * `MarketDb.txRestartingOnOrderChange`: the initial order and the subscription are locked in the order of 00 section 8.3, the row is re-read and re-judged
     * (mode, status, due), then exactly one of: nothing to charge ([ChargePreparation.Skipped]), an attempt of this period that has not settled
     * ([ChargePreparation.InFlight]: never charge twice), a failure that needs no provider call (stored method missing or expired), or the intent of a charge
     * ([ChargePreparation.Charge]): renewal row, renewal order, attempt `CREATED`, `renewal.attempts + 1` and a 15 minute lease on `nextChargeAt`.
     * The provider call and tx2 are the caller's. [provider] is what the caller learned about the provider before the transaction.
     */
    suspend fun prepareCharge(conn: SqlConnection, subscriptionId: Long, provider: ProviderFacts, admin: Boolean = false): ChargePreparation {
        val known = subscriptions.getById(subscriptionId, conn) ?: return ChargePreparation.Skipped(SKIP_GONE)

        return locks.orderWithSubscription(conn, known.initialOrderId) { locked -> prepareChargeLocked(conn, locked.order, subscriptionId, provider, admin) }
    }

    private suspend fun prepareChargeLocked(conn: SqlConnection, initial: MarketOrder, subscriptionId: Long, provider: ProviderFacts, admin: Boolean): ChargePreparation {
        val row = subscriptions.getById(subscriptionId, conn) ?: return ChargePreparation.Skipped(SKIP_GONE)
        val now = clock.now()
        val open = row.status == SubscriptionStatus.ACTIVE || row.status == SubscriptionStatus.PAST_DUE

        if (row.mode != SubscriptionMode.MERCHANT || !open) return ChargePreparation.Skipped(SKIP_NOT_CHARGEABLE)

        if (admin) {
            // 09 section 9.3: PAST_DUE, or ACTIVE with the period over, and a stored method
            if (row.storedMethod == null || (row.status == SubscriptionStatus.ACTIVE && (row.currentPeriodEnd ?: now) > now)) return ChargePreparation.Skipped(SKIP_NOT_RETRYABLE)
        } else if (row.nextChargeAt == null || row.nextChargeAt > now) {
            return ChargePreparation.Skipped(SKIP_NOT_DUE)
        }

        // the plan is finished or the buyer cancelled: nothing more is charged
        if (row.cancelAtPeriodEnd || (row.maxCycles != null && row.cycleCount >= row.maxCycles)) {
            update(conn, row.id, linkedMapOf("nextChargeAt" to null))

            return ChargePreparation.Skipped(SKIP_NO_CHARGE_NEEDED)
        }

        // a test-mode subscription whose provider left test mode is never charged for real (09 section 8.3)
        if (row.testMode && !provider.testMode) {
            applyDecision(conn, row, decide(row, SubEvent.CancelRequested(CancelActor.ADMIN, atPeriodEnd = false)), Context(initial, OrderActorType.SYSTEM))

            return ChargePreparation.Skipped(SKIP_TEST_MODE, ended = true)
        }

        if (blocks.blocked(row.playerUsername, row.playerUsername, row.email, null, row.userId, conn)) {
            applyDecision(conn, row, decide(row, SubEvent.BuyerBlocked), Context(initial, OrderActorType.SYSTEM))

            return ChargePreparation.Skipped(SKIP_BLOCKED, ended = subscriptions.getById(row.id, conn)?.status?.isTerminal == true)
        }

        var renewal = renewals.getByPeriod(row.id, row.cycleCount.coerceAtLeast(1), conn)

        if (!provider.available) {
            update(conn, row.id, linkedMapOf("nextChargeAt" to now + SubscriptionTimings.TECHNICAL_RETRY_MS))
            renewal?.let { setRenewalError(conn, it.id, PROVIDER_UNAVAILABLE, now) }

            return ChargePreparation.Skipped(SKIP_PROVIDER_UNAVAILABLE)
        }

        // a renewal that is paid, closed or waiting for an admin is not charged again
        if (renewal != null && renewal.status != RenewalStatus.PENDING) {
            update(conn, row.id, linkedMapOf("nextChargeAt" to null))

            return ChargePreparation.Skipped(SKIP_NOT_CHARGEABLE)
        }

        val renewalOrder = renewal?.orderId?.let { orders.getById(it, conn) }

        if (renewalOrder != null && (renewalOrder.status == OrderStatus.REVIEW || renewalOrder.status == OrderStatus.COMPLETED)) {
            update(conn, row.id, linkedMapOf("nextChargeAt" to null))

            return ChargePreparation.Skipped(SKIP_NOT_CHARGEABLE)
        }

        val inFlight = renewalOrder?.let { payments.getByOrderId(it.id, conn).firstOrNull { attempt -> attempt.status in OPEN_ATTEMPTS } }

        if (inFlight != null) {
            if (!admin) {
                // the intent was written and the outcome is unknown: never charge again, the status query or a human resolves it (09 section 8.3)
                update(conn, row.id, linkedMapOf("nextChargeAt" to null))
                renewal?.let { conn.preparedQuery("UPDATE ${table("market_subscription_renewal")} SET `nextAttemptAt` = NULL, `updatedAt` = ? WHERE `id` = ?").execute(Tuple.of(now, it.id)).coAwait() }

                return ChargePreparation.InFlight(row, renewalOrder, inFlight)
            }

            // an admin retry closes the attempt of unknown outcome first and accepts the double-charge risk
            conn.preparedQuery("UPDATE ${table("market_payment")} SET `status` = 'CANCELLED', `closedAt` = ?, `updatedAt` = ? WHERE `id` = ? AND `status` IN ('CREATED', 'PENDING', 'PROCESSING')")
                .execute(Tuple.of(now, now, inFlight.id)).coAwait()
        }

        val stored = storedMethodOf(row)
        val usable = stored != null && (stored.expiresAt == null || stored.expiresAt!! > now)
        val bundle = ensureRenewal(conn, row, now) { merchantExpiry(it, now) }

        renewal = bundle.renewal

        if (!usable) {
            // no instrument to charge: a final failure, the renewal order exists so that the buyer can pay it by hand (09 section 8.3, 9.1)
            val failure = Failure(if (stored == null) STORED_METHOD_MISSING else STORED_METHOD_EXPIRED, final = true, technical = false, gateway = false, attemptCount = null, attempts = renewal.attempts)

            applyFailure(conn, row, initial, renewal, bundle.order, failure)

            return ChargePreparation.Skipped(SKIP_NO_STORED_METHOD)
        }

        val attempt = insertAttempt(conn, row, bundle.order, row.providerId, bundle.order.paymentLabel, bundle.order.testMode, JsonObject().put("recurring", true))
        val attempts = renewal.attempts + 1

        conn.preparedQuery("UPDATE ${table("market_subscription_renewal")} SET `attempts` = `attempts` + 1, `nextAttemptAt` = ?, `paymentId` = ?, `updatedAt` = ? WHERE `id` = ?")
            .execute(Tuple.of(now + CHARGE_LEASE_MS, attempt.id, now, renewal.id)).coAwait()
        update(conn, row.id, linkedMapOf("nextChargeAt" to now + CHARGE_LEASE_MS))

        return ChargePreparation.Charge(row, bundle.order, attempt, stored!!, "sub-${row.id}-${renewal.periodIndex}-$attempts")
    }

    /**
     * `chargeRecurring` answered `Pending` (09 section 8.3): the charge is settled later by the gateway's notification or the reconcile job, so the lease on
     * `nextChargeAt` is released without scheduling another charge. Only a lease that is still there is cleared.
     */
    suspend fun settlePendingCharge(conn: SqlConnection, subscriptionId: Long) {
        val now = clock.now()

        conn.preparedQuery(
            "UPDATE ${table("market_subscription")} SET `nextChargeAt` = NULL, `updatedAt` = ? WHERE `id` = ? AND `mode` = 'MERCHANT' AND `status` IN ('ACTIVE', 'PAST_DUE') AND `nextChargeAt` > ? AND `nextChargeAt` <= ?"
        ).execute(Tuple.of(now, subscriptionId, now, now + CHARGE_LEASE_MS)).coAwait()
    }

    /**
     * The status query of an attempt of unknown outcome got no answer (the provider threw or timed out, 09 sections 8.3 and 11): the attempt stays open, never closed
     * as a failure on a question that was not answered, and never answered by a second charge. [prepareCharge] cleared `nextChargeAt` for the open attempt, so it is
     * armed again one lease ahead: the next due tick finds the attempt in flight and asks again. Only for a `CREATED` attempt of a `MERCHANT` row that is still
     * waiting (an attempt the gateway holds is settled by its notification and the reconcile job, a row that moved on is not touched). Judged under the row lock.
     * Answers whether the question was re-armed.
     */
    suspend fun askAgainLater(conn: SqlConnection, subscriptionId: Long, attemptId: Long): Boolean {
        val known = subscriptions.getById(subscriptionId, conn) ?: return false

        return locks.orderWithSubscription(conn, known.initialOrderId) {
            val row = subscriptions.getById(subscriptionId, conn) ?: return@orderWithSubscription false
            val attempt = payments.getById(attemptId, conn)
            val open = row.status == SubscriptionStatus.ACTIVE || row.status == SubscriptionStatus.PAST_DUE

            if (row.mode != SubscriptionMode.MERCHANT || !open || row.nextChargeAt != null || attempt == null || attempt.status != PaymentStatus.CREATED) {
                return@orderWithSubscription false
            }

            val now = clock.now()

            update(conn, row.id, linkedMapOf("nextChargeAt" to now + CHARGE_LEASE_MS))
            conn.preparedQuery("UPDATE ${table("market_subscription_renewal")} SET `nextAttemptAt` = ?, `updatedAt` = ? WHERE `subscriptionId` = ? AND `status` = 'PENDING' AND `nextAttemptAt` IS NULL")
                .execute(Tuple.of(now + CHARGE_LEASE_MS, now, row.id)).coAwait()

            true
        }
    }

    /** `max(now, periodStart) + (subscriptionGraceDays + 8) days`: the window of the renewal order of a merchant charge (09 section 8.3). */
    private fun merchantExpiry(renewal: MarketSubscriptionRenewal, now: Long): Long =
        maxOf(now, renewal.periodStart) + (config().subscriptionGraceDays + MERCHANT_ORDER_EXTRA_DAYS) * SubscriptionTimings.DAY_MS

    /** The stored instrument of [row] (09 section 4.4 step 3), `null` when there is none or it cannot be read. */
    private fun storedMethodOf(row: MarketSubscription): StoredPaymentMethod? {
        val json = row.storedMethod?.let { cipher.decrypt(it) }?.let { runCatching { JsonObject(it) }.getOrNull() } ?: return null
        val token = json.getString("token")?.takeIf { it.isNotBlank() } ?: return null

        return StoredPaymentMethod(token).also {
            it.label = json.getString("label")
            it.expiresAt = json.getLong("expiresAt")
            it.gatewayCustomerId = json.getString("gatewayCustomerId")
        }
    }

    private suspend fun setRenewalError(conn: SqlClient, renewalId: Long, code: String?, now: Long) {
        conn.preparedQuery("UPDATE ${table("market_subscription_renewal")} SET `lastError` = ?, `updatedAt` = ? WHERE `id` = ?")
            .execute(Tuple.of((code ?: CHARGE_FAILED).take(ERROR_MAX), now, renewalId)).coAwait()
    }

    /** [decision] applied (nothing for an ignored one, never a poll). */
    private suspend fun applyDecision(conn: SqlConnection, row: MarketSubscription, decision: SubTransition, context: Context): Boolean {
        if (decision !is SubTransition.Apply) return false

        apply(conn, row, decision, context)

        return true
    }

    // ----- 09 section 9.1: a failed charge

    /**
     * `RenewalFailed` through the state machine with the failure [failure] (09 section 9.1): the code on the renewal row, `failCount`, the status (`ACTIVE` becomes
     * `PAST_DUE` with its grace end, the mail and the timeline row once), the next try by [RetrySchedule] (`nextChargeAt`, mirrored on the renewal row), `null`
     * when it is final. A technical failure changes nothing but the retry in one hour.
     */
    private suspend fun applyFailure(
        conn: SqlConnection, row: MarketSubscription, initial: MarketOrder, renewal: MarketSubscriptionRenewal?, renewalOrder: MarketOrder?, failure: Failure
    ) {
        val decision = decide(row, SubEvent.RenewalFailed(failure.final, failure.technical, failure.attempts))

        if (!applyDecision(conn, row, decision, Context(initial, OrderActorType.SYSTEM, renewalOrder, renewal, failure))) return

        val now = clock.now()
        val next = subscriptions.getById(row.id, conn)?.nextChargeAt

        renewal?.let { conn.preparedQuery("UPDATE ${table("market_subscription_renewal")} SET `nextAttemptAt` = ?, `updatedAt` = ? WHERE `id` = ?").execute(Tuple.of(next, now, it.id)).coAwait() }
    }

    /**
     * An attempt of a renewal order reached `FAILED` / `EXPIRED` (hook of [PaymentService.applyIn], 09 section 9.1): the subscription and the order are locked.
     * The attempt of a charge market made is a failure of the subscription (declined, or technical when the call itself failed); the buyer's own attempt only
     * leaves its code on the renewal row; `UNSUPPORTED` is no failure at all, the subscription turns `MANUAL` (09 section 8.3).
     */
    override suspend fun onAttemptFailed(conn: SqlConnection, order: MarketOrder, attempt: MarketPayment, final: Boolean, facts: AttemptFacts) {
        val id = order.subscriptionId ?: return
        val row = subscriptions.getById(id, conn) ?: return
        val renewal = renewalOf(conn, order)
        val now = clock.now()

        if (attempt.subscriptionId == null) {
            renewal?.let { setRenewalError(conn, it.id, facts.failureCode, now) }

            return
        }

        if (row.mode != SubscriptionMode.MERCHANT) return

        val initial = orders.getById(row.initialOrderId, conn) ?: return

        if (facts.recurringOutcome == AttemptFacts.RECURRING_UNSUPPORTED) return downgradeToManual(conn, row, initial, order, renewal)

        val failure = Failure(facts.failureCode, final, facts.recurringOutcome == AttemptFacts.RECURRING_TECHNICAL, gateway = false, attemptCount = null, attempts = renewal?.attempts ?: 1)

        applyFailure(conn, row, initial, renewal, order, failure)
    }

    /**
     * `chargeRecurring` answered `UNSUPPORTED` (09 section 8.3): the attempt is failed, the subscription renews manually from now on (`storedMethod` dropped), the
     * renewal order stays payable for the grace period, the buyer gets the reminder mail and the timeline a note.
     */
    private suspend fun downgradeToManual(conn: SqlConnection, row: MarketSubscription, initial: MarketOrder, renewalOrder: MarketOrder, renewal: MarketSubscriptionRenewal?) {
        val now = clock.now()

        update(conn, row.id, linkedMapOf("mode" to SubscriptionMode.MANUAL.name, "storedMethod" to null, "storedMethodLabel" to null, "nextChargeAt" to null))

        if (renewal != null) conn.preparedQuery("UPDATE ${table("market_subscription_renewal")} SET `nextAttemptAt` = NULL, `updatedAt` = ? WHERE `id` = ?").execute(Tuple.of(now, renewal.id)).coAwait()

        if (renewalOrder.status == OrderStatus.PENDING) {
            val expiresAt = maxOf(now, row.currentPeriodEnd ?: now) + config().subscriptionGraceDays * SubscriptionTimings.DAY_MS

            conn.preparedQuery("UPDATE ${table("market_order")} SET `expiresAt` = ?, `updatedAt` = GREATEST(?, `updatedAt` + 1) WHERE `id` = ? AND `status` = 'PENDING'")
                .execute(Tuple.of(expiresAt, now, renewalOrder.id)).coAwait()
        }

        orderEvents.add(
            MarketOrderEvent(
                orderId = initial.id, type = OrderEventType.NOTE, actorType = OrderActorType.SYSTEM, message = UNSUPPORTED_NOTE,
                data = JsonObject().put("subscriptionId", row.id).put("providerId", row.providerId).encode(), createdAt = now, updatedAt = now
            ),
            conn
        )

        enqueueReminder(conn, subscriptions.getById(row.id, conn) ?: row, renewalOrder, renewal?.periodIndex ?: row.cycleCount.coerceAtLeast(1))
    }

    // ================================================================================================ 09 section 8.4: a paid renewal

    /** What a hand payment of a renewal order says about the instrument (09 section 8.4 step 4), kept between [onPaid] and the order transition of the same transaction. */
    private class PaidFacts(val attemptId: Long, val stored: StoredPaymentMethod?)

    private val paidFacts: MutableMap<Long, PaidFacts> = java.util.Collections.synchronizedMap(object : LinkedHashMap<Long, PaidFacts>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, PaidFacts>?): Boolean = size > PAID_FACTS_MAX
    })

    /** `SubscriptionOnOrderPaid` of a renewal order (O2 / O4): the renewal row becomes `PAID`, the period moves, the mode and the next charge follow (09 section 8.4). */
    private suspend fun onRenewalPaid(conn: SqlConnection, order: MarketOrder) {
        val subscriptionId = order.subscriptionId ?: return
        val row = subscriptions.getById(subscriptionId, conn) ?: error("order ${order.id} names subscription $subscriptionId, which does not exist")
        val renewal = renewalOf(conn, order) ?: error("the renewal order ${order.id} has no renewal row")

        // the period was booked already (a replay, or an older order of the row paid after its replacement): money is recorded on the order, a period never twice
        if (renewal.status == RenewalStatus.PAID) return

        val payment = order.paymentId?.let { payments.getById(it, conn) }
        val caps = payment?.let { capabilities(it.providerId, conn) }
        val recorded = paidFacts.remove(order.id)?.takeIf { it.attemptId == payment?.id }
        val charged = payment?.subscriptionId != null
        val modeAfter = when {
            caps != null -> ModeResolver.atRenewal(row.mode, caps, recorded?.stored != null, charged)
            row.mode == SubscriptionMode.GATEWAY -> SubscriptionMode.GATEWAY
            row.mode == SubscriptionMode.MERCHANT && charged -> SubscriptionMode.MERCHANT
            else -> SubscriptionMode.MANUAL
        }
        val initial = orders.getById(row.initialOrderId, conn) ?: error("the initial order of subscription $subscriptionId does not exist")

        when (val decision = decide(row, SubEvent.RenewalPaid(modeAfter))) {
            is SubTransition.Ignored ->
                // a payment for a closed subscription never reaches O2 (the guard sends it to review, 09 section 8.5); an admin's accept of it is refused
                if (decision.reason == SubscriptionStateMachine.SUBSCRIPTION_CLOSED) throw InvalidOrderTransition(reason = SubscriptionStateMachine.SUBSCRIPTION_CLOSED)
                else logger.warn("subscription {}: the paid renewal order {} was ignored ({})", subscriptionId, order.id, decision.reason)

            is SubTransition.Apply -> apply(conn, row, decision, Context(initial, OrderActorType.SYSTEM, order, renewal, storedMethod = recorded?.stored))

            SubTransition.PollFirst -> error("a paid renewal never polls")
        }
    }

    /** `ApplyRenewal` (09 section 8.4 steps 2 to 5). */
    private suspend fun applyRenewal(conn: SqlConnection, id: Long, effect: SubEffect.ApplyRenewal, context: Context, now: Long) {
        val row = subscriptions.getById(id, conn) ?: error("subscription $id vanished inside its transaction")
        val order = context.renewalOrder ?: error("a paid renewal without its order")
        val renewal = context.renewal ?: error("a paid renewal without its renewal row")
        val payment = order.paymentId?.let { payments.getById(it, conn) }
        val stored = context.storedMethod

        // 2. the renewal row
        conn.preparedQuery("UPDATE ${table("market_subscription_renewal")} SET `status` = 'PAID', `orderId` = ?, `paymentId` = COALESCE(?, `paymentId`), `nextAttemptAt` = NULL, `updatedAt` = ? WHERE `id` = ?")
            .execute(Tuple.of(order.id, payment?.id, now, renewal.id)).coAwait()

        // 3. the period moves; the new price is what the buyer paid (09 section 8.4, last paragraph)
        // a renewal that credits paid in full carries no payment fee (a full-credit tender has none), so its total is the frozen price minus the fee of the first order:
        // it must not become the new price, or every credit-paid period would take that fee off the subscription for good. A gateway-paid renewal has the frozen fee
        // (RENEWAL pricing profile, 05 section 12) and so always totals the frozen price.
        val paidInCredits = order.gatewayAmount == 0L && order.creditAmount > 0
        val sets = linkedMapOf<String, Any?>(
            "cycleCount" to row.cycleCount + 1, "currentPeriodStart" to renewal.periodStart, "currentPeriodEnd" to renewal.periodEnd, "graceEndsAt" to null, "failCount" to 0,
            "reminderSentAt" to null, "price" to if (paidInCredits) row.price else order.totalPrice, "mode" to effect.mode.name
        )

        // 4. the method: the paying provider, a replaced card, or the fall back to a manual renewal
        payment?.providerId?.let { sets["providerId"] = it }

        when {
            effect.mode == SubscriptionMode.MANUAL -> {
                sets["storedMethod"] = null
                sets["storedMethodLabel"] = null
            }

            effect.mode == SubscriptionMode.MERCHANT && stored != null -> {
                val json = JsonObject().put("token", stored.token).put("label", stored.label).put("expiresAt", stored.expiresAt).put("gatewayCustomerId", stored.gatewayCustomerId)

                sets["storedMethod"] = cipher.encrypt(json.encode())
                sets["storedMethodLabel"] = clip(stored.label ?: payment?.methodDetail, LABEL_MAX)
                stored.gatewayCustomerId?.let { sets["gatewayCustomerId"] = it }
            }
        }

        // 5. the next charge (merchant) or the next poll (gateway)
        sets["nextChargeAt"] = if (effect.scheduleCharge) renewal.periodEnd else null
        sets["nextQueryAt"] = if (effect.scheduleQuery) renewal.periodEnd + SubscriptionTimings.HOUR_MS else row.nextQueryAt.takeIf { row.remoteCancelState == RemoteCancelState.PENDING }

        update(conn, id, sets)
    }

    /** `QueueRenewDeliveries` (09 section 8.4 step 6): the `RENEW` rows O2 planned for the renewal order's item belong to the subscription's entitlement. */
    private suspend fun linkRenewDeliveries(conn: SqlConnection, id: Long, context: Context, now: Long) {
        val order = context.renewalOrder ?: return
        val row = subscriptions.getById(id, conn) ?: return

        conn.preparedQuery(
            "UPDATE ${table("market_delivery")} SET `entitlementId` = ?, `subscriptionId` = ?, `updatedAt` = ? WHERE `orderId` = ? AND `phase` = 'RENEW' AND `entitlementId` IS NULL"
        ).execute(Tuple.of(row.entitlementId, id, now, order.id)).coAwait()
    }

    /**
     * What an admin or a hand payment of a renewal order says to the instrument, recorded when the success is applied (hook of [PaymentService.applyIn]): the
     * subscription row is not touched here, the transition of the order reads it back at O2 / O4.
     */
    private fun recordRenewalPayment(order: MarketOrder, attempt: MarketPayment, facts: AttemptFacts) {
        paidFacts[order.id] = PaidFacts(attempt.id, facts.storedMethod)
    }

    // ================================================================================================ 09 section 8.6: manual renewal, notices

    /** What [prepareManual] and [notifyUpcoming] did, for the job's count and the tests. */
    enum class NoticeOutcome { PREPARED, NOTIFIED, SKIPPED }

    /**
     * Step B of `SubscriptionJob` for a `MANUAL` subscription (09 section 8.6), in one transaction: the renewal row of the period (`PENDING`), its order
     * (`expiresAt = currentPeriodEnd + subscriptionGraceDays`, no attempt) and the reminder mail (`subscriptionReminderDays > 0`), `reminderSentAt = now`. Judged again under
     * the lock; a period that has its renewal order is never prepared twice.
     */
    suspend fun prepareManual(conn: SqlConnection, subscriptionId: Long): NoticeOutcome {
        val known = subscriptions.getById(subscriptionId, conn) ?: return NoticeOutcome.SKIPPED

        return locks.orderWithSubscription(conn, known.initialOrderId) { locked ->
            val row = subscriptions.getById(subscriptionId, conn) ?: return@orderWithSubscription NoticeOutcome.SKIPPED
            val now = clock.now()
            val end = row.currentPeriodEnd ?: return@orderWithSubscription NoticeOutcome.SKIPPED
            val start = row.currentPeriodStart ?: end
            val lead = PeriodCalculator.reminderLeadMs(config().subscriptionReminderDays, start, end)
            val finished = row.maxCycles != null && row.cycleCount >= row.maxCycles

            if (row.status != SubscriptionStatus.ACTIVE || row.mode != SubscriptionMode.MANUAL || row.cancelAtPeriodEnd || finished || end - lead > now) {
                return@orderWithSubscription NoticeOutcome.SKIPPED
            }

            val existing = renewals.getByPeriod(row.id, row.cycleCount.coerceAtLeast(1), conn)

            if (existing != null && (existing.status != RenewalStatus.PENDING || existing.orderId != null)) return@orderWithSubscription NoticeOutcome.SKIPPED

            val bundle = ensureRenewal(conn, row, now) { row.currentPeriodEnd!! + config().subscriptionGraceDays * SubscriptionTimings.DAY_MS }

            if (config().subscriptionReminderDays > 0) enqueueReminder(conn, row, bundle.order, bundle.renewal.periodIndex)

            update(conn, row.id, linkedMapOf("reminderSentAt" to now))

            NoticeOutcome.PREPARED
        }
    }

    /**
     * The upcoming-charge notice of a `GATEWAY` / `MERCHANT` subscription (09 section 8.6, last paragraph): `subscriptionReminderDays` before the period ends, once
     * per period (`reminderSentAt`), no renewal order and no pay link.
     */
    suspend fun notifyUpcoming(conn: SqlConnection, subscriptionId: Long): NoticeOutcome {
        val known = subscriptions.getById(subscriptionId, conn) ?: return NoticeOutcome.SKIPPED

        return locks.orderWithSubscription(conn, known.initialOrderId) {
            val row = subscriptions.getById(subscriptionId, conn) ?: return@orderWithSubscription NoticeOutcome.SKIPPED
            val now = clock.now()
            val end = row.currentPeriodEnd ?: return@orderWithSubscription NoticeOutcome.SKIPPED
            val days = config().subscriptionReminderDays
            val finished = row.maxCycles != null && row.cycleCount >= row.maxCycles

            if (days <= 0 || row.status != SubscriptionStatus.ACTIVE || row.mode == SubscriptionMode.MANUAL || row.cancelAtPeriodEnd || finished ||
                row.reminderSentAt != null || end - days * SubscriptionTimings.DAY_MS > now || end <= now
            ) {
                return@orderWithSubscription NoticeOutcome.SKIPPED
            }

            enqueueReminder(conn, row, null, row.cycleCount.coerceAtLeast(1))
            update(conn, row.id, linkedMapOf("reminderSentAt" to now))

            NoticeOutcome.NOTIFIED
        }
    }

    /** `SUBSCRIPTION_REMINDER` (09 section 12.2): once per `(subscription, periodIndex)`; [renewalOrder] gives the pay link, `null` for an upcoming-charge notice. */
    private suspend fun enqueueReminder(conn: SqlConnection, row: MarketSubscription, renewalOrder: MarketOrder?, periodIndex: Int) {
        val outbox = mail ?: return
        val recipient = row.email ?: return
        val params = JsonObject().put("productName", row.productName).put("periodEnd", row.currentPeriodEnd).put("price", EventPayloads.money(row.price))
            .put("currency", row.currency).put("manageUrl", MANAGE_PATH)

        renewalOrder?.publicId?.let { params.put("payUrl", "/store/order/$it") }

        outbox.enqueue(
            conn, MailKind.SUBSCRIPTION_REMINDER, MailRefType.SUBSCRIPTION, row.id, refKey = periodIndex.toString(), orderId = row.initialOrderId, userId = row.userId,
            recipient = recipient, locale = orders.getById(row.initialOrderId, conn)?.locale ?: DEFAULT_LOCALE, params = params
        )
    }

    // ================================================================================================ 09 sections 8.2 and 9.1: gateway renewal and failure

    /** What [onGatewayRenewed] did. */
    sealed class RenewedOutcome {
        data object UnknownSubscription : RenewedOutcome()

        /** The subscription is still `PENDING`: the provider must not report the first period as a renewal. */
        data object NotActivated : RenewedOutcome()

        /** The event was seen before ([rule] of [RenewalDedupe]); nothing was written. */
        data class Duplicate(val rule: String) : RenewedOutcome()

        /** The renewal order and its attempt exist (tx A); [event] is the success to apply to [attemptId] (tx B, `PaymentService.applyEvent`). */
        class Prepared(val subscriptionId: Long, val orderId: Long, val attemptId: Long, val event: PaymentEvent.Succeeded) : RenewedOutcome()
    }

    /**
     * `SubscriptionRenewed` (09 section 8.2), tx A: the subscription is found by `uq_provider_sub`, locked, the event is de-duplicated ([RenewalDedupe]), the renewal row
     * of the period is inserted or reused (`uq_sub_period`: the same period twice is one row and one order), the renewal order and a `CREATED` attempt are written
     * (amount = the frozen price, the gateway's id is stored by tx B). Tx B is `PaymentService.applyEvent` of [RenewedOutcome.Prepared.event]: the normal
     * payment path under its own lock set (an underpaid renewal goes to `REVIEW`, a terminal subscription to `REVIEW (LATE)`, 09 section 8.5). Two transactions,
     * because O2 needs the product locks before the subscription's (00 section 8.3), which a single transaction that already holds the subscription cannot take.
     */
    suspend fun onGatewayRenewed(conn: SqlConnection, providerId: String, event: PaymentEvent.SubscriptionRenewed): RenewedOutcome {
        val target = event.target as? com.panomc.plugins.market.spi.payment.PaymentTarget.Subscription ?: return RenewedOutcome.UnknownSubscription
        val known = subscriptions.getByGatewaySubscription(providerId, target.gatewaySubscriptionId, conn) ?: return RenewedOutcome.UnknownSubscription.also {
            logger.warn("provider {} reported a renewal of the subscription {}, which is not one of its subscriptions, skipped", providerId, target.gatewaySubscriptionId)
        }

        return locks.orderWithSubscription(conn, known.initialOrderId) { locked ->
            val row = subscriptions.getById(known.id, conn) ?: return@orderWithSubscription RenewedOutcome.UnknownSubscription

            if (row.status == SubscriptionStatus.PENDING) return@orderWithSubscription RenewedOutcome.NotActivated

            val now = clock.now()
            val index = row.cycleCount.coerceAtLeast(1)
            val transaction = event.gatewayTransactionId?.takeIf { it.isNotBlank() }
            val allRenewals = renewals.getBySubscriptionId(row.id, conn)
            val facts = RenewalDedupe.Facts(
                paymentWithTransactionExists = transaction?.let { payments.getByProviderTransaction(providerId, it, conn) } != null,
                paidRenewalWithPeriodStartExists = event.periodStart?.let { start -> allRenewals.any { it.status == RenewalStatus.PAID && it.periodStart == start } } ?: false,
                currentPeriodStart = row.currentPeriodStart, currentPeriodEnd = row.currentPeriodEnd, newestPaidOrderPaidAt = newestPaidAt(conn, row.id)
            )

            RenewalDedupe.match(RenewalDedupe.Event(transaction, event.periodStart), facts, now)?.let { return@orderWithSubscription RenewedOutcome.Duplicate(it.name) }

            // the period: the event's own when it is sound, else the computed one (09 section 5); the row of the period is reused when it exists
            var renewal = renewals.getByPeriod(row.id, index, conn)

            if (renewal != null && renewal.status == RenewalStatus.PAID) return@orderWithSubscription RenewedOutcome.Duplicate(RenewalDedupe.Match.PERIOD_START.name)

            if (renewal == null) {
                val period = calculatorOf(row).gatewayRenewalPeriod(anchorOf(conn, row, now), row.currentPeriodEnd ?: now, now, event.periodStart, event.periodEnd)

                renewals.add(
                    MarketSubscriptionRenewal(
                        subscriptionId = row.id, periodIndex = index, periodStart = period.start, periodEnd = period.end, status = RenewalStatus.PENDING,
                        amount = event.paid.amount, currency = event.paid.currency, createdAt = now, updatedAt = now
                    ),
                    conn
                )
                renewal = renewals.getByPeriod(row.id, index, conn) ?: error("renewal $index of subscription ${row.id} was not written")
            } else if (renewal.status == RenewalStatus.PENDING && renewal.orderId == null) {
                // a row that a failed payment of the gateway created (09 section 9.1) knows nothing of the gateway's period: the event's own is what the renewal covers
                // (09 section 5), so the row takes it now, before its order exists
                val period = calculatorOf(row).gatewayRenewalPeriod(anchorOf(conn, row, now), row.currentPeriodEnd ?: now, now, event.periodStart, event.periodEnd)

                if (period.start != renewal.periodStart || period.end != renewal.periodEnd) {
                    conn.preparedQuery("UPDATE ${table("market_subscription_renewal")} SET `periodStart` = ?, `periodEnd` = ?, `amount` = ?, `currency` = ?, `updatedAt` = ? WHERE `id` = ? AND `status` = 'PENDING' AND `orderId` IS NULL")
                        .execute(Tuple.of(period.start, period.end, event.paid.amount, event.paid.currency, now, renewal.id)).coAwait()
                }
            }

            val bundle = ensureRenewal(conn, row, now) { now + config().orderExpiryMinutes * SubscriptionTimings.MINUTE_MS }
            val order = bundle.order

            // a replay after a crash between tx A and tx B finds its attempt: the same order and attempt are paid, never a second pair
            val existing = payments.getByOrderId(order.id, conn).firstOrNull { it.status in OPEN_ATTEMPTS }
            val attempt = existing ?: insertAttempt(conn, row, order, row.providerId, order.paymentLabel, row.testMode, JsonObject().put("gatewayRenewal", true))

            conn.preparedQuery("UPDATE ${table("market_subscription_renewal")} SET `paymentId` = ?, `updatedAt` = ? WHERE `id` = ?").execute(Tuple.of(attempt.id, now, bundle.renewal.id)).coAwait()

            val success = PaymentEvent.Succeeded(com.panomc.plugins.market.spi.payment.PaymentTarget.Attempt(attempt.id), event.paid).also {
                it.gatewayTransactionId = transaction
                it.gatewayFee = event.gatewayFee
                it.net = event.net
                it.methodDetail = event.methodDetail
                it.testMode = event.testMode
                it.occurredAt = event.occurredAt
            }

            RenewedOutcome.Prepared(row.id, order.id, attempt.id, success)
        }
    }

    /** `paidAt` of the newest paid order of the subscription (the initial order and every renewal), `null` when none is paid (rule (c) of 09 section 8.2). */
    private suspend fun newestPaidAt(conn: SqlClient, subscriptionId: Long): Long? =
        conn.preparedQuery("SELECT MAX(`paidAt`) AS `at` FROM ${table("market_order")} WHERE `subscriptionId` = ? AND `status` IN ('COMPLETED', 'PARTIALLY_REFUNDED', 'REFUNDED')")
            .execute(Tuple.of(subscriptionId)).coAwait().first().getLong("at")

    /**
     * `SubscriptionPaymentFailed` (09 section 9.1, `GATEWAY` column): the row of the period is created when it is missing (no order), `attempts` follows the
     * gateway's `attemptCount`, the failure is recorded once per attempt count (the same count delivered again changes nothing), `ACTIVE` becomes `PAST_DUE`; a final failure
     * after the period ended expires the subscription at once, inside the paid period it schedules the end (S8, `PAYMENT_FAILED`).
     */
    suspend fun onGatewayPaymentFailed(conn: SqlConnection, providerId: String, event: PaymentEvent.SubscriptionPaymentFailed): GatewayOutcome {
        val target = event.target as? com.panomc.plugins.market.spi.payment.PaymentTarget.Subscription ?: return GatewayOutcome.UnknownSubscription
        val known = subscriptions.getByGatewaySubscription(providerId, target.gatewaySubscriptionId, conn) ?: return GatewayOutcome.UnknownSubscription.also {
            logger.warn("provider {} reported a failed payment of the subscription {}, which is not one of its subscriptions, skipped", providerId, target.gatewaySubscriptionId)
        }

        return locks.orderWithSubscription(conn, known.initialOrderId) { locked ->
            val row = subscriptions.getById(known.id, conn) ?: return@orderWithSubscription GatewayOutcome.UnknownSubscription
            val renewal = renewals.getByPeriod(row.id, row.cycleCount.coerceAtLeast(1), conn)

            // the same attempt count delivered again is the same failure
            if (event.attemptCount != null && renewal != null && renewal.attempts >= event.attemptCount!!) return@orderWithSubscription GatewayOutcome.Ignored(DUPLICATE_FAILURE)

            val attempts = event.attemptCount ?: ((renewal?.attempts ?: 0) + 1)
            val failure = Failure(GATEWAY_DECLINED, event.final, technical = false, gateway = true, attemptCount = attempts, attempts = attempts)

            when (val decision = decide(row, SubEvent.RenewalFailed(event.final, technical = false, attempts = attempts))) {
                is SubTransition.Ignored -> GatewayOutcome.Ignored(decision.reason)

                is SubTransition.Apply -> {
                    apply(conn, row, decision, Context(locked.order, OrderActorType.GATEWAY, null, renewal, failure))

                    GatewayOutcome.Applied(decision.to, decision.rules.map { it.name })
                }

                SubTransition.PollFirst -> GatewayOutcome.Ignored("POLL_FIRST")
            }
        }
    }

    // ================================================================================================ 09 section 11: the clock steps

    /** What a clock step did to one row. */
    sealed class StepOutcome {
        /** The machine ignored the step ([reason] is one of its codes): not due, in the slack, waiting for a payment. */
        data class Idle(val reason: String) : StepOutcome()

        /** Step C on a `GATEWAY` row: ask the gateway first, apply what it says, then run the step again with `polled = true`. */
        data object PollFirst : StepOutcome()

        /** The row moved to [to]; [rules] are the rows of 09 section 6 that were applied. */
        data class Applied(val to: SubscriptionStatus, val rules: List<String>) : StepOutcome() {
            val ended: Boolean get() = to.isTerminal
        }
    }

    /**
     * Step C `periodOver` of one row (09 section 11), judged under the lock: the cancel at the period end (S9), a paused row (S13), a finished plan (S14), a manual
     * row that was not renewed (S4), a gateway or merchant row that is still unpaid after the 24 hour slack (S4). [polled] / [providerUnavailable] are what the job
     * learned from the gateway; the renewal order's open attempt is read here.
     */
    suspend fun periodOver(conn: SqlConnection, subscriptionId: Long, polled: Boolean, providerUnavailable: Boolean): StepOutcome {
        val known = subscriptions.getById(subscriptionId, conn) ?: return StepOutcome.Idle(SKIP_GONE)

        return locks.orderWithSubscription(conn, known.initialOrderId) { locked ->
            val row = subscriptions.getById(subscriptionId, conn) ?: return@orderWithSubscription StepOutcome.Idle(SKIP_GONE)
            val renewal = renewals.getByPeriod(row.id, row.cycleCount.coerceAtLeast(1), conn)
            val inFlight = renewal?.orderId?.let { payments.getByOrderId(it, conn).any { attempt -> attempt.status in OPEN_ATTEMPTS } } ?: false
            val context = Context(locked.order, OrderActorType.SYSTEM, renewal?.orderId?.let { orders.getById(it, conn) }, renewal)

            outcomeOf(conn, row, decide(row, SubEvent.PeriodOver(polled, providerUnavailable, inFlight)), context)
        }
    }

    /**
     * Step D `graceOver` of one row (09 section 9.4): `PAST_DUE` whose grace ended expires (S6) unless the renewal order has an attempt in `PROCESSING` or a last
     * retry is still scheduled inside the grace (the wait is capped at `graceEndsAt + 7 days`); the end reason is `PROVIDER_UNAVAILABLE` when the last failure was
     * technical.
     */
    suspend fun graceOver(conn: SqlConnection, subscriptionId: Long): StepOutcome {
        val known = subscriptions.getById(subscriptionId, conn) ?: return StepOutcome.Idle(SKIP_GONE)

        return locks.orderWithSubscription(conn, known.initialOrderId) { locked ->
            val row = subscriptions.getById(subscriptionId, conn) ?: return@orderWithSubscription StepOutcome.Idle(SKIP_GONE)
            val renewal = renewals.getByPeriod(row.id, row.cycleCount.coerceAtLeast(1), conn)
            val processing = renewal?.orderId?.let { payments.getByOrderId(it, conn).any { attempt -> attempt.status == PaymentStatus.PROCESSING } } ?: false
            val unavailable = renewal?.lastError in TECHNICAL_ERRORS
            val context = Context(locked.order, OrderActorType.SYSTEM, renewal?.orderId?.let { orders.getById(it, conn) }, renewal)

            outcomeOf(conn, row, decide(row, SubEvent.GraceOver(processing, unavailable)), context)
        }
    }

    /**
     * Step F `pendingCleanup` of one row (09 section 4.3): a `PENDING` row whose initial order was released at least 30 days ago is closed (S3,
     * `PAYMENT_FAILED`); a row whose order is still waiting or was paid stays.
     */
    suspend fun closePendingAfterTimeout(conn: SqlConnection, subscriptionId: Long): StepOutcome {
        val known = subscriptions.getById(subscriptionId, conn) ?: return StepOutcome.Idle(SKIP_GONE)

        return locks.orderWithSubscription(conn, known.initialOrderId) { locked ->
            val row = subscriptions.getById(subscriptionId, conn) ?: return@orderWithSubscription StepOutcome.Idle(SKIP_GONE)
            val order = locked.order
            val released = order.status in CLOSED_UNPAID && order.updatedAt <= clock.now() - PENDING_TIMEOUT_MS

            if (!released) return@orderWithSubscription StepOutcome.Idle(SKIP_NOT_DUE)

            outcomeOf(conn, row, decide(row, SubEvent.PendingTimeout), Context(order, OrderActorType.SYSTEM))
        }
    }

    private suspend fun outcomeOf(conn: SqlConnection, row: MarketSubscription, decision: SubTransition, context: Context): StepOutcome = when (decision) {
        is SubTransition.Ignored -> StepOutcome.Idle(decision.reason)

        SubTransition.PollFirst -> StepOutcome.PollFirst

        is SubTransition.Apply -> {
            apply(conn, row, decision, context)

            StepOutcome.Applied(decision.to, decision.rules.map { it.name })
        }
    }

    /**
     * The renewal orders of ended subscriptions that nobody paid are cancelled (O7, actor `SYSTEM`, open attempt cancelled at the gateway after the commit):
     * 09 section 10.5 step 2, in a transaction of its own. Doing it inside the ending would take the product and credit locks of the renewal order after the
     * subscription's, against the order of 00 section 8.3; the ending marks the renewal row, this follows. A payment that arrives before it is diverted to
     * review (`LATE`, 09 section 8.5). Returns the orders cancelled. Without an order service nothing is cancelled and the orders expire.
     */
    suspend fun cancelClosedRenewalOrders(db: MarketDb, afterCommit: suspend (List<AfterCommit>) -> Unit, subscriptionId: Long? = null): Int {
        val service = orderService() ?: return 0
        val open = db.tx { client ->
            client.preparedQuery(
                "SELECT o.`id` FROM ${table("market_order")} o JOIN ${table("market_subscription")} s ON s.`id` = o.`subscriptionId` " +
                    "WHERE o.`source` = 'RENEWAL' AND o.`status` = 'PENDING' AND s.`status` IN ('EXPIRED', 'CANCELLED', 'COMPLETED')" +
                    (if (subscriptionId != null) " AND s.`id` = ?" else "") + " ORDER BY o.`id` LIMIT $CLEANUP_BATCH"
            ).execute(if (subscriptionId != null) Tuple.of(subscriptionId) else Tuple.tuple()).coAwait().map { it.getLong("id") }
        }
        var cancelled = 0

        for (orderId in open) {
            val after = ArrayList<AfterCommit>()

            try {
                db.txRestartingOnOrderChange { conn ->
                    after.clear()

                    locks.forOrder(conn, orderId, OrderLockScope.RELEASE) { locked ->
                        val owner = locked.order.subscriptionId?.let { subscriptions.getById(it, conn) }

                        if (owner != null && owner.status.isTerminal && locked.order.status == OrderStatus.PENDING) {
                            val moved = service.transition(conn, locked, OrderEvent.Cancel(OrderActor.SYSTEM))

                            after += moved.after

                            if (moved.moved) cancelled++
                        }
                    }
                }

                afterCommit(after)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                logger.warn("the renewal order {} of an ended subscription could not be cancelled, it expires on its own: {}", orderId, t.toString())
            }
        }

        return cancelled
    }

    // ================================================================================================ 09 sections 10.3 and 11 step E: remote queue, gateway poll

    /** The facts of a provider event for the attempt row (the encrypted `providerData` needs this service's cipher). */
    fun factsOf(event: PaymentEvent): AttemptFacts = AttemptFacts.of(event, cipher)

    /** The id of the subscription a provider knows as [gatewaySubscriptionId], `null` when it is not one of its subscriptions. */
    suspend fun subscriptionIdOf(conn: SqlClient, providerId: String, gatewaySubscriptionId: String): Long? =
        subscriptions.getByGatewaySubscription(providerId, gatewaySubscriptionId, conn)?.id

    /** Everything the job needs to ask a gateway about [row] and to tell it to stop: a read-only view with `providerData` decrypted (02 section 5). */
    fun viewOf(row: MarketSubscription): SubscriptionView = subscriptionViewOf(row, cipher)

    /** The stored instrument of [row] for a provider that deletes it when the subscription ends (09 section 10.3, `MERCHANT`); `null` when there is none. */
    fun storedMethodFor(row: MarketSubscription): StoredPaymentMethod? = storedMethodOf(row)

    /**
     * The result of a remote cancel of the queue (09 section 10.3): `DONE`, or one more failure with the next try after `Backoff(30 s, x2, cap 6 h)`; after
     * [REMOTE_CANCEL_MAX_ATTEMPTS] failures the state is `FAILED` (the panel warns, an admin cancels at the gateway by hand) and the timeline gets a row.
     * [error] is the short text of the failure. Compare-and-set on `PENDING`: a result that arrives after another one changes nothing.
     */
    suspend fun settleRemoteCancel(conn: SqlConnection, row: MarketSubscription, succeeded: Boolean, error: String?): Boolean {
        val now = clock.now()

        if (succeeded) return subscriptions.updateRemoteCancel(row.id, RemoteCancelState.PENDING, RemoteCancelState.DONE, row.remoteCancelAttempts, null, now, conn)

        val attempts = row.remoteCancelAttempts + 1

        if (attempts >= REMOTE_CANCEL_MAX_ATTEMPTS) {
            val moved = subscriptions.updateRemoteCancel(row.id, RemoteCancelState.PENDING, RemoteCancelState.FAILED, attempts, null, now, conn)

            if (moved) {
                orderEvents.add(
                    MarketOrderEvent(
                        orderId = row.initialOrderId, type = OrderEventType.SUBSCRIPTION_REMOTE_CANCEL_FAILED, actorType = OrderActorType.SYSTEM,
                        data = JsonObject().put("subscriptionId", row.id).put("providerId", row.providerId).put("attempts", attempts).put("error", error?.take(ERROR_MAX)).encode(),
                        createdAt = now, updatedAt = now
                    ),
                    conn
                )
            }

            return moved
        }

        return subscriptions.updateRemoteCancel(row.id, RemoteCancelState.PENDING, RemoteCancelState.PENDING, attempts, now + REMOTE_BACKOFF.baseDelayMs(attempts), now, conn)
    }

    /** `lastQueriedAt = now` and the next poll of a gateway row (09 section 11 step E): `now + 24 h`, or the period end plus one hour (six hours once it has passed) when that is nearer. */
    suspend fun scheduleNextPoll(conn: SqlConnection, row: MarketSubscription, supported: Boolean) {
        val now = clock.now()
        val end = row.currentPeriodEnd
        val next = when {
            !supported -> null
            end != null && end + SubscriptionTimings.HOUR_MS > now && end + SubscriptionTimings.HOUR_MS < now + SubscriptionTimings.DAY_MS -> end + SubscriptionTimings.HOUR_MS
            end != null && end <= now -> now + 6 * SubscriptionTimings.HOUR_MS
            else -> now + SubscriptionTimings.DAY_MS
        }

        update(conn, row.id, linkedMapOf("lastQueriedAt" to now, "nextQueryAt" to next))
    }

    // ----- SQL

    /** `UPDATE market_subscription SET ... WHERE id = ?` (and `status` when [whereStatus] is given); `true` when a row matched. */
    private suspend fun update(conn: SqlClient, id: Long, sets: Map<String, Any?>, whereStatus: SubscriptionStatus? = null): Boolean {
        if (sets.isEmpty()) return true

        val columns = sets.keys.joinToString(", ") { "`$it` = ?" }
        val values = ArrayList<Any?>(sets.values.map { if (it is Boolean) (if (it) 1 else 0) else it }).also { it += clock.now(); it += id }
        val guard = if (whereStatus != null) " AND `status` = '${whereStatus.name}'" else ""

        return conn.preparedQuery("UPDATE ${table("market_subscription")} SET $columns, `updatedAt` = ? WHERE `id` = ?$guard")
            .execute(Tuple.from(values)).coAwait().rowCount() > 0
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SubscriptionService::class.java)

        const val WEBHOOK_STARTED = SubscriptionStateMachine.WEBHOOK_STARTED
        const val WEBHOOK_RENEWED = SubscriptionStateMachine.WEBHOOK_RENEWED
        const val WEBHOOK_CANCELLED = SubscriptionStateMachine.WEBHOOK_CANCELLED
        const val WEBHOOK_EXPIRED = SubscriptionStateMachine.WEBHOOK_EXPIRED

        /** `lastError` of the renewal row of a period a gateway reported as declined (09 section 9.1). */
        const val GATEWAY_DECLINED = "GATEWAY_DECLINED"

        /** `lastError` when a failed charge carried no code, and the width of the column (`VARCHAR(512)`). */
        const val CHARGE_FAILED = "CHARGE_FAILED"
        private const val ERROR_MAX = 512

        /** The order event written when the activation fell back from the offered recurring mode to `MANUAL` (09 section 4.4 step 1). */
        const val DOWNGRADE_NOTE = "provider returned no recurring data; subscription renews manually"

        const val IDEM_PREFIX = "renewal:"
        private const val ACCESS_TOKEN_BYTES = 20
        private const val MAX_ID_TRIES = 8
        private const val PAID_FACTS_MAX = 512
        private const val CLEANUP_BATCH = 50
        private const val MANAGE_PATH = "/profile/subscriptions"

        /** The lease of a charge on `nextChargeAt` and `renewal.nextAttemptAt`: a crashed run is noticed after this long, never charged twice (09 section 8.3). */
        const val CHARGE_LEASE_MS = 15 * SubscriptionTimings.MINUTE_MS

        /** The renewal order of a merchant charge lives `subscriptionGraceDays + 8` days after the period start (09 section 8.3). */
        private const val MERCHANT_ORDER_EXTRA_DAYS = 8

        /** Step F: a `PENDING` row is closed 30 days after its order was released (09 section 4.3). */
        const val PENDING_TIMEOUT_MS = 30 * SubscriptionTimings.DAY_MS

        /** After this many failed remote cancels the queue gives up (`FAILED`, 09 section 10.3). */
        const val REMOTE_CANCEL_MAX_ATTEMPTS = 20
        private val REMOTE_BACKOFF = Backoff(baseMs = 30_000L, factor = 2.0, capMs = 6 * SubscriptionTimings.HOUR_MS, jitter = 0.0)

        private val CLOSED_UNPAID = setOf(OrderStatus.EXPIRED, OrderStatus.CANCELLED, OrderStatus.FAILED)
        private val OPEN_ATTEMPTS = setOf(PaymentStatus.CREATED, PaymentStatus.PENDING, PaymentStatus.PROCESSING)

        const val STORED_METHOD_MISSING = "STORED_METHOD_MISSING"
        const val STORED_METHOD_EXPIRED = "STORED_METHOD_EXPIRED"
        const val PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE"
        const val DUPLICATE_FAILURE = "DUPLICATE_FAILURE"

        /** `lastError` values that say the charge never reached a decision (09 section 9.4: the end reason is then `PROVIDER_UNAVAILABLE`). */
        private val TECHNICAL_ERRORS = setOf("CONFIGURATION", "AUTHENTICATION", "IP_NOT_ALLOWED", "GATEWAY_UNREACHABLE", "RATE_LIMITED", "INVALID_REQUEST", "NOT_FOUND", PROVIDER_UNAVAILABLE)

        /** The order event written when `chargeRecurring` said the provider cannot charge a stored method (09 section 8.3). */
        const val UNSUPPORTED_NOTE = "provider cannot charge a stored method; subscription renews manually"

        /** Why [prepareCharge] charged nothing. */
        const val SKIP_GONE = "GONE"
        const val SKIP_NOT_CHARGEABLE = "NOT_CHARGEABLE"
        const val SKIP_NOT_DUE = "NOT_DUE"
        const val SKIP_NOT_RETRYABLE = "NOT_RETRYABLE"
        const val SKIP_NO_CHARGE_NEEDED = "NO_CHARGE_NEEDED"
        const val SKIP_TEST_MODE = "TEST_MODE"
        const val SKIP_BLOCKED = "BLOCKED"
        const val SKIP_PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE"
        const val SKIP_NO_STORED_METHOD = "NO_STORED_METHOD"

        private val ALWAYS_MANUAL = setOf(
            com.panomc.plugins.market.core.pricing.MethodInput.CREDITS, com.panomc.plugins.market.core.order.OrderTimings.FREE_PROVIDER,
            com.panomc.plugins.market.core.order.OrderTimings.BANK_TRANSFER_PROVIDER
        )

        /** The width of `market_subscription.storedMethodLabel` (01 section 10.1, `VARCHAR(64)`); `market_payment.methodDetail` is wider (128), so a longer text is legal there. */
        private const val LABEL_MAX = 64

        /** [text] cut to [max] characters (code points: a surrogate pair is never split), `null` stays `null`. */
        private fun clip(text: String?, max: Int): String? {
            if (text == null || text.codePointCount(0, text.length) <= max) return text

            return text.substring(0, text.offsetByCodePoints(0, max))
        }
        private const val DEFAULT_LOCALE = "en-US"

        private val SubscriptionStatus.isTerminal: Boolean
            get() = this == SubscriptionStatus.EXPIRED || this == SubscriptionStatus.CANCELLED || this == SubscriptionStatus.COMPLETED

        /** The billing interval of a subscription product, `null` for a unit a subscription cannot have (`MINUTE`, `HOUR`) or no unit. */
        fun intervalUnitOf(unit: PeriodUnit?): SubscriptionIntervalUnit? = when (unit) {
            PeriodUnit.DAY -> SubscriptionIntervalUnit.DAY
            PeriodUnit.WEEK -> SubscriptionIntervalUnit.WEEK
            PeriodUnit.MONTH -> SubscriptionIntervalUnit.MONTH
            PeriodUnit.YEAR -> SubscriptionIntervalUnit.YEAR
            else -> null
        }
    }
}

/**
 * The subscription side of the order transitions (09 section 2: `onOrderPaid`, `onOrderClosedUnpaid`) as a [ForeignEffects] router: O2 / O4 hand
 * `SubscriptionOnOrderPaid` and O5 to O8 `SubscriptionOnClosedUnpaid` to [SubscriptionService], every other effect goes to [next]. [service] is a
 * provider so the order service can be built before the subscription service exists (the composition root passes a lazy lookup).
 */
class SubscriptionEffects(
    private val service: () -> SubscriptionService,
    private val next: ForeignEffects = ForeignEffects.PENDING_SLICES
) : ForeignEffects {
    override suspend fun apply(conn: SqlConnection, locked: LockedOrder, effect: OrderEffect) {
        when (effect) {
            is OrderEffect.SubscriptionOnOrderPaid -> service().onOrderPaid(conn, locked)

            is OrderEffect.SubscriptionOnClosedUnpaid -> service().onOrderClosedUnpaid(conn, locked)

            // a renewal order owns no entitlement: the subscription's own one runs on, the `RENEW` rows are linked to it (09 section 8.4 step 6)
            is OrderEffect.GrantEntitlements -> if (locked.order.source != OrderSource.RENEWAL) next.apply(conn, locked, effect)

            else -> next.apply(conn, locked, effect)
        }
    }
}

/**
 * 09 section 8.5 as a [PaidGuard]: a payment that would complete a renewal order of a closed subscription goes to review (`LATE`) instead of O2, and a
 * gateway-managed row has its remote cancel queued again so the gateway stops billing. Nothing for any other order.
 */
class SubscriptionClosedGuard(private val service: () -> SubscriptionService) : PaidGuard {
    override suspend fun divert(conn: SqlConnection, locked: LockedOrder, order: MarketOrder): PaidDiversion? {
        val subscription = service()
        val reason = subscription.reviewReasonFor(conn, order) ?: return null

        order.subscriptionId?.let { subscription.requeueRemoteCancel(conn, it) }

        // 09 section 8.5: the money of a closed subscription's renewal goes back at once when the store's switch is on and the provider can refund
        return PaidDiversion(reason, NOTE, refundAtOnce = true)
    }

    companion object {
        const val NOTE = "subscription closed"
    }
}

/**
 * The `SubscriptionUpdated` half of the inbound sink (09 section 7): the event is applied in its own transaction under the subscription's lock set, every
 * other kind goes to [next] (refunds, disputes, renewals and failed renewals stay with the slices that own them, until then they make their request
 * `FAILED` and replayable, never dropped).
 */
class SubscriptionEventSink(
    private val db: com.panomc.plugins.market.db.tx.MarketDb,
    private val service: () -> SubscriptionService,
    private val next: PaymentEventSink = PaymentEventSink.UNHANDLED,
    /** The payment service that applies the success of a renewal (tx B of 09 section 8.2) and runs the steps after a commit; `null` makes a renewal event fail (replayable). */
    private val payments: () -> PaymentService? = { null }
) : PaymentEventSink {
    /** The same sink with the payment service that applies renewals (the composition root builds the sink before the payment service exists). */
    fun withPayments(payments: () -> PaymentService?): SubscriptionEventSink = SubscriptionEventSink(db, service, next, payments)

    override suspend fun apply(event: PaymentEvent, attempt: MarketPayment?, context: InboundEventContext) {
        when (event) {
            // the order may change between the unlocked read and the lock (a payment on the same order): the whole event runs again on the new state
            is PaymentEvent.SubscriptionUpdated -> {
                db.txRestartingOnOrderChange { conn -> service().onGatewayEvent(conn, context.providerId, event) }
                cancelOrphans(event.state.gatewaySubscriptionId, context.providerId)
            }

            is PaymentEvent.SubscriptionRenewed -> {
                // without a payment service the success cannot be applied: nothing is written, the event goes on (and stays replayable)
                val paymentService = payments() ?: return next.apply(event, attempt, context)
                val prepared = db.txRestartingOnOrderChange { conn -> service().onGatewayRenewed(conn, context.providerId, event) }

                if (prepared is SubscriptionService.RenewedOutcome.Prepared) {
                    paymentService.applyEvent(prepared.orderId, prepared.attemptId, PaymentEventMapper.attemptEvent(prepared.event)!!, service().factsOf(prepared.event))
                    cancelOrphans(prepared.subscriptionId)
                }
            }

            is PaymentEvent.SubscriptionPaymentFailed -> {
                db.txRestartingOnOrderChange { conn -> service().onGatewayPaymentFailed(conn, context.providerId, event) }
                cancelOrphans((event.target as? com.panomc.plugins.market.spi.payment.PaymentTarget.Subscription)?.gatewaySubscriptionId, context.providerId)
            }

            else -> next.apply(event, attempt, context)
        }
    }

    private suspend fun cancelOrphans(gatewaySubscriptionId: String?, providerId: String) {
        val id = gatewaySubscriptionId?.let { db.tx { conn -> service().subscriptionIdOf(conn, providerId, it) } } ?: return

        cancelOrphans(id)
    }

    /** The unpaid renewal orders of an ended subscription are cancelled after the transaction that ended it (09 section 10.5 step 2). */
    private suspend fun cancelOrphans(subscriptionId: Long) {
        val paymentService = payments() ?: return

        try {
            service().cancelClosedRenewalOrders(db, { after -> paymentService.runAfterCommit(after) }, subscriptionId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.warn("the renewal orders of subscription {} could not be cleaned up: {}", subscriptionId, t.toString())
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(SubscriptionEventSink::class.java)
    }
}

/** `ctx.payments.subscriptionByGatewayId` (02 section 5): the read-only view of a subscription a provider may see, `providerData` decrypted. */
fun subscriptionViewOf(row: MarketSubscription, cipher: SecretCipher): SubscriptionView = SubscriptionView(
    id = row.id, status = row.status.name, gatewaySubscriptionId = row.gatewaySubscriptionId, gatewayCustomerId = row.gatewayCustomerId,
    price = Money(row.price, row.currency), intervalUnit = IntervalUnit.valueOf(row.intervalUnit.name), intervalCount = row.intervalCount,
    currentPeriodEnd = row.currentPeriodEnd, providerData = row.providerData?.let { stored -> cipher.decrypt(stored)?.let { runCatching { JsonObject(it) }.getOrNull() } },
    testMode = row.testMode
)
