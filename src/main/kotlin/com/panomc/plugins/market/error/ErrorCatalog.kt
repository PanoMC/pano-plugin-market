package com.panomc.plugins.market.error

import com.panomc.platform.model.Error

/**
 * Every error class of the market plugin with a sample instance (04 section 11). The `ErrorCatalogTest` compares
 * this list to the catalogue table of the design (code, HTTP status, extras) and to the classes of this package, so
 * a code cannot be added, renamed or given another status without the table being touched.
 */
object ErrorCatalog {
    class Entry(val type: Class<out Error>, val sample: () -> Error) {
        val code: String get() = sample().getErrorCode()
        val status: Int get() = sample().getStatusCode()
    }

    private inline fun <reified T : Error> e(noinline sample: () -> T) = Entry(T::class.java, sample)

    val entries: List<Entry> = listOf(
        // existing
        e { CodeAlreadyExists() },
        e { SlugAlreadyExists() },
        e { InvalidCategoryMove() },
        e { InvalidPassword() },
        e { PaymentMethodNotConfigured() },
        e { ExchangeRateFetchFailed() },
        // 400
        e { EmptyCart() },
        e { InvalidCart(mapOf("line-1" to listOf("OUT_OF_STOCK"))) },
        e { InvalidCoupon("CODE_NOT_FOUND") },
        e { InvalidCreatorCode("CODE_NOT_FOUND") },
        e { InvalidGiftCode("CODE_NOT_FOUND") },
        e { InvalidRecipient() },
        e { MinimumOrderAmountNotReached(10.0) },
        e { LegalAcceptanceRequired(1L) },
        e { BuyerInfoRequired(listOf("name")) },
        e { ShippingAddressRequired(listOf("line1")) },
        e { ShippingUnavailable("NO_ZONE") },
        e { PaymentMethodUnavailable("TEST_MODE") },
        e { SubscriptionMustBeAlone() },
        e { InsufficientCredits(1.0, 1.0) },
        e { InvalidOrderTransition("refund", "reason") },
        e { InvalidRefundAmount(1.0, 1.0, 0.0) },
        e { RefundNotSupported() },
        e { CascadeDecisionRequired() },
        e { StatusQueryNotSupported() },
        e { InvalidProviderSettings(mapOf("apiKey" to "REQUIRED")) },
        e { InvalidSettings(mapOf("vatPercent" to "OUT_OF_RANGE")) },
        e { InvalidProduct(mapOf("name" to "REQUIRED")) },
        e { InvalidWebhookUrl("SCHEME") },
        e { InvalidCreditAmount("MIN", 1.0, 2.0) },
        e { InvalidPayoutAmount(1.0) },
        e { CreatorHasNoAccount() },
        e { PublicUrlRequired() },
        e { ReservedSlug() },
        e { InvalidBlock("VALUE") },
        e { InvalidShipment(mapOf("trackingNumber" to "REQUIRED")) },
        e { InvalidShipmentTransition("DELIVERED", "PENDING") },
        e { InvalidMailKind() },
        e { MailRecipientRequired() },
        e { InvalidInvoiceSequence() },
        // 403
        e { BuyerBlocked() },
        // 409
        e { OutOfStock(listOf("line-1")) },
        e { PurchaseLimitReached(1L, 2) },
        e { CooldownActive(1L, 30) },
        e { ProductRequirementNotMet(1L) },
        e { PriceChanged(mapOf("total" to 1.0)) },
        e { OrderNotPayable() },
        e { OrderNotCancellable() },
        e { OrderNotShippable("NO_ADDRESS") },
        e { SubscriptionNotCancellable() },
        e { SubscriptionNotResumable() },
        e { SubscriptionNotRetryable() },
        e { SubscriptionNotManageable() },
        e { DeliveryNotRetryable() },
        e { DeliveryNotCancellable() },
        e { ShipmentNotCancellable("SHIPPED") },
        e { InvalidState("PAID") },
        e { IdempotencyConflict() },
        e { ProviderUnavailable("UNAVAILABLE") },
        e { CategoryInUse() },
        e { BlockAlreadyExists() },
        e { CreditsDisabled() },
        e { MailDisabled() },
        e { MailNotApplicable() },
        e { InvoiceNotIssuable() },
        // 429
        e { TooManyRequests(30) },
        e { CodeAttemptsLocked(30) },
        // 500
        e { InvoiceRenderFailed() },
        // 502
        e { PaymentProviderError("NETWORK") },
        e { ShippingProviderError("NETWORK", 1L) },
        e { MailSendFailed() },
        // 503
        e { StoreDisabled() },
        e { StoreUnavailable() },
        e { StoreBusy() }
    )
}
