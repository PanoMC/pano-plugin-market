package com.panomc.plugins.market.service

import com.google.gson.JsonObject as GsonObject
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.Error
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.order.OrderEvent
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.OrderActorType
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.db.tx.OrderChild
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.routes.api.checkout.parseCheckoutRequest
import com.panomc.plugins.market.routes.api.order.parseBankTransferNotice
import com.panomc.plugins.market.routes.api.order.requireBuyerNotice
import com.panomc.plugins.market.spi.testkit.TestContexts
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.CurrencyType
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * The object graph of the bank transfer and manual order tests (17 section 5.3): the real [CheckoutService] (with its checkout wiring), [OrderService],
 * [PaymentService], [OrderReviewService] and [BankTransferService] on the DAOs of a [TestWiring], the free / credits / bank transfer built-ins and the
 * scriptable fake provider from [PaymentHarness]. `config` is read at call time, so a test turns `bankTransferExpiryHours` or `allowGuestCheckout`.
 */
internal class BankRig(val w: TestWiring, private val vertx: Vertx) {
    val ph = PaymentHarness(w, vertx)

    @Volatile
    var config: MarketConfig = config()

    @Volatile
    var requireNotice = false

    @Volatile
    var blockedNames: Set<String> = emptySet()

    @Volatile
    var shippingResult: ShippingQuote? = null

    val emails = ConcurrentHashMap<Long, String>()
    val alerts = CopyOnWriteArrayList<Pair<Long, String?>>()
    private val keys = AtomicLong()

    lateinit var orders: OrderService
        private set
    lateinit var payments: PaymentService
        private set
    lateinit var checkout: CheckoutService
        private set
    lateinit var review: OrderReviewService
        private set
    lateinit var bank: BankTransferService
        private set

    fun config(bankHours: Int = 72, guests: Boolean = true, orderExpiryMinutes: Int = 60) = MarketConfig(
        currency = CurrencyType.EUR, statsCurrency = CurrencyType.EUR, vatPercent = 20.0, showVatInPrice = true, creditValue = 1.0, storeTimeZone = "UTC",
        allowGuestCheckout = guests, bankTransferExpiryHours = bankHours, orderExpiryMinutes = orderExpiryMinutes
    )

    private val directory = object : com.panomc.plugins.market.service.platform.UserDirectory {
        override suspend fun byUsername(username: String, sqlClient: io.vertx.sqlclient.SqlClient) =
            w.users.idOf(username)?.let { com.panomc.plugins.market.service.platform.DirectoryUser(it, w.users.nameOf(it)!!) }

        override suspend fun usernameOf(userId: Long, sqlClient: io.vertx.sqlclient.SqlClient): String? = w.users.nameOf(userId)

        override suspend fun emailOf(userId: Long, sqlClient: io.vertx.sqlclient.SqlClient): String? = emails[userId]

        override suspend fun hasPermission(userId: Long, node: String): Boolean = false
    }

    fun rebuild() {
        val redemptions = RedemptionService(w.clock, ph.locks, w.redemptions)
        val reservations = ReservationService(w.clock, ph.locks, redemptions, w.orders)

        orders = OrderService(
            w.clock, w.ids, w.orders, w.orderItems, w.orderEvents, w.payments, redemptions, { _, _ -> false },
            reservations = reservations, settlement = ph.ledger, foreign = ph.effects,
            webhooks = PaidWebhooks { conn, orderId -> ph.webhooks.service.emitOrderPaid(conn, orderId) },
            rates = { sqlClient -> w.currencyRates.getAll(sqlClient).filter { it.rate.signum() > 0 }.associate { it.currency to it.rate } },
            statsCurrency = { "EUR" }, limits = ProductPurchaseLimits(w.orders, w.products, w.entitlements, w.clock), refunds = w.refunds,
            duplicates = DuplicateRefundPolicy { conn, providerId -> payments.duplicateRefundRule(conn, providerId) }
        )
        payments = PaymentService(
            db = ph.db, locks = ph.locks, clock = w.clock, ids = w.ids, config = { config }, orders = w.orders, orderItems = w.orderItems, orderEvents = w.orderEvents,
            payments = w.payments, methods = w.paymentMethods, creditAccounts = w.creditAccounts, currencyRates = w.currencyRates, lookup = ph.lookup, cipher = ph.cipher,
            contexts = PaymentContexts { provider, settings, testMode -> TestContexts.payment(provider.id, settings, vertx, testMode) },
            orderService = orders, site = { TestContexts.defaultSite() }, readClient = { w.pool }, products = w.products, entitlements = w.entitlements,
            alerts = PanelAlerts { orderId, reason -> alerts += orderId to reason }
        )
        review = OrderReviewService(ph.db, ph.locks, w.orders, w.payments, w.orderEvents, w.clock, orders, { false }, { after -> payments.runAfterCommit(after, w.pool) })
        bank = BankTransferService(ph.db, ph.locks, w.clock, w.orders, w.payments, w.orderEvents, payments, { false }, { requireNotice }, { w.pool })
        checkout = CheckoutService(
            config = { config }, clock = w.clock, categories = w.categories, products = w.products, variants = w.variants, prices = w.prices, fields = w.fields,
            bundleItems = w.bundleItems, discounts = w.discounts, coupons = w.coupons, creatorCodes = w.creatorCodes, currencyRates = w.currencyRates,
            redemptions = w.redemptions, orders = w.orders, entitlements = w.entitlements, subscriptions = w.subscriptions, creditAccounts = w.creditAccounts,
            carts = w.carts, cartItems = w.cartItems, paymentMethods = w.paymentMethods, lookup = ph.lookup, cipher = ph.cipher,
            contexts = PaymentContexts { provider, settings, testMode -> TestContexts.payment(provider.id, settings, vertx, testMode) },
            legal = LegalTextService(w.db, w.clock, w.legalTexts, { "en-US" }), users = directory, servers = com.panomc.plugins.market.service.platform.ServerDirectory { _, _ -> emptySet() },
            blocks = BuyerBlocks { payer, recipient, _, _, _, _ -> (payer?.lowercase() in blockedNames) || (recipient?.lowercase() in blockedNames) },
            shipping = ShippingQuoter { _, _ -> shippingResult ?: ShippingQuote(null) }, addresses = w.addresses,
            checkout = CheckoutDeps(
                db = ph.db, locks = ph.locks, reservations = reservations, redemptions = redemptions, orders = orders, payments = w.payments,
                providerMeta = w.providerMeta, starter = payments, replayWaitMs = 0, replayPollMs = 0
            )
        )
    }

    fun nextKey(): String = "key-" + keys.incrementAndGet().toString().padStart(16, '0')

    init {
        rebuild()
    }

    /** A storefront checkout of one unit of [product] paid with [method], the way the route parses it. */
    suspend fun buy(product: com.panomc.plugins.market.db.model.MarketProduct, method: String = "bank-transfer", caller: QuoteCaller = QuoteCaller.GUEST, quantity: Int = 1): CheckoutResult {
        val body = JsonObject()
            .put("items", listOf(mapOf("productId" to product.id, "quantity" to quantity)))
            .put("paymentMethodId", method)

        if (!caller.loggedIn) body.put("guest", mapOf("username" to "Steve", "email" to "steve@example.com"))

        return checkout.checkout(parseCheckoutRequest(body, nextKey()).copy(orderLocale = "en-US"), caller, w.pool)
    }
}

/** The accounts of the bank transfer method in the JSON the settings store. */
internal fun bankSettings(): GsonObject = GsonObject().apply {
    addProperty("accounts", """[{"bank":"Test Bank","holder":"Pano Shop","iban":"TR000000000000000000000001","currency":"EUR"}]""")
}

/**
 * The bank transfer flow on a real MariaDB (MK-094; 06 section 14.1, 02 section 12, 04 sections 3 and 7, P-18 twin): the instructions with the attempt
 * reference, the buyer's notice that moves the attempt to `PROCESSING` without extending anything, the admin's approval (O2, or O9 for money that
 * arrived late) and rejection, `bankTransferExpiryHours`, the states that refuse (409 `ORDER_NOT_PAYABLE`), the races. The invariants I1 to I22 are
 * checked after every test by the base class.
 */
class BankTransferIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var rig: BankRig
    private val vertx: Vertx = Vertx.vertx()
    private val admin = 77L

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    @BeforeEach
    fun freshState() {
        runBlocking { resetState() }
        w = TestWiring(pool)
        rig = BankRig(w, vertx)
    }

    private val fx get() = w.fixtures
    private val ph get() = rig.ph

    // ------------------------------------------------------------------------------------------------------ helpers

    private suspend fun bankOrder(price: Long = 1000, stock: Int? = 5): Pair<MarketOrder, com.panomc.plugins.market.db.model.MarketProduct> {
        if (w.paymentMethods.getByMethodId("bank-transfer", pool) == null) fx.paymentMethod("bank-transfer", settings = bankSettings())

        val product = fx.product(price = price, stock = stock)
        val result = rig.buy(product)

        return ph.order(result.order.getString("publicId")) to product
    }

    private suspend fun attempt(orderId: Long): MarketPayment = ph.attempts(orderId).single()

    private suspend fun order(id: Long) = ph.order(id)

    private suspend fun stock(product: com.panomc.plugins.market.db.model.MarketProduct): Int? = w.products.getById(product.id, pool)!!.stock

    private suspend fun timeline(orderId: Long) = w.orderEvents.getByOrderId(orderId, pool)

    private suspend fun notify(order: MarketOrder, sender: String? = "Ayşe Yılmaz", note: String? = "EFT 14:02") = rig.bank.notify(order.id, sender, note, order.userId)

    private suspend fun decide(order: MarketOrder, decision: BankTransferDecision, note: String? = null) = rig.bank.decide(order.id, decision, note, admin)

    private suspend fun expect(code: String, status: Int, block: suspend () -> Any?): JsonObject {
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

    /** O6 as the expiry job applies it: the clock passes `expiresAt`, the machine decides under the `RELEASE` locks. */
    private suspend fun expire(orderId: Long) {
        val order = order(orderId)

        w.clock.advance(order.expiresAt!! - w.clock.now() + 1)

        // the expiry job first expires the open attempts (a PROCESSING one keeps the order alive), then the order
        for (open in ph.attempts(orderId).filter { it.status in setOf(PaymentStatus.CREATED, PaymentStatus.PENDING, PaymentStatus.PROCESSING) }) {
            rig.payments.applyEvent(orderId, open.id, com.panomc.plugins.market.core.payment.PaymentAttemptEvent.Expired, actor = com.panomc.plugins.market.core.order.OrderActor.SYSTEM)
        }

        ph.db.txRestartingOnOrderChange { conn ->
            ph.locks.forOrder(conn, orderId, OrderLockScope.RELEASE) { locked ->
                ph.locks.children(conn, orderId, OrderChild.PAYMENT)

                rig.orders.transition(conn, locked, OrderEvent.Expire(w.clock.now()))
            }
        }

        assertEquals(OrderStatus.EXPIRED, order(orderId).status)
    }

    // ============================================================================================ instructions (P-18)

    @Test
    fun `instructions carry the attempt reference, the exact amount and the account, the attempt waits PENDING`(): Unit = runBlocking {
        fx.paymentMethod("bank-transfer", settings = bankSettings())

        val product = fx.product(price = 1000, stock = 5)
        val result = rig.buy(product)
        val order = ph.order(result.order.getString("publicId"))
        val attempt = attempt(order.id)
        val start = result.payment!!

        assertEquals("INSTRUCTIONS", start.getString("kind"))
        assertEquals(PaymentStatus.PENDING, attempt.status)
        assertEquals("bank-transfer", attempt.providerId)
        assertEquals("INSTRUCTIONS", attempt.startKind)

        val values = start.getJsonObject("instructions").getJsonArray("fields").map { (it as JsonObject).getString("value") }
        val amount = com.panomc.plugins.market.spi.common.Money(attempt.amount, attempt.currency).toDecimalString() + " " + attempt.currency

        assertTrue(attempt.reference in values, "the transfer note is the attempt reference: $values")
        assertTrue(amount in values, "the exact amount to transfer ($amount): $values")
        assertEquals("10.00 EUR", amount)
        assertTrue("TR000000000000000000000001" in values)
        assertEquals(OrderStatus.PENDING, order.status)
        assertEquals(ReservationState.HELD, order.reservationState)
        assertEquals(4, stock(product))
        assertNull(timeline(order.id).firstOrNull { it.type == OrderEventType.BANK_TRANSFER_NOTIFIED })
    }

    @Test
    fun `P-18 twin bankTransferExpiryHours is the lifetime of the order and the attempt`(): Unit = runBlocking {
        rig.config = rig.config(bankHours = 24)

        val (order, _) = bankOrder()
        val attempt = attempt(order.id)
        val day = 24L * 60 * 60 * 1000

        assertEquals(order.createdAt + day, order.expiresAt)
        assertEquals(order.createdAt + day, attempt.expiresAt)

        rig.config = rig.config(bankHours = 72)

        val (second, _) = bankOrder()

        assertEquals(second.createdAt + 3 * day, second.expiresAt)
        assertEquals(second.createdAt + 3 * day, attempt(second.id).expiresAt)
    }

    // ===================================================================================================== the notice

    @Test
    fun `the buyer notice moves the attempt to PROCESSING and does not extend any expiry`(): Unit = runBlocking {
        val (order, product) = bankOrder()
        val before = attempt(order.id)

        w.clock.advance(60 * 60 * 1000)

        assertTrue(notify(order))

        val processing = attempt(order.id)
        val after = order(order.id)

        assertEquals(PaymentStatus.PROCESSING, processing.status)
        assertEquals(before.expiresAt, processing.expiresAt, "the attempt keeps its expiresAt")
        assertEquals(order.expiresAt, after.expiresAt, "the order keeps its expiresAt")
        assertEquals(OrderStatus.PENDING, after.status)
        assertEquals(ReservationState.HELD, after.reservationState)
        assertEquals(4, stock(product))

        val event = timeline(order.id).single { it.type == OrderEventType.BANK_TRANSFER_NOTIFIED }
        val data = JsonObject(event.data!!)

        assertEquals(OrderActorType.BUYER, event.actorType)
        assertEquals("Ayşe Yılmaz", data.getString("senderName"))
        assertEquals("EFT 14:02", data.getString("note"))

        // a notice never earns a grace either: at expiresAt the attempt and the order are gone like for any unpaid order
        w.clock.set(processing.expiresAt!!)
        assertEquals(1, ph.attempts(order.id).count { it.status == PaymentStatus.PROCESSING })
    }

    @Test
    fun `a repeated notice is a no-op that says nothing new, text is cleaned and cut at 255 characters`(): Unit = runBlocking {
        val (order, _) = bankOrder()
        val long = "x".repeat(300)

        assertTrue(notify(order, sender = "  Ali\u0000\nVeli\t ", note = long))
        assertFalse(notify(order, sender = "someone else", note = "other"), "already PROCESSING: 200, nothing changes")

        val events = timeline(order.id).filter { it.type == OrderEventType.BANK_TRANSFER_NOTIFIED }

        assertEquals(1, events.size)

        val data = JsonObject(events.single().data!!)

        assertEquals("AliVeli", data.getString("senderName"), "control characters are removed")
        assertEquals(255, data.getString("note").length)
        assertEquals(PaymentStatus.PROCESSING, attempt(order.id).status)
    }

    @Test
    fun `a notice needs a PENDING order inside its window whose newest attempt is a PENDING bank transfer`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val product = fx.product(price = 1000, stock = 5)
        val other = ph.order(rig.buy(product, "fake").order.getString("publicId"))

        expect("ORDER_NOT_PAYABLE", 409) { notify(other) }

        val (cancelled, _) = bankOrder()

        rig.payments.cancel(cancelled, pool)
        expect("ORDER_NOT_PAYABLE", 409) { notify(order(cancelled.id)) }

        val (late, _) = bankOrder()

        w.clock.set(late.expiresAt!!)
        expect("ORDER_NOT_PAYABLE", 409) { notify(late) }
        assertEquals(PaymentStatus.PENDING, attempt(late.id).status, "a refused notice changes nothing")
        assertTrue(timeline(late.id).none { it.type == OrderEventType.BANK_TRANSFER_NOTIFIED })
    }

    @Test
    fun `the notice body is parsed strictly`() {
        assertEquals(null to null, parseBankTransferNotice(null))
        assertEquals("a" to "b", parseBankTransferNotice(JsonObject().put("senderName", "a").put("note", "b")))

        for (bad in listOf(JsonObject().put("senderName", 5), JsonObject().put("other", "x"), JsonObject().put("note", JsonObject()))) {
            assertTrue(runCatching { parseBankTransferNotice(bad) }.exceptionOrNull() is com.panomc.plugins.market.error.RequestValueException, "$bad")
        }

        assertFalse(requireBuyerNotice(null))
        assertFalse(requireBuyerNotice("not json"))
        assertFalse(requireBuyerNotice("""{"requireBuyerNotice":false}"""))
        assertTrue(requireBuyerNotice("""{"requireBuyerNotice":true}"""))
    }

    // =================================================================================================== the decision

    @Test
    fun `approve after the notice is O2 with actor ADMIN, every effect once, the stock stays sold`(): Unit = runBlocking {
        val (order, product) = bankOrder()

        notify(order)

        val outcome = decide(order, BankTransferDecision.APPROVE, note = "seen on the statement")
        val done = order(order.id)
        val paid = attempt(order.id)

        assertFalse(outcome.review)
        assertEquals(OrderStatus.COMPLETED, done.status)
        assertEquals(ReservationState.COMMITTED, done.reservationState)
        assertEquals(PaymentStatus.SUCCEEDED, paid.status)
        assertEquals(paid.amount, paid.paidAmount)
        assertEquals(paid.amount, done.paidAmount)
        assertEquals(paid.id, done.paymentId)
        assertNull(done.expiresAt)
        assertEquals(4, stock(product))
        assertEquals(1, w.products.getById(product.id, pool)!!.soldCount)
        assertEquals(1, ph.effects.of(order.id).count { it == "QueueGrantDeliveries" })
        assertEquals(1, ph.effects.of(order.id).count { it == "IssueInvoice" })

        val status = timeline(order.id).single { it.type == OrderEventType.STATUS_CHANGED && it.toStatus == "COMPLETED" }

        assertEquals(OrderActorType.ADMIN, status.actorType)

        val succeeded = timeline(order.id).single { it.type == OrderEventType.PAYMENT_SUCCEEDED }

        assertEquals(OrderActorType.ADMIN, succeeded.actorType)
        assertEquals("seen on the statement", timeline(order.id).single { it.type == OrderEventType.NOTE }.message)
    }

    @Test
    fun `the admin may approve without a buyer notice unless requireBuyerNotice is on`(): Unit = runBlocking {
        val (first, _) = bankOrder()

        decide(first, BankTransferDecision.APPROVE)

        assertEquals(OrderStatus.COMPLETED, order(first.id).status)

        rig.requireNotice = true

        val (second, _) = bankOrder()

        expect("ORDER_NOT_PAYABLE", 409) { decide(second, BankTransferDecision.APPROVE) }
        assertEquals(OrderStatus.PENDING, order(second.id).status)
        assertEquals(PaymentStatus.PENDING, attempt(second.id).status)

        notify(second)
        decide(second, BankTransferDecision.APPROVE)

        assertEquals(OrderStatus.COMPLETED, order(second.id).status)
    }

    @Test
    fun `reject fails the attempt, the order stays PENDING with its stock, and the buyer can pay another way`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (order, product) = bankOrder()

        notify(order)
        decide(order, BankTransferDecision.REJECT, note = "no such transfer")

        val failed = attempt(order.id)
        val pending = order(order.id)

        assertEquals(PaymentStatus.FAILED, failed.status)
        assertEquals("REJECTED", failed.failureCode)
        assertEquals(OrderStatus.PENDING, pending.status)
        assertEquals(order.expiresAt, pending.expiresAt)
        assertEquals(ReservationState.HELD, pending.reservationState)
        assertEquals(4, stock(product))
        assertTrue(timeline(order.id).any { it.type == OrderEventType.PAYMENT_FAILED && it.actorType == OrderActorType.ADMIN })

        // pay another way: a new attempt on the same order
        ph.fake.onStart = { req -> com.panomc.plugins.market.spi.payment.StartPaymentResult.Redirect("https://gateway.invalid/pay/${req.attempt.reference}") }

        val start = rig.payments.pay(pending, PayRequest("fake", null, null), PayCaller(), pool)

        assertEquals("REDIRECT", start!!.getString("kind"))
        assertEquals(2, ph.attempts(order.id).size)

        // a second rejection of the failed attempt is refused, nothing moves
        expect("ORDER_NOT_PAYABLE", 409) { decide(order(order.id), BankTransferDecision.REJECT) }
    }

    @Test
    fun `a rejection after the window ends the order at once and gives the stock back`(): Unit = runBlocking {
        val (order, product) = bankOrder()

        w.clock.set(order.expiresAt!! + 1)
        decide(order, BankTransferDecision.REJECT)

        val done = order(order.id)

        assertEquals(OrderStatus.FAILED, done.status)
        assertEquals(ReservationState.RELEASED, done.reservationState)
        assertEquals(5, stock(product))
        assertEquals(PaymentStatus.FAILED, attempt(order.id).status)
    }

    @Test
    fun `money that arrives after the order expired is O9, the admin accepts through the review and the order is re-reserved`(): Unit = runBlocking {
        val (order, product) = bankOrder()

        notify(order)
        expire(order.id)

        assertEquals(5, stock(product))

        val outcome = decide(order, BankTransferDecision.APPROVE, note = "late")
        val waiting = order(order.id)

        assertTrue(outcome.review)
        assertEquals(OrderStatus.REVIEW, waiting.status)
        assertEquals("LATE", waiting.reviewReason)
        assertEquals(PaymentStatus.SUCCEEDED, attempt(order.id).status)
        assertTrue(ph.effects.of(order.id).none { it == "QueueGrantDeliveries" }, "nothing is delivered while a human decides")
        assertTrue(rig.alerts.any { it.first == order.id }, "the panel is told")

        rig.review.review(order.id, ReviewDecision.ACCEPT, refund = false, force = false, note = null, adminUserId = admin)

        val done = order(order.id)

        assertEquals(OrderStatus.COMPLETED, done.status)
        assertEquals(ReservationState.COMMITTED, done.reservationState)
        assertEquals(4, stock(product), "re-reserved")
        assertEquals(1, ph.effects.of(order.id).count { it == "QueueGrantDeliveries" })
    }

    @Test
    fun `approve of a cancelled order is O9 too, the same event as for an expired one`(): Unit = runBlocking {
        val (order, _) = bankOrder()

        rig.payments.cancel(order, pool)

        val outcome = decide(order(order.id), BankTransferDecision.APPROVE)

        assertTrue(outcome.review)
        assertEquals(OrderStatus.REVIEW, order(order.id).status)
    }

    @Test
    fun `every other state refuses with ORDER_NOT_PAYABLE, an order without a bank transfer is 404`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (order, _) = bankOrder()

        decide(order, BankTransferDecision.APPROVE)

        // COMPLETED: neither decision applies, and nothing is written again
        val effects = ph.effects.of(order.id).size

        expect("ORDER_NOT_PAYABLE", 409) { decide(order(order.id), BankTransferDecision.APPROVE) }
        expect("ORDER_NOT_PAYABLE", 409) { decide(order(order.id), BankTransferDecision.REJECT) }
        assertEquals(effects, ph.effects.of(order.id).size)
        assertEquals(1, timeline(order.id).count { it.type == OrderEventType.PAYMENT_SUCCEEDED })

        // a rejected order that expired afterwards: reject is refused (not PENDING)
        val (rejected, _) = bankOrder()

        decide(rejected, BankTransferDecision.REJECT)
        expire(rejected.id)
        expect("ORDER_NOT_PAYABLE", 409) { decide(order(rejected.id), BankTransferDecision.REJECT) }

        // an order paid another way has no bank transfer attempt
        val product = fx.product(price = 1000, stock = 5)
        val gateway = ph.order(rig.buy(product, "fake").order.getString("publicId"))

        try {
            decide(gateway, BankTransferDecision.APPROVE)

            error("expected 404")
        } catch (e: NotFound) {
            assertEquals(404, e.getStatusCode())
        }

        try {
            rig.bank.decide(987_654, BankTransferDecision.APPROVE, null, admin)

            error("expected 404")
        } catch (e: NoSuchElementException) {
            // an unknown order id: the route answers 404 before the service is called
        }
    }

    @Test
    fun `a notice and an approval that race, the order is paid once and the effects run once`(): Unit = runBlocking {
        repeat(Race.rounds) {
            val (order, _) = bankOrder()
            val results = Race.run(6) { i -> if (i % 3 == 0) decide(order, BankTransferDecision.APPROVE) else notify(order) }
            val done = order(order.id)

            assertEquals(OrderStatus.COMPLETED, done.status)
            assertEquals(1, results.count { r -> r.getOrNull() is BankTransferOutcome }, "exactly one approval won: ${results.map { it.exceptionOrNull()?.javaClass?.simpleName }}")
            assertEquals(1, ph.effects.of(order.id).count { it == "QueueGrantDeliveries" })
            assertEquals(1, ph.attempts(order.id).count { it.status == PaymentStatus.SUCCEEDED })
        }
    }

    @Test
    fun `an approval and a rejection that race, exactly one decision is applied`(): Unit = runBlocking {
        repeat(Race.rounds) {
            val (order, _) = bankOrder()
            val results = Race.run(4) { i -> if (i % 2 == 0) decide(order, BankTransferDecision.APPROVE) else decide(order, BankTransferDecision.REJECT) }
            val won = results.count { it.isSuccess }
            val done = order(order.id)
            val a = attempt(order.id)

            assertEquals(1, won, "one winner: ${results.map { it.exceptionOrNull()?.javaClass?.simpleName }}")
            assertTrue(
                (done.status == OrderStatus.COMPLETED && a.status == PaymentStatus.SUCCEEDED) || (done.status == OrderStatus.PENDING && a.status == PaymentStatus.FAILED),
                "${done.status} / ${a.status}"
            )
            assertTrue(results.filter { it.isFailure }.all { (it.exceptionOrNull() as? Error)?.getErrorCode() == "ORDER_NOT_PAYABLE" })
        }
    }

}
