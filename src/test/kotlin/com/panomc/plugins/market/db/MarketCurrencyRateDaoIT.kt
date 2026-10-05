package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.impl.MarketCurrencyRateDaoImpl
import com.panomc.plugins.market.db.model.CurrencyRateMode
import com.panomc.plugins.market.db.model.MarketCurrencyRate
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** `market_currency_rate` (01 section 2.9): the `DECIMAL(20,10)` round trip, upsert on `uq_currency`, finders. */
class MarketCurrencyRateDaoIT : MarketDaoITBase() {
    private val dao = MarketCurrencyRateDaoImpl()

    @Test
    fun `a decimal rate with ten fraction digits round trips exactly`(): Unit = runBlocking {
        val rate = BigDecimal("32.1234567891")
        val id = dao.upsert(
            MarketCurrencyRate(
                currency = "TRY", rate = rate, mode = CurrencyRateMode.MANUAL, fetchedAt = 1_700_000_005_000,
                createdAt = 1_700_000_000_000, updatedAt = 1_700_000_001_000
            ),
            pool
        )
        val read = dao.getById(id, pool)!!
        assertEquals("TRY", read.currency)
        assertEquals(0, rate.compareTo(read.rate), "stored ${read.rate}")
        assertEquals("32.1234567891", read.rate.setScale(10).toPlainString())
        assertEquals(CurrencyRateMode.MANUAL, read.mode)
        assertEquals(1_700_000_005_000, read.fetchedAt)
        assertEquals(1_700_000_000_000, read.createdAt)
        assertEquals(1_700_000_001_000, read.updatedAt)
    }

    @Test
    fun `very small and very large rates keep their value, defaults apply`(): Unit = runBlocking {
        val small = dao.getById(dao.upsert(MarketCurrencyRate(currency = "BTC", rate = BigDecimal("0.0000000001")), pool), pool)!!
        assertEquals("0.0000000001", small.rate.setScale(10).toPlainString())
        val large = dao.getById(dao.upsert(MarketCurrencyRate(currency = "IDR", rate = BigDecimal("9999999999.9999999999")), pool), pool)!!
        assertEquals("9999999999.9999999999", large.rate.setScale(10).toPlainString())
        assertEquals(CurrencyRateMode.AUTO, small.mode)
        assertNull(small.fetchedAt)
        assertNull(dao.getById(99999, pool))
    }

    @Test
    fun `a second upsert of the currency overwrites rate mode and fetchedAt and keeps the id`(): Unit = runBlocking {
        val id = dao.upsert(MarketCurrencyRate(currency = "EUR", rate = BigDecimal("0.9"), mode = CurrencyRateMode.AUTO, fetchedAt = 5, createdAt = 10, updatedAt = 10), pool)
        val again = dao.upsert(MarketCurrencyRate(currency = "EUR", rate = BigDecimal("0.95"), mode = CurrencyRateMode.MANUAL, fetchedAt = null, createdAt = 99, updatedAt = 20), pool)
        assertEquals(id, again)
        assertEquals(1L, count("market_currency_rate"))
        val read = dao.getByCurrency("EUR", pool)!!
        assertEquals(0, BigDecimal("0.95").compareTo(read.rate))
        assertEquals(CurrencyRateMode.MANUAL, read.mode)
        assertNull(read.fetchedAt)
        assertEquals(10L, read.createdAt)
        assertEquals(20L, read.updatedAt)
    }

    @Test
    fun `getAll is ordered by currency and delete removes one`(): Unit = runBlocking {
        for (c in listOf("USD", "EUR", "GBP")) dao.upsert(MarketCurrencyRate(currency = c, rate = BigDecimal.ONE), pool)
        assertEquals(listOf("EUR", "GBP", "USD"), dao.getAll(pool).map { it.currency })
        assertNull(dao.getByCurrency("JPY", pool))
        assertEquals(1, dao.deleteByCurrency("GBP", pool))
        assertEquals(0, dao.deleteByCurrency("GBP", pool))
        assertEquals(listOf("EUR", "USD"), dao.getAll(pool).map { it.currency })
    }
}
