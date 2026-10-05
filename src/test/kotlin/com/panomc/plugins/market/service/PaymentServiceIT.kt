package com.panomc.plugins.market.service

import com.panomc.platform.model.Error
import com.panomc.plugins.market.core.order.OrderEffect
import com.panomc.plugins.market.core.payment.PaymentAttemptEvent
import com.panomc.plugins.market.db.model.EntitlementStatus
import com.panomc.plugins.market.db.model.MarketEntitlement
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.spi.payment.ReviewReason
import com.panomc.plugins.market.support.Race
import java.util.concurrent.atomic.AtomicInteger
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.CreditSystemKey
import com.panomc.plugins.market.db.model.CreditTxType
import com.panomc.plugins.market.db.model.CurrencyRateMode
import com.panomc.plugins.market.db.model.MarketCreditAccount
import com.panomc.plugins.market.db.model.MarketCreditEntry
import com.panomc.plugins.market.db.model.MarketCreditTx
import com.panomc.plugins.market.db.model.MarketCurrencyRate
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.OrderActorType
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.model.PaymentFeeMode
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.db.model.RedemptionState
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.db.tx.LockedOrder
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.InsufficientCredits
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.provider.BankTransferProvider
import com.panomc.plugins.market.provider.CreditsProvider
import com.panomc.plugins.market.provider.FreeProvider
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.routes.api.OrderAccess
import com.panomc.plugins.market.routes.api.OrderRole
import com.panomc.plugins.market.routes.api.order.parsePayRequest
import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.payment.BuyerField
import com.panomc.plugins.market.spi.payment.ContinuePaymentRequest
import com.panomc.plugins.market.spi.payment.InstructionField
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.payment.PaymentContext
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentProvider
import com.panomc.plugins.market.spi.payment.PaymentQueryResult
import com.panomc.plugins.market.spi.payment.PaymentTarget
import com.panomc.plugins.market.spi.payment.StartPaymentRequest
import com.panomc.plugins.market.spi.payment.StartPaymentResult
import com.panomc.plugins.market.spi.testkit.TestContexts
import com.panomc.plugins.market.support.FakeClock
import com.panomc.plugins.market.support.FakePaymentProvider
import com.panomc.plugins.market.support.StaticProviderLookup
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.support.WebhookHarness
import com.panomc.plugins.market.util.DiscountUnit
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** The foreign effects of O2 as a test sees them: which effect ran for which order, and a switch that makes the next one fail. */
internal class RecordingEffects : ForeignEffects {
    val applied = CopyOnWriteArrayList<Pair<Long, String>>()

    /** The simple name of the effect that fails (and throws), `null` = none. */
    @Volatile
    var failOn: String? = null

    override suspend fun apply(conn: SqlConnection, locked: LockedOrder, effect: OrderEffect) {
        if (effect::class.simpleName == failOn) throw IllegalStateException("${effect::class.simpleName} is down")

        applied += locked.order.id to effect::class.simpleName!!
    }

    fun of(orderId: Long): List<String> = applied.filter { it.first == orderId }.map { it.second }
}

/**
 * Stand-in for `CreditService` capture / release / re-tender (MK-091, 07 sections 5 and 6.4): real ledger postings inside the transition's
 * transaction, so the invariants I1 to I4 and I3b judge them. Every call is recorded.
 */
internal class LedgerSettlement(private val w: TestWiring) : CreditSettlement {
    val captures = CopyOnWriteArrayList<Long>()
    val releases = CopyOnWriteArrayList<Long>()
    val retenders = CopyOnWriteArrayList<Pair<Long, Long>>()
    private val sequence = AtomicLong()

    override suspend fun capture(conn: SqlConnection, order: MarketOrder) {
        captures += order.id

        val user = w.creditAccounts.getByUserId(order.userId!!, conn)!!
        val hold = w.creditAccounts.getBySystemKey(CreditSystemKey.HOLD, conn)!!
        val spent = w.creditAccounts.getBySystemKey(CreditSystemKey.SPENT, conn)!!

        w.creditAccounts.lockByIds(listOf(user.id, hold.id, spent.id), conn)
        move(conn, hold, spent, order.creditAmount, CreditTxType.CAPTURE, "order:${order.id}:capture", order)
    }

    override suspend fun release(conn: SqlConnection, order: MarketOrder) {
        releases += order.id

        releaseOf(conn, order)
    }

    private suspend fun releaseOf(conn: SqlConnection, order: MarketOrder) {
        val user = w.creditAccounts.getByUserId(order.userId!!, conn)!!
        val hold = w.creditAccounts.getBySystemKey(CreditSystemKey.HOLD, conn)!!

        w.creditAccounts.lockByIds(listOf(user.id, hold.id), conn)
        move(conn, hold, user, order.creditAmount, CreditTxType.RELEASE, "order:${order.id}:release:${sequence.incrementAndGet()}", order)
    }

    override suspend fun retender(conn: SqlConnection, order: MarketOrder, newCredits: Long) {
        retenders += order.id to newCredits

        if (order.creditAmount > 0) releaseOf(conn, order)

        if (newCredits > 0) {
            val user = w.creditAccounts.getByUserId(order.userId!!, conn)!!
            val hold = w.creditAccounts.getBySystemKey(CreditSystemKey.HOLD, conn)!!

            w.creditAccounts.lockByIds(listOf(user.id, hold.id), conn)
            move(conn, user, hold, newCredits, CreditTxType.HOLD, "order:${order.id}:hold:${sequence.incrementAndGet()}", order)
        }
    }

    private suspend fun move(conn: SqlConnection, from: MarketCreditAccount, to: MarketCreditAccount, amount: Long, type: CreditTxType, key: String, order: MarketOrder) {
        if (w.creditAccounts.addToBalance(from.id, -amount, true, conn) == 0) throw InsufficientCredits(w.creditAccounts.getById(from.id, conn)!!.balance / 100.0)

        w.creditAccounts.addToBalance(to.id, amount, false, conn)

        val now = w.clock.now()
        val tx = w.creditTxs.add(MarketCreditTx(type = type, idempotencyKey = key, userId = order.userId, amount = amount, orderId = order.id, createdAt = now, updatedAt = now), conn)!!

        w.creditEntries.add(MarketCreditEntry(txId = tx, accountId = from.id, amount = -amount, balanceAfter = w.creditAccounts.getById(from.id, conn)!!.balance, createdAt = now, updatedAt = now), conn)
        w.creditEntries.add(MarketCreditEntry(txId = tx, accountId = to.id, amount = amount, balanceAfter = w.creditAccounts.getById(to.id, conn)!!.balance, createdAt = now, updatedAt = now), conn)
    }
}

/** The fake provider plus a `continuePayment` (the fake has none). */
internal class ContinuableFake(val fake: FakePaymentProvider) : PaymentProvider by fake {
    @Volatile
    var onContinue: (ContinuePaymentRequest) -> StartPaymentResult = { StartPaymentResult.Redirect("https://gateway.invalid/step2/${it.attempt.reference}") }

    val continued = CopyOnWriteArrayList<ContinuePaymentRequest>()

    override suspend fun continuePayment(ctx: PaymentContext, request: ContinuePaymentRequest): StartPaymentResult {
        continued += request

        return onContinue(request)
    }
}

/**
 * The object graph of a payment test (17 section 5.3): the checkout harness of MK-075 with the real [PaymentService] as its payment starter, a
 * real [OrderService] that can move orders (the real reservation service, the ledger and webhook stand-ins of the other slices), the free /
 * credits / bank transfer built-ins and the scriptable fake provider in the provider list.
 */
internal class PaymentHarness(val w: TestWiring, val vertx: Vertx, private val lockWaitSeconds: Int = 30) {
    val h = CheckoutHarness(w, vertx)
    val fake: FakePaymentProvider get() = h.fake
    val cipher = SecretCipher(ByteArray(32) { (it + 9).toByte() })
    val ledger = LedgerSettlement(w)
    val effects = RecordingEffects()
    val alerts = CopyOnWriteArrayList<Pair<Long, String?>>()
    val continuable = ContinuableFake(h.fake)
    val lookup = StaticProviderLookup(listOf(continuable, FreeProvider(), CreditsProvider(), BankTransferProvider()))
    val webhooks = WebhookHarness(w, vertx)

    @Volatile
    var statsCurrency = "EUR"

    lateinit var db: MarketDb
        private set

    lateinit var locks: Locks
        private set

    lateinit var orderService: OrderService
        private set

    lateinit var payments: PaymentService
        private set

    init {
        rebuild()
    }

    fun rebuild(startTimeoutMs: Long = PaymentService.START_TIMEOUT_MS, statusWaitMs: Long = PaymentService.STATUS_WAIT_MS, extraGuards: List<PaidGuard> = emptyList()) {
        db = MarketDb({ w.pool }, w.clock, lockWaitSeconds)
        locks = Locks(w.orders, w.orderItems, w.redemptions, w.creditAccounts)

        val redemptions = RedemptionService(w.clock, locks, w.redemptions)

        orderService = OrderService(
            w.clock, w.ids, w.orders, w.orderItems, w.orderEvents, w.payments, redemptions, { _, _ -> false },
            reservations = ReservationService(w.clock, locks, redemptions, w.orders), settlement = ledger, foreign = effects,
            webhooks = PaidWebhooks { conn, orderId -> webhooks.service.emitOrderPaid(conn, orderId) },
            rates = { sqlClient -> w.currencyRates.getAll(sqlClient).filter { it.rate.signum() > 0 }.associate { it.currency to it.rate } },
            statsCurrency = { statsCurrency }
        )
        payments = PaymentService(
            db = db, locks = locks, clock = w.clock, ids = w.ids, config = { h.config.toConfig() }, orders = w.orders, orderItems = w.orderItems, orderEvents = w.orderEvents,
            payments = w.payments, methods = w.paymentMethods, creditAccounts = w.creditAccounts, currencyRates = w.currencyRates, lookup = lookup, cipher = cipher,
            contexts = PaymentContexts { provider, settings, testMode -> TestContexts.payment(provider.id, settings, vertx, testMode) },
            orderService = orderService, site = { TestContexts.defaultSite() }, readClient = { w.pool }, products = w.products, entitlements = w.entitlements,
            alerts = PanelAlerts { orderId, reason -> alerts += orderId to reason }, startTimeoutMs = startTimeoutMs, statusWaitMs = statusWaitMs,
            extraPaidGuards = extraGuards
        )

        h.useStarter(payments)
    }

    suspend fun order(publicId: String): MarketOrder = w.orders.getByPublicId(publicId, w.pool)!!

    suspend fun order(id: Long): MarketOrder = w.orders.getById(id, w.pool)!!

    suspend fun attempts(orderId: Long): List<MarketPayment> = w.payments.getByOrderId(orderId, w.pool)

    /** A `Succeeded` event for [attempt] (its own amount and currency unless given). */
    suspend fun succeed(orderId: Long, attempt: MarketPayment, amount: Long = attempt.amount, currency: String = attempt.currency, testMode: Boolean? = null): AppliedEvent =
        payments.applyEvent(orderId, attempt.id, PaymentAttemptEvent.Succeeded(amount, currency, testMode))
}


/**
 * `PaymentService` and `OrderAccess` on a real MariaDB (MK-076; 06 sections 9 to 11 and 14.2, 02 section 6, 00 sections 6.9 and 7.2,
 * 11 sections 4.2 and 5; tests 36 to 44, 46, 50, 56, 57, 59 to 61 of 06 section 16): every start kind stored and re-served, the frozen tender,
 * `/pay` re-tendering with the cancel-before-start order, the amount and tender checks of a success, O2 with its effects in the order
 * transaction, the late payment, the owner's cancel, the status query, `continue`, and the object access. The invariants I1 to I22 are checked
 * after every test by the base class.
 */
class PaymentServiceIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var ph: PaymentHarness
    private val vertx: Vertx = Vertx.vertx()

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    @BeforeEach
    fun freshState() {
        runBlocking { resetState() }
        w = TestWiring(pool)
        ph = PaymentHarness(w, vertx)
    }

    private val fx get() = w.fixtures
    private val h get() = ph.h
    private val fake get() = ph.fake

    // ------------------------------------------------------------------------------------------------------ helpers

    private suspend fun buy(product: MarketProduct, method: String = "fake", quantity: Int = 1, caller: QuoteCaller = QuoteCaller.GUEST, vararg extra: Pair<String, Any?>): CheckoutResult =
        h.checkout(h.body("items" to listOf(h.line(product, quantity)), "paymentMethodId" to method, *extra), caller = caller)

    private suspend fun orderOf(result: CheckoutResult): MarketOrder = ph.order(result.order.getString("publicId"))

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

        assertEquals(code, e.getErrorCode(), "error code, body ${e.encode()}")
        assertEquals(status, e.getStatusCode())

        return JsonObject(e.encode())
    }

    private suspend fun pay(order: MarketOrder, method: String = "fake", credits: Long? = null, caller: PayCaller = PayCaller(), billing: JsonObject? = null): JsonObject? =
        ph.payments.pay(order, PayRequest(method, credits, billing), caller, pool)

    private suspend fun onlyOrder(): MarketOrder = ph.order(sql("SELECT `id` FROM `pano_market_order`").single().getLong("id"))

    private suspend fun count(orderId: Long, status: PaymentStatus): Int = ph.attempts(orderId).count { it.status == status }

    private fun result(req: StartPaymentRequest, amount: Long = req.amount.amount) =
        StartPaymentResult.Completed(PaymentEvent.Succeeded(PaymentTarget.Attempt(req.attempt.id), Money(amount, req.amount.currency)))

    // ================================================================================== start kinds (test 36)

    @Test
    fun `every start kind is stored encrypted, moves the attempt to PENDING and is re-served`(): Unit = runBlocking {
        data class Case(val kind: String, val start: (StartPaymentRequest) -> StartPaymentResult)

        val cases = listOf(
            Case("REDIRECT") { StartPaymentResult.Redirect("https://gateway.invalid/pay/${it.attempt.reference}") },
            Case("FORM_POST") { StartPaymentResult.FormPost("https://gateway.invalid/post", mapOf("a" to "1", "b" to "<x>")) },
            Case("IFRAME") { StartPaymentResult.Iframe("https://gateway.invalid/frame") },
            Case("HTML") { StartPaymentResult.Html("<html><form action=\"https://acs.example/3ds\"></form></html>") },
            Case("EMBEDDED") { StartPaymentResult.Embedded(JsonObject().put("hint", "enter your phone")).also { e -> e.component = "market:checkout:payment:fake" } },
            Case("INSTRUCTIONS") { StartPaymentResult.Instructions(LocalizedText.of("Pay <b>now</b>\nthen wait"), listOf(InstructionField(LocalizedText.of("IBAN"), "TR00 0000"))) }
        )

        fx.paymentMethod("fake")

        for (case in cases) {
            fake.onStart = case.start

            val result = buy(fx.product(price = 1000))
            val order = orderOf(result)
            val attempt = ph.attempts(order.id).single()

            assertEquals(PaymentStatus.PENDING, attempt.status, case.kind)
            assertEquals(case.kind, attempt.startKind, case.kind)
            assertEquals(case.kind, result.payment!!.getString("kind"), case.kind)
            assertNotNull(attempt.startedAt, case.kind)
            assertTrue(attempt.startPayload!!.startsWith("v1:"), "${case.kind}: the stored start is encrypted, never plain JSON")
            assertFalse(attempt.startPayload!!.contains("gateway.invalid"), case.kind)

            val stored = JsonObject(ph.cipher.decrypt(attempt.startPayload!!)!!)

            assertEquals(case.kind, stored.getJsonObject("start").getString("kind"), case.kind)
            assertEquals(result.payment, ph.payments.served(attempt, pool), "${case.kind}: the stored start is what the buyer was shown")
            assertEquals(result.payment, JsonObject(ph.payments.viewFor(order, OrderRole.OWNER, PayCaller(), pool).encode()).getJsonObject("payment").getJsonObject("start"), case.kind)

            val limited = ph.payments.viewFor(order, OrderRole.LIMITED, PayCaller(), pool)

            assertFalse(limited.encode().contains("gateway.invalid"), "${case.kind}: a limited view never shows the start")
            assertFalse(limited.containsKey("payment"), case.kind)
        }
    }

    @Test
    fun `a FORM_POST and an HTML document are kept for the attempt page, the start only points at it`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        fake.onStart = { StartPaymentResult.FormPost("https://gateway.invalid/post", mapOf("token" to "T1")).also { f -> f.acceptCharset = "ISO-8859-9" } }

        val form = buy(fx.product(price = 1000))
        val formAttempt = ph.attempts(orderOf(form).id).single()

        assertEquals("/api/market/payments/attempts/${formAttempt.token}/page", form.payment!!.getString("url"))
        assertFalse(form.payment!!.encode().contains("T1"), "the fields are not in the start the browser gets from the API")

        val storedForm = JsonObject(ph.cipher.decrypt(formAttempt.startPayload!!)!!).getJsonObject("formPost")

        assertEquals("https://gateway.invalid/post", storedForm.getString("actionUrl"))
        assertEquals("T1", storedForm.getJsonObject("fields").getString("token"))
        assertEquals("ISO-8859-9", storedForm.getString("acceptCharset"))

        fake.onStart = { StartPaymentResult.Html("<form></form>").also { html -> html.scriptOrigins = listOf("https://js.gateway.invalid"); html.inlineScript = true } }

        val html = buy(fx.product(price = 1000))
        val htmlAttempt = ph.attempts(orderOf(html).id).single()
        val storedHtml = JsonObject(ph.cipher.decrypt(htmlAttempt.startPayload!!)!!).getJsonObject("html")

        assertEquals("<form></form>", storedHtml.getString("document"))
        assertEquals(listOf("https://js.gateway.invalid"), storedHtml.getJsonArray("scriptOrigins").list)
        assertTrue(storedHtml.getBoolean("inlineScript"))
        assertFalse(html.payment!!.encode().contains("<form>"))
    }

    @Test
    fun `an instructions body is sanitised, escaped plain text never runs`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        fake.onStart = { StartPaymentResult.Instructions(LocalizedText.of("<script>alert(1)</script>Pay now"), emptyList()) }

        val body = buy(fx.product(price = 1000)).payment!!.getJsonObject("instructions").getString("body")

        assertFalse(body.contains("<script"), body)
        assertTrue(body.contains("Pay now"), body)
    }

    // ======================================================================================== the frozen tender

    @Test
    fun `the first attempt freezes amount, fee, credit part and order total`(): Unit = runBlocking {
        fx.paymentMethod("fake", feeMode = PaymentFeeMode.BUYER, feePercent = 1000, feeFixed = 50)

        val result = buy(fx.product(price = 2000))
        val order = orderOf(result)
        val attempt = ph.attempts(order.id).single()

        assertEquals(2000 + 200 + 50, order.totalPrice.toInt(), "10 % of 20.00 and a fixed 0.50 on top")
        assertEquals(order.gatewayAmount, attempt.amount)
        assertEquals(250, attempt.feeAmount)
        assertEquals(order.paymentFee, attempt.feeAmount)
        assertEquals(order.creditAmount, attempt.creditAmount)
        assertEquals(order.creditValue, attempt.creditValue)
        assertEquals(order.totalPrice, attempt.orderTotal)
        assertEquals(fake.calls(FakePaymentProvider.Op.START).single().let { (it.request as StartPaymentRequest).amount.amount }, attempt.amount, "the provider is asked for exactly that amount")
    }

    @Test
    fun `the provider request carries the order, the buyer, the urls, the idempotency key and the attempt window`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val product = fx.product(price = 1500, name = "Gold rank")
        val result = buy(product, quantity = 2, caller = QuoteCaller.GUEST.copy(ip = "203.0.113.5", agent = "Agent/1"))
        val order = orderOf(result)
        val attempt = ph.attempts(order.id).single()
        val request = fake.calls(FakePaymentProvider.Op.START).single().request as StartPaymentRequest

        assertEquals(attempt.id, request.attempt.id)
        assertEquals(attempt.reference, request.attempt.reference)
        assertEquals("pay:${attempt.reference}", request.idempotencyKey, "the gateway idempotency key of 06 section 9.2")
        assertEquals(order.publicId, request.order.publicId)
        assertEquals("Gold rank", request.order.lines.single().name)
        assertEquals(2, request.order.lines.single().quantity)
        assertEquals(order.totalPrice, request.order.total.amount)
        assertTrue(request.buyer.guest)
        assertEquals("g:steve", request.buyer.stableId)
        assertEquals("203.0.113.5", request.buyer.ip)
        assertEquals("Agent/1", request.buyer.userAgent)
        assertEquals(attempt.expiresAt, request.expiresAt)
        assertEquals("https://shop.example/api/market/payments/fake/return/${attempt.token}/success", request.urls.success)
        assertEquals("https://shop.example/api/market/payments/fake/notify/${attempt.token}", request.urls.notify)
        assertEquals("https://shop.example/store/order/${order.publicId}", request.urls.orderPage)
        assertNull(request.replaces)
        assertNull(request.subscription)
    }

    // ===================================================================================== failure and timeout (37)

    @Test
    fun `a provider error fails the attempt, keeps the order PENDING, sets the method error and answers 502 with the order`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        fake.failNext(FakePaymentProvider.Op.START, ProviderException(ProviderErrorCode.GATEWAY_REJECTED, "rejected", adminMessage = "merchant 123 is blocked"))

        val body = expect("PAYMENT_PROVIDER_ERROR", 502) { buy(fx.product(price = 1000)) }
        val order = onlyOrder()
        val attempt = ph.attempts(order.id).single()

        assertEquals("GATEWAY_REJECTED", body.getString("code"))
        assertEquals(order.publicId, body.getJsonObject("order").getString("publicId"))
        assertNotNull(body.getString("orderToken"))
        assertEquals(OrderStatus.PENDING, order.status)
        assertEquals(PaymentStatus.FAILED, attempt.status)
        assertEquals("GATEWAY_REJECTED", attempt.failureCode)
        assertEquals(PaymentService.START_FAILED_TEXT, attempt.failureMessage, "the buyer gets market's generic text, never the gateway's")
        assertEquals("merchant 123 is blocked", attempt.adminMessage)
        assertNotNull(attempt.closedAt)
        assertNull(attempt.startPayload)
        assertEquals("GATEWAY_REJECTED", w.paymentMethods.getByMethodId("fake", pool)!!.lastError)
        assertTrue(w.orderEvents.getByOrderId(order.id, pool).any { it.type == OrderEventType.PAYMENT_FAILED })
        assertFalse(body.encode().contains("merchant 123"), "the gateway's text is not in the response")
    }

    @Test
    fun `an unexpected exception is an INTERNAL failure, an unregistered provider a CONFIGURATION failure`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        fake.onStart = { throw IllegalStateException("boom with secret-token-1") }

        val first = expect("PAYMENT_PROVIDER_ERROR", 502) { buy(fx.product(price = 1000)) }

        assertEquals("INTERNAL", first.getString("code"))
        assertEquals("INTERNAL", ph.attempts(onlyOrder().id).single().failureCode)
        assertFalse(first.encode().contains("secret-token-1"))

        ph.lookup.remove("fake")

        val second = expect("PAYMENT_PROVIDER_ERROR", 502) { buy(fx.product(price = 1000)) }

        assertEquals("CONFIGURATION", second.getString("code"))
    }

    @Test
    fun `a provider that misses the deadline leaves the attempt CREATED with TIMEOUT, a later success still completes the order`(): Unit = runBlocking {
        ph.rebuild(startTimeoutMs = 300)
        fx.paymentMethod("fake")
        fake.delay(FakePaymentProvider.Op.START, 5_000)

        val body = expect("PAYMENT_PROVIDER_ERROR", 502) { buy(fx.product(price = 1000)) }
        val order = onlyOrder()
        val attempt = ph.attempts(order.id).single()

        assertEquals("GATEWAY_UNREACHABLE", body.getString("code"))
        assertEquals(PaymentStatus.CREATED, attempt.status, "the outcome is unknown: the attempt stays CREATED")
        assertEquals("TIMEOUT", attempt.failureCode)
        assertEquals(OrderStatus.PENDING, order.status)

        ph.succeed(order.id, attempt)

        assertEquals(OrderStatus.COMPLETED, ph.order(order.id).status)
        assertEquals(PaymentStatus.SUCCEEDED, ph.attempts(order.id).single().status)
    }

    @Test
    fun `an event that wins the race against tx2 is not moved back to PENDING`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        // the success arrives while startPayment has not returned yet (test 39)
        fake.onStart = { req ->
            runBlocking { ph.payments.applyEvent(req.order.id, req.attempt.id, PaymentAttemptEvent.Succeeded(req.amount.amount, req.amount.currency)) }

            StartPaymentResult.Redirect("https://gateway.invalid/late")
        }

        val result = buy(fx.product(price = 1000))
        val order = orderOf(result)
        val attempt = ph.attempts(order.id).single()

        assertEquals(OrderStatus.COMPLETED, order.status)
        assertEquals(PaymentStatus.SUCCEEDED, attempt.status, "tx2 never touches the status of an attempt that is no longer CREATED")
        assertNull(attempt.startPayload)
        assertNull(result.payment!!.getString("url"), "an order that is paid has nothing to redirect to")
        assertEquals("COMPLETED", result.payment!!.getString("kind"))
    }

    // ============================================================================ free and credit orders (56, 57)

    @Test
    fun `a free product is completed inside the checkout request`(): Unit = runBlocking {
        val gift = fx.product(price = 0, stock = 3)
        val result = h.checkout(h.body("items" to listOf(h.line(gift)))) // no payment method: the body's is ignored for a free order
        val order = orderOf(result)
        val attempt = ph.attempts(order.id).single()

        assertEquals(OrderStatus.COMPLETED, order.status)
        assertEquals("COMPLETED", result.payment!!.getString("kind"))
        assertEquals("COMPLETED", result.order.getString("status"))
        assertEquals("free", attempt.providerId)
        assertEquals(PaymentStatus.SUCCEEDED, attempt.status)
        assertEquals(0, attempt.paidAmount)
        assertEquals("COMPLETED", attempt.startKind)
        assertEquals(attempt.id, order.paymentId)
        assertEquals(ReservationState.COMMITTED, order.reservationState)
        assertNotNull(order.paidAt)
        assertNull(order.expiresAt)
        assertEquals(2, w.products.getById(gift.id, pool)!!.stock)
        assertEquals(1, w.products.getById(gift.id, pool)!!.soldCount)
        assertTrue(fake.calls.isEmpty(), "no gateway is involved in a free order")
        assertEquals(listOf("CREATED", "PAYMENT_STARTED", "PAYMENT_SUCCEEDED", "STATUS_CHANGED"), w.orderEvents.getByOrderId(order.id, pool).map { it.type.name })
    }

    @Test
    fun `a coupon that takes 100 percent makes a free order that completes at once and the redemption is applied`(): Unit = runBlocking {
        val coupon = fx.coupon("ALLFREE", DiscountUnit.PERCENT, 10_000)
        val result = buy(fx.product(price = 2000), "fake", 1, QuoteCaller.GUEST, "couponCode" to "ALLFREE")
        val order = orderOf(result)

        assertEquals(0, order.totalPrice)
        assertEquals(OrderStatus.COMPLETED, order.status)
        assertEquals("free", ph.attempts(order.id).single().providerId)
        assertEquals(RedemptionState.APPLIED, w.redemptions.getByOrderId(order.id, pool).single().state)
        assertEquals(1, w.coupons.getById(coupon.id, pool)!!.usedCount)
    }

    @Test
    fun `a credits-only order is completed in checkout, the hold is captured and the credits are spent`(): Unit = runBlocking {
        val (alex, caller) = user("Alex", credit = 5_000)
        val product = fx.product(price = 3000, creditPrice = 2500, stock = 2)
        val result = h.checkout(h.body("items" to listOf(h.line(product)), "paymentMethodId" to "credits", "payWithCredits" to true), caller = caller)
        val order = orderOf(result)
        val attempt = ph.attempts(order.id).single()

        assertEquals(OrderStatus.COMPLETED, order.status)
        assertEquals("credits", attempt.providerId)
        assertEquals(0, attempt.amount, "nothing for the gateway")
        assertEquals(2500, attempt.creditAmount)
        assertEquals(listOf(order.id), ph.ledger.captures, "captured exactly once, by the order's own hold")
        assertEquals(2500, fx.creditBalance(alex), "5000 - 2500 held, captured")
        assertEquals(0, w.creditAccounts.getBySystemKey(CreditSystemKey.HOLD, pool)!!.balance, "the hold account is empty again (I4)")
        assertEquals(2500, w.creditAccounts.getBySystemKey(CreditSystemKey.SPENT, pool)!!.balance)
        assertEquals(ReservationState.COMMITTED, order.reservationState)
    }

    @Test
    fun `a full-credit order whose start never ran is completed by pay with credits, no second hold, one capture`(): Unit = runBlocking {
        val (alex, caller) = user("Alex", credit = 5_000)
        val product = fx.product(price = 3000, creditPrice = 2500)

        // the crash between the order transaction and O2: the order is PENDING with its hold and a CREATED attempt
        h.useStarter(PaymentStarter.NONE)

        val result = h.checkout(h.body("items" to listOf(h.line(product)), "paymentMethodId" to "credits", "payWithCredits" to true), caller = caller)
        val order = orderOf(result)

        assertEquals(OrderStatus.PENDING, order.status)
        assertEquals(PaymentStatus.CREATED, ph.attempts(order.id).single().status)
        assertEquals(2500, order.creditAmount)
        assertEquals(2500, fx.creditBalance(alex), "held")

        h.useStarter(ph.payments)

        val start = pay(order, "credits")

        assertEquals("COMPLETED", start!!.getString("kind"))
        assertEquals(OrderStatus.COMPLETED, ph.order(order.id).status)
        assertEquals(listOf(PaymentStatus.CANCELLED, PaymentStatus.SUCCEEDED), ph.attempts(order.id).map { it.status })
        assertTrue(ph.ledger.retenders.isEmpty(), "the credit part did not change: nothing is posted")
        assertEquals(listOf(order.id), ph.ledger.captures)
        assertEquals(2500, w.creditAccounts.getBySystemKey(CreditSystemKey.SPENT, pool)!!.balance)
        assertEquals(2500, fx.creditBalance(alex))
    }

    @Test
    fun `an ordinary order cannot be switched to credits, its items carry no credit price`(): Unit = runBlocking {
        val (_, caller) = user("Alex", credit = 5_000)

        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 3000, creditPrice = 2500), "fake", 1, caller))

        assertEquals("NOT_PAYABLE_WITH_CREDITS", expect("PAYMENT_METHOD_UNAVAILABLE", 400) { pay(order, "credits") }.getString("reason"))
        assertEquals(1, ph.attempts(order.id).size)
        assertTrue(ph.ledger.retenders.isEmpty())
    }

    @Test
    fun `a free order whose start never ran is completed when its CREATED attempt is started again`(): Unit = runBlocking {
        h.useStarter(PaymentStarter.NONE)

        val order = orderOf(h.checkout(h.body("items" to listOf(h.line(fx.product(price = 0))))))
        val attempt = ph.attempts(order.id).single()

        assertEquals(OrderStatus.PENDING, order.status)
        assertEquals(PaymentStatus.CREATED, attempt.status)

        // what the reconcile job does for the built-ins (06 section 9.2 step 6)
        val start = ph.payments.startAttempt(order.id, attempt.id, emptyList(), pool)

        assertEquals("COMPLETED", start!!.getString("kind"))
        assertEquals(OrderStatus.COMPLETED, ph.order(order.id).status)

        // and again: nothing happens twice
        assertNull(ph.payments.startAttempt(order.id, attempt.id, emptyList(), pool))
        assertEquals(1, ph.effects.of(order.id).count { it == "IssueInvoice" })
    }

    @Test
    fun `a mixed order captures its hold when the gateway payment succeeds and releases it when the buyer cancels`(): Unit = runBlocking {
        h.config = h.config.copy(allowMixedCreditPayment = true)

        val (alex, caller) = user("Alex", credit = 8_000)

        fx.paymentMethod("fake")

        val paid = buy(fx.product(price = 10_000), "fake", 1, caller, "useCredits" to 80)
        val paidOrder = orderOf(paid)
        val attempt = ph.attempts(paidOrder.id).single()

        assertEquals(8000, paidOrder.creditAmount)
        assertEquals(2000, paidOrder.gatewayAmount)
        assertEquals(8000, attempt.creditAmount)
        assertEquals(0, fx.creditBalance(alex), "the 80 credits are held")

        ph.succeed(paidOrder.id, attempt)

        assertEquals(OrderStatus.COMPLETED, ph.order(paidOrder.id).status)
        assertEquals(listOf(paidOrder.id), ph.ledger.captures)
        assertEquals(8000, w.creditAccounts.getBySystemKey(CreditSystemKey.SPENT, pool)!!.balance)

        fx.credit(alex, 8_000)

        val open = buy(fx.product(price = 10_000), "fake", 1, caller, "useCredits" to 80)
        val openOrder = orderOf(open)

        assertEquals(0, fx.creditBalance(alex))

        ph.payments.cancel(openOrder, pool)

        assertEquals(OrderStatus.CANCELLED, ph.order(openOrder.id).status)
        assertEquals(8_000, fx.creditBalance(alex), "released, never refunded")
        assertEquals(listOf(openOrder.id), ph.ledger.releases)
    }

    // ================================================================= O2 (test 46) and what it writes

    @Test
    fun `O2 commits the reservation, applies the redemption, stamps the order, clears the expiry and runs the foreign effects once`(): Unit = runBlocking {
        val coupon = fx.coupon("TEN", DiscountUnit.PERCENT, 1000)
        val product = fx.product(price = 1000, stock = 5)

        fx.paymentMethod("fake")

        val result = buy(product, "fake", 2, QuoteCaller.GUEST, "couponCode" to "TEN")
        val order = orderOf(result)
        val attempt = ph.attempts(order.id).single()

        assertEquals(ReservationState.HELD, order.reservationState)
        assertEquals(3, w.products.getById(product.id, pool)!!.stock)
        assertEquals(0, w.products.getById(product.id, pool)!!.soldCount)

        w.clock.advance(5_000)
        ph.succeed(order.id, attempt)

        val paid = ph.order(order.id)

        assertEquals(OrderStatus.COMPLETED, paid.status)
        assertEquals(ReservationState.COMMITTED, paid.reservationState)
        assertEquals(RedemptionState.APPLIED, w.redemptions.getByOrderId(order.id, pool).single().state)
        assertEquals(1, w.coupons.getById(coupon.id, pool)!!.usedCount, "used once at O1, never again")
        assertEquals(3, w.products.getById(product.id, pool)!!.stock, "stock was deducted at O1")
        assertEquals(2, w.products.getById(product.id, pool)!!.soldCount)
        assertEquals(w.clock.now(), paid.paidAt)
        assertEquals(attempt.id, paid.paymentId)
        assertEquals(attempt.amount, paid.paidAmount)
        assertNull(paid.expiresAt)
        assertEquals(1.0, paid.exchangeRate, "order and stats currency are both EUR")
        assertEquals(PaymentStatus.SUCCEEDED, ph.attempts(order.id).single().status)
        assertEquals(attempt.amount, ph.attempts(order.id).single().paidAmount)
        assertEquals("EUR", ph.attempts(order.id).single().paidCurrency)
        assertNull(ph.attempts(order.id).single().startPayload)
        assertNotNull(ph.attempts(order.id).single().closedAt)
        assertEquals(
            listOf("AccrueCreatorEarning", "GrantCashback", "CreditGrantingLines", "GrantEntitlements", "QueueGrantDeliveries", "SubscriptionOnOrderPaid", "IssueInvoice", "QueueMail", "AdvanceGoalProgress", "StartShipping"),
            ph.effects.of(order.id), "the effects of other slices, in the order of the state machine, each once"
        )

        // applying the same success again changes nothing
        val again = ph.succeed(order.id, attempt)

        assertFalse(again.changed)
        assertEquals(2, w.products.getById(product.id, pool)!!.soldCount)
        assertEquals(10, ph.effects.of(order.id).size)
    }

    @Test
    fun `a test order is not counted as sold`(): Unit = runBlocking {
        h.config = h.config.copy(testMode = true)
        fx.paymentMethod("fake")

        val product = fx.product(price = 1000, stock = 2)
        val order = orderOf(buy(product, caller = QuoteCaller.GUEST.copy(test = true)))

        ph.succeed(order.id, ph.attempts(order.id).single(), testMode = true)

        assertEquals(OrderStatus.COMPLETED, ph.order(order.id).status)
        assertEquals(0, w.products.getById(product.id, pool)!!.soldCount, "a test payment never counts (I17)")
        assertTrue(ph.order(order.id).testMode)
    }

    @Test
    fun `the exchange rate is frozen from the stored rate table, null when it cannot say`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        ph.statsCurrency = "USD"

        val noRate = orderOf(buy(fx.product(price = 1000)))

        ph.succeed(noRate.id, ph.attempts(noRate.id).single())

        assertNull(ph.order(noRate.id).exchangeRate, "no USD rate stored: the stats fall back to the view rate")

        w.currencyRates.upsert(MarketCurrencyRate(currency = "USD", rate = java.math.BigDecimal("1.25"), mode = CurrencyRateMode.MANUAL), pool)

        val withRate = orderOf(buy(fx.product(price = 1000)))

        ph.succeed(withRate.id, ph.attempts(withRate.id).single())

        assertEquals(1.25, ph.order(withRate.id).exchangeRate)
    }

    @Test
    fun `a foreign effect that fails rolls the whole success back, nothing is committed, nothing is half done`(): Unit = runBlocking {
        val coupon = fx.coupon("TWO", DiscountUnit.PERCENT, 1000)
        val product = fx.product(price = 1000, stock = 3)

        fx.paymentMethod("fake")

        val order = orderOf(buy(product, "fake", 1, QuoteCaller.GUEST, "couponCode" to "TWO"))
        val attempt = ph.attempts(order.id).single()

        fx.webhookEndpoint()

        ph.effects.failOn = "StartShipping"

        assertThrows(IllegalStateException::class.java) { runBlocking { ph.succeed(order.id, attempt) } }
        assertEquals(0, count("market_webhook_delivery"), "the order.paid row was written before the failing effect and is gone with the rollback")

        val after = ph.order(order.id)

        assertEquals(OrderStatus.PENDING, after.status)
        assertEquals(ReservationState.HELD, after.reservationState)
        assertEquals(PaymentStatus.PENDING, ph.attempts(order.id).single().status, "the attempt is rolled back with the order")
        assertEquals(RedemptionState.HELD, w.redemptions.getByOrderId(order.id, pool).single().state)
        assertEquals(0, w.products.getById(product.id, pool)!!.soldCount)
        assertEquals(0, count("market_webhook_delivery"), "the webhook row is in the same transaction")

        ph.effects.failOn = null
        ph.succeed(order.id, attempt)

        assertEquals(OrderStatus.COMPLETED, ph.order(order.id).status, "the event can be applied again")
        assertEquals(1, count("market_webhook_delivery"))
    }

    @Test
    fun `O2 writes the order paid webhook in its own transaction, once, and a review writes none`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        fx.webhookEndpoint(events = "[\"order.paid\"]")

        val paid = orderOf(buy(fx.product(price = 1000)))
        val attempt = ph.attempts(paid.id).single()

        assertEquals(0, count("market_webhook_delivery"))

        ph.succeed(paid.id, attempt)
        ph.succeed(paid.id, attempt)

        val rows = sql("SELECT `event`, `orderId` FROM `pano_market_webhook_delivery`")

        assertEquals(1, rows.size)
        assertEquals("order.paid", rows.single().getString("event"))
        assertEquals(paid.id, rows.single().getLong("orderId"))

        val underpaid = orderOf(buy(fx.product(price = 1000)))

        ph.succeed(underpaid.id, ph.attempts(underpaid.id).single(), amount = 100)

        assertEquals(OrderStatus.REVIEW, ph.order(underpaid.id).status)
        assertEquals(1, count("market_webhook_delivery"), "no order.paid for an order that waits for a human")
    }

    @Test
    fun `a success on a second attempt cancels the open ones and tells the gateway after the commit`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        fake.caps = PaymentCapabilities().also { it.cancelPending = true }

        val order = orderOf(buy(fx.product(price = 1000)))
        val first = ph.attempts(order.id).single()

        pay(order)

        val second = ph.attempts(order.id).last()

        assertEquals(PaymentStatus.CANCELLED, ph.attempts(order.id).first().status)
        assertEquals(1, fake.calls(FakePaymentProvider.Op.CANCEL).size)

        // the old attempt is paid at the gateway after all: its tender equals the order's, so the order is complete and the open one cancelled (test 41, 60)
        ph.succeed(order.id, first)

        assertEquals(OrderStatus.COMPLETED, ph.order(order.id).status)
        assertEquals(PaymentStatus.SUCCEEDED, ph.attempts(order.id).first().status)
        assertEquals(PaymentStatus.CANCELLED, ph.attempts(order.id).last().status)
        assertEquals(second.id, ph.attempts(order.id).last().id)
        assertEquals(2, fake.calls(FakePaymentProvider.Op.CANCEL).size, "the newer open attempt is cancelled at the gateway too, after the commit")
        assertEquals(first.id, ph.order(order.id).paymentId)
    }

    @Test
    fun `a second paid attempt of a paid order is flagged duplicate and changes nothing else`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000)))
        val first = ph.attempts(order.id).single()

        pay(order)

        val second = ph.attempts(order.id).last()

        ph.succeed(order.id, second)
        ph.succeed(order.id, first)

        assertEquals(OrderStatus.COMPLETED, ph.order(order.id).status)
        assertEquals(second.id, ph.order(order.id).paymentId, "the order keeps its first payment")
        assertTrue(ph.attempts(order.id).first().duplicate)
        assertFalse(ph.attempts(order.id).last().duplicate)
        assertEquals(1, ph.effects.of(order.id).count { it == "IssueInvoice" }, "side effects once")
    }

    // ================================================================== amount and environment checks (test 40)

    @Test
    fun `an underpaid, overpaid or wrong-currency success is a review with nothing delivered`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val cases = listOf(
            Triple("UNDERPAID", 999L, "EUR"), Triple("OVERPAID", 1001L, "EUR"), Triple("CURRENCY_MISMATCH", 1000L, "USD")
        )

        for ((reason, amount, currency) in cases) {
            val order = orderOf(buy(fx.product(price = 1000)))
            val attempt = ph.attempts(order.id).single()
            val applied = ph.succeed(order.id, attempt, amount, currency)

            assertEquals(PaymentStatus.REVIEW, applied.attemptStatus, reason)
            assertEquals(OrderStatus.REVIEW, ph.order(order.id).status, reason)
            assertEquals(reason, ph.order(order.id).reviewReason, reason)
            assertEquals(ReservationState.HELD, ph.order(order.id).reservationState, "$reason: the reservation stays")
            assertNull(ph.order(order.id).expiresAt, "$reason: expiry is paused")
            assertEquals(attempt.id, ph.order(order.id).paymentId, reason)
            assertEquals(amount, ph.order(order.id).paidAmount, "$reason: what arrived is recorded for a later refund")
            assertTrue(ph.effects.of(order.id).isEmpty(), "$reason: nothing is delivered")
            assertTrue(ph.alerts.any { it.first == order.id && it.second == reason }, "$reason: the panel is told after the commit")
            assertTrue(w.orderEvents.getByOrderId(order.id, pool).any { it.type == OrderEventType.REVIEW_OPENED }, reason)
        }
    }

    @Test
    fun `a provider that lets the buyer pay more accepts an overpayment and records what was paid`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        fake.caps = PaymentCapabilities().also { it.buyerMayPayMore = true }

        val order = orderOf(buy(fx.product(price = 1000)))
        val attempt = ph.attempts(order.id).single()

        ph.succeed(order.id, attempt, amount = 1100)

        assertEquals(OrderStatus.COMPLETED, ph.order(order.id).status)
        assertEquals(1100, ph.order(order.id).paidAmount)
        assertEquals(1100, ph.attempts(order.id).single().paidAmount)
    }

    @Test
    fun `a success from another environment than the attempt's is a review, never an O2`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000)))

        ph.succeed(order.id, ph.attempts(order.id).single(), testMode = true)

        assertEquals(OrderStatus.REVIEW, ph.order(order.id).status)
        assertEquals("OTHER", ph.order(order.id).reviewReason)
        assertTrue(ph.effects.of(order.id).isEmpty())
    }

    @Test
    fun `a gateway that sets its own prices is exempt from the amount check`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        fake.caps = PaymentCapabilities().also { it.priceAuthority = com.panomc.plugins.market.spi.payment.PriceAuthority.GATEWAY_CATALOG }

        val order = orderOf(buy(fx.product(price = 1000)))

        // the amount is not market's to judge here: 5.00 paid on a 10.00 attempt completes
        ph.succeed(order.id, ph.attempts(order.id).single(), amount = 500)

        assertEquals(OrderStatus.COMPLETED, ph.order(order.id).status)
    }

    // ===================================================================== the pay-less attack (test 59), V-01

    @Test
    fun `paying a cheaper superseded attempt after the credits were released is a review, nothing is captured or delivered`(): Unit = runBlocking {
        h.config = h.config.copy(allowMixedCreditPayment = true)

        val (alex, caller) = user("Alex", credit = 8_000)

        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 10_000), "fake", 1, caller, "useCredits" to 80))
        val cheap = ph.attempts(order.id).single()

        assertEquals(2000, cheap.amount)
        assertEquals(8000, cheap.creditAmount)

        // the buyer drops the credits: a 100.00 attempt, the 80 credits are released, the cheap attempt is cancelled locally only
        pay(order, credits = 0)

        val reloaded = ph.order(order.id)

        assertEquals(0, reloaded.creditAmount)
        assertEquals(10_000, reloaded.gatewayAmount)
        assertEquals(8_000, fx.creditBalance(alex), "released")
        assertEquals(PaymentStatus.CANCELLED, ph.attempts(order.id).first().status)

        // ... and then the old 20.00 payment arrives at the gateway
        val applied = ph.succeed(order.id, cheap)

        assertEquals(PaymentStatus.SUCCEEDED, applied.attemptStatus, "money arrived, the attempt records it")
        assertEquals(OrderStatus.REVIEW, ph.order(order.id).status)
        assertEquals("AMOUNT_MISMATCH", ph.order(order.id).reviewReason)
        assertTrue(ph.ledger.captures.isEmpty(), "no capture")
        assertTrue(ph.effects.of(order.id).isEmpty(), "nothing delivered")
        assertEquals(ReservationState.HELD, ph.order(order.id).reservationState)
        assertEquals(cheap.id, ph.order(order.id).paymentId)
        assertEquals(2000, ph.order(order.id).paidAmount, "the 20.00 that arrived are on the order, a rejection refunds them")
        assertEquals(PaymentStatus.CANCELLED, ph.attempts(order.id).last().status, "the newer open attempt is cancelled")
        assertEquals(0, count("market_credit_tx", "`type` = 'CAPTURE'"))
    }

    @Test
    fun `the mirror case, paying the 100 attempt after 80 credits were held leaves the credits held and uncaptured`(): Unit = runBlocking {
        h.config = h.config.copy(allowMixedCreditPayment = true)

        val (alex, caller) = user("Alex", credit = 8_000)

        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 10_000), "fake", 1, caller))
        val full = ph.attempts(order.id).single()

        assertEquals(10_000, full.amount)

        pay(order, credits = 8_000)

        val reloaded = ph.order(order.id)

        assertEquals(8_000, reloaded.creditAmount)
        assertEquals(2_000, reloaded.gatewayAmount)
        assertEquals(0, fx.creditBalance(alex))

        ph.succeed(order.id, full)

        assertEquals(OrderStatus.REVIEW, ph.order(order.id).status)
        assertEquals("AMOUNT_MISMATCH", ph.order(order.id).reviewReason)
        assertTrue(ph.ledger.captures.isEmpty())
        assertEquals(8_000, w.creditAccounts.getBySystemKey(CreditSystemKey.HOLD, pool)!!.balance, "the 80 credits stay held")
        assertEquals(0, fx.creditBalance(alex))
        assertEquals(10_000, ph.order(order.id).paidAmount)
        assertTrue(ph.effects.of(order.id).isEmpty())
    }

    @Test
    fun `the same tender after a retry is not an attack, the superseded attempt completes the order`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000)))
        val old = ph.attempts(order.id).single()

        pay(order)

        assertEquals(old.amount, ph.attempts(order.id).last().amount)

        ph.succeed(order.id, old)

        assertEquals(OrderStatus.COMPLETED, ph.order(order.id).status)
    }

    // ============================================================================================ /pay (test 38)

    @Test
    fun `pay cancels the open attempt at the gateway before it calls startPayment, re-prices the fee and leaves the lines alone`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        fake.caps = PaymentCapabilities().also { it.cancelPending = true }

        val product = fx.product(price = 2000)
        val order = orderOf(buy(product))
        val before = ph.order(order.id)
        val items = w.orderItems.getByOrderIds(listOf(order.id), pool).map { it.lineTotal to it.unitPrice }

        // a method with a fee
        fx.paymentMethod("fake", feeMode = PaymentFeeMode.BUYER, feePercent = 1000)

        val start = pay(order)

        val after = ph.order(order.id)
        val attempts = ph.attempts(order.id)

        assertEquals(2, attempts.size)
        assertEquals(PaymentStatus.CANCELLED, attempts[0].status)
        assertNotNull(attempts[0].closedAt)
        assertNull(attempts[0].startPayload)
        assertEquals(PaymentStatus.PENDING, attempts[1].status)
        assertEquals("REDIRECT", start!!.getString("kind"))
        assertEquals(before.totalPrice + 200, after.totalPrice, "10 % fee on 20.00")
        assertEquals(200, after.paymentFee)
        assertEquals(after.totalPrice, after.gatewayAmount + after.creditValue)
        assertEquals(after.gatewayAmount, attempts[1].amount)
        assertEquals(200, attempts[1].feeAmount)
        assertEquals(after.totalPrice, attempts[1].orderTotal)
        assertEquals(before.gatewayAmount, attempts[0].amount, "the superseded attempt keeps its own tender")
        assertEquals(items, w.orderItems.getByOrderIds(listOf(order.id), pool).map { it.lineTotal to it.unitPrice }, "lines never change after O1")
        assertEquals(before.subtotal, after.subtotal)
        assertEquals(before.discountTotal, after.discountTotal)
        assertEquals(before.shippingTotal, after.shippingTotal)
        assertTrue(after.updatedAt > before.updatedAt)

        // the order of the provider calls: start, cancel of the old attempt, start of the new one
        val ops = fake.calls.map { it.op }

        assertEquals(listOf(FakePaymentProvider.Op.START, FakePaymentProvider.Op.CANCEL, FakePaymentProvider.Op.START), ops, "cancelPayment comes before the second startPayment")

        val second = fake.calls(FakePaymentProvider.Op.START).last().request as StartPaymentRequest

        assertEquals(attempts[0].reference, second.replaces!!.reference, "the cancelled attempt is handed over as `replaces`")
        assertEquals("CANCELLED", second.replaces!!.status)
        assertTrue(w.orderEvents.getByOrderId(order.id, pool).any { it.type == OrderEventType.PAYMENT_CANCELLED })
        assertEquals(2, w.orderEvents.getByOrderId(order.id, pool).count { it.type == OrderEventType.PAYMENT_STARTED })
    }

    @Test
    fun `pay raises the order expiry with the new attempt window`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000)))

        w.clock.advance(30 * 60_000L)
        pay(order)

        val after = ph.order(order.id)

        assertEquals(w.clock.now() + 60 * 60_000L, ph.attempts(order.id).last().expiresAt)
        assertEquals(ph.attempts(order.id).last().expiresAt, after.expiresAt, "max(order.expiresAt, attempt.expiresAt)")
    }

    @Test
    fun `pay is refused while an attempt is PROCESSING, under review or succeeded, after expiry and near the hard cap`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val processing = orderOf(buy(fx.product(price = 1000)))

        ph.payments.applyEvent(processing.id, ph.attempts(processing.id).single().id, PaymentAttemptEvent.Pending)

        assertEquals(PaymentStatus.PROCESSING, ph.attempts(processing.id).single().status)
        expect("ORDER_NOT_PAYABLE", 409) { pay(processing) }
        assertEquals(1, ph.attempts(processing.id).size, "nothing was created")

        val review = orderOf(buy(fx.product(price = 1000)))

        ph.succeed(review.id, ph.attempts(review.id).single(), amount = 1)
        expect("ORDER_NOT_PAYABLE", 409) { pay(review) }

        val paid = orderOf(buy(fx.product(price = 1000)))

        ph.succeed(paid.id, ph.attempts(paid.id).single())
        expect("ORDER_NOT_PAYABLE", 409) { pay(paid) }

        val expired = orderOf(buy(fx.product(price = 1000)))

        w.clock.advance(61 * 60_000L)
        expect("ORDER_NOT_PAYABLE", 409) { pay(expired) }

        // the order window is long (a bank transfer) but retries may not keep stock beyond the hard cap of 24 hours
        val late = orderOf(buy(fx.product(price = 1000)))

        sql("UPDATE `pano_market_order` SET `expiresAt` = ? WHERE `id` = ?", w.clock.now() + 2 * 86_400_000L, late.id)
        w.clock.advance(24 * 3_600_000L - 4 * 60_000L)
        expect("ORDER_NOT_PAYABLE", 409) { pay(ph.order(late.id)) }
    }

    @Test
    fun `a test-mode method is refused to the public and to guests, accepted for holders of SET or PAY`(): Unit = runBlocking {
        h.config = h.config.copy(testMode = true)
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000), caller = QuoteCaller.GUEST.copy(test = true)))

        assertTrue(ph.attempts(order.id).single().testMode)

        val denied = expect("PAYMENT_METHOD_UNAVAILABLE", 400) { pay(order) }

        assertEquals("TEST_MODE", denied.getString("reason"))
        assertEquals(1, ph.attempts(order.id).size)

        pay(order, caller = PayCaller(canUseTestMode = true))

        assertEquals(2, ph.attempts(order.id).size)
        assertTrue(ph.attempts(order.id).last().testMode)
    }

    @Test
    fun `pay refuses a method that is not offered, free, a locked provider and a bad credit request`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        fx.paymentMethod("disabled", enabled = false)

        val order = orderOf(buy(fx.product(price = 1000)))

        assertEquals("METHOD_NOT_OFFERED", expect("PAYMENT_METHOD_UNAVAILABLE", 400) { pay(order, "nope") }.getString("reason"))
        assertEquals("METHOD_NOT_OFFERED", expect("PAYMENT_METHOD_UNAVAILABLE", 400) { pay(order, "disabled") }.getString("reason"))
        assertEquals("METHOD_NOT_OFFERED", expect("PAYMENT_METHOD_UNAVAILABLE", 400) { pay(order, "free") }.getString("reason"))

        // an order priced by a gateway keeps its provider
        sql("UPDATE `pano_market_order` SET `pricingMode` = 'EXTERNAL' WHERE `id` = ?", order.id)
        fx.paymentMethod("bank-transfer")

        assertEquals("METHOD_LOCKED", expect("PAYMENT_METHOD_UNAVAILABLE", 400) { pay(ph.order(order.id), "bank-transfer") }.getString("reason"))

        // credits on a store without mixed payment
        sql("UPDATE `pano_market_order` SET `pricingMode` = 'MARKET' WHERE `id` = ?", order.id)

        val guest = expect("PAYMENT_METHOD_UNAVAILABLE", 400) { pay(ph.order(order.id), credits = 100) }

        assertEquals("MIXED_CREDIT_NOT_SUPPORTED", guest.getString("reason"))
        assertEquals(1, ph.attempts(order.id).size, "every refusal left the order as it was")
    }

    @Test
    fun `pay checks the billing info the new provider requires and stores the one it is sent`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000)))

        // the provider asks for more now than it did at checkout
        fake.caps = PaymentCapabilities().also { it.requiredBuyerFields = setOf(BuyerField.FIRST_NAME, BuyerField.LAST_NAME) }

        val body = expect("BUYER_INFO_REQUIRED", 400) { pay(order) }

        assertEquals(listOf("billingInfo.firstName", "billingInfo.lastName"), body.getJsonArray("fields").list)

        pay(order, billing = JsonObject().put("firstName", "Ada").put("lastName", "Lovelace"))

        val stored = JsonObject(ph.order(order.id).billingInfo!!)

        assertEquals("Ada", stored.getString("firstName"))
        assertEquals("Lovelace", stored.getString("lastName"))
        assertEquals("Ada", (fake.calls(FakePaymentProvider.Op.START).last().request as StartPaymentRequest).buyer.firstName)
    }

    @Test
    fun `switching the credit part posts through the ledger and a refused ledger leaves the whole order unchanged`(): Unit = runBlocking {
        h.config = h.config.copy(allowMixedCreditPayment = true)

        val (alex, caller) = user("Alex", credit = 5_000)

        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 10_000), "fake", 1, caller))

        pay(order, credits = 3_000)

        assertEquals(listOf(order.id to 3_000L), ph.ledger.retenders)
        assertEquals(3_000, ph.order(order.id).creditAmount)
        assertEquals(2_000, fx.creditBalance(alex))

        // more than the buyer has, counting what is already held for this order: 52 credits are 5200 > 5000
        val rich = expect("INSUFFICIENT_CREDITS", 400) { pay(ph.order(order.id), credits = 5_200) }

        assertEquals(50.0, rich.getDouble("balance"), "balance plus the order's own hold")
        assertEquals(3_000, ph.order(order.id).creditAmount)
        assertEquals(2, ph.attempts(order.id).size)
        assertEquals(1, count(order.id, PaymentStatus.PENDING))

        // up to exactly that
        pay(ph.order(order.id), credits = 5_000)

        assertEquals(5_000, ph.order(order.id).creditAmount)
        assertEquals(0, fx.creditBalance(alex))
        assertEquals(5_000, ph.attempts(order.id).last().creditAmount)

        // an unchanged credit part never touches the ledger
        val calls = ph.ledger.retenders.size

        pay(ph.order(order.id))

        assertEquals(calls, ph.ledger.retenders.size)
    }

    @Test
    fun `without a ledger the credit part cannot change and the order stays as it was`(): Unit = runBlocking {
        h.config = h.config.copy(allowMixedCreditPayment = true)

        val (_, caller) = user("Alex", credit = 5_000)

        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 10_000), "fake", 1, caller))
        val before = ph.order(order.id)

        // an order service whose settlement is the production default (MK-091 not wired)
        val plain = OrderService(
            w.clock, w.ids, w.orders, w.orderItems, w.orderEvents, w.payments, RedemptionService(w.clock, ph.locks, w.redemptions), { _, _ -> false },
            reservations = ReservationService(w.clock, ph.locks, RedemptionService(w.clock, ph.locks, w.redemptions), w.orders)
        )
        val service = PaymentService(
            ph.db, ph.locks, w.clock, w.ids, { h.config.toConfig() }, w.orders, w.orderItems, w.orderEvents, w.payments, w.paymentMethods, w.creditAccounts, w.currencyRates,
            ph.lookup, ph.cipher, PaymentContexts { p, s, t -> TestContexts.payment(p.id, s, vertx, t) }, plain, { TestContexts.defaultSite() }, { pool },
            w.products, w.entitlements
        )

        expect("CREDITS_DISABLED", 409) { service.pay(before, PayRequest("fake", 4_000, null), PayCaller(), pool) }

        assertEquals(before.creditAmount, ph.order(order.id).creditAmount)
        assertEquals(1, ph.attempts(order.id).size)
        assertEquals(PaymentStatus.PENDING, ph.attempts(order.id).single().status, "the open attempt was not cancelled by the refused call")
    }

    // ============================================================================================ continue

    @Test
    fun `continue stores the next step of an embedded form, a completed step completes the order`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        fake.onStart = { StartPaymentResult.Embedded(JsonObject().put("step", 1)) }

        val order = orderOf(buy(fx.product(price = 1000)))

        assertEquals("EMBEDDED", ph.attempts(order.id).single().startKind)

        val next = ph.payments.continuePayment(order, JsonObject().put("phone", "5551234567"), PayCaller(), pool)

        assertEquals("REDIRECT", next!!.getString("kind"))
        assertEquals("5551234567", ph.continuable.continued.single().values.getString("phone"))
        assertEquals("REDIRECT", ph.attempts(order.id).single().startKind)
        assertEquals(next, ph.payments.served(ph.attempts(order.id).single(), pool))

        // a redirect is no form any more
        expect("ORDER_NOT_PAYABLE", 409) { ph.payments.continuePayment(order, JsonObject(), PayCaller(), pool) }

        val paying = orderOf(buy(fx.product(price = 1000)))

        ph.continuable.onContinue = { req -> StartPaymentResult.Completed(PaymentEvent.Succeeded(PaymentTarget.Attempt(req.attempt.id), Money(req.attempt.amount.amount, req.attempt.amount.currency))) }
        fake.onStart = { StartPaymentResult.Embedded(JsonObject()) }

        val embedded = orderOf(buy(fx.product(price = 1000)))
        val done = ph.payments.continuePayment(embedded, JsonObject(), PayCaller(), pool)

        assertEquals("COMPLETED", done!!.getString("kind"))
        assertEquals(OrderStatus.COMPLETED, ph.order(embedded.id).status)
        assertNotNull(paying)
    }

    @Test
    fun `a provider error in continue answers 502 and leaves the attempt PENDING`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        fake.onStart = { StartPaymentResult.Embedded(JsonObject()) }
        ph.continuable.onContinue = { throw ProviderException(ProviderErrorCode.GATEWAY_REJECTED, "bad phone") }

        val order = orderOf(buy(fx.product(price = 1000)))
        val body = expect("PAYMENT_PROVIDER_ERROR", 502) { ph.payments.continuePayment(order, JsonObject(), PayCaller(), pool) }

        assertEquals("GATEWAY_REJECTED", body.getString("code"))
        assertEquals(PaymentStatus.PENDING, ph.attempts(order.id).single().status)
        assertEquals("EMBEDDED", ph.attempts(order.id).single().startKind)
    }

    // ============================================================================================ cancel (test 50)

    @Test
    fun `the owner's cancel releases stock and coupon, closes the attempts and tells the gateway after the commit`(): Unit = runBlocking {
        val coupon = fx.coupon("CANC", DiscountUnit.PERCENT, 1000)
        val product = fx.product(price = 1000, stock = 4)

        fx.paymentMethod("fake")
        fake.caps = PaymentCapabilities().also { it.cancelPending = true }

        val order = orderOf(buy(product, "fake", 2, QuoteCaller.GUEST, "couponCode" to "CANC"))

        assertEquals(2, w.products.getById(product.id, pool)!!.stock)
        assertEquals(OrderStatus.CANCELLED, ph.payments.cancel(order, pool).let { ph.order(order.id).status })

        val cancelled = ph.order(order.id)

        assertEquals(ReservationState.RELEASED, cancelled.reservationState)
        assertEquals(4, w.products.getById(product.id, pool)!!.stock)
        assertEquals(0, w.coupons.getById(coupon.id, pool)!!.usedCount)
        assertEquals(RedemptionState.RELEASED, w.redemptions.getByOrderId(order.id, pool).single().state)
        assertEquals(PaymentStatus.CANCELLED, ph.attempts(order.id).single().status)
        assertNull(ph.attempts(order.id).single().startPayload)
        assertEquals(1, fake.calls(FakePaymentProvider.Op.CANCEL).size, "cancelPayment once, after the commit")
        assertTrue(w.orderEvents.getByOrderId(order.id, pool).any { it.type == OrderEventType.STATUS_CHANGED && it.toStatus == "CANCELLED" && it.actorType == OrderActorType.BUYER })

        // a repeated cancel is idempotent, a second release changes nothing
        ph.payments.cancel(ph.order(order.id), pool)

        assertEquals(4, w.products.getById(product.id, pool)!!.stock)
        assertEquals(1, fake.calls(FakePaymentProvider.Op.CANCEL).size)
    }

    @Test
    fun `a buyer cannot cancel a PROCESSING, paid or renewal order`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val processing = orderOf(buy(fx.product(price = 1000)))

        ph.payments.applyEvent(processing.id, ph.attempts(processing.id).single().id, PaymentAttemptEvent.Pending)
        expect("ORDER_NOT_CANCELLABLE", 409) { ph.payments.cancel(processing, pool) }
        assertEquals(OrderStatus.PENDING, ph.order(processing.id).status)

        val paid = orderOf(buy(fx.product(price = 1000)))

        ph.succeed(paid.id, ph.attempts(paid.id).single())
        expect("ORDER_NOT_CANCELLABLE", 409) { ph.payments.cancel(ph.order(paid.id), pool) }
        assertEquals(OrderStatus.COMPLETED, ph.order(paid.id).status)

        val renewal = orderOf(buy(fx.product(price = 1000)))

        sql("UPDATE `pano_market_order` SET `source` = 'RENEWAL' WHERE `id` = ?", renewal.id)
        expect("ORDER_NOT_CANCELLABLE", 409) { ph.payments.cancel(ph.order(renewal.id), pool) }
    }

    @Test
    fun `a success that arrives after a cancel is a late payment, a review with nothing delivered, the reservation stays released`(): Unit = runBlocking {
        val product = fx.product(price = 1000, stock = 2)

        fx.paymentMethod("fake")

        val order = orderOf(buy(product))
        val attempt = ph.attempts(order.id).single()

        ph.payments.cancel(order, pool)
        ph.succeed(order.id, attempt)

        val late = ph.order(order.id)

        assertEquals(OrderStatus.REVIEW, late.status)
        assertEquals("LATE", late.reviewReason)
        assertEquals(ReservationState.RELEASED, late.reservationState)
        assertEquals(attempt.id, late.paymentId)
        assertEquals(attempt.amount, late.paidAmount)
        assertEquals(2, w.products.getById(product.id, pool)!!.stock, "the stock stays back")
        assertTrue(ph.effects.of(order.id).isEmpty())
        assertTrue(ph.alerts.any { it.first == order.id && it.second == "LATE" })
    }

    // ================================================================================================ status (43)

    @Test
    fun `the status poll of the owner asks the provider at most once every 10 seconds, a stranger never`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        fake.caps = PaymentCapabilities().also { it.statusQuery = true }

        val order = orderOf(buy(fx.product(price = 1000)))
        val queries = { fake.calls(FakePaymentProvider.Op.QUERY).size }

        val first = ph.payments.status(order, owner = true, pool)

        assertEquals("PENDING", first.getString("status"))
        assertEquals("PENDING", first.getString("paymentStatus"))
        assertEquals(1, queries())

        w.clock.advance(1_000)
        ph.payments.status(order, owner = true, pool)

        assertEquals(1, queries(), "a second poll one second later is only the state")

        ph.payments.status(order, owner = false, pool)

        assertEquals(1, queries(), "a non-owner never triggers a query")

        w.clock.advance(10_000)
        ph.payments.status(order, owner = true, pool)

        assertEquals(2, queries())
        assertEquals(2, ph.attempts(order.id).single().queryCount)
        assertEquals(OrderStatus.PENDING, ph.order(order.id).status, "unknown() changes nothing")
    }

    @Test
    fun `a status query that finds the payment completes the order, and a provider without statusQuery is never asked`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        fake.caps = PaymentCapabilities().also { it.statusQuery = true }

        val order = orderOf(buy(fx.product(price = 1000)))
        val attempt = ph.attempts(order.id).single()

        fake.onQuery = { PaymentQueryResult.of(PaymentEvent.Succeeded(PaymentTarget.Attempt(attempt.id), Money(attempt.amount, attempt.currency))) }

        val body = ph.payments.status(order, owner = true, pool)

        assertEquals("COMPLETED", body.getString("status"))
        assertEquals("SUCCEEDED", body.getString("paymentStatus"))

        val quiet = orderOf(buy(fx.product(price = 1000)))

        fake.caps = PaymentCapabilities()
        ph.payments.status(quiet, owner = true, pool)

        assertEquals(1, fake.calls(FakePaymentProvider.Op.QUERY).size)
    }

    // ======================================================================================== the order view

    @Test
    fun `the owner view carries the retry data while the order can be paid again and not while a payment is being verified`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        fx.paymentMethod("fake-two")
        ph.lookup.add(FakePaymentProvider("fake-two"))

        val order = orderOf(buy(fx.product(price = 1000)))
        val view = ph.payments.viewFor(order, OrderRole.OWNER, PayCaller(), pool)

        assertTrue(view.getBoolean("canRetryPayment"))
        assertTrue(view.getBoolean("canCancel"))
        assertEquals(setOf("fake", "fake-two"), view.getJsonArray("paymentMethods").map { (it as JsonObject).getString("id") }.toSet())
        assertTrue(view.getJsonArray("paymentMethods").all { (it as JsonObject).getBoolean("available") })
        assertEquals("REDIRECT", view.getJsonObject("payment").getJsonObject("start").getString("kind"))

        ph.payments.applyEvent(order.id, ph.attempts(order.id).single().id, PaymentAttemptEvent.Pending)

        val verifying = ph.payments.viewFor(ph.order(order.id), OrderRole.OWNER, PayCaller(), pool)

        assertFalse(verifying.getBoolean("canRetryPayment"))
        assertFalse(verifying.getBoolean("canCancel"))
        assertFalse(verifying.containsKey("paymentMethods"))
    }

    @Test
    fun `a test-mode method is listed unavailable for the public in the retry data`(): Unit = runBlocking {
        h.config = h.config.copy(testMode = true)
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000), caller = QuoteCaller.GUEST.copy(test = true)))
        val option = { caller: PayCaller -> runBlocking { ph.payments.viewFor(order, OrderRole.OWNER, caller, pool) }.getJsonArray("paymentMethods").getJsonObject(0) }

        assertFalse(option(PayCaller()).getBoolean("available"))
        assertEquals("TEST_MODE", option(PayCaller()).getString("unavailableReason"))
        assertTrue(option(PayCaller(canUseTestMode = true)).getBoolean("available"))
    }

    // ===================================================================================== replay of a checkout

    @Test
    fun `a replayed checkout gets the stored start of the first request and starts nothing twice`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val product = fx.product(price = 1000)
        val body = h.body("items" to listOf(h.line(product)), "paymentMethodId" to "fake")
        val key = h.nextKey()
        val first = h.checkout(body, key)
        val second = h.checkout(body, key)

        assertEquals(first.payment, second.payment)
        assertEquals(first.order.getString("publicId"), second.order.getString("publicId"))
        assertEquals(1, fake.calls(FakePaymentProvider.Op.START).size)
    }

    // ================================================================================================ OrderAccess

    @Test
    fun `OrderAccess resolves the owner by session or token, the recipient and a stranger, and answers 404 without a query for a bad id`(): Unit = runBlocking {
        h.config = h.config.copy(allowGiftPurchase = true)
        fx.paymentMethod("fake")

        val (alex, alexCaller) = user("Alex")
        val (bob, _) = user("Bob")
        val (eve, _) = user("Eve")
        val order = orderOf(h.checkout(h.body("items" to listOf(h.line(fx.product(price = 1000))), "paymentMethodId" to "fake", "recipientUsername" to "Bob"), caller = alexCaller))
        val access = OrderAccess(w.orders, w.clock)
        val resolve = { session: Long?, header: String?, query: String?, get: Boolean ->
            runBlocking { access.resolve(order.publicId, session, header, query, get, "203.0.113.7", pool).role }
        }

        assertEquals(OrderRole.OWNER, resolve(alex.id, null, null, true))
        assertEquals(OrderRole.RECIPIENT, resolve(bob.id, null, null, true))
        assertEquals(OrderRole.LIMITED, resolve(eve.id, null, null, true))
        assertEquals(OrderRole.LIMITED, resolve(null, null, null, true))
        assertEquals(OrderRole.OWNER, resolve(null, order.accessToken, null, false), "the header, on any method")
        assertEquals(OrderRole.OWNER, resolve(null, null, order.accessToken, true), "the query on a GET")
        assertEquals(OrderRole.LIMITED, resolve(null, null, order.accessToken, false), "never the query on a mutation")
        assertEquals(OrderRole.OWNER, resolve(bob.id, order.accessToken, null, true), "the recipient who also holds the token")

        val owner = runBlocking { access.resolve(order.publicId, alex.id, null, null, true, null, pool) }

        assertEquals(order.id, owner.order.id)
        assertEquals(order.id, owner.requireOwner().id)
        expect("NOT_FOUND", 404) { access.resolve(order.publicId, bob.id, null, null, false, null, pool).requireOwner() }
        expect("NOT_FOUND", 404) { access.resolve(order.publicId, null, null, null, false, null, pool).requireOwner() }

        expect("NOT_FOUND", 404) { access.resolve("not-an-id", alex.id, null, null, true, null, pool) }
        expect("NOT_FOUND", 404) { access.resolve("0".repeat(20), alex.id, null, null, true, null, pool) }
        expect("NOT_FOUND", 404) { access.resolve(null, alex.id, null, null, true, null, pool) }
    }

    @Test
    fun `a token expires after 90 days and a wrong token costs one token of the limiter L6 per address`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000)))
        val access = OrderAccess(w.orders, w.clock)

        assertEquals(OrderRole.OWNER, access.resolve(order.publicId, null, order.accessToken, null, true, "203.0.113.7", pool).role)

        w.clock.advance(90 * 86_400_000L)

        assertEquals(OrderRole.LIMITED, access.resolve(order.publicId, null, order.accessToken, null, true, "203.0.113.8", pool).role, "the 90 days are over")

        // 30 wrong tokens from one address pass, the 31st is a 429
        w.clock.set(FakeClock.START_MS)

        repeat(OrderAccess.L6_BURST) { assertEquals(OrderRole.LIMITED, access.resolve(order.publicId, null, "wrong-$it", null, true, "198.51.100.1", pool).role) }

        val limited = expect("TOO_MANY_REQUESTS", 429) { access.resolve(order.publicId, null, "wrong-last", null, true, "198.51.100.1", pool) }

        assertTrue(limited.getLong("retryAfter") >= 1)

        // another address has its own bucket, and a request without a token costs nothing
        assertEquals(OrderRole.LIMITED, access.resolve(order.publicId, null, "wrong", null, true, "198.51.100.2", pool).role)
        assertEquals(OrderRole.LIMITED, access.resolve(order.publicId, null, null, null, true, "198.51.100.1", pool).role)
        assertEquals(OrderRole.OWNER, access.resolve(order.publicId, null, order.accessToken, null, true, "198.51.100.1", pool).role, "the right token of an exhausted address is not charged")
    }

    // ================================================================================== review fixes (MK-076)

    // ---- /pay answers a provider failure as 502 (06 section 9.2 steps 4 and 5)

    @Test
    fun `a provider error in pay answers 502 with the order and the token, fails the new attempt and keeps the order PENDING`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        fake.caps = PaymentCapabilities().also { it.cancelPending = true }

        val order = orderOf(buy(fx.product(price = 1000)))

        fake.failNext(FakePaymentProvider.Op.START, ProviderException(ProviderErrorCode.GATEWAY_REJECTED, "rejected", adminMessage = "merchant 7 is blocked"))

        val body = expect("PAYMENT_PROVIDER_ERROR", 502) { pay(order) }
        val attempts = ph.attempts(order.id)

        assertEquals("GATEWAY_REJECTED", body.getString("code"))
        assertEquals(order.publicId, body.getJsonObject("order").getString("publicId"))
        assertEquals(order.accessToken, body.getString("orderToken"))
        assertFalse(body.encode().contains("merchant 7"), "the gateway's text is not in the response")
        assertEquals(listOf(PaymentStatus.CANCELLED, PaymentStatus.FAILED), attempts.map { it.status }, "the old attempt was cancelled by the pay, the new one failed")
        assertEquals("GATEWAY_REJECTED", attempts.last().failureCode)
        assertEquals(PaymentService.START_FAILED_TEXT, attempts.last().failureMessage)
        assertEquals("merchant 7 is blocked", attempts.last().adminMessage)
        assertNotNull(attempts.last().closedAt)
        assertEquals(OrderStatus.PENDING, ph.order(order.id).status)
        assertEquals(ReservationState.HELD, ph.order(order.id).reservationState)
        assertEquals("GATEWAY_REJECTED", w.paymentMethods.getByMethodId("fake", pool)!!.lastError)
        assertTrue(w.orderEvents.getByOrderId(order.id, pool).any { it.type == OrderEventType.PAYMENT_FAILED })

        // the buyer picks the method again and gets through
        val start = pay(ph.order(order.id))

        assertEquals("REDIRECT", start!!.getString("kind"))
        assertEquals(PaymentStatus.PENDING, ph.attempts(order.id).last().status)
    }

    @Test
    fun `an unexpected exception in pay is an INTERNAL 502 that never leaks its text`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000)))

        fake.onStart = { throw IllegalStateException("boom with secret-token-9") }

        val internal = expect("PAYMENT_PROVIDER_ERROR", 502) { pay(order) }

        assertEquals("INTERNAL", internal.getString("code"))
        assertFalse(internal.encode().contains("secret-token-9"))
        assertEquals("INTERNAL", ph.attempts(order.id).last().failureCode)
        assertEquals(order.publicId, internal.getJsonObject("order").getString("publicId"))
        assertEquals(OrderStatus.PENDING, ph.order(order.id).status)
    }

    @Test
    fun `a provider that misses the deadline in pay leaves the new attempt CREATED with TIMEOUT and answers GATEWAY_UNREACHABLE`(): Unit = runBlocking {
        ph.rebuild(startTimeoutMs = 300)
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000)))

        fake.delay(FakePaymentProvider.Op.START, 5_000)

        val body = expect("PAYMENT_PROVIDER_ERROR", 502) { pay(order) }
        val attempts = ph.attempts(order.id)

        assertEquals("GATEWAY_UNREACHABLE", body.getString("code"))
        assertEquals(order.publicId, body.getJsonObject("order").getString("publicId"))
        assertEquals(order.accessToken, body.getString("orderToken"))
        assertEquals(listOf(PaymentStatus.CANCELLED, PaymentStatus.CREATED), attempts.map { it.status }, "the outcome is unknown: the new attempt stays CREATED")
        assertEquals("TIMEOUT", attempts.last().failureCode)
        assertEquals(OrderStatus.PENDING, ph.order(order.id).status)

        // a late success of the new attempt still completes the order
        ph.succeed(order.id, attempts.last())

        assertEquals(OrderStatus.COMPLETED, ph.order(order.id).status)
    }

    @Test
    fun `a long gateway text is clipped to the column instead of failing the failure`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        fake.failNext(FakePaymentProvider.Op.START, ProviderException(ProviderErrorCode.GATEWAY_REJECTED, "rejected", adminMessage = "a".repeat(900)))

        expect("PAYMENT_PROVIDER_ERROR", 502) { buy(fx.product(price = 1000)) }

        val attempt = ph.attempts(onlyOrder().id).single()

        assertEquals(PaymentStatus.FAILED, attempt.status)
        assertEquals(PaymentService.ADMIN_MESSAGE_MAX, attempt.adminMessage!!.length)
    }

    // ---- a Completed start result is money already collected: never dropped (06 section 9.2 step 3)

    @Test
    fun `a Completed start result of an attempt that a concurrent pay cancelled completes the order, the newer attempt is cancelled`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val product = fx.product(price = 1000, stock = 3)
        val calls = AtomicInteger()

        fake.onStart = { req ->
            if (calls.incrementAndGet() == 1) {
                // while this charge runs, the buyer presses pay again: the attempt of this call is cancelled, a second one starts
                runBlocking { pay(ph.order(req.order.id)) }

                result(req)
            } else {
                StartPaymentResult.Redirect("https://gateway.invalid/second")
            }
        }

        val response = buy(product)
        val order = orderOf(response)
        val attempts = ph.attempts(order.id)

        assertEquals(2, attempts.size)
        assertEquals(PaymentStatus.SUCCEEDED, attempts[0].status, "the money was collected for the first attempt")
        assertEquals(attempts[0].amount, attempts[0].paidAmount)
        assertEquals(PaymentStatus.CANCELLED, attempts[1].status, "O2 cancels the newer open attempt")
        assertEquals(OrderStatus.COMPLETED, order.status)
        assertEquals(attempts[0].id, order.paymentId)
        assertEquals(ReservationState.COMMITTED, order.reservationState)
        assertEquals("COMPLETED", response.payment!!.getString("kind"))
        assertEquals(1, ph.effects.of(order.id).count { it == "IssueInvoice" })
        assertEquals(1, w.products.getById(product.id, pool)!!.soldCount)
    }

    @Test
    fun `a Completed start result whose attempt lost the tender to a pay is a review, never lost`(): Unit = runBlocking {
        h.config = h.config.copy(allowMixedCreditPayment = true)

        val (alex, caller) = user("Alex", credit = 8_000)

        fx.paymentMethod("fake")

        val calls = AtomicInteger()

        fake.onStart = { req ->
            if (calls.incrementAndGet() == 1) {
                // the buyer drops the credits while the 20.00 charge of the first attempt is running
                runBlocking { pay(ph.order(req.order.id), credits = 0) }

                result(req)
            } else {
                StartPaymentResult.Redirect("https://gateway.invalid/second")
            }
        }

        buy(fx.product(price = 10_000), "fake", 1, caller, "useCredits" to 80)

        val order = onlyOrder()
        val attempts = ph.attempts(order.id)

        assertEquals(2_000, attempts[0].amount)
        assertEquals(PaymentStatus.SUCCEEDED, attempts[0].status)
        assertEquals(2_000, attempts[0].paidAmount, "what the gateway took is on record")
        assertEquals(OrderStatus.REVIEW, order.status)
        assertEquals("AMOUNT_MISMATCH", order.reviewReason)
        assertEquals(attempts[0].id, order.paymentId)
        assertEquals(2_000, order.paidAmount)
        assertEquals(PaymentStatus.CANCELLED, attempts[1].status)
        assertTrue(ph.ledger.captures.isEmpty(), "nothing is captured")
        assertTrue(ph.effects.of(order.id).isEmpty(), "nothing is delivered")
        assertEquals(ReservationState.HELD, order.reservationState)
        assertEquals(8_000, fx.creditBalance(alex), "the credits the buyer released stay released")
    }

    @Test
    fun `a Completed start result of an attempt that became PROCESSING completes it`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        fake.onStart = { req ->
            runBlocking { ph.payments.applyEvent(req.order.id, req.attempt.id, PaymentAttemptEvent.Pending) }

            result(req)
        }

        val response = buy(fx.product(price = 1000))
        val order = orderOf(response)
        val attempt = ph.attempts(order.id).single()

        assertEquals(PaymentStatus.SUCCEEDED, attempt.status)
        assertEquals(attempt.amount, attempt.paidAmount)
        assertEquals(OrderStatus.COMPLETED, order.status)
        assertEquals("COMPLETED", response.payment!!.getString("kind"))
        assertNotNull(attempt.closedAt)
    }

    @Test
    fun `a Completed step of continue on an attempt that became PROCESSING completes the order`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        fake.onStart = { StartPaymentResult.Embedded(JsonObject().put("step", 1)) }

        val order = orderOf(buy(fx.product(price = 1000)))

        ph.continuable.onContinue = { req ->
            runBlocking { ph.payments.applyEvent(order.id, req.attempt.id, PaymentAttemptEvent.Pending) }

            StartPaymentResult.Completed(PaymentEvent.Succeeded(PaymentTarget.Attempt(req.attempt.id), Money(req.attempt.amount.amount, req.attempt.amount.currency)))
        }

        val done = ph.payments.continuePayment(order, JsonObject(), PayCaller(), pool)

        assertEquals("COMPLETED", done!!.getString("kind"))
        assertEquals(PaymentStatus.SUCCEEDED, ph.attempts(order.id).single().status)
        assertEquals(OrderStatus.COMPLETED, ph.order(order.id).status)
    }

    @Test
    fun `a Completed step of continue for an attempt that a pay cancelled meanwhile is applied as a late success`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        fake.onStart = { StartPaymentResult.Embedded(JsonObject().put("step", 1)) }

        val order = orderOf(buy(fx.product(price = 1000)))
        val first = ph.attempts(order.id).single()

        ph.continuable.onContinue = { req ->
            runBlocking { pay(ph.order(order.id)) }

            StartPaymentResult.Completed(PaymentEvent.Succeeded(PaymentTarget.Attempt(req.attempt.id), Money(req.attempt.amount.amount, req.attempt.amount.currency)))
        }

        ph.payments.continuePayment(order, JsonObject(), PayCaller(), pool)

        assertEquals(PaymentStatus.SUCCEEDED, ph.attempts(order.id).first { it.id == first.id }.status)
        assertEquals(OrderStatus.COMPLETED, ph.order(order.id).status, "same tender: O2")
        assertEquals(PaymentStatus.CANCELLED, ph.attempts(order.id).last().status)
    }

    @Test
    fun `the Completed result of a built-in for an attempt that is no longer current is dropped, no real money moved`(): Unit = runBlocking {
        val (alex, caller) = user("Alex", credit = 5_000)
        val product = fx.product(price = 3000, creditPrice = 2500)

        h.useStarter(PaymentStarter.NONE)

        val order = orderOf(h.checkout(h.body("items" to listOf(h.line(product)), "paymentMethodId" to "credits", "payWithCredits" to true), caller = caller))
        val attempt = ph.attempts(order.id).single()

        // the attempt was superseded before the built-in's start ran
        sql("UPDATE `pano_market_payment` SET `status` = 'CANCELLED', `closedAt` = ? WHERE `id` = ?", w.clock.now(), attempt.id)

        assertNull(ph.payments.startAttempt(order.id, attempt.id, emptyList(), pool), "nothing to show: the attempt is not the current one")
        assertEquals(OrderStatus.PENDING, ph.order(order.id).status)
        assertTrue(ph.ledger.captures.isEmpty())
        assertEquals(PaymentStatus.CANCELLED, ph.attempts(order.id).single().status)
        assertEquals(2_500, fx.creditBalance(alex), "the hold is untouched")
    }

    // ---- continue serialises on the attempt (06 section 9.3)

    @Test
    fun `two concurrent continue calls reach the provider once, the other one answers 409`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        fake.onStart = { StartPaymentResult.Embedded(JsonObject().put("step", 1)) }

        val order = orderOf(buy(fx.product(price = 1000)))

        ph.continuable.onContinue = {
            Thread.sleep(500)

            StartPaymentResult.Redirect("https://gateway.invalid/step2")
        }

        val results = Race.run(2) { ph.payments.continuePayment(order, JsonObject(), PayCaller(), pool) }

        assertEquals(1, ph.continuable.continued.size, "the second call never reaches the provider")
        assertEquals(1, results.count { it.isSuccess })

        val refused = results.single { it.isFailure }.exceptionOrNull() as Error

        assertEquals("ORDER_NOT_PAYABLE", refused.getErrorCode())
        assertEquals(409, refused.getStatusCode())
        assertEquals("REDIRECT", ph.attempts(order.id).single().startKind)
        assertEquals(0, ph.payments.attemptLocksInUse(), "no lock outlives its calls")
    }

    @Test
    fun `a second continue call is built from the step the first one stored, not from the row it read before waiting`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        fake.onStart = { StartPaymentResult.Embedded(JsonObject().put("step", 0)) }

        val order = orderOf(buy(fx.product(price = 1000)))
        val counter = AtomicInteger()

        ph.continuable.onContinue = {
            Thread.sleep(300)

            val n = counter.incrementAndGet()

            StartPaymentResult.Embedded(JsonObject().put("step", n)).also { e -> e.providerData = JsonObject().put("n", n) }
        }

        val results = Race.run(2) { ph.payments.continuePayment(order, JsonObject(), PayCaller(), pool) }

        assertTrue(results.all { it.isSuccess }, "the form is still EMBEDDED after the first step, so both go through, one after the other")

        val seen = ph.continuable.continued.map { it.attempt.providerData?.getInteger("n") }

        assertEquals(listOf(null, 1), seen, "the second request carries the provider data the first call stored")
        assertEquals(2, JsonObject(ph.cipher.decrypt(ph.attempts(order.id).single().providerData!!)!!).getInteger("n"))
        assertEquals(0, ph.payments.attemptLocksInUse())
    }

    // ---- NeedsReview keeps the money the gateway reports (06 section 9.4)

    private suspend fun needsReview(orderId: Long, attempt: MarketPayment, reason: ReviewReason, received: Long? = null, currency: String = attempt.currency): AppliedEvent {
        val event = PaymentEvent.NeedsReview(PaymentTarget.Attempt(attempt.id), reason).also { e -> if (received != null) e.received = Money(received, currency) }

        return ph.payments.applyEvent(orderId, attempt.id, PaymentEventMapper.attemptEvent(event)!!, AttemptFacts.of(event, ph.cipher))
    }

    @Test
    fun `a NeedsReview that reports what was received records it on the attempt and the order, nothing is captured or delivered`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000, stock = 3)))
        val attempt = ph.attempts(order.id).single()
        val applied = needsReview(order.id, attempt, ReviewReason.UNDERPAID, received = 600)

        assertTrue(applied.changed)
        assertEquals(PaymentStatus.REVIEW, applied.attemptStatus)
        assertEquals(OrderStatus.REVIEW, applied.orderStatus)

        val reviewed = ph.attempts(order.id).single()
        val moved = ph.order(order.id)

        assertEquals(600, reviewed.paidAmount)
        assertEquals("EUR", reviewed.paidCurrency)
        assertNotNull(reviewed.paidAt)
        assertEquals(OrderStatus.REVIEW, moved.status)
        assertEquals("UNDERPAID", moved.reviewReason)
        assertEquals(attempt.id, moved.paymentId)
        assertEquals(600, moved.paidAmount, "a rejection with a refund returns what really arrived")
        assertEquals(ReservationState.HELD, moved.reservationState)
        assertNull(moved.expiresAt, "expiry is paused")
        assertTrue(ph.ledger.captures.isEmpty())
        assertTrue(ph.effects.of(order.id).isEmpty())
        assertTrue(ph.alerts.any { it.first == order.id && it.second == "UNDERPAID" })
        assertTrue(w.orderEvents.getByOrderId(order.id, pool).any { it.type == OrderEventType.REVIEW_OPENED })
    }

    @Test
    fun `a NeedsReview on a cancelled order is O9 and carries the received money`(): Unit = runBlocking {
        val product = fx.product(price = 1000, stock = 3)

        fx.paymentMethod("fake")

        val order = orderOf(buy(product))
        val attempt = ph.attempts(order.id).single()

        ph.payments.cancel(order, pool)

        needsReview(order.id, attempt, ReviewReason.UNDERPAID, received = 600)

        val late = ph.order(order.id)

        assertEquals(OrderStatus.REVIEW, late.status)
        assertEquals("UNDERPAID", late.reviewReason)
        assertEquals(attempt.id, late.paymentId)
        assertEquals(600, late.paidAmount)
        assertEquals(ReservationState.RELEASED, late.reservationState, "the stock stays back")
        assertEquals(3, w.products.getById(product.id, pool)!!.stock)
        assertEquals(600, ph.attempts(order.id).single().paidAmount)
        assertTrue(ph.effects.of(order.id).isEmpty())
        assertTrue(ph.alerts.any { it.first == order.id && it.second == "UNDERPAID" })
    }

    @Test
    fun `a NeedsReview without an amount leaves the paid amount unset`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000)))
        val attempt = ph.attempts(order.id).single()

        needsReview(order.id, attempt, ReviewReason.WRONG_ASSET)

        assertNull(ph.attempts(order.id).single().paidAmount)
        assertNull(ph.attempts(order.id).single().paidAt)
        assertEquals(OrderStatus.REVIEW, ph.order(order.id).status)
        assertEquals("WRONG_ASSET", ph.order(order.id).reviewReason)
        assertEquals(attempt.id, ph.order(order.id).paymentId)
        assertEquals(0, ph.order(order.id).paidAmount)
    }

    @Test
    fun `money reported for a second attempt of an order already in review is added to what the order holds`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000)))
        val first = ph.attempts(order.id).single()

        pay(order)

        val second = ph.attempts(order.id).last()

        needsReview(order.id, first, ReviewReason.UNDERPAID, received = 600)

        assertEquals(PaymentStatus.CANCELLED, ph.attempts(order.id).last().status, "the review cancels the newer open attempt")
        assertEquals(600, ph.order(order.id).paidAmount)

        val applied = needsReview(order.id, second, ReviewReason.UNDERPAID, received = 400)

        assertTrue(applied.changed)
        assertEquals(PaymentStatus.REVIEW, applied.attemptStatus)
        assertEquals(1000, ph.order(order.id).paidAmount, "all of it is refunded by a rejection")
        assertEquals(first.id, ph.order(order.id).paymentId, "the order keeps the first payment")
        assertEquals(400, ph.attempts(order.id).last().paidAmount)
        assertEquals(2, ph.alerts.count { it.first == order.id }, "the panel is told about the second payment too")
    }

    @Test
    fun `a gateway Failed keeps its code, the buyer gets the generic text and the gateway's text is the admin's`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000)))
        val attempt = ph.attempts(order.id).single()
        val event = PaymentEvent.Failed(PaymentTarget.Attempt(attempt.id), "card_declined", "Declined by issuer 4242").also { it.note = "webhook 1" }

        ph.payments.applyEvent(order.id, attempt.id, PaymentEventMapper.attemptEvent(event)!!, AttemptFacts.of(event, ph.cipher))

        val failed = ph.attempts(order.id).single()

        assertEquals(PaymentStatus.FAILED, failed.status)
        assertEquals("card_declined", failed.failureCode)
        assertEquals(PaymentService.PAYMENT_FAILED_TEXT, failed.failureMessage)
        assertEquals("webhook 1; Declined by issuer 4242", failed.adminMessage)
        assertFalse(JsonObject(ph.payments.viewFor(ph.order(order.id), OrderRole.OWNER, PayCaller(), pool).encode()).encode().contains("4242"))
    }

    // ---- the rows of the table of 06 section 9.4 that move an attempt without paying it

    private fun statusEvents(orderId: Long, type: OrderEventType, attemptId: Long) =
        runBlocking { w.orderEvents.getByOrderId(orderId, pool) }.filter { it.type == type && it.data?.let { data -> JsonObject(data).getLong("paymentId") } == attemptId }

    @Test
    fun `Failed, Cancelled and Expired close a PENDING or a PROCESSING attempt, the order stays PENDING and can be paid again`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        class Case(val to: PaymentStatus, val timeline: OrderEventType, val event: PaymentAttemptEvent, val facts: (MarketPayment) -> AttemptFacts = { AttemptFacts.NONE })

        val cases = listOf(
            Case(PaymentStatus.FAILED, OrderEventType.PAYMENT_FAILED, PaymentAttemptEvent.Failed(false)) { a ->
                AttemptFacts.of(PaymentEvent.Failed(PaymentTarget.Attempt(a.id), "insufficient_funds", null), ph.cipher)
            },
            Case(PaymentStatus.CANCELLED, OrderEventType.PAYMENT_CANCELLED, PaymentAttemptEvent.Cancelled),
            Case(PaymentStatus.EXPIRED, OrderEventType.PAYMENT_FAILED, PaymentAttemptEvent.Expired)
        )

        for (processing in listOf(false, true)) {
            for (case in cases) {
                val label = "${case.to} from ${if (processing) "PROCESSING" else "PENDING"}"
                val product = fx.product(price = 1000, stock = 3)
                val order = orderOf(buy(product))
                val attempt = ph.attempts(order.id).single()

                if (processing) {
                    ph.payments.applyEvent(order.id, attempt.id, PaymentAttemptEvent.Pending)

                    assertEquals(PaymentStatus.PROCESSING, ph.attempts(order.id).single().status, label)
                }

                val applied = ph.payments.applyEvent(order.id, attempt.id, case.event, case.facts(attempt))
                val closed = ph.attempts(order.id).single()

                assertTrue(applied.changed, label)
                assertEquals(case.to, applied.attemptStatus, label)
                assertEquals(OrderStatus.PENDING, applied.orderStatus, label)
                assertEquals(case.to, closed.status, label)
                assertNotNull(closed.closedAt, label)
                assertNull(closed.startPayload, "$label: the stored start is gone")
                assertEquals(if (case.to == PaymentStatus.FAILED) "insufficient_funds" else null, closed.failureCode, label)

                val rows = statusEvents(order.id, case.timeline, attempt.id)

                assertEquals(1, rows.size, "$label: one timeline row")
                assertEquals(case.to.name, JsonObject(rows.single().data!!).getString("to"), label)
                assertEquals(OrderActorType.GATEWAY, rows.single().actorType, label)
                assertEquals(OrderStatus.PENDING, ph.order(order.id).status, label)
                assertEquals(ReservationState.HELD, ph.order(order.id).reservationState, "$label: the reservation stays")
                assertEquals(2, w.products.getById(product.id, pool)!!.stock, label)
                assertTrue(ph.effects.of(order.id).isEmpty(), label)

                // the same event again changes nothing (the attempt is closed)
                val again = ph.payments.applyEvent(order.id, attempt.id, case.event, case.facts(attempt))

                assertFalse(again.changed, "$label: a replay")
                assertEquals(1, statusEvents(order.id, case.timeline, attempt.id).size, "$label: no second row")

                // and the buyer may pay again
                val start = pay(ph.order(order.id))

                assertEquals("REDIRECT", start!!.getString("kind"), label)
                assertEquals(PaymentStatus.PENDING, ph.attempts(order.id).last().status, label)
                assertEquals(2, ph.attempts(order.id).size, label)
            }
        }
    }

    @Test
    fun `the lock set of an event is the one its worst effect needs`() {
        assertEquals(OrderLockScope.RELEASE, ph.payments.scopeFor(PaymentAttemptEvent.Failed(final = true)), "a final failure can end the order, O8")
        assertEquals(OrderLockScope.PAYMENT, ph.payments.scopeFor(PaymentAttemptEvent.Failed(final = false)))
        assertEquals(OrderLockScope.COMMIT, ph.payments.scopeFor(PaymentAttemptEvent.Succeeded(1, "EUR")))
        assertEquals(OrderLockScope.COMMIT, ph.payments.scopeFor(PaymentAttemptEvent.NeedsReview(ReviewReason.OTHER)))
        assertEquals(OrderLockScope.PAYMENT, ph.payments.scopeFor(PaymentAttemptEvent.Pending))
        assertEquals(OrderLockScope.PAYMENT, ph.payments.scopeFor(PaymentAttemptEvent.Cancelled))
        assertEquals(OrderLockScope.PAYMENT, ph.payments.scopeFor(PaymentAttemptEvent.Expired))
    }

    @Test
    fun `a final failure before the order window ends only fails the attempt`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val product = fx.product(price = 1000, stock = 3)
        val order = orderOf(buy(product))
        val attempt = ph.attempts(order.id).single()
        val applied = ph.payments.applyEvent(order.id, attempt.id, PaymentAttemptEvent.Failed(final = true))

        assertTrue(applied.changed)
        assertEquals(PaymentStatus.FAILED, applied.attemptStatus)
        assertEquals(OrderStatus.PENDING, applied.orderStatus, "the window is open: the buyer may retry")
        assertEquals(OrderStatus.PENDING, ph.order(order.id).status)
        assertEquals(ReservationState.HELD, ph.order(order.id).reservationState)
        assertEquals(2, w.products.getById(product.id, pool)!!.stock)
    }

    @Test
    fun `a final failure after the order window is O8, the reservation goes back without a gateway cancel, a later success is a late payment`(): Unit = runBlocking {
        val coupon = fx.coupon("FIN", DiscountUnit.PERCENT, 1000)
        val product = fx.product(price = 1000, stock = 4)

        fx.paymentMethod("fake")
        fake.caps = PaymentCapabilities().also { it.cancelPending = true }

        val order = orderOf(buy(product, "fake", 2, QuoteCaller.GUEST, "couponCode" to "FIN"))
        val attempt = ph.attempts(order.id).single()

        assertEquals(2, w.products.getById(product.id, pool)!!.stock)
        assertEquals(1, w.coupons.getById(coupon.id, pool)!!.usedCount)

        w.clock.advance(61 * 60_000L)

        val applied = ph.payments.applyEvent(order.id, attempt.id, PaymentAttemptEvent.Failed(final = true))
        val failed = ph.order(order.id)

        assertTrue(applied.changed)
        assertEquals(PaymentStatus.FAILED, applied.attemptStatus)
        assertEquals(OrderStatus.FAILED, applied.orderStatus)
        assertEquals(OrderStatus.FAILED, failed.status)
        assertEquals(ReservationState.RELEASED, failed.reservationState)
        assertEquals(4, w.products.getById(product.id, pool)!!.stock, "the stock is back")
        assertEquals(0, w.coupons.getById(coupon.id, pool)!!.usedCount, "the coupon use is back")
        assertEquals(RedemptionState.RELEASED, w.redemptions.getByOrderId(order.id, pool).single().state)
        assertEquals(PaymentStatus.FAILED, ph.attempts(order.id).single().status, "the gateway's own failure is left as it is")
        assertTrue(fake.calls(FakePaymentProvider.Op.CANCEL).isEmpty(), "the gateway reported the failure: no cancel is sent back")
        assertTrue(w.orderEvents.getByOrderId(order.id, pool).any { it.type == OrderEventType.STATUS_CHANGED && it.toStatus == "FAILED" && it.actorType == OrderActorType.GATEWAY })

        // a replay is a no-op
        assertFalse(ph.payments.applyEvent(order.id, attempt.id, PaymentAttemptEvent.Failed(final = true)).changed)
        assertEquals(4, w.products.getById(product.id, pool)!!.stock)

        // the gateway took the money after all
        ph.succeed(order.id, attempt)

        val late = ph.order(order.id)

        assertEquals(OrderStatus.REVIEW, late.status)
        assertEquals("LATE", late.reviewReason)
        assertEquals(ReservationState.RELEASED, late.reservationState)
        assertEquals(attempt.id, late.paymentId)
        assertTrue(ph.effects.of(order.id).isEmpty())
    }

    @Test
    fun `a final failure after the window releases the credit hold of a mixed order once`(): Unit = runBlocking {
        h.config = h.config.copy(allowMixedCreditPayment = true)

        val (alex, caller) = user("Alex", credit = 8_000)

        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 10_000), "fake", 1, caller, "useCredits" to 80))
        val attempt = ph.attempts(order.id).single()

        assertEquals(0, fx.creditBalance(alex))

        w.clock.advance(61 * 60_000L)
        ph.payments.applyEvent(order.id, attempt.id, PaymentAttemptEvent.Failed(final = true))

        assertEquals(OrderStatus.FAILED, ph.order(order.id).status)
        assertEquals(listOf(order.id), ph.ledger.releases, "released exactly once")
        assertEquals(8_000, fx.creditBalance(alex))
        assertEquals(0, w.creditAccounts.getBySystemKey(CreditSystemKey.HOLD, pool)!!.balance)

        ph.payments.applyEvent(order.id, attempt.id, PaymentAttemptEvent.Failed(final = true))

        assertEquals(listOf(order.id), ph.ledger.releases, "a replay does not release again")
        assertEquals(8_000, fx.creditBalance(alex))
    }

    @Test
    fun `any event on an attempt that already succeeded only merges the ids it lacks`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000)))
        val attempt = ph.attempts(order.id).single()

        ph.succeed(order.id, attempt)

        val paid = ph.attempts(order.id).single()
        val events = listOf(
            PaymentAttemptEvent.Failed(false), PaymentAttemptEvent.Failed(true), PaymentAttemptEvent.Cancelled, PaymentAttemptEvent.Expired, PaymentAttemptEvent.Pending,
            PaymentAttemptEvent.Replaced, PaymentAttemptEvent.NeedsReview(ReviewReason.UNDERPAID), PaymentAttemptEvent.Succeeded(paid.amount, paid.currency)
        )

        for (event in events) {
            val applied = ph.payments.applyEvent(order.id, attempt.id, event, AttemptFacts(gatewayTransactionId = "tx-1", gatewayRefs = mapOf("ref" to "r1")))

            assertFalse(applied.changed, "$event")
            assertEquals(PaymentStatus.SUCCEEDED, applied.attemptStatus, "$event")
            assertEquals(OrderStatus.COMPLETED, applied.orderStatus, "$event")
        }

        val after = ph.attempts(order.id).single()

        assertEquals(PaymentStatus.SUCCEEDED, after.status)
        assertEquals(paid.closedAt, after.closedAt)
        assertEquals(paid.paidAmount, after.paidAmount)
        assertEquals("tx-1", after.gatewayTransactionId, "the id the attempt lacked is merged")
        assertEquals("r1", JsonObject(after.gatewayRefs!!).getString("ref"))

        ph.payments.applyEvent(order.id, attempt.id, PaymentAttemptEvent.Failed(false), AttemptFacts(gatewayTransactionId = "tx-2", gatewayRefs = mapOf("ref" to "r2", "other" to "o")))

        val again = ph.attempts(order.id).single()

        assertEquals("tx-1", again.gatewayTransactionId, "an id that is there is never overwritten")
        assertEquals("r1", JsonObject(again.gatewayRefs!!).getString("ref"))
        assertEquals("o", JsonObject(again.gatewayRefs!!).getString("other"), "a ref it lacked is added")
        assertEquals(OrderStatus.COMPLETED, ph.order(order.id).status)
        assertEquals(0, statusEvents(order.id, OrderEventType.PAYMENT_FAILED, attempt.id).size)
        assertEquals(0, statusEvents(order.id, OrderEventType.PAYMENT_CANCELLED, attempt.id).size)
        assertEquals(10, ph.effects.of(order.id).size, "the effects of O2 ran once")
    }

    // ---- the recipient limit of a paid gift (06 section 6.4, test 63, review-log M-6)

    private suspend fun gift(product: MarketProduct, payer: QuoteCaller, to: String = "Bob"): MarketOrder =
        orderOf(h.checkout(h.body("items" to listOf(h.line(product)), "paymentMethodId" to "fake", "recipientUsername" to to), caller = payer))

    @Test
    fun `a pending gift does not use up the recipient's allowance, and when it is paid after he bought the product himself it waits for review`(): Unit = runBlocking {
        h.config = h.config.copy(allowGiftPurchase = true)
        fx.paymentMethod("fake")

        val product = fx.product(price = 1000, stock = 5, columns = mapOf("limitPerPlayer" to 1))
        val (_, alex) = user("Alex")
        val (bob, bobCaller) = user("Bob")
        val gift = gift(product, alex)

        assertTrue(gift.isGift)
        assertEquals("u:${bob.id}", gift.recipientKey)

        // the unpaid gift counts for nothing: Bob buys the product himself and pays it
        val own = orderOf(buy(product, "fake", 1, bobCaller))

        ph.succeed(own.id, ph.attempts(own.id).single())

        assertEquals(OrderStatus.COMPLETED, ph.order(own.id).status)

        // now the gift is paid: it would be Bob's second unit
        val applied = ph.succeed(gift.id, ph.attempts(gift.id).single())
        val held = ph.order(gift.id)

        assertEquals(PaymentStatus.SUCCEEDED, applied.attemptStatus, "the money arrived and is recorded")
        assertEquals(OrderStatus.REVIEW, applied.orderStatus)
        assertEquals(OrderStatus.REVIEW, held.status)
        assertEquals("OTHER", held.reviewReason)
        assertEquals(ReservationState.HELD, held.reservationState, "nothing is committed: the stock stays on hold")
        assertEquals(3, w.products.getById(product.id, pool)!!.stock, "both orders keep their unit on hold")
        assertEquals(1, w.products.getById(product.id, pool)!!.soldCount, "only Bob's own order is sold")
        assertEquals(ph.attempts(gift.id).single().id, held.paymentId)
        assertEquals(1000, held.paidAmount, "the payer's money is on the order, a rejection refunds it")
        assertTrue(ph.effects.of(gift.id).isEmpty(), "nothing is delivered")
        assertTrue(ph.ledger.captures.isEmpty())
        assertNull(held.expiresAt, "expiry is paused while a human decides")
        assertTrue(ph.alerts.any { it.first == gift.id && it.second == "OTHER" })
        assertTrue(w.orderEvents.getByOrderId(gift.id, pool).any { it.type == OrderEventType.STATUS_CHANGED && it.toStatus == "REVIEW" && it.message == "recipient limit" })
        assertEquals(0, ph.effects.of(gift.id).size)
    }

    @Test
    fun `a paid gift that stays inside the recipient's limit completes like any order`(): Unit = runBlocking {
        h.config = h.config.copy(allowGiftPurchase = true)
        fx.paymentMethod("fake")

        val product = fx.product(price = 1000, stock = 5, columns = mapOf("limitPerPlayer" to 1, "cooldownSeconds" to 3600))
        val (_, alex) = user("Alex")

        user("Bob")

        val gift = gift(product, alex)

        ph.succeed(gift.id, ph.attempts(gift.id).single())

        assertEquals(OrderStatus.COMPLETED, ph.order(gift.id).status)
        assertEquals(ReservationState.COMMITTED, ph.order(gift.id).reservationState)
        assertEquals(10, ph.effects.of(gift.id).size)
    }

    @Test
    fun `a paid gift inside the recipient's cooldown waits for review, one after it completes`(): Unit = runBlocking {
        h.config = h.config.copy(allowGiftPurchase = true)
        fx.paymentMethod("fake")

        val product = fx.product(price = 1000, stock = 9, columns = mapOf("cooldownSeconds" to 3600))
        val (_, alex) = user("Alex")
        val (_, bobCaller) = user("Bob")
        val early = gift(product, alex)
        val late = gift(product, alex)

        // neither pending gift started a cooldown: Bob buys now and his pending order starts it
        val own = orderOf(buy(product, "fake", 1, bobCaller))

        ph.succeed(early.id, ph.attempts(early.id).single())

        assertEquals(OrderStatus.REVIEW, ph.order(early.id).status, "inside the cooldown of Bob's own order")
        assertEquals("OTHER", ph.order(early.id).reviewReason)
        assertEquals(ReservationState.HELD, ph.order(early.id).reservationState)

        w.clock.advance(3_601_000L)
        ph.succeed(late.id, ph.attempts(late.id).single())

        assertEquals(OrderStatus.COMPLETED, ph.order(late.id).status, "the cooldown of ${own.id} is over")
    }

    @Test
    fun `a TIMED product the recipient already owns is an extension, the gift is not held for a limit of one`(): Unit = runBlocking {
        h.config = h.config.copy(allowGiftPurchase = true)
        fx.paymentMethod("fake")

        val product = fx.product(price = 1000, stock = 9, columns = mapOf("billingMode" to "TIMED", "limitPerPlayer" to 1))
        val (_, alex) = user("Alex")
        val (bob, bobCaller) = user("Bob")
        val first = gift(product, alex)
        val second = gift(product, alex)
        val own = orderOf(buy(product, "fake", 1, bobCaller))

        ph.succeed(own.id, ph.attempts(own.id).single())

        // Bob holds one unit now, without an entitlement the gift would be his second one
        ph.succeed(first.id, ph.attempts(first.id).single())

        assertEquals(OrderStatus.REVIEW, ph.order(first.id).status)

        // with the entitlement the second gift extends it
        w.entitlements.add(
            MarketEntitlement(
                userId = bob.id, playerUsername = "Bob", ownerKey = "u:${bob.id}", productId = product.id, orderId = own.id,
                orderItemId = w.orderItems.getByOrderIds(listOf(own.id), pool).single().id, status = EntitlementStatus.ACTIVE, startsAt = w.clock.now() - 1_000
            ),
            pool
        )

        ph.succeed(second.id, ph.attempts(second.id).single())

        assertEquals(OrderStatus.COMPLETED, ph.order(second.id).status)
    }

    @Test
    fun `an order the buyer places for himself is not judged again at payment`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val product = fx.product(price = 1000, stock = 5, columns = mapOf("limitPerPlayer" to 1))
        val (_, bobCaller) = user("Bob")
        val own = orderOf(buy(product, "fake", 1, bobCaller))

        ph.succeed(own.id, ph.attempts(own.id).single())

        assertEquals(OrderStatus.COMPLETED, ph.order(own.id).status, "it was counted at checkout, its own units are not a second holding")
    }

    @Test
    fun `an injected guard diverts O2 under the COMMIT locks and leaves other orders alone`(): Unit = runBlocking {
        val scopes = CopyOnWriteArrayList<OrderLockScope>()
        val divert = java.util.concurrent.atomic.AtomicBoolean(true)

        ph.rebuild(
            extraGuards = listOf(
                PaidGuard { _, locked, _ ->
                    scopes += locked.scope

                    if (divert.get()) PaidDiversion(ReviewReason.FRAUD_REVIEW, "risk score 97") else null
                }
            )
        )
        fx.paymentMethod("fake")

        val held = orderOf(buy(fx.product(price = 1000, stock = 3)))

        ph.succeed(held.id, ph.attempts(held.id).single())

        assertEquals(OrderStatus.REVIEW, ph.order(held.id).status)
        assertEquals("FRAUD_REVIEW", ph.order(held.id).reviewReason)
        assertEquals(ReservationState.HELD, ph.order(held.id).reservationState)
        assertTrue(ph.effects.of(held.id).isEmpty())
        assertTrue(w.orderEvents.getByOrderId(held.id, pool).any { it.type == OrderEventType.STATUS_CHANGED && it.message == "risk score 97" })
        assertEquals(listOf(OrderLockScope.COMMIT), scopes.toList(), "the guard sees the locks of 06 section 13.2")

        divert.set(false)

        val free = orderOf(buy(fx.product(price = 1000, stock = 3)))

        ph.succeed(free.id, ph.attempts(free.id).single())

        assertEquals(OrderStatus.COMPLETED, ph.order(free.id).status)
        assertEquals(2, scopes.size)

        // an order that is not PENDING when its payment arrives never reaches a guard (a duplicate of a paid order, a late payment)
        ph.succeed(held.id, ph.attempts(held.id).single())

        assertEquals(2, scopes.size)
    }

    @Test
    fun `the recipient key set is the key and the guest twin of a registered player`() {
        fun order(key: String, name: String) = MarketOrder(recipientKey = key, recipientUsername = name)

        assertEquals(listOf("u:7", "g:steve"), RecipientLimitGuard.recipientKeys(order("u:7", "Steve")))
        assertEquals(listOf("g:steve"), RecipientLimitGuard.recipientKeys(order("g:steve", "Steve")))
        assertEquals(listOf("u:7"), RecipientLimitGuard.recipientKeys(order("u:7", "")))
    }

    // ===================================================================================== the pay request parser

    @Test
    fun `the pay request body is parsed strictly`() {
        val ok = parsePayRequest(JsonObject().put("paymentMethodId", "fake").put("useCredits", 12.5).put("billingInfo", JsonObject().put("city", "Izmir")))

        assertEquals("fake", ok.paymentMethodId)
        assertEquals(1250L, ok.useCredits)
        assertEquals("Izmir", ok.billingInfo!!.getString("city"))
        assertNull(parsePayRequest(JsonObject().put("paymentMethodId", "fake")).useCredits, "omitted keeps the credit part")
        assertEquals(0L, parsePayRequest(JsonObject().put("paymentMethodId", "fake").put("useCredits", 0)).useCredits, "0 drops it")

        val bad = listOf(
            JsonObject(), JsonObject().put("paymentMethodId", ""), JsonObject().put("paymentMethodId", 5), JsonObject().put("paymentMethodId", "x".repeat(65)),
            JsonObject().put("paymentMethodId", "fake").put("useCredits", "MAX"), JsonObject().put("paymentMethodId", "fake").put("useCredits", -1),
            JsonObject().put("paymentMethodId", "fake").put("useCredits", 1.234), JsonObject().put("paymentMethodId", "fake").put("useCredits", "5"),
            JsonObject().put("paymentMethodId", "fake").put("billingInfo", "x"), JsonObject().put("paymentMethodId", "fake").put("surprise", 1)
        )

        for (body in bad) assertThrows(RequestValueException::class.java, { parsePayRequest(body) }, body.encode())
    }
}
