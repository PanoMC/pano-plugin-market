package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity
import java.math.BigDecimal

enum class CurrencyRateMode {
    AUTO, MANUAL
}

/** `market_currency_rate` (01 section 2.9): units of [currency] per 1 base-currency unit. */
open class MarketCurrencyRate(
    val id: Long = -1,
    val currency: String = "",
    val rate: BigDecimal = BigDecimal.ONE,
    val mode: CurrencyRateMode = CurrencyRateMode.AUTO,
    val fetchedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
