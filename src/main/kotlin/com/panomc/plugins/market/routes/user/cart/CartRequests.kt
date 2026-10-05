package com.panomc.plugins.market.routes.user.cart

import com.panomc.plugins.market.core.cart.CartLimits
import com.panomc.plugins.market.core.cart.CartLine
import com.panomc.plugins.market.core.cart.CartLineKey
import com.panomc.plugins.market.core.cart.CartLineParser
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.routes.base.parseBodyId
import com.panomc.plugins.market.service.CartService
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

// Pure request parsing of the buyer cart routes (06 section 2.1: structural limits are "enforced by the request schema
// (400 BAD_REQUEST)"). Every function throws RequestValueException for a value outside the contract; the route base
// answers that with 400 BAD_REQUEST.

/** Longest `items` array a write accepts before the lines are summed; the cart itself holds at most 50 distinct lines. */
internal const val MAX_REQUEST_LINES = 500

private const val MAX_USERNAME = 64

/** A single-line write (`POST /me/cart/items`, an element of `PUT /me/cart`): quantity must already be in `1..999`. */
internal fun parseStrictLine(body: JsonObject, name: String = "item"): CartLine {
    val quantity = body.getValue("quantity")

    if (quantity != null) {
        val q = integral(quantity) ?: throw RequestValueException("$name.quantity", "MUST_BE_AN_INTEGER")

        if (q < CartLimits.MIN_QUANTITY || q > CartLimits.MAX_QUANTITY) throw RequestValueException("$name.quantity", "OUT_OF_RANGE")
    }

    val productId = parseBodyId(body.getValue("productId"), "$name.productId")
    val variantId = body.getValue("variantId")?.let { nonNegative(it, "$name.variantId") } ?: 0L
    val target = body.getValue("targetServerId")?.let { parseBodyId(it, "$name.targetServerId") }
    val values = fieldValues(body.getValue("fieldValues"), "$name.fieldValues")

    return CartLine(productId, variantId, quantity?.let { CartLimits.clampQuantity(integral(it)!!) } ?: 1, CartLineKey.normalize(values), target)
}

/** A browser cart line of a merge: quantity is clamped, an unreadable line is `null` (reported, not refused). */
internal fun parseLenientLine(raw: Any?): CartLine? = (raw as? JsonObject)?.let { CartLineParser.parse(it.map) }

/** `POST /me/cart/merge`: the readable lines, and how many entries could not be read. */
internal class MergeRequest(val lines: List<CartLine>, val unreadable: Int)

internal fun parseMerge(body: JsonObject): MergeRequest {
    val items = body.getValue("items")

    if (items !is JsonArray) throw RequestValueException("items", "REQUIRED")

    if (items.size() > MAX_REQUEST_LINES) throw RequestValueException("items", "TOO_MANY")

    val parsed = items.list.map { parseLenientLine(if (it is Map<*, *>) JsonObject(@Suppress("UNCHECKED_CAST") (it as Map<String, Any?>)) else it) }

    return MergeRequest(parsed.filterNotNull(), parsed.count { it == null })
}

/** `PUT /me/cart/items/:itemId`: at least one of `quantity`, `fieldValues`, `targetServerId`. */
internal fun parseItemPatch(body: JsonObject): CartService.ItemPatch {
    if (!body.containsKey("quantity") && !body.containsKey("fieldValues") && !body.containsKey("targetServerId")) {
        throw RequestValueException("body", "EMPTY")
    }

    val quantity = body.getValue("quantity")?.let {
        val q = integral(it) ?: throw RequestValueException("quantity", "MUST_BE_AN_INTEGER")

        if (q < CartLimits.MIN_QUANTITY || q > CartLimits.MAX_QUANTITY) throw RequestValueException("quantity", "OUT_OF_RANGE")

        q.toInt()
    }

    return CartService.ItemPatch(
        quantity = quantity,
        fieldValues = if (body.containsKey("fieldValues")) CartService.Field(body.getValue("fieldValues")?.let { fieldValues(it, "fieldValues") }) else null,
        targetServerId = if (body.containsKey("targetServerId")) CartService.Field(body.getValue("targetServerId")?.let { parseBodyId(it, "targetServerId") }) else null
    )
}

/** `PUT /me/cart`: `items` and the cart-level fields, each applied only when present. */
internal fun parseReplacement(body: JsonObject): CartService.Replacement {
    val items = if (body.containsKey("items")) {
        when (val raw = body.getValue("items")) {
            null -> emptyList()
            is JsonArray -> {
                if (raw.size() > MAX_REQUEST_LINES) throw RequestValueException("items", "TOO_MANY")

                raw.list.mapIndexed { index, element ->
                    val obj = when (element) {
                        is JsonObject -> element
                        is Map<*, *> -> JsonObject(@Suppress("UNCHECKED_CAST") (element as Map<String, Any?>))
                        else -> throw RequestValueException("items[$index]", "MUST_BE_AN_OBJECT")
                    }

                    parseStrictLine(obj, "items[$index]")
                }
            }

            else -> throw RequestValueException("items", "MUST_BE_AN_ARRAY")
        }
    } else null

    return CartService.Replacement(
        items = items,
        currency = text(body, "currency", 8),
        couponCode = text(body, "couponCode", CartLimits.MAX_CODE_LENGTH),
        creatorCode = text(body, "creatorCode", CartLimits.MAX_CODE_LENGTH),
        recipientUsername = text(body, "recipientUsername", MAX_USERNAME),
        giftMessage = text(body, "giftMessage", CartLimits.MAX_GIFT_MESSAGE),
        shippingAddressId = idField(body, "shippingAddressId"),
        shippingMethodId = idField(body, "shippingMethodId")
    )
}

private fun text(body: JsonObject, key: String, max: Int): CartService.Field<String?>? {
    if (!body.containsKey(key)) return null

    val value = body.getValue(key) ?: return CartService.Field(null)

    if (value !is String) throw RequestValueException(key, "MUST_BE_A_STRING")

    if (value.trim().length > max) throw RequestValueException(key, "TOO_LONG")

    return CartService.Field(value)
}

private fun idField(body: JsonObject, key: String): CartService.Field<Long?>? {
    if (!body.containsKey(key)) return null

    return CartService.Field(body.getValue(key)?.let { parseBodyId(it, key) })
}

private fun fieldValues(raw: Any?, name: String): Map<String, Any?> {
    val map = when (raw) {
        null -> emptyMap()
        is JsonObject -> raw.map
        is Map<*, *> -> @Suppress("UNCHECKED_CAST") (raw as Map<String, Any?>)
        else -> throw RequestValueException(name, "MUST_BE_AN_OBJECT")
    }

    val values = CartLineParser.fieldValues(map) ?: throw RequestValueException(name, "INVALID_VALUE")

    if (values.size > CartLimits.MAX_FIELD_KEYS) throw RequestValueException(name, "TOO_MANY")

    if (!CartLimits.fieldValuesFit(values)) throw RequestValueException(name, "TOO_LONG")

    return values
}

private fun nonNegative(raw: Any, name: String): Long = integral(raw)?.takeIf { it >= 0 } ?: throw RequestValueException(name, "MUST_BE_AN_INTEGER_ID")

private fun integral(value: Any?): Long? = when (value) {
    is Int -> value.toLong()
    is Long -> value
    is Short -> value.toLong()
    is Byte -> value.toLong()
    is Double -> value.takeIf { it == Math.floor(it) && !it.isInfinite() && Math.abs(it) < 9.0E15 }?.toLong()
    else -> null
}
