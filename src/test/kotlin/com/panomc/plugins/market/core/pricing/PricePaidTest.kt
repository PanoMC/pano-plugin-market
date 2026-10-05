package com.panomc.plugins.market.core.pricing

import com.panomc.plugins.market.core.money.Conversions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** `PricePaid` (05 section 5.2, the value stored on the entitlement a line creates). */
class PricePaidTest {
    private fun conv(order: String = "TRY", fx: String = "1", cv: Long = 100, removeCents: Boolean = false) =
        Conversions("TRY", order, BigDecimal(fx), cv, removeCents)

    @Test
    fun `money order is the basis per unit plus the upgrade deduction, in the base currency (rows 30 and 31)`() {
        val c = conv()
        assertEquals(12000L, PricePaid.moneyOrder(c, basisLine = 7000, quantity = 1, upgradeUnitAmount = 5000)) // row 30
        assertEquals(20000L, PricePaid.moneyOrder(c, basisLine = 17500, quantity = 1, upgradeUnitAmount = 2500)) // row 31
        assertEquals(10000L, PricePaid.moneyOrder(c, basisLine = 10000, quantity = 1, upgradeUnitAmount = 0))
    }

    @Test
    fun `a line of several units is divided once and rounded once`() {
        val c = conv()
        assertEquals(333L, PricePaid.moneyOrder(c, basisLine = 1000, quantity = 3, upgradeUnitAmount = 0)) // 333.33
        assertEquals(334L, PricePaid.moneyOrder(c, basisLine = 1001, quantity = 3, upgradeUnitAmount = 0)) // 333.67
        assertEquals(501L, PricePaid.moneyOrder(c, basisLine = 1001, quantity = 2, upgradeUnitAmount = 0)) // 500.5: a tie rounds up
    }

    @Test
    fun `a foreign order currency is converted back to the base currency`() {
        val usd = conv("USD", "0.025")
        // 2.99 USD per unit is 119.60 TRY, the deduction 1.00 USD is 40.00 TRY
        assertEquals(11960L + 4000L, PricePaid.moneyOrder(usd, basisLine = 299, quantity = 1, upgradeUnitAmount = 100))
        assertEquals(11960L, PricePaid.moneyOrder(usd, basisLine = 598, quantity = 2, upgradeUnitAmount = 0))
    }

    @Test
    fun `credit order is the credit line total in base money plus the upgrade deduction`() {
        val c = conv(cv = 10) // 0.10 per credit
        // 117.00 credits (row 50) at 0.10 = 11.70 base money
        assertEquals(1170L, PricePaid.creditOrder(c, creditLineTotal = 11700, quantity = 1, upgradeUnitAmount = 0))
        assertEquals(585L, PricePaid.creditOrder(c, creditLineTotal = 11700, quantity = 2, upgradeUnitAmount = 0))
        assertEquals(1170L + 5000L, PricePaid.creditOrder(c, creditLineTotal = 11700, quantity = 1, upgradeUnitAmount = 5000))
        assertThrows(IllegalArgumentException::class.java) { PricePaid.creditOrder(conv(cv = 0), 100, 1, 0) }
    }

    @Test
    fun `a quantity of zero is refused`() {
        assertThrows(IllegalArgumentException::class.java) { PricePaid.moneyOrder(conv(), 100, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { PricePaid.creditOrder(conv(), 100, 0, 0) }
    }
}
