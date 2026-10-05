package com.panomc.plugins.market.spi.common

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * `amount` = value x 100 regardless of the currency exponent (00 section 6): 12.34 EUR is 1234, 500 JPY is 50000.
 * A zero-decimal currency must hold whole units (`amount % 100 == 0`); a currency [Currencies.isSupported]
 * refuses (three decimals, unknown) cannot be constructed. All conversions are exact or throw: no `Double`.
 */
class Money(val amount: Long, val currency: String) {
    init {
        require(Currencies.isSupported(currency)) { "Unsupported currency '$currency'" }
        require(Currencies.exponent(currency) != 0 || amount % 100L == 0L) {
            "$currency has no decimals: amount $amount is not a whole number of units"
        }
    }

    /** Scale 2 decimal (`12.34`; `500.00` for JPY). */
    fun toDecimal(): BigDecimal = BigDecimal.valueOf(amount, 2)

    /** `"12.34"`; `"500"` for zero-decimal currencies. Plain notation, `-` prefix for negatives. */
    fun toDecimalString(): String =
        if (Currencies.exponent(currency) == 0) (amount / 100L).toString() else toDecimal().toPlainString()

    /** ISO minor units: 1234 (EUR), 500 (JPY). */
    fun toMinorUnits(): Long = if (Currencies.exponent(currency) == 0) amount / 100L else amount

    fun isZero(): Boolean = amount == 0L

    override fun equals(other: Any?): Boolean = other is Money && other.amount == amount && other.currency == currency

    override fun hashCode(): Int = 31 * amount.hashCode() + currency.hashCode()

    override fun toString(): String = "${toDecimalString()} $currency"

    companion object {
        /**
         * Rounds HALF_UP to scale 2 (`0.125 -> 0.13`, `-0.125 -> -0.13`). A zero-decimal currency must come out as
         * whole units, otherwise [IllegalArgumentException] (a fractional yen is refused, never rounded away).
         * Throws [ArithmeticException] when the x100 value does not fit a `Long`.
         */
        fun ofDecimal(value: BigDecimal, currency: String): Money =
            Money(value.setScale(2, RoundingMode.HALF_UP).unscaledValue().longValueExact(), currency)

        /** ISO minor units to x100: 1234 EUR cents, 500 JPY (= 50000). Throws [ArithmeticException] on overflow. */
        fun ofMinorUnits(minor: Long, currency: String): Money {
            require(Currencies.isSupported(currency)) { "Unsupported currency '$currency'" }
            return Money(if (Currencies.exponent(currency) == 0) Math.multiplyExact(minor, 100L) else minor, currency)
        }
    }
}
