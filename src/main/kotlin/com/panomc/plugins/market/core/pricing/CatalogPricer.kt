package com.panomc.plugins.market.core.pricing

import com.panomc.plugins.market.core.money.Conversions
import com.panomc.plugins.market.core.money.Rounding
import com.panomc.plugins.market.db.model.UpgradeMode
import com.panomc.plugins.market.util.DiscountUnit
import java.math.BigDecimal

/*
 * Catalogue prices (05 section 13): what a storefront card and a product page show. Plain values in, plain values out, same
 * rules as the cart so that what is shown is what the quote charges: the list price is stage A1 (`ListPrice`), the sale is the
 * automatic discount of stage A2 (`DiscountStage`, one winner per unit, ties to the lowest id), the upgrade deduction is the
 * one of section 5.2. The caller (StoreQueryService) loads the rows and maps the result to `ProductCard` / `ProductDetail`.
 */

/**
 * One row of `market_product_price`: an explicit price (and optionally its "was" price) of a product ([variantId] 0) or a
 * variant in [currency]. Only the currencies that differ from the base currency have rows.
 */
class CatalogPriceRow(val variantId: Long, val currency: String, val price: Long, val compareAtPrice: Long? = null)

/** A variant of a product. A `null` [price] inherits the product's base price, a `null` [creditPrice] the product's. */
class CatalogVariant(val id: Long, val price: Long?, val compareAtPrice: Long? = null, val creditPrice: Long? = null)

/**
 * The catalogue facts of one product, base currency, price basis. [kind] is `PRODUCT`, `BUNDLE` or `CREDIT_PACK` (a
 * credit top-up has no product). Only the variants the caller wants priced are passed (the active ones); no variants = the
 * product itself is the one priced thing.
 */
class CatalogProduct(
    val id: Long,
    val kind: LineKind,
    val basePrice: Long,
    val compareAtPrice: Long?,
    val creditPrice: Long,
    val categoryPath: List<Long>,
    val subscription: Boolean = false,
    val tier: TierInfo? = null,
    val prices: List<CatalogPriceRow> = emptyList(),
    val variants: List<CatalogVariant> = emptyList()
)

/** An ACTIVE automatic discount with its badge flag (`market_discount.showBadge`, 01 section 2.9). */
class CatalogDiscount(val discount: DiscountInput, val showBadge: Boolean)

class CatalogContext(
    val config: PricingConfig,
    /** Epoch milliseconds, for the windows and usage limits of the discounts. */
    val now: Long,
    /** The `?currency=` of the request; resolved like a cart's (05 section 4.1). */
    val requestedCurrency: String?,
    val discounts: List<CatalogDiscount>,
    /** ACTIVE tier entitlements of a logged-in caller; empty for a guest (no `upgrade` block then). */
    val ownedTiers: List<OwnedTier> = emptyList()
)

/**
 * The `sale` block of a card: [percentBp] for a percent discount, [amountOff] (shown currency) for a fixed one, [endsAt] the
 * discount's expiry. Non-null only when the winning discount has `showBadge`.
 */
class SaleBadge(val percentBp: Long?, val amountOff: Long?, val endsAt: Long?) {
    /** `percentBp / 100`, the number of the `ProductCard.sale.percent` field. */
    val percent: BigDecimal?
        get() = percentBp?.let { bp -> BigDecimal.valueOf(bp, 2).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it } }
}

/** `ProductDetail.upgrade` as far as pricing knows it: the product and name behind [fromEntitlementId] are the caller's to add. */
class UpgradeOffer(val fromEntitlementId: Long, val mode: UpgradeMode, val deduction: Long)

/** One priced variant (`ProductDetail.variants[]`); the product itself is the variant with id 0. Amounts in the shown currency. */
class VariantPrice(
    val variantId: Long,
    /** The catalogue price before the sale. */
    val listPrice: Long,
    /** The price after the automatic discount (what a quote charges per unit, before codes and the upgrade). */
    val price: Long,
    val compareAtPrice: Long?,
    val creditPrice: Long,
    val sale: SaleBadge?,
    val upgrade: UpgradeOffer?
)

/**
 * The prices of one product. [price], [compareAtPrice], [creditPrice], [sale] and [upgrade] are those of the card's variant
 * (the cheapest one; the first of the list on a tie).
 *
 * [currency] is the currency of the figures: the display currency in `DISPLAY` mode (every figure goes through
 * `toDisplay`), else the order currency; [chargeCurrency] is what a quote charges. [pricesIncludeVat] tells the theme
 * whether to label the prices "+ VAT".
 */
class CatalogPrice(
    val currency: String,
    val chargeCurrency: String,
    val pricesIncludeVat: Boolean,
    val price: Long,
    val compareAtPrice: Long?,
    val creditPrice: Long,
    /** The variants' prices differ: [price] is the lowest ("from"). */
    val priceFrom: Boolean,
    val sale: SaleBadge?,
    val upgrade: UpgradeOffer?,
    /** Every priced variant, in the order given; one entry (id 0) for a product without variants. */
    val variants: List<VariantPrice>,
    /** `CURRENCY_NOT_SUPPORTED` when the requested currency fell back (05 section 4.1). */
    val messages: List<PricingMessage>
)

object CatalogPricer {
    /**
     * Prices [product] for [context] (05 section 13); `null` when the product has nothing to show in the currency (the
     * `HIDE` fallback of 05 section 4.2 step 4 for every variant): the listing leaves it out, a product page answers 404.
     *
     * Only discounts without `minPaymentAmount` are candidates: a cart-dependent discount is not advertised. The `compareAtPrice`
     * is the list price while a discount takes something off, else the stored "was" price when it is above the price.
     */
    fun card(product: CatalogProduct, context: CatalogContext): CatalogPrice? =
        try {
            compute(product, context)
        } catch (e: ArithmeticException) {
            throw PricingException(PricingError.AMOUNT_OVERFLOW, e.message ?: "arithmetic overflow", e)
        }

    private fun compute(product: CatalogProduct, context: CatalogContext): CatalogPrice? {
        check(product)
        val config = context.config
        val currency = OrderCurrencies.resolve(config, context.requestedCurrency)
        val conversions = Conversions(
            baseCurrency = config.baseCurrency,
            orderCurrency = currency.currency,
            fx = currency.fx,
            creditValue = config.creditValue,
            removeCents = config.removeCents,
            displayCurrency = currency.displayCurrency,
            displayRate = currency.displayRate
        )
        val shown: (Long) -> Long = if (conversions.displayCurrency != null) conversions::toDisplay else { v -> v }
        val badges = context.discounts.filter { it.discount.minPaymentAmount == null }.associateBy { it.discount.id }
        val owned = context.ownedTiers

        val sources = if (product.variants.isEmpty()) listOf(null) else product.variants
        val priced = ArrayList<VariantPrice>(sources.size)
        for (variant in sources) {
            val variantId = variant?.id ?: 0L
            val hasOwnPrice = variant?.price != null
            val basePrice = variant?.price ?: product.basePrice
            val compareBase = variant?.compareAtPrice ?: if (hasOwnPrice) null else product.compareAtPrice
            val creditPrice = variant?.creditPrice ?: product.creditPrice

            // 05 section 4.2 steps 1 and 2: the explicit foreign price, with the row that also carries its "was" price
            val row = if (conversions.orderCurrency == conversions.baseCurrency) null
            else pickRow(product.prices, conversions.orderCurrency, variantId, hasOwnPrice)
            val line = LineInput(
                lineKey = "p", productId = product.id, variantId = variantId, kind = product.kind, quantity = 1, basePrice = basePrice,
                currencyPrices = if (row != null) mapOf(conversions.orderCurrency to row.price) else emptyMap(),
                creditPrice = creditPrice, vatBp = null, categoryPath = product.categoryPath, physical = false,
                subscription = product.subscription, tier = product.tier, topUpCredits = null, children = emptyList()
            )
            val listed = ListPrice.list(line, conversions, config)
            if (listed.excluded) continue
            val list = listed.listUnitPrice

            val settings = DiscountStage.Settings(
                unit = AmountUnit(conversions.oq, conversions::toOrder),
                now = context.now,
                subtotal = list,
                discounts = badges.values.map { it.discount },
                discountsEnabled = true,
                fullGift = false,
                upgradeLink = owned.isNotEmpty(),
                upgradeDeduction = owned.isNotEmpty(),
                recipientTiers = owned,
                upgradeClaimants = DiscountStage.upgradeClaimants(listOf(listed))
            )
            val outcome = DiscountStage.apply(listed, settings)
            val price = list - outcome.unitDiscount

            val shownPrice = shown(price)
            val was = if (outcome.unitDiscount > 0L) shown(list) else shown(compareAt(compareBase, row, listed, conversions, config))
            val compareAt = was.takeIf { it > shownPrice }

            val winner = outcome.discountId?.let { badges[it] }
            val sale = winner?.takeIf { it.showBadge }?.let {
                val d = it.discount
                SaleBadge(
                    percentBp = if (d.unit == DiscountUnit.PERCENT) DiscountStage.clampBp(d.value) else null,
                    amountOff = if (d.unit == DiscountUnit.FIXED) shown(outcome.unitDiscount) else null,
                    endsAt = d.expiryDate
                )
            }
            val upgrade = outcome.upgradeFromEntitlementId?.let {
                UpgradeOffer(it, product.tier!!.upgradeMode, shown(outcome.upgradeUnitAmount))
            }
            priced += VariantPrice(variantId, shown(list), shownPrice, compareAt, creditPrice, sale, upgrade)
        }
        if (priced.isEmpty()) return null

        val card = priced.minByOrNull { it.price }!! // first of the lowest: a stable tie-break
        return CatalogPrice(
            currency = conversions.displayCurrency ?: conversions.orderCurrency,
            chargeCurrency = conversions.orderCurrency,
            pricesIncludeVat = config.pricesIncludeVat,
            price = card.price,
            compareAtPrice = card.compareAtPrice,
            creditPrice = card.creditPrice,
            priceFrom = priced.map { it.price }.toSet().size > 1,
            sale = card.sale,
            upgrade = card.upgrade,
            variants = priced,
            messages = currency.messages
        )
    }

    /**
     * The stored "was" price converted like a price (05 section 13): an explicit foreign row says its own (or none), a converted
     * price converts the base "was" price the same way (one rounding, at least one quantum), the base currency rounds it to the
     * quantum. 0 when there is none. Never compared across a mixed basis: an explicit price never takes a converted "was" price.
     */
    private fun compareAt(compareBase: Long?, row: CatalogPriceRow?, listed: ListedLine, c: Conversions, config: PricingConfig): Long {
        if (c.orderCurrency == c.baseCurrency) return compareBase?.let { Rounding.roundQ(it, c.oq) } ?: 0L
        if (row != null) return row.compareAtPrice?.let { Rounding.roundQ(it, c.oq) } ?: 0L
        if (listed.listUnitPrice == 0L && listed.line.basePrice > 0L) return 0L
        return if (compareBase != null && compareBase > 0L) maxOf(c.oq, c.toOrder(compareBase)) else 0L
    }

    /** The same precedence as [CurrencyPriceResolver.resolve], returning the row so that its "was" price comes with it. */
    private fun pickRow(rows: List<CatalogPriceRow>, currency: String, variantId: Long, variantHasOwnBasePrice: Boolean): CatalogPriceRow? {
        rows.firstOrNull { it.variantId == variantId && it.currency == currency }?.let { return it }
        if (variantId != 0L && !variantHasOwnBasePrice) {
            rows.firstOrNull { it.variantId == 0L && it.currency == currency }?.let { return it }
        }
        return null
    }

    private fun check(product: CatalogProduct) {
        fun bad(condition: Boolean, message: () -> String) {
            if (condition) throw PricingException(PricingError.INVALID_INPUT, message())
        }
        bad(product.kind == LineKind.CREDIT_TOPUP) { "a credit top-up has no catalogue price" }
        bad(product.basePrice !in 0..PricingLimits.MAX_AMOUNT) { "product ${product.id}: base price ${product.basePrice} is out of bounds" }
        bad(product.creditPrice !in 0..PricingLimits.MAX_AMOUNT) { "product ${product.id}: credit price ${product.creditPrice} is out of bounds" }
        bad(product.tier != null && product.kind != LineKind.PRODUCT) { "product ${product.id}: only a plain product is tiered" }
        for (v in product.variants) {
            bad(v.price != null && v.price !in 0..PricingLimits.MAX_AMOUNT) { "variant ${v.id}: price ${v.price} is out of bounds" }
            bad(v.creditPrice != null && v.creditPrice !in 0..PricingLimits.MAX_AMOUNT) { "variant ${v.id}: credit price ${v.creditPrice} is out of bounds" }
        }
        bad(product.variants.map { it.id }.toSet().size != product.variants.size) { "product ${product.id}: duplicate variant ids" }
        for (r in product.prices) bad(r.price !in 0..PricingLimits.MAX_AMOUNT) { "product ${product.id}: ${r.currency} price ${r.price} is out of bounds" }
    }
}
