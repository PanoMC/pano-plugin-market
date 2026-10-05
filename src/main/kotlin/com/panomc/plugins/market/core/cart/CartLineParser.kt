package com.panomc.plugins.market.core.cart

/**
 * Reads a `CartLine` of 04 section 3 from the untyped JSON map of a request. A line that cannot be read (no integral
 * `productId`, `quantity` that is not a number, `fieldValues` that is not an object of strings, numbers and booleans) is
 * `null`; quantity is clamped into `1..999` (a request schema rejects out-of-range quantities first on single-line
 * writes, the browser cart of a merge is clamped), `variantId` defaults to 0, `fieldValues` are normalised.
 */
object CartLineParser {
    fun parse(raw: Map<String, Any?>): CartLine? {
        val productId = integral(raw["productId"]) ?: return null
        val variantId = if (raw["variantId"] == null) 0L else integral(raw["variantId"]) ?: return null
        val quantity = if (raw["quantity"] == null) 1L else integral(raw["quantity"]) ?: return null
        val target = if (raw["targetServerId"] == null) null else integral(raw["targetServerId"]) ?: return null
        val values = fieldValues(raw["fieldValues"]) ?: return null

        return CartLine(productId, variantId, CartLimits.clampQuantity(quantity), CartLineKey.normalize(values), target)
    }

    /** `null` when [raw] is not an object of scalars; an absent value is the empty map. */
    fun fieldValues(raw: Any?): Map<String, Any?>? {
        if (raw == null) return emptyMap()

        if (raw !is Map<*, *>) return null

        val out = LinkedHashMap<String, Any?>()

        for ((key, value) in raw) {
            if (key !is String) return null

            if (value != null && value !is String && value !is Boolean && value !is Number) return null

            out[key] = value
        }

        return out
    }

    private fun integral(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is Number -> value.toDouble().takeIf { it == Math.floor(it) && !it.isInfinite() }?.toLong()
        else -> null
    }
}
