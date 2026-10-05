package com.panomc.plugins.market.core.abuse

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CsvCellTest {
    @Test
    fun `plain text is quoted`() {
        assertEquals("\"hello\"", CsvCell.text("hello"))
        assertEquals("\"\"", CsvCell.text(""))
        assertEquals("\"\"", CsvCell.text(null))
        assertEquals("\"a,b;c\"", CsvCell.text("a,b;c"))
    }

    @Test
    fun `formula prefixes`() {
        assertEquals("\"'=1+1\"", CsvCell.text("=1+1"))
        assertEquals("\"'+x\"", CsvCell.text("+x"))
        assertEquals("\"'-x\"", CsvCell.text("-x"))
        assertEquals("\"'@x\"", CsvCell.text("@x"))
        assertEquals("\"'\tx\"", CsvCell.text("\tx"))
        assertEquals("\"'x\"", CsvCell.text("\rx").replace(" ", ""))
        assertTrue(CsvCell.text("\rcmd").startsWith("\"'"))
        assertFalse(CsvCell.text("\rcmd").contains('\r'))
        assertEquals("\"a=1\"", CsvCell.text("a=1"))
        assertEquals("\"1-2\"", CsvCell.text("1-2"))
    }

    @Test
    fun `quotes are doubled and newlines flattened`() {
        assertEquals("\"say \"\"hi\"\"\"", CsvCell.text("say \"hi\""))
        assertEquals("\"a b\"", CsvCell.text("a\nb"))
        assertEquals("\"a b\"", CsvCell.text("a\r\nb"))
        assertEquals("\"a b\"", CsvCell.text("a\rb"))
        assertEquals("\"'=\"\"x\"\" y\"", CsvCell.text("=\"x\"\ny"))
    }

    @Test
    fun `numbers are unquoted and exempt`() {
        assertEquals("-5", CsvCell.number(-5L))
        assertEquals("12", CsvCell.number(12))
        assertEquals("-1.50", CsvCell.number(java.math.BigDecimal("-1.50")))
        assertEquals("1000000", CsvCell.number(java.math.BigDecimal("1E+6")))
        assertEquals("", CsvCell.number(null as Long?))
    }
}
