package com.panomc.plugins.market.core.shipping

import com.panomc.plugins.market.db.model.ShippingRateBasis
import com.panomc.plugins.market.db.model.ShippingRateBasis.AMOUNT
import com.panomc.plugins.market.db.model.ShippingRateBasis.FLAT
import com.panomc.plugins.market.db.model.ShippingRateBasis.QUANTITY
import com.panomc.plugins.market.db.model.ShippingRateBasis.WEIGHT

/**
 * One `market_shipping_rate` row of a (method, zone): [rangeFrom] / [rangeTo] are inclusive and in grams, base-currency
 * x100 money or units by [basis] (`rangeTo == null` = open); [price] and [perUnitPrice] are base-currency x100 money.
 */
class RateRow(
    val basis: ShippingRateBasis,
    val rangeFrom: Long = 0,
    val rangeTo: Long? = null,
    val price: Long = 0,
    val perUnitPrice: Long = 0,
    val position: Int = 0,
    val id: Long = 0
)

/** What a cart measures: [amountBase] is the physical basis converted to the base currency (10 section 5.2). */
class Measure(val weightGrams: Long, val amountBase: Long, val units: Long)

/** Rule price of a method in a zone (10 section 5.2). Pure; all arithmetic is exact. */
object RateEngine {
    /**
     * The price in base currency of the first row (by `position`, `id`) whose range contains the measure, or `null`
     * when none does (the method is not offered). `FLAT` ignores the measure; a `FLAT` row therefore ends the search.
     */
    fun price(rates: List<RateRow>, measure: Measure): Long? {
        for (row in rates.sortedWith(compareBy({ it.position }, { it.id }))) {
            val m = when (row.basis) {
                FLAT -> null
                WEIGHT -> measure.weightGrams
                AMOUNT -> measure.amountBase
                QUANTITY -> measure.units
            }
            if (m != null && !(row.rangeFrom <= m && (row.rangeTo == null || m <= row.rangeTo))) continue

            val price = when (row.basis) {
                FLAT, AMOUNT -> row.price
                WEIGHT -> Math.addExact(row.price, Math.multiplyExact(ceilDiv(m!! - row.rangeFrom, 1000), row.perUnitPrice))
                QUANTITY -> Math.addExact(row.price, Math.multiplyExact(m!! - row.rangeFrom, row.perUnitPrice))
            }
            check(price >= 0) { "a rate price cannot be negative: $price" }

            return price
        }

        return null
    }

    private fun ceilDiv(a: Long, b: Long): Long = Math.floorDiv(a + b - 1, b)
}
