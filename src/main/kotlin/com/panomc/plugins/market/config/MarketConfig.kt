package com.panomc.plugins.market.config

import com.panomc.platform.api.config.ConfigComment
import com.panomc.platform.api.config.ConfigSection
import com.panomc.platform.api.config.PluginConfig
import com.panomc.plugins.market.util.CurrencyType
import com.panomc.plugins.market.util.ExchangeRateMode

class MarketConfig(
    @ConfigSection("Store")
    val storeName: String = "Market",
    val storeDescription: String = "",
    @ConfigComment("Store currency. One of: TRY, USD, EUR, GBP")
    val currency: CurrencyType = CurrencyType.TRY,
    @ConfigComment("Currency the stats/reports are displayed in. One of: TRY, USD, EUR, GBP")
    val statsCurrency: CurrencyType = CurrencyType.TRY,
    @ConfigComment("How the sales -> stats exchange rate is maintained. One of: AUTO, MANUAL")
    val exchangeRateMode: ExchangeRateMode = ExchangeRateMode.AUTO,
    @ConfigComment("Current view rate: stats-currency units per 1 sales-currency unit.")
    val exchangeRate: Double = 1.0,
    @ConfigComment("Epoch millis the exchange rate was last updated.")
    val exchangeRateUpdatedAt: Long = 0,
    @ConfigComment("Hours between automatic exchange-rate refreshes (AUTO mode).")
    val exchangeRateAutoIntervalHours: Int = 6,
    val vatPercent: Double = 20.0,
    @ConfigComment("Whether displayed prices already include VAT.")
    val showVatInPrice: Boolean = true,
    val testMode: Boolean = false,
    val allowGuestCheckout: Boolean = true,
    val minimumOrderAmount: Double = 0.0,
    val removeCents: Boolean = false,
    val showBestsellers: Boolean = true,
    val showFeaturedProducts: Boolean = true,
    val showComparisons: Boolean = true,
    val sendEmailAfterPurchase: Boolean = true,
    @ConfigComment("Whether automatic discounts and coupon codes can apply to the same order.")
    val combineDiscountsAndCoupons: Boolean = true,
    @ConfigSection("Credits")
    val creditsEnabled: Boolean = true,
    @ConfigComment("Display name of the credit currency. Leave empty to use the site language's localized default (\"Credits\").")
    val creditName: String = "",
    val cashbackPercent: Double = 0.0,
    val onlyAcceptCredits: Boolean = false,
    version: Int = 1
) : PluginConfig(version)
