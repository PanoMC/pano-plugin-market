package com.panomc.plugins.market.core.pricing

import com.panomc.plugins.market.config.CurrencyMode
import com.panomc.plugins.market.config.MultiCurrencyFallback
import com.panomc.plugins.market.db.model.PaymentFeeMode
import com.panomc.plugins.market.db.model.UpgradeMode
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.payment.PriceAuthority
import com.panomc.plugins.market.util.CouponScope
import com.panomc.plugins.market.util.DiscountScope
import com.panomc.plugins.market.util.DiscountUnit
import java.math.BigDecimal

/*
 * The input model of the pricing engine (05 section 3). Plain values: the caller (checkout, store query, panel
 * manual order, gift redeem, renewal) loads everything and passes it in; the engine never reads a clock, a
 * database or a config. Money is `Long` x 100 (00 section 6.1), percentages are basis points, rates are BigDecimal.
 * `MarketConfig` doubles (vatPercent, minimumOrderAmount, creditValue, cashbackPercent) are converted once by the
 * caller with `MoneyUtil.toMinor`.
 */

/** What a cart line is (05 section 3). `CREDIT_TOPUP` is the synthetic free-amount credit purchase, `productId == null`. */
enum class LineKind { PRODUCT, BUNDLE, CREDIT_PACK, CREDIT_TOPUP }

/** `market_order.source` as far as pricing is concerned (05 section 12). `EXTERNAL` totals bypass the engine. */
enum class PricingProfile(
    /** Automatic discounts (stage A2) apply. */
    val discounts: Boolean,
    /** Coupons and creator codes (stage A3) apply. */
    val codes: Boolean,
    /** The tier upgrade deduction (stage A2) applies. */
    val upgrade: Boolean,
    /** `minimumOrderAmount` is checked. */
    val minimumOrder: Boolean
) {
    STOREFRONT(discounts = true, codes = true, upgrade = true, minimumOrder = true),
    PANEL(discounts = true, codes = false, upgrade = true, minimumOrder = false),
    GIFT_CODE(discounts = false, codes = false, upgrade = false, minimumOrder = false),
    INGAME(discounts = true, codes = false, upgrade = true, minimumOrder = false),
    RENEWAL(discounts = false, codes = false, upgrade = false, minimumOrder = false)
}

/** Who sets the price: `MARKET` (always, unless the selected method says otherwise), or the gateway (`PriceAuthority`). */
enum class PricingMode { MARKET, EXTERNAL_TAX, EXTERNAL }

class PricingConfig(
    val baseCurrency: String,
    val currencyMode: CurrencyMode,
    val additionalCurrencies: List<String>,
    val multiCurrencyFallback: MultiCurrencyFallback,
    /** `market_currency_rate`: currency units per 1 base unit. A currency without a (positive) rate is never offered. */
    val rates: Map<String, BigDecimal>,
    val vatBp: Long,
    /** `showVatInPrice`: admin-entered amounts include VAT. */
    val pricesIncludeVat: Boolean,
    val removeCents: Boolean,
    /** Base currency, price basis. */
    val minimumOrderAmount: Long,
    val combineDiscountsAndCoupons: Boolean,
    val creditsEnabled: Boolean,
    val onlyAcceptCredits: Boolean,
    /** `cv`: base minor units (x100) per 1 credit, `> 0` wherever credits are converted. */
    val creditValue: Long,
    val allowMixedCreditPayment: Boolean,
    val cashbackBp: Long
)

/** Category tier facts of a product (`market_category.tiered`, `tierRank`, `upgradeMode`; 01 section 2.1). */
class TierInfo(val categoryId: Long, val tierRank: Int, val upgradeMode: UpgradeMode)

/** One child of a bundle (`market_bundle_item`). */
class BundleChild(val productId: Long, val variantId: Long, val quantity: Int)

/** An ACTIVE entitlement of the recipient in a tiered category. [pricePaid] is base currency, already net of refunds. */
class OwnedTier(val entitlementId: Long, val tierCategoryId: Long, val tierRank: Int, val pricePaid: Long)

class LineInput(
    val lineKey: String,
    /** `null` = `CREDIT_TOPUP`. */
    val productId: Long?,
    val variantId: Long,
    val kind: LineKind,
    val quantity: Int,
    /** `variant.price ?: product.price`, base currency. */
    val basePrice: Long,
    /** Resolved per 05 section 4.2 steps 1 and 2 (see [CurrencyPriceResolver]); may be empty. */
    val currencyPrices: Map<String, Long>,
    /** `variant.creditPrice ?: product.creditPrice`. */
    val creditPrice: Long,
    /** `product.vatPercent`; `null` = [PricingConfig.vatBp]. */
    val vatBp: Long?,
    /** The product's category first, then its ancestors. */
    val categoryPath: List<Long>,
    val physical: Boolean,
    /** `billingMode == SUBSCRIPTION`. */
    val subscription: Boolean,
    val tier: TierInfo?,
    /** `CREDIT_TOPUP`: credits x 100 requested. */
    val topUpCredits: Long?,
    /** `BUNDLE`: rows of `market_bundle_item`. */
    val children: List<BundleChild>
)

class BuyerContext(
    val buyerKey: String,
    val userId: Long?,
    val loggedIn: Boolean,
    val recipientUserId: Long?,
    /** For the creator self-use check (05 section 6.3 check 3). */
    val email: String?,
    /** `0` for guests. */
    val creditBalance: Long,
    /** ACTIVE entitlements of the RECIPIENT with a tier category. */
    val recipientTiers: List<OwnedTier>
)

/** Only ACTIVE, not deleted rows are passed. */
class DiscountInput(
    val id: Long,
    val value: Long,
    val unit: DiscountUnit,
    val scope: DiscountScope,
    val productIds: Set<Long>,
    val categoryIds: Set<Long>,
    val minPaymentAmount: Long?,
    val startDate: Long?,
    val expiryDate: Long?,
    val usageLimit: Int?,
    val usedCount: Int
)

class CouponInput(
    val found: Boolean,
    val id: Long,
    val code: String,
    val active: Boolean,
    val discount: Long,
    val unit: DiscountUnit,
    val scope: CouponScope,
    val productIds: Set<Long>,
    val categoryIds: Set<Long>,
    val minPaymentAmount: Long?,
    val startDate: Long?,
    val expiryDate: Long?,
    val redeemLimit: Int?,
    val customerRedeemLimit: Int?,
    val usedCount: Int,
    /** `market_redemption` rows HELD + APPLIED for this buyer key, e-mail or recipient key (01 section 3.5). */
    val buyerUses: Int
)

class CreatorCodeInput(
    val found: Boolean,
    val id: Long,
    val code: String,
    val active: Boolean,
    val discount: Long,
    val unit: DiscountUnit,
    val commissionBp: Long,
    val creatorUserId: Long?,
    val startDate: Long?,
    val expiryDate: Long?,
    val redeemLimit: Int?,
    val usedCount: Int
)

class PricingInput(
    val config: PricingConfig,
    /** Epoch milliseconds; the window checks of discounts and codes use it. */
    val now: Long,
    val profile: PricingProfile,
    val requestedCurrency: String?,
    val lines: List<LineInput>,
    val buyer: BuyerContext,
    val discounts: List<DiscountInput>,
    val coupon: CouponInput?,
    val creatorCode: CreatorCodeInput?,
    /** From the selected method's `priceAuthority`; `MARKET` when none is selected. */
    val pricingMode: PricingMode,
    /** `CartInput.payWithCredits`, forced by `onlyAcceptCredits` (05 section 8.1). */
    val payWithCredits: Boolean,
    /** `PANEL` profile only: a gross total in the order currency (05 section 12). */
    val priceOverride: Long?
)

/** Shipping as priced by the rate engine: order currency, price basis, free threshold already applied. */
class ShippingCharge(val price: Long, val vatBp: Long?)

/** What the buyer chose to pay with. [useCredits] is credits x 100, `Long.MAX_VALUE` for "MAX", null / 0 = none. */
class TenderInput(val useCredits: Long?, val method: MethodInput?)

class MethodInput(
    val id: String,
    val feeMode: PaymentFeeMode,
    val feePercent: Long,
    val feeFixed: Long,
    val minAmount: Long?,
    val maxAmount: Long?,
    val adminCurrencies: Set<String>?,
    val providerCurrencies: Set<String>?,
    val providerMin: Money?,
    val providerMax: Money?,
    val mixedCredit: Boolean,
    val priceAuthority: PriceAuthority,
    val physicalGoods: Boolean
)
