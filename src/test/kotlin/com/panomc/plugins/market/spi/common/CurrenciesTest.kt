package com.panomc.plugins.market.spi.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import com.panomc.plugins.market.core.money.Currencies as MarketCurrencies

class CurrenciesTest {
    @Test
    fun `exponents`() {
        assertEquals(2, Currencies.exponent("EUR"))
        assertEquals(2, Currencies.exponent("TRY"))
        assertEquals(0, Currencies.exponent("JPY"))
        assertEquals(0, Currencies.exponent("KRW"))
        assertEquals(0, Currencies.exponent("VND"))
    }

    @Test
    fun `supported is exact upper-case ISO with exponent 0 or 2`() {
        for (c in listOf("TRY", "USD", "EUR", "GBP", "JPY", "RUB")) assertTrue(Currencies.isSupported(c), c)
        for (c in listOf("KWD", "BHD", "JOD", "OMR", "TND", "IQD", "LYD", "CLF", "XXX", "eur", "EU", "EURO", "")) {
            assertFalse(Currencies.isSupported(c), c)
        }
        assertThrows<IllegalArgumentException> { Currencies.exponent("KWD") }
        assertThrows<IllegalArgumentException> { Currencies.exponent("nope") }
    }

    @Test
    fun `symbols`() {
        assertEquals("₺", Currencies.symbol("TRY"))
        assertEquals("$", Currencies.symbol("USD"))
        assertEquals("€", Currencies.symbol("EUR"))
        assertEquals("£", Currencies.symbol("GBP"))
        assertEquals("CHF", Currencies.symbol("CHF"))
        assertEquals("ZMW", Currencies.symbol("ZMW"))
        assertThrows<IllegalArgumentException> { Currencies.symbol("KWD") }
    }

    @Test
    fun `table is sorted unique and every entry answers`() {
        val all = Currencies.all()
        assertEquals(all.sorted(), all)
        assertEquals(all.toSet().size, all.size)
        assertTrue(all.size > 100)
        for (c in all) {
            assertEquals(3, c.length)
            assertTrue(Currencies.exponent(c) in listOf(0, 2), c)
            assertTrue(Currencies.symbol(c).isNotEmpty(), c)
        }
    }

    @Test
    fun `market facade answers exactly like the spi table`() {
        assertEquals(Currencies.all(), MarketCurrencies.all())
        for (c in Currencies.all() + listOf("KWD", "XXX", "eur")) {
            assertEquals(Currencies.isSupported(c), MarketCurrencies.isSupported(c), c)
            if (Currencies.isSupported(c)) {
                assertEquals(Currencies.exponent(c), MarketCurrencies.exponent(c), c)
                assertEquals(Currencies.symbol(c), MarketCurrencies.symbol(c), c)
            } else {
                assertThrows<IllegalArgumentException> { MarketCurrencies.exponent(c) }
            }
        }
    }
}
