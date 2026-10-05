package com.panomc.plugins.market.core.credit

import com.panomc.plugins.market.core.money.Rounding
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Credit <-> money conversions (07 section 2). Pure, exact [BigDecimal] arithmetic, each result rounded **once**; there is no
 * floating point anywhere in this package (`CreditMathTest` scans the sources for it).
 *
 * | symbol | meaning |
 * |---|---|
 * | credits `c` | `Long`, credits x 100 (two decimals) |
 * | `rateMinor` | base-currency money x 100 per 1 credit (`MarketConfig.creditValue` through `MoneyUtil.toMinor`), `>= 1` |
 * | `fxRate` | order currency per 1 base unit (`market_order.fxRate`, 1 in `SINGLE` / `DISPLAY`) |
 * | `unit(currency)` | the smallest chargeable amount x 100: 100 for a zero-decimal currency, else 1 |
 *
 * Naming trap (07 section 2): `MarketConfig.creditValue` is a **rate**, `market_order.creditValue` and
 * `market_refund.creditValue` are **money amounts**. In code the rate is always `rateMinor`.
 *
 * The rounding direction is fixed per use and never chosen ad hoc by a caller, so the per-use entry points below are what
 * services call; [valueOf] and [creditsFor] take the mode for the cases that share one formula with another direction.
 * Only `HALF_UP`, `FLOOR` and `CEILING` are accepted (all inputs are non-negative, so `DOWN` / `UP` would be duplicates).
 *
 * The pricing engine keeps its own, equal formulas on `Conversions` (`moneyToCredits`, `creditsToMoney`, the mixed-payment
 * value and the shipping credits); `CreditMathTest` proves the two agree.
 */
object CreditMath {
    private val HUNDRED = BigDecimal(100)
    private val MODES = setOf(RoundingMode.HALF_UP, RoundingMode.FLOOR, RoundingMode.CEILING)

    /**
     * `unit(cur)` of 07 section 2: 100 for a zero-decimal currency, else 1. [removeCents] (the store setting) also makes it 100,
     * which is the pricing engine's quantum (05 section 2); `false` is the plain `unit(cur)` of the credits spec.
     */
    fun unit(currency: String, removeCents: Boolean = false): Long = Rounding.quantum(currency, removeCents)

    /**
     * Money value (order currency, x 100) of [credits]:
     * `raw = credits x rateMinor / 100 x fxRate`, rounded with [mode] to a multiple of [unit].
     */
    fun valueOf(
        credits: Long,
        rateMinor: Long,
        fxRate: BigDecimal,
        currency: String,
        mode: RoundingMode,
        removeCents: Boolean = false
    ): Long {
        require(credits >= 0) { "credit amounts are never negative: $credits" }
        requireRate(rateMinor, fxRate)
        requireMode(mode)
        val unit = unit(currency, removeCents)
        val raw = BigDecimal.valueOf(credits).multiply(BigDecimal.valueOf(rateMinor)).multiply(fxRate)
        // one division by `100 x unit` with the final rounding: the quotient is the number of units
        val units = raw.divide(HUNDRED.multiply(BigDecimal.valueOf(unit)), 0, mode)
        return Math.multiplyExact(units.longValueExact(), unit)
    }

    /**
     * Credits (x 100) worth [value] (order currency, x 100):
     * `raw = value x 100 / (rateMinor x fxRate)`, rounded with [mode] to an integer (0.01 credit).
     */
    fun creditsFor(value: Long, rateMinor: Long, fxRate: BigDecimal, mode: RoundingMode): Long {
        require(value >= 0) { "money amounts are never negative: $value" }
        requireRate(rateMinor, fxRate)
        requireMode(mode)
        return BigDecimal.valueOf(value).multiply(HUNDRED)
            .divide(BigDecimal.valueOf(rateMinor).multiply(fxRate), 0, mode)
            .longValueExact()
    }

    // ---------------------------------------------------------------- the fixed use of each direction (07 section 2 table)

    /** Value covered by credits in a mixed order: `HALF_UP`; the caller clamps it to the cap (07 section 6.2, 05 section 8.2). */
    fun mixedValue(credits: Long, rateMinor: Long, fxRate: BigDecimal, currency: String, removeCents: Boolean = false): Long =
        valueOf(credits, rateMinor, fxRate, currency, RoundingMode.HALF_UP, removeCents)

    /** Most credits a money value can pay (the maximum applicable to a mixed order): `FLOOR`, credits never buy more than they are worth. */
    fun maxCredits(value: Long, rateMinor: Long, fxRate: BigDecimal): Long =
        creditsFor(value, rateMinor, fxRate, RoundingMode.FLOOR)

    /** Shipping of a full-credit order in credits: `CEILING` (0 for no shipping). */
    fun shippingCredits(value: Long, rateMinor: Long, fxRate: BigDecimal): Long =
        if (value == 0L) 0L else creditsFor(value, rateMinor, fxRate, RoundingMode.CEILING)

    /** Price of a free-amount top-up: `HALF_UP`, never below one [unit] (a positive amount of credits is never free). */
    fun topUpPrice(credits: Long, rateMinor: Long, fxRate: BigDecimal, currency: String, removeCents: Boolean = false): Long {
        require(credits > 0) { "a top-up buys a positive amount of credits: $credits" }
        return maxOf(unit(currency, removeCents), valueOf(credits, rateMinor, fxRate, currency, RoundingMode.HALF_UP, removeCents))
    }

    /** Credits a creator payout of [value] (base currency, no exchange rate) comes to: `HALF_UP`. */
    fun payoutCredits(value: Long, rateMinor: Long): Long =
        creditsFor(value, rateMinor, BigDecimal.ONE, RoundingMode.HALF_UP)

    private fun requireRate(rateMinor: Long, fxRate: BigDecimal) {
        require(rateMinor >= 1) { "the credit rate must be at least 1: $rateMinor" }
        require(fxRate.signum() > 0) { "the exchange rate must be positive: $fxRate" }
    }

    private fun requireMode(mode: RoundingMode) =
        require(mode in MODES) { "credit amounts round HALF_UP, FLOOR or CEILING only: $mode" }
}
