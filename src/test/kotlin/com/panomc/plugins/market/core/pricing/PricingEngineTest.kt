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
import com.panomc.plugins.market.core.pricing.PricingFixtures.T1
import com.panomc.plugins.market.core.pricing.PricingFixtures.T2
import com.panomc.plugins.market.core.pricing.PricingFixtures.T3
import com.panomc.plugins.market.core.pricing.PricingFixtures.config
import com.panomc.plugins.market.core.pricing.PricingFixtures.discount
import com.panomc.plugins.market.core.pricing.PricingFixtures.line
import com.panomc.plugins.market.core.pricing.PricingFixtures.owned
import com.panomc.plugins.market.core.pricing.PricingFixtures.price
import com.panomc.plugins.market.core.pricing.PricingFixtures.topUp
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.UpgradeMode
import com.panomc.plugins.market.util.DiscountScope
import com.panomc.plugins.market.util.DiscountUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.random.Random

/**
 * Stages A1 (order currency, list price) and A2 (automatic discount, upgrade deduction) of 05, against the vector
 * table of section 17. A row of that table that goes beyond A2 (VAT, codes, shipping, tender, totals) is asserted
 * here only as far as A2 decides it; the rest belongs to the slices of the later stages. Test names carry the row.
 *
 * Where the table says "total", the fixture has VAT-inclusive prices, no shipping and no fee, so the total is
 * `itemsAmount`.
 */
class PricingEngineTest {
    private fun ItemsResult.key(key: String): PricedLine = lines.single { it.lineKey == key }

    // ---------------------------------------------------------------- A2: automatic discount (rows 7 to 13, 58)

    @Test
    fun `row 7 percent discount is taken per unit`() {
        val r = price(line(P2, 3), discounts = listOf(D1))
        val l = r.key("L2")
        assertEquals(999L, l.listUnitPrice)
        assertEquals(100L, l.unitDiscount)
        assertEquals(899L, l.unitPrice)
        assertEquals(300L, l.discountAmount)
        assertEquals(300L, r.discountTotal)
        assertEquals(2697L, r.itemsAmount) // total 26.97
        assertEquals(2997L, r.subtotal)
        assertEquals(listOf(DiscountRedemption(1, 300)), r.discountRedemptions)
    }

    @Test
    fun `row 8 a FIXED discount is capped at the unit price`() {
        val r = price(line(P2, 2), discounts = listOf(D2))
        val l = r.key("L2")
        assertEquals(999L, l.unitDiscount)
        assertEquals(0L, l.unitPrice)
        assertEquals(1998L, r.discountTotal)
        assertEquals(0L, r.itemsAmount) // total 0.00, method free
        assertEquals(listOf(DiscountRedemption(2, 1998)), r.discountRedemptions)
    }

    @Test
    fun `row 9 the best discount wins and discounts never stack`() {
        val r = price(line(P1), line(P3), discounts = listOf(D1, D3))
        val p1 = r.key("L1")
        val p3 = r.key("L3")
        assertEquals(1000L, p1.unitDiscount) // D1; D3 is not in P1's category
        assertEquals(9000L, p1.unitPrice)
        assertEquals(1L, p1.discountId)
        assertEquals(500L, p3.unitDiscount) // D3 FIXED 5.00 beats D1 4.99
        assertEquals(4490L, p3.unitPrice)
        assertEquals(3L, p3.discountId)
        assertEquals(1500L, r.discountTotal)
        assertEquals(13490L, r.itemsAmount) // total 134.90
        assertEquals(listOf(DiscountRedemption(1, 1000), DiscountRedemption(3, 500)), r.discountRedemptions)
    }

    @Test
    fun `row 10 a discount whose minimum is not reached is no candidate`() {
        val r = price(line(P1), discounts = listOf(D4))
        assertEquals(0L, r.discountTotal)
        assertEquals(10000L, r.itemsAmount)
        assertTrue(r.discountRedemptions.isEmpty())
    }

    @Test
    fun `row 11 a discount whose minimum is reached applies to the subtotal it was checked on`() {
        val r = price(line(P1, 2), discounts = listOf(D4))
        val l = r.key("L1")
        assertEquals(2000L, l.unitDiscount)
        assertEquals(8000L, l.unitPrice)
        assertEquals(4000L, r.discountTotal)
        assertEquals(16000L, r.itemsAmount) // total 160.00
    }

    @Test
    fun `row 12 window and usage limit decide whether a discount is a candidate`() {
        val expired = discount(1, 1000, expiry = NOW) // now == expiryDate: over
        val notYet = discount(1, 1000, start = NOW + 1)
        val used = discount(1, 1000, usageLimit = 5, used = 5)
        for (d in listOf(expired, notYet, used)) {
            val r = price(line(P1), discounts = listOf(d))
            assertEquals(0L, r.discountTotal)
            assertEquals(10000L, r.itemsAmount)
        }
        // and the boundaries on the other side: startDate == now and expiryDate == now + 1 are inside the window
        assertEquals(1000L, price(line(P1), discounts = listOf(discount(1, 1000, start = NOW))).discountTotal)
        assertEquals(1000L, price(line(P1), discounts = listOf(discount(1, 1000, expiry = NOW + 1))).discountTotal)
        assertEquals(1000L, price(line(P1), discounts = listOf(discount(1, 1000, usageLimit = 5, used = 4))).discountTotal)
    }

    @Test
    fun `row 13 a tie goes to the lowest id whatever the order the discounts arrive in`() {
        val a = discount(9, 1000)
        val b = discount(7, 1000)
        for (order in listOf(listOf(a, b), listOf(b, a))) {
            val r = price(line(P1), discounts = order)
            assertEquals(1000L, r.discountTotal)
            assertEquals(listOf(DiscountRedemption(7, 1000)), r.discountRedemptions)
            assertEquals(7L, r.key("L1").discountId)
        }
    }

    @Test
    fun `row 58 tiny amounts round the discount half up per unit`() {
        val r = price(line(P8, 3), discounts = listOf(D6))
        val l = r.key("L8")
        assertEquals(3L, l.unitDiscount) // 0.025 rounds up
        assertEquals(2L, l.unitPrice)
        assertEquals(6L, r.itemsAmount) // total 0.06
    }

    // ---------------------------------------------------------------- A2: upgrade deduction (rows 30 to 35, 78)

    @Test
    fun `row 30 an owner of the lower tier pays the difference`() {
        val buyer = PricingFixtures.buyer(listOf(owned(501, T1, 5000)))
        val r = price(line(T2), buyer = buyer)
        val l = r.key("L22")
        assertEquals(5000L, l.upgradeUnitAmount)
        assertEquals(5000L, l.upgradeAmount)
        assertEquals(5000L, r.upgradeDiscount)
        assertEquals(7000L, l.unitPrice)
        assertEquals(7000L, r.itemsAmount) // total 70.00
        assertEquals(501L, l.upgradeFromEntitlementId)
        // the entitlement this line creates is worth what was actually paid plus what the old tier covered
        assertEquals(12000L, PricePaid.moneyOrder(r.conversions, basisLine = 7000, quantity = 1, upgradeUnitAmount = l.upgradeUnitAmount))
    }

    @Test
    fun `row 31 an upgrade credits what was paid for the lower tier not its list price`() {
        val r = price(line(T3), buyer = PricingFixtures.buyer(listOf(owned(501, T1, 2500))))
        assertEquals(2500L, r.upgradeDiscount)
        assertEquals(17500L, r.itemsAmount) // total 175.00
    }

    @Test
    fun `row 32 the upgrade deduction comes after the automatic discount`() {
        val r = price(line(T2), discounts = listOf(D1), buyer = PricingFixtures.buyer(listOf(owned(501, T1, 5000))))
        val l = r.key("L22")
        assertEquals(1200L, l.unitDiscount)
        assertEquals(5000L, l.upgradeUnitAmount)
        assertEquals(5800L, l.unitPrice)
        assertEquals(5800L, r.itemsAmount) // total 58.00
    }

    @Test
    fun `row 33 mode FULL deducts nothing but still replaces the old entitlement`() {
        val full = PricingFixtures.tier(22, 12000, 2, UpgradeMode.FULL)
        val r = price(line(full), buyer = PricingFixtures.buyer(listOf(owned(501, T1, 5000))))
        val l = r.key("L22")
        assertEquals(0L, l.upgradeAmount)
        assertEquals(12000L, r.itemsAmount) // total 120.00
        assertEquals(501L, l.upgradeFromEntitlementId)
    }

    @Test
    fun `row 34 the deduction is capped at what is left after the discount`() {
        val r = price(line(T3), discounts = listOf(D6), buyer = PricingFixtures.buyer(listOf(owned(502, T2, 15000))))
        val l = r.key("L23")
        assertEquals(10000L, l.unitDiscount)
        assertEquals(10000L, l.upgradeUnitAmount)
        assertEquals(0L, l.unitPrice)
        assertEquals(0L, r.itemsAmount) // total 0.00, method free
    }

    @Test
    fun `row 35 the highest owned lower tier is the one that counts`() {
        val buyer = PricingFixtures.buyer(listOf(owned(501, T1, 5000), owned(502, T2, 12000)))
        val r = price(line(T3), buyer = buyer)
        val l = r.key("L23")
        assertEquals(12000L, l.upgradeUnitAmount)
        assertEquals(8000L, r.itemsAmount) // total 80.00
        assertEquals(502L, l.upgradeFromEntitlementId)
    }

    @Test
    fun `row 78 a refunded lower tier finances only what is left of it`() {
        // the caller passes pricePaid net of the refund: 50.00 paid, 20.00 refunded -> 30.00
        val r = price(line(T2), buyer = PricingFixtures.buyer(listOf(owned(501, T1, 3000))))
        assertEquals(3000L, r.upgradeDiscount)
        assertEquals(9000L, r.itemsAmount)
    }

    @Test
    fun `an owned tier of another category or of the same or a higher rank gives nothing`() {
        val otherCategory = OwnedTier(500, 99, 1, 5000)
        val sameRank = OwnedTier(501, 10, 2, 12000)
        val higher = OwnedTier(502, 10, 3, 20000)
        val r = price(line(T2), buyer = PricingFixtures.buyer(listOf(otherCategory, sameRank, higher)))
        assertEquals(0L, r.upgradeDiscount)
        assertNull(r.key("L22").upgradeFromEntitlementId)
        assertEquals(12000L, r.itemsAmount)
    }

    @Test
    fun `two entitlements of the same rank are broken by the higher entitlement id`() {
        val buyer = PricingFixtures.buyer(listOf(OwnedTier(7, 10, 1, 1000), OwnedTier(9, 10, 1, 3000), OwnedTier(8, 10, 1, 2000)))
        val r = price(line(T2), buyer = buyer)
        assertEquals(9L, r.key("L22").upgradeFromEntitlementId)
        assertEquals(3000L, r.upgradeDiscount)
    }

    @Test
    fun `one owned tier finances only one of two tier lines of its category`() {
        // owns T1 (50.00, entitlement 501); the cart holds T2 (120.00) and T3 (200.00) of the same category
        val r = price(line(T2), line(T3), buyer = PricingFixtures.buyer(listOf(owned(501, T1, 5000))))
        assertEquals(5000L, r.upgradeDiscount)
        assertEquals(27000L, r.itemsAmount) // 120.00 + 200.00 - 50.00, not 220.00
        // the line of the highest rank claims the entitlement, the other one pays its full price and links nothing
        assertEquals(5000L, r.key("L23").upgradeAmount)
        assertEquals(501L, r.key("L23").upgradeFromEntitlementId)
        assertEquals(15000L, r.key("L23").unitPrice)
        assertEquals(0L, r.key("L22").upgradeAmount)
        assertNull(r.key("L22").upgradeFromEntitlementId)
        assertEquals(12000L, r.key("L22").unitPrice)
        // the order of the lines does not matter
        val reversed = price(line(T3), line(T2), buyer = PricingFixtures.buyer(listOf(owned(501, T1, 5000))))
        assertEquals(27000L, reversed.itemsAmount)
        assertEquals(501L, reversed.key("L23").upgradeFromEntitlementId)
        assertNull(reversed.key("L22").upgradeFromEntitlementId)
    }

    @Test
    fun `two lines of one tier product with different keys deduct the owned tier once, the first line claims it`() {
        val buyer = PricingFixtures.buyer(listOf(owned(501, T1, 5000)))
        val r = price(line(T2, key = "L22a"), line(T2, key = "L22b"), buyer = buyer)
        assertEquals(5000L, r.upgradeDiscount)
        assertEquals(19000L, r.itemsAmount) // 2 x 120.00 - 50.00
        assertEquals(7000L, r.key("L22a").unitPrice)
        assertEquals(501L, r.key("L22a").upgradeFromEntitlementId)
        assertEquals(12000L, r.key("L22b").unitPrice)
        assertNull(r.key("L22b").upgradeFromEntitlementId)

        // mode FULL records the link on the claimant only, too: an entitlement has one successor (replacedById)
        val full = PricingFixtures.tier(22, 12000, 2, UpgradeMode.FULL)
        val f = price(line(full, key = "a"), line(full, key = "b"), line(full, key = "c"), buyer = buyer)
        assertEquals(0L, f.upgradeDiscount)
        assertEquals(listOf<Long?>(501L, null, null), f.lines.map { it.upgradeFromEntitlementId })
    }

    @Test
    fun `every tiered category has its own claimant and each owned tier finances one line`() {
        val other = PricingFixtures.Product(41, "Other tier", 8000, 0, listOf(11), tier = TierInfo(11, 2, UpgradeMode.DIFFERENCE))
        val buyer = PricingFixtures.buyer(listOf(owned(501, T1, 5000), OwnedTier(502, 11, 1, 3000)))
        val r = price(line(T2), line(other), line(T3), buyer = buyer)
        assertEquals(8000L, r.upgradeDiscount) // 50.00 in category 10 (T3 claims), 30.00 in category 11
        assertEquals(501L, r.key("L23").upgradeFromEntitlementId)
        assertEquals(502L, r.key("L41").upgradeFromEntitlementId)
        assertNull(r.key("L22").upgradeFromEntitlementId)
        assertEquals(12000L + 5000L + 15000L, r.itemsAmount)
        val links = r.lines.mapNotNull { it.upgradeFromEntitlementId }
        assertEquals(links.size, links.toSet().size)
    }

    @Test
    fun `a line that is excluded from the sums does not claim the owned tier`() {
        val cfg = config(mode = CurrencyMode.MULTI, fallback = MultiCurrencyFallback.HIDE)
        // T3 has no USD price and is hidden, T2 has one: T2 is the only priced tier line and claims the entitlement
        val r = price(
            line(T2, currencyPrices = mapOf("USD" to 300)), line(T3), config = cfg, currency = "USD",
            buyer = PricingFixtures.buyer(listOf(owned(501, T1, 5000)))
        )
        assertTrue(r.key("L23").excluded)
        assertNull(r.key("L23").upgradeFromEntitlementId)
        assertEquals(125L, r.key("L22").upgradeAmount) // 50.00 TRY at 0.025 = 1.25 USD
        assertEquals(501L, r.key("L22").upgradeFromEntitlementId)
        assertEquals(175L, r.itemsAmount)
    }

    @Test
    fun `the upgrade is not deducted from a subscription line, under EXTERNAL pricing, or for profiles without upgrades`() {
        val sub = PricingFixtures.Product(31, "Sub tier", 3000, 0, listOf(10), subscription = true, tier = TierInfo(10, 2, UpgradeMode.DIFFERENCE))
        val buyer = PricingFixtures.buyer(listOf(owned(501, T1, 5000)))

        val subscription = price(line(sub), buyer = buyer).key("L31")
        assertEquals(0L, subscription.upgradeAmount)
        assertEquals(501L, subscription.upgradeFromEntitlementId)

        val external = price(line(T2), buyer = buyer, mode = PricingMode.EXTERNAL).key("L22")
        assertEquals(0L, external.upgradeAmount)
        assertEquals(501L, external.upgradeFromEntitlementId)

        for (profile in listOf(PricingProfile.GIFT_CODE, PricingProfile.RENEWAL)) {
            val l = price(line(T2), buyer = buyer, profile = profile).key("L22")
            assertEquals(0L, l.upgradeAmount)
            assertNull(l.upgradeFromEntitlementId)
        }
        for (profile in listOf(PricingProfile.PANEL, PricingProfile.INGAME)) {
            assertEquals(5000L, price(line(T2), buyer = buyer, profile = profile).key("L22").upgradeAmount)
        }
    }

    // ---------------------------------------------------------------- A1: list price, removeCents, bundle, variants

    @Test
    fun `row 36 a bundle is one priced line plus children that cost nothing`() {
        val r = price(line(P5, 2))
        assertEquals(3, r.lines.size)
        val bundle = r.key("L5")
        assertEquals(OrderItemKind.BUNDLE, bundle.kind)
        assertEquals(12000L, bundle.unitPrice)
        assertEquals(24000L, bundle.lineAmount)
        val children = r.lines.filter { it.kind == OrderItemKind.BUNDLE_CHILD }
        assertEquals(listOf(2L, 3L), children.map { it.productId })
        assertEquals(listOf(6, 2), children.map { it.quantity }) // 3 x 2 and 1 x 2
        for (c in children) {
            assertEquals("L5", c.parentLineKey)
            assertEquals(0L, c.listUnitPrice)
            assertEquals(0L, c.unitPrice)
            assertEquals(0L, c.lineAmount)
            assertEquals(0L, c.discountAmount)
            assertEquals(0L, c.creditUnitPrice)
        }
        assertEquals(24000L, r.subtotal)
        assertEquals(24000L, r.itemsAmount) // total 240.00
        assertEquals(setOf("L5/c0", "L5/c1"), children.map { it.lineKey }.toSet())
    }

    @Test
    fun `a bundle takes part in discounts of scope ALL as one line`() {
        val r = price(line(P5), discounts = listOf(D1))
        assertEquals(1200L, r.key("L5").unitDiscount)
        assertEquals(10800L, r.itemsAmount)
        assertTrue(r.lines.filter { it.kind == OrderItemKind.BUNDLE_CHILD }.all { it.discountAmount == 0L })
    }

    @Test
    fun `row 37 and 38 a variant price replaces the product price and a NULL variant price inherits it`() {
        val xl = price(line(P4, variantId = 2, basePrice = 27500))
        assertEquals(27500L, xl.key("L4v2").listUnitPrice)
        assertEquals(27500L, xl.itemsAmount) // lineTotal 275.00 inclusive
        val m = price(line(P4, variantId = 1), config = config(includeVat = false))
        assertEquals(25000L, m.key("L4v1").listUnitPrice)
        assertEquals(25000L, m.subtotal) // subtotal 250.00 exclusive
        assertFalse(m.pricesIncludeVat)
    }

    @Test
    fun `row 40 removeCents rounds the list price to whole units`() {
        val r = price(line(P2, 3), config = config(removeCents = true))
        assertEquals(1000L, r.key("L2").listUnitPrice)
        assertEquals(3000L, r.subtotal)
        assertEquals(3000L, r.itemsAmount) // total 30.00
    }

    @Test
    fun `row 41 removeCents rounds the percent discount per unit to whole units`() {
        val r = price(line(P2, 3), discounts = listOf(D7), config = config(removeCents = true))
        val l = r.key("L2")
        assertEquals(200L, l.unitDiscount) // 1.50 rounds up
        assertEquals(800L, l.unitPrice)
        assertEquals(2400L, r.itemsAmount) // total 24.00
    }

    @Test
    fun `row 42 removeCents on an exclusive price`() {
        val r = price(line(P3), config = config(removeCents = true, includeVat = false))
        assertEquals(5000L, r.key("L3").listUnitPrice)
    }

    @Test
    fun `removeCents quantises a FIXED discount to whole units too`() {
        val d = discount(10, 1250, DiscountUnit.FIXED) // 12.50 -> 13.00
        val r = price(line(P1), discounts = listOf(d), config = config(removeCents = true))
        assertEquals(1300L, r.key("L1").unitDiscount)
        assertEquals(8700L, r.key("L1").unitPrice)
    }

    // ---------------------------------------------------------------- A1: currency modes (rows 59 to 66)

    @Test
    fun `row 59 DISPLAY mode charges in the base currency and carries a display block`() {
        val r = price(line(P1), config = config(mode = CurrencyMode.DISPLAY), currency = "USD")
        assertEquals("TRY", r.currency)
        assertEquals("TRY", r.baseCurrency)
        assertEquals(DisplayInfo("USD", BigDecimal("0.025")), r.display)
        assertEquals(BigDecimal.ONE, r.fxRate)
        assertEquals(10000L, r.itemsAmount)
        assertEquals(250L, r.conversions.toDisplay(r.itemsAmount)) // display total 2.50
        assertTrue(r.messages.isEmpty())

        val keys = price(line(P2, 3), config = config(mode = CurrencyMode.DISPLAY), currency = "USD")
        assertEquals(2997L, keys.itemsAmount)
        assertEquals(75L, keys.conversions.toDisplay(keys.itemsAmount)) // 0.74925 rounds up to 0.75
    }

    @Test
    fun `DISPLAY mode without a usable request has no display block and says so only for a foreign request`() {
        val cfg = config(mode = CurrencyMode.DISPLAY)
        assertNull(price(line(P1), config = cfg).display)
        assertNull(price(line(P1), config = cfg, currency = "TRY").display)
        assertTrue(price(line(P1), config = cfg, currency = "TRY").messages.isEmpty())
        val unknown = price(line(P1), config = cfg, currency = "GBP")
        assertNull(unknown.display)
        assertEquals(listOf(PricingMessage(PricingCode.CURRENCY_NOT_SUPPORTED)), unknown.messages)
        assertEquals(MessageLevel.WARNING, unknown.messages.single().level)
    }

    @Test
    fun `row 60 MULTI mode with an explicit price uses it and the rate`() {
        val r = price(line(P1, 2), config = config(mode = CurrencyMode.MULTI), currency = "USD")
        assertEquals("USD", r.currency)
        assertEquals("TRY", r.baseCurrency)
        assertEquals(BigDecimal("0.025"), r.fxRate)
        assertEquals(299L, r.key("L1").listUnitPrice)
        assertEquals(598L, r.itemsAmount) // total 5.98
        assertNull(r.display)
    }

    @Test
    fun `row 61 MULTI fallback CONVERT converts the base price half up`() {
        val r = price(line(P2, 3), config = config(mode = CurrencyMode.MULTI), currency = "USD")
        assertEquals(25L, r.key("L2").listUnitPrice) // 0.24975 rounds up to 0.25
        assertEquals(75L, r.itemsAmount) // total 0.75
    }

    @Test
    fun `MULTI fallback CONVERT never turns a priced product into a free one, a free product stays free`() {
        val cfg = config(mode = CurrencyMode.MULTI)
        // P8 costs 0.05 TRY: 0.00125 USD and 0.225 JPY round to 0, so one quantum is charged
        val usd = price(line(P8), config = cfg, currency = "USD")
        assertEquals(1L, usd.key("L8").listUnitPrice) // 0.01 USD
        assertEquals(1L, usd.itemsAmount)
        val jpy = price(line(P8), config = cfg, currency = "JPY")
        assertEquals(100L, jpy.key("L8").listUnitPrice) // 1 JPY
        assertEquals(100L, jpy.itemsAmount)
        // the whole of a big quantity is no longer free either
        val bulk = price(line(P8, qty = 100_000), config = cfg, currency = "USD")
        assertEquals(100_000L, bulk.itemsAmount)
        assertEquals(1L, bulk.subtotal / 100_000L)
        // a base price of 0 is a free product: it stays 0 in every currency
        for (c in listOf("USD", "JPY")) {
            val free = price(line(P8, basePrice = 0), config = cfg, currency = c)
            assertEquals(0L, free.key("L8").listUnitPrice, c)
            assertEquals(0L, free.itemsAmount, c)
        }
        // an explicit price of 0 in the currency is the admin's decision and stays 0
        assertEquals(0L, price(line(P8, currencyPrices = mapOf("USD" to 0)), config = cfg, currency = "USD").itemsAmount)
        // at the rounding edge the plain half-up result is kept (rows 61 and 66 have their own tests): 0.20 TRY = 0.005 USD
        assertEquals(1L, price(line(P8, basePrice = 20), config = cfg, currency = "USD").key("L8").listUnitPrice)
        assertEquals(25L, price(line(P2), config = cfg, currency = "USD").key("L2").listUnitPrice)
    }

    @Test
    fun `the CONVERT floor is one quantum of the order currency, whole units with removeCents`() {
        val cfg = config(mode = CurrencyMode.MULTI, removeCents = true)
        // 9.99 TRY = 0.24975 USD, removeCents rounds USD to whole units: 0 -> floored to 1.00 USD
        assertEquals(100L, price(line(P2), config = cfg, currency = "USD").key("L2").listUnitPrice)
        // 100.00 TRY = 2.50 USD rounds half up to 3.00 USD (not floored, well above one unit)
        assertEquals(300L, price(line(P1, currencyPrices = emptyMap()), config = cfg, currency = "USD").key("L1").listUnitPrice)
    }

    @Test
    fun `row 62 MULTI fallback HIDE excludes a line without a price in the currency`() {
        val cfg = config(mode = CurrencyMode.MULTI, fallback = MultiCurrencyFallback.HIDE)
        val r = price(line(P1), line(P2, 3), config = cfg, currency = "USD")
        val hidden = r.key("L2")
        assertTrue(hidden.excluded)
        assertEquals(listOf(PricingCode.NOT_IN_CURRENCY), hidden.errors)
        assertEquals(0L, hidden.lineAmount)
        assertEquals(299L, r.subtotal)
        assertEquals(299L, r.itemsAmount)
        assertFalse(r.canCheckout)
        assertTrue(price(line(P1), config = cfg, currency = "USD").canCheckout)
    }

    @Test
    fun `an excluded bundle takes no discount and brings no children`() {
        val cfg = config(mode = CurrencyMode.MULTI, fallback = MultiCurrencyFallback.HIDE)
        val r = price(line(P5), config = cfg, discounts = listOf(D1), currency = "USD")
        assertEquals(1, r.lines.size)
        assertTrue(r.key("L5").excluded)
        assertEquals(0L, r.discountTotal)
        assertTrue(r.discountRedemptions.isEmpty())
    }

    @Test
    fun `row 64 MULTI mode in a zero-decimal currency prices in whole units`() {
        val cfg = config(mode = CurrencyMode.MULTI)
        val vip = price(line(P1), config = cfg, currency = "JPY")
        assertEquals("JPY", vip.currency)
        assertEquals(45000L, vip.key("L1").listUnitPrice) // 450 JPY
        val key = price(line(P2), config = cfg, currency = "JPY")
        assertEquals(4500L, key.key("L2").listUnitPrice) // 44.955 rounds to 45 JPY
        assertEquals(100L, key.conversions.oq)
    }

    @Test
    fun `row 65 an unknown currency falls back to the base currency with a warning`() {
        val r = price(line(P1), config = config(mode = CurrencyMode.MULTI), currency = "GBP")
        assertEquals("TRY", r.currency)
        assertEquals(10000L, r.itemsAmount)
        assertEquals(listOf(PricingMessage(PricingCode.CURRENCY_NOT_SUPPORTED)), r.messages)
    }

    @Test
    fun `SINGLE mode ignores a foreign request with a warning and asks nothing of the rate table`() {
        val foreign = price(line(P1), currency = "USD")
        assertEquals("TRY", foreign.currency)
        assertEquals(listOf(PricingCode.CURRENCY_NOT_SUPPORTED), foreign.messages.map { it.code })
        assertTrue(price(line(P1), currency = "TRY").messages.isEmpty())
        assertTrue(price(line(P1), currency = null).messages.isEmpty())
        assertTrue(price(line(P1), currency = "  ").messages.isEmpty())
    }

    @Test
    fun `a currency is offered only when it is an additional currency with a positive rate`() {
        val multi = CurrencyMode.MULTI
        // no rate row
        assertEquals("TRY", price(line(P1), config = config(mode = multi, rates = emptyMap()), currency = "USD").currency)
        // a zero rate is no rate
        assertEquals("TRY", price(line(P1), config = config(mode = multi, rates = mapOf("USD" to BigDecimal.ZERO)), currency = "USD").currency)
        // a rate for a currency that is not listed as additional
        assertEquals("TRY", price(line(P1), config = config(mode = multi, additional = listOf("JPY")), currency = "USD").currency)
        // an unsupported (three-decimal) code never gets in, even with a rate
        val kwd = config(mode = multi, additional = listOf("KWD"), rates = mapOf("KWD" to BigDecimal("0.01")))
        assertEquals("TRY", price(line(P1), config = kwd, currency = "KWD").currency)
        // a request is normalised: case and blanks do not matter
        assertEquals("USD", price(line(P1), config = config(mode = multi), currency = " usd ").currency)
    }

    @Test
    fun `row 66 a variant with its own price never takes the product level foreign price`() {
        val rows = listOf(CurrencyPriceResolver.PriceRow(0, "USD", 600))
        val cfg = config(mode = CurrencyMode.MULTI)
        // XL has its own base price: no foreign price, CONVERT gives 275.00 x 0.025 = 6.875 -> 6.88
        val xlPrice = CurrencyPriceResolver.resolve("USD", variantId = 2, variantHasOwnBasePrice = true, rows = rows)
        assertNull(xlPrice)
        val xl = price(line(P4, variantId = 2, basePrice = 27500, currencyPrices = listOfNotNull(xlPrice?.let { "USD" to it }).toMap()), config = cfg, currency = "USD")
        assertEquals(688L, xl.key("L4v2").listUnitPrice)
        // M inherits the product price and so the product-level USD 6.00
        val mPrice = CurrencyPriceResolver.resolve("USD", variantId = 1, variantHasOwnBasePrice = false, rows = rows)
        assertEquals(600L, mPrice)
        val m = price(line(P4, variantId = 1, currencyPrices = mapOf("USD" to mPrice!!)), config = cfg, currency = "USD")
        assertEquals(600L, m.key("L4v1").listUnitPrice)
    }

    @Test
    fun `the currency price resolver prefers the exact row and ignores other currencies`() {
        val rows = listOf(
            CurrencyPriceResolver.PriceRow(0, "USD", 600),
            CurrencyPriceResolver.PriceRow(1, "USD", 650),
            CurrencyPriceResolver.PriceRow(1, "JPY", 9000)
        )
        assertEquals(650L, CurrencyPriceResolver.resolve("USD", 1, false, rows))
        assertEquals(650L, CurrencyPriceResolver.resolve("USD", 1, true, rows))
        assertEquals(600L, CurrencyPriceResolver.resolve("USD", 2, false, rows))
        assertNull(CurrencyPriceResolver.resolve("USD", 2, true, rows))
        assertEquals(600L, CurrencyPriceResolver.resolve("USD", 0, true, rows))
        assertNull(CurrencyPriceResolver.resolve("EUR", 0, false, rows))
        assertEquals(9000L, CurrencyPriceResolver.resolve("JPY", 1, true, rows))
    }

    @Test
    fun `an admin FIXED discount and its threshold are converted into the order currency`() {
        val cfg = config(mode = CurrencyMode.MULTI)
        // 5.00 TRY x 0.025 = 0.125 -> 0.13 USD
        val fixed = discount(10, 500, DiscountUnit.FIXED)
        val r = price(line(P1), discounts = listOf(fixed), config = cfg, currency = "USD")
        assertEquals(13L, r.key("L1").unitDiscount)
        assertEquals(286L, r.key("L1").unitPrice)
        // a minimum of 200.00 TRY is 5.00 USD: 2 x 2.99 = 5.98 reaches it, 1 x 2.99 does not
        val minimum = discount(11, 1000, min = 20000)
        assertEquals(60L, price(line(P1, 2), discounts = listOf(minimum), config = cfg, currency = "USD").discountTotal)
        assertEquals(0L, price(line(P1, 1), discounts = listOf(minimum), config = cfg, currency = "USD").discountTotal)
    }

    // ---------------------------------------------------------------- A2: scopes and exclusions, profiles, pricing modes

    @Test
    fun `credit packs are discounted only when a PRODUCTS scope lists them`() {
        val all = D1
        val categories = discount(20, 5000, scope = DiscountScope.CATEGORIES, categoryIds = setOf(5))
        val listed = discount(21, 2500, scope = DiscountScope.PRODUCTS, productIds = setOf(6))
        assertEquals(0L, price(line(P6), discounts = listOf(all, categories)).discountTotal)
        val r = price(line(P6), discounts = listOf(all, categories, listed))
        assertEquals(2500L, r.key("L6").unitDiscount)
        assertEquals(21L, r.key("L6").discountId)
        assertEquals(OrderItemKind.PRODUCT, r.key("L6").kind)
    }

    @Test
    fun `a category scope matches the category or any ancestor and never a line without a product`() {
        val parent = discount(30, 1000, scope = DiscountScope.CATEGORIES, categoryIds = setOf(99))
        val child = line(P1).let { l ->
            LineInput(l.lineKey, l.productId, l.variantId, l.kind, l.quantity, l.basePrice, l.currencyPrices, l.creditPrice,
                l.vatBp, listOf(1, 50, 99), l.physical, l.subscription, l.tier, l.topUpCredits, l.children)
        }
        assertEquals(1000L, price(child, discounts = listOf(parent)).discountTotal)
        assertEquals(0L, price(line(P1), discounts = listOf(parent)).discountTotal)
        assertEquals(0L, price(line(P2), discounts = listOf(discount(31, 1000, scope = DiscountScope.PRODUCTS, productIds = emptySet()))).discountTotal)
    }

    @Test
    fun `row 72 a subscription line takes no automatic discount`() {
        val r = price(line(P9), discounts = listOf(D1, D6))
        assertEquals(0L, r.discountTotal)
        assertEquals(3000L, r.itemsAmount) // total 30.00
        assertTrue(r.discountRedemptions.isEmpty())
    }

    @Test
    fun `automatic discounts follow the pricing profile`() {
        for (profile in listOf(PricingProfile.STOREFRONT, PricingProfile.PANEL, PricingProfile.INGAME)) {
            assertEquals(1000L, price(line(P1), discounts = listOf(D1), profile = profile).discountTotal, profile.name)
        }
        assertEquals(0L, price(line(P1), discounts = listOf(D1), profile = PricingProfile.RENEWAL).discountTotal)
        assertEquals(10000L, price(line(P1), discounts = listOf(D1), profile = PricingProfile.RENEWAL).itemsAmount)
    }

    @Test
    fun `row 68 under EXTERNAL pricing no discount applies and the quote says so`() {
        val r = price(line(P1), discounts = listOf(D1), mode = PricingMode.EXTERNAL)
        assertEquals(0L, r.discountTotal)
        assertEquals(10000L, r.itemsAmount) // estimate: the gateway sets the price
        assertEquals(listOf(PricingMessage(PricingCode.EXTERNAL_PRICING, MessageLevel.INFO)), r.messages)
        // EXTERNAL_TAX still prices with the market's discounts (the gateway only adds tax)
        assertEquals(1000L, price(line(P1), discounts = listOf(D1), mode = PricingMode.EXTERNAL_TAX).discountTotal)
        assertTrue(price(line(P1), mode = PricingMode.EXTERNAL_TAX).messages.isEmpty())
    }

    @Test
    fun `row 70 a gift code order discounts every line down to nothing without a discount redemption`() {
        val r = price(line(P1), line(P2, 3), discounts = listOf(D1), profile = PricingProfile.GIFT_CODE)
        val vip = r.key("L1")
        assertEquals(10000L, vip.listUnitPrice)
        assertEquals(10000L, vip.unitDiscount)
        assertEquals(0L, vip.unitPrice)
        assertNull(vip.discountId)
        val keys = r.key("L2")
        assertEquals(999L, keys.unitDiscount)
        assertEquals(2997L, keys.discountAmount)
        assertEquals(0L, keys.unitPrice)
        assertEquals(12997L, r.subtotal)
        assertEquals(12997L, r.discountTotal) // discountTotal == subtotal, total 0
        assertEquals(0L, r.itemsAmount)
        assertTrue(r.discountRedemptions.isEmpty()) // the GIFT redemption row is written by the caller
    }

    @Test
    fun `row 71 a panel price override leaves no automatic discount and no upgrade to A2`() {
        val buyer = PricingFixtures.buyer(listOf(owned(501, T1, 5000)))
        val r = price(line(P1), line(P2, 3), line(T2), discounts = listOf(D1), profile = PricingProfile.PANEL, override = 8000, buyer = buyer)
        assertEquals(0L, r.discountTotal)
        assertEquals(0L, r.upgradeDiscount)
        assertNull(r.key("L22").upgradeFromEntitlementId)
        assertEquals(r.subtotal, r.itemsAmount)
    }

    @Test
    fun `row 79 a free amount credit top-up is one synthetic line that no discount touches`() {
        val cfg = config(creditValue = 10) // 0.10 per credit
        val r = price(topUp(25000), config = cfg, discounts = listOf(D1))
        assertEquals(1, r.lines.size)
        val l = r.lines.single()
        assertEquals("topup", l.lineKey)
        assertEquals(OrderItemKind.CREDIT_TOPUP, l.kind)
        assertNull(l.productId)
        assertEquals(2500L, l.listUnitPrice) // 250 credits x 0.10 = 25.00
        assertEquals(0L, l.discountAmount)
        assertEquals(25000L, l.creditAmount)
        assertEquals(2500L, r.itemsAmount)
        assertTrue(r.discountRedemptions.isEmpty())
    }

    @Test
    fun `a top-up costs at least one quantum in the order currency`() {
        val cfg = config(creditValue = 100)
        assertEquals(1L, price(topUp(1), config = cfg).lines.single().listUnitPrice) // 0.01 credit = 0.01 TRY
        val tiny = config(creditValue = 1)
        assertEquals(1L, price(topUp(1), config = tiny).lines.single().listUnitPrice) // 0.0001 rounds to 0, minimum 1
        val whole = config(creditValue = 1, removeCents = true)
        assertEquals(100L, price(topUp(1), config = whole).lines.single().listUnitPrice)
        val multi = config(mode = CurrencyMode.MULTI, creditValue = 10_000) // 100.00 TRY per credit
        assertEquals(250L, price(topUp(100), config = multi, currency = "USD").lines.single().listUnitPrice) // 100 TRY x 0.025 = 2.50 USD
    }

    // ---------------------------------------------------------------- input contract and overflow

    private fun refused(block: () -> Unit): PricingException = assertThrows(PricingException::class.java) { block() }

    @Test
    fun `an input that breaks the contract is refused as INVALID_INPUT`() {
        val cases: List<() -> Unit> = listOf(
            { price(line(P1, 0)) },
            { price(line(P1, PricingLimits.MAX_QUANTITY + 1)) },
            { price(line(P1, basePrice = -1)) },
            { price(line(P1, basePrice = PricingLimits.MAX_AMOUNT + 1)) },
            { price(line(P1, currencyPrices = mapOf("USD" to -5))) },
            { price(line(P1), line(P1)) }, // duplicate line key
            { price(line(T2, 2)) }, // a tiered line has quantity 1
            { price(topUp(100), line(P1)) }, // a top-up is alone
            { price(topUp(0)) },
            { price(topUp(100), profile = PricingProfile.GIFT_CODE) },
            { price(topUp(100), config = config(creditValue = 0)) },
            { price(line(P1), override = 100) }, // override outside PANEL
            { price(line(P1), override = -1, profile = PricingProfile.PANEL) },
            { price(line(T2), buyer = PricingFixtures.buyer(listOf(OwnedTier(1, 10, 1, -1)))) },
            { price(line(P1), config = config(base = "KWD")) },
            { price(line(P5).let { b -> LineInput(b.lineKey, b.productId, 0, b.kind, 100_000, b.basePrice, b.currencyPrices, 0, null, emptyList(), false, false, null, null, listOf(BundleChild(2, 0, 100_000))) }) }
        )
        for ((i, case) in cases.withIndex()) {
            assertEquals(PricingError.INVALID_INPUT, refused(case).error, "case $i")
        }
    }

    @Test
    fun `a cart whose sum does not fit a Long fails as AMOUNT_OVERFLOW and never wraps`() {
        // every line is inside the bounds (100 000 x 10^12 = 10^17); 100 of them are not
        val lines = (1..100).map { line(P1, PricingLimits.MAX_QUANTITY, key = "L$it", basePrice = PricingLimits.MAX_AMOUNT) }
        val e = refused { price(*lines.toTypedArray()) }
        assertEquals(PricingError.AMOUNT_OVERFLOW, e.error)
        // a conversion that overflows is the same error
        val huge = config(mode = CurrencyMode.MULTI, rates = mapOf("USD" to BigDecimal("100000000000")))
        assertEquals(
            PricingError.AMOUNT_OVERFLOW,
            refused { price(line(P1, PricingLimits.MAX_QUANTITY, basePrice = PricingLimits.MAX_AMOUNT, currencyPrices = emptyMap()), config = huge, currency = "USD") }.error
        )
        // the largest legal single line is exact
        val max = price(line(P1, PricingLimits.MAX_QUANTITY, basePrice = PricingLimits.MAX_AMOUNT))
        assertEquals(100_000_000_000_000_000L, max.itemsAmount)
    }

    @Test
    fun `stored discount values outside their range are clamped and never fail a quote`() {
        val over = discount(1, 25_000) // 250 %
        val r = price(line(P1), discounts = listOf(over))
        assertEquals(10000L, r.key("L1").unitDiscount) // clamped to 100 %
        val negative = discount(2, -500)
        assertEquals(0L, price(line(P1), discounts = listOf(negative)).discountTotal)
        val negativeFixed = discount(3, -500, DiscountUnit.FIXED)
        assertEquals(0L, price(line(P1), discounts = listOf(negativeFixed)).discountTotal)
        val hugeFixed = discount(4, Long.MAX_VALUE, DiscountUnit.FIXED)
        assertEquals(10000L, price(line(P1), discounts = listOf(hugeFixed)).key("L1").unitDiscount)
    }

    @Test
    fun `an empty cart prices to nothing`() {
        val r = price()
        assertTrue(r.lines.isEmpty())
        assertEquals(0L, r.subtotal)
        assertEquals(0L, r.itemsAmount)
        assertTrue(r.canCheckout)
    }

    // ---------------------------------------------------------------- determinism

    @Test
    fun `pricing the same input twice, and with the discounts shuffled, gives equal output`() {
        val discounts = listOf(D1, D2, D3, D4, D5, D6, D7)
        val lines = arrayOf(line(P1, 2), line(P2, 3), line(P3), line(P5), line(P6))
        val first = price(*lines, discounts = discounts)
        assertEquals(first, price(*lines, discounts = discounts))
        val rnd = Random(7)
        repeat(50) {
            assertEquals(first, price(*lines, discounts = discounts.shuffled(rnd)))
        }
    }

    // ---------------------------------------------------------------- no floating point, anywhere

    @Test
    fun `the engine and the money primitives contain no floating point`() {
        val roots = listOf("core/money", "core/pricing").map { File("src/main/kotlin/com/panomc/plugins/market/$it") }
        val forbidden = Regex("""\b(Double|Float)\b|\.toDouble\(|\.toFloat\(|roundTo(Long|Int)\(|Math\.(round|floor|ceil)\(|\b\d+\.\d+\b""")
        var scanned = 0
        for (root in roots) {
            assertTrue(root.isDirectory, "missing ${root.path}")
            for (file in root.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
                scanned++
                val code = stripCommentsAndStrings(file.readText())
                val hit = forbidden.find(code)
                assertNull(hit, "${file.name}: floating point '${hit?.value}'")
            }
        }
        assertTrue(scanned >= 10, "scanned only $scanned files")
    }

    // ---------------------------------------------------------------- the seeded property loop

    @Test
    fun `property loop over 10000 seeded carts, unitPrice times quantity is exact and matches an independent oracle`() {
        val rnd = Random(20261005)
        var linesChecked = 0L
        // the loop must not be vacuous: how many carts exercised each branch
        val seen = java.util.TreeMap<String, Int>()
        fun hit(branch: String) = seen.merge(branch, 1) { a, b -> a + b }
        repeat(10_000) { n ->
            val input = randomInput(rnd)
            val result = PricingEngine.priceItems(input)
            val expected = PricingOracle.expect(input)
            val where = "cart #$n"

            assertEquals(expected.currency, result.currency, where)
            if (result.currency != result.baseCurrency) hit("foreign order currency")
            if (input.config.removeCents) hit("removeCents")
            if (input.profile == PricingProfile.GIFT_CODE) hit("gift code")
            if (input.pricingMode == PricingMode.EXTERNAL) hit("external pricing")
            if (result.lines.any { it.excluded }) hit("excluded line")
            if (result.lines.any { it.kind == OrderItemKind.BUNDLE_CHILD }) hit("bundle")
            if (result.discountRedemptions.size > 1) hit("two winning discounts")
            val priced = result.lines.filter { it.kind != OrderItemKind.BUNDLE_CHILD }
            assertEquals(input.lines.size, priced.size, where)

            // one owned entitlement finances at most one line and has one successor: no id is linked twice, and what
            // is deducted for it never exceeds what it was worth
            val links = priced.mapNotNull { it.upgradeFromEntitlementId }
            assertEquals(links.size, links.toSet().size, "$where an entitlement is linked from two lines")
            val deductedFor = HashMap<Long, BigInteger>()
            for (l in priced) {
                val id = l.upgradeFromEntitlementId ?: continue
                deductedFor.merge(id, BigInteger.valueOf(l.upgradeAmount)) { a, b -> a + b }
            }
            for ((id, deducted) in deductedFor) {
                val worth = input.buyer.recipientTiers.single { it.entitlementId == id }.pricePaid
                assertTrue(deducted <= BigInteger.valueOf(result.conversions.toOrder(worth)), "$where entitlement $id deducted $deducted")
            }
            val tierLines = input.lines.indices.filter { input.lines[it].tier != null && !priced[it].excluded }
            if (tierLines.groupBy { input.lines[it].tier!!.categoryId }.values.any { it.size > 1 }) {
                hit("competing tier lines")
                if (links.isNotEmpty()) hit("competing tier lines, one linked")
            }

            val q = result.conversions.oq
            var sumSubtotal = BigInteger.ZERO
            var sumDiscount = BigInteger.ZERO
            var sumUpgrade = BigInteger.ZERO
            var sumItems = BigInteger.ZERO
            val redeemed = HashMap<Long, BigInteger>()
            for ((i, line) in priced.withIndex()) {
                val e = expected.lines[i]
                val tag = "$where line ${line.lineKey}"
                assertEquals(input.lines[i].lineKey, line.lineKey, tag)
                assertEquals(e.excluded, line.excluded, tag)
                assertEquals(e.list, line.listUnitPrice, tag)
                assertEquals(e.unitDiscount, line.unitDiscount, tag)
                assertEquals(e.upgrade, line.upgradeUnitAmount, tag)
                assertEquals(e.discountId, line.discountId, tag)
                assertEquals(e.from, line.upgradeFromEntitlementId, tag)

                // exactness: unitPrice x quantity is the line amount, with no rounding left to do
                val quantity = BigInteger.valueOf(line.quantity.toLong())
                assertEquals(line.listUnitPrice - line.unitDiscount - line.upgradeUnitAmount, line.unitPrice, tag)
                assertTrue(line.unitPrice >= 0, tag)
                assertEquals(BigInteger.valueOf(line.unitPrice).multiply(quantity), BigInteger.valueOf(line.lineAmount), tag)
                assertEquals(BigInteger.valueOf(line.unitDiscount).multiply(quantity), BigInteger.valueOf(line.discountAmount), tag)
                assertEquals(BigInteger.valueOf(line.upgradeUnitAmount).multiply(quantity), BigInteger.valueOf(line.upgradeAmount), tag)
                assertTrue(line.unitDiscount <= line.listUnitPrice, tag)

                // every figure is a multiple of the quantum
                for (v in listOf(line.listUnitPrice, line.unitDiscount, line.upgradeUnitAmount, line.unitPrice)) {
                    assertEquals(0L, v % q, "$tag quantum")
                }

                if (!line.excluded) sumSubtotal += BigInteger.valueOf(line.listUnitPrice).multiply(quantity)
                sumDiscount += BigInteger.valueOf(line.discountAmount)
                sumUpgrade += BigInteger.valueOf(line.upgradeAmount)
                sumItems += BigInteger.valueOf(line.lineAmount)
                if (line.discountId != null) redeemed.merge(line.discountId, BigInteger.valueOf(line.discountAmount)) { a, b -> a + b }
                linesChecked++
                if (line.discountId != null) {
                    val won = input.discounts.single { it.id == line.discountId }
                    hit(if (won.unit == DiscountUnit.PERCENT) "percent winner" else "fixed winner")
                    if (input.discounts.size > 1) hit("winner among several discounts")
                }
                // a priced product never converts to 0: at least one quantum
                if (!line.excluded && input.lines[i].basePrice > 0 && result.currency != result.baseCurrency &&
                    input.lines[i].currencyPrices[result.currency] == null
                ) {
                    assertTrue(line.listUnitPrice >= q, "$tag converted to ${line.listUnitPrice}")
                    if (result.conversions.toOrder(input.lines[i].basePrice) == 0L) hit("cheap CONVERT line floored")
                }
                if (line.upgradeUnitAmount > 0) hit("upgrade deducted")
                if (line.upgradeFromEntitlementId != null && line.upgradeUnitAmount == 0L) hit("upgrade linked, nothing deducted")
                if (line.discountId == null && line.unitDiscount > 0) hit("gift line")
            }
            for (child in result.lines.filter { it.kind == OrderItemKind.BUNDLE_CHILD }) {
                assertEquals(0L, child.lineAmount, where)
                assertEquals(0L, child.unitPrice, where)
            }

            assertEquals(expected.subtotal, BigInteger.valueOf(result.subtotal), where)
            assertEquals(sumSubtotal, BigInteger.valueOf(result.subtotal), where)
            assertEquals(sumDiscount, BigInteger.valueOf(result.discountTotal), where)
            assertEquals(sumUpgrade, BigInteger.valueOf(result.upgradeDiscount), where)
            assertEquals(sumItems, BigInteger.valueOf(result.itemsAmount), where)
            assertEquals(redeemed.toSortedMap().map { it.key to it.value }, result.discountRedemptions.map { it.discountId to BigInteger.valueOf(it.amount) }, where)

            // determinism and independence of the order the discounts arrive in
            assertEquals(result, PricingEngine.priceItems(input), where)
            assertEquals(result, PricingEngine.priceItems(withDiscounts(input, input.discounts.shuffled(rnd))), where)
        }
        assertTrue(linesChecked >= 10_000, "only $linesChecked lines were checked")
        println("PROPERTY-LOOP lines=$linesChecked branches=$seen")
        for (branch in listOf(
            "foreign order currency", "removeCents", "gift code", "external pricing", "excluded line", "bundle",
            "percent winner", "fixed winner", "winner among several discounts", "upgrade deducted",
            "upgrade linked, nothing deducted", "gift line", "competing tier lines", "competing tier lines, one linked",
            "cheap CONVERT line floored"
        )) {
            assertTrue((seen[branch] ?: 0) >= 100, "the loop barely exercised '$branch': ${seen[branch]}")
        }
    }

    private fun withDiscounts(i: PricingInput, discounts: List<DiscountInput>) = PricingInput(
        i.config, i.now, i.profile, i.requestedCurrency, i.lines, i.buyer, discounts, i.coupon, i.creatorCode, i.pricingMode,
        i.payWithCredits, i.priceOverride
    )

    private fun randomInput(rnd: Random): PricingInput {
        val mode = CurrencyMode.values()[rnd.nextInt(3)]
        val cfg = config(
            mode = mode,
            fallback = if (rnd.nextBoolean()) MultiCurrencyFallback.CONVERT else MultiCurrencyFallback.HIDE,
            removeCents = rnd.nextInt(4) == 0,
            includeVat = rnd.nextBoolean()
        )
        val currency = listOf(null, "TRY", "USD", "JPY", "GBP", "usd")[rnd.nextInt(6)]

        val lineCount = 1 + rnd.nextInt(6)
        val tierPool = listOf(10L, 11L)
        val lines = (1..lineCount).map { i ->
            val kindRoll = rnd.nextInt(100)
            val kind = when {
                kindRoll < 70 -> LineKind.PRODUCT
                kindRoll < 82 -> LineKind.BUNDLE
                else -> LineKind.CREDIT_PACK
            }
            val tiered = kind == LineKind.PRODUCT && rnd.nextInt(5) == 0
            val quantity = if (tiered) 1 else if (rnd.nextInt(20) == 0) 1 + rnd.nextInt(100_000) else 1 + rnd.nextInt(20)
            val big = quantity <= 1000 && rnd.nextInt(10) == 0
            val basePrice = when (rnd.nextInt(3)) {
                0 -> rnd.nextLong(0, 30)
                1 -> rnd.nextLong(0, 100_000)
                else -> rnd.nextLong(0, if (big) 1_000_000_000L else 5_000_000L)
            }
            val prices = buildMap {
                if (rnd.nextBoolean()) put("USD", rnd.nextLong(0, 100_000))
                if (rnd.nextInt(3) == 0) put("JPY", rnd.nextLong(0, 5_000_000))
            }
            LineInput(
                lineKey = "k$i", productId = i.toLong(), variantId = rnd.nextLong(0, 3), kind = kind, quantity = quantity,
                basePrice = basePrice, currencyPrices = prices, creditPrice = rnd.nextLong(0, 5000), vatBp = null,
                categoryPath = (1..5).filter { rnd.nextInt(3) == 0 }.map { it.toLong() },
                physical = false, subscription = rnd.nextInt(10) == 0,
                tier = if (tiered) TierInfo(tierPool[rnd.nextInt(2)], 2 + rnd.nextInt(2), if (rnd.nextInt(4) == 0) UpgradeMode.FULL else UpgradeMode.DIFFERENCE) else null,
                topUpCredits = null,
                children = if (kind == LineKind.BUNDLE) listOf(BundleChild(100L + i, 0, 1 + rnd.nextInt(5)), BundleChild(200L + i, 0, 1)) else emptyList()
            )
        }

        val discounts = (1..rnd.nextInt(6)).map { id ->
            val unit = if (rnd.nextBoolean()) DiscountUnit.PERCENT else DiscountUnit.FIXED
            val scope = DiscountScope.values()[rnd.nextInt(3)]
            DiscountInput(
                id = id.toLong() * 3 + rnd.nextInt(3),
                value = if (unit == DiscountUnit.PERCENT) rnd.nextLong(0, 10_001) else rnd.nextLong(0, 200_000),
                unit = unit, scope = scope,
                productIds = (1..6).filter { rnd.nextInt(3) == 0 }.map { it.toLong() }.toSet(),
                categoryIds = (1..5).filter { rnd.nextInt(2) == 0 }.map { it.toLong() }.toSet(),
                minPaymentAmount = if (rnd.nextInt(4) == 0) rnd.nextLong(0, 500_000) else null,
                startDate = if (rnd.nextInt(6) == 0) NOW + rnd.nextLong(-5, 5) else null,
                expiryDate = if (rnd.nextInt(6) == 0) NOW + rnd.nextLong(-5, 5) else null,
                usageLimit = if (rnd.nextInt(6) == 0) rnd.nextInt(1, 5) else null,
                usedCount = rnd.nextInt(0, 6)
            )
        }.distinctBy { it.id }

        val owned = (0 until rnd.nextInt(4)).map { OwnedTier(1000L + it, tierPool[rnd.nextInt(2)], 1 + rnd.nextInt(2), rnd.nextLong(0, 200_000)) }
        val profile = PricingProfile.values()[rnd.nextInt(PricingProfile.values().size)]
        val pricingMode = when (rnd.nextInt(10)) {
            0 -> PricingMode.EXTERNAL
            1 -> PricingMode.EXTERNAL_TAX
            else -> PricingMode.MARKET
        }
        val override = if (profile == PricingProfile.PANEL && rnd.nextInt(5) == 0) rnd.nextLong(0, 100_000) else null
        return PricingFixtures.input(
            *lines.toTypedArray(), config = cfg, discounts = discounts, profile = profile, currency = currency,
            buyer = PricingFixtures.buyer(owned), mode = pricingMode, override = override
        )
    }

    private fun stripCommentsAndStrings(source: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val next = if (i + 1 < source.length) source[i + 1] else ' '
            when {
                c == '/' && next == '/' -> while (i < source.length && source[i] != '\n') i++
                c == '/' && next == '*' -> {
                    var depth = 1
                    i += 2
                    while (i < source.length && depth > 0) {
                        if (source[i] == '/' && i + 1 < source.length && source[i + 1] == '*') { depth++; i += 2 }
                        else if (source[i] == '*' && i + 1 < source.length && source[i + 1] == '/') { depth--; i += 2 }
                        else i++
                    }
                }
                c == '"' -> {
                    i++
                    while (i < source.length && source[i] != '"') { if (source[i] == '\\') i++; i++ }
                    i++
                    sb.append("\"\"")
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }
}
