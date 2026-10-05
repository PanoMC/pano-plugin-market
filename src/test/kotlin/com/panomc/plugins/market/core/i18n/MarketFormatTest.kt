package com.panomc.plugins.market.i18n

import com.panomc.plugins.market.core.time.Clock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class MarketFormatTest {
    private val bundles = listOf("tr", "en-US", "ru").associateWith {
        MarketI18n.flatten(File("src/locales/core/$it.json").readText())
    }
    private val clock = object : Clock {
        override fun now() = 0L
    }

    private fun i18n(override: Map<String, String> = emptyMap(), bundles: Map<String, Map<String, String>> = this.bundles) =
        MarketI18n(bundles, { l -> if (l == "en-US") override else emptyMap() }, clock) { }

    private fun fmt(zone: String = "UTC", credit: String = "Coins", i: MarketI18n = i18n()) = MarketFormat(i, { zone }, { credit })

    @Test
    fun `EUR in the three locales`() = runBlocking {
        val f = fmt()
        assertEquals("€1,234,567.89", f.money(123456789, "EUR", "en-US"))
        assertEquals("1.234.567,89 €", f.money(123456789, "EUR", "tr"))
        assertEquals("1 234 567,89 €", f.money(123456789, "EUR", "ru"))
        assertEquals("€0.05", f.money(5, "EUR", "en-US"))
        assertEquals("€0.00", f.money(0, "EUR", "en-US"))
        assertEquals("€999.00", f.money(99900, "EUR", "en-US"))
        assertEquals("€1,000.00", f.money(100000, "EUR", "en-US"))
    }

    @Test
    fun `JPY has no fraction`() = runBlocking {
        val f = fmt()
        assertEquals("¥1,234,567", f.money(1234567, "JPY", "en-US"))
        assertEquals("¥5", f.money(5, "JPY", "en-US"))
        assertEquals("999 ¥", f.money(999, "JPY", "tr"))
    }

    @Test
    fun `negative amounts get a leading minus`() = runBlocking {
        val f = fmt()
        assertEquals("-€12.34", f.money(-1234, "EUR", "en-US"))
        assertEquals("-€0.01", f.money(-1, "EUR", "en-US"))
        assertEquals("-1.234,00 €", f.money(-123400, "EUR", "tr"))
        assertEquals("-¥1,000", f.money(-1000, "JPY", "en-US"))
    }

    @Test
    fun `Long extremes do not overflow`() = runBlocking {
        val f = fmt()
        assertEquals("€92,233,720,368,547,758.07", f.money(Long.MAX_VALUE, "EUR", "en-US"))
        assertEquals("-€92,233,720,368,547,758.08", f.money(Long.MIN_VALUE, "EUR", "en-US"))
        assertEquals("€9,007,199,254,740,993.00", f.money(900719925474099300, "EUR", "en-US"))
    }

    @Test
    fun `unknown currency falls back to the code and two digits`() = runBlocking {
        assertEquals("XXX1.50", fmt().money(150, "XXX", "en-US"))
    }

    @Test
    fun `credits and percent`() = runBlocking {
        val f = fmt()
        assertEquals("1,250.50 Coins", f.credits(125050, "en-US"))
        assertEquals("-1,25 Coins", f.credits(-125, "tr"))
        assertEquals("12.5%", f.percent(1250, "en-US"))
        assertEquals("18%", f.percent(1800, "en-US"))
        assertEquals("0,05%", f.percent(5, "tr"))
        assertEquals("7.99%", f.percent(799, "en-US"))
        assertEquals("-2.5%", f.percent(-250, "en-US"))
        assertEquals("1,00", fmt(credit = "").credits(100, "ru"))
    }

    @Test
    fun `date patterns per locale and zone`() = runBlocking {
        val ms = 1_767_225_600_000L + 23 * 3_600_000L + 5 * 60_000L // 2026-01-01 23:05 UTC
        assertEquals("Jan 1, 2026", fmt("UTC").date(ms, "en-US"))
        assertEquals("01.01.2026", fmt("UTC").date(ms, "tr"))
        assertEquals("Jan 1, 2026 23:05", fmt("UTC").dateTime(ms, "en-US"))
        assertEquals("01.01.2026 23:05", fmt("UTC").dateTime(ms, "ru"))
        assertEquals("02.01.2026 02:05", fmt("Europe/Istanbul").dateTime(ms, "tr"))
        assertEquals("Jan 1, 2026 18:05", fmt("America/New_York").dateTime(ms, "en-US"))
        assertEquals("Jan 1, 2026", fmt("America/New_York").date(ms, "en-US"))
    }

    @Test
    fun `invalid pattern or zone falls back`() = runBlocking {
        val ms = 1_767_225_600_000L
        val i = i18n(mapOf("plugins.pano-plugin-market.server-format.date" to "ZZ{bad"))
        assertEquals("2026-01-01", fmt("UTC", i = i).date(ms, "en-US"))
        // invalid zone => JVM default, must not throw
        assertEquals(11, fmt("Not/AZone").date(ms, "en-US").length)
    }

    @Test
    fun `an admin override of the money pattern is honoured`() = runBlocking {
        val i = i18n(mapOf("plugins.pano-plugin-market.server-format.money" to "{amount} {symbol}!"))
        assertEquals("1.00 €!", fmt(i = i).money(100, "EUR", "en-US"))
    }

    @Test
    fun `missing server-format keys use built in defaults`() = runBlocking {
        val i = i18n(bundles = mapOf("en-US" to emptyMap()))
        assertEquals("\$1,000.50", fmt(i = i).money(100050, "USD", "en-US"))
    }
}
