package com.panomc.plugins.market.service

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.delivery.DeliveryError
import com.panomc.plugins.market.core.order.OrderEffect
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
    private val webhooks: SubscriptionWebhooks = SubscriptionWebhooks.NONE
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

    private fun pendingPlanOf(row: MarketSubscription): PendingPlan? {
        if (row.status != SubscriptionStatus.PENDING) return null

        return PendingPlan(
            row.id, row.productId, row.variantId, row.productName, RecurringPlan(row.currency, IntervalUnit.valueOf(row.intervalUnit.name), row.intervalCount, row.maxCycles)
        )
    }

    /** `StartPaymentRequest.subscription` (09 section 4.2): the plan of the pending row, only for an automatic mode and the provider the row was made for. */
    override suspend fun planFor(order: MarketOrder, sqlClient: SqlClient): SubscriptionPlan? {
        val id = order.subscriptionId ?: return null

        if (order.source == OrderSource.RENEWAL) return null

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

        // a paid renewal (09 section 8.4) is MK-122's: a renewal order cannot be completed without extending the period, so the transition stops
        if (order.source == OrderSource.RENEWAL) throw EffectNotOwnedYet(OrderEffect.SubscriptionOnOrderPaid, "the renewal slice (MK-122)")

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

    private class Context(val order: MarketOrder, val actor: OrderActorType)

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

            SubEffect.RecordRenewalError -> recordGatewayDecline(conn, id, now)

            SubEffect.MarkRemoteCancelDone -> update(conn, id, linkedMapOf("remoteCancelState" to "DONE", "nextQueryAt" to null))

            SubEffect.QueueRemoteCancel -> update(conn, id, linkedMapOf("remoteCancelState" to "PENDING", "remoteCancelAttempts" to 0, "nextQueryAt" to now))

            SubEffect.ActivateFromPayment -> activate(conn, id, context, now)

            is SubEffect.ClosePending -> close(conn, id, effect, now)

            is SubEffect.EndSubscription -> end(conn, id, effect, context, now)

            is SubEffect.QueueMail -> queueMail(conn, id, effect, context)

            is SubEffect.QueueWebhook -> queueWebhook(conn, id, effect.event, context)

            is SubEffect.AddOrderEvent -> addOrderEvent(conn, id, effect.type, context)

            is SubEffect.ApplyRenewal, SubEffect.QueueRenewDeliveries -> throw SubscriptionEffectNotOwned(effect, "the renewal slice (MK-122)")

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
        renewals.getByPeriod(id, row.cycleCount, conn)?.takeIf { it.status == RenewalStatus.PENDING }?.let { renewal ->
            renewals.transition(renewal.id, RenewalStatus.PENDING, if (effect.renewalRow == com.panomc.plugins.market.core.subscription.RenewalDisposition.FAILED) RenewalStatus.FAILED else RenewalStatus.SKIPPED, now, conn)

            if (renewal.orderId != null) logger.warn("subscription {}: the open renewal order {} is not cancelled by the ending (MK-122), a later payment goes to review", id, renewal.orderId)
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

    private suspend fun recordGatewayDecline(conn: SqlConnection, id: Long, now: Long) {
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

        renewals.recordAttempt(renewalId, null, GATEWAY_DECLINED, now, conn)
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
            MailKind.SUBSCRIPTION_PAYMENT_FAILED -> params.put("reason", effect.reason).put("graceEndsAt", row.graceEndsAt)
            MailKind.SUBSCRIPTION_CANCELLED -> params.put("endsAt", row.currentPeriodEnd)
            MailKind.SUBSCRIPTION_ENDED -> params.put("endReason", row.endReason).put("endedAt", row.endedAt)
            else -> Unit
        }

        outbox.enqueue(
            conn, kind, MailRefType.SUBSCRIPTION, id, refKey = key, orderId = row.initialOrderId, userId = row.userId, recipient = recipient,
            locale = order?.locale ?: DEFAULT_LOCALE, params = params
        )
    }

    private suspend fun addOrderEvent(conn: SqlConnection, id: Long, type: String, context: Context, data: JsonObject = JsonObject()) {
        val row = subscriptions.getById(id, conn) ?: return
        val now = clock.now()

        orderEvents.add(
            MarketOrderEvent(
                orderId = row.initialOrderId, type = OrderEventType.valueOf(type), actorType = context.actor,
                data = data.put("subscriptionId", id).put("status", row.status.name).encode(), createdAt = now, updatedAt = now
            ),
            conn
        )
    }

    private suspend fun queueWebhook(conn: SqlConnection, id: Long, event: String, context: Context) {
        val row = subscriptions.getById(id, conn) ?: return
        val subject = when (event) {
            WEBHOOK_STARTED -> "sub:$id:started"
            WEBHOOK_CANCELLED -> "sub:$id:cancelled:${row.cancelRequestedAt ?: row.endedAt ?: clock.now()}"
            WEBHOOK_EXPIRED -> "sub:$id:ended"
            else -> error("$event is not a subscription event of this slice")
        }
        val data = JsonObject().put("subscription", subscriptionJson(row))

        if (event == WEBHOOK_STARTED) {
            val order = orders.getById(row.initialOrderId, conn)

            if (order != null) data.put("order", JsonObject().put("id", order.id).put("publicId", order.publicId).put("total", EventPayloads.money(order.totalPrice)).put("currency", order.currency))
        }

        webhooks.emit(conn, event, subject, row.initialOrderId, data, row.testMode)
    }

    private fun subscriptionJson(s: MarketSubscription): JsonObject = JsonObject()
        .put("id", s.id).put("status", s.status.name).put("mode", s.mode.name).put("productId", s.productId).put("variantId", s.variantId)
        .put("productName", s.productName).put("playerUsername", s.playerUsername).put("userId", s.userId).put("price", EventPayloads.money(s.price))
        .put("currency", s.currency).put("intervalUnit", s.intervalUnit.name).put("intervalCount", s.intervalCount).put("cycleCount", s.cycleCount)
        .put("maxCycles", s.maxCycles).put("currentPeriodStart", s.currentPeriodStart).put("currentPeriodEnd", s.currentPeriodEnd)
        .put("cancelAtPeriodEnd", s.cancelAtPeriodEnd).put("endReason", s.endReason).put("endedAt", s.endedAt).put("providerId", s.providerId)
        .put("testMode", s.testMode)

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
        const val WEBHOOK_CANCELLED = SubscriptionStateMachine.WEBHOOK_CANCELLED
        const val WEBHOOK_EXPIRED = SubscriptionStateMachine.WEBHOOK_EXPIRED

        /** `lastError` of the renewal row of a period a gateway reported as declined (09 section 9.1). */
        const val GATEWAY_DECLINED = "GATEWAY_DECLINED"

        /** The order event written when the activation fell back from the offered recurring mode to `MANUAL` (09 section 4.4 step 1). */
        const val DOWNGRADE_NOTE = "provider returned no recurring data; subscription renews manually"

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

        return PaidDiversion(reason, NOTE)
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
    private val next: PaymentEventSink = PaymentEventSink.UNHANDLED
) : PaymentEventSink {
    override suspend fun apply(event: PaymentEvent, attempt: MarketPayment?, context: InboundEventContext) {
        if (event !is PaymentEvent.SubscriptionUpdated) return next.apply(event, attempt, context)

        // the order may change between the unlocked read and the lock (a payment on the same order): the whole event runs again on the new state
        db.txRestartingOnOrderChange { conn -> service().onGatewayEvent(conn, context.providerId, event) }
    }
}

/** `ctx.payments.subscriptionByGatewayId` (02 section 5): the read-only view of a subscription a provider may see, `providerData` decrypted. */
fun subscriptionViewOf(row: MarketSubscription, cipher: SecretCipher): SubscriptionView = SubscriptionView(
    id = row.id, status = row.status.name, gatewaySubscriptionId = row.gatewaySubscriptionId, gatewayCustomerId = row.gatewayCustomerId,
    price = Money(row.price, row.currency), intervalUnit = IntervalUnit.valueOf(row.intervalUnit.name), intervalCount = row.intervalCount,
    currentPeriodEnd = row.currentPeriodEnd, providerData = row.providerData?.let { stored -> cipher.decrypt(stored)?.let { runCatching { JsonObject(it) }.getOrNull() } },
    testMode = row.testMode
)
