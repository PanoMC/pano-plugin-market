package com.panomc.plugins.market.service

import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.abuse.PiiMask
import com.panomc.plugins.market.core.credit.CreditMath
import com.panomc.plugins.market.core.delivery.DeliveryError
import com.panomc.plugins.market.core.refund.RefundMath
import com.panomc.plugins.market.db.dao.MarketDeliveryDao
import com.panomc.plugins.market.db.dao.MarketDisputeDao
import com.panomc.plugins.market.db.dao.MarketEntitlementDao
import com.panomc.plugins.market.db.dao.MarketInvoiceDao
import com.panomc.plugins.market.db.dao.MarketLegalTextDao
import com.panomc.plugins.market.db.dao.MarketMailOutboxDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderEventDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketPaymentDao
import com.panomc.plugins.market.db.dao.MarketRefundDao
import com.panomc.plugins.market.db.dao.MarketShipmentDao
import com.panomc.plugins.market.db.dao.MarketSubscriptionDao
import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.db.model.DeliverySourceType
import com.panomc.plugins.market.db.model.DeliveryStatus
import com.panomc.plugins.market.db.model.DisputeStatus
import com.panomc.plugins.market.db.model.FulfillmentStatus
import com.panomc.plugins.market.db.model.MarketDelivery
import com.panomc.plugins.market.db.model.MarketMailOutbox
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderEvent
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.MarketRefund
import com.panomc.plugins.market.db.model.MarketShipment
import com.panomc.plugins.market.db.model.MarketSubscription
import com.panomc.plugins.market.db.model.OrderActorType
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.db.model.ShippingStatus
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.permission.FieldGating
import com.panomc.plugins.market.routes.panel.delivery.DeliveryAdminService
import com.panomc.plugins.market.service.platform.UserDirectory
import com.panomc.plugins.market.util.CsvValue
import com.panomc.plugins.market.util.CsvWriter
import com.panomc.plugins.market.util.MoneyUtil
import com.panomc.plugins.market.util.OrderStatus
import com.panomc.plugins.market.util.Paging
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.time.Instant

/** The filters of `GET /orders` and `GET /orders/export` (04 section 7). Every set is "any of"; an empty set or a `null` does not filter. */
class OrderFilter(
    val statuses: Set<OrderStatus> = emptySet(),
    val paymentMethodId: String? = null,
    val fulfillmentStatuses: Set<FulfillmentStatus> = emptySet(),
    val shippingStatuses: Set<ShippingStatus> = emptySet(),
    /** `createdAt` of the order, epoch ms, inclusive. */
    val from: Long? = null,
    val to: Long? = null,
    val testMode: Boolean? = null,
    val sources: Set<OrderSource> = emptySet(),
    val search: String? = null
) {
    /**
     * The filter as the activity log of an export keeps it (11 section 15): the keys that narrowed the export. The search text itself is never
     * written (it may be an e-mail address): only that one was used.
     */
    fun describe(): JsonObject {
        val out = JsonObject()

        if (statuses.isNotEmpty()) out.put("status", statuses.joinToString(",") { it.name })
        paymentMethodId?.let { out.put("paymentMethodId", it) }
        if (fulfillmentStatuses.isNotEmpty()) out.put("fulfillmentStatus", fulfillmentStatuses.joinToString(",") { it.name })
        if (shippingStatuses.isNotEmpty()) out.put("shippingStatus", shippingStatuses.joinToString(",") { it.name })
        from?.let { out.put("from", it) }
        to?.let { out.put("to", it) }
        testMode?.let { out.put("testMode", it) }
        if (sources.isNotEmpty()) out.put("source", sources.joinToString(",") { it.name })
        if (!search.isNullOrBlank()) out.put("search", true)

        return out
    }
}

class OrderPage(val rows: List<JsonObject>, val count: Long)

/** What the caller holds (04 section 9): [pii] is `OM` or `PAY` (11 section 14.5), [manage] is `OM`, [pay] is `PAY`; the umbrella role and `*` hold all of them. */
class OrderViewer(val pii: Boolean, val manage: Boolean, val pay: Boolean) {
    companion object {
        fun of(manage: Boolean, pay: Boolean) = OrderViewer(pii = manage || pay, manage = manage, pay = pay)

        val NOBODY = OrderViewer(pii = false, manage = false, pay = false)
    }
}

class OrderExportResult(val rows: Int, val truncated: Boolean)

/** The CSV columns of `GET /orders/export`, in the order of 04 section 7. */
object OrderExportColumns {
    val ALL: List<String> = listOf(
        "orderId", "publicId", "createdAt", "paidAt", "status", "source", "playerUsername", "recipientUsername", "email", "productName", "variantName", "sku", "quantity",
        "unitPrice", "lineTotal", "currency", "orderTotal", "couponCode", "creatorCode", "paymentMethod", "gatewayTransactionId", "gatewayAmount", "creditValue",
        "refundedTotal", "fulfillmentStatus", "shippingStatus", "country", "testMode"
    )

    /**
     * The columns of the request: absent or blank = every column the caller may select (so a caller below the PII tier never gets a 403 for a default
     * export); a `csv` is trimmed and de-duplicated, an unknown key is a 400, a PII column below the tier is the 403 of 11 section 14.5.
     */
    fun parse(raw: String?, pii: Boolean): List<String> {
        val requested = raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()

        if (requested.isEmpty()) return if (pii) ALL else ALL.filter { it !in FieldGating.PII_EXPORT_COLUMNS }

        val unknown = requested.firstOrNull { it !in ALL }

        if (unknown != null) throw RequestValueException("columns", "UNKNOWN_VALUE")

        FieldGating.requireExportColumns(requested, pii)

        return requested
    }
}

/**
 * The panel's read side of orders (MK-170; 04 section 7, 11 sections 6.5 and 14.5, 13 sections 5 and 6): the filtered list, the full detail with its blocks in the
 * pinned row shapes and the `allowed{}` flags, the note, and the CSV export. It reads only; the writes stay with the services that own the state.
 *
 * Personal data goes through [FieldGating] in one place for list, detail and export, so a caller below the PII tier (`OM` or `PAY`) gets a masked e-mail,
 * nulls for the address / network fields, no e-mail predicate in the search and a 403 for a PII export column.
 */
class OrderQueryService(
    private val orders: MarketOrderDao,
    private val orderItems: MarketOrderItemDao,
    private val payments: MarketPaymentDao,
    private val refunds: MarketRefundDao,
    private val disputes: MarketDisputeDao,
    private val deliveries: MarketDeliveryDao,
    private val shipments: MarketShipmentDao,
    private val orderEvents: MarketOrderEventDao,
    private val invoices: MarketInvoiceDao,
    private val mailOutbox: MarketMailOutboxDao,
    private val subscriptions: MarketSubscriptionDao,
    private val entitlements: MarketEntitlementDao,
    private val legalTexts: MarketLegalTextDao,
    private val users: UserDirectory,
    private val config: () -> MarketConfig,
    private val clock: Clock,
    /** Names of the servers by id, for the target server of a line and the server of a delivery. */
    private val serverNames: suspend (SqlClient) -> Map<Long, String> = { emptyMap() }
) {
    private fun table(name: String) = "`${orders.prefix()}$name`"

    // ================================================================================================================ list

    /** `GET /orders`: newest first, the filter of 04 section 7; [pii] decides the e-mail mask and the e-mail predicate of the search. */
    suspend fun list(filter: OrderFilter, window: Paging.Window, pii: Boolean, client: SqlClient): OrderPage {
        val args = ArrayList<Any?>()
        val where = whereOf(filter, pii, args)
        val count = client.preparedQuery("SELECT COUNT(*) AS c FROM ${table("market_order")} o WHERE $where").execute(Tuple.from(args)).coAwait().first().getLong("c")

        val pageArgs = ArrayList<Any?>(args).also {
            it += window.pageSize.toLong()
            it += window.offset
        }
        val rows = client.preparedQuery(
            "SELECT o.`id`, o.`publicId`, o.`source`, o.`userId`, o.`playerUsername`, o.`recipientUsername`, o.`isGift`, o.`email`, o.`totalPrice`, o.`currency`, o.`paymentMethodId`, " +
                "o.`paymentLabel`, o.`status`, o.`gatewayAmount`, o.`creditValue`, o.`refundedTotal`, o.`fulfillmentStatus`, o.`shippingStatus`, o.`paidAt`, o.`testMode`, " +
                "o.`reviewReason`, o.`createdAt`, o.`updatedAt` FROM ${table("market_order")} o WHERE $where ORDER BY o.`createdAt` DESC, o.`id` DESC LIMIT ? OFFSET ?"
        ).execute(Tuple.from(pageArgs)).coAwait().toList()

        val itemsByOrder = orderItems.getByOrderIds(rows.map { it.getLong("id") }, client).groupBy { it.orderId }

        return OrderPage(rows.map { listRow(it, itemsByOrder[it.getLong("id")].orEmpty(), pii) }, count)
    }

    private fun listRow(r: Row, items: List<MarketOrderItem>, pii: Boolean): JsonObject = JsonObject()
        .put("id", r.getLong("id")).put("publicId", r.getString("publicId")).put("source", r.getString("source")).put("userId", r.getLong("userId"))
        .put("playerUsername", r.getString("playerUsername")).put("recipientUsername", r.getString("recipientUsername")).put("isGift", flag(r, "isGift"))
        .put("email", FieldGating.email(r.getString("email"), pii))
        .put("totalPrice", money(r.getLong("totalPrice"))).put("currency", r.getString("currency")).put("paymentMethodId", r.getString("paymentMethodId"))
        .put("paymentLabel", r.getString("paymentLabel")).put("status", r.getString("status"))
        .put("gatewayAmount", money(r.getLong("gatewayAmount"))).put("creditValue", money(r.getLong("creditValue"))).put("refundedTotal", money(r.getLong("refundedTotal")))
        .put("fulfillmentStatus", r.getString("fulfillmentStatus")).put("shippingStatus", r.getString("shippingStatus")).put("paidAt", r.getLong("paidAt"))
        .put("testMode", flag(r, "testMode")).put("reviewReason", r.getString("reviewReason")).put("createdAt", r.getLong("createdAt")).put("updatedAt", r.getLong("updatedAt"))
        .put(
            "items",
            JsonArray(
                items.map {
                    JsonObject().put("id", it.id).put("productId", it.productId).put("productName", it.productName).put("variantName", it.variantName)
                        .put("quantity", it.quantity).put("unitPrice", money(it.unitPrice)).put("lineTotal", money(it.lineTotal)).put("createdAt", it.createdAt)
                        .put("updatedAt", it.updatedAt)
                }
            )
        )

    private fun flag(r: Row, column: String): Boolean = r.getBoolean(column) == true

    private fun money(minor: Long?): Double? = minor?.let { MoneyUtil.toDecimal(it) }

    /**
     * The WHERE of [list] and [export] over alias `o` (without the word WHERE). A search term is matched as a substring of the player, the recipient, the payment
     * label, the id, the `publicId` and an item's product name, as the gateway transaction id of any attempt of the order (exact), and, only with [pii], the e-mail:
     * no oracle through search (11 section 14.5). `%`, `_` and `\` of the term are escaped (11 section 6.5).
     */
    private fun whereOf(filter: OrderFilter, pii: Boolean, args: MutableList<Any?>): String {
        val where = ArrayList<String>().also { it += "1 = 1" }

        if (filter.statuses.isNotEmpty()) {
            where += "o.`status` IN (${filter.statuses.joinToString(",") { "?" }})"
            args.addAll(filter.statuses.map { it.name })
        }

        filter.paymentMethodId?.let {
            where += "o.`paymentMethodId` = ?"
            args += it
        }

        if (filter.fulfillmentStatuses.isNotEmpty()) {
            where += "o.`fulfillmentStatus` IN (${filter.fulfillmentStatuses.joinToString(",") { "?" }})"
            args.addAll(filter.fulfillmentStatuses.map { it.name })
        }

        if (filter.shippingStatuses.isNotEmpty()) {
            where += "o.`shippingStatus` IN (${filter.shippingStatuses.joinToString(",") { "?" }})"
            args.addAll(filter.shippingStatuses.map { it.name })
        }

        filter.from?.let {
            where += "o.`createdAt` >= ?"
            args += it
        }

        filter.to?.let {
            where += "o.`createdAt` <= ?"
            args += it
        }

        filter.testMode?.let {
            where += "o.`testMode` = ?"
            args += if (it) 1 else 0
        }

        if (filter.sources.isNotEmpty()) {
            where += "o.`source` IN (${filter.sources.joinToString(",") { "?" }})"
            args.addAll(filter.sources.map { it.name })
        }

        filter.search?.trim()?.takeIf { it.isNotEmpty() }?.let { term ->
            val like = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            val parts = arrayListOf(
                "o.`playerUsername` LIKE ?", "o.`recipientUsername` LIKE ?", "o.`paymentLabel` LIKE ?", "CAST(o.`id` AS CHAR) LIKE ?", "o.`publicId` LIKE ?"
            )

            repeat(parts.size) { args += like }

            if (pii) {
                parts += "o.`email` LIKE ?"
                args += like
            }

            parts += "EXISTS (SELECT 1 FROM ${table("market_order_item")} i WHERE i.`orderId` = o.`id` AND i.`productName` LIKE ?)"
            args += like
            parts += "EXISTS (SELECT 1 FROM ${table("market_payment")} p WHERE p.`orderId` = o.`id` AND p.`gatewayTransactionId` = ?)"
            args += term

            where += "(" + parts.joinToString(" OR ") + ")"
        }

        return where.joinToString(" AND ")
    }

    // ================================================================================================================ detail

    /** `GET /orders/:id`: `null` for an unknown id. */
    suspend fun detail(orderId: Long, viewer: OrderViewer, client: SqlClient): JsonObject? {
        val order = orders.getById(orderId, client) ?: return null
        val items = orderItems.getByOrderIds(listOf(orderId), client).sortedBy { it.id }
        val paymentRows = payments.getByOrderId(orderId, client).sortedBy { it.id }
        val refundRows = refunds.getByOrderId(orderId, client).sortedBy { it.id }
        val disputeRows = disputes.getByOrderId(orderId, client).sortedBy { it.id }
        val deliveryRows = deliveries.getByOrderId(orderId, client).sortedBy { it.id }
        val shipmentRows = shipments.getByOrderId(orderId, client).sortedBy { it.id }
        val eventRows = orderEvents.getByOrderId(orderId, client).sortedWith(compareByDescending<MarketOrderEvent> { it.createdAt }.thenByDescending { it.id })
        val invoiceRows = invoices.getByOrderId(orderId, client).sortedBy { it.id }
        val mailRows = mailOutbox.getByOrderId(orderId, client).sortedBy { it.id }
        val subscription = order.subscriptionId?.let { subscriptions.getById(it, client) }
        val names = serverNames(client)
        val legalVersion = order.legalTextId?.let { legalTexts.getById(it, client)?.version }
        val actors = HashMap<Long, String?>()

        suspend fun username(userId: Long?): String? = userId?.let { id -> actors.getOrPut(id) { users.usernameOf(id, client) } }

        val orderJson = orderJson(order, legalVersion, viewer.pii)
        val itemJson = items.map { itemJson(it, names) }

        val itemExpiry = HashMap<Long, Long?>()

        for (item in items) itemExpiry[item.id] = entitlements.getByOrderItemId(item.id, client).maxByOrNull { it.id }?.expiresAt

        val itemsWithExpiry = itemJson.mapIndexed { index, json -> json.put("expiresAt", itemExpiry[items[index].id]) }

        return JsonObject()
            .put("order", orderJson)
            .put("items", JsonArray(itemsWithExpiry))
            .put("payments", JsonArray(paymentRows.map { paymentJson(it) }))
            .put("refunds", JsonArray(refundRows.map { refundJson(it, username(it.initiatedBy)) }))
            .put("disputes", JsonArray(disputeRows.map { disputeJson(it) }))
            .put("deliveries", JsonArray(deliveryRows.map { deliveryJson(it, names) }))
            .put("shipments", JsonArray(shipmentRows.map { shipmentJson(it, viewer.pii) }))
            .put("events", JsonArray(eventRows.map { eventJson(it, username(it.actorUserId)) }))
            .put("invoices", JsonArray(invoiceRows.map { JsonObject().put("id", it.id).put("type", it.type.name).put("refundId", it.refundId).put("number", it.number).put("issuedAt", it.issuedAt) }))
            .put("mails", JsonArray(mailRows.map { mailJson(it, viewer.pii) }))
            .put("subscription", subscription?.let { subscriptionJson(it) })
            .put("revokePending", deliveryRows.count { it.phase in END_PHASES && it.status in OPEN_DELIVERY })
            .put("revokeFailed", deliveryRows.count { it.phase in END_PHASES && it.status == DeliveryStatus.FAILED })
            .put("allowed", OrderAllowed.compute(order, items, paymentRows, refundRows, deliveryRows, shipmentRows, viewer))
    }

    private fun orderJson(order: MarketOrder, legalVersion: Int?, pii: Boolean): JsonObject {
        val cfg = config()
        val effectiveRate = order.exchangeRate ?: when (order.currency) {
            cfg.statsCurrency.name -> 1.0
            cfg.currency.name -> cfg.exchangeRate
            else -> 1.0
        }
        val statsValue = Math.round(order.totalPrice / 100.0 * effectiveRate * 100.0) / 100.0

        // every column of 01 section 5.1 except the tokens and hashes (accessToken, idempotencyKey, idempotencyHash); money x100 is a decimal, basis points a percent
        val json = JsonObject()
            .put("id", order.id).put("publicId", order.publicId).put("userId", order.userId).put("playerUsername", order.playerUsername).put("source", order.source.name)
            .put("buyerKey", order.buyerKey).put("locale", order.locale).put("recipientUsername", order.recipientUsername).put("recipientUserId", order.recipientUserId)
            .put("recipientKey", order.recipientKey).put("isGift", order.isGift).put("hideFromBroadcast", order.hideFromBroadcast)
            .put("status", order.status.name).put("statusBeforeDispute", order.statusBeforeDispute?.name).put("disputeStatus", order.disputeStatus.name)
            .put("reviewReason", order.reviewReason).put("reservationState", order.reservationState.name).put("expiresAt", order.expiresAt)
            .put("currency", order.currency).put("baseCurrency", order.baseCurrency).put("fxRate", order.fxRate.toDouble()).put("displayCurrency", order.displayCurrency)
            .put("displayRate", order.displayRate?.toDouble()).put("pricingMode", order.pricingMode.name).put("pricesIncludeVat", order.pricesIncludeVat)
            .put("subtotal", money(order.subtotal)).put("discountTotal", money(order.discountTotal)).put("couponDiscount", money(order.couponDiscount))
            .put("creatorDiscount", money(order.creatorDiscount)).put("upgradeDiscount", money(order.upgradeDiscount)).put("shippingTotal", money(order.shippingTotal))
            .put("shippingVatPercent", order.shippingVatPercent / 100.0).put("shippingVatAmount", money(order.shippingVatAmount)).put("paymentFee", money(order.paymentFee))
            .put("paymentFeeVatPercent", order.paymentFeeVatPercent / 100.0).put("paymentFeeVatAmount", money(order.paymentFeeVatAmount)).put("vatTotal", money(order.vatTotal))
            .put("totalPrice", money(order.totalPrice)).put("creditAmount", money(order.creditAmount)).put("creditValue", money(order.creditValue))
            .put("gatewayAmount", money(order.gatewayAmount)).put("paidAmount", money(order.paidAmount)).put("refundedTotal", money(order.refundedTotal))
            .put("refundedGatewayAmount", money(order.refundedGatewayAmount)).put("refundedCreditAmount", money(order.refundedCreditAmount))
            .put("couponId", order.couponId).put("creatorCodeId", order.creatorCodeId).put("giftId", order.giftId).put("couponCode", order.couponCode)
            .put("creatorCode", order.creatorCode).put("paymentId", order.paymentId).put("paymentMethodId", order.paymentMethodId).put("paymentLabel", order.paymentLabel)
            .put("paidAt", order.paidAt).put("testMode", order.testMode).put("fulfillmentStatus", order.fulfillmentStatus.name).put("fulfillmentBy", order.fulfillmentBy.name)
            .put("requiresShipping", order.requiresShipping).put("shippingStatus", order.shippingStatus.name).put("shippingMethodId", order.shippingMethodId)
            .put("shippingMethodName", order.shippingMethodName).put("shippingQuote", FieldGating.jsonObject(order.shippingQuote)).put("shippingWeightGrams", order.shippingWeightGrams)
            .put("legalTextId", order.legalTextId).put("legalTextVersion", legalVersion).put("legalAcceptedAt", order.legalAcceptedAt)
            .put("subscriptionId", order.subscriptionId).put("invoiceId", order.invoiceId).put("note", order.note).put("createdBy", order.createdBy)
            .put("createdAt", order.createdAt).put("updatedAt", order.updatedAt)
            .put("exchangeRate", order.exchangeRate).put("statsValue", statsValue).put("statsCurrency", cfg.statsCurrency.name).put("statsCurrencySymbol", cfg.statsCurrency.symbol)

        for ((key, value) in FieldGating.orderPii(order, pii)) json.put(key, value)

        return json
    }

    private fun itemJson(item: MarketOrderItem, names: Map<Long, String>): JsonObject = JsonObject()
        .put("id", item.id).put("parentItemId", item.parentItemId).put("kind", item.kind.name).put("productId", item.productId).put("productName", item.productName)
        .put("variantId", item.variantId).put("variantName", item.variantName).put("sku", item.sku).put("quantity", item.quantity)
        .put("unitPrice", money(item.unitPrice)).put("listUnitPrice", money(item.listUnitPrice)).put("discountAmount", money(item.discountAmount))
        .put("upgradeAmount", money(item.upgradeAmount)).put("couponAmount", money(item.couponAmount)).put("vatPercent", item.vatPercent / 100.0)
        .put("vatAmount", money(item.vatAmount)).put("lineTotal", money(item.lineTotal)).put("creditUnitPrice", money(item.creditUnitPrice))
        .put("creditAmount", money(item.creditAmount)).put("fieldValues", FieldGating.jsonObject(item.fieldValues)).put("targetServerId", item.targetServerId)
        .put("targetServerName", item.targetServerId?.let { names[it] }).put("physical", item.physical).put("refundedQuantity", item.refundedQuantity)
        .put("refundedAmount", money(item.refundedAmount)).put("shippedQuantity", item.shippedQuantity).put("snapshot", snapshotSummary(item.snapshot))
        .put("createdAt", item.createdAt).put("updatedAt", item.updatedAt)

    /** The product as sold, without its actions (commands and webhook targets are not a thing the order page shows): `{slug, imageFileName, kind, billingMode, periodUnit, periodCount, physical, weightGrams, variantAttributes, actionCount}`. */
    private fun snapshotSummary(raw: String?): JsonObject? {
        val snapshot = FieldGating.jsonObject(raw) ?: return null
        val out = JsonObject()

        for (key in listOf("slug", "imageFileName", "kind", "billingMode", "periodUnit", "periodCount", "physical", "weightGrams", "variantAttributes")) out.put(key, snapshot.getValue(key))

        return out.put("actionCount", snapshot.getJsonArray("actions")?.size() ?: 0)
    }

    private fun paymentJson(p: MarketPayment): JsonObject = JsonObject()
        .put("id", p.id).put("providerId", p.providerId).put("status", p.status.name).put("amount", money(p.amount)).put("creditAmount", money(p.creditAmount))
        .put("paidAmount", money(p.paidAmount)).put("gatewayTransactionId", p.gatewayTransactionId).put("testMode", p.testMode).put("duplicate", p.duplicate)
        .put("failureMessage", p.failureMessage).put("adminMessage", p.adminMessage).put("createdAt", p.createdAt).put("paidAt", p.paidAt)

    private fun refundJson(r: MarketRefund, initiatedBy: String?): JsonObject = JsonObject()
        .put("id", r.id).put("status", r.status.name).put("origin", r.origin.name).put("amount", money(r.amount)).put("gatewayAmount", money(r.gatewayAmount))
        .put("creditAmount", money(r.creditAmount)).put("creditValue", money(r.creditValue)).put("currency", r.currency).put("reason", r.reason).put("revoke", r.revoke)
        .put("revokeFirst", r.revokeFirst).put("buyerActionUrl", r.buyerActionUrl).put("failureMessage", r.failureMessage).put("initiatedByUsername", initiatedBy)
        .put("createdAt", r.createdAt).put("completedAt", r.completedAt)

    private fun disputeJson(d: com.panomc.plugins.market.db.model.MarketDispute): JsonObject = JsonObject()
        .put("id", d.id).put("status", d.status.name).put("origin", d.origin.name).put("amount", money(d.amount)).put("currency", d.currency).put("reason", d.reason)
        .put("openedAt", d.openedAt).put("resolvedAt", d.resolvedAt)

    /** The row of `GET /deliveries` plus `orderItemId` (04 section 7); the payload never carries a webhook secret. */
    private fun deliveryJson(d: MarketDelivery, names: Map<Long, String>): JsonObject {
        val item = d.orderItemId

        return JsonObject()
            .put("id", d.id).put("orderId", d.orderId).put("orderItemId", item).put("playerUsername", d.playerUsername).put("phase", d.phase.name)
            .put("actionId", d.actionId).put("actionType", d.actionType.name).put("transport", d.transport?.name).put("idempotencyKey", d.idempotencyKey)
            .put("serverId", d.serverId).put("serverName", names[d.serverId]).put("status", d.status.name).put("attempts", d.attempts)
            .put("requiresOnline", d.requiresOnline).put("waitUntil", d.waitUntil).put("cancelRequested", d.cancelRequestedAt != null).put("lastErrorCode", d.lastErrorCode)
            .put("lastError", d.lastError).put("runAfter", d.runAfter).put("sentAt", d.sentAt).put("confirmedAt", d.confirmedAt)
            .put("payload", DeliveryAdminService.payloadView(d.payload)).put("result", jsonOrText(d.result))
    }

    private fun jsonOrText(text: String?): Any? {
        if (text == null) return null

        return runCatching { JsonObject(text) }.getOrNull() ?: runCatching { JsonArray(text) }.getOrNull() ?: text
    }

    private fun shipmentJson(s: MarketShipment, pii: Boolean): JsonObject {
        val to = FieldGating.jsonObject(s.toAddress)
        val slim = if (to == null) null else JsonObject().put("firstName", to.getValue("firstName")).put("lastName", to.getValue("lastName")).put("city", to.getValue("city")).put("country", to.getValue("country"))

        return JsonObject()
            .put("id", s.id).put("orderId", s.orderId).put("status", s.status.name).put("entryMode", s.entryMode.name).put("providerId", s.providerId)
            .put("carrierName", s.carrierName).put("serviceCode", s.serviceCode).put("trackingNumber", s.trackingNumber).put("trackingUrl", s.trackingUrl)
            .put("labelFile", FieldGating.piiOnly(s.labelFile, pii)).put("cost", money(s.cost)).put("costCurrency", s.costCurrency).put("toAddress", FieldGating.piiOnly(slim, pii))
            .put("stale", s.stale).put("lastErrorCode", s.lastErrorCode).put("lastError", s.lastError).put("note", s.note)
            .put("estimatedDeliveryAt", s.estimatedDeliveryAt).put("shippedAt", s.shippedAt).put("deliveredAt", s.deliveredAt).put("createdAt", s.createdAt)
    }

    private fun eventJson(e: MarketOrderEvent, actor: String?): JsonObject = JsonObject()
        .put("id", e.id).put("type", e.type.name).put("fromStatus", e.fromStatus).put("toStatus", e.toStatus).put("actorType", e.actorType.name)
        .put("actorUsername", actor).put("message", e.message).put("createdAt", e.createdAt)

    /** The recipient is masked below the PII tier, and so is the address inside an error text (a mail server may echo the address it refused). */
    private fun mailJson(m: MarketMailOutbox, pii: Boolean): JsonObject {
        val masked = PiiMask.email(m.recipient)

        return JsonObject()
            .put("id", m.id).put("kind", m.kind.name).put("recipient", if (pii) m.recipient else masked).put("status", m.status.name).put("attempts", m.attempts)
            .put("lastError", if (pii || m.lastError == null || m.recipient.isEmpty()) m.lastError else m.lastError.replace(m.recipient, masked ?: "***", ignoreCase = true))
            .put("createdAt", m.createdAt).put("sentAt", m.sentAt)
    }

    private fun subscriptionJson(s: MarketSubscription): JsonObject = JsonObject()
        .put("id", s.id).put("playerUsername", s.playerUsername).put("productName", s.productName).put("status", s.status.name).put("mode", s.mode.name)
        .put("providerId", s.providerId).put("price", money(s.price)).put("currency", s.currency).put("intervalUnit", s.intervalUnit.name)
        .put("intervalCount", s.intervalCount).put("cycleCount", s.cycleCount).put("maxCycles", s.maxCycles).put("currentPeriodStart", s.currentPeriodStart)
        .put("currentPeriodEnd", s.currentPeriodEnd).put("nextChargeAt", s.nextChargeAt).put("graceEndsAt", s.graceEndsAt).put("cancelAtPeriodEnd", s.cancelAtPeriodEnd)
        .put("failCount", s.failCount).put("endReason", s.endReason).put("remoteCancelState", s.remoteCancelState.name).put("gatewaySubscriptionId", s.gatewaySubscriptionId)
        .put("storedMethodLabel", s.storedMethodLabel).put("testMode", s.testMode).put("createdAt", s.createdAt)

    // ================================================================================================================ note

    /**
     * `PUT /orders/:id/note`: the admin note of the order (at most [NOTE_MAX] characters after trimming; blank clears it). The order timeline gets a `NOTE` row
     * without the text, so the change is visible and the text lives in one place. [NotFound] for an unknown order.
     */
    suspend fun setNote(orderId: Long, rawNote: String?, actorUserId: Long?, client: SqlClient) {
        val note = rawNote?.trim()?.takeIf { it.isNotEmpty() }

        if (note != null && note.length > NOTE_MAX) throw RequestValueException("note", "TOO_LONG")

        orders.getById(orderId, client) ?: throw NotFound()

        val now = clock.now()

        client.preparedQuery("UPDATE ${table("market_order")} SET `note` = ?, `updatedAt` = ? WHERE `id` = ?").execute(Tuple.of(note, now, orderId)).coAwait()

        orderEvents.add(
            MarketOrderEvent(
                orderId = orderId, type = OrderEventType.NOTE, actorType = OrderActorType.ADMIN, actorUserId = actorUserId,
                data = JsonObject().put("cleared", note == null).encode(), createdAt = now, updatedAt = now
            ),
            client
        )
    }

    // ================================================================================================================ export

    /**
     * `GET /orders/export`: the CSV of the filtered orders, one row per order item, written through [sink] in pieces (the byte order mark first). At most
     * [CsvWriter.MAX_ROWS] rows; [begin] is told before the first byte whether the cap will cut the export (a route announces it in a header), [OrderExportResult.truncated] repeats it. [columns] comes from [OrderExportColumns.parse], which has already refused
     * the PII columns below the tier; they are checked here again so no caller can reach the data around that rule.
     */
    suspend fun export(
        filter: OrderFilter, columns: List<String>, delimiter: CsvWriter.Delimiter, pii: Boolean, client: SqlClient, begin: suspend (truncated: Boolean) -> Unit = {},
        sink: suspend (String) -> Unit
    ): OrderExportResult {
        FieldGating.requireExportColumns(columns, pii)

        val unknown = columns.firstOrNull { it !in OrderExportColumns.ALL }

        if (unknown != null) throw RequestValueException("columns", "UNKNOWN_VALUE")

        val writer = CsvWriter(delimiter)
        val args = ArrayList<Any?>()
        val where = whereOf(filter, pii, args)
        val from = "FROM ${table("market_order")} o LEFT JOIN ${table("market_order_item")} i ON i.`orderId` = o.`id` LEFT JOIN ${table("market_payment")} p ON p.`id` = o.`paymentId`"
        val total = client.preparedQuery("SELECT COUNT(*) AS c $from WHERE $where").execute(Tuple.from(args)).coAwait().first().getLong("c")
        val cap = CsvWriter.MAX_ROWS.toLong()
        val selected = columns.toSet()
        val needAddress = "country" in selected

        begin(total > cap)
        sink(writer.bom())
        sink(writer.header(columns))

        var written = 0
        var lastOrder = Long.MAX_VALUE
        var lastItem = -1L

        while (written < cap) {
            val batch = minOf(BATCH.toLong(), cap - written).toInt()
            val batchArgs = ArrayList<Any?>(args).also {
                it += lastOrder
                it += lastOrder
                it += lastItem
                it += batch.toLong()
            }
            val rows = client.preparedQuery(
                "SELECT o.`id` AS oid, o.`publicId`, o.`createdAt`, o.`paidAt`, o.`status`, o.`source`, o.`playerUsername`, o.`recipientUsername`, o.`email`, o.`currency`, " +
                    "o.`totalPrice`, o.`couponCode`, o.`creatorCode`, o.`paymentLabel`, o.`gatewayAmount`, o.`creditValue`, o.`refundedTotal`, o.`fulfillmentStatus`, " +
                    "o.`shippingStatus`, o.`testMode`, " + (if (needAddress) "o.`shippingAddress`, o.`billingInfo`, " else "") +
                    "p.`gatewayTransactionId`, COALESCE(i.`id`, 0) AS iid, i.`productName`, i.`variantName`, i.`sku`, i.`quantity`, i.`unitPrice`, i.`lineTotal` " +
                    "$from WHERE $where AND (o.`id` < ? OR (o.`id` = ? AND COALESCE(i.`id`, 0) > ?)) ORDER BY o.`id` DESC, COALESCE(i.`id`, 0) ASC LIMIT ?"
            ).execute(Tuple.from(batchArgs)).coAwait().toList()

            if (rows.isEmpty()) break

            val text = StringBuilder()

            for (row in rows) {
                text.append(writer.row(columns.map { cell(it, row) }))
                lastOrder = row.getLong("oid")
                lastItem = row.getLong("iid")
            }

            written += rows.size
            sink(text.toString())

            if (rows.size < batch) break
        }

        return OrderExportResult(written, total > cap)
    }

    private fun cell(column: String, r: Row): CsvValue = when (column) {
        "orderId" -> CsvValue.number(r.getLong("oid"))
        "publicId" -> CsvValue.text(r.getString("publicId"))
        "createdAt" -> CsvValue.text(instant(r.getLong("createdAt")))
        "paidAt" -> CsvValue.text(instant(r.getLong("paidAt")))
        "status" -> CsvValue.text(r.getString("status"))
        "source" -> CsvValue.text(r.getString("source"))
        "playerUsername" -> CsvValue.text(r.getString("playerUsername"))
        "recipientUsername" -> CsvValue.text(r.getString("recipientUsername"))
        "email" -> CsvValue.text(r.getString("email"))
        "productName" -> CsvValue.text(r.getString("productName"))
        "variantName" -> CsvValue.text(r.getString("variantName"))
        "sku" -> CsvValue.text(r.getString("sku"))
        "quantity" -> CsvValue.number(r.getInteger("quantity"))
        "unitPrice" -> CsvValue.money(r.getLong("unitPrice"))
        "lineTotal" -> CsvValue.money(r.getLong("lineTotal"))
        "currency" -> CsvValue.text(r.getString("currency"))
        "orderTotal" -> CsvValue.money(r.getLong("totalPrice"))
        "couponCode" -> CsvValue.text(r.getString("couponCode"))
        "creatorCode" -> CsvValue.text(r.getString("creatorCode"))
        "paymentMethod" -> CsvValue.text(r.getString("paymentLabel"))
        "gatewayTransactionId" -> CsvValue.text(r.getString("gatewayTransactionId"))
        "gatewayAmount" -> CsvValue.money(r.getLong("gatewayAmount"))
        "creditValue" -> CsvValue.money(r.getLong("creditValue"))
        "refundedTotal" -> CsvValue.money(r.getLong("refundedTotal"))
        "fulfillmentStatus" -> CsvValue.text(r.getString("fulfillmentStatus"))
        "shippingStatus" -> CsvValue.text(r.getString("shippingStatus"))
        "country" -> CsvValue.text(countryOf(r.getString("shippingAddress")) ?: countryOf(r.getString("billingInfo")))
        "testMode" -> CsvValue.bool(r.getBoolean("testMode") == true)
        else -> throw RequestValueException("columns", "UNKNOWN_VALUE")
    }

    private fun instant(ms: Long?): String? = ms?.let { Instant.ofEpochMilli(it).toString() }

    private fun countryOf(raw: String?): String? = FieldGating.jsonObject(raw)?.getValue("country")?.toString()?.takeIf { it.isNotBlank() }

    companion object {
        /** `maxlength` of the note textarea (13 section 6.1). */
        const val NOTE_MAX = 2000

        private const val BATCH = 1000

        private val END_PHASES = setOf(DeliveryPhase.REVOKE, DeliveryPhase.EXPIRE)
        private val OPEN_DELIVERY = setOf(
            DeliveryStatus.PENDING, DeliveryStatus.SCHEDULED, DeliveryStatus.WAITING_SERVER, DeliveryStatus.WAITING_PLAYER, DeliveryStatus.SENDING, DeliveryStatus.SENT, DeliveryStatus.QUEUED
        )
    }
}

/**
 * The `allowed{}` block of `GET /orders/:id` (04 section 7, 13 section 6.2): what the caller can do with this order right now. A flag is true only when the state
 * of the order allows the action (the same rule the action's service enforces) and the caller holds the node that action needs (`PAY` for money, `OM` for
 * fulfilment); so a button needs nothing but its flag. Pure: no clock, no database.
 */
object OrderAllowed {
    private val PAID = setOf(OrderStatus.COMPLETED, OrderStatus.PARTIALLY_REFUNDED)
    private val DISPUTABLE = setOf(OrderStatus.COMPLETED, OrderStatus.PARTIALLY_REFUNDED, OrderStatus.REFUNDED)
    private val RELEASED = setOf(OrderStatus.EXPIRED, OrderStatus.CANCELLED, OrderStatus.FAILED)
    private const val BANK_TRANSFER = "bank-transfer"

    fun compute(
        order: MarketOrder,
        items: List<MarketOrderItem>,
        payments: List<MarketPayment>,
        refunds: List<MarketRefund>,
        deliveries: List<MarketDelivery>,
        shipments: List<MarketShipment>,
        viewer: OrderViewer
    ): JsonObject {
        val status = order.status
        val refund = viewer.pay && refundable(order, refunds)
        val remaining = if (refund) remaining(order, refunds) else 0L

        return JsonObject()
            .put("markPaid", viewer.pay && status == OrderStatus.PENDING)
            .put("cancel", viewer.pay && status == OrderStatus.PENDING)
            .put("refund", refund)
            .put("refundMax", MoneyUtil.toDecimal(remaining))
            .put("refundModes", JsonArray(if (refund) refundModes(items) else emptyList<String>()))
            .put("review", viewer.pay && status == OrderStatus.REVIEW)
            .put("bankTransfer", viewer.pay && bankTransfer(order, payments))
            .put("dispute", viewer.pay && status in DISPUTABLE)
            .put("rerunDelivery", viewer.manage && status in PAID && rerunnable(deliveries, viewer.pay))
            .put("revoke", viewer.manage && order.paidAt != null && revocable(deliveries))
            .put("createShipment", viewer.manage && shippable(order, items))
            .put("editShippingAddress", viewer.manage && order.requiresShipping && status !in RELEASED && status != OrderStatus.REFUNDED && shipments.none { !it.itemsReleased })
            .put("resendMail", viewer.manage && (order.paidAt != null || status == OrderStatus.PENDING || status == OrderStatus.REVIEW))
            .put("anonymize", viewer.pay && status != OrderStatus.PENDING && status != OrderStatus.REVIEW)
            .put("runChargebackActions", viewer.pay && heldChargebackActions(deliveries))
    }

    private fun amounts(order: MarketOrder) = RefundMath.OrderAmounts(
        totalPrice = order.totalPrice, gatewayAmount = order.gatewayAmount, creditValue = order.creditValue, creditAmount = order.creditAmount,
        refundedTotal = order.refundedTotal, refundedGatewayAmount = order.refundedGatewayAmount, refundedCreditAmount = order.refundedCreditAmount,
        unit = CreditMath.unit(order.currency), anonymised = order.userId == null
    )

    /** What a refund can still take: the order's remainder less the refunds in flight; a refund of another attempt's money is not on this order's books (21 section 2). */
    private fun remaining(order: MarketOrder, refunds: List<MarketRefund>): Long {
        val books = refunds.filter { !(it.paymentId != null && it.paymentId != order.paymentId) }.map { RefundMath.RefundAmounts(it.id, it.status, it.amount, it.gatewayAmount, it.creditAmount) }

        return RefundMath.remaining(amounts(order), books).total
    }

    /** `COMPLETED` or `PARTIALLY_REFUNDED` with something left to give back (money, or credits of an order that carried no money). */
    private fun refundable(order: MarketOrder, refunds: List<MarketRefund>): Boolean {
        if (order.status !in PAID) return false

        val books = refunds.filter { !(it.paymentId != null && it.paymentId != order.paymentId) }.map { RefundMath.RefundAmounts(it.id, it.status, it.amount, it.gatewayAmount, it.creditAmount) }
        val left = RefundMath.remaining(amounts(order), books)

        return left.total > 0L || (order.totalPrice == 0L && left.credit > 0L)
    }

    private fun refundModes(items: List<MarketOrderItem>): List<String> = buildList {
        add("FULL")
        add("PARTIAL")

        if (items.any { it.kind != OrderItemKind.CREDIT_TOPUP && it.quantity > it.refundedQuantity && it.lineTotal > it.refundedAmount }) add("PER_LINE")

        add("MANUAL")
    }

    /** 06 section 11 / `BankTransferService.decide`: a decision applies to a `PENDING` order with an open bank-transfer attempt, or (approve only) to a released order whose money arrived late. */
    private fun bankTransfer(order: MarketOrder, payments: List<MarketPayment>): Boolean {
        val attempt = payments.lastOrNull { it.providerId == BANK_TRANSFER } ?: return false
        val open = attempt.status == PaymentStatus.PENDING || attempt.status == PaymentStatus.PROCESSING

        return when (order.status) {
            OrderStatus.PENDING -> open
            in RELEASED -> attempt.status != PaymentStatus.SUCCEEDED
            else -> false
        }
    }

    /** `DeliveryAdminService.rerun`: a row that failed or was cancelled can run again with `OM`; one that is confirmed (its effect may repeat) needs `PAY` as well. */
    private fun rerunnable(rows: List<MarketDelivery>, pay: Boolean): Boolean =
        rows.filter { it.sourceType == DeliverySourceType.ORDER_ITEM && it.phase == DeliveryPhase.GRANT }.any {
            it.status == DeliveryStatus.FAILED || it.status == DeliveryStatus.CANCELLED || (pay && it.status == DeliveryStatus.CONFIRMED)
        }

    /** `DeliveryAdminService.revoke`: a unit that was granted and has no live `REVOKE` row yet. */
    private fun revocable(rows: List<MarketDelivery>): Boolean {
        val revoked = rows.filter { it.phase == DeliveryPhase.REVOKE && it.status != DeliveryStatus.CANCELLED }.map { Triple(it.orderItemId, it.actionId, it.serverId) to it.unitIndex }.toSet()

        return rows.any {
            it.sourceType == DeliverySourceType.ORDER_ITEM && it.phase == DeliveryPhase.GRANT && it.status == DeliveryStatus.CONFIRMED &&
                (Triple(it.orderItemId, it.actionId, it.serverId) to it.unitIndex) !in revoked
        }
    }

    /** `ShippingService.shippableOrThrow` and the shippable units: a paid shipping order with an address, no open dispute and a physical line with units left. */
    private fun shippable(order: MarketOrder, items: List<MarketOrderItem>): Boolean =
        order.status in PAID && order.requiresShipping && order.shippingAddress != null && order.disputeStatus != DisputeStatus.OPEN &&
            items.any { it.physical && it.kind != OrderItemKind.BUNDLE && it.quantity - it.refundedQuantity - it.shippedQuantity > 0 }

    /** `DisputeService.runChargebackActions`: a chargeback action row that was held `NEEDS_CONFIRMATION` and nothing replaced since. */
    private fun heldChargebackActions(rows: List<MarketDelivery>): Boolean =
        rows.filter { it.sourceType == DeliverySourceType.CHARGEBACK_ACTION && it.sourceId != null }.groupBy { it.sourceId }.values.any { ofDispute ->
            ofDispute.groupBy { Triple(it.actionId, it.serverId, it.unitIndex) }.values.map { group -> group.maxByOrNull { it.attemptGroup }!! }
                .any { it.status == DeliveryStatus.CANCELLED && it.lastErrorCode == DeliveryError.NEEDS_CONFIRMATION }
        }
}
