package com.panomc.plugins.market.pdf

import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.model.InvoiceType
import com.panomc.plugins.market.db.model.MailKind
import com.panomc.plugins.market.db.model.MailRefType
import com.panomc.plugins.market.db.model.MarketMailOutbox
import com.panomc.plugins.market.mail.MailAttachment
import com.panomc.plugins.market.mail.MailAttachments
import com.panomc.plugins.market.db.dao.MarketInvoiceDao
import com.panomc.plugins.market.db.model.MarketInvoice
import com.panomc.plugins.market.i18n.MarketFormat
import com.panomc.plugins.market.i18n.MarketI18n
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The `invoice.*` keys the renderer asks for. [InvoiceTextsFactory] resolves exactly these before it renders; a test pins that the renderer
 * asks for nothing else and that every key exists in tr, en-US and ru.
 */
object InvoiceLabelKeys {
    val ALL: List<String> = listOf(
        "title", "credit-note-title", "test-notice", "number", "date", "order", "for-invoice", "seller", "bill-to", "username", "recipient",
        "tax-office", "tax-number", "identity-number", "email", "phone", "description", "sku", "quantity", "unit-price", "discount", "incl-vat",
        "excl-vat", "vat-percent", "vat", "line-total", "vat-rate", "net", "gross", "subtotal", "shipping", "payment-fee", "net-total", "vat-total",
        "total", "refund-total", "paid-with", "paid-with-credits", "refunded-to-method", "refunded-as-credits", "line-shipping", "line-payment-fee",
        "line-refund", "line-credit-topup", "page"
    ).map { "invoice.$it" }
}

/** [InvoiceTexts] with everything looked up beforehand: the renderer never suspends (12 section 8.2). */
class PrecomputedTexts(
    private val templates: Map<String, String>,
    private val amounts: Map<Long, String>,
    private val dates: Map<Long, String>,
    private val percents: Map<Long, String>,
    override val creditName: String
) : InvoiceTexts {
    override fun label(key: String, vars: Map<String, Any?>): String = MarketI18n.interpolate(templates[key] ?: key, vars)

    override fun money(amount: Long): String = amounts[amount] ?: amount.toString()

    override fun date(ms: Long): String = dates[ms] ?: ms.toString()

    override fun percent(bp: Long): String = percents[bp] ?: bp.toString()
}

/** Builds the [InvoiceTexts] of one snapshot through [MarketI18n] and [MarketFormat] (12 sections 2.4 and 8.2). */
object InvoiceTextsFactory {
    suspend fun build(snapshot: JsonObject, locale: String, i18n: MarketI18n, format: MarketFormat, creditName: String): InvoiceTexts {
        val currency = snapshot.getString("currency").orEmpty()
        val templates = InvoiceLabelKeys.ALL.associateWith { i18n.t(locale, it) }
        val amounts = LinkedHashSet<Long>()
        val percents = LinkedHashSet<Long>()

        fun amount(o: JsonObject, vararg keys: String) {
            for (k in keys) (o.getValue(k) as? Number)?.let { amounts.add(it.toLong()) }
        }

        for (l in snapshot.getJsonArray("lines") ?: JsonArray()) {
            val line = l as? JsonObject ?: continue

            amount(line, "unitPrice", "discount", "vat", "gross", "net")
            (line.getValue("vatPercent") as? Number)?.let { percents.add(it.toLong()) }
        }

        for (r in snapshot.getJsonArray("vatRows") ?: JsonArray()) {
            val row = r as? JsonObject ?: continue

            amount(row, "net", "vat", "gross")
            (row.getValue("vatPercent") as? Number)?.let { percents.add(it.toLong()) }
        }

        snapshot.getJsonObject("totals")?.let { totals ->
            for (k in totals.fieldNames()) (totals.getValue(k) as? Number)?.let { amounts.add(it.toLong()) }

            // the discount row of the totals box is drawn negative
            (totals.getValue("discount") as? Number)?.let { amounts.add(-it.toLong()) }
        }

        val times = LinkedHashSet<Long>()

        (snapshot.getValue("issuedAt") as? Number)?.let { times.add(it.toLong()) }
        snapshot.getJsonObject("order")?.let { o -> for (k in listOf("createdAt", "paidAt")) (o.getValue(k) as? Number)?.let { times.add(it.toLong()) } }
        (snapshot.getJsonObject("ref")?.getValue("issuedAt") as? Number)?.let { times.add(it.toLong()) }

        return PrecomputedTexts(
            templates = templates,
            amounts = amounts.associateWith { format.money(it, currency, locale) },
            dates = times.associateWith { format.date(it, locale) },
            percents = percents.associateWith { format.percent(it, locale) },
            creditName = creditName
        )
    }
}

/** An invoice number may only hold these characters (12 section 8.3): it becomes a file name. */
private val NUMBER_PATTERN = Regex("^[A-Z0-9-]{1,64}$")

/**
 * Storage and lazy rendering of the PDFs (12 sections 8.3 and 8.4). The file is a cache of the snapshot: `market_invoice.fileName` names it
 * relative to [base] (`<yyyy>/<number>.pdf`), a missing file (a restore, a lost data folder) is rendered again on the next request, a
 * render failure is [InvoiceRenderException]. Nothing here runs inside a database transaction.
 */
class InvoiceDocuments(
    /** `<pluginDataFolder>/invoices`; never web-served. */
    private val base: Path,
    private val invoices: MarketInvoiceDao,
    private val clock: Clock,
    private val texts: suspend (snapshot: JsonObject, locale: String) -> InvoiceTexts,
    /** The seller logo bytes of the moment, `null` when there is none or `invoiceShowLogo` is off. */
    private val logo: suspend () -> ByteArray?,
    /** `vertx.executeBlocking`: the renderer is CPU bound. */
    private val onWorker: suspend (() -> ByteArray) -> ByteArray,
    private val sqlClient: suspend () -> SqlClient
) {
    private val root: Path = base.toAbsolutePath().normalize()

    private fun table() = "`${invoices.prefix()}market_invoice`"

    /** The path of a stored file; `null` for a name that would leave [root] (never taken from a client, defence in depth). */
    private fun resolve(fileName: String): Path? {
        val path = root.resolve(fileName).normalize()

        return path.takeIf { it.startsWith(root) && it != root }
    }

    /** The relative name of [invoice]: `<yyyy of issuedAt, UTC>/<number>.pdf`. */
    fun relativeName(invoice: MarketInvoice): String {
        if (!NUMBER_PATTERN.matches(invoice.number)) throw InvoiceRenderException("invoice number '${invoice.number}' cannot be a file name")

        val year = Instant.ofEpochMilli(invoice.issuedAt).atOffset(ZoneOffset.UTC).year

        return "%04d/%s.pdf".format(year, invoice.number)
    }

    /**
     * The PDF of [invoice]: the stored file when it exists and is not empty, else rendered from the snapshot, written to a temp file in the
     * same directory and moved over the target atomically, then `fileName` / `updatedAt` are set. Concurrent calls are harmless.
     */
    suspend fun ensureFile(invoice: MarketInvoice): File {
        invoice.fileName?.let { name ->
            val existing = resolve(name)

            if (existing != null && Files.isRegularFile(existing) && Files.size(existing) > 0) return existing.toFile()
        }

        val relative = relativeName(invoice)
        val target = resolve(relative) ?: throw InvoiceRenderException("invoice file name '$relative' leaves the invoice folder")
        val bytes = renderBytes(invoice)

        try {
            Files.createDirectories(target.parent)

            val tmp = target.resolveSibling("${target.fileName}.${UUID.randomUUID()}.tmp")

            try {
                Files.write(tmp, bytes)
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(tmp)
            }
        } catch (e: Exception) {
            throw InvoiceRenderException("invoice PDF could not be stored: ${e.message}", e)
        }

        sqlClient().preparedQuery("UPDATE ${table()} SET `fileName` = ?, `updatedAt` = ? WHERE `id` = ?")
            .execute(Tuple.of(relative, clock.now(), invoice.id)).coAwait()

        return target.toFile()
    }

    /** Renders [invoice] without touching the disk or the row (the mail attachment path and `ensureFile` share it). */
    suspend fun renderBytes(invoice: MarketInvoice): ByteArray {
        try {
            val snapshot = JsonObject(invoice.snapshot)

            return render(snapshot, invoice.locale)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvoiceRenderException) {
            throw e
        } catch (e: Exception) {
            throw InvoiceRenderException("invoice ${invoice.number} could not be rendered: ${e.message}", e)
        }
    }

    /** Renders any snapshot (the settings preview uses a built-in sample, nothing is stored). */
    suspend fun render(snapshot: JsonObject, locale: String): ByteArray {
        val t = texts(snapshot, locale)
        val image = logo()

        return onWorker { InvoicePdfRenderer.render(snapshot, t, image) }
    }

    /**
     * Forgets the cached file of [invoice] (regenerate, 12 section 8.4 a): the file is deleted and `fileName` set to `NULL`; the next
     * [ensureFile] renders the same snapshot again with the current texts and logo.
     */
    suspend fun forget(invoice: MarketInvoice) {
        invoice.fileName?.let { name ->
            val path = resolve(name)

            try {
                if (path != null) Files.deleteIfExists(path)
            } catch (e: Exception) {
                logger.warn("Could not delete the cached PDF {}: {}", name, e.message)
            }
        }

        sqlClient().preparedQuery("UPDATE ${table()} SET `fileName` = NULL, `updatedAt` = ? WHERE `id` = ?")
            .execute(Tuple.of(clock.now(), invoice.id)).coAwait()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(InvoiceDocuments::class.java)
    }
}

/** The built-in sample of `GET /settings/invoice/preview` (12 section 9.2): number `INV-0000-000000`, never stored, the current seller settings. */
object InvoiceSample {
    fun snapshot(
        creditNote: Boolean, locale: String, currency: String, timeZone: String, seller: JsonObject, footer: String, now: Long,
        pricesIncludeVat: Boolean = true
    ): JsonObject {
        val lines = if (creditNote) {
            JsonArray().add(sampleLine("REFUND", "", null, null, 1, 1990, 0, 2000))
        } else {
            JsonArray()
                .add(sampleLine("PRODUCT", "VIP Rank", "30 days", "VIP-30", 1, 5990, 0, 2000))
                .add(sampleLine("PRODUCT", "Crate keys", null, "KEY-5", 2, 1990, 500, 2000))
                .add(sampleLine("SHIPPING", "", null, null, 1, 990, 0, 2000))
        }

        val total = lines.sumOf { (it as JsonObject).getLong("gross") }
        val totalVat = lines.sumOf { (it as JsonObject).getLong("vat") }
        val discount = if (creditNote) 0L else 500L
        val shipping = if (creditNote) 0L else 990L

        return JsonObject()
            .put("v", 1)
            .put("type", if (creditNote) "CREDIT_NOTE" else "INVOICE")
            .put("number", if (creditNote) "CN-0000-000000" else "INV-0000-000000")
            .put("issuedAt", now)
            .put("locale", locale)
            .put("currency", currency)
            .put("timeZone", timeZone)
            .put("testMode", false)
            .put("seller", seller)
            .put(
                "buyer",
                JsonObject().put("username", "Steve").put("type", "INDIVIDUAL").put("name", "Alex Example").put("company", "").put("taxOffice", "")
                    .put("taxNumber", "").put("identityNumber", "*******8901").put("addressLines", JsonArray().add("1 Sample Street").add("34000 Sample City"))
                    .put("country", "TR").put("email", "buyer@example.com").put("phone", "")
            )
            .put("order", JsonObject().put("id", 1).put("publicId", "0000000000SAMPLE0000").put("createdAt", now).put("paidAt", now).put("paymentLabel", "Card").put("isGift", false).put("recipientUsername", "Steve"))
            .put("pricesIncludeVat", pricesIncludeVat)
            .put("lines", lines)
            .put(
                "vatRows",
                JsonArray().add(JsonObject().put("vatPercent", 2000).put("net", total - totalVat).put("vat", totalVat).put("gross", total))
            )
            .put(
                "totals",
                JsonObject().put("subtotal", total + discount).put("discount", discount).put("shipping", shipping)
                    .put("paymentFee", 0).put("net", total - totalVat).put("vat", totalVat).put("total", total).put("creditValue", 0).put("creditAmount", 0)
                    .put("gatewayAmount", total)
            )
            .put("footer", footer)
            .put("ref", if (creditNote) JsonObject().put("number", "INV-0000-000000").put("issuedAt", now).put("refundId", 1).put("reason", "sample") else null)
    }

    private fun sampleLine(kind: String, name: String, variant: String?, sku: String?, quantity: Int, unit: Long, discount: Long, vatPercent: Long): JsonObject {
        val gross = unit * quantity - discount
        val vat = gross * vatPercent / (10000 + vatPercent)

        return JsonObject().put("kind", kind).put("name", name).put("variantName", variant).put("sku", sku).put("quantity", quantity).put("unitPrice", unit)
            .put("discount", discount).put("vatPercent", vatPercent).put("net", gross - vat).put("vat", vat).put("gross", gross)
    }
}

/**
 * The invoice side of a mail (12 section 4.4), for `MailComposition.attachments`: `ORDER_CONFIRMATION` carries the `INVOICE` of its order,
 * `ORDER_REFUNDED` the `CREDIT_NOTE` of its refund, both only when `mailAttachInvoice` is on, a row exists and the PDF is at most
 * [MAX_BYTES]. A render failure does not fail the mail: it answers [MailAttachments.invoiceRenderFailed] and the mail goes out without it.
 */
class InvoiceMailAttachments(
    private val invoices: MarketInvoiceDao,
    private val documents: InvoiceDocuments,
    private val attachInvoice: () -> Boolean
) {
    suspend fun attachmentsFor(row: MarketMailOutbox, sqlClient: SqlClient): MailAttachments {
        if (!attachInvoice()) return MailAttachments.NONE

        val orderId = row.orderId ?: row.refId.takeIf { row.refType == MailRefType.ORDER } ?: return MailAttachments.NONE
        val invoice = when (row.kind) {
            MailKind.ORDER_CONFIRMATION -> invoices.getByOrderTypeRefund(orderId, InvoiceType.INVOICE, 0, sqlClient)
            MailKind.ORDER_REFUNDED ->
                if (row.refType == MailRefType.REFUND) invoices.getByOrderTypeRefund(orderId, InvoiceType.CREDIT_NOTE, row.refId, sqlClient) else null
            else -> null
        } ?: return MailAttachments.NONE

        return try {
            val bytes = documents.ensureFile(invoice).readBytes()

            if (bytes.size > MAX_BYTES) MailAttachments.NONE else MailAttachments(listOf(MailAttachment("${invoice.number}.pdf", "application/pdf", bytes)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoggerFactory.getLogger(InvoiceMailAttachments::class.java).warn("Invoice {} could not be attached: {}", invoice.number, e.message)

            MailAttachments(emptyList(), invoiceRenderFailed = true)
        }
    }

    companion object {
        const val MAX_BYTES = 5 * 1024 * 1024
    }
}
