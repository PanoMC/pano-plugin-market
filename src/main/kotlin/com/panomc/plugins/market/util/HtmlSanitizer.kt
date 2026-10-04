package com.panomc.plugins.market.util

/**
 * Allowlist HTML sanitizer for merchant-authored rich text (product descriptions) that the storefront
 * renders with {@html}.
 *
 * The platform ships no HTML parser/sanitizer (no Jsoup on the host classpath), so this is a small
 * tokenizer that REBUILDS the markup instead of filtering it: only allowlisted tags with allowlisted,
 * re-escaped attributes are emitted, every text run is escaped, and everything else (comments, doctype,
 * unknown tags, scripts, event handlers, javascript:/data: URLs) is dropped. The only inline style kept
 * is a filtered subset of text-styling declarations on span (the editor's colour/font picker output). Because nothing from
 * the input is copied through verbatim there is no re-serialisation (mXSS) gap, and no raw-text element
 * (script, style, textarea, title, svg, math, ...) can ever reach the output.
 *
 * Output is balanced (unclosed tags are closed, stray end tags dropped) and idempotent.
 */
object HtmlSanitizer {
    private val VOID_TAGS = setOf("br", "hr", "img")

    private val ALLOWED_TAGS = setOf(
        "p", "br", "hr", "div", "span",
        "h1", "h2", "h3", "h4", "h5", "h6",
        "ul", "ol", "li",
        "strong", "b", "em", "i", "u", "s", "strike", "del", "ins", "sub", "sup", "small", "mark",
        "blockquote", "pre", "code",
        "a", "img",
        "table", "thead", "tbody", "tfoot", "tr", "th", "td", "caption"
    )

    // Elements whose whole content is dropped with the tag. Includes every raw-text / foreign-content
    // element, since their body is parsed differently by browsers and must never be re-emitted as text.
    // Void elements (embed, frame, input, ...) are deliberately absent: they have no end tag, so
    // "drop until the closing tag" would swallow the rest of the document. They are simply unwrapped.
    private val DROP_CONTENT_TAGS = setOf(
        "script", "style", "iframe", "frameset", "noscript", "noembed", "noframes", "template",
        "object", "applet", "svg", "math", "textarea", "title", "xmp", "plaintext", "head"
    )

    private val ALLOWED_ATTRIBUTES = mapOf(
        "span" to setOf("style"),
        "a" to setOf("href", "title", "target"),
        "img" to setOf("src", "alt", "title", "width", "height"),
        "th" to setOf("colspan", "rowspan"),
        "td" to setOf("colspan", "rowspan")
    )

    // The properties the panel editor's TipTap TextStyleKit can emit; anything else is dropped.
    private val ALLOWED_STYLE_PROPERTIES = setOf("color", "background-color", "font-size", "font-family", "line-height")
    private val STYLE_VALUE = Regex("^[#a-zA-Z0-9 .,%()'\\-]{1,64}$")
    private val FORBIDDEN_STYLE_FRAGMENTS = listOf("url(", "expression(", "\\", "/*")

    private val LINK_SCHEMES = setOf("http", "https", "mailto", "tel")
    private val IMAGE_SCHEMES = setOf("http", "https")

    private val VALID_ENTITY = Regex("&(?:#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});")
    private val DIGITS = Regex("[0-9]{1,4}")
    private val SCHEME = Regex("^([a-z][a-z0-9+.\\-]*):")

    fun sanitizeOrNull(html: String?): String? = html?.let { sanitize(it) }

    fun sanitize(html: String): String {
        val input = html.replace("\u0000", "")

        // Nothing that could start markup or an entity: already safe as-is (also keeps plain text cheap).
        if (input.none { it == '<' || it == '>' || it == '&' || it == '"' || it == '\'' }) return input

        val out = StringBuilder(input.length + 16)
        val open = ArrayDeque<String>()
        var i = 0
        val n = input.length

        while (i < n) {
            val c = input[i]

            if (c != '<') {
                val next = input.indexOf('<', i).let { if (it == -1) n else it }
                appendText(out, input.substring(i, next))
                i = next
                continue
            }

            // Comments, CDATA, doctype and processing instructions are dropped.
            if (input.startsWith("<!--", i)) {
                val end = input.indexOf("-->", i + 4)
                i = if (end == -1) n else end + 3
                continue
            }

            if (i + 1 < n && (input[i + 1] == '!' || input[i + 1] == '?')) {
                val end = input.indexOf('>', i + 2)
                i = if (end == -1) n else end + 1
                continue
            }

            val isEnd = i + 1 < n && input[i + 1] == '/'
            val nameStart = if (isEnd) i + 2 else i + 1

            // "<" not followed by a tag name is literal text ("a < b", "1<2").
            if (nameStart >= n || !input[nameStart].isAsciiLetter()) {
                out.append("&lt;")
                i++
                continue
            }

            var nameEnd = nameStart
            while (nameEnd < n && !input[nameEnd].isWhitespace() && input[nameEnd] != '/' && input[nameEnd] != '>') nameEnd++
            val name = input.substring(nameStart, nameEnd).lowercase()

            val parsed = parseTagBody(input, nameEnd) ?: break // unterminated tag: browsers drop it, so do we
            i = parsed.end

            if (isEnd) {
                if (name in ALLOWED_TAGS && name !in VOID_TAGS && name in open) {
                    while (open.isNotEmpty()) {
                        val top = open.removeLast()
                        out.append("</").append(top).append('>')
                        if (top == name) break
                    }
                }
                continue
            }

            if (name in DROP_CONTENT_TAGS) {
                val close = Regex("</" + Regex.escape(name) + "(?=[\\s/>]|$)", RegexOption.IGNORE_CASE).find(input, i)

                if (close == null) {
                    i = n
                } else {
                    val end = input.indexOf('>', close.range.last + 1)
                    i = if (end == -1) n else end + 1
                }
                continue
            }

            if (name !in ALLOWED_TAGS) continue // unknown tag: unwrap, keep the text

            out.append('<').append(name)
            val allowed = ALLOWED_ATTRIBUTES[name].orEmpty()
            val seen = HashSet<String>()
            var hasHref = false
            var blank = false

            for ((attrName, rawValue) in parsed.attributes) {
                // First occurrence wins, like in browsers.
                if (!seen.add(attrName) || attrName !in allowed) continue

                val value = decodeEntities(rawValue)
                val clean = when (attrName) {
                    "href" -> value.takeIf { isSafeUrl(it, LINK_SCHEMES, allowRelative = true) }
                    "src" -> value.takeIf { isSafeUrl(it, IMAGE_SCHEMES, allowRelative = true) }
                    "width", "height", "colspan", "rowspan" -> value.trim().takeIf { DIGITS.matches(it) }
                    "style" -> sanitizeStyle(value)
                    "target" -> value.trim().takeIf { it.equals("_blank", ignoreCase = true) }?.lowercase()
                    else -> value
                } ?: continue

                if (name == "a" && attrName == "href") hasHref = true
                if (name == "a" && attrName == "target") blank = true

                out.append(' ').append(attrName).append("=\"").append(escapeAttribute(clean)).append('"')
            }

            if (name == "a" && hasHref && blank) out.append(" rel=\"noopener noreferrer\"")
            else if (name == "a" && hasHref) out.append(" rel=\"noopener\"")

            out.append('>')

            if (name !in VOID_TAGS) open.addLast(name)
        }

        while (open.isNotEmpty()) out.append("</").append(open.removeLast()).append('>')

        return out.toString()
    }

    private class TagBody(val attributes: List<Pair<String, String>>, val end: Int)

    // Parses attributes from [start] up to and including the closing '>'. Returns null when the tag is
    // never terminated (EOF inside a tag or an unterminated quoted value).
    private fun parseTagBody(input: String, start: Int): TagBody? {
        val n = input.length
        var i = start
        val attributes = ArrayList<Pair<String, String>>()

        while (true) {
            while (i < n && (input[i].isWhitespace() || input[i] == '/')) i++
            if (i >= n) return null
            if (input[i] == '>') return TagBody(attributes, i + 1)

            val nameStart = i
            // A leading '=' is part of the attribute name per the HTML spec; the loop must still advance.
            i++
            while (i < n && !input[i].isWhitespace() && input[i] != '=' && input[i] != '>' && input[i] != '/') i++
            val attrName = input.substring(nameStart, i).lowercase()

            var j = i
            while (j < n && input[j].isWhitespace()) j++

            if (j < n && input[j] == '=') {
                j++
                while (j < n && input[j].isWhitespace()) j++
                if (j >= n) return null

                val quote = input[j]
                if (quote == '"' || quote == '\'') {
                    val end = input.indexOf(quote, j + 1)
                    if (end == -1) return null
                    attributes.add(attrName to input.substring(j + 1, end))
                    i = end + 1
                } else {
                    var end = j
                    while (end < n && !input[end].isWhitespace() && input[end] != '>') end++
                    attributes.add(attrName to input.substring(j, end))
                    i = end
                }
            } else {
                attributes.add(attrName to "")
            }
        }
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    private fun appendText(out: StringBuilder, text: String) {
        var i = 0

        while (i < text.length) {
            when (val c = text[i]) {
                '&' -> {
                    val match = VALID_ENTITY.matchAt(text, i)

                    if (match != null) {
                        out.append(match.value)
                        i += match.value.length
                        continue
                    }

                    out.append("&amp;")
                }

                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                else -> out.append(c)
            }
            i++
        }
    }

    private fun escapeAttribute(value: String): String {
        val sb = StringBuilder(value.length + 8)

        value.forEach {
            when (it) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(it)
            }
        }

        return sb.toString()
    }

    // Decodes the entities a browser would decode inside an attribute value, so scheme checks see the
    // same string the browser would. Unknown named entities stay literal; since the output re-escapes
    // every '&', they can never be decoded into anything on the way out.
    private fun decodeEntities(value: String): String {
        if ('&' !in value) return value

        return VALID_ENTITY.replace(value) { match ->
            val body = match.value.substring(1, match.value.length - 1)

            when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.substring(2).toIntOrNull(16)?.let(::codePointToString) ?: match.value

                body.startsWith("#") -> body.substring(1).toIntOrNull()?.let(::codePointToString) ?: match.value

                else -> when (body) {
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    "colon" -> ":"
                    "Tab" -> "\t"
                    "NewLine" -> "\n"
                    "sol" -> "/"
                    "lpar" -> "("
                    "rpar" -> ")"
                    "num" -> "#"
                    "comma" -> ","
                    "period" -> "."
                    else -> match.value
                }
            }
        }
    }

    // Keeps only allowlisted declarations with a plain value (no functions that fetch or evaluate, no
    // escapes, no comments); returns null when nothing survives so the attribute is omitted.
    private fun sanitizeStyle(value: String): String? {
        val kept = value.split(';').mapNotNull { declaration ->
            val colon = declaration.indexOf(':')
            if (colon == -1) return@mapNotNull null

            val property = declaration.substring(0, colon).trim().lowercase()
            val propertyValue = declaration.substring(colon + 1).trim()
            val lower = propertyValue.lowercase()

            if (property !in ALLOWED_STYLE_PROPERTIES) return@mapNotNull null
            if (!STYLE_VALUE.matches(propertyValue)) return@mapNotNull null
            if (FORBIDDEN_STYLE_FRAGMENTS.any { it in lower }) return@mapNotNull null

            "$property: $propertyValue"
        }

        return kept.joinToString("; ").takeIf { it.isNotEmpty() }
    }

    private fun codePointToString(codePoint: Int): String? =
        if (codePoint in 0..0x10FFFF && codePoint !in 0xD800..0xDFFF) String(Character.toChars(codePoint)) else null

    private fun isSafeUrl(url: String, schemes: Set<String>, allowRelative: Boolean): Boolean {
        // Browsers ignore tab/newline anywhere in a URL and trim leading C0 controls and spaces, so
        // "java\tscript:" and " javascript:" are both javascript: URLs.
        val normalized = url.filterNot { it.code <= 0x20 || it.code == 0x7F }.lowercase()

        if (normalized.isEmpty()) return false

        val scheme = SCHEME.find(normalized)?.groupValues?.get(1)

        if (scheme != null) return scheme in schemes
        if (!allowRelative) return false

        // A colon before any '/', '?' or '#' is a scheme the regex did not accept (e.g. "1x:..."): reject.
        val colon = normalized.indexOf(':')
        if (colon != -1) {
            val firstDelimiter = normalized.indexOfFirst { it == '/' || it == '?' || it == '#' }
            if (firstDelimiter == -1 || colon < firstDelimiter) return false
        }

        return true
    }
}
