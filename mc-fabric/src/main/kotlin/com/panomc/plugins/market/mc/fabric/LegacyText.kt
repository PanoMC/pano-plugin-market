package com.panomc.plugins.market.mc.fabric

/**
 * The legacy `§` text every message of the component is made of (`Messages` / `ChatFormat`), split into styled runs. Pure
 * logic, no Minecraft class: [FabricText] turns the runs into chat components, and the tests cover the parsing.
 *
 * Rules (the vanilla client's): a colour code replaces the colour AND clears the styles before it, `§r` clears everything,
 * the style codes `k l m n o` add to the current colour, codes are case-insensitive, and a `§` that is no code stays text.
 */
object LegacyText {
    /** One run of text with one colour and one set of styles; [color] is the code char (`0`-`9`, `a`-`f`) or `null`. */
    data class Run(
        val text: String,
        val color: Char? = null,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underlined: Boolean = false,
        val strikethrough: Boolean = false,
        val obfuscated: Boolean = false
    )

    private const val COLORS = "0123456789abcdef"
    private const val STYLES = "klmno"

    fun parse(text: String): List<Run> {
        val runs = ArrayList<Run>()
        val buffer = StringBuilder()
        var color: Char? = null
        var bold = false
        var italic = false
        var underlined = false
        var strike = false
        var obfuscated = false

        fun flush() {
            if (buffer.isNotEmpty()) {
                runs.add(Run(buffer.toString(), color, bold, italic, underlined, strike, obfuscated))
                buffer.setLength(0)
            }
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '§' && i + 1 < text.length) {
                val code = text[i + 1].lowercaseChar()
                if (code in COLORS || code in STYLES || code == 'r') {
                    flush()
                    when {
                        code in COLORS -> {
                            color = code
                            bold = false; italic = false; underlined = false; strike = false; obfuscated = false
                        }
                        code == 'r' -> {
                            color = null
                            bold = false; italic = false; underlined = false; strike = false; obfuscated = false
                        }
                        code == 'k' -> obfuscated = true
                        code == 'l' -> bold = true
                        code == 'm' -> strike = true
                        code == 'n' -> underlined = true
                        code == 'o' -> italic = true
                    }
                    i += 2
                    continue
                }
            }
            buffer.append(c)
            i++
        }
        flush()
        return runs
    }

    /** The text without its codes (the console line and the fallback when a component cannot be built). */
    fun plain(text: String): String = parse(text).joinToString("") { it.text }

    /** Only plain `http` / `https` URLs may become a click action (19 section 7.4). */
    fun isWebUrl(url: String?): Boolean = url != null && (url.startsWith("https://") || url.startsWith("http://"))
}
