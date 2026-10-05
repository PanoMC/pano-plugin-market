package com.panomc.plugins.market.core.invoice

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.money.Currencies
import com.panomc.plugins.market.core.money.Rounding
import com.panomc.plugins.market.db.model.InvoiceType
import com.panomc.plugins.market.db.model.MarketInvoice
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketRefund
import com.panomc.plugins.market.db.model.MarketRefundItem
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.OrderSource
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * What the snapshot freezes about the seller (12 section 7): the six values at the time of issue. [timeZone] is the zone id of
 * `storeTimeZone` ([InvoiceNumbering.zone]); dates of an old document never move when the setting changes.
 */
class InvoiceSnapshotConfig(
    val sellerName: String,
    val sellerAddress: String,
    val sellerTaxOffice: String,
    val sellerTaxNumber: String,
    val footer: String,
    val websiteName: String,
    val websiteUrl: String,
    val timeZone: String
) {
    companion object {
        fun of(config: MarketConfig, websiteName: String, websiteUrl: String): InvoiceSnapshotConfig = InvoiceSnapshotConfig(
            sellerName = config.invoiceSellerName,
            sellerAddress = config.invoiceSellerAddress,
            sellerTaxOffice = config.invoiceSellerTaxOffice,
            sellerTaxNumber = config.invoiceSellerTaxNumber,
            footer = config.invoiceFooter,
            websiteName = websiteName,
            websiteUrl = websiteUrl,
            timeZone = InvoiceNumbering.zone(config.storeTimeZone).id
        )
    }
}

/**
 * Why the builder refused (12 section 7.3: `TOTAL_MISMATCH`, `NEGATIVE_AMOUNT`, `UNKNOWN_CURRENCY`). The other codes are the data
 * problems of a credit note that the section does not list: a VAT rate outside 0..100 %, a refund row of a line the order does
 * not have, an invoice whose snapshot cannot be read.
 */
enum class InvoiceBuildFailure { TOTAL_MISMATCH, NEGATIVE_AMOUNT, UNKNOWN_CURRENCY, INVALID_VAT_RATE, UNKNOWN_ITEM, INVOICE_UNREADABLE }

/** The answer of [InvoiceSnapshotBuilder.build]: a snapshot, or the reason there is none (an error instead of a wrong document). */
sealed class InvoiceBuild {
    /** [total] and [vatTotal] are the `market_invoice.total` / `vatTotal` columns. */
    class Built(val snapshot: JsonObject, val total: Long, val vatTotal: Long) : InvoiceBuild()

    class Failed(val reason: InvoiceBuildFailure, val detail: String) : InvoiceBuild()
}

/**
 * `InvoiceSnapshotBuilder.build(order, items, refund?, refundItems?, config, sellerLogoHash, issuedAt, number, locale)` of 12
 * section 7, a pure function from the order books to the `market_invoice.snapshot` JSON (version 1). Money is `Long` x 100 in the
 * order currency; every text is stored as data, labels are translated when the PDF is rendered.
 *
 * - **Invoice** (`refund == null`): one line per order item in id order (a `BUNDLE_CHILD` has amounts 0), a shipping line when
 *   `shippingTotal > 0`, a payment fee line when `paymentFee > 0`. The VAT of the last two comes from the C-3 columns; an
 *   order of `source = LEGACY` whose columns are 0 gets the residual of `vatTotal` instead (shipping first, capped).
 * - **Credit note** (`refund != null`): one line per refund item, then the part of `refund.amount` the rows do not cover as
 *   `REFUND` lines, one per VAT rate of [invoice], split by the largest remainder method (ties to the lower rate).
 *
 * The consistency checks of section 7.3 run last: no snapshot leaves the builder that does not add up to the order's
 * `totalPrice` (invoice) or the refund's `amount` (credit note).
 *
 * Defaults taken where 12 leaves a detail open (recorded in `evidence/MK-143.md`): the shipping line is named after
 * `shippingMethodName` and the fee line has an empty name (the renderer labels a line by its `kind`); the seller block shows
 * `websiteName` when no seller name is configured (12 section 10); `identityNumber` shows its last 4 characters only (an identity
 * number of 4 characters or fewer is masked completely); `sellerLogoHash` is stored as `seller.logoHash` when there is one.
 */
object InvoiceSnapshotBuilder {
    const val VERSION = 1

    private val BP_ONE = BigDecimal(10_000)

    private class Fail(val reason: InvoiceBuildFailure, val detail: String) : RuntimeException(detail, null, false, false)

    private class Line(
        val kind: String,
        val name: String,
        val variantName: String?,
        val sku: String?,
        val quantity: Int,
        val unitPrice: Long,
        val discount: Long,
        val vatPercent: Long,
        val net: Long,
        val vat: Long,
        val gross: Long
    )

    private class Rate(val vatPercent: Long, var net: Long = 0, var vat: Long = 0, var gross: Long = 0)

    /**
     * @param invoice the invoice a credit note refers to (its number, issue time and VAT rates); ignored for an invoice
     * @param number the document number; the service builds once with `""` to validate before it takes a number from the counter
     */
    fun build(
        order: MarketOrder,
        items: List<MarketOrderItem>,
        refund: MarketRefund? = null,
        refundItems: List<MarketRefundItem> = emptyList(),
        invoice: MarketInvoice? = null,
        config: InvoiceSnapshotConfig,
        sellerLogoHash: String? = null,
        issuedAt: Long,
        number: String,
        locale: String
    ): InvoiceBuild = try {
        if (!Currencies.isSupported(order.currency)) throw Fail(InvoiceBuildFailure.UNKNOWN_CURRENCY, order.currency)

        val creditNote = refund != null

        if (creditNote && invoice == null) throw Fail(InvoiceBuildFailure.INVOICE_UNREADABLE, "a credit note needs the invoice it refers to")

        val lines = if (refund != null) creditNoteLines(order, items, refund, refundItems, invoice) else invoiceLines(order, items)

        requireNonNegative(lines)

        val total = lines.fold(0L) { sum, line -> Math.addExact(sum, line.gross) }
        val expected = refund?.amount ?: order.totalPrice

        if (total != expected) throw Fail(InvoiceBuildFailure.TOTAL_MISMATCH, "lines add up to $total, the ${if (creditNote) "refund" else "order"} is $expected")

        val rates = vatRows(lines)
        val vatTotal = rates.fold(0L) { sum, rate -> Math.addExact(sum, rate.vat) }

        if (rates.fold(0L) { sum, rate -> Math.addExact(sum, rate.gross) } != total) {
            throw Fail(InvoiceBuildFailure.TOTAL_MISMATCH, "VAT rows do not add up to the total $total")
        }

        val snapshot = JsonObject()
            .put("v", VERSION)
            .put("type", (if (creditNote) InvoiceType.CREDIT_NOTE else InvoiceType.INVOICE).name)
            .put("number", number)
            .put("issuedAt", issuedAt)
            .put("locale", locale)
            .put("currency", order.currency)
            .put("timeZone", config.timeZone)
            .put("testMode", order.testMode)
            .put("seller", seller(config, sellerLogoHash))
            .put("buyer", buyer(order))
            .put("order", orderBlock(order))
            .put("pricesIncludeVat", order.pricesIncludeVat)
            .put("lines", JsonArray(lines.map { lineJson(it) }))
            .put("vatRows", JsonArray(rates.map { rateJson(it) }))
            .put("totals", totals(order, refund, total, vatTotal))
            .put("footer", config.footer)
            .putNullable("ref", if (refund != null) ref(refund, invoice) else null)

        InvoiceBuild.Built(snapshot, total, vatTotal)
    } catch (e: Fail) {
        InvoiceBuild.Failed(e.reason, e.detail)
    } catch (e: ArithmeticException) {
        InvoiceBuild.Failed(InvoiceBuildFailure.TOTAL_MISMATCH, "amount overflow")
    }

    // ----- invoice lines (12 section 7.1) --------------------------------------------------------------------------

    private fun invoiceLines(order: MarketOrder, items: List<MarketOrderItem>): List<Line> {
        val lines = ArrayList<Line>()

        for (item in items.sortedBy { it.id }) lines += itemLine(item)

        // the VAT of the shipping and the fee line: the C-3 columns, or for a LEGACY order whose columns are 0 the residual of vatTotal
        var shippingVat = order.shippingVatAmount
        var shippingPercent = order.shippingVatPercent
        var feeVat = order.paymentFeeVatAmount
        var feePercent = order.paymentFeeVatPercent

        if (order.source == OrderSource.LEGACY && shippingVat == 0L && feeVat == 0L) {
            val residual = maxOf(0L, order.vatTotal - items.sumOf { it.vatAmount })

            shippingVat = minOf(residual, order.shippingTotal)
            feeVat = residual - shippingVat
            shippingPercent = percentOf(shippingVat, order.shippingTotal)
            feePercent = percentOf(feeVat, order.paymentFee)
        }

        if (order.shippingTotal > 0) {
            lines += Line(
                "SHIPPING", order.shippingMethodName.orEmpty(), null, null, 1, order.shippingTotal, 0, shippingPercent,
                order.shippingTotal - shippingVat, shippingVat, order.shippingTotal
            )
        }

        if (order.paymentFee > 0) {
            lines += Line("PAYMENT_FEE", "", null, null, 1, order.paymentFee, 0, feePercent, order.paymentFee - feeVat, feeVat, order.paymentFee)
        }

        return lines
    }

    private fun itemLine(item: MarketOrderItem): Line {
        if (item.kind == OrderItemKind.BUNDLE_CHILD) {
            // a child is only a description line under its bundle: the bundle line carries the money
            return Line(item.kind.name, item.productName, item.variantName, item.sku, item.quantity, 0, 0, 0, 0, 0, 0)
        }

        return Line(
            kind = item.kind.name,
            name = item.productName,
            variantName = item.variantName,
            sku = item.sku,
            quantity = item.quantity,
            unitPrice = item.listUnitPrice,
            discount = item.discountAmount + item.upgradeAmount + item.couponAmount,
            vatPercent = item.vatPercent,
            net = item.lineTotal - item.vatAmount,
            vat = item.vatAmount,
            gross = item.lineTotal
        )
    }

    /** `round(vat x 10000 / (gross - vat))` half up, 0 when there is nothing to divide by. */
    private fun percentOf(vat: Long, gross: Long): Long {
        val net = gross - vat

        if (net <= 0) return 0

        return BigDecimal.valueOf(vat).multiply(BP_ONE).divide(BigDecimal.valueOf(net), 0, RoundingMode.HALF_UP).longValueExact()
    }

    // ----- credit note lines (12 section 7.2) ----------------------------------------------------------------------

    private fun creditNoteLines(
        order: MarketOrder,
        items: List<MarketOrderItem>,
        refund: MarketRefund,
        refundItems: List<MarketRefundItem>,
        invoice: MarketInvoice?
    ): List<Line> {
        val quantum = Rounding.quantum(order.currency, false)
        val byId = items.associateBy { it.id }
        val lines = ArrayList<Line>()
        var covered = 0L

        for (row in refundItems.sortedBy { it.id }) {
            val item = byId[row.orderItemId] ?: throw Fail(InvoiceBuildFailure.UNKNOWN_ITEM, "refund item ${row.id} names order item ${row.orderItemId}")

            if (row.amount < 0) throw Fail(InvoiceBuildFailure.NEGATIVE_AMOUNT, "refund item ${row.id}: ${row.amount}")

            val vat = vatInside(row.amount, item.vatPercent, quantum)
            val unitPrice = if (row.quantity > 0) BigDecimal.valueOf(row.amount).divide(BigDecimal.valueOf(row.quantity.toLong()), 0, RoundingMode.HALF_UP).longValueExact() else row.amount

            lines += Line(item.kind.name, item.productName, item.variantName, item.sku, row.quantity, unitPrice, 0, item.vatPercent, row.amount - vat, vat, row.amount)
            covered = Math.addExact(covered, row.amount)
        }

        val rest = refund.amount - covered

        if (rest < 0) throw Fail(InvoiceBuildFailure.TOTAL_MISMATCH, "refund items add up to $covered, the refund is ${refund.amount}")

        if (rest > 0) {
            val rates = invoiceRates(invoice)
            val shares = try {
                Rounding.allocate(rest, rates.map { it.second }, quantum)
            } catch (e: IllegalArgumentException) {
                throw Fail(InvoiceBuildFailure.TOTAL_MISMATCH, "the amount-only part $rest cannot be split over the invoice: ${e.message}")
            }

            for ((index, share) in shares.withIndex()) {
                if (share == 0L) continue

                val percent = rates[index].first
                val vat = vatInside(share, percent, quantum)

                lines += Line("REFUND", "", null, null, 1, share, 0, percent, share - vat, vat, share)
            }
        }

        return lines
    }

    /** The `(vatPercent, gross)` rows of the invoice's snapshot, ascending by rate. */
    private fun invoiceRates(invoice: MarketInvoice?): List<Pair<Long, Long>> {
        if (invoice == null) throw Fail(InvoiceBuildFailure.INVOICE_UNREADABLE, "a credit note needs the invoice it refers to")

        val rows = try {
            JsonObject(invoice.snapshot).getJsonArray("vatRows")
        } catch (e: Exception) {
            throw Fail(InvoiceBuildFailure.INVOICE_UNREADABLE, "the snapshot of ${invoice.number} cannot be read")
        }

        val rates = try {
            rows.map { row ->
                val rate = row as JsonObject

                checkNotNull(rate.getLong("vatPercent")) to checkNotNull(rate.getLong("gross"))
            }
        } catch (e: Exception) {
            throw Fail(InvoiceBuildFailure.INVOICE_UNREADABLE, "the VAT rows of ${invoice.number} cannot be read")
        }

        if (rates.isEmpty()) throw Fail(InvoiceBuildFailure.INVOICE_UNREADABLE, "the VAT rows of ${invoice.number} are empty")

        return rates.sortedBy { it.first }
    }

    private fun vatInside(gross: Long, percent: Long, quantum: Long): Long = try {
        Rounding.vatInside(gross, percent, quantum)
    } catch (e: IllegalArgumentException) {
        throw Fail(InvoiceBuildFailure.INVALID_VAT_RATE, "VAT $percent bp on $gross: ${e.message}")
    }

    // ----- checks, VAT rows, totals --------------------------------------------------------------------------------

    private fun requireNonNegative(lines: List<Line>) {
        for (line in lines) {
            if (line.gross < 0 || line.net < 0 || line.vat < 0) {
                throw Fail(InvoiceBuildFailure.NEGATIVE_AMOUNT, "${line.kind} '${line.name}': net ${line.net}, VAT ${line.vat}, gross ${line.gross}")
            }
        }
    }

    /** Lines grouped by VAT rate, ascending; a rate with no gross amount is dropped. */
    private fun vatRows(lines: List<Line>): List<Rate> {
        val rows = sortedMapOf<Long, Rate>()

        for (line in lines) {
            val row = rows.getOrPut(line.vatPercent) { Rate(line.vatPercent) }

            row.net = Math.addExact(row.net, line.net)
            row.vat = Math.addExact(row.vat, line.vat)
            row.gross = Math.addExact(row.gross, line.gross)
        }

        return rows.values.filter { it.gross != 0L }
    }

    private fun totals(order: MarketOrder, refund: MarketRefund?, total: Long, vatTotal: Long): JsonObject {
        val totals = JsonObject()

        if (refund == null) {
            totals
                .put("subtotal", order.subtotal)
                .put("discount", order.discountTotal + order.couponDiscount + order.creatorDiscount + order.upgradeDiscount)
                .put("shipping", order.shippingTotal)
                .put("paymentFee", order.paymentFee)
        } else {
            totals.put("subtotal", total).put("discount", 0L).put("shipping", 0L).put("paymentFee", 0L)
        }

        return totals
            .put("net", total - vatTotal)
            .put("vat", vatTotal)
            .put("total", total)
            .put("creditValue", refund?.creditValue ?: order.creditValue)
            .put("creditAmount", refund?.creditAmount ?: order.creditAmount)
            .put("gatewayAmount", refund?.gatewayAmount ?: order.gatewayAmount)
    }

    // ----- parties ---------------------------------------------------------------------------------------------------

    private fun seller(config: InvoiceSnapshotConfig, logoHash: String?): JsonObject {
        val seller = JsonObject()
            // 12 section 10: without a configured seller name the seller block shows the website name
            .put("name", config.sellerName.ifBlank { config.websiteName })
            .put("address", config.sellerAddress)
            .put("taxOffice", config.sellerTaxOffice)
            .put("taxNumber", config.sellerTaxNumber)
            .put("websiteName", config.websiteName)
            .put("websiteUrl", config.websiteUrl)

        if (!logoHash.isNullOrBlank()) seller.put("logoHash", logoHash)

        return seller
    }

    private fun buyer(order: MarketOrder): JsonObject {
        val billing = order.billingInfo?.takeIf { it.isNotBlank() }?.let { raw -> runCatching { JsonObject(raw) }.getOrNull() }

        fun text(key: String): String = billing?.getValue(key)?.let { it as? String }?.trim().orEmpty()

        val buyer = JsonObject().put("username", order.playerUsername)

        if (billing == null) {
            return buyer
                .putNull("type")
                .put("name", "")
                .put("company", "")
                .put("taxOffice", "")
                .put("taxNumber", "")
                .put("identityNumber", "")
                .put("addressLines", JsonArray())
                .put("country", "")
                .put("email", order.email.orEmpty())
                .put("phone", "")
        }

        val type = text("type").takeIf { it == "INDIVIDUAL" || it == "COMPANY" }
        val addressLines = listOf(
            text("line1"),
            text("line2"),
            listOf(text("neighborhood"), text("district")).filter { it.isNotEmpty() }.joinToString(" "),
            listOf(text("postalCode"), text("city"), text("state")).filter { it.isNotEmpty() }.joinToString(" ")
        ).filter { it.isNotEmpty() }

        return buyer
            .putNullable("type", type)
            .put("name", listOf(text("firstName"), text("lastName")).filter { it.isNotEmpty() }.joinToString(" "))
            .put("company", text("company"))
            .put("taxOffice", text("taxOffice"))
            .put("taxNumber", text("taxNumber"))
            .put("identityNumber", maskIdentity(text("identityNumber")))
            .put("addressLines", JsonArray(addressLines))
            .put("country", text("country"))
            .put("email", text("email").ifEmpty { order.email.orEmpty() })
            .put("phone", text("phone"))
    }

    /** An identity number is kept as `*******1234`: only its last 4 characters stay readable. */
    fun maskIdentity(value: String): String {
        if (value.length <= 4) return "*".repeat(value.length)

        return "*".repeat(value.length - 4) + value.takeLast(4)
    }

    private fun orderBlock(order: MarketOrder): JsonObject = JsonObject()
        .put("id", order.id)
        .putNullable("publicId", order.publicId)
        .put("createdAt", order.createdAt)
        .put("paidAt", order.paidAt ?: 0L)
        .put("paymentLabel", order.paymentLabel)
        .put("isGift", order.isGift)
        .put("recipientUsername", order.recipientUsername)

    private fun ref(refund: MarketRefund, invoice: MarketInvoice?): JsonObject? {
        if (invoice == null) return null

        return JsonObject()
            .put("number", invoice.number)
            .put("issuedAt", invoice.issuedAt)
            .put("refundId", refund.id)
            .putNullable("reason", refund.reason)
    }

    // ----- JSON ------------------------------------------------------------------------------------------------------

    private fun lineJson(line: Line): JsonObject = JsonObject()
        .put("kind", line.kind)
        .put("name", line.name)
        .putNullable("variantName", line.variantName)
        .putNullable("sku", line.sku)
        .put("quantity", line.quantity)
        .put("unitPrice", line.unitPrice)
        .put("discount", line.discount)
        .put("vatPercent", line.vatPercent)
        .put("net", line.net)
        .put("vat", line.vat)
        .put("gross", line.gross)

    private fun rateJson(rate: Rate): JsonObject = JsonObject()
        .put("vatPercent", rate.vatPercent)
        .put("net", rate.net)
        .put("vat", rate.vat)
        .put("gross", rate.gross)

    private fun JsonObject.putNullable(key: String, value: Any?): JsonObject = if (value == null) putNull(key) else put(key, value)
}
