package com.panomc.plugins.market.spi.payment

import com.panomc.plugins.market.spi.common.Address
import com.panomc.plugins.market.spi.common.Money
import io.vertx.core.json.JsonObject

// ---- query -----------------------------------------------------------------------------------------------------

class QueryPaymentRequest(val attempt: PaymentAttemptView, val reason: QueryReason)

/** Facts the gateway reports for an attempt. Build it with [of], [unknown] or [unsupported]. */
class PaymentQueryResult(val events: List<PaymentEvent>) {
    /** Provider hint; market applies its own backoff otherwise. */
    var pollAgainAfterSeconds: Long? = null

    /** The gateway has no record (yet): treated as still pending, never as failed. */
    var unknown: Boolean = false
        internal set

    /** `queryPayment` is not implemented. */
    var unsupported: Boolean = false
        internal set

    companion object {
        fun of(vararg events: PaymentEvent): PaymentQueryResult = PaymentQueryResult(events.toList())

        fun unknown(): PaymentQueryResult = PaymentQueryResult(emptyList()).also { it.unknown = true }

        fun unsupported(): PaymentQueryResult = PaymentQueryResult(emptyList()).also { it.unsupported = true }
    }
}

// ---- cancel ----------------------------------------------------------------------------------------------------

class CancelPaymentRequest(val attempt: PaymentAttemptView)

class CancelPaymentResult private constructor(val cancelled: Boolean, val supported: Boolean) {
    companion object {
        fun cancelled(): CancelPaymentResult = CancelPaymentResult(cancelled = true, supported = true)

        fun notCancellable(): CancelPaymentResult = CancelPaymentResult(cancelled = false, supported = true)

        fun unsupported(): CancelPaymentResult = CancelPaymentResult(cancelled = false, supported = false)
    }
}

// ---- refund ----------------------------------------------------------------------------------------------------

/**
 * PER_LINE: `amount` is the gateway part of that line and never exceeds its `charged - already refunded`. Shipping and
 * fee refunds arrive with the reserved ids [OrderLine.SHIPPING_LINE_ID] / [OrderLine.FEE_LINE_ID]. A provider that
 * sent the gateway one consolidated line must declare PARTIAL, not PER_LINE.
 */
class RefundLine(val orderItemId: Long, val gatewayItemRef: String?, val quantity: Int, val amount: Money)

class RefundRequest(
    val refundId: Long,
    val idempotencyKey: String,
    val attempt: PaymentAttemptView,
    val order: OrderSnapshot,
    /** Gateway part only; the credit part is market's. */
    val amount: Money,
    /**
     * True = refund everything the gateway still holds for this attempt (`attempt.paidAmount - attempt.refundedAmount`),
     * which may exceed `amount` when the buyer paid a surcharge; providers send the gateway's own remaining total.
     */
    val full: Boolean,
    val lines: List<RefundLine>,
    val reason: String?,
    /** Lets providers choose void (same day) versus refund. */
    val paidAt: Long
)

sealed class RefundResult {
    var gatewayRefundId: String? = null
    var providerData: JsonObject? = null

    /** What the gateway actually returned (full refunds on buyerMayPayMore gateways). */
    var refundedAmount: Money? = null

    class Succeeded : RefundResult()

    /** Async gateway, or the buyer must claim the refund (BTCPay). */
    class Pending : RefundResult() {
        var buyerActionUrl: String? = null
    }

    class Failed(val code: String, val message: String?) : RefundResult() {
        var retryable: Boolean = false
    }

    /** `queryRefund`: no answer yet. */
    class Unknown : RefundResult()

    companion object {
        fun unknown(): RefundResult = Unknown()
    }
}

class QueryRefundRequest(
    val refundId: Long,
    val idempotencyKey: String,
    val gatewayRefundId: String?,
    val attempt: PaymentAttemptView,
    val amount: Money
)

// ---- recurring -------------------------------------------------------------------------------------------------

/** MERCHANT_INITIATED: market's SubscriptionJob created a renewal order and attempt and asks the provider to charge. */
class RecurringChargeRequest(
    val attempt: AttemptRef,
    val amount: Money,
    val subscription: SubscriptionView,
    val storedMethod: StoredPaymentMethod,
    val order: OrderSnapshot,
    val buyer: BuyerInfo,
    /** Unique per attempt; market never calls `chargeRecurring` twice for one attempt. */
    val idempotencyKey: String,
    val notifyUrl: String
)

/** Succeeded, Failed or Pending for the attempt. */
class RecurringChargeResult(val events: List<PaymentEvent>)

class CancelSubscriptionRequest(
    val subscription: SubscriptionView,
    val atPeriodEnd: Boolean,
    val reason: String?,
    /** MERCHANT mode: lets the provider delete the stored instrument at the gateway. */
    val storedMethod: StoredPaymentMethod?
)

sealed class CancelSubscriptionResult {
    /** Null = now. */
    class Cancelled(val effectiveAt: Long?) : CancelSubscriptionResult()

    class Scheduled(val endsAt: Long) : CancelSubscriptionResult()

    /** Nothing to cancel remotely (stored-method model). Not accepted for a GATEWAY_MANAGED subscription. */
    class LocalOnly : CancelSubscriptionResult()

    /** Only the buyer can cancel, at the gateway (Tebex Headless): nothing changes locally. */
    class BuyerActionRequired(val url: String) : CancelSubscriptionResult()

    class Failed(val message: String) : CancelSubscriptionResult()

    companion object {
        fun localOnly(): CancelSubscriptionResult = LocalOnly()
    }
}

class ResumeSubscriptionRequest(val subscription: SubscriptionView)

sealed class ResumeSubscriptionResult {
    class Resumed : ResumeSubscriptionResult()

    class Failed(val message: String) : ResumeSubscriptionResult()

    class Unsupported : ResumeSubscriptionResult()

    companion object {
        fun unsupported(): ResumeSubscriptionResult = Unsupported()
    }
}

enum class PortalPurpose { MANAGE, UPDATE_PAYMENT_METHOD, CANCEL }

class SubscriptionPortalRequest(val subscription: SubscriptionView, val returnUrl: String, val purpose: PortalPurpose)

sealed class SubscriptionPortalResult {
    class Redirect(val url: String) : SubscriptionPortalResult()

    /** Served like [StartPaymentResult.Html] (sandboxed). */
    class Html(val document: String) : SubscriptionPortalResult()

    class Unsupported : SubscriptionPortalResult()

    companion object {
        fun unsupported(): SubscriptionPortalResult = Unsupported()
    }
}

class QuerySubscriptionRequest(val subscription: SubscriptionView)

/** Build [unsupported] for a gateway without subscription queries. */
class SubscriptionQueryResult(val events: List<PaymentEvent>) {
    /** `querySubscription` is not implemented. */
    var unsupported: Boolean = false
        internal set

    companion object {
        fun unsupported(): SubscriptionQueryResult = SubscriptionQueryResult(emptyList()).also { it.unsupported = true }
    }
}

/** Only called when `capabilities.fulfillmentUpdates` is true (Paymentwall delivery confirmation). */
class FulfillmentUpdate(
    val attempt: PaymentAttemptView,
    /** PENDING, FULFILLED, FAILED. */
    val status: String,
    val buyer: BuyerInfo,
    val shippingAddress: Address?
)
