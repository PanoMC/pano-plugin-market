package com.panomc.plugins.market.core.money

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * The one place where market money is rounded (05 section 2; 00 section 6.6).
 *
 * Amounts are `Long` x 100 (a fixed scale, not the ISO minor unit), percentages are basis points (`bp`, 10 000 = 100 %),
 * rates are [BigDecimal]. Every intermediate product and quotient is exact; each primitive rounds **once**, half up,
 * at the end. There is no floating point anywhere in this package (`RoundingTest` scans the sources for it).
 *
 * The **quantum** `q` of a currency is the smallest payable step: 100 for a zero-decimal currency or when the store
 * removes cents, 1 otherwise, always 1 for credits. A result of [roundQ], [pctQ], [vatInside], [vatOnTop],
 * [floorQ] and [allocate] is a multiple of `q`.
 *
 * Inputs are never negative (05 section 2 rule 1) and basis points lie in `0..10000` (rule 4); a violation is a
 * caller bug and throws [IllegalArgumentException]. A result that does not fit a `Long` throws [ArithmeticException]
 * (the pricing engine maps it to `PricingException(AMOUNT_OVERFLOW)`).
 */
object Rounding {
    /** Pseudo currency code of the credit unit; its quantum is always 1 (credits keep two decimals). */
    const val CREDITS = "CREDITS"

    private const val BP_ONE = 10_000L
    private val BP_DIVISOR = BigDecimal(BP_ONE)

    /**
     * Quantum of [currency] in a store (05 section 2): 100 when the currency has no decimals or [removeCents] is on,
     * 1 otherwise; credits are always 1. Throws [IllegalArgumentException] for an unsupported currency code.
     */
    fun quantum(currency: String, removeCents: Boolean): Long {
        if (currency == CREDITS) return 1L
        val zeroDecimal = Currencies.exponent(currency) == 0
        return if (zeroDecimal || removeCents) 100L else 1L
    }

    /** `RoundingMode.HALF_UP` to an integer. */
    fun halfUp(x: BigDecimal): Long {
        require(x.signum() >= 0) { "money amounts are never negative: $x" }
        return x.setScale(0, RoundingMode.HALF_UP).longValueExact()
    }

    /** `round(x / q) * q`, half up, one rounding. */
    fun roundQ(x: BigDecimal, q: Long): Long {
        requireQuantum(q)
        require(x.signum() >= 0) { "money amounts are never negative: $x" }
        return Math.multiplyExact(x.divide(BigDecimal.valueOf(q), 0, RoundingMode.HALF_UP).longValueExact(), q)
    }

    /** `roundQ(x, q)` for a whole amount. */
    fun roundQ(x: Long, q: Long): Long = roundQ(BigDecimal.valueOf(x), q)

    /** `floor(x / q) * q`. */
    fun floorQ(x: BigDecimal, q: Long): Long {
        requireQuantum(q)
        require(x.signum() >= 0) { "money amounts are never negative: $x" }
        return Math.multiplyExact(x.divide(BigDecimal.valueOf(q), 0, RoundingMode.FLOOR).longValueExact(), q)
    }

    /**
     * `roundQ(numerator / denominator, q)` as one division, so a non-terminating quotient (VAT inside a gross
     * amount, a rate that does not divide) is rounded exactly once instead of after a truncated division.
     */
    fun ratioQ(numerator: BigDecimal, denominator: BigDecimal, q: Long): Long {
        requireQuantum(q)
        require(numerator.signum() >= 0) { "money amounts are never negative: $numerator" }
        require(denominator.signum() > 0) { "denominator must be positive: $denominator" }
        val quotient = numerator.divide(denominator.multiply(BigDecimal.valueOf(q)), 0, RoundingMode.HALF_UP)
        return Math.multiplyExact(quotient.longValueExact(), q)
    }

    /** `roundQ(amount * bp / 10000, q)`: a percentage of an amount, exact product, one rounding. */
    fun pctQ(amount: Long, bp: Long, q: Long): Long {
        requireAmount(amount)
        requireBp(bp)
        return ratioQ(BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(bp)), BP_DIVISOR, q)
    }

    /** VAT contained in a gross amount: `roundQ(gross * bp / (10000 + bp), q)`. */
    fun vatInside(gross: Long, bp: Long, q: Long): Long {
        requireAmount(gross)
        requireBp(bp)
        return ratioQ(
            BigDecimal.valueOf(gross).multiply(BigDecimal.valueOf(bp)),
            BigDecimal.valueOf(BP_ONE + bp),
            q
        )
    }

    /** VAT added on top of a net amount: `roundQ(net * bp / 10000, q)`. */
    fun vatOnTop(net: Long, bp: Long, q: Long): Long = pctQ(net, bp, q)

    /**
     * Largest-remainder allocation of [amount] over [weights] (05 section 6.2), deterministic.
     *
     * ```
     * n = amount / q;  W = sum(weights)
     * share_i = floor(n * w_i / W) * q
     * the n - sum(share_i / q) leftover quanta go one each to the lines with the largest (n * w_i mod W),
     * ties to the lower index
     * ```
     * The shares always add up to exactly [amount], and `share_i <= w_i` holds because `amount <= W` and every value is
     * a multiple of [q] (both are required, a violation throws [IllegalArgumentException]).
     */
    fun allocate(amount: Long, weights: List<Long>, q: Long): List<Long> {
        requireQuantum(q)
        requireAmount(amount)
        require(amount % q == 0L) { "the amount to allocate must be a multiple of the quantum: $amount % $q" }
        var total = 0L
        for (w in weights) {
            requireAmount(w)
            require(w % q == 0L) { "every weight must be a multiple of the quantum: $w % $q" }
            total = Math.addExact(total, w)
        }
        require(amount <= total) { "cannot allocate $amount over weights that add up to $total" }
        if (amount == 0L) return weights.map { 0L }

        val n = BigInteger.valueOf(amount / q)
        val whole = BigInteger.valueOf(total)
        val floors = LongArray(weights.size)
        val remainders = arrayOfNulls<BigInteger>(weights.size)
        var given = 0L
        for ((i, w) in weights.withIndex()) {
            val qr = n.multiply(BigInteger.valueOf(w)).divideAndRemainder(whole)
            floors[i] = qr[0].longValueExact()
            remainders[i] = qr[1]
            given = Math.addExact(given, floors[i])
        }
        var leftover = amount / q - given
        // Largest remainder first, ties to the lower index; the sort is stable on the index tie-break below.
        val order = weights.indices.sortedWith(
            Comparator<Int> { a, b -> remainders[b]!!.compareTo(remainders[a]!!) }.thenBy { it }
        )
        for (i in order) {
            if (leftover == 0L) break
            floors[i] += 1
            leftover -= 1
        }
        check(leftover == 0L) { "allocation left $leftover quanta unassigned" }
        return floors.map { Math.multiplyExact(it, q) }
    }

    private fun requireQuantum(q: Long) = require(q > 0) { "the quantum must be positive: $q" }

    private fun requireAmount(amount: Long) = require(amount >= 0) { "money amounts are never negative: $amount" }

    private fun requireBp(bp: Long) = require(bp in 0..BP_ONE) { "basis points must lie in 0..10000: $bp" }
}
