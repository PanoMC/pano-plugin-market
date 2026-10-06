package com.panomc.plugins.market.util

import com.panomc.plugins.market.error.RequestValueException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The CSV format of `GET /orders/export` (04 section 7, 11 section 6.5; MK-170). */
class CsvWriterTest {
    @Test
    fun `the file starts with a UTF-8 byte order mark`() {
        assertEquals("﻿", CsvWriter().bom())
        assertEquals(listOf(0xEF, 0xBB, 0xBF), CsvWriter().bom().toByteArray(Charsets.UTF_8).map { it.toInt() and 0xFF })
    }

    @Test
    fun `text cells are always quoted, numbers are bare, rows end in CRLF`() {
        val line = CsvWriter().row(listOf(CsvValue.number(7L), CsvValue.text("Steve"), CsvValue.money(1999), CsvValue.number(2), CsvValue.bool(true)))

        assertEquals("7,\"Steve\",19.99,2,true\r\n", line)
    }

    @Test
    fun `the header names the columns as text`() {
        assertEquals("\"orderId\",\"publicId\"\r\n", CsvWriter().header(listOf("orderId", "publicId")))
    }

    @Test
    fun `a formula start is neutralised, quotes are doubled, line breaks become a space`() {
        val writer = CsvWriter()

        assertEquals("\"'=HYPERLINK(\"\"http://x\"\")\"\r\n", writer.row(listOf(CsvValue.text("=HYPERLINK(\"http://x\")"))))
        assertEquals("\"'+1\"\r\n", writer.row(listOf(CsvValue.text("+1"))))
        assertEquals("\"'-1\"\r\n", writer.row(listOf(CsvValue.text("-1"))))
        assertEquals("\"'@SUM(A1)\"\r\n", writer.row(listOf(CsvValue.text("@SUM(A1)"))))
        assertEquals("\"'\tcmd\"\r\n", writer.row(listOf(CsvValue.text("\tcmd"))))
        assertEquals("\"a b c d\"\r\n", writer.row(listOf(CsvValue.text("a\r\nb\nc\rd"))))
        assertEquals("\"\"\r\n", writer.row(listOf(CsvValue.text(null))), "a missing text is an empty quoted cell")
    }

    @Test
    fun `a delimiter inside a text cell cannot split it, whichever delimiter is chosen`() {
        for (delimiter in CsvWriter.Delimiter.entries) {
            val line = CsvWriter(delimiter).row(listOf(CsvValue.text("a,b;c\td"), CsvValue.text("x")))

            assertEquals("\"a,b;c\td\"${delimiter.char}\"x\"\r\n", line, delimiter.name)
        }
    }

    @Test
    fun `the delimiter option takes comma, semicolon and tab`() {
        assertEquals(CsvWriter.Delimiter.COMMA, CsvWriter.Delimiter.parse(null))
        assertEquals(CsvWriter.Delimiter.COMMA, CsvWriter.Delimiter.parse(""))
        assertEquals(CsvWriter.Delimiter.COMMA, CsvWriter.Delimiter.parse(","))
        assertEquals(CsvWriter.Delimiter.SEMICOLON, CsvWriter.Delimiter.parse(";"))
        assertEquals(CsvWriter.Delimiter.TAB, CsvWriter.Delimiter.parse("tab"))
        assertEquals(CsvWriter.Delimiter.TAB, CsvWriter.Delimiter.parse("\t"))

        val e = assertThrows(RequestValueException::class.java) { CsvWriter.Delimiter.parse("|") }

        assertEquals("delimiter", e.field)
    }

    @Test
    fun `semicolon and tab files use their separator between cells`() {
        assertEquals("1;\"a\"\r\n", CsvWriter(CsvWriter.Delimiter.SEMICOLON).row(listOf(CsvValue.number(1L), CsvValue.text("a"))))
        assertEquals("1\t\"a\"\r\n", CsvWriter(CsvWriter.Delimiter.TAB).row(listOf(CsvValue.number(1L), CsvValue.text("a"))))
    }

    @Test
    fun `money is a plain decimal with two places, also for zero, small and large values`() {
        fun cell(minor: Long?) = CsvWriter().row(listOf(CsvValue.money(minor))).trimEnd()

        assertEquals("0.00", cell(0))
        assertEquals("0.05", cell(5))
        assertEquals("19.99", cell(1999))
        assertEquals("-3.50", cell(-350))
        assertEquals("90071992547409.91", cell(9_007_199_254_740_991L))
        assertEquals("", cell(null))
        assertFalse(cell(1_000_000_000_000L).contains("E"), "never exponent notation")
    }

    @Test
    fun `the export is capped at 50 000 rows`() {
        assertEquals(50_000, CsvWriter.MAX_ROWS)
        assertTrue(CsvWriter.BOM.startsWith("﻿"))
    }
}
