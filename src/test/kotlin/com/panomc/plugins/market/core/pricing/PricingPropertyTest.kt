package com.panomc.plugins.market.core.pricing

import com.panomc.plugins.market.config.CurrencyMode
import com.panomc.plugins.market.core.money.Rounding
import com.panomc.plugins.market.core.pricing.PricingFixtures.CR5
import com.panomc.plugins.market.core.pricing.PricingFixtures.D1
import com.panomc.plugins.market.core.pricing.PricingFixtures.D2
import com.panomc.plugins.market.core.pricing.PricingFixtures.D3
import com.panomc.plugins.market.core.pricing.PricingFixtures.D4
import com.panomc.plugins.market.core.pricing.PricingFixtures.D5
import com.panomc.plugins.market.core.pricing.PricingFixtures.D6
import com.panomc.plugins.market.core.pricing.PricingFixtures.D7
import com.panomc.plugins.market.core.pricing.PricingFixtures.K25
import com.panomc.plugins.market.core.pricing.PricingFixtures.K50P2
import com.panomc.plugins.market.core.pricing.PricingFixtures.KF20
import com.panomc.plugins.market.core.pricing.PricingFixtures.KF500
import com.panomc.plugins.market.core.pricing.PricingFixtures.KMIN
import com.panomc.plugins.market.core.pricing.PricingFixtures.METHOD_ADDS_TAX
import com.panomc.plugins.market.core.pricing.PricingFixtures.METHOD_CATALOG
import com.panomc.plugins.market.core.pricing.PricingFixtures.METHOD_F
import com.panomc.plugins.market.core.pricing.PricingFixtures.METHOD_G
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
import com.panomc.plugins.market.core.pricing.PricingFixtures.T3
import com.panomc.plugins.market.core.pricing.PricingFixtures.config
import com.panomc.plugins.market.core.pricing.PricingFixtures.line
import com.panomc.plugins.market.core.pricing.PricingFixtures.method
import com.panomc.plugins.market.core.pricing.PricingFixtures.owned
import com.panomc.plugins.market.db.model.OrderItemKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger
import kotlin.random.Random

/**
 * The property tests of 05 section 17, run through the whole of stages A to C (`priceItems` then `finalize`) on 10 000 seeded
 * random carts, independently of the oracle loops of the stage tests: only invariants are stated here, never an expected
 * figure. Each cart asserts
 *
 * 1. the identities of 05 section 7 and the invariants of 01 section 5.1, in the exact form of the invariant I8 of 17 section 6
 *    (`totalPrice = sum(lineTotal) + shippingTotal + paymentFee` and `gatewayAmount + creditValue = totalPrice`) with the sum
 *    of the rounded lines computed here, in `BigInteger`;
 * 2. no negative amount, and `lineTotal <= listUnitPrice x quantity` (plus the VAT on top when prices are exclusive);
 * 3. every amount is a multiple of the quantum (`removeCents`, JPY);
 * 4. the coupon and creator shares add up to the code discounts and never exceed a line (and `Rounding.allocate` itself,
 *    10 000 random allocations, adds up exactly and never exceeds a weight);
 * 5. determinism: the same input twice, and with shuffled `discounts`, gives an equal output;
 * 6. with `combine = false` the chosen `itemsBasis` is `min(S1, S2)` of 05 section 6.4, both computed by separate engine runs;
 * 7. `finalize(priceItems(x))` with `useCredits = 0`, no method and no fee is `itemsTotal + shippingTotal`.
 */
class PricingPropertyTest {
    private val pool: List<Product> = listOf(P1, P2, P3, P4, P5, P5M, P6, P8, P9, T1, T2, T3, Product(44, "Crate", 4500, 3000, listOf(2)))
    private val discountPool = listOf(D1, D2, D3, D4, D5, D6, D7)
    private val couponPool = listOf(K25, KF20, KF500, K50P2, KMIN)
    private val methodPool = listOf(
        METHOD_F, METHOD_G, method("plain"), method("ship", 150, 0, physicalGoods = true), method("fixed", 0, 125, physicalGoods = true),
        method("nomix", 100, 10, mixedCredit = false, physicalGoods = true), METHOD_ADDS_TAX, METHOD_CATALOG
    )

    private class Cart(val input: PricingInput, val shipping: ShippingCharge?, val method: MethodInput?, val useCredits: Long?)

    private fun withDiscounts(i: PricingInput, discounts: List<DiscountInput>) = PricingInput(
        i.config, i.now, i.profile, i.requestedCurrency, i.lines, i.buyer, discounts, i.coupon, i.creatorCode, i.pricingMode,
        i.payWithCredits, i.priceOverride, i.renewal
    )

    private fun withCodes(i: PricingInput, coupon: CouponInput?, creator: CreatorCodeInput?, combine: Boolean, discounts: List<DiscountInput>) =
        PricingInput(
            PricingConfig(
                i.config.baseCurrency, i.config.currencyMode, i.config.additionalCurrencies, i.config.multiCurrencyFallback, i.config.rates,
                i.config.vatBp, i.config.pricesIncludeVat, i.config.removeCents, i.config.minimumOrderAmount, combine,
                i.config.creditsEnabled, i.config.onlyAcceptCredits, i.config.creditValue, i.config.allowMixedCreditPayment, i.config.cashbackBp
            ),
            i.now, i.profile, i.requestedCurrency, i.lines, i.buyer, discounts, coupon, creator, i.pricingMode, i.payWithCredits,
            i.priceOverride, i.renewal
        )

    private fun randomCart(rnd: Random): Cart {
        val mode = CurrencyMode.values()[rnd.nextInt(3)]
        val cfg = config(
            mode = mode,
            removeCents = rnd.nextInt(4) == 0,
            includeVat = rnd.nextBoolean(),
            vatBp = listOf(2000L, 1000L, 0L, 1800L)[rnd.nextInt(4)],
            minimumOrder = if (rnd.nextInt(4) == 0) rnd.nextLong(0, 20_000) else 0L,
            combine = rnd.nextBoolean(),
            creditValue = listOf(100L, 10L, 30L, 250L)[rnd.nextInt(4)],
            mixed = rnd.nextInt(6) != 0,
            cashbackBp = rnd.nextLong(0, 1000)
        )
        val currency = when (mode) {
            CurrencyMode.MULTI -> listOf(null, "USD", "JPY")[rnd.nextInt(3)]
            CurrencyMode.DISPLAY -> listOf(null, "USD")[rnd.nextInt(2)]
            else -> null
        }
        val lines = (0 until 1 + rnd.nextInt(5)).map { i ->
            val p = pool[rnd.nextInt(pool.size)]
            val variant = if (p === P4) rnd.nextLong(0, 3) else 0L
            line(p, if (p.tier != null) 1 else 1 + rnd.nextInt(6), variantId = variant, key = "k$i", basePrice = if (variant == 2L) 27500 else p.price)
        }
        val discounts = discountPool.filter { rnd.nextInt(5) == 0 }
        val coupon = if (rnd.nextInt(10) < 4) couponPool[rnd.nextInt(couponPool.size)] else null
        val creator = if (rnd.nextInt(5) == 0) CR5 else null
        val loggedIn = rnd.nextInt(8) != 0
        val tiers = if (rnd.nextInt(8) == 0) listOf(owned(501, T1, rnd.nextLong(0, 20_000))) else emptyList()
        val balance = when (rnd.nextInt(4)) {
            0 -> 0L
            1 -> rnd.nextLong(0, 30_000)
            2 -> rnd.nextLong(0, 3_000_000)
            else -> -rnd.nextLong(1, 50_000)
        }
        val buyer = PricingFixtures.buyer(tiers, balance = balance, loggedIn = loggedIn, userId = if (loggedIn) 1L else null)
        val method = if (rnd.nextInt(5) == 0) null else methodPool[rnd.nextInt(methodPool.size)]
        val useCredits = when (rnd.nextInt(5)) {
            0 -> null
            1 -> 0L
            2 -> MixedPayment.MAX
            else -> rnd.nextLong(1, 100_000)
        }
        val shipping = if (rnd.nextInt(100) < 50) ShippingCharge(rnd.nextLong(0, 40_000), if (rnd.nextInt(3) == 0) rnd.nextLong(0, 3000) else null) else null
        val input = PricingFixtures.input(
            *lines.toTypedArray(), config = cfg, discounts = discounts, coupon = coupon, creatorCode = creator, currency = currency,
            buyer = buyer, mode = method?.pricingMode ?: PricingMode.MARKET
        )
        return Cart(input, shipping, method, useCredits)
    }

    @Test
    fun `property loop over 10000 seeded carts, the lines add up to the order, I8 arithmetic holds and the seven properties of 05 section 17 hold`() {
        val rnd = Random(20261014)
        val seen = java.util.TreeMap<String, Int>()
        fun hit(branch: String) = seen.merge(branch, 1) { a, b -> a + b }
        repeat(10_000) { n ->
            val cart = randomCart(rnd)
            val input = cart.input
            val tender = TenderInput(cart.useCredits, cart.method, strict = rnd.nextInt(4) == 0 && cart.useCredits != MixedPayment.MAX)
            val items = PricingEngine.priceItems(input)
            val r = PricingEngine.finalize(items, cart.shipping, tender)
            val q = r.conversions.oq
            val where = "cart #$n ${input.config.currencyMode} ${r.currency} method=${cart.method?.id}"

            // ---- 1. identities: the sum of the rounded lines, computed here in BigInteger
            var sumLines = BigInteger.ZERO
            var sumBasis = BigInteger.ZERO
            var sumList = BigInteger.ZERO
            var sumDiscount = BigInteger.ZERO
            var sumUpgrade = BigInteger.ZERO
            var sumVat = BigInteger.ZERO
            var sumCoupon = BigInteger.ZERO
            var sumCreator = BigInteger.ZERO
            for (l in r.lines) {
                sumLines += BigInteger.valueOf(l.lineTotal)
                sumBasis += BigInteger.valueOf(l.lineBasis)
                sumVat += BigInteger.valueOf(l.vatAmount)
                sumCoupon += BigInteger.valueOf(l.couponShare)
                sumCreator += BigInteger.valueOf(l.creatorShare)
                sumDiscount += BigInteger.valueOf(l.discountAmount)
                sumUpgrade += BigInteger.valueOf(l.upgradeAmount)
                if (!l.excluded) sumList += BigInteger.valueOf(l.listUnitPrice).multiply(BigInteger.valueOf(l.quantity.toLong()))
            }
            assertEquals(sumLines, BigInteger.valueOf(items.itemsTotal), "$where sum of the rounded lines = itemsTotal")
            assertEquals(sumList, BigInteger.valueOf(r.subtotal), "$where subtotal")
            assertEquals(
                sumList - sumDiscount - sumUpgrade - BigInteger.valueOf(r.couponDiscount) - BigInteger.valueOf(r.creatorDiscount), sumBasis,
                "$where sum(lineBasis) = subtotal - every discount"
            )
            // I8, form 1: totalPrice = sum(lineTotal) + shippingTotal + paymentFee
            assertEquals(
                sumLines + BigInteger.valueOf(r.shippingTotal) + BigInteger.valueOf(r.paymentFee), BigInteger.valueOf(r.total),
                "$where I8 totalPrice = sum(lineTotal) + shippingTotal + paymentFee"
            )
            // I8, form 2: gatewayAmount + creditValue = totalPrice
            assertEquals(BigInteger.valueOf(r.total), BigInteger.valueOf(r.gatewayAmount) + BigInteger.valueOf(r.creditValue), "$where I8 gatewayAmount + creditValue = totalPrice")
            assertEquals(sumVat + BigInteger.valueOf(r.shippingVat) + BigInteger.valueOf(r.tender.paymentFeeVatAmount), BigInteger.valueOf(r.vatTotal), "$where vat parts")
            if (items.pricingMode == PricingMode.MARKET) {
                val onTop = if (items.pricesIncludeVat) BigInteger.ZERO else sumVat
                assertEquals(sumBasis + onTop, sumLines, "$where itemsTotal = sum(lineBasis) (+ VAT when exclusive)")
            }

            // ---- 2. no negative amount; lineTotal <= listUnitPrice x quantity (+ VAT on top when exclusive)
            for (l in r.lines) {
                val tag = "$where line ${l.lineKey}"
                for (v in listOf(
                    l.listUnitPrice, l.unitDiscount, l.discountAmount, l.upgradeUnitAmount, l.upgradeAmount, l.unitPrice, l.lineAmount, l.couponShare,
                    l.creatorShare, l.lineBasis, l.vatAmount, l.lineTotal
                )) assertTrue(v >= 0L, "$tag negative amount $v")
                if (l.kind == OrderItemKind.BUNDLE_CHILD) {
                    assertEquals(0L, l.lineTotal, tag)
                    continue
                }
                val gross = BigInteger.valueOf(l.listUnitPrice).multiply(BigInteger.valueOf(l.quantity.toLong()))
                val ceiling = if (items.pricesIncludeVat || l.vatPercent == 0L) gross
                else gross + BigInteger.valueOf(Rounding.vatOnTop(gross.longValueExact(), l.vatPercent, q))
                assertTrue(BigInteger.valueOf(l.lineTotal) <= ceiling, "$tag lineTotal ${l.lineTotal} above $ceiling")
            }
            for (v in listOf(r.total, r.paymentFee, r.shippingTotal, r.shippingVat, r.gatewayAmount, r.creditValue, r.vatTotal, r.subtotal, r.couponDiscount, r.creatorDiscount, r.discountTotal, r.upgradeDiscount)) {
                assertTrue(v >= 0L, "$where negative order amount $v")
                // ---- 3. quantum
                assertEquals(0L, v % q, "$where $v is not a multiple of the quantum $q")
            }
            for (l in r.lines) {
                for (v in listOf(l.listUnitPrice, l.unitDiscount, l.upgradeUnitAmount, l.unitPrice, l.lineAmount, l.couponShare, l.creatorShare, l.lineBasis, l.vatAmount, l.lineTotal)) {
                    assertEquals(0L, v % q, "$where line ${l.lineKey}: $v is not a multiple of the quantum $q")
                }
            }

            // ---- 4. allocate: the shares add up to the code discounts and never exceed their line
            assertEquals(BigInteger.valueOf(r.couponDiscount), sumCoupon, "$where coupon shares add up")
            assertEquals(BigInteger.valueOf(r.creatorDiscount), sumCreator, "$where creator shares add up")
            for (l in r.lines) assertTrue(l.couponShare + l.creatorShare <= l.lineAmount, "$where line ${l.lineKey} code shares above the line")

            // ---- 5. determinism, and independence of the order the discounts arrive in
            assertEquals(items, PricingEngine.priceItems(input), "$where determinism")
            assertEquals(items, PricingEngine.priceItems(withDiscounts(input, input.discounts.shuffled(rnd))), "$where shuffled discounts")
            assertEquals(r, PricingEngine.finalize(items, cart.shipping, tender), "$where finalize determinism")

            // ---- 6. combine = false: the chosen basis is min(S1, S2)
            if (!input.config.combineDiscountsAndCoupons && (input.coupon != null || input.creatorCode != null)) {
                val s1 = PricingEngine.priceItems(withCodes(input, null, null, combine = true, discounts = input.discounts)) // automatic discounts, no code
                val s2 = PricingEngine.priceItems(withCodes(input, input.coupon, input.creatorCode, combine = true, discounts = emptyList())) // codes, no automatic discount
                assertEquals(minOf(s1.itemsBasis, s2.itemsBasis), items.itemsBasis, "$where combine=false picks min(S1, S2)")
                hit("combine=false with a code")
                if (s2.itemsBasis < s1.itemsBasis) hit("combine=false, the code scenario wins") else hit("combine=false, the discount scenario wins")
            }

            // ---- 7. no credits, no method, no fee: itemsTotal + shippingTotal
            if (!items.payWithCredits && items.pricingMode == PricingMode.MARKET) {
                val bare = PricingEngine.finalize(items, cart.shipping, TenderInput(0L, null))
                assertEquals(BigInteger.valueOf(items.itemsTotal) + BigInteger.valueOf(bare.shippingTotal), BigInteger.valueOf(bare.total), "$where finalize(priceItems(x)) bare")
                assertEquals(0L, bare.paymentFee, where)
                assertEquals(0L, bare.creditValue, where)
                hit("bare finalize")
            }

            // ---- the loop must not be vacuous
            if (r.currency != r.conversions.baseCurrency) hit("foreign order currency")
            if (input.config.removeCents) hit("removeCents")
            if (!input.config.pricesIncludeVat) hit("VAT exclusive")
            if (r.discountTotal > 0L) hit("automatic discount")
            if (r.couponDiscount > 0L) hit("coupon discount")
            if (r.creatorDiscount > 0L) hit("creator discount")
            if (r.upgradeDiscount > 0L) hit("upgrade deduction")
            if (r.paymentFee > 0L) hit("fee")
            if (r.shippingTotal > 0L) hit("shipping")
            if (r.creditAmount > 0L) hit("credits spent")
            if (r.total == 0L) hit("free order")
            if (r.lines.any { it.excluded }) hit("excluded line")
            if (r.lines.any { it.kind == OrderItemKind.BUNDLE_CHILD }) hit("bundle")
            if (items.pricingMode != PricingMode.MARKET) hit("gateway sets the price or tax")
            if (r.tender.unavailable != null) hit("method unavailable")
        }
        println("PROPERTY-TEST branches=$seen")
        for (branch in listOf(
            "combine=false with a code", "combine=false, the code scenario wins", "combine=false, the discount scenario wins", "bare finalize",
            "foreign order currency", "removeCents", "VAT exclusive", "automatic discount", "coupon discount", "creator discount",
            "upgrade deduction", "fee", "shipping", "credits spent", "free order", "bundle", "gateway sets the price or tax", "method unavailable"
        )) {
            assertTrue((seen[branch] ?: 0) >= 100, "the loop barely exercised '$branch': ${seen[branch]}")
        }
    }

    @Test
    fun `allocate adds up exactly, never exceeds a weight and keeps the quantum over 10000 seeded allocations`() {
        val rnd = Random(20261015)
        var ties = 0
        repeat(10_000) { n ->
            val q = listOf(1L, 100L)[rnd.nextInt(2)]
            val span = if (rnd.nextBoolean()) 4L else 5_000L // small weights make equal ones, the ties of the largest remainder
            val weights = (0 until 1 + rnd.nextInt(6)).map { rnd.nextLong(0, span) * q }
            val total = weights.sum()
            val amount = if (total == 0L) 0L else rnd.nextLong(0, total / q + 1) * q
            val shares = Rounding.allocate(amount, weights, q)
            assertEquals(amount, shares.sum(), "allocation #$n adds up")
            for ((i, s) in shares.withIndex()) {
                assertTrue(s in 0..weights[i], "allocation #$n share $s above weight ${weights[i]}")
                assertEquals(0L, s % q, "allocation #$n quantum")
            }
            assertEquals(shares, Rounding.allocate(amount, weights, q), "allocation #$n determinism")
            if (weights.size > 1 && weights.toSet().size < weights.size) ties++
        }
        assertTrue(ties >= 100, "only $ties allocations had equal weights")
    }
}
