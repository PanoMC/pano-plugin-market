package com.panomc.plugins.market.core.shipping

import com.panomc.plugins.market.core.money.Conversions
import com.panomc.plugins.market.core.money.Rounding
import java.math.BigDecimal

/** Where a shipping price came from (`shippingQuote.source`). */
enum class RateSource { RULES, CARRIER, FALLBACK }

/**
 * Currency rates of one quote (10 section 5.6): units of a currency per 1 base unit. The base currency is 1, the order
 * currency is the order's `fxRate`, any other currency is a `market_currency_rate` row; a missing row means the
 * conversion fails.
 */
class RateTable(
    val baseCurrency: String,
    val orderCurrency: String,
    val orderRate: BigDecimal,
    private val others: Map<String, BigDecimal> = emptyMap()
) {
    fun rate(currency: String): BigDecimal? = when (currency) {
        baseCurrency -> BigDecimal.ONE
        orderCurrency -> orderRate
        else -> others[currency]?.takeIf { it.signum() > 0 }
    }

    /** `halfUp(amount x rate(to) / rate(from))` as one division; equal currencies return [amount]; `null` for a missing rate. */
    fun convert(amount: Long, from: String, to: String): Long? {
        require(amount >= 0) { "money amounts are never negative: $amount" }
        if (from == to) return amount

        val rateFrom = rate(from) ?: return null
        val rateTo = rate(to) ?: return null

        return Rounding.ratioQ(BigDecimal.valueOf(amount).multiply(rateTo), rateFrom, 1)
    }
}

/** The terms of a method that matter for its price. [freeShippingThreshold] and [handlingFee] are base-currency x100. */
class ShippingTerms(val freeShippingThreshold: Long?, val handlingFee: Long, val vatBp: Long?)

/** The raw price of a method before handling, VAT and conversion. */
class RawRate(val amount: Long, val currency: String, val includesTax: Boolean, val source: RateSource)

/** What the buyer pays for one option, in the order currency: [gross] includes [vat]. */
class ShippingCharge(
    val gross: Long,
    val vat: Long,
    val vatBp: Long,
    val free: Boolean,
    /** The raw rate converted to the order currency (0 when free). */
    val carrierPart: Long,
    /** The handling fee in the order currency (0 when free). */
    val handlingPart: Long,
    val source: RateSource
)

/** `ShippingPriceCalculator.compute` (10 section 5.3 step 5). Pure. */
object ShippingPriceCalculator {
    /**
     * [shippableValue] is the physical price basis in the order currency (10 section 5.2); the threshold compares with
     * it converted to base. Returns `null` when the carrier amount cannot be converted (treat as no live rate).
     *
     * A free option has gross 0 and vat 0 and waives the handling fee. Otherwise the raw rate (inclusive of VAT as
     * [RawRate.includesTax] says) and the handling fee (as [pricesIncludeVat] says) are split at the method's VAT
     * rate (`vatBp`, else [configVatBp]) and added. When the order quantum is above 1 (zero-decimal currency or
     * removed cents) the gross is rounded **up** to that quantum and the VAT recomputed as inclusive on it, at that quantum (always, even when aligned).
     */
    fun compute(
        terms: ShippingTerms,
        raw: RawRate,
        shippableValue: Long,
        pricesIncludeVat: Boolean,
        configVatBp: Long,
        conversions: Conversions,
        rates: RateTable
    ): ShippingCharge? {
        val bp = terms.vatBp ?: configVatBp
        val threshold = terms.freeShippingThreshold

        if (threshold != null && toBase(shippableValue, conversions) >= threshold) {
            return ShippingCharge(0, 0, bp, true, 0, 0, raw.source)
        }

        val carrierPart = rates.convert(raw.amount, raw.currency, conversions.orderCurrency) ?: return null
        val handlingPart = conversions.toOrder(terms.handlingFee)
        val first = ShippingVat.split(carrierPart, bp, raw.includesTax)
        val second = ShippingVat.split(handlingPart, bp, pricesIncludeVat)

        var gross = Math.addExact(first.gross, second.gross)
        var vat = Math.addExact(first.vat, second.vat)
        val q = conversions.oq

        if (q > 1) {
            // Always recomputed on the quantum: an already aligned gross can still carry a quantum-1 VAT (05 section 9.1).
            gross = Math.multiplyExact(Math.floorDiv(gross + q - 1, q), q)
            vat = Rounding.vatInside(gross, bp, q)
        }

        return ShippingCharge(gross, vat, bp, false, carrierPart, handlingPart, raw.source)
    }

    /** `halfUp(x / fx)`: an order-currency amount in the base currency, one rounding, no quantum. */
    fun toBase(orderAmount: Long, conversions: Conversions): Long =
        Rounding.ratioQ(BigDecimal.valueOf(orderAmount), conversions.fx, 1)
}
