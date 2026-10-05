package com.panomc.plugins.market.core.pricing

import com.panomc.plugins.market.core.money.Conversions
import com.panomc.plugins.market.core.money.Rounding
import com.panomc.plugins.market.spi.common.Money
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Mixed payment (05 section 8.2, owner decision: never automatic): credits are tender against the money total at
 * `creditValue`, the gateway collects the rest. **A mixed order always leaves a gateway remainder**: covering a whole
 * order with credits is `payWithCredits` (05 section 8.1), which charges `creditPrice`; otherwise a product that is "not
 * sold for credits" (`creditPrice` 0) would still be fully payable with credits, and buyers would always pick the cheaper
 * of the two rates.
 *
 * ```
 * T0            = itemsTotal + shippingTotal                       (the total without the payment fee)
 * minRemainder  = max(oq, the selected provider's minimum in the order currency, rounded up to the quantum)
 * capValue      = T0 - minRemainder                                (<= 0: nothing can be applied)
 * maxByTotal    = floor(capValue x 100 / (cv x fx))                (credits x 100)
 * maxApplicable = min(creditBalance, maxByTotal)
 * applied       = maxApplicable                                     for "MAX" (the quote only)
 *               = min(useCredits, maxApplicable)                    for a number: the quote clamps (CREDITS_REDUCED),
 *                                                                   checkout and /pay refuse a number above it
 * appliedValue  = min(roundQ(applied x cv x fx / 100, oq), capValue)    (0 => applied = 0)
 * G0            = T0 - appliedValue                                 (the fee base, 05 section 9.2)
 * ```
 */
object MixedPayment {
    /** The request of the buyer: no credits, the maximum ("MAX", the quote only) or an exact number of credits x 100. */
    const val MAX = Long.MAX_VALUE

    /**
     * What a mixed payment comes to. [reduced] is a number the quote clamped to [maxApplicable]; [rejected] is a number
     * that checkout or `/pay` refuse because it is above [maxApplicable] (400 `INSUFFICIENT_CREDITS {balance, maxApplicable}`;
     * nothing is applied then).
     */
    data class Result(
        val maxApplicable: Long,
        val applied: Long,
        val appliedValue: Long,
        /** `T0 - appliedValue`: what the gateway collects before the fee. */
        val remainder: Long,
        val reduced: Boolean,
        val rejected: Boolean
    )

    /**
     * Runs the algorithm. [t0] is the total without fee in the order currency, [providerMinimum] the selected provider's
     * minimum already in the order currency (null = none, see [ProviderLimits]), [request] credits x 100 ([MAX], null or 0 =
     * none), [strict] true for checkout and `/pay`.
     */
    fun apply(conversions: Conversions, t0: Long, providerMinimum: Long?, creditBalance: Long, request: Long?, strict: Boolean): Result {
        require(t0 >= 0) { "the total is never negative: $t0" }
        require(creditBalance >= 0) { "the credit balance is never negative: $creditBalance" }
        if (request != null && request < 0) throw PricingException(PricingError.INVALID_INPUT, "useCredits is negative")
        if (strict && request == MAX) throw PricingException(PricingError.INVALID_INPUT, "MAX is accepted on the quote only")

        val oq = conversions.oq
        val minRemainder = maxOf(oq, providerMinimum?.let { ceilToQuantum(it, oq) } ?: 0L)
        val capValue = Math.subtractExact(t0, minRemainder)
        val maxApplicable = if (capValue <= 0L) 0L else minOf(creditBalance, floorCredits(conversions, capValue, creditBalance))

        val wanted = request ?: 0L
        var reduced = false
        var rejected = false
        var applied = when {
            wanted == 0L -> 0L
            wanted == MAX -> maxApplicable
            wanted <= maxApplicable -> wanted
            strict -> {
                rejected = true
                0L
            }
            else -> {
                reduced = true
                maxApplicable
            }
        }
        var appliedValue = 0L
        if (applied > 0L) {
            appliedValue = minOf(creditValue(conversions, applied), capValue)
            if (appliedValue <= 0L) applied = 0L // credits so small that their value rounds to nothing are not spent
        }
        return Result(maxApplicable, applied, appliedValue, Math.subtractExact(t0, appliedValue), reduced, rejected)
    }

    /** The money value of [credits] in the order currency, rounded half up to the quantum (the value covered by credits of a mixed order). */
    fun creditValue(conversions: Conversions, credits: Long): Long =
        Rounding.roundQ(
            BigDecimal.valueOf(credits).multiply(BigDecimal.valueOf(conversions.creditValue)).multiply(conversions.fx)
                .divide(BigDecimal(100)),
            conversions.oq
        )

    /**
     * `floor(value x 100 / (cv x fx))`: the most credits x 100 whose exact value does not exceed [value]; at most [bound]
     * (a tiny exchange rate must not overflow a `Long` when the balance is the real limit).
     */
    private fun floorCredits(conversions: Conversions, value: Long, bound: Long): Long {
        val credits = BigDecimal.valueOf(value).multiply(BigDecimal(100))
            .divide(BigDecimal.valueOf(conversions.creditValue).multiply(conversions.fx), 0, RoundingMode.FLOOR)
        return if (credits >= BigDecimal.valueOf(bound)) bound else credits.longValueExact()
    }

    private fun ceilToQuantum(amount: Long, q: Long): Long {
        val rest = amount % q
        return if (rest == 0L) amount else Math.addExact(amount, q - rest)
    }
}

/** The payment provider's hard limits (`PaymentCapabilities.minAmount` / `maxAmount`) in the order currency (05 section 9.5 check 6). */
internal object ProviderLimits {
    /**
     * Limit currency equal to the order currency: direct; equal to the base currency: `toOrder`; any other currency with a
     * known rate: through the base; otherwise null (the limit is skipped, not guessed).
     */
    fun inOrderCurrency(limit: Money?, conversions: Conversions, rates: Map<String, BigDecimal>): Long? {
        if (limit == null) return null
        return when (val currency = limit.currency) {
            conversions.orderCurrency -> limit.amount
            conversions.baseCurrency -> conversions.toOrder(limit.amount)
            else -> {
                val rate = rates[currency]?.takeIf { it.signum() > 0 } ?: return null
                conversions.toOrder(Rounding.ratioQ(BigDecimal.valueOf(limit.amount), rate, conversions.bq))
            }
        }
    }
}
