package com.panomc.plugins.market.spi.payment

import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.TestModeSupport
import com.panomc.plugins.market.spi.common.WebhookSetup

/**
 * What one configuration of a provider can do (02 section 5.1). A pure function of the settings. A plugin
 * constructs it, so every field is an optional `var` with a default: a new capability is a new `var`.
 */
class PaymentCapabilities {
    /** Null = any supported currency. */
    var currencies: Set<String>? = null

    /** NONE, FULL_ONLY, PARTIAL, PER_LINE. */
    var refund: RefundSupport = RefundSupport.NONE

    /** NONE, GATEWAY_MANAGED, MERCHANT_INITIATED. */
    var recurring: RecurringSupport = RecurringSupport.NONE
    var recurringCurrencies: Set<String>? = null

    /** Null = all. */
    var recurringIntervals: Set<IntervalUnit>? = null

    /** Sipay: 121. */
    var recurringMaxCycles: Int? = null

    /** Paymentwall: 3. */
    var recurringMinIntervalDays: Int? = null

    /** Mollie, Paymentwall: 365. */
    var recurringMaxIntervalDays: Int? = null

    /** `resumeSubscription` is implemented. */
    var recurringResume: Boolean = false

    /** `retrySubscriptionCharge` is implemented. */
    var recurringRetry: Boolean = false

    /** `subscriptionPortal` is implemented. */
    var recurringPortal: Boolean = false

    /** `queryPayment` is implemented. */
    var statusQuery: Boolean = false

    /** `cancelPayment` is implemented. */
    var cancelPending: Boolean = false

    /** The gateway reports chargebacks. */
    var disputeEvents: Boolean = false

    /** Instalment commission etc. (00 section 6.9): the buyer may pay more than the order amount. */
    var buyerMayPayMore: Boolean = false

    /** MARKET, GATEWAY_ADDS_TAX, GATEWAY_CATALOG. */
    var priceAuthority: PriceAuthority = PriceAuthority.MARKET

    /** GATEWAY only together with GATEWAY_CATALOG (Tebex deliveryBy=TEBEX). */
    var fulfillment: FulfillmentAuthority = FulfillmentAuthority.MARKET

    /** FLAG (uses ctx.testMode), DERIVED (from keys), NONE. */
    var testMode: TestModeSupport = TestModeSupport.FLAG

    /** DERIVED: what the configured keys are. */
    var derivedTestMode: Boolean? = null
    var requiredBuyerFields: Set<BuyerField> = emptySet()

    /** Gateway hard limits (admin limits are separate). */
    var minAmount: Money? = null
    var maxAmount: Money? = null
    var physicalGoods: Boolean = true
    var guests: Boolean = true
    var mixedCredit: Boolean = true

    /** Attempt lifetime in minutes; null = the store's order expiry. */
    var paymentWindowMinutes: Int? = null

    /** May stay PROCESSING for days. */
    var longPending: Boolean = false

    /** `onFulfillment` is called (Paymentwall delivery confirmation). */
    var fulfillmentUpdates: Boolean = false

    /** PER_PAYMENT, MANUAL_URL, AUTO_REGISTER, NONE. */
    var webhookSetup: WebhookSetup = WebhookSetup.PER_PAYMENT

    /** Refuse to enable on a site that is not publicly reachable over https. */
    var needsPublicUrl: Boolean = false
}

/** Checkout fields a method makes mandatory. New values only at the end. */
enum class BuyerField { EMAIL, FIRST_NAME, LAST_NAME, PHONE, COUNTRY, BILLING_ADDRESS, SHIPPING_ADDRESS, IDENTITY_NUMBER }

enum class IntervalUnit { DAY, WEEK, MONTH, YEAR }

enum class RefundSupport { NONE, FULL_ONLY, PARTIAL, PER_LINE }

enum class RecurringSupport { NONE, GATEWAY_MANAGED, MERCHANT_INITIATED }

enum class PriceAuthority { MARKET, GATEWAY_ADDS_TAX, GATEWAY_CATALOG }

enum class FulfillmentAuthority { MARKET, GATEWAY }

enum class QueryReason { RETURN_PAGE, RECONCILE, PANEL }
