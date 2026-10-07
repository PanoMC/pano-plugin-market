package com.panomc.plugins.market.routes.panel.invoice

import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.invoice.InvoiceIssueRules
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketInvoiceDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.model.InvoiceType
import com.panomc.plugins.market.db.model.MarketInvoice
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.error.InvalidInvoiceSequence
import com.panomc.plugins.market.error.InvoiceNotIssuable
import com.panomc.plugins.market.error.InvoiceRenderFailed
import com.panomc.plugins.market.pdf.InvoiceDocuments
import com.panomc.plugins.market.pdf.InvoiceRenderException
import com.panomc.plugins.market.pdf.InvoiceSample
import com.panomc.plugins.market.routes.api.OrderAccessResult
import com.panomc.plugins.market.service.InvoiceOutcome
import com.panomc.plugins.market.service.InvoiceService
import com.panomc.plugins.market.service.InvoiceSite
import com.panomc.plugins.market.service.SequenceChange
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.io.File

/** A PDF ready to be sent: the stored [file] and the document [number] it is named after (`<number>.pdf`). */
class InvoiceFile(val file: File, val number: String)

/**
 * What the invoice routes do (12 sections 8.4 and 9; 04 sections 3 and 7), as a service so the rules run in a database test without the
 * host: which row a request means, who may have its file, the regeneration, the preview and the series counter. The routes only parse,
 * call this and write the activity log.
 */
class InvoiceEndpoints(
    private val db: MarketDb,
    private val locks: Locks,
    private val orders: MarketOrderDao,
    private val invoices: MarketInvoiceDao,
    private val service: InvoiceService,
    private val documents: InvoiceDocuments,
    private val config: () -> MarketConfig,
    private val clock: Clock,
    private val site: () -> InvoiceSite,
    private val defaultLocale: () -> String
) {
    // ----- download (12 sections 9.1 and 9.2) ---------------------------------------------------------------------------

    /**
     * The row [type] / [refundId] of an order. A credit note without `refundId` is the order's only credit note; with several (or none) it is
     * not found, because the caller did not say which one. No row is a 404.
     */
    suspend fun find(orderId: Long, type: InvoiceType, refundId: Long?, client: SqlClient): MarketInvoice =
        when (type) {
            InvoiceType.INVOICE -> invoices.getByOrderTypeRefund(orderId, InvoiceType.INVOICE, 0, client)
            InvoiceType.CREDIT_NOTE ->
                if (refundId != null) invoices.getByOrderTypeRefund(orderId, InvoiceType.CREDIT_NOTE, refundId, client)
                else invoices.getByOrderId(orderId, client).filter { it.type == InvoiceType.CREDIT_NOTE }.singleOrNull()
        } ?: throw NotFound()

    /** `GET /api/market/orders/:publicId/invoice`: the owner only (the limited view, a gift recipient included, gets 404, never a hint). */
    suspend fun buyerFile(access: OrderAccessResult, type: InvoiceType, refundId: Long?, client: SqlClient): InvoiceFile {
        val order = access.requireOwner()

        return fileOf(find(order.id, type, refundId, client))
    }

    /** `GET /api/panel/market/orders/:id/invoice`: any order; the node check (`OM` or `PAY`) is the route's. */
    suspend fun panelFile(orderId: Long, type: InvoiceType, refundId: Long?, client: SqlClient): InvoiceFile {
        orders.getById(orderId, client) ?: throw NotFound()

        return fileOf(find(orderId, type, refundId, client))
    }

    private suspend fun fileOf(invoice: MarketInvoice): InvoiceFile = InvoiceFile(rendering { documents.ensureFile(invoice) }, invoice.number)

    // ----- regenerate (12 section 8.4) ----------------------------------------------------------------------------------

    /**
     * For each document of the order the cached file is dropped and rendered again from the same snapshot (same number, the current texts
     * and logo); when the order has no `INVOICE` and was paid, one is issued now (`issuedAt` = now) followed by the credit notes of its
     * `SUCCEEDED` refunds. Nothing to do and nothing issuable is 409 `INVOICE_NOT_ISSUABLE` with the `reason`. Rendering never runs in the
     * transaction.
     */
    suspend fun regenerate(orderId: Long, client: SqlClient): List<MarketInvoice> {
        val order = orders.getById(orderId, client) ?: throw NotFound()
        var reason: String? = null

        if (invoices.getByOrderTypeRefund(orderId, InvoiceType.INVOICE, 0, client) == null) {
            reason = if (order.status !in InvoiceIssueRules.PAID_STATUSES) {
                "NOT_PAID"
            } else {
                db.txRestartingOnOrderChange { conn ->
                    locks.forOrder(conn, orderId, OrderLockScope.PAYMENT) { locked ->
                        val outcome = service.issueForOrder(conn, locked.order, locked.items, admin = true)

                        service.issueMissingCreditNotes(conn, locked.order, locked.items)

                        reasonOf(outcome)
                    }
                }
            }
        }

        val documentsOfOrder = invoices.getByOrderId(orderId, client)

        if (documentsOfOrder.isEmpty()) throw InvoiceNotIssuable(reason ?: "NOT_ISSUABLE")

        for (document in documentsOfOrder) {
            documents.forget(document)

            rendering { documents.ensureFile(invoices.getById(document.id, client) ?: document) }
        }

        return documentsOfOrder
    }

    private fun reasonOf(outcome: InvoiceOutcome): String? = when (outcome) {
        is InvoiceOutcome.Issued, is InvoiceOutcome.Existing -> null
        is InvoiceOutcome.Skipped -> outcome.reason.name
        is InvoiceOutcome.NotIssued -> outcome.reason
    }

    // ----- preview and series (12 sections 6.2 and 9.2) -----------------------------------------------------------------

    /**
     * The sample PDF of the settings page: the built-in sample snapshot under the current seller settings, number `INV-0000-000000`
     * (`CN-0000-000000` for a credit note), never stored. [locale] empty = `invoiceLocale`, else the platform's.
     */
    suspend fun preview(locale: String?, type: InvoiceType): ByteArray {
        val cfg = config()
        val chosen = locale?.takeIf { it.isNotBlank() } ?: cfg.invoiceLocale.ifBlank { defaultLocale() }
        val website = site()
        val seller = JsonObject()
            .put("name", cfg.invoiceSellerName.ifBlank { website.name })
            .put("address", cfg.invoiceSellerAddress)
            .put("taxOffice", cfg.invoiceSellerTaxOffice)
            .put("taxNumber", cfg.invoiceSellerTaxNumber)
            .put("websiteName", website.name)
            .put("websiteUrl", website.url)
        val snapshot = InvoiceSample.snapshot(
            type == InvoiceType.CREDIT_NOTE, chosen, cfg.currency, cfg.storeTimeZone, seller, cfg.invoiceFooter, clock.now()
        )

        return rendering { documents.render(snapshot, chosen) }
    }

    /** `PUT /settings/invoice-sequence`: only upwards (12 section 6.2); 400 `INVALID_INVOICE_SEQUENCE` with the smallest accepted `minimum`. */
    suspend fun setSequence(series: String, nextNumber: Long) {
        when (val change = db.tx { conn -> service.setNextNumber(conn, series, nextNumber) }) {
            SequenceChange.Changed -> {}
            is SequenceChange.TooLow -> throw InvalidInvoiceSequence(change.minimum)
            SequenceChange.InvalidSeries, is SequenceChange.TooHigh -> throw InvalidInvoiceSequence()
        }
    }

    /** `invoiceSequences` of `GET /settings`: `[{series, lastNumber, nextNumber}]` (04 names `nextNumber`, 12 `lastNumber`: both are sent). */
    suspend fun sequences(client: SqlClient): JsonArray =
        JsonArray(service.sequences(client).map { JsonObject().put("series", it.series).put("lastNumber", it.lastNumber).put("nextNumber", it.nextNumber) })

    // ----- helpers ------------------------------------------------------------------------------------------------------

    /** A render failure is 500 `INVOICE_RENDER_FAILED`; the cause stays in the log, never in the body. */
    private suspend fun <T> rendering(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: InvoiceRenderException) {
        logger.error("Invoice PDF could not be produced: {}", e.message, e)

        throw InvoiceRenderFailed()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(InvoiceEndpoints::class.java)
    }
}
