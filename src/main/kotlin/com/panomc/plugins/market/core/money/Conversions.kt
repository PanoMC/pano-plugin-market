package com.panomc.plugins.market.core.money

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The currency and credit conversions of one order (05 section 2). Immutable, pure.
 *
 * - [fx]: order-currency units per 1 base unit (1 for the base currency; `DECIMAL(20,10)` in the database).
 * - [creditValue] (`cv`): money value of one credit in base minor units (x100 scale), `> 0` wherever credits are converted.
 * - [oq] / [bq]: quantum of the order / base currency ([Rounding.quantum]); [dq]: quantum of the display currency.
 * - [displayCurrency] / [displayRate]: the `DISPLAY` mode block, both set or both null.
 *
 * Every conversion rounds once, half up (credits to money: floor, so credits never buy more than they are worth).
 */
data class Conversions(
    val baseCurrency: String,
    val orderCurrency: String,
    val fx: BigDecimal,
    val creditValue: Long,
    val removeCents: Boolean,
    val displayCurrency: String? = null,
    val displayRate: BigDecimal? = null
) {
    init {
        require(fx.signum() > 0) { "the exchange rate must be positive: $fx" }
        require((displayCurrency == null) == (displayRate == null)) { "displayCurrency and displayRate go together" }
        require(displayRate == null || displayRate.signum() > 0) { "the display rate must be positive: $displayRate" }
    }

    /** Quantum of the order currency. */
    val oq: Long = Rounding.quantum(orderCurrency, removeCents)

    /** Quantum of the base currency. */
    val bq: Long = Rounding.quantum(baseCurrency, removeCents)

    /** Quantum of the display currency (the order quantum when there is no display block). */
    val dq: Long = displayCurrency?.let { Rounding.quantum(it, removeCents) } ?: oq

    /** `roundQ(baseAmount * fx, oq)`: a base-currency amount in the order currency. */
    fun toOrder(baseAmount: Long): Long =
        Rounding.roundQ(BigDecimal.valueOf(baseAmount).multiply(fx), oq)

    /** `roundQ(orderAmount / fx, bq)`: an order-currency amount in the base currency (one division, one rounding). */
    fun fromOrder(orderAmount: Long): Long =
        Rounding.ratioQ(BigDecimal.valueOf(orderAmount), fx, bq)

    /** `halfUp(baseAmount * 100 / cv)`: base money as credits x 100. */
    fun moneyToCredits(baseAmount: Long): Long {
        requireCreditValue()
        require(baseAmount >= 0) { "money amounts are never negative: $baseAmount" }
        return BigDecimal.valueOf(baseAmount).multiply(BigDecimal(100))
            .divide(BigDecimal.valueOf(creditValue), 0, RoundingMode.HALF_UP)
            .longValueExact()
    }

    /** `floorQ(credits * cv * fx / 100, oq)`: credits x 100 as order-currency money, rounded down. */
    fun creditsToMoney(credits: Long): Long {
        requireCreditValue()
        require(credits >= 0) { "credit amounts are never negative: $credits" }
        val numerator = BigDecimal.valueOf(credits).multiply(BigDecimal.valueOf(creditValue)).multiply(fx)
        return Rounding.floorQ(numerator.divide(BigDecimal(100)), oq)
    }

    /** `roundQ(orderAmount * displayRate, q(displayCurrency))`; requires a display block. */
    fun toDisplay(orderAmount: Long): Long {
        val rate = checkNotNull(displayRate) { "there is no display currency" }
        return Rounding.roundQ(BigDecimal.valueOf(orderAmount).multiply(rate), dq)
    }

    private fun requireCreditValue() = require(creditValue > 0) { "the credit value must be positive: $creditValue" }
}
