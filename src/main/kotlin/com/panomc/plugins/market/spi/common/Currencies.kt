package com.panomc.plugins.market.spi.common

/**
 * ISO 4217 currencies the store can handle: exponent 0 (JPY) or 2 (EUR). Three- and four-decimal currencies
 * (KWD, BHD, CLF, ...) are known but rejected on purpose (00 section 6.4). Codes are upper-case, exact match.
 *
 * Lives in `spi.common` because [Money] needs it and no `spi` class may reference market classes outside `spi`
 * (02 section 9); market code uses the `core.money.Currencies` facade with the same API.
 */
object Currencies {
    private val zeroDecimal: Set<String> = setOf(
        "BIF", "CLP", "DJF", "GNF", "ISK", "JPY", "KMF", "KRW", "PYG", "RWF", "UGX", "UYI", "VND", "VUV", "XAF", "XOF", "XPF"
    )

    private val twoDecimal: Set<String> = (
        "AED AFN ALL AMD ANG AOA ARS AUD AWG AZN BAM BBD BDT BGN BMD BND BOB BRL BSD BTN BWP BYN BZD CAD CDF CHF CNY COP " +
            "CRC CUP CVE CZK DKK DOP DZD EGP ERN ETB EUR FJD FKP GBP GEL GHS GIP GMD GTQ GYD HKD HNL HTG HUF IDR ILS INR " +
            "IRR JMD KES KGS KHR KYD KZT LAK LBP LKR LRD LSL MAD MDL MGA MKD MMK MNT MOP MRU MUR MVR MWK MXN MYR MZN NAD " +
            "NGN NIO NOK NPR NZD PAB PEN PGK PHP PKR PLN QAR RON RSD RUB SAR SBD SCR SDG SEK SGD SHP SLE SOS SRD SSP STN " +
            "SYP SZL THB TJS TMT TOP TRY TTD TWD TZS UAH USD UYU UZS VES WST XCD YER ZAR ZMW ZWL"
        ).split(' ').toSet()

    /** Known ISO codes with more than two decimals: refused by [isSupported]. */
    private val unsupportedKnown: Set<String> = setOf("BHD", "IQD", "JOD", "KWD", "LYD", "OMR", "TND", "CLF", "UYW")

    private val symbols: Map<String, String> = mapOf(
        "TRY" to "₺", "USD" to "$", "EUR" to "€", "GBP" to "£", "JPY" to "¥", "CNY" to "CN¥", "KRW" to "₩", "INR" to "₹",
        "RUB" to "₽", "UAH" to "₴", "PLN" to "zł", "CZK" to "Kč", "HUF" to "Ft", "RON" to "lei", "BGN" to "лв",
        "CHF" to "CHF", "SEK" to "kr", "NOK" to "kr", "DKK" to "kr", "ILS" to "₪", "VND" to "₫", "THB" to "฿",
        "PHP" to "₱", "NGN" to "₦", "BRL" to "R$", "AUD" to "A$", "CAD" to "CA$", "NZD" to "NZ$", "HKD" to "HK$",
        "MXN" to "MX$", "TWD" to "NT$", "SGD" to "S$", "ZAR" to "R", "AED" to "AED", "SAR" to "SAR", "EGP" to "E£",
        "KZT" to "₸", "GEL" to "₾", "AZN" to "₼", "IDR" to "Rp", "MYR" to "RM", "PKR" to "Rs", "BDT" to "৳",
        "LKR" to "Rs", "NPR" to "Rs", "KES" to "KSh", "ARS" to "AR$", "CLP" to "CL$", "COP" to "CO$", "PEN" to "S/"
    )

    /** Known ISO code with exponent 0 or 2. */
    fun isSupported(code: String): Boolean = code in zeroDecimal || code in twoDecimal

    /** All supported codes, sorted. */
    fun all(): List<String> = (zeroDecimal + twoDecimal).sorted()

    /** 0 or 2. Throws [IllegalArgumentException] for a code [isSupported] refuses. */
    fun exponent(code: String): Int = when (code) {
        in zeroDecimal -> 0
        in twoDecimal -> 2
        else -> throw IllegalArgumentException(
            if (code in unsupportedKnown) "Currency $code has more than two decimals and is not supported"
            else "Unknown or unsupported currency '$code'"
        )
    }

    /** Display symbol; the code itself when the currency has no common symbol. Throws for an unsupported code. */
    fun symbol(code: String): String {
        exponent(code)
        return symbols[code] ?: code
    }
}
