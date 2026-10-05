package com.panomc.plugins.market.core.credit

import com.panomc.plugins.market.core.money.Conversions
import com.panomc.plugins.market.core.pricing.MixedPayment
import com.panomc.plugins.market.core.pricing.Tender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.random.Random

/**
 * 07 section 19.1 (U-M1 .. U-M7) and the pieces around the credit arithmetic: the fixed use of each rounding direction,
 * the agreement with the pricing engine's own conversions, an independent integer oracle over a seeded loop, the credit
 * amount parser, and a scan that keeps floating point out of `core.credit`.
 *
 * Amounts are x 100: `bd("0.10")` is a rate of 0.10 money per credit, which is `rateMinor = 10`.
 */
class CreditMathTest {
    private fun bd(s: String) = BigDecimal(s)
    private val one = BigDecimal.ONE
    private val HALF_UP = RoundingMode.HALF_UP
    private val FLOOR = RoundingMode.FLOOR
    private val CEILING = RoundingMode.CEILING

    // ---------------------------------------------------------------- 19.1

    @Test
    fun `U-M1 1000 credits at 0 10 are 100 00 TRY`() {
        assertEquals(10_000L, CreditMath.valueOf(100_000, 10, one, "TRY", HALF_UP))
    }

    @Test
    fun `U-M2 333 33 credits at 0 03 are 10 00 half up and 9 99 floor`() {
        assertEquals(1_000L, CreditMath.valueOf(33_333, 3, one, "TRY", HALF_UP)) // 9.9999 -> 10.00
        assertEquals(999L, CreditMath.valueOf(33_333, 3, one, "TRY", FLOOR))
        assertEquals(1_000L, CreditMath.valueOf(33_333, 3, one, "TRY", CEILING))
    }

    @Test
    fun `U-M3 zero decimal currency rounds to whole units and the result is a multiple of 100`() {
        // 15 credits at 1.5 = 22.5 JPY: half up 23 -> stored 2300
        assertEquals(2_300L, CreditMath.valueOf(1_500, 150, one, "JPY", HALF_UP))
        assertEquals(2_200L, CreditMath.valueOf(1_500, 150, one, "JPY", FLOOR))
        assertEquals(2_300L, CreditMath.valueOf(1_500, 150, one, "JPY", CEILING))
        for (credits in listOf(1L, 99, 100, 101, 12_345, 99_999)) {
            for (mode in listOf(HALF_UP, FLOOR, CEILING)) {
                assertEquals(0L, CreditMath.valueOf(credits, 150, one, "JPY", mode) % 100, "$credits $mode")
            }
        }
    }

    @Test
    fun `U-M4 10 00 at 0 03 is 333 33 credits floor and 333 34 ceiling`() {
        assertEquals(33_333L, CreditMath.creditsFor(1_000, 3, one, FLOOR))
        assertEquals(33_334L, CreditMath.creditsFor(1_000, 3, one, CEILING))
        assertEquals(33_333L, CreditMath.creditsFor(1_000, 3, one, HALF_UP))
    }

    @Test
    fun `U-M5 the exchange rate is applied and the round trip is exact`() {
        // 100 credits at 1.00 base, 0.025 order per base: 2.50
        assertEquals(250L, CreditMath.valueOf(10_000, 100, bd("0.025"), "USD", HALF_UP))
        assertEquals(10_000L, CreditMath.creditsFor(250, 100, bd("0.025"), HALF_UP))
        assertEquals(10_000L, CreditMath.creditsFor(250, 100, bd("0.025"), FLOOR))
        assertEquals(10_000L, CreditMath.creditsFor(250, 100, bd("0.025"), CEILING))
    }

    @Test
    fun `U-M6 a rate of zero is refused`() {
        assertThrows(IllegalArgumentException::class.java) { CreditMath.valueOf(100, 0, one, "TRY", HALF_UP) }
        assertThrows(IllegalArgumentException::class.java) { CreditMath.creditsFor(100, 0, one, FLOOR) }
        assertThrows(IllegalArgumentException::class.java) { CreditMath.valueOf(100, -5, one, "TRY", HALF_UP) }
        assertThrows(IllegalArgumentException::class.java) { CreditMath.valueOf(100, 10, BigDecimal.ZERO, "TRY", HALF_UP) }
        assertThrows(IllegalArgumentException::class.java) { CreditMath.creditsFor(100, 10, bd("-1"), FLOOR) }
    }

    @Test
    fun `U-M7 1 000 000 000 credits at a rate of 1 000 000 do not overflow`() {
        val credits = 100_000_000_000L // 1e9 credits x 100
        val rateMinor = 100_000_000L // rate 1 000 000 x 100
        // 1e17: the BigDecimal path is exact, the product 1e19 would already overflow a Long
        assertEquals(100_000_000_000_000_000L, CreditMath.valueOf(credits, rateMinor, one, "TRY", HALF_UP))
        assertEquals(credits, CreditMath.creditsFor(100_000_000_000_000_000L, rateMinor, one, FLOOR))
        // a result that does not fit is an error, never a wrong amount
        assertThrows(ArithmeticException::class.java) { CreditMath.valueOf(Long.MAX_VALUE, 1_000_000_000, one, "TRY", HALF_UP) }
    }

    // ---------------------------------------------------------------- guards and units

    @Test
    fun `only half up floor and ceiling are accepted and negatives are refused`() {
        for (mode in listOf(RoundingMode.HALF_EVEN, RoundingMode.HALF_DOWN, RoundingMode.UNNECESSARY)) {
            assertThrows(IllegalArgumentException::class.java, { CreditMath.valueOf(100, 10, one, "TRY", mode) }, "$mode")
            assertThrows(IllegalArgumentException::class.java, { CreditMath.creditsFor(100, 10, one, mode) }, "$mode")
        }
        assertThrows(IllegalArgumentException::class.java) { CreditMath.valueOf(-1, 10, one, "TRY", HALF_UP) }
        assertThrows(IllegalArgumentException::class.java) { CreditMath.creditsFor(-1, 10, one, HALF_UP) }
    }

    @Test
    fun `the unit is 100 for a zero decimal currency and for a store that removes cents`() {
        assertEquals(1L, CreditMath.unit("TRY"))
        assertEquals(100L, CreditMath.unit("JPY"))
        assertEquals(100L, CreditMath.unit("TRY", removeCents = true))
        // removeCents rounds the value to whole units like the engine's quantum
        assertEquals(1_000L, CreditMath.valueOf(10_040, 10, one, "TRY", HALF_UP, removeCents = true)) // 10.04 -> 10.00
        assertEquals(1_004L, CreditMath.valueOf(10_040, 10, one, "TRY", HALF_UP))
    }

    @Test
    fun `zero in is zero out`() {
        assertEquals(0L, CreditMath.valueOf(0, 10, one, "TRY", CEILING))
        assertEquals(0L, CreditMath.creditsFor(0, 10, one, CEILING))
    }

    // ---------------------------------------------------------------- the fixed direction of each use (07 section 2 table)

    @Test
    fun `each use has its own fixed rounding direction`() {
        // mixed value: half up. 333.33 credits at 0.03 = 9.9999
        assertEquals(1_000L, CreditMath.mixedValue(33_333, 3, one, "TRY"))
        // the most credits a value can pay: floor
        assertEquals(33_333L, CreditMath.maxCredits(1_000, 3, one))
        // shipping: ceiling, and nothing for no shipping
        assertEquals(33_334L, CreditMath.shippingCredits(1_000, 3, one))
        assertEquals(0L, CreditMath.shippingCredits(0, 3, one))
        // 0.01 of shipping at a rate of 3.00 is a third of a credit cent: rounds up to one cent, never to nothing
        assertEquals(1L, CreditMath.shippingCredits(1, 300, one))
        // a creator payout: half up, fx = 1
        assertEquals(33_333L, CreditMath.payoutCredits(1_000, 3))
        assertEquals(5L, CreditMath.payoutCredits(3, 60)) // 0.03 / 0.60 = 0.05 credits
        assertEquals(1L, CreditMath.payoutCredits(1, 200)) // 0.005 credits rounds half up to 0.01
    }

    @Test
    fun `a top-up is never free`() {
        // 0.01 credit at a rate of 0.01 is worth 0.0001: still one unit
        assertEquals(1L, CreditMath.topUpPrice(1, 1, one, "TRY"))
        // zero-decimal: one whole unit, not 0.01
        assertEquals(100L, CreditMath.topUpPrice(1, 1, one, "JPY"))
        assertEquals(100L, CreditMath.topUpPrice(1, 1, one, "TRY", removeCents = true))
        // a normal amount is the half-up value
        assertEquals(1_000L, CreditMath.topUpPrice(33_333, 3, one, "TRY"))
        assertThrows(IllegalArgumentException::class.java) { CreditMath.topUpPrice(0, 100, one, "TRY") }
    }

    // ---------------------------------------------------------------- agreement with the engine's own conversions

    @Test
    fun `the engine conversions are the same formulas`() {
        val rnd = Random(20261101)
        val currencies = listOf("TRY", "USD", "EUR", "JPY")
        val fxs = listOf("1", "0.025", "4.5", "0.0312345678", "37.123456789")
        var compared = 0
        repeat(5_000) {
            val order = currencies[rnd.nextInt(currencies.size)]
            val fx = if (rnd.nextInt(4) == 0) one else bd(fxs[rnd.nextInt(fxs.size)])
            val cv = 1L + rnd.nextLong(1, 2_000)
            val removeCents = rnd.nextBoolean()
            val c = Conversions("TRY", order, fx, cv, removeCents)
            val credits = rnd.nextLong(0, 5_000_000)
            // creditsToMoney = valueOf floor
            assertEquals(c.creditsToMoney(credits), CreditMath.valueOf(credits, cv, fx, order, FLOOR, removeCents), "creditsToMoney $credits $cv $fx $order $removeCents")
            // the mixed value = valueOf half up
            assertEquals(MixedPayment.creditValue(c, credits), CreditMath.mixedValue(credits, cv, fx, order, removeCents), "mixed $credits $cv $fx $order $removeCents")
            // moneyToCredits = creditsFor half up in the base currency
            val base = rnd.nextLong(0, 50_000_000)
            assertEquals(c.moneyToCredits(base), CreditMath.creditsFor(base, cv, one, HALF_UP), "moneyToCredits $base $cv")
            // the shipping of a full-credit order, through the base currency like finalize does it
            val shipping = rnd.nextLong(0, 50_000_000)
            assertEquals(Tender.shippingCredits(c, shipping), CreditPricing.shippingCredits(shipping, c), "shipping $shipping $cv $fx $order")
            compared++
        }
        assertEquals(5_000, compared)
    }

    // ---------------------------------------------------------------- an independent oracle (integers only)

    /** `num / den` rounded with [mode], on non-negative integers; written without BigDecimal division. */
    private fun divide(num: BigInteger, den: BigInteger, mode: RoundingMode): BigInteger = when (mode) {
        FLOOR -> num.divide(den)
        CEILING -> num.add(den).subtract(BigInteger.ONE).divide(den)
        HALF_UP -> num.multiply(BigInteger.TWO).add(den).divide(den.multiply(BigInteger.TWO))
        else -> error("mode")
    }

    private fun unscaled(x: BigDecimal): Pair<BigInteger, BigInteger> = x.unscaledValue() to BigInteger.TEN.pow(x.scale())

    @Test
    fun `property loop over 20000 seeded conversions equals an integer oracle`() {
        val rnd = Random(20261102)
        val modes = listOf(HALF_UP, FLOOR, CEILING)
        val fxs = listOf("1", "0.025", "4.5", "0.0000000001", "1234.5678901234", "0.3333333333", "99999.9999999999")
        val currencies = listOf("TRY", "USD", "JPY")
        var roundTrips = 0
        repeat(20_000) { n ->
            val credits = when (rnd.nextInt(4)) {
                0 -> rnd.nextLong(0, 1_000)
                1 -> rnd.nextLong(0, 10_000_000)
                2 -> rnd.nextLong(0, 100_000_000_000L)
                else -> 100_000_000_000L
            }
            val rate = when (rnd.nextInt(3)) {
                0 -> rnd.nextLong(1, 10)
                1 -> rnd.nextLong(1, 10_000)
                else -> rnd.nextLong(1, 100_000_000)
            }
            val fx = bd(fxs[rnd.nextInt(fxs.size)])
            val currency = currencies[rnd.nextInt(currencies.size)]
            val removeCents = rnd.nextBoolean()
            val mode = modes[rnd.nextInt(modes.size)]
            val unit = if (currency == "JPY" || removeCents) 100L else 1L
            val (fxNum, fxDen) = unscaled(fx)
            val where = "#$n credits=$credits rate=$rate fx=$fx $currency removeCents=$removeCents $mode"

            // valueOf: units = round(credits x rate x fxNum / (100 x fxDen x unit))
            val num = BigInteger.valueOf(credits).multiply(BigInteger.valueOf(rate)).multiply(fxNum)
            val den = BigInteger.valueOf(100).multiply(fxDen).multiply(BigInteger.valueOf(unit))
            val expectedValue = divide(num, den, mode).multiply(BigInteger.valueOf(unit))
            if (expectedValue.bitLength() <= 63) {
                assertEquals(expectedValue.toLong(), CreditMath.valueOf(credits, rate, fx, currency, mode, removeCents), where)
                assertEquals(0L, expectedValue.toLong() % unit, where)
            } else {
                assertThrows(ArithmeticException::class.java, { CreditMath.valueOf(credits, rate, fx, currency, mode, removeCents) }, where)
            }

            // creditsFor: round(value x 100 x fxDen / (rate x fxNum))
            val value = rnd.nextLong(0, 100_000_000_000L)
            val cNum = BigInteger.valueOf(value).multiply(BigInteger.valueOf(100)).multiply(fxDen)
            val cDen = BigInteger.valueOf(rate).multiply(fxNum)
            val expectedCredits = divide(cNum, cDen, mode)
            if (expectedCredits.bitLength() <= 63) {
                assertEquals(expectedCredits.toLong(), CreditMath.creditsFor(value, rate, fx, mode), where)
            } else {
                assertThrows(ArithmeticException::class.java, { CreditMath.creditsFor(value, rate, fx, mode) }, where)
            }

            // the floor of the credits a value buys never buys more than the value, and one cent more does
            if (divide(cNum, cDen, FLOOR).bitLength() <= 62) {
                val floorCredits = CreditMath.creditsFor(value, rate, fx, FLOOR)
                val back = BigInteger.valueOf(floorCredits).multiply(BigInteger.valueOf(rate)).multiply(fxNum)
                val limit = BigInteger.valueOf(value).multiply(BigInteger.valueOf(100)).multiply(fxDen)
                assertTrue(back <= limit, "floor never exceeds the value: $where")
                val over = BigInteger.valueOf(floorCredits + 1).multiply(BigInteger.valueOf(rate)).multiply(fxNum)
                assertTrue(over > limit, "floor is the largest: $where")
                roundTrips++
            }
        }
        assertTrue(roundTrips > 15_000, "the round-trip check must cover most draws: $roundTrips")
    }

    // ---------------------------------------------------------------- credit amounts as text or JSON numbers

    @Test
    fun `credit amounts are numbers with at most two decimals`() {
        assertEquals(0L, CreditAmounts.parse("0"))
        assertEquals(0L, CreditAmounts.parse("0.00"))
        assertEquals(1_200L, CreditAmounts.parse("12"))
        assertEquals(1_250L, CreditAmounts.parse("12.5"))
        assertEquals(1_205L, CreditAmounts.parse("12.05"))
        assertEquals(1L, CreditAmounts.parse("0.01"))
        // U-X10: three decimals, a sign, an exponent, spaces, blanks and words are not amounts
        for (bad in listOf("12.345", "0.001", "-1", "-0.01", "+5", "1e2", "1,5", " 5", "5 ", "", "abc", "NaN", "5.", ".5", "0x10", "١٢")) {
            assertNull(CreditAmounts.parse(bad), "'$bad'")
        }
    }

    @Test
    fun `a decimal with trailing zeros is accepted and an absurd one is refused without expanding it`() {
        assertEquals(1_250L, CreditAmounts.fromDecimal(bd("12.500000")))
        assertEquals(1_200L, CreditAmounts.fromDecimal(bd("1.2E+1")))
        assertEquals(10_000L, CreditAmounts.fromDecimal(bd("1E+2")))
        assertNull(CreditAmounts.fromDecimal(bd("-0.01")))
        assertNull(CreditAmounts.fromDecimal(bd("0.001")))
        assertNull(CreditAmounts.fromDecimal(bd("1E+1000000000")), "a huge exponent must be refused, not computed")
        assertNull(CreditAmounts.fromDecimal(bd("1E-1000000000")))
        assertNull(CreditAmounts.fromDecimal(bd("99999999999999999999")), "does not fit a Long x 100")
        assertEquals(9_223_372_036_854_775_800L, CreditAmounts.fromDecimal(bd("92233720368547758")), "the largest whole amount that fits")
        assertNull(CreditAmounts.fromDecimal(bd("92233720368547758.08")), "one cent above the Long range")
        // the MAX sentinel of the quote is never a real amount
        assertNull(CreditAmounts.fromDecimal(BigDecimal(MixedPayment.MAX).movePointLeft(2)))
    }

    @Test
    fun `useCredits is absent zero a number or MAX on the quote only`() {
        assertEquals(0L, CreditAmounts.parseUseCredits(null, strict = false))
        assertEquals(0L, CreditAmounts.parseUseCredits("  ", strict = true))
        assertEquals(3_000L, CreditAmounts.parseUseCredits("30", strict = true))
        assertEquals(MixedPayment.MAX, CreditAmounts.parseUseCredits("MAX", strict = false))
        assertNull(CreditAmounts.parseUseCredits("MAX", strict = true), "MAX is a quote word: 400 at checkout and /pay")
        assertNull(CreditAmounts.parseUseCredits("max", strict = false))
        assertNull(CreditAmounts.parseUseCredits("12.345", strict = false))
        assertNull(CreditAmounts.parseUseCredits("-1", strict = false))
    }

    // ---------------------------------------------------------------- no floating point anywhere in core.credit

    @Test
    fun `core credit contains no floating point`() {
        val root = File("src/main/kotlin/com/panomc/plugins/market/core/credit")
        assertTrue(root.isDirectory, "missing ${root.path}")
        val forbidden = Regex("""\b(Double|Float)\b|\.toDouble\(|\.toFloat\(|roundTo(Long|Int)\(|Math\.(round|floor|ceil)\(|\b\d+\.\d+\b""")
        var scanned = 0
        for (file in root.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            scanned++
            val code = stripCommentsAndStrings(file.readText())
            val hit = forbidden.find(code)
            assertTrue(hit == null, "${file.name}: floating point '${hit?.value}'")
        }
        assertTrue(scanned >= 8, "scanned only $scanned files")
    }

    private fun stripCommentsAndStrings(source: String): String {
        val out = StringBuilder()
        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            val next = if (i + 1 < n) source[i + 1] else ' '
            when {
                c == '/' && next == '/' -> while (i < n && source[i] != '\n') i++
                c == '/' && next == '*' -> {
                    var depth = 1
                    i += 2
                    while (i < n && depth > 0) {
                        if (source[i] == '/' && i + 1 < n && source[i + 1] == '*') { depth++; i += 2 }
                        else if (source[i] == '*' && i + 1 < n && source[i + 1] == '/') { depth--; i += 2 }
                        else i++
                    }
                }
                c == '"' && source.startsWith("\"\"\"", i) -> {
                    i += 3
                    while (i < n && !source.startsWith("\"\"\"", i)) i++
                    i += 3
                }
                c == '"' -> {
                    i++
                    while (i < n && source[i] != '"') { if (source[i] == '\\') i++; i++ }
                    i++
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}
