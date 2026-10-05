package com.panomc.plugins.market.core.pricing

import com.panomc.plugins.market.core.pricing.PricingFixtures.D1
import com.panomc.plugins.market.core.pricing.PricingFixtures.KF20
import com.panomc.plugins.market.core.pricing.PricingFixtures.P1
import com.panomc.plugins.market.core.pricing.PricingFixtures.config
import com.panomc.plugins.market.core.pricing.PricingFixtures.full
import com.panomc.plugins.market.core.pricing.PricingFixtures.line
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The completeness of the vector table of 05 section 17 (rows 1 to 81, plus the lettered rows 47b and 73b). Every row of the
 * table is asserted by a test whose name starts with its number (`row 37 ...`, `rows 30 to 35 ...`): the stage tests of the
 * slices (`PricingEngineTest`, `PricingProfileTest`, `RetenderTest`) and this class, which holds the rows that no stage owned
 * (69, the exclusive FIXED coupon, and 75, the price drift that the checkout detects).
 *
 * [every row of the table has a test] reads the test method names of those classes by reflection and **fails when a row number
 * is missing**; [the detector sees a missing row] proves that the detector itself would fail.
 */
class PricingVectorTableTest {
    companion object {
        /** The row ids of the table: 1 to 81 and the two lettered rows. */
        val ROWS: List<String> = (1..81).map { it.toString() } + listOf("47b", "73b")

        /** The classes whose `@Test` names carry the rows. */
        val CLASSES: List<Class<*>> = listOf(
            PricingEngineTest::class.java, PricingProfileTest::class.java, RetenderTest::class.java, PricingVectorTableTest::class.java
        )

        private const val ID = "\\d+[a-z]?"
        private val LIST = Regex("""\brows? ($ID)((?:, | and | to | \+ )$ID)*""")
        private val TOKEN = Regex("""\d+[a-z]?|to""")

        /** Every row id that [name] declares: `rows 59 to 61 and 64 ...` gives 59, 60, 61 and 64. */
        fun rowsOf(name: String): Set<String> {
            val out = LinkedHashSet<String>()
            for (m in LIST.findAll(name)) {
                var previous: String? = null
                var range = false
                for (t in TOKEN.findAll(m.value.substringAfter(' '))) {
                    val token = t.value
                    if (token == "to") {
                        range = true
                        continue
                    }
                    if (range) {
                        for (i in previous!!.trimEnd { it.isLetter() }.toInt()..token.trimEnd { it.isLetter() }.toInt()) out += i.toString()
                    }
                    out += token
                    previous = token
                    range = false
                }
            }
            return out
        }

        /** The rows of [ROWS] that none of [names] declares. */
        fun missing(names: Collection<String>): List<String> {
            val declared = names.flatMapTo(HashSet()) { rowsOf(it) }
            return ROWS.filter { it !in declared }
        }

        fun testNames(): List<String> =
            CLASSES.flatMap { c -> c.declaredMethods.filter { it.isAnnotationPresent(Test::class.java) }.map { it.name } }
    }

    @Test
    fun `every row of the table has a test`() {
        val names = testNames()
        assertTrue(names.size > 200, "the reflection found only ${names.size} tests")
        val missing = missing(names)
        assertTrue(missing.isEmpty(), "rows of 05 section 17 without a test: $missing")
    }

    @Test
    fun `the detector sees a missing row`() {
        val names = testNames()
        // dropping every test of row N must report N, for rows of each shape: plain, ranged, listed, lettered
        for (row in listOf("1", "47", "47b", "69", "73b", "75", "81")) {
            val without = names.filter { row !in rowsOf(it) }
            assertEquals(listOf(row), missing(without), "removing the tests of row $row")
        }
        assertEquals(ROWS.size, missing(emptyList()).size)
        // the parser: lists, ranges, letters, and no row where there is none
        assertEquals(setOf("37", "38"), rowsOf("row 37 and 38 a variant price"))
        assertEquals(setOf("30", "31", "32", "33", "34", "35"), rowsOf("rows 30 to 35 the upgrade deduction"))
        assertEquals(setOf("8", "16", "34"), rowsOf("rows 8, 16 and 34 an order worth nothing"))
        assertEquals(setOf("59", "60", "61", "64"), rowsOf("rows 59 to 61 and 64 currencies"))
        assertEquals(setOf("47b"), rowsOf("row 47b a number above the maximum"))
        assertEquals(emptySet<String>(), rowsOf("the browser shows no rows at all"))
    }

    @Test
    fun `row 69 an exclusive price with a FIXED coupon takes the coupon off the net price and adds the VAT on top`() {
        val r = full(line(P1), config = config(includeVat = false), coupon = KF20)
        val l = r.lines.single()
        assertEquals(10000L, l.lineAmount)
        assertEquals(2000L, l.couponShare)
        assertEquals(8000L, l.lineBasis) // 100.00 - 20.00
        assertEquals(1600L, l.vatAmount) // 20 % of 80.00
        assertEquals(9600L, l.lineTotal)
        assertEquals(9600L, r.total)
        assertEquals(2000L, r.couponDiscount)
        assertEquals(1600L, r.vatTotal)
    }

    @Test
    fun `row 75 price drift is the difference between the total the buyer saw and the fresh quote at checkout`() {
        // the checkout service compares expectedTotal with a quote made after the locks (PRICE_CHANGED carries the new quote):
        // at the engine this is the same input giving a different total once D1 became active
        val seen = full(line(P1))
        val expectedTotal = seen.total
        assertEquals(10000L, expectedTotal)
        assertEquals(expectedTotal, full(line(P1)).total) // unchanged prices: no drift, the quote is reproducible
        val fresh = full(line(P1), discounts = listOf(D1))
        assertEquals(9000L, fresh.total)
        assertFalse(fresh.total == expectedTotal, "the drift is visible as a different total")
        assertEquals(1000L, fresh.discountTotal)
        assertEquals(1, fresh.items.discountRedemptions.size)
    }
}
