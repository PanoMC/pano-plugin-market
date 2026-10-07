package com.panomc.plugins.market.config

import com.panomc.platform.api.config.ConfigComment
import com.panomc.platform.api.config.ConfigSection
import com.panomc.platform.api.config.PluginConfig
import com.panomc.plugins.market.util.ExchangeRateMode

class MarketConfig(
    @ConfigSection("Store")
    val storeName: String = "Market",
    val storeDescription: String = "",
    @ConfigComment("false: the storefront endpoints answer STORE_DISABLED; the panel is unaffected.")
    val storeEnabled: Boolean = true,
    @ConfigComment("Time zone of the store day (e.g. Europe/Istanbul). Empty = the server's default zone.")
    val storeTimeZone: String = "",
    @ConfigComment("One of: SINGLE, DISPLAY, MULTI")
    val currencyMode: CurrencyMode = CurrencyMode.SINGLE,
    @ConfigComment("ISO 4217 codes offered besides the store currency in DISPLAY / MULTI mode.")
    val additionalCurrencies: List<String> = emptyList(),
    @ConfigComment("Products without a price in the buyer's currency. One of: CONVERT, HIDE")
    val multiCurrencyFallback: MultiCurrencyFallback = MultiCurrencyFallback.CONVERT,
    @ConfigComment("Store currency. ISO 4217 code supported by the store engine (e.g. TRY, USD, EUR, GBP, JPY).")
    val currency: String = "TRY",
    @ConfigComment("Currency the stats/reports are displayed in. ISO 4217 code supported by the store engine (e.g. TRY, USD, EUR, GBP, JPY).")
    val statsCurrency: String = "TRY",
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
    @ConfigComment("Mail kinds switched off individually.")
    val mailDisabledKinds: List<String> = emptyList(),
    val mailAttachInvoice: Boolean = true,
    val mailReplyTo: String = "",
    val mailOrderDeliveredDelayMinutes: Int = 10,
    val allowGiftPurchase: Boolean = true,
    @ConfigComment("One of: OFF, OPTIONAL, REQUIRED")
    val billingInfoMode: BillingInfoMode = BillingInfoMode.OPTIONAL,
    val legalTextRequired: Boolean = false,
    @ConfigComment("Lifetime of a PENDING order in minutes.")
    val orderExpiryMinutes: Int = 60,
    val bankTransferExpiryHours: Int = 72,
    val autoRefundDuplicatePayments: Boolean = true,
    @ConfigComment("Whether automatic discounts and coupon codes can apply to the same order.")
    val combineDiscountsAndCoupons: Boolean = true,
    @ConfigSection("Invoices")
    val invoiceEnabled: Boolean = true,
    @ConfigComment("1-8 characters A-Z, 0-9; not TEST; different from the credit note series.")
    val invoiceSeries: String = "INV",
    val invoiceSellerName: String = "",
    val invoiceSellerAddress: String = "",
    val invoiceSellerTaxOffice: String = "",
    val invoiceSellerTaxNumber: String = "",
    val invoiceFooter: String = "",
    val invoiceCreditNoteSeries: String = "CN",
    val invoiceCreditOrders: Boolean = false,
    @ConfigComment("Fixed invoice locale. Empty = the order's locale.")
    val invoiceLocale: String = "",
    val invoiceShowLogo: Boolean = true,
    @ConfigSection("Credits")
    val creditsEnabled: Boolean = true,
    @ConfigComment("Display name of the credit currency. Leave empty to use the site language's localized default (\"Credits\").")
    val creditName: String = "",
    val cashbackPercent: Double = 0.0,
    val onlyAcceptCredits: Boolean = false,
    @ConfigComment("Base-currency value of one credit (>= 0.01).")
    val creditValue: Double = 1.0,
    val allowMixedCreditPayment: Boolean = false,
    val creditTopUpEnabled: Boolean = false,
    val creditTopUpFreeAmount: Boolean = false,
    val creditTopUpMin: Double = 1.0,
    val creditTopUpMax: Double = 10000.0,
    val revokeOnRefund: Boolean = true,
    val revokeOnChargeback: Boolean = true,
    @ConfigSection("Delivery and subscriptions")
    val deliveryMaxAttempts: Int = 5,
    @ConfigComment("Give up waiting for an offline player after N days (0 = never).")
    val deliveryOnlineWaitDays: Int = 0,
    val deliveryAckTimeoutSeconds: Int = 30,
    val subscriptionGraceDays: Int = 3,
    val subscriptionReminderDays: Int = 3,
    val subscriptionManualFallback: Boolean = true,
    @ConfigSection("Abuse and chargebacks")
    val autoBlockOnChargeback: Boolean = true,
    val revokeCreditOrdersOnTopUpChargeback: Boolean = true,
    @ConfigComment("Days a creator earning stays PENDING after payment.")
    val creatorEarningHoldDays: Int = 14,
    @ConfigComment("JSON action array run on a chargeback.")
    val chargebackActions: String = "[]",
    @ConfigComment("0 disables the limit.")
    val checkoutRateLimitPerMinute: Int = 6,
    val quoteRateLimitPerMinute: Int = 60,
    val couponLockThreshold: Int = 5,
    val couponLockMinutes: Int = 15,
    val allowPrivateWebhookTargets: Boolean = false,
    @ConfigSection("Storefront")
    val storePageSize: Int = 24,
    val moduleRecentBuyers: Boolean = true,
    val moduleRecentBuyersCount: Int = 10,
    val moduleRecentBuyersShowAmount: Boolean = false,
    val moduleTopSupporters: Boolean = true,
    @ConfigComment("One of: MONTH, ALL_TIME")
    val moduleTopSupportersPeriod: TopSupportersPeriod = TopSupportersPeriod.MONTH,
    val moduleTopSupportersCount: Int = 5,
    val moduleGoal: Boolean = true,
    val moduleSaleBadges: Boolean = true,
    val moduleSaleCountdown: Boolean = true,
    val moduleStats: Boolean = false,
    @ConfigComment("Host sidebars the market widgets render in: home, profile.")
    val moduleSidebars: List<String> = listOf("home"),
    @ConfigSection("In-game")
    val mcStoreCommand: Boolean = true,
    val mcCreditsCommand: Boolean = true,
    val mcJoinNotifications: Boolean = true,
    val mcStoreMenu: Boolean = true,
    val mcAdminCommands: Boolean = true,
    val mcPlaceholders: Boolean = true,
    val mcLuckPerms: Boolean = true,
    val mcBroadcast: Boolean = false,
    @ConfigComment("Placeholders: {player} {product} {quantity} {store}; colour codes with &.")
    val mcBroadcastTemplate: String = DEFAULT_BROADCAST_TEMPLATE,
    @ConfigComment("In-game admin sub-commands switched off: give-credits, take-credits, set-credits, grant-product, purchases.")
    val mcDisabledAdminCommands: List<String> = emptyList(),
    @ConfigComment("One of: OFF, PROVIDER, CONVERT")
    val mcVaultMode: VaultMode = VaultMode.OFF,
    @ConfigComment("Server-economy units per credit.")
    val mcVaultRate: Double = 1.0,
    @ConfigComment("One of: BOTH, TO_SERVER, TO_CREDITS")
    val mcVaultDirection: VaultDirection = VaultDirection.BOTH,
    version: Int = 1
) : PluginConfig(version) {
    companion object {
        const val DEFAULT_BROADCAST_TEMPLATE = "&a{player} &7bought &e{product}&7 from the &b{store}&7!"
    }
}

enum class CurrencyMode { SINGLE, DISPLAY, MULTI }

enum class MultiCurrencyFallback { CONVERT, HIDE }

enum class BillingInfoMode { OFF, OPTIONAL, REQUIRED }

enum class TopSupportersPeriod { MONTH, ALL_TIME }

enum class VaultMode { OFF, PROVIDER, CONVERT }

enum class VaultDirection { BOTH, TO_SERVER, TO_CREDITS }
