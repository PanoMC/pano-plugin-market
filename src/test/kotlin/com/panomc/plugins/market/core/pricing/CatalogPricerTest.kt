package com.panomc.plugins.market.core.pricing

import com.panomc.plugins.market.config.CurrencyMode
import com.panomc.plugins.market.config.MultiCurrencyFallback
import com.panomc.plugins.market.core.pricing.PricingFixtures.D1
import com.panomc.plugins.market.core.pricing.PricingFixtures.D2
import com.panomc.plugins.market.core.pricing.PricingFixtures.D3
import com.panomc.plugins.market.core.pricing.PricingFixtures.D4
import com.panomc.plugins.market.core.pricing.PricingFixtures.D5
import com.panomc.plugins.market.core.pricing.PricingFixtures.D6
import com.panomc.plugins.market.core.pricing.PricingFixtures.D7
import com.panomc.plugins.market.core.pricing.PricingFixtures.NOW
import com.panomc.plugins.market.core.pricing.PricingFixtures.P1
import com.panomc.plugins.market.core.pricing.PricingFixtures.P2
import com.panomc.plugins.market.core.pricing.PricingFixtures.P3
import com.panomc.plugins.market.core.pricing.PricingFixtures.P4
import com.panomc.plugins.market.core.pricing.PricingFixtures.P5
import com.panomc.plugins.market.core.pricing.PricingFixtures.P6
import com.panomc.plugins.market.core.pricing.PricingFixtures.P8
import com.panomc.plugins.market.core.pricing.PricingFixtures.P9
import com.panomc.plugins.market.core.pricing.PricingFixtures.Product
import com.panomc.plugins.market.core.pricing.PricingFixtures.T1
import com.panomc.plugins.market.core.pricing.PricingFixtures.T2
import com.panomc.plugins.market.core.pricing.PricingFixtures.T3
import com.panomc.plugins.market.core.pricing.PricingFixtures.config
import com.panomc.plugins.market.core.pricing.PricingFixtures.discount
import com.panomc.plugins.market.core.pricing.PricingFixtures.line
import com.panomc.plugins.market.core.pricing.PricingFixtures.owned
import com.panomc.plugins.market.db.model.UpgradeMode
import com.panomc.plugins.market.util.DiscountScope
import com.panomc.plugins.market.util.DiscountUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.random.Random

/**
 * `CatalogPricer` (05 section 13): the prices of storefront cards and product pages. Every expectation is a decimal of the
 * fixture of 05 section 17 times 100, worked out by hand; the seeded loop at the end proves that a card shows exactly what
 * the cart engine would charge for one unit of the same product.
 */
class CatalogPricerTest {
    private fun cp(
        p: Product,
        compareAt: Long? = null,
        rows: List<CatalogPriceRow> = emptyList(),
        variants: List<CatalogVariant> = emptyList()
    ) = CatalogProduct(
        id = p.id, kind = p.kind, basePrice = p.price, compareAtPrice = compareAt, creditPrice = p.creditPrice,
        categoryPath = p.categoryPath, subscription = p.subscription, tier = p.tier, prices = rows, variants = variants
    )

    private fun badge(d: DiscountInput, show: Boolean = true) = CatalogDiscount(d, show)

    private fun ctx(
        vararg discounts: CatalogDiscount,
        config: PricingConfig = config(),
        currency: String? = null,
        owned: List<OwnedTier> = emptyList(),
        now: Long = NOW
    ) = CatalogContext(config, now, currency, discounts.toList(), owned)

    private fun card(p: CatalogProduct, c: CatalogContext): CatalogPrice = CatalogPricer.card(p, c).also { assertNotNull(it, "the product is left out") }!!

    // ---------------------------------------------------------------- list price, sale, compareAt

    @Test
    fun `a product without a discount shows its list price and no strikethrough`() {
        val c = card(cp(P1), ctx())
        assertEquals(10000, c.price)
        assertNull(c.compareAtPrice)
        assertNull(c.sale)
        assertFalse(c.priceFrom)
        assertEquals("TRY", c.currency)
        assertEquals("TRY", c.chargeCurrency)
        assertTrue(c.pricesIncludeVat)
        assertEquals(10000, c.creditPrice) // the credit price as stored, no conversion
        assertEquals(listOf(0L), c.variants.map { it.variantId })
        assertTrue(c.messages.isEmpty())
    }

    @Test
    fun `a percent sale with a badge shows the discounted price, the list price as compareAt and the percent`() {
        val c = card(cp(P1), ctx(badge(D1)))
        assertEquals(9000, c.price)
        assertEquals(10000, c.compareAtPrice)
        assertEquals(1000L, c.sale!!.percentBp)
        assertEquals(0, BigDecimal(10).compareTo(c.sale!!.percent))
        assertNull(c.sale!!.amountOff)
        assertNull(c.sale!!.endsAt)
    }

    @Test
    fun `a fixed sale shows the amount off and the end of the window`() {
        val c = card(cp(P3), ctx(badge(discount(3, 500, DiscountUnit.FIXED, DiscountScope.CATEGORIES, categoryIds = setOf(2), expiry = NOW + 5_000))))
        assertEquals(4490, c.price)
        assertEquals(4990, c.compareAtPrice)
        assertNull(c.sale!!.percentBp)
        assertNull(c.sale!!.percent)
        assertEquals(500, c.sale!!.amountOff)
        assertEquals(NOW + 5_000, c.sale!!.endsAt)
    }

    @Test
    fun `a discount without a badge still lowers the price and strikes the list price but advertises no sale block`() {
        val c = card(cp(P1), ctx(badge(D1, show = false)))
        assertEquals(9000, c.price)
        assertEquals(10000, c.compareAtPrice)
        assertNull(c.sale)
    }

    @Test
    fun `the best discount wins and a tie goes to the lowest id, whatever the order they arrive in`() {
        // 05 row 9: D3 (5.00) beats D1 (10 % of 9.99 = 1.00 half up) on P2
        val best = card(cp(P2), ctx(badge(D1), badge(D3)))
        assertEquals(499, best.price)
        assertEquals(500, best.sale!!.amountOff)
        // row 13: two 10 % discounts, the lower id is the winner (its badge flag decides the sale block)
        val seven = discount(7, 1000)
        val nine = discount(9, 1000)
        val a = card(cp(P1), ctx(badge(nine, show = false), badge(seven, show = true)))
        val b = card(cp(P1), ctx(badge(seven, show = true), badge(nine, show = false)))
        assertEquals(9000, a.price)
        assertEquals(1000L, a.sale!!.percentBp)
        assertEquals(a.price, b.price)
        assertEquals(a.sale!!.percentBp, b.sale!!.percentBp)
        val c = card(cp(P1), ctx(badge(nine, show = true), badge(seven, show = false)))
        assertNull(c.sale) // id 7 won and has no badge
    }

    @Test
    fun `a cart dependent discount is not advertised`() {
        val c = card(cp(P1), ctx(badge(D4))) // 20 % ALL with minPaymentAmount 200.00
        assertEquals(10000, c.price)
        assertNull(c.compareAtPrice)
        assertNull(c.sale)
        // even where the unit price itself is above the minimum (250.00 >= 200.00) the discount is not advertised: it depends on the cart
        val high = card(cp(P4), ctx(badge(D4)))
        assertEquals(25000, high.price)
        assertNull(high.compareAtPrice)
        assertNull(high.sale)
    }

    @Test
    fun `a discount outside its window or past its usage limit does not apply`() {
        for (d in listOf(
            discount(1, 1000, expiry = NOW), // expiryDate == now: over
            discount(1, 1000, start = NOW + 1), // not started
            discount(1, 1000, usageLimit = 5, used = 5)
        )) {
            val c = card(cp(P1), ctx(badge(d)))
            assertEquals(10000, c.price)
            assertNull(c.sale)
        }
        assertEquals(9000, card(cp(P1), ctx(badge(discount(1, 1000, start = NOW, expiry = NOW + 1)))).price)
    }

    @Test
    fun `a stored compareAt price is shown while it is above the price and is dropped otherwise`() {
        assertEquals(15000, card(cp(P1, compareAt = 15000), ctx()).compareAtPrice)
        assertNull(card(cp(P1, compareAt = 10000), ctx()).compareAtPrice)
        assertNull(card(cp(P1, compareAt = 5000), ctx()).compareAtPrice)
        // while a discount applies, the strikethrough is the list price, not the stored "was" price
        assertEquals(10000, card(cp(P1, compareAt = 15000), ctx(badge(D1))).compareAtPrice)
    }

    @Test
    fun `a free product stays free and takes no discount`() {
        val free = Product(30, "Free", 0, 0, listOf(2))
        val c = card(cp(free), ctx(badge(D1), badge(D3)))
        assertEquals(0, c.price)
        assertNull(c.compareAtPrice)
        assertNull(c.sale)
    }

    @Test
    fun `a subscription takes no discount and a credit pack only one that lists it`() {
        assertEquals(3000, card(cp(P9), ctx(badge(D1))).price)
        assertNull(card(cp(P9), ctx(badge(D1))).sale)
        assertEquals(10000, card(cp(P6), ctx(badge(D1))).price) // ALL is products and bundles only
        val listed = discount(8, 2000, DiscountUnit.PERCENT, DiscountScope.PRODUCTS, productIds = setOf(6))
        assertEquals(8000, card(cp(P6), ctx(badge(listed))).price)
        // a category scope never reaches a credit pack
        val category = discount(9, 2000, DiscountUnit.PERCENT, DiscountScope.CATEGORIES, categoryIds = setOf(5))
        assertEquals(10000, card(cp(P6), ctx(badge(category))).price)
    }

    @Test
    fun `a bundle is priced as its own price`() {
        assertEquals(10800, card(cp(P5), ctx(badge(D1))).price) // 120.00 less 10 %
        assertEquals(12000, card(cp(P5), ctx()).price)
    }

    // ---------------------------------------------------------------- removeCents

    @Test
    fun `removeCents rounds the list price and the percent discount to whole units`() {
        val cfg = config(removeCents = true)
        val plain = card(cp(P2), ctx(config = cfg))
        assertEquals(1000, plain.price) // 9.99 -> 10.00
        // 05 row 41: D7 (15 %) of 10.00 is 1.50, rounded half up to 2.00
        val sale = card(cp(P2), ctx(badge(D7), config = cfg))
        assertEquals(800, sale.price)
        assertEquals(1000, sale.compareAtPrice)
        // the stored "was" price is rounded like a price
        assertEquals(2000, card(cp(P2, compareAt = 1950), ctx(config = cfg)).compareAtPrice)
        assertNull(card(cp(P2, compareAt = 1040), ctx(config = cfg)).compareAtPrice) // 10.40 -> 10.00, not above the price
    }

    // ---------------------------------------------------------------- variants

    private val variantsOfShirt = listOf(
        CatalogVariant(1, price = null), // M inherits 250.00
        CatalogVariant(2, price = 27500) // XL 275.00
    )

    @Test
    fun `variants give priceFrom with the lowest price and one entry each`() {
        val c = card(cp(P4, variants = variantsOfShirt), ctx())
        assertEquals(25000, c.price)
        assertTrue(c.priceFrom)
        assertEquals(listOf(1L, 2L), c.variants.map { it.variantId })
        assertEquals(listOf(25000L, 27500L), c.variants.map { it.price })
    }

    @Test
    fun `variants that cost the same are not priceFrom`() {
        val c = card(cp(P4, variants = listOf(CatalogVariant(1, null), CatalogVariant(2, 25000))), ctx())
        assertFalse(c.priceFrom)
        assertEquals(25000, c.price)
    }

    @Test
    fun `the card takes the first of the cheapest variants on a tie and the cheapest after the sale`() {
        val vs = listOf(CatalogVariant(5, 9000), CatalogVariant(3, 9000), CatalogVariant(4, 20000))
        val c = card(cp(P4, variants = vs), ctx())
        assertEquals(9000, c.price)
        assertEquals(5L, c.variants.first { it.price == c.price }.variantId)
        // a fixed discount takes the same amount of every variant (70.00 - 60.00, 200.00 - 60.00)
        val fixed = discount(2, 6000, DiscountUnit.FIXED, DiscountScope.PRODUCTS, productIds = setOf(4))
        val d = card(cp(P4, variants = listOf(CatalogVariant(1, 7000), CatalogVariant(2, 20000))), ctx(badge(fixed)))
        assertEquals(1000, d.price)
        assertEquals(listOf(1000L, 14000L), d.variants.map { it.price })
        assertTrue(d.priceFrom)
    }

    @Test
    fun `a variant carries its own compareAt and one that inherits the price inherits the product's compareAt`() {
        val vs = listOf(
            CatalogVariant(1, price = null), // inherits price and compareAt 280.00
            CatalogVariant(2, price = 27500, compareAtPrice = 30000),
            CatalogVariant(3, price = 26000) // own price, no own compareAt: the product's would be the wrong "was"
        )
        val c = card(cp(P4, compareAt = 28000, variants = vs), ctx())
        assertEquals(listOf<Long?>(28000, 30000, null), c.variants.map { it.compareAtPrice })
        assertEquals(28000, c.compareAtPrice) // the card's variant is the cheapest: variant 1
    }

    @Test
    fun `a variant credit price overrides the product's and the card shows the card variant's`() {
        val vs = listOf(CatalogVariant(1, 5000, creditPrice = 700), CatalogVariant(2, 9000))
        val c = card(cp(P1, variants = vs), ctx())
        assertEquals(700, c.creditPrice)
        assertEquals(listOf(700L, 10000L), c.variants.map { it.creditPrice })
    }

    // ---------------------------------------------------------------- currencies

    private val multi = config(mode = CurrencyMode.MULTI)

    @Test
    fun `MULTI with an explicit price uses it, its own compareAt, and discounts in that currency`() {
        val rows = listOf(CatalogPriceRow(0, "USD", 299, 399))
        val plain = card(cp(P1, rows = rows), ctx(config = multi, currency = "USD"))
        assertEquals("USD", plain.currency)
        assertEquals("USD", plain.chargeCurrency)
        assertEquals(299, plain.price)
        assertEquals(399, plain.compareAtPrice)
        // 10 % of 2.99 = 0.299 -> 0.30 half up, the quantum of USD is 0.01
        val sale = card(cp(P1, rows = rows), ctx(badge(D1), config = multi, currency = "USD"))
        assertEquals(269, sale.price)
        assertEquals(299, sale.compareAtPrice)
        // an explicit price never takes a converted "was" price
        assertNull(card(cp(P1, compareAt = 20000, rows = listOf(CatalogPriceRow(0, "USD", 299))), ctx(config = multi, currency = "USD")).compareAtPrice)
    }

    @Test
    fun `MULTI fallback CONVERT converts the price and the compareAt half up`() {
        // 05 row 61: 9.99 TRY x 0.025 = 0.24975 -> 0.25
        val c = card(cp(P2, compareAt = 1500), ctx(config = multi, currency = "USD"))
        assertEquals(25, c.price)
        assertEquals(38, c.compareAtPrice) // 15.00 x 0.025 = 0.375 -> 0.38
        // a FIXED discount is converted too: 5.00 TRY = 0.125 -> 0.13
        val fixed = card(cp(P2), ctx(badge(D3), config = multi, currency = "USD"))
        assertEquals(12, fixed.price)
        assertEquals(13, fixed.sale!!.amountOff)
        // a zero decimal currency
        assertEquals(4500, card(cp(P2), ctx(config = multi, currency = "JPY")).price) // 44.955 -> 45 JPY (whole units, x100)
    }

    @Test
    fun `a variant with its own price never takes the product level foreign price`() {
        val rows = listOf(CatalogPriceRow(0, "USD", 600, 700))
        val vs = listOf(CatalogVariant(1, price = null), CatalogVariant(2, price = 27500))
        val c = card(cp(P4, rows = rows, variants = vs), ctx(config = multi, currency = "USD"))
        assertEquals(listOf(600L, 688L), c.variants.map { it.price }) // XL: 275.00 x 0.025 = 6.875 -> 6.88 (05 row 66)
        assertEquals(700, c.variants[0].compareAtPrice)
        assertNull(c.variants[1].compareAtPrice)
        // a row of the variant itself wins over the product level one
        val own = listOf(CatalogPriceRow(0, "USD", 600), CatalogPriceRow(1, "USD", 550, 650))
        val d = card(cp(P4, rows = own, variants = vs), ctx(config = multi, currency = "USD"))
        assertEquals(550, d.variants[0].price)
        assertEquals(650, d.variants[0].compareAtPrice)
    }

    @Test
    fun `the row precedence is the one of CurrencyPriceResolver`() {
        val rows = listOf(
            CatalogPriceRow(0, "USD", 100), CatalogPriceRow(1, "USD", 200), CatalogPriceRow(0, "JPY", 300), CatalogPriceRow(2, "JPY", 400)
        )
        val resolverRows = rows.map { CurrencyPriceResolver.PriceRow(it.variantId, it.currency, it.price) }
        for (currency in listOf("USD", "JPY")) {
            for (variantId in listOf(0L, 1L, 2L, 3L)) {
                for (own in listOf(false, true)) {
                    val variants = if (variantId == 0L) emptyList() else listOf(CatalogVariant(variantId, if (own) 12345 else null))
                    val c = card(cp(P1, rows = rows, variants = variants), ctx(config = multi, currency = currency))
                    val expected = CurrencyPriceResolver.resolve(currency, variantId, own, resolverRows)
                    if (expected != null) {
                        assertEquals(expected, c.price, "$currency v$variantId own=$own")
                    } else {
                        // no explicit row: the base price converted, never the product level row of another variant
                        val base = if (variantId != 0L && own) 12345L else 10000L
                        val conv = PricingFixtures.conversionsOf(PricingEngine.priceItems(PricingFixtures.input(line(P1, basePrice = base, currencyPrices = emptyMap()), config = multi, currency = currency)))
                        assertEquals(conv.toOrder(base), c.price, "$currency v$variantId own=$own converted")
                    }
                }
            }
        }
    }

    @Test
    fun `MULTI fallback HIDE leaves the product out, or only the variant that has no price`() {
        val hide = config(mode = CurrencyMode.MULTI, fallback = MultiCurrencyFallback.HIDE)
        assertNull(CatalogPricer.card(cp(P2), ctx(config = hide, currency = "USD")))
        assertEquals(10000, card(cp(P2, rows = listOf(CatalogPriceRow(0, "USD", 10000))), ctx(config = hide, currency = "USD")).price)
        val vs = listOf(CatalogVariant(1, 100), CatalogVariant(2, 200))
        val rows = listOf(CatalogPriceRow(2, "USD", 5))
        val c = card(cp(P4, rows = rows, variants = vs), ctx(config = hide, currency = "USD"))
        assertEquals(listOf(2L), c.variants.map { it.variantId })
        assertFalse(c.priceFrom)
        assertNull(CatalogPricer.card(cp(P4, variants = vs), ctx(config = hide, currency = "USD")))
        // the base currency is always priced
        assertEquals(999, card(cp(P2), ctx(config = hide)).price)
    }

    @Test
    fun `an unknown currency falls back to the base currency with the warning`() {
        val c = card(cp(P1), ctx(config = multi, currency = "GBP"))
        assertEquals("TRY", c.currency)
        assertEquals(10000, c.price)
        assertEquals(listOf(PricingCode.CURRENCY_NOT_SUPPORTED), c.messages.map { it.code })
        // SINGLE ignores a foreign request with the same warning
        val single = card(cp(P1), ctx(currency = "USD"))
        assertEquals("TRY", single.currency)
        assertEquals(listOf(PricingCode.CURRENCY_NOT_SUPPORTED), single.messages.map { it.code })
    }

    @Test
    fun `DISPLAY mode shows every figure converted on its own, in the display currency, and charges the base currency`() {
        val display = config(mode = CurrencyMode.DISPLAY)
        val plain = card(cp(P1, compareAt = 20000), ctx(config = display, currency = "USD"))
        assertEquals("USD", plain.currency)
        assertEquals("TRY", plain.chargeCurrency)
        assertEquals(250, plain.price)
        assertEquals(500, plain.compareAtPrice)
        assertEquals(250, plain.variants[0].listPrice) // the list price too is shown in the display currency

        // 05 row 59 style: 49.90 TRY, fixed 5.00 off: 44.90 -> 1.1225 -> 1.12 ; 5.00 -> 0.125 -> 0.13 ; list 1.2475 -> 1.25
        val c = card(cp(P3), ctx(badge(D3), config = display, currency = "USD"))
        assertEquals(112, c.price)
        assertEquals(125, c.compareAtPrice)
        assertEquals(13, c.sale!!.amountOff)

        // without a display request the figures are the base ones
        val none = card(cp(P1), ctx(config = display))
        assertEquals("TRY", none.currency)
        assertEquals(10000, none.price)
    }

    @Test
    fun `a strikethrough that is no longer above the price after the display conversion is dropped`() {
        val display = config(mode = CurrencyMode.DISPLAY)
        // P8 is 0.05 TRY; 50 % off takes 0.03 (row 58): 0.02 TRY = 0.0005 USD -> 0.00, list 0.05 = 0.00125 -> 0.00
        val half = discount(6, 5000)
        val c = card(cp(P8), ctx(badge(half), config = display, currency = "USD"))
        assertEquals(0, c.price)
        assertNull(c.compareAtPrice)
        assertEquals(5000L, c.sale!!.percentBp)
    }

    // ---------------------------------------------------------------- upgrade (ProductDetail)

    @Test
    fun `an owner of a lower tier sees the deduction and the card price stays the price after the sale`() {
        val c = card(cp(T2), ctx(owned = listOf(owned(501, T1, 5000))))
        assertEquals(12000, c.price) // the deduction is shown separately, not folded into the price
        val u = c.upgrade!!
        assertEquals(501, u.fromEntitlementId)
        assertEquals(UpgradeMode.DIFFERENCE, u.mode)
        assertEquals(5000, u.deduction)
    }

    @Test
    fun `the deduction counts what was paid, follows the sale and is capped by what is left`() {
        val sale = discount(6, 5000) // D6: 50 %
        val c = card(cp(T2), ctx(badge(sale), owned = listOf(owned(502, T1, 25000))))
        assertEquals(6000, c.price)
        assertEquals(6000, c.upgrade!!.deduction) // owned 250.00 is capped at the 60.00 left
        val d = card(cp(T3), ctx(owned = listOf(owned(503, T1, 2500), owned(504, T2, 12000))))
        assertEquals(504, d.upgrade!!.fromEntitlementId) // the highest owned lower tier
        assertEquals(12000, d.upgrade!!.deduction)
    }

    @Test
    fun `mode FULL links the old entitlement and deducts nothing`() {
        val full = Product(61, "Gold FULL", 20000, 0, listOf(10), tier = TierInfo(10, 3, UpgradeMode.FULL))
        val c = card(cp(full), ctx(owned = listOf(owned(505, T1, 5000))))
        assertEquals(UpgradeMode.FULL, c.upgrade!!.mode)
        assertEquals(0, c.upgrade!!.deduction)
        assertEquals(505, c.upgrade!!.fromEntitlementId)
    }

    @Test
    fun `no upgrade for a guest, for the lowest tier, for a higher owned tier and for another category`() {
        assertNull(card(cp(T2), ctx()).upgrade)
        assertNull(card(cp(T1), ctx(owned = listOf(owned(501, T1, 5000)))).upgrade)
        assertNull(card(cp(T1), ctx(owned = listOf(owned(506, T2, 12000)))).upgrade)
        val other = OwnedTier(507, tierCategoryId = 99, tierRank = 1, pricePaid = 5000)
        assertNull(card(cp(T2), ctx(owned = listOf(other))).upgrade)
        assertNull(card(cp(P1), ctx(owned = listOf(owned(501, T1, 5000)))).upgrade)
    }

    @Test
    fun `the upgrade deduction is converted into the shown currency`() {
        val c = card(cp(T2), ctx(config = multi, currency = "USD", owned = listOf(owned(501, T1, 5000))))
        assertEquals(300, c.price) // 120.00 x 0.025
        assertEquals(125, c.upgrade!!.deduction) // 50.00 x 0.025
    }

    // ---------------------------------------------------------------- the contract with the cart

    @Test
    fun `out of bound or contradictory input is refused as INVALID_INPUT`() {
        fun refused(p: CatalogProduct) = assertEquals(PricingError.INVALID_INPUT, assertThrows(PricingException::class.java) { CatalogPricer.card(p, ctx()) }.error)
        refused(CatalogProduct(1, LineKind.PRODUCT, -1, null, 0, emptyList()))
        refused(CatalogProduct(1, LineKind.PRODUCT, PricingLimits.MAX_AMOUNT + 1, null, 0, emptyList()))
        refused(CatalogProduct(1, LineKind.PRODUCT, 100, null, -5, emptyList()))
        refused(CatalogProduct(1, LineKind.CREDIT_TOPUP, 100, null, 0, emptyList()))
        refused(CatalogProduct(1, LineKind.PRODUCT, 100, null, 0, emptyList(), variants = listOf(CatalogVariant(1, -3))))
        refused(CatalogProduct(1, LineKind.PRODUCT, 100, null, 0, emptyList(), variants = listOf(CatalogVariant(1, 1), CatalogVariant(1, 2))))
        refused(CatalogProduct(1, LineKind.PRODUCT, 100, null, 0, emptyList(), prices = listOf(CatalogPriceRow(0, "USD", -1))))
        refused(CatalogProduct(1, LineKind.BUNDLE, 100, null, 0, emptyList(), tier = TierInfo(1, 1, UpgradeMode.FULL)))
    }

    private val loopCatalogue: List<Product> = listOf(P1, P2, P3, P4, P5, P6, P8, P9, T1, T2, T3, Product(40, "Free", 0, 0, listOf(2)), Product(41, "Pricey", 2_000_000_000, 5, listOf(1)))

    @Test
    fun `a card shows exactly what the cart engine charges for one unit, over 10000 seeded products`() {
        val rnd = Random(20261013)
        val seen = java.util.TreeMap<String, Int>()
        fun hit(branch: String) = seen.merge(branch, 1) { a, b -> a + b }
        val pool = listOf(
            D1, D2, D3, D5, D6, D7, discount(10, 2500), discount(11, 1500, DiscountUnit.FIXED), discount(12, 1000, expiry = NOW),
            discount(13, 3000, start = NOW + 10), discount(14, 4000, usageLimit = 2, used = 2),
            discount(15, 777, DiscountUnit.PERCENT, DiscountScope.CATEGORIES, categoryIds = setOf(1, 10)),
            discount(16, 2500, DiscountUnit.FIXED, DiscountScope.PRODUCTS, productIds = setOf(1, 6, 22))
        )
        repeat(10_000) { n ->
            val mode = CurrencyMode.values()[rnd.nextInt(3)]
            val fallback = if (rnd.nextBoolean()) MultiCurrencyFallback.CONVERT else MultiCurrencyFallback.HIDE
            val cfg = config(mode = mode, fallback = fallback, removeCents = rnd.nextInt(4) == 0, includeVat = rnd.nextBoolean())
            val currency = when (mode) {
                CurrencyMode.MULTI -> listOf(null, "USD", "JPY", "TRY", "GBP")[rnd.nextInt(5)]
                CurrencyMode.DISPLAY -> listOf(null, "USD", "JPY", "GBP")[rnd.nextInt(4)]
                else -> listOf(null, "USD")[rnd.nextInt(2)]
            }
            val p = loopCatalogue[rnd.nextInt(loopCatalogue.size)]
            val hasOwn = rnd.nextInt(3) == 0
            val own = if (hasOwn) rnd.nextLong(0, 5_000_000) else null
            val rows = ArrayList<CatalogPriceRow>()
            if (rnd.nextInt(3) == 0) rows += CatalogPriceRow(0, "USD", rnd.nextLong(0, 100_000), if (rnd.nextBoolean()) rnd.nextLong(0, 200_000) else null)
            if (rnd.nextInt(4) == 0) rows += CatalogPriceRow(1, "JPY", rnd.nextLong(0, 100_000))
            val variants = if (rnd.nextInt(3) == 0) listOf(CatalogVariant(1, own, null), CatalogVariant(2, null)) else emptyList()
            val discounts = pool.filter { rnd.nextInt(4) == 0 }
            val ownedTiers = if (p.tier != null && rnd.nextInt(3) == 0) listOf(owned(601, T1, rnd.nextLong(0, 30_000))) else emptyList()
            val product = cp(p, compareAt = if (rnd.nextBoolean()) rnd.nextLong(0, 3_000_000) else null, rows = rows, variants = variants)
            val context = CatalogContext(cfg, NOW, currency, discounts.map { CatalogDiscount(it, rnd.nextBoolean()) }, ownedTiers)
            val result = CatalogPricer.card(product, context)
            val where = "#$n $mode $fallback currency=$currency product ${p.id} variants=${variants.size}"

            // what the cart engine charges for one unit of each variant
            val orderCurrency = OrderCurrencies.resolve(cfg, currency).currency
            val resolverRows = rows.map { CurrencyPriceResolver.PriceRow(it.variantId, it.currency, it.price) }
            val expectedVariants = (if (variants.isEmpty()) listOf(null) else variants).mapNotNull { v ->
                val variantId = v?.id ?: 0L
                val explicit = if (orderCurrency == cfg.baseCurrency) null
                else CurrencyPriceResolver.resolve(orderCurrency, variantId, v?.price != null, resolverRows)
                val engine = PricingEngine.priceItems(
                    PricingFixtures.input(
                        line(p, variantId = variantId, basePrice = v?.price ?: p.price, currencyPrices = if (explicit != null) mapOf(orderCurrency to explicit) else emptyMap()),
                        config = cfg, discounts = discounts, currency = currency, buyer = PricingFixtures.buyer(ownedTiers)
                    )
                )
                val l = engine.lines.first()
                if (l.excluded) null else Triple(variantId, l, engine)
            }
            if (expectedVariants.isEmpty()) {
                assertNull(result, where)
                hit("hidden")
                return@repeat
            }
            val c = result!!
            assertEquals(expectedVariants.map { it.first }, c.variants.map { it.variantId }, where)
            val conv = expectedVariants[0].third.conversions
            val shown: (Long) -> Long = if (conv.displayCurrency != null) conv::toDisplay else { x -> x }
            assertEquals(conv.displayCurrency ?: conv.orderCurrency, c.currency, where)
            assertEquals(conv.orderCurrency, c.chargeCurrency, where)
            for ((i, e) in expectedVariants.withIndex()) {
                val v = c.variants[i]
                val l = e.second
                // the engine applies the minPaymentAmount discounts too; the catalogue advertises none of them: the pool has none
                assertEquals(shown(l.listUnitPrice), v.listPrice, "$where variant ${v.variantId} list")
                assertEquals(shown(l.listUnitPrice - l.unitDiscount), v.price, "$where variant ${v.variantId} price")
                assertTrue(v.price <= v.listPrice, where)
                assertTrue(v.compareAtPrice == null || v.compareAtPrice > v.price, "$where compareAt ${v.compareAtPrice} price ${v.price}")
                if (l.unitDiscount > 0L && shown(l.listUnitPrice) > shown(l.listUnitPrice - l.unitDiscount)) assertEquals(shown(l.listUnitPrice), v.compareAtPrice, "$where strikethrough is the list price")
                if (l.unitDiscount > 0L) hit("discounted") else hit("not discounted")
                assertEquals(l.upgradeFromEntitlementId, v.upgrade?.fromEntitlementId, "$where upgrade link")
                if (v.upgrade != null) {
                    assertEquals(shown(l.upgradeUnitAmount), v.upgrade.deduction, "$where deduction")
                    hit(if (v.upgrade.deduction > 0L) "upgrade deduction" else "upgrade link only")
                }
                if (v.sale != null) {
                    assertTrue(l.unitDiscount > 0L, "$where a sale block needs a discount")
                    hit("sale block")
                }
                assertEquals(p.creditPrice, v.creditPrice, where)
            }
            // the card is the cheapest variant, the first of the cheapest
            val lowest = c.variants.minByOrNull { it.price }!!
            assertEquals(lowest.price, c.price, where)
            assertEquals(lowest.compareAtPrice, c.compareAtPrice, where)
            assertEquals(lowest.sale?.percentBp, c.sale?.percentBp, where)
            assertEquals(lowest.upgrade?.deduction, c.upgrade?.deduction, where)
            assertEquals(c.variants.map { it.price }.toSet().size > 1, c.priceFrom, where)
            if (c.priceFrom) hit("priceFrom")
            if (c.compareAtPrice != null && c.sale == null) hit("strikethrough without a badge")
            if (c.compareAtPrice != null && c.sale == null && discounts.isEmpty()) hit("stored compareAt")
            if (c.currency != "TRY") hit("foreign shown currency")
            if (cfg.removeCents) hit("removeCents")

            // deterministic, and independent of the order the discounts arrive in
            val shuffled = CatalogContext(cfg, NOW, currency, context.discounts.shuffled(rnd), ownedTiers)
            val again = CatalogPricer.card(product, shuffled)!!
            assertEquals(c.price, again.price, where)
            assertEquals(c.compareAtPrice, again.compareAtPrice, where)
            assertEquals(c.variants.map { it.price }, again.variants.map { it.price }, where)
            assertEquals(c.sale?.percentBp, again.sale?.percentBp, where)
            assertEquals(c.sale?.amountOff, again.sale?.amountOff, where)
        }
        println("CATALOG-LOOP branches=$seen")
        for (branch in listOf(
            "hidden", "discounted", "not discounted", "sale block", "priceFrom", "strikethrough without a badge", "stored compareAt",
            "foreign shown currency", "removeCents", "upgrade deduction"
        )) {
            // a stored "was" price only shows when there is no discount at all, which one draw in about 40 has
            val least = if (branch == "stored compareAt") 50 else 100
            assertTrue((seen[branch] ?: 0) >= least, "the loop barely exercised '$branch': ${seen[branch]}")
        }
    }
}
