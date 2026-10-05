package com.panomc.plugins.market.core.pricing

import com.panomc.plugins.market.config.CurrencyMode
import com.panomc.plugins.market.core.money.Currencies
import java.math.BigDecimal
import java.util.Locale

/** Order currency, rate and display block of one order (05 section 4.1) plus the warnings that came with it. */
internal class OrderCurrency(
    val currency: String,
    /** Order-currency units per 1 base unit; 1 for the base currency. */
    val fx: BigDecimal,
    val displayCurrency: String?,
    val displayRate: BigDecimal?,
    val messages: List<PricingMessage>
)

internal object OrderCurrencies {
    /**
     * | mode | order currency | display |
     * |---|---|---|
     * | `SINGLE` | base | none; a foreign request is ignored with `CURRENCY_NOT_SUPPORTED` |
     * | `DISPLAY` | base | the requested currency when it is offered (additional currency with a rate) |
     * | `MULTI` | the requested currency when it is the base or offered, else base + the warning | none |
     *
     * A currency without a positive `market_currency_rate` is never offered: FIXED amounts, fees and thresholds need
     * `fx` even when every product has an explicit price there.
     */
    fun resolve(config: PricingConfig, requested: String?): OrderCurrency {
        val base = config.baseCurrency
        val wanted = requested?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        val foreign = wanted != null && wanted != base
        val rate = wanted?.let { offeredRate(config, it) }
        val unsupported = listOf(PricingMessage(PricingCode.CURRENCY_NOT_SUPPORTED))

        return when (config.currencyMode) {
            CurrencyMode.SINGLE ->
                OrderCurrency(base, BigDecimal.ONE, null, null, if (foreign) unsupported else emptyList())

            CurrencyMode.DISPLAY ->
                when {
                    !foreign -> OrderCurrency(base, BigDecimal.ONE, null, null, emptyList())
                    rate != null -> OrderCurrency(base, BigDecimal.ONE, wanted, rate, emptyList())
                    else -> OrderCurrency(base, BigDecimal.ONE, null, null, unsupported)
                }

            CurrencyMode.MULTI ->
                when {
                    !foreign -> OrderCurrency(base, BigDecimal.ONE, null, null, emptyList())
                    rate != null -> OrderCurrency(wanted!!, rate, null, null, emptyList())
                    else -> OrderCurrency(base, BigDecimal.ONE, null, null, unsupported)
                }
        }
    }

    /** The rate of [currency] when it is an additional, supported currency with a positive rate; null otherwise. */
    private fun offeredRate(config: PricingConfig, currency: String): BigDecimal? {
        if (currency == config.baseCurrency) return null
        if (currency !in config.additionalCurrencies || !Currencies.isSupported(currency)) return null
        return config.rates[currency]?.takeIf { it.signum() > 0 }
    }
}
