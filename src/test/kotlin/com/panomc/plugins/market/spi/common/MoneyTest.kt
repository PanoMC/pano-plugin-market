package com.panomc.plugins.market.spi.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class MoneyTest {
    @Test
    fun `minor units and decimal strings for EUR`() {
        val m = Money(1234, "EUR")
        assertEquals(1234L, m.toMinorUnits())
        assertEquals("12.34", m.toDecimalString())
        assertEquals(BigDecimal("12.34"), m.toDecimal())
        assertEquals(2, m.toDecimal().scale())
    }

    @Test
    fun `minor units and decimal strings for JPY`() {
        val m = Money(50000, "JPY")
        assertEquals(500L, m.toMinorUnits())
        assertEquals("500", m.toDecimalString())
        assertEquals(BigDecimal("500.00"), m.toDecimal())
    }

    @Test
    fun `negative and small amounts format correctly`() {
        assertEquals("-0.05", Money(-5, "EUR").toDecimalString())
        assertEquals("0.00", Money(0, "EUR").toDecimalString())
        assertEquals("0", Money(0, "JPY").toDecimalString())
        assertEquals("-500", Money(-50000, "JPY").toDecimalString())
        assertEquals(-500L, Money(-50000, "JPY").toMinorUnits())
        assertEquals("0.01", Money(1, "USD").toDecimalString())
    }

    @Test
    fun `ofDecimal rounds HALF_UP to scale 2`() {
        assertEquals(13L, Money.ofDecimal(BigDecimal("0.125"), "EUR").amount)
        assertEquals(12L, Money.ofDecimal(BigDecimal("0.124"), "EUR").amount)
        assertEquals(-13L, Money.ofDecimal(BigDecimal("-0.125"), "EUR").amount)
        assertEquals(-12L, Money.ofDecimal(BigDecimal("-0.124"), "EUR").amount)
        assertEquals(1234L, Money.ofDecimal(BigDecimal("12.34"), "EUR").amount)
        assertEquals(1000L, Money.ofDecimal(BigDecimal("9.995"), "EUR").amount)
        assertEquals(50000L, Money.ofDecimal(BigDecimal("500"), "JPY").amount)
        assertEquals(50000L, Money.ofDecimal(BigDecimal("500.004"), "JPY").amount)
    }

    @Test
    fun `ofDecimal is exact on a value a double would get wrong`() {
        assertEquals(1999L, Money.ofDecimal(BigDecimal("19.99"), "EUR").amount)
        assertEquals(10L, Money.ofDecimal(BigDecimal("0.1"), "EUR").amount)
        // 1.005 is 1.00499999... as a double; as a BigDecimal it is a tie and rounds up
        assertEquals(101L, Money.ofDecimal(BigDecimal("1.005"), "EUR").amount)
    }

    @Test
    fun `ofDecimal refuses a fractional zero-decimal amount`() {
        assertThrows<IllegalArgumentException> { Money.ofDecimal(BigDecimal("500.5"), "JPY") }
        assertThrows<IllegalArgumentException> { Money.ofDecimal(BigDecimal("0.01"), "JPY") }
    }

    @Test
    fun `ofMinorUnits scales zero-decimal currencies`() {
        assertEquals(50000L, Money.ofMinorUnits(500, "JPY").amount)
        assertEquals(500L, Money.ofMinorUnits(500, "EUR").amount)
        assertEquals(500L, Money.ofMinorUnits(500, "JPY").toMinorUnits())
        assertEquals(1234L, Money.ofMinorUnits(1234, "EUR").toMinorUnits())
    }

    @Test
    fun `zero-decimal currency must be whole units`() {
        assertThrows<IllegalArgumentException> { Money(50050, "JPY") }
        assertThrows<IllegalArgumentException> { Money(1, "JPY") }
        assertThrows<IllegalArgumentException> { Money(-1, "KRW") }
        Money(50000, "JPY")
        Money(0, "JPY")
    }

    @Test
    fun `three-decimal and unknown currencies are rejected`() {
        assertFalse(Currencies.isSupported("KWD"))
        assertThrows<IllegalArgumentException> { Money(1000, "KWD") }
        assertThrows<IllegalArgumentException> { Money.ofMinorUnits(1000, "KWD") }
        assertThrows<IllegalArgumentException> { Money.ofDecimal(BigDecimal.ONE, "BHD") }
        assertThrows<IllegalArgumentException> { Money(100, "XXX") }
        assertThrows<IllegalArgumentException> { Money(100, "eur") }
        assertThrows<IllegalArgumentException> { Money(100, "") }
    }

    @Test
    fun `Long overflow throws`() {
        assertThrows<ArithmeticException> { Money.ofMinorUnits(Long.MAX_VALUE, "JPY") }
        assertThrows<ArithmeticException> { Money.ofMinorUnits(Long.MIN_VALUE, "JPY") }
        assertThrows<ArithmeticException> { Money.ofDecimal(BigDecimal("92233720368547758.08"), "EUR") }
        assertThrows<ArithmeticException> { Money.ofDecimal(BigDecimal("1e30"), "EUR") }
        // the edge itself is representable
        assertEquals(Long.MAX_VALUE, Money.ofDecimal(BigDecimal("92233720368547758.07"), "EUR").amount)
        assertEquals(Long.MAX_VALUE / 100, Money.ofMinorUnits(Long.MAX_VALUE / 100, "JPY").toMinorUnits())
    }

    @Test
    fun `isZero equality and toString`() {
        assertTrue(Money(0, "EUR").isZero())
        assertFalse(Money(1, "EUR").isZero())
        assertEquals(Money(100, "EUR"), Money(100, "EUR"))
        assertEquals(Money(100, "EUR").hashCode(), Money(100, "EUR").hashCode())
        assertNotEquals(Money(100, "EUR"), Money(100, "USD"))
        assertNotEquals(Money(100, "EUR"), Money(101, "EUR"))
        assertEquals("12.34 EUR", Money(1234, "EUR").toString())
        assertEquals("500 JPY", Money(50000, "JPY").toString())
    }

    @Test
    fun `decimal round trip for every supported currency`() {
        for (code in Currencies.all()) {
            val m = Money.ofMinorUnits(12345, code)
            assertEquals(m, Money.ofDecimal(BigDecimal(m.toDecimalString()), code), code)
            assertEquals(12345L, m.toMinorUnits(), code)
        }
    }
}
