package com.panomc.plugins.market.core.pricing

import com.panomc.plugins.market.config.CurrencyMode
import com.panomc.plugins.market.config.MultiCurrencyFallback
import com.panomc.plugins.market.core.money.Rounding
import com.panomc.plugins.market.core.pricing.PricingFixtures.CR5
import com.panomc.plugins.market.core.pricing.PricingFixtures.K25
import com.panomc.plugins.market.core.pricing.PricingFixtures.K50P2
import com.panomc.plugins.market.core.pricing.PricingFixtures.KF20
import com.panomc.plugins.market.core.pricing.PricingFixtures.KF500
import com.panomc.plugins.market.core.pricing.PricingFixtures.KMIN
import com.panomc.plugins.market.core.pricing.PricingFixtures.Product
import com.panomc.plugins.market.core.pricing.PricingFixtures.coupon
import com.panomc.plugins.market.core.pricing.PricingFixtures.creatorCode
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
import com.panomc.plugins.market.core.pricing.PricingFixtures.P5M
import com.panomc.plugins.market.core.pricing.PricingFixtures.P6
import com.panomc.plugins.market.core.pricing.PricingFixtures.P8
import com.panomc.plugins.market.core.pricing.PricingFixtures.P9
import com.panomc.plugins.market.core.pricing.PricingFixtures.T1
import com.panomc.plugins.market.core.pricing.PricingFixtures.T2
import com.panomc.plugins.market.core.pricing.PricingFixtures.T3
import com.panomc.plugins.market.core.pricing.PricingFixtures.config
import com.panomc.plugins.market.core.pricing.PricingFixtures.discount
import com.panomc.plugins.market.core.pricing.PricingFixtures.full
import com.panomc.plugins.market.core.pricing.PricingFixtures.line
import com.panomc.plugins.market.core.pricing.PricingFixtures.owned
import com.panomc.plugins.market.core.pricing.PricingFixtures.price
import com.panomc.plugins.market.core.pricing.PricingFixtures.topUp
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.PaymentFeeMode
import com.panomc.plugins.market.db.model.UpgradeMode
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.payment.PriceAuthority
import com.panomc.plugins.market.util.CouponScope
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
        // what A2 decides: no winner, no redemption row, no upgrade link or deduction. The override itself (what the lines
        // cost, discountTotal 49.97 for P1 + 3 x P2 at 80.00) is stage A4: PricingProfileTest `row 71 ...`
        val buyer = PricingFixtures.buyer(listOf(owned(501, T1, 5000)))
        val r = price(line(P1), line(P2, 3), line(T2), discounts = listOf(D1), profile = PricingProfile.PANEL, override = 8000, buyer = buyer)
        assertEquals(0L, r.upgradeDiscount)
        assertNull(r.key("L22").upgradeFromEntitlementId)
        assertTrue(r.lines.all { it.discountId == null && it.upgradeAmount == 0L })
        assertTrue(r.discountRedemptions.isEmpty())
        assertEquals(8000L, r.itemsTotal)
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
            { price(line(P5).let { b -> LineInput(b.lineKey, b.productId, 0, b.kind, 100_000, b.basePrice, b.currencyPrices, 0, null, emptyList(), false, false, null, null, listOf(BundleChild(2, 0, 100_000, false))) }) }
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

    // ================================================================ stage A3: coupon and creator code (05 section 6)

    private fun ItemsResult.reasons(): List<PricingCode?> = listOf(coupon?.reason, creatorCode?.reason)

    @Test
    fun `row 14 a percent coupon takes its share of every eligible line`() {
        val r = price(line(P1), line(P2, 3), coupon = K25)
        assertEquals(2500L, r.key("L1").couponShare)
        assertEquals(749L, r.key("L2").couponShare) // 29.97 x 25 % = 7.4925
        assertEquals(3249L, r.couponDiscount)
        assertEquals(7500L, r.key("L1").lineBasis)
        assertEquals(2248L, r.key("L2").lineBasis)
        assertEquals(9748L, r.itemsTotal)
        assertEquals(1625L, r.itemsVat) // 12.50 + 3.75
        assertEquals(CodeOutcome(1, "K25", true, null, 3249), r.coupon)
        assertNull(r.creatorCode)
        assertEquals(0L, r.creatorDiscount)
        assertTrue(r.canCheckout)
        assertEquals(12997L, r.subtotal) // the codes change neither the subtotal nor the automatic figures
        assertEquals(0L, r.discountTotal)
    }

    @Test
    fun `row 15 a FIXED coupon is allocated over the lines by largest remainder`() {
        val r = price(line(P1), line(P2, 3), coupon = KF20)
        assertEquals(1539L, r.key("L1").couponShare)
        assertEquals(461L, r.key("L2").couponShare)
        assertEquals(2000L, r.couponDiscount)
        assertEquals(8461L, r.key("L1").lineBasis)
        assertEquals(2536L, r.key("L2").lineBasis)
        assertEquals(10997L, r.itemsTotal)
        assertEquals(1833L, r.itemsVat) // 14.10 + 4.23
    }

    @Test
    fun `row 16 a FIXED coupon larger than the cart takes the whole cart and leaves no VAT`() {
        val r = price(line(P1), coupon = KF500)
        assertEquals(10000L, r.couponDiscount)
        assertEquals(10000L, r.key("L1").couponShare)
        assertEquals(0L, r.itemsBasis)
        assertEquals(0L, r.itemsTotal)
        assertEquals(0L, r.itemsVat)
        assertTrue(r.coupon!!.valid)
    }

    @Test
    fun `row 17 a scoped coupon touches only the listed products`() {
        val r = price(line(P1), line(P2, 3), coupon = K50P2)
        assertEquals(0L, r.key("L1").couponShare)
        assertEquals(1499L, r.key("L2").couponShare) // 14.985 rounds half up
        assertEquals(1499L, r.couponDiscount)
        assertEquals(1498L, r.key("L2").lineBasis)
        assertEquals(11498L, r.itemsTotal)
    }

    @Test
    fun `row 18 a scoped coupon with nothing eligible is refused and ignored`() {
        val r = price(line(P1), coupon = K50P2)
        assertEquals(CodeOutcome(4, "K50P2", false, PricingCode.COUPON_NOT_APPLICABLE, 0), r.coupon)
        assertEquals(10000L, r.itemsTotal)
        assertFalse(r.canCheckout)
        assertEquals(listOf(PricingMessage(PricingCode.COUPON_NOT_APPLICABLE, MessageLevel.ERROR)), r.messages)
    }

    @Test
    fun `row 19 the coupon minimum is checked against the whole cart`() {
        val r = price(line(P1), line(P2, 3), coupon = KMIN) // 129.97 < 150.00
        assertEquals(PricingCode.CODE_MIN_AMOUNT, r.coupon!!.reason)
        assertFalse(r.coupon!!.valid)
        assertEquals(12997L, r.itemsTotal)
        assertFalse(r.canCheckout)
    }

    @Test
    fun `row 20 the coupon minimum looks at the amount after the automatic discount`() {
        val r = price(line(P1, 2), discounts = listOf(D1), coupon = KMIN) // 200.00 -> 180.00 >= 150.00
        assertTrue(r.coupon!!.valid)
        assertEquals(4500L, r.couponDiscount)
        assertEquals(13500L, r.itemsTotal)
        // and just below the minimum it is refused: 150.00 is enough, 149.99 is not
        val exact = coupon(9, "EXACT", 2500, min = 18000)
        assertTrue(price(line(P1, 2), discounts = listOf(D1), coupon = exact).coupon!!.valid)
        val above = coupon(9, "ABOVE", 2500, min = 18001)
        assertEquals(PricingCode.CODE_MIN_AMOUNT, price(line(P1, 2), discounts = listOf(D1), coupon = above).coupon!!.reason)
    }

    @Test
    fun `row 21 every state of a coupon gives its reason and a total that is untouched`() {
        val cases = listOf(
            coupon(1, "K25", 2500, found = false) to PricingCode.CODE_NOT_FOUND,
            coupon(1, "K25", 2500, active = false) to PricingCode.CODE_NOT_FOUND,
            coupon(1, "K25", 2500, start = NOW + 1) to PricingCode.CODE_NOT_STARTED,
            coupon(1, "K25", 2500, expiry = NOW) to PricingCode.CODE_EXPIRED,
            coupon(1, "K25", 2500, redeemLimit = 3, used = 3) to PricingCode.CODE_LIMIT_REACHED,
            coupon(1, "K25", 2500, customerRedeemLimit = 1, buyerUses = 1) to PricingCode.CODE_LIMIT_REACHED
        )
        for ((c, reason) in cases) {
            val r = price(line(P1), coupon = c)
            assertEquals(reason, r.coupon!!.reason, "$reason")
            assertFalse(r.coupon!!.valid)
            assertEquals(0L, r.couponDiscount)
            assertEquals(10000L, r.itemsTotal)
            assertFalse(r.canCheckout)
        }
        // the boundaries on the other side are all valid
        for (c in listOf(
            coupon(1, "K25", 2500, start = NOW), coupon(1, "K25", 2500, expiry = NOW + 1),
            coupon(1, "K25", 2500, redeemLimit = 3, used = 2), coupon(1, "K25", 2500, customerRedeemLimit = 1, buyerUses = 0)
        )) {
            assertEquals(2500L, price(line(P1), coupon = c).couponDiscount)
        }
    }

    @Test
    fun `the first failing check of a coupon gives the reason`() {
        // not found beats everything, not started beats expired, expired beats the limits, the limits beat the lines
        assertEquals(PricingCode.CODE_NOT_FOUND, price(line(P1), coupon = coupon(1, "K", 2500, found = false, expiry = NOW)).coupon!!.reason)
        assertEquals(PricingCode.CODE_NOT_STARTED, price(line(P1), coupon = coupon(1, "K", 2500, start = NOW + 5, expiry = NOW)).coupon!!.reason)
        assertEquals(PricingCode.CODE_EXPIRED, price(line(P1), coupon = coupon(1, "K", 2500, expiry = NOW, redeemLimit = 1, used = 1)).coupon!!.reason)
        assertEquals(
            PricingCode.CODE_LIMIT_REACHED,
            price(line(P1), coupon = coupon(1, "K", 2500, scope = CouponScope.SELECTED, redeemLimit = 1, used = 1)).coupon!!.reason
        )
        assertEquals(
            PricingCode.COUPON_NOT_APPLICABLE,
            price(line(P1), coupon = coupon(1, "K", 2500, scope = CouponScope.SELECTED, min = 99_999_999)).coupon!!.reason
        )
        assertEquals(PricingCode.EXTERNAL_PRICING, price(line(P1), coupon = coupon(1, "K", 2500, found = false), mode = PricingMode.EXTERNAL).coupon!!.reason)
    }

    @Test
    fun `row 22 automatic discounts and a coupon stack when they may`() {
        val r = price(line(P1), discounts = listOf(D1), coupon = K25)
        assertEquals(9000L, r.key("L1").unitPrice)
        assertEquals(2250L, r.couponDiscount)
        assertEquals(6750L, r.itemsTotal)
        assertEquals(1125L, r.itemsVat)
        assertEquals(1000L, r.discountTotal)
        assertEquals(listOf(DiscountRedemption(1, 1000)), r.discountRedemptions)
    }

    @Test
    fun `row 23 without combining, a coupon that beats the automatic discount takes its place`() {
        val r = price(line(P1), discounts = listOf(D1), coupon = K25, config = config(combine = false))
        assertEquals(0L, r.discountTotal)
        assertEquals(10000L, r.key("L1").unitPrice)
        assertNull(r.key("L1").discountId)
        assertEquals(2500L, r.couponDiscount)
        assertEquals(7500L, r.itemsTotal)
        assertTrue(r.discountRedemptions.isEmpty()) // no DISCOUNT redemption row in S2
        assertTrue(r.coupon!!.valid)
    }

    @Test
    fun `row 24 without combining, an automatic discount that beats the coupon keeps its place`() {
        val r = price(line(P1), discounts = listOf(D5), coupon = K25, config = config(combine = false))
        assertEquals(4000L, r.discountTotal)
        assertEquals(6000L, r.itemsTotal)
        assertEquals(0L, r.couponDiscount)
        assertEquals(CodeOutcome(1, "K25", false, PricingCode.CODE_NOT_COMBINABLE, 0), r.coupon)
        assertEquals(listOf(DiscountRedemption(5, 4000)), r.discountRedemptions)
        assertFalse(r.canCheckout) // checkout would answer 400 INVALID_COUPON: the buyer must remove it
    }

    @Test
    fun `row 25 without combining, a tie keeps the automatic discount and does not consume the coupon`() {
        val r = price(line(P1), discounts = listOf(discount(10, 2500)), coupon = K25, config = config(combine = false))
        assertEquals(2500L, r.discountTotal)
        assertEquals(7500L, r.itemsTotal)
        assertEquals(PricingCode.CODE_NOT_COMBINABLE, r.coupon!!.reason)
        assertEquals(0L, r.couponDiscount)
    }

    @Test
    fun `row 26 a creator code takes a percentage like a coupon and keeps the attribution`() {
        val r = price(line(P1), creatorCode = CR5)
        assertEquals(500L, r.creatorDiscount)
        assertEquals(500L, r.key("L1").creatorShare)
        assertEquals(500L, r.key("L1").couponAmount)
        assertEquals(9500L, r.itemsTotal)
        assertEquals(1583L, r.itemsVat)
        assertEquals(CodeOutcome(1, "CR5", true, null, 500), r.creatorCode)
        assertNull(r.coupon)
    }

    @Test
    fun `row 27 the creator code takes its share of what the coupon left`() {
        val r = price(line(P1), coupon = K25, creatorCode = CR5)
        val l = r.key("L1")
        assertEquals(2500L, l.couponShare)
        assertEquals(375L, l.creatorShare) // 5 % of 75.00
        assertEquals(2875L, l.couponAmount)
        assertEquals(2500L, r.couponDiscount)
        assertEquals(375L, r.creatorDiscount)
        assertEquals(7125L, r.itemsTotal)
        assertEquals(1188L, r.itemsVat) // 11.875 rounds half up
    }

    @Test
    fun `row 28 a creator never benefits from their own code`() {
        val own = creatorCode(creatorUserId = 900)
        val cases = listOf(
            "payer and recipient" to PricingFixtures.buyer(userId = 900, recipientUserId = 900),
            // the payer clause alone: the recipient is somebody else, so only `creatorUserId == buyer.userId` refuses it
            "creator pays a gift for somebody else" to PricingFixtures.buyer(userId = 900, recipientUserId = 5),
            "creator pays, recipient not resolved" to PricingFixtures.buyer(userId = 900, recipientUserId = null),
            "gift to the creator by a guest" to PricingFixtures.buyer(userId = null, loggedIn = false, recipientUserId = 900, email = "guest@example.com"),
            "gift to the creator by another user" to PricingFixtures.buyer(userId = 5, recipientUserId = 900)
        )
        for ((name, buyer) in cases) {
            val r = price(line(P1), creatorCode = own, buyer = buyer)
            assertEquals(CodeOutcome(1, "CR", false, PricingCode.CODE_NOT_FOUND, 0), r.creatorCode, name)
            assertEquals(10000L, r.itemsTotal, name)
            assertFalse(r.canCheckout, name)
        }
        // counter-case: somebody else paying for somebody else gets the discount (neither clause matches the creator)
        val other = price(line(P1), creatorCode = own, buyer = PricingFixtures.buyer(userId = 5, recipientUserId = 6))
        assertEquals(CodeOutcome(1, "CR", true, null, 500), other.creatorCode)
        assertEquals(500L, other.creatorDiscount)
        assertEquals(9500L, other.itemsTotal)
        assertTrue(other.canCheckout)
        // the order e-mail of the creator's account, written differently
        val byMail = creatorCode(creatorUserId = 900, creatorEmail = " Creator@Example.com ")
        val guest = PricingFixtures.buyer(userId = null, loggedIn = false, recipientUserId = null, email = "creator@EXAMPLE.com")
        assertEquals(PricingCode.CODE_NOT_FOUND, price(line(P1), creatorCode = byMail, buyer = guest).creatorCode!!.reason)
        // somebody else is not the creator; a missing e-mail never matches a missing e-mail
        assertTrue(price(line(P1), creatorCode = byMail, buyer = PricingFixtures.buyer(userId = 5, email = "else@example.com")).creatorCode!!.valid)
        val noMail = creatorCode(creatorUserId = null, creatorEmail = null)
        assertTrue(price(line(P1), creatorCode = noMail, buyer = PricingFixtures.buyer(userId = null, loggedIn = false, recipientUserId = null, email = null)).creatorCode!!.valid)
    }

    @Test
    fun `row 29 without combining, a creator code loses its discount but keeps the attribution`() {
        val r = price(line(P1), discounts = listOf(D5), creatorCode = CR5, config = config(combine = false))
        assertEquals(6000L, r.itemsTotal)
        assertEquals(0L, r.creatorDiscount)
        assertEquals(CodeOutcome(1, "CR5", true, PricingCode.CODE_NOT_COMBINABLE, 0), r.creatorCode)
        assertTrue(r.canCheckout) // valid: the reason is an info message
        assertEquals(listOf(PricingMessage(PricingCode.CODE_NOT_COMBINABLE, MessageLevel.INFO)), r.messages)
    }

    @Test
    fun `a creator code that has its own failing checks is refused with the reason`() {
        val cases = listOf(
            creatorCode(found = false) to PricingCode.CODE_NOT_FOUND,
            creatorCode(active = false) to PricingCode.CODE_NOT_FOUND,
            creatorCode(start = NOW + 1) to PricingCode.CODE_NOT_STARTED,
            creatorCode(expiry = NOW) to PricingCode.CODE_EXPIRED,
            creatorCode(redeemLimit = 2, used = 2) to PricingCode.CODE_LIMIT_REACHED
        )
        for ((c, reason) in cases) {
            val r = price(line(P1), creatorCode = c)
            assertEquals(reason, r.creatorCode!!.reason)
            assertFalse(r.creatorCode!!.valid)
            assertEquals(0L, r.creatorDiscount)
            assertEquals(10000L, r.itemsTotal)
            assertFalse(r.canCheckout)
            assertEquals(listOf(PricingMessage(reason, MessageLevel.ERROR)), r.messages)
        }
        // a creator code has no per-customer limit and no minimum amount: those are coupon checks
        assertEquals(500L, price(line(P1), creatorCode = creatorCode(redeemLimit = 2, used = 1)).creatorDiscount)
    }

    @Test
    fun `a creator code without a discount is valid and gives attribution only`() {
        for (c in listOf(creatorCode(value = 0), creatorCode(value = 0, unit = DiscountUnit.FIXED))) {
            val r = price(line(P1), creatorCode = c)
            assertEquals(CodeOutcome(1, "CR", true, null, 0), r.creatorCode)
            assertEquals(10000L, r.itemsTotal)
            assertTrue(r.messages.isEmpty())
        }
        // even where nothing is eligible: there is no discount to zero, so no reason
        assertNull(price(line(P9), creatorCode = creatorCode(value = 0)).creatorCode!!.reason)
    }

    @Test
    fun `a creator code takes nothing from a subscription, a credit purchase or a line the coupon emptied`() {
        // subscription (row 72), credit pack and top-up: valid with the info reason COUPON_NOT_APPLICABLE, discount 0
        for (lines in listOf(arrayOf(line(P9)), arrayOf(line(P6)))) {
            val r = price(*lines, creatorCode = CR5)
            assertEquals(CodeOutcome(1, "CR5", true, PricingCode.COUPON_NOT_APPLICABLE, 0), r.creatorCode)
            assertEquals(r.itemsAmount, r.itemsBasis)
            assertTrue(r.canCheckout)
        }
        val topUp = price(topUp(25000), config = config(creditValue = 10), creatorCode = CR5)
        assertEquals(PricingCode.COUPON_NOT_APPLICABLE, topUp.creatorCode!!.reason)
        // a 100 % coupon leaves nothing for the creator code
        val everything = coupon(7, "ALL", 10000)
        val r = price(line(P1), coupon = everything, creatorCode = CR5)
        assertEquals(10000L, r.couponDiscount)
        assertEquals(0L, r.creatorDiscount)
        assertEquals(PricingCode.COUPON_NOT_APPLICABLE, r.creatorCode!!.reason)
        assertTrue(r.creatorCode!!.valid)
        assertEquals(0L, r.itemsTotal)
        // a mixed cart: the creator code takes the product and leaves the pack
        val mixed = price(line(P1), line(P6), creatorCode = CR5)
        assertEquals(500L, mixed.creatorDiscount)
        assertEquals(0L, mixed.key("L6").creatorShare)
    }

    @Test
    fun `row 54 a coupon does not discount a credit pack unless the pack is listed`() {
        val r = price(line(P1), line(P6), coupon = K25)
        assertEquals(2500L, r.couponDiscount) // P1 only
        assertEquals(0L, r.key("L6").couponShare)
        assertEquals(17500L, r.itemsTotal)
        // K25 on a pack alone
        val alone = price(line(P6), coupon = K25)
        assertEquals(PricingCode.COUPON_NOT_APPLICABLE, alone.coupon!!.reason)
        assertEquals(10000L, alone.itemsTotal)
        // the admin lists the pack explicitly: now it takes the coupon
        val listed = coupon(8, "PACK", 2500, scope = CouponScope.SELECTED, productIds = setOf(6))
        val pack = price(line(P6), coupon = listed)
        assertEquals(2500L, pack.couponDiscount)
        // a category scope never reaches a credit pack
        val byCategory = coupon(8, "CAT", 2500, scope = CouponScope.SELECTED, categoryIds = setOf(5))
        assertEquals(PricingCode.COUPON_NOT_APPLICABLE, price(line(P6), coupon = byCategory).coupon!!.reason)
    }

    @Test
    fun `a selected coupon takes products by id and by category, bundles included`() {
        val byCategory = coupon(8, "CAT", 5000, scope = CouponScope.SELECTED, categoryIds = setOf(2))
        val r = price(line(P1), line(P2, 2), line(P3), coupon = byCategory)
        assertEquals(0L, r.key("L1").couponShare)
        assertEquals(999L, r.key("L2").couponShare) // 19.98 / 2
        assertEquals(2495L, r.key("L3").couponShare) // 49.90 / 2
        val bundle = coupon(8, "B", 1000, scope = CouponScope.SELECTED, productIds = setOf(5))
        val b = price(line(P5), coupon = bundle)
        assertEquals(1200L, b.key("L5").couponShare)
        assertTrue(b.lines.filter { it.kind == OrderItemKind.BUNDLE_CHILD }.all { it.couponShare == 0L && it.lineTotal == 0L })
        // scope ALL takes bundles too
        assertEquals(3000L, price(line(P5), coupon = K25).couponDiscount)
    }

    @Test
    fun `a coupon takes nothing from a line that costs nothing, a hidden line or a subscription`() {
        // a line the automatic discount made free is not eligible
        val free = price(line(P2, 2), discounts = listOf(D2), coupon = K25)
        assertEquals(PricingCode.COUPON_NOT_APPLICABLE, free.coupon!!.reason)
        assertEquals(0L, free.itemsTotal)
        // a line hidden by the MULTI / HIDE fallback is out of every sum
        val hide = config(mode = CurrencyMode.MULTI, fallback = MultiCurrencyFallback.HIDE)
        val r = price(line(P1), line(P2, 3), config = hide, currency = "USD", coupon = K25)
        assertTrue(r.key("L2").excluded)
        assertEquals(0L, r.key("L2").couponShare)
        assertEquals(75L, r.couponDiscount) // 25 % of 2.99 = 0.7475 -> 0.75
        assertFalse(r.canCheckout) // the hidden line is an error of its own
        // the minimum of the coupon is entered in the base currency and converted: 299.00 TRY is 7.48 USD
        val min = coupon(8, "MIN", 2500, min = 29900)
        assertEquals(PricingCode.CODE_MIN_AMOUNT, price(line(P1), line(P2, 3), config = hide, currency = "USD", coupon = min).coupon!!.reason)
        // a subscription next to a product: only the product takes the coupon
        val mixed = price(line(P9), line(P1), coupon = K25)
        assertEquals(0L, mixed.key("L9").couponShare)
        assertEquals(2500L, mixed.couponDiscount)
        assertEquals(10500L, mixed.itemsTotal) // 30.00 + 75.00
    }

    @Test
    fun `a FIXED code splits its amount by largest remainder and a tie goes to the lower line`() {
        // 0.01 over two equal lines: the first line gets the quantum
        val a = line(P1, key = "a")
        val b = line(P1, key = "b")
        val r = price(a, b, coupon = coupon(8, "ONE", 1, DiscountUnit.FIXED))
        assertEquals(listOf(1L, 0L), listOf(r.key("a").couponShare, r.key("b").couponShare))
        assertEquals(1L, r.couponDiscount)
        // 1.00 over 1 : 2 : 3 (6.00 of cart) is 0.17 / 0.33 / 0.50 exactly by rounding down plus the leftover
        val x = line(P2, key = "x", basePrice = 100)
        val y = line(P2, key = "y", basePrice = 200)
        val z = line(P2, key = "z", basePrice = 300)
        val s = price(x, y, z, coupon = coupon(8, "SPLIT", 100, DiscountUnit.FIXED))
        assertEquals(listOf(17L, 33L, 50L), listOf(s.key("x").couponShare, s.key("y").couponShare, s.key("z").couponShare))
        assertEquals(100L, s.couponDiscount)
        // never more than the line and always exactly the amount: 0.02 over three equal 0.01 lines
        val tiny = listOf("p", "q", "r").map { line(P2, key = it, basePrice = 1) }
        val t = price(*tiny.toTypedArray(), coupon = coupon(8, "TWO", 2, DiscountUnit.FIXED))
        assertEquals(listOf(1L, 1L, 0L), tiny.map { t.key(it.lineKey).couponShare })
        assertEquals(2L, t.couponDiscount)
        assertTrue(t.lines.all { it.lineBasis >= 0 })
    }

    @Test
    fun `a coupon in whole units under removeCents takes whole units`() {
        val cfg = config(removeCents = true)
        val r = price(line(P2, 3), config = cfg, coupon = K25) // 30.00 x 25 % = 7.50 -> 8.00
        assertEquals(800L, r.couponDiscount)
        val fixed = price(line(P2, 3), config = cfg, coupon = coupon(8, "F", 1050, DiscountUnit.FIXED)) // 10.50 -> 11.00 (half up)
        assertEquals(1100L, fixed.couponDiscount)
        assertTrue(fixed.lines.all { it.couponShare % 100 == 0L && it.lineBasis % 100 == 0L })
    }

    @Test
    fun `row 63 a FIXED coupon is converted into the order currency`() {
        val cfg = config(mode = CurrencyMode.MULTI)
        val r = price(line(P1, 2), config = cfg, currency = "USD", coupon = KF20)
        assertEquals(50L, r.couponDiscount) // 20.00 TRY = 0.50 USD
        assertEquals(548L, r.itemsTotal)
        assertEquals(91L, r.itemsVat)
        // a coupon that converts to nothing takes nothing and is still valid
        val tiny = price(line(P1, 2), config = cfg, currency = "USD", coupon = coupon(8, "T", 10, DiscountUnit.FIXED))
        assertEquals(0L, tiny.couponDiscount)
        assertTrue(tiny.coupon!!.valid)
    }

    @Test
    fun `row 68 an external price takes no code and says so once on the quote`() {
        val r = price(line(P1), discounts = listOf(D1), coupon = K25, creatorCode = CR5, mode = PricingMode.EXTERNAL)
        assertEquals(0L, r.discountTotal)
        assertEquals(0L, r.couponDiscount)
        assertEquals(0L, r.creatorDiscount)
        assertEquals(CodeOutcome(1, "K25", false, PricingCode.EXTERNAL_PRICING, 0), r.coupon)
        assertEquals(CodeOutcome(1, "CR5", false, PricingCode.EXTERNAL_PRICING, 0), r.creatorCode)
        assertEquals(10000L, r.itemsTotal) // an estimate
        assertEquals(0L, r.itemsVat)
        assertEquals(listOf(PricingMessage(PricingCode.EXTERNAL_PRICING, MessageLevel.INFO)), r.messages)
        assertFalse(r.canCheckout) // an entered code that cannot apply must be removed
        assertTrue(price(line(P1), mode = PricingMode.EXTERNAL).canCheckout)
    }

    @Test
    fun `row 72 a subscription takes no promotion`() {
        val r = price(line(P9), discounts = listOf(D1), coupon = K25)
        assertEquals(0L, r.discountTotal)
        assertEquals(PricingCode.COUPON_NOT_APPLICABLE, r.coupon!!.reason)
        assertEquals(3000L, r.itemsTotal)
        assertEquals(500L, r.itemsVat)
    }

    @Test
    fun `row 79 a credit top-up takes no code`() {
        val cfg = config(creditValue = 10)
        val r = price(topUp(25000), config = cfg, discounts = listOf(D1), coupon = K25)
        assertEquals(PricingCode.COUPON_NOT_APPLICABLE, r.coupon!!.reason)
        assertEquals(2500L, r.itemsTotal)
        assertEquals(417L, r.itemsVat) // VAT is computed as for any line
        // a SELECTED coupon cannot list a top-up either (it has no product)
        val selected = coupon(8, "S", 2500, scope = CouponScope.SELECTED, productIds = setOf(0))
        assertEquals(PricingCode.COUPON_NOT_APPLICABLE, price(topUp(25000), config = cfg, coupon = selected).coupon!!.reason)
    }

    @Test
    fun `a code of a profile that takes none is refused as a caller bug`() {
        for (profile in listOf(PricingProfile.PANEL, PricingProfile.GIFT_CODE, PricingProfile.INGAME, PricingProfile.RENEWAL)) {
            assertEquals(PricingError.INVALID_INPUT, refused { price(line(P1), coupon = K25, profile = profile) }.error, "$profile coupon")
            assertEquals(PricingError.INVALID_INPUT, refused { price(line(P1), creatorCode = CR5, profile = profile) }.error, "$profile creator")
            price(line(P1), profile = profile) // without a code they all price
        }
    }

    // ---------------------------------------------------------------- the combine rule (05 section 6.4) both ways

    @Test
    fun `combine on stacks the coupon on top of the automatic discount`() {
        val r = price(line(P1), line(P2, 3), discounts = listOf(D1), coupon = K25, config = config(combine = true))
        // 10 % off first (P1 90.00, P2 8.99 x 3), then 25 % of what is left per line (22.50 and 6.74)
        assertEquals(1300L, r.discountTotal)
        assertEquals(2924L, r.couponDiscount)
        assertEquals(8773L, r.itemsBasis)
        assertTrue(r.coupon!!.valid)
    }

    @Test
    fun `combine off takes the cheaper of the automatic discount and the coupon, never both`() {
        val lines = arrayOf(line(P1), line(P2, 3))
        val off = config(combine = false)
        val r = price(*lines, discounts = listOf(D1), coupon = K25, config = off)
        // S1 (discount only) 116.97, S2 (coupon only) 97.48: the coupon wins
        assertEquals(11697L, price(*lines, discounts = listOf(D1), config = off).itemsBasis)
        assertEquals(9748L, price(*lines, coupon = K25, config = off).itemsBasis)
        assertEquals(0L, r.discountTotal)
        assertEquals(3249L, r.couponDiscount)
        assertEquals(9748L, r.itemsBasis)
        // with a bigger automatic discount the order flips, whatever the order the inputs come in
        for (discounts in listOf(listOf(D1, D5), listOf(D5, D1))) {
            val flipped = price(*lines, discounts = discounts, coupon = K25, config = off)
            assertEquals(7797L, flipped.itemsBasis) // 40 % off per unit: 60.00 + 3 x 5.99
            assertEquals(0L, flipped.couponDiscount)
            assertEquals(PricingCode.CODE_NOT_COMBINABLE, flipped.coupon!!.reason)
        }
    }

    @Test
    fun `without combining, a code whose minimum is met only without the automatic discount can win there`() {
        // 2 x 100.00 = 200.00; D1 takes 10 % (180.00 < 190.00); the coupon wants 190.00 and takes 25 %
        val needs190 = coupon(8, "MIN190", 2500, min = 19000)
        val stacked = price(line(P1, 2), discounts = listOf(D1), coupon = needs190, config = config(combine = true))
        assertEquals(PricingCode.CODE_MIN_AMOUNT, stacked.coupon!!.reason) // stacked, it is checked after the discount
        assertEquals(18000L, stacked.itemsTotal)
        val either = price(line(P1, 2), discounts = listOf(D1), coupon = needs190, config = config(combine = false))
        assertTrue(either.coupon!!.valid) // S2: no automatic discount, 200.00 >= 190.00
        assertEquals(0L, either.discountTotal)
        assertEquals(5000L, either.couponDiscount)
        assertEquals(15000L, either.itemsTotal) // cheaper than the 180.00 of S1
        assertTrue(either.discountRedemptions.isEmpty())
    }

    @Test
    fun `without combining, the upgrade deduction applies in both scenarios`() {
        val buyer = PricingFixtures.buyer(listOf(owned(501, T1, 5000)))
        val s2 = price(line(T2), discounts = listOf(D1), coupon = K25, buyer = buyer, config = config(combine = false))
        // S1: 120.00 - 12.00 - 50.00 = 58.00; S2: 120.00 - 50.00 = 70.00 and the coupon 25 % of 70.00 = 17.50 -> 52.50
        assertEquals(5000L, s2.upgradeDiscount)
        assertEquals(0L, s2.discountTotal)
        assertEquals(1750L, s2.couponDiscount)
        assertEquals(5250L, s2.itemsBasis)
        assertEquals(501L, s2.key("L22").upgradeFromEntitlementId)
        // and the entitlement the line creates is worth what was really paid (row 30 with a coupon)
        val l = s2.key("L22")
        assertEquals(10250L, PricePaid.moneyOrder(s2.conversions, l.lineBasis, l.quantity, l.upgradeUnitAmount))
    }

    @Test
    fun `without combining, the choice with no automatic discount or no code is no choice`() {
        val noDiscount = price(line(P1), coupon = K25, config = config(combine = false))
        assertEquals(2500L, noDiscount.couponDiscount)
        assertTrue(noDiscount.coupon!!.valid)
        val noCode = price(line(P1), discounts = listOf(D1), config = config(combine = false))
        assertEquals(1000L, noCode.discountTotal)
        // an expired coupon next to an automatic discount: the reason stays the coupon's own
        val expired = price(line(P1), discounts = listOf(D1), coupon = coupon(8, "OLD", 5000, expiry = NOW), config = config(combine = false))
        assertEquals(PricingCode.CODE_EXPIRED, expired.coupon!!.reason)
        assertEquals(1000L, expired.discountTotal)
        // a coupon that gives nothing keeps its own validity
        val zero = price(line(P1), discounts = listOf(D1), coupon = coupon(8, "ZERO", 0), config = config(combine = false))
        assertTrue(zero.coupon!!.valid)
        assertEquals(1000L, zero.discountTotal)
    }

    @Test
    fun `without combining, a coupon and a creator code together are weighed against the automatic discount`() {
        val both = price(line(P1), discounts = listOf(D1), coupon = K25, creatorCode = CR5, config = config(combine = false))
        // S1 90.00 against S2 71.25: the codes win together
        assertEquals(0L, both.discountTotal)
        assertEquals(2500L, both.couponDiscount)
        assertEquals(375L, both.creatorDiscount)
        assertEquals(7125L, both.itemsTotal)
        val small = price(line(P1), discounts = listOf(D5), coupon = K25, creatorCode = CR5, config = config(combine = false))
        // S1 60.00 against S2 71.25: the discount wins, the coupon is refused, the creator keeps the attribution
        assertEquals(4000L, small.discountTotal)
        assertEquals(PricingCode.CODE_NOT_COMBINABLE, small.coupon!!.reason)
        assertFalse(small.coupon!!.valid)
        assertEquals(PricingCode.CODE_NOT_COMBINABLE, small.creatorCode!!.reason)
        assertTrue(small.creatorCode!!.valid)
        assertEquals(6000L, small.itemsTotal)
    }

    // ================================================================ stage A4: VAT and line totals (05 section 7)

    @Test
    fun `row 1 VAT contained in a gross price`() {
        val r = price(line(P1))
        val l = r.key("L1")
        assertEquals(10000L, r.subtotal)
        assertEquals(1667L, r.itemsVat)
        assertEquals(1667L, l.vatAmount)
        assertEquals(2000L, l.vatPercent)
        assertEquals(10000L, l.lineBasis)
        assertEquals(10000L, l.lineTotal)
        assertEquals(10000L, r.itemsTotal)
    }

    @Test
    fun `row 2 inclusive VAT rounds half up`() {
        val r = price(line(P2, 3))
        assertEquals(2997L, r.subtotal)
        assertEquals(500L, r.itemsVat) // 4.995
        assertEquals(2997L, r.itemsTotal)
    }

    @Test
    fun `row 3 exclusive VAT is added on top`() {
        val r = price(line(P1), config = config(includeVat = false))
        val l = r.key("L1")
        assertEquals(10000L, r.subtotal)
        assertEquals(2000L, l.vatAmount)
        assertEquals(10000L, l.lineBasis)
        assertEquals(12000L, l.lineTotal)
        assertEquals(12000L, r.itemsTotal)
        assertEquals(2000L, r.itemsVat)
    }

    @Test
    fun `row 4 exclusive VAT rounds the added amount`() {
        val r = price(line(P2, 3), config = config(includeVat = false))
        assertEquals(599L, r.itemsVat) // 5.994
        assertEquals(3596L, r.itemsTotal)
    }

    @Test
    fun `row 5 a per-product VAT rate overrides the global one`() {
        val r = price(line(P1), line(P3))
        assertEquals(1667L, r.key("L1").vatAmount)
        assertEquals(454L, r.key("L3").vatAmount) // 49.90 at 10 %
        assertEquals(1000L, r.key("L3").vatPercent)
        assertEquals(2121L, r.itemsVat)
        assertEquals(14990L, r.itemsTotal)
    }

    @Test
    fun `row 6 a per-product VAT rate with exclusive prices`() {
        val r = price(line(P3, 2), config = config(includeVat = false))
        assertEquals(9980L, r.key("L3").lineBasis)
        assertEquals(998L, r.itemsVat)
        assertEquals(10978L, r.itemsTotal)
    }

    @Test
    fun `VAT is taken on the amount after every discount, with showVatInPrice on and off`() {
        // row 7: percent discount per unit
        val seven = price(line(P2, 3), discounts = listOf(D1))
        assertEquals(450L, seven.itemsVat) // 26.97 inside: 4.495 -> 4.50
        assertEquals(2697L, seven.itemsTotal)
        // row 9: best discount wins per line
        val nine = price(line(P1), line(P3), discounts = listOf(D1, D3))
        assertEquals(1908L, nine.itemsVat) // 15.00 + 4.08
        assertEquals(13490L, nine.itemsTotal)
        // row 11
        val eleven = price(line(P1, 2), discounts = listOf(D4))
        assertEquals(2667L, eleven.itemsVat)
        assertEquals(16000L, eleven.itemsTotal)
        // row 69: exclusive prices and a FIXED coupon
        val sixtyNine = price(line(P1), coupon = KF20, config = config(includeVat = false))
        assertEquals(8000L, sixtyNine.key("L1").lineBasis)
        assertEquals(1600L, sixtyNine.itemsVat)
        assertEquals(9600L, sixtyNine.itemsTotal)
        // a percent coupon on exclusive prices: VAT on the discounted net
        val exclusive = price(line(P1), coupon = K25, config = config(includeVat = false))
        assertEquals(1500L, exclusive.itemsVat)
        assertEquals(9000L, exclusive.itemsTotal)
    }

    @Test
    fun `rows 30 to 35 the upgrade deduction is taken before VAT`() {
        fun total(vararg lines: LineInput, discounts: List<DiscountInput> = emptyList(), owns: List<OwnedTier>) =
            price(*lines, discounts = discounts, buyer = PricingFixtures.buyer(owns))
        val r30 = total(line(T2), owns = listOf(owned(501, T1, 5000)))
        assertEquals(7000L, r30.itemsTotal)
        assertEquals(1167L, r30.itemsVat)
        val r31 = total(line(T3), owns = listOf(owned(501, T1, 2500)))
        assertEquals(17500L, r31.itemsTotal)
        assertEquals(2917L, r31.itemsVat)
        val r32 = total(line(T2), discounts = listOf(D1), owns = listOf(owned(501, T1, 5000)))
        assertEquals(5800L, r32.itemsTotal)
        assertEquals(967L, r32.itemsVat)
        val full = PricingFixtures.tier(22, 12000, 2, UpgradeMode.FULL)
        val r33 = total(line(full), owns = listOf(owned(501, T1, 5000)))
        assertEquals(12000L, r33.itemsTotal)
        assertEquals(2000L, r33.itemsVat)
        val r34 = total(line(T3), discounts = listOf(D6), owns = listOf(owned(502, T2, 15000)))
        assertEquals(0L, r34.itemsTotal)
        assertEquals(0L, r34.itemsVat)
        val r35 = total(line(T3), owns = listOf(owned(501, T1, 5000), owned(502, T2, 12000)))
        assertEquals(8000L, r35.itemsTotal)
        assertEquals(1333L, r35.itemsVat)
    }

    @Test
    fun `row 36 a bundle is one priced line and its children carry no amount at all`() {
        val r = price(line(P5, 2))
        val bundle = r.key("L5")
        assertEquals(24000L, bundle.lineTotal)
        assertEquals(4000L, bundle.vatAmount)
        assertEquals(24000L, r.itemsTotal)
        assertEquals(4000L, r.itemsVat)
        val children = r.lines.filter { it.kind == OrderItemKind.BUNDLE_CHILD }
        assertEquals(listOf(6, 2), children.map { it.quantity })
        for (c in children) {
            assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 0L, 0L), listOf(c.lineAmount, c.couponShare, c.creatorShare, c.lineBasis, c.vatPercent, c.vatAmount, c.lineTotal))
        }
    }

    @Test
    fun `rows 37 and 38 a variant price and the item part of a physical order`() {
        val xl = price(line(P4, variantId = 2, basePrice = 27500))
        assertEquals(27500L, xl.key("L4v2").lineTotal)
        assertEquals(4583L, xl.itemsVat) // 45.83, shipping VAT is stage B
        val exclusive = price(line(P4, variantId = 1), config = config(includeVat = false))
        assertEquals(30000L, exclusive.itemsTotal)
        assertEquals(5000L, exclusive.itemsVat)
        assertEquals(25000L, exclusive.subtotal)
    }

    @Test
    fun `rows 40 to 42 removeCents rounds the amounts and the VAT is taken on whole units`() {
        val cfg = config(removeCents = true)
        val r40 = price(line(P2, 3), config = cfg)
        assertEquals(3000L, r40.subtotal)
        assertEquals(500L, r40.itemsVat)
        assertEquals(3000L, r40.itemsTotal)
        val r41 = price(line(P2, 3), config = cfg, discounts = listOf(D7))
        assertEquals(800L, r41.key("L2").unitPrice)
        assertEquals(400L, r41.itemsVat)
        assertEquals(2400L, r41.itemsTotal)
        val r42 = price(line(P3), config = config(removeCents = true, includeVat = false))
        assertEquals(5000L, r42.key("L3").listUnitPrice)
        assertEquals(500L, r42.itemsVat)
        assertEquals(5500L, r42.itemsTotal)
    }

    @Test
    fun `row 58 tiny amounts keep one VAT cent`() {
        val r = price(line(P8, 3), discounts = listOf(D6))
        assertEquals(6L, r.itemsTotal)
        assertEquals(1L, r.itemsVat)
    }

    @Test
    fun `rows 59 to 61 and 64 VAT in other currencies`() {
        // DISPLAY: charged in the base currency, the converted figure is informative
        val display = config(mode = CurrencyMode.DISPLAY)
        val one = price(line(P1), config = display, currency = "USD")
        assertEquals(10000L, one.itemsTotal)
        assertEquals(1667L, one.itemsVat)
        assertEquals(250L, one.conversions.toDisplay(one.itemsTotal))
        assertEquals(75L, price(line(P2, 3), config = display, currency = "USD").let { it.conversions.toDisplay(it.itemsTotal) }) // 74.925
        // MULTI with an explicit price (row 60) and the converted fallback (row 61)
        val multi = config(mode = CurrencyMode.MULTI)
        val r60 = price(line(P1, 2), config = multi, currency = "USD")
        assertEquals(598L, r60.itemsTotal)
        assertEquals(100L, r60.itemsVat)
        val r61 = price(line(P2, 3), config = multi, currency = "USD")
        assertEquals(75L, r61.itemsTotal)
        assertEquals(13L, r61.itemsVat) // 0.125 rounds half up to a whole cent of the order currency
        // zero-decimal currency: VAT in whole yen, half up
        val r64 = price(line(P1), line(P2), config = multi, currency = "JPY")
        assertEquals(7500L, r64.key("L1").vatAmount) // 75 JPY
        assertEquals(800L, r64.key("L2").vatAmount) // 7.5 JPY -> 8 JPY
        assertEquals(0L, r64.itemsVat % 100)
    }

    @Test
    fun `row 67 when the gateway adds the tax the lines carry none`() {
        val r = price(line(P1), mode = PricingMode.EXTERNAL_TAX)
        val l = r.key("L1")
        assertEquals(8333L, l.lineTotal)
        assertEquals(10000L, l.lineBasis)
        assertEquals(0L, l.vatAmount)
        assertEquals(0L, l.vatPercent)
        assertEquals(8333L, r.itemsTotal)
        assertEquals(0L, r.itemsVat)
        // with exclusive prices the gateway simply adds its tax to the net
        val net = price(line(P1), mode = PricingMode.EXTERNAL_TAX, config = config(includeVat = false))
        assertEquals(10000L, net.itemsTotal)
        assertEquals(0L, net.itemsVat)
        // a coupon still works: the tax is taken off the discounted amount
        val withCoupon = price(line(P1), mode = PricingMode.EXTERNAL_TAX, coupon = K25)
        assertEquals(2500L, withCoupon.couponDiscount)
        assertEquals(6250L, withCoupon.itemsTotal) // 75.00 - 12.50
    }

    @Test
    fun `row 70 a gift code order is worth nothing and carries no VAT`() {
        val r = price(line(P1), line(P2, 3), discounts = listOf(D1), profile = PricingProfile.GIFT_CODE)
        assertEquals(0L, r.itemsTotal)
        assertEquals(0L, r.itemsVat)
        assertEquals(0L, r.itemsBasis)
        assertTrue(r.lines.all { it.lineTotal == 0L && it.vatAmount == 0L && it.lineBasis == 0L })
    }

    @Test
    fun `a product rate of zero, a rate over 100 percent and a negative global rate are clamped`() {
        val zero = Product(30, "Tax free", 10000, vatBp = 0)
        val r0 = price(line(zero))
        assertEquals(0L, r0.itemsVat)
        assertEquals(0L, r0.key("L30").vatPercent)
        assertEquals(10000L, r0.itemsTotal)
        val huge = Product(31, "Silly rate", 10000, vatBp = 20_000)
        val rh = price(line(huge))
        assertEquals(10_000L, rh.key("L31").vatPercent)
        assertEquals(5000L, rh.itemsVat) // 100 % VAT: half of the gross is VAT
        val negative = price(line(P1), config = config(vatBp = -5))
        assertEquals(0L, negative.itemsVat)
        val exclusive = price(line(huge), config = config(includeVat = false))
        assertEquals(10_000L, exclusive.itemsVat)
        assertEquals(20_000L, exclusive.itemsTotal)
    }

    @Test
    fun `VAT rows of a result add up to the VAT total and the line totals to the items total`() {
        for (includeVat in listOf(true, false)) {
            val r = price(line(P1, 2), line(P2, 3), line(P3, 4), line(P6), line(P9), discounts = listOf(D1),
                coupon = K25, creatorCode = CR5, config = config(includeVat = includeVat))
            assertEquals(r.itemsVat, r.lines.sumOf { it.vatAmount }, "includeVat=$includeVat")
            assertEquals(r.itemsTotal, r.lines.sumOf { it.lineTotal })
            assertEquals(r.itemsBasis, r.lines.sumOf { it.lineBasis })
            assertEquals(r.itemsTotal, r.itemsBasis + if (includeVat) 0L else r.itemsVat)
            // the identity of 05 section 7
            assertEquals(r.itemsBasis, r.subtotal - r.discountTotal - r.upgradeDiscount - r.couponDiscount - r.creatorDiscount)
        }
    }

    @Test
    fun `a line hidden by the currency fallback carries nothing`() {
        val hide = config(mode = CurrencyMode.MULTI, fallback = MultiCurrencyFallback.HIDE)
        val r = price(line(P1), line(P2, 3), config = hide, currency = "USD")
        val hidden = r.key("L2")
        assertTrue(hidden.excluded)
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L), listOf(hidden.lineBasis, hidden.vatPercent, hidden.vatAmount, hidden.lineTotal, hidden.couponShare))
        assertEquals(299L, r.itemsTotal)
        assertEquals(50L, r.itemsVat)
    }

    @Test
    fun `row 81 the physical basis counts the physical lines only, after the coupon`() {
        val r = price(line(P1), line(P4, variantId = 1), coupon = K25)
        assertEquals(18750L, r.physicalBasis)
        assertEquals(18750L, r.physicalBasisBase)
        assertEquals(7500L + 18750L, r.itemsBasis)
        assertTrue(r.requiresShipping)
        val digital = price(line(P1), coupon = K25)
        assertFalse(digital.requiresShipping)
        assertEquals(0L, digital.physicalBasis)
        // base-currency figures of a foreign order are converted back: 6.88 USD is 275.20 TRY
        val multi = config(mode = CurrencyMode.MULTI)
        val usd = price(line(P4, variantId = 2, basePrice = 27500), config = multi, currency = "USD")
        assertEquals(688L, usd.physicalBasis)
        assertEquals(27520L, usd.physicalBasisBase) // 6.88 / 0.025 = 275.20
        assertEquals(27520L, usd.itemsBasisBase)
    }

    @Test
    fun `a physical line hidden by the currency fallback needs no shipping and adds nothing to the physical basis`() {
        val hide = config(mode = CurrencyMode.MULTI, fallback = MultiCurrencyFallback.HIDE)
        val r = price(line(P1), line(P4, variantId = 1), config = hide, currency = "USD", coupon = K25)
        assertTrue(r.key("L4v1").excluded)
        assertFalse(r.key("L1").excluded)
        assertFalse(r.requiresShipping) // "any priced line is physical": a hidden line is not priced
        assertEquals(0L, r.physicalBasis)
        assertEquals(0L, r.physicalBasisBase)
        // the same cart in the base currency does need shipping
        assertTrue(price(line(P1), line(P4, variantId = 1), coupon = K25).requiresShipping)
    }

    @Test
    fun `a bundle with a physical child needs shipping and its whole basis is the physical basis, after the coupon`() {
        // the bundle's own flag is 0 (forced on save); the physical child alone makes it shippable
        assertFalse(line(P5M).physical)
        val r = price(line(P5M), coupon = K25)
        assertEquals(3000L, r.key("L15").couponShare) // 25 % of 120.00
        assertEquals(9000L, r.key("L15").lineBasis)
        assertTrue(r.requiresShipping)
        assertEquals(9000L, r.physicalBasis)
        assertEquals(r.key("L15").lineBasis, r.physicalBasis)
        assertEquals(9000L, r.physicalBasisBase)
        // the children are output lines of their own and never carry a price or a basis
        assertEquals(listOf(0L, 0L), r.lines.filter { it.kind == OrderItemKind.BUNDLE_CHILD }.map { it.lineBasis })
        // next to a digital product only the bundle counts, quantity scales its basis
        val mixed = price(line(P1), line(P5M, 2), coupon = K25)
        assertTrue(mixed.requiresShipping)
        assertEquals(mixed.key("L15").lineBasis, mixed.physicalBasis)
        assertEquals(18000L, mixed.physicalBasis) // 2 x 120.00 - 25 %
        assertEquals(7500L + 18000L, mixed.itemsBasis)
        // a bundle priced in a foreign currency: the base-currency figure is converted back from the order currency
        val multi = config(mode = CurrencyMode.MULTI)
        val usd = price(line(P5M, basePrice = 12000, currencyPrices = mapOf("USD" to 300)), config = multi, currency = "USD")
        assertEquals(300L, usd.physicalBasis)
        assertEquals(12000L, usd.physicalBasisBase)
    }

    @Test
    fun `a bundle of digital children needs no shipping and adds nothing to the physical basis`() {
        val r = price(line(P5), coupon = K25)
        assertFalse(r.requiresShipping)
        assertEquals(0L, r.physicalBasis)
        assertEquals(0L, r.physicalBasisBase)
        assertEquals(9000L, r.itemsBasis)
        // a physical product next to it still counts alone
        val mixed = price(line(P5), line(P4, variantId = 1), coupon = K25)
        assertTrue(mixed.requiresShipping)
        assertEquals(mixed.key("L4v1").lineBasis, mixed.physicalBasis)
        // a bundle with no child at all is not shippable either
        val empty = Product(16, "Empty bundle", 1000, 0, listOf(4), kind = LineKind.BUNDLE)
        assertFalse(price(line(empty)).requiresShipping)
    }

    @Test
    fun `a bundle with a physical child hidden by the currency fallback needs no shipping`() {
        val hide = config(mode = CurrencyMode.MULTI, fallback = MultiCurrencyFallback.HIDE)
        val r = price(line(P1), line(P5M), config = hide, currency = "USD", coupon = K25)
        assertTrue(r.key("L15").excluded)
        assertFalse(r.key("L1").excluded)
        assertFalse(r.requiresShipping)
        assertEquals(0L, r.physicalBasis)
        assertEquals(0L, r.physicalBasisBase)
        // the same cart in the base currency does ship
        assertTrue(price(line(P1), line(P5M), coupon = K25).requiresShipping)
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

    // ---------------------------------------------------------------- the seeded property loop of stages A3 and A4

    @Test
    fun `property loop over 10000 seeded carts with codes and VAT, shares add up, rows add up and an independent oracle agrees`() {
        val rnd = Random(20261006)
        val seen = java.util.TreeMap<String, Int>()
        fun hit(branch: String) = seen.merge(branch, 1) { a, b -> a + b }
        var linesChecked = 0L
        repeat(10_000) { n ->
            val input = randomCodesInput(rnd)
            val result = PricingEngine.priceItems(input)
            val expected = PricingOracle.expectFull(input)
            val where = "cart #$n"
            val q = result.conversions.oq
            val priced = result.lines.filter { it.kind != OrderItemKind.BUNDLE_CHILD }
            assertEquals(input.lines.size, priced.size, where)

            // the independent oracle: every figure of every line, and the outcome of every code
            assertEquals(expected.currency, result.currency, where)
            for ((i, line) in priced.withIndex()) {
                val e = expected.lines[i]
                val tag = "$where line ${line.lineKey}"
                assertEquals(e.a2.list, line.listUnitPrice, tag)
                assertEquals(e.a2.unitDiscount, line.unitDiscount, tag)
                assertEquals(e.a2.upgrade, line.upgradeUnitAmount, tag)
                assertEquals(e.a2.discountId, line.discountId, tag)
                assertEquals(e.couponShare, line.couponShare, "$tag coupon share")
                assertEquals(e.creatorShare, line.creatorShare, "$tag creator share")
                assertEquals(e.basis, line.lineBasis, "$tag basis")
                assertEquals(e.vatPercent, line.vatPercent, "$tag vat percent")
                assertEquals(e.vat, line.vatAmount, "$tag vat")
                assertEquals(e.total, line.lineTotal, "$tag total")
            }
            fun same(name: String, engine: CodeOutcome?, oracle: PricingOracle.CodeOut?) {
                assertEquals(oracle == null, engine == null, "$where $name present")
                if (engine != null && oracle != null) {
                    assertEquals(oracle.valid, engine.valid, "$where $name valid")
                    assertEquals(oracle.reason, engine.reason, "$where $name reason")
                    assertEquals(oracle.discount, engine.discount, "$where $name discount")
                }
            }
            same("coupon", result.coupon, expected.coupon)
            same("creator code", result.creatorCode, expected.creator)
            assertEquals(expected.basis, BigInteger.valueOf(result.itemsBasis), where)
            assertEquals(expected.total, BigInteger.valueOf(result.itemsTotal), where)
            assertEquals(expected.vat, BigInteger.valueOf(result.itemsVat), where)
            assertEquals(expected.physicalBasis, BigInteger.valueOf(result.physicalBasis), where)
            assertEquals(expected.requiresShipping, result.requiresShipping, "$where requiresShipping")
            assertEquals(expected.basisBase, result.itemsBasisBase, where)
            assertEquals(expected.physicalBasisBase, result.physicalBasisBase, where)

            // the identities of 05 section 7, summed on BigInteger so that nothing can wrap
            fun sum(f: (PricedLine) -> Long) = priced.fold(BigInteger.ZERO) { a, l -> a + BigInteger.valueOf(f(l)) }
            val identity = BigInteger.valueOf(result.subtotal) - BigInteger.valueOf(result.discountTotal) - BigInteger.valueOf(result.upgradeDiscount) -
                BigInteger.valueOf(result.couponDiscount) - BigInteger.valueOf(result.creatorDiscount)
            assertEquals(identity, sum { it.lineBasis }, "$where basis identity")
            assertEquals(BigInteger.valueOf(result.itemsBasis), sum { it.lineBasis }, where)
            assertEquals(BigInteger.valueOf(result.itemsTotal), sum { it.lineTotal }, where)
            assertEquals(BigInteger.valueOf(result.itemsVat), sum { it.vatAmount }, where)
            assertEquals(BigInteger.valueOf(result.couponDiscount), sum { it.couponShare }, where)
            assertEquals(BigInteger.valueOf(result.creatorDiscount), sum { it.creatorShare }, where)
            if (input.pricingMode == PricingMode.MARKET) {
                val vatOnTop = if (input.config.pricesIncludeVat) BigInteger.ZERO else BigInteger.valueOf(result.itemsVat)
                assertEquals(sum { it.lineBasis } + vatOnTop, BigInteger.valueOf(result.itemsTotal), "$where total identity")
            }
            // a code reports what it took, and the lines carry exactly that
            assertEquals(result.coupon?.discount ?: 0L, result.couponDiscount, where)
            assertEquals(result.creatorCode?.discount ?: 0L, result.creatorDiscount, where)

            for (line in priced) {
                val tag = "$where line ${line.lineKey}"
                linesChecked++
                // nothing negative, shares stay inside the line, the figures are on the quantum
                assertTrue(line.couponShare >= 0 && line.creatorShare >= 0 && line.lineBasis >= 0 && line.vatAmount >= 0 && line.lineTotal >= 0, tag)
                assertEquals(line.couponShare + line.creatorShare, line.couponAmount, tag)
                assertTrue(line.couponAmount <= line.lineAmount, tag)
                assertEquals(line.lineAmount - line.couponAmount, line.lineBasis, tag)
                for (v in listOf(line.couponShare, line.creatorShare, line.lineBasis, line.vatAmount, line.lineTotal)) assertEquals(0L, v % q, "$tag quantum")
                // lineTotal <= listUnitPrice x quantity, plus the VAT added on top of exclusive prices
                val ceiling = BigInteger.valueOf(line.listUnitPrice) * BigInteger.valueOf(line.quantity.toLong()) +
                    if (input.pricingMode == PricingMode.MARKET && !input.config.pricesIncludeVat) BigInteger.valueOf(line.vatAmount) else BigInteger.ZERO
                assertTrue(BigInteger.valueOf(line.lineTotal) <= ceiling, "$tag total over the list price")
                // a code never takes from a line it does not apply to
                val source = input.lines.single { it.lineKey == line.lineKey }
                if (line.couponAmount > 0) {
                    assertFalse(source.subscription, "$tag a code took from a subscription")
                    assertFalse(source.kind == LineKind.CREDIT_TOPUP, tag)
                }
                if (line.creatorShare > 0) assertTrue(source.kind == LineKind.PRODUCT || source.kind == LineKind.BUNDLE, "$tag creator share on ${source.kind}")
                if (line.excluded) assertEquals(0L, line.lineTotal + line.lineBasis + line.couponAmount, tag)
                assertTrue(line.vatAmount <= line.lineBasis, "$tag VAT over the basis")
                val rate = source.vatBp
                if (rate != null) hit("per-product VAT rate")
                if (rate != null && rate !in 0L..10_000L) hit("VAT rate clamped")
                if (source.physical && !line.excluded) hit("physical line")
                if (source.kind == LineKind.BUNDLE && source.children.any { it.physical }) {
                    hit(if (line.excluded) "hidden bundle with a physical child" else "bundle shipped by a physical child")
                }
                if (source.kind == LineKind.BUNDLE && !line.excluded && source.children.none { it.physical }) hit("digital bundle")
            }

            // the codes: branch counters (the loop must not be vacuous)
            val coupon = result.coupon
            val creator = result.creatorCode
            if (coupon != null) hit(if (coupon.valid) "coupon applied" else "coupon refused: ${coupon.reason}")
            if (creator != null) {
                hit(if (!creator.valid) "creator code refused: ${creator.reason}" else if (creator.reason != null) "creator code attribution only: ${creator.reason}" else "creator code applied")
            }
            if (result.couponDiscount > 0 && result.creatorDiscount > 0) hit("coupon then creator code")
            if (input.coupon?.unit == DiscountUnit.FIXED && priced.count { it.couponShare > 0 } >= 2) hit("FIXED coupon allocated over several lines")
            if (input.creatorCode?.unit == DiscountUnit.FIXED && priced.count { it.creatorShare > 0 } >= 2) hit("FIXED creator code allocated over several lines")
            if (input.pricingMode == PricingMode.EXTERNAL_TAX) hit("EXTERNAL_TAX")
            if (input.pricingMode == PricingMode.EXTERNAL) hit("EXTERNAL")
            hit(if (input.config.pricesIncludeVat) "VAT inclusive" else "VAT exclusive")
            if (input.config.removeCents) hit("removeCents")
            if (result.currency != result.baseCurrency) hit("foreign order currency")
            // only the id clauses count, and only where the e-mail clause does not also refuse the code, so every hit
            // is a case that one clause alone decides (a null creatorUserId never matches a null buyer id)
            input.creatorCode?.let { c ->
                val creator = c.creatorUserId
                val mail = c.creatorEmail?.trim()?.lowercase()
                val sameMail = !mail.isNullOrEmpty() && mail == input.buyer.email?.trim()?.lowercase()
                if (creator != null && !sameMail) {
                    val payer = creator == input.buyer.userId
                    val recipient = creator == input.buyer.recipientUserId
                    if (payer && !recipient) hit("own code: payer only")
                    if (recipient && !payer) hit("own code: recipient only")
                }
            }

            // combine off: the chosen basis is the cheaper of "automatic discounts only" and "codes only" (property 6)
            if (!input.config.combineDiscountsAndCoupons && (input.coupon != null || input.creatorCode != null)) {
                val s1 = PricingEngine.priceItems(withCodes(input, null, null))
                val s2 = PricingEngine.priceItems(withDiscounts(input, emptyList()))
                assertEquals(minOf(s1.itemsBasis, s2.itemsBasis), result.itemsBasis, "$where combine off")
                if (s1.discountTotal > 0) {
                    hit("combine off, automatic discount present")
                    if (s2.itemsBasis < s1.itemsBasis) hit("combine off, codes chosen") else hit("combine off, automatic discount chosen")
                    // never both: a code and an automatic discount are exclusive in the result
                    assertTrue(result.discountTotal == 0L || result.couponDiscount + result.creatorDiscount == 0L, "$where both taken")
                }
            }
            if (input.config.combineDiscountsAndCoupons && result.discountTotal > 0 && result.couponDiscount > 0) hit("combine on, both taken")

            // determinism, and independence of the order the discounts arrive in
            assertEquals(result, PricingEngine.priceItems(input), where)
            assertEquals(result, PricingEngine.priceItems(withDiscounts(input, input.discounts.shuffled(rnd))), where)
        }
        assertTrue(linesChecked >= 10_000, "only $linesChecked lines were checked")
        println("PROPERTY-LOOP-A3A4 lines=$linesChecked branches=$seen")
        for (branch in listOf(
            "coupon applied", "creator code applied", "coupon then creator code", "creator code attribution only: COUPON_NOT_APPLICABLE",
            "creator code refused: CODE_NOT_FOUND", "coupon refused: CODE_NOT_FOUND", "coupon refused: COUPON_NOT_APPLICABLE",
            "coupon refused: CODE_MIN_AMOUNT", "coupon refused: CODE_LIMIT_REACHED", "coupon refused: CODE_EXPIRED",
            "coupon refused: CODE_NOT_STARTED", "coupon refused: EXTERNAL_PRICING", "coupon refused: CODE_NOT_COMBINABLE",
            "creator code attribution only: CODE_NOT_COMBINABLE", "FIXED coupon allocated over several lines",
            "FIXED creator code allocated over several lines", "own code: payer only", "own code: recipient only", "combine off, codes chosen",
            "combine off, automatic discount chosen", "combine on, both taken", "VAT inclusive", "VAT exclusive", "EXTERNAL_TAX",
            "EXTERNAL", "per-product VAT rate", "VAT rate clamped", "physical line", "bundle shipped by a physical child",
            "hidden bundle with a physical child", "digital bundle", "removeCents", "foreign order currency"
        )) {
            assertTrue((seen[branch] ?: 0) >= 100, "the loop barely exercised '$branch': ${seen[branch]}")
        }
    }

    private fun withCodes(i: PricingInput, coupon: CouponInput?, creatorCode: CreatorCodeInput?) = PricingInput(
        i.config, i.now, i.profile, i.requestedCurrency, i.lines, i.buyer, i.discounts, coupon, creatorCode, i.pricingMode,
        i.payWithCredits, i.priceOverride
    )

    /** [randomInput] with a coupon and a creator code, per-line VAT rates, physical lines and a random combine rule. */
    private fun randomCodesInput(rnd: Random): PricingInput {
        val base = randomInput(rnd)
        val storefront = rnd.nextInt(100) < 85 // only the storefront takes codes; the A2 loop covers the other profiles
        val c = base.config
        val config = PricingConfig(
            baseCurrency = c.baseCurrency, currencyMode = c.currencyMode, additionalCurrencies = c.additionalCurrencies,
            multiCurrencyFallback = c.multiCurrencyFallback, rates = c.rates, vatBp = listOf(2000L, 0L, 1800L, 10_000L, 1L)[rnd.nextInt(5)],
            pricesIncludeVat = c.pricesIncludeVat, removeCents = c.removeCents, minimumOrderAmount = 0,
            combineDiscountsAndCoupons = rnd.nextBoolean(), creditsEnabled = true, onlyAcceptCredits = false,
            creditValue = c.creditValue, allowMixedCreditPayment = true, cashbackBp = 0
        )
        val rates = listOf<Long?>(null, null, null, 0L, 1000L, 2000L, 20_000L)
        val lines = base.lines.map { l ->
            LineInput(
                lineKey = l.lineKey, productId = l.productId, variantId = l.variantId, kind = l.kind, quantity = l.quantity,
                basePrice = l.basePrice, currencyPrices = l.currencyPrices, creditPrice = l.creditPrice,
                vatBp = rates[rnd.nextInt(rates.size)], categoryPath = l.categoryPath, physical = l.kind != LineKind.BUNDLE && rnd.nextInt(4) == 0,
                subscription = l.subscription, tier = l.tier, topUpCredits = l.topUpCredits, children = l.children
            )
        }
        val buyer = PricingFixtures.buyer(
            base.buyer.recipientTiers, userId = listOf(1L, 1L, 900L)[rnd.nextInt(3)], recipientUserId = listOf(1L, 1L, 900L, null)[rnd.nextInt(4)],
            email = listOf("buyer@example.com", null, "Buyer@Example.com ")[rnd.nextInt(3)]
        )
        return PricingInput(
            config = config, now = base.now, profile = if (storefront) PricingProfile.STOREFRONT else base.profile,
            requestedCurrency = base.requestedCurrency, lines = lines, buyer = buyer, discounts = base.discounts,
            coupon = if (storefront && rnd.nextInt(10) < 6) randomCoupon(rnd) else null,
            creatorCode = if (storefront && rnd.nextInt(10) < 5) randomCreatorCode(rnd) else null,
            pricingMode = base.pricingMode, payWithCredits = false, priceOverride = null
        )
    }

    private fun randomDiscountValue(rnd: Random, unit: DiscountUnit): Long = when {
        unit == DiscountUnit.PERCENT && rnd.nextInt(30) == 0 -> rnd.nextLong(10_001, 50_000) // clamped to 100 %
        unit == DiscountUnit.PERCENT -> rnd.nextLong(0, 10_001)
        rnd.nextInt(30) == 0 -> rnd.nextLong(1, Long.MAX_VALUE / 4) // clamped to the bound
        else -> rnd.nextLong(0, 300_000)
    }

    private fun randomCoupon(rnd: Random): CouponInput {
        val unit = if (rnd.nextBoolean()) DiscountUnit.PERCENT else DiscountUnit.FIXED
        return CouponInput(
            found = rnd.nextInt(20) != 0, id = 7, code = "RC", active = rnd.nextInt(20) != 0,
            discount = randomDiscountValue(rnd, unit), unit = unit,
            scope = if (rnd.nextBoolean()) CouponScope.ALL else CouponScope.SELECTED,
            productIds = (1..6).filter { rnd.nextInt(3) == 0 }.map { it.toLong() }.toSet(),
            categoryIds = (1..5).filter { rnd.nextInt(3) == 0 }.map { it.toLong() }.toSet(),
            minPaymentAmount = if (rnd.nextInt(3) == 0) rnd.nextLong(0, 1_500_000) else null,
            startDate = if (rnd.nextInt(14) == 0) NOW + rnd.nextLong(-2, 4) else null,
            expiryDate = if (rnd.nextInt(14) == 0) NOW + rnd.nextLong(-3, 3) else null,
            redeemLimit = if (rnd.nextInt(12) == 0) rnd.nextInt(1, 4) else null,
            customerRedeemLimit = if (rnd.nextInt(12) == 0) rnd.nextInt(1, 3) else null,
            usedCount = rnd.nextInt(0, 4), buyerUses = rnd.nextInt(0, 3)
        )
    }

    private fun randomCreatorCode(rnd: Random): CreatorCodeInput {
        val unit = if (rnd.nextBoolean()) DiscountUnit.PERCENT else DiscountUnit.FIXED
        return CreatorCodeInput(
            found = rnd.nextInt(20) != 0, id = 3, code = "RCR", active = rnd.nextInt(20) != 0,
            discount = if (rnd.nextInt(8) == 0) 0 else randomDiscountValue(rnd, unit), unit = unit, commissionBp = 1000,
            creatorUserId = listOf(900L, 901L, null)[rnd.nextInt(3)],
            startDate = if (rnd.nextInt(14) == 0) NOW + rnd.nextLong(-2, 4) else null,
            expiryDate = if (rnd.nextInt(14) == 0) NOW + rnd.nextLong(-3, 3) else null,
            redeemLimit = if (rnd.nextInt(12) == 0) rnd.nextInt(1, 4) else null, usedCount = rnd.nextInt(0, 4),
            creatorEmail = listOf(null, "creator@example.com", "buyer@example.com")[rnd.nextInt(3)]
        )
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
                children = if (kind == LineKind.BUNDLE) listOf(BundleChild(100L + i, 0, 1 + rnd.nextInt(5), rnd.nextInt(3) == 0), BundleChild(200L + i, 0, 1, rnd.nextInt(3) == 0)) else emptyList()
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
        // the panel's price override replaces the line figures that these loops prove exact (unitPrice x quantity): it has its own seeded loop in PricingProfileTest
        val override: Long? = null
        return PricingFixtures.input(
            *lines.toTypedArray(), config = cfg, discounts = discounts, profile = profile, currency = currency,
            buyer = PricingFixtures.buyer(owned), mode = pricingMode, override = override
        )
    }

    // ================================================================ stages B and C: shipping, tender, fee, totals (05 sections 8 to 10)

    private fun PriceBreakdown.key(key: String): PricedLine = lines.single { it.lineKey == key }

    private fun PriceBreakdown.codes(): List<PricingCode> = messages.map { it.code }

    private val creditsOnlyProduct = Product(40, "Credits only", 0, 4000, listOf(2))

    @Test
    fun `row 37 a variant price with an inclusive shipping charge`() {
        val r = full(line(P4, variantId = 2, basePrice = 27500), shipping = 2990)
        assertEquals(27500L, r.key("L4v2").lineTotal)
        assertEquals(2990L, r.shippingTotal)
        assertEquals(498L, r.shippingVat) // 29.90 x 20 / 120 = 4.983
        assertEquals(2000L, r.shippingVatPercent)
        assertEquals(30490L, r.total)
        assertEquals(5081L, r.vatTotal) // 45.83 + 4.98
        assertEquals(30490L, r.gatewayAmount)
        assertTrue(r.items.requiresShipping)
    }

    @Test
    fun `row 38 a variant that inherits the price with an exclusive shipping charge`() {
        val r = full(line(P4, variantId = 1), shipping = 2990, config = config(includeVat = false))
        assertEquals(30000L, r.key("L4v1").lineTotal)
        assertEquals(3588L, r.shippingTotal) // 29.90 + 5.98
        assertEquals(598L, r.shippingVat)
        assertEquals(33588L, r.total)
        assertEquals(5598L, r.vatTotal)
        assertEquals(25000L, r.subtotal)
    }

    @Test
    fun `row 39 free shipping is a charge of zero and adds nothing`() {
        val r = full(line(P4, variantId = 2, basePrice = 27500), shipping = 0)
        assertEquals(0L, r.shippingTotal)
        assertEquals(0L, r.shippingVat)
        assertEquals(27500L, r.total)
    }

    @Test
    fun `shipping uses its own VAT rate and is priced only for a cart with a physical line`() {
        val r = full(line(P4, variantId = 2, basePrice = 27500), shipping = 1100, shippingVatBp = 1000)
        assertEquals(1000L, r.shippingVatPercent)
        assertEquals(100L, r.shippingVat) // 11.00 x 10 / 110
        // a digital cart takes no shipping whatever the caller passes
        val digital = full(line(P1), shipping = 5000)
        assertEquals(0L, digital.shippingTotal)
        assertEquals(10000L, digital.total)
        // a physical cart without a charge cannot be checked out and says nothing else
        val missing = full(line(P4, variantId = 2, basePrice = 27500))
        assertTrue(missing.shippingMissing)
        assertFalse(missing.canCheckout)
        assertEquals(27500L, missing.total)
    }

    @Test
    fun `the shipping price is rounded to the quantum and bounded`() {
        val whole = full(line(P4, variantId = 2, basePrice = 27500), shipping = 2950, config = config(removeCents = true))
        assertEquals(3000L, whole.shippingTotal) // 29.50 rounds half up to 30
        assertEquals(PricingError.INVALID_INPUT, refused { full(line(P4, variantId = 2), shipping = -1) }.error)
        assertEquals(PricingError.INVALID_INPUT, refused { full(line(P4, variantId = 2), shipping = PricingLimits.MAX_AMOUNT + 1) }.error)
    }

    @Test
    fun `row 43 the gateway fee is a percentage plus a fixed part of what the gateway collects`() {
        val r = full(line(P1), method = PricingFixtures.METHOD_F)
        assertEquals(320L, r.paymentFee)
        assertEquals(10320L, r.total)
        assertEquals(10320L, r.gatewayAmount)
        assertEquals(53L, r.tender.paymentFeeVatAmount) // 3.20 x 20 / 120 = 0.533
        assertEquals(1720L, r.vatTotal) // 16.67 + 0.53
        assertEquals("F", r.paymentMethodId)
        assertEquals(10000L, r.tender.preFee)
    }

    @Test
    fun `row 44 the fee rounds half up`() {
        val r = full(line(P2, 3), method = PricingFixtures.METHOD_G)
        assertEquals(105L, r.paymentFee) // 1.04895
        assertEquals(3102L, r.total)
        assertEquals(518L, r.vatTotal) // 5.00 + 0.18
    }

    @Test
    fun `row 45 the fee is taken only on the part the gateway collects`() {
        val r = full(line(P1), method = PricingFixtures.METHOD_F, buyer = PricingFixtures.buyer(balance = 3000), useCredits = MixedPayment.MAX)
        assertEquals(3000L, r.creditAmount)
        assertEquals(3000L, r.creditValue)
        assertEquals(233L, r.paymentFee) // 2.9 % of 70.00 + 0.30
        assertEquals(10233L, r.total)
        assertEquals(7233L, r.gatewayAmount)
        assertEquals(3000L, r.credits!!.applied)
    }

    @Test
    fun `row 46 a mixed payment converts credits at the credit value`() {
        val r = full(
            line(P1), config = config(creditValue = 10), buyer = PricingFixtures.buyer(balance = 25000), useCredits = 10000,
            method = PricingFixtures.method("plain")
        )
        assertEquals(10000L, r.creditAmount)
        assertEquals(1000L, r.creditValue) // 100 credits x 0.10
        assertEquals(9000L, r.gatewayAmount)
        assertEquals(25000L, r.credits!!.maxApplicable)
    }

    @Test
    fun `row 47 a mixed payment never covers everything and leaves what the gateway needs`() {
        val rich = PricingFixtures.buyer(balance = 50000)
        val r = full(line(P1), method = PricingFixtures.METHOD_F, buyer = rich, useCredits = MixedPayment.MAX)
        assertEquals(9999L, r.credits!!.maxApplicable)
        assertEquals(9999L, r.creditAmount)
        assertEquals(9999L, r.creditValue)
        assertEquals(30L, r.paymentFee) // 2.9 % of 0.01 rounds to 0, plus the fixed 0.30
        assertEquals(10030L, r.total)
        assertEquals(31L, r.gatewayAmount)
        assertEquals("F", r.paymentMethodId) // full coverage needs payWithCredits
        // the provider's own minimum is kept for the gateway
        val withMinimum = PricingFixtures.method("F", 290, 30, providerMin = Money(500, "TRY"))
        val m = full(line(P1), method = withMinimum, buyer = rich, useCredits = MixedPayment.MAX)
        assertEquals(9500L, m.creditValue)
        assertTrue(m.gatewayAmount >= 500L)
    }

    @Test
    fun `row 47b a number above the maximum is clamped by the quote and refused at checkout`() {
        val rich = PricingFixtures.buyer(balance = 50000)
        val quote = full(line(P1), method = PricingFixtures.METHOD_F, buyer = rich, useCredits = 15000)
        assertEquals(9999L, quote.creditAmount)
        assertTrue(PricingCode.CREDITS_REDUCED in quote.codes())
        assertTrue(quote.canCheckout) // a warning only
        val checkout = full(line(P1), method = PricingFixtures.METHOD_F, buyer = rich, useCredits = 15000, strict = true)
        assertEquals(0L, checkout.creditAmount)
        assertEquals(0L, checkout.creditValue)
        assertTrue(PricingCode.INSUFFICIENT_CREDITS in checkout.codes())
        assertFalse(checkout.canCheckout)
        assertEquals(9999L, checkout.credits!!.maxApplicable) // the caller answers {balance, maxApplicable}
        assertEquals(50000L, checkout.credits!!.balance)
        // the exact maximum is accepted at checkout, MAX is not a checkout value
        assertEquals(9999L, full(line(P1), method = PricingFixtures.METHOD_F, buyer = rich, useCredits = 9999, strict = true).creditAmount)
        assertEquals(PricingError.INVALID_INPUT, refused { full(line(P1), buyer = rich, useCredits = MixedPayment.MAX, strict = true) }.error)
    }

    @Test
    fun `a provider minimum between two quanta is rounded up so the gateway keeps at least that much in whole units`() {
        // removeCents: the quantum is 1.00; the provider wants 5.50, so the gateway keeps 6.00 and the credits cover 94.00
        val cfg = config(removeCents = true)
        val rich = PricingFixtures.buyer(balance = 50_000)
        val odd = PricingFixtures.method("odd", providerMin = Money(550, "TRY"))
        val r = full(line(P1), config = cfg, method = odd, buyer = rich, useCredits = MixedPayment.MAX)
        assertEquals(9400L, r.credits!!.maxApplicable)
        assertEquals(9400L, r.creditValue)
        assertEquals(600L, r.gatewayAmount)
        assertEquals(0L, r.gatewayAmount % 100)
        assertNull(r.tender.unavailable)
        // 5.00 is a whole quantum and stays 5.00
        val whole = full(line(P1), config = cfg, method = PricingFixtures.method("whole", providerMin = Money(500, "TRY")), buyer = rich, useCredits = MixedPayment.MAX)
        assertEquals(500L, whole.gatewayAmount)
    }

    @Test
    fun `row 48 mixed payment that is switched off is ignored on the quote and refused at checkout`() {
        val buyer = PricingFixtures.buyer(balance = 5000)
        val quote = full(line(P1), config = config(mixed = false), buyer = buyer, useCredits = 5000)
        assertEquals(0L, quote.creditAmount)
        assertEquals(10000L, quote.gatewayAmount)
        assertTrue(PricingCode.MIXED_CREDIT_NOT_SUPPORTED in quote.codes())
        assertEquals(MessageLevel.WARNING, quote.messages.single { it.code == PricingCode.MIXED_CREDIT_NOT_SUPPORTED }.level)
        assertEquals(0L, quote.credits!!.maxApplicable)
        val checkout = full(line(P1), config = config(mixed = false), buyer = buyer, useCredits = 5000, strict = true)
        assertEquals(PricingCode.MIXED_CREDIT_NOT_SUPPORTED, checkout.tender.unavailable)
        assertFalse(checkout.canCheckout)
    }

    @Test
    fun `mixed payment applies only where every rule of 07 section 6 2 holds`() {
        val funded = PricingFixtures.buyer(balance = 5000)
        fun mixed(
            vararg lines: LineInput, config: PricingConfig = config(), buyer: BuyerContext = funded, profile: PricingProfile = PricingProfile.STOREFRONT,
            method: MethodInput? = PricingFixtures.method("plain"), mode: PricingMode = PricingMode.MARKET
        ) = full(*lines, config = config, buyer = buyer, profile = profile, useCredits = 1000, method = method, mode = mode)
        assertEquals(1000L, mixed(line(P1)).creditAmount)
        // M1: credits off, mixed off, onlyAcceptCredits
        val off = config().let { c ->
            PricingConfig(c.baseCurrency, c.currencyMode, c.additionalCurrencies, c.multiCurrencyFallback, c.rates, c.vatBp, c.pricesIncludeVat,
                c.removeCents, c.minimumOrderAmount, c.combineDiscountsAndCoupons, false, false, c.creditValue, true, c.cashbackBp)
        }
        assertEquals(0L, mixed(line(P1), config = off).creditAmount)
        assertNull(mixed(line(P1), config = off).credits) // Quote.credits is null while credits are off
        // M2: a guest
        assertEquals(0L, mixed(line(P1), buyer = PricingFixtures.buyer(loggedIn = false, userId = null, balance = 0)).creditAmount)
        // M3: the gateway sets the price
        assertEquals(0L, mixed(line(P1), method = PricingFixtures.METHOD_ADDS_TAX, mode = PricingMode.EXTERNAL_TAX).creditAmount)
        // M4: a credit purchase, a subscription, a profile other than the storefront
        assertEquals(0L, mixed(line(P6)).creditAmount)
        assertEquals(0L, mixed(topUp(10000)).creditAmount)
        assertEquals(0L, mixed(line(P9)).creditAmount)
        assertEquals(0L, mixed(line(P1), profile = PricingProfile.PANEL).creditAmount)
        // M5: the selected method cannot be paid in part with credits
        assertEquals(0L, mixed(line(P1), method = PricingFixtures.method("nomix", mixedCredit = false)).creditAmount)
        // no method chosen yet: assumed capable
        assertEquals(1000L, mixed(line(P1), method = null).creditAmount)
        // M6: nothing to spend is clamped, not "unsupported"
        val broke = mixed(line(P1), buyer = PricingFixtures.buyer(balance = 0))
        assertEquals(0L, broke.creditAmount)
        assertTrue(PricingCode.CREDITS_REDUCED in broke.codes())
    }

    @Test
    fun `a mixed payment that cannot be rounded to any value spends nothing and a zero request spends nothing`() {
        val tiny = config(creditValue = 1) // 0.0001 per credit: 0.01 credit is worth nothing
        val r = full(line(P1), config = tiny, buyer = PricingFixtures.buyer(balance = 1), useCredits = 1, method = PricingFixtures.method("plain"))
        assertEquals(0L, r.creditAmount)
        assertEquals(0L, r.creditValue)
        val zero = full(line(P1), buyer = PricingFixtures.buyer(balance = 5000), useCredits = 0)
        assertEquals(0L, zero.creditAmount)
        assertEquals(5000L, zero.credits!!.balance)
        assertEquals(5000L, zero.credits!!.maxApplicable)
        assertTrue(refused { full(line(P1), buyer = PricingFixtures.buyer(balance = 5000), useCredits = -1) }.error == PricingError.INVALID_INPUT)
    }

    @Test
    fun `mixed payment in a zero decimal currency leaves whole units for the gateway`() {
        val cfg = config(mode = CurrencyMode.MULTI, creditValue = 100)
        val r = full(
            line(P1), config = cfg, currency = "JPY", buyer = PricingFixtures.buyer(balance = 100_000_000),
            useCredits = MixedPayment.MAX, method = PricingFixtures.method("plain")
        )
        assertEquals("JPY", r.currency)
        assertEquals(0L, r.creditValue % 100)
        assertEquals(0L, r.gatewayAmount % 100)
        assertTrue(r.gatewayAmount >= 100L)
        assertEquals(r.total, r.gatewayAmount + r.creditValue)
    }

    @Test
    fun `row 49 paying the whole order in credits charges the credit price and records the money value`() {
        val r = full(line(P1), line(P2, 3), payWithCredits = true, buyer = PricingFixtures.buyer(balance = 20000))
        assertEquals(13000L, r.credits!!.creditTotal) // 100.00 + 3 x 10.00
        assertEquals(13000L, r.creditAmount)
        assertEquals(12997L, r.total) // the money run: 100.00 + 3 x 9.99
        assertEquals(12997L, r.creditValue)
        assertEquals(0L, r.gatewayAmount)
        assertEquals(0L, r.paymentFee)
        assertEquals("credits", r.paymentMethodId)
        assertEquals(13000L, r.credits!!.applied)
        assertEquals(12997L, r.credits!!.appliedValue)
        assertEquals(10000L, r.key("L1").creditUnitPrice)
        assertTrue(r.canCheckout)
        // the credits method id is equivalent
        val byMethod = PricingEngine.finalize(
            price(line(P1), line(P2, 3), payWithCredits = true, buyer = PricingFixtures.buyer(balance = 20000)),
            null, TenderInput(null, PricingFixtures.method("credits"))
        )
        assertEquals(13000L, byMethod.creditAmount)
    }

    @Test
    fun `row 50 the credit run takes its own discounts`() {
        val r = full(line(P1), line(P2, 3), payWithCredits = true, discounts = listOf(PricingFixtures.D1), buyer = PricingFixtures.buyer(balance = 20000))
        assertEquals(9000L, r.items.credit!!.lines.single { it.lineKey == "L1" }.lineTotal)
        assertEquals(2700L, r.items.credit!!.lines.single { it.lineKey == "L2" }.lineTotal) // 3 x 9.00
        assertEquals(11700L, r.creditAmount) // 90.00 + 27.00
        assertEquals(11697L, r.total) // the money run: 90.00 + 3 x 8.99
        assertEquals(900L, r.key("L2").creditUnitPrice)
    }

    @Test
    fun `row 51 a balance below the credit total says so and charges nothing`() {
        val r = full(line(P1), line(P2, 3), payWithCredits = true, buyer = PricingFixtures.buyer(balance = 10000))
        assertTrue(PricingCode.INSUFFICIENT_CREDITS in r.codes())
        assertFalse(r.canCheckout)
        assertEquals(0L, r.creditAmount)
        assertEquals(13000L, r.credits!!.creditTotal) // what it would cost
        assertEquals("credits", r.paymentMethodId)
    }

    @Test
    fun `row 52 a line without a credit price cannot be paid in credits`() {
        val r = full(line(P3), payWithCredits = true, buyer = PricingFixtures.buyer(balance = 99999))
        assertEquals(listOf(PricingCode.NOT_PAYABLE_WITH_CREDITS), r.key("L3").errors)
        assertFalse(r.credits!!.payableInCredits)
        assertFalse(r.canCheckout)
        assertEquals(PricingCode.NOT_PAYABLE_WITH_CREDITS, r.tender.unavailable)
        assertEquals(0L, r.creditAmount)
        // without asking for credits the same cart is a normal money cart and is not payable-in-credits only as information
        val money = full(line(P3))
        assertTrue(money.canCheckout)
        assertFalse(money.credits!!.payableInCredits)
    }

    @Test
    fun `row 53 a product priced in credits only is never free in a money quote`() {
        val money = full(line(creditsOnlyProduct))
        assertEquals(listOf(PricingCode.CREDITS_ONLY), money.key("L40").errors)
        assertFalse(money.canCheckout)
        val credits = full(line(creditsOnlyProduct), payWithCredits = true, buyer = PricingFixtures.buyer(balance = 5000))
        assertEquals(4000L, credits.credits!!.creditTotal)
        assertEquals(0L, credits.total)
        assertEquals(0L, credits.creditValue)
        assertEquals(4000L, credits.creditAmount)
        assertEquals("credits", credits.paymentMethodId)
        assertTrue(credits.canCheckout)
        // a free product (no credit price either) stays free
        val free = full(line(Product(41, "Free", 0, 0, listOf(2))))
        assertTrue(free.canCheckout)
        assertEquals("free", free.paymentMethodId)
    }

    @Test
    fun `row 54 a coupon takes the product and leaves the credit pack, a pack alone is not applicable`() {
        val r = full(line(P1), line(P6), coupon = K25)
        assertEquals(2500L, r.couponDiscount)
        assertEquals(17500L, r.total)
        val alone = full(line(P6), coupon = K25)
        assertEquals(PricingCode.COUPON_NOT_APPLICABLE, alone.items.coupon!!.reason)
        assertEquals(10000L, alone.total)
        assertFalse(alone.canCheckout)
    }

    @Test
    fun `row 55 a credit pack cannot be bought with credits, whole or in part`() {
        val buyer = PricingFixtures.buyer(balance = 99999)
        val mixed = full(line(P6), buyer = buyer, useCredits = MixedPayment.MAX)
        assertEquals(0L, mixed.creditAmount)
        assertTrue(PricingCode.MIXED_CREDIT_NOT_SUPPORTED in mixed.codes())
        val whole = full(line(P6), buyer = buyer, payWithCredits = true)
        assertEquals(listOf(PricingCode.NOT_PAYABLE_WITH_CREDITS), whole.key("L6").errors)
        assertFalse(whole.credits!!.payableInCredits)
        // the pack next to a product spoils the credit payment of the product too: the cart is not payable in credits
        val both = full(line(P1), line(P6), buyer = buyer, payWithCredits = true)
        assertEquals(emptyList<PricingCode>(), both.key("L1").errors)
        assertEquals(listOf(PricingCode.NOT_PAYABLE_WITH_CREDITS), both.key("L6").errors)
        assertFalse(both.canCheckout)
        // a top-up line is a credit purchase as well
        assertEquals(listOf(PricingCode.NOT_PAYABLE_WITH_CREDITS), full(topUp(10000), buyer = buyer, payWithCredits = true).key("topup").errors)
    }

    @Test
    fun `row 56 the minimum order amount looks at the merchandise after discounts and only where a gateway is paid`() {
        val cfg = config(minimumOrder = 5000)
        val below = full(line(P2, 3), config = cfg)
        assertTrue(PricingCode.MINIMUM_ORDER_AMOUNT_NOT_REACHED in below.codes())
        assertFalse(below.canCheckout)
        assertTrue(PricingCode.MINIMUM_ORDER_AMOUNT_NOT_REACHED in full(line(P3), config = cfg).codes()) // 49.90
        assertFalse(PricingCode.MINIMUM_ORDER_AMOUNT_NOT_REACHED in full(line(P1), coupon = K25, config = cfg).codes()) // 75.00 after the coupon
        assertFalse(PricingCode.MINIMUM_ORDER_AMOUNT_NOT_REACHED in full(line(P1), coupon = KF500, config = cfg).codes()) // free
        // 49.90 + a fee is still below: the fee and the shipping do not count as merchandise
        assertTrue(PricingCode.MINIMUM_ORDER_AMOUNT_NOT_REACHED in full(line(P3), config = cfg, method = PricingFixtures.METHOD_F).codes())
        // a full-credit order, the panel and a free-amount top-up are exempt
        assertFalse(PricingCode.MINIMUM_ORDER_AMOUNT_NOT_REACHED in
            full(line(P2, 3), config = cfg, payWithCredits = true, buyer = PricingFixtures.buyer(balance = 99999)).codes())
        assertFalse(PricingCode.MINIMUM_ORDER_AMOUNT_NOT_REACHED in full(line(P2, 3), config = cfg, profile = PricingProfile.PANEL).codes())
        assertFalse(PricingCode.MINIMUM_ORDER_AMOUNT_NOT_REACHED in full(topUp(1000), config = config(minimumOrder = 5000, creditValue = 100)).codes())
        // exactly the minimum passes
        assertFalse(PricingCode.MINIMUM_ORDER_AMOUNT_NOT_REACHED in full(line(P1), config = config(minimumOrder = 10000)).codes())
        assertTrue(PricingCode.MINIMUM_ORDER_AMOUNT_NOT_REACHED in full(line(P1), config = config(minimumOrder = 10001)).codes())
    }

    @Test
    fun `row 57 the admin window and the provider limits decide whether a method is available`() {
        val cheap = PricingFixtures.price(line(P8))
        val tender = TenderInput(null, null)
        fun reason(items: ItemsResult, m: MethodInput): PricingCode? =
            PricingEngine.evaluateMethods(items, null, tender, listOf(m)).single().unavailableReason
        assertEquals(PricingCode.AMOUNT_BELOW_MINIMUM, reason(cheap, PricingFixtures.method("a", minAmount = 1000)))
        assertEquals(PricingCode.AMOUNT_ABOVE_MAXIMUM, reason(PricingFixtures.price(line(P1)), PricingFixtures.method("b", maxAmount = 5000)))
        assertNull(reason(PricingFixtures.price(line(P1)), PricingFixtures.method("c", minAmount = 10000, maxAmount = 10000)))
        // the window is the cart total before the fee and before the credits
        val rich = PricingFixtures.price(line(P1), buyer = PricingFixtures.buyer(balance = 9000))
        val credits = TenderInput(9000, null)
        val windowed = PricingFixtures.method("d", feePercent = 290, minAmount = 10000)
        assertNull(PricingEngine.evaluateMethods(rich, null, credits, listOf(windowed)).single().unavailableReason)
    }

    @Test
    fun `the provider limits look at the gateway amount including the fee, in the order currency`() {
        val items = PricingFixtures.price(line(P1))
        val tender = TenderInput(null, null)
        fun eval(m: MethodInput) = PricingEngine.evaluateMethods(items, null, tender, listOf(m)).single()
        // 100.00 + 3.20 fee: a ceiling of 103.00 is exceeded only because of the fee
        val withFee = PricingFixtures.method("F", 290, 30, providerMax = Money(10300, "TRY"))
        assertEquals(PricingCode.AMOUNT_ABOVE_MAXIMUM, eval(withFee).unavailableReason)
        assertEquals(10320L, eval(withFee).gatewayAmount)
        assertEquals(320L, eval(withFee).feeAmount)
        assertNull(eval(PricingFixtures.method("F", 290, 30, providerMax = Money(10320, "TRY"))).unavailableReason)
        assertEquals(PricingCode.AMOUNT_BELOW_MINIMUM, eval(PricingFixtures.method("m", providerMin = Money(10001, "TRY"))).unavailableReason)
        // a limit in a third currency goes through the base: 2.00 USD is 80.00 TRY at 0.025
        assertNull(eval(PricingFixtures.method("usd", providerMin = Money(200, "USD"))).unavailableReason)
        assertEquals(PricingCode.AMOUNT_BELOW_MINIMUM, eval(PricingFixtures.method("usd", providerMin = Money(300, "USD"))).unavailableReason) // 120.00 TRY
        // a limit in a currency without a rate is skipped, not guessed
        assertNull(eval(PricingFixtures.method("gbp", providerMin = Money(99999, "GBP"))).unavailableReason)
        // a limit in the order currency of a foreign order is direct
        val usdItems = PricingFixtures.price(line(P1), config = config(mode = CurrencyMode.MULTI), currency = "USD")
        assertEquals(PricingCode.AMOUNT_ABOVE_MAXIMUM,
            PricingEngine.evaluateMethods(usdItems, null, tender, listOf(PricingFixtures.method("u", providerMax = Money(298, "USD")))).single().unavailableReason)
    }

    @Test
    fun `the method a tender names must be able to take the amount, the goods and the currency`() {
        // the admin window is the cart total before the fee
        val low = full(line(P8), method = PricingFixtures.method("a", minAmount = 1000))
        assertEquals(PricingCode.AMOUNT_BELOW_MINIMUM, low.tender.unavailable)
        assertFalse(low.canCheckout)
        assertEquals(PricingCode.AMOUNT_ABOVE_MAXIMUM, full(line(P1), method = PricingFixtures.method("b", maxAmount = 5000)).tender.unavailable)
        // the provider's ceiling counts the fee: 100.00 + 3.20
        assertEquals(PricingCode.AMOUNT_ABOVE_MAXIMUM, full(line(P1), method = PricingFixtures.method("F", 290, 30, providerMax = Money(10300, "TRY"))).tender.unavailable)
        val fits = full(line(P1), method = PricingFixtures.method("F", 290, 30, providerMax = Money(10320, "TRY")))
        assertNull(fits.tender.unavailable)
        assertTrue(fits.canCheckout)
        // goods that ship need a method that ships them
        val tshirt = line(P4, variantId = 2, basePrice = 27500)
        assertEquals(PricingCode.PHYSICAL_NOT_SUPPORTED, full(tshirt, shipping = 0, method = PricingFixtures.method("digital")).tender.unavailable)
        assertTrue(full(tshirt, shipping = 0, method = PricingFixtures.method("post", physicalGoods = true)).canCheckout)
        // a currency the method does not know
        assertEquals(PricingCode.CURRENCY_NOT_SUPPORTED, full(line(P1), method = PricingFixtures.method("eur", providerCurrencies = setOf("EUR"))).tender.unavailable)
        assertEquals(PricingCode.CURRENCY_NOT_SUPPORTED, full(line(P1), method = PricingFixtures.method("eur", adminCurrencies = setOf("EUR"))).tender.unavailable)
        // a mixed order is checked on what the gateway is left with: 0.31 against a minimum of 5.00
        val rich = PricingFixtures.buyer(balance = 50000)
        val big = PricingFixtures.method("big", providerMin = Money(500, "TRY"))
        assertEquals(500L, full(line(P1), method = big, buyer = rich, useCredits = MixedPayment.MAX).gatewayAmount) // the minimum keeps its share out of the credits
        // a free order and a full-credit order ignore the method's limits
        val free = full(line(P1), coupon = KF500, method = PricingFixtures.method("min", providerMin = Money(99_999, "TRY")))
        assertNull(free.tender.unavailable)
        assertEquals("free", free.paymentMethodId)
        assertNull(full(line(P1), payWithCredits = true, buyer = rich, method = PricingFixtures.method("credits")).tender.unavailable)
        // the credit rules of the tender come before the limits
        val noMix = full(
            line(P1), config = config(mixed = false), buyer = rich, useCredits = 5000, strict = true,
            method = PricingFixtures.method("a", minAmount = 99_999_999)
        )
        assertEquals(PricingCode.MIXED_CREDIT_NOT_SUPPORTED, noMix.tender.unavailable)
        // no method chosen: nothing to refuse
        assertNull(full(line(P8)).tender.unavailable)
    }

    @Test
    fun `the checks of a method run in the order of 05 section 9 5`() {
        val items = PricingFixtures.price(line(P4, variantId = 2, basePrice = 27500))
        val tender = TenderInput(5000, null)
        val all = PricingFixtures.method(
            "x", mixedCredit = false, minAmount = 99_999_999, adminCurrencies = setOf("EUR"), providerCurrencies = setOf("TRY"), physicalGoods = false
        )
        fun reason(m: MethodInput) = PricingEngine.evaluateMethods(items, ShippingCharge(0, null), tender, listOf(m)).single().unavailableReason
        assertEquals(PricingCode.CURRENCY_NOT_SUPPORTED, reason(all))
        val noCurrency = PricingFixtures.method("x", mixedCredit = false, minAmount = 99_999_999, physicalGoods = false)
        assertEquals(PricingCode.PHYSICAL_NOT_SUPPORTED, reason(noCurrency))
        val physical = PricingFixtures.method("x", mixedCredit = false, minAmount = 99_999_999, physicalGoods = true)
        assertEquals(PricingCode.MIXED_CREDIT_NOT_SUPPORTED, reason(physical))
        val mixedOk = PricingFixtures.method("x", minAmount = 99_999_999, physicalGoods = true)
        assertEquals(PricingCode.AMOUNT_BELOW_MINIMUM, reason(mixedOk))
        // a method that prices differently never ships goods, even when it says it can
        val catalog = PricingFixtures.method("x", authority = PriceAuthority.GATEWAY_CATALOG, physicalGoods = true)
        assertEquals(PricingCode.PHYSICAL_NOT_SUPPORTED, reason(catalog))
        assertEquals(PricingMode.EXTERNAL, PricingEngine.evaluateMethods(items, null, tender, listOf(catalog)).single().pricing)
    }

    @Test
    fun `a method that prices differently is listed with no fee and no credits`() {
        val items = PricingFixtures.price(line(P1), buyer = PricingFixtures.buyer(balance = 5000))
        val feeCatalog = PricingFixtures.method("cat", 290, 30, authority = PriceAuthority.GATEWAY_CATALOG, mixedCredit = true)
        val e = PricingEngine.evaluateMethods(items, null, TenderInput(1000, null), listOf(feeCatalog)).single()
        assertEquals(0L, e.feeAmount)
        assertEquals(10000L, e.gatewayAmount)
    }

    @Test
    fun `with onlyAcceptCredits a product cart has no method to list and a credit purchase keeps its methods`() {
        val cfg = config(onlyCredits = true)
        val products = PricingFixtures.price(line(P1), config = cfg)
        assertTrue(PricingEngine.evaluateMethods(products, null, TenderInput(null, null), listOf(PricingFixtures.METHOD_F)).isEmpty())
        val pack = PricingFixtures.price(line(P6), config = cfg)
        assertEquals(1, PricingEngine.evaluateMethods(pack, null, TenderInput(null, null), listOf(PricingFixtures.METHOD_F)).size)
    }

    @Test
    fun `onlyAcceptCredits turns a product cart into a full-credit quote and keeps credit purchases on the gateway`() {
        val cfg = config(onlyCredits = true)
        val buyer = PricingFixtures.buyer(balance = 20000)
        val products = full(line(P1), config = cfg, buyer = buyer)
        assertTrue(products.items.payWithCredits)
        assertEquals(10000L, products.creditAmount)
        assertEquals("credits", products.paymentMethodId)
        // credit purchases are unaffected by the mode
        val pack = full(line(P6), config = cfg, buyer = buyer, method = PricingFixtures.METHOD_F)
        assertFalse(pack.items.payWithCredits)
        assertEquals(10000L, pack.total - pack.paymentFee)
        // the credits of such a store are the whole order, not a part: a request for a few is not a mixed payment
        val partial = full(line(P1), config = cfg, buyer = buyer, useCredits = 1000)
        assertEquals(10000L, partial.creditAmount)
        // a gateway chosen for a product cart is refused and the quote stays the full-credit one
        val gateway = full(line(P1), config = cfg, buyer = buyer, method = PricingFixtures.METHOD_F)
        assertEquals(PricingCode.CREDITS_REQUIRED, gateway.tender.unavailable)
        assertEquals(10000L, gateway.creditAmount)
        assertEquals(0L, gateway.paymentFee)
        assertFalse(gateway.canCheckout)
        // a pack and a product in one cart: the product has no money price
        val mixedCart = full(line(P1), line(P6), config = cfg, buyer = buyer)
        assertEquals(listOf(PricingCode.CREDITS_ONLY), mixedCart.key("L1").errors)
        assertFalse(mixedCart.canCheckout)
        // a free product is free as always
        assertEquals("free", full(line(Product(41, "Free", 0, 0, listOf(2))), config = cfg, buyer = buyer).paymentMethodId)
    }

    @Test
    fun `row 59 DISPLAY mode converts each shown figure on its own and charges the base total`() {
        val cfg = config(mode = CurrencyMode.DISPLAY)
        val one = full(line(P1), config = cfg, currency = "USD")
        assertEquals(10000L, one.total)
        assertEquals(DisplayBlock("USD", BigDecimal("0.025"), 250L, 250L, 250L), one.display)
        val three = full(line(P2, 3), config = cfg, currency = "USD")
        assertEquals(75L, three.display!!.total) // 29.97 x 0.025 = 0.74925
        assertEquals(2997L, three.total)
        assertNull(full(line(P1), config = cfg).display)
    }

    @Test
    fun `row 68 an external catalogue price carries no fee and is an estimate`() {
        val catalog = PricingFixtures.method("cat", 290, 30, authority = PriceAuthority.GATEWAY_CATALOG, mixedCredit = false)
        val r = full(line(P1), discounts = listOf(PricingFixtures.D1), coupon = K25, method = catalog)
        assertEquals(0L, r.discountTotal)
        assertEquals(PricingCode.EXTERNAL_PRICING, r.items.coupon!!.reason)
        assertEquals(10000L, r.total) // an estimate
        assertEquals(0L, r.paymentFee)
        assertTrue(r.messages.contains(PricingMessage(PricingCode.EXTERNAL_PRICING, MessageLevel.INFO)))
        assertEquals("cat", r.paymentMethodId)
    }

    @Test
    fun `row 67 when the gateway adds the tax the order carries no VAT and no fee`() {
        val r = full(line(P1), method = PricingFixtures.METHOD_ADDS_TAX)
        assertEquals(8333L, r.total)
        assertEquals(0L, r.vatTotal)
        assertEquals(0L, r.paymentFee)
        assertEquals(0L, r.key("L1").vatPercent)
    }

    @Test
    fun `rows 8 16 and 34 an order worth nothing goes to the free method`() {
        assertEquals("free", full(line(P2, 2), discounts = listOf(PricingFixtures.D2)).paymentMethodId)
        assertEquals("free", full(line(P1), coupon = KF500).paymentMethodId)
        val upgrade = full(line(T3), discounts = listOf(PricingFixtures.D6), buyer = PricingFixtures.buyer(listOf(owned(502, T2, 15000))))
        assertEquals(0L, upgrade.total)
        assertEquals("free", upgrade.paymentMethodId)
        // the client's method is ignored for a free order, there is no gateway amount and no fee
        val ignored = full(line(P1), coupon = KF500, method = PricingFixtures.METHOD_F)
        assertEquals("free", ignored.paymentMethodId)
        assertEquals(0L, ignored.paymentFee)
        assertEquals(0L, ignored.gatewayAmount)
        assertTrue(ignored.canCheckout)
    }

    @Test
    fun `no method chosen leaves the method open while a gateway amount is due`() {
        val r = full(line(P1))
        assertNull(r.paymentMethodId)
        assertEquals(10000L, r.gatewayAmount)
        assertEquals(0L, r.paymentFee)
    }

    @Test
    fun `no fee for the credits free and manual providers, for a fee mode of none, or a zero remainder`() {
        for (id in listOf("credits", "free", "manual")) {
            val m = PricingFixtures.method(id, 290, 30)
            val r = if (id == "credits") {
                full(line(P1), payWithCredits = true, method = m, buyer = PricingFixtures.buyer(balance = 20000))
            } else {
                full(line(P1), method = m)
            }
            assertEquals(0L, r.paymentFee, id)
        }
        assertEquals(0L, full(line(P1), method = PricingFixtures.method("none", 290, 30, feeMode = PaymentFeeMode.NONE)).paymentFee)
        assertEquals(0L, full(line(P1), coupon = KF500, method = PricingFixtures.METHOD_F).paymentFee)
    }

    @Test
    fun `a fee of 100 percent or more and a stored fee outside its range never fail a quote`() {
        val huge = PricingFixtures.method("h", 50_000, Long.MAX_VALUE / 8)
        val r = full(line(P1), method = huge)
        assertEquals(10000L + PricingLimits.MAX_AMOUNT, r.paymentFee) // 100 % clamped, fixed part clamped to the bound
        assertEquals(r.total, r.gatewayAmount)
        assertEquals(0L, full(line(P1), method = PricingFixtures.method("n", -5, -5, feeMode = PaymentFeeMode.BUYER)).paymentFee)
    }

    @Test
    fun `the fee is quantised in a zero decimal currency and with removeCents`() {
        val cfg = config(mode = CurrencyMode.MULTI)
        val jpy = full(line(P1), config = cfg, currency = "JPY", method = PricingFixtures.METHOD_F)
        assertEquals(0L, jpy.paymentFee % 100)
        assertEquals(0L, jpy.total % 100)
        val whole = full(line(P2, 3), config = config(removeCents = true), method = PricingFixtures.METHOD_G)
        assertEquals(0L, whole.paymentFee % 100)
    }

    @Test
    fun `row 72 a subscription takes no promotion and may be paid with credits`() {
        val r = full(line(P9), discounts = listOf(PricingFixtures.D1), coupon = K25)
        assertEquals(3000L, r.total)
        assertEquals(PricingCode.COUPON_NOT_APPLICABLE, r.items.coupon!!.reason)
    }

    @Test
    fun `row 76 a subscription with a credit price is payable in full with credits`() {
        val r = full(line(P9), payWithCredits = true, buyer = PricingFixtures.buyer(balance = 5000))
        assertEquals(3000L, r.credits!!.creditTotal)
        assertEquals(3000L, r.creditAmount)
        assertEquals("credits", r.paymentMethodId)
        assertTrue(r.canCheckout)
    }

    @Test
    fun `row 79 a free amount top-up has no minimum order amount and no promotion`() {
        val r = full(topUp(25000), config = config(creditValue = 10, minimumOrder = 5000), discounts = listOf(PricingFixtures.D1), coupon = K25)
        assertEquals(2500L, r.total)
        assertEquals(PricingCode.COUPON_NOT_APPLICABLE, r.items.coupon!!.reason)
        assertFalse(PricingCode.MINIMUM_ORDER_AMOUNT_NOT_REACHED in r.codes())
        assertEquals(2500L, r.gatewayAmount)
    }

    @Test
    fun `row 80 the shipping of a full-credit order is converted at the credit value and rounded up`() {
        val tshirt = Product(14, "T-shirt for credits", 25000, 25000, listOf(3), physical = true)
        val r = full(line(tshirt), payWithCredits = true, config = config(creditValue = 30), shipping = 2990, buyer = PricingFixtures.buyer(balance = 99999))
        assertEquals(9967L, r.credits!!.shippingCredits) // 29.90 / 0.30 = 99.666...
        assertEquals(34967L, r.credits!!.creditTotal) // 250.00 + 99.67
        assertEquals(34967L, r.creditAmount)
        assertEquals(27990L, r.total) // the money run: 250.00 + 29.90
        assertEquals(r.total, r.creditValue)
        assertEquals(0L, r.gatewayAmount)
        // exactly at the balance passes, one hundredth below does not
        assertTrue(full(line(tshirt), payWithCredits = true, config = config(creditValue = 30), shipping = 2990, buyer = PricingFixtures.buyer(balance = 34967)).canCheckout)
        assertFalse(full(line(tshirt), payWithCredits = true, config = config(creditValue = 30), shipping = 2990, buyer = PricingFixtures.buyer(balance = 34966)).canCheckout)
        // no shipping, no shipping credits
        assertEquals(0L, full(line(P1), payWithCredits = true, buyer = PricingFixtures.buyer(balance = 99999)).credits!!.shippingCredits)
    }

    @Test
    fun `the credit run decides and the money run replays the decision`() {
        // a FIXED 15.00 discount against a 25 % coupon: with money prices (100.00) the coupon is better, with credit prices (30.00) the discount is
        val dual = Product(42, "Dual", 10000, 3000, listOf(1))
        val fixed = discount(8, 1500, DiscountUnit.FIXED)
        val cfg = config(combine = false)
        val buyer = PricingFixtures.buyer(balance = 99999)

        val money = full(line(dual), discounts = listOf(fixed), coupon = K25, config = cfg, buyer = buyer)
        assertEquals(7500L, money.total) // S2: the coupon
        assertTrue(money.items.coupon!!.valid)
        assertEquals(0L, money.discountTotal)

        val credits = full(line(dual), discounts = listOf(fixed), coupon = K25, config = cfg, buyer = buyer, payWithCredits = true)
        assertEquals(1500L, credits.creditAmount) // S1 in credits: 30.00 - 15.00
        assertEquals(1500L, credits.key("L42").creditUnitPrice)
        assertEquals(8500L, credits.total) // the money run replays S1: the discount, not the coupon
        assertEquals(1500L, credits.discountTotal)
        assertEquals(PricingCode.CODE_NOT_COMBINABLE, credits.items.coupon!!.reason)
        assertFalse(credits.items.coupon!!.valid)
        assertEquals(8500L, credits.creditValue)
        assertEquals(listOf(DiscountRedemption(8, 1500)), credits.items.discountRedemptions)
        assertEquals(1500L, credits.items.credit!!.lines.single().lineTotal)
    }

    @Test
    fun `a coupon of the credit run is spread over the lines of the credit run on the money amounts`() {
        val buyer = PricingFixtures.buyer(balance = 99999)
        val r = full(line(P1), line(P2, 3), coupon = K25, payWithCredits = true, buyer = buyer)
        // credits: 100.00 -> 25.00 off, 3 x 10.00 -> 7.50 off
        assertEquals(10000L + 3000L - 2500L - 750L, r.creditAmount)
        assertTrue(r.items.credit!!.coupon!!.valid)
        assertEquals(3250L, r.items.credit!!.coupon!!.discount)
        // money: 25.00 and 7.49 (0.25 x 29.97 = 7.4925 -> 7.49)
        assertEquals(2500L, r.key("L1").couponShare)
        assertEquals(749L, r.key("L2").couponShare)
        assertEquals(9748L, r.total)
        // a refused coupon is refused in both runs
        val bad = full(line(P1), coupon = coupon(9, "NOPE", 2500, found = false), payWithCredits = true, buyer = buyer)
        assertFalse(bad.canCheckout)
        assertEquals(PricingCode.CODE_NOT_FOUND, bad.items.coupon!!.reason)
        assertEquals(PricingCode.CODE_NOT_FOUND, bad.items.credit!!.coupon!!.reason)
    }

    @Test
    fun `the upgrade deduction of a full-credit order is converted at the credit value`() {
        val silver = Product(52, "Silver for credits", 12000, 6000, listOf(10), tier = TierInfo(10, 2, UpgradeMode.DIFFERENCE))
        val cfg = config(creditValue = 200) // 2.00 TRY per credit: the owned 50.00 TRY are 25.00 credits
        val r = full(
            line(silver), payWithCredits = true, config = cfg,
            buyer = PricingFixtures.buyer(listOf(owned(701, T1, 5000)), balance = 99999)
        )
        assertEquals(3500L, r.creditAmount) // 60.00 - 25.00 credits
        assertEquals(3500L, r.key("L52").creditUnitPrice)
        assertEquals(701L, r.key("L52").upgradeFromEntitlementId)
        assertEquals(7000L, r.total) // the money run: 120.00 - 50.00 TRY
        assertEquals(5000L, r.key("L52").upgradeAmount)
        // the deduction can never be more than the credit price
        val rich = config(creditValue = 50) // the owned 50.00 TRY are 100.00 credits, the product costs 60.00
        val capped = full(
            line(silver), payWithCredits = true, config = rich,
            buyer = PricingFixtures.buyer(listOf(owned(701, T1, 5000)), balance = 99999)
        )
        assertEquals(0L, capped.creditAmount)
    }

    @Test
    fun `finalize refuses a combination the caller must not make`() {
        val buyer = PricingFixtures.buyer(balance = 99999)
        fun bad(block: () -> Unit) = assertEquals(PricingError.INVALID_INPUT, refused(block).error)
        // the method prices differently from the items
        bad { PricingEngine.finalize(PricingFixtures.price(line(P1)), null, TenderInput(null, PricingFixtures.METHOD_CATALOG)) }
        // credits without payWithCredits in priceItems, and payWithCredits with a gateway method
        bad { PricingEngine.finalize(PricingFixtures.price(line(P1)), null, TenderInput(null, PricingFixtures.method("credits"))) }
        bad { PricingEngine.finalize(PricingFixtures.price(line(P1), buyer = buyer, payWithCredits = true), null, TenderInput(null, PricingFixtures.METHOD_F)) }
        // an in-game purchase is always paid with credits
        bad { PricingEngine.finalize(PricingFixtures.price(line(P1), profile = PricingProfile.INGAME, buyer = buyer), null, TenderInput(null, null)) }
        // a renewal needs its frozen charge to be finalized
        bad { PricingEngine.finalize(PricingFixtures.price(line(P9), profile = PricingProfile.RENEWAL), null, TenderInput(null, null)) }
        // the whole order in credits belongs to the storefront and the game
        bad { PricingFixtures.price(line(P1), profile = PricingProfile.PANEL, payWithCredits = true) }
    }

    // ---------------------------------------------------------------- a buyer in credit debt (07 sections 3.1, 8.5, invariant L6)

    @Test
    fun `a buyer in credit debt is quoted like a buyer with nothing to spend and is never refused`() {
        // a dispute clawback (ALLOW_DEBT) can leave a USER balance below zero; the balance is passed as it is and the engine clamps it
        val debtor = PricingFixtures.buyer(balance = -10_000)
        val broke = PricingFixtures.buyer(balance = 0)

        // a gateway quote: nothing can be applied, MAX applies nothing, the order is payable in money
        val gateway = full(line(P1), method = PricingFixtures.METHOD_F, buyer = debtor, useCredits = MixedPayment.MAX)
        assertEquals(0L, gateway.credits!!.maxApplicable)
        assertEquals(0L, gateway.creditAmount)
        assertEquals(0L, gateway.creditValue)
        assertEquals(0L, gateway.credits!!.balance) // the spendable amount, never negative
        assertEquals(10320L, gateway.gatewayAmount)
        assertTrue(gateway.canCheckout)
        assertEquals(full(line(P1), method = PricingFixtures.METHOD_F, buyer = broke, useCredits = MixedPayment.MAX), gateway)

        // the top-up that repays the debt is quoted: it is a credit purchase, no credits are tendered against it
        val topUp = full(topUp(50_000), method = PricingFixtures.METHOD_F, buyer = debtor)
        assertEquals(0L, topUp.credits!!.maxApplicable)
        assertEquals(50_000L, topUp.total - topUp.paymentFee)
        assertTrue(topUp.canCheckout)
        assertEquals(PricingFixtures.METHOD_F.id, topUp.paymentMethodId)

        // the whole order in credits: the debtor cannot pay it
        val whole = full(line(P1), payWithCredits = true, buyer = debtor)
        assertEquals(0L, whole.creditAmount)
        assertEquals(10_000L, whole.credits!!.creditTotal)
        assertTrue(whole.codes().contains(PricingCode.INSUFFICIENT_CREDITS))
        assertFalse(whole.canCheckout)

        // a number of credits: the quote clamps it to what can be applied (nothing), checkout refuses it
        val asked = full(line(P1), method = PricingFixtures.METHOD_F, buyer = debtor, useCredits = 3000)
        assertEquals(0L, asked.creditAmount)
        assertTrue(asked.codes().contains(PricingCode.CREDITS_REDUCED))
        assertFalse(asked.codes().contains(PricingCode.INSUFFICIENT_CREDITS))
        val strict = full(line(P1), method = PricingFixtures.METHOD_F, buyer = debtor, useCredits = 3000, strict = true)
        assertEquals(0L, strict.creditAmount)
        assertTrue(strict.codes().contains(PricingCode.INSUFFICIENT_CREDITS))
        assertFalse(strict.codes().contains(PricingCode.CREDITS_REDUCED))
        assertFalse(strict.canCheckout)

        // every profile prices it; the in-game purchase just cannot be paid
        for (profile in listOf(PricingProfile.STOREFRONT, PricingProfile.PANEL, PricingProfile.GIFT_CODE, PricingProfile.RENEWAL)) {
            val lines = if (profile == PricingProfile.RENEWAL) arrayOf(line(P9)) else arrayOf(line(P1))
            val renewal = if (profile == PricingProfile.RENEWAL) RenewalCharge(3000, 87) else null
            assertEquals(
                price(*lines, profile = profile, buyer = broke, renewal = renewal),
                price(*lines, profile = profile, buyer = debtor, renewal = renewal), "profile $profile"
            )
        }
        val ingame = full(line(P1), profile = PricingProfile.INGAME, payWithCredits = true, buyer = debtor)
        assertEquals(0L, ingame.creditAmount)
        assertTrue(ingame.codes().contains(PricingCode.INSUFFICIENT_CREDITS))

        // a guest never had a balance, whatever it was handed in
        assertEquals(0L, full(line(P1), buyer = PricingFixtures.buyer(loggedIn = false, userId = null, balance = -500)).credits!!.balance)
    }

    @Test
    fun `MixedPayment counts a debt as nothing to spend, also when it is called with one`() {
        val c = PricingFixtures.conversionsOf(PricingFixtures.price(line(P1)))
        val max = MixedPayment.apply(c, 10_000, null, -5_000, MixedPayment.MAX, strict = false)
        assertEquals(0L, max.maxApplicable)
        assertEquals(0L, max.applied)
        assertEquals(0L, max.appliedValue)
        assertEquals(10_000L, max.remainder)
        assertFalse(max.reduced)
        val quote = MixedPayment.apply(c, 10_000, null, -5_000, 3_000, strict = false)
        assertTrue(quote.reduced)
        assertEquals(0L, quote.applied)
        val strict = MixedPayment.apply(c, 10_000, null, -5_000, 3_000, strict = true)
        assertTrue(strict.rejected)
        assertEquals(MixedPayment.apply(c, 10_000, null, 0, 3_000, strict = true), strict)
        assertEquals(PricingError.INVALID_INPUT, refused { MixedPayment.apply(c, 10_000, null, 5_000, -1, strict = true) }.error)
        assertEquals(PricingError.INVALID_INPUT, refused { MixedPayment.apply(c, 10_000, null, 5_000, MixedPayment.MAX, strict = true) }.error)
    }

    @Test
    fun `the creator earning follows how the money run was priced, a cart that is not payable in credits is a money cart`() {
        // P3 has no credit price: payWithCredits is refused (NOT_PAYABLE_WITH_CREDITS) and the money run is the normal one,
        // so its net money amount is what a commission would be taken from (10 % VAT inside 49.90 is 4.54)
        val r = full(line(P3), payWithCredits = true, buyer = PricingFixtures.buyer(balance = 99_999))
        assertEquals(PricingCode.NOT_PAYABLE_WITH_CREDITS, r.tender.unavailable)
        assertFalse(r.items.credit!!.payable)
        assertEquals(4536L, OrderValues.creatorEarning(r, 1000).baseAmount)
        // a guest cannot pay with credits either
        val guest = full(line(P1), payWithCredits = true, buyer = PricingFixtures.buyer(loggedIn = false, userId = null))
        assertEquals(8333L, OrderValues.creatorEarning(guest, 1000).baseAmount)
    }

    @Test
    fun `the totals of a result add up`() {
        val r = full(
            line(P1), line(P4, variantId = 2, basePrice = 27500), shipping = 2990, method = PricingFixtures.METHOD_F,
            buyer = PricingFixtures.buyer(balance = 4000), useCredits = 4000
        )
        assertEquals(r.items.itemsTotal + r.shippingTotal + r.paymentFee, r.total)
        assertEquals(r.gatewayAmount + r.creditValue, r.total)
        assertEquals(r.items.itemsVat + r.shippingVat + r.tender.paymentFeeVatAmount, r.vatTotal)
        assertEquals(r.items.discountTotal + r.items.couponDiscount + r.items.creatorDiscount + r.items.upgradeDiscount, r.snapshotDiscount)
    }

    @Test
    fun `a finalize that overflows is AMOUNT_OVERFLOW and never wraps`() {
        // 90 lines of 10^17 fit a Long (9 x 10^18), a fee of 100 % on top does not
        val lines = (1..90).map { line(P1, PricingLimits.MAX_QUANTITY, key = "L$it", basePrice = PricingLimits.MAX_AMOUNT) }
        val items = PricingFixtures.price(*lines.toTypedArray())
        assertEquals(9_000_000_000_000_000_000L, items.itemsTotal)
        val fee = PricingFixtures.method("f", 10_000, 0)
        assertEquals(PricingError.AMOUNT_OVERFLOW, refused { PricingEngine.finalize(items, null, TenderInput(null, fee)) }.error)
        // without the fee it is exact
        assertEquals(9_000_000_000_000_000_000L, PricingEngine.finalize(items, null, TenderInput(null, null)).total)
    }

    // ---------------------------------------------------------------- creator earning and cashback (05 section 10)

    @Test
    fun `row 26 a creator code earns its commission on the net amount of the product lines`() {
        val r = full(line(P1), creatorCode = CR5)
        assertEquals(500L, r.creatorDiscount)
        assertEquals(9500L, r.total)
        assertEquals(1583L, r.vatTotal)
        val e = OrderValues.creatorEarning(r, CR5.commissionBp)
        assertEquals(7917L, e.baseAmount) // 95.00 - 15.83
        assertEquals(792L, e.amount) // 10 % of 79.17 = 7.917
    }

    @Test
    fun `row 29 without combining a creator code keeps the attribution and earns on what was paid`() {
        val r = full(line(P1), discounts = listOf(PricingFixtures.D5), creatorCode = CR5, config = config(combine = false))
        assertEquals(6000L, r.total)
        assertEquals(0L, r.creatorDiscount)
        assertTrue(r.items.creatorCode!!.valid)
        assertEquals(PricingCode.CODE_NOT_COMBINABLE, r.items.creatorCode!!.reason)
        val e = OrderValues.creatorEarning(r, CR5.commissionBp)
        assertEquals(5000L, e.baseAmount)
        assertEquals(500L, e.amount)
    }

    @Test
    fun `creator earning leaves out the shipping the fee and the credit purchases and follows a full-credit order`() {
        val r = full(line(P1), line(P6), line(P4, variantId = 2, basePrice = 27500), shipping = 2990, method = PricingFixtures.METHOD_F)
        // P1 100.00 and the T-shirt 275.00 earn; the pack, the shipping and the fee do not
        val e = OrderValues.creatorEarning(r, 1000)
        assertEquals(8333L + 22917L, e.baseAmount) // 83.33 + 229.17
        assertEquals(3125L, e.amount)
        // a full-credit order earns on the credits' money value
        val credit = full(line(P1), line(P2, 3), payWithCredits = true, buyer = PricingFixtures.buyer(balance = 99999), config = config(creditValue = 50))
        val ce = OrderValues.creatorEarning(credit, 1000)
        assertEquals(6500L, ce.baseAmount) // 130.00 credits x 0.50
        assertEquals(650L, ce.amount)
    }

    @Test
    fun `a full-credit order that costs the buyer nothing earns the creator nothing, however much its money record says`() {
        // 100.00 TRY, 15.00 credits at 1.00 per credit; a fixed coupon of 20.00 takes the whole credit price, the money record is 80.00
        val cheap = Product(44, "Cheap in credits", 10000, 1500, listOf(2))
        val rich = PricingFixtures.buyer(balance = 99_999)
        val coupon = full(line(cheap), coupon = KF20, creatorCode = CR5, payWithCredits = true, buyer = rich)
        assertEquals(8000L, coupon.items.itemsTotal) // the money record
        assertEquals(0L, coupon.creditAmount) // what the buyer pays
        assertEquals(0L, coupon.gatewayAmount)
        assertEquals(0L, coupon.credits!!.creditTotal)
        assertTrue(coupon.items.creatorCode!!.valid) // the attribution is kept
        val e = OrderValues.creatorEarning(coupon, CR5.commissionBp)
        assertEquals(0L, e.baseAmount)
        assertEquals(0L, e.amount)

        // the same through a fixed automatic discount that is at least the credit price
        val fixedOff = discount(30, 2000, DiscountUnit.FIXED, DiscountScope.PRODUCTS, productIds = setOf(44))
        val auto = full(line(cheap), discounts = listOf(fixedOff), payWithCredits = true, buyer = rich)
        assertEquals(8000L, auto.items.itemsTotal)
        assertEquals(0L, auto.creditAmount)
        assertEquals(0L, OrderValues.creatorEarning(auto, 1000).baseAmount)
        assertEquals(0L, OrderValues.creatorEarning(auto, 1000).amount)

        // two lines: only the credits the line cost count (the free line adds nothing, the other earns on its credits)
        val mixed = full(line(cheap), line(P1), discounts = listOf(fixedOff), payWithCredits = true, buyer = rich)
        assertEquals(10_000L, mixed.creditAmount) // the cheap line is free in credits, P1 costs 100.00 credits
        assertEquals(10_000L, OrderValues.creatorEarning(mixed, 1000).baseAmount)
        assertEquals(1000L, OrderValues.creatorEarning(mixed, 1000).amount)

        // the same cart paid in money still earns on the net money amount
        val money = full(line(cheap), discounts = listOf(fixedOff), method = PricingFixtures.METHOD_F)
        assertEquals(6667L, OrderValues.creatorEarning(money, 1000).baseAmount) // 80.00 - 13.33
        assertEquals(667L, OrderValues.creatorEarning(money, 1000).amount)
    }

    @Test
    fun `row 53 a credits-only order earns the creator on the credits it cost, not on its zero money total`() {
        val buyer = PricingFixtures.buyer(balance = 5000)
        val order = full(line(creditsOnlyProduct), payWithCredits = true, buyer = buyer)
        assertEquals(0L, order.total)
        assertEquals(4000L, order.creditAmount)
        val e = OrderValues.creatorEarning(order, 1000)
        assertEquals(4000L, e.baseAmount) // 40.00 credits x 1.00
        assertEquals(400L, e.amount)
        // at 0.50 per credit
        val half = full(line(creditsOnlyProduct), payWithCredits = true, buyer = buyer, config = config(creditValue = 50))
        assertEquals(2000L, OrderValues.creatorEarning(half, 1000).baseAmount)
        // the credits of a credit pack or of the shipping never count
        val shipped = full(line(Product(14, "T-shirt for credits", 25000, 25000, listOf(3), physical = true)), payWithCredits = true,
            config = config(creditValue = 30), shipping = 2990, buyer = PricingFixtures.buyer(balance = 99_999))
        assertEquals(25000L + 9967L, shipped.creditAmount)
        assertEquals(7500L, OrderValues.creatorEarning(shipped, 1000).baseAmount) // 250.00 credits x 0.30, no shipping
    }

    @Test
    fun `row 74 cashback is paid on the gateway part of the merchandise only`() {
        val r = full(
            line(P1), method = PricingFixtures.METHOD_F, buyer = PricingFixtures.buyer(balance = 3000), useCredits = MixedPayment.MAX,
            config = config(cashbackBp = 500)
        )
        assertEquals(350L, OrderValues.cashback(r)) // gatewayShare 0.70: floor(70.00 x 5 %) = 3.50 credits
        val cheap = full(line(P2), config = config(cashbackBp = 333))
        assertEquals(33L, OrderValues.cashback(cheap)) // floor(0.3326) = 0.33
        // the fraction above one half still floors: 9.99 x 3.36 % = 0.3357 credits is 0.33, never 0.34
        assertEquals(33L, OrderValues.cashback(full(line(P2), config = config(cashbackBp = 336))))
        // nothing on credit tender, on a credit purchase, on shipping, with no percentage
        val paid = full(line(P1), payWithCredits = true, buyer = PricingFixtures.buyer(balance = 99999), config = config(cashbackBp = 500))
        assertEquals(0L, OrderValues.cashback(paid))
        assertEquals(0L, OrderValues.cashback(full(line(P6), config = config(cashbackBp = 500))))
        assertEquals(0L, OrderValues.cashback(full(line(P1), config = config(cashbackBp = 0))))
        val shipped = full(line(P4, variantId = 2, basePrice = 27500), shipping = 2990, config = config(cashbackBp = 1000))
        assertEquals(2750L, OrderValues.cashback(shipped)) // 10 % of 275.00, not of the 29.90 shipping
        // a foreign order currency converts through the rate: 2.99 USD at 1 TRY per credit is 119.60 credits
        val usd = full(line(P1), currency = "USD", config = config(mode = CurrencyMode.MULTI, cashbackBp = 10_000))
        assertEquals(11960L, OrderValues.cashback(usd))
    }

    // ---------------------------------------------------------------- the seeded property loop of stages B and C

    private class TenderCase(val input: PricingInput, val shipping: ShippingCharge?)

    private val loopGateways: List<MethodInput> = listOf(
        PricingFixtures.METHOD_F,
        PricingFixtures.METHOD_G,
        PricingFixtures.method("plain"),
        PricingFixtures.method("nomix", 100, 10, mixedCredit = false, physicalGoods = true),
        PricingFixtures.method("ship", 150, 0, physicalGoods = true),
        PricingFixtures.method("bounded", 200, 0, providerMin = Money(700, "TRY"), providerMax = Money(2_000_000, "TRY"), physicalGoods = true),
        PricingFixtures.method("usdlimits", 0, 25, providerMin = Money(500, "USD"), providerMax = Money(60_000, "USD"), physicalGoods = true),
        PricingFixtures.method("window", minAmount = 500, maxAmount = 3_000_000, physicalGoods = true),
        PricingFixtures.method("narrow", providerCurrencies = setOf("TRY", "USD"), adminCurrencies = setOf("TRY", "USD", "JPY"), physicalGoods = true),
        PricingFixtures.method("fixedfee", 0, 125, physicalGoods = true),
        PricingFixtures.method("manual", 290, 30, physicalGoods = true),
        PricingFixtures.method("free", 290, 30),
        // limits that most carts trip: a high minimum, a low ceiling, a currency list that never contains the store's
        PricingFixtures.method("tiny", 0, 10, providerMin = Money(500_000, "TRY"), physicalGoods = true),
        PricingFixtures.method("cap", 100, 0, providerMax = Money(20_000, "TRY"), physicalGoods = true),
        PricingFixtures.method("eur", providerCurrencies = setOf("EUR"), physicalGoods = true)
    )

    private val loopPool: List<Product> = listOf(
        P1, P2, P3, P4, P5, P5M, P6, P8, P9, creditsOnlyProduct, T1, T2, T3,
        Product(14, "T-shirt for credits", 25000, 25000, listOf(3), physical = true),
        Product(43, "Crate", 4500, 3000, listOf(2)),
        Product(44, "Cheap in credits", 10000, 1500, listOf(2))
    )

    /** The products of [loopPool] that can be paid with credits (credit price above 0, or free in money and priced in credits). */
    private val creditablePool: List<Product> = loopPool.filter { it.creditPrice > 0L && it.kind == LineKind.PRODUCT && it.tier == null }

    private fun PricingConfig.withCredits(enabled: Boolean) = PricingConfig(
        baseCurrency, currencyMode, additionalCurrencies, multiCurrencyFallback, rates, vatBp, pricesIncludeVat, removeCents,
        minimumOrderAmount, combineDiscountsAndCoupons, enabled, onlyAcceptCredits, creditValue, allowMixedCreditPayment, cashbackBp
    )

    private fun randomTenderCase(rnd: Random): TenderCase {
        val roll = rnd.nextInt(100)
        val profile = when {
            roll < 62 -> PricingProfile.STOREFRONT
            roll < 72 -> PricingProfile.PANEL
            roll < 78 -> PricingProfile.GIFT_CODE
            roll < 90 -> PricingProfile.INGAME
            else -> PricingProfile.RENEWAL
        }
        val mode = CurrencyMode.values()[rnd.nextInt(3)]
        val onlyCredits = rnd.nextInt(16) == 0
        var cfg = config(
            mode = mode,
            removeCents = rnd.nextInt(5) == 0,
            includeVat = rnd.nextBoolean(),
            vatBp = listOf(2000L, 1000L, 0L, 1800L, 10_000L)[rnd.nextInt(5)],
            minimumOrder = if (rnd.nextInt(3) == 0) rnd.nextLong(0, 30_000) else 0L,
            combine = rnd.nextInt(3) != 0,
            creditValue = listOf(100L, 10L, 30L, 250L, 1L, 7L)[rnd.nextInt(6)],
            mixed = rnd.nextInt(8) != 0,
            onlyCredits = onlyCredits,
            cashbackBp = rnd.nextLong(0, 1500)
        )
        if (!onlyCredits && rnd.nextInt(12) == 0) cfg = cfg.withCredits(false)
        val currency = when (mode) {
            CurrencyMode.MULTI -> listOf(null, "USD", "JPY")[rnd.nextInt(3)]
            CurrencyMode.DISPLAY -> listOf(null, "USD")[rnd.nextInt(2)]
            else -> null
        }
        val oq = if (currency == "JPY" || cfg.removeCents) 100L else 1L

        val lines: List<LineInput>
        var renewal: RenewalCharge? = null
        val creditableCart = rnd.nextInt(4) == 0
        // 100.00 TRY priced 15.00 in credits, with a fixed 20.00 coupon: free in credits while the money record is 80.00 (05 section 10)
        val cheapCase = profile == PricingProfile.STOREFRONT && rnd.nextInt(20) == 0
        when {
            cheapCase -> lines = listOf(line(loopPool.single { it.id == 44L }))
            profile == PricingProfile.RENEWAL -> {
                lines = listOf(line(P9))
                val price = rnd.nextLong(1, 2000) * 100
                renewal = RenewalCharge(price, rnd.nextLong(0, price / 400 + 1) * 100)
            }
            profile == PricingProfile.STOREFRONT && rnd.nextInt(16) == 0 -> lines = listOf(topUp(rnd.nextLong(100, 1_000_000)))
            else -> lines = (1..1 + rnd.nextInt(4)).map { i ->
                // a quarter of the carts are made of products that have a credit price, so that the credit run is reached often
                val pool = if (creditableCart) creditablePool else loopPool
                val p = pool[rnd.nextInt(pool.size)]
                val variant = if (p === P4) rnd.nextLong(0, 3) else 0L
                line(p, if (p.tier != null) 1 else 1 + rnd.nextInt(3), variantId = variant, key = "k$i", basePrice = if (variant == 2L) 27500 else p.price)
            }
        }
        val storefront = profile == PricingProfile.STOREFRONT
        val discounts = if (profile == PricingProfile.GIFT_CODE || profile == PricingProfile.RENEWAL) emptyList()
        else listOf(D1, D2, D3, D4, D5, D6, D7).filter { rnd.nextInt(6) == 0 }
        val coupon = if (cheapCase) KF20 else if (storefront && rnd.nextInt(10) < 3) listOf(K25, KF20, KF500, K50P2, KMIN)[rnd.nextInt(5)] else null
        val creator = if (storefront && rnd.nextInt(5) == 0) CR5 else null
        val loggedIn = rnd.nextInt(10) != 0
        val owned = if (rnd.nextInt(10) == 0) listOf(owned(501, T1, rnd.nextLong(0, 20_000))) else emptyList()
        // a negative USER balance is a debt after a dispute clawback (07 section 3.1): the engine treats it as 0, it never refuses it
        val balance = when (rnd.nextInt(5)) {
            0 -> 0L
            1 -> rnd.nextLong(0, 50_000)
            2 -> rnd.nextLong(0, 5_000_000)
            3 -> -rnd.nextLong(1, if (rnd.nextBoolean()) 100_000 else 1_000_000_000_000L / 10)
            else -> rnd.nextLong(0, 1_000_000_000_000L / 10)
        }
        val buyer = PricingFixtures.buyer(owned, balance = balance, loggedIn = loggedIn, userId = if (loggedIn) 1L else null)
        val pricingMode = if (!storefront) PricingMode.MARKET else when (rnd.nextInt(100)) {
            in 0..5 -> PricingMode.EXTERNAL_TAX
            in 6..11 -> PricingMode.EXTERNAL
            else -> PricingMode.MARKET
        }
        val payWithCredits = profile == PricingProfile.INGAME || cheapCase || (storefront && rnd.nextInt(4) == 0)
        val shipping = if (rnd.nextInt(100) < 55) ShippingCharge(rnd.nextLong(0, 400_000), if (rnd.nextInt(3) == 0) rnd.nextLong(0, 3000) else null) else null
        check(oq > 0)
        val input = PricingFixtures.input(
            *lines.toTypedArray(), config = cfg, discounts = discounts, coupon = coupon, creatorCode = creator, profile = profile,
            currency = currency, buyer = buyer, mode = pricingMode, payWithCredits = payWithCredits, renewal = renewal
        )
        return TenderCase(input, shipping)
    }

    @Test
    fun `property loop over 10000 seeded orders, stages B and C agree with an independent oracle and add up`() {
        val rnd = Random(20261011)
        val seen = java.util.TreeMap<String, Int>()
        fun hit(branch: String) = seen.merge(branch, 1) { a, b -> a + b }
        val creditsMethod = PricingFixtures.method("credits")
        repeat(10_000) { n ->
            val case = randomTenderCase(rnd)
            val input = case.input
            val items = PricingEngine.priceItems(input)
            val where = "case #$n ${input.profile} ${input.pricingMode} credits=${input.payWithCredits} balance=${input.buyer.creditBalance}"

            val method: MethodInput? = when {
                items.payWithCredits && !items.creditsForced -> if (rnd.nextBoolean()) null else creditsMethod
                input.pricingMode == PricingMode.EXTERNAL -> listOf(null, PricingFixtures.METHOD_CATALOG)[rnd.nextInt(2)]
                input.pricingMode == PricingMode.EXTERNAL_TAX -> listOf(null, PricingFixtures.METHOD_ADDS_TAX)[rnd.nextInt(2)]
                items.payWithCredits -> if (rnd.nextInt(4) == 0) creditsMethod else loopGateways[rnd.nextInt(loopGateways.size)]
                rnd.nextInt(8) == 0 -> null
                else -> loopGateways[rnd.nextInt(loopGateways.size)]
            }
            val strict = rnd.nextInt(3) == 0
            val useCredits: Long? = when (rnd.nextInt(6)) {
                0 -> null
                1 -> 0L
                2 -> if (strict) rnd.nextLong(1, 100_000) else MixedPayment.MAX
                3 -> rnd.nextLong(1, 100_000)
                4 -> rnd.nextLong(1, 5_000_000)
                else -> if (strict) rnd.nextLong(1, 1_000) else MixedPayment.MAX
            }
            val tender = TenderInput(useCredits, method, strict)
            val r = PricingEngine.finalize(items, case.shipping, tender)
            val e = TenderOracle.expect(input, items, case.shipping, tender)
            val t = r.tender
            val oq = items.conversions.oq

            // the engine against the oracle, field by field
            assertEquals(e.shippingTotal, r.shippingTotal, "$where shippingTotal")
            assertEquals(e.shippingVat, r.shippingVat, "$where shippingVat")
            assertEquals(e.preFee, t.preFee, "$where preFee")
            assertEquals(e.creditAmount, t.creditAmount, "$where creditAmount")
            assertEquals(e.creditValue, t.creditValue, "$where creditValue")
            assertEquals(e.paymentFee, t.paymentFee, "$where paymentFee")
            assertEquals(e.feeVat, t.paymentFeeVatAmount, "$where fee VAT")
            assertEquals(e.total, t.total, "$where total")
            assertEquals(e.vatTotal, t.vatTotal, "$where vatTotal")
            assertEquals(e.gatewayAmount, t.gatewayAmount, "$where gatewayAmount")
            assertEquals(e.methodId, t.paymentMethodId, "$where method")
            assertEquals(e.unavailable, t.unavailable, "$where unavailable")
            assertEquals(e.messages, t.messages.map { it.code }, "$where messages")
            if (e.creditsBlock) {
                val c = t.credits!!
                assertEquals(if (input.buyer.loggedIn) maxOf(0L, input.buyer.creditBalance) else 0L, c.balance, "$where balance")
                assertEquals(e.payable, c.payableInCredits, "$where payable")
                assertEquals(e.creditTotal, c.creditTotal, "$where creditTotal")
                assertEquals(e.shippingCredits, c.shippingCredits, "$where shippingCredits")
                assertEquals(e.maxApplicable, c.maxApplicable, "$where maxApplicable")
                assertEquals(e.creditAmount, c.applied, "$where applied")
                assertEquals(e.creditValue, c.appliedValue, "$where appliedValue")
            } else {
                assertNull(t.credits, "$where credits are off")
            }

            // the invariants of 01 section 5.1 and 05 section 7, stated without the oracle
            var lineSum = 0L
            var vatSum = 0L
            for (l in r.lines) {
                lineSum += l.lineTotal
                vatSum += l.vatAmount
            }
            assertEquals(lineSum + r.shippingTotal + r.paymentFee, r.total, "$where total = sum of lines + shipping + fee")
            assertEquals(r.gatewayAmount + r.creditValue, r.total, "$where gateway + credit value = total")
            assertEquals(vatSum + r.shippingVat + t.paymentFeeVatAmount, r.vatTotal, "$where vat parts")
            assertTrue(r.gatewayAmount >= 0 && r.creditValue >= 0 && r.creditAmount >= 0 && r.paymentFee >= 0 && r.shippingTotal >= 0, where)
            assertTrue(r.creditValue <= t.preFee, "$where credits never pay the fee")
            for (v in listOf(r.total, r.paymentFee, r.shippingTotal, r.gatewayAmount, r.creditValue, r.vatTotal)) assertEquals(0L, v % oq, "$where quantum $oq of $v")
            if (!items.payWithCredits && r.creditAmount > 0L) {
                assertTrue(t.preFee - r.creditValue >= oq && r.gatewayAmount > 0L, "$where a mixed order leaves the gateway something")
                hit("mixed order leaves a remainder")
            }
            if (items.payWithCredits) {
                assertEquals(0L, r.paymentFee, "$where no fee on a credit order")
                if (r.creditAmount > 0L) assertEquals(0L, r.gatewayAmount, "$where no gateway on a full-credit order")
            }
            if (input.buyer.loggedIn && input.buyer.creditBalance < 0L) hit("buyer in debt")
            // 05 section 10: a creator earns on what the buyer paid; an order that cost nothing earns nothing, whatever its money record says
            if (input.profile == PricingProfile.STOREFRONT && r.items.lines.any { it.lineKind == LineKind.PRODUCT || it.lineKind == LineKind.BUNDLE }) {
                val earning = OrderValues.creatorEarning(r, 1000)
                assertTrue(earning.baseAmount >= 0L && earning.amount >= 0L && earning.amount <= earning.baseAmount, "$where earning is never negative")
                val created = t.unavailable == null && t.messages.none { it.code == PricingCode.INSUFFICIENT_CREDITS } // a refused tender is no order
                if (created && items.payWithCredits && items.credit?.payable == true) {
                    // a credit-mode order earns on the credits it cost: never on the money record, never more than the credits spent are worth
                    val spent = Rounding.ratioQ(
                        java.math.BigDecimal.valueOf(r.creditAmount).multiply(java.math.BigDecimal.valueOf(input.config.creditValue)),
                        java.math.BigDecimal(100), 1L
                    )
                    assertTrue(earning.baseAmount <= spent, "$where credit-mode earning ${earning.baseAmount} above the credits spent worth $spent")
                    if (r.creditAmount == 0L) {
                        assertEquals(0L, earning.baseAmount, "$where an order that cost nothing earns nothing")
                        if (r.total > 0L && r.gatewayAmount == 0L) hit("free in credits with a money record")
                    }
                    if (r.creditAmount > 0L) hit("credit-mode earning")
                }
            }
            when (input.profile) {
                PricingProfile.GIFT_CODE -> {
                    assertEquals(0L, r.total, where)
                    assertEquals("free", r.paymentMethodId, where)
                    assertEquals(r.subtotal, r.discountTotal, "$where a gift is a full discount")
                    assertEquals(0L, r.vatTotal, where)
                }
                PricingProfile.RENEWAL -> assertEquals(input.renewal!!.price, r.total, "$where a renewal charges the frozen price")
                else -> {}
            }

            // property 7 of 05 section 17: no credits, no method, no fee => items + shipping
            if (!items.payWithCredits) {
                val bare = PricingEngine.finalize(items, case.shipping, TenderInput(null, null))
                assertEquals(items.itemsTotal + bare.shippingTotal + (input.renewal?.paymentFee ?: 0L), bare.total, "$where bare total")
                assertEquals(0L, bare.creditAmount, where)
                assertEquals(0L, bare.creditValue, where)
            }
            // determinism
            assertEquals(r, PricingEngine.finalize(items, case.shipping, tender), "$where determinism")

            // the list of methods and the chosen method are the same arithmetic
            if (!items.payWithCredits && input.pricingMode == PricingMode.MARKET && !(input.config.onlyAcceptCredits && input.config.creditsEnabled)) {
                for (g in loopGateways) {
                    val alone = PricingEngine.finalize(items, case.shipping, TenderInput(null, g))
                    val listed = PricingEngine.evaluateMethods(items, case.shipping, TenderInput(null, null), listOf(g))
                    assertEquals(1, listed.size, where)
                    assertEquals(alone.paymentFee, listed[0].feeAmount, "$where ${g.id} fee")
                    assertEquals(alone.gatewayAmount, listed[0].gatewayAmount, "$where ${g.id} gateway")
                    if (alone.gatewayAmount > 0L) {
                        assertEquals(alone.tender.unavailable, listed[0].unavailableReason, "$where ${g.id} reason")
                        assertEquals(alone.tender.unavailable == null, listed[0].available, "$where ${g.id} available")
                    }
                }
                hit("method list agrees with the tender")
            }

            for (b in e.branches) hit(b)
            hit("profile ${input.profile}")
            if (input.pricingMode != PricingMode.MARKET) hit("pricing mode ${input.pricingMode}")
            if (items.creditsForced) hit("credits forced by the store")
            if (r.canCheckout) hit("can checkout")
        }
        println("TENDER-LOOP branches=$seen")
        val required = listOf(
            "shipping charged", "full credit", "credits refused: balance", "credits refused: not payable", "mixed applied", "mixed clamped",
            "mixed rejected", "mixed not supported", "fee", "free order", "minimum order", "mixed order leaves a remainder",
            "method refused: AMOUNT_BELOW_MINIMUM", "method refused: AMOUNT_ABOVE_MAXIMUM", "method refused: PHYSICAL_NOT_SUPPORTED",
            "method refused: CURRENCY_NOT_SUPPORTED", "method list agrees with the tender", "credits forced by the store", "can checkout",
            "profile STOREFRONT", "profile PANEL", "profile GIFT_CODE", "profile INGAME", "profile RENEWAL",
            "pricing mode EXTERNAL", "pricing mode EXTERNAL_TAX", "buyer in debt", "credit-mode earning", "free in credits with a money record"
        )
        for (branch in required) assertTrue((seen[branch] ?: 0) >= 100, "the loop barely exercised '$branch': ${seen[branch]}")
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
