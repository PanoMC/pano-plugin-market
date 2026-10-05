package com.panomc.plugins.market.core.pricing

import com.panomc.plugins.market.config.CurrencyMode
import com.panomc.plugins.market.config.MultiCurrencyFallback
import com.panomc.plugins.market.core.money.Conversions
import com.panomc.plugins.market.db.model.PaymentFeeMode
import com.panomc.plugins.market.db.model.UpgradeMode
import com.panomc.plugins.market.spi.payment.PriceAuthority
import com.panomc.plugins.market.util.CouponScope
import com.panomc.plugins.market.util.DiscountScope
import com.panomc.plugins.market.util.DiscountUnit
import java.math.BigDecimal

/**
 * The fixture of 05 section 17: base `TRY`, `SINGLE`, VAT 20 %, `showVatInPrice = true`, `removeCents = false`,
 * `minimumOrderAmount = 0`, `combineDiscountsAndCoupons = true`, `creditValue = 1.00`, credits enabled, buyer logged
 * in, no method fee, no shipping; rates `USD 0.025`, `JPY 4.5`. Amounts are x100 (the table's decimals times 100).
 *
 * Shared by the pricing test classes of every stage (A1-A2 here, codes / VAT / tender in the following slices).
 */
object PricingFixtures {
    const val NOW = 1_800_000_000_000L

    val RATES: Map<String, BigDecimal> = mapOf("USD" to BigDecimal("0.025"), "JPY" to BigDecimal("4.5"))

    fun config(
        mode: CurrencyMode = CurrencyMode.SINGLE,
        additional: List<String> = listOf("USD", "JPY"),
        fallback: MultiCurrencyFallback = MultiCurrencyFallback.CONVERT,
        rates: Map<String, BigDecimal> = RATES,
        base: String = "TRY",
        vatBp: Long = 2000,
        includeVat: Boolean = true,
        removeCents: Boolean = false,
        minimumOrder: Long = 0,
        combine: Boolean = true,
        creditValue: Long = 100,
        mixed: Boolean = true,
        onlyCredits: Boolean = false,
        cashbackBp: Long = 0
    ) = PricingConfig(
        baseCurrency = base, currencyMode = mode, additionalCurrencies = additional, multiCurrencyFallback = fallback,
        rates = rates, vatBp = vatBp, pricesIncludeVat = includeVat, removeCents = removeCents,
        minimumOrderAmount = minimumOrder, combineDiscountsAndCoupons = combine, creditsEnabled = true,
        onlyAcceptCredits = onlyCredits, creditValue = creditValue, allowMixedCreditPayment = mixed, cashbackBp = cashbackBp
    )

    /** A catalogue product of the fixture table. [prices] are the explicit per-currency prices (`market_product_price`). */
    class Product(
        val id: Long,
        val name: String,
        val price: Long,
        val creditPrice: Long = 0,
        val categoryPath: List<Long> = emptyList(),
        val kind: LineKind = LineKind.PRODUCT,
        val vatBp: Long? = null,
        val physical: Boolean = false,
        val subscription: Boolean = false,
        val tier: TierInfo? = null,
        val prices: Map<String, Long> = emptyMap(),
        val children: List<BundleChild> = emptyList()
    )

    val P1 = Product(1, "VIP", 10000, 10000, listOf(1), prices = mapOf("USD" to 299))
    val P2 = Product(2, "Key", 999, 1000, listOf(2))
    val P3 = Product(3, "Sword", 4990, 0, listOf(2), vatBp = 1000)
    val P4 = Product(4, "T-shirt", 25000, 0, listOf(3), physical = true)
    val P5 = Product(5, "Starter bundle", 12000, 0, listOf(4), kind = LineKind.BUNDLE,
        children = listOf(BundleChild(2, 0, 3), BundleChild(3, 0, 1)))
    val P6 = Product(6, "Credit pack", 10000, 0, listOf(5), kind = LineKind.CREDIT_PACK)
    val P8 = Product(8, "Sticker", 5, 0, listOf(6))
    val P9 = Product(9, "Monthly rank", 3000, 3000, listOf(7), subscription = true)

    fun tier(id: Long, price: Long, rank: Int, mode: UpgradeMode = UpgradeMode.DIFFERENCE) =
        Product(id, "Tier $rank", price, 0, listOf(10), tier = TierInfo(10, rank, mode))

    val T1 = tier(21, 5000, 1)
    val T2 = tier(22, 12000, 2)
    val T3 = tier(23, 20000, 3)

    /** Lines are keyed `L<productId>` (+ `v<variantId>`), so two lines of one product need an explicit key. */
    fun line(
        p: Product,
        qty: Int = 1,
        variantId: Long = 0,
        key: String = "L${p.id}" + if (variantId != 0L) "v$variantId" else "",
        basePrice: Long = p.price,
        currencyPrices: Map<String, Long> = p.prices,
        tier: TierInfo? = p.tier
    ) = LineInput(
        lineKey = key, productId = p.id, variantId = variantId, kind = p.kind, quantity = qty, basePrice = basePrice,
        currencyPrices = currencyPrices, creditPrice = p.creditPrice, vatBp = p.vatBp, categoryPath = p.categoryPath,
        physical = p.physical, subscription = p.subscription, tier = tier, topUpCredits = null, children = p.children
    )

    fun topUp(credits: Long) = LineInput(
        lineKey = "topup", productId = null, variantId = 0, kind = LineKind.CREDIT_TOPUP, quantity = 1, basePrice = 0,
        currencyPrices = emptyMap(), creditPrice = 0, vatBp = null, categoryPath = emptyList(), physical = false,
        subscription = false, tier = null, topUpCredits = credits, children = emptyList()
    )

    fun discount(
        id: Long, value: Long, unit: DiscountUnit = DiscountUnit.PERCENT, scope: DiscountScope = DiscountScope.ALL,
        productIds: Set<Long> = emptySet(), categoryIds: Set<Long> = emptySet(), min: Long? = null,
        start: Long? = null, expiry: Long? = null, usageLimit: Int? = null, used: Int = 0
    ) = DiscountInput(id, value, unit, scope, productIds, categoryIds, min, start, expiry, usageLimit, used)

    val D1 = discount(1, 1000)
    val D2 = discount(2, 1500, DiscountUnit.FIXED, DiscountScope.PRODUCTS, productIds = setOf(2))
    val D3 = discount(3, 500, DiscountUnit.FIXED, DiscountScope.CATEGORIES, categoryIds = setOf(2))
    val D4 = discount(4, 2000, min = 20000)
    val D5 = discount(5, 4000)
    val D6 = discount(6, 5000)
    val D7 = discount(7, 1500)

    fun coupon(
        id: Long, code: String, value: Long, unit: DiscountUnit = DiscountUnit.PERCENT, scope: CouponScope = CouponScope.ALL,
        productIds: Set<Long> = emptySet(), min: Long? = null
    ) = CouponInput(
        found = true, id = id, code = code, active = true, discount = value, unit = unit, scope = scope,
        productIds = productIds, categoryIds = emptySet(), minPaymentAmount = min, startDate = null, expiryDate = null,
        redeemLimit = null, customerRedeemLimit = null, usedCount = 0, buyerUses = 0
    )

    val K25 = coupon(1, "K25", 2500)
    val KF20 = coupon(2, "KF20", 2000, DiscountUnit.FIXED)
    val KF500 = coupon(3, "KF500", 50000, DiscountUnit.FIXED)
    val K50P2 = coupon(4, "K50P2", 5000, scope = CouponScope.SELECTED, productIds = setOf(2))
    val KMIN = coupon(5, "KMIN", 2500, min = 15000)

    val CR5 = CreatorCodeInput(
        found = true, id = 1, code = "CR5", active = true, discount = 500, unit = DiscountUnit.PERCENT, commissionBp = 1000,
        creatorUserId = 900, startDate = null, expiryDate = null, redeemLimit = null, usedCount = 0
    )

    /** Fee method F: 2.9 % + 0.30 paid by the buyer. */
    val METHOD_F = method("F", feePercent = 290, feeFixed = 30)

    /** Fee method G: 3.5 %, no fixed part. */
    val METHOD_G = method("G", feePercent = 350, feeFixed = 0)

    fun method(
        id: String, feePercent: Long = 0, feeFixed: Long = 0, authority: PriceAuthority = PriceAuthority.MARKET,
        mixedCredit: Boolean = true
    ) = MethodInput(
        id = id, feeMode = if (feePercent > 0 || feeFixed > 0) PaymentFeeMode.BUYER else PaymentFeeMode.NONE,
        feePercent = feePercent, feeFixed = feeFixed, minAmount = null, maxAmount = null, adminCurrencies = null,
        providerCurrencies = null, providerMin = null, providerMax = null, mixedCredit = mixedCredit,
        priceAuthority = authority, physicalGoods = false
    )

    fun buyer(
        tiers: List<OwnedTier> = emptyList(), balance: Long = 0, loggedIn: Boolean = true, userId: Long? = 1,
        recipientUserId: Long? = userId, email: String? = "buyer@example.com"
    ) = BuyerContext("user:1", userId, loggedIn, recipientUserId, email, balance, tiers)

    fun input(
        vararg lines: LineInput,
        config: PricingConfig = config(),
        discounts: List<DiscountInput> = emptyList(),
        coupon: CouponInput? = null,
        creatorCode: CreatorCodeInput? = null,
        profile: PricingProfile = PricingProfile.STOREFRONT,
        currency: String? = null,
        buyer: BuyerContext = buyer(),
        mode: PricingMode = PricingMode.MARKET,
        payWithCredits: Boolean = false,
        override: Long? = null,
        now: Long = NOW
    ) = PricingInput(
        config = config, now = now, profile = profile, requestedCurrency = currency, lines = lines.toList(), buyer = buyer,
        discounts = discounts, coupon = coupon, creatorCode = creatorCode, pricingMode = mode,
        payWithCredits = payWithCredits, priceOverride = override
    )

    fun price(
        vararg lines: LineInput,
        config: PricingConfig = config(),
        discounts: List<DiscountInput> = emptyList(),
        profile: PricingProfile = PricingProfile.STOREFRONT,
        currency: String? = null,
        buyer: BuyerContext = buyer(),
        mode: PricingMode = PricingMode.MARKET,
        override: Long? = null,
        now: Long = NOW
    ): ItemsResult = PricingEngine.priceItems(
        input(*lines, config = config, discounts = discounts, profile = profile, currency = currency, buyer = buyer,
            mode = mode, override = override, now = now)
    )

    fun owned(entitlementId: Long, tierProduct: Product, pricePaid: Long) =
        OwnedTier(entitlementId, tierProduct.tier!!.categoryId, tierProduct.tier!!.tierRank, pricePaid)

    /** The conversions the engine builds for [config] and the order currency of [result] (for checks of figures). */
    fun conversionsOf(result: ItemsResult): Conversions = result.conversions
}
