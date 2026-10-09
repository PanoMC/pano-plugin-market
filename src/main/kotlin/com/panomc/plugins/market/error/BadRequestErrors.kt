package com.panomc.plugins.market.error

import com.panomc.platform.model.Error

// HTTP 400 codes of 04 section 11. The code of an error is its class name in UPPER_SNAKE (`P/model/Error.kt`).
// Extras are the keys named in the catalogue; optional ones are only present when given.

class EmptyCart : Error("EMPTY_CART", 400)

/** [lineErrors]: `{lineKey: [CODE, ...]}`. */
class InvalidCart(lineErrors: Map<String, List<String>> = emptyMap()) :
    Error("INVALID_CART", 400, extras = mapOf("lineErrors" to lineErrors))

class InvalidCoupon(reason: String) : Error("INVALID_COUPON", 400, extras = mapOf("reason" to reason))

class InvalidCreatorCode(reason: String) : Error("INVALID_CREATOR_CODE", 400, extras = mapOf("reason" to reason))

class InvalidGiftCode(reason: String) : Error("INVALID_GIFT_CODE", 400, extras = mapOf("reason" to reason))

class InvalidRecipient : Error("INVALID_RECIPIENT", 400)

class MinimumOrderAmountNotReached(minimum: Number) : Error("MINIMUM_ORDER_AMOUNT_NOT_REACHED", 400, extras = mapOf("minimum" to minimum))

class LegalAcceptanceRequired(legalTextId: Long) : Error("LEGAL_ACCEPTANCE_REQUIRED", 400, extras = mapOf("legalTextId" to legalTextId))

class BuyerInfoRequired(fields: List<String>) : Error("BUYER_INFO_REQUIRED", 400, extras = mapOf("fields" to fields))

class ShippingAddressRequired(fields: List<String>) : Error("SHIPPING_ADDRESS_REQUIRED", 400, extras = mapOf("fields" to fields))

/** [reason]: `NO_ZONE`, `NO_METHOD`, `METHOD_REQUIRED` or `METHOD_NOT_OFFERED`. */
class ShippingUnavailable(reason: String) : Error("SHIPPING_UNAVAILABLE", 400, extras = mapOf("reason" to reason))

/** [reason]: an `unavailableReason` code, or `TEST_MODE`, `CREDITS_DISABLED`, `CREDITS_REQUIRED`, ... (04 section 11). */
class PaymentMethodUnavailable(reason: String) : Error("PAYMENT_METHOD_UNAVAILABLE", 400, extras = mapOf("reason" to reason))

class SubscriptionMustBeAlone : Error("SUBSCRIPTION_MUST_BE_ALONE", 400)

class InsufficientCredits(balance: Number, maxApplicable: Number? = null) :
    Error("INSUFFICIENT_CREDITS", 400, extras = extrasOf("balance" to balance, "maxApplicable" to maxApplicable))

class InvalidOrderTransition(use: String? = null, reason: String? = null) :
    Error("INVALID_ORDER_TRANSITION", 400, extras = extrasOf("use" to use, "reason" to reason))

class InvalidRefundAmount(max: Number, maxGateway: Number? = null, maxCredit: Number? = null) :
    Error("INVALID_REFUND_AMOUNT", 400, extras = extrasOf("max" to max, "maxGateway" to maxGateway, "maxCredit" to maxCredit))

class RefundNotSupported : Error("REFUND_NOT_SUPPORTED", 400)

class CascadeDecisionRequired : Error("CASCADE_DECISION_REQUIRED", 400)

class StatusQueryNotSupported : Error("STATUS_QUERY_NOT_SUPPORTED", 400)

/** [fieldErrors]: `{dotted.path: CODE}`; the settings keys of a provider carry a localized text object (02 section 4) instead of a code. */
class InvalidProviderSettings(fieldErrors: Map<String, Any?> = emptyMap()) :
    Error("INVALID_PROVIDER_SETTINGS", 400, extras = mapOf("fieldErrors" to fieldErrors))

class InvalidSettings(fieldErrors: Map<String, String> = emptyMap()) :
    Error("INVALID_SETTINGS", 400, extras = mapOf("fieldErrors" to fieldErrors))

class InvalidProduct(fieldErrors: Map<String, String> = emptyMap()) :
    Error("INVALID_PRODUCT", 400, extras = mapOf("fieldErrors" to fieldErrors))

/** [reason]: `MALFORMED`, `SCHEME`, `USERINFO`, `HOST`, `PORT`, `DISCORD_URL`, `DNS` or `PRIVATE_ADDRESS`. */
class InvalidWebhookUrl(reason: String) : Error("INVALID_WEBHOOK_URL", 400, extras = mapOf("reason" to reason))

class InvalidCreditAmount(reason: String? = null, min: Number? = null, max: Number? = null) :
    Error("INVALID_CREDIT_AMOUNT", 400, extras = extrasOf("reason" to reason, "min" to min, "max" to max))

class InvalidPayoutAmount(available: Number) : Error("INVALID_PAYOUT_AMOUNT", 400, extras = mapOf("available" to available))

class CreatorHasNoAccount : Error("CREATOR_HAS_NO_ACCOUNT", 400)

class PublicUrlRequired : Error("PUBLIC_URL_REQUIRED", 400)

class ReservedSlug : Error("RESERVED_SLUG", 400)

/** [reason]: `VALUE`, `RANGE_TOO_WIDE` or `SELF`. */
class InvalidBlock(reason: String) : Error("INVALID_BLOCK", 400, extras = mapOf("reason" to reason))

class InvalidShipment(fieldErrors: Map<String, String> = emptyMap()) :
    Error("INVALID_SHIPMENT", 400, extras = mapOf("fieldErrors" to fieldErrors))

class InvalidShipmentTransition(from: String, to: String) :
    Error("INVALID_SHIPMENT_TRANSITION", 400, extras = mapOf("from" to from, "to" to to))

class InvalidMailKind : Error("INVALID_MAIL_KIND", 400)

class MailRecipientRequired : Error("MAIL_RECIPIENT_REQUIRED", 400)

/** [minimum]: the smallest `nextNumber` that is accepted for the series (12 section 9.3); absent when the series itself is invalid. */
class InvalidInvoiceSequence(minimum: Number? = null) : Error("INVALID_INVOICE_SEQUENCE", 400, extras = extrasOf("minimum" to minimum))
