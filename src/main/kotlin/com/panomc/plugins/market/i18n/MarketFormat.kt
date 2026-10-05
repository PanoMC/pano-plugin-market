package com.panomc.plugins.market.i18n

import com.panomc.plugins.market.core.money.Currencies
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Document formatting (12 section 2.4). Patterns and separators come from `server-format.*` so admins can override them.
 * Money is pure `Long` arithmetic on strings, never `Double`.
 */
class MarketFormat(
    private val i18n: MarketI18n,
    /** `MarketConfig.storeTimeZone`; empty or invalid = JVM default. */
    private val zone: () -> String = { "" },
    /** `MarketConfig.creditName`. */
    private val creditName: () -> String = { "" },
) {
    suspend fun date(ms: Long, locale: String): String = formatDate(ms, locale, "server-format.date", "yyyy-MM-dd")

    suspend fun dateTime(ms: Long, locale: String): String = formatDate(ms, locale, "server-format.date-time", "yyyy-MM-dd HH:mm")

    /** [amount] is x100; fraction digits follow the currency exponent; a negative amount gets a leading `-`. */
    suspend fun money(amount: Long, currency: String, locale: String): String {
        val exponent = try {
            Currencies.exponent(currency)
        } catch (e: IllegalArgumentException) {
            2
        }
        val number = number(amount, exponent, locale)
        val symbol = try {
            Currencies.symbol(currency)
        } catch (e: IllegalArgumentException) {
            currency
        }.ifEmpty { currency }
        val template = setting(locale, "server-format.money", "{symbol}{amount}")
        val body = MarketI18n.interpolate(template, mapOf("symbol" to symbol, "amount" to number.digits))
        return if (number.negative) "-$body" else body
    }

    suspend fun credits(amount: Long, locale: String): String {
        val number = number(amount, 2, locale)
        val text = number.digits + creditName().let { if (it.isEmpty()) "" else " $it" }
        return if (number.negative) "-$text" else text
    }

    /** [bp] basis points: up to two fraction digits, trailing zeros removed. */
    suspend fun percent(bp: Long, locale: String): String {
        val digits = bp.toString().removePrefix("-").padStart(3, '0')
        val whole = digits.dropLast(2)
        val frac = digits.takeLast(2).trimEnd('0')
        val dec = setting(locale, "server-format.decimal-separator", ".")
        val body = if (frac.isEmpty()) whole else whole + dec + frac
        return (if (bp < 0) "-" else "") + body + "%"
    }

    private class Number(val negative: Boolean, val digits: String)

    private suspend fun number(amount: Long, exponent: Int, locale: String): Number {
        val dec = setting(locale, "server-format.decimal-separator", ".")
        val group = setting(locale, "server-format.group-separator", ",")
        val digits = amount.toString().removePrefix("-").padStart(exponent + 1, '0')
        val whole = digits.dropLast(exponent)
        val frac = digits.takeLast(exponent)
        val grouped = StringBuilder()
        whole.forEachIndexed { i, c ->
            if (i > 0 && (whole.length - i) % 3 == 0) grouped.append(group)
            grouped.append(c)
        }
        if (exponent > 0) grouped.append(dec).append(frac)
        return Number(amount < 0, grouped.toString())
    }

    /** A setting is a locale key; when it resolves to nothing (the key itself) or blank-less default, [default] is used. */
    private suspend fun setting(locale: String, key: String, default: String): String =
        if (i18n.has(locale, key)) i18n.t(locale, key) else default

    private suspend fun formatDate(ms: Long, locale: String, key: String, fallback: String): String {
        val zoneId = try {
            zone().takeIf { it.isNotEmpty() }?.let { ZoneId.of(it) } ?: ZoneId.systemDefault()
        } catch (e: Exception) {
            ZoneId.systemDefault()
        }
        val jvmLocale = Locale.forLanguageTag(locale.replace('_', '-'))
        val pattern = setting(locale, key, fallback)
        val formatter = try {
            DateTimeFormatter.ofPattern(pattern, jvmLocale)
        } catch (e: IllegalArgumentException) {
            DateTimeFormatter.ofPattern(fallback, jvmLocale)
        }
        return formatter.withZone(zoneId).format(Instant.ofEpochMilli(ms))
    }
}
