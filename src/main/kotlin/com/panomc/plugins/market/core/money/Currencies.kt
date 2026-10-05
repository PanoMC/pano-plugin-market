package com.panomc.plugins.market.core.money

import com.panomc.plugins.market.spi.common.Currencies as SpiCurrencies

/**
 * Market's name for the currency table (00 section 6.5; replaces the four-value `CurrencyType` enum). The data
 * lives in [SpiCurrencies] because `spi.common.Money` depends on it; this object only forwards, so market code
 * and plugins always see the same answers.
 */
object Currencies {
    fun isSupported(code: String): Boolean = SpiCurrencies.isSupported(code)

    fun exponent(code: String): Int = SpiCurrencies.exponent(code)

    fun symbol(code: String): String = SpiCurrencies.symbol(code)

    fun all(): List<String> = SpiCurrencies.all()
}
