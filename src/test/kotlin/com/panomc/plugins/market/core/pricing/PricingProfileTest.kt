package com.panomc.plugins.market.core.pricing

import com.panomc.plugins.market.config.CurrencyMode
import com.panomc.plugins.market.core.pricing.PricingFixtures.CR5
import com.panomc.plugins.market.core.pricing.PricingFixtures.D1
import com.panomc.plugins.market.core.pricing.PricingFixtures.K25
import com.panomc.plugins.market.core.pricing.PricingFixtures.METHOD_ADDS_TAX
import com.panomc.plugins.market.core.pricing.PricingFixtures.METHOD_F
import com.panomc.plugins.market.core.pricing.PricingFixtures.P1
import com.panomc.plugins.market.core.pricing.PricingFixtures.P2
import com.panomc.plugins.market.core.pricing.PricingFixtures.P3
import com.panomc.plugins.market.core.pricing.PricingFixtures.P4
import com.panomc.plugins.market.core.pricing.PricingFixtures.P5
import com.panomc.plugins.market.core.pricing.PricingFixtures.P5M
import com.panomc.plugins.market.core.pricing.PricingFixtures.P6
import com.panomc.plugins.market.core.pricing.PricingFixtures.P8
import com.panomc.plugins.market.core.pricing.PricingFixtures.P9
import com.panomc.plugins.market.core.pricing.PricingFixtures.Product
import com.panomc.plugins.market.core.pricing.PricingFixtures.T1
import com.panomc.plugins.market.core.pricing.PricingFixtures.T2
import com.panomc.plugins.market.core.pricing.PricingFixtures.buyer
import com.panomc.plugins.market.core.pricing.PricingFixtures.config
import com.panomc.plugins.market.core.pricing.PricingFixtures.full
import com.panomc.plugins.market.core.pricing.PricingFixtures.line
import com.panomc.plugins.market.core.pricing.PricingFixtures.method
import com.panomc.plugins.market.core.pricing.PricingFixtures.owned
import com.panomc.plugins.market.core.pricing.PricingFixtures.price
import com.panomc.plugins.market.db.model.UpgradeMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger
import kotlin.random.Random

/**
 * The pricing profiles of 05 section 12 (`market_order.source`): `STOREFRONT` (the checkout, called CHECKOUT in the work
 * breakdown), `PANEL`, `GIFT_CODE`, `INGAME` and `RENEWAL`. What each profile takes and what it leaves out is asserted
 * through the whole of stages A to C, including rows 70 (gift code), 71 (panel price override) and 77 (renewal total) of
 * 05 section 17, then a seeded loop that proves the price override exact against its own largest-remainder reference.
 */
class PricingProfileTest {
    private fun PriceBreakdown.key(key: String): PricedLine = lines.single { it.lineKey == key }

    private fun refused(block: () -> Unit): PricingException = assertThrows(PricingException::class.java) { block() }

    private fun PriceBreakdown.codes(): List<PricingCode> = messages.map { it.code }

    private val tshirt = line(P4, variantId = 2, basePrice = 27500)

    /** A physical product that is also sold for credits (the in-game and full-credit shipping cases). */
    private val creditTshirt = Product(14, "T-shirt for credits", 25000, 25000, listOf(3), physical = true)

    // ================================================================ the table of 05 section 12

    @Test
    fun `the profile table of 05 section 12 is what the profiles say`() {
        data class Row(val p: PricingProfile, val discounts: Boolean, val codes: Boolean, val upgrade: Boolean, val minimumOrder: Boolean)
        val table = listOf(
            Row(PricingProfile.STOREFRONT, true, true, true, true),
            Row(PricingProfile.PANEL, true, false, true, false),
            Row(PricingProfile.GIFT_CODE, false, false, false, false),
            Row(PricingProfile.INGAME, true, false, true, false),
            Row(PricingProfile.RENEWAL, false, false, false, false)
        )
        assertEquals(table.size, PricingProfile.values().size)
        for (r in table) {
            assertEquals(r.discounts, r.p.discounts, "${r.p} discounts")
            assertEquals(r.codes, r.p.codes, "${r.p} codes")
            assertEquals(r.upgrade, r.p.upgrade, "${r.p} upgrade")
            assertEquals(r.minimumOrder, r.p.minimumOrder, "${r.p} minimum order")
        }
    }

    @Test
    fun `a profile that takes no codes refuses a coupon or a creator code instead of ignoring it`() {
        val rich = buyer(balance = 99_999)
        fun bad(block: () -> Unit) = assertEquals(PricingError.INVALID_INPUT, refused(block).error)
        bad { price(line(P1), profile = PricingProfile.PANEL, coupon = K25) }
        bad { price(line(P1), profile = PricingProfile.PANEL, creatorCode = CR5) }
        bad { price(line(P1), profile = PricingProfile.GIFT_CODE, coupon = K25) }
        bad { price(line(P1), profile = PricingProfile.INGAME, coupon = K25, payWithCredits = true, buyer = rich) }
        bad { price(line(P9), profile = PricingProfile.RENEWAL, creatorCode = CR5, renewal = RenewalCharge(3000, 0)) }
        // and the storefront takes both
        val ok = price(line(P1), profile = PricingProfile.STOREFRONT, coupon = K25, creatorCode = CR5)
        assertEquals(2500L, ok.couponDiscount)
    }

    // ================================================================ STOREFRONT (the checkout)

    @Test
    fun `STOREFRONT takes the discount, the coupon, the fee and works out every part of the total`() {
        val r = full(line(P1), discounts = listOf(D1), coupon = K25, method = METHOD_F)
        assertEquals(9000L, r.key("L1").unitPrice) // 100.00 - 10 %
        assertEquals(2250L, r.couponDiscount) // 25 % of 90.00
        assertEquals(6750L, r.items.itemsBasis)
        assertEquals(1125L, r.key("L1").vatAmount) // 67.50 x 20 / 120
        assertEquals(226L, r.paymentFee) // 2.9 % of 67.50 = 1.96, plus 0.30
        assertEquals(6976L, r.total)
        assertEquals(6976L, r.gatewayAmount)
        assertEquals(38L, r.tender.paymentFeeVatAmount) // 2.26 x 20 / 120 = 0.377
        assertEquals(1163L, r.vatTotal)
        assertEquals("F", r.paymentMethodId)
        assertTrue(r.canCheckout)
    }

    @Test
    fun `STOREFRONT checks the minimum order amount, ships, takes a fee on the gateway part and a mixed credit part`() {
        // 90.00 after the discount is below a minimum of 100.00 in the storefront, and not in the panel
        val minimum = config(minimumOrder = 10000)
        assertTrue(PricingCode.MINIMUM_ORDER_AMOUNT_NOT_REACHED in full(line(P1), discounts = listOf(D1), config = minimum).codes())
        assertFalse(PricingCode.MINIMUM_ORDER_AMOUNT_NOT_REACHED in full(line(P1), discounts = listOf(D1), config = minimum, profile = PricingProfile.PANEL).codes())
        // the upgrade deduction
        assertEquals(7000L, full(line(T2), buyer = buyer(listOf(owned(501, T1, 5000)))).total)
        // goods, shipping, a mixed credit part and the fee on what the gateway collects
        val post = method("F", 290, 30, physicalGoods = true)
        val r = full(tshirt, shipping = 2990, method = post, buyer = buyer(balance = 10_000), useCredits = MixedPayment.MAX)
        assertEquals(2990L, r.shippingTotal)
        assertEquals(498L, r.shippingVat) // 29.90 x 20 / 120
        assertEquals(10_000L, r.creditAmount)
        assertEquals(10_000L, r.creditValue)
        assertEquals(624L, r.paymentFee) // 2.9 % of (304.90 - 100.00) = 5.94, plus 0.30
        assertEquals(31_114L, r.total)
        assertEquals(21_114L, r.gatewayAmount)
        assertEquals(5185L, r.vatTotal) // 45.83 + 4.98 + 1.04
        assertTrue(r.canCheckout)
    }

    // ================================================================ PANEL

    @Test
    fun `PANEL takes the automatic discount and the upgrade, no minimum, and no fee without a method`() {
        val r = full(line(P1), discounts = listOf(D1), profile = PricingProfile.PANEL, config = config(minimumOrder = 1_000_000))
        assertEquals(9000L, r.total)
        assertEquals(1000L, r.discountTotal)
        assertEquals(0L, r.paymentFee)
        assertNull(r.paymentMethodId) // the admin has not named a method: nothing is charged on top
        assertFalse(PricingCode.MINIMUM_ORDER_AMOUNT_NOT_REACHED in r.codes())
        assertTrue(r.canCheckout)
        // the upgrade deduction applies (row 30)
        val upgrade = full(line(T2), profile = PricingProfile.PANEL, buyer = buyer(listOf(owned(501, T1, 5000))))
        assertEquals(7000L, upgrade.total)
        assertEquals(5000L, upgrade.upgradeDiscount)
        assertEquals(501L, upgrade.key("L22").upgradeFromEntitlementId)
    }

    @Test
    fun `PANEL prices the goods it ships, takes the manual method without a fee and a gateway with one`() {
        val shipped = full(tshirt, profile = PricingProfile.PANEL, shipping = 2990)
        assertEquals(2990L, shipped.shippingTotal)
        assertEquals(30_490L, shipped.total)
        // a goods order without a charge says so (the caller answers SHIPPING_ADDRESS_REQUIRED)
        assertTrue(full(tshirt, profile = PricingProfile.PANEL).shippingMissing)
        // the manual provider carries no buyer fee, whatever its settings say
        val manual = full(line(P1), profile = PricingProfile.PANEL, method = method("manual", 290, 30, physicalGoods = true))
        assertEquals(0L, manual.paymentFee)
        assertEquals(10_000L, manual.total)
        // a pending manual order paid by the buyer later goes through a gateway and its fee (retender); finalize shows the same figure
        assertEquals(320L, full(line(P1), profile = PricingProfile.PANEL, method = METHOD_F).paymentFee) // 2.9 % of 100.00 + 0.30
        // the panel never touches a credit balance
        val credits = full(line(P1), profile = PricingProfile.PANEL, buyer = buyer(balance = 50_000), useCredits = MixedPayment.MAX)
        assertEquals(0L, credits.creditAmount)
        assertTrue(PricingCode.MIXED_CREDIT_NOT_SUPPORTED in credits.codes())
    }

    @Test
    fun `row 71 a panel price override is spread by largest remainder over the gross list totals`() {
        val r = full(line(P1), line(P2, 3), profile = PricingProfile.PANEL, override = 8000)
        assertEquals(6155L, r.key("L1").lineTotal)
        assertEquals(1845L, r.key("L2").lineTotal) // the leftover cent goes to the line with the larger remainder
        assertEquals(4997L, r.discountTotal) // 129.97 - 80.00
        assertEquals(3845L, r.key("L1").discountAmount)
        assertEquals(1152L, r.key("L2").discountAmount)
        assertEquals(8000L, r.total)
        assertEquals(1334L, r.vatTotal) // 10.26 + 3.08
        assertEquals(0L, r.shippingTotal)
        assertEquals(0L, r.paymentFee)
        assertEquals(8000L, r.gatewayAmount)
        // above the gross list total is refused, the endpoint answers 400 BAD_REQUEST
        assertEquals(PricingError.PRICE_OVERRIDE_OUT_OF_RANGE, refused { full(line(P1), line(P2, 3), profile = PricingProfile.PANEL, override = 13_000) }.error)
        assertEquals(PricingError.PRICE_OVERRIDE_OUT_OF_RANGE, refused { full(line(P1), line(P2, 3), profile = PricingProfile.PANEL, override = 12_998) }.error)
        // the whole list total is allowed and leaves every line as it was
        val whole = full(line(P1), line(P2, 3), profile = PricingProfile.PANEL, override = 12_997)
        assertEquals(10_000L, whole.key("L1").lineTotal)
        assertEquals(2997L, whole.key("L2").lineTotal)
        assertEquals(0L, whole.discountTotal)
    }

    @Test
    fun `a price override sets the total whatever else is in the cart`() {
        // no automatic discount, no upgrade: the override is what the buyer pays
        val discounted = full(line(P1), discounts = listOf(D1), profile = PricingProfile.PANEL, override = 5000)
        assertEquals(5000L, discounted.total)
        assertEquals(5000L, discounted.discountTotal) // against the list price, not on top of the discount
        assertTrue(discounted.items.discountRedemptions.isEmpty())
        assertNull(discounted.key("L1").discountId)
        val upgrade = full(line(T2), profile = PricingProfile.PANEL, override = 9000, buyer = buyer(listOf(owned(501, T1, 5000))))
        assertEquals(9000L, upgrade.total)
        assertEquals(0L, upgrade.upgradeDiscount)
        // zero is allowed and the order is free
        val zero = full(line(P1), profile = PricingProfile.PANEL, override = 0)
        assertEquals(0L, zero.total)
        assertEquals("free", zero.paymentMethodId)
        assertEquals(0L, zero.vatTotal)
        // goods: no shipping, no fee, even when the caller passes a charge and a gateway
        val goods = full(tshirt, profile = PricingProfile.PANEL, override = 10_000, shipping = 2990, method = method("F", 290, 30, physicalGoods = true))
        assertEquals(10_000L, goods.total)
        assertEquals(0L, goods.shippingTotal)
        assertEquals(0L, goods.paymentFee)
        assertFalse(goods.shippingMissing)
        // exclusive prices: the override is the gross total, VAT is the part inside it
        val exclusive = full(line(P1), profile = PricingProfile.PANEL, override = 6000, config = config(includeVat = false))
        assertEquals(6000L, exclusive.total)
        assertEquals(1000L, exclusive.vatTotal)
        assertEquals(5000L, exclusive.items.itemsBasis)
        assertEquals(5000L, exclusive.key("L1").discountAmount) // 100.00 net list against 50.00 net
        // the override is rounded to the quantum before it is spread
        val whole = full(line(P1), line(P2, 3), profile = PricingProfile.PANEL, override = 8050, config = config(removeCents = true))
        assertEquals(8100L, whole.total) // 80.50 rounds half up to 81
        assertEquals(6200L, whole.key("L1").lineTotal)
        assertEquals(1900L, whole.key("L2").lineTotal)
        // only the panel, only a market price, never negative
        assertEquals(PricingError.INVALID_INPUT, refused { full(line(P1), override = 5000) }.error)
        assertEquals(PricingError.INVALID_INPUT, refused { full(line(P1), profile = PricingProfile.PANEL, override = -1) }.error)
        assertEquals(
            PricingError.INVALID_INPUT,
            refused { full(line(P1), profile = PricingProfile.PANEL, override = 5000, method = METHOD_ADDS_TAX) }.error
        )
    }

    // ================================================================ GIFT_CODE

    @Test
    fun `row 70 a gift code order is worth nothing, carries no VAT and goes to the free method`() {
        val r = full(line(P1), profile = PricingProfile.GIFT_CODE)
        assertEquals(10_000L, r.key("L1").listUnitPrice)
        assertEquals(10_000L, r.discountTotal)
        assertEquals(10_000L, r.subtotal)
        assertEquals(0L, r.total)
        assertEquals(0L, r.vatTotal)
        assertEquals("free", r.paymentMethodId)
        assertEquals(0L, r.gatewayAmount)
        assertEquals(10_000L, r.snapshotDiscount)
        assertTrue(r.canCheckout)
    }

    @Test
    fun `a gift code order takes no shipping, no fee, no credits, no minimum and no automatic discount redemption`() {
        val gift = full(
            line(P1), line(P2, 3), tshirt, profile = PricingProfile.GIFT_CODE, discounts = listOf(D1), shipping = 2990, method = METHOD_F,
            config = config(minimumOrder = 1_000_000), buyer = buyer(balance = 99_999), useCredits = MixedPayment.MAX
        )
        assertEquals(0L, gift.total)
        assertEquals(0L, gift.shippingTotal) // physical products cannot be redeemed: shipping is never priced here
        assertFalse(gift.shippingMissing)
        assertEquals(0L, gift.paymentFee)
        assertEquals(0L, gift.creditAmount)
        assertNull(gift.credits) // credits are not offered on a gift
        assertEquals("free", gift.paymentMethodId)
        assertFalse(PricingCode.MINIMUM_ORDER_AMOUNT_NOT_REACHED in gift.codes())
        assertTrue(gift.items.discountRedemptions.isEmpty())
        for (l in gift.lines.filter { it.parentLineKey == null }) {
            assertEquals(l.listUnitPrice, l.unitDiscount, l.lineKey)
            assertEquals(0L, l.lineTotal, l.lineKey)
            assertEquals(0L, l.vatAmount, l.lineKey)
            assertNull(l.discountId, l.lineKey)
        }
        assertEquals(gift.subtotal, gift.discountTotal)
        assertTrue(gift.canCheckout)
    }

    // ================================================================ RENEWAL

    @Test
    fun `row 77 a renewal charges the frozen price however the VAT rate moved`() {
        val r = full(line(P9), profile = PricingProfile.RENEWAL, renewal = RenewalCharge(3000, 87), config = config(vatBp = 1000), method = METHOD_F)
        assertEquals(3000L, r.total)
        assertEquals(87L, r.paymentFee)
        assertEquals(2913L, r.key("L9").lineTotal)
        assertEquals(265L, r.key("L9").vatAmount) // 29.13 x 10 / 110 = 2.648
        assertEquals(8L, r.tender.paymentFeeVatAmount) // 0.87 x 10 / 110 = 0.079
        assertEquals(273L, r.vatTotal)
        assertEquals(3000L, r.gatewayAmount)
        assertEquals(0L, r.creditAmount) // a renewal is created without credits
        // only the VAT split moves, never the total, whatever the rate is
        val splits = mutableListOf<Long>()
        for (bp in listOf(0L, 500L, 1000L, 2000L, 2500L, 10_000L)) {
            for (inclusive in listOf(true, false)) {
                val x = full(line(P9), profile = PricingProfile.RENEWAL, renewal = RenewalCharge(3000, 87), config = config(vatBp = bp, includeVat = inclusive))
                assertEquals(3000L, x.total, "vat $bp inclusive=$inclusive")
                assertEquals(87L, x.paymentFee, "vat $bp")
                assertEquals(2913L, x.items.itemsTotal, "vat $bp")
                assertEquals(3000L, x.gatewayAmount, "vat $bp")
                assertEquals(x.items.itemsVat + x.tender.paymentFeeVatAmount, x.vatTotal, "vat $bp")
                assertTrue(x.key("L9").vatAmount <= 2913L, "vat $bp")
                splits += x.key("L9").vatAmount
            }
        }
        assertEquals(setOf(0L, 139L, 265L, 486L, 583L, 1457L), splits.toSet()) // 2913 x bp / (10000 + bp), half up
    }

    @Test
    fun `a renewal takes no promotion, no shipping, no minimum, no credits and no recomputed fee`() {
        val frozen = RenewalCharge(3000, 87)
        val r = full(
            line(P9), profile = PricingProfile.RENEWAL, renewal = frozen, discounts = listOf(D1), shipping = 2990,
            config = config(minimumOrder = 1_000_000), buyer = buyer(balance = 50_000), useCredits = MixedPayment.MAX, method = method("other", 500, 500)
        )
        assertEquals(0L, r.discountTotal)
        assertEquals(0L, r.shippingTotal)
        assertEquals(87L, r.paymentFee) // the fee copied at activation, whatever the method says now
        assertEquals(3000L, r.total)
        assertFalse(PricingCode.MINIMUM_ORDER_AMOUNT_NOT_REACHED in r.codes())
        assertEquals(0L, r.creditAmount)
        assertTrue(PricingCode.MIXED_CREDIT_NOT_SUPPORTED in r.codes())
        // goods in a renewal are not shipped again
        val goods = full(line(P4, variantId = 2, basePrice = 27500), profile = PricingProfile.RENEWAL, renewal = RenewalCharge(27_500, 0), shipping = 2990)
        assertEquals(0L, goods.shippingTotal)
        assertEquals(27_500L, goods.total)
        // a gateway that sets the tax: no VAT split, the price is still the frozen one
        val external = full(line(P9), profile = PricingProfile.RENEWAL, renewal = RenewalCharge(3000, 0), method = METHOD_ADDS_TAX)
        assertEquals(3000L, external.total)
        assertEquals(0L, external.vatTotal)
        assertEquals(0L, external.key("L9").vatPercent)
        // no fee at all frozen: none now
        assertEquals(0L, full(line(P9), profile = PricingProfile.RENEWAL, renewal = RenewalCharge(3000, 0), method = METHOD_F).paymentFee)
    }

    @Test
    fun `a renewal that breaks the contract is refused`() {
        fun bad(block: () -> Unit) = assertEquals(PricingError.INVALID_INPUT, refused(block).error)
        bad { price(line(P9), profile = PricingProfile.RENEWAL, renewal = RenewalCharge(3000, 3001)) } // the fee is part of the price
        bad { price(line(P9), profile = PricingProfile.RENEWAL, renewal = RenewalCharge(-1, 0)) }
        bad { price(line(P9, 2), profile = PricingProfile.RENEWAL, renewal = RenewalCharge(3000, 0)) } // one line of quantity 1
        bad { price(line(P9), line(P2), profile = PricingProfile.RENEWAL, renewal = RenewalCharge(3000, 0)) }
        bad { price(line(P5), profile = PricingProfile.RENEWAL, renewal = RenewalCharge(3000, 0)) } // a product line
        bad { price(line(P9), renewal = RenewalCharge(3000, 0)) } // the charge belongs to the renewal profile
        bad { price(line(P9), profile = PricingProfile.RENEWAL, renewal = RenewalCharge(3000, 0), payWithCredits = true) }
        // a renewal needs its frozen charge to be finalized
        bad { PricingEngine.finalize(price(line(P9), profile = PricingProfile.RENEWAL), null, TenderInput(null, null)) }
    }

    // ================================================================ INGAME

    @Test
    fun `INGAME takes the discount in credits and is always a full-credit purchase`() {
        val buyer = buyer(balance = 20_000)
        val r = full(line(P1), line(P2, 3), profile = PricingProfile.INGAME, payWithCredits = true, discounts = listOf(D1), buyer = buyer)
        assertEquals(11_700L, r.creditAmount) // 90.00 + 3 x 9.00
        assertEquals(11_697L, r.total) // the money run: 90.00 + 3 x 8.99
        assertEquals(r.total, r.creditValue)
        assertEquals(0L, r.gatewayAmount)
        assertEquals(0L, r.paymentFee)
        assertEquals("credits", r.paymentMethodId)
        assertTrue(r.canCheckout)
        // it is never a gateway order and never a partial one
        assertEquals(PricingError.INVALID_INPUT, refused { full(line(P1), profile = PricingProfile.INGAME, buyer = buyer) }.error)
        assertEquals(PricingError.INVALID_INPUT, refused { full(line(P1), profile = PricingProfile.INGAME, payWithCredits = true, buyer = buyer, method = METHOD_F) }.error)
        val asked = full(line(P1), profile = PricingProfile.INGAME, payWithCredits = true, buyer = buyer, useCredits = 1000)
        assertEquals(10_000L, asked.creditAmount) // the whole price, not the part asked for
    }

    @Test
    fun `INGAME takes the upgrade, no minimum, no shipping, and refuses what credits cannot pay`() {
        val silver = Product(52, "Silver for credits", 12000, 6000, listOf(10), tier = TierInfo(10, 2, UpgradeMode.DIFFERENCE))
        val upgrade = full(
            line(silver), profile = PricingProfile.INGAME, payWithCredits = true, config = config(creditValue = 200),
            buyer = buyer(listOf(owned(701, T1, 5000)), balance = 99_999)
        )
        assertEquals(3500L, upgrade.creditAmount) // 60.00 - 25.00 credits
        assertEquals(701L, upgrade.key("L52").upgradeFromEntitlementId)
        // a minimum order amount is not checked, goods are not shipped from the game
        val goods = full(
            line(creditTshirt), profile = PricingProfile.INGAME, payWithCredits = true, shipping = 2990,
            config = config(minimumOrder = 100_000_000), buyer = buyer(balance = 99_999)
        )
        assertEquals(25_000L, goods.creditAmount)
        assertEquals(0L, goods.shippingTotal)
        assertEquals(0L, goods.credits!!.shippingCredits)
        assertFalse(goods.shippingMissing)
        assertFalse(PricingCode.MINIMUM_ORDER_AMOUNT_NOT_REACHED in goods.codes())
        assertTrue(goods.canCheckout)
        // what credits cannot pay is refused, never turned into money
        val none = full(line(P3), profile = PricingProfile.INGAME, payWithCredits = true, buyer = buyer(balance = 99_999))
        assertEquals(listOf(PricingCode.NOT_PAYABLE_WITH_CREDITS), none.key("L3").errors)
        assertFalse(none.canCheckout)
        val guest = full(line(P1), profile = PricingProfile.INGAME, payWithCredits = true, buyer = buyer(loggedIn = false, userId = null))
        assertEquals(PricingCode.LOGIN_REQUIRED, guest.tender.unavailable)
        assertFalse(guest.canCheckout)
        val short = full(line(P1), profile = PricingProfile.INGAME, payWithCredits = true, buyer = buyer(balance = 9_999))
        assertTrue(PricingCode.INSUFFICIENT_CREDITS in short.codes())
        assertEquals(0L, short.creditAmount)
        assertFalse(short.canCheckout)
        val off = config().let { c ->
            PricingConfig(c.baseCurrency, c.currencyMode, c.additionalCurrencies, c.multiCurrencyFallback, c.rates, c.vatBp, c.pricesIncludeVat,
                c.removeCents, c.minimumOrderAmount, c.combineDiscountsAndCoupons, false, false, c.creditValue, true, c.cashbackBp)
        }
        assertEquals(PricingCode.CREDITS_DISABLED, full(line(P1), profile = PricingProfile.INGAME, payWithCredits = true, config = off, buyer = buyer(balance = 99_999)).tender.unavailable)
    }

    // ================================================================ across the profiles

    @Test
    fun `shipping is priced for the storefront and the panel only`() {
        val credit = buyer(balance = 999_999)
        for (profile in PricingProfile.values()) {
            val renewal = if (profile == PricingProfile.RENEWAL) RenewalCharge(27_500, 0) else null
            val credits = profile == PricingProfile.INGAME
            val product = if (credits) creditTshirt else P4
            val l = if (credits) line(product) else line(product, variantId = 2, basePrice = 27500)
            val r = full(l, profile = profile, shipping = 2990, renewal = renewal, payWithCredits = credits, buyer = credit)
            val ships = profile == PricingProfile.STOREFRONT || profile == PricingProfile.PANEL
            assertEquals(if (ships) 2990L else 0L, r.shippingTotal, "$profile")
            assertTrue(r.items.requiresShipping, "$profile: the cart still has goods, the profile just does not price their shipping")
            assertFalse(r.shippingMissing && !ships, "$profile")
        }
    }

    @Test
    fun `a credit part of a mixed payment is a storefront thing`() {
        val rich = buyer(balance = 99_999)
        val plain = method("plain")
        val store = full(line(P1), buyer = rich, useCredits = 5000, method = plain)
        assertEquals(5000L, store.creditAmount)
        for (profile in listOf(PricingProfile.PANEL, PricingProfile.GIFT_CODE)) {
            val r = full(line(P1), profile = profile, buyer = rich, useCredits = 5000, method = plain)
            assertEquals(0L, r.creditAmount, "$profile")
            assertTrue(PricingCode.MIXED_CREDIT_NOT_SUPPORTED in r.codes(), "$profile")
        }
        val renewal = full(line(P9), profile = PricingProfile.RENEWAL, renewal = RenewalCharge(3000, 0), buyer = rich, useCredits = 5000, method = plain)
        assertEquals(0L, renewal.creditAmount)
        // at checkout the same request is refused, not ignored
        val strict = full(line(P1), profile = PricingProfile.PANEL, buyer = rich, useCredits = 5000, method = plain, strict = true)
        assertEquals(PricingCode.MIXED_CREDIT_NOT_SUPPORTED, strict.tender.unavailable)
    }

    // ================================================================ the seeded loop of the price override

    private fun big(v: Long) = BigInteger.valueOf(v)

    private fun halfUpDiv(n: BigInteger, d: BigInteger): BigInteger = n.shiftLeft(1).add(d).divide(d.shiftLeft(1))

    private fun roundQ(n: BigInteger, d: BigInteger, q: Long): Long = halfUpDiv(n, d.multiply(big(q))).multiply(big(q)).longValueExact()

    /** Largest-remainder allocation written from 05 section 6.2: floor shares, leftover quanta to the largest remainders, ties to the lower index. */
    private fun allocate(amount: Long, weights: List<Long>, q: Long): List<Long> {
        val units = big(amount / q)
        val whole = big(weights.sum())
        if (units.signum() == 0) return weights.map { 0L }
        val floors = weights.map { units.multiply(big(it)).divide(whole) }
        val remainders = weights.map { units.multiply(big(it)).mod(whole) }
        var leftover = units.subtract(floors.fold(BigInteger.ZERO) { a, b -> a.add(b) }).toInt()
        val shares = floors.toMutableList()
        for (i in weights.indices.sortedWith(compareByDescending<Int> { remainders[it] }.thenBy { it })) {
            if (leftover == 0) break
            shares[i] = shares[i].add(BigInteger.ONE)
            leftover--
        }
        return shares.map { it.multiply(big(q)).longValueExact() }
    }

    @Test
    fun `property loop over 3000 seeded panel orders, a price override is exact, spread by largest remainder and never above the list`() {
        val rnd = Random(20261012)
        val seen = java.util.TreeMap<String, Int>()
        fun hit(b: String) = seen.merge(b, 1) { a, c -> a + c }
        val pool = listOf(P1, P2, P3, P4, P5, P5M, P6, P8, P9, creditTshirt)
        repeat(3000) { n ->
            val multi = rnd.nextInt(4) == 0
            val inclusive = rnd.nextBoolean()
            val cfg = config(
                mode = if (multi) CurrencyMode.MULTI else CurrencyMode.SINGLE, includeVat = inclusive, removeCents = rnd.nextInt(4) == 0,
                vatBp = listOf(2000L, 1000L, 0L, 1800L, 10_000L)[rnd.nextInt(5)]
            )
            val currency = if (multi) listOf(null, "USD", "JPY")[rnd.nextInt(3)] else null
            val lines = (1..1 + rnd.nextInt(4)).map { i ->
                val p = pool[rnd.nextInt(pool.size)]
                val variant = if (p === P4) rnd.nextLong(0, 3) else 0L
                line(p, 1 + rnd.nextInt(5), key = "k$i", variantId = variant, basePrice = if (variant == 2L) 27500 else p.price)
            }.toTypedArray()
            val list = price(*lines, config = cfg, profile = PricingProfile.PANEL, currency = currency)
            val oq = list.conversions.oq
            val tops = list.lines.filter { it.parentLineKey == null }
            val bps = tops.map { l -> (lines.single { it.lineKey == l.lineKey }.vatBp ?: cfg.vatBp).coerceIn(0L, 10_000L) }
            val gross = tops.mapIndexed { i, l ->
                val net = l.listUnitPrice * l.quantity
                if (inclusive) net else net + roundQ(big(net).multiply(big(bps[i])), big(10_000), oq)
            }
            val total = gross.sum()
            val where = "order #$n ${cfg.currencyMode} $currency inclusive=$inclusive q=$oq"

            val override = when (rnd.nextInt(10)) {
                0 -> 0L
                1 -> total
                2 -> total + oq * (1 + rnd.nextInt(3)) // above the list: refused
                else -> rnd.nextLong(0, total + 1)
            }
            val amount = roundQ(big(override), BigInteger.ONE, oq)
            if (amount > total) {
                assertEquals(PricingError.PRICE_OVERRIDE_OUT_OF_RANGE, refused { full(*lines, config = cfg, profile = PricingProfile.PANEL, currency = currency, override = override) }.error, where)
                hit("refused above the list")
                return@repeat
            }
            val r = full(
                *lines, config = cfg, profile = PricingProfile.PANEL, currency = currency, override = override, shipping = 4990, method = METHOD_F,
                discounts = listOf(D1)
            )
            val shares = allocate(amount, gross, oq)
            val got = r.lines.filter { it.parentLineKey == null }
            assertEquals(shares, got.map { it.lineTotal }, "$where lineTotal")
            assertEquals(amount, got.sumOf { it.lineTotal }, "$where the lines add up to the override")
            assertEquals(amount, r.total, "$where total")
            assertEquals(amount, r.gatewayAmount, "$where gateway")
            assertEquals(0L, r.shippingTotal, "$where shipping")
            assertEquals(0L, r.paymentFee, "$where fee")
            assertEquals(0L, r.creditAmount, where)
            assertTrue(r.items.discountRedemptions.isEmpty() && r.upgradeDiscount == 0L && r.couponDiscount == 0L, "$where nothing but the override")
            var vatSum = 0L
            for ((i, l) in got.withIndex()) {
                assertTrue(l.lineTotal <= gross[i] && l.lineTotal % oq == 0L, "$where line ${l.lineKey} within its list and on the quantum")
                val vat = roundQ(big(l.lineTotal).multiply(big(bps[i])), big(10_000 + bps[i]), oq)
                assertEquals(vat, l.vatAmount, "$where line ${l.lineKey} vat")
                vatSum += vat
                val basis = if (inclusive) l.lineTotal else l.lineTotal - vat
                assertEquals(basis, l.lineBasis, "$where line ${l.lineKey} basis")
                assertEquals(l.listUnitPrice * l.quantity - basis, l.discountAmount, "$where line ${l.lineKey} what the override took off the list")
                assertTrue(l.discountAmount >= 0, "$where line ${l.lineKey} the override never raises a price")
                assertNull(l.discountId, where)
            }
            assertEquals(vatSum, r.vatTotal, "$where vatTotal")
            assertEquals(got.sumOf { it.lineBasis }, r.items.itemsBasis, "$where basis")
            assertEquals(list.subtotal - got.sumOf { it.lineBasis }, r.discountTotal, "$where discountTotal is the list less what is charged")
            if (amount == 0L) {
                assertEquals("free", r.paymentMethodId, where)
                hit("zero override")
            }
            if (amount == total) hit("override equals the list")
            if (inclusive) hit("inclusive") else hit("exclusive")
            if (oq == 100L) hit("whole units")
            if (tops.size > 1) hit("several lines")
            if (amount in 1 until total) hit("a real reduction")
        }
        println("OVERRIDE-LOOP branches=$seen")
        for (branch in listOf("refused above the list", "zero override", "override equals the list", "inclusive", "exclusive", "whole units", "several lines", "a real reduction")) {
            assertTrue((seen[branch] ?: 0) >= 100, "the loop barely exercised '$branch': ${seen[branch]}")
        }
    }
}
