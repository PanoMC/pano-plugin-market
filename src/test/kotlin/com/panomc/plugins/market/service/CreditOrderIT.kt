package com.panomc.plugins.market.service

import com.panomc.platform.model.Error
import com.panomc.plugins.market.core.money.Conversions
import com.panomc.plugins.market.core.payment.PaymentAttemptEvent
import com.panomc.plugins.market.core.pricing.Tender
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.CreditSystemKey
import com.panomc.plugins.market.db.model.CreditTxType
import com.panomc.plugins.market.db.model.MarketCreditTx
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.error.InsufficientCredits
import com.panomc.plugins.market.job.OrderExpiryJob
import com.panomc.plugins.market.routes.api.checkout.parseCheckoutRequest
import com.panomc.plugins.market.spi.payment.ReviewReason
import com.panomc.plugins.market.spi.testkit.TestContexts
import com.panomc.plugins.market.support.Fixtures
import com.panomc.plugins.market.support.InvariantChecker
import com.panomc.plugins.market.support.StaticProviderLookup
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.DiscountUnit
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
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
import java.math.BigDecimal

/**
 * The object graph of the credit tests (17 section 5.3): the real [CreditService] as the hold of checkout and as the settlement of every order transition,
 * the real [CheckoutService], [OrderService], [PaymentService], [OrderReviewService] and [OrderExpiryJob], with the checkout and payment harnesses of the
 * earlier slices only for their fixtures (fake provider, body builders, web hook writer, effect recorder). Shared by [CreditOrderIT] and [CreditRaceIT].
 *
 * [deferStart] leaves the first attempt of a checkout `CREATED` (the crash between O1 and O2 of 07 section 5): the order stays `PENDING` with its hold until
 * [PaymentService.startAttempt] (the re-drive of the reconcile job) completes it.
 */
internal class CreditHarness(val w: TestWiring, val vertx: Vertx, lockWaitSeconds: Int = 30) {
    val ph = PaymentHarness(w, vertx, lockWaitSeconds)
    val h get() = ph.h
    val credits = CreditService(w.clock, w.creditAccounts, w.creditTxs, w.creditEntries)

    @Volatile
    var deferStart = false

    lateinit var orders: OrderService
        private set

    lateinit var payments: PaymentService
        private set

    lateinit var review: OrderReviewService
        private set

    lateinit var service: CheckoutService
        private set

    lateinit var expiry: OrderExpiryJob
        private set

    private val directory = object : com.panomc.plugins.market.service.platform.UserDirectory {
        override suspend fun byUsername(username: String, sqlClient: io.vertx.sqlclient.SqlClient) =
            w.users.idOf(username)?.let { com.panomc.plugins.market.service.platform.DirectoryUser(it, w.users.nameOf(it)!!) }

        override suspend fun usernameOf(userId: Long, sqlClient: io.vertx.sqlclient.SqlClient): String? = w.users.nameOf(userId)

        override suspend fun emailOf(userId: Long, sqlClient: io.vertx.sqlclient.SqlClient): String? = h.emails[userId]

        override suspend fun hasPermission(userId: Long, node: String): Boolean = node in h.granted[userId].orEmpty()
    }

    init {
        rebuild()
    }

    fun rebuild() {
        val redemptions = RedemptionService(w.clock, ph.locks, w.redemptions)
        val reservations = ReservationService(w.clock, ph.locks, redemptions, w.orders)

        orders = OrderService(
            w.clock, w.ids, w.orders, w.orderItems, w.orderEvents, w.payments, redemptions, { _, _ -> false },
            credits = credits.checkoutHolds, settlement = credits,
            reservations = reservations, foreign = ph.effects,
            webhooks = PaidWebhooks { conn, orderId -> ph.webhooks.service.emitOrderPaid(conn, orderId) },
            rates = { sqlClient -> w.currencyRates.getAll(sqlClient).filter { it.rate.signum() > 0 }.associate { it.currency to it.rate } },
            statsCurrency = { ph.statsCurrency },
            limits = ProductPurchaseLimits(w.orders, w.products, w.entitlements, w.clock),
            refunds = w.refunds,
            duplicates = DuplicateRefundPolicy { conn, providerId -> payments.duplicateRefundRule(conn, providerId) }
        )
        payments = PaymentService(
            db = ph.db, locks = ph.locks, clock = w.clock, ids = w.ids, config = { h.config.toConfig() }, orders = w.orders, orderItems = w.orderItems, orderEvents = w.orderEvents,
            payments = w.payments, methods = w.paymentMethods, creditAccounts = w.creditAccounts, currencyRates = w.currencyRates, lookup = ph.lookup, cipher = ph.cipher,
            contexts = PaymentContexts { provider, settings, testMode -> TestContexts.payment(provider.id, settings, vertx, testMode) },
            orderService = orders, site = { TestContexts.defaultSite() }, readClient = { w.pool }, products = w.products, entitlements = w.entitlements,
            alerts = PanelAlerts { orderId, reason -> ph.alerts += orderId to reason },
            extraPaidGuards = listOf(CreditHoldGuard(credits))
        )
        review = OrderReviewService(ph.db, ph.locks, w.orders, w.payments, w.orderEvents, w.clock, orders, { false }, { after -> payments.runAfterCommit(after, w.pool) })
        expiry = OrderExpiryJob(w.clock, ph.db, ph.locks, w.orders, w.payments, orders, payments, { w.pool })

        val deferred = object : PaymentStarter {
            override suspend fun start(order: MarketOrder, attempt: MarketPayment, sqlClient: io.vertx.sqlclient.SqlClient): JsonObject? =
                if (deferStart) null else payments.start(order, attempt, sqlClient)

            override suspend fun served(attempt: MarketPayment, sqlClient: io.vertx.sqlclient.SqlClient): JsonObject? = payments.served(attempt, sqlClient)
        }

        service = CheckoutService(
            config = { h.config.toConfig() }, clock = w.clock, categories = w.categories, products = w.products, variants = w.variants, prices = w.prices,
            fields = w.fields, bundleItems = w.bundleItems, discounts = w.discounts, coupons = w.coupons, creatorCodes = w.creatorCodes,
            currencyRates = w.currencyRates, redemptions = w.redemptions, orders = w.orders, entitlements = w.entitlements,
            subscriptions = w.subscriptions, creditAccounts = w.creditAccounts, carts = w.carts, cartItems = w.cartItems,
            paymentMethods = w.paymentMethods, lookup = StaticProviderLookup(listOf(ph.fake)), cipher = ph.cipher,
            contexts = PaymentContexts { provider, settings, testMode -> TestContexts.payment(provider.id, settings, vertx, testMode) },
            legal = LegalTextService(w.db, w.clock, w.legalTexts, { "en-US" }), users = directory, servers = com.panomc.plugins.market.service.platform.ServerDirectory { _, _ -> emptySet() },
            checkout = CheckoutDeps(
                db = ph.db, locks = ph.locks, reservations = reservations, redemptions = redemptions, orders = orders, payments = w.payments,
                providerMeta = w.providerMeta, starter = deferred
            )
        )
    }

    /** A checkout as the route runs it (06 section 5): the body parsed with a fresh idempotency key, the order locale `en-US`. */
    suspend fun checkout(body: JsonObject, caller: QuoteCaller, key: String = h.nextKey()): CheckoutResult {
        val request = parseCheckoutRequest(if (caller.loggedIn) body.copy().also { it.remove("guest") } else body, key)

        return service.checkout(request.copy(orderLocale = "en-US"), caller, w.pool)
    }

    /** `payWithCredits` (06 section 6.6): the whole order is paid with credits. */
    suspend fun spend(product: MarketProduct, caller: QuoteCaller, quantity: Int = 1): CheckoutResult =
        checkout(h.body("items" to listOf(h.line(product, quantity)), "paymentMethodId" to "credits", "payWithCredits" to true), caller)

    /** A mixed purchase: [useCredits] credits (a decimal, `30` = 30.00) and the rest through the fake gateway. */
    suspend fun mixed(product: MarketProduct, caller: QuoteCaller, useCredits: Number, quantity: Int = 1): CheckoutResult =
        checkout(h.body("items" to listOf(h.line(product, quantity)), "paymentMethodId" to "fake", "useCredits" to useCredits), caller)

    suspend fun order(id: Long): MarketOrder = w.orders.getById(id, w.pool)!!

    suspend fun orderOf(result: CheckoutResult): MarketOrder = ph.order(result.order.getString("publicId"))

    suspend fun attempts(orderId: Long): List<MarketPayment> = w.payments.getByOrderId(orderId, w.pool)

    suspend fun ledger(orderId: Long): List<MarketCreditTx> = w.creditTxs.getByOrderId(orderId, w.pool)

    suspend fun succeed(orderId: Long, attempt: MarketPayment, amount: Long = attempt.amount) =
        payments.applyEvent(orderId, attempt.id, PaymentAttemptEvent.Succeeded(amount, attempt.currency, null))

    suspend fun system(key: CreditSystemKey): Long = w.creditAccounts.getBySystemKey(key, w.pool)!!.balance

    fun reconciler() = CreditReconciler(w.clock, "pano_", { w.pool }, recheckDelayMs = 0)
}

/**
 * Orders and the credit ledger on a real MariaDB (MK-091; 07 sections 5, 6.4 and 19.8, 06 section 7.3): the hold at O1, the capture at O2 and O4, the release
 * at O5 to O8, `/pay` changing the credit part and the tender, the re-hold of an accepted late payment, the crash between O1 and O2, an order whose column and
 * ledger disagree, and the self-check of the ledger on healthy and on corrupted data. The real services run on the real ledger; the invariants I1 to I22 are
 * checked after every test by the base class, and [CreditReconciler] (L1 to L7, O1 to O8, P1) after it (D-O19).
 */
class CreditOrderIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var c: CreditHarness
    private val vertx: Vertx = Vertx.vertx()

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    @BeforeEach
    fun freshState() {
        runBlocking { resetState() }
        w = TestWiring(pool)
        c = CreditHarness(w, vertx)
    }

    /** D-O19: the self-check of the ledger passes after every scenario. */
    override suspend fun assertInvariants() {
        super.assertInvariants()

        val result = c.reconciler().run(full = true)

        assertTrue(result.ok, "the credit reconciler found ${result.problems}")
    }

    private val fx get() = w.fixtures
    private val h get() = c.h
    private val admin = 77L

    // ------------------------------------------------------------------------------------------------------ helpers

    private suspend fun user(name: String = "Alex", credit: Long = 0): Pair<TestUser, QuoteCaller> {
        val u = fx.user(name)

        h.emails[u.id] = "$name@example.com"

        if (credit > 0) fx.credit(u, credit)

        return u to QuoteCaller(u.id)
    }

    private suspend fun expect(code: String, status: Int, block: suspend () -> Any?): JsonObject {
        val e = try {
            block()

            null
        } catch (e: Error) {
            e
        } ?: error("expected $code, nothing was thrown")
        val body = JsonObject(e.encode())

        assertEquals(code, body.getString("error"), "error code of the wire body ${e.encode()}")
        assertEquals(status, e.getStatusCode())

        return body
    }

    private suspend fun pay(order: MarketOrder, method: String = "fake", credits: Long? = null) = c.payments.pay(order, PayRequest(method, credits, null), PayCaller(), pool)

    private suspend fun balance(u: TestUser) = fx.creditBalance(u)

    private suspend fun typesOf(orderId: Long) = c.ledger(orderId).map { it.type }

    private suspend fun keysOf(orderId: Long) = c.ledger(orderId).map { it.idempotencyKey }

    private suspend fun amountsOf(orderId: Long) = c.ledger(orderId).map { it.amount }

    private suspend fun only(): MarketOrder = c.order(sql("SELECT `id` FROM `pano_market_order`").single().getLong("id"))

    private suspend fun stock(p: MarketProduct) = w.products.getById(p.id, pool)!!.stock

    private suspend fun configure(mixed: Boolean = true) {
        h.config = h.config.copy(allowMixedCreditPayment = mixed)
        fx.paymentMethod("fake")
    }

    // ================================================================================== D-O1: full credit

    @Test
    fun `D-O1 a full-credit checkout holds at O1 and captures at O2, the columns are those of 6_1`(): Unit = runBlocking {
        configure()
        c.deferStart = true

        val (alex, caller) = user("Alex", credit = 10_000)
        val product = fx.product(price = 3_000, creditPrice = 2_500, stock = 3)
        val order = c.orderOf(c.spend(product, caller))

        // O1: held, nothing else
        assertEquals(OrderStatus.PENDING, order.status)
        assertEquals(ReservationState.HELD, order.reservationState)
        assertEquals("credits", order.paymentMethodId)
        assertEquals(2_500, order.creditAmount)
        assertEquals(order.totalPrice, order.creditValue, "creditValue is the money value of the whole order")
        assertEquals(0, order.gatewayAmount)
        assertEquals(0, order.paymentFee)
        assertEquals(order.totalPrice, order.gatewayAmount + order.creditValue, "O5")
        assertEquals(listOf(CreditTxType.HOLD), typesOf(order.id))
        assertEquals(listOf("order:${order.id}:hold"), keysOf(order.id))
        assertEquals(listOf(2_500L), amountsOf(order.id))
        assertEquals(7_500, balance(alex))
        assertEquals(2_500, c.system(CreditSystemKey.HOLD))
        assertEquals(0, c.system(CreditSystemKey.SPENT))
        assertEquals(2, stock(product), "stock was reserved at O1")

        val attempt = c.attempts(order.id).single()

        assertEquals("credits", attempt.providerId)
        assertEquals(PaymentStatus.CREATED, attempt.status)
        assertEquals(2_500, attempt.creditAmount)

        // O2 by the re-drive of the reconcile job
        c.payments.startAttempt(order.id, attempt.id, emptyList(), pool)

        val done = c.order(order.id)

        assertEquals(OrderStatus.COMPLETED, done.status)
        assertEquals(ReservationState.COMMITTED, done.reservationState)
        assertEquals(listOf(CreditTxType.HOLD, CreditTxType.CAPTURE), typesOf(order.id))
        assertEquals(listOf("order:${order.id}:hold", "order:${order.id}:capture"), keysOf(order.id))
        assertEquals(listOf(2_500L, 2_500L), amountsOf(order.id))
        assertEquals(0, c.system(CreditSystemKey.HOLD))
        assertEquals(2_500, c.system(CreditSystemKey.SPENT))
        assertEquals(7_500, balance(alex))

        // a second run of the same step posts nothing
        c.payments.startAttempt(order.id, attempt.id, emptyList(), pool)

        assertEquals(2, c.ledger(order.id).size)
        assertEquals(2_500, c.system(CreditSystemKey.SPENT))
    }

    @Test
    fun `a full-credit checkout that starts at once is completed with its capture in the same request`(): Unit = runBlocking {
        configure()

        val (alex, caller) = user("Alex", credit = 10_000)
        val product = fx.product(price = 3_000, creditPrice = 2_500, stock = 3)
        val result = c.spend(product, caller)

        assertEquals("COMPLETED", result.order.getString("status"))
        assertEquals(listOf(CreditTxType.HOLD, CreditTxType.CAPTURE), typesOf(c.orderOf(result).id))
        assertEquals(7_500, balance(alex))
        assertEquals(2_500, c.system(CreditSystemKey.SPENT))
    }

    // ================================================================================== D-O2: mixed

    @Test
    fun `D-O2 a mixed checkout holds the credit part, the gateway success captures it and the attempt carries the gateway amount`(): Unit = runBlocking {
        configure()

        val (alex, caller) = user("Alex", credit = 8_000)
        val product = fx.product(price = 10_000, stock = 5)
        val order = c.orderOf(c.mixed(product, caller, useCredits = 30))
        val attempt = c.attempts(order.id).single()

        assertEquals(OrderStatus.PENDING, order.status)
        assertEquals(3_000, order.creditAmount)
        assertEquals(3_000, order.creditValue)
        assertEquals(order.totalPrice - 3_000, order.gatewayAmount)
        assertEquals(order.gatewayAmount, attempt.amount, "market_payment.amount is the gateway amount")
        assertEquals(3_000, attempt.creditAmount)
        assertEquals(listOf(CreditTxType.HOLD), typesOf(order.id))
        assertEquals(5_000, balance(alex))
        assertEquals(3_000, c.system(CreditSystemKey.HOLD))

        c.succeed(order.id, attempt)

        val done = c.order(order.id)

        assertEquals(OrderStatus.COMPLETED, done.status)
        assertEquals(listOf(CreditTxType.HOLD, CreditTxType.CAPTURE), typesOf(order.id))
        assertEquals(0, c.system(CreditSystemKey.HOLD))
        assertEquals(3_000, c.system(CreditSystemKey.SPENT))
        assertEquals(5_000, balance(alex))

        // the same event again: nothing is captured twice
        c.succeed(order.id, attempt)

        assertEquals(2, c.ledger(order.id).size)
        assertEquals(3_000, c.system(CreditSystemKey.SPENT))
    }

    // ================================================================================== D-O3: release

    private suspend fun pendingMixed(credit: Long = 8_000, use: Long = 50, name: String = "Alex"): Triple<TestUser, QuoteCaller, MarketOrder> {
        configure()

        val (alex, caller) = user(name, credit = credit)
        val order = c.orderOf(c.mixed(fx.product(price = 10_000, stock = 5), caller, useCredits = use))

        assertEquals(use * 100, order.creditAmount)
        assertEquals(credit - use * 100, balance(alex), "the credits are on hold")

        return Triple(alex, caller, order)
    }

    private suspend fun assertReleased(alex: TestUser, order: MarketOrder, credit: Long, status: OrderStatus) {
        val after = c.order(order.id)

        assertEquals(status, after.status)
        assertEquals(ReservationState.RELEASED, after.reservationState)
        assertEquals(listOf(CreditTxType.HOLD, CreditTxType.RELEASE), typesOf(order.id))
        assertEquals(listOf("order:${order.id}:hold", "order:${order.id}:release"), keysOf(order.id))
        assertEquals(credit, balance(alex), "the held credits are back")
        assertEquals(0, c.system(CreditSystemKey.HOLD))
        assertEquals(0, c.system(CreditSystemKey.SPENT), "nothing was spent")
    }

    @Test
    fun `D-O3 an order that expires releases its hold`(): Unit = runBlocking {
        val (alex, _, order) = pendingMixed()

        w.clock.advance(61 * 60_000L)

        assertTrue(c.expiry.runOnce() >= 1)
        assertReleased(alex, order, 8_000, OrderStatus.EXPIRED)

        // the job again: nothing moves, nothing is released twice
        c.expiry.runOnce()

        assertEquals(2, c.ledger(order.id).size)
    }

    @Test
    fun `D-O3 an order the buyer cancels releases its hold`(): Unit = runBlocking {
        val (alex, _, order) = pendingMixed()

        assertEquals(OrderStatus.CANCELLED, c.payments.cancel(order, pool))
        assertReleased(alex, order, 8_000, OrderStatus.CANCELLED)

        // a second cancel is a no-op for the ledger
        c.payments.cancel(c.order(order.id), pool)

        assertEquals(2, c.ledger(order.id).size)
    }

    @Test
    fun `D-O3 an order whose payment failed finally releases its hold`(): Unit = runBlocking {
        val (alex, _, order) = pendingMixed()
        val attempt = c.attempts(order.id).single()

        // another buyer's order: a final failure inside the order window leaves it open for a retry (06 section 9.2), the hold stays
        val (blake, _, open) = pendingMixed(name = "Blake")

        c.payments.applyEvent(open.id, c.attempts(open.id).single().id, PaymentAttemptEvent.Failed(final = true))

        assertEquals(OrderStatus.PENDING, c.order(open.id).status)
        assertEquals(listOf(CreditTxType.HOLD), typesOf(open.id))
        assertEquals(3_000, balance(blake))

        // after the window it is O8: both orders are past it now, the first one is failed finally
        w.clock.advance(61 * 60_000L)
        c.payments.applyEvent(order.id, attempt.id, PaymentAttemptEvent.Failed(final = true))

        val failed = c.order(order.id)

        assertEquals(OrderStatus.FAILED, failed.status)
        assertEquals(ReservationState.RELEASED, failed.reservationState)
        assertEquals(listOf("order:${order.id}:hold", "order:${order.id}:release"), keysOf(order.id))
        assertEquals(8_000, balance(alex), "the held credits are back")
        assertEquals(5_000, c.system(CreditSystemKey.HOLD), "only the other order's hold is left")
        assertEquals(0, c.system(CreditSystemKey.SPENT))
        assertEquals(listOf(CreditTxType.HOLD), typesOf(open.id), "the other order is not touched")

        // a replay releases nothing more
        c.payments.applyEvent(order.id, attempt.id, PaymentAttemptEvent.Failed(final = true))

        assertEquals(2, c.ledger(order.id).size)
        assertEquals(3_000, balance(blake))

        // the expiry gives the open one back
        assertTrue(c.expiry.runOnce() >= 1)
        assertEquals(8_000, balance(blake))
    }

    @Test
    fun `D-O3 a review the admin rejects releases the hold, the refund row has no credit part and nothing is refunded in credits`(): Unit = runBlocking {
        val (alex, _, order) = pendingMixed()
        val attempt = c.attempts(order.id).single()

        c.succeed(order.id, attempt, amount = attempt.amount - 100)

        assertEquals(OrderStatus.REVIEW, c.order(order.id).status)
        assertEquals(listOf(CreditTxType.HOLD), typesOf(order.id), "the hold stays while the order is in review (C6)")
        assertEquals(3_000, balance(alex))

        c.review.review(order.id, ReviewDecision.REJECT, refund = true, force = false, note = null, adminUserId = admin)

        assertReleased(alex, order, 8_000, OrderStatus.CANCELLED)

        val refund = w.refunds.getByOrderId(order.id, pool).single()

        assertEquals(0, refund.creditAmount)
        assertEquals(0, refund.creditValue)
        assertTrue(typesOf(order.id).none { it == CreditTxType.REFUND }, "held credits were released, not refunded")
    }

    @Test
    fun `an accepted review captures the hold it kept`(): Unit = runBlocking {
        val (alex, _, order) = pendingMixed()
        val attempt = c.attempts(order.id).single()

        c.succeed(order.id, attempt, amount = attempt.amount + 500)

        assertEquals(OrderStatus.REVIEW, c.order(order.id).status)

        c.review.review(order.id, ReviewDecision.ACCEPT, refund = false, force = false, note = null, adminUserId = admin)

        assertEquals(OrderStatus.COMPLETED, c.order(order.id).status)
        assertEquals(listOf(CreditTxType.HOLD, CreditTxType.CAPTURE), typesOf(order.id))
        assertEquals(3_000, balance(alex))
        assertEquals(5_000, c.system(CreditSystemKey.SPENT))
    }

    // ================================================================================== D-O4: refusal at checkout

    @Test
    fun `D-O4 a full-credit checkout without the balance is a 400 and leaves no order, no stock and no coupon use behind`(): Unit = runBlocking {
        configure()

        val (alex, caller) = user("Alex", credit = 1_000)
        val coupon = fx.coupon("TEN", DiscountUnit.PERCENT, 1_000, redeemLimit = 5)
        val product = fx.product(price = 3_000, creditPrice = 2_500, stock = 3)

        val body = expect("INSUFFICIENT_CREDITS", 400) {
            c.checkout(h.body("items" to listOf(h.line(product)), "paymentMethodId" to "credits", "payWithCredits" to true, "couponCode" to "TEN"), caller)
        }

        assertEquals(10.0, body.getDouble("balance"))
        assertEquals(0, count("market_order"))
        assertEquals(3, stock(product))
        assertEquals(0, w.coupons.getById(coupon.id, pool)!!.usedCount)
        assertEquals(0, count("market_credit_tx", "`type` IN ('HOLD', 'CAPTURE', 'RELEASE')"))
        assertEquals(1_000, balance(alex))
        assertEquals(0, c.system(CreditSystemKey.HOLD))
    }

    // ================================================================================== D-O5, D-O6: /pay

    @Test
    fun `D-O5 pay with another credit part releases the old hold and holds the new one under the next generation`(): Unit = runBlocking {
        val (alex, _, first) = pendingMixed(credit = 10_000, use = 30)

        assertEquals(listOf("order:${first.id}:hold"), keysOf(first.id))

        // 30 -> 10
        pay(first, credits = 1_000)

        val lowered = c.order(first.id)

        assertEquals(1_000, lowered.creditAmount)
        assertEquals(1_000, lowered.creditValue)
        assertEquals(lowered.totalPrice - 1_000, lowered.gatewayAmount)
        assertEquals(listOf(CreditTxType.HOLD, CreditTxType.RELEASE, CreditTxType.HOLD), typesOf(first.id))
        assertEquals(listOf("order:${first.id}:hold", "order:${first.id}:release", "order:${first.id}:hold:1"), keysOf(first.id))
        assertEquals(listOf(3_000L, 3_000L, 1_000L), amountsOf(first.id))
        assertEquals(9_000, balance(alex))
        assertEquals(1_000, c.system(CreditSystemKey.HOLD))
        assertEquals(1_000, c.attempts(first.id).last().creditAmount, "the new attempt carries the new tender")

        // no useCredits: the credit part is kept, nothing is posted
        pay(c.order(first.id), credits = null)

        assertEquals(3, c.ledger(first.id).size)
        assertEquals(1_000, c.order(first.id).creditAmount)

        // the same part again: nothing is posted
        pay(c.order(first.id), credits = 1_000)

        assertEquals(3, c.ledger(first.id).size)

        // 10 -> 0: only a release (generation 1), then 0 -> 20: a hold of generation 2
        pay(c.order(first.id), credits = 0)

        assertEquals(0, c.order(first.id).creditAmount)
        assertEquals(10_000, balance(alex))
        assertEquals(listOf("order:${first.id}:hold", "order:${first.id}:release", "order:${first.id}:hold:1", "order:${first.id}:release:1"), keysOf(first.id))

        pay(c.order(first.id), credits = 2_000)

        assertEquals(2_000, c.order(first.id).creditAmount)
        assertEquals(keysOf(first.id).last(), "order:${first.id}:hold:2")
        assertEquals(8_000, balance(alex))

        // the order ends: the last hold is released under generation 2
        assertEquals(OrderStatus.CANCELLED, c.payments.cancel(c.order(first.id), pool))
        assertEquals("order:${first.id}:release:2", keysOf(first.id).last())
        assertEquals(10_000, balance(alex))
        assertEquals(0, c.system(CreditSystemKey.HOLD))
    }

    @Test
    fun `a re-tender that the balance cannot hold changes nothing`(): Unit = runBlocking {
        val (alex, _, order) = pendingMixed(credit = 5_000, use = 20)

        val before = c.ledger(order.id).size
        val body = expect("INSUFFICIENT_CREDITS", 400) { pay(order, credits = 5_001) }

        assertEquals(50.0, body.getDouble("balance"), "the order's own held credits count as spendable")
        assertEquals(before, c.ledger(order.id).size)
        assertEquals(2_000, c.order(order.id).creditAmount)
        assertEquals(3_000, balance(alex))
        assertEquals(1, c.attempts(order.id).size)
    }

    @Test
    fun `D-O6 pay keeps a full-credit order on credits as 06 section 9_3 step 2 says, a gateway is refused and nothing moves, the credits retry completes it with the first hold`(): Unit = runBlocking {
        configure()
        c.deferStart = true

        val (alex, caller) = user("Alex", credit = 10_000)
        val product = fx.product(price = 3_000, creditPrice = 2_500, stock = 3)
        val order = c.orderOf(c.spend(product, caller))

        assertEquals(2_500, order.creditAmount)

        // to the gateway: refused, the order is a payWithCredits order and keeps its provider, credit part and hold
        c.deferStart = false

        val body = expect("PAYMENT_METHOD_UNAVAILABLE", 400) { pay(order, "fake") }

        assertEquals("CREDITS_REQUIRED", body.getString("reason"))

        val kept = c.order(order.id)

        assertEquals("credits", kept.paymentMethodId)
        assertEquals(2_500, kept.creditAmount)
        assertEquals(0, kept.gatewayAmount)
        assertEquals(listOf(CreditTxType.HOLD), typesOf(order.id))
        assertEquals(7_500, balance(alex))
        assertEquals(2_500, c.system(CreditSystemKey.HOLD))
        assertEquals(1, c.attempts(order.id).size, "no new attempt")

        // a gateway together with useCredits is refused the same way
        expect("PAYMENT_METHOD_UNAVAILABLE", 400) { pay(kept, "fake", credits = 1_000) }

        assertEquals(listOf(CreditTxType.HOLD), typesOf(order.id))

        // the credits retry: the credit part is unchanged, so no second hold, and the order completes with its capture
        pay(c.order(order.id), "credits")

        val done = c.order(order.id)

        assertEquals(OrderStatus.COMPLETED, done.status)
        assertEquals(2_500, done.creditAmount)
        assertEquals(listOf(CreditTxType.HOLD, CreditTxType.CAPTURE), typesOf(order.id))
        assertEquals(listOf("order:${order.id}:hold", "order:${order.id}:capture"), keysOf(order.id))
        assertEquals(7_500, balance(alex))
        assertEquals(2_500, c.system(CreditSystemKey.SPENT))
        assertEquals(0, c.system(CreditSystemKey.HOLD))
    }

    @Test
    fun `an ordinary gateway order can be switched to credits because its items carry the credit price, one that is not sold for credits cannot`(): Unit = runBlocking {
        configure()

        val (alex, caller) = user("Alex", credit = 10_000)
        val sold = fx.product(price = 3_000, creditPrice = 2_500, stock = 3)
        val notSold = fx.product(price = 3_000, creditPrice = 0, stock = 3)

        val soldOrder = c.orderOf(c.checkout(h.body("items" to listOf(h.line(sold)), "paymentMethodId" to "fake"), caller))
        val notSoldOrder = c.orderOf(c.checkout(h.body("items" to listOf(h.line(notSold)), "paymentMethodId" to "fake"), caller))

        assertEquals(listOf<Long?>(2_500L), w.orderItems.getByOrderIds(listOf(soldOrder.id), pool).filter { it.kind == OrderItemKind.PRODUCT }.map { it.creditUnitPrice })
        assertEquals(listOf<Long?>(null), w.orderItems.getByOrderIds(listOf(notSoldOrder.id), pool).filter { it.kind == OrderItemKind.PRODUCT }.map { it.creditUnitPrice })
        assertEquals(0, soldOrder.creditAmount)

        assertEquals("NOT_PAYABLE_WITH_CREDITS", expect("PAYMENT_METHOD_UNAVAILABLE", 400) { pay(notSoldOrder, "credits") }.getString("reason"))
        assertEquals(0, c.ledger(notSoldOrder.id).size)

        pay(soldOrder, "credits")

        val switched = c.order(soldOrder.id)

        assertEquals(OrderStatus.COMPLETED, switched.status)
        assertEquals("credits", switched.paymentMethodId)
        assertEquals(2_500, switched.creditAmount)
        assertEquals(switched.totalPrice, switched.creditValue)
        assertEquals(0, switched.gatewayAmount)
        assertEquals(listOf(CreditTxType.HOLD, CreditTxType.CAPTURE), typesOf(soldOrder.id))
        assertEquals(listOf("order:${soldOrder.id}:hold", "order:${soldOrder.id}:capture"), keysOf(soldOrder.id))
        assertEquals(7_500, balance(alex))
        assertEquals(2_500, c.system(CreditSystemKey.SPENT))

        assertEquals(OrderStatus.CANCELLED, c.payments.cancel(notSoldOrder, pool))
    }

    /** A full-credit checkout of [product] with the given codes, left `PENDING` with its hold (the crash between O1 and O2). */
    private suspend fun pendingCreditOrder(caller: QuoteCaller, product: MarketProduct, vararg codes: Pair<String, String>): MarketOrder {
        val body = h.body("items" to listOf(h.line(product)), "paymentMethodId" to "credits", "payWithCredits" to true, *codes)

        c.deferStart = true

        val order = c.orderOf(c.checkout(body, caller))

        c.deferStart = false

        return order
    }

    @Test
    fun `D-O9 a full-credit order with a coupon keeps its credit part when the buyer retries with credits, nothing is posted but the capture and the balance does not move`(): Unit = runBlocking {
        configure()

        val (alex, caller) = user("Alex", credit = 10_000)

        fx.coupon("TEN", DiscountUnit.PERCENT, 1_000, redeemLimit = 5)

        val product = fx.product(price = 3_000, creditPrice = 2_500, stock = 3)
        val order = pendingCreditOrder(caller, product, "couponCode" to "TEN")

        assertEquals(2_250, order.creditAmount, "the hold is the credit run's total, net of the 10 % coupon")
        assertEquals(7_750, balance(alex))
        assertEquals(listOf(CreditTxType.HOLD), typesOf(order.id))

        pay(order, "credits")

        val done = c.order(order.id)

        assertEquals(OrderStatus.COMPLETED, done.status)
        assertEquals(2_250, done.creditAmount, "the retry did not re-price the credit part")
        assertEquals(listOf(CreditTxType.HOLD, CreditTxType.CAPTURE), typesOf(order.id), "no RELEASE and no second HOLD")
        assertEquals(listOf(2_250L, 2_250L), amountsOf(order.id))
        assertEquals(listOf("order:${order.id}:hold", "order:${order.id}:capture"), keysOf(order.id))
        assertEquals(7_750, balance(alex))
        assertEquals(0, c.system(CreditSystemKey.HOLD))
        assertEquals(2_250, c.system(CreditSystemKey.SPENT))
    }

    @Test
    fun `D-O9 a full-credit order with a coupon and a creator code keeps its credit part on the credits retry, to the unit`(): Unit = runBlocking {
        configure()

        val (alex, caller) = user("Alex", credit = 10_000)

        fx.coupon("TEN", DiscountUnit.PERCENT, 1_000, redeemLimit = 5)
        fx.creatorCode("STREAMER", creator = "streamer", discount = 700)

        val product = fx.product(price = 3_000, creditPrice = 2_500, stock = 3)
        val order = pendingCreditOrder(caller, product, "couponCode" to "TEN", "creatorCode" to "STREAMER")
        val held = order.creditAmount

        assertTrue(held in 1 until 2_250, "both codes took something off the credit price: $held")
        assertEquals(10_000 - held, balance(alex))

        pay(order, "credits")

        assertEquals(OrderStatus.COMPLETED, c.order(order.id).status)
        assertEquals(held, c.order(order.id).creditAmount)
        assertEquals(listOf(CreditTxType.HOLD, CreditTxType.CAPTURE), typesOf(order.id))
        assertEquals(listOf(held, held), amountsOf(order.id))
        assertEquals(10_000 - held, balance(alex))
        assertEquals(held, c.system(CreditSystemKey.SPENT))
    }

    @Test
    fun `the frozen credit items of a credits order are its credit part less the shipping the tender adds back, of any other order the credit run's lines`() {
        val money = Conversions("EUR", "EUR", BigDecimal.ONE, 100, false)
        val creditsOrder = MarketOrder(paymentMethodId = "credits", creditAmount = 2_650, shippingTotal = 400, totalPrice = 3_400)
        val shipping = Tender.shippingCredits(money, 400)

        assertEquals(400, shipping, "4.00 of shipping at 1.00 per credit")
        assertEquals(2_250L, CreditRunSnapshot.itemsTotal(creditsOrder, emptyList(), money), "no item is read: the credit part was frozen at O1")
        assertEquals(2_650L, CreditRunSnapshot.itemsTotal(creditsOrder, emptyList(), money)!! + shipping, "the tender yields creditAmount again, to the unit")

        // another credit value later: the same function on both sides, so the round trip still gives creditAmount
        val dearer = Conversions("EUR", "EUR", BigDecimal.ONE, 300, false)

        assertEquals(2_650L, CreditRunSnapshot.itemsTotal(creditsOrder, emptyList(), dearer)!! + Tender.shippingCredits(dearer, 400))

        // an order on any other method is priced from its items, a line that is not sold for credits makes it unpayable, bundle children cost nothing
        val gateway = MarketOrder(paymentMethodId = "fake", creditAmount = 0)
        val coupon = MarketOrderItem(
            kind = OrderItemKind.PRODUCT, quantity = 2, listUnitPrice = 3_000, unitPrice = 3_000, couponAmount = 600, creditUnitPrice = 2_500,
            snapshot = JsonObject().put(CreditRunSnapshot.KEY, 4_500L).encode()
        )
        val child = MarketOrderItem(kind = OrderItemKind.BUNDLE_CHILD, quantity = 1, creditUnitPrice = null)

        assertEquals(4_500L, CreditRunSnapshot.itemsTotal(gateway, listOf(coupon, child), money), "the stored credit run total, not 2 x 25.00")
        assertNull(CreditRunSnapshot.itemsTotal(gateway, listOf(coupon, MarketOrderItem(kind = OrderItemKind.PRODUCT, quantity = 1, creditUnitPrice = null)), money))
        assertNull(CreditRunSnapshot.itemsTotal(gateway, emptyList(), money))

        // no stored total: the closed form takes the code share off the credit price that is already after the discount and the upgrade
        val bare = MarketOrderItem(
            kind = OrderItemKind.PRODUCT, quantity = 2, listUnitPrice = 3_000, discountAmount = 1_000, upgradeAmount = 0, unitPrice = 2_500, couponAmount = 500, creditUnitPrice = 2_000
        )

        assertEquals(3_600L, CreditRunSnapshot.itemsTotal(gateway, listOf(bare), money), "2 x 20.00 x (50.00 - 5.00) / 50.00")
        assertNull(CreditRunSnapshot.read(null))
        assertNull(CreditRunSnapshot.read("not json"))
        assertNull(CreditRunSnapshot.read(JsonObject().put(CreditRunSnapshot.KEY, "x").encode()))
        assertEquals(7L, CreditRunSnapshot.read(JsonObject().put(CreditRunSnapshot.KEY, 7).encode()))
    }

    @Test
    fun `an ordinary gateway order with a coupon switched to credits holds exactly what the credit run charges at checkout`(): Unit = runBlocking {
        configure()

        val (alex, caller) = user("Alex", credit = 10_000)
        val (_, beaCaller) = user("Bea", credit = 10_000)

        fx.coupon("TEN", DiscountUnit.PERCENT, 1_000, redeemLimit = 5)

        val product = fx.product(price = 3_000, creditPrice = 2_500, stock = 5)
        val gateway = c.orderOf(c.checkout(h.body("items" to listOf(h.line(product)), "paymentMethodId" to "fake", "couponCode" to "TEN"), caller))
        val direct = c.orderOf(c.checkout(h.body("items" to listOf(h.line(product)), "paymentMethodId" to "credits", "payWithCredits" to true, "couponCode" to "TEN"), beaCaller))

        assertEquals(0, gateway.creditAmount)
        assertEquals(2_250, direct.creditAmount, "the credit run: 25.00 credits less the 10 % coupon")

        val item = w.orderItems.getByOrderIds(listOf(gateway.id), pool).single { it.kind == OrderItemKind.PRODUCT }

        assertEquals(2_500L, item.creditUnitPrice)
        assertEquals(2_250L, CreditRunSnapshot.read(item.snapshot), "the credit run's line total is stored with the item")

        pay(gateway, "credits")

        val switched = c.order(gateway.id)

        assertEquals(OrderStatus.COMPLETED, switched.status)
        assertEquals(direct.creditAmount, switched.creditAmount, "the coupon stays consumed, so its credit share is taken off")
        assertEquals(listOf(CreditTxType.HOLD, CreditTxType.CAPTURE), typesOf(gateway.id))
        assertEquals(listOf(2_250L, 2_250L), amountsOf(gateway.id))
        assertEquals(7_750, balance(alex))
    }

    @Test
    fun `a switch to credits charges the credit run's rounding of a percentage code, not the money run's`(): Unit = runBlocking {
        configure()

        val (alex, caller) = user("Alex", credit = 300_000)
        val (_, beaCaller) = user("Bea", credit = 300_000)

        fx.coupon("ODD", DiscountUnit.PERCENT, 3_333, redeemLimit = 5)
        fx.creatorCode("STREAMER", creator = "streamer", discount = 500)

        // 9.99 in money, 1000.00 credits: the 33.33 % coupon takes 3.33 of the money price but 333.30 of the credit price
        val product = fx.product(price = 999, creditPrice = 100_000, stock = 5)
        val codes = arrayOf("couponCode" to "ODD", "creatorCode" to "STREAMER")
        val gateway = c.orderOf(c.checkout(h.body("items" to listOf(h.line(product)), "paymentMethodId" to "fake", *codes), caller))
        val direct = c.orderOf(c.checkout(h.body("items" to listOf(h.line(product)), "paymentMethodId" to "credits", "payWithCredits" to true, *codes), beaCaller))

        assertTrue(direct.creditAmount in 1 until 100_000, "${direct.creditAmount}")

        pay(gateway, "credits")

        val switched = c.order(gateway.id)

        assertEquals(direct.creditAmount, switched.creditAmount, "the same cart and codes cost the same credits whichever way the buyer arrived at them")
        assertEquals(listOf(direct.creditAmount, direct.creditAmount), amountsOf(gateway.id))
        assertEquals(300_000 - direct.creditAmount, balance(alex))
    }

    @Test
    fun `an item without the stored credit line total falls back to the closed form over its money columns`(): Unit = runBlocking {
        configure()

        val (alex, caller) = user("Alex", credit = 10_000)

        fx.coupon("TEN", DiscountUnit.PERCENT, 1_000, redeemLimit = 5)

        val product = fx.product(price = 3_000, creditPrice = 2_500, stock = 5)
        val gateway = c.orderOf(c.checkout(h.body("items" to listOf(h.line(product)), "paymentMethodId" to "fake", "couponCode" to "TEN"), caller))
        val item = w.orderItems.getByOrderIds(listOf(gateway.id), pool).single { it.kind == OrderItemKind.PRODUCT }
        val without = JsonObject(item.snapshot!!).also { it.remove(CreditRunSnapshot.KEY) }

        sql("UPDATE `pano_market_order_item` SET `snapshot` = ? WHERE `id` = ?", without.encode(), item.id)

        pay(gateway, "credits")

        val switched = c.order(gateway.id)

        assertEquals(OrderStatus.COMPLETED, switched.status)
        assertEquals(2_250, switched.creditAmount, "25.00 x (30.00 - 3.00) / 30.00")
        assertEquals(7_750, balance(alex))
    }

    // ================================================================================== D-O7: late payment, re-hold

    @Test
    fun `D-O7 a late payment of a released order accepted holds the credits again under the next generation and captures them, without balance the order stays in review`(): Unit = runBlocking {
        val (alex, caller, order) = pendingMixed(credit = 8_000, use = 80)
        val attempt = c.attempts(order.id).single()

        c.payments.cancel(order, pool)

        assertEquals(8_000, balance(alex), "cancel released the hold")

        c.succeed(order.id, attempt)

        val late = c.order(order.id)

        assertEquals(OrderStatus.REVIEW, late.status)
        assertEquals(ReservationState.RELEASED, late.reservationState)
        assertEquals(listOf(CreditTxType.HOLD, CreditTxType.RELEASE), typesOf(order.id), "a late payment never captures")

        // the credits are spent elsewhere before the human looks at the review
        c.spend(fx.product(price = 5_000, creditPrice = 5_000, stock = 5), caller)

        assertEquals(3_000, balance(alex))

        expect("INSUFFICIENT_CREDITS", 400) { accept(order.id) }

        assertEquals(OrderStatus.REVIEW, c.order(order.id).status)
        assertEquals(listOf(CreditTxType.HOLD, CreditTxType.RELEASE), typesOf(order.id), "the refused accept posted nothing")
        assertEquals(3_000, balance(alex))

        fx.credit(alex, 5_000)
        accept(order.id)

        val done = c.order(order.id)

        assertEquals(OrderStatus.COMPLETED, done.status)
        assertEquals(8_000, done.creditAmount)
        assertEquals(listOf(CreditTxType.HOLD, CreditTxType.RELEASE, CreditTxType.HOLD, CreditTxType.CAPTURE), typesOf(order.id))
        assertEquals(listOf("order:${order.id}:hold", "order:${order.id}:release", "order:${order.id}:hold:1", "order:${order.id}:capture"), keysOf(order.id))
        assertEquals(0, balance(alex))
        assertEquals(0, c.system(CreditSystemKey.HOLD))
    }

    private suspend fun accept(orderId: Long) = c.review.review(orderId, ReviewDecision.ACCEPT, refund = false, force = false, note = null, adminUserId = admin)

    // ================================================================================== D-O9: crash between O1 and O2

    @Test
    fun `D-O9 a crash after O1 leaves the hold which the expiry releases`(): Unit = runBlocking {
        configure()
        c.deferStart = true

        val (alex, caller) = user("Alex", credit = 10_000)
        val order = c.orderOf(c.spend(fx.product(price = 3_000, creditPrice = 2_500, stock = 3), caller))

        assertEquals(OrderStatus.PENDING, order.status)
        assertEquals(7_500, balance(alex))

        w.clock.advance(61 * 60_000L)
        c.expiry.runOnce()

        assertEquals(OrderStatus.EXPIRED, c.order(order.id).status)
        assertEquals(10_000, balance(alex))
        assertEquals(listOf(CreditTxType.HOLD, CreditTxType.RELEASE), typesOf(order.id))
        assertEquals(0, c.system(CreditSystemKey.HOLD))
    }

    @Test
    fun `D-O9 a crash after O1 and the buyer retrying with the credits completes the order without a second hold`(): Unit = runBlocking {
        configure()
        c.deferStart = true

        val (alex, caller) = user("Alex", credit = 10_000)
        val order = c.orderOf(c.spend(fx.product(price = 3_000, creditPrice = 2_500, stock = 3), caller))

        assertEquals(listOf(CreditTxType.HOLD), typesOf(order.id))

        c.deferStart = false
        pay(order, "credits")

        val done = c.order(order.id)

        assertEquals(OrderStatus.COMPLETED, done.status)
        assertEquals(listOf(CreditTxType.HOLD, CreditTxType.CAPTURE), typesOf(order.id), "the credit part did not change: no second hold")
        assertEquals(7_500, balance(alex))
        assertEquals(0, c.system(CreditSystemKey.HOLD))
    }

    // ================================================================================== D-R3 and mismatches

    @Test
    fun `D-R3 an order whose credit part has no hold behind it is never captured, it goes to review and other buyers' holds stay untouched`(): Unit = runBlocking {
        configure()

        val (bob, bobCaller) = user("Bob", credit = 5_000)
        val (_, caller) = user("Alex")
        val other = c.orderOf(c.mixed(fx.product(price = 10_000, stock = 5), bobCaller, useCredits = 20))

        assertEquals(2_000, c.system(CreditSystemKey.HOLD))

        val order = c.orderOf(c.checkout(h.body("items" to listOf(h.line(fx.product(price = 10_000, stock = 5))), "paymentMethodId" to "fake"), caller))
        val attempt = c.attempts(order.id).single()

        // the corruption: a credit part that no hold backs
        sql("UPDATE `pano_market_order` SET `creditAmount` = 5000 WHERE `id` = ?", order.id)
        sql("UPDATE `pano_market_payment` SET `creditAmount` = 5000 WHERE `orderId` = ?", order.id)

        try {
            val found = c.reconciler().run(full = true)

            assertTrue(found.problems.any { it.invariant == "O3b" && it.id == order.id }, "the reconciler reports O3b: ${found.problems}")
            assertTrue(found.problems.any { it.invariant == "O1" }, "and the HOLD account does not add up: ${found.problems}")

            c.succeed(order.id, attempt)

            val diverted = c.order(order.id)

            assertEquals(OrderStatus.REVIEW, diverted.status)
            assertEquals(ReviewReason.OTHER.name, diverted.reviewReason)
            assertEquals(0, c.ledger(order.id).size, "nothing was captured, nothing was released")
            assertEquals(2_000, c.system(CreditSystemKey.HOLD), "Bob's hold is untouched")
            assertEquals(0, c.system(CreditSystemKey.SPENT))
            assertEquals(3_000, balance(bob))
            assertTrue(w.orderEvents.getByOrderId(order.id, pool).any { it.message == CreditHoldGuard.NOTE }, "the timeline says why")
            assertTrue(ph().alerts.any { it.first == order.id }, "the panel is told")
        } finally {
            // repair the corruption so that the base class sees a consistent database, then close both orders
            sql("UPDATE `pano_market_order` SET `creditAmount` = 0 WHERE `id` = ?", order.id)
            sql("UPDATE `pano_market_payment` SET `creditAmount` = 0 WHERE `orderId` = ?", order.id)
        }

        c.review.review(order.id, ReviewDecision.REJECT, refund = false, force = false, note = null, adminUserId = admin)
        c.payments.cancel(other, pool)

        assertEquals(5_000, balance(bob))
        assertEquals(0, c.system(CreditSystemKey.HOLD))
    }

    private fun ph() = c.ph

    @Test
    fun `capture is refused when the ledger holds less than the column promises, release gives back what the ledger holds`(): Unit = runBlocking {
        val (alex, _, order) = pendingMixed(credit = 8_000, use = 30)

        assertEquals(3_000, order.creditAmount)

        // another buyer holds credits too: they must stay where they are
        val (bob, bobCaller) = user("Bob", credit = 5_000)
        val bobOrder = c.orderOf(c.mixed(fx.product(price = 10_000, stock = 5), bobCaller, useCredits = 20))

        sql("UPDATE `pano_market_order` SET `creditAmount` = 5000 WHERE `id` = ?", order.id)

        val inflated = c.order(order.id)
        val txsBefore = count("market_credit_tx")

        val refusal = assertThrows(CreditLedgerMismatch::class.java) { runBlocking { w.db.tx { conn -> c.credits.capture(inflated, conn) } } }

        assertTrue(refusal.message!!.contains("3000") && refusal.message!!.contains("5000"), refusal.message)
        assertEquals(txsBefore, count("market_credit_tx"), "nothing was posted")
        assertEquals(5_000, c.system(CreditSystemKey.HOLD), "the pooled hold account is intact")

        // the release moves the 30.00 that were held for it and nothing more, although the column says 50.00
        assertEquals(OrderStatus.CANCELLED, c.payments.cancel(inflated, pool))

        assertEquals(listOf(CreditTxType.HOLD, CreditTxType.RELEASE), typesOf(order.id))
        assertEquals(listOf(3_000L, 3_000L), amountsOf(order.id))
        assertEquals(8_000, balance(alex))
        assertEquals(2_000, c.system(CreditSystemKey.HOLD), "Bob's hold is untouched")

        c.payments.cancel(bobOrder, pool)

        assertEquals(5_000, balance(bob))
    }

    @Test
    fun `a captured hold is never released and a hold is never placed over another`(): Unit = runBlocking {
        configure()

        val (alex, caller) = user("Alex", credit = 10_000)
        val done = c.orderOf(c.spend(fx.product(price = 3_000, creditPrice = 2_500, stock = 3), caller))

        assertEquals(OrderStatus.COMPLETED, done.status)

        val released = assertThrows(CreditLedgerMismatch::class.java) { runBlocking { w.db.tx { conn -> c.credits.release(done, 1, conn) } } }

        assertTrue(released.message!!.contains("captured"))
        assertThrows(CreditLedgerMismatch::class.java) { runBlocking { w.db.tx { conn -> c.credits.rehold(conn, done, 2_500) } } }
        assertEquals(listOf(CreditTxType.HOLD, CreditTxType.CAPTURE), typesOf(done.id))
        assertEquals(2_500, c.system(CreditSystemKey.SPENT))
        assertEquals(7_500, balance(alex))

        // a second hold over a live one
        val (_, _, pending) = pendingMixed(credit = 8_000, use = 30, name = "Dana")

        assertThrows(CreditLedgerMismatch::class.java) { runBlocking { w.db.tx { conn -> c.credits.rehold(conn, pending, 1_000) } } }
        assertEquals(1, c.ledger(pending.id).size)
    }

    @Test
    fun `capture replays under its key and a release with nothing on hold posts nothing`(): Unit = runBlocking {
        val (_, _, order) = pendingMixed(credit = 8_000, use = 30)

        c.succeed(order.id, c.attempts(order.id).single())

        val captured = c.ledger(order.id).single { it.type == CreditTxType.CAPTURE }
        val again = w.db.tx { conn -> c.credits.capture(order, conn) }

        assertTrue(again.replayed)
        assertEquals(captured.id, again.tx.id)
        assertEquals(3_000, c.system(CreditSystemKey.SPENT))
        assertEquals(2, c.ledger(order.id).size)

        // an order that never held has nothing to release: no posting, no failure
        val ghost = MarketOrder(id = 987_654, userId = order.userId, creditAmount = 1_000)

        assertNull(w.db.tx { conn -> c.credits.release(ghost, 0, conn) })
        assertEquals(0, c.ledger(987_654).size)
    }

    // ================================================================================== D-O20: the reconciler finds corruption

    private suspend fun problemsOf(vararg ids: String): List<CreditProblem> {
        val found = c.reconciler().run(full = true)

        assertFalse(found.ok)
        assertEquals(ids.toSet(), found.problems.map { it.invariant }.toSet(), "problems: ${found.problems}")

        return found.problems
    }

    /** The corruption tests damage the ledger on purpose: they end by wiping the tables, so that the checks of the base class see a clean database. */
    private suspend fun corrupt(block: suspend () -> Unit) {
        try {
            block()
        } finally {
            resetState()
        }
    }

    @Test
    fun `D-O20 L3 an edited account balance is found`(): Unit = runBlocking {
        corrupt {
            val (alex, _) = user("Alex", credit = 5_000)

            sql("UPDATE `pano_market_credit_account` SET `balance` = `balance` + 7 WHERE `id` = ?", alex.accountId)

            val problems = c.reconciler().run(full = true)

            assertFalse(problems.ok)
            assertTrue(problems.problems.any { it.invariant == "L3" && it.id == alex.accountId }, "${problems.problems}")
            assertTrue(problems.problems.any { it.invariant == "L5" }, "the ledger no longer sums to zero: ${problems.problems}")
        }
    }

    @Test
    fun `D-O20 L1 a deleted entry is found`(): Unit = runBlocking {
        corrupt {
            val (alex, _) = user("Alex", credit = 5_000)
            val entry = w.creditEntries.getByAccountId(alex.accountId, 1, pool).single()

            sql("DELETE FROM `pano_market_credit_entry` WHERE `id` = ?", entry.id)

            val found = c.reconciler().run(full = true).problems

            assertTrue(found.any { it.invariant == "L1" }, "$found")
        }
    }

    @Test
    fun `D-O20 L2 L4 L6 L7 a negative shortfall, a broken balanceAfter chain, a negative HOLD and a posting between the wrong accounts are found`(): Unit = runBlocking {
        corrupt {
            val (alex, _) = user("Alex", credit = 5_000)
            val db = w.db

            db.tx { conn -> c.credits.grant(alex.id, 1_000, "panel:l4", null, "n", conn) }

            val chain = w.creditEntries.getByAccountId(alex.accountId, 1, pool).single()

            sql("UPDATE `pano_market_credit_entry` SET `balanceAfter` = `balanceAfter` + 1 WHERE `id` = ?", chain.id)
            sql("UPDATE `pano_market_credit_tx` SET `shortfall` = 5 WHERE `type` = 'GRANT' AND `idempotencyKey` = 'panel:l4'")
            sql("UPDATE `pano_market_credit_account` SET `balance` = -1 WHERE `systemKey` = 'HOLD'")
            sql("UPDATE `pano_market_credit_tx` SET `type` = 'CAPTURE' WHERE `idempotencyKey` = 'panel:l4'")

            val ids = c.reconciler().run(full = true).problems.map { it.invariant }.toSet()

            for (expected in listOf("L2", "L4", "L6", "L7")) assertTrue(expected in ids, "$expected missing from $ids")
        }
    }

    @Test
    fun `D-O20 O1 an orphan hold and O2 a missing capture are found`(): Unit = runBlocking {
        corrupt {
            val (alex, _) = user("Alex", credit = 5_000)

            // an orphan hold: credits on HOLD that no open order owns
            w.db.tx { conn ->
                c.credits.post(
                    Posting(CreditTxType.HOLD, "order:424242:hold", alex.id, 1_000, AccountRef.User(alex.id), AccountRef.System(CreditSystemKey.HOLD), PostingPolicy.FAIL, orderId = 424242), conn
                )
            }

            val orphan = c.reconciler().run(full = true).problems

            assertTrue(orphan.any { it.invariant == "O1" }, "$orphan")

            // a COMMITTED order with a credit part and no CAPTURE
            val orderId = Fixtures.insertRaw(
                pool, "market_order",
                mapOf(
                    "playerUsername" to "alex", "pricingMode" to "MARKET", "status" to "COMPLETED", "reservationState" to "COMMITTED", "creditAmount" to 1_000,
                    "creditValue" to 1_000, "totalPrice" to 1_000, "createdAt" to w.clock.now(), "updatedAt" to w.clock.now()
                )
            )

            val missing = c.reconciler().run(full = true).problems

            assertTrue(missing.any { it.invariant == "O2" && it.id == orderId }, "$missing")
            assertTrue(missing.any { it.invariant == "O3" && it.id == orderId }, "$missing")
        }
    }

    @Test
    fun `D-O20 O4 an over-refund, O5 a tender that does not add up, O6 a credit grant that never happened and P1 a payout without its tx are found`(): Unit = runBlocking {
        corrupt {
            val (alex, _) = user("Alex", credit = 5_000)
            val now = w.clock.now()
            val raw = mapOf("playerUsername" to "alex", "pricingMode" to "MARKET", "createdAt" to now, "updatedAt" to now)

            val overRefunded = Fixtures.insertRaw(pool, "market_order", raw + mapOf("status" to "REFUNDED", "creditAmount" to 1_000, "creditValue" to 1_000, "totalPrice" to 1_000, "refundedCreditAmount" to 2_000))
            val unbalanced = Fixtures.insertRaw(pool, "market_order", raw + mapOf("status" to "CANCELLED", "creditAmount" to 0, "creditValue" to 0, "gatewayAmount" to 10, "totalPrice" to 99))
            val paidPack = Fixtures.insertRaw(pool, "market_order", raw + mapOf("status" to "COMPLETED", "userId" to alex.id, "totalPrice" to 500, "gatewayAmount" to 500))

            Fixtures.insertRaw(
                pool, "market_order_item",
                mapOf("orderId" to paidPack, "productName" to "Pack", "quantity" to 1, "creditAmount" to 700, "createdAt" to now, "updatedAt" to now)
            )

            val payout = Fixtures.insertRaw(
                pool, "market_creator_payout",
                mapOf(
                    "creatorCodeId" to 1, "creatorUserId" to alex.id, "amount" to 100, "currency" to "EUR", "method" to "CREDIT", "state" to "PAID",
                    "idempotencyKey" to "payout:k1", "idempotencyHash" to "h", "createdAt" to now, "updatedAt" to now
                )
            )

            val found = c.reconciler().run(full = true).problems

            assertTrue(found.any { it.invariant == "O4" && it.id == overRefunded }, "$found")
            assertTrue(found.any { it.invariant == "O5" && it.id == unbalanced }, "$found")
            assertTrue(found.any { it.invariant == "O6" && it.entity == "orderItem" }, "$found")
            assertTrue(found.any { it.invariant == "P1" && it.id == payout }, "$found")
        }
    }

    @Test
    fun `D-O20 O5 a credit value without a credit part and a credit part without a value on a priced order are found, a credit part worth 0 on a total of 0 is not`(): Unit = runBlocking {
        corrupt {
            user("Alex", credit = 5_000)

            val now = w.clock.now()
            val raw = mapOf("playerUsername" to "alex", "pricingMode" to "MARKET", "status" to "CANCELLED", "createdAt" to now, "updatedAt" to now)

            // creditValue > 0 with no credit part: nothing was ever held for that value
            val valueOnly = Fixtures.insertRaw(pool, "market_order", raw + mapOf("creditAmount" to 0, "creditValue" to 100, "gatewayAmount" to 0, "totalPrice" to 100))
            // a credit part with a money total that none of it covers (a mixed order never has a credit part worth 0)
            val partOnly = Fixtures.insertRaw(pool, "market_order", raw + mapOf("creditAmount" to 500, "creditValue" to 0, "gatewayAmount" to 100, "totalPrice" to 100))
            // a gateway total that does not add up
            val unbalanced = Fixtures.insertRaw(pool, "market_order", raw + mapOf("creditAmount" to 0, "creditValue" to 0, "gatewayAmount" to 10, "totalPrice" to 99))
            // the legitimate shape: a credits-only product, credit part 40.00 on a total of 0 (05 section 17 row 53)
            val creditsOnly = Fixtures.insertRaw(pool, "market_order", raw + mapOf("creditAmount" to 4_000, "creditValue" to 0, "gatewayAmount" to 0, "totalPrice" to 0))
            // and an ordinary full-credit order, a mixed one and a gateway one
            val full = Fixtures.insertRaw(pool, "market_order", raw + mapOf("creditAmount" to 2_500, "creditValue" to 3_000, "gatewayAmount" to 0, "totalPrice" to 3_000))
            val mixed = Fixtures.insertRaw(pool, "market_order", raw + mapOf("creditAmount" to 1_000, "creditValue" to 1_000, "gatewayAmount" to 2_000, "totalPrice" to 3_000))
            val gateway = Fixtures.insertRaw(pool, "market_order", raw + mapOf("creditAmount" to 0, "creditValue" to 0, "gatewayAmount" to 3_000, "totalPrice" to 3_000))

            val o5 = c.reconciler().run(full = true).problems.filter { it.invariant == "O5" }.map { it.id }.toSet()

            assertEquals(setOf(valueOnly, partOnly, unbalanced), o5, "only the three broken orders: $o5 (credits-only $creditsOnly, full $full, mixed $mixed, gateway $gateway)")
        }
    }

    @Test
    fun `D-O20 O7 a second cashback and O8 clawbacks above the grant are found`(): Unit = runBlocking {
        corrupt {
            val (alex, _) = user("Alex", credit = 5_000)
            val now = w.clock.now()
            val orderId = Fixtures.insertRaw(
                w.pool, "market_order",
                mapOf("playerUsername" to "alex", "pricingMode" to "MARKET", "status" to "COMPLETED", "userId" to alex.id, "totalPrice" to 500, "gatewayAmount" to 500, "createdAt" to now, "updatedAt" to now)
            )
            val itemId = Fixtures.insertRaw(
                pool, "market_order_item", mapOf("orderId" to orderId, "productName" to "Pack", "quantity" to 1, "creditAmount" to 700, "createdAt" to now, "updatedAt" to now)
            )

            w.db.tx { conn ->
                for (n in 1..2) {
                    c.credits.post(
                        Posting(CreditTxType.CASHBACK, "order:$orderId:cashback:$n", alex.id, 100, AccountRef.System(CreditSystemKey.ISSUANCE), AccountRef.User(alex.id), orderId = orderId), conn
                    )
                }

                c.credits.post(Posting(CreditTxType.TOPUP, "orderitem:$itemId:topup", alex.id, 700, AccountRef.System(CreditSystemKey.ISSUANCE), AccountRef.User(alex.id), orderId = orderId), conn)

                c.credits.post(
                    Posting(
                        CreditTxType.REVOKE, "refund:1:clawback:$itemId", alex.id, 5_000, AccountRef.User(alex.id), AccountRef.System(CreditSystemKey.REVOKED), PostingPolicy.TAKE_AVAILABLE, orderId = orderId
                    ),
                    conn
                )
            }

            val found = c.reconciler().run(full = true).problems

            assertTrue(found.any { it.invariant == "O7" && it.id == orderId }, "$found")
            assertTrue(found.any { it.invariant == "O8" && it.id == itemId }, "$found")
        }
    }

    @Test
    fun `a problem is reported only when it fails twice and the cursor only moves past a clean run`(): Unit = runBlocking {
        corrupt {
            val (alex, _) = user("Alex", credit = 5_000)
            val reconciler = CreditReconciler(w.clock, "pano_", { pool }, recheckDelayMs = 400)

            assertTrue(reconciler.run().ok)

            // a transient inconsistency that heals between the two evaluations (a transaction in flight) is not reported
            sql("UPDATE `pano_market_credit_account` SET `balance` = `balance` + 7 WHERE `id` = ?", alex.accountId)

            val healer = Thread {
                Thread.sleep(150)
                runBlocking { sql("UPDATE `pano_market_credit_account` SET `balance` = `balance` - 7 WHERE `id` = ?", alex.accountId) }
            }

            healer.start()

            val transient = reconciler.run(full = true)

            healer.join()

            assertTrue(transient.ok, "healed before the second look: ${transient.problems}")

            // one that stays is reported, and again by the next run
            sql("UPDATE `pano_market_credit_account` SET `balance` = `balance` + 7 WHERE `id` = ?", alex.accountId)

            assertFalse(reconciler.run().ok)
            assertFalse(reconciler.run().ok, "L5 is checked on every run")
            assertNotNull(reconciler.last)
            assertEquals(false, reconciler.last!!.ok)
        }
    }

    @Test
    fun `D-O19 the self-check passes on a ledger with every kind of order behind it`(): Unit = runBlocking {
        configure()

        val (_, caller) = user("Alex", credit = 20_000)
        val product = fx.product(price = 10_000, creditPrice = 4_000, stock = 20)

        c.spend(product, caller)

        val mixed = c.orderOf(c.mixed(product, caller, useCredits = 30))

        c.succeed(mixed.id, c.attempts(mixed.id).single())

        val open = c.orderOf(c.mixed(product, caller, useCredits = 10))

        pay(open, credits = 2_000)
        c.payments.cancel(c.order(open.id), pool)

        val result = c.reconciler().run(full = true)

        assertTrue(result.ok, "${result.problems}")
        InvariantChecker.assertAll(pool)
    }

    @Test
    fun `D-O19 a credits-only product bought with credits is a credit part worth 0 on a total of 0 and the self-check accepts it`(): Unit = runBlocking {
        configure()

        val (alex, caller) = user("Alex", credit = 20_000)
        val creditsOnly = fx.product(price = 0, creditPrice = 4_000, stock = 5)
        val result = c.spend(creditsOnly, caller)
        val order = c.orderOf(result)

        // 05 section 17 row 53: creditTotal 40.00, totalPrice 0, creditValue 0
        assertEquals("COMPLETED", result.order.getString("status"))
        assertEquals("credits", order.paymentMethodId)
        assertEquals(4_000, order.creditAmount)
        assertEquals(0, order.totalPrice)
        assertEquals(0, order.creditValue)
        assertEquals(0, order.gatewayAmount)
        assertEquals(listOf(CreditTxType.HOLD, CreditTxType.CAPTURE), typesOf(order.id))
        assertEquals(16_000, balance(alex))

        val found = c.reconciler().run(full = true)

        assertTrue(found.ok, "a credits-only order is not ledger corruption: ${found.problems}")
        assertTrue(found.problems.none { it.invariant == "O5" })

        // a credits-only product next to a priced one, and one that waits for its capture: the same shapes in every state
        val priced = fx.product(price = 3_000, creditPrice = 2_500, stock = 5)

        c.spend(priced, caller)
        c.deferStart = true
        c.orderOf(c.spend(creditsOnly, caller))

        val again = c.reconciler().run(full = true)

        assertTrue(again.ok, "${again.problems}")
    }

    @Test
    fun `the pending checkouts of two buyers hold their own credits and never each other's`(): Unit = runBlocking {
        configure()
        c.deferStart = true

        val (alex, a) = user("Alex", credit = 5_000)
        val (bea, b) = user("Bea", credit = 7_000)
        val product = fx.product(price = 3_000, creditPrice = 2_500, stock = 10)

        val first = c.orderOf(c.spend(product, a))
        val second = c.orderOf(c.spend(product, b, quantity = 2))

        assertEquals(2_500, first.creditAmount)
        assertEquals(5_000, second.creditAmount)
        assertEquals(7_500, c.system(CreditSystemKey.HOLD))

        c.payments.cancel(first, pool)

        assertEquals(5_000, balance(alex))
        assertEquals(5_000, c.system(CreditSystemKey.HOLD), "only Alex's hold went back")
        assertEquals(2_000, balance(bea))

        c.payments.startAttempt(second.id, c.attempts(second.id).single().id, emptyList(), pool)

        assertEquals(OrderStatus.COMPLETED, c.order(second.id).status)
        assertEquals(0, c.system(CreditSystemKey.HOLD))
        assertEquals(5_000, c.system(CreditSystemKey.SPENT))
    }
}
