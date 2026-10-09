package com.panomc.plugins.market.error

import com.panomc.platform.model.Error

// HTTP 403, 409, 429, 500, 502 and 503 codes of 04 section 11 (400 is in BadRequestErrors.kt).

// ---- 403

/** The body never says which subject (IP, account, e-mail) matched (11 section 18). */
class BuyerBlocked : Error("BUYER_BLOCKED", 403)

// ---- 409

/** [lines]: the lines that are out of stock. */
class OutOfStock(lines: List<Any?> = emptyList()) : Error("OUT_OF_STOCK", 409, extras = mapOf("lines" to lines))

class PurchaseLimitReached(productId: Long, limit: Number) :
    Error("PURCHASE_LIMIT_REACHED", 409, extras = mapOf("productId" to productId, "limit" to limit))

class CooldownActive(productId: Long, retryAfter: Number) :
    Error("COOLDOWN_ACTIVE", 409, extras = mapOf("productId" to productId, "retryAfter" to retryAfter))

class ProductRequirementNotMet(productId: Long) : Error("PRODUCT_REQUIREMENT_NOT_MET", 409, extras = mapOf("productId" to productId))

/** [quote]: the fresh quote the client must confirm. */
class PriceChanged(quote: Any?) : Error("PRICE_CHANGED", 409, extras = mapOf("quote" to quote))

class OrderNotPayable : Error("ORDER_NOT_PAYABLE", 409)

class OrderNotCancellable : Error("ORDER_NOT_CANCELLABLE", 409)

class OrderNotShippable(reason: String) : Error("ORDER_NOT_SHIPPABLE", 409, extras = mapOf("reason" to reason))

class SubscriptionNotCancellable : Error("SUBSCRIPTION_NOT_CANCELLABLE", 409)

class SubscriptionNotResumable : Error("SUBSCRIPTION_NOT_RESUMABLE", 409)

class SubscriptionNotRetryable : Error("SUBSCRIPTION_NOT_RETRYABLE", 409)

class SubscriptionNotManageable : Error("SUBSCRIPTION_NOT_MANAGEABLE", 409)

class DeliveryNotRetryable : Error("DELIVERY_NOT_RETRYABLE", 409)

class DeliveryNotCancellable : Error("DELIVERY_NOT_CANCELLABLE", 409)

class ShipmentNotCancellable(reason: String) : Error("SHIPMENT_NOT_CANCELLABLE", 409, extras = mapOf("reason" to reason))

/** The generic "row is not in a state that allows this action" (04 section 11). */
class InvalidState(state: String) : Error("INVALID_STATE", 409, extras = mapOf("state" to state))

class IdempotencyConflict : Error("IDEMPOTENCY_CONFLICT", 409)

class ProviderUnavailable(state: String) : Error("PROVIDER_UNAVAILABLE", 409, extras = mapOf("state" to state))

class CategoryInUse : Error("CATEGORY_IN_USE", 409)

class BlockAlreadyExists : Error("BLOCK_ALREADY_EXISTS", 409)

class CreditsDisabled : Error("CREDITS_DISABLED", 409)

class MailDisabled : Error("MAIL_DISABLED", 409)

class MailNotApplicable : Error("MAIL_NOT_APPLICABLE", 409)

/** [reason]: `NOT_PAID`, `EXTERNAL_PRICING`, `ZERO_TOTAL`, `TOTAL_MISMATCH`, ... (12 section 9.3). */
class InvoiceNotIssuable(reason: String? = null) : Error("INVOICE_NOT_ISSUABLE", 409, extras = extrasOf("reason" to reason))

// ---- 429 (the bodies never say which subject triggered them, 11 section 18)

class TooManyRequests(retryAfter: Number) : Error("TOO_MANY_REQUESTS", 429, extras = mapOf("retryAfter" to retryAfter))

class CodeAttemptsLocked(retryAfter: Number) : Error("CODE_ATTEMPTS_LOCKED", 429, extras = mapOf("retryAfter" to retryAfter))

// ---- 500

class InvoiceRenderFailed : Error("INVOICE_RENDER_FAILED", 500)

// ---- 502 (never carries the gateway's own text)

/**
 * [code]: a `ProviderErrorCode`. Checkout answers with the order that was created (it stays `PENDING`, 04 section 3): [order]
 * is its `OrderView` and [orderToken] its access token, so the buyer can pick another method.
 */
class PaymentProviderError(code: String, order: Any? = null, orderToken: String? = null) :
    Error("PAYMENT_PROVIDER_ERROR", 502, extras = extrasOf("code" to code, "order" to order, "orderToken" to orderToken))

class ShippingProviderError(code: String, shipmentId: Long? = null) :
    Error("SHIPPING_PROVIDER_ERROR", 502, extras = extrasOf("code" to code, "shipmentId" to shipmentId))

class MailSendFailed : Error("MAIL_SEND_FAILED", 502)

// ---- 503

/** `storeEnabled = false` (04 section 1). */
class StoreDisabled : Error("STORE_DISABLED", 503)

/** The runtime state is not READY (00 section 8.9). */
class StoreUnavailable : Error("STORE_UNAVAILABLE", 503)

/** A transaction ran out of retries on a deadlock; the response carries `Retry-After: 2` (set by the base classes). */
class StoreBusy : Error("STORE_BUSY", 503)
