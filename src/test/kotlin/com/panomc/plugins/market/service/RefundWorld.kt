package com.panomc.plugins.market.service

import com.panomc.platform.model.Error
import com.panomc.plugins.market.core.delivery.ProductAction
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.MarketRefund
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.payment.PaymentContext
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentProvider
import com.panomc.plugins.market.spi.payment.PaymentTarget
import com.panomc.plugins.market.spi.payment.QueryRefundRequest
import com.panomc.plugins.market.spi.payment.RefundResult
import com.panomc.plugins.market.spi.payment.RefundState
import com.panomc.plugins.market.spi.payment.RefundSupport
import com.panomc.plugins.market.spi.testkit.TestContexts
import com.panomc.plugins.market.support.FakePaymentProvider
import com.panomc.plugins.market.support.MarketTestDb
import com.panomc.plugins.market.support.StaticProviderLookup
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.support.WebhookHarness
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** One child of a bundle line: the actions its snapshot carries and its units per bundle (08 section 5.2). */
internal class RefundChild(val actions: List<ProductAction>, val perBundle: Int = 1)

/**
 * One line of an order built by [RefundWorld.place]: its gross total, units, the actions its snapshot carries and the stock it reserved. With [children] the line
 * is a `BUNDLE` and each child is a `BUNDLE_CHILD` line of its own (price 0, `quantity x perBundle` units), appended after all the lines of the order.
 */
internal class RefundLine(
    val total: Long,
    val quantity: Int = 1,
    val actions: List<ProductAction> = emptyList(),
    val reservedStock: Int = 0,
    val product: MarketProduct? = null,
    val billing: String = "ONE_TIME",
    val children: List<RefundChild> = emptyList()
)

/** A paid order of [RefundWorld.place]: its rows as inserted (re-read them for the current state) and the payment attempt that took the gateway part. */
internal class PaidOrder(val order: MarketOrder, val items: List<MarketOrderItem>, val attempt: MarketPayment, val products: List<MarketProduct>)

/**
 * The object graph of a refund test (17 section 5.3): the delivery engine of [DeliveryWorld] (real DAOs, ledger, entitlements, permission nodes in memory), a real
 * [PaymentService] on a scripted provider (id `fake`, refund support `PARTIAL`, `queryRefund` scripted by [onQueryRefund]) behind the production
 * [PaymentServiceRefundGateway], the webhook and mail queues, and the [RefundService] under test.
 */
internal class RefundWorld(val w: TestWiring, val vertx: Vertx, invoices: InvoiceService? = null, settleWaitMs: Long = 5_000L, refundTimeoutMs: Long = PaymentService.REFUND_TIMEOUT_MS) {
    val d = DeliveryWorld(w)
    val fake = FakePaymentProvider("fake").also { it.caps = PaymentCapabilities().apply { refund = RefundSupport.PARTIAL } }

    @Volatile
    var onQueryRefund: (QueryRefundRequest) -> RefundResult = { RefundResult.unknown() }
    val queries = CopyOnWriteArrayList<QueryRefundRequest>()
    val alerts = CopyOnWriteArrayList<Triple<Long, String, JsonObject>>()

    private val provider: PaymentProvider = object : PaymentProvider by fake {
        override suspend fun queryRefund(ctx: PaymentContext, request: QueryRefundRequest): RefundResult {
            queries += request

            return onQueryRefund(request)
        }
    }
    val lookup = StaticProviderLookup(listOf(provider))
    private val redemptions = RedemptionService(w.clock, d.locks, w.redemptions)
    val orderService = OrderService(
        w.clock, w.ids, w.orders, w.orderItems, w.orderEvents, w.payments, redemptions, { _, _ -> false },
        reservations = ReservationService(w.clock, d.locks, redemptions, w.orders), refunds = w.refunds
    )
    val payments = PaymentService(
        db = w.db, locks = d.locks, clock = w.clock, ids = w.ids, config = { w.config }, orders = w.orders, orderItems = w.orderItems, orderEvents = w.orderEvents,
        payments = w.payments, methods = w.paymentMethods, creditAccounts = w.creditAccounts, currencyRates = w.currencyRates, lookup = lookup,
        cipher = SecretCipher(ByteArray(32) { (it + 9).toByte() }), contexts = PaymentContexts { p, s, t -> TestContexts.payment(p.id, s, vertx, t) },
        orderService = orderService, site = { TestContexts.defaultSite() }, readClient = { w.pool }, products = w.products, entitlements = w.entitlements,
        refundTimeoutMs = refundTimeoutMs
    )
    val webhooks = WebhookHarness(w, vertx)
    val effects = StandardRefundEffects(
        w.clock, w.creatorEarnings, { MarketTestDb.TABLE_PREFIX }, invoices = invoices, mailOutbox = MailOutboxService({ w.config }, w.clock, w.mailOutbox, w.orderEvents),
        webhooks = webhooks.service
    )
    val service = RefundService(
        w.db, d.locks, w.clock, { w.config }, w.orders, w.orderItems, w.orderEvents, w.payments, w.refunds, w.refundItems, w.deliveries, w.entitlements, w.creditTxs, d.credits,
        d.service, d.entitlementService, PaymentServiceRefundGateway(payments) { w.pool }, w.serverStates, effects, RefundAlerts { orderId, code, data -> alerts += Triple(orderId, code, data) },
        settleWaitMs
    )
    val job = com.panomc.plugins.market.job.RefundReconcileJob(service)

    private val sequence = AtomicInteger()

    /** The test store of [TestWiring.defaultConfig] with the switches these tests flip (a [com.panomc.plugins.market.config.MarketConfig] cannot be copied). */
    fun config(revokeOnRefund: Boolean = true, seller: Boolean = false) = com.panomc.plugins.market.config.MarketConfig(
        currency = "EUR", vatPercent = 20.0, showVatInPrice = true, creditValue = 1.0, storeTimeZone = "UTC", revokeOnRefund = revokeOnRefund,
        invoiceSellerName = if (seller) "Acme Ltd" else "", invoiceSellerAddress = if (seller) "1 Main St" else "", invoiceSellerTaxOffice = if (seller) "Kadikoy" else "",
        invoiceSellerTaxNumber = if (seller) "123456" else ""
    )

    fun key(name: String = "k"): String = "refund-${name}-${sequence.incrementAndGet()}".padEnd(24, '-')

    private fun snapshot(product: MarketProduct, line: RefundLine): String = JsonObject()
        .put("slug", product.slug).put("billingMode", line.billing).put("actions", JsonArray(line.actions.map { it.toJson() })).encode()

    /**
     * A paid order (`COMPLETED`, `COMMITTED`) of [user] with [lines]: total = the sum of the lines (plus [shipping]); the gateway part defaults to everything
     * the credits do not cover, [creditValue] is the money value of the credit part and [credits] the credits (x100) that paid it (credit value 1.0: the same
     * number). The credit part is held and captured through the real ledger, the line products' `soldCount` counts the units (I17), stock rows keep what is left
     * of the reserved units. With [grant] the entitlements and the `GRANT` rows are written and the inline ones executed.
     */
    suspend fun place(
        user: TestUser?,
        lines: List<RefundLine>,
        creditValue: Long = 0,
        credits: Long = creditValue,
        provider: String = "fake",
        grant: Boolean = true,
        shipping: Long = 0,
        testMode: Boolean = false,
        email: String? = null,
        currency: String = "EUR",
        fxRate: java.math.BigDecimal = java.math.BigDecimal.ONE
    ): PaidOrder {
        val total = lines.sumOf { it.total } + shipping
        val gateway = total - creditValue
        val now = w.clock.now()
        val key = if (user != null) "u:${user.id}" else "g:guest${sequence.incrementAndGet()}"
        val name = user?.username ?: "Guest"
        val products = lines.map { it.product ?: w.fixtures.product(actions = JsonArray(it.actions.map { a -> a.toJson() }).encode(), stock = it.reservedStock.takeIf { r -> r > 0 }?.let { r -> r + 8 }) }

        if (credits > 0 && user != null) w.fixtures.credit(user, credits)

        val orderId = w.orders.add(
            MarketOrder(
                userId = user?.id, playerUsername = name, recipientUsername = name, recipientUserId = user?.id, recipientKey = key, buyerKey = key, publicId = w.ids.publicId(),
                status = OrderStatus.COMPLETED, currency = currency, baseCurrency = "EUR", fxRate = fxRate, subtotal = lines.sumOf { it.total }, shippingTotal = shipping, totalPrice = total,
                gatewayAmount = gateway, creditValue = creditValue, creditAmount = credits, paidAmount = gateway, paidAt = now, createdAt = now, updatedAt = now,
                paymentMethodId = provider, reservationState = ReservationState.COMMITTED, email = email, testMode = testMode
            ),
            w.pool
        )
        val itemIds = lines.mapIndexed { i, line ->
            w.orderItems.add(
                MarketOrderItem(
                    orderId = orderId, productId = products[i].id, productName = "Line ${i + 1}", quantity = line.quantity, unitPrice = line.total / line.quantity,
                    lineTotal = line.total, listUnitPrice = line.total / line.quantity, kind = if (line.children.isEmpty()) OrderItemKind.PRODUCT else OrderItemKind.BUNDLE,
                    snapshot = snapshot(products[i], line), stockReserved = line.reservedStock, createdAt = now, updatedAt = now
                ),
                w.pool
            )
        }

        // the children of a bundle line, after every line (the tests address the lines by their index)
        lines.forEachIndexed { i, line ->
            for (child in line.children) {
                val childProduct = w.fixtures.product(actions = JsonArray(child.actions.map { a -> a.toJson() }).encode())
                val quantity = line.quantity * child.perBundle

                w.orderItems.add(
                    MarketOrderItem(
                        orderId = orderId, productId = childProduct.id, productName = "Child of line ${i + 1}", quantity = quantity, unitPrice = 0, lineTotal = 0, listUnitPrice = 0,
                        kind = OrderItemKind.BUNDLE_CHILD, parentItemId = itemIds[i],
                        snapshot = JsonObject().put("slug", childProduct.slug).put("billingMode", "ONE_TIME").put("actions", JsonArray(child.actions.map { a -> a.toJson() })).encode(),
                        createdAt = now, updatedAt = now
                    ),
                    w.pool
                )

                // I17: the units of a paid order are in the products' soldCount
                if (!testMode) MarketTestDb.sql(w.pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_product` SET `soldCount` = `soldCount` + ? WHERE `id` = ?", quantity, childProduct.id)
            }
        }

        // I17: the units of a paid order are in the products' soldCount
        if (!testMode) {
            lines.forEachIndexed { i, line ->
                MarketTestDb.sql(w.pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_product` SET `soldCount` = `soldCount` + ? WHERE `id` = ?", line.quantity, products[i].id)
            }
        }

        val unique = sequence.incrementAndGet()
        val attemptId = w.payments.add(
            MarketPayment(
                orderId = orderId, providerId = provider, methodLabel = "Fake", status = PaymentStatus.SUCCEEDED, reference = "REF%017d".format(unique), token = "%040x".format(unique),
                amount = gateway, currency = currency, creditAmount = credits, creditValue = creditValue, orderTotal = total, gatewayTransactionId = "txn-$unique", paidAmount = gateway,
                paidCurrency = currency, paidAt = now, createdAt = now, updatedAt = now
            ),
            w.pool
        )!!

        MarketTestDb.sql(w.pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order` SET `paymentId` = ? WHERE `id` = ?", attemptId, orderId)

        if (credits > 0 && user != null) {
            val order = w.orders.getById(orderId, w.pool)!!

            w.db.tx { conn ->
                d.credits.lockAccounts(listOf(user.id), true, conn)
                d.credits.hold(order, 0, conn)
                d.credits.capture(order, conn)
            }
        }

        val order = w.orders.getById(orderId, w.pool)!!
        val items = w.orderItems.getByOrderIds(listOf(orderId), w.pool)
        val placed = Placed(order, items, products.first())

        if (grant) {
            d.pay(placed)
            d.runInline()
        }

        return PaidOrder(w.orders.getById(orderId, w.pool)!!, w.orderItems.getByOrderIds(listOf(orderId), w.pool), w.payments.getById(attemptId, w.pool)!!, products)
    }

    suspend fun order(id: Long): MarketOrder = w.orders.getById(id, w.pool)!!

    suspend fun items(orderId: Long): List<MarketOrderItem> = w.orderItems.getByOrderIds(listOf(orderId), w.pool)

    suspend fun refunds(orderId: Long): List<MarketRefund> = w.refunds.getByOrderId(orderId, w.pool)

    suspend fun refund(id: Long): MarketRefund = w.refunds.getById(id, w.pool)!!

    suspend fun attempt(id: Long): MarketPayment = w.payments.getById(id, w.pool)!!

    /** An inbound `RefundUpdated` for the order's own attempt, applied the way the dispatcher's sink does. */
    suspend fun inbound(
        paid: PaidOrder,
        state: RefundState,
        amount: Long? = null,
        gatewayRefundId: String? = null,
        refundKey: String? = null,
        cumulative: Long? = null,
        eventKey: String? = "evt-${sequence.incrementAndGet()}"
    ) {
        val event = PaymentEvent.RefundUpdated(PaymentTarget.Attempt(paid.attempt.id), state, amount?.let { Money(it, "EUR") })

        event.gatewayRefundId = gatewayRefundId
        event.refundKey = refundKey
        event.cumulativeRefunded = cumulative?.let { Money(it, "EUR") }

        service.onRefundUpdated(event, attempt(paid.attempt.id), eventKey, null)
    }

    /** The error [block] throws, as the wire body (`code`, `status`, extras); fails when nothing is thrown. */
    suspend fun expect(code: String, status: Int, block: suspend () -> Any?): JsonObject {
        val e = try {
            block()

            null
        } catch (e: Error) {
            e
        } ?: error("expected $code, nothing was thrown")

        assertEquals(code, e.getErrorCode(), "error code, body ${e.encode()}")
        assertEquals(status, e.getStatusCode())

        return JsonObject(e.encode())
    }
}
