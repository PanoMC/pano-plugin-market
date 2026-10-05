package com.panomc.plugins.market.spi.payment

import com.panomc.plugins.market.spi.common.Money
import io.vertx.core.json.JsonObject

/** How market finds the attempt (or subscription) an event is about (02 section 7.2). */
sealed class PaymentTarget {
    class Attempt(val attemptId: Long) : PaymentTarget()

    class Reference(val reference: String) : PaymentTarget()

    class GatewayTransaction(val gatewayTransactionId: String) : PaymentTarget()

    class GatewayRef(val name: String, val value: String) : PaymentTarget()

    class Subscription(val gatewaySubscriptionId: String) : PaymentTarget()
}

/**
 * Normalized facts a provider reports (webhook, return, query). Every event may arrive any number of times; market
 * applies them idempotently (02 section 7.4). A plugin constructs them: required data in the constructor, optional
 * data as `var`.
 */
sealed class PaymentEvent(val target: PaymentTarget) {
    var occurredAt: Long? = null

    /** Attaches or replaces the attempt's primary gateway id. */
    var gatewayTransactionId: String? = null

    /** Merged into the attempt's refs. */
    var gatewayRefs: Map<String, String> = emptyMap()

    /** Replaces the attempt's provider data when non-null. */
    var providerData: JsonObject? = null

    /** Admin-facing. */
    var note: String? = null

    /**
     * Environment the gateway states for this fact (PayTR test_mode, Stripe livemode, Paymentwall is_test, Mollie
     * mode); null = not stated.
     */
    var testMode: Boolean? = null

    class Succeeded(target: PaymentTarget, val paid: Money) : PaymentEvent(target) {
        var gatewayFee: Money? = null
        var net: Money? = null

        /** For example `"USDC"`. */
        var settlementCurrency: String? = null

        /** For example `"12.340000"`. */
        var settlementAmount: String? = null
        var installments: Int? = null
        var methodDetail: String? = null

        /** orderItemId (or the shipping / fee line id) to gateway line, for per-line refunds. */
        var itemRefs: Map<Long, GatewayLineRef> = emptyMap()

        /** MERCHANT_INITIATED recurring. */
        var storedMethod: StoredPaymentMethod? = null

        /** GATEWAY_MANAGED recurring: activation. */
        var subscription: GatewaySubscriptionState? = null

        /** `priceAuthority != MARKET`. */
        var externalTotals: ExternalTotals? = null
    }

    class Pending(target: PaymentTarget, val reason: PendingReason) : PaymentEvent(target)

    class Failed(target: PaymentTarget, val code: String, val message: String?) : PaymentEvent(target) {
        var final: Boolean = false
    }

    class Cancelled(target: PaymentTarget) : PaymentEvent(target)

    class Expired(target: PaymentTarget) : PaymentEvent(target)

    class NeedsReview(target: PaymentTarget, val reason: ReviewReason) : PaymentEvent(target) {
        var received: Money? = null
    }

    /** `amount` is null when the gateway reports none (Tebex, Paymentwall 220). */
    class RefundUpdated(target: PaymentTarget, val state: RefundState, val amount: Money?) : PaymentEvent(target) {
        var gatewayRefundId: String? = null

        /** Our `RefundRequest.idempotencyKey` when the gateway echoes it. */
        var refundKey: String? = null

        /** Snapshot gateways (Mollie, PayTR, Moka): total refunded so far. */
        var cumulativeRefunded: Money? = null

        /** orderItemId to amount. */
        var lines: Map<Long, Money> = emptyMap()
        var buyerActionUrl: String? = null
    }

    class DisputeUpdated(target: PaymentTarget, val state: DisputeState) : PaymentEvent(target) {
        var gatewayDisputeId: String? = null
        var amount: Money? = null
        var reason: String? = null
    }

    class SubscriptionUpdated(val state: GatewaySubscriptionState) :
        PaymentEvent(PaymentTarget.Subscription(state.gatewaySubscriptionId))

    /**
     * A renewal charge market did not initiate. Market creates the renewal order and attempt from it. It must carry
     * `gatewayTransactionId` (the gateway id of this charge) or `periodStart`: market de-duplicates renewals on them.
     * Never emitted for the first period (that is [Succeeded.subscription]).
     */
    class SubscriptionRenewed(gatewaySubscriptionId: String, val paid: Money) :
        PaymentEvent(PaymentTarget.Subscription(gatewaySubscriptionId)) {
        var periodStart: Long? = null
        var periodEnd: Long? = null
        var gatewayFee: Money? = null
        var net: Money? = null
        var methodDetail: String? = null

        /** `priceAuthority != MARKET` (Tebex, PayNow renewals). */
        var externalTotals: ExternalTotals? = null
    }

    class SubscriptionPaymentFailed(gatewaySubscriptionId: String) : PaymentEvent(PaymentTarget.Subscription(gatewaySubscriptionId)) {
        var attemptCount: Int? = null
        var nextRetryAt: Long? = null
        var final: Boolean = false
    }

    /** Only attaches ids / provider data to the attempt. */
    class ReferencesUpdated(target: PaymentTarget) : PaymentEvent(target)
}

/** New values only at the end; both sides treat an unknown value as OTHER. */
enum class PendingReason { AWAITING_BUYER, AWAITING_CONFIRMATIONS, AWAITING_BANK, FRAUD_REVIEW, AWAITING_CAPTURE, OTHER }

enum class ReviewReason { UNDERPAID, OVERPAID, LATE, WRONG_ASSET, AMOUNT_MISMATCH, CURRENCY_MISMATCH, FRAUD_REVIEW, OTHER }

enum class RefundState { PENDING, SUCCEEDED, FAILED, CANCELLED }

enum class DisputeState { INQUIRY, OPENED, WON, LOST, CLOSED }

enum class GatewaySubscriptionStatus { ACTIVE, PAST_DUE, PAUSED, CANCEL_SCHEDULED, CANCELLED, ENDED }

class GatewaySubscriptionState(val gatewaySubscriptionId: String, val status: GatewaySubscriptionStatus) {
    var gatewayCustomerId: String? = null
    var currentPeriodStart: Long? = null
    var currentPeriodEnd: Long? = null

    /** CANCEL_SCHEDULED: when it stops. */
    var endsAt: Long? = null
    var providerData: JsonObject? = null
}

class StoredPaymentMethod(val token: String) {
    var label: String? = null
    var expiresAt: Long? = null
    var gatewayCustomerId: String? = null
}

/** `charged` = what the gateway booked for that line; stored in `market_order_item.gatewayLineAmount`. */
class GatewayLineRef(val ref: String, val charged: Money)

class ExternalTotals(val total: Money) {
    var tax: Money? = null
    var discount: Money? = null
}
