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
 * Builds the [MarketMailMessage] of the order mails (12 section 5) from loaded rows, with no database and no host class: the
 * kinds of 12 section 4.1: the order kinds (MK-142) and the subscription, expiry-reminder and shipment kinds (MK-146).
 *
 * Every text, the table labels too, lives in the plugin's mail templates (`mail-templates/order/<variant>/<locale>.hbs`); this
 * class supplies only the values they read (formatted for [MailInput.locale], cut to 255 characters; a table row that is not
 * shown is an empty string), the flags that pick their conditional sentences and rows, and the rows of the `items` list.
 */
class MailContentBuilder(
    private val format: MarketFormat,
    private val config: () -> MarketConfig,
    private val site: () -> MailSite,
    private val sanitizeHtml: (String) -> String = { HtmlSanitizer.sanitize(it) }
) {
    fun supports(kind: MailKind): Boolean = kind in KINDS

    suspend fun build(input: MailInput): MarketMailMessage {
        require(supports(input.kind)) { "the mail kind ${input.kind} has no composer yet" }

        return when (input.kind) {
            MailKind.ORDER_RECEIVED -> received(input)
            MailKind.BANK_TRANSFER_INSTRUCTIONS -> bankTransfer(input)
            MailKind.ORDER_CONFIRMATION -> confirmation(input)
            MailKind.GIFT_RECEIVED -> gift(input)
            MailKind.ORDER_DELIVERED -> delivered(input)
            MailKind.ORDER_REFUNDED -> refunded(input)
            MailKind.EXPIRY_REMINDER -> expiryReminder(input)
            MailKind.SUBSCRIPTION_REMINDER -> subscriptionReminder(input)
            MailKind.SUBSCRIPTION_PAYMENT_FAILED -> subscriptionPaymentFailed(input)
            MailKind.SUBSCRIPTION_CANCELLED -> subscriptionCancelled(input)
            MailKind.SUBSCRIPTION_ENDED -> subscriptionEnded(input)
            MailKind.SHIPMENT_SHIPPED -> shipmentShipped(input)
            MailKind.SHIPMENT_DELIVERED -> shipmentDelivered(input)
        }
    }

    /** The plain test mail of the panel ("send test" without a kind): proves the e-mail settings, mentions no order. */
    fun test(@Suppress("UNUSED_PARAMETER") locale: String): MarketMailMessage =
        MarketMailMessage(MarketMailMessage.TEST_VARIANT, mapOf("storeName" to storeName()))

    // ----- the kinds ---------------------------------------------------------------------------------------------------

    private suspend fun received(i: MailInput) = message(i, totals(i) + orderDetails(i, paid = false), items = items(i, withPrices = true))

    private suspend fun bankTransfer(i: MailInput): MarketMailMessage {
        val expiresAt = long(i.params, "expiresAt")
        val body = i.params.getJsonObject("instructions")
        val html = body?.getString("body")?.takeIf { it.isNotBlank() }?.let { sanitizeHtml(it) }?.takeIf { it.isNotBlank() }
        val fields = (body?.getJsonArray("fields") ?: JsonArray()).filterIsInstance<JsonObject>().mapNotNull { f ->
            val label = f.getString("label")?.trim().orEmpty()
            val value = f.getString("value")?.trim().orEmpty()

            if (label.isEmpty() || value.isEmpty()) null else MailRow(cut(label), cut(value))
        }

        return message(
            i, totals(i) + ("expiresAt" to (expiresAt?.let { format.dateTime(it, i.locale) } ?: "")),
            items = items(i, withPrices = true), instructionsHtml = html, instructionFields = fields
        )
    }

    private suspend fun confirmation(i: MailInput): MarketMailMessage {
        val address = shippingAddress(i.order).takeIf { i.order.requiresShipping && it.isNotEmpty() }?.let { cut(it) }.orEmpty()

        return message(
            i, mapOf("shippingAddress" to address, "invoiceAttached" to i.invoiceAttached) + totals(i) + orderDetails(i, paid = true),
            items = items(i, withPrices = true)
        )
    }

    private suspend fun gift(i: MailInput): MarketMailMessage {
        val message = i.order.giftMessage?.trim()?.takeIf { it.isNotEmpty() }?.let { cut(it) }.orEmpty()

        return message(i, mapOf("giftMessage" to message, "storeUrl" to site().links.store().orEmpty()), items = items(i, withPrices = false))
    }

    private suspend fun delivered(i: MailInput) = message(i, items = items(i, withPrices = false))

    private suspend fun refunded(i: MailInput): MarketMailMessage {
        val refund = checkNotNull(i.refund) { "ORDER_REFUNDED needs its refund row" }
        val currency = refund.currency.ifBlank { i.order.currency }
        val amount = format.money(refund.amount, currency, i.locale)
        val details = mapOf(
            "refundToMethod" to if (refund.gatewayAmount > 0) format.money(refund.gatewayAmount, currency, i.locale) else "",
            "refundToCredits" to if (refund.creditAmount > 0) format.credits(refund.creditAmount, i.locale) else "",
            "refundReason" to refund.reason?.trim()?.takeIf { it.isNotEmpty() }?.let { cut(it) }.orEmpty()
        )

        val byId = i.items.associateBy { it.id }
        val lines = i.refundItems.mapNotNull { r ->
            val item = byId[r.orderItemId] ?: return@mapNotNull null

            MailItem(cut(item.productName), item.variantName?.takeIf { it.isNotBlank() }?.let { cut(it) }, r.quantity.toString(), format.money(r.amount, currency, i.locale), false)
        }
        val claim = refund.buyerActionUrl?.takeIf { isWebUrl(it) }.orEmpty()

        return message(
            i, mapOf("amount" to amount, "claimUrl" to claim, "creditNoteAttached" to i.invoiceAttached) + details, items = lines
        )
    }

    // ----- subscription, expiry and shipment kinds (MK-146) --------------------------------------------------------------

    /** 12 section 5 `EXPIRY_REMINDER`: product, valid until, a "Renew" button to the product page (left out when the product is gone). */
    private suspend fun expiryReminder(i: MailInput): MarketMailMessage {
        val expiresAt = long(i.params, "expiresAt") ?: i.entitlement?.expiresAt ?: error("EXPIRY_REMINDER has no expiry date")
        val name = cut(entitlementProductName(i))

        return message(
            i, mapOf(
                "productName" to name, "date" to format.date(expiresAt, i.locale), "validUntil" to format.dateTime(expiresAt, i.locale),
                "renewUrl" to productUrl(i).orEmpty()
            )
        )
    }

    /**
     * `SUBSCRIPTION_REMINDER`: `MANUAL` = "pay to keep it" with the pay link (the renewal order) or else the product page; `GATEWAY` / `MERCHANT` = the
     * upcoming-charge notice with the stored method. The amount is the subscription's price (the row), the date the period end the reminder was queued for.
     */
    private suspend fun subscriptionReminder(i: MailInput): MarketMailMessage {
        val sub = checkNotNull(i.subscription) { "SUBSCRIPTION_REMINDER needs its subscription row" }
        val periodEnd = long(i.params, "periodEnd") ?: sub.currentPeriodEnd ?: error("SUBSCRIPTION_REMINDER has no period end")
        val v = subscriptionValues(i, sub, periodEnd)
        val manual = sub.mode == SubscriptionMode.MANUAL
        val method = sub.storedMethodLabel?.trim()?.takeIf { it.isNotEmpty() && !manual }?.let { cut(it) }.orEmpty()
        val pay = if (manual) siteUrl(i.params.getString("payUrl")) ?: productUrl(i) else null

        return message(
            i, v + mapOf("manual" to manual, "paymentMethod" to method, "payUrl" to pay.orEmpty(), "manageUrl" to site().links.profile().orEmpty())
        )
    }

    /** `SUBSCRIPTION_PAYMENT_FAILED`: the grace date, the amount, a button to the failed renewal order (else the profile). */
    private suspend fun subscriptionPaymentFailed(i: MailInput): MarketMailMessage {
        val sub = checkNotNull(i.subscription) { "SUBSCRIPTION_PAYMENT_FAILED needs its subscription row" }
        val grace = long(i.params, "graceEndsAt") ?: sub.graceEndsAt
        val v = subscriptionValues(i, sub, null)
        val target = siteUrl(i.params.getString("payUrl")) ?: site().links.profile()

        return message(
            i, v + mapOf("graceEndsAt" to (grace?.let { format.date(it, i.locale) } ?: ""), "payUrl" to target.orEmpty())
        )
    }

    /** `SUBSCRIPTION_CANCELLED`: access continues until `accessUntil`; a "Manage subscription" link. */
    private suspend fun subscriptionCancelled(i: MailInput): MarketMailMessage {
        val sub = checkNotNull(i.subscription) { "SUBSCRIPTION_CANCELLED needs its subscription row" }
        val until = long(i.params, "accessUntil") ?: long(i.params, "endsAt") ?: sub.currentPeriodEnd

        return message(
            i, subscriptionValues(i, sub, until) + mapOf(
                "accessUntil" to (until?.let { format.date(it, i.locale) } ?: ""), "manageUrl" to site().links.profile().orEmpty()
            )
        )
    }

    /** `SUBSCRIPTION_ENDED`: one `reason<EndReason>` flag picks the sentence; a reason without a sentence of its own is `reasonOther`. */
    private suspend fun subscriptionEnded(i: MailInput): MarketMailMessage {
        val sub = checkNotNull(i.subscription) { "SUBSCRIPTION_ENDED needs its subscription row" }
        val raw = (i.params.getString("endReason") ?: sub.endReason)?.trim().orEmpty()
        val reason = raw.takeIf { it in END_REASONS } ?: "OTHER"
        val flags = END_REASONS.associate { reasonFlag(it) to (it == reason) }

        return message(i, subscriptionValues(i, sub, null) + flags + ("storeUrl" to site().links.store().orEmpty()))
    }

    /** 10 section 11.1 `SHIPMENT_SHIPPED`: carrier, tracking number, estimate, parcel lines, a track button (http / https only) and the order link. */
    private suspend fun shipmentShipped(i: MailInput): MarketMailMessage {
        val shipment = i.shipment
        val carrier = (i.params.getString("carrierName") ?: shipment?.carrierName)?.trim()?.takeIf { it.isNotEmpty() }
        val tracking = (i.params.getString("trackingNumber") ?: shipment?.trackingNumber)?.trim()?.takeIf { it.isNotEmpty() }
        val trackUrl = (i.params.getString("trackingUrl") ?: shipment?.trackingUrl)?.trim()?.takeIf { isWebUrl(it) }
        val estimate = shipment?.estimatedDeliveryAt?.let { format.date(it, i.locale) } ?: i.params.getString("estimatedDelivery")?.trim()?.takeIf { it.isNotEmpty() }
        val address = addressLines(i)

        return message(
            i, mapOf(
                "partial" to (i.params.getBoolean("isPartial", false) == true), "trackingUrl" to trackUrl.orEmpty(),
                "carrier" to carrier?.let { cut(it) }.orEmpty(),
                "trackingNumber" to tracking?.let { cut(it) }.orEmpty(),
                "estimatedDelivery" to estimate?.let { cut(it) }.orEmpty(),
                "shipTo" to if (address.isEmpty()) "" else cut(address.joinToString(", "))
            ),
            items = shipmentLines(i)
        )
    }

    /** `SHIPMENT_DELIVERED`: carrier, delivered date, parcel lines, the order link. */
    private suspend fun shipmentDelivered(i: MailInput): MarketMailMessage {
        val shipment = i.shipment
        val carrier = (i.params.getString("carrierName") ?: shipment?.carrierName)?.trim()?.takeIf { it.isNotEmpty() }
        val delivered = shipment?.deliveredAt?.let { format.date(it, i.locale) } ?: i.params.getString("deliveredAt")?.trim()?.takeIf { it.isNotEmpty() }
        return message(
            i, mapOf("carrier" to carrier?.let { cut(it) }.orEmpty(), "deliveredOn" to delivered?.let { cut(it) }.orEmpty()), items = shipmentLines(i)
        )
    }

    // ----- pieces of the new kinds -------------------------------------------------------------------------------------

    /** `productName`, `amount` (the subscription price in its currency) and `date` (when [date] is given). */
    private suspend fun subscriptionValues(i: MailInput, sub: MarketSubscription, date: Long?): Map<String, Any?> {
        val currency = sub.currency.ifBlank { i.order.currency }
        val base = mapOf("productName" to cut(sub.productName), "amount" to format.money(sub.price, currency, i.locale))

        return if (date != null) base + ("date" to format.date(date, i.locale)) else base
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
    private fun shipmentLines(i: MailInput): List<MailItem> {
        if (i.shipmentItems.isNotEmpty()) {
            val byId = i.items.associateBy { it.id }

            return i.shipmentItems.sortedBy { it.id }.mapNotNull { s ->
                val line = byId[s.orderItemId] ?: return@mapNotNull null

                MailItem(cut(line.productName), line.variantName?.takeIf { it.isNotBlank() }?.let { cut(it) }, s.quantity.toString(), "", false)
            }
        }

        return (i.params.getJsonArray("items") ?: JsonArray()).filterIsInstance<JsonObject>().map { o ->
            MailItem(cut(o.getString("name").orEmpty()), o.getString("variantName")?.takeIf { it.isNotBlank() }?.let { cut(it) }, (o.getValue("quantity") ?: 1).toString(), "", false)
        }
    }

    private fun addressLines(i: MailInput): List<String> =
        (i.params.getJsonArray("addressLines") ?: JsonArray()).filterIsInstance<String>().map { it.trim() }.filter { it.isNotEmpty() }

    private fun long(params: JsonObject, key: String): Long? = (params.getValue(key) as? Number)?.toLong()

    // ----- shared pieces -----------------------------------------------------------------------------------------------

    /**
     * The message every kind shares: the variant of [MailInput.kind], the common values (`storeName`, `orderNumber`, `username`, `orderUrl`)
     * plus [values], the test-order flag, the `items` rows and the bank-transfer instructions.
     */
    private fun message(
        i: MailInput,
        values: Map<String, Any?> = emptyMap(),
        items: List<MailItem> = emptyList(),
        instructionsHtml: String? = null,
        instructionFields: List<MailRow> = emptyList()
    ): MarketMailMessage {
        val common = mapOf(
            "storeName" to storeName(),
            "orderNumber" to orderNumber(i),
            "username" to cut(i.order.playerUsername),
            "orderUrl" to orderUrl(i.order).orEmpty()
        )

        return MarketMailMessage(
            variant = MarketMailMessage.variantOf(i.kind),
            values = common + values,
            testMode = i.order.testMode,
            items = items,
            instructionsHtml = instructionsHtml,
            instructionFields = instructionFields
        )
    }

    private fun storeName(): String = cut(config().storeName.ifBlank { site().websiteName })

    /** 12 section 5: the order number is `#<order id>`. */
    private fun orderNumber(i: MailInput): String = "#${i.order.id}"

    /** `{n}` is plain: the partial adds the multiplication sign. Bundle children carry no price. */
    private suspend fun items(i: MailInput, withPrices: Boolean): List<MailItem> =
        i.items.sortedBy { it.id }.map { item ->
            val child = item.kind == OrderItemKind.BUNDLE_CHILD || item.parentItemId != null

            MailItem(
                name = cut(item.productName),
                variant = item.variantName?.takeIf { it.isNotBlank() }?.let { cut(it) },
                quantity = item.quantity.toString(),
                total = if (!withPrices || child) "" else format.money(item.lineTotal, i.order.currency, i.locale),
                child = child
            )
        }

    /**
     * 12 section 5: the totals rows the template shows, each an empty string when it is not shown: every row only when non-zero;
     * `pricingMode <> MARKET` shows the total only; a credits-only order shows the total as credits. `discount` is negative;
     * `vatIncluded` picks the "VAT (included)" label; `paidWithCredits` only when credits and money were mixed.
     */
    private suspend fun totals(i: MailInput): Map<String, Any?> {
        val o = i.order
        val market = o.pricingMode == PricingMode.MARKET

        suspend fun money(amount: Long, negative: Boolean = false): String =
            if (!market || amount == 0L) "" else format.money(if (negative) -amount else amount, o.currency, i.locale)

        return linkedMapOf(
            "subtotal" to money(o.subtotal),
            "discount" to money(o.discountTotal + o.couponDiscount + o.creatorDiscount + o.upgradeDiscount, negative = true),
            "shipping" to money(o.shippingTotal),
            "paymentFee" to money(o.paymentFee),
            "vat" to money(o.vatTotal),
            "vatIncluded" to o.pricesIncludeVat,
            "total" to totalLabel(i),
            "paidWithCredits" to if (market && o.creditAmount > 0 && o.gatewayAmount > 0) format.credits(o.creditAmount, i.locale) else ""
        )
    }

    /** The grand total: money, or credits when the order was paid with credits alone. */
    private suspend fun totalLabel(i: MailInput): String =
        if (i.order.creditAmount > 0 && i.order.gatewayAmount == 0L) format.credits(i.order.creditAmount, i.locale) else format.money(i.order.totalPrice, i.order.currency, i.locale)

    /** The details rows of an order: its date (paid or placed), payment method and, for a paid gift, the recipient. */
    private suspend fun orderDetails(i: MailInput, paid: Boolean): Map<String, Any?> {
        val o = i.order

        return linkedMapOf(
            "orderDate" to format.dateTime(if (paid) o.paidAt ?: o.createdAt else o.createdAt, i.locale),
            "paymentMethod" to o.paymentLabel.takeIf { it.isNotBlank() }?.let { cut(it) }.orEmpty(),
            "recipient" to if (paid && o.isGift && o.recipientUsername.isNotBlank()) cut(o.recipientUsername) else ""
        )
    }

    /** The order page (12 section 4.4), with the access token for a guest order; none without a site URL. */
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

        private val SLUG = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$")

        /** The end reasons with a sentence of their own in the `subscription-ended` template; any other reason reads `OTHER`. */
        val END_REASONS = listOf(
            "BUYER_CANCEL", "ADMIN_CANCEL", "PAYMENT_FAILED", "GATEWAY_ENDED", "COMPLETED", "REFUND", "CHARGEBACK", "PROVIDER_UNAVAILABLE", "OTHER"
        )

        /** `PAYMENT_FAILED` -> `reasonPaymentFailed`, the boolean variable of the template. */
        fun reasonFlag(reason: String): String =
            "reason" + reason.split('_').joinToString("") { part -> part.lowercase().replaceFirstChar { it.uppercase() } }

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

    override suspend fun compose(row: MarketMailOutbox, sqlClient: SqlClient): MarketMailMessage {
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
