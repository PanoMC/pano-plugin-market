package com.panomc.plugins.market.spi.payment

import com.panomc.plugins.market.spi.common.ProviderDescriptor
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.common.SettingsSchema
import io.vertx.core.json.JsonObject

/**
 * One payment gateway (02 section 5). Every method added after v1 must have a body: market compiles with
 * `-jvm-default=enable`, so those are real JVM default methods and an older plugin jar keeps loading (02 section 9).
 */
interface PaymentProvider {
    /** `[a-z0-9-]{2,32}`, stable forever. Equals `market_payment_method.methodId`. */
    val id: String
    val descriptor: ProviderDescriptor

    fun settingsSchema(): SettingsSchema

    /** Pure function of the settings (no I/O): what this configuration can do. */
    fun capabilities(settings: ProviderSettings): PaymentCapabilities

    /** Deep validation, may call the gateway. Called on save and before enabling. */
    suspend fun validateSettings(ctx: PaymentContext, settings: ProviderSettings): SettingsValidation = SettingsValidation.ok()

    /** After settings were stored (register remote webhooks, drop caches). The result is shown to the admin. */
    suspend fun onSettingsSaved(ctx: PaymentContext, previous: ProviderSettings?): ActionResult = ActionResult.none()

    /** Runs a settings action (`test-connection`, `register-webhooks`, `import-catalog`). */
    suspend fun runAction(ctx: PaymentContext, actionId: String, input: JsonObject): ActionResult =
        throw ProviderException(ProviderErrorCode.UNSUPPORTED, "unknown action $actionId")

    /**
     * Extra per-product fields this provider needs (Tebex package id), stored in `market_product_provider_meta`.
     * Depends on the settings so a mode switch can hide them.
     */
    fun productMetaSchema(settings: ProviderSettings): SettingsSchema? = null

    /** Can this provider pay this checkout? Pure, no I/O. */
    fun checkEligibility(ctx: PaymentContext, checkout: CheckoutSnapshot): Eligibility = Eligibility.eligible()

    suspend fun startPayment(ctx: PaymentContext, request: StartPaymentRequest): StartPaymentResult

    /** Second step for [StartPaymentResult.Embedded] forms. */
    suspend fun continuePayment(ctx: PaymentContext, request: ContinuePaymentRequest): StartPaymentResult =
        throw ProviderException(ProviderErrorCode.UNSUPPORTED, "continuePayment")

    /** Every inbound HTTP request for this provider: webhook, per-attempt notification, browser return. */
    suspend fun handleInbound(ctx: PaymentContext, request: PaymentInboundRequest): InboundResult

    suspend fun queryPayment(ctx: PaymentContext, request: QueryPaymentRequest): PaymentQueryResult = PaymentQueryResult.unsupported()

    /** Best effort: void or expire a payment the buyer has not completed. */
    suspend fun cancelPayment(ctx: PaymentContext, request: CancelPaymentRequest): CancelPaymentResult = CancelPaymentResult.unsupported()

    suspend fun refund(ctx: PaymentContext, request: RefundRequest): RefundResult =
        throw ProviderException(ProviderErrorCode.UNSUPPORTED, "refund")

    suspend fun queryRefund(ctx: PaymentContext, request: QueryRefundRequest): RefundResult = RefundResult.unknown()

    suspend fun chargeRecurring(ctx: PaymentContext, request: RecurringChargeRequest): RecurringChargeResult =
        throw ProviderException(ProviderErrorCode.UNSUPPORTED, "chargeRecurring")

    /**
     * GATEWAY_MANAGED providers must override it. The default is deliberately not "local only": a gateway
     * subscription that market ends locally while the gateway keeps charging is the worst outcome.
     */
    suspend fun cancelSubscription(ctx: PaymentContext, request: CancelSubscriptionRequest): CancelSubscriptionResult =
        throw ProviderException(ProviderErrorCode.UNSUPPORTED, "cancelSubscription")

    suspend fun resumeSubscription(ctx: PaymentContext, request: ResumeSubscriptionRequest): ResumeSubscriptionResult =
        ResumeSubscriptionResult.unsupported()

    suspend fun querySubscription(ctx: PaymentContext, request: QuerySubscriptionRequest): SubscriptionQueryResult =
        SubscriptionQueryResult.unsupported()

    /** Ask the gateway to retry a failed gateway-managed renewal now (`capabilities.recurringRetry`). */
    suspend fun retrySubscriptionCharge(ctx: PaymentContext, request: QuerySubscriptionRequest): SubscriptionQueryResult =
        SubscriptionQueryResult.unsupported()

    /** Hosted page where the buyer manages or cancels the subscription or replaces the card (`capabilities.recurringPortal`). */
    suspend fun subscriptionPortal(ctx: PaymentContext, request: SubscriptionPortalRequest): SubscriptionPortalResult =
        SubscriptionPortalResult.unsupported()

    /** Only called when `capabilities.fulfillmentUpdates` is true (Paymentwall delivery confirmation). */
    suspend fun onFulfillment(ctx: PaymentContext, update: FulfillmentUpdate) {}
}
