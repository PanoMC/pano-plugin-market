package com.panomc.plugins.market.core.pricing

import com.panomc.plugins.market.config.MultiCurrencyFallback
import com.panomc.plugins.market.core.money.Conversions
import com.panomc.plugins.market.core.money.Rounding
import java.math.BigDecimal

/** A line after stage A1: its catalogue unit price in the order currency, or excluded with an error. */
internal class ListedLine(
    val line: LineInput,
    val listUnitPrice: Long,
    val excluded: Boolean,
    val errors: List<PricingCode>
)

/** Stage A1, list unit price (05 section 4.2). */
internal object ListPrice {
    /**
     * ```
     * c == base                          -> roundQ(basePrice, oq)
     * MULTI, c != base:
     *   1./2. LineInput.currencyPrices[c] (resolved by the caller, see CurrencyPriceResolver) -> roundQ(price, oq)
     *   3. fallback CONVERT              -> toOrder(basePrice)
     *   4. fallback HIDE                 -> line error NOT_IN_CURRENCY, excluded from every sum
     * CREDIT_TOPUP                       -> max(oq, toOrder(halfUp(topUpCredits * cv / 100)))
     * ```
     */
    fun list(line: LineInput, conversions: Conversions, config: PricingConfig): ListedLine {
        val oq = conversions.oq
        if (line.kind == LineKind.CREDIT_TOPUP) {
            // validated: topUpCredits > 0, creditValue > 0
            val money = Rounding.ratioQ(
                BigDecimal.valueOf(line.topUpCredits!!).multiply(BigDecimal.valueOf(config.creditValue)),
                BigDecimal(100),
                1L
            )
            return ListedLine(line, maxOf(oq, conversions.toOrder(money)), excluded = false, errors = emptyList())
        }

        if (conversions.orderCurrency == conversions.baseCurrency) {
            return ListedLine(line, Rounding.roundQ(line.basePrice, oq), excluded = false, errors = emptyList())
        }

        val explicit = line.currencyPrices[conversions.orderCurrency]
        return when {
            explicit != null -> ListedLine(line, Rounding.roundQ(explicit, oq), false, emptyList())
            config.multiCurrencyFallback == MultiCurrencyFallback.CONVERT ->
                ListedLine(line, conversions.toOrder(line.basePrice), false, emptyList())
            else -> ListedLine(line, 0L, excluded = true, errors = listOf(PricingCode.NOT_IN_CURRENCY))
        }
    }
}

/**
 * Steps 1 and 2 of 05 section 4.2, for the callers that load `market_product_price` rows (checkout, store query,
 * catalogue pricer): the explicit foreign price of a line, or null (the engine then converts or hides).
 *
 * 1. the row of (variant, currency);
 * 2. when the line has a variant that **inherits** the product's base price: the product-level row (variant 0).
 *    A variant with its own base price never takes the product-level foreign price: that would be the wrong price.
 */
object CurrencyPriceResolver {
    class PriceRow(val variantId: Long, val currency: String, val price: Long)

    fun resolve(currency: String, variantId: Long, variantHasOwnBasePrice: Boolean, rows: Collection<PriceRow>): Long? {
        rows.firstOrNull { it.variantId == variantId && it.currency == currency }?.let { return it.price }
        if (variantId != 0L && !variantHasOwnBasePrice) {
            rows.firstOrNull { it.variantId == 0L && it.currency == currency }?.let { return it.price }
        }
        return null
    }
}
