package com.panomc.plugins.market.core.money

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.random.Random

/**
 * The rounding primitives of 05 section 2. The vector rows quoted in the test names are the rows of 05 section 17
 * whose figure comes straight from one primitive; the loops compare every primitive with an exact `BigInteger`
 * reference over thousands of seeded values.
 */
class RoundingTest {
    private fun bd(s: String) = BigDecimal(s)

    // ---------------------------------------------------------------- quantum

    @Test
    fun `the quantum is 100 for zero-decimal currencies and with removeCents, 1 otherwise, always 1 for credits`() {
        assertEquals(1L, Rounding.quantum("TRY", false))
        assertEquals(1L, Rounding.quantum("USD", false))
        assertEquals(100L, Rounding.quantum("TRY", true))
        assertEquals(100L, Rounding.quantum("JPY", false))
        assertEquals(100L, Rounding.quantum("JPY", true))
        assertEquals(100L, Rounding.quantum("KRW", false))
        assertEquals(1L, Rounding.quantum(Rounding.CREDITS, false))
        assertEquals(1L, Rounding.quantum(Rounding.CREDITS, true))
        assertThrows(IllegalArgumentException::class.java) { Rounding.quantum("KWD", false) }
        assertThrows(IllegalArgumentException::class.java) { Rounding.quantum("???", true) }
    }

    // ---------------------------------------------------------------- halfUp, roundQ, floorQ

    @Test
    fun `halfUp rounds a tie away from zero upwards and never below`() {
        assertEquals(0L, Rounding.halfUp(bd("0")))
        assertEquals(0L, Rounding.halfUp(bd("0.4999999999")))
        assertEquals(1L, Rounding.halfUp(bd("0.5")))
        assertEquals(2L, Rounding.halfUp(bd("1.5")))
        assertEquals(3L, Rounding.halfUp(bd("2.5")))
        assertEquals(2L, Rounding.halfUp(bd("2.4999999999")))
        assertEquals(Long.MAX_VALUE, Rounding.halfUp(BigDecimal(Long.MAX_VALUE)))
        assertThrows(ArithmeticException::class.java) { Rounding.halfUp(BigDecimal(Long.MAX_VALUE).add(BigDecimal.ONE)) }
        assertThrows(IllegalArgumentException::class.java) { Rounding.halfUp(bd("-0.1")) }
    }

    @Test
    fun `roundQ rounds to the nearest multiple of the quantum, a tie upwards`() {
        assertEquals(1000L, Rounding.roundQ(999L, 100))
        assertEquals(1000L, Rounding.roundQ(950L, 100))
        assertEquals(900L, Rounding.roundQ(949L, 100))
        assertEquals(0L, Rounding.roundQ(49L, 100))
        assertEquals(100L, Rounding.roundQ(50L, 100))
        assertEquals(999L, Rounding.roundQ(999L, 1))
        assertEquals(1000L, Rounding.roundQ(bd("999.5"), 1)) // a rate result with a fraction
        assertEquals(4500L, Rounding.roundQ(bd("4495.5"), 100)) // 44.955 -> 45 (row 64)
        assertEquals(3L, Rounding.roundQ(bd("2.5"), 3)) // an odd quantum is just a unit
        assertEquals(0L, Rounding.roundQ(0L, 100))
        assertThrows(IllegalArgumentException::class.java) { Rounding.roundQ(5L, 0) }
        assertThrows(IllegalArgumentException::class.java) { Rounding.roundQ(-1L, 100) }
        // 92233720368547758.5 rounds up to ...759 and times 100 no longer fits a Long
        assertThrows(ArithmeticException::class.java) { Rounding.roundQ(bd("9223372036854775850"), 100) }
    }

    @Test
    fun `floorQ rounds down to a multiple of the quantum`() {
        assertEquals(900L, Rounding.floorQ(bd("999.99"), 100))
        assertEquals(1000L, Rounding.floorQ(bd("1000"), 100))
        assertEquals(0L, Rounding.floorQ(bd("99.99"), 100))
        assertEquals(66L, Rounding.floorQ(bd("66.9999"), 1))
        assertThrows(IllegalArgumentException::class.java) { Rounding.floorQ(bd("-1"), 1) }
    }

    // ---------------------------------------------------------------- pctQ, vatInside, vatOnTop (rows of section 17)

    @Test
    fun `pctQ takes a percentage with one rounding (rows 7, 41, 58)`() {
        assertEquals(100L, Rounding.pctQ(999, 1000, 1)) // row 7: 10 % of 9.99 = 0.999 -> 1.00
        assertEquals(200L, Rounding.pctQ(1000, 1500, 100)) // row 41: 15 % of 10.00 = 1.50 -> 2.00
        assertEquals(3L, Rounding.pctQ(5, 5000, 1)) // row 58: 50 % of 0.05 = 0.025 -> 0.03
        assertEquals(0L, Rounding.pctQ(0, 5000, 1))
        assertEquals(0L, Rounding.pctQ(12345, 0, 1))
        assertEquals(12345L, Rounding.pctQ(12345, 10000, 1))
        assertEquals(12300L, Rounding.pctQ(12345, 10000, 100)) // whole units: 123.45 -> 123.00
        assertThrows(IllegalArgumentException::class.java) { Rounding.pctQ(100, 10001, 1) }
        assertThrows(IllegalArgumentException::class.java) { Rounding.pctQ(100, -1, 1) }
        assertThrows(IllegalArgumentException::class.java) { Rounding.pctQ(-100, 1000, 1) }
    }

    @Test
    fun `vatInside is the VAT contained in a gross amount (rows 1, 2, 5, 37, 64)`() {
        assertEquals(1667L, Rounding.vatInside(10000, 2000, 1)) // row 1: 100.00 at 20 % -> 16.67
        assertEquals(500L, Rounding.vatInside(2997, 2000, 1)) // row 2: 4.995 rounds up to 5.00
        assertEquals(454L, Rounding.vatInside(4990, 1000, 1)) // row 5: Sword at 10 % -> 4.54
        assertEquals(4583L, Rounding.vatInside(27500, 2000, 1)) // row 37: 45.83
        assertEquals(498L, Rounding.vatInside(2990, 2000, 1)) // row 37: shipping 4.98
        assertEquals(800L, Rounding.vatInside(4500, 2000, 100)) // row 64: 7.5 JPY rounds up to 8 JPY
        assertEquals(0L, Rounding.vatInside(10000, 0, 1))
        assertEquals(5000L, Rounding.vatInside(10000, 10000, 1)) // 100 % VAT: half of the gross
    }

    @Test
    fun `vatOnTop is VAT added to a net amount (rows 3, 4, 6, 42)`() {
        assertEquals(2000L, Rounding.vatOnTop(10000, 2000, 1)) // row 3
        assertEquals(599L, Rounding.vatOnTop(2997, 2000, 1)) // row 4: 5.994 rounds down
        assertEquals(998L, Rounding.vatOnTop(9980, 1000, 1)) // row 6: net 99.80 at 10 %
        assertEquals(500L, Rounding.vatOnTop(5000, 1000, 100)) // row 42: removeCents, 5.00
    }

    @Test
    fun `a non-terminating quotient is rounded once, not after a truncated division`() {
        // 7 * 2000 / 12000 = 1.1666..., 1 * 1 / 3 = 0.333...: the exact half-up result, no matter how long the expansion
        assertEquals(1L, Rounding.vatInside(7, 2000, 1))
        assertEquals(0L, Rounding.ratioQ(bd("1"), bd("3"), 1))
        assertEquals(1L, Rounding.ratioQ(bd("1.5"), bd("3"), 1)) // exactly 0.5: up
        assertEquals(1L, Rounding.ratioQ(bd("2"), bd("3"), 1))
        assertEquals(100L, Rounding.ratioQ(bd("50"), bd("1"), 100))
        assertEquals(0L, Rounding.ratioQ(bd("49.999999999999999999999999"), bd("1"), 100))
    }

    @Test
    fun `every primitive agrees with an exact BigInteger reference over seeded values`() {
        val rnd = Random(424242)
        fun ref(num: BigInteger, den: BigInteger, q: Long): Long =
            num.shiftLeft(1).add(den.multiply(BigInteger.valueOf(q))).divide(den.multiply(BigInteger.valueOf(q)).shiftLeft(1))
                .multiply(BigInteger.valueOf(q)).longValueExact()
        repeat(20_000) {
            val q = if (rnd.nextBoolean()) 1L else 100L
            val amount = when (rnd.nextInt(3)) {
                0 -> rnd.nextLong(0, 100)
                1 -> rnd.nextLong(0, 1_000_000)
                else -> rnd.nextLong(0, 1_000_000_000_000L)
            }
            val bp = rnd.nextLong(0, 10_001)
            val a = BigInteger.valueOf(amount)
            val b = BigInteger.valueOf(bp)
            assertEquals(ref(a.multiply(b), BigInteger.valueOf(10_000), q), Rounding.pctQ(amount, bp, q), "pct $amount $bp $q")
            assertEquals(ref(a.multiply(b), BigInteger.valueOf(10_000), q), Rounding.vatOnTop(amount, bp, q), "top $amount $bp $q")
            assertEquals(ref(a.multiply(b), BigInteger.valueOf(10_000 + bp), q), Rounding.vatInside(amount, bp, q), "inside $amount $bp $q")
            assertEquals(0L, Rounding.pctQ(amount, bp, q) % q)
            assertEquals(0L, Rounding.vatInside(amount, bp, q) % q)
            assertTrue(Rounding.pctQ(amount, bp, q) <= Rounding.roundQ(amount, q))
            assertEquals(ref(a, BigInteger.ONE, q), Rounding.roundQ(amount, q), "roundQ $amount $q")
            val floor = Rounding.floorQ(BigDecimal(amount), q)
            assertEquals(amount / q * q, floor, "floor $amount $q")
        }
    }

    // ---------------------------------------------------------------- allocate

    @Test
    fun `allocate gives the leftover quanta to the largest remainders (rows 15 and 71)`() {
        // row 15: FIXED coupon 20.00 over P1 100.00 and 3 x P2 29.97 -> 15.39 / 4.61
        assertEquals(listOf(1539L, 461L), Rounding.allocate(2000, listOf(10000, 2997), 1))
        // row 71: panel override 80.00 over the same lines -> 61.55 / 18.45
        assertEquals(listOf(6155L, 1845L), Rounding.allocate(8000, listOf(10000, 2997), 1))
    }

    @Test
    fun `allocate ties go to the lower index, zero weights get nothing, a full amount returns the weights`() {
        assertEquals(listOf(1L, 0L), Rounding.allocate(1, listOf(1, 1), 1))
        assertEquals(listOf(1L, 1L, 0L), Rounding.allocate(2, listOf(1, 1, 1), 1))
        assertEquals(listOf(0L, 5L, 0L), Rounding.allocate(5, listOf(0, 10, 0), 1))
        assertEquals(listOf(300L, 700L), Rounding.allocate(1000, listOf(300, 700), 1))
        assertEquals(listOf(0L, 0L), Rounding.allocate(0, listOf(300, 700), 1))
        assertEquals(emptyList<Long>(), Rounding.allocate(0, emptyList(), 1))
        assertEquals(listOf(100L, 0L), Rounding.allocate(100, listOf(100, 100), 100)) // quantum 100: whole units
        assertEquals(listOf(200L, 100L), Rounding.allocate(300, listOf(1000, 500), 100))
    }

    @Test
    fun `allocate refuses what would break its guarantees`() {
        assertThrows(IllegalArgumentException::class.java) { Rounding.allocate(1001, listOf(500, 500), 1) } // amount > W
        assertThrows(IllegalArgumentException::class.java) { Rounding.allocate(150, listOf(100, 100), 100) } // not a multiple of q
        assertThrows(IllegalArgumentException::class.java) { Rounding.allocate(100, listOf(150, 50), 100) } // weight not a multiple
        assertThrows(IllegalArgumentException::class.java) { Rounding.allocate(-1, listOf(5), 1) }
        assertThrows(IllegalArgumentException::class.java) { Rounding.allocate(1, listOf(-5, 10), 1) }
        assertThrows(IllegalArgumentException::class.java) { Rounding.allocate(1, emptyList(), 1) }
        assertThrows(IllegalArgumentException::class.java) { Rounding.allocate(1, listOf(1), 0) }
    }

    @Test
    fun `allocate over seeded weights adds up exactly, never exceeds a line and stays within a quantum of the exact share`() {
        val rnd = Random(777)
        repeat(10_000) { n ->
            val q = if (rnd.nextBoolean()) 1L else 100L
            val size = 1 + rnd.nextInt(8)
            val weights = List(size) { if (rnd.nextInt(6) == 0) 0L else rnd.nextLong(0, 100_000) * q }
            val total = weights.sum()
            if (total == 0L) return@repeat
            val amount = rnd.nextLong(0, total / q + 1) * q
            val shares = Rounding.allocate(amount, weights, q)
            val tag = "case $n: $amount over $weights q=$q"
            assertEquals(size, shares.size, tag)
            assertEquals(amount, shares.sum(), tag)
            for (i in 0 until size) {
                assertTrue(shares[i] in 0..weights[i], "$tag line $i share ${shares[i]}")
                assertEquals(0L, shares[i] % q, tag)
                // within one quantum of amount * w / W, on the right side of it
                val exact = BigInteger.valueOf(amount).multiply(BigInteger.valueOf(weights[i]))
                val whole = BigInteger.valueOf(total)
                val floor = exact.divide(whole).divide(BigInteger.valueOf(q)).multiply(BigInteger.valueOf(q))
                assertTrue(BigInteger.valueOf(shares[i]) >= floor && BigInteger.valueOf(shares[i]) <= floor.add(BigInteger.valueOf(q)), "$tag line $i")
            }
            assertEquals(shares, Rounding.allocate(amount, weights, q), tag) // deterministic
        }
    }

    // ---------------------------------------------------------------- no floating point

    @Test
    fun `the rounding primitives work on exact decimals only`() {
        // 0.1 + 0.2 style traps: a Double would give 30000000000000004
        assertEquals(30L, Rounding.roundQ(bd("0.1").add(bd("0.2")).multiply(BigDecimal(100)), 1))
        // a long rate expansion does not drift: 12345678901234 * 1.0000000001 stays exact
        assertEquals(12345678902469L, Rounding.roundQ(BigDecimal(12345678901234L).multiply(bd("1.0000000001")), 1))
    }
}
