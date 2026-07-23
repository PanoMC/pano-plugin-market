package com.panomc.plugins.market.config

import com.panomc.platform.api.config.ConfigComment
import com.panomc.platform.api.config.ConfigSection
import com.panomc.platform.api.config.PluginConfig
import com.panomc.plugins.market.util.CurrencyType

class MarketConfig(
    @ConfigSection("Store")
    val storeName: String = "Market",
    val storeDescription: String = "",
    @ConfigComment("Store currency. One of: TRY, USD, EUR, GBP")
    val currency: CurrencyType = CurrencyType.TRY,
    val vatPercent: Double = 20.0,
    @ConfigComment("Whether displayed prices already include VAT.")
    val showVatInPrice: Boolean = true,
    val testMode: Boolean = false,
    val allowGuestCheckout: Boolean = true,
    val minimumOrderAmount: Double = 0.0,
    val removeCents: Boolean = false,
    val showBestsellers: Boolean = true,
    val showFeaturedProducts: Boolean = true,
    val sendEmailAfterPurchase: Boolean = true,
    @ConfigComment("Whether automatic discounts and coupon codes can apply to the same order.")
    val combineDiscountsAndCoupons: Boolean = true,
    @ConfigSection("Credits")
    val creditsEnabled: Boolean = true,
    val creditName: String = "Kredi",
    val cashbackPercent: Double = 0.0,
    val onlyAcceptCredits: Boolean = false,
    version: Int = 1
) : PluginConfig(version)
