package com.panomc.plugins.market.core.shipping

import com.panomc.plugins.market.core.money.Conversions
import com.panomc.plugins.market.db.model.ShippingRateBasis.AMOUNT
import com.panomc.plugins.market.db.model.ShippingRateBasis.FLAT
import com.panomc.plugins.market.db.model.ShippingRateBasis.QUANTITY
import com.panomc.plugins.market.db.model.ShippingRateBasis.WEIGHT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** `RateEngine`, `ShippingVat`, `RateTable` and `ShippingPriceCalculator` (10 sections 5.2, 5.3, 5.6; tests 13 to 23 of section 16). */
class RateEngineTest {
    private fun m(weight: Long = 0, amount: Long = 0, units: Long = 0) = Measure(weight, amount, units)

    // ---- RateEngine ----

    @Test
    fun `flat returns the price and a flat row first makes later rows irrelevant`() {
        val rows = listOf(RateRow(FLAT, price = 500, position = 0), RateRow(WEIGHT, 0, null, 99, position = 1))

        assertEquals(500, RateEngine.price(rows, m(weight = 12345)))
        assertEquals(500, RateEngine.price(rows, m()))
    }

    @Test
    fun `weight rows add the price of every started kilogram above the range start`() {
        val rows = listOf(
            RateRow(WEIGHT, 0, 1000, price = 30, position = 0),
            RateRow(WEIGHT, 1001, null, price = 30, perUnitPrice = 5, position = 1)
        )

        assertEquals(30, RateEngine.price(rows, m(weight = 1000)))
        assertEquals(30, RateEngine.price(rows, m(weight = 1001)))
        assertEquals(35, RateEngine.price(rows, m(weight = 2001)))
        assertEquals(40, RateEngine.price(rows, m(weight = 2002)))
        assertEquals(30, RateEngine.price(rows, m(weight = 1)))
    }

    @Test
    fun `amount range edges are inclusive on both ends`() {
        val rows = listOf(RateRow(AMOUNT, 10_000, 50_000, price = 700, position = 0))

        assertEquals(700, RateEngine.price(rows, m(amount = 10_000)))
        assertEquals(700, RateEngine.price(rows, m(amount = 50_000)))
        assertNull(RateEngine.price(rows, m(amount = 50_001)))
        assertNull(RateEngine.price(rows, m(amount = 9_999)))
    }

    @Test
    fun `quantity rows add the per unit price above the range start`() {
        val rows = listOf(RateRow(QUANTITY, 1, null, price = 20, perUnitPrice = 5))

        assertEquals(20, RateEngine.price(rows, m(units = 1)))
        assertEquals(30, RateEngine.price(rows, m(units = 3)))
        assertNull(RateEngine.price(rows, m(units = 0)))
    }

    @Test
    fun `gaps give no price and the first containing row wins by position then id`() {
        val gap = listOf(RateRow(WEIGHT, 0, 1000, 10, position = 0), RateRow(WEIGHT, 2000, null, 20, position = 1))

        assertNull(RateEngine.price(gap, m(weight = 1001)))
        assertNull(RateEngine.price(gap, m(weight = 1999)))
        assertEquals(20, RateEngine.price(gap, m(weight = 2000)))
        assertNull(RateEngine.price(emptyList(), m(weight = 1)))

        val overlapping = listOf(RateRow(FLAT, price = 1, position = 3, id = 9), RateRow(FLAT, price = 2, position = 3, id = 4), RateRow(FLAT, price = 3, position = 5))
        assertEquals(2, RateEngine.price(overlapping, m()))
    }

    @Test
    fun `rows of different bases are tried in order`() {
        val rows = listOf(RateRow(AMOUNT, 100_000, null, price = 0, position = 0), RateRow(WEIGHT, 0, null, price = 400, perUnitPrice = 100, position = 1))

        assertEquals(0, RateEngine.price(rows, m(weight = 5000, amount = 100_000)))
        assertEquals(400 + 100 * 5, RateEngine.price(rows, m(weight = 5000, amount = 99_999)))
    }

    @Test
    fun `an overflowing price throws instead of wrapping`() {
        val rows = listOf(RateRow(QUANTITY, 0, null, price = 1, perUnitPrice = Long.MAX_VALUE / 2))

        assertThrows(ArithmeticException::class.java) { RateEngine.price(rows, m(units = 5)) }
    }

    // ---- ShippingVat ----

    @Test
    fun `vat split inclusive and exclusive`() {
        val inc = ShippingVat.split(12_000, 2000, true)
        assertEquals(12_000, inc.gross)
        assertEquals(2_000, inc.vat)

        val exc = ShippingVat.split(10_000, 2000, false)
        assertEquals(12_000, exc.gross)
        assertEquals(2_000, exc.vat)

        val zero = ShippingVat.split(10_000, 0, false)
        assertEquals(10_000, zero.gross)
        assertEquals(0, zero.vat)
        assertEquals(0, ShippingVat.split(10_000, 0, true).vat)

        // half up: 1.05 at 20 % inclusive -> 0.175 -> 0.18; on top: 1.05 x 20 % = 0.21
        assertEquals(18, ShippingVat.split(105, 2000, true).vat)
        assertEquals(21, ShippingVat.split(105, 2000, false).vat)
        assertEquals(1, ShippingVat.split(7, 1000, false).vat)   // 0.7 -> 1 (half up)
    }

    // ---- RateTable ----

    private fun table(base: String = "TRY", order: String = "TRY", fx: String = "1", others: Map<String, String> = emptyMap()) =
        RateTable(base, order, BigDecimal(fx), others.mapValues { BigDecimal(it.value) })

    @Test
    fun `currency conversion goes through the base and a missing rate fails`() {
        // base TRY, order EUR at 0.025 EUR per TRY
        val t = table("TRY", "EUR", "0.025", mapOf("USD" to "0.03"))

        assertEquals(1000, t.convert(40_000, "TRY", "EUR"))       // 400.00 TRY -> 10.00 EUR
        assertEquals(40_000, t.convert(1000, "EUR", "TRY"))
        assertEquals(1000, t.convert(1200, "USD", "EUR"))         // 12.00 USD / 0.03 x 0.025 = 10.00 EUR
        assertEquals(777, t.convert(777, "EUR", "EUR"))
        assertNull(t.convert(1200, "GBP", "EUR"))
        assertNull(t.convert(1200, "EUR", "GBP"))
        assertNull(table(others = mapOf("USD" to "0")).convert(100, "USD", "TRY"))
        assertThrows(IllegalArgumentException::class.java) { t.convert(-1, "TRY", "EUR") }
    }

    @Test
    fun `conversion rounds half up once`() {
        val t = table("TRY", "EUR", "0.025")

        assertEquals(1, t.convert(20, "TRY", "EUR"))     // 0.5 -> 1
        assertEquals(0, t.convert(19, "TRY", "EUR"))     // 0.475 -> 0
    }

    // ---- ShippingPriceCalculator ----

    private fun conv(base: String = "TRY", order: String = "TRY", fx: String = "1", removeCents: Boolean = false) =
        Conversions(base, order, BigDecimal(fx), 100, removeCents)

    private fun compute(
        raw: RawRate, terms: ShippingTerms = ShippingTerms(null, 0, null), value: Long = 0, includesVat: Boolean = true, config: Long = 2000,
        c: Conversions = conv(), t: RateTable = table()
    ) = ShippingPriceCalculator.compute(terms, raw, value, includesVat, config, c, t)!!

    private fun rule(amount: Long, inclusive: Boolean, currency: String = "TRY") = RawRate(amount, currency, inclusive, RateSource.RULES)

    @Test
    fun `free threshold is inclusive and one cent below is charged`() {
        val terms = ShippingTerms(freeShippingThreshold = 10_000, handlingFee = 500, vatBp = null)

        val free = compute(rule(3000, true), terms, value = 10_000)
        assertEquals(0, free.gross)
        assertEquals(0, free.vat)
        assertTrue(free.free)
        assertEquals(0, free.handlingPart)                       // the handling fee is waived

        val paid = compute(rule(3000, true), terms, value = 9_999)
        assertFalse(paid.free)
        assertEquals(3500, paid.gross)
    }

    @Test
    fun `the threshold compares the order value converted to base`() {
        val c = conv("TRY", "EUR", "0.025")
        val t = table("TRY", "EUR", "0.025")
        val terms = ShippingTerms(freeShippingThreshold = 40_000, handlingFee = 0, vatBp = null)

        assertTrue(compute(rule(100, true), terms, value = 1000, c = c, t = t).free)       // 10.00 EUR = 400.00 TRY
        assertFalse(compute(rule(100, true), terms, value = 999, c = c, t = t).free)      // 9.99 EUR = 399.60 TRY
    }

    @Test
    fun `value of digital lines never counts because only physical lines are passed in`() {
        val lines = listOf(
            ShippableLine(1, 1, 0, "mug", null, 1, 300, null, null, null, lineValue = 5_000)
        )
        val terms = ShippingTerms(freeShippingThreshold = 10_000, handlingFee = 0, vatBp = null)

        // cart: digital 500.00 + physical 50.00, threshold 100.00 -> physical value only
        assertFalse(compute(rule(2000, true), terms, value = ShippableLines.totals(lines).shippableValue).free)
    }

    @Test
    fun `handling fee is added before vat`() {
        // VAT inclusive store, 20 %: rule 120.00 gross 120.00 vat 20.00
        val inc = compute(rule(12_000, true))
        assertEquals(12_000, inc.gross)
        assertEquals(2_000, inc.vat)

        // exclusive: rule 100.00 -> gross 120.00 vat 20.00
        val exc = compute(rule(10_000, false), includesVat = false)
        assertEquals(12_000, exc.gross)
        assertEquals(2_000, exc.vat)

        // the handling fee joins the vat base
        val withFee = compute(rule(10_000, false), ShippingTerms(null, 2_500, null), includesVat = false)
        assertEquals(15_000, withFee.gross)
        assertEquals(10_000, withFee.carrierPart)
        assertEquals(2_500, withFee.handlingPart)
        assertEquals(2_500, withFee.vat)
    }

    @Test
    fun `method vat percent overrides the store rate including zero`() {
        val zero = compute(rule(12_000, true), ShippingTerms(null, 0, 0))
        assertEquals(12_000, zero.gross)
        assertEquals(0, zero.vat)
        assertEquals(0, zero.vatBp)

        val ten = compute(rule(11_000, true), ShippingTerms(null, 0, 1000))
        assertEquals(1_000, ten.vat)
        assertEquals(1000, ten.vatBp)

        assertEquals(2000, compute(rule(100, true)).vatBp)
    }

    @Test
    fun `carrier rate without tax in an inclusive store plus an inclusive handling fee`() {
        val r = compute(RawRate(10_000, "TRY", false, RateSource.CARRIER), ShippingTerms(null, 1_200, null), includesVat = true)

        assertEquals(13_200, r.gross)       // 100 + 20 vat + 12 handling
        assertEquals(2_200, r.vat)          // 20 + 2
        assertEquals(RateSource.CARRIER, r.source)
    }

    @Test
    fun `currency conversion of rules and carrier rates`() {
        val c = conv("TRY", "EUR", "0.025")
        val t = table("TRY", "EUR", "0.025", mapOf("USD" to "0.03"))

        val rules = compute(rule(40_000, true), c = c, t = t)
        assertEquals(1000, rules.gross)                    // 400 TRY -> 10.00 EUR

        val carrier = compute(RawRate(1200, "USD", true, RateSource.CARRIER), c = c, t = t)
        assertEquals(1000, carrier.gross)                  // through the base

        assertNull(ShippingPriceCalculator.compute(ShippingTerms(null, 0, null), RawRate(1200, "GBP", true, RateSource.CARRIER), 0, true, 2000, c, t))
    }

    @Test
    fun `handling fee is converted from base to the order currency`() {
        val c = conv("TRY", "EUR", "0.025")
        val t = table("TRY", "EUR", "0.025")

        val r = compute(rule(40_000, true), ShippingTerms(null, 4_000, null), c = c, t = t)

        assertEquals(1000 + 100, r.gross)                  // 10.00 + 1.00 EUR
    }

    @Test
    fun `zero decimal order currency rounds the gross up and recomputes the vat inside`() {
        val c = conv("JPY", "JPY", "1")
        val t = table("JPY", "JPY", "1")

        val r = compute(rule(123_456, true, "JPY"), c = c, t = t)        // 1234.56 yen
        assertEquals(123_500, r.gross)
        assertEquals(20_583, r.vat)                               // inside 1235 at 20 %, half up: 205.83 -> 20 583 (x100 scale)

        val exact = compute(rule(120_000, true, "JPY"), c = c, t = t)
        assertEquals(120_000, exact.gross)
        assertEquals(20_000, exact.vat)

        val removed = compute(rule(10_050, true, "EUR"), c = conv("EUR", "EUR", "1", removeCents = true), t = table("EUR", "EUR"))
        assertEquals(10_100, removed.gross)                       // removeCents is a quantum of 100 too
    }

    @Test
    fun `a zero price is charged and is not free`() {
        val r = compute(rule(0, true))

        assertEquals(0, r.gross)
        assertFalse(r.free)
    }

    @Test
    fun `negative amounts are a caller bug`() {
        assertThrows(IllegalArgumentException::class.java) { compute(rule(-1, true)) }
    }

    @Test
    fun `gross always equals the parts plus vat when the order quantum is one`() {
        val c = conv()
        val t = table()
        var seed = 20261006L

        repeat(2_000) {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            val amount = Math.floorMod(seed ushr 8, 1_000_000L)
            val fee = Math.floorMod(seed ushr 28, 50_000L)
            val inc = seed % 2 == 0L
            val bp = Math.floorMod(seed ushr 40, 10_001L)
            val r = compute(RawRate(amount, "TRY", inc, RateSource.CARRIER), ShippingTerms(null, fee, bp), includesVat = !inc, c = c, t = t)

            assertTrue(r.vat in 0..r.gross)
            val parts = (if (inc) amount else amount + ShippingVat.split(amount, bp, false).vat) + (if (!inc) fee else fee + ShippingVat.split(fee, bp, false).vat)
            assertEquals(parts, r.gross)
        }
    }

    @Test
    fun `toBase rounds half up with no quantum`() {
        assertEquals(40_000, ShippingPriceCalculator.toBase(1000, conv("TRY", "EUR", "0.025")))
        assertEquals(61, ShippingPriceCalculator.toBase(5, conv("TRY", "EUR", "0.0817")))    // 61.2 -> 61
        assertEquals(123, ShippingPriceCalculator.toBase(123, conv()))
    }
}
