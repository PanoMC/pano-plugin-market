package com.panomc.plugins.market.core.abuse

import java.net.URLEncoder

/**
 * Removes secrets and card data from text before it is logged, stored or returned (11 section 8.4). Pure: Kotlin
 * stdlib and the JDK only.
 *
 * Built per provider from its decrypted secret settings and `market_provider_state` values; values shorter than
 * [MIN_SECRET_LENGTH] are ignored (they would blank out ordinary words). [redact] applies, in this order:
 * 1. every occurrence of a secret value, also in its URL-encoded and JSON-escaped forms, becomes `[REDACTED]`;
 * 2. runs of 13 to 19 digits (single spaces or dashes allowed between digits) that pass the Luhn check keep only
 *    their last four digits, all the others become `*` (`************1234`);
 * 3. the values of the JSON / form keys `cvv`, `cvc`, `cvv2`, `password`, `card_number`, `cardNumber`, `pan`,
 *    `identityNumber`, `tckn` (any case) become `[REDACTED]`.
 */
class Redactor(secretValues: Set<String> = emptySet()) {
    /** Every spelling of every secret, longest first so a secret that contains another one is replaced as a whole. */
    private val needles: List<String> = secretValues
        .filter { it.length >= MIN_SECRET_LENGTH }
        .flatMap { variants(it) }
        .filter { it.isNotEmpty() }
        .distinct()
        .sortedByDescending { it.length }

    /** A redactor that also knows [more] (for example values of `market_provider_state`). */
    fun plus(more: Set<String>): Redactor = Redactor(knownSecrets + more)

    private val knownSecrets: Set<String> = secretValues.filter { it.length >= MIN_SECRET_LENGTH }.toSet()

    /** Rule 1 only: used for header values and other text that must not be rewritten otherwise. */
    fun redactSecrets(text: String): String {
        var out = text
        for (needle in needles) if (out.contains(needle)) out = out.replace(needle, REDACTED)
        return out
    }

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var out = redactSecrets(text)
        out = maskCards(out)
        out = redactKeys(out)
        return out
    }

    /** The text of a possibly null value. */
    fun redactOrNull(text: String?): String? = text?.let { redact(it) }

    /**
     * Header values of `authorization`, `proxy-authorization`, `cookie`, `set-cookie` and `x-api-key` (any case)
     * become `[REDACTED]`; secret values are removed from every other value.
     */
    fun redactHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (name, value) -> if (name.lowercase() in SENSITIVE_HEADERS) REDACTED else redactSecrets(value) }

    @JvmName("redactMultiHeaders")
    fun redactHeaders(headers: Map<String, List<String>>): Map<String, List<String>> =
        headers.mapValues { (name, values) ->
            if (name.lowercase() in SENSITIVE_HEADERS) values.map { REDACTED } else values.map { redactSecrets(it) }
        }

    /**
     * For `market_payment_event.url`: a path segment that looks like a token (16 or more characters of
     * `[A-Za-z0-9_-]`) keeps its first 6 characters and ends in `...` (the ellipsis character), the values of
     * the sensitive query parameters ([SENSITIVE_QUERY]) become `[REDACTED]`, and the secret values are removed.
     */
    fun redactUrl(rawUrl: String): String {
        val url = redactSecrets(rawUrl)
        val queryAt = url.indexOf('?')
        val path = if (queryAt < 0) url else url.substring(0, queryAt)
        val query = if (queryAt < 0) null else url.substring(queryAt + 1)
        val shortPath = path.split('/').joinToString("/") { seg -> if (TOKEN_SEGMENT.matches(seg)) seg.substring(0, 6) + ELLIPSIS else seg }
        val shortQuery = query?.split('&')?.joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq > 0 && pair.substring(0, eq).lowercase() in SENSITIVE_QUERY) pair.substring(0, eq + 1) + REDACTED else pair
        }
        return if (shortQuery == null) shortPath else "$shortPath?$shortQuery"
    }

    private fun maskCards(text: String): String = CARD_RUN.replace(text) { m ->
        val digits = m.value.filter { it.isDigit() }
        if (digits.length in 13..19 && luhn(digits)) "*".repeat(digits.length - 4) + digits.takeLast(4) else maskWindows(m.value)
    }

    /**
     * The whole run is not a card number, but a card number may sit inside it next to other digits (a CVV, an expiry,
     * an order number behind a single space or dash). Scans the digit positions for Luhn valid windows of 13 up to 19
     * digits and masks all but the last four digits of the shortest one found at each start (so a card number is not
     * widened over a neighbouring CVV by a chance Luhn match), then continues after it.
     * A window starts at the beginning of a digit group (run start or after a separator) and ends at the end of one
     * (run end or before a separator), so a single failing number such as `4111111111111112` is never cut into pieces.
     * Separators and the digits outside a window stay as they are.
     */
    private fun maskWindows(run: String): String {
        val positions = run.indices.filter { run[it].isDigit() }
        val n = positions.size
        if (n < 13) return run
        val chars = run.toCharArray()
        var start = 0
        fun groupStart(i: Int) = i == 0 || positions[i] - positions[i - 1] > 1
        fun groupEnd(i: Int) = i == n - 1 || positions[i + 1] - positions[i] > 1
        while (start <= n - 13) {
            var matched = 0
            for (len in 13..(if (groupStart(start)) minOf(19, n - start) else 0)) {
                if (!groupEnd(start + len - 1)) continue
                val window = StringBuilder(len)
                for (i in start until start + len) window.append(run[positions[i]])
                if (luhn(window.toString())) {
                    matched = len
                    break
                }
            }
            if (matched == 0) {
                start++
            } else {
                for (i in start until start + matched - 4) chars[positions[i]] = '*'
                start += matched
            }
        }
        return String(chars)
    }

    private fun redactKeys(text: String): String {
        var out = text
        if (out.contains('"')) {
            out = JSON_STRING_VALUE.replace(out) { m -> m.groupValues[1] + "\"" + REDACTED + "\"" }
            out = JSON_BARE_VALUE.replace(out) { m -> m.groupValues[1] + "\"" + REDACTED + "\"" }
        }
        if (out.contains('=')) out = FORM_VALUE.replace(out) { m -> m.groupValues[1] + REDACTED }
        return out
    }

    companion object {
        const val REDACTED = "[REDACTED]"
        const val MIN_SECRET_LENGTH = 6
        private const val ELLIPSIS = "…"

        /** The redactor that knows no secret: card data and sensitive keys are still removed. */
        val NONE = Redactor()

        private val SENSITIVE_HEADERS = setOf("authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key")
        private val SENSITIVE_QUERY = setOf(
            "token", "access_token", "accesstoken", "attempttoken", "webhooktoken", "installtoken", "key", "api_key", "apikey",
            "secret", "signature", "sig", "password", "hash", "code"
        )
        private val HEX_ESCAPE = Regex("%[0-9A-F]{2}")
        private val TOKEN_SEGMENT = Regex("^[A-Za-z0-9_-]{16,}$")

        private const val KEYS = "card_number|cardNumber|identityNumber|password|cvv2|cvv|cvc|tckn|pan"

        // A digit run with single space / dash separators; not preceded or followed by another digit.
        private val CARD_RUN = Regex("(?<!\\d)\\d(?:[ -]?\\d){12,18}(?!\\d)")
        private val JSON_STRING_VALUE = Regex("(\"(?:$KEYS)\"\\s*:\\s*)\"(?:[^\"\\\\]|\\\\.)*\"", RegexOption.IGNORE_CASE)
        private val JSON_BARE_VALUE = Regex("(\"(?:$KEYS)\"\\s*:\\s*)(?:-?\\d[\\d.eE+-]*|true|false|null)(?![\\w\"])", RegexOption.IGNORE_CASE)
        private val FORM_VALUE = Regex("((?<![A-Za-z0-9_])(?:$KEYS)(?:%5D|\\])?=)[^&\\s\"'<>]*", RegexOption.IGNORE_CASE)

        internal fun luhn(digits: String): Boolean {
            var sum = 0
            var double = false
            for (i in digits.length - 1 downTo 0) {
                var d = digits[i] - '0'
                if (double) {
                    d *= 2
                    if (d > 9) d -= 9
                }
                sum += d
                double = !double
            }
            return sum % 10 == 0
        }

        /** The spellings a secret takes in a log line, a form body, a URL or a JSON document. */
        internal fun variants(secret: String): List<String> {
            val out = LinkedHashSet<String>()
            out.add(secret)
            val formEncoded = URLEncoder.encode(secret, "UTF-8")
            out.add(formEncoded)
            out.add(formEncoded.replace("+", "%20"))
            out.add(percentEncode(secret, upper = true))
            out.add(percentEncode(secret, upper = false))
            // some clients write the hex digits of a form-encoded value in lower case
            out.add(formEncoded.replace(HEX_ESCAPE) { it.value.lowercase() })
            out.add(formEncoded.replace("+", "%20").replace(HEX_ESCAPE) { it.value.lowercase() })
            out.add(jsonEscape(secret, escapeSlash = false, escapeNonAscii = false))
            out.add(jsonEscape(secret, escapeSlash = true, escapeNonAscii = false))
            out.add(jsonEscape(secret, escapeSlash = false, escapeNonAscii = true))
            out.add(jsonEscape(secret, escapeSlash = true, escapeNonAscii = true))
            // JSON inside a form or URL value is escaped twice
            out.add(URLEncoder.encode(jsonEscape(secret, escapeSlash = false, escapeNonAscii = false), "UTF-8"))
            return out.toList()
        }

        /** RFC 3986: everything but the unreserved characters becomes `%XX` (UTF-8 bytes). */
        private fun percentEncode(s: String, upper: Boolean): String {
            val sb = StringBuilder()
            val hex = if (upper) "0123456789ABCDEF" else "0123456789abcdef"
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                if ((c in 'A'.code..'Z'.code) || (c in 'a'.code..'z'.code) || (c in '0'.code..'9'.code) || c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code) {
                    sb.append(c.toChar())
                } else {
                    sb.append('%').append(hex[c shr 4]).append(hex[c and 15])
                }
            }
            return sb.toString()
        }

        private fun jsonEscape(s: String, escapeSlash: Boolean, escapeNonAscii: Boolean): String {
            val sb = StringBuilder()
            for (c in s) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c == '\t' -> sb.append("\\t")
                    c == '\b' -> sb.append("\\b")
                    c == '\u000C' -> sb.append("\\f")
                    c == '/' && escapeSlash -> sb.append("\\/")
                    c.code < 0x20 || (escapeNonAscii && c.code > 0x7E) -> sb.append(String.format("\\u%04x", c.code))
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }
    }
}
