package com.panomc.plugins.market.service

import com.panomc.plugins.market.config.CurrencyMode
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.CurrencyRateMode
import com.panomc.plugins.market.db.model.MarketCurrencyRate
import com.panomc.plugins.market.error.ExchangeRateFetchFailed
import com.panomc.plugins.market.error.InvalidSettings
import com.panomc.plugins.market.support.Fixtures
import com.panomc.plugins.market.support.TestWiring
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import com.panomc.plugins.market.support.ErrorBodies

/**
 * `CurrencyRateService` on a real MariaDB (MK-064, 04 section 8 `GET / PUT / POST refresh /settings/currencies`): the stored
 * `market_currency_rate` rows, `MANUAL` versus `AUTO`, validation without partial writes, the provider being down, and the
 * stored rate being the one the storefront converts with (DISPLAY mode).
 */
class CurrencyRateIT : MarketDaoITBase() {
    private val w by lazy { TestWiring(pool) }
    private val fx by lazy { Fixtures(w) }

    @Volatile
    private var config = MarketConfig(currency = "TRY", currencyMode = CurrencyMode.DISPLAY, additionalCurrencies = listOf("USD", "EUR"))

    /** What the provider answers; `null` = unreachable. */
    @Volatile
    private var provider: Map<String, BigDecimal>? = mapOf("USD" to BigDecimal("0.0250000000"), "EUR" to BigDecimal("0.0230"), "GBP" to BigDecimal("0.02"))

    @Volatile
    private var asked = mutableListOf<String>()

    private val service by lazy {
        CurrencyRateService(w.db, w.clock, { config }, w.currencyRates, { base -> asked.add(base); provider })
    }

    override suspend fun resetState() {
        super.resetState()
        config = MarketConfig(currency = "TRY", currencyMode = CurrencyMode.DISPLAY, additionalCurrencies = listOf("USD", "EUR"))
        provider = mapOf("USD" to BigDecimal("0.0250000000"), "EUR" to BigDecimal("0.0230"), "GBP" to BigDecimal("0.02"))
        asked = mutableListOf()
    }

    private fun entry(currency: String?, mode: CurrencyRateMode?, rate: String? = null) = CurrencyRateEntry(currency, mode, rate?.let { BigDecimal(it) })

    private fun fieldErrors(e: InvalidSettings): JsonObject = ErrorBodies.details(e).getJsonObject("fieldErrors")

    private suspend fun stored(code: String) = w.currencyRates.getByCurrency(code, pool)

    private suspend fun rejected(vararg entries: CurrencyRateEntry): JsonObject =
        fieldErrors(assertThrows(InvalidSettings::class.java) { runBlocking { service.update(entries.toList(), pool) } })

    @Test
    fun `the view lists the offered currencies without a row first and stored extras after`(): Unit = runBlocking {
        w.currencyRates.upsert(MarketCurrencyRate(currency = "GBP", rate = BigDecimal("0.02"), mode = CurrencyRateMode.MANUAL, fetchedAt = 5), pool)
        w.currencyRates.upsert(MarketCurrencyRate(currency = "EUR", rate = BigDecimal("0.023"), mode = CurrencyRateMode.AUTO, fetchedAt = 7), pool)

        val view = service.view(pool)

        assertEquals("DISPLAY", view.currencyMode)
        assertEquals("TRY", view.baseCurrency)
        assertEquals(listOf("USD", "EUR", "GBP"), view.rates.map { it.currency })
        assertNull(view.rates[0].rate)
        assertEquals(CurrencyRateMode.AUTO, view.rates[0].mode)
        assertNull(view.rates[0].fetchedAt)
        assertEquals(0, BigDecimal("0.023").compareTo(view.rates[1].rate))
        assertEquals(7L, view.rates[1].fetchedAt)
        assertEquals(CurrencyRateMode.MANUAL, view.rates[2].mode)
    }

    @Test
    fun `a manual rate is stored at ten fraction digits with the time it was set`(): Unit = runBlocking {
        w.clock.set(1_800_000_000_000)

        val view = service.update(listOf(entry("usd", CurrencyRateMode.MANUAL, "0.02512345678")), pool)

        val row = stored("USD")!!
        assertEquals("0.0251234568", row.rate.setScale(10).toPlainString(), "half up at scale 10")
        assertEquals(CurrencyRateMode.MANUAL, row.mode)
        assertEquals(1_800_000_000_000, row.fetchedAt)
        assertEquals(0, BigDecimal("0.0251234568").compareTo(view.rates.first { it.currency == "USD" }.rate))
        assertTrue(asked.isEmpty(), "a manual rate never asks the provider")

        // overwrite with another manual value
        service.update(listOf(entry("USD", CurrencyRateMode.MANUAL, "0.03")), pool)
        assertEquals("0.0300000000", stored("USD")!!.rate.setScale(10).toPlainString())
        assertEquals(1, w.currencyRates.getAll(pool).size)
    }

    @Test
    fun `invalid entries are refused with field errors and nothing is written`(): Unit = runBlocking {
        val errors = rejected(
            entry("XXX", CurrencyRateMode.MANUAL, "1"),          // 0 unknown
            entry("TRY", CurrencyRateMode.MANUAL, "1"),          // 1 base currency
            entry("USD", CurrencyRateMode.MANUAL, "0.02"),       // 2 fine
            entry("usd", CurrencyRateMode.MANUAL, "0.03"),       // 3 duplicate of 2
            entry("EUR", CurrencyRateMode.MANUAL, null),         // 4 rate missing
            entry("GBP", CurrencyRateMode.MANUAL, "0"),          // 5 zero
            entry("CHF", CurrencyRateMode.MANUAL, "-1"),         // 6 negative
            entry("JPY", CurrencyRateMode.MANUAL, "99999999999"), // 7 too large
            entry("CAD", null, "1"),                             // 8 no mode
            entry("AUD", CurrencyRateMode.MANUAL, "0.00000000001"), // 9 rounds to zero
            entry(null, CurrencyRateMode.AUTO)                   // 10 no currency
        )

        assertEquals(
            mapOf(
                "rates.0.currency" to "UNKNOWN_CURRENCY", "rates.1.currency" to "IS_BASE_CURRENCY", "rates.3.currency" to "DUPLICATE",
                "rates.4.rate" to "REQUIRED", "rates.5.rate" to "OUT_OF_RANGE", "rates.6.rate" to "OUT_OF_RANGE", "rates.7.rate" to "OUT_OF_RANGE",
                "rates.8.mode" to "REQUIRED", "rates.9.rate" to "OUT_OF_RANGE", "rates.10.currency" to "UNKNOWN_CURRENCY"
            ),
            errors.map
        )
        assertEquals(0, w.currencyRates.getAll(pool).size, "the valid entry 2 was not written either")
    }

    @Test
    fun `an auto entry takes the provider rate now`(): Unit = runBlocking {
        w.clock.set(1_800_000_000_000)

        service.update(listOf(entry("USD", CurrencyRateMode.AUTO), entry("EUR", CurrencyRateMode.MANUAL, "0.5")), pool)

        assertEquals(listOf("TRY"), asked, "one provider request for the whole table")
        assertEquals("0.0250000000", stored("USD")!!.rate.setScale(10).toPlainString())
        assertEquals(CurrencyRateMode.AUTO, stored("USD")!!.mode)
        assertEquals(1_800_000_000_000, stored("USD")!!.fetchedAt)
        assertEquals("0.5000000000", stored("EUR")!!.rate.setScale(10).toPlainString())
    }

    @Test
    fun `switching to auto while the provider is down keeps the stored rate and its time`(): Unit = runBlocking {
        service.update(listOf(entry("USD", CurrencyRateMode.MANUAL, "0.04")), pool)
        val before = stored("USD")!!
        w.clock.advance(60_000)
        provider = null

        service.update(listOf(entry("USD", CurrencyRateMode.AUTO)), pool)

        val after = stored("USD")!!
        assertEquals(CurrencyRateMode.AUTO, after.mode)
        assertEquals(0, before.rate.compareTo(after.rate))
        assertEquals(before.fetchedAt, after.fetchedAt)
    }

    @Test
    fun `an auto entry with neither a provider rate nor a stored one is refused`(): Unit = runBlocking {
        provider = null

        val errors = rejected(entry("USD", CurrencyRateMode.MANUAL, "0.04"), entry("EUR", CurrencyRateMode.AUTO))

        assertEquals(mapOf("rates.1.rate" to "RATE_UNAVAILABLE"), errors.map)
        assertEquals(0, w.currencyRates.getAll(pool).size)
    }

    @Test
    fun `refresh updates auto rows and leaves manual rows alone`(): Unit = runBlocking {
        service.update(listOf(entry("EUR", CurrencyRateMode.MANUAL, "0.5")), pool)
        w.currencyRates.upsert(MarketCurrencyRate(currency = "GBP", rate = BigDecimal("0.5"), mode = CurrencyRateMode.AUTO, fetchedAt = 1), pool)
        w.clock.set(1_900_000_000_000)

        val view = service.refresh(pool)

        assertEquals("0.0250000000", stored("USD")!!.rate.setScale(10).toPlainString(), "offered but never stored: created")
        assertEquals(CurrencyRateMode.AUTO, stored("USD")!!.mode)
        assertEquals("0.5000000000", stored("EUR")!!.rate.setScale(10).toPlainString(), "manual stays")
        assertEquals(CurrencyRateMode.MANUAL, stored("EUR")!!.mode)
        assertEquals("0.0200000000", stored("GBP")!!.rate.setScale(10).toPlainString(), "a stored auto row outside the offered list is refreshed too")
        assertEquals(1_900_000_000_000, stored("GBP")!!.fetchedAt)
        assertEquals(listOf("USD", "EUR", "GBP"), view.rates.map { it.currency })
    }

    @Test
    fun `refresh with the provider down fails and keeps every row`(): Unit = runBlocking {
        w.currencyRates.upsert(MarketCurrencyRate(currency = "USD", rate = BigDecimal("0.04"), mode = CurrencyRateMode.AUTO, fetchedAt = 3), pool)
        provider = null

        assertThrows(ExchangeRateFetchFailed::class.java) { runBlocking { service.refresh(pool) } }

        assertEquals("0.0400000000", stored("USD")!!.rate.setScale(10).toPlainString())
        assertEquals(3L, stored("USD")!!.fetchedAt)
    }

    @Test
    fun `refresh skips currencies the provider does not know and rates it cannot use`(): Unit = runBlocking {
        provider = mapOf("USD" to BigDecimal("0.025"), "EUR" to BigDecimal("0.0000000000001"))

        service.refresh(pool)

        assertEquals("0.0250000000", stored("USD")!!.rate.setScale(10).toPlainString())
        assertNull(stored("EUR"), "a rate that rounds to zero is never stored")
    }

    @Test
    fun `positive rates is the table the pricing code gets`(): Unit = runBlocking {
        service.update(listOf(entry("USD", CurrencyRateMode.MANUAL, "0.025"), entry("EUR", CurrencyRateMode.MANUAL, "0.5")), pool)
        Fixtures.setColumns(pool, "market_currency_rate", stored("EUR")!!.id, mapOf("rate" to 0))

        assertEquals(setOf("USD"), service.positiveRates(pool).keys)
    }

    @Test
    fun `the stored rate is the one the storefront converts a DISPLAY price with`(): Unit = runBlocking {
        val store = StoreQueryService(
            { config }, w.clock, w.categories, w.products, w.variants, w.prices, w.fields, w.bundleItems, w.discounts,
            w.currencyRates, w.comparisons, w.orderItems, w.entitlements
        )
        fx.product(slug = "x", price = 20000)

        service.update(listOf(entry("USD", CurrencyRateMode.MANUAL, "0.05")), pool)
        assertEquals(10.0, store.products(ProductListQuery(currency = "USD"), StoreViewer.GUEST, pool).getJsonArray("products").getJsonObject(0).getDouble("price"))

        service.update(listOf(entry("USD", CurrencyRateMode.MANUAL, "0.1")), pool)
        assertEquals(20.0, store.products(ProductListQuery(currency = "USD"), StoreViewer.GUEST, pool).getJsonArray("products").getJsonObject(0).getDouble("price"))
    }
}
