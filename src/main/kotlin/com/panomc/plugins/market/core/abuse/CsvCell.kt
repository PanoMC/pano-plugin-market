package com.panomc.plugins.market.core.abuse

import java.math.BigDecimal

/** CSV cell escaping against formula injection (11 section 6.5). Pure. */
object CsvCell {
    private val FORMULA_START = setOf('=', '+', '-', '@', '\t', '\r')

    /**
     * Always quoted: `"` doubled, CR / LF replaced by one space, and a cell whose first character is `=`, `+`, `-`,
     * `@`, TAB or CR is prefixed with `'`. null becomes an empty quoted cell.
     */
    fun text(value: String?): String {
        val v = value ?: ""
        val prefix = if (v.isNotEmpty() && v[0] in FORMULA_START) "'" else ""
        val flat = v.replace(Regex("\r\n|\r|\n"), " ").replace("\"", "\"\"")
        return "\"$prefix$flat\""
    }

    /** Server-produced numbers (amounts, ids, quantities): unquoted and exempt from the prefix rule. */
    fun number(value: Long?): String = value?.toString() ?: ""

    fun number(value: Int?): String = value?.toString() ?: ""

    fun number(value: BigDecimal?): String = value?.toPlainString() ?: ""
}
