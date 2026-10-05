package com.panomc.plugins.market.error

import com.panomc.platform.model.Error

// HTTP 403, 409, 429, 500, 502 and 503 codes of 04 section 11 (400 is in BadRequestErrors.kt).

// ---- 403

/** The body never says which subject (IP, account, e-mail) matched (11 section 18). */
class BuyerBlocked : Error(403)

// ---- 409

/** [lines]: the lines that are out of stock. */
class OutOfStock(lines: List<Any?> = emptyList()) : Error(409, extras = mapOf("lines" to lines))

class PurchaseLimitReached(productId: Long, limit: Number) :
    Error(409, extras = mapOf("productId" to productId, "limit" to limit))

class CooldownActive(productId: Long, retryAfter: Number) :
    Error(409, extras = mapOf("productId" to productId, "retryAfter" to retryAfter))

class ProductRequirementNotMet(productId: Long) : Error(409, extras = mapOf("productId" to productId))

/** [quote]: the fresh quote the client must confirm. */
class PriceChanged(quote: Any?) : Error(409, extras = mapOf("quote" to quote))

class OrderNotPayable : Error(409)

class OrderNotCancellable : Error(409)

class OrderNotShippable(reason: String) : Error(409, extras = mapOf("reason" to reason))

class SubscriptionNotCancellable : Error(409)

class SubscriptionNotResumable : Error(409)

class SubscriptionNotRetryable : Error(409)

class SubscriptionNotManageable : Error(409)

class DeliveryNotRetryable : Error(409)

class DeliveryNotCancellable : Error(409)

class ShipmentNotCancellable(reason: String) : Error(409, extras = mapOf("reason" to reason))

/** The generic "row is not in a state that allows this action" (04 section 11). */
class InvalidState(state: String) : Error(409, extras = mapOf("state" to state))

class IdempotencyConflict : Error(409)

class ProviderUnavailable(state: String) : Error(409, extras = mapOf("state" to state))

class CategoryInUse : Error(409)

class BlockAlreadyExists : Error(409)

class CreditsDisabled : Error(409)

class MailDisabled : Error(409)

class MailNotApplicable : Error(409)

class InvoiceNotIssuable : Error(409)

// ---- 429 (the bodies never say which subject triggered them, 11 section 18)

class TooManyRequests(retryAfter: Number) : Error(429, extras = mapOf("retryAfter" to retryAfter))

class CodeAttemptsLocked(retryAfter: Number) : Error(429, extras = mapOf("retryAfter" to retryAfter))

// ---- 500

class InvoiceRenderFailed : Error(500)

// ---- 502 (never carries the gateway's own text)

/**
 * [code]: a `ProviderErrorCode`. Checkout answers with the order that was created (it stays `PENDING`, 04 section 3): [order]
 * is its `OrderView` and [orderToken] its access token, so the buyer can pick another method.
 */
class PaymentProviderError(code: String, order: Any? = null, orderToken: String? = null) :
    Error(502, extras = extrasOf("code" to code, "order" to order, "orderToken" to orderToken))

class ShippingProviderError(code: String, shipmentId: Long? = null) :
    Error(502, extras = extrasOf("code" to code, "shipmentId" to shipmentId))

class MailSendFailed : Error(502)

// ---- 503

/** `storeEnabled = false` (04 section 1). */
class StoreDisabled : Error(503)

/** The runtime state is not READY (00 section 8.9). */
class StoreUnavailable : Error(503)

/** A transaction ran out of retries on a deadlock; the response carries `Retry-After: 2` (set by the base classes). */
class StoreBusy : Error(503)
