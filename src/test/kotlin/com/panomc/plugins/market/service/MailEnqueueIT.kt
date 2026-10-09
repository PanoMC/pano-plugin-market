package com.panomc.plugins.market.service

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.payment.PaymentAttemptEvent
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.DeliveryActionType
import com.panomc.plugins.market.db.model.MailKind
import com.panomc.plugins.market.db.model.MailRefType
import com.panomc.plugins.market.db.model.MailStatus
import com.panomc.plugins.market.db.model.MarketMailOutbox
import com.panomc.plugins.market.core.delivery.ProductAction
import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.db.model.DeliveryStatus
import com.panomc.plugins.market.db.model.EntitlementStatus
import com.panomc.plugins.market.db.model.FulfillmentStatus
import com.panomc.plugins.market.db.model.MarketDelivery
import com.panomc.plugins.market.db.model.MarketOrderEvent
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.OrderActorType
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.i18n.MarketFormat
import com.panomc.plugins.market.i18n.MarketI18n
import com.panomc.plugins.market.job.MailOutboxJob
import com.panomc.plugins.market.mail.MailComposer
import com.panomc.plugins.market.mail.MailContentBuilder
import com.panomc.plugins.market.mail.MailSite
import com.panomc.plugins.market.pdf.InvoiceDocuments
import com.panomc.plugins.market.pdf.InvoiceMailAttachments
import com.panomc.plugins.market.pdf.InvoiceTextsFactory
import com.panomc.plugins.market.service.platform.DirectoryUser
import com.panomc.plugins.market.service.platform.UserDirectory
import com.panomc.plugins.market.spi.payment.ReviewReason
import com.panomc.plugins.market.support.FakeMailGateway
import com.panomc.plugins.market.support.MarketTestDb
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * The enqueue wiring of the order mails on a real MariaDB (MK-142; 12 sections 4.1 and 4.2, R8 of 18): each of `ORDER_RECEIVED`,
 * `BANK_TRANSFER_INSTRUCTIONS`, `ORDER_CONFIRMATION`, `GIFT_RECEIVED`, `ORDER_DELIVERED` and `ORDER_REFUNDED` is queued at its transition, inside the
 * transaction of that transition (a rollback leaves no row), by the real `OrderService` / `PaymentService` / `DeliveryService` / `RefundService` with the
 * production [MailEffects] / [OrderMails]; `sendEmailAfterPurchase` switches the order mails only, `mailDisabledKinds` every kind, and the rows compose
 * through the real [MailComposer] and send through `MailOutboxJob`. Invariants I1 to I22 are checked after every test by the base class.
 */
class MailEnqueueIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var ph: PaymentHarness
    private lateinit var orders: OrderService
    private lateinit var payments: PaymentService
    private lateinit var mails: OrderMails
    private lateinit var outbox: MailOutboxService
    private val vertx: Vertx = Vertx.vertx()
    private val emails = ConcurrentHashMap<Long, String>()

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    private fun config(sendEmailAfterPurchase: Boolean = true, disabled: List<String> = emptyList(), deliveredDelayMinutes: Int = 10) = MarketConfig(
        currency = "EUR", statsCurrency = "EUR", vatPercent = 20.0, showVatInPrice = true, creditValue = 1.0, storeTimeZone = "UTC",
        storeName = "Blocky Store", sendEmailAfterPurchase = sendEmailAfterPurchase, mailDisabledKinds = disabled, mailOrderDeliveredDelayMinutes = deliveredDelayMinutes,
        allowGuestCheckout = true, bankTransferExpiryHours = 72, orderExpiryMinutes = 60
    )

    private val directory = object : UserDirectory {
        override suspend fun byUsername(username: String, sqlClient: SqlClient) = w.users.idOf(username)?.let { DirectoryUser(it, w.users.nameOf(it)!!) }

        override suspend fun usernameOf(userId: Long, sqlClient: SqlClient): String? = w.users.nameOf(userId)

        override suspend fun emailOf(userId: Long, sqlClient: SqlClient): String? = emails[userId]

        override suspend fun hasPermission(userId: Long, node: String): Boolean = false
    }

    @BeforeEach
    fun wire() {
        runBlocking { resetState() }
        emails.clear()
        w = TestWiring(pool)
        w.configure { config() }
        ph = PaymentHarness(w, vertx)
        outbox = MailOutboxService({ w.config }, w.clock, w.mailOutbox, w.orderEvents)
        mails = OrderMails({ w.config }, w.clock, outbox, w.mailOutbox, w.orderItems, w.orderEvents, directory)

        // the order and payment services of the harness with the mail wiring of production: MailEffects in front of the recording effects, PaymentMails
        val redemptions = RedemptionService(w.clock, ph.locks, w.redemptions)

        orders = OrderService(
            w.clock, w.ids, w.orders, w.orderItems, w.orderEvents, w.payments, redemptions, { _, _ -> false },
            reservations = ReservationService(w.clock, ph.locks, redemptions, w.orders), settlement = ph.ledger, foreign = MailEffects(mails, w.orders, ph.effects),
            webhooks = PaidWebhooks { conn, orderId -> ph.webhooks.service.emitOrderPaid(conn, orderId) },
            rates = { sqlClient -> w.currencyRates.getAll(sqlClient).filter { it.rate.signum() > 0 }.associate { it.currency to it.rate } },
            statsCurrency = { "EUR" }, receivedMails = mails
        )
        payments = PaymentService(
            db = ph.db, locks = ph.locks, clock = w.clock, ids = w.ids, config = { w.config }, orders = w.orders, orderItems = w.orderItems,
            orderEvents = w.orderEvents, payments = w.payments, methods = w.paymentMethods, creditAccounts = w.creditAccounts, currencyRates = w.currencyRates,
            lookup = ph.lookup, cipher = ph.cipher,
            contexts = PaymentContexts { provider, settings, testMode -> com.panomc.plugins.market.spi.testkit.TestContexts.payment(provider.id, settings, vertx, testMode) },
            orderService = orders, site = { com.panomc.plugins.market.spi.testkit.TestContexts.defaultSite() }, readClient = { w.pool }, products = w.products,
            entitlements = w.entitlements, mails = mails
        )
        checkout = CheckoutService(
            config = { w.config }, clock = w.clock, categories = w.categories, products = w.products, variants = w.variants, prices = w.prices, fields = w.fields,
            bundleItems = w.bundleItems, discounts = w.discounts, coupons = w.coupons, creatorCodes = w.creatorCodes, currencyRates = w.currencyRates,
            redemptions = w.redemptions, orders = w.orders, entitlements = w.entitlements, subscriptions = w.subscriptions, creditAccounts = w.creditAccounts,
            carts = w.carts, cartItems = w.cartItems, paymentMethods = w.paymentMethods, lookup = ph.lookup, cipher = ph.cipher,
            contexts = PaymentContexts { provider, settings, testMode -> com.panomc.plugins.market.spi.testkit.TestContexts.payment(provider.id, settings, vertx, testMode) },
            legal = LegalTextService(w.db, w.clock, w.legalTexts, { "en-US" }), users = directory,
            servers = com.panomc.plugins.market.service.platform.ServerDirectory { _, _ -> emptySet() },
            blocks = BuyerBlocks { _, _, _, _, _, _ -> false }, shipping = ShippingQuoter { _, _ -> ShippingQuote(null) }, addresses = w.addresses,
            checkout = CheckoutDeps(
                db = ph.db, locks = ph.locks, reservations = ReservationService(w.clock, ph.locks, redemptions, w.orders), redemptions = redemptions, orders = orders,
                payments = w.payments, providerMeta = w.providerMeta, starter = payments, replayWaitMs = 0, replayPollMs = 0
            )
        )
    }

    private lateinit var checkout: CheckoutService
    private val keys = java.util.concurrent.atomic.AtomicLong()

    private val fx get() = w.fixtures
    private val h get() = ph.h

    // ------------------------------------------------------------------------------------------------------ helpers

    private suspend fun buy(method: String = "fake", vararg extra: Pair<String, Any?>): MarketOrder {
        val product = fx.product(price = 1000, stock = 5)
        val body = h.body("items" to listOf(h.line(product)), "paymentMethodId" to method, *extra)
        val key = "key-" + keys.incrementAndGet().toString().padStart(16, '0')
        val result = checkout.checkout(com.panomc.plugins.market.routes.api.checkout.parseCheckoutRequest(body, key).copy(orderLocale = "en-US"), QuoteCaller.GUEST, w.pool)

        return ph.order(result.order.getString("publicId"))
    }

    private suspend fun attempt(order: MarketOrder): MarketPayment = ph.attempts(order.id).single()

    private suspend fun pay(order: MarketOrder) {
        val a = attempt(order)

        payments.applyEvent(order.id, a.id, PaymentAttemptEvent.Succeeded(a.amount, a.currency))
    }

    private suspend fun rows(orderId: Long): List<MarketMailOutbox> = w.mailOutbox.getByOrderId(orderId, pool).sortedBy { it.id }

    private suspend fun kinds(orderId: Long): List<MailKind> = rows(orderId).map { it.kind }

    private suspend fun timeline(orderId: Long) = w.orderEvents.getByOrderId(orderId, pool)

    private suspend fun registered(name: String): TestUser = fx.user(name).also { emails[it.id] = "${name.lowercase()}@example.com"; h.emails[it.id] = "${name.lowercase()}@example.com" }

    private val i18n by lazy { MarketI18n(MarketI18n.loadBundles(MailEnqueueIT::class.java.classLoader), { emptyMap() }, w.clock) }
    private val format by lazy { MarketFormat(i18n, { "UTC" }, { "" }) }

    private fun composer(attachments: InvoiceMailAttachments? = null) = MailComposer(
        MailContentBuilder(i18n, format, { w.config }, { MailSite("Blocky Network", "https://shop.example") }),
        w.orders, w.orderItems, w.refunds, w.refundItems, if (attachments != null) w.invoices else null, attachments, { w.config }
    )

    private fun job(gateway: FakeMailGateway, composition: MailComposer = composer()) = MailOutboxJob(
        config = { w.config }, clock = w.clock, service = outbox, gateway = gateway, composition = composition, sqlClient = { pool }, mailEnabled = { true },
        mailOptionsAvailable = { true }
    )

    // ================================================================================== ORDER_CONFIRMATION (O2)

    @Test
    fun `O2 queues ORDER_CONFIRMATION in the transition's transaction, a failing later effect takes the row with it`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val order = buy()

        ph.effects.failOn = "AdvanceGoalProgress"

        assertThrows(IllegalStateException::class.java) { runBlocking { pay(order) } }
        assertEquals(emptyList<MailKind>(), kinds(order.id), "the mail row was written before the failing effect and is gone with the rollback")
        assertEquals(0, timeline(order.id).count { it.type == OrderEventType.MAIL_QUEUED })
        assertEquals(OrderStatus.PENDING, ph.order(order.id).status)

        ph.effects.failOn = null
        pay(order)

        val queued = rows(order.id).single()

        assertEquals(MailKind.ORDER_CONFIRMATION, queued.kind)
        assertEquals(MailRefType.ORDER, queued.refType)
        assertEquals(order.id, queued.refId)
        assertEquals("", queued.refKey)
        assertEquals(order.id, queued.orderId)
        assertEquals("steve@example.com", queued.recipient)
        assertEquals("en-US", queued.locale)
        assertEquals(MailStatus.PENDING, queued.status)
        assertEquals(1, timeline(order.id).count { it.type == OrderEventType.MAIL_QUEUED })

        // O2 does not run twice: a replay of the same event queues nothing more
        pay(order)

        assertEquals(1, rows(order.id).size)
    }

    @Test
    fun `sendEmailAfterPurchase switches the order mails only, the bank transfer instructions are a service mail`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        fx.paymentMethod("bank-transfer", settings = bankSettings())
        w.configure { config(sendEmailAfterPurchase = false) }

        val paid = buy()

        pay(paid)

        assertEquals(emptyList<MailKind>(), kinds(paid.id), "no order mail with the purchase switch off")
        assertEquals(0, timeline(paid.id).count { it.type == OrderEventType.MAIL_QUEUED || it.message?.startsWith("MAIL_NOT_QUEUED") == true }, "a switched-off kind writes nothing")

        val bank = buy("bank-transfer")

        assertEquals(listOf(MailKind.BANK_TRANSFER_INSTRUCTIONS), kinds(bank.id), "the instructions are sent regardless of the purchase switch")
    }

    @Test
    fun `mailDisabledKinds is honoured per kind`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        w.configure { config(disabled = listOf("ORDER_CONFIRMATION")) }

        val order = buy()

        pay(order)

        assertEquals(emptyList<MailKind>(), kinds(order.id))

        // the other kinds still go: the same switch set for GIFT only leaves the confirmation
        w.configure { config(disabled = listOf("GIFT_RECEIVED")) }

        val alex = registered("Alex")
        val gift = buy("fake", "recipientUsername" to alex.username, "giftMessage" to "Enjoy!")

        pay(gift)

        assertEquals(listOf(MailKind.ORDER_CONFIRMATION), kinds(gift.id))
    }

    // ================================================================================== GIFT_RECEIVED (O2)

    @Test
    fun `a gift to a registered recipient with another e-mail queues GIFT_RECEIVED to the recipient besides the confirmation`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val alex = registered("Alex")
        val order = buy("fake", "recipientUsername" to alex.username, "giftMessage" to "Enjoy the rank!")

        assertTrue(order.isGift)

        pay(order)

        val mails = rows(order.id)

        assertEquals(listOf(MailKind.ORDER_CONFIRMATION, MailKind.GIFT_RECEIVED), mails.map { it.kind })
        assertEquals("steve@example.com", mails[0].recipient)
        assertEquals("alex@example.com", mails[1].recipient)
        assertEquals(alex.id, mails[1].userId)
        assertEquals(MailRefType.ORDER, mails[1].refType)

        // composed for the recipient: the sender's name in the subject, the message quoted, no prices, a link to the store without a token
        val content = composer().compose(mails[1], pool)

        assertTrue(content.subject.endsWith("sent you a gift on Blocky Network"), content.subject)
        assertEquals("Enjoy the rank!", content.quote)
        assertTrue(content.items.all { it.total.isEmpty() })
        assertEquals("https://shop.example/store", content.buttonUrl)
    }

    @Test
    fun `a gift whose recipient has no e-mail, or the payer's own e-mail, or is not registered queues no gift mail`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        // no e-mail on the account
        val mute = fx.user("Mute")
        val noMail = buy("fake", "recipientUsername" to mute.username)

        pay(noMail)

        assertEquals(listOf(MailKind.ORDER_CONFIRMATION), kinds(noMail.id))

        // the recipient's address equals the payer's
        val same = registered("Twin")

        emails[same.id] = "steve@example.com"

        val twin = buy("fake", "recipientUsername" to same.username)

        pay(twin)

        assertEquals(listOf(MailKind.ORDER_CONFIRMATION), kinds(twin.id))

        // a plain order is no gift
        val plain = buy()

        pay(plain)

        assertEquals(listOf(MailKind.ORDER_CONFIRMATION), kinds(plain.id))
    }

    // ================================================================================== BANK_TRANSFER_INSTRUCTIONS / ORDER_RECEIVED

    @Test
    fun `an attempt that starts with INSTRUCTIONS queues the bank transfer mail in the start transaction, and the buyer's notice adds no ORDER_RECEIVED`(): Unit = runBlocking {
        fx.paymentMethod("bank-transfer", settings = bankSettings())

        val order = buy("bank-transfer")
        val a = attempt(order)
        val queued = rows(order.id).single()

        assertEquals(MailKind.BANK_TRANSFER_INSTRUCTIONS, queued.kind)
        assertEquals(MailRefType.PAYMENT, queued.refType)
        assertEquals(a.id, queued.refId)
        assertEquals("steve@example.com", queued.recipient)

        val params = JsonObject(queued.params)
        val fields = params.getJsonObject("instructions").getJsonArray("fields").map { (it as JsonObject).getString("value") }

        assertTrue("TR000000000000000000000001" in fields, "the account is in the stored params: $fields")
        assertTrue(a.reference in fields, "the transfer reference is in the stored params: $fields")
        assertNotNull(params.getLong("expiresAt"))

        val content = composer().compose(queued, pool)

        assertEquals("Payment instructions for order #${order.id}", content.subject)
        assertTrue(content.instructionFields.any { it.value == "TR000000000000000000000001" })
        assertTrue(content.details.any { it.label == "Pay before" })

        // the buyer says "I paid" (PENDING -> PROCESSING): the order was acknowledged by the instructions already
        payments.applyEvent(order.id, a.id, PaymentAttemptEvent.Pending)

        assertEquals(PaymentStatus.PROCESSING, attempt(order).status)
        assertEquals(listOf(MailKind.BANK_TRANSFER_INSTRUCTIONS), kinds(order.id))
    }

    @Test
    fun `an attempt reaching PROCESSING queues ORDER_RECEIVED once, an order in review queues it at O3`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val processing = buy()
        val a = attempt(processing)

        payments.applyEvent(processing.id, a.id, PaymentAttemptEvent.Pending)
        payments.applyEvent(processing.id, a.id, PaymentAttemptEvent.Pending)

        assertEquals(listOf(MailKind.ORDER_RECEIVED), kinds(processing.id))
        assertEquals(MailRefType.ORDER, rows(processing.id).single().refType)

        // the money arrives for an order that was waiting: the confirmation follows, the acknowledgement stays
        payments.applyEvent(processing.id, a.id, PaymentAttemptEvent.Succeeded(a.amount, a.currency))

        assertEquals(listOf(MailKind.ORDER_RECEIVED, MailKind.ORDER_CONFIRMATION), kinds(processing.id))

        // O3: a payment that needs a human
        val review = buy()
        val b = attempt(review)

        payments.applyEvent(review.id, b.id, PaymentAttemptEvent.NeedsReview(ReviewReason.FRAUD_REVIEW))

        assertEquals(OrderStatus.REVIEW, ph.order(review.id).status)
        assertEquals(listOf(MailKind.ORDER_RECEIVED), kinds(review.id))
    }

    @Test
    fun `an ORDER_RECEIVED of an order that moved on is obsolete and is not sent`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val order = buy()
        val a = attempt(order)

        payments.applyEvent(order.id, a.id, PaymentAttemptEvent.Pending)

        val received = rows(order.id).single()

        assertFalse(composer().isObsolete(received, pool), "the order still waits")

        payments.applyEvent(order.id, a.id, PaymentAttemptEvent.Succeeded(a.amount, a.currency))

        assertTrue(composer().isObsolete(received, pool), "COMPLETED: the confirmation says everything")

        val gateway = FakeMailGateway()

        job(gateway).runOnce()

        val byKind = rows(order.id).associateBy { it.kind }

        assertEquals(MailStatus.SKIPPED, byKind.getValue(MailKind.ORDER_RECEIVED).status)
        assertEquals("OBSOLETE", byKind.getValue(MailKind.ORDER_RECEIVED).lastError)
        assertEquals(MailStatus.SENT, byKind.getValue(MailKind.ORDER_CONFIRMATION).status)
        assertEquals(listOf("Your order #${order.id} is confirmed"), gateway.sent.map { it.content.subject })
    }

    // ================================================================================== the outbox job sends what the transitions queued

    @Test
    fun `the job composes the confirmation from the database and hands the translated mail to the gateway`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val order = buy()

        pay(order)

        val gateway = FakeMailGateway()

        assertEquals(1, job(gateway).runOnce())

        val sent = gateway.sent.single()

        assertEquals("steve@example.com", sent.recipient)
        assertEquals("en-US", sent.locale)
        assertEquals("Your order #${order.id} is confirmed", sent.content.subject)
        assertTrue(sent.content.items.single().name.isNotEmpty())
        assertEquals("https://shop.example/store/order/${order.publicId}?token=${order.accessToken}", sent.content.buttonUrl, "a guest order link carries the token")
        assertEquals(MailStatus.SENT, rows(order.id).single().status)
        assertFalse(sent.content.toText().contains("{"), "no placeholder is left in the text")
    }

    // ================================================================================== ORDER_DELIVERED

    private fun deliveryService(d: DeliveryWorld) = DeliveryService(
        w.db, d.locks, w.clock, w.ids, { w.config }, w.orders, w.orderItems, w.orderEvents, w.deliveries, w.entitlements, w.creditAccounts, w.products, w.fields,
        d.roster, directory, d.playerAccounts, d.credits, d.permissionService, kotlin.random.Random(7), null, { it }, null, mails
    )

    private suspend fun runDeliveries(service: DeliveryService) {
        for (row in service.claimDue()) service.execute(row)
    }

    private fun credit(id: String) = com.panomc.plugins.market.core.delivery.ProductAction(id = id, type = DeliveryActionType.CREDIT, credit = 500)

    @Test
    fun `ORDER_DELIVERED is queued when the delivery finishes after mailOrderDeliveredDelayMinutes, in the transaction that finished it`(): Unit = runBlocking {
        val u = registered("Steve")
        val d = DeliveryWorld(w)

        w.configure { config(deliveredDelayMinutes = 10) }

        val placed = d.place(user = u, actions = listOf(credit("a1")))

        d.pay(placed)

        w.clock.advance(11 * 60_000L)
        runDeliveries(deliveryService(d))

        assertEquals(com.panomc.plugins.market.db.model.FulfillmentStatus.FULFILLED, d.order(placed.order.id).fulfillmentStatus)

        val queued = rows(placed.order.id).single()

        assertEquals(MailKind.ORDER_DELIVERED, queued.kind)
        assertEquals("steve@example.com", queued.recipient)
        assertEquals(MailRefType.ORDER, queued.refType)

        val content = composer().compose(queued, pool)

        assertEquals("Your order #${placed.order.id} has been delivered", content.subject)
        assertTrue(content.totals.isEmpty(), "no totals in the delivered mail")
    }

    @Test
    fun `a delivery inside the delay window queues no ORDER_DELIVERED, a zero delay queues it at once, a switched-off kind never`(): Unit = runBlocking {
        val u = registered("Steve")
        val d = DeliveryWorld(w)

        w.configure { config(deliveredDelayMinutes = 10) }

        val quick = d.place(user = u, actions = listOf(credit("a1")))

        d.pay(quick)
        w.clock.advance(60_000L)
        runDeliveries(deliveryService(d))

        assertEquals(com.panomc.plugins.market.db.model.FulfillmentStatus.FULFILLED, d.order(quick.order.id).fulfillmentStatus)
        assertEquals(emptyList<MailKind>(), kinds(quick.order.id), "the confirmation already said it: delivered within the delay")

        w.configure { config(deliveredDelayMinutes = 0) }

        val instant = d.place(user = u, actions = listOf(credit("b1")))

        d.pay(instant)
        runDeliveries(deliveryService(d))

        assertEquals(listOf(MailKind.ORDER_DELIVERED), kinds(instant.order.id))

        w.configure { config(deliveredDelayMinutes = 0, disabled = listOf("ORDER_DELIVERED")) }

        val off = d.place(user = u, actions = listOf(credit("c1")))

        d.pay(off)
        runDeliveries(deliveryService(d))

        assertEquals(emptyList<MailKind>(), kinds(off.order.id))

        w.configure { config(deliveredDelayMinutes = 0, sendEmailAfterPurchase = false) }

        val silent = d.place(user = u, actions = listOf(credit("d1")))

        d.pay(silent)
        runDeliveries(deliveryService(d))

        assertEquals(emptyList<MailKind>(), kinds(silent.order.id), "ORDER_DELIVERED is an order mail: the purchase switch turns it off")
    }

    // ================================================================================== ORDER_DELIVERED: first fulfillment only (review fix)

    private fun permission(id: String, node: String) = ProductAction(id = id, type = DeliveryActionType.PERMISSION, nodes = listOf(node))

    /** A second line on a placed order (before it is paid), with its own product and [actions]; [physical] marks a shipped line. */
    private suspend fun secondLine(placed: Placed, actions: List<ProductAction>, physical: Boolean = false): MarketOrderItem {
        val product = w.fixtures.product(actions = JsonArray(actions.map { it.toJson() }).encode())
        val snapshot = JsonObject().put("slug", product.slug).put("billingMode", "ONE_TIME").put("actions", JsonArray(actions.map { it.toJson() }))
        val now = w.clock.now()
        val id = w.orderItems.add(
            MarketOrderItem(
                orderId = placed.order.id, productId = product.id, productName = "Extra", quantity = 1, unitPrice = 500, lineTotal = 500, listUnitPrice = 500,
                physical = physical, snapshot = snapshot.encode(), createdAt = now, updatedAt = now
            ),
            pool
        )

        // the order's money follows its lines (I8), the product's sold count its units (I17)
        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_product` SET `soldCount` = `soldCount` + 1 WHERE `id` = ?", product.id)
        MarketTestDb.sql(
            pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order` SET `subtotal` = `subtotal` + 500, `totalPrice` = `totalPrice` + 500, `gatewayAmount` = `gatewayAmount` + 500, " +
                "`paidAmount` = `paidAmount` + 500 WHERE `id` = ?", placed.order.id
        )

        return w.orderItems.getById(id, pool)!!
    }

    private suspend fun fulfillment(orderId: Long) = w.orders.getById(orderId, pool)!!.fulfillmentStatus

    @Test
    fun `an expiry that is confirmed later makes the order FULFILLED again and sends no second ORDER_DELIVERED`(): Unit = runBlocking {
        val u = registered("Steve")
        val d = DeliveryWorld(w)
        val service = deliveryService(d)

        w.configure { config(deliveredDelayMinutes = 10) }

        val placed = d.place(user = u, actions = listOf(permission("a1", "group.vip")), billing = "TIMED", periodUnit = "DAY", periodCount = 30)

        d.pay(placed)
        runDeliveries(service)

        assertEquals(FulfillmentStatus.FULFILLED, fulfillment(placed.order.id))
        assertEquals(emptyList<MailKind>(), kinds(placed.order.id), "instant delivery inside the window: no ORDER_DELIVERED")

        w.clock.advance(31 * 86_400_000L)

        w.db.txRestartingOnOrderChange { conn ->
            d.locks.forOrder(conn, placed.order.id, OrderLockScope.COMMIT) { locked ->
                service.planEnd(conn, locked.order, locked.items)

                for (e in w.entitlements.getByOrderItemId(locked.items[0].id, conn)) w.entitlements.end(e.id, EntitlementStatus.EXPIRED, "EXPIRED", w.clock.now(), conn)

                service.refreshFulfillment(conn, locked.order.id)
            }
        }

        assertEquals(FulfillmentStatus.PARTIAL, fulfillment(placed.order.id), "the open EXPIRE row keeps the order PARTIAL")

        runDeliveries(service)

        assertEquals(DeliveryStatus.CONFIRMED, d.rows(placed.order.id).single { it.phase == DeliveryPhase.EXPIRE }.status)
        assertEquals(FulfillmentStatus.FULFILLED, fulfillment(placed.order.id), "the confirmed EXPIRE row brings it back to FULFILLED")
        assertEquals(emptyList<MailKind>(), kinds(placed.order.id), "weeks after payment: still no 'delivered' mail for an expiry")
    }

    @Test
    fun `a partial refund whose REVOKE rows are confirmed sends no ORDER_DELIVERED`(): Unit = runBlocking {
        val u = registered("Steve")
        val d = DeliveryWorld(w)
        val service = deliveryService(d)

        w.configure { config(deliveredDelayMinutes = 10) }

        val placed = d.place(user = u, actions = listOf(permission("a1", "group.vip")))

        secondLine(placed, listOf(credit("a2")))
        d.pay(placed)
        runDeliveries(service)

        assertEquals(FulfillmentStatus.FULFILLED, fulfillment(placed.order.id))
        assertEquals(emptyList<MailKind>(), kinds(placed.order.id))

        w.clock.advance(11 * 60_000L)
        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order` SET `status` = 'PARTIALLY_REFUNDED' WHERE `id` = ?", placed.order.id)

        w.db.txRestartingOnOrderChange { conn ->
            d.locks.forOrder(conn, placed.order.id, OrderLockScope.COMMIT) { locked ->
                service.planRevoke(conn, locked.order, locked.items, mapOf(placed.items[0].id to (0..0)))

                for (e in w.entitlements.getByOrderItemId(placed.items[0].id, conn)) w.entitlements.end(e.id, EntitlementStatus.REVOKED, "REFUND", w.clock.now(), conn)

                service.refreshFulfillment(conn, locked.order.id)
            }
        }

        assertEquals(FulfillmentStatus.PARTIAL, fulfillment(placed.order.id))

        runDeliveries(service)

        assertTrue(d.rows(placed.order.id).any { it.phase == DeliveryPhase.REVOKE && it.status == DeliveryStatus.CONFIRMED })
        assertEquals(FulfillmentStatus.FULFILLED, fulfillment(placed.order.id))
        assertEquals(emptyList<MailKind>(), kinds(placed.order.id), "a refund's confirmed undo is not a delivery")
    }

    @Test
    fun `an admin re-run on an already fulfilled order sends no ORDER_DELIVERED`(): Unit = runBlocking {
        val u = registered("Steve")
        val d = DeliveryWorld(w)
        val service = deliveryService(d)

        w.configure { config(deliveredDelayMinutes = 10) }

        val placed = d.place(user = u, actions = listOf(credit("a1")))

        d.pay(placed)
        runDeliveries(service)

        assertEquals(FulfillmentStatus.FULFILLED, fulfillment(placed.order.id))
        assertEquals(emptyList<MailKind>(), kinds(placed.order.id))

        w.clock.advance(11 * 60_000L)

        // the planner of the panel re-run: the same logical delivery as attempt group 1
        val inserted = w.db.txRestartingOnOrderChange { conn ->
            d.locks.forOrder(conn, placed.order.id, OrderLockScope.COMMIT) { locked ->
                service.insertPlanned(conn, service.planAgain(conn, locked.order, locked.items, DeliveryPhase.GRANT, 1))
            }
        }

        assertEquals(1, inserted.size)
        assertEquals(FulfillmentStatus.PENDING, fulfillment(placed.order.id))

        runDeliveries(service)

        assertEquals(FulfillmentStatus.FULFILLED, fulfillment(placed.order.id))
        assertEquals(emptyList<MailKind>(), kinds(placed.order.id), "FULFILLED -> PENDING -> FULFILLED by a re-run is not the first time")
    }

    @Test
    fun `firstFulfillment tells the first success from an undo, a renewal and a re-run of a delivery that already succeeded`() {
        fun row(group: Int, status: DeliveryStatus, phase: DeliveryPhase = DeliveryPhase.GRANT, action: String = "a1") =
            MarketDelivery(orderId = 1, orderItemId = 1, actionId = action, phase = phase, attemptGroup = group, status = status)

        assertTrue(DeliveryService.firstFulfillment(listOf(row(0, DeliveryStatus.CONFIRMED))))
        assertTrue(DeliveryService.firstFulfillment(listOf(row(0, DeliveryStatus.CONFIRMED), row(0, DeliveryStatus.CONFIRMED, action = "a2"))))

        // a retry of a delivery that failed first is still the first fulfillment
        assertTrue(DeliveryService.firstFulfillment(listOf(row(0, DeliveryStatus.FAILED), row(1, DeliveryStatus.CONFIRMED))))

        // a re-run of one that had succeeded is not
        assertFalse(DeliveryService.firstFulfillment(listOf(row(0, DeliveryStatus.CONFIRMED), row(1, DeliveryStatus.CONFIRMED))))

        for (phase in listOf(DeliveryPhase.RENEW, DeliveryPhase.EXPIRE, DeliveryPhase.REVOKE)) {
            assertFalse(DeliveryService.firstFulfillment(listOf(row(0, DeliveryStatus.CONFIRMED), row(0, DeliveryStatus.CONFIRMED, phase))), phase.name)
        }
    }

    // ================================================================================== manual orders: sendMail = false (06 section 14.3)

    private suspend fun manualOrder(d: DeliveryWorld, u: TestUser, sendMail: Boolean, lineTotal: Long = 1000, source: OrderSource = OrderSource.PANEL): Placed {
        val placed = d.place(user = u, actions = listOf(credit("a1")), source = source, lineTotal = lineTotal)
        val now = w.clock.now()

        w.orderEvents.add(
            MarketOrderEvent(
                orderId = placed.order.id, type = OrderEventType.CREATED, actorType = OrderActorType.ADMIN,
                data = JsonObject().put("runDeliveries", true).put("sendMail", sendMail).encode(), createdAt = now, updatedAt = now
            ),
            pool
        )

        return placed
    }

    @Test
    fun `a manual order with sendMail = false queues no order mail through any seam, with sendMail = true it queues them`(): Unit = runBlocking {
        val u = registered("Steve")
        val d = DeliveryWorld(w)
        val service = deliveryService(d)

        w.configure { config(deliveredDelayMinutes = 10) }

        val silent = manualOrder(d, u, sendMail = false)

        d.pay(silent)
        w.clock.advance(11 * 60_000L)
        runDeliveries(service)

        assertEquals(FulfillmentStatus.FULFILLED, fulfillment(silent.order.id))

        w.db.tx { conn ->
            val order = w.orders.getById(silent.order.id, conn)!!

            mails.paid(conn, order)
            mails.received(conn, order)
        }

        assertEquals(emptyList<MailKind>(), kinds(silent.order.id), "no ORDER_CONFIRMATION, ORDER_RECEIVED or ORDER_DELIVERED for a silent manual order")

        val loud = manualOrder(d, u, sendMail = true)

        d.pay(loud)
        w.clock.advance(11 * 60_000L)
        runDeliveries(service)

        w.db.tx { conn -> mails.paid(conn, w.orders.getById(loud.order.id, conn)!!) }

        assertEquals(listOf(MailKind.ORDER_DELIVERED, MailKind.ORDER_CONFIRMATION), kinds(loud.order.id))
    }

    @Test
    fun `a bank transfer instruction of a manual order stays a service mail even with sendMail = false`(): Unit = runBlocking {
        val u = registered("Steve")
        val d = DeliveryWorld(w)
        val silent = manualOrder(d, u, sendMail = false)

        // PaymentMails.instructions is not asked for the flag: the payer of a markPaid = false order cannot pay without it
        val attempt = MarketPayment(orderId = silent.order.id, id = 1)
        val start = JsonObject().put("instructions", JsonObject().put("body", "Pay").put("fields", JsonArray())).put("expiresAt", w.clock.now() + 1000)

        w.db.tx { conn -> mails.instructions(conn, w.orders.getById(silent.order.id, conn)!!, attempt, start) }

        assertEquals(listOf(MailKind.BANK_TRANSFER_INSTRUCTIONS), kinds(silent.order.id))
    }

    @Test
    fun `a renewal that costs nothing queues no confirmation, a paid renewal does`(): Unit = runBlocking {
        val u = registered("Steve")
        val d = DeliveryWorld(w)
        val free = d.place(user = u, actions = listOf(credit("a1")), source = OrderSource.RENEWAL, lineTotal = 0)
        val paid = d.place(user = u, actions = listOf(credit("b1")), source = OrderSource.RENEWAL, lineTotal = 1000)

        w.db.tx { conn ->
            mails.paid(conn, w.orders.getById(free.order.id, conn)!!)
            mails.paid(conn, w.orders.getById(paid.order.id, conn)!!)
        }

        assertEquals(emptyList<MailKind>(), kinds(free.order.id))
        assertEquals(listOf(MailKind.ORDER_CONFIRMATION), kinds(paid.order.id))
    }

    @Test
    fun `ORDER_DELIVERED needs a non-physical line when the order ships, and a COMPLETED or PARTIALLY_REFUNDED order`(): Unit = runBlocking {
        val u = registered("Steve")
        val d = DeliveryWorld(w)
        val service = deliveryService(d)

        w.configure { config(deliveredDelayMinutes = 0) }

        // only physical lines: the shipment mails tell the story
        val shipped = d.place(user = u, actions = listOf(credit("a1")))

        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order` SET `requiresShipping` = 1 WHERE `id` = ?", shipped.order.id)
        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order_item` SET `physical` = 1 WHERE `orderId` = ?", shipped.order.id)
        d.pay(shipped)
        runDeliveries(service)

        assertEquals(FulfillmentStatus.FULFILLED, fulfillment(shipped.order.id))
        assertEquals(emptyList<MailKind>(), kinds(shipped.order.id), "a shipping order with only physical lines gets no ORDER_DELIVERED")

        // a digital line next to the physical one: that line was delivered
        val mixed = d.place(user = u, actions = listOf(credit("b1")))

        secondLine(mixed, listOf(credit("b2")))
        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order` SET `requiresShipping` = 1 WHERE `id` = ?", mixed.order.id)
        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order_item` SET `physical` = 1 WHERE `id` = ?", mixed.items[0].id)
        d.pay(mixed)
        runDeliveries(service)

        assertEquals(listOf(MailKind.ORDER_DELIVERED), kinds(mixed.order.id))

        // the order status condition: a refunded order is not "delivered" (the status is put back, the order is only the seam's input)
        val refunded = d.place(user = u, actions = listOf(credit("c1")))

        d.pay(refunded)
        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order` SET `status` = 'REFUNDED' WHERE `id` = ?", refunded.order.id)
        w.db.tx { conn -> mails.fulfilled(conn, w.orders.getById(refunded.order.id, conn)!!) }
        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order` SET `status` = 'COMPLETED' WHERE `id` = ?", refunded.order.id)

        assertEquals(emptyList<MailKind>(), kinds(refunded.order.id), "REFUNDED: no ORDER_DELIVERED")

        w.db.tx { conn -> mails.fulfilled(conn, w.orders.getById(refunded.order.id, conn)!!) }

        assertEquals(listOf(MailKind.ORDER_DELIVERED), kinds(refunded.order.id), "the same order as COMPLETED does get it")

        // PARTIALLY_REFUNDED is explicitly allowed
        val partial = d.place(user = u, actions = listOf(credit("d1")))

        d.pay(partial)
        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order` SET `status` = 'PARTIALLY_REFUNDED' WHERE `id` = ?", partial.order.id)
        runDeliveries(service)

        assertEquals(listOf(MailKind.ORDER_DELIVERED), kinds(partial.order.id))
    }

    // ================================================================================== ORDER_REFUNDED (O10)

    @Test
    fun `O10 queues ORDER_REFUNDED once for the payer and the row composes with the refund figures`(): Unit = runBlocking {
        val r = RefundWorld(w, vertx)
        val u = registered("Steve")
        val paid = r.place(u, listOf(RefundLine(1000)), email = "steve@example.com")
        val done = r.service.request(paid.order.id, RefundInput(amount = 400), r.key(), null)
        val queued = rows(paid.order.id).single()

        assertEquals(MailKind.ORDER_REFUNDED, queued.kind)
        assertEquals(MailRefType.REFUND, queued.refType)
        assertEquals(done.refund.id, queued.refId)
        assertEquals("steve@example.com", queued.recipient)

        val content = composer().compose(queued, pool)

        assertEquals("Refund for order #${paid.order.id}", content.subject)
        assertEquals("€4.00", content.details.single { it.label == "Refund amount" }.value)
        assertNull(content.secondaryLabel)
        assertFalse(composer().isObsolete(queued, pool))
    }

    @Test
    fun `ORDER_REFUNDED follows the purchase switch like every order mail and mailDisabledKinds`(): Unit = runBlocking {
        val r = RefundWorld(w, vertx)
        val u = registered("Steve")

        w.configure { config(sendEmailAfterPurchase = false) }

        val first = r.place(u, listOf(RefundLine(1000)), email = "steve@example.com")

        r.service.request(first.order.id, RefundInput(amount = 400), r.key(), null)

        assertEquals(emptyList<MailKind>(), kinds(first.order.id))

        w.configure { config(disabled = listOf("ORDER_REFUNDED")) }

        val second = r.place(u, listOf(RefundLine(1000)), email = "steve@example.com")

        r.service.request(second.order.id, RefundInput(amount = 400), r.key(), null)

        assertEquals(emptyList<MailKind>(), kinds(second.order.id))
    }

    // ================================================================================== the invoice attachment (12 section 4.4, MK-143 / MK-144 seam)

    @Test
    fun `the confirmation carries the invoice PDF and the refund mail the credit note, the footer says so, mailAttachInvoice off attaches none`(): Unit = runBlocking {
        val invoices = InvoiceService(
            config = { w.config }, clock = w.clock, orders = w.orders, orderEvents = w.orderEvents, refunds = w.refunds, refundItems = w.refundItems, invoices = w.invoices,
            sequences = w.sequences, site = { InvoiceSite("Blocky Network", "https://shop.example") }, defaultLocale = { "en-US" }
        )
        val world = RefundWorld(w, vertx, invoices)
        val seller = world.config(seller = true)

        w.configure { seller }

        val u = registered("Steve")
        val paid = world.place(u, listOf(RefundLine(1000)), email = "steve@example.com")

        w.db.tx { conn ->
            val order = w.orders.getById(paid.order.id, conn)!!

            invoices.issueForOrder(conn, order, w.orderItems.getByOrderIds(listOf(order.id), conn))
            mails.paid(conn, order)
        }

        world.service.request(paid.order.id, RefundInput(amount = 400), world.key(), null)

        assertEquals(listOf(MailKind.ORDER_CONFIRMATION, MailKind.ORDER_REFUNDED), kinds(paid.order.id))

        val documents = InvoiceDocuments(
            base = java.nio.file.Files.createTempDirectory("market-mail-invoices"), invoices = w.invoices, clock = w.clock,
            texts = { snapshot, locale -> InvoiceTextsFactory.build(snapshot, locale, i18n, format, "") }, logo = { null }, onWorker = { it() }, sqlClient = { pool }
        )
        val gateway = FakeMailGateway()

        job(gateway, composer(InvoiceMailAttachments(w.invoices, documents) { w.config.mailAttachInvoice })).runOnce()

        val confirmation = gateway.sent.single { it.content.subject.contains("is confirmed") }
        val refund = gateway.sent.single { it.content.subject.startsWith("Refund for order") }

        assertEquals(2, gateway.sent.size)
        assertEquals(1, confirmation.attachments.size)
        assertTrue(confirmation.attachments.single().name.matches(Regex("INV-\\d{4}-\\d{6}\\.pdf")), confirmation.attachments.single().name)
        assertEquals("application/pdf", confirmation.attachments.single().contentType)
        assertEquals("%PDF", String(confirmation.attachments.single().data.copyOfRange(0, 4)))
        assertTrue(confirmation.content.footerNote!!.contains("invoice is attached"), confirmation.content.footerNote)
        assertEquals(1, refund.attachments.size)
        assertTrue(refund.attachments.single().name.matches(Regex("CN-\\d{4}-\\d{6}\\.pdf")), refund.attachments.single().name)
        assertTrue(refund.content.footerNote!!.contains("credit note is attached"), refund.content.footerNote)

        // the switch off: the same rows, no attachment and no promise in the footer
        for (row in rows(paid.order.id)) outbox.enqueue(pool, row.kind, row.refType, row.refId, "again", row.orderId, row.userId, row.recipient, row.locale)

        w.configure { MarketConfig(currency = "EUR", vatPercent = 20.0, showVatInPrice = true, creditValue = 1.0, storeTimeZone = "UTC", mailAttachInvoice = false) }

        val quiet = FakeMailGateway()

        job(quiet, composer(InvoiceMailAttachments(w.invoices, documents) { w.config.mailAttachInvoice })).runOnce()

        assertEquals(2, quiet.sent.size)
        assertTrue(quiet.sent.all { it.attachments.isEmpty() })
        assertTrue(quiet.sent.none { it.content.footerNote!!.contains("attached") })
    }

    // ================================================================================== a row without its reference is never sent (MK-146 owns the other kinds)

    @Test
    fun `a shipment mail whose parcel does not exist ends SKIPPED OBSOLETE and is never sent`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val order = buy()

        outbox.enqueue(pool, MailKind.SHIPMENT_SHIPPED, MailRefType.SHIPMENT, 1, "", order.id, null, "steve@example.com", "en-US")

        val gateway = FakeMailGateway()

        job(gateway).runOnce()

        val row = rows(order.id).single()

        assertEquals(MailStatus.SKIPPED, row.status)
        assertEquals("OBSOLETE", row.lastError)
        assertEquals(0, gateway.sent.size)
    }
}
