package com.panomc.plugins.market.component

import com.panomc.plugins.market.pdf.Code128
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Code 128 (MK-145): known vectors, the width table, and a decode round trip with its own reader. */
class Code128Test {
    private val start = "11010010000"
    private val stopTail = "1100011101011"

    @Test
    fun `A in code set B is the known module string`() {
        // start B 104 = 11010010000, 'A' = 33 = 10100011000, checksum (104 + 33) mod 103 = 34 = 10001011000, stop 1100011101011
        assertArrayEquals(intArrayOf(104, 33, 34, 106), Code128.symbols("A"))
        assertEquals(start + "10100011000" + "10001011000" + stopTail, Code128.modules("A"))
    }

    @Test
    fun `an even digit string uses code set C with the known checksum`() {
        // 105 + 12 + 34 * 2 + 56 * 3 + 78 * 4 = 665, 665 mod 103 = 47
        assertArrayEquals(intArrayOf(105, 12, 34, 56, 78, 47, 106), Code128.symbols("12345678"))
        assertEquals(47, Code128.checksum("12345678"))
    }

    @Test
    fun `Hello in code set B has checksum 76`() {
        assertArrayEquals(intArrayOf(104, 40, 69, 76, 76, 79, 76, 106), Code128.symbols("Hello"))
    }

    @Test
    fun `odd digit strings and mixed strings use code set B`() {
        assertEquals(Code128.START_B, Code128.symbols("123")[0])
        assertEquals(Code128.START_B, Code128.symbols("12A4")[0])
        assertEquals(Code128.START_C, Code128.symbols("1234")[0])
        assertEquals(Code128.START_B, Code128.symbols("1")[0])
    }

    @Test
    fun `a space and the edges of printable ASCII encode`() {
        assertEquals(0, Code128.symbols(" ")[1])
        assertEquals(94, Code128.symbols("~")[1])
        assertTrue(Code128.encodable("TRK-123 /x~"))
    }

    @Test
    fun `every symbol is 11 modules wide, the stop symbol 13, and all patterns differ`() {
        val seen = HashSet<String>()

        for (s in 0..106) {
            val widths = Code128.widthsOf(s)

            assertEquals(if (s == 106) 7 else 6, widths.length, "digits of symbol $s")
            assertEquals(if (s == 106) 13 else 11, widths.sumOf { it - '0' }, "modules of symbol $s")
            assertTrue(seen.add(widths), "pattern of symbol $s is unique")
        }
    }

    @Test
    fun `module strings decode back to the text with a valid checksum`() {
        val reverse = HashMap<String, Int>()

        for (s in 0..105) reverse[bars(Code128.widthsOf(s))] = s

        val samples = listOf("A", "TRK123456", "12345678", "1234567", "MRN-9f3a/7 ~z", "00", "0", "Aras Kargo 0001", "9".repeat(40), "x".repeat(48))

        for (text in samples) {
            val m = Code128.modules(text)

            assertTrue(m.endsWith(stopTail), "stop pattern of $text")
            assertEquals(0, (m.length - 13) % 11, "11 modules per symbol, 13 for the stop symbol, $text")

            val body = m.removeSuffix(stopTail)
            val values = body.chunked(11).map { reverse[it] ?: error("unknown pattern in $text") }
            val mode = values[0]
            val data = values.subList(1, values.size - 1)
            val check = values.last()
            val sum = mode + data.withIndex().sumOf { (i, v) -> v * (i + 1) }

            assertEquals(sum % 103, check, "checksum of $text")

            val decoded = if (mode == Code128.START_C) data.joinToString("") { it.toString().padStart(2, '0') } else data.map { (it + 32).toChar() }.joinToString("")

            assertEquals(text, decoded)
        }
    }

    private fun bars(widths: String): String {
        val out = StringBuilder()
        var bar = true

        for (c in widths) {
            repeat(c - '0') { out.append(if (bar) '1' else '0') }

            bar = !bar
        }

        return out.toString()
    }

    @Test
    fun `refused input`() {
        assertThrows<IllegalArgumentException> { Code128.symbols("") }
        assertThrows<IllegalArgumentException> { Code128.symbols("Çay") }
        assertThrows<IllegalArgumentException> { Code128.symbols("a\tb") }
        assertFalse(Code128.encodable("ş"))
    }

    @Test
    fun `sanitize replaces what code set B cannot carry and cuts to the maximum`() {
        assertEquals("?ay", Code128.sanitize("Çay"))
        assertEquals("a?b", Code128.sanitize("a\nb"))
        assertEquals(Code128.MAX_LENGTH, Code128.sanitize("z".repeat(200)).length)
        assertEquals("TRK-1", Code128.sanitize("TRK-1"))
    }
}
