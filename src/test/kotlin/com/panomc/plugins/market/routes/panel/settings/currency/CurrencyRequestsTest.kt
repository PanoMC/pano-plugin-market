package com.panomc.plugins.market.routes.panel.settings.currency

import com.panomc.plugins.market.db.model.CurrencyRateMode
import com.panomc.plugins.market.error.InvalidSettings
import com.panomc.plugins.market.service.CurrencyRateService
import com.panomc.plugins.market.service.CurrencyRates
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import com.panomc.plugins.market.support.ErrorBodies

/** The body of `PUT /settings/currencies`, the rate arithmetic and the response shape (04 section 8). */
class CurrencyRequestsTest {
    private fun body(json: String) = parseCurrencyRatesBody(JsonObject(json))

    private fun errors(json: String?): Map<String, Any?> {
        val e = assertThrows(InvalidSettings::class.java) { parseCurrencyRatesBody(json?.let { JsonObject(it) }) }

        return ErrorBodies.details(e).getJsonObject("fieldErrors").map
    }

    @Test
    fun `a body is a list of entries`() {
        val entries = body("""{"rates":[{"currency":"USD","mode":"MANUAL","rate":0.025},{"currency":"EUR","mode":"AUTO"}]}""")

        assertEquals(2, entries.size)
        assertEquals("USD", entries[0].currency)
        assertEquals(CurrencyRateMode.MANUAL, entries[0].mode)
        assertEquals(BigDecimal("0.025"), entries[0].rate)
        assertEquals(CurrencyRateMode.AUTO, entries[1].mode)
        assertNull(entries[1].rate)
        assertEquals(0, body("""{"rates":[]}""").size)
    }

    @Test
    fun `an integer rate and a large decimal keep their value`() {
        assertEquals(BigDecimal("32"), body("""{"rates":[{"currency":"TRY","mode":"MANUAL","rate":32}]}""")[0].rate)
        assertEquals(0, BigDecimal("1234.5678").compareTo(body("""{"rates":[{"currency":"TRY","mode":"MANUAL","rate":1234.5678}]}""")[0].rate))
    }

    @Test
    fun `the shape is judged`() {
        assertEquals(mapOf("rates" to "REQUIRED"), errors(null))
        assertEquals(mapOf("rates" to "REQUIRED"), errors("{}"))
        assertEquals(mapOf("rates" to "REQUIRED"), errors("""{"rates":"x"}"""))
        assertEquals(mapOf("other" to "UNKNOWN_PROPERTY", "rates" to "REQUIRED"), errors("""{"other":1}"""))
        assertEquals(mapOf("rates.0" to "INVALID"), errors("""{"rates":[1]}"""))
        assertEquals(mapOf("rates.0.extra" to "UNKNOWN_PROPERTY"), errors("""{"rates":[{"currency":"USD","mode":"AUTO","extra":1}]}"""))
        assertEquals(mapOf("rates.0.mode" to "UNKNOWN_VALUE"), errors("""{"rates":[{"currency":"USD","mode":"manual"}]}"""))
        assertEquals(mapOf("rates.0.currency" to "INVALID"), errors("""{"rates":[{"currency":5,"mode":"AUTO"}]}"""))
        assertEquals(mapOf("rates.0.rate" to "INVALID"), errors("""{"rates":[{"currency":"USD","mode":"MANUAL","rate":"0.02"}]}"""))
        assertEquals(mapOf("rates" to "TOO_MANY"), errors("""{"rates":[${(1..101).joinToString(",") { """{"currency":"USD","mode":"AUTO"}""" }}]}"""))
    }

    @Test
    fun `rate arithmetic`() {
        assertEquals(BigDecimal("0.0250000000"), CurrencyRates.parse("0.025"))
        assertEquals(BigDecimal("1.2345678901"), CurrencyRates.parse("1.23456789005"), "half up at the tenth digit")
        assertEquals(BigDecimal("0.0000000001"), CurrencyRates.parse("1E-10"))
        assertEquals(BigDecimal("9999999999.0000000000"), CurrencyRates.parse("9999999999"))
        assertNull(CurrencyRates.parse("0"))
        assertNull(CurrencyRates.parse("-1"))
        assertNull(CurrencyRates.parse("1E-11"), "rounds to zero")
        assertNull(CurrencyRates.parse("10000000000"))
        assertNull(CurrencyRates.parse("abc"))
        assertNull(CurrencyRates.parse(null))
        assertNull(CurrencyRates.parse(""))
    }

    @Test
    fun `the response is the contract shape`() {
        val json = currencyRatesJson(
            CurrencyRateService.View(
                "DISPLAY", "TRY",
                listOf(
                    CurrencyRateService.RateView("USD", BigDecimal("0.0250000000"), CurrencyRateMode.AUTO, 5),
                    CurrencyRateService.RateView("EUR", null, CurrencyRateMode.AUTO, null),
                    CurrencyRateService.RateView("JPY", BigDecimal("4.5000000000"), CurrencyRateMode.MANUAL, 9)
                )
            )
        )

        assertEquals(setOf("currencyMode", "baseCurrency", "rates"), json.keys)
        assertEquals("DISPLAY", json["currencyMode"])
        assertEquals("TRY", json["baseCurrency"])
        @Suppress("UNCHECKED_CAST")
        val rates = json["rates"] as List<Map<String, Any?>>
        assertEquals(listOf("currency", "rate", "mode", "fetchedAt"), rates[0].keys.toList())
        assertEquals(mapOf("currency" to "USD", "rate" to 0.025, "mode" to "AUTO", "fetchedAt" to 5L), rates[0])
        assertEquals(mapOf("currency" to "EUR", "rate" to null, "mode" to "AUTO", "fetchedAt" to null), rates[1])
        assertEquals(4.5, rates[2]["rate"])
    }
}
