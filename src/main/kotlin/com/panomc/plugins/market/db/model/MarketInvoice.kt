package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

enum class InvoiceType { INVOICE, CREDIT_NOTE }

/** `market_invoice` (01 section 6.7): one invoice or credit note, numbered gap-free per series. */
open class MarketInvoice(
    val id: Long = -1,
    val orderId: Long = -1,
    val type: InvoiceType = InvoiceType.INVOICE,
    /** 0 for an invoice, the refund id for a credit note. */
    val refundId: Long = 0,
    val series: String = "",
    val sequence: Long = 0,
    /** `<series>-<yyyy>-<000001>`. */
    val number: String = "",
    val locale: String = "",
    val currency: String = "",
    val total: Long = 0,
    val vatTotal: Long = 0,
    /** JSON text: seller, buyer, billing, lines, VAT rows, totals. */
    val snapshot: String = "",
    val fileName: String? = null,
    val issuedAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
