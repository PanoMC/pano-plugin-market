package com.panomc.plugins.market.spi.payment

import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.payment.PaymentTestData.eur
import com.panomc.plugins.market.spi.payment.PaymentTestData.line
import com.panomc.plugins.market.spi.payment.PaymentTestData.order
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Random

class OrderSnapshotTest {
    private fun sum(lines: List<OrderLine>) = lines.sumOf { it.total.amount }

    @Test
    fun `an unchanged amount returns the real lines untouched`() {
        val o = order(listOf(line(1, 1000), line(2, 2500, quantity = 5)))
        val balanced = o.balancedLines(eur(3500))
        assertEquals(listOf(1L, 2L), balanced.map { it.orderItemId })
        assertTrue(balanced[0] === o.lines[0] && balanced[1] === o.lines[1])
    }

    @Test
    fun `shipping and fee become synthetic lines with the reserved ids`() {
        val o = order(listOf(line(1, 1000)), shipping = 250, fee = 75)
        val balanced = o.balancedLines(eur(1325))
        assertEquals(listOf(1L, OrderLine.SHIPPING_LINE_ID, OrderLine.FEE_LINE_ID), balanced.map { it.orderItemId })
        assertEquals(listOf(1000L, 250L, 75L), balanced.map { it.total.amount })
        assertEquals(-1L, OrderLine.SHIPPING_LINE_ID)
        assertEquals(-2L, OrderLine.FEE_LINE_ID)
        assertEquals(1, balanced[1].quantity)
        assertEquals(false, balanced[1].physical)
    }

    @Test
    fun `a zero shipping or fee adds no synthetic line`() {
        val o = order(listOf(line(1, 1000)), shipping = 0, fee = 40)
        assertEquals(listOf(1L, OrderLine.FEE_LINE_ID), o.balancedLines(eur(1040)).map { it.orderItemId })
    }

    @Test
    fun `a discount is spread proportionally half-up and the last real line absorbs the remainder`() {
        // 3 lines of 100.00 / 200.00 / 300.00, amount 5.00 less: target 595.00 over weights 100:200:300
        val o = order(listOf(line(1, 10000), line(2, 20000), line(3, 30000)))
        val b = o.balancedLines(eur(59500))
        assertEquals(59500L, sum(b))
        assertEquals(listOf(9917L, 19833L, 29750L), b.map { it.total.amount })
    }

    @Test
    fun `rounding every share up never makes the last line negative`() {
        // four equal lines, target 2 minor units: each share is 0.5 and rounds up to 1; without a cap the last line would be -1
        val o = order(listOf(line(1, 1), line(2, 1), line(3, 1), line(4, 1)))
        val b = o.balancedLines(eur(2))
        assertEquals(listOf(1L, 1L, 0L, 0L), b.map { it.total.amount })
        assertEquals(2L, sum(b))
    }

    @Test
    fun `half-up rounding on an exact tie`() {
        // weights 1:1 target 3 minor units: share 1.5 -> 2 for the first line, the last gets the rest (1)
        val o = order(listOf(line(1, 1), line(2, 1)))
        assertEquals(listOf(2L, 1L), o.balancedLines(eur(3)).map { it.total.amount })
    }

    @Test
    fun `the credit part is removed together with the shipping and fee lines kept whole`() {
        val o = order(listOf(line(1, 4000), line(2, 6000)), shipping = 500, fee = 100)
        // order total 10600, buyer pays 3600 by gateway (7000 credit)
        val b = o.balancedLines(eur(3600))
        assertEquals(3600L, sum(b))
        assertEquals(500L, b.single { it.orderItemId == OrderLine.SHIPPING_LINE_ID }.total.amount)
        assertEquals(100L, b.single { it.orderItemId == OrderLine.FEE_LINE_ID }.total.amount)
        assertEquals(listOf(1200L, 1800L), b.filter { it.orderItemId > 0 }.map { it.total.amount })
    }

    @Test
    fun `an amount that does not cover shipping and fee spreads over every line without a negative total`() {
        val o = order(listOf(line(1, 1000)), shipping = 600, fee = 400)
        val b = o.balancedLines(eur(800))
        assertEquals(800L, sum(b))
        assertTrue(b.all { it.total.amount >= 0 })
        assertEquals(listOf(1L, OrderLine.SHIPPING_LINE_ID, OrderLine.FEE_LINE_ID), b.map { it.orderItemId })
    }

    @Test
    fun `zero total real lines put the whole target on the last line`() {
        val o = order(listOf(line(1, 0), line(2, 0)), fee = 100)
        val b = o.balancedLines(eur(600))
        assertEquals(listOf(0L, 500L, 100L), b.map { it.total.amount })
    }

    @Test
    fun `an order without lines balances only to shipping plus fee`() {
        val o = order(emptyList(), shipping = 300, fee = 200)
        assertEquals(500L, sum(o.balancedLines(eur(500))))
        assertThrows<IllegalArgumentException> { o.balancedLines(eur(501)) }
    }

    @Test
    fun `a zero-decimal currency keeps whole units`() {
        // JPY: 1000 and 2000 yen lines (amount x 100), target 2999 yen over 3000
        val o = order(listOf(line(1, 100_000, currency = "JPY"), line(2, 200_000, currency = "JPY")), currency = "JPY")
        val b = o.balancedLines(Money(299_900, "JPY"))
        assertEquals(299_900L, sum(b))
        assertTrue(b.all { it.total.amount % 100 == 0L }, "every line is a whole number of yen: ${b.map { it.total.amount }}")
        // 100000 * 299900 / 300000 = 99966.67 (x100 units) = 999.67 yen, rounded to 1000 yen; the last line takes 1999 yen
        assertEquals(listOf(100_000L, 199_900L), b.map { it.total.amount })
    }

    @Test
    fun `the unit price follows the new total and quantity`() {
        val o = order(listOf(line(1, 3000, quantity = 3)))
        val b = o.balancedLines(eur(2700)).single()
        assertEquals(2700L, b.total.amount)
        assertEquals(900L, b.unitPrice.amount)
        assertEquals(3, b.quantity)
        assertEquals("Item 1", b.name)
        assertEquals(101L, b.productId)
    }

    @Test
    fun `the result is deterministic for the same order and amount`() {
        val o = order(listOf(line(1, 3333), line(2, 6667), line(3, 1)), shipping = 123, fee = 45)
        val first = o.balancedLines(eur(7000)).map { it.orderItemId to it.total.amount }
        repeat(5) { assertEquals(first, o.balancedLines(eur(7000)).map { it.orderItemId to it.total.amount }) }
    }

    @Test
    fun `the sum always equals the amount for 20000 seeded random orders`() {
        val rnd = Random(20261005L)
        repeat(20_000) {
            val n = 1 + rnd.nextInt(6)
            val lines = (1..n).map { line(it.toLong(), rnd.nextInt(500_000).toLong() + 1, quantity = 1) }
            val shipping = if (rnd.nextBoolean()) rnd.nextInt(5000).toLong() else 0L
            val fee = if (rnd.nextBoolean()) rnd.nextInt(2000).toLong() else 0L
            val o = order(lines, shipping, fee)
            val amount = rnd.nextInt((o.total.amount + 1).toInt()).toLong()
            val b = o.balancedLines(eur(amount))
            assertEquals(amount, sum(b), "lines=${lines.map { it.total.amount }} shipping=$shipping fee=$fee amount=$amount")
            assertTrue(b.all { it.total.amount >= 0 }, "negative line in ${b.map { it.total.amount }}")
        }
    }

    @Test
    fun `the sum equals the amount for random zero-decimal orders and every line stays whole`() {
        val rnd = Random(77L)
        repeat(5_000) {
            val lines = (1..(1 + rnd.nextInt(5))).map { line(it.toLong(), (rnd.nextInt(5000) + 1) * 100L, currency = "JPY") }
            val shipping = if (rnd.nextBoolean()) rnd.nextInt(300) * 100L else 0L
            val o = order(lines, shipping, 0, currency = "JPY")
            val amount = rnd.nextInt((o.total.amount / 100).toInt() + 1) * 100L
            val b = o.balancedLines(Money(amount, "JPY"))
            assertEquals(amount, sum(b))
            assertTrue(b.all { it.total.amount % 100 == 0L && it.unitPrice.amount % 100 == 0L })
        }
    }

    @Test
    fun `a wrong currency or a negative amount is refused`() {
        val o = order(listOf(line(1, 1000)))
        assertThrows<IllegalArgumentException> { o.balancedLines(Money(1000, "USD")) }
        assertThrows<IllegalArgumentException> { o.balancedLines(Money(-1, "EUR")) }
        assertThrows<IllegalArgumentException> { o.singleLine(Money(1000, "USD")) }
    }

    @Test
    fun `single line carries the whole amount and the description`() {
        val o = order(listOf(line(1, 1000, vat = 2000), line(2, 500, vat = 2000)), description = "Order #42")
        val s = o.singleLine(eur(1500))
        assertEquals("Order #42", s.name)
        assertEquals(1, s.quantity)
        assertEquals(eur(1500), s.total)
        assertEquals(eur(1500), s.unitPrice)
        assertEquals(2000L, s.vatPercent)
        assertEquals(OrderLine.SINGLE_LINE_ID, s.orderItemId)
        val mixed = order(listOf(line(1, 1000, vat = 2000), line(2, 500, vat = 800)))
        assertEquals(0L, mixed.singleLine(eur(1500)).vatPercent)
    }
}
