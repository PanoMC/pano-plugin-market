package com.panomc.plugins.market.core.credit

import com.panomc.plugins.market.core.money.Conversions
import com.panomc.plugins.market.core.pricing.MixedPayment
import com.panomc.plugins.market.core.pricing.PricingCode
import com.panomc.plugins.market.core.pricing.PricingError
import com.panomc.plugins.market.core.pricing.PricingException
import com.panomc.plugins.market.core.pricing.PricingFixtures
import com.panomc.plugins.market.core.pricing.PricingFixtures.METHOD_F
import com.panomc.plugins.market.core.pricing.PricingFixtures.P1
import com.panomc.plugins.market.core.pricing.PricingFixtures.buyer
import com.panomc.plugins.market.core.pricing.PricingFixtures.config
import com.panomc.plugins.market.core.pricing.PricingFixtures.line
import com.panomc.plugins.market.core.pricing.PricingFixtures.method
import com.panomc.plugins.market.spi.common.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.random.Random

/**
 * 07 section 19.4 (U-X1 .. U-X11) and the rows 45 to 47b of 05 section 17 (07 section 19.10 U-M1: "every vector comes from
 * rows 45-47b, single definition"). The algorithm under test is `core.pricing.MixedPayment` (05 section 8.2 is its only
 * definition); the fee and the totals of U-X6 run through the engine's `finalize`.
 *
 * Figures are x 100: 10000 is 100.00 money or 100.00 credits; `cv = 100` is a credit value of 1.00.
 */
class MixedPaymentTest {
    private fun conv(cv: Long = 100, currency: String = "TRY", fx: BigDecimal = BigDecimal.ONE, removeCents: Boolean = false) =
        Conversions("TRY", currency, fx, cv, removeCents)

    private val MAX = MixedPayment.MAX

    // ---------------------------------------------------------------- 19.4

    @Test
    fun `U-X1 useCredits absent applies nothing and the gateway takes the total`() {
        for (request in listOf(null, 0L)) {
            val r = MixedPayment.apply(conv(), 10_000, null, 5_000, request, strict = false)
            assertEquals(0L, r.applied)
            assertEquals(0L, r.appliedValue)
            assertEquals(10_000L, r.remainder)
            assertFalse(r.reduced)
            assertFalse(r.rejected)
        }
        // the maximum is still reported
        assertEquals(5_000L, MixedPayment.apply(conv(), 10_000, null, 5_000, null, strict = false).maxApplicable)
    }

    @Test
    fun `U-X2 30 credits of a 100 00 order at a rate of 1 00 are worth 30 00 and the gateway gets 70 00`() {
        val r = MixedPayment.apply(conv(), 10_000, null, 3_000, 3_000, strict = true)
        assertEquals(3_000L, r.applied)
        assertEquals(3_000L, r.appliedValue)
        assertEquals(7_000L, r.remainder)
        assertEquals(3_000L, r.maxApplicable)
        assertFalse(r.reduced)
        assertFalse(r.rejected)
    }

    @Test
    fun `U-X3 MAX on a rich balance leaves the gateway one cent`() {
        val r = MixedPayment.apply(conv(), 10_000, null, 50_000, MAX, strict = false)
        assertEquals(9_999L, r.maxApplicable)
        assertEquals(9_999L, r.applied)
        assertEquals(9_999L, r.appliedValue)
        assertEquals(1L, r.remainder)
    }

    @Test
    fun `U-X4 a provider minimum of 5 00 caps the credits at 95 00`() {
        val r = MixedPayment.apply(conv(), 10_000, 500, 50_000, MAX, strict = false)
        assertEquals(9_500L, r.maxApplicable)
        assertEquals(9_500L, r.applied)
        assertEquals(500L, r.remainder)
    }

    @Test
    fun `U-X5 a request above the maximum is clamped on the quote and refused at checkout`() {
        val quote = MixedPayment.apply(conv(), 10_000, null, 2_000, 5_000, strict = false)
        assertEquals(2_000L, quote.applied)
        assertEquals(2_000L, quote.appliedValue)
        assertTrue(quote.reduced)
        assertFalse(quote.rejected)
        val checkout = MixedPayment.apply(conv(), 10_000, null, 2_000, 5_000, strict = true)
        assertTrue(checkout.rejected, "InsufficientCredits at checkout and /pay")
        assertEquals(0L, checkout.applied, "nothing is applied then")
        assertEquals(2_000L, checkout.maxApplicable, "the 400 body reports the maximum")
        assertFalse(checkout.reduced)
        // exactly the maximum is fine, one cent more is not
        assertFalse(MixedPayment.apply(conv(), 10_000, null, 2_000, 2_000, strict = true).rejected)
        assertTrue(MixedPayment.apply(conv(), 10_000, null, 2_000, 2_001, strict = true).rejected)
    }

    @Test
    fun `U-X6 a fee of 2 percent is taken on the gateway part only`() {
        // T0 100.00, 40.00 credits applied: fee on 60.00 = 1.20, totalPrice 101.20, gatewayAmount 61.20
        val twoPercent = method("two", feePercent = 200)
        val bd = PricingFixtures.full(line(P1), useCredits = 4_000, method = twoPercent, buyer = buyer(balance = 10_000), strict = true)
        assertEquals(4_000L, bd.creditAmount)
        assertEquals(4_000L, bd.creditValue)
        assertEquals(120L, bd.paymentFee)
        assertEquals(10_120L, bd.total)
        assertEquals(6_120L, bd.gatewayAmount)
        assertEquals(bd.total, bd.gatewayAmount + bd.creditValue, "O5: gatewayAmount + creditValue = totalPrice")
        assertEquals("two", bd.paymentMethodId)
        assertTrue(bd.canCheckout)
    }

    @Test
    fun `U-X7 at a rate of 0 03 the credits are the floor and their value never exceeds the cap`() {
        val c = conv(cv = 3)
        // T0 10.01: cap 10.00 = 1000, 1000 x 100 / 3 = 33333.33 -> 33333 credits worth 999.99 -> 1000
        val r = MixedPayment.apply(c, 1_001, null, 1_000_000, MAX, strict = false)
        assertEquals(33_333L, r.maxApplicable)
        assertEquals(33_333L, r.applied)
        assertEquals(1_000L, r.appliedValue)
        assertTrue(r.appliedValue <= 1_001 - 1)
        // T0 10.00: cap 9.99 = 999 -> exactly 333.00 credits
        val exact = MixedPayment.apply(c, 1_000, null, 1_000_000, MAX, strict = false)
        assertEquals(33_300L, exact.applied)
        assertEquals(999L, exact.appliedValue)
        // a rate that never divides: 0.07
        val seven = MixedPayment.apply(conv(cv = 7), 101, null, 1_000_000, MAX, strict = false)
        assertEquals(1_428L, seven.applied) // floor(100 x 100 / 7)
        assertEquals(100L, seven.appliedValue) // 99.96 rounds to 100, the cap
    }

    @Test
    fun `U-X8 in a zero-decimal currency the cap leaves at least one whole unit and the value is whole units`() {
        // 100 JPY order (10000), credit value 1.00 base per credit and JPY 4.5 per base... use fx 1 for the plain case
        val jpy = conv(cv = 100, currency = "JPY")
        val r = MixedPayment.apply(jpy, 10_000, null, 1_000_000, MAX, strict = false)
        assertEquals(9_900L, r.appliedValue)
        assertEquals(100L, r.remainder, "one whole unit stays for the gateway")
        assertEquals(0L, r.appliedValue % 100)
        // an exchange rate and a rate that does not divide: the value is still a multiple of 100 and within the cap
        for (cv in listOf(110L, 130L, 150L, 175L, 333L)) {
            val c = conv(cv = cv, currency = "JPY", fx = BigDecimal("4.5"))
            val x = MixedPayment.apply(c, 45_000, null, 1_000_000, MAX, strict = false)
            assertEquals(0L, x.appliedValue % 100, "cv $cv")
            assertTrue(x.appliedValue <= 45_000 - 100, "cv $cv")
            assertTrue(x.remainder >= 100, "cv $cv")
        }
        // removeCents makes a TRY store whole-unit as well
        val noCents = MixedPayment.apply(conv(removeCents = true), 10_000, null, 1_000_000, MAX, strict = false)
        assertEquals(0L, noCents.appliedValue % 100)
        assertTrue(noCents.remainder >= 100)
    }

    @Test
    fun `U-X9 a total equal to the minimum remainder leaves nothing to apply`() {
        assertEquals(0L, MixedPayment.apply(conv(), 500, 500, 50_000, MAX, strict = false).maxApplicable)
        assertEquals(0L, MixedPayment.apply(conv(), 1, null, 50_000, MAX, strict = false).maxApplicable, "one cent is the minimum remainder")
        assertEquals(0L, MixedPayment.apply(conv(), 0, null, 50_000, MAX, strict = false).maxApplicable)
        // a provider minimum above the total
        val r = MixedPayment.apply(conv(), 400, 500, 50_000, MAX, strict = false)
        assertEquals(0L, r.maxApplicable)
        assertEquals(0L, r.applied)
        assertEquals(400L, r.remainder)
        // one cent above the minimum there is exactly one cent to apply
        assertEquals(1L, MixedPayment.apply(conv(), 501, 500, 50_000, MAX, strict = false).maxApplicable)
    }

    @Test
    fun `U-X10 useCredits with three decimals or a negative number is rejected`() {
        assertNull(CreditAmounts.parseUseCredits("12.345", strict = true))
        assertNull(CreditAmounts.parseUseCredits("-5", strict = false))
        // the negative that reaches the engine as a number is a caller bug
        val e = assertThrows(PricingException::class.java) { MixedPayment.apply(conv(), 10_000, null, 5_000, -1L, strict = false) }
        assertEquals(PricingError.INVALID_INPUT, e.error)
        // MAX is a quote word
        val m = assertThrows(PricingException::class.java) { MixedPayment.apply(conv(), 10_000, null, 5_000, MAX, strict = true) }
        assertEquals(PricingError.INVALID_INPUT, m.error)
    }

    @Test
    fun `U-X11 credits so small that their value rounds to nothing are not applied`() {
        // 0.40 credits at 0.01 per credit = 0.004 money: rounds to 0
        val r = MixedPayment.apply(conv(cv = 1), 10_000, null, 40, 40, strict = false)
        assertEquals(0L, r.applied)
        assertEquals(0L, r.appliedValue)
        assertEquals(10_000L, r.remainder)
        // 50 credit cents at 0.01 each are worth 0.005: half up gives one cent, so they are applied
        val half = MixedPayment.apply(conv(cv = 1), 10_000, null, 50, 50, strict = false)
        assertEquals(50L, half.applied)
        assertEquals(1L, half.appliedValue)
    }

    // ---------------------------------------------------------------- rows 45 to 47b of 05 section 17 (the same figures through the engine)

    @Test
    fun `row 45 the fee is only on the part the gateway collects`() {
        val bd = PricingFixtures.full(line(P1), useCredits = MAX, method = METHOD_F, buyer = buyer(balance = 3_000))
        assertEquals(3_000L, bd.creditAmount)
        assertEquals(3_000L, bd.creditValue)
        assertEquals(233L, bd.paymentFee)
        assertEquals(10_233L, bd.total)
        assertEquals(7_233L, bd.gatewayAmount)
    }

    @Test
    fun `row 46 credits at a credit value of 0 10`() {
        val bd = PricingFixtures.full(line(P1), config = config(creditValue = 10), useCredits = 10_000, buyer = buyer(balance = 25_000), method = method("g"))
        assertEquals(10_000L, bd.creditAmount)
        assertEquals(1_000L, bd.creditValue)
        assertEquals(9_000L, bd.gatewayAmount)
        assertEquals(25_000L, bd.credits!!.maxApplicable)
    }

    @Test
    fun `row 47 mixed credits never cover everything`() {
        val bd = PricingFixtures.full(line(P1), useCredits = MAX, method = METHOD_F, buyer = buyer(balance = 50_000))
        assertEquals(9_999L, bd.credits!!.maxApplicable)
        assertEquals(9_999L, bd.creditAmount)
        assertEquals(9_999L, bd.creditValue)
        assertEquals(30L, bd.paymentFee)
        assertEquals(10_030L, bd.total)
        assertEquals(31L, bd.gatewayAmount)
        assertEquals("F", bd.paymentMethodId, "full coverage needs payWithCredits")
        // with a provider minimum of 5.00
        val min5 = method("F", feePercent = 290, feeFixed = 30, providerMin = Money(500, "TRY"))
        val capped = PricingFixtures.full(line(P1), useCredits = MAX, method = min5, buyer = buyer(balance = 50_000))
        assertEquals(9_500L, capped.creditValue)
    }

    @Test
    fun `row 47b a number above the maximum is clamped by the quote and refused at checkout`() {
        val quote = PricingFixtures.full(line(P1), useCredits = 15_000, method = METHOD_F, buyer = buyer(balance = 50_000))
        assertEquals(9_999L, quote.creditAmount)
        assertTrue(quote.messages.any { it.code == PricingCode.CREDITS_REDUCED })
        val checkout = PricingFixtures.full(line(P1), useCredits = 15_000, method = METHOD_F, buyer = buyer(balance = 50_000), strict = true)
        assertEquals(0L, checkout.creditAmount, "never clamped at checkout")
        assertTrue(checkout.messages.any { it.code == PricingCode.INSUFFICIENT_CREDITS })
        assertFalse(checkout.canCheckout)
    }

    // ---------------------------------------------------------------- an independent oracle

    private class Expected(val max: Long, val applied: Long, val value: Long, val remainder: Long, val reduced: Boolean, val rejected: Boolean)

    /** 05 section 8.2 written again with explicit scales, sharing nothing with `MixedPayment`. */
    private fun oracle(c: Conversions, t0: Long, providerMin: Long?, balance: Long, request: Long?, strict: Boolean): Expected {
        val oq = BigInteger.valueOf(c.oq)
        val bal = BigInteger.valueOf(maxOf(0L, balance))
        // minRemainder = max(oq, ceil(providerMin to oq))
        val pm = BigInteger.valueOf(providerMin ?: 0L)
        val pmCeil = pm.add(oq).subtract(BigInteger.ONE).divide(oq).multiply(oq)
        val minRem = oq.max(pmCeil)
        val cap = BigInteger.valueOf(t0).subtract(minRem)
        val cvFx = BigDecimal.valueOf(c.creditValue).multiply(c.fx)
        val maxByTotal = if (cap.signum() <= 0) BigInteger.ZERO else
            BigDecimal(cap).multiply(BigDecimal(100)).divide(cvFx, 0, RoundingMode.FLOOR).toBigIntegerExact()
        val maxApplicable = if (cap.signum() <= 0) BigInteger.ZERO else bal.min(maxByTotal)

        val wanted = request ?: 0L
        var reduced = false
        var rejected = false
        var applied: BigInteger = when {
            wanted == 0L -> BigInteger.ZERO
            wanted == MAX -> maxApplicable
            BigInteger.valueOf(wanted) <= maxApplicable -> BigInteger.valueOf(wanted)
            strict -> { rejected = true; BigInteger.ZERO }
            else -> { reduced = true; maxApplicable }
        }
        var value = BigInteger.ZERO
        if (applied.signum() > 0) {
            val raw = BigDecimal(applied).multiply(cvFx).divide(BigDecimal(100))
            val rounded = raw.divide(BigDecimal(oq), 0, RoundingMode.HALF_UP).toBigIntegerExact().multiply(oq)
            value = rounded.min(cap)
            if (value.signum() <= 0) applied = BigInteger.ZERO
        }
        return Expected(
            maxApplicable.toLong(), applied.toLong(), value.toLong(), BigInteger.valueOf(t0).subtract(value).toLong(), reduced, rejected
        )
    }

    @Test
    fun `property loop over 20000 seeded mixed payments equals an independent oracle and keeps its invariants`() {
        val rnd = Random(20261103)
        val currencies = listOf("TRY", "USD", "JPY", "EUR")
        val fxs = listOf("1", "0.025", "4.5", "0.0312345678", "37.123456789", "1.0000000001")
        val seen = java.util.TreeMap<String, Int>()
        fun hit(branch: String) = seen.merge(branch, 1) { a, b -> a + b }
        repeat(20_000) { n ->
            val currency = currencies[rnd.nextInt(currencies.size)]
            val fx = if (rnd.nextInt(3) == 0) BigDecimal.ONE else BigDecimal(fxs[rnd.nextInt(fxs.size)])
            val cv = when (rnd.nextInt(3)) { 0 -> rnd.nextLong(1, 10); 1 -> rnd.nextLong(1, 400); else -> rnd.nextLong(1, 100_000) }
            val removeCents = rnd.nextInt(3) == 0
            val c = conv(cv, currency, fx, removeCents)
            val rawT0 = when (rnd.nextInt(4)) { 0 -> rnd.nextLong(0, 1_000); 1 -> rnd.nextLong(0, 100_000); 2 -> rnd.nextLong(0, 10_000_000); else -> rnd.nextLong(0, 10_000_000_000L) }
            val t0 = rawT0 / c.oq * c.oq // a total is always on the quantum
            val providerMin = if (rnd.nextInt(3) == 0) null else rnd.nextLong(0, maxOf(1L, t0 + 1_000))
            val balance = when (rnd.nextInt(5)) { 0 -> -rnd.nextLong(0, 5_000); 1 -> 0; 2 -> rnd.nextLong(0, 1_000); 3 -> rnd.nextLong(0, 1_000_000); else -> rnd.nextLong(0, 10_000_000_000L) }
            val strict = rnd.nextBoolean()
            val request: Long? = when (rnd.nextInt(6)) {
                0 -> null
                1 -> 0L
                2 -> if (strict) rnd.nextLong(1, 100_000) else MAX
                3 -> rnd.nextLong(1, 1_000)
                4 -> rnd.nextLong(1, 1_000_000)
                else -> rnd.nextLong(1, 10_000_000_000L)
            }
            val where = "#$n $currency fx=$fx cv=$cv removeCents=$removeCents t0=$t0 min=$providerMin balance=$balance req=$request strict=$strict"
            val got = MixedPayment.apply(c, t0, providerMin, balance, request, strict)
            val exp = oracle(c, t0, providerMin, balance, request, strict)

            assertEquals(exp.max, got.maxApplicable, "max $where")
            assertEquals(exp.applied, got.applied, "applied $where")
            assertEquals(exp.value, got.appliedValue, "value $where")
            assertEquals(exp.remainder, got.remainder, "remainder $where")
            assertEquals(exp.reduced, got.reduced, "reduced $where")
            assertEquals(exp.rejected, got.rejected, "rejected $where")

            // invariants stated without the oracle
            assertTrue(got.applied <= maxOf(0L, balance), "never more than the balance $where")
            assertTrue(got.applied <= got.maxApplicable, "never more than the maximum $where")
            assertEquals(0L, got.appliedValue % c.oq, "value on the quantum $where")
            assertEquals(t0 - got.appliedValue, got.remainder, "T0 = value + remainder $where")
            assertTrue(got.applied == 0L || got.remainder >= c.oq, "a mixed order always leaves a gateway remainder $where")
            assertEquals(got.applied > 0L, got.appliedValue > 0L, "credits and their value are both there or both absent $where")
            val minRemainder = maxOf(c.oq, (providerMin ?: 0L).let { (it + c.oq - 1) / c.oq * c.oq })
            assertTrue(got.applied == 0L || got.remainder >= minRemainder, "the provider minimum is kept $where")
            // the value never exceeds what the credits are worth, rounded up to the quantum at most
            if (got.applied > 0L) {
                val exact = BigDecimal.valueOf(got.applied).multiply(BigDecimal.valueOf(cv)).multiply(fx).divide(BigDecimal(100))
                assertTrue(BigDecimal.valueOf(got.appliedValue).subtract(exact).abs() <= BigDecimal.valueOf(c.oq).divide(BigDecimal(2)), "value within half a quantum of the worth $where")
            }

            hit(if (got.applied > 0) "applied" else "nothing applied")
            if (got.reduced) hit("clamped")
            if (got.rejected) hit("rejected")
            if (request == MAX) hit("MAX")
            if (providerMin != null && providerMin > 0) hit("provider minimum")
            if (balance < 0) hit("debt")
            if (c.oq == 100L) hit("whole units")
            if (got.maxApplicable == 0L) hit("no room")
        }
        for (branch in listOf("applied", "nothing applied", "clamped", "rejected", "MAX", "provider minimum", "debt", "whole units", "no room")) {
            assertTrue((seen[branch] ?: 0) >= 100, "the loop must exercise '$branch' at least 100 times: $seen")
        }
    }
}
