package com.panomc.plugins.market.core.invoice

import com.panomc.plugins.market.db.model.InvoiceType
import java.time.Instant
import java.time.ZoneId

/**
 * Series, counter names and document numbers of invoices and credit notes (12 section 6.2). Pure.
 *
 * ```
 * series   INVOICE -> MarketConfig.invoiceSeries (default INV), CREDIT_NOTE -> invoiceCreditNoteSeries (default CN),
 *          any document of a test-mode order -> TEST
 * counter  market_sequence name "invoice:<series>", never reset
 * number   <series>-<yyyy>-<sequence left-padded with zeros to 6>, yyyy = year of issuedAt in storeTimeZone
 * ```
 */
object InvoiceNumbering {
    /** The series of every document of a test-mode order: keeps the real numbering free of test documents. */
    const val TEST_SERIES = "TEST"

    const val DEFAULT_INVOICE_SERIES = "INV"
    const val DEFAULT_CREDIT_NOTE_SERIES = "CN"

    /** A series is 1 to 8 characters of `A-Z0-9` (`MarketConfigKeys.INVOICE_SERIES`). */
    val SERIES = Regex("^[A-Z0-9]{1,8}$")

    /**
     * The largest sequence value that is still stored: 15 digits keep `<series>-<yyyy>-<digits>` (8 + 6 + 15 = 29 characters)
     * inside `market_invoice.number VARCHAR(32)`.
     */
    const val MAX_SEQUENCE = 999_999_999_999_999L

    private const val PAD = 6

    /**
     * The series a document is numbered in. A configured value that is not a valid series (a hand-edited config file; the
     * panel validates on save) or is `TEST` falls back to the default, so a file name never carries a stray character.
     */
    fun series(type: InvoiceType, testMode: Boolean, invoiceSeries: String, creditNoteSeries: String): String {
        if (testMode) return TEST_SERIES

        return when (type) {
            InvoiceType.INVOICE -> usable(invoiceSeries, DEFAULT_INVOICE_SERIES)
            InvoiceType.CREDIT_NOTE -> usable(creditNoteSeries, DEFAULT_CREDIT_NOTE_SERIES)
        }
    }

    private fun usable(configured: String, default: String): String =
        if (SERIES.matches(configured) && configured != TEST_SERIES) configured else default

    /** `market_sequence.name` of a series. */
    fun sequenceName(series: String): String = "invoice:$series"

    /** The series a `market_sequence` row name stands for, or `null` for another kind of row (a `fixup:<id>` marker). */
    fun seriesOfSequenceName(name: String): String? = name.removePrefix("invoice:").takeIf { name.startsWith("invoice:") && SERIES.matches(it) }

    /** `MarketConfig.storeTimeZone`: empty or unknown means the JVM default zone (00 section 9). */
    fun zone(storeTimeZone: String): ZoneId = try {
        if (storeTimeZone.isBlank()) ZoneId.systemDefault() else ZoneId.of(storeTimeZone.trim())
    } catch (e: Exception) {
        ZoneId.systemDefault()
    }

    /** The year of [issuedAt] (epoch ms) in [zone]. */
    fun year(issuedAt: Long, zone: ZoneId): Int = Instant.ofEpochMilli(issuedAt).atZone(zone).year

    /** `<series>-<yyyy>-<sequence padded to 6>`; beyond 999999 the number simply grows. */
    fun format(series: String, sequence: Long, issuedAt: Long, zone: ZoneId): String {
        require(sequence >= 1) { "a sequence value starts at 1: $sequence" }

        return "$series-${year(issuedAt, zone)}-${sequence.toString().padStart(PAD, '0')}"
    }
}
