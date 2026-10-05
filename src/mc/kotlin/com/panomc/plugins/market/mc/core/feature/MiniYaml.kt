package com.panomc.plugins.market.mc.core.feature

/** The text is not valid for [MiniYaml]; [line] is 1 based. */
class YamlException(message: String, val line: Int) : RuntimeException("line $line: $message")

/**
 * The small YAML subset the component's `config.yml` and lang files use, so the platform independent core needs no YAML
 * library (SnakeYAML is not on every platform's class path, and the versions differ):
 *
 * - comments (`#` at the start of a line or after a space, outside quotes) and blank lines;
 * - `key: value` and `key:` followed by a more indented block (a map, or a `- item` list);
 * - scalars: `'single'` (`''` escapes a quote), `"double"` (`\"`, `\\`, `\n`, `\t`), plain text, `true` / `false`,
 *   integers, decimals, `null` / `~` / empty;
 * - `[]`, `[a, b]` and `{}` on one line.
 *
 * Tabs for indentation, duplicate keys, multi-line scalars (`|`, `>`) and flow maps with content are errors: a typo must
 * never be read as something else (the caller fails closed on an error, see [LocalConfig]).
 */
object MiniYaml {
    private class Line(val no: Int, val indent: Int, val text: String)

    /** Returns a `Map<String, Any?>`; values are `String`, `Boolean`, `Long`, `Double`, `null`, `List<Any?>` or a nested map. */
    fun parse(source: String): Map<String, Any?> {
        val lines = ArrayList<Line>()
        source.removePrefix("﻿").split('\n').forEachIndexed { i, raw ->
            val noCr = raw.trimEnd('\r')
            val stripped = stripComment(noCr, i + 1)
            if (stripped.isBlank()) return@forEachIndexed
            val indent = stripped.indexOfFirst { it != ' ' }
            if (stripped.substring(0, indent).contains('\t') || stripped.trimStart(' ').startsWith("\t")) throw YamlException("tabs are not allowed for indentation", i + 1)
            lines.add(Line(i + 1, indent, stripped.trimEnd().substring(indent)))
        }
        if (lines.isEmpty()) return emptyMap()
        val cursor = intArrayOf(0)
        val block = parseBlock(lines, cursor, lines[0].indent)
        if (cursor[0] < lines.size) throw YamlException("unexpected indentation", lines[cursor[0]].no)
        @Suppress("UNCHECKED_CAST")
        return (block as? Map<String, Any?>) ?: throw YamlException("the document must be a map of keys", lines[0].no)
    }

    private fun parseBlock(lines: List<Line>, cursor: IntArray, indent: Int): Any? {
        val first = lines[cursor[0]]
        return if (first.text.startsWith("- ") || first.text == "-") parseList(lines, cursor, indent) else parseMap(lines, cursor, indent)
    }

    private fun parseMap(lines: List<Line>, cursor: IntArray, indent: Int): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        while (cursor[0] < lines.size) {
            val line = lines[cursor[0]]
            if (line.indent < indent) break
            if (line.indent > indent) throw YamlException("unexpected indentation", line.no)
            if (line.text.startsWith("- ") || line.text == "-") throw YamlException("a list item inside a map", line.no)
            val (key, rest) = splitKey(line)
            if (map.containsKey(key)) throw YamlException("duplicate key '$key'", line.no)
            cursor[0]++
            map[key] = if (rest.isEmpty()) {
                val next = lines.getOrNull(cursor[0])
                when {
                    next == null || next.indent <= indent -> {
                        // `key:` with nothing below: null, except that a list may sit at the same indent (`key:` / `- a`).
                        if (next != null && next.indent == indent && (next.text.startsWith("- ") || next.text == "-")) parseList(lines, cursor, indent) else null
                    }
                    else -> parseBlock(lines, cursor, next.indent)
                }
            } else scalar(rest, line.no)
        }
        return map
    }

    private fun parseList(lines: List<Line>, cursor: IntArray, indent: Int): List<Any?> {
        val list = ArrayList<Any?>()
        while (cursor[0] < lines.size) {
            val line = lines[cursor[0]]
            if (line.indent != indent || !(line.text.startsWith("- ") || line.text == "-")) break
            val rest = line.text.removePrefix("-").trim()
            if (rest.isEmpty()) throw YamlException("an empty list item", line.no)
            if (rest.contains(": ") && !rest.startsWith("'") && !rest.startsWith("\"") && !rest.startsWith("[")) throw YamlException("maps inside lists are not supported", line.no)
            list.add(scalar(rest, line.no))
            cursor[0]++
        }
        return list
    }

    private fun splitKey(line: Line): Pair<String, String> {
        val t = line.text
        if (t.startsWith("'") || t.startsWith("\"")) {
            val end = closingQuote(t, 0, line.no)
            val key = unquote(t.substring(0, end + 1), line.no)
            val after = t.substring(end + 1)
            if (!after.startsWith(":")) throw YamlException("expected ':' after the key", line.no)
            return key to after.substring(1).trim()
        }
        val idx = findColon(t)
        if (idx < 0) throw YamlException("expected 'key: value'", line.no)
        val key = t.substring(0, idx).trim()
        if (key.isEmpty()) throw YamlException("an empty key", line.no)
        return key to t.substring(idx + 1).trim()
    }

    /** The first `:` that ends the key: followed by a space or the end of the line. */
    private fun findColon(t: String): Int {
        var i = 0
        while (i < t.length) {
            if (t[i] == ':' && (i == t.length - 1 || t[i + 1] == ' ')) return i
            i++
        }
        return -1
    }

    private fun closingQuote(t: String, start: Int, no: Int): Int {
        val q = t[start]
        var i = start + 1
        while (i < t.length) {
            val c = t[i]
            if (q == '"' && c == '\\') {
                i += 2
                continue
            }
            if (c == q) {
                if (q == '\'' && i + 1 < t.length && t[i + 1] == '\'') {
                    i += 2
                    continue
                }
                return i
            }
            i++
        }
        throw YamlException("unterminated quoted text", no)
    }

    private fun scalar(raw: String, no: Int): Any? {
        val t = raw.trim()
        if (t.isEmpty() || t == "~" || t == "null") return null
        if (t.startsWith("'") || t.startsWith("\"")) {
            val end = closingQuote(t, 0, no)
            if (end != t.length - 1) throw YamlException("unexpected text after the quoted value", no)
            return unquote(t, no)
        }
        if (t == "[]") return emptyList<Any?>()
        if (t == "{}") return emptyMap<String, Any?>()
        if (t.startsWith("[")) {
            if (!t.endsWith("]")) throw YamlException("unterminated list", no)
            return splitFlow(t.substring(1, t.length - 1), no).map { scalar(it, no) }
        }
        if (t.startsWith("{")) throw YamlException("flow maps are not supported", no)
        if (BLOCK_SCALAR.matches(t)) throw YamlException("multi-line scalars are not supported", no)
        return when (t.lowercase()) {
            "true" -> true
            "false" -> false
            else -> t.toLongOrNull() ?: (if (DECIMAL.matches(t)) t.toDoubleOrNull() else null) ?: t
        }
    }

    private val DECIMAL = Regex("-?\\d+\\.\\d+")
    private val BLOCK_SCALAR = Regex("[|>][+-]?\\d*")

    private fun splitFlow(inner: String, no: Int): List<String> {
        val out = ArrayList<String>()
        var depthQuote: Char? = null
        val cur = StringBuilder()
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            when {
                depthQuote != null -> {
                    cur.append(c)
                    if (depthQuote == '"' && c == '\\' && i + 1 < inner.length) {
                        cur.append(inner[i + 1])
                        i++
                    } else if (c == depthQuote) {
                        if (depthQuote == '\'' && i + 1 < inner.length && inner[i + 1] == '\'') {
                            cur.append('\'')
                            i++
                        } else depthQuote = null
                    }
                }
                c == '\'' || c == '"' -> {
                    depthQuote = c
                    cur.append(c)
                }
                c == ',' -> {
                    out.add(cur.toString())
                    cur.setLength(0)
                }
                else -> cur.append(c)
            }
            i++
        }
        if (depthQuote != null) throw YamlException("unterminated quoted text in a list", no)
        val last = cur.toString()
        if (last.isNotBlank() || out.isNotEmpty()) out.add(last)
        if (out.any { it.isBlank() }) throw YamlException("an empty list item", no)
        return out
    }

    private fun unquote(t: String, no: Int): String {
        val body = t.substring(1, t.length - 1)
        if (t[0] == '\'') return body.replace("''", "'")
        val sb = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\') {
                if (i + 1 >= body.length) throw YamlException("a dangling backslash", no)
                when (val n = body[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    else -> throw YamlException("unknown escape \\$n", no)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** Cuts a trailing `# comment` that is outside quotes and preceded by a space (or starts the line). */
    private fun stripComment(line: String, no: Int): String {
        var quote: Char? = null
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (quote != null) {
                if (quote == '"' && c == '\\') {
                    i += 2
                    continue
                }
                if (c == quote) {
                    if (quote == '\'' && i + 1 < line.length && line[i + 1] == '\'') {
                        i += 2
                        continue
                    }
                    quote = null
                }
            } else if ((c == '\'' || c == '"') && (i == 0 || line[i - 1] == ' ' || line[i - 1] == ':' || line[i - 1] == '[' || line[i - 1] == ',' || line[i - 1] == '-')) {
                quote = c
            } else if (c == '#' && (i == 0 || line[i - 1] == ' ')) {
                return line.substring(0, i)
            }
            i++
        }
        return line
    }
}
