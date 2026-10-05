package com.panomc.plugins.market.core.shipping

import com.panomc.plugins.market.core.money.Rounding
import java.math.BigDecimal

/**
 * A physical line after bundle expansion (10 section 2.2). Money is `Long` x 100 in the order currency; [lineValue] is
 * the line's `lineTotal` (the share of the parent bundle for a bundle child).
 */
class ShippableLine(
    val orderItemId: Long?,
    val productId: Long,
    val variantId: Long,
    val name: String,
    val sku: String?,
    val quantity: Int,
    val unitWeightGrams: Int,
    val lengthMm: Int?,
    val widthMm: Int?,
    val heightMm: Int?,
    val lineValue: Long,
    val hsCode: String? = null,
    val originCountry: String? = null
) {
    init {
        require(quantity > 0) { "quantity must be positive: $quantity" }
        require(unitWeightGrams > 0) { "unit weight must be positive: $unitWeightGrams" }
        require(lineValue >= 0) { "line value must not be negative: $lineValue" }
    }

    val hasDimensions: Boolean get() = lengthMm != null && widthMm != null && heightMm != null

    /** `l x w x h` in mm^3, 0 without dimensions. */
    val volume: Long get() = if (hasDimensions) lengthMm!!.toLong() * widthMm!! * heightMm!! else 0L
}

/** The cart-level figures of 10 section 2.2. */
class ShippableTotals(val requiresShipping: Boolean, val shippableValue: Long, val shippableUnits: Long, val weightGrams: Long)

object ShippableLines {
    /** The weight above which no method is offered (10 section 2.2). */
    const val MAX_WEIGHT_GRAMS = 2_000_000_000L

    /** `sum(unitWeightGrams x quantity)` with exact arithmetic (an overflow throws [ArithmeticException]). */
    fun weightGrams(lines: List<ShippableLine>): Long =
        lines.fold(0L) { acc, l -> Math.addExact(acc, Math.multiplyExact(l.unitWeightGrams.toLong(), l.quantity.toLong())) }

    fun isTooHeavy(lines: List<ShippableLine>): Boolean = weightGrams(lines) > MAX_WEIGHT_GRAMS

    fun totals(lines: List<ShippableLine>): ShippableTotals = ShippableTotals(
        requiresShipping = lines.isNotEmpty(),
        shippableValue = lines.fold(0L) { acc, l -> Math.addExact(acc, l.lineValue) },
        shippableUnits = lines.fold(0L) { acc, l -> Math.addExact(acc, l.quantity.toLong()) },
        weightGrams = weightGrams(lines)
    )

    /**
     * The `lineValue` of the physical children of a bundle (10 section 2.2): the bundle's [bundleLineTotal] divided
     * equally over all child **units**, half up per unit; the rounding remainder (which can be negative) goes to the
     * first child line. [childQuantities] are the unit counts per child line, in line order.
     */
    fun splitBundleValue(bundleLineTotal: Long, childQuantities: List<Int>): List<Long> {
        require(bundleLineTotal >= 0) { "bundle total must not be negative: $bundleLineTotal" }
        require(childQuantities.isNotEmpty() && childQuantities.all { it > 0 }) { "a bundle needs child lines with units" }

        val units = childQuantities.sumOf { it.toLong() }
        val perUnit = Rounding.halfUp(BigDecimal.valueOf(bundleLineTotal).divide(BigDecimal.valueOf(units), 10, java.math.RoundingMode.HALF_UP))
        val values = childQuantities.map { Math.multiplyExact(perUnit, it.toLong()) }.toMutableList()
        values[0] = Math.addExact(values[0], bundleLineTotal - Math.multiplyExact(perUnit, units))

        return values
    }
}
