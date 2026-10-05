package com.panomc.plugins.market.core.cart

/** One cart line as every entry point (server rows, browser cart v2, `CartLine`) shapes it (06 section 2.1). */
data class CartLine(
    val productId: Long,
    val variantId: Long = 0,
    val quantity: Int = 1,
    /** Normalised by [CartLineKey.normalize]: no blank values, typed. */
    val fieldValues: Map<String, Any> = emptyMap(),
    val targetServerId: Long? = null
) {
    val lineKey: String get() = CartLineKey.of(productId, variantId, fieldValues, targetServerId)

    fun withQuantity(quantity: Int): CartLine = copy(quantity = quantity)
}

/**
 * Structural limits of a cart (06 section 2.1). Business limits (stock, `maxQuantityPerOrder`, per-player limit) are not
 * enforced on cart writes: they are reported as advice ([maxQuantity]) so the buyer can see and fix them.
 */
object CartLimits {
    const val MAX_LINES = 50
    const val MIN_QUANTITY = 1
    const val MAX_QUANTITY = 999
    const val MAX_FIELD_KEYS = 20
    const val MAX_FIELD_VALUE_LENGTH = 128
    const val MAX_GIFT_MESSAGE = 255
    const val MAX_CODE_LENGTH = 64

    /** [quantity] forced into `1..999`. */
    fun clampQuantity(quantity: Long): Int = quantity.coerceIn(MIN_QUANTITY.toLong(), MAX_QUANTITY.toLong()).toInt()

    /** Sum of two quantities capped at 999 (the `LEAST(quantity + ?, 999)` of the upsert), overflow-safe. */
    fun addQuantities(a: Int, b: Int): Int = clampQuantity(a.toLong() + b.toLong())

    /**
     * The most a buyer may put in a line right now: the smallest of the remaining stock (`null` = unlimited),
     * `maxQuantityPerOrder` (`null` = none) and 999; never negative.
     */
    fun maxQuantity(stock: Int?, maxQuantityPerOrder: Int?): Int {
        var max = MAX_QUANTITY

        if (stock != null) max = minOf(max, stock)
        if (maxQuantityPerOrder != null) max = minOf(max, maxQuantityPerOrder)

        return maxOf(max, 0)
    }

    /** `true` when the shape limits hold: `fieldValues` <= 20 keys, each value <= 128 chars. Quantity is clamped, not judged. */
    fun fieldValuesFit(fieldValues: Map<String, Any?>?): Boolean {
        if (fieldValues == null) return true

        if (fieldValues.size > MAX_FIELD_KEYS) return false

        return fieldValues.values.all { (CartLineKey.stringValues(mapOf("k" to it))["k"]?.length ?: 0) <= MAX_FIELD_VALUE_LENGTH }
    }

    /** A cart-level code as stored (06 section 2.2): trimmed, upper-cased, `null` for blank. Length is judged by [codeFits]. */
    fun normalizeCode(code: String?): String? = code?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }

    fun codeFits(code: String?): Boolean = code == null || code.length <= MAX_CODE_LENGTH
}
