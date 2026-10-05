package com.panomc.plugins.market.core.credit

import com.panomc.plugins.market.core.credit.Cashback.Item
import com.panomc.plugins.market.core.credit.Cashback.OrderFacts
import com.panomc.plugins.market.core.credit.Cashback.Settings
import com.panomc.plugins.market.core.pricing.LineKind
import com.panomc.plugins.market.core.pricing.MixedPayment
import com.panomc.plugins.market.core.pricing.OrderValues
import com.panomc.plugins.market.core.pricing.PricingFixtures
import com.panomc.plugins.market.core.pricing.PricingFixtures.K25
import com.panomc.plugins.market.core.pricing.PricingFixtures.METHOD_F
import com.panomc.plugins.market.core.pricing.PricingFixtures.METHOD_G
import com.panomc.plugins.market.core.pricing.PricingFixtures.P1
import com.panomc.plugins.market.core.pricing.PricingFixtures.P2
import com.panomc.plugins.market.core.pricing.PricingFixtures.P3
import com.panomc.plugins.market.core.pricing.PricingFixtures.P5
import com.panomc.plugins.market.core.pricing.PricingFixtures.P6
import com.panomc.plugins.market.core.pricing.PricingFixtures.buyer
import com.panomc.plugins.market.core.pricing.PricingFixtures.config
import com.panomc.plugins.market.core.pricing.PricingFixtures.line
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.OrderSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.random.Random
import com.panomc.plugins.market.db.model.PricingMode as OrderPricingMode

/**
 * 07 section 19.6, cashback half (U-C1 .. U-C8): the amount, when it applies, and its reversal. Figures are x 100;
 * `Settings(true, 500, 100)` is 5 % cashback at a credit value of 1.00. The property loop compares the persisted-order
 * formula with the pricing engine's quote-time `OrderValues.cashback` over thousands of seeded orders.
 */
class CashbackTest {
    private val five = Settings(creditsEnabled = true, cashbackBp = 500, rateMinor = 100)

    private fun order(
        total: Long,
        items: List<Item>,
        fee: Long = 0,
        gateway: Long = total,
        userId: Long? = 1,
        testMode: Boolean = false,
        pricing: OrderPricingMode = OrderPricingMode.MARKET,
        source: OrderSource = OrderSource.STOREFRONT,
        fx: BigDecimal = BigDecimal.ONE
    ) = OrderFacts(userId, testMode, pricing, source, total, fee, gateway, fx, items)

    private fun product(lineTotal: Long) = Item(OrderItemKind.PRODUCT, lineTotal)

    // ---------------------------------------------------------------- 19.6 cashback

    @Test
    fun `U-C1 a gateway-only order of 100 00 at 5 percent earns 5 00 credits`() {
        assertEquals(500L, Cashback.compute(order(10_000, listOf(product(10_000))), five))
    }

    @Test
    fun `U-C2 a mixed order earns on the gateway share only and a full-credit order earns nothing`() {
        assertEquals(300L, Cashback.compute(order(10_000, listOf(product(10_000)), gateway = 6_000), five), "G 60 of 100")
        assertEquals(0L, Cashback.compute(order(10_000, listOf(product(10_000)), gateway = 0), five), "paid entirely with credits")
    }

    @Test
    fun `U-C3 shipping and the payment fee are not part of the base`() {
        // 100.00 of goods + 10.00 shipping + 2.00 fee, everything through the gateway
        assertEquals(500L, Cashback.compute(order(11_200, listOf(product(10_000)), fee = 200, gateway = 11_200), five))
        // the same with 40.00 paid in credits: share = (72.00 - 2.00) / 110.00 of the goods
        // 10000 x 7000 / 11000 x 5 % = 318.18 -> 318
        assertEquals(318L, Cashback.compute(order(11_200, listOf(product(10_000)), fee = 200, gateway = 7_200), five))
    }

    @Test
    fun `U-C4 a credit pack in the order does not earn`() {
        val items = listOf(product(5_000), Item(OrderItemKind.PRODUCT, 5_000, creditPack = true))
        assertEquals(250L, Cashback.compute(order(10_000, items), five))
        // a pack alone: nothing
        assertEquals(0L, Cashback.compute(order(10_000, listOf(Item(OrderItemKind.PRODUCT, 10_000, creditPack = true))), five))
        // bundle children and a top-up line never count, a bundle does
        val bundle = listOf(Item(OrderItemKind.BUNDLE, 6_000), Item(OrderItemKind.BUNDLE_CHILD, 0), Item(OrderItemKind.CREDIT_TOPUP, 4_000))
        assertEquals(300L, Cashback.compute(order(10_000, bundle), five))
    }

    @Test
    fun `U-C5 no cashback for a guest a test order another source gateway pricing a zero percent or credits off`() {
        val items = listOf(product(10_000))
        assertEquals(500L, Cashback.compute(order(10_000, items), five), "the baseline")
        assertEquals(0L, Cashback.compute(order(10_000, items, userId = null), five), "guest")
        assertEquals(0L, Cashback.compute(order(10_000, items, testMode = true), five), "test mode")
        for (source in listOf(OrderSource.PANEL, OrderSource.GIFT_CODE, OrderSource.EXTERNAL, OrderSource.INGAME, OrderSource.LEGACY)) {
            assertEquals(0L, Cashback.compute(order(10_000, items, source = source), five), "$source")
        }
        assertEquals(0L, Cashback.compute(order(10_000, items, pricing = OrderPricingMode.EXTERNAL), five), "external pricing")
        assertEquals(0L, Cashback.compute(order(10_000, items), Settings(true, 0, 100)), "0 percent")
        assertEquals(0L, Cashback.compute(order(10_000, items), Settings(false, 500, 100)), "credits off")
        assertEquals(0L, Cashback.compute(order(10_000, items), Settings(true, 500, 0)), "no usable credit value")
        // what does earn: a renewal, and a gateway that adds the tax
        assertEquals(500L, Cashback.compute(order(10_000, items, source = OrderSource.RENEWAL), five))
        assertEquals(500L, Cashback.compute(order(10_000, items, pricing = OrderPricingMode.EXTERNAL_TAX), five))
    }

    @Test
    fun `U-C6 the cashback is floored`() {
        // base 0.99 at 5 % = 0.0495 -> 0.04
        assertEquals(4L, Cashback.compute(order(99, listOf(product(99))), five))
        // 9.99 at 3.33 % = 0.3326 -> 0.33 (row 74 of 05 section 17)
        assertEquals(33L, Cashback.compute(order(999, listOf(product(999))), Settings(true, 333, 100)))
        // 3.50 credits for the gateway share 0.70 of row 45: total 102.33, fee 2.33, gateway 72.33
        assertEquals(350L, Cashback.compute(order(10_233, listOf(product(10_000)), fee = 233, gateway = 7_233), five))
        // nothing is rounded up
        assertEquals(0L, Cashback.compute(order(1, listOf(product(1))), Settings(true, 1, 100)))
    }

    @Test
    fun `the credit value and the exchange rate convert the base into credits`() {
        // 2.50 USD at 0.025 per base unit with a credit value of 1.00 is 100 credits, 5 % of it is 5.00
        assertEquals(500L, Cashback.compute(order(250, listOf(product(250)), fx = BigDecimal("0.025")), five))
        // a credit value of 0.10 makes ten times the credits
        assertEquals(5_000L, Cashback.compute(order(10_000, listOf(product(10_000))), Settings(true, 500, 10)))
        // more than 100 percent is clamped like every basis point value
        assertEquals(10_000L, Cashback.compute(order(10_000, listOf(product(10_000))), Settings(true, 99_999, 100)))
    }

    @Test
    fun `a fee that is the whole gateway amount leaves nothing`() {
        assertEquals(0L, Cashback.compute(order(200, listOf(product(0)), fee = 200, gateway = 200), five), "denominator 0")
        assertEquals(0L, Cashback.compute(order(10_000, listOf(product(10_000)), fee = 500, gateway = 400), five), "fee above the gateway amount")
    }

    // ---------------------------------------------------------------- 19.6 reversal

    @Test
    fun `U-C7 a refund takes back its share and the shares add up to the cashback`() {
        // cashback 5.00 on a 100.00 order
        assertEquals(250L, Cashback.reversal(500, 0, refundedTotalAfter = 5_000, totalPrice = 10_000), "half refunded")
        assertEquals(250L, Cashback.reversal(500, alreadyReversed = 250, refundedTotalAfter = 10_000, totalPrice = 10_000), "the rest at the full refund")
        assertEquals(0L, Cashback.reversal(500, alreadyReversed = 500, refundedTotalAfter = 10_000, totalPrice = 10_000), "nothing is taken twice")
        assertEquals(0L, Cashback.reversal(0, 0, 5_000, 10_000), "an order without cashback")
        // already is amount + shortfall: 250 asked first, 100 more for another partial refund (350 counted), so 150 are left at the end
        assertEquals(150L, Cashback.reversal(500, alreadyReversed = 350, refundedTotalAfter = 10_000, totalPrice = 10_000))
        // the floor loses at most a unit, and the last step catches up
        assertEquals(33L, Cashback.reversal(100, 0, 3_333, 10_000))
        // more refunded than paid (over-refund): everything, never more
        assertEquals(500L, Cashback.reversal(500, 0, 12_000, 10_000))
        assertEquals(500L, Cashback.reversal(500, 0, 0, 0), "an order without a total price is refunded whole")
        assertThrows(IllegalArgumentException::class.java) { Cashback.reversal(-1, 0, 0, 1) }
    }

    @Test
    fun `U-C8 a chargeback after a partial refund takes back only what is left`() {
        assertEquals(500L, Cashback.chargeback(500, 0))
        assertEquals(250L, Cashback.chargeback(500, 250))
        assertEquals(0L, Cashback.chargeback(500, 500))
        assertEquals(0L, Cashback.chargeback(500, 600), "never negative")
        assertEquals(0L, Cashback.chargeback(0, 0))
        assertThrows(IllegalArgumentException::class.java) { Cashback.chargeback(1, -1) }
    }

    @Test
    fun `a random sequence of refunds never takes back more than the cashback and ends exactly on it`() {
        val rnd = Random(20261107)
        repeat(3_000) { n ->
            val total = rnd.nextLong(1, 100_000_000)
            val cashback = rnd.nextLong(0, 5_000_000)
            var refunded = 0L
            var already = 0L // amount + shortfall of the reversals so far
            while (refunded < total) {
                val step = if (rnd.nextInt(4) == 0) total - refunded else rnd.nextLong(1, total - refunded + 1)
                refunded += step
                val request = Cashback.reversal(cashback, already, refunded, total)
                assertTrue(request >= 0, "#$n")
                // the user could not always pay it all: the part that was taken plus the shortfall is the whole request
                already += request
                assertTrue(already <= cashback, "#$n never more than the cashback: $already of $cashback")
                // monotone and proportional up to the floor of one unit
                if (refunded < total) assertTrue(already <= cashback * refunded / total, "#$n not ahead of the refund")
            }
            assertEquals(cashback, already, "#$n the full refund takes back exactly the cashback")
            // and a chargeback right after takes nothing more
            assertEquals(0L, Cashback.chargeback(cashback, already), "#$n")
        }
    }

    // ---------------------------------------------------------------- the quote-time formula and the persisted one agree

    @Test
    fun `property loop over 4000 engine orders gives the same cashback as OrderValues`() {
        val rnd = Random(20261108)
        val catalog = listOf(P1, P2, P3, P5, P6)
        val methods = listOf(null, METHOD_F, METHOD_G, PricingFixtures.method("free-of-fee"))
        val seen = java.util.TreeMap<String, Int>()
        fun hit(name: String) = seen.merge(name, 1) { a, b -> a + b }
        repeat(4_000) { n ->
            val lines = (0 until rnd.nextInt(1, 4)).map { line(catalog[rnd.nextInt(catalog.size)], qty = rnd.nextInt(1, 4), key = "k$it") }
            val cv = listOf(10L, 100L, 250L, 37L)[rnd.nextInt(4)]
            val bp = listOf(0L, 100L, 333L, 500L, 1_000L, 10_000L)[rnd.nextInt(6)]
            val method = methods[rnd.nextInt(methods.size)]
            val balance = listOf(0L, 3_000L, 50_000L, 10_000_000L)[rnd.nextInt(4)]
            val payWithCredits = rnd.nextInt(5) == 0
            val useCredits: Long? = when (rnd.nextInt(4)) { 0 -> MixedPayment.MAX; 1 -> rnd.nextLong(1, 20_000); else -> null }
            val discounts = if (rnd.nextBoolean()) listOf(PricingFixtures.D1) else emptyList()
            val coupon = if (rnd.nextInt(3) == 0) K25 else null
            val bd = PricingFixtures.full(
                *lines.toTypedArray(), config = config(creditValue = cv, cashbackBp = bp), discounts = discounts, coupon = coupon,
                buyer = buyer(balance = balance), payWithCredits = payWithCredits, useCredits = if (payWithCredits) null else useCredits,
                method = if (payWithCredits) null else method
            )
            val items = bd.lines.map { Item(it.kind, it.lineTotal, creditPack = it.lineKind == LineKind.CREDIT_PACK) }
            val facts = OrderFacts(1, false, OrderPricingMode.MARKET, OrderSource.STOREFRONT, bd.total, bd.paymentFee, bd.gatewayAmount, bd.conversions.fx, items)
            val expected = OrderValues.cashback(bd)
            val actual = Cashback.compute(facts, Settings(true, bp, cv))
            assertEquals(expected, actual, "#$n bp=$bp cv=$cv total=${bd.total} fee=${bd.paymentFee} gateway=${bd.gatewayAmount} lines=${bd.lines.map { it.lineKey to it.lineTotal }}")
            hit(if (actual > 0) "earns" else "no cashback")
            if (bd.creditAmount > 0 && bd.gatewayAmount > 0) hit("mixed")
            if (bd.paymentFee > 0) hit("with fee")
            if (bd.paymentMethodId == "credits") hit("full credit")
            if (bd.lines.any { it.lineKind == LineKind.CREDIT_PACK }) hit("with pack")
        }
        for (name in listOf("earns", "no cashback", "mixed", "with fee", "full credit", "with pack")) {
            assertTrue((seen[name] ?: 0) >= 50, "the loop must exercise '$name' at least 50 times: $seen")
        }
    }
}
