package com.panomc.plugins.market.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MoneyUtilTest {
    @Test
    fun `toMinor converts whole and fractional amounts`() {
        assertEquals(0L, MoneyUtil.toMinor(0.0))
        assertEquals(100L, MoneyUtil.toMinor(1.0))
        assertEquals(1999L, MoneyUtil.toMinor(19.99))
        assertEquals(5L, MoneyUtil.toMinor(0.05))
    }

    @Test
    fun `toMinor rounds away floating point noise`() {
        // 0.1 + 0.2 = 0.30000000000000004 and 1.005 * 100 = 100.49999999999999 in binary floating point
        assertEquals(30L, MoneyUtil.toMinor(0.1 + 0.2))
        assertEquals(0L, MoneyUtil.toMinor(0.0049))
        assertEquals(1L, MoneyUtil.toMinor(0.0051))
        assertEquals(0L, MoneyUtil.toMinor(0.0001))
    }

    @Test
    fun `toMinor rounds half up`() {
        assertEquals(3L, MoneyUtil.toMinor(0.025))
        assertEquals(2L, MoneyUtil.toMinor(0.015))
    }

    @Test
    fun `toMinor handles negative amounts`() {
        assertEquals(-1050L, MoneyUtil.toMinor(-10.5))
        assertEquals(0L, MoneyUtil.toMinor(-0.001))
    }

    @Test
    fun `toMinor handles large amounts`() {
        assertEquals(99999999900L, MoneyUtil.toMinor(999999999.0))
    }

    @Test
    fun `toDecimal converts minor units`() {
        assertEquals(0.0, MoneyUtil.toDecimal(0L))
        assertEquals(19.99, MoneyUtil.toDecimal(1999L))
        assertEquals(0.01, MoneyUtil.toDecimal(1L))
        assertEquals(-10.5, MoneyUtil.toDecimal(-1050L))
    }

    @Test
    fun `round trip is stable for two decimal amounts`() {
        listOf(0L, 1L, 7L, 99L, 100L, 1999L, 123456L, 99999999L).forEach { minor ->
            assertEquals(minor, MoneyUtil.toMinor(MoneyUtil.toDecimal(minor)))
        }
    }
}
