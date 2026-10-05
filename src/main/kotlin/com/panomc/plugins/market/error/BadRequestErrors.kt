package com.panomc.plugins.market.error

import com.panomc.platform.model.Error

// HTTP 400 codes of 04 section 11. The code of an error is its class name in UPPER_SNAKE (`P/model/Error.kt`).
// Extras are the keys named in the catalogue; optional ones are only present when given.

class EmptyCart : Error(400)

/** [lineErrors]: `{lineKey: [CODE, ...]}`. */
class InvalidCart(lineErrors: Map<String, List<String>> = emptyMap()) :
    Error(400, extras = mapOf("lineErrors" to lineErrors))

class InvalidCoupon(reason: String) : Error(400, extras = mapOf("reason" to reason))

class InvalidCreatorCode(reason: String) : Error(400, extras = mapOf("reason" to reason))

class InvalidGiftCode(reason: String) : Error(400, extras = mapOf("reason" to reason))

class InvalidRecipient : Error(400)

class MinimumOrderAmountNotReached(minimum: Number) : Error(400, extras = mapOf("minimum" to minimum))

class LegalAcceptanceRequired(legalTextId: Long) : Error(400, extras = mapOf("legalTextId" to legalTextId))

class BuyerInfoRequired(fields: List<String>) : Error(400, extras = mapOf("fields" to fields))

class ShippingAddressRequired(fields: List<String>) : Error(400, extras = mapOf("fields" to fields))

/** [reason]: `NO_ZONE`, `NO_METHOD`, `METHOD_REQUIRED` or `METHOD_NOT_OFFERED`. */
class ShippingUnavailable(reason: String) : Error(400, extras = mapOf("reason" to reason))

/** [reason]: an `unavailableReason` code, or `TEST_MODE`, `CREDITS_DISABLED`, `CREDITS_REQUIRED`, ... (04 section 11). */
class PaymentMethodUnavailable(reason: String) : Error(400, extras = mapOf("reason" to reason))

class SubscriptionMustBeAlone : Error(400)

class InsufficientCredits(balance: Number, maxApplicable: Number? = null) :
    Error(400, extras = extrasOf("balance" to balance, "maxApplicable" to maxApplicable))

class InvalidOrderTransition(use: String? = null, reason: String? = null) :
    Error(400, extras = extrasOf("use" to use, "reason" to reason))

class InvalidRefundAmount(max: Number, maxGateway: Number? = null, maxCredit: Number? = null) :
    Error(400, extras = extrasOf("max" to max, "maxGateway" to maxGateway, "maxCredit" to maxCredit))

class RefundNotSupported : Error(400)

class CascadeDecisionRequired : Error(400)

class StatusQueryNotSupported : Error(400)

/** [fieldErrors]: `{dotted.path: CODE}`; the settings keys of a provider carry a localized text object (02 section 4) instead of a code. */
class InvalidProviderSettings(fieldErrors: Map<String, Any?> = emptyMap()) :
    Error(400, extras = mapOf("fieldErrors" to fieldErrors))

class InvalidSettings(fieldErrors: Map<String, String> = emptyMap()) :
    Error(400, extras = mapOf("fieldErrors" to fieldErrors))

class InvalidProduct(fieldErrors: Map<String, String> = emptyMap()) :
    Error(400, extras = mapOf("fieldErrors" to fieldErrors))

/** [reason]: `MALFORMED`, `SCHEME`, `USERINFO`, `HOST`, `PORT`, `DISCORD_URL`, `DNS` or `PRIVATE_ADDRESS`. */
class InvalidWebhookUrl(reason: String) : Error(400, extras = mapOf("reason" to reason))

class InvalidCreditAmount(reason: String? = null, min: Number? = null, max: Number? = null) :
    Error(400, extras = extrasOf("reason" to reason, "min" to min, "max" to max))

class InvalidPayoutAmount(available: Number) : Error(400, extras = mapOf("available" to available))

class CreatorHasNoAccount : Error(400)

class PublicUrlRequired : Error(400)

class ReservedSlug : Error(400)

/** [reason]: `VALUE`, `RANGE_TOO_WIDE` or `SELF`. */
class InvalidBlock(reason: String) : Error(400, extras = mapOf("reason" to reason))

class InvalidShipment(fieldErrors: Map<String, String> = emptyMap()) :
    Error(400, extras = mapOf("fieldErrors" to fieldErrors))

class InvalidShipmentTransition(from: String, to: String) :
    Error(400, extras = mapOf("from" to from, "to" to to))

class InvalidMailKind : Error(400)

class MailRecipientRequired : Error(400)

class InvalidInvoiceSequence : Error(400)
