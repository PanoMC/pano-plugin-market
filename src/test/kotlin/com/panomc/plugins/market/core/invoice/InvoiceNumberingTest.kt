package com.panomc.plugins.market.core.invoice

import com.panomc.plugins.market.db.model.InvoiceType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.ZoneId

/** Document numbers and series (12 section 6.2; T-INV-4 of 12 section 12). */
class InvoiceNumberingTest {
    private val utc = ZoneId.of("UTC")

    /** 2026-12-31T23:30:00Z. */
    private val newYearsEve = 1_798_759_800_000L

    @Test
    fun `the sequence is padded with zeros to 6 digits`() {
        assertEquals("INV-2026-000001", InvoiceNumbering.format("INV", 1, 1_780_000_000_000L, utc))
        assertEquals("INV-2026-000042", InvoiceNumbering.format("INV", 42, 1_780_000_000_000L, utc))
        assertEquals("CN-2026-123456", InvoiceNumbering.format("CN", 123_456, 1_780_000_000_000L, utc))
        assertEquals("TEST-2026-000007", InvoiceNumbering.format("TEST", 7, 1_780_000_000_000L, utc))
    }

    @Test
    fun `beyond 999999 the number simply grows and still fits the column`() {
        assertEquals("INV-2026-999999", InvoiceNumbering.format("INV", 999_999, 1_780_000_000_000L, utc))
        assertEquals("INV-2026-1000000", InvoiceNumbering.format("INV", 1_000_000, 1_780_000_000_000L, utc))

        val longest = InvoiceNumbering.format("ABCD1234", InvoiceNumbering.MAX_SEQUENCE, 1_780_000_000_000L, utc)

        assertEquals(29, longest.length)
        assertEquals(true, longest.length <= 32, "market_invoice.number is VARCHAR(32)")
    }

    @Test
    fun `the year is the year of the issue time in the store time zone`() {
        // 2026-12-31 23:30 UTC is already 2027 in Istanbul (UTC+3) and still 2026 in UTC and in New York
        assertEquals("INV-2026-000001", InvoiceNumbering.format("INV", 1, newYearsEve, utc))
        assertEquals("INV-2027-000001", InvoiceNumbering.format("INV", 1, newYearsEve, ZoneId.of("Europe/Istanbul")))
        assertEquals("INV-2026-000001", InvoiceNumbering.format("INV", 1, newYearsEve, ZoneId.of("America/New_York")))

        // one millisecond before and after local midnight in Istanbul (2026-12-31T21:00:00Z)
        val midnight = 1_798_750_800_000L

        assertEquals(2026, InvoiceNumbering.year(midnight - 1, ZoneId.of("Europe/Istanbul")))
        assertEquals(2027, InvoiceNumbering.year(midnight, ZoneId.of("Europe/Istanbul")))
    }

    @Test
    fun `an empty or unknown store time zone is the JVM default zone`() {
        assertEquals(ZoneId.systemDefault(), InvoiceNumbering.zone(""))
        assertEquals(ZoneId.systemDefault(), InvoiceNumbering.zone("   "))
        assertEquals(ZoneId.systemDefault(), InvoiceNumbering.zone("Mars/Olympus"))
        assertEquals(ZoneId.of("Europe/Istanbul"), InvoiceNumbering.zone("Europe/Istanbul"))
        assertEquals(ZoneId.of("Europe/Istanbul"), InvoiceNumbering.zone(" Europe/Istanbul "))
    }

    @Test
    fun `a sequence value starts at 1`() {
        assertThrows(IllegalArgumentException::class.java) { InvoiceNumbering.format("INV", 0, newYearsEve, utc) }
        assertThrows(IllegalArgumentException::class.java) { InvoiceNumbering.format("INV", -3, newYearsEve, utc) }
    }

    @Test
    fun `the series follows the document type and a test-mode order is always TEST`() {
        assertEquals("INV", InvoiceNumbering.series(InvoiceType.INVOICE, false, "INV", "CN"))
        assertEquals("CN", InvoiceNumbering.series(InvoiceType.CREDIT_NOTE, false, "INV", "CN"))
        assertEquals("SHOP", InvoiceNumbering.series(InvoiceType.INVOICE, false, "SHOP", "CRN"))
        assertEquals("CRN", InvoiceNumbering.series(InvoiceType.CREDIT_NOTE, false, "SHOP", "CRN"))
        assertEquals("TEST", InvoiceNumbering.series(InvoiceType.INVOICE, true, "SHOP", "CRN"))
        assertEquals("TEST", InvoiceNumbering.series(InvoiceType.CREDIT_NOTE, true, "SHOP", "CRN"))
    }

    @Test
    fun `a configured series that is not valid falls back to the default`() {
        assertEquals("INV", InvoiceNumbering.series(InvoiceType.INVOICE, false, "inv", "CN"))
        assertEquals("INV", InvoiceNumbering.series(InvoiceType.INVOICE, false, "", "CN"))
        assertEquals("INV", InvoiceNumbering.series(InvoiceType.INVOICE, false, "TOOLONGSERIES", "CN"))
        assertEquals("INV", InvoiceNumbering.series(InvoiceType.INVOICE, false, "A/B", "CN"))
        assertEquals("INV", InvoiceNumbering.series(InvoiceType.INVOICE, false, "TEST", "CN"), "TEST is reserved")
        assertEquals("CN", InvoiceNumbering.series(InvoiceType.CREDIT_NOTE, false, "INV", "te st"))
        assertEquals("CN", InvoiceNumbering.series(InvoiceType.CREDIT_NOTE, false, "INV", "TEST"))
    }

    @Test
    fun `the counter of a series is named invoice colon series`() {
        assertEquals("invoice:INV", InvoiceNumbering.sequenceName("INV"))
        assertEquals("INV", InvoiceNumbering.seriesOfSequenceName("invoice:INV"))
        assertEquals("TEST", InvoiceNumbering.seriesOfSequenceName("invoice:TEST"))
        assertNull(InvoiceNumbering.seriesOfSequenceName("fixup:soldCount"))
        assertNull(InvoiceNumbering.seriesOfSequenceName("invoice:lower"))
        assertNull(InvoiceNumbering.seriesOfSequenceName("invoice:"))
        assertNull(InvoiceNumbering.seriesOfSequenceName("INV"))
    }
}
