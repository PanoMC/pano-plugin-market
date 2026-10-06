package com.panomc.plugins.market.mail

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.db.dao.MarketInvoiceDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketRefundDao
import com.panomc.plugins.market.db.dao.MarketRefundItemDao
import com.panomc.plugins.market.db.model.InvoiceType
import com.panomc.plugins.market.db.model.MailKind
import com.panomc.plugins.market.db.model.MailRefType
import com.panomc.plugins.market.db.model.MarketMailOutbox
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketRefund
import com.panomc.plugins.market.db.model.MarketRefundItem
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.PricingMode
import com.panomc.plugins.market.i18n.MarketFormat
import com.panomc.plugins.market.i18n.MarketI18n
import com.panomc.plugins.market.pdf.InvoiceMailAttachments
import com.panomc.plugins.market.util.HtmlSanitizer
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import java.net.URI

/** The facts of the site a mail names (12 section 4.4): `websiteName`, `websiteUrl` (no trailing slash; empty = no buttons). */
class MailSite(val websiteName: String, val websiteUrl: String)

/**
 * Everything the content of one mail reads (12 section 5), already loaded: the pure input of [MailContentBuilder].
 * [invoiceAttached] says whether the mail will carry the invoice / credit note PDF (the `invoice-attached` footer note).
 */
class MailInput(
    val kind: MailKind,
    val locale: String,
    val order: MarketOrder,
    val items: List<MarketOrderItem>,
    val params: JsonObject = JsonObject(),
    val refund: MarketRefund? = null,
    val refundItems: List<MarketRefundItem> = emptyList(),
    val invoiceAttached: Boolean = false
)

/**
 * Builds the [MailContent] block model of the order mails (12 section 5) from loaded rows, with no database and no host class: the
 * kinds `ORDER_RECEIVED`, `BANK_TRANSFER_INSTRUCTIONS`, `ORDER_CONFIRMATION`, `GIFT_RECEIVED`, `ORDER_DELIVERED` and `ORDER_REFUNDED`.
 * Locale keys are `mail.<kind-key>.*` and `mail.common.*`; every string is final (translated, formatted, cut to 255 characters).
 *
 * The subscription, expiry and shipment kinds are MK-146: [supports] is false for them and [build] refuses them.
 */
class MailContentBuilder(
    private val i18n: MarketI18n,
    private val format: MarketFormat,
    private val config: () -> MarketConfig,
    private val site: () -> MailSite,
    private val sanitizeHtml: (String) -> String = { HtmlSanitizer.sanitize(it) }
) {
    fun supports(kind: MailKind): Boolean = kind in KINDS

    suspend fun build(input: MailInput): MailContent {
        require(supports(input.kind)) { "the mail kind ${input.kind} has no composer yet" }

        val vars = vars(input)

        return when (input.kind) {
            MailKind.ORDER_RECEIVED -> received(input, vars)
            MailKind.BANK_TRANSFER_INSTRUCTIONS -> bankTransfer(input, vars)
            MailKind.ORDER_CONFIRMATION -> confirmation(input, vars)
            MailKind.GIFT_RECEIVED -> gift(input, vars)
            MailKind.ORDER_DELIVERED -> delivered(input, vars)
            MailKind.ORDER_REFUNDED -> refunded(input, vars)
            else -> throw IllegalStateException("unreachable: ${input.kind}")
        }
    }

    // ----- the kinds ---------------------------------------------------------------------------------------------------

    private suspend fun received(i: MailInput, vars: Map<String, Any?>): MailContent = shell(
        i, vars, "order-received",
        paragraphs = listOf(t(i, "mail.order-received.body", vars), t(i, "mail.order-received.body-2", vars)),
        items = items(i, withPrices = true), totals = totals(i), details = orderDetails(i, paid = false),
        button = viewOrder(i, "mail.order-received.button")
    )

    private suspend fun bankTransfer(i: MailInput, vars: Map<String, Any?>): MailContent {
        val expiresAt = i.params.getValue("expiresAt")?.let { (it as? Number)?.toLong() }
        val withExpiry = if (expiresAt != null) vars + ("expiresAt" to format.dateTime(expiresAt, i.locale)) else vars
        val body = i.params.getJsonObject("instructions")
        val html = body?.getString("body")?.takeIf { it.isNotBlank() }?.let { sanitizeHtml(it) }?.takeIf { it.isNotBlank() }
        val fields = (body?.getJsonArray("fields") ?: JsonArray()).filterIsInstance<JsonObject>().mapNotNull { f ->
            val label = f.getString("label")?.trim().orEmpty()
            val value = f.getString("value")?.trim().orEmpty()

            if (label.isEmpty() || value.isEmpty()) null else MailContent.Row(cut(label), cut(value))
        }
        val details = ArrayList<MailContent.Row>()

        details += MailContent.Row(t(i, "mail.common.order-number", vars), vars.getValue("orderNumber").toString())
        details += MailContent.Row(t(i, "mail.common.total", vars), totalLabel(i))

        if (expiresAt != null) details += MailContent.Row(t(i, "mail.common.pay-before", vars), format.dateTime(expiresAt, i.locale))

        val paragraphs = ArrayList<String>()

        paragraphs += t(i, "mail.bank-transfer-instructions.body", withExpiry)

        if (expiresAt != null) paragraphs += t(i, "mail.bank-transfer-instructions.body-2", withExpiry)

        return shell(
            i, withExpiry, "bank-transfer-instructions", paragraphs = paragraphs, items = items(i, withPrices = true), totals = totals(i), details = details,
            instructionsHtml = html, instructionFields = fields, button = viewOrder(i, "mail.bank-transfer-instructions.button")
        )
    }

    private suspend fun confirmation(i: MailInput, vars: Map<String, Any?>): MailContent {
        val paragraphs = arrayListOf(t(i, "mail.order-confirmation.body", vars), t(i, "mail.order-confirmation.body-2", vars))
        val address = shippingAddress(i.order)

        if (i.order.requiresShipping && address.isNotEmpty()) {
            paragraphs += t(i, "mail.order-confirmation.shipping-address", vars + ("address" to cut(address)))
        }

        return shell(
            i, vars, "order-confirmation", paragraphs = paragraphs, items = items(i, withPrices = true), totals = totals(i),
            details = orderDetails(i, paid = true), button = viewOrder(i, "mail.order-confirmation.button"),
            footerExtra = if (i.invoiceAttached) t(i, "mail.common.invoice-attached", vars) else null
        )
    }

    private suspend fun gift(i: MailInput, vars: Map<String, Any?>): MailContent {
        val message = i.order.giftMessage?.trim()?.takeIf { it.isNotEmpty() }?.let { cut(it) }
        val base = site().websiteUrl

        return shell(
            i, vars, "gift-received", paragraphs = listOf(t(i, "mail.gift-received.body", vars), t(i, "mail.gift-received.body-2", vars)),
            quote = message, items = items(i, withPrices = false), button = if (base.isEmpty()) null else t(i, "mail.gift-received.button", vars) to "$base/store"
        )
    }

    private suspend fun delivered(i: MailInput, vars: Map<String, Any?>): MailContent = shell(
        i, vars, "order-delivered", paragraphs = listOf(t(i, "mail.order-delivered.body", vars)), items = items(i, withPrices = false),
        button = viewOrder(i, "mail.order-delivered.button")
    )

    private suspend fun refunded(i: MailInput, vars: Map<String, Any?>): MailContent {
        val refund = checkNotNull(i.refund) { "ORDER_REFUNDED needs its refund row" }
        val currency = refund.currency.ifBlank { i.order.currency }
        val amount = format.money(refund.amount, currency, i.locale)
        val withAmount = vars + ("amount" to amount)
        val details = ArrayList<MailContent.Row>()

        details += MailContent.Row(t(i, "mail.common.order-number", withAmount), vars.getValue("orderNumber").toString())
        details += MailContent.Row(t(i, "mail.common.refund-amount", withAmount), amount, strong = true)

        if (refund.gatewayAmount > 0) details += MailContent.Row(t(i, "mail.common.refund-to-method", withAmount), format.money(refund.gatewayAmount, currency, i.locale))
        if (refund.creditAmount > 0) details += MailContent.Row(t(i, "mail.common.refund-to-credits", withAmount), format.credits(refund.creditAmount, i.locale))

        refund.reason?.trim()?.takeIf { it.isNotEmpty() }?.let { details += MailContent.Row(t(i, "mail.common.refund-reason", withAmount), cut(it)) }

        val byId = i.items.associateBy { it.id }
        val lines = i.refundItems.mapNotNull { r ->
            val item = byId[r.orderItemId] ?: return@mapNotNull null

            MailContent.Item(cut(item.productName), item.variantName?.takeIf { it.isNotBlank() }?.let { cut(it) }, r.quantity.toString(), format.money(r.amount, currency, i.locale), false)
        }
        val claim = refund.buyerActionUrl?.takeIf { isWebUrl(it) }
        val view = viewOrder(i, "mail.order-refunded.button")
        val paragraphs = listOf(t(i, "mail.order-refunded.body", withAmount), t(i, "mail.order-refunded.body-2", withAmount))

        return shell(
            i, withAmount, "order-refunded", paragraphs = paragraphs, items = lines, details = details,
            button = if (claim != null) t(i, "mail.order-refunded.button-claim", withAmount) to claim else view,
            secondary = if (claim != null) view else null,
            footerExtra = if (i.invoiceAttached) t(i, "mail.order-refunded.credit-note-attached", withAmount) else null
        )
    }

    // ----- shared pieces -----------------------------------------------------------------------------------------------

    /** The skeleton every kind shares: subject (with the test prefix), preheader, heading, greeting, banner, footer. */
    private suspend fun shell(
        i: MailInput,
        vars: Map<String, Any?>,
        key: String,
        paragraphs: List<String>,
        quote: String? = null,
        items: List<MailContent.Item> = emptyList(),
        totals: List<MailContent.Row> = emptyList(),
        details: List<MailContent.Row> = emptyList(),
        instructionsHtml: String? = null,
        instructionFields: List<MailContent.Row> = emptyList(),
        button: Pair<String, String>? = null,
        secondary: Pair<String, String>? = null,
        footerExtra: String? = null
    ): MailContent {
        val test = i.order.testMode
        val subject = t(i, "mail.$key.subject", vars).let { if (test) "[TEST] $it" else it }
        val greeting = if (i.kind == MailKind.GIFT_RECEIVED) emptyList() else listOf(t(i, "mail.common.hello", vars))
        val footer = listOfNotNull(t(i, "mail.common.footer", vars), footerExtra).joinToString(" ")

        return MailContent(
            subject = subject,
            preheader = t(i, "mail.$key.preheader", vars),
            heading = t(i, "mail.$key.heading", vars),
            paragraphs = greeting + paragraphs,
            testMode = test,
            quote = quote,
            items = items,
            totals = totals,
            details = details,
            instructionsHtml = instructionsHtml,
            instructionFields = instructionFields,
            buttonLabel = button?.first,
            buttonUrl = button?.second,
            secondaryLabel = secondary?.first,
            secondaryUrl = secondary?.second,
            footerNote = footer,
            testModeLabel = t(i, "mail.common.test-banner", vars)
        )
    }

    private suspend fun t(i: MailInput, key: String, vars: Map<String, Any?>): String = i18n.t(i.locale, key, vars)

    /** 12 section 5: `{websiteName}`, `{storeName}`, `{orderNumber}` (= `#<order id>`), `{username}` (the payer). */
    private fun vars(i: MailInput): Map<String, Any?> = mapOf(
        "websiteName" to cut(site().websiteName),
        "storeName" to cut(config().storeName.ifBlank { site().websiteName }),
        "orderNumber" to "#${i.order.id}",
        "username" to cut(i.order.playerUsername)
    )

    /** `{n}` is plain: the template and the text alternative both add the multiplication sign. Bundle children carry no price. */
    private suspend fun items(i: MailInput, withPrices: Boolean): List<MailContent.Item> =
        i.items.sortedBy { it.id }.filter { it.kind != OrderItemKind.BUNDLE || true }.map { item ->
            val child = item.kind == OrderItemKind.BUNDLE_CHILD || item.parentItemId != null

            MailContent.Item(
                name = cut(item.productName),
                variant = item.variantName?.takeIf { it.isNotBlank() }?.let { cut(it) },
                quantity = item.quantity.toString(),
                total = if (!withPrices || child) "" else format.money(item.lineTotal, i.order.currency, i.locale),
                child = child
            )
        }

    /** 12 section 5: each row only when non-zero, in order; `pricingMode <> MARKET` shows the total only; a credits-only order shows credits. */
    private suspend fun totals(i: MailInput): List<MailContent.Row> {
        val o = i.order
        val rows = ArrayList<MailContent.Row>()
        val vars = vars(i)

        suspend fun row(label: String, amount: Long, negative: Boolean = false, key: String = label) {
            if (amount == 0L) return

            rows += MailContent.Row(t(i, "mail.common.$key", vars), format.money(if (negative) -amount else amount, o.currency, i.locale))
        }

        if (o.pricingMode == PricingMode.MARKET) {
            row("subtotal", o.subtotal)
            row("discount", o.discountTotal + o.couponDiscount + o.creatorDiscount + o.upgradeDiscount, negative = true)
            row("shipping", o.shippingTotal)
            row("payment-fee", o.paymentFee)

            if (o.vatTotal != 0L) {
                rows += MailContent.Row(
                    t(i, if (o.pricesIncludeVat) "mail.common.vat-included" else "mail.common.vat", vars), format.money(o.vatTotal, o.currency, i.locale)
                )
            }
        }

        rows += MailContent.Row(t(i, "mail.common.total", vars), totalLabel(i), strong = true)

        if (o.pricingMode == PricingMode.MARKET && o.creditAmount > 0 && o.gatewayAmount > 0) {
            rows += MailContent.Row(t(i, "mail.common.paid-with-credits", vars), format.credits(o.creditAmount, i.locale))
        }

        return rows
    }

    /** The grand total: money, or credits when the order was paid with credits alone. */
    private suspend fun totalLabel(i: MailInput): String =
        if (i.order.creditAmount > 0 && i.order.gatewayAmount == 0L) format.credits(i.order.creditAmount, i.locale) else format.money(i.order.totalPrice, i.order.currency, i.locale)

    private suspend fun orderDetails(i: MailInput, paid: Boolean): List<MailContent.Row> {
        val o = i.order
        val vars = vars(i)
        val rows = ArrayList<MailContent.Row>()

        rows += MailContent.Row(t(i, "mail.common.order-number", vars), vars.getValue("orderNumber").toString())
        rows += MailContent.Row(t(i, "mail.common.order-date", vars), format.dateTime(if (paid) o.paidAt ?: o.createdAt else o.createdAt, i.locale))

        if (o.paymentLabel.isNotBlank()) rows += MailContent.Row(t(i, "mail.common.payment-method", vars), cut(o.paymentLabel))
        if (paid && o.isGift && o.recipientUsername.isNotBlank()) rows += MailContent.Row(t(i, "mail.common.recipient", vars), cut(o.recipientUsername))

        return rows
    }

    /** The button of every order mail (12 section 4.4): the order page, with the access token for a guest order; none without a site URL. */
    private suspend fun viewOrder(i: MailInput, key: String): Pair<String, String>? {
        val url = orderUrl(i.order) ?: return null

        return t(i, key, vars(i)) to url
    }

    private fun orderUrl(order: MarketOrder): String? {
        val base = site().websiteUrl

        if (base.isEmpty()) return null

        val publicId = order.publicId?.takeIf { it.isNotBlank() } ?: return null

        return "$base/store/order/$publicId" + if (order.userId == null && !order.accessToken.isNullOrBlank()) "?token=${order.accessToken}" else ""
    }

    private fun shippingAddress(order: MarketOrder): String {
        val a = order.shippingAddress?.takeIf { it.isNotBlank() }?.let { runCatching { JsonObject(it) }.getOrNull() } ?: return ""
        val city = listOfNotNull(a.getString("postalCode"), a.getString("district"), a.getString("city")).filter { it.isNotBlank() }.joinToString(" ")

        return listOfNotNull(
            listOfNotNull(a.getString("firstName"), a.getString("lastName")).joinToString(" ").takeIf { it.isNotBlank() },
            a.getString("line1"), a.getString("line2"), city.takeIf { it.isNotBlank() }, a.getString("state"), a.getString("country")
        ).filter { it.isNotBlank() }.joinToString(", ")
    }

    private fun isWebUrl(url: String): Boolean = try {
        val uri = URI(url)

        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty()
    } catch (e: Exception) {
        false
    }

    private fun cut(text: String): String = if (text.length <= MAX_TEXT) text else text.take(MAX_TEXT)

    companion object {
        const val MAX_TEXT = 255

        val KINDS: Set<MailKind> = setOf(
            MailKind.ORDER_RECEIVED, MailKind.BANK_TRANSFER_INSTRUCTIONS, MailKind.ORDER_CONFIRMATION, MailKind.GIFT_RECEIVED,
            MailKind.ORDER_DELIVERED, MailKind.ORDER_REFUNDED
        )
    }
}

/**
 * The content side of the mail outbox for the order mails (12 sections 4.3.5, 4.3.6, 4.4, 5), the production [MailComposition]: reads the rows
 * the outbox row points at and hands them to [MailContentBuilder]. A kind without a composer yet ends `FAILED (RENDER_ERROR)` like the
 * unwired composition did (the subscription, expiry and shipment kinds are MK-146).
 *
 * Relevance (12 section 4.3.5): a missing referenced row is obsolete for every kind; `BANK_TRANSFER_INSTRUCTIONS` and `ORDER_RECEIVED` are obsolete
 * once the order left `PENDING` / `REVIEW`.
 */
class MailComposer(
    private val builder: MailContentBuilder,
    private val orders: MarketOrderDao,
    private val orderItems: MarketOrderItemDao,
    private val refunds: MarketRefundDao,
    private val refundItems: MarketRefundItemDao,
    private val invoices: MarketInvoiceDao? = null,
    private val attachments: InvoiceMailAttachments? = null,
    private val config: () -> MarketConfig = { MarketConfig() }
) : MailComposition {
    override suspend fun isObsolete(row: MarketMailOutbox, sqlClient: SqlClient): Boolean {
        if (!builder.supports(row.kind)) return false

        val order = orderOf(row, sqlClient) ?: return true

        if (row.kind == MailKind.ORDER_REFUNDED) return refundOf(row, order, sqlClient) == null

        return when (row.kind) {
            MailKind.BANK_TRANSFER_INSTRUCTIONS, MailKind.ORDER_RECEIVED -> order.status != OrderStatus.PENDING && order.status != OrderStatus.REVIEW
            else -> false
        }
    }

    override suspend fun compose(row: MarketMailOutbox, sqlClient: SqlClient): MailContent {
        val order = checkNotNull(orderOf(row, sqlClient)) { "order of mail ${row.id} is gone" }
        val items = orderItems.getByOrderIds(listOf(order.id), sqlClient)
        val refund = if (row.kind == MailKind.ORDER_REFUNDED) refundOf(row, order, sqlClient) else null

        check(row.kind != MailKind.ORDER_REFUNDED || refund != null) { "refund ${row.refId} of mail ${row.id} is gone" }

        val refundLines = refund?.let { refundItems.getByRefundId(it.id, sqlClient) }.orEmpty()
        val params = runCatching { JsonObject(row.params.ifBlank { "{}" }) }.getOrDefault(JsonObject())

        return builder.build(MailInput(row.kind, row.locale, order, items, params, refund, refundLines, invoiceWillBeAttached(row, order, refund, sqlClient)))
    }

    override suspend fun attachments(row: MarketMailOutbox, sqlClient: SqlClient): MailAttachments = attachments?.attachmentsFor(row, sqlClient) ?: MailAttachments.NONE

    /** `mailAttachInvoice` and a document row for the reference: the footer note may promise the PDF (12 section 4.4). */
    private suspend fun invoiceWillBeAttached(row: MarketMailOutbox, order: MarketOrder, refund: MarketRefund?, sqlClient: SqlClient): Boolean {
        if (attachments == null || invoices == null || !config().mailAttachInvoice) return false

        return when (row.kind) {
            MailKind.ORDER_CONFIRMATION -> invoices.getByOrderTypeRefund(order.id, InvoiceType.INVOICE, 0, sqlClient) != null
            MailKind.ORDER_REFUNDED -> refund != null && invoices.getByOrderTypeRefund(order.id, InvoiceType.CREDIT_NOTE, refund.id, sqlClient) != null
            else -> false
        }
    }

    private suspend fun orderOf(row: MarketMailOutbox, sqlClient: SqlClient): MarketOrder? {
        val id = row.orderId ?: row.refId.takeIf { row.refType == MailRefType.ORDER } ?: return null

        return orders.getById(id, sqlClient)
    }

    private suspend fun refundOf(row: MarketMailOutbox, order: MarketOrder, sqlClient: SqlClient): MarketRefund? =
        if (row.refType == MailRefType.REFUND) refunds.getById(row.refId, sqlClient)?.takeIf { it.orderId == order.id } else null
}
