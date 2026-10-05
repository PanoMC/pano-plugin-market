package com.panomc.plugins.market.core.money

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** The conversions of 05 section 2: base <-> order currency, money <-> credits, display. */
class ConversionsTest {
    private fun conv(
        order: String = "TRY", fx: String = "1", cv: Long = 100, removeCents: Boolean = false,
        display: String? = null, rate: String? = null
    ) = Conversions("TRY", order, BigDecimal(fx), cv, removeCents, display, rate?.let { BigDecimal(it) })

    @Test
    fun `toOrder converts at the rate and rounds to the order quantum (rows 60, 61, 63, 64, 66)`() {
        val usd = conv("USD", "0.025")
        assertEquals(25L, usd.toOrder(999)) // row 61: 0.24975 -> 0.25
        assertEquals(50L, usd.toOrder(2000)) // row 63: FIXED coupon 20.00 TRY -> 0.50 USD
        assertEquals(688L, usd.toOrder(27500)) // row 66: 6.875 -> 6.88
        assertEquals(299L, usd.toOrder(11960)) // an exact conversion stays exact
        val jpy = conv("JPY", "4.5")
        assertEquals(45000L, jpy.toOrder(10000)) // row 64: 450 JPY
        assertEquals(4500L, jpy.toOrder(999)) // row 64: 44.955 -> 45 JPY
        assertEquals(100L, jpy.oq)
        assertEquals(1L, usd.bq)
    }

    @Test
    fun `the identity holds for the base currency and rounds only under removeCents`() {
        val id = conv()
        for (v in listOf(0L, 1L, 999L, 123456789L)) assertEquals(v, id.toOrder(v))
        val whole = conv(removeCents = true)
        assertEquals(1000L, whole.toOrder(999))
        assertEquals(1000L, whole.toOrder(950))
        assertEquals(900L, whole.toOrder(949))
    }

    @Test
    fun `fromOrder converts back at the rate with one rounding`() {
        val usd = conv("USD", "0.025")
        assertEquals(10000L, usd.fromOrder(250))
        assertEquals(11960L, usd.fromOrder(299)) // 299 / 0.025 = 11960
        assertEquals(40L, usd.fromOrder(1)) // 1 / 0.025 = 40
        val odd = conv("USD", "3")
        assertEquals(333L, odd.fromOrder(1000)) // 333.33 -> 333
        assertEquals(334L, odd.fromOrder(1001)) // 333.67 -> 334
        val whole = Conversions("TRY", "USD", BigDecimal("0.025"), 100, true)
        assertEquals(100L, whole.bq)
        assertEquals(10000L, whole.fromOrder(250))
        assertEquals(0L, whole.fromOrder(1)) // 40 -> whole units of the base currency
    }

    @Test
    fun `moneyToCredits and creditsToMoney use the credit value, the second one rounds down (row 46)`() {
        val dime = conv(cv = 10) // 0.10 per credit
        assertEquals(1000L, dime.creditsToMoney(10000)) // row 46: 100 credits = 10.00
        assertEquals(25000L, dime.moneyToCredits(2500)) // row 79: 25.00 = 250 credits
        assertEquals(0L, dime.creditsToMoney(5)) // 0.05 credit is worth 0.005: floor
        assertEquals(1L, dime.creditsToMoney(10))
        assertEquals(10L, dime.moneyToCredits(1)) // 0.01 = 0.1 credit
        val third = conv(cv = 300)
        assertEquals(333L, third.moneyToCredits(1000)) // 10.00 / 3.00 = 3.33 credits (x100 = 333.33)
        assertEquals(0L, third.moneyToCredits(1)) // 0.01 / 3.00 = 0.0033 credit, below the 0.01 credit step
    }

    @Test
    fun `creditsToMoney is converted into the order currency and floored to its quantum`() {
        val usd = conv("USD", "0.025", cv = 100)
        assertEquals(2L, usd.creditsToMoney(100)) // 1 credit = 1.00 TRY = 0.025 USD -> floor to 0.02
        assertEquals(250L, usd.creditsToMoney(10000)) // 100 credits = 2.50 USD
        val jpy = conv("JPY", "4.5", cv = 100)
        assertEquals(400L, jpy.creditsToMoney(100)) // 1 credit = 4.5 JPY -> floor to whole units: 4 JPY
    }

    @Test
    fun `the credit conversions refuse a missing credit value and negative amounts`() {
        assertThrows(IllegalArgumentException::class.java) { conv(cv = 0).moneyToCredits(100) }
        assertThrows(IllegalArgumentException::class.java) { conv(cv = 0).creditsToMoney(100) }
        assertThrows(IllegalArgumentException::class.java) { conv().moneyToCredits(-1) }
        assertThrows(IllegalArgumentException::class.java) { conv().creditsToMoney(-1) }
    }

    @Test
    fun `toDisplay rounds per figure to the display quantum (row 59)`() {
        val d = conv(display = "USD", rate = "0.025")
        assertEquals(250L, d.toDisplay(10000)) // 2.50
        assertEquals(75L, d.toDisplay(2997)) // 0.74925 -> 0.75
        assertEquals(1L, d.toDisplay(20)) // 20 x 0.025 = 0.5: a tie rounds up
        val jpy = conv(display = "JPY", rate = "4.5")
        assertEquals(100L, jpy.dq)
        assertEquals(45000L, jpy.toDisplay(10000)) // display currencies with no decimals show whole units
        assertThrows(IllegalStateException::class.java) { conv().toDisplay(1) }
    }

    @Test
    fun `a conversion block must be consistent`() {
        assertThrows(IllegalArgumentException::class.java) { conv(fx = "0") }
        assertThrows(IllegalArgumentException::class.java) { conv(fx = "-1") }
        assertThrows(IllegalArgumentException::class.java) { conv(display = "USD") }
        assertThrows(IllegalArgumentException::class.java) { conv(rate = "0.025") }
        assertThrows(IllegalArgumentException::class.java) { conv(display = "USD", rate = "0") }
        assertThrows(ArithmeticException::class.java) { conv("USD", "100000000000").toOrder(Long.MAX_VALUE / 2) }
        assertTrue(conv(display = "USD", rate = "0.025").dq == 1L)
    }
}
