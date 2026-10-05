package com.panomc.plugins.market.core.cart

import java.math.BigDecimal
import java.security.MessageDigest

/**
 * The identity of a cart line (06 section 2.1, 01 section 4.2): lower-case hex SHA-1 of the UTF-8 string
 * `"<productId>|<variantId or 0>|<canonical(fieldValues)>|<targetServerId or 0>"`. Identical lines share a key and merge.
 * The theme computes the same key for the browser cart; its value is advisory, the server always recomputes.
 * Pure: no database, no clock.
 */
object CartLineKey {
    fun of(productId: Long, variantId: Long?, fieldValues: Map<String, Any?>?, targetServerId: Long?): String {
        val preimage = preimage(productId, variantId, fieldValues, targetServerId)

        return MessageDigest.getInstance("SHA-1").digest(preimage.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun preimage(productId: Long, variantId: Long?, fieldValues: Map<String, Any?>?, targetServerId: Long?): String =
        "$productId|${variantId ?: 0}|${canonical(fieldValues)}|${targetServerId ?: 0}"

    /**
     * Keys whose value is `null` or blank are dropped; every value becomes a string (`true` / `false`, numbers without
     * a trailing zero or exponent, text trimmed); keys sorted by code point (the byte order of UTF-8); compact JSON, `{}`
     * when nothing is left. Independent of the order the keys arrive in.
     */
    fun canonical(fieldValues: Map<String, Any?>?): String {
        val entries = stringValues(fieldValues)
        val sb = StringBuilder("{")

        entries.entries.sortedWith { a, b -> compareCodePoints(a.key, b.key) }.forEachIndexed { index, (key, value) ->
            if (index > 0) sb.append(',')

            appendJsonString(sb, key)
            sb.append(':')
            appendJsonString(sb, value)
        }

        return sb.append('}').toString()
    }

    /** The values of [fieldValues] as the strings that enter the key; dropped keys are absent. */
    fun stringValues(fieldValues: Map<String, Any?>?): Map<String, String> {
        val out = LinkedHashMap<String, String>()

        fieldValues?.forEach { (key, raw) ->
            val text = asText(raw)?.trim()

            if (!text.isNullOrEmpty()) out[key] = text
        }

        return out
    }

    /**
     * What a stored line keeps of [fieldValues]: blank and `null` values dropped, text trimmed, numbers and booleans
     * left typed (04 section 4: `fieldValues` JSON types are string, number, boolean).
     */
    fun normalize(fieldValues: Map<String, Any?>?): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()

        fieldValues?.forEach { (key, raw) ->
            val text = asText(raw)?.trim()

            if (text.isNullOrEmpty()) return@forEach

            out[key] = if (raw is String) text else raw!!
        }

        return out
    }

    private fun asText(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is Boolean -> value.toString()
        is Int, is Long, is Short, is Byte -> value.toString()
        is Number -> numberText(value)
        else -> value.toString()
    }

    private fun numberText(value: Number): String {
        val decimal = when (value) {
            is BigDecimal -> value
            else -> runCatching { BigDecimal(value.toString()) }.getOrNull() ?: return value.toString()
        }

        return if (decimal.signum() == 0) "0" else decimal.stripTrailingZeros().toPlainString()
    }

    private fun compareCodePoints(a: String, b: String): Int {
        var i = 0
        var j = 0

        while (i < a.length && j < b.length) {
            val ca = a.codePointAt(i)
            val cb = b.codePointAt(j)

            if (ca != cb) return ca.compareTo(cb)

            i += Character.charCount(ca)
            j += Character.charCount(cb)
        }

        return (a.length - i).compareTo(b.length - j)
    }

    /** The escaping of `JSON.stringify`, so the theme's preimage is byte-identical. */
    private fun appendJsonString(sb: StringBuilder, text: String) {
        sb.append('"')

        var i = 0

        while (i < text.length) {
            val c = text[i]

            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append("%04x".format(c.code))
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                    sb.append(c).append(text[i + 1])
                    i++
                }

                Character.isSurrogate(c) -> sb.append("\\u").append("%04x".format(c.code))
                else -> sb.append(c)
            }

            i++
        }

        sb.append('"')
    }
}
