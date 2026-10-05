package com.panomc.plugins.market.pdf

/**
 * Code 128 (ISO/IEC 15417) for the generic shipping label (10 section 9.8). Pure: text in, symbol values and bar / space modules out.
 *
 * Code set choice: a string of an even number of digits (at least two) uses code set C (two digits per symbol), everything else code set B
 * (printable ASCII 32 to 126) from the start; no switching in between. [encodable] says whether a text fits, [sanitize] replaces what does not.
 */
object Code128 {
    /** Bar / space widths of the symbols 0 to 105 (six digits each) and the stop symbol 106 (seven digits). */
    private val WIDTHS = arrayOf(
        "212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312", "132212", "221213",
        "221312", "231212", "112232", "122132", "122231", "113222", "123122", "123221", "223211", "221132",
        "221231", "213212", "223112", "312131", "311222", "321122", "321221", "312212", "322112", "322211",
        "212123", "212321", "232121", "111323", "131123", "131321", "112313", "132113", "132311", "211313",
        "231113", "231311", "112133", "112331", "132131", "113123", "113321", "133121", "313121", "211331",
        "231131", "213113", "213311", "213131", "311123", "311321", "331121", "312113", "312311", "332111",
        "314111", "221411", "431111", "111224", "111422", "121124", "121421", "141122", "141221", "112214",
        "112412", "122114", "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111",
        "111242", "121142", "121241", "114212", "124112", "124211", "411212", "421112", "421211", "212141",
        "214121", "412121", "111143", "111341", "131141", "114113", "114311", "411113", "411311", "113141",
        "114131", "311141", "411131", "211412", "211214", "211232", "2331112"
    )

    const val START_B = 104
    const val START_C = 105
    const val STOP = 106

    /** Longest text the label draws (the barcode would not stay readable on A6 beyond this). */
    const val MAX_LENGTH = 48

    /** Printable ASCII only (code set B). */
    fun encodable(text: String): Boolean = text.all { it.code in 32..126 }

    /** [text] with every character code set B cannot carry replaced by `?`, cut to [MAX_LENGTH]. */
    fun sanitize(text: String): String = text.take(MAX_LENGTH).map { if (it.code in 32..126) it else '?' }.joinToString("")

    private fun digitsOnly(text: String) = text.length >= 2 && text.length % 2 == 0 && text.all { it in '0'..'9' }

    /** The symbol values including start, checksum and stop. Throws [IllegalArgumentException] for an empty or non-encodable [text]. */
    fun symbols(text: String): IntArray {
        require(text.isNotEmpty()) { "an empty text has no barcode" }
        require(encodable(text)) { "Code 128 code set B carries printable ASCII only" }

        val data = ArrayList<Int>()
        val start: Int

        if (digitsOnly(text)) {
            start = START_C

            for (i in text.indices step 2) data += text.substring(i, i + 2).toInt()
        } else {
            start = START_B

            for (c in text) data += c.code - 32
        }

        var sum = start

        data.forEachIndexed { i, v -> sum += v * (i + 1) }

        return (listOf(start) + data + listOf(sum % 103, STOP)).toIntArray()
    }

    /** The checksum symbol of [text] (the one before the stop symbol). */
    fun checksum(text: String): Int = symbols(text).let { it[it.size - 2] }

    /** The module string, `1` = bar, `0` = space, e.g. `11010010000` for the start B symbol. Includes the 2 module terminator of the stop symbol. */
    fun modules(text: String): String {
        val out = StringBuilder()

        for (symbol in symbols(text)) {
            var bar = true

            for (digit in WIDTHS[symbol]) {
                repeat(digit - '0') { out.append(if (bar) '1' else '0') }

                bar = !bar
            }
        }

        return out.toString()
    }

    /** The width table, for the tests (every symbol sums to 11 modules, the stop symbol to 13). */
    internal fun widthsOf(symbol: Int): String = WIDTHS[symbol]
}
