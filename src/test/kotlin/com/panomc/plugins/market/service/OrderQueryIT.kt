package com.panomc.plugins.market.service

import com.panomc.platform.model.PageRequest
import com.panomc.platform.model.Paging
import com.panomc.platform.error.NoPermission
import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.impl.MarketDeliveryDaoImpl
import com.panomc.plugins.market.db.impl.MarketDisputeDaoImpl
import com.panomc.plugins.market.db.impl.MarketEntitlementDaoImpl
import com.panomc.plugins.market.db.impl.MarketInvoiceDaoImpl
import com.panomc.plugins.market.db.impl.MarketLegalTextDaoImpl
import com.panomc.plugins.market.db.impl.MarketMailOutboxDaoImpl
import com.panomc.plugins.market.db.impl.MarketOrderDaoImpl
import com.panomc.plugins.market.db.impl.MarketOrderEventDaoImpl
import com.panomc.plugins.market.db.impl.MarketOrderItemDaoImpl
import com.panomc.plugins.market.db.impl.MarketPaymentDaoImpl
import com.panomc.plugins.market.db.impl.MarketRefundDaoImpl
import com.panomc.plugins.market.db.impl.MarketShipmentDaoImpl
import com.panomc.plugins.market.db.impl.MarketSubscriptionDaoImpl
import com.panomc.plugins.market.db.model.DeliveryActionType
import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.db.model.DeliveryStatus
import com.panomc.plugins.market.db.model.DisputeOrigin
import com.panomc.plugins.market.db.model.DisputeRecordStatus
import com.panomc.plugins.market.db.model.EntitlementStatus
import com.panomc.plugins.market.db.model.FulfillmentStatus
import com.panomc.plugins.market.db.model.InvoiceType
import com.panomc.plugins.market.db.model.MailKind
import com.panomc.plugins.market.db.model.MailRefType
import com.panomc.plugins.market.db.model.MailStatus
import com.panomc.plugins.market.db.model.MarketDelivery
import com.panomc.plugins.market.db.model.MarketDispute
import com.panomc.plugins.market.db.model.MarketEntitlement
import com.panomc.plugins.market.db.model.MarketInvoice
import com.panomc.plugins.market.db.model.MarketLegalText
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
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.db.model.RefundOrigin
import com.panomc.plugins.market.db.model.RefundStatus
import com.panomc.plugins.market.db.model.ShipmentStatus
import com.panomc.plugins.market.db.model.ShippingStatus
import com.panomc.plugins.market.db.model.SubscriptionMode
import com.panomc.plugins.market.db.model.SubscriptionStatus
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.service.platform.DirectoryUser
import com.panomc.plugins.market.service.platform.UserDirectory
import com.panomc.plugins.market.util.CsvWriter
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The panel's order reads on a real MariaDB (MK-170; 04 section 7, 11 sections 6.5 and 14.5, 13 sections 5 and 6): every filter of `GET /orders`, the search by `publicId`
 * and (below the PII tier never) the e-mail, the detail with its eight blocks in the pinned shapes and the `allowed{}` flags, the note, and the CSV export (BOM, one row per
 * item, delimiter, the cap, formula-safe cells, the 403 for a PII column). The rows are written raw: this tier tests the projection and the queries, the services that
 * write these rows have their own tests.
 */
class OrderQueryIT : MarketDaoITBase() {
    override suspend fun assertInvariants() {}

    private val orders = MarketOrderDaoImpl()
    private val items = MarketOrderItemDaoImpl()
    private val payments = MarketPaymentDaoImpl()
    private val refunds = MarketRefundDaoImpl()
    private val disputes = MarketDisputeDaoImpl()
    private val deliveries = MarketDeliveryDaoImpl()
    private val shipments = MarketShipmentDaoImpl()
    private val events = MarketOrderEventDaoImpl()
    private val invoices = MarketInvoiceDaoImpl()
    private val mails = MarketMailOutboxDaoImpl()
    private val subscriptions = MarketSubscriptionDaoImpl()
    private val entitlements = MarketEntitlementDaoImpl()
    private val legalTexts = MarketLegalTextDaoImpl()

    private val directory = object : UserDirectory {
        override suspend fun byUsername(username: String, sqlClient: SqlClient): DirectoryUser? = null

        override suspend fun usernameOf(userId: Long, sqlClient: SqlClient): String? = if (userId == 99L) "Admin" else null

        override suspend fun emailOf(userId: Long, sqlClient: SqlClient): String? = null

        override suspend fun hasPermission(userId: Long, node: String): Boolean = false
    }

    private val clock = object : Clock { override fun now() = 5_000L }

    private val service = OrderQueryService(
        orders, items, payments, refunds, disputes, deliveries, shipments, events, invoices, mails, subscriptions, entitlements, legalTexts, directory,
        { MarketConfig(currency = "EUR", statsCurrency = "EUR") }, clock, { mapOf(2L to "Survival") }
    )

    private val viewOnly = OrderViewer.NOBODY
    private val manage = OrderViewer.of(manage = true, pay = false)
    private val pay = OrderViewer.of(manage = false, pay = true)
    private val both = OrderViewer.of(manage = true, pay = true)
    private val firstPage = PageRequest(1, 50)
    private var sequence = 0

    private suspend fun order(
        player: String = "Steve", status: OrderStatus = OrderStatus.COMPLETED, source: OrderSource = OrderSource.STOREFRONT, email: String? = "john@example.com", method: String = "stripe",
        label: String = "Card", testMode: Boolean = false, fulfillment: FulfillmentStatus = FulfillmentStatus.NONE, shipping: ShippingStatus = ShippingStatus.NOT_REQUIRED,
        createdAt: Long = 1_000, total: Long = 2_500, recipient: String = "", gift: Boolean = false, shippingAddress: String? = null, billing: String? = null,
        coupon: String? = null, paymentId: Long? = null, legalTextId: Long? = null, subscriptionId: Long? = null, note: String? = null, requiresShipping: Boolean? = null,
        creatorCode: String? = null
    ): MarketOrder {
        val n = ++sequence

        val id = orders.add(
            MarketOrder(
                playerUsername = player, totalPrice = total, currency = "EUR", paymentMethodId = method, paymentLabel = label, status = status, publicId = "QUERYIT" + n.toString().padStart(13, '0'),
                accessToken = "tok-$n-secret", idempotencyKey = "idem-$n-secret", idempotencyHash = "hash-$n-secret", source = source, buyerKey = "g:${player.lowercase()}$n", email = email,
                recipientUsername = recipient, isGift = gift, testMode = testMode, fulfillmentStatus = fulfillment, shippingStatus = shipping, createdAt = createdAt + n, updatedAt = createdAt + n,
                gatewayAmount = total, paidAmount = total, paidAt = if (status == OrderStatus.COMPLETED) createdAt + n else null, clientIp = "203.0.113.57", userAgent = "JUnit/1",
                giftMessage = if (gift) "enjoy" else null, shippingAddress = shippingAddress, billingInfo = billing, couponCode = coupon, requiresShipping = requiresShipping ?: (shippingAddress != null),
                paymentId = paymentId, userId = 5, baseCurrency = "EUR", legalTextId = legalTextId, legalAcceptedAt = legalTextId?.let { 77L }, subscriptionId = subscriptionId, note = note,
                creatorCode = creatorCode
            ),
            pool
        )

        return orders.getById(id, pool)!!
    }

    private suspend fun item(order: MarketOrder, name: String = "VIP", variant: String? = null, quantity: Int = 1, unit: Long = 2_500, sku: String? = null, physical: Boolean = false, fieldValues: String? = null): Long =
        items.add(
            MarketOrderItem(
                orderId = order.id, productId = 7, productName = name, variantName = variant, sku = sku, quantity = quantity, unitPrice = unit, listUnitPrice = unit,
                lineTotal = unit * quantity, physical = physical, fieldValues = fieldValues, snapshot = "{\"slug\":\"vip\",\"kind\":\"PRODUCT\",\"actions\":[{\"id\":\"a\",\"type\":\"COMMAND\"}]}",
                createdAt = order.createdAt, updatedAt = order.createdAt
            ),
            pool
        )

    private suspend fun listIds(filter: OrderFilter = OrderFilter(), viewer: OrderViewer = both, window: PageRequest = firstPage): List<Long> =
        service.list(filter, window, viewer.pii, pool).rows.map { it.getLong("id") }

    private suspend fun export(filter: OrderFilter = OrderFilter(), columns: List<String> = OrderExportColumns.ALL, delimiter: CsvWriter.Delimiter = CsvWriter.Delimiter.COMMA, viewer: OrderViewer = both): Pair<String, OrderExportResult> {
        val out = StringBuilder()
        val result = service.export(filter, columns, delimiter, viewer.pii, pool) { out.append(it) }

        return out.toString() to result
    }

    // ================================================================================================================ list

    @Test
    fun `the list is newest first and carries the row shape of 04 section 7`(): Unit = runBlocking {
        val old = order(player = "Alex", createdAt = 1_000, recipient = "Notch", gift = true, label = "Bank", method = "bank-transfer")
        val recent = order(player = "Steve", createdAt = 9_000, testMode = true, status = OrderStatus.REVIEW)

        item(old, name = "VIP", variant = "Gold", quantity = 2, unit = 1_000)
        item(recent, name = "Coins")

        val page = service.list(OrderFilter(), firstPage, pii = true, client = pool)

        assertEquals(2L, page.count)
        assertEquals(listOf(recent.id, old.id), page.rows.map { it.getLong("id") })

        val row = page.rows[1]

        for (key in listOf(
            "id", "publicId", "source", "recipientUsername", "isGift", "email", "gatewayAmount", "creditValue", "refundedTotal", "fulfillmentStatus", "shippingStatus", "paidAt", "testMode",
            "reviewReason", "totalPrice", "currency", "paymentMethodId", "paymentLabel", "status", "playerUsername", "createdAt", "updatedAt", "items"
        )) assertTrue(row.containsKey(key), key)

        assertEquals("Notch", row.getString("recipientUsername"))
        assertTrue(row.getBoolean("isGift"))
        assertEquals(25.0, row.getDouble("totalPrice"))
        assertEquals(25.0, row.getDouble("gatewayAmount"))
        assertEquals(0.0, row.getDouble("refundedTotal"))
        assertTrue(page.rows[0].getBoolean("testMode"))

        val line = row.getJsonArray("items").getJsonObject(0)

        assertEquals("Gold", line.getString("variantName"))
        assertEquals(10.0, line.getDouble("unitPrice"))
        assertEquals(20.0, line.getDouble("lineTotal"))
        assertEquals(2, line.getInteger("quantity"))
        assertFalse(row.encode().contains("secret"), "no token or hash in a row")
    }

    @Test
    fun `each filter narrows the list on its own`(): Unit = runBlocking {
        val completed = order(status = OrderStatus.COMPLETED, source = OrderSource.STOREFRONT, method = "stripe", fulfillment = FulfillmentStatus.FULFILLED, shipping = ShippingStatus.SHIPPED, createdAt = 1_000)
        val refunded = order(status = OrderStatus.REFUNDED, source = OrderSource.PANEL, method = "bank-transfer", fulfillment = FulfillmentStatus.FAILED, shipping = ShippingStatus.PENDING, createdAt = 2_000, testMode = true)
        val pending = order(status = OrderStatus.PENDING, source = OrderSource.GIFT_CODE, method = "stripe", fulfillment = FulfillmentStatus.PARTIAL, shipping = ShippingStatus.NOT_REQUIRED, createdAt = 3_000)

        suspend fun ids(filter: OrderFilter) = listIds(filter).toSet()

        assertEquals(setOf(completed.id, refunded.id, pending.id), ids(OrderFilter()))
        assertEquals(setOf(completed.id), ids(OrderFilter(statuses = setOf(OrderStatus.COMPLETED))))
        assertEquals(setOf(completed.id, pending.id), ids(OrderFilter(statuses = setOf(OrderStatus.COMPLETED, OrderStatus.PENDING))))
        assertEquals(setOf(completed.id, pending.id), ids(OrderFilter(paymentMethodId = "stripe")))
        assertEquals(setOf(refunded.id), ids(OrderFilter(fulfillmentStatuses = setOf(FulfillmentStatus.FAILED))))
        assertEquals(setOf(refunded.id, pending.id), ids(OrderFilter(fulfillmentStatuses = setOf(FulfillmentStatus.FAILED, FulfillmentStatus.PARTIAL))))
        assertEquals(setOf(completed.id, refunded.id), ids(OrderFilter(shippingStatuses = setOf(ShippingStatus.SHIPPED, ShippingStatus.PENDING))))
        assertEquals(setOf(refunded.id), ids(OrderFilter(testMode = true)))
        assertEquals(setOf(completed.id, pending.id), ids(OrderFilter(testMode = false)))
        assertEquals(setOf(refunded.id), ids(OrderFilter(sources = setOf(OrderSource.PANEL))))
        assertEquals(setOf(completed.id, pending.id), ids(OrderFilter(sources = setOf(OrderSource.STOREFRONT, OrderSource.GIFT_CODE))))
        // the date range is on createdAt, both ends inclusive
        assertEquals(setOf(refunded.id, pending.id), ids(OrderFilter(from = refunded.createdAt)))
        assertEquals(setOf(completed.id, refunded.id), ids(OrderFilter(to = refunded.createdAt)))
        assertEquals(setOf(pending.id), ids(OrderFilter(from = refunded.createdAt + 1)))
        assertEquals(setOf(completed.id), ids(OrderFilter(to = refunded.createdAt - 1)))
        assertEquals(setOf(refunded.id), ids(OrderFilter(from = refunded.createdAt, to = refunded.createdAt)))
        // filters combine with AND
        assertEquals(setOf(completed.id), ids(OrderFilter(paymentMethodId = "stripe", sources = setOf(OrderSource.STOREFRONT), testMode = false, statuses = setOf(OrderStatus.COMPLETED))))
        assertEquals(emptySet<Long>(), ids(OrderFilter(paymentMethodId = "bank-transfer", testMode = false)))
        assertEquals(2L, service.list(OrderFilter(paymentMethodId = "stripe"), firstPage, true, pool).count, "the count follows the same filter")
    }

    @Test
    fun `search finds the publicId, the player, the recipient, the product, the id and a gateway transaction id`(): Unit = runBlocking {
        val a = order(player = "Steve", recipient = "Notch", gift = true)
        val b = order(player = "Alex")
        val attempt = payments.add(MarketPayment(orderId = b.id, providerId = "stripe", reference = "REF0000000000000000B", token = "t".repeat(40), gatewayTransactionId = "pi_3Abc", status = PaymentStatus.SUCCEEDED), pool)!!

        item(a, name = "Dragon Egg")
        item(b, name = "Coins")

        suspend fun found(term: String, viewer: OrderViewer = both) = listIds(OrderFilter(search = term), viewer).toSet()

        assertEquals(setOf(a.id), found(a.publicId!!), "publicId")
        assertEquals(setOf(a.id), found(a.publicId!!.takeLast(3)), "a piece of the publicId")
        assertEquals(setOf(a.id), found("steve"))
        assertEquals(setOf(a.id), found("Notch"), "recipient")
        assertEquals(setOf(a.id), found("Dragon"), "item product name")
        assertEquals(setOf(b.id), found(b.id.toString()).intersect(setOf(b.id)), "order id")
        assertEquals(setOf(b.id), found("pi_3Abc"), "gateway transaction id (exact)")
        assertEquals(emptySet<Long>(), found("pi_3"), "a gateway transaction id matches exactly, never as a piece")
        assertEquals(setOf(a.id), found("Notch", viewOnly))
        assertNotNull(attempt)
    }

    @Test
    fun `search by e-mail works with OM or PAY only and is no oracle below`(): Unit = runBlocking {
        val john = order(player = "Steve", email = "john@example.com")
        order(player = "Alex", email = "mary@example.org")

        suspend fun found(term: String, viewer: OrderViewer) = service.list(OrderFilter(search = term), firstPage, viewer.pii, pool).let { it.count to it.rows.map { r -> r.getLong("id") } }

        assertEquals(1L to listOf(john.id), found("john@example", both))
        assertEquals(1L to listOf(john.id), found("john@example", manage))
        assertEquals(1L to listOf(john.id), found("john@example", pay))
        assertEquals(0L to emptyList<Long>(), found("john@example", viewOnly))
        // an existing and an unknown address look the same below the tier
        assertEquals(found("zzz@nowhere", viewOnly), found("mary@example", viewOnly))
    }

    @Test
    fun `the search escapes percent, underscore and backslash`(): Unit = runBlocking {
        order(player = "Steve")

        val odd = order(player = "50%_off\\x")

        assertEquals(listOf(odd.id), listIds(OrderFilter(search = "50%_off\\x")))
        assertEquals(listOf(odd.id), listIds(OrderFilter(search = "%")), "a percent sign matches a percent sign, not everything")
        assertEquals(listOf(odd.id), listIds(OrderFilter(search = "_")), "an underscore matches an underscore, not any character")
        assertEquals(listOf(odd.id), listIds(OrderFilter(search = "\\")), "a backslash matches a backslash")
    }

    @Test
    fun `the e-mail of a row is masked below the PII tier`(): Unit = runBlocking {
        order(email = "john@example.com")

        assertEquals("john@example.com", service.list(OrderFilter(), firstPage, true, pool).rows[0].getString("email"))
        assertEquals("j***@e***.com", service.list(OrderFilter(), firstPage, false, pool).rows[0].getString("email"))
        assertFalse(service.list(OrderFilter(), firstPage, false, pool).rows[0].encode().contains("john@example.com"))
    }

    @Test
    fun `paging counts every match and slices by page size`(): Unit = runBlocking {
        repeat(7) { order(player = "P$it", createdAt = 1_000L + it * 10) }

        val first = service.list(OrderFilter(), PageRequest(1, 3), true, pool)
        val third = service.list(OrderFilter(), PageRequest(3, 3), true, pool)
        val beyond = service.list(OrderFilter(), PageRequest(9, 3), true, pool)

        assertEquals(7L, first.count)
        assertEquals(3, first.rows.size)
        assertEquals(1, third.rows.size)
        assertEquals(0, beyond.rows.size)
        assertEquals(3L, Paging.totalPages(first.count, 3))
    }

    // ================================================================================================================ detail

    private suspend fun payment(order: MarketOrder, status: PaymentStatus = PaymentStatus.SUCCEEDED, provider: String = "stripe", txn: String? = null, admin: String? = null, failure: String? = null): Long {
        val n = ++sequence

        return payments.add(
            MarketPayment(
                orderId = order.id, providerId = provider, status = status, reference = "REF" + n.toString().padStart(17, '0'), token = n.toString().padStart(40, 'a'), amount = 2_500, currency = "EUR",
                paidAmount = if (status == PaymentStatus.SUCCEEDED) 2_500 else null, gatewayTransactionId = txn, adminMessage = admin, failureMessage = failure, duplicate = false, creditAmount = 0,
                createdAt = 100L + n, paidAt = if (status == PaymentStatus.SUCCEEDED) 200L else null, startPayload = "v1:secret-start", providerData = "v1:secret-data"
            ),
            pool
        )!!
    }

    private suspend fun delivery(order: MarketOrder, itemId: Long, status: DeliveryStatus, phase: DeliveryPhase = DeliveryPhase.GRANT, payload: String = "{}", unit: Int = 0, error: String? = null): Long {
        val n = ++sequence

        return deliveries.add(
            MarketDelivery(
                orderId = order.id, orderItemId = itemId, phase = phase, actionId = "a", actionType = DeliveryActionType.COMMAND, unitIndex = unit, serverId = 2, idempotencyKey = "dlv-$n",
                status = status, playerUsername = order.playerUsername, payload = payload, lastErrorCode = error, result = "{\"ok\":true}"
            ),
            pool
        )!!
    }

    private suspend fun fullOrder(): Triple<MarketOrder, Long, Long> {
        val legal = legalTexts.add(MarketLegalText(version = 3, locale = "en-US", title = "T", content = "c", contentHash = "h"), pool)!!
        val sub = subscriptions.add(
            MarketSubscription(
                userId = 5, playerUsername = "Steve", ownerKey = "u:5:1", email = "john@example.com", productId = 7, variantId = 0, productName = "VIP", initialOrderId = 1, providerId = "stripe",
                mode = SubscriptionMode.GATEWAY, status = SubscriptionStatus.ACTIVE, price = 999, currency = "EUR", gatewaySubscriptionId = "sub_1", storedMethodLabel = "Visa 4242",
                createdAt = 10, updatedAt = 10
            ),
            pool
        )!!
        val base = order(
            player = "Steve", email = "john@example.com", billing = "{\"type\":\"PERSON\",\"firstName\":\"John\"}", shippingAddress = "{\"city\":\"Izmir\",\"country\":\"TR\"}", gift = true,
            recipient = "Notch", legalTextId = legal, subscriptionId = sub, note = "call the buyer", coupon = "SAVE10", creatorCode = "CREATOR"
        )
        val vip = item(base, name = "VIP", variant = "Gold", sku = "VIP-G", fieldValues = "{\"discord\":\"steve#1\"}")
        val box = item(base, name = "Box", quantity = 2, unit = 500, physical = true)

        return Triple(base, vip, box)
    }

    @Test
    fun `the detail carries every block in the pinned shapes`(): Unit = runBlocking {
        val (base, vip, box) = fullOrder()
        val id = base.id

        entitlements.add(
            MarketEntitlement(userId = 5, playerUsername = "Steve", ownerKey = "u:5", productId = 7, orderId = id, orderItemId = vip, status = EntitlementStatus.ACTIVE, expiresAt = 123_456), pool
        )
        payment(base, PaymentStatus.FAILED, failure = "declined")
        val paid = payment(base, PaymentStatus.SUCCEEDED, txn = "pi_77", admin = "checked by hand")
        val refund = refunds.add(
            MarketRefund(
                orderId = id, paymentId = paid, providerId = "stripe", status = RefundStatus.SUCCEEDED, origin = RefundOrigin.PANEL, idempotencyKey = "refund-key-0001", amount = 500, gatewayAmount = 500,
                creditAmount = 0, creditValue = 0, currency = "EUR", reason = "changed mind", revoke = true, revokeFirst = false, buyerActionUrl = "https://shop.example/r/1", failureMessage = null,
                initiatedBy = 99, createdAt = 300, completedAt = 400
            ),
            pool
        )!!
        disputes.add(MarketDispute(orderId = id, status = DisputeRecordStatus.OPEN, origin = DisputeOrigin.MANUAL, amount = 2_000, currency = "EUR", reason = "chargeback", openedAt = 500), pool)
        delivery(base, vip, DeliveryStatus.CONFIRMED, payload = "{\"webhook\":{\"url\":\"https://hook.example\",\"secret\":\"s3cret-value\"}}")
        delivery(base, vip, DeliveryStatus.PENDING, phase = DeliveryPhase.REVOKE, unit = 1)
        delivery(base, vip, DeliveryStatus.FAILED, phase = DeliveryPhase.EXPIRE, unit = 2, error = "SERVER_REMOVED")
        shipments.add(
            MarketShipment(
                orderId = id, providerId = "manual", status = ShipmentStatus.IN_TRANSIT, merchantReference = "SHP-Q1", carrierName = "DHL", trackingNumber = "TRK1", trackingUrl = "https://t/1",
                labelFile = "label-1.pdf", cost = 450, costCurrency = "EUR", serviceCode = "svc", toAddress = "{\"firstName\":\"John\",\"lastName\":\"Doe\",\"city\":\"Izmir\",\"country\":\"TR\",\"line1\":\"Secret St 1\"}",
                stale = true, note = "fragile", shippedAt = 600, createdAt = 550, providerData = "v1:hidden"
            ),
            pool
        )
        events.add(MarketOrderEvent(orderId = id, type = OrderEventType.CREATED, actorType = OrderActorType.BUYER, createdAt = 1_000, updatedAt = 1_000), pool)
        events.add(
            MarketOrderEvent(
                orderId = id, type = OrderEventType.STATUS_CHANGED, fromStatus = "PENDING", toStatus = "COMPLETED", actorType = OrderActorType.ADMIN, actorUserId = 99, message = "paid by hand",
                createdAt = 2_000, updatedAt = 2_000
            ),
            pool
        )
        invoices.add(MarketInvoice(orderId = id, type = InvoiceType.INVOICE, refundId = 0, series = "INV", sequence = 1, number = "INV-2026-000001", issuedAt = 700, snapshot = "{}"), pool)
        invoices.add(MarketInvoice(orderId = id, type = InvoiceType.CREDIT_NOTE, refundId = refund, series = "CN", sequence = 1, number = "CN-2026-000001", issuedAt = 800, snapshot = "{}"), pool)
        mails.add(
            MarketMailOutbox(
                kind = MailKind.ORDER_CONFIRMATION, refType = MailRefType.ORDER, refId = id, orderId = id, recipient = "john@example.com", status = MailStatus.FAILED, attempts = 3,
                lastError = "550 john@example.com does not exist", createdAt = 900, sentAt = null
            ),
            pool
        )

        val d = service.detail(id, both, pool)!!

        for (key in listOf("order", "items", "payments", "refunds", "disputes", "deliveries", "shipments", "events", "invoices", "mails", "subscription", "revokePending", "revokeFailed", "allowed")) {
            assertTrue(d.containsKey(key), key)
        }

        // order: every column but the tokens and hashes, the legal text version, the gated fields in the clear for OM / PAY
        val order = d.getJsonObject("order")

        for (secret in listOf("accessToken", "idempotencyKey", "idempotencyHash")) assertFalse(order.containsKey(secret), secret)
        assertEquals(3, order.getInteger("legalTextVersion"))
        assertEquals("call the buyer", order.getString("note"))
        assertEquals("SAVE10", order.getString("couponCode"))
        assertEquals("CREATOR", order.getString("creatorCode"))
        assertEquals("STOREFRONT", order.getString("source"))
        assertEquals(25.0, order.getDouble("totalPrice"))
        assertEquals(25.0, order.getDouble("gatewayAmount"))
        assertEquals("john@example.com", order.getString("email"))
        assertEquals("Izmir", order.getJsonObject("shippingAddress").getString("city"))
        assertEquals("John", order.getJsonObject("billingInfo").getString("firstName"))
        assertEquals("203.0.113.57", order.getString("clientIp"))
        assertEquals("enjoy", order.getString("giftMessage"))
        assertTrue(order.getBoolean("requiresShipping"))
        assertEquals(77L, order.getLong("legalAcceptedAt"))
        assertTrue(order.containsKey("statsValue") && order.containsKey("statsCurrency") && order.containsKey("exchangeRate"), "the stats block the page already uses stays")

        // items: every column, the snapshot summary without the actions, the field values as an object, the server name, the entitlement expiry
        val lines = d.getJsonArray("items")

        assertEquals(2, lines.size())

        val first = lines.getJsonObject(0)

        assertEquals("VIP", first.getString("productName"))
        assertEquals("Gold", first.getString("variantName"))
        assertEquals("VIP-G", first.getString("sku"))
        assertEquals("steve#1", first.getJsonObject("fieldValues").getString("discord"))
        assertEquals("vip", first.getJsonObject("snapshot").getString("slug"))
        assertEquals(1, first.getJsonObject("snapshot").getInteger("actionCount"))
        assertFalse(first.getJsonObject("snapshot").containsKey("actions"), "commands of the product stay out of the order page")
        assertEquals(123_456L, first.getLong("expiresAt"))
        assertEquals("PRODUCT", first.getString("kind"))
        assertTrue(lines.getJsonObject(1).getBoolean("physical"))
        assertNull(lines.getJsonObject(1).getLong("expiresAt"))
        for (key in listOf("listUnitPrice", "discountAmount", "vatAmount", "refundedQuantity", "refundedAmount", "shippedQuantity", "targetServerName", "createdAt")) assertTrue(first.containsKey(key), key)

        // payments: the attempts in the pinned shape
        val attempts = d.getJsonArray("payments")

        assertEquals(2, attempts.size())
        assertEquals(listOf("FAILED", "SUCCEEDED"), attempts.map { (it as JsonObject).getString("status") })

        val ok = attempts.getJsonObject(1)

        assertEquals(setOf("id", "providerId", "status", "amount", "creditAmount", "paidAmount", "gatewayTransactionId", "testMode", "duplicate", "failureMessage", "adminMessage", "createdAt", "paidAt"), ok.fieldNames())
        assertEquals("pi_77", ok.getString("gatewayTransactionId"))
        assertEquals("checked by hand", ok.getString("adminMessage"))
        assertEquals("declined", attempts.getJsonObject(0).getString("failureMessage"))
        assertFalse(d.encode().contains("secret-start") || d.encode().contains("secret-data"), "startPayload and providerData never leave")

        // refunds
        val r = d.getJsonArray("refunds").getJsonObject(0)

        assertEquals(
            setOf("id", "status", "origin", "amount", "gatewayAmount", "creditAmount", "creditValue", "currency", "reason", "revoke", "revokeFirst", "buyerActionUrl", "failureMessage", "initiatedByUsername", "createdAt", "completedAt"),
            r.fieldNames()
        )
        assertEquals("SUCCEEDED", r.getString("status"))
        assertEquals(5.0, r.getDouble("amount"))
        assertEquals("Admin", r.getString("initiatedByUsername"))
        assertEquals("PANEL", r.getString("origin"))

        // disputes
        assertEquals(setOf("id", "status", "origin", "amount", "currency", "reason", "openedAt", "resolvedAt"), d.getJsonArray("disputes").getJsonObject(0).fieldNames())
        assertEquals(20.0, d.getJsonArray("disputes").getJsonObject(0).getDouble("amount"))

        // deliveries: the row of GET /deliveries plus orderItemId, the webhook secret removed
        val rows = d.getJsonArray("deliveries")

        assertEquals(3, rows.size())

        val grant = rows.getJsonObject(0)

        assertEquals(vip, grant.getLong("orderItemId"))
        assertEquals("Survival", grant.getString("serverName"))
        assertEquals("GRANT", grant.getString("phase"))
        assertEquals("https://hook.example", grant.getJsonObject("payload").getJsonObject("webhook").getString("url"))
        assertFalse(d.encode().contains("s3cret-value"), "a webhook secret never leaves")
        assertEquals(true, grant.getJsonObject("result").getBoolean("ok"))
        assertEquals("VIP", grant.getString("productName"), "the Product column of the order page: the row of GET /deliveries carries it")
        assertEquals(
            setOf(
                "id", "orderId", "orderItemId", "productName", "playerUsername", "phase", "actionId", "actionType", "transport", "idempotencyKey", "serverId", "serverName", "status", "attempts",
                "requiresOnline", "waitUntil", "cancelRequested", "lastErrorCode", "lastError", "runAfter", "sentAt", "confirmedAt", "payload", "result"
            ),
            grant.fieldNames(),
            "the pinned row of GET /deliveries plus orderItemId"
        )
        assertEquals(1, d.getInteger("revokePending"), "the REVOKE row that nobody confirmed yet")
        assertEquals(1, d.getInteger("revokeFailed"), "the EXPIRE row that failed")

        // shipments
        val shipment = d.getJsonArray("shipments").getJsonObject(0)

        assertEquals(
            setOf(
                "id", "orderId", "status", "entryMode", "providerId", "carrierName", "serviceCode", "trackingNumber", "trackingUrl", "labelFile", "cost", "costCurrency", "toAddress", "stale",
                "lastErrorCode", "lastError", "note", "estimatedDeliveryAt", "shippedAt", "deliveredAt", "createdAt"
            ),
            shipment.fieldNames()
        )
        assertEquals(4.5, shipment.getDouble("cost"))
        assertEquals(setOf("firstName", "lastName", "city", "country"), shipment.getJsonObject("toAddress").fieldNames(), "the street is not in the order detail")
        assertEquals("label-1.pdf", shipment.getString("labelFile"))
        assertFalse(d.encode().contains("Secret St") || d.encode().contains("hidden"))

        // timeline newest first
        val timeline = d.getJsonArray("events")

        assertEquals(listOf("STATUS_CHANGED", "CREATED"), timeline.map { (it as JsonObject).getString("type") })
        assertEquals(setOf("id", "type", "fromStatus", "toStatus", "actorType", "actorUsername", "message", "createdAt"), timeline.getJsonObject(0).fieldNames())
        assertEquals("Admin", timeline.getJsonObject(0).getString("actorUsername"))
        assertEquals("PENDING", timeline.getJsonObject(0).getString("fromStatus"))
        assertNull(timeline.getJsonObject(1).getString("actorUsername"))

        // invoices, mails, subscription
        assertEquals(setOf("id", "type", "refundId", "number", "issuedAt"), d.getJsonArray("invoices").getJsonObject(0).fieldNames())
        assertEquals(listOf("INV-2026-000001", "CN-2026-000001"), d.getJsonArray("invoices").map { (it as JsonObject).getString("number") })
        assertEquals(refund, d.getJsonArray("invoices").getJsonObject(1).getLong("refundId"))

        val mail = d.getJsonArray("mails").getJsonObject(0)

        assertEquals(setOf("id", "kind", "recipient", "status", "attempts", "lastError", "createdAt", "sentAt"), mail.fieldNames())
        assertEquals("john@example.com", mail.getString("recipient"))
        assertEquals(3, mail.getInteger("attempts"))

        val subscription = d.getJsonObject("subscription")

        assertEquals(
            setOf(
                "id", "playerUsername", "productName", "status", "mode", "providerId", "price", "currency", "intervalUnit", "intervalCount", "cycleCount", "maxCycles", "currentPeriodStart",
                "currentPeriodEnd", "nextChargeAt", "graceEndsAt", "cancelAtPeriodEnd", "failCount", "endReason", "remoteCancelState", "gatewaySubscriptionId", "storedMethodLabel", "testMode", "createdAt"
            ),
            subscription.fieldNames()
        )
        assertEquals(9.99, subscription.getDouble("price"))
        assertEquals("sub_1", subscription.getString("gatewaySubscriptionId"))
        assertFalse(d.encode().contains("storedMethod\""), "the stored method itself never leaves")
        assertNotNull(box)
    }

    @Test
    fun `an order without a subscription, a legal text or any child row has empty blocks`(): Unit = runBlocking {
        val base = order()
        val d = service.detail(base.id, both, pool)!!

        for (block in listOf("items", "payments", "refunds", "disputes", "deliveries", "shipments", "events", "invoices", "mails")) assertEquals(0, d.getJsonArray(block).size(), block)

        assertNull(d.getValue("subscription"))
        assertNull(d.getJsonObject("order").getValue("legalTextVersion"))
        assertEquals(0, d.getInteger("revokePending"))
        assertEquals(0, d.getInteger("revokeFailed"))
    }

    @Test
    fun `below the PII tier the detail masks the e-mail and hides address, network data, gift message and carrier fields`(): Unit = runBlocking {
        val (base, vip, _) = fullOrder()

        shipments.add(
            MarketShipment(orderId = base.id, providerId = "manual", merchantReference = "SHP-Q2", labelFile = "label-2.pdf", toAddress = "{\"firstName\":\"John\",\"city\":\"Izmir\",\"country\":\"TR\"}"), pool
        )
        mails.add(
            MarketMailOutbox(
                kind = MailKind.ORDER_CONFIRMATION, refType = MailRefType.ORDER, refId = base.id, orderId = base.id, recipient = "john@example.com", status = MailStatus.FAILED,
                lastError = "550 john@example.com does not exist"
            ),
            pool
        )

        val d = service.detail(base.id, viewOnly, pool)!!
        val order = d.getJsonObject("order")

        assertEquals("j***@e***.com", order.getString("email"))
        for (key in listOf("billingInfo", "shippingAddress", "clientIp", "userAgent", "giftMessage")) assertNull(order.getValue(key), key)

        val shipment = d.getJsonArray("shipments").getJsonObject(0)

        assertNull(shipment.getValue("toAddress"))
        assertNull(shipment.getValue("labelFile"))

        val mail = d.getJsonArray("mails").getJsonObject(0)

        assertEquals("j***@e***.com", mail.getString("recipient"))
        assertFalse(mail.getString("lastError").contains("john@example.com"), "an address echoed in an error text is masked too")

        val text = d.encode()

        for (leak in listOf("john@example.com", "203.0.113.57", "Izmir", "enjoy", "JUnit/1", "label-2.pdf")) assertFalse(text.contains(leak), leak)
        // fieldValues and adminMessage are for OV (11 section 14.5)
        assertEquals("steve#1", d.getJsonArray("items").getJsonObject(0).getJsonObject("fieldValues").getString("discord"))
        assertNotNull(vip)

        for (tier in listOf(manage, pay, both)) {
            assertEquals("john@example.com", service.detail(base.id, tier, pool)!!.getJsonObject("order").getString("email"))
        }
    }

    @Test
    fun `allowed follows the state of the order and the node of the caller, read from the real rows`(): Unit = runBlocking {
        // a pending order with an open bank transfer
        val pending = order(status = OrderStatus.PENDING, method = "bank-transfer")

        payment(pending, PaymentStatus.PENDING, provider = "bank-transfer")

        fun JsonObject.flags() = listOf("markPaid", "cancel", "refund", "review", "bankTransfer", "dispute", "rerunDelivery", "revoke", "createShipment", "editShippingAddress", "resendMail", "anonymize", "runChargebackActions")
            .filter { getBoolean(it) == true }.toSet()

        assertEquals(setOf("markPaid", "cancel", "bankTransfer", "resendMail"), service.detail(pending.id, both, pool)!!.getJsonObject("allowed").flags())
        assertEquals(setOf("markPaid", "cancel", "bankTransfer"), service.detail(pending.id, pay, pool)!!.getJsonObject("allowed").flags())
        assertEquals(setOf("resendMail"), service.detail(pending.id, manage, pool)!!.getJsonObject("allowed").flags())
        assertEquals(emptySet<String>(), service.detail(pending.id, viewOnly, pool)!!.getJsonObject("allowed").flags())

        // a paid physical order with a failed delivery, a granted unit, and a held chargeback action
        val paid = order(status = OrderStatus.COMPLETED, shippingAddress = "{\"country\":\"TR\"}")
        val line = item(paid, name = "Box", quantity = 2, physical = true)

        delivery(paid, line, DeliveryStatus.FAILED, unit = 0)
        delivery(paid, line, DeliveryStatus.CONFIRMED, unit = 1)
        deliveries.add(
            MarketDelivery(
                orderId = paid.id, orderItemId = null, sourceType = com.panomc.plugins.market.db.model.DeliverySourceType.CHARGEBACK_ACTION, sourceId = 5, phase = DeliveryPhase.GRANT, actionId = "c1",
                actionType = DeliveryActionType.COMMAND, serverId = 2, idempotencyKey = "cb-held-1", status = DeliveryStatus.CANCELLED, lastErrorCode = "NEEDS_CONFIRMATION", playerUsername = "Steve"
            ),
            pool
        )

        val paidDetail = service.detail(paid.id, both, pool)!!
        val allowed = paidDetail.getJsonObject("allowed")

        assertEquals(listOf("Box", "Box", null), paidDetail.getJsonArray("deliveries").map { (it as JsonObject).getString("productName") }, "null for the chargeback action without an item")
        assertEquals(
            setOf("refund", "dispute", "rerunDelivery", "revoke", "createShipment", "editShippingAddress", "resendMail", "anonymize", "runChargebackActions"), allowed.flags()
        )
        assertEquals(25.0, allowed.getDouble("refundMax"))
        assertEquals(listOf("FULL", "PARTIAL", "PER_LINE", "MANUAL"), allowed.getJsonArray("refundModes").map { it.toString() })
        assertEquals(setOf("refund", "dispute", "anonymize", "runChargebackActions"), service.detail(paid.id, pay, pool)!!.getJsonObject("allowed").flags(), "PAY alone moves money, it never runs a delivery")
        assertEquals(setOf("rerunDelivery", "revoke", "createShipment", "editShippingAddress", "resendMail"), service.detail(paid.id, manage, pool)!!.getJsonObject("allowed").flags())

        // an order in review
        val review = order(status = OrderStatus.REVIEW)

        assertEquals(setOf("review", "resendMail"), service.detail(review.id, both, pool)!!.getJsonObject("allowed").flags())
    }

    // ================================================================================================================ note

    @Test
    fun `the note is trimmed, stored, cleared by a blank, and leaves a timeline row without the text`(): Unit = runBlocking {
        val base = order()

        service.setNote(base.id, "  call the buyer  ", 99, pool)

        assertEquals("call the buyer", orders.getById(base.id, pool)!!.note)
        assertEquals(5_000L, orders.getById(base.id, pool)!!.updatedAt)

        val rows = events.getByOrderId(base.id, pool).filter { it.type == OrderEventType.NOTE }

        assertEquals(1, rows.size)
        assertEquals(OrderActorType.ADMIN, rows[0].actorType)
        assertEquals(99L, rows[0].actorUserId)
        assertNull(rows[0].message)
        assertFalse(rows[0].data.orEmpty().contains("call the buyer"))

        service.setNote(base.id, "   ", 99, pool)

        assertNull(orders.getById(base.id, pool)!!.note)

        service.setNote(base.id, "x".repeat(OrderQueryService.NOTE_MAX), 99, pool)

        assertEquals(OrderQueryService.NOTE_MAX, orders.getById(base.id, pool)!!.note!!.length)
        assertEquals(3, events.getByOrderId(base.id, pool).count { it.type == OrderEventType.NOTE })
    }

    @Test
    fun `a note that is too long or for an unknown order changes nothing`(): Unit = runBlocking {
        val base = order()

        service.setNote(base.id, "keep", 99, pool)

        assertEquals("note", assertThrows(RequestValueException::class.java) { runBlocking { service.setNote(base.id, "x".repeat(OrderQueryService.NOTE_MAX + 1), 99, pool) } }.field)
        assertEquals("keep", orders.getById(base.id, pool)!!.note)
        assertThrows(NotFound::class.java) { runBlocking { service.setNote(424242, "x", 99, pool) } }
        assertEquals(1, events.getByOrderId(base.id, pool).count { it.type == OrderEventType.NOTE })
    }

    // ================================================================================================================ export

    private fun lines(csv: String): List<String> = csv.removePrefix("﻿").split("\r\n").filter { it.isNotEmpty() }

    @Test
    fun `the export starts with a BOM and the header and writes one row per order item`(): Unit = runBlocking {
        val multi = order(player = "Steve", total = 3_500, coupon = "SAVE10", creatorCode = "CREATOR")
        val single = order(player = "Alex", total = 1_000, status = OrderStatus.REFUNDED)

        item(multi, name = "VIP", variant = "Gold", quantity = 1, unit = 2_500, sku = "VIP-G")
        item(multi, name = "Coins", quantity = 4, unit = 250)
        item(multi, name = "Crate", quantity = 1, unit = 0)
        item(single, name = "Cape", unit = 1_000)

        val (csv, result) = export()
        val rows = lines(csv)

        assertTrue(csv.startsWith("﻿\"orderId\",\"publicId\""), "BOM, then the header")
        assertEquals(OrderExportColumns.ALL.map { "\"$it\"" }.joinToString(","), rows[0].removePrefix("﻿"))
        assertEquals(4, rows.size - 1, "one row per item, not per order")
        assertEquals(4, result.rows)
        assertFalse(result.truncated)
        assertEquals(listOf(single.id, multi.id, multi.id, multi.id), rows.drop(1).map { it.substringBefore(',').toLong() }, "newest order first, items in line order")
        assertEquals(listOf("Cape", "VIP", "Coins", "Crate"), rows.drop(1).map { cells(it)[OrderExportColumns.ALL.indexOf("productName")].trim('"') })

        val vip = cells(rows[2])

        fun column(name: String) = vip[OrderExportColumns.ALL.indexOf(name)]

        assertEquals("\"VIP\"", column("productName"))
        assertEquals("\"Gold\"", column("variantName"))
        assertEquals("\"VIP-G\"", column("sku"))
        assertEquals("1", column("quantity"))
        assertEquals("25.00", column("unitPrice"))
        assertEquals("25.00", column("lineTotal"))
        assertEquals("35.00", column("orderTotal"))
        assertEquals("\"EUR\"", column("currency"))
        assertEquals("\"SAVE10\"", column("couponCode"))
        assertEquals("\"CREATOR\"", column("creatorCode"))
        assertEquals("\"Card\"", column("paymentMethod"))
        assertEquals("\"STOREFRONT\"", column("source"))
        assertEquals("\"COMPLETED\"", column("status"))
        assertEquals("false", column("testMode"))
        assertEquals("\"john@example.com\"", column("email"))
        assertEquals("\"Steve\"", column("playerUsername"))
        assertTrue(column("createdAt").matches(Regex("\"1970-01-01T00:00:01(\\.[0-9]+)?Z\"")), column("createdAt"))
    }

    private fun cells(line: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0

        while (i < line.length) {
            val c = line[i]

            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { current.append("\"\""); i++ }
                c == '"' -> { quoted = !quoted; current.append(c) }
                c == ',' && !quoted -> { out += current.toString(); current.clear() }
                else -> current.append(c)
            }

            i++
        }

        return out + current.toString()
    }

    @Test
    fun `the columns option picks and orders the columns, the delimiter option changes the separator`(): Unit = runBlocking {
        val base = order(player = "Steve")

        item(base, name = "VIP", unit = 1_999)

        val picked = listOf("lineTotal", "orderId", "productName")

        assertEquals("\"lineTotal\",\"orderId\",\"productName\"", lines(export(columns = picked).first)[0])
        assertEquals("19.99,${base.id},\"VIP\"", lines(export(columns = picked).first)[1])
        assertEquals("\"lineTotal\";\"orderId\";\"productName\"", lines(export(columns = picked, delimiter = CsvWriter.Delimiter.SEMICOLON).first)[0])
        assertEquals("19.99;${base.id};\"VIP\"", lines(export(columns = picked, delimiter = CsvWriter.Delimiter.SEMICOLON).first)[1])
        assertEquals("19.99\t${base.id}\t\"VIP\"", lines(export(columns = picked, delimiter = CsvWriter.Delimiter.TAB).first)[1])
    }

    @Test
    fun `a buyer text that starts like a formula is exported neutralised and a quote is doubled`(): Unit = runBlocking {
        val base = order(player = "=cmd|' /C calc'!A0", email = "+evil@example.com")

        item(base, name = "=HYPERLINK(\"http://evil.example\",\"click\")", variant = "@SUM(1+1)", sku = "-1+1")

        val (csv, _) = export(columns = listOf("playerUsername", "email", "productName", "variantName", "sku"))
        val row = lines(csv)[1]

        assertTrue(row.contains("\"'=cmd|' /C calc'!A0\""), row)
        assertTrue(row.contains("\"'+evil@example.com\""), row)
        assertTrue(row.contains("\"'=HYPERLINK(\"\"http://evil.example\"\",\"\"click\"\")\""), row)
        assertTrue(row.contains("\"'@SUM(1+1)\""), row)
        assertTrue(row.contains("\"'-1+1\""), row)
    }

    @Test
    fun `email and country are exported only with OM or PAY and a request for them below the tier is a 403`(): Unit = runBlocking {
        val base = order(email = "john@example.com", shippingAddress = "{\"country\":\"TR\"}", billing = "{\"country\":\"DE\"}")
        val billingOnly = order(email = "mary@example.org", billing = "{\"country\":\"DE\"}")

        item(base)
        item(billingOnly)

        assertThrows(NoPermission::class.java) { runBlocking { export(columns = listOf("orderId", "email"), viewer = viewOnly) } }
        assertThrows(NoPermission::class.java) { runBlocking { export(columns = listOf("country"), viewer = viewOnly) } }
        assertThrows(NoPermission::class.java) { runBlocking { service.export(OrderFilter(), listOf("email"), CsvWriter.Delimiter.COMMA, false, pool) {} } }

        val (csv, _) = export(columns = listOf("orderId", "email", "country"), viewer = pay)
        val rows = lines(csv)

        assertEquals("${billingOnly.id},\"mary@example.org\",\"DE\"", rows[1], "the billing country when there is no shipping address")
        assertEquals("${base.id},\"john@example.com\",\"TR\"", rows[2], "the shipping country wins")

        // the default for a caller below the tier is every column it may select: no e-mail anywhere in the file
        val defaults = OrderExportColumns.parse(null, pii = false)
        val (plain, _) = export(columns = defaults, viewer = viewOnly)

        assertFalse(plain.contains("john@example.com") || plain.contains("mary@example.org"))
        assertFalse(lines(plain)[0].contains("email") || lines(plain)[0].contains("country"))
    }

    @Test
    fun `an unknown column is refused by the service too`(): Unit = runBlocking {
        assertEquals("columns", assertThrows(RequestValueException::class.java) { runBlocking { export(columns = listOf("accessToken")) } }.field)
    }

    @Test
    fun `the filters of the list apply to the export`(): Unit = runBlocking {
        val keep = order(player = "Steve", status = OrderStatus.COMPLETED, testMode = false)
        val skip = order(player = "Alex", status = OrderStatus.REFUNDED, testMode = true)

        item(keep, name = "VIP")
        item(skip, name = "Cape")

        val (csv, result) = export(filter = OrderFilter(statuses = setOf(OrderStatus.COMPLETED)), columns = listOf("orderId", "productName"))

        assertEquals(listOf("${keep.id},\"VIP\""), lines(csv).drop(1))
        assertEquals(1, result.rows)
        assertEquals(listOf("${skip.id},\"Cape\""), lines(export(filter = OrderFilter(testMode = true), columns = listOf("orderId", "productName")).first).drop(1))
        assertEquals(emptyList<String>(), lines(export(filter = OrderFilter(search = "nobody"), columns = listOf("orderId")).first).drop(1))
    }

    @Test
    fun `an order without items still appears once`(): Unit = runBlocking {
        val bare = order()

        val rows = lines(export(columns = listOf("orderId", "productName", "quantity")).first)

        assertEquals(listOf("${bare.id},\"\","), rows.drop(1))
    }

    @Test
    fun `the export is cut at 50 000 rows, announced before the first byte, and the batches neither repeat nor skip a row`(): Unit = runBlocking {
        val low = order(player = "Low")
        val high = order(player = "High")

        for (target in listOf(low, high)) {
            pool.preparedQuery(
                "INSERT INTO `${orders.prefix()}market_order_item` (`orderId`, `productId`, `productName`, `quantity`, `unitPrice`, `createdAt`, `updatedAt`) " +
                    "SELECT ${target.id}, 7, CONCAT('Item ', seq), 1, 100, 0, 0 FROM seq_1_to_30000"
            ).execute().coAwait()
        }

        val out = StringBuilder()
        var announced: Boolean? = null
        val result = service.export(OrderFilter(), listOf("orderId", "productName"), CsvWriter.Delimiter.COMMA, true, pool, begin = { announced = it }) { out.append(it) }
        val rows = lines(out.toString()).drop(1)

        assertEquals(50_000, result.rows)
        assertEquals(50_000, rows.size)
        assertTrue(result.truncated)
        assertEquals(true, announced, "the route learns about the cut before it writes")
        assertEquals(50_000, rows.toSet().size, "no row twice")
        assertEquals(30_000, rows.count { it.startsWith("${high.id},") }, "the newest order first, complete")
        assertEquals(20_000, rows.count { it.startsWith("${low.id},") })
        assertEquals("${high.id},\"Item 1\"", rows.first(), "items of an order in line order")
        assertEquals("${high.id},\"Item 30000\"", rows[29_999])
        assertEquals("${low.id},\"Item 1\"", rows[30_000])
        assertEquals("${low.id},\"Item 20000\"", rows.last())
    }

    @Test
    fun `an export below the cap is not announced as cut`(): Unit = runBlocking {
        item(order())

        var announced: Boolean? = null
        val result = service.export(OrderFilter(), listOf("orderId"), CsvWriter.Delimiter.COMMA, true, pool, begin = { announced = it }) {}

        assertEquals(false, announced)
        assertFalse(result.truncated)
        assertEquals(1, result.rows)
    }

    @Test
    fun `the route classes call the service, the gating and the limiter, and the export route is registered before the id route`() {
        val root = java.io.File("src/main/kotlin/com/panomc/plugins/market/routes/panel/order/")
        val list = java.io.File(root, "PanelGetOrdersAPI.kt").readText()
        val detail = java.io.File(root, "PanelGetOrderAPI.kt").readText()
        val export = java.io.File(root, "PanelExportOrdersAPI.kt").readText()
        val note = java.io.File(root, "PanelUpdateOrderNoteAPI.kt").readText()

        assertTrue(list.contains("FieldGating.piiTier(context)") && list.contains("orderQueryService(plugin).list(filter, window, pii"), "the list is gated")
        assertTrue(detail.contains("has(context, MarketNode.ORDERS_MANAGE)") && detail.contains("has(context, MarketNode.PAYMENTS)") && detail.contains(".detail(id, viewer"), "the detail knows OM and PAY")
        assertTrue(export.contains("abuseWiring(plugin).rateLimits.export(userId)"), "L11 on the export")
        assertTrue(export.contains("FieldGating.piiTier(context)") && export.contains("OrderExportColumns.parse(query(\"columns\"), pii)"), "the PII columns are checked")
        assertTrue(export.contains("override val order = 0"), "ahead of /orders/:id")
        assertTrue(export.contains("ExportedMarketOrdersLog("), "the export is logged")
        assertTrue(note.contains("UpdatedMarketOrderNoteLog("), "the note is logged")
        assertTrue(java.io.File("src/main/kotlin/com/panomc/plugins/market/service/OrderQueryService.kt").readText().let { it.contains("FieldGating.orderPii(order, pii)") && it.contains("FieldGating.email(r.getString(\"email\"), pii)") }, "one projection")
    }
}
