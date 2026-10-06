package com.panomc.plugins.market.util

import com.panomc.plugins.market.core.abuse.CsvCell
import com.panomc.plugins.market.error.RequestValueException
import java.math.BigDecimal

/**
 * One cell of a CSV row. A [Text] is always quoted and formula-safe ([CsvCell.text], 11 section 6.5); a [Number] is a value the server produced
 * (an id, an amount, a quantity) and is written bare. A cell can only be built through these two, so no caller can write an unescaped buyer string.
 */
sealed class CsvValue {
    class Text(val value: String?) : CsvValue()

    class Number(val rendered: String) : CsvValue()

    companion object {
        fun text(value: String?): CsvValue = Text(value)

        fun number(value: Long?): CsvValue = Number(CsvCell.number(value))

        fun number(value: Int?): CsvValue = Number(CsvCell.number(value))

        fun number(value: BigDecimal?): CsvValue = Number(CsvCell.number(value))

        /** A money amount x100 as a plain decimal with two places (`1999` is `19.99`); never exponent notation. */
        fun money(minor: Long?): CsvValue = Number(if (minor == null) "" else BigDecimal.valueOf(minor, 2).toPlainString())

        fun bool(value: Boolean): CsvValue = Number(if (value) "true" else "false")
    }
}

/**
 * The CSV format of `GET /orders/export` (04 section 7, 13 section 5.2): UTF-8 with a byte order mark (so a spreadsheet opens the accents right),
 * a delimiter of `,`, `;` or a tab, CRLF line ends, and at most [MAX_ROWS] data rows. Pure: it turns rows into text, the caller moves the bytes.
 */
class CsvWriter(val delimiter: Delimiter = Delimiter.COMMA) {
    /** `delimiter` of the request: `,`, `;` or `tab`. */
    enum class Delimiter(val key: String, val char: Char) {
        COMMA(",", ','),
        SEMICOLON(";", ';'),
        TAB("tab", '\t');

        companion object {
            /** Absent or blank is the comma; an unknown value is a 400 (a typo must not silently change the format). */
            fun parse(raw: String?): Delimiter {
                val value = raw?.takeIf { it.isNotEmpty() } ?: return COMMA

                // a literal tab character in the query string is accepted next to its name; a `+` in a URL is a space, so a bare `;` or `,` is the only other form
                if (value == "\t") return TAB

                return entries.firstOrNull { it.key == value.trim() } ?: throw RequestValueException("delimiter", "UNKNOWN_VALUE")
            }
        }
    }

    /** The byte order mark, the first characters of the file. */
    fun bom(): String = BOM

    /** One row, closed by CRLF. */
    fun row(values: List<CsvValue>): String {
        val out = StringBuilder()

        for ((index, value) in values.withIndex()) {
            if (index > 0) out.append(delimiter.char)

            out.append(
                when (value) {
                    is CsvValue.Text -> CsvCell.text(value.value)
                    is CsvValue.Number -> value.rendered
                }
            )
        }

        return out.append("\r\n").toString()
    }

    /** The header row: every column key as text. */
    fun header(columns: List<String>): String = row(columns.map { CsvValue.text(it) })

    companion object {
        const val BOM = "﻿"

        /** The most data rows (order items) one export writes (04 section 7). */
        const val MAX_ROWS = 50_000
    }
}
