package com.panomc.plugins.market.core.delivery

/**
 * Renders webhook templates (08 section 3.4, 16). Nothing is executed here, so no validators apply: each token is
 * replaced by the JSON-string-escaped value (without surrounding quotes), a name that is not in the catalogue stays
 * verbatim, an empty value (without `|default`) renders as the empty string. Single pass: substituted text is never
 * scanned again. `{uuid}` is the platform UUID of the recipient or empty.
 */
object TemplateRenderer {
    fun render(template: String, ctx: VariableContext): String {
        val out = StringBuilder(template.length + 64)
        var last = 0

        for (match in VariableContext.TOKEN.findAll(template)) {
            val value = ctx.resolve(match.groupValues[1], includeExtras = true) ?: continue

            out.append(template, last, match.range.first)
            last = match.range.last + 1

            val text = if (value.raw.isEmpty()) match.groups[2]?.value.orEmpty() else value.raw

            out.append(jsonEscape(text))
        }

        out.append(template, last, template.length)

        return out.toString()
    }

    /** Escapes [text] for the inside of a JSON string: quote, backslash, control characters, and U+2028 / U+2029. */
    fun jsonEscape(text: String): String {
        val sb = StringBuilder(text.length + 8)

        for (c in text) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c < ' ' || c == ' ' || c == ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }

        return sb.toString()
    }
}
