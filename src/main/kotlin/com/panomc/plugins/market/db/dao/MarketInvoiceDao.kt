package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.InvoiceType
import com.panomc.plugins.market.db.model.MarketInvoice
import io.vertx.sqlclient.SqlClient

/** Invoices and credit notes (01 section 6.7). */
abstract class MarketInvoiceDao : MarketDao<MarketInvoice>(MarketInvoice::class.java) {
    /** The new id, or `null` on `uq_series_seq` / `uq_order_type_refund`. */
    abstract suspend fun add(invoice: MarketInvoice, sqlClient: SqlClient): Long?

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketInvoice?

    abstract suspend fun getByOrderId(orderId: Long, sqlClient: SqlClient): List<MarketInvoice>

    abstract suspend fun getByOrderTypeRefund(orderId: Long, type: InvoiceType, refundId: Long, sqlClient: SqlClient): MarketInvoice?
}
