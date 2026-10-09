package com.panomc.plugins.market.mail

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.db.dao.MarketEntitlementDao
import com.panomc.plugins.market.db.dao.MarketInvoiceDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketRefundDao
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.dao.MarketRefundItemDao
import com.panomc.plugins.market.db.dao.MarketShipmentDao
import com.panomc.plugins.market.db.dao.MarketShipmentItemDao
import com.panomc.plugins.market.db.dao.MarketSubscriptionDao
import com.panomc.plugins.market.db.model.InvoiceType
import com.panomc.plugins.market.db.model.MailKind
import com.panomc.plugins.market.db.model.MailRefType
import com.panomc.plugins.market.db.model.MarketMailOutbox
import com.panomc.plugins.market.db.model.EntitlementStatus
import com.panomc.plugins.market.db.model.MarketEntitlement
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketRefund
import com.panomc.plugins.market.db.model.MarketRefundItem
import com.panomc.plugins.market.db.model.MarketShipment
import com.panomc.plugins.market.db.model.MarketShipmentItem
import com.panomc.plugins.market.db.model.MarketSubscription
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.PricingMode
import com.panomc.plugins.market.db.model.SubscriptionMode
import com.panomc.plugins.market.db.model.SubscriptionStatus
import com.panomc.plugins.market.i18n.MarketFormat
import com.panomc.plugins.market.i18n.MarketI18n
import com.panomc.plugins.market.pdf.InvoiceMailAttachments
import com.panomc.plugins.market.util.HtmlSanitizer
import com.panomc.plugins.market.util.MarketStatus
import com.panomc.plugins.market.util.OrderStatus
import com.panomc.plugins.market.util.StoreLinks
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import java.net.URI

/**
 * The facts of the site a mail names (12 section 4.4): `websiteName`, `websiteUrl` (no trailing slash; empty = no buttons) and the [links] to the
 * pages of the front-end (the URL map of doc 05 section 10.2); a page that has no address is left out of the mail.
 */
class MailSite(val websiteName: String, val websiteUrl: String, val links: StoreLinks = StoreLinks.ofBase(websiteUrl))

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
    val invoiceAttached: Boolean = false,
    /** The subscription of a `SUBSCRIPTION_*` mail (MK-146). */
    val subscription: MarketSubscription? = null,
    /** The entitlement of an `EXPIRY_REMINDER`. */
    val entitlement: MarketEntitlement? = null,
    /** The slug of the product page the renew buttons point to; `null` when the product is gone or archived (the button is then left out). */
    val productSlug: String? = null,
    /** The parcel of a `SHIPMENT_*` mail and its lines. */
    val shipment: MarketShipment? = null,
    val shipmentItems: List<MarketShipmentItem> = emptyList()
)

/**
 * Builds the [MailContent] block model of the order mails (12 section 5) from loaded rows, with no database and no host class: the
 * kinds of 12 section 4.1: the order kinds (MK-142) and the subscription, expiry-reminder and shipment kinds (MK-146).
 * Locale keys are `mail.<kind-key>.*` and `mail.common.*`; every string is final (translated, formatted, cut to 255 characters).
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
            MailKind.EXPIRY_REMINDER -> expiryReminder(input, vars)
            MailKind.SUBSCRIPTION_REMINDER -> subscriptionReminder(input, vars)
            MailKind.SUBSCRIPTION_PAYMENT_FAILED -> subscriptionPaymentFailed(input, vars)
            MailKind.SUBSCRIPTION_CANCELLED -> subscriptionCancelled(input, vars)
            MailKind.SUBSCRIPTION_ENDED -> subscriptionEnded(input, vars)
            MailKind.SHIPMENT_SHIPPED -> shipmentShipped(input, vars)
            MailKind.SHIPMENT_DELIVERED -> shipmentDelivered(input, vars)
        }
    }

    /** The plain test mail of the panel ("send test" without a kind): proves the e-mail settings, mentions no order. */
    suspend fun test(locale: String): MailContent {
        val vars = mapOf("websiteName" to cut(site().websiteName), "storeName" to cut(config().storeName.ifBlank { site().websiteName }))

        return MailContent(
            subject = i18n.t(locale, "mail.test.subject", vars), preheader = i18n.t(locale, "mail.test.preheader", vars), heading = i18n.t(locale, "mail.test.heading", vars),
            paragraphs = listOf(i18n.t(locale, "mail.test.body", vars)), footerNote = i18n.t(locale, "mail.test.footer", vars)
        )
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
        val store = site().links.store()

        return shell(
            i, vars, "gift-received", paragraphs = listOf(t(i, "mail.gift-received.body", vars), t(i, "mail.gift-received.body-2", vars)),
            quote = message, items = items(i, withPrices = false), button = store?.let { t(i, "mail.gift-received.button", vars) to it }
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


    // ----- subscription, expiry and shipment kinds (MK-146) --------------------------------------------------------------

    /** 12 section 5 `EXPIRY_REMINDER`: product, valid until, a "Renew" button to the product page (left out when the product is gone). */
    private suspend fun expiryReminder(i: MailInput, vars: Map<String, Any?>): MailContent {
        val expiresAt = long(i.params, "expiresAt") ?: i.entitlement?.expiresAt ?: error("EXPIRY_REMINDER has no expiry date")
        val name = cut(entitlementProductName(i))
        val v = vars + ("productName" to name) + ("date" to format.date(expiresAt, i.locale))
        val details = listOf(
            MailContent.Row(t(i, "mail.common.product", v), name),
            MailContent.Row(t(i, "mail.common.valid-until", v), format.dateTime(expiresAt, i.locale), strong = true)
        )

        return shell(
            i, v, "expiry-reminder", paragraphs = listOf(t(i, "mail.expiry-reminder.body", v), t(i, "mail.expiry-reminder.body-2", v)), details = details,
            button = productUrl(i)?.let { t(i, "mail.expiry-reminder.button", v) to it }
        )
    }

    /**
     * `SUBSCRIPTION_REMINDER`: `MANUAL` = "pay to keep it" with the pay link (the renewal order) or else the product page; `GATEWAY` / `MERCHANT` = the
     * upcoming-charge notice with the stored method. The amount is the subscription's price (the row), the date the period end the reminder was queued for.
     */
    private suspend fun subscriptionReminder(i: MailInput, vars: Map<String, Any?>): MailContent {
        val sub = checkNotNull(i.subscription) { "SUBSCRIPTION_REMINDER needs its subscription row" }
        val periodEnd = long(i.params, "periodEnd") ?: sub.currentPeriodEnd ?: error("SUBSCRIPTION_REMINDER has no period end")
        val v = subscriptionVars(i, sub, vars, periodEnd)
        val manual = sub.mode == SubscriptionMode.MANUAL
        val paragraphs = ArrayList<String>()

        paragraphs += t(i, if (manual) "mail.subscription-reminder.body-manual" else "mail.subscription-reminder.body", v)

        val method = sub.storedMethodLabel?.trim()?.takeIf { it.isNotEmpty() }

        if (!manual && method != null) paragraphs += t(i, "mail.subscription-reminder.body-method", v + ("method" to cut(method)))

        val details = listOf(
            MailContent.Row(t(i, "mail.common.product", v), v.getValue("productName").toString()),
            MailContent.Row(t(i, "mail.common.renews-on", v), format.date(periodEnd, i.locale)),
            MailContent.Row(t(i, "mail.common.amount", v), v.getValue("amount").toString(), strong = true)
        )
        val pay = if (manual) (siteUrl(i.params.getString("payUrl")) ?: productUrl(i))?.let { t(i, "mail.subscription-reminder.button", v) to it } else null

        return shell(i, v, "subscription-reminder", paragraphs = paragraphs, details = details, button = pay, secondary = manage(i, v))
    }

    /** `SUBSCRIPTION_PAYMENT_FAILED`: the grace date, the amount, a button to the failed renewal order (else the profile). */
    private suspend fun subscriptionPaymentFailed(i: MailInput, vars: Map<String, Any?>): MailContent {
        val sub = checkNotNull(i.subscription) { "SUBSCRIPTION_PAYMENT_FAILED needs its subscription row" }
        val grace = long(i.params, "graceEndsAt") ?: sub.graceEndsAt
        val v = subscriptionVars(i, sub, vars, null) + (if (grace != null) mapOf("graceEndsAt" to format.date(grace, i.locale)) else emptyMap())
        val details = listOf(
            MailContent.Row(t(i, "mail.common.product", v), v.getValue("productName").toString()),
            MailContent.Row(t(i, "mail.common.amount", v), v.getValue("amount").toString(), strong = true)
        )
        val target = siteUrl(i.params.getString("payUrl")) ?: site().links.profile()

        return shell(
            i, v, "subscription-payment-failed",
            paragraphs = listOf(t(i, if (grace != null) "mail.subscription-payment-failed.body" else "mail.subscription-payment-failed.body-no-grace", v), t(i, "mail.subscription-payment-failed.body-2", v)),
            details = details, button = target?.let { t(i, "mail.subscription-payment-failed.button", v) to it }
        )
    }

    /** `SUBSCRIPTION_CANCELLED`: access continues until `accessUntil`; a "Manage subscription" link. */
    private suspend fun subscriptionCancelled(i: MailInput, vars: Map<String, Any?>): MailContent {
        val sub = checkNotNull(i.subscription) { "SUBSCRIPTION_CANCELLED needs its subscription row" }
        val until = long(i.params, "accessUntil") ?: long(i.params, "endsAt") ?: sub.currentPeriodEnd
        val v = subscriptionVars(i, sub, vars, until)

        return shell(
            i, v, "subscription-cancelled", paragraphs = listOf(t(i, if (until != null) "mail.subscription-cancelled.body" else "mail.subscription-cancelled.body-no-date", v + ("accessUntil" to (until?.let { format.date(it, i.locale) } ?: "")))),
            secondary = manage(i, v)
        )
    }

    /** `SUBSCRIPTION_ENDED`: the sentence of `mail.subscription-ended.reason.<END_REASON>`, `OTHER` for a reason without a text. */
    private suspend fun subscriptionEnded(i: MailInput, vars: Map<String, Any?>): MailContent {
        val sub = checkNotNull(i.subscription) { "SUBSCRIPTION_ENDED needs its subscription row" }
        val v = subscriptionVars(i, sub, vars, null)
        val reason = (i.params.getString("endReason") ?: sub.endReason)?.trim().orEmpty()
        val key = "mail.subscription-ended.reason.$reason".takeIf { reason.matches(REASON) && i18n.has(i.locale, it) } ?: "mail.subscription-ended.reason.OTHER"
        val store = site().links.store()

        return shell(
            i, v, "subscription-ended", paragraphs = listOf(t(i, "mail.subscription-ended.body", v), t(i, key, v)),
            button = store?.let { t(i, "mail.subscription-ended.button", v) to it }
        )
    }

    /** 10 section 11.1 `SHIPMENT_SHIPPED`: carrier, tracking number, estimate, parcel lines, a track button (http / https only) and the order link. */
    private suspend fun shipmentShipped(i: MailInput, vars: Map<String, Any?>): MailContent {
        val shipment = i.shipment
        val carrier = (i.params.getString("carrierName") ?: shipment?.carrierName)?.trim()?.takeIf { it.isNotEmpty() }
        val tracking = (i.params.getString("trackingNumber") ?: shipment?.trackingNumber)?.trim()?.takeIf { it.isNotEmpty() }
        val trackUrl = (i.params.getString("trackingUrl") ?: shipment?.trackingUrl)?.trim()?.takeIf { isWebUrl(it) }
        val estimate = shipment?.estimatedDeliveryAt?.let { format.date(it, i.locale) } ?: i.params.getString("estimatedDelivery")?.trim()?.takeIf { it.isNotEmpty() }
        val details = ArrayList<MailContent.Row>()

        details += MailContent.Row(t(i, "mail.common.order-number", vars), vars.getValue("orderNumber").toString())
        if (carrier != null) details += MailContent.Row(t(i, "mail.common.carrier", vars), cut(carrier))
        if (tracking != null) details += MailContent.Row(t(i, "mail.common.tracking-number", vars), cut(tracking), strong = true)
        if (estimate != null) details += MailContent.Row(t(i, "mail.common.estimated-delivery", vars), cut(estimate))

        val address = addressLines(i)

        if (address.isNotEmpty()) details += MailContent.Row(t(i, "mail.common.ship-to", vars), cut(address.joinToString(", ")))

        val paragraphs = arrayListOf(t(i, "mail.shipment-shipped.body", vars))

        if (i.params.getBoolean("isPartial", false) == true) paragraphs += t(i, "mail.shipment-shipped.partial-note", vars)

        val view = viewOrder(i, "mail.shipment-shipped.button")

        return shell(
            i, vars, "shipment-shipped", paragraphs = paragraphs, items = shipmentLines(i), details = details,
            button = if (trackUrl != null) t(i, "mail.shipment-shipped.button-track", vars) to trackUrl else view,
            secondary = if (trackUrl != null) viewOrder(i, "mail.common.view-order") else null
        )
    }

    /** `SHIPMENT_DELIVERED`: carrier, delivered date, parcel lines, the order link. */
    private suspend fun shipmentDelivered(i: MailInput, vars: Map<String, Any?>): MailContent {
        val shipment = i.shipment
        val carrier = (i.params.getString("carrierName") ?: shipment?.carrierName)?.trim()?.takeIf { it.isNotEmpty() }
        val delivered = shipment?.deliveredAt?.let { format.date(it, i.locale) } ?: i.params.getString("deliveredAt")?.trim()?.takeIf { it.isNotEmpty() }
        val details = ArrayList<MailContent.Row>()

        details += MailContent.Row(t(i, "mail.common.order-number", vars), vars.getValue("orderNumber").toString())
        if (carrier != null) details += MailContent.Row(t(i, "mail.common.carrier", vars), cut(carrier))
        if (delivered != null) details += MailContent.Row(t(i, "mail.common.delivered-on", vars), cut(delivered))

        return shell(
            i, vars, "shipment-delivered", paragraphs = listOf(t(i, "mail.shipment-delivered.body", vars)), items = shipmentLines(i), details = details,
            button = viewOrder(i, "mail.shipment-delivered.button")
        )
    }

    // ----- pieces of the new kinds -------------------------------------------------------------------------------------

    /** `{productName}`, `{amount}` (the subscription price in its currency) and `{date}` (when [date] is given) on top of the common variables. */
    private suspend fun subscriptionVars(i: MailInput, sub: MarketSubscription, vars: Map<String, Any?>, date: Long?): Map<String, Any?> {
        val currency = sub.currency.ifBlank { i.order.currency }
        val base = vars + ("productName" to cut(sub.productName)) + ("amount" to format.money(sub.price, currency, i.locale))

        return if (date != null) base + ("date" to format.date(date, i.locale)) else base
    }

    /** The "Manage subscription" link to the buyer's profile; none without a site URL. */
    private suspend fun manage(i: MailInput, vars: Map<String, Any?>): Pair<String, String>? {
        val profile = site().links.profile()

        return profile?.let { t(i, "mail.common.manage-subscription", vars) to it }
    }

    /** The name of the product an entitlement reminder is about: the snapshot of its order line (never the live product). */
    private fun entitlementProductName(i: MailInput): String {
        val line = i.entitlement?.let { e -> i.items.firstOrNull { it.id == e.orderItemId } }
            ?: i.items.firstOrNull { it.kind != OrderItemKind.BUNDLE_CHILD }

        return line?.productName.orEmpty()
    }

    private fun productUrl(i: MailInput): String? {
        val slug = i.productSlug?.takeIf { it.isNotBlank() } ?: return null

        return if (!SLUG.matches(slug)) null else site().links.product(slug)
    }

    /** A site-relative link stored in `params` (older rows kept `/store/order/<publicId>`) made absolute; an absolute http(s) link is kept; anything else is no link. */
    private fun siteUrl(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val base = site().websiteUrl

        return when {
            value.startsWith("/") && !value.startsWith("//") -> if (base.isEmpty()) null else base + value
            isWebUrl(value) -> value
            else -> null
        }
    }

    /** The lines of a parcel: the `market_shipment_item` rows against the order lines, else the pre-formatted `params.items`. No prices. */
    private fun shipmentLines(i: MailInput): List<MailContent.Item> {
        if (i.shipmentItems.isNotEmpty()) {
            val byId = i.items.associateBy { it.id }

            return i.shipmentItems.sortedBy { it.id }.mapNotNull { s ->
                val line = byId[s.orderItemId] ?: return@mapNotNull null

                MailContent.Item(cut(line.productName), line.variantName?.takeIf { it.isNotBlank() }?.let { cut(it) }, s.quantity.toString(), "", false)
            }
        }

        return (i.params.getJsonArray("items") ?: JsonArray()).filterIsInstance<JsonObject>().map { o ->
            MailContent.Item(cut(o.getString("name").orEmpty()), o.getString("variantName")?.takeIf { it.isNotBlank() }?.let { cut(it) }, (o.getValue("quantity") ?: 1).toString(), "", false)
        }
    }

    private fun addressLines(i: MailInput): List<String> =
        (i.params.getJsonArray("addressLines") ?: JsonArray()).filterIsInstance<String>().map { it.trim() }.filter { it.isNotEmpty() }

    private fun long(params: JsonObject, key: String): Long? = (params.getValue(key) as? Number)?.toLong()

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
        val publicId = order.publicId?.takeIf { it.isNotBlank() } ?: return null
        val token = if (order.userId == null && !order.accessToken.isNullOrBlank()) mapOf("token" to order.accessToken) else emptyMap()

        return site().links.order(publicId, token)
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

        private val REASON = Regex("^[A-Z_]{1,40}$")
        private val SLUG = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$")

        /** Every kind has a composer (MK-142: the order kinds, MK-146: subscription, expiry and shipment kinds). */
        val KINDS: Set<MailKind> = MailKind.entries.toSet()
    }
}

/**
 * The content side of the mail outbox (12 sections 4.3.5, 4.3.6, 4.4, 5), the production [MailComposition]: reads the rows
 * the outbox row points at and hands them to [MailContentBuilder].
 *
 * Relevance (12 section 4.3.5): a missing referenced row is obsolete for every kind; `BANK_TRANSFER_INSTRUCTIONS` and `ORDER_RECEIVED` are obsolete
 * once the order left `PENDING` / `REVIEW`; `EXPIRY_REMINDER` once the entitlement is not `ACTIVE`, its `expiresAt` is no longer the `refKey` or has
 * passed; `SUBSCRIPTION_REMINDER` once the subscription is not `ACTIVE`, is set to end, or went past the reminded period; `SUBSCRIPTION_PAYMENT_FAILED`
 * once the subscription is not `PAST_DUE`.
 */
class MailComposer(
    private val builder: MailContentBuilder,
    private val orders: MarketOrderDao,
    private val orderItems: MarketOrderItemDao,
    private val refunds: MarketRefundDao,
    private val refundItems: MarketRefundItemDao,
    private val invoices: MarketInvoiceDao? = null,
    private val attachments: InvoiceMailAttachments? = null,
    private val config: () -> MarketConfig = { MarketConfig() },
    private val subscriptions: MarketSubscriptionDao? = null,
    private val entitlements: MarketEntitlementDao? = null,
    private val shipments: MarketShipmentDao? = null,
    private val shipmentItems: MarketShipmentItemDao? = null,
    private val products: MarketProductDao? = null,
    private val now: () -> Long = { System.currentTimeMillis() }
) : MailComposition {
    override suspend fun isObsolete(row: MarketMailOutbox, sqlClient: SqlClient): Boolean {
        if (!builder.supports(row.kind)) return false

        val order = orderOf(row, sqlClient) ?: return true

        return when (row.kind) {
            MailKind.ORDER_REFUNDED -> refundOf(row, order, sqlClient) == null
            MailKind.BANK_TRANSFER_INSTRUCTIONS, MailKind.ORDER_RECEIVED -> order.status != OrderStatus.PENDING && order.status != OrderStatus.REVIEW
            MailKind.EXPIRY_REMINDER -> {
                val entitlement = entitlementOf(row, sqlClient) ?: return true
                val expiresAt = entitlement.expiresAt

                entitlement.status != EntitlementStatus.ACTIVE || expiresAt == null || expiresAt.toString() != row.refKey || expiresAt <= now()
            }
            MailKind.SUBSCRIPTION_REMINDER -> {
                val sub = subscriptionOf(row, sqlClient) ?: return true
                val reminded = row.refKey.toIntOrNull()

                // `periodIndex` is the cycle the reminder was queued in: a paid renewal moves `cycleCount` past it
                sub.status != SubscriptionStatus.ACTIVE || sub.cancelAtPeriodEnd || (reminded != null && sub.cycleCount > reminded)
            }
            MailKind.SUBSCRIPTION_PAYMENT_FAILED -> (subscriptionOf(row, sqlClient) ?: return true).status != SubscriptionStatus.PAST_DUE
            MailKind.SUBSCRIPTION_CANCELLED, MailKind.SUBSCRIPTION_ENDED -> subscriptionOf(row, sqlClient) == null
            MailKind.SHIPMENT_SHIPPED, MailKind.SHIPMENT_DELIVERED -> shipmentOf(row, order, sqlClient) == null
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
        val attached = invoiceWillBeAttached(row, order, refund, sqlClient)
        val base = MailInput(row.kind, row.locale, order, items, params, refund, refundLines, attached)

        return builder.build(
            when (row.kind) {
                MailKind.EXPIRY_REMINDER -> {
                    val entitlement = checkNotNull(entitlementOf(row, sqlClient)) { "entitlement ${row.refId} of mail ${row.id} is gone" }

                    base.withEntitlement(entitlement, slugOf(entitlement.productId, sqlClient))
                }
                MailKind.SUBSCRIPTION_REMINDER, MailKind.SUBSCRIPTION_PAYMENT_FAILED, MailKind.SUBSCRIPTION_CANCELLED, MailKind.SUBSCRIPTION_ENDED -> {
                    val sub = checkNotNull(subscriptionOf(row, sqlClient)) { "subscription ${row.refId} of mail ${row.id} is gone" }

                    base.withSubscription(sub, slugOf(sub.productId, sqlClient))
                }
                MailKind.SHIPMENT_SHIPPED, MailKind.SHIPMENT_DELIVERED -> {
                    val shipment = checkNotNull(shipmentOf(row, order, sqlClient)) { "shipment ${row.refId} of mail ${row.id} is gone" }

                    base.withShipment(shipment, shipmentItems?.getByShipmentId(shipment.id, sqlClient).orEmpty())
                }
                else -> base
            }
        )
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

    private suspend fun entitlementOf(row: MarketMailOutbox, sqlClient: SqlClient): MarketEntitlement? =
        if (row.refType == MailRefType.ENTITLEMENT) entitlements?.getById(row.refId, sqlClient) else null

    private suspend fun subscriptionOf(row: MarketMailOutbox, sqlClient: SqlClient): MarketSubscription? =
        if (row.refType == MailRefType.SUBSCRIPTION) subscriptions?.getById(row.refId, sqlClient) else null

    private suspend fun shipmentOf(row: MarketMailOutbox, order: MarketOrder, sqlClient: SqlClient): MarketShipment? =
        if (row.refType == MailRefType.SHIPMENT) shipments?.getById(row.refId, sqlClient)?.takeIf { it.orderId == order.id } else null

    /** The product page slug for a renew button: `null` when the product is deleted, archived (inactive) or unknown (12 section 5). */
    private suspend fun slugOf(productId: Long, sqlClient: SqlClient): String? {
        val product = products?.getById(productId, sqlClient) ?: return null

        return product.slug.takeIf { product.deletedAt == null && product.status != MarketStatus.INACTIVE }
    }

    private fun MailInput.withEntitlement(entitlement: MarketEntitlement, slug: String?) = MailInput(
        kind, locale, order, items, params, refund, refundItems, invoiceAttached, entitlement = entitlement, productSlug = slug
    )

    private fun MailInput.withSubscription(sub: MarketSubscription, slug: String?) = MailInput(
        kind, locale, order, items, params, refund, refundItems, invoiceAttached, subscription = sub, productSlug = slug
    )

    private fun MailInput.withShipment(shipment: MarketShipment, lines: List<MarketShipmentItem>) = MailInput(
        kind, locale, order, items, params, refund, refundItems, invoiceAttached, shipment = shipment, shipmentItems = lines
    )
}
