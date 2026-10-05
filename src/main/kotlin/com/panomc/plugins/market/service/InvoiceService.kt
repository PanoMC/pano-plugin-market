package com.panomc.plugins.market.service

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.invoice.InvoiceBuild
import com.panomc.plugins.market.core.invoice.InvoiceIssueRules
import com.panomc.plugins.market.core.invoice.InvoiceNumbering
import com.panomc.plugins.market.core.invoice.InvoiceSkip
import com.panomc.plugins.market.core.invoice.InvoiceSnapshotBuilder
import com.panomc.plugins.market.core.invoice.InvoiceSnapshotConfig
import com.panomc.plugins.market.core.order.OrderEffect
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketInvoiceDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderEventDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketRefundDao
import com.panomc.plugins.market.db.dao.MarketRefundItemDao
import com.panomc.plugins.market.db.dao.MarketSequenceDao
import com.panomc.plugins.market.db.model.InvoiceType
import com.panomc.plugins.market.db.model.MarketInvoice
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderEvent
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketRefund
import com.panomc.plugins.market.db.model.OrderActorType
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.model.RefundStatus
import com.panomc.plugins.market.db.tx.LockedOrder
import com.panomc.plugins.market.db.tx.MarketDb
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/** The website values a document freezes next to the seller data (`MarketConfig` has no such keys: the platform config does). */
class InvoiceSite(val name: String, val url: String)

/** The answer of [InvoiceService.issueForOrder] and [InvoiceService.issueCreditNote]. */
sealed class InvoiceOutcome {
    /** A document was numbered and stored by this call. */
    class Issued(val invoice: MarketInvoice) : InvoiceOutcome()

    /** The document of this `(order, type, refund)` already existed (a replay or a concurrent issue that won): nothing changed. */
    class Existing(val invoice: MarketInvoice) : InvoiceOutcome()

    /** A condition of 12 section 6.1 does not hold: nothing was written, no number was taken. */
    class Skipped(val reason: InvoiceSkip) : InvoiceOutcome()

    /**
     * The document could not be built or stored: no row, no number consumed, the timeline got the note
     * `INVOICE_NOT_ISSUED:<reason>` (`CREDIT_NOTE_NOT_ISSUED:<reason>` for a credit note). The order still completes.
     */
    class NotIssued(val reason: String) : InvoiceOutcome()
}

/** The answer of [InvoiceService.setNextNumber]. */
sealed class SequenceChange {
    data object Changed : SequenceChange()

    /** `nextNumber - 1` is below the counter: numbers cannot repeat. [minimum] is the smallest `nextNumber` that is accepted. */
    class TooLow(val minimum: Long) : SequenceChange()

    /** The series is not `^[A-Z0-9]{1,8}$`. */
    data object InvalidSeries : SequenceChange()

    /** The value would not fit `market_invoice.number` any more ([InvoiceNumbering.MAX_SEQUENCE]). */
    class TooHigh(val maximum: Long) : SequenceChange()
}

/** One counter of the settings page: the last number taken in [series] (0 = none yet). */
class InvoiceSequenceInfo(val series: String, val lastNumber: Long) {
    val nextNumber: Long get() = lastNumber + 1
}

/**
 * Invoices and credit notes (12 sections 6 and 7): the numbering by series, the snapshot, the rows. It only writes rows; the PDF
 * is rendered lazily elsewhere (12 section 8), nothing here touches a file or the network.
 *
 * Every method that writes takes the [SqlConnection] of the caller's transaction (`MarketDb.tx`) and belongs at the **end** of
 * it: the counter row of `market_sequence` is lock level 8 of 00 section 8.3, taken after the order row and its children
 * (`Locks.forOrder`) and held until the commit, so numbers are gap-free and strictly increasing in commit order and a rollback
 * returns the number.
 *
 * [issueForOrder] and [issueCreditNote] never fail the caller's transaction for a business reason: a document that cannot be
 * built or stored is rolled back to the savepoint (its number goes back to the counter), leaves the timeline note and the
 * answer [InvoiceOutcome.NotIssued]. Only a deadlock or a lock wait timeout (the transaction must run again, [MarketDb]) and
 * cancellation propagate.
 */
class InvoiceService(
    private val config: () -> MarketConfig,
    private val clock: Clock,
    private val orders: MarketOrderDao,
    private val orderEvents: MarketOrderEventDao,
    private val refunds: MarketRefundDao,
    private val refundItems: MarketRefundItemDao,
    private val invoices: MarketInvoiceDao,
    private val sequences: MarketSequenceDao,
    /** The platform's website name and URL (`ConfigManager.config`). */
    private val site: () -> InvoiceSite = { InvoiceSite("", "") },
    /** `configManager.config.locale`: the last candidate of 12 section 2.3. */
    private val defaultLocale: () -> String = { DEFAULT_LOCALE },
    /** Hash of the seller logo of the moment, stored in the snapshot; `null` without a logo. */
    private val logoHash: () -> String? = { null }
) {
    private fun table(name: String) = "`${orders.prefix()}$name`"

    // ----- issue (12 section 6.1) ------------------------------------------------------------------------------------

    /**
     * Issues the `INVOICE` of [order] when all conditions of 12 section 6.1 hold (see [InvoiceIssueRules.invoiceBlock]) and
     * there is none yet. Call it inside the O2 / O4 transaction after the status update and before the mail enqueue, with the
     * order as it is **after** the transition (`paidAt`, amounts); [items] are its `market_order_item` rows.
     *
     * [admin] is the explicit panel action (regenerate, 12 section 8.4 b): the `invoiceEnabled` switch and the credit-only rule are
     * ignored and the order must be in a paid status.
     */
    suspend fun issueForOrder(conn: SqlConnection, order: MarketOrder, items: List<MarketOrderItem>, admin: Boolean = false): InvoiceOutcome =
        guarded(conn, order.id, InvoiceType.INVOICE, refundId = 0) {
            val block = InvoiceIssueRules.invoiceBlock(config(), order, admin)

            if (block != null) return@guarded InvoiceOutcome.Skipped(block)

            invoices.getByOrderTypeRefund(order.id, InvoiceType.INVOICE, 0, conn)?.let { return@guarded InvoiceOutcome.Existing(it) }

            issue(conn, order, items, InvoiceType.INVOICE, refund = null, original = null)
        }

    /**
     * Issues the `CREDIT_NOTE` of the refund [refundId] of [order] (12 section 6.1): the order has an `INVOICE`, the refund moved
     * money (`amount > 0`) and is `SUCCEEDED`. The caller is the O10 transaction (`RefundService`) and must have written the
     * `SUCCEEDED` status **before** it calls; the refund and its items are read again here, so the document never carries
     * numbers of a stale object. Chargebacks never get here (12 section 6.1), and `invoiceEnabled` does not matter.
     */
    suspend fun issueCreditNote(conn: SqlConnection, order: MarketOrder, items: List<MarketOrderItem>, refundId: Long): InvoiceOutcome =
        guarded(conn, order.id, InvoiceType.CREDIT_NOTE, refundId) {
            val original = invoices.getByOrderTypeRefund(order.id, InvoiceType.INVOICE, 0, conn)
            val refund = refunds.getById(refundId, conn)?.takeIf { it.orderId == order.id }
                ?: return@guarded InvoiceOutcome.Skipped(InvoiceSkip.REFUND_NOT_SUCCEEDED)
            val block = InvoiceIssueRules.creditNoteBlock(original != null, refund.amount, refund.status == RefundStatus.SUCCEEDED)

            if (block != null || original == null) return@guarded InvoiceOutcome.Skipped(block ?: InvoiceSkip.NO_INVOICE)

            invoices.getByOrderTypeRefund(order.id, InvoiceType.CREDIT_NOTE, refund.id, conn)?.let { return@guarded InvoiceOutcome.Existing(it) }

            issue(conn, order, items, InvoiceType.CREDIT_NOTE, refund = refund, original = original)
        }

    /**
     * The credit notes of [order]'s `SUCCEEDED` refunds that have none, in refund-id order (12 section 8.4 b). An order without an
     * invoice answers an empty list.
     */
    suspend fun issueMissingCreditNotes(conn: SqlConnection, order: MarketOrder, items: List<MarketOrderItem>): List<InvoiceOutcome> {
        if (invoices.getByOrderTypeRefund(order.id, InvoiceType.INVOICE, 0, conn) == null) return emptyList()

        val missing = refunds.getByOrderId(order.id, conn)
            .filter { it.status == RefundStatus.SUCCEEDED && it.amount > 0 }
            .sortedBy { it.id }
            .filter { invoices.getByOrderTypeRefund(order.id, InvoiceType.CREDIT_NOTE, it.id, conn) == null }

        return missing.map { issueCreditNote(conn, order, items, it.id) }
    }

    // ----- the numbered document ---------------------------------------------------------------------------------------

    private suspend fun issue(
        conn: SqlConnection,
        order: MarketOrder,
        items: List<MarketOrderItem>,
        type: InvoiceType,
        refund: MarketRefund?,
        original: MarketInvoice?
    ): InvoiceOutcome {
        val refundId = refund?.id ?: 0L
        val cfg = config()
        val now = clock.now()
        val zone = InvoiceNumbering.zone(cfg.storeTimeZone)
        val series = InvoiceNumbering.series(type, order.testMode, cfg.invoiceSeries, cfg.invoiceCreditNoteSeries)
        val locale = cfg.invoiceLocale.ifBlank { order.locale.orEmpty() }.ifBlank { defaultLocale() }.trim().take(MAX_LOCALE)
        val website = site()
        val snapshotConfig = InvoiceSnapshotConfig.of(cfg, website.name, website.url)
        val logo = logoHash()
        val refundRows = if (refund != null) refundItems.getByRefundId(refund.id, conn) else emptyList()

        fun build(number: String): InvoiceBuild = InvoiceSnapshotBuilder.build(
            order = order, items = items, refund = refund, refundItems = refundRows, invoice = original, config = snapshotConfig,
            sellerLogoHash = logo, issuedAt = now, number = number, locale = locale
        )

        // A document that cannot be built must not even touch the counter row: validate first, with no number yet.
        val dryRun = build("")

        if (dryRun is InvoiceBuild.Failed) return notIssued(conn, order.id, type, refundId, dryRun.reason.name, dryRun.detail)

        savepoint(conn)

        try {
            val sequence = sequences.next(InvoiceNumbering.sequenceName(series), conn)

            if (sequence > InvoiceNumbering.MAX_SEQUENCE) {
                rollbackToSavepoint(conn)

                return notIssued(conn, order.id, type, refundId, "SEQUENCE_EXHAUSTED", "series $series is at $sequence")
            }

            val number = InvoiceNumbering.format(series, sequence, now, zone)
            val built = build(number) as? InvoiceBuild.Built ?: error("the snapshot of $number stopped building after its dry run")

            val id = invoices.add(
                MarketInvoice(
                    orderId = order.id, type = type, refundId = refundId, series = series, sequence = sequence, number = number,
                    locale = locale, currency = order.currency, total = built.total, vatTotal = built.vatTotal,
                    snapshot = built.snapshot.encode(), fileName = null, issuedAt = now, createdAt = now, updatedAt = now
                ),
                conn
            )

            if (id == null) {
                // uq_order_type_refund: a concurrent issue won (it is the answer); uq_series_seq: the counter points at a taken number
                rollbackToSavepoint(conn)

                val winner = invoices.getByOrderTypeRefund(order.id, type, refundId, conn)

                return if (winner != null) {
                    InvoiceOutcome.Existing(winner)
                } else {
                    notIssued(conn, order.id, type, refundId, "SEQUENCE_CONFLICT", "$number is already taken in series $series")
                }
            }

            if (type == InvoiceType.INVOICE) {
                conn.preparedQuery("UPDATE ${table("market_order")} SET `invoiceId` = ? WHERE `id` = ?").execute(Tuple.of(id, order.id)).coAwait()
            }

            releaseSavepoint(conn)

            return InvoiceOutcome.Issued(checkNotNull(invoices.getById(id, conn)) { "invoice $id vanished inside its transaction" })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // a deadlock victim or a lock wait timeout: the transaction runs again from the start, nothing is swallowed
            if (MarketDb.retryableErrorCode(e) != null) throw e

            try {
                rollbackToSavepoint(conn)
            } catch (rollback: Exception) {
                // the connection is not usable any more: the transaction must fail instead of carrying on with a taken number
                e.addSuppressed(rollback)

                throw e
            }

            return notIssued(conn, order.id, type, refundId, "ERROR", e.toString())
        }
    }

    /** Anything unexpected before the savepoint (a read) is a failed issue too: the caller's transaction goes on. */
    private suspend fun guarded(conn: SqlConnection, orderId: Long, type: InvoiceType, refundId: Long, block: suspend () -> InvoiceOutcome): InvoiceOutcome =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (MarketDb.retryableErrorCode(e) != null) throw e

            notIssued(conn, orderId, type, refundId, reason = "ERROR", detail = e.toString())
        }

    private suspend fun notIssued(conn: SqlConnection, orderId: Long, type: InvoiceType, refundId: Long, reason: String, detail: String): InvoiceOutcome {
        logger.warn("order {}: {} not issued ({}): {}", orderId, type, reason, detail)

        val now = clock.now()
        val prefix = if (type == InvoiceType.INVOICE) "INVOICE_NOT_ISSUED" else "CREDIT_NOTE_NOT_ISSUED"

        try {
            orderEvents.add(
                MarketOrderEvent(
                    orderId = orderId, type = OrderEventType.NOTE, actorType = OrderActorType.SYSTEM, message = "$prefix:$reason",
                    data = JsonObject().put("refundId", refundId).encode(), createdAt = now, updatedAt = now
                ),
                conn
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (MarketDb.retryableErrorCode(e) != null) throw e

            // the note is a courtesy for the panel: the order must still complete
            logger.warn("order {}: the note {} could not be written: {}", orderId, prefix, e.toString())
        }

        return InvoiceOutcome.NotIssued(reason)
    }

    private suspend fun savepoint(conn: SqlConnection) {
        conn.query("SAVEPOINT $SAVEPOINT").execute().coAwait()
    }

    private suspend fun rollbackToSavepoint(conn: SqlConnection) {
        conn.query("ROLLBACK TO SAVEPOINT $SAVEPOINT").execute().coAwait()
    }

    private suspend fun releaseSavepoint(conn: SqlConnection) {
        conn.query("RELEASE SAVEPOINT $SAVEPOINT").execute().coAwait()
    }

    // ----- counters (12 section 6.2) -------------------------------------------------------------------------------------

    /**
     * The start value of a series (`PUT /settings/invoice-sequence`): sets the counter to `nextNumber - 1`, only when that is at
     * least the current value, so a number can never repeat. Run it inside `MarketDb.tx`: the counter row is locked until the
     * commit, so it cannot interleave with an issue.
     */
    suspend fun setNextNumber(conn: SqlConnection, series: String, nextNumber: Long): SequenceChange {
        if (!InvoiceNumbering.SERIES.matches(series)) return SequenceChange.InvalidSeries
        if (nextNumber - 1 > InvoiceNumbering.MAX_SEQUENCE) return SequenceChange.TooHigh(InvoiceNumbering.MAX_SEQUENCE + 1)

        val name = InvoiceNumbering.sequenceName(series)
        val now = clock.now()
        val sequenceTable = table("market_sequence")

        conn.preparedQuery("INSERT INTO $sequenceTable (`name`, `value`, `createdAt`, `updatedAt`) VALUES (?, 0, ?, ?) ON DUPLICATE KEY UPDATE `value` = `value`")
            .execute(Tuple.of(name, now, now)).coAwait()

        val current = conn.preparedQuery("SELECT `value` FROM $sequenceTable WHERE `name` = ? FOR UPDATE")
            .execute(Tuple.of(name)).coAwait().first().getLong(0)

        if (nextNumber - 1 < current) return SequenceChange.TooLow(current + 1)

        conn.preparedQuery("UPDATE $sequenceTable SET `value` = ?, `updatedAt` = ? WHERE `name` = ?")
            .execute(Tuple.of(nextNumber - 1, now, name)).coAwait()

        return SequenceChange.Changed
    }

    /**
     * The counters of the settings page (`invoiceSequences`): every `invoice:<series>` row, plus the two configured series when
     * they have no row yet, ordered by series.
     */
    suspend fun sequences(client: SqlClient): List<InvoiceSequenceInfo> {
        val cfg = config()
        val last = linkedMapOf<String, Long>()

        client.query("SELECT `name`, `value` FROM ${table("market_sequence")} WHERE `name` LIKE 'invoice:%'").execute().coAwait().forEach { row ->
            InvoiceNumbering.seriesOfSequenceName(row.getString("name"))?.let { last[it] = row.getLong("value") }
        }

        for (series in listOf(
            InvoiceNumbering.series(InvoiceType.INVOICE, false, cfg.invoiceSeries, cfg.invoiceCreditNoteSeries),
            InvoiceNumbering.series(InvoiceType.CREDIT_NOTE, false, cfg.invoiceSeries, cfg.invoiceCreditNoteSeries)
        )) {
            last.putIfAbsent(series, 0)
        }

        return last.entries.sortedBy { it.key }.map { InvoiceSequenceInfo(it.key, it.value) }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(InvoiceService::class.java)

        const val DEFAULT_LOCALE = "en-US"

        private const val MAX_LOCALE = 16
        private const val SAVEPOINT = "market_invoice"
    }
}

/**
 * The `IssueInvoice` effect of O2 / O4 (06 section 11) for [OrderService]: wrap the [ForeignEffects] it is given so that the effect
 * calls [InvoiceService.issueForOrder] and every other effect still goes to [next]. The order is read again, because the
 * `LockedOrder` was read before `StampPaid` set `paidAt`.
 *
 * Wiring (`OrderService(..., foreign = InvoiceEffects(invoices, orders, next))`) is the job of the composition root.
 */
class InvoiceEffects(
    private val invoices: InvoiceService,
    private val orders: MarketOrderDao,
    private val next: ForeignEffects = ForeignEffects.PENDING_SLICES
) : ForeignEffects {
    override suspend fun apply(conn: SqlConnection, locked: LockedOrder, effect: OrderEffect) {
        if (effect !is OrderEffect.IssueInvoice) {
            next.apply(conn, locked, effect)

            return
        }

        val order = orders.getById(locked.order.id, conn) ?: error("order ${locked.order.id} vanished inside its transaction")

        invoices.issueForOrder(conn, order, locked.items)
    }
}
