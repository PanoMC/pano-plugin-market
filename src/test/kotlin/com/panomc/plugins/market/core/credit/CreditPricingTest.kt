package com.panomc.plugins.market.core.credit

import com.panomc.plugins.market.core.money.Conversions
import com.panomc.plugins.market.core.pricing.PriceBreakdown
import com.panomc.plugins.market.core.pricing.PricingFixtures
import com.panomc.plugins.market.core.pricing.PricingFixtures.D1
import com.panomc.plugins.market.core.pricing.PricingFixtures.P1
import com.panomc.plugins.market.core.pricing.PricingFixtures.P2
import com.panomc.plugins.market.core.pricing.PricingFixtures.buyer
import com.panomc.plugins.market.core.pricing.PricingFixtures.config
import com.panomc.plugins.market.core.pricing.PricingFixtures.line
import com.panomc.plugins.market.db.model.OrderItemKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * 07 section 19.2 (U-P1 .. U-P9): the full-credit total. Figures are x 100: `10000` is 100.00 money or 100.00 credits.
 * The last tests run the same carts through the pricing engine's credit run (05 section 8.1), which is the authority for an
 * order: where no reduction applies the two must be equal to the unit.
 */
class CreditPricingTest {
    private fun product(
        creditUnitPrice: Long, qty: Int = 1, listTotal: Long = 0, discount: Long = 0, upgrade: Long = 0, coupon: Long = 0,
        kind: OrderItemKind = OrderItemKind.PRODUCT
    ) = CreditPricing.Line(kind, creditUnitPrice, qty, listTotal, discount, upgrade, coupon)

    @Test
    fun `U-P1 two lines without reductions cost the sum of credit unit price times quantity`() {
        val lines = listOf(
            product(creditUnitPrice = 1_000, qty = 2, listTotal = 2_000),
            product(creditUnitPrice = 2_500, qty = 1, listTotal = 3_000)
        )
        assertEquals(2_000L, CreditPricing.lineCredits(lines[0]))
        assertEquals(2_500L, CreditPricing.lineCredits(lines[1]))
        assertEquals(4_500L, CreditPricing.itemsCredits(lines))
        assertEquals(4_500L, CreditPricing.total(lines, 0).total)
    }

    @Test
    fun `U-P2 an automatic discount of a quarter of the money price takes a quarter off the credit price`() {
        // list 100.00, discount 25.00, credit unit price 400.00 -> 300.00
        assertEquals(30_000L, CreditPricing.lineCredits(product(creditUnitPrice = 40_000, listTotal = 10_000, discount = 2_500)))
    }

    @Test
    fun `U-P3 discount upgrade and coupon share all count in the ratio`() {
        // list 200.00: discount 10.00 + upgrade 20.00 + coupon 10.00 = 40.00 off -> 80 % of 500.00 = 400.00
        assertEquals(
            40_000L,
            CreditPricing.lineCredits(product(creditUnitPrice = 50_000, listTotal = 20_000, discount = 1_000, upgrade = 2_000, coupon = 1_000))
        )
        // each of the three matters on its own
        assertEquals(45_000L, CreditPricing.lineCredits(product(50_000, listTotal = 20_000, discount = 2_000)))
        assertEquals(45_000L, CreditPricing.lineCredits(product(50_000, listTotal = 20_000, upgrade = 2_000)))
        assertEquals(45_000L, CreditPricing.lineCredits(product(50_000, listTotal = 20_000, coupon = 2_000)))
        // the reduction never exceeds the list price
        assertEquals(0L, CreditPricing.lineCredits(product(50_000, listTotal = 20_000, discount = 30_000, coupon = 30_000)))
    }

    @Test
    fun `U-P4 a money price of zero has no ratio and costs the full credit price`() {
        assertEquals(15_000L, CreditPricing.lineCredits(product(creditUnitPrice = 5_000, qty = 3, listTotal = 0)))
        // a reduction on a free line cannot exist, and is ignored rather than dividing by zero
        assertEquals(15_000L, CreditPricing.lineCredits(product(creditUnitPrice = 5_000, qty = 3, listTotal = 0, coupon = 100)))
    }

    @Test
    fun `U-P5 a line a coupon takes to zero costs zero credits and the other line is untouched`() {
        val free = product(creditUnitPrice = 8_000, listTotal = 4_000, coupon = 4_000)
        val paid = product(creditUnitPrice = 6_000, listTotal = 3_000)
        assertEquals(0L, CreditPricing.lineCredits(free))
        assertEquals(6_000L, CreditPricing.itemsCredits(listOf(free, paid)))
    }

    @Test
    fun `U-P6 every line is rounded half up once and the total is the sum of the rounded lines`() {
        // 10.00 credits at two thirds of the money price: 6.6666 -> 6.67 per line
        val a = product(creditUnitPrice = 1_000, listTotal = 300, discount = 100)
        val b = product(creditUnitPrice = 1_000, listTotal = 300, discount = 100)
        assertEquals(667L, CreditPricing.lineCredits(a))
        assertEquals(1_334L, CreditPricing.itemsCredits(listOf(a, b)), "the sum of rounded lines, not the rounded sum 13.33")
        // exactly half rounds up: 0.01 credit at half the money price is 0.005
        assertEquals(1L, CreditPricing.lineCredits(product(creditUnitPrice = 1, listTotal = 200, discount = 100)))
        // 33.33 credits at a third off: exact
        assertEquals(2_222L, CreditPricing.lineCredits(product(creditUnitPrice = 3_333, listTotal = 300, discount = 100)))
        // the quantity is part of the one rounding: 3 x 10.00 at two thirds is 20.00, not 3 x 6.67
        assertEquals(2_000L, CreditPricing.lineCredits(product(creditUnitPrice = 1_000, qty = 3, listTotal = 900, discount = 300)))
    }

    @Test
    fun `U-P7 shipping is converted at the credit value and rounded up`() {
        // 12.50 at 0.10 per credit = 125.00 credits
        assertEquals(12_500L, CreditPricing.shippingCredits(1_250, 10))
        // 12.51 at 1.00 per credit = 12.51 credits
        assertEquals(1_251L, CreditPricing.shippingCredits(1_251, 100))
        // a fraction of a credit cent rounds up, never down to nothing
        assertEquals(1L, CreditPricing.shippingCredits(1, 300)) // 0.01 at 3.00 per credit = 0.0033 credits
        assertEquals(4L, CreditPricing.shippingCredits(1, 30)) // 0.01 at 0.30 = 0.0333 credits -> 0.04 (half up would give 0.03)
        assertEquals(0L, CreditPricing.shippingCredits(0, 30))
        // through the exchange rate like the quote does it
        assertEquals(10_000L, CreditPricing.shippingCredits(250, 100, BigDecimal("0.025")))
        // the total adds the shipping to the lines
        val total = CreditPricing.total(listOf(product(1_000, listTotal = 500)), CreditPricing.shippingCredits(1_250, 10))
        assertEquals(1_000L, total.itemsCredits)
        assertEquals(12_500L, total.shippingCredits)
        assertEquals(13_500L, total.total)
    }

    @Test
    fun `U-P8 a variant without a credit price inherits the product's and zero means not sold for credits`() {
        assertEquals(5_000L, CreditPricing.effectiveCreditPrice(5_000, null))
        assertEquals(7_500L, CreditPricing.effectiveCreditPrice(5_000, 7_500))
        assertEquals(0L, CreditPricing.effectiveCreditPrice(5_000, 0), "a variant at 0 is not sold for credits")
        assertEquals(0L, CreditPricing.effectiveCreditPrice(0, null))
        // not payable with credits unless the line is free in money anyway
        assertFalse(CreditPricing.sellableForCredits(creditUnitPrice = 0, listUnitPrice = 9_990))
        assertTrue(CreditPricing.sellableForCredits(creditUnitPrice = 1, listUnitPrice = 9_990))
        assertTrue(CreditPricing.sellableForCredits(creditUnitPrice = 0, listUnitPrice = 0), "a free line costs 0 credits")
        assertTrue(CreditPricing.sellableForCredits(creditUnitPrice = 4_000, listUnitPrice = 0), "a credits-only product")
        assertThrows(IllegalArgumentException::class.java) { CreditPricing.effectiveCreditPrice(-1, null) }
        assertThrows(IllegalArgumentException::class.java) { CreditPricing.effectiveCreditPrice(1, -1) }
    }

    @Test
    fun `U-P9 a bundle is counted once and its children cost nothing`() {
        val bundle = product(creditUnitPrice = 12_000, listTotal = 12_000, kind = OrderItemKind.BUNDLE)
        val childA = product(creditUnitPrice = 1_000, qty = 3, listTotal = 0, kind = OrderItemKind.BUNDLE_CHILD)
        val childB = product(creditUnitPrice = 4_990, qty = 1, listTotal = 0, kind = OrderItemKind.BUNDLE_CHILD)
        assertEquals(0L, CreditPricing.lineCredits(childA))
        assertEquals(12_000L, CreditPricing.itemsCredits(listOf(bundle, childA, childB)))
    }

    @Test
    fun `a credit top-up line is not payable with credits and bad figures are refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            CreditPricing.lineCredits(product(1_000, listTotal = 1_000, kind = OrderItemKind.CREDIT_TOPUP))
        }
        assertThrows(IllegalArgumentException::class.java) { product(1_000, qty = 0) }
        assertThrows(IllegalArgumentException::class.java) { product(-1, listTotal = 1) }
        assertThrows(IllegalArgumentException::class.java) { CreditPricing.total(emptyList(), -1) }
        assertEquals(0L, CreditPricing.total(emptyList(), 0).total)
    }

    @Test
    fun `no overflow at the largest figures and an impossible total is an error`() {
        // 100 000 units of 10^9 credits x 100 each: 10^16, fine
        assertEquals(10_000_000_000_000_000L, CreditPricing.lineCredits(product(100_000_000_000L, qty = 100_000, listTotal = 1)))
        assertThrows(ArithmeticException::class.java) {
            CreditPricing.lineCredits(product(Long.MAX_VALUE, qty = 2, listTotal = 1))
        }
    }

    // ---------------------------------------------------------------- the engine's credit run (05 section 8.1)

    /** The money-run figures of an engine result as lines of [CreditPricing]; [creditPrices] are the catalogue credit prices by line key. */
    private fun linesOf(bd: PriceBreakdown, creditPrices: Map<String, Long>) = bd.lines.map {
        CreditPricing.Line(
            kind = it.kind,
            creditUnitPrice = creditPrices[it.lineKey] ?: 0L,
            quantity = it.quantity,
            listTotal = Math.multiplyExact(it.listUnitPrice, it.quantity.toLong()),
            discountAmount = it.discountAmount,
            upgradeAmount = it.upgradeAmount,
            couponAmount = it.couponAmount
        )
    }

    @Test
    fun `without a reduction the engine's credit total equals the closed form to the unit`() {
        // row 49 of 05 section 17: P1 (100.00 credits) + 3 x P2 (10.00 credits) = 130.00
        val bd = PricingFixtures.full(line(P1), line(P2, qty = 3), payWithCredits = true, buyer = buyer(balance = 20_000))
        val prices = mapOf("L1" to P1.creditPrice, "L2" to P2.creditPrice)
        val credits = bd.credits!!
        assertEquals(13_000L, credits.creditTotal)
        assertEquals(credits.creditTotal, CreditPricing.total(linesOf(bd, prices), credits.shippingCredits).total)
    }

    @Test
    fun `a ten percent discount gives the same credit total in the engine and in the closed form`() {
        // row 50: credit lines 90.00 + 27.00 = 117.00
        val bd = PricingFixtures.full(
            line(P1), line(P2, qty = 3), payWithCredits = true, buyer = buyer(balance = 20_000), discounts = listOf(D1)
        )
        val prices = mapOf("L1" to P1.creditPrice, "L2" to P2.creditPrice)
        val credits = bd.credits!!
        assertEquals(11_700L, credits.creditTotal)
        assertEquals(11_700L, CreditPricing.total(linesOf(bd, prices), credits.shippingCredits).total)
    }

    @Test
    fun `the shipping of a full-credit order is the engine's figure`() {
        val box = PricingFixtures.Product(40, "Box", price = 1_000, creditPrice = 5_000, physical = true)
        for ((creditValue, shipping) in listOf(10L to 1_250L, 100L to 1_251L, 300L to 1L, 30L to 777L, 7L to 99_999L)) {
            val bd = PricingFixtures.full(
                line(box), config = config(creditValue = creditValue), shipping = shipping, payWithCredits = true,
                buyer = buyer(balance = 1_000_000_000L)
            )
            val c: Conversions = bd.conversions
            val expected = CreditPricing.shippingCredits(bd.shippingTotal, c)
            assertEquals(expected, bd.credits!!.shippingCredits, "creditValue $creditValue shipping $shipping")
            assertEquals(CreditMath.shippingCredits(bd.shippingTotal, creditValue, BigDecimal.ONE), expected)
        }
    }
}
