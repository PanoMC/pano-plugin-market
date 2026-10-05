package com.panomc.plugins.market.routes.api.checkout

import com.panomc.plugins.market.core.cart.CartLimits
import com.panomc.plugins.market.core.cart.CartLine
import com.panomc.plugins.market.core.cart.CartLineKey
import com.panomc.plugins.market.core.cart.CartLineParser
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.routes.base.parseBodyId
import com.panomc.plugins.market.service.GuestInput
import com.panomc.plugins.market.service.QuoteInput
import com.panomc.plugins.market.service.TopUpRequest
import com.panomc.plugins.market.service.UseCredits
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.math.BigDecimal

// Pure parsing of the `CartInput` body of `POST /api/market/checkout/quote` (04 section 2). A value outside the contract is a
// RequestValueException (400 BAD_REQUEST); a business problem is never one: it reaches the quote as a message.

/** Longest `items` array a quote accepts before equal lines are summed; the cart holds at most 50 distinct lines. */
internal const val MAX_QUOTE_LINES = 500

private const val MAX_NAME = 64
private const val MAX_METHOD = 64
private const val MAX_LOCALE = 16
private const val MAX_CURRENCY = 8
private const val MAX_TEXT = 255

internal fun parseQuoteInput(body: JsonObject): QuoteInput {
    val items = if (body.containsKey("items") && body.getValue("items") != null) parseItems(body.getValue("items")) else null

    return QuoteInput(
        items = items,
        currency = text(body, "currency", MAX_CURRENCY),
        couponCode = text(body, "couponCode", CartLimits.MAX_CODE_LENGTH),
        creatorCode = text(body, "creatorCode", CartLimits.MAX_CODE_LENGTH),
        recipientUsername = text(body, "recipientUsername", MAX_NAME),
        giftMessage = text(body, "giftMessage", MAX_TEXT),
        creditTopUp = body.getValue("creditTopUp")?.let { TopUpRequest(creditsOrNull(it)) },
        guest = guest(body.getValue("guest")),
        useCredits = useCredits(body.getValue("useCredits")),
        payWithCredits = bool(body, "payWithCredits"),
        shippingAddress = obj(body, "shippingAddress"),
        shippingAddressId = body.getValue("shippingAddressId")?.let { parseBodyId(it, "shippingAddressId") },
        shippingMethodId = body.getValue("shippingMethodId")?.let { parseBodyId(it, "shippingMethodId") },
        billingInfo = obj(body, "billingInfo"),
        paymentMethodId = text(body, "paymentMethodId", MAX_METHOD),
        locale = text(body, "locale", MAX_LOCALE)
    )
}

private fun parseItems(raw: Any?): List<CartLine> {
    val array = raw as? JsonArray ?: throw RequestValueException("items", "MUST_BE_AN_ARRAY")

    if (array.size() > MAX_QUOTE_LINES) throw RequestValueException("items", "TOO_MANY")

    return array.list.mapIndexed { index, element ->
        val map = when (element) {
            is JsonObject -> element.map
            is Map<*, *> -> @Suppress("UNCHECKED_CAST") (element as Map<String, Any?>)
            else -> throw RequestValueException("items[$index]", "MUST_BE_AN_OBJECT")
        }
        val line = CartLineParser.parse(map) ?: throw RequestValueException("items[$index]", "INVALID_LINE")

        if (line.productId <= 0 || line.variantId < 0 || (line.targetServerId != null && line.targetServerId <= 0)) throw RequestValueException("items[$index]", "INVALID_LINE")
        if (line.fieldValues.size > CartLimits.MAX_FIELD_KEYS) throw RequestValueException("items[$index].fieldValues", "TOO_MANY")
        if (!CartLimits.fieldValuesFit(line.fieldValues)) throw RequestValueException("items[$index].fieldValues", "TOO_LONG")

        line.copy(fieldValues = CartLineKey.normalize(line.fieldValues))
    }
}

/** `useCredits`: a number of credits (at most two decimals, not negative) or the string `MAX`. Absent / `null` = none. */
internal fun useCredits(raw: Any?): UseCredits? = when (raw) {
    null -> null
    is String -> if (raw == "MAX") UseCredits.Max else throw RequestValueException("useCredits", "MUST_BE_A_NUMBER_OR_MAX")
    is Number -> UseCredits.Amount(creditsOrNull(raw)?.takeIf { it >= 0 } ?: throw RequestValueException("useCredits", "INVALID_AMOUNT"))
    else -> throw RequestValueException("useCredits", "MUST_BE_A_NUMBER_OR_MAX")
}

/** A credit count x 100 from a JSON number, or `null` when it is not a number, is negative, or has more than two decimals. */
internal fun creditsOrNull(raw: Any?): Long? {
    if (raw !is Number) return null

    val value = runCatching { BigDecimal(raw.toString()) }.getOrNull() ?: return null
    val scaled = value.movePointRight(2)

    if (scaled.signum() < 0 || scaled.stripTrailingZeros().scale() > 0) return null

    return runCatching { scaled.longValueExact() }.getOrNull()
}

private fun guest(raw: Any?): GuestInput? {
    if (raw == null) return null

    val obj = when (raw) {
        is JsonObject -> raw
        is Map<*, *> -> JsonObject(@Suppress("UNCHECKED_CAST") (raw as Map<String, Any?>))
        else -> throw RequestValueException("guest", "MUST_BE_AN_OBJECT")
    }

    return GuestInput(text(obj, "username", MAX_NAME, "guest.username"), text(obj, "email", MAX_TEXT, "guest.email"))
}

private fun text(body: JsonObject, key: String, max: Int, name: String = key): String? {
    val value = body.getValue(key) ?: return null

    if (value !is String) throw RequestValueException(name, "MUST_BE_A_STRING")

    if (value.length > max) throw RequestValueException(name, "TOO_LONG")

    return value
}

private fun bool(body: JsonObject, key: String): Boolean {
    val value = body.getValue(key) ?: return false

    return value as? Boolean ?: throw RequestValueException(key, "MUST_BE_A_BOOLEAN")
}

private fun obj(body: JsonObject, key: String): JsonObject? = when (val value = body.getValue(key)) {
    null -> null
    is JsonObject -> value
    is Map<*, *> -> JsonObject(@Suppress("UNCHECKED_CAST") (value as Map<String, Any?>))
    else -> throw RequestValueException(key, "MUST_BE_AN_OBJECT")
}
