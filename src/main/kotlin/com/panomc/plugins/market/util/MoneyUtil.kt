package com.panomc.plugins.market.util

import kotlin.math.roundToLong

/**
 * Single money/percent boundary: JSON carries plain decimal numbers, entities and DB columns
 * carry x100 integers (minor units for money, basis points for percentages).
 */
object MoneyUtil {
    fun toMinor(value: Double): Long = (value * 100).roundToLong()

    fun toDecimal(value: Long): Double = value / 100.0
}
