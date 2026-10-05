package com.panomc.plugins.market.core.pricing

import com.panomc.plugins.market.config.CurrencyMode
import com.panomc.plugins.market.core.pricing.PricingFixtures.METHOD_ADDS_TAX
import com.panomc.plugins.market.core.pricing.PricingFixtures.METHOD_CATALOG
import com.panomc.plugins.market.core.pricing.PricingFixtures.METHOD_F
import com.panomc.plugins.market.core.pricing.PricingFixtures.METHOD_G
import com.panomc.plugins.market.core.pricing.PricingFixtures.P1
import com.panomc.plugins.market.core.pricing.PricingFixtures.P2
import com.panomc.plugins.market.core.pricing.PricingFixtures.P3
import com.panomc.plugins.market.core.pricing.PricingFixtures.P4
import com.panomc.plugins.market.core.pricing.PricingFixtures.P6
import com.panomc.plugins.market.core.pricing.PricingFixtures.P9
import com.panomc.plugins.market.core.pricing.PricingFixtures.buyer
import com.panomc.plugins.market.core.pricing.PricingFixtures.config
import com.panomc.plugins.market.core.pricing.PricingFixtures.full
import com.panomc.plugins.market.core.pricing.PricingFixtures.line
import com.panomc.plugins.market.core.pricing.PricingFixtures.method
import com.panomc.plugins.market.core.pricing.PricingFixtures.price
import com.panomc.plugins.market.spi.common.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * `retender` (05 section 9.6, `POST /orders/:publicId/pay`): the item and shipping amounts of a pending order are frozen at
 * O1 and never change, only the method, the fee and the credit part do. Rows 73 and 73b of 05 section 17, then the rules
 * around them (07 section 6.4), then a seeded loop that proves `retender` agrees with `finalize` for the same tender.
 */
class RetenderTest {
    /** The pending order that [order] describes, as the pay endpoint would load it (the balance already includes the order's own hold). */
    private fun frozen(
        order: PriceBreakdown,
        balance: Long,
        currentMethodId: String? = order.paymentMethodId,
        creditItemsTotal: Long? = order.items.credit?.takeIf { it.payable }?.itemsTotal,
        mixedCreditCart: Boolean = !order.items.terms.creditGranting && !order.items.terms.hasSubscription,
        pricingMode: PricingMode = order.items.pricingMode,
        loggedIn: Boolean = true
    ) = FrozenOrder(
        conversions = order.conversions,
        pricingMode = pricingMode,
        profile = order.items.profile,
        itemsTotal = order.items.itemsTotal,
        itemsVat = order.items.itemsVat,
        shippingTotal = order.shippingTotal,
        shippingVat = order.shippingVat,
        requiresShipping = order.items.requiresShipping,
        vatBp = order.items.terms.vatBp,
        currentMethodId = currentMethodId,
        creditAmount = order.creditAmount,
        creditValue = order.creditValue,
        creditBalance = balance,
        loggedIn = loggedIn,
        creditsEnabled = order.items.terms.creditsEnabled,
        allowMixedCreditPayment = order.items.terms.allowMixedCreditPayment,
        onlyAcceptCredits = order.items.terms.onlyAcceptCredits,
        mixedCreditCart = mixedCreditCart,
        creditItemsTotal = creditItemsTotal,
        renewalFee = order.items.terms.renewalFee,
        rates = order.items.terms.rates
    )

    private fun retender(f: FrozenOrder, useCredits: Long?, method: MethodInput?) = PricingEngine.retender(f, TenderInput(useCredits, method))

    // ---------------------------------------------------------------- rows 73 and 73b

    @Test
    fun `row 73 switching to a method with a fee changes the fee and the total and leaves the lines alone`() {
        val order = full(line(P1))
        assertEquals(10000L, order.total)
        val f = frozen(order, balance = 0)
        val t = retender(f, null, METHOD_F)
        assertEquals(320L, t.paymentFee)
        assertEquals(10320L, t.total)
        assertEquals(10320L, t.gatewayAmount)
        assertEquals(10000L, t.preFee) // the frozen items, nothing re-priced
        assertEquals(f.itemsVat + f.shippingVat + 53L, t.vatTotal) // 16.67 + the 0.53 inside the fee
        assertEquals("F", t.paymentMethodId)
        assertNull(t.unavailable)
        assertTrue(t.messages.isEmpty())
    }

    @Test
    fun `row 73b the credit part follows useCredits, an absent useCredits keeps it as it is`() {
        val before = full(
            line(P1), method = METHOD_F, buyer = buyer(balance = 3000), useCredits = MixedPayment.MAX
        )
        assertEquals(3000L, before.creditAmount)
        val f = frozen(before, balance = 3000) // the hold went back into the balance the endpoint sees

        val dropped = retender(f, 0, METHOD_F)
        assertEquals(0L, dropped.creditAmount)
        assertEquals(0L, dropped.creditValue)
        assertEquals(320L, dropped.paymentFee)
        assertEquals(10320L, dropped.gatewayAmount)

        val kept = retender(f, null, METHOD_F)
        assertEquals(before.creditAmount, kept.creditAmount)
        assertEquals(before.creditValue, kept.creditValue)
        assertEquals(before.paymentFee, kept.paymentFee) // 2.33
        assertEquals(before.total, kept.total)
        assertEquals(before.gatewayAmount, kept.gatewayAmount)
        assertEquals(before.vatTotal, kept.vatTotal)

        // it is never increased silently: a bigger balance does not change a kept part
        val richer = retender(frozen(before, balance = 90_000), null, METHOD_F)
        assertEquals(3000L, richer.creditAmount)
    }

    @Test
    fun `retender gives the same tender as finalize for the same cart and the same request`() {
        val cfg = config(creditValue = 10)
        val cart = full(line(P1), line(P2, 2), config = cfg, buyer = buyer(balance = 40000))
        val items = price(line(P1), line(P2, 2), config = cfg, buyer = buyer(balance = 40000))
        val f = frozen(cart, balance = 40000)
        for (credits in listOf<Long?>(0L, 1L, 500L, 30000L, 40000L)) {
            for (m in listOf(METHOD_F, METHOD_G, method("plain"))) {
                val fin = PricingEngine.finalize(items, null, TenderInput(credits, m, strict = true))
                val re = retender(f, credits, m)
                assertEquals(fin.tender, re, "credits $credits method ${m.id}")
            }
        }
    }

    // ---------------------------------------------------------------- the credit part

    @Test
    fun `the credit part can grow from nothing and is bounded like at checkout`() {
        val order = full(line(P1), method = METHOD_F, buyer = buyer(balance = 5000))
        val f = frozen(order, balance = 5000)
        val grown = retender(f, 2000, METHOD_F)
        assertEquals(2000L, grown.creditAmount)
        assertEquals(2000L, grown.creditValue)
        assertEquals(5000L, grown.credits!!.balance)
        // the balance is the limit; a number above it is refused, never clamped (07 section 6.4)
        val above = retender(f, 9000, METHOD_F)
        assertEquals(0L, above.creditAmount)
        assertTrue(above.messages.any { it.code == PricingCode.INSUFFICIENT_CREDITS })
        assertEquals(5000L, above.credits!!.maxApplicable)
        // the maximum of a mixed payment keeps what the gateway needs
        val rich = retender(frozen(order, balance = 99_999), 99_000, METHOD_F)
        assertTrue(rich.messages.any { it.code == PricingCode.INSUFFICIENT_CREDITS })
        assertEquals(9999L, rich.credits!!.maxApplicable)
        assertEquals(9999L, retender(frozen(order, balance = 99_999), 9999, METHOD_F).creditAmount)
        // MAX is a quote value, never a pay value
        val e = assertThrows(PricingException::class.java) { retender(f, MixedPayment.MAX, METHOD_F) }
        assertEquals(PricingError.INVALID_INPUT, e.error)
    }

    @Test
    fun `a method that cannot take credits is refused while credits are held and nothing is asked`() {
        val order = full(line(P1), method = METHOD_F, buyer = buyer(balance = 3000), useCredits = 3000)
        val f = frozen(order, balance = 3000)
        val nomix = method("nomix", mixedCredit = false)
        val t = retender(f, null, nomix)
        assertEquals(PricingCode.MIXED_CREDIT_NOT_SUPPORTED, t.unavailable)
        // dropping the credits explicitly makes the switch possible
        val ok = retender(f, 0, nomix)
        assertNull(ok.unavailable)
        assertEquals(0L, ok.creditAmount)
        // and a request for credits on that method is refused the same way
        assertEquals(PricingCode.MIXED_CREDIT_NOT_SUPPORTED, retender(f, 1000, nomix).unavailable)
    }

    @Test
    fun `the provider minimum of the new method keeps its remainder out of the credits`() {
        val order = full(line(P1), method = METHOD_F, buyer = buyer(balance = 50_000))
        val f = frozen(order, balance = 50_000)
        val strict = method("big", providerMin = Money(500, "TRY"))
        val t = retender(f, 9500, strict)
        assertEquals(9500L, t.creditAmount)
        assertEquals(500L, t.gatewayAmount)
        assertTrue(retender(f, 9501, strict).messages.any { it.code == PricingCode.INSUFFICIENT_CREDITS })
    }

    // ---------------------------------------------------------------- the credits method

    @Test
    fun `paying a gateway order with credits re-runs the credit total and drops the fee`() {
        val order = full(line(P1), line(P2, 3), method = METHOD_F, buyer = buyer(balance = 20_000))
        assertEquals(13000L, order.credits!!.creditTotal) // 100.00 + 3 x 10.00, the credit run of the order
        val f = frozen(order, balance = 20_000)
        val t = retender(f, null, method("credits"))
        assertEquals(13000L, t.creditAmount)
        assertEquals(order.items.itemsTotal, t.creditValue) // the money value of the frozen items
        assertEquals(0L, t.paymentFee)
        assertEquals(0L, t.gatewayAmount)
        assertEquals("credits", t.paymentMethodId)
        assertEquals(t.total, t.creditValue)
        assertNull(t.unavailable)
        // short of credits
        val short = retender(frozen(order, balance = 12_999), null, method("credits"))
        assertTrue(short.messages.any { it.code == PricingCode.INSUFFICIENT_CREDITS })
        assertEquals(0L, short.creditAmount)
        assertEquals("credits", short.paymentMethodId)
        // a cart that has no credit price, a guest, credits switched off
        assertEquals(PricingCode.NOT_PAYABLE_WITH_CREDITS, retender(frozen(order, 20_000, creditItemsTotal = null), null, method("credits")).unavailable)
        assertEquals(PricingCode.LOGIN_REQUIRED, retender(frozen(order, 20_000, loggedIn = false), null, method("credits")).unavailable)
    }

    @Test
    fun `the shipping of a pending order is converted to credits and stays frozen`() {
        val tshirt = PricingFixtures.Product(14, "T-shirt for credits", 25000, 25000, listOf(3), physical = true)
        val order = full(line(tshirt), config = config(creditValue = 30), shipping = 2990, buyer = buyer(balance = 99_999), method = METHOD_F)
        val f = frozen(order, balance = 99_999)
        val t = retender(f, null, method("credits"))
        assertEquals(9967L, t.credits!!.shippingCredits)
        assertEquals(25000L + 9967L, t.creditAmount)
        assertEquals(order.items.itemsTotal + order.shippingTotal, t.preFee)
        // a gateway method still pays the frozen shipping and its fee is on the whole
        val gw = retender(f, null, METHOD_G)
        assertEquals(order.items.itemsTotal + order.shippingTotal, gw.preFee)
        assertEquals(order.shippingVat, f.shippingVat)
    }

    @Test
    fun `leaving a full-credit order for a gateway drops the credits unless useCredits says otherwise`() {
        val order = full(line(P1), payWithCredits = true, buyer = buyer(balance = 20_000))
        assertEquals("credits", order.paymentMethodId)
        val f = frozen(order, balance = 20_000)
        val gateway = retender(f, null, METHOD_F)
        assertEquals(0L, gateway.creditAmount)
        assertEquals(0L, gateway.creditValue)
        assertEquals(320L, gateway.paymentFee)
        assertEquals(10320L, gateway.gatewayAmount)
        val mixed = retender(f, 4000, METHOD_F)
        assertEquals(4000L, mixed.creditAmount)
        assertEquals(10000L - 4000L + mixed.paymentFee, mixed.gatewayAmount)
        // the same method again keeps the credit part
        assertEquals(order.creditAmount, retender(f, null, method("credits")).creditAmount)
    }

    // ---------------------------------------------------------------- the pricing mode

    @Test
    fun `an order cannot move to a method that prices differently`() {
        val market = full(line(P1))
        assertEquals(PricingCode.EXTERNAL_PRICING, retender(frozen(market, 0), null, METHOD_CATALOG).unavailable)
        assertEquals(PricingCode.EXTERNAL_PRICING, retender(frozen(market, 0), null, METHOD_ADDS_TAX).unavailable)
        val external = full(line(P1), method = METHOD_CATALOG)
        assertEquals(PricingCode.EXTERNAL_PRICING, retender(frozen(external, 0), null, METHOD_F).unavailable)
        // the same pricing mode is fine, and so is another method of that mode
        assertNull(retender(frozen(external, 0), null, method("other", authority = com.panomc.plugins.market.spi.payment.PriceAuthority.GATEWAY_CATALOG)).unavailable)
        // credits belong to the market's own pricing
        assertEquals(PricingCode.EXTERNAL_PRICING, retender(frozen(external, 20_000, creditItemsTotal = 10000), null, method("credits")).unavailable)
        // an order priced by the gateway never has a fee
        assertEquals(0L, retender(frozen(external, 0), null, method("fee", 290, 30, authority = com.panomc.plugins.market.spi.payment.PriceAuthority.GATEWAY_CATALOG)).paymentFee)
    }

    // ---------------------------------------------------------------- renewals

    @Test
    fun `a renewal keeps its frozen fee on a gateway and pays the items alone in credits`() {
        val renewal = full(line(P9), profile = PricingProfile.RENEWAL, renewal = RenewalCharge(3000, 87), method = METHOD_F)
        assertEquals(3000L, renewal.total)
        assertEquals(87L, renewal.paymentFee)
        val f = frozen(renewal, balance = 5000)
        // another gateway: the fee is the frozen one, never recomputed, the total is still the subscription price
        val gw = retender(f, null, METHOD_G)
        assertEquals(87L, gw.paymentFee)
        assertEquals(3000L, gw.total)
        // credits: no gateway, no fee; the items are 29.13
        val credits = retender(f.let { frozen(renewal, 5000, creditItemsTotal = 3000) }, null, method("credits"))
        assertEquals(3000L, credits.creditAmount)
        assertEquals(0L, credits.paymentFee)
        assertEquals(2913L, credits.total)
        assertEquals(2913L, credits.creditValue)
        // a renewal takes no mixed payment
        assertEquals(PricingCode.MIXED_CREDIT_NOT_SUPPORTED, retender(f, 1000, METHOD_F).unavailable)
    }

    /** 05 section 9.5 checks 2 and 6 for the methods of the seeded loop (all priced by the market, limits stated in the base currency). */
    private fun gatewayLimitReason(m: MethodInput?, t: TenderBreakdown, f: FrozenOrder): PricingCode? {
        if (m == null || t.gatewayAmount <= 0L) return null
        if (f.requiresShipping && !m.physicalGoods) return PricingCode.PHYSICAL_NOT_SUPPORTED
        val min = m.providerMin?.let { if (it.currency == f.conversions.orderCurrency) it.amount else f.conversions.toOrder(it.amount) }
        if (min != null && t.gatewayAmount < min) return PricingCode.AMOUNT_BELOW_MINIMUM
        return null
    }

    // ---------------------------------------------------------------- the selected method's limits

    @Test
    fun `a kept credit part cannot push the gateway below the limits of the method the buyer switches to`() {
        val order = full(line(P1), method = METHOD_F, buyer = buyer(balance = 50_000), useCredits = MixedPayment.MAX)
        assertEquals(9999L, order.creditAmount) // the gateway collects 0.31
        val f = frozen(order, balance = 50_000)
        val big = method("big", providerMin = Money(500, "TRY"))
        // nothing said about credits: the part stays and the new gateway cannot take 0.01 + nothing
        val kept = retender(f, null, big)
        assertEquals(9999L, kept.creditAmount)
        assertEquals(PricingCode.AMOUNT_BELOW_MINIMUM, kept.unavailable)
        // asking for credits again clamps nothing here: the new method's minimum bounds the maximum (it is refused above it)
        val asked = retender(f, 9500, big)
        assertNull(asked.unavailable)
        assertEquals(500L, asked.gatewayAmount)
        // a ceiling counts the fee: 100.00 + 3.20 against 103.00
        val ceiling = method("F", 290, 30, providerMax = Money(10300, "TRY"))
        assertEquals(PricingCode.AMOUNT_ABOVE_MAXIMUM, retender(frozen(full(line(P1)), 0), 0, ceiling).unavailable)
        // the admin window is the total before the fee and before the credits
        val window = method("w", minAmount = 10000, maxAmount = 10000)
        assertNull(retender(f, 5000, window).unavailable)
        assertEquals(PricingCode.AMOUNT_BELOW_MINIMUM, retender(f, 5000, method("w", minAmount = 10001)).unavailable)
        // goods that ship need a method that ships them, a currency the method does not know is refused
        val shipped = full(line(P4, variantId = 2, basePrice = 27500), shipping = 1000, method = method("ok", physicalGoods = true))
        assertNull(shipped.tender.unavailable)
        assertEquals(PricingCode.PHYSICAL_NOT_SUPPORTED, retender(frozen(shipped, 0), null, method("digital")).unavailable)
        assertEquals(PricingCode.CURRENCY_NOT_SUPPORTED, retender(f, 0, method("eur", providerCurrencies = setOf("EUR"))).unavailable)
        // a free order and a full-credit order ignore the method's limits
        val free = full(line(P1), coupon = PricingFixtures.KF500)
        assertNull(retender(frozen(free, 0), null, method("min", providerMin = Money(99_999, "TRY"))).unavailable)
        val paid = full(line(P1), payWithCredits = true, buyer = buyer(balance = 20_000))
        assertNull(retender(frozen(paid, 20_000), null, method("credits")).unavailable)
    }

    // ---------------------------------------------------------------- the seeded loop

    @Test
    fun `property loop over 3000 seeded pending orders, retender equals finalize and never touches the items`() {
        val rnd = Random(20261010)
        val seen = java.util.TreeMap<String, Int>()
        fun hit(b: String) = seen.merge(b, 1) { a, c -> a + c }
        val products = listOf(P1, P2, P3, P4, P6, P9)
        val methods = listOf<MethodInput?>(
            null, METHOD_F, METHOD_G, method("plain"), method("nomix", 100, 10, mixedCredit = false),
            method("bounded", 200, 0, providerMin = Money(700, "TRY")), method("fixedfee", 0, 125)
        )
        repeat(3000) { n ->
            val cfg = config(
                mode = if (rnd.nextInt(5) == 0) CurrencyMode.MULTI else CurrencyMode.SINGLE,
                includeVat = rnd.nextBoolean(), removeCents = rnd.nextInt(4) == 0,
                creditValue = listOf(100L, 10L, 30L, 250L, 1L)[rnd.nextInt(5)], mixed = rnd.nextInt(8) != 0
            )
            val currency = if (cfg.currencyMode == CurrencyMode.MULTI && rnd.nextBoolean()) listOf("USD", "JPY")[rnd.nextInt(2)] else null
            val lines = (1..1 + rnd.nextInt(3)).map { i ->
                val p = products[rnd.nextInt(products.size)]
                line(p, 1 + rnd.nextInt(3), key = "k$i", variantId = if (p === P4) rnd.nextLong(0, 3) else 0)
            }.toTypedArray()
            val balance = if (rnd.nextInt(10) == 0) 0L else rnd.nextLong(0, 300_000)
            val b = buyer(balance = balance)
            val shipping = if (rnd.nextBoolean()) rnd.nextLong(0, 5000) else null
            val items = price(*lines, config = cfg, currency = currency, buyer = b)
            val where = "order #$n"

            // the order as it was created, with a random first tender
            val m1 = methods[rnd.nextInt(methods.size)]
            val first = PricingEngine.finalize(items, shipping?.let { ShippingCharge(it, null) }, TenderInput(listOf(null, 0L, MixedPayment.MAX, rnd.nextLong(0, 100_000))[rnd.nextInt(4)], m1))
            val f = frozen(first, balance)

            // a second tender, always with an explicit credit part so that nothing is "kept"
            val m2 = methods[rnd.nextInt(methods.size)]
            val credits2 = listOf(0L, 1L, rnd.nextLong(0, 50_000), rnd.nextLong(0, 300_000))[rnd.nextInt(4)]
            val fin = PricingEngine.finalize(items, shipping?.let { ShippingCharge(it, null) }, TenderInput(credits2, m2, strict = true))
            val re = retender(f, credits2, m2)
            assertEquals(fin.tender, re, where)
            assertEquals(first.items.itemsTotal + first.shippingTotal, re.preFee, "$where the frozen amounts are the base")
            if (re.creditAmount > 0) hit("credits applied")
            if (re.messages.any { it.code == PricingCode.INSUFFICIENT_CREDITS }) hit("refused above the maximum")
            if (re.paymentFee > 0) hit("fee")
            if (shipping != null && first.items.requiresShipping) hit("shipping")
            if (re.unavailable != null) hit("unavailable")

            // with nothing said about credits the part is kept exactly (or dropped when it was a method that cannot hold them)
            val kept = retender(f, null, m2)
            if (kept.unavailable == null) {
                assertEquals(first.creditAmount, kept.creditAmount, "$where kept credit part")
                assertEquals(first.creditValue, kept.creditValue, "$where kept credit value")
                assertEquals(first.items.itemsTotal + first.shippingTotal, kept.preFee, where)
                assertEquals(kept.total, kept.gatewayAmount + kept.creditValue, where)
                if (first.creditAmount > 0) hit("credit part kept")
            } else if (kept.unavailable == PricingCode.MIXED_CREDIT_NOT_SUPPORTED) {
                assertTrue(first.creditAmount > 0 && m2 != null && !m2.mixedCredit, where)
                hit("kept part refused by the method")
            } else {
                hit("kept part leaves the gateway out of its limits")
            }
            // the chosen method must be able to take what the tender leaves for it: an independent statement of 05 section 9.5
            for ((label, t) in listOf("re" to re, "kept" to kept)) {
                val limits = gatewayLimitReason(m2, t, f)
                when (t.unavailable) {
                    null -> assertNull(limits, "$where $label: the method's limits are broken and nothing was said")
                    PricingCode.MIXED_CREDIT_NOT_SUPPORTED -> {} // the credit rule of the tender runs first
                    else -> assertEquals(limits, t.unavailable, "$where $label")
                }
                if (limits != null && t.unavailable == limits) hit("method limits refused")
            }
            // nothing a retender returns can contradict the identities of stage C
            for (t in listOf(re, kept)) {
                assertEquals(t.preFee + t.paymentFee, t.total, where)
                assertEquals(t.total, t.gatewayAmount + t.creditValue, where)
                assertTrue(t.gatewayAmount >= 0 && t.creditValue >= 0 && t.creditAmount >= 0, where)
                if (t.creditAmount > 0) assertTrue(t.gatewayAmount > 0, "$where a mixed order leaves the gateway something")
            }
        }
        println("RETENDER-LOOP branches=$seen")
        for (branch in listOf("credits applied", "refused above the maximum", "fee", "shipping", "credit part kept", "unavailable", "method limits refused", "kept part leaves the gateway out of its limits")) {
            assertTrue((seen[branch] ?: 0) >= 100, "the loop barely exercised '$branch': ${seen[branch]}")
        }
    }
}
