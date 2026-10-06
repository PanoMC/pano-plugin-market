package com.panomc.plugins.market.service

import com.panomc.platform.error.NotLoggedIn
import com.panomc.platform.model.Error
import com.panomc.plugins.market.core.cart.CartLine
import com.panomc.plugins.market.core.delivery.ProductAction
import com.panomc.plugins.market.core.order.OrderActor
import com.panomc.plugins.market.core.order.OrderEvent
import com.panomc.plugins.market.core.subscription.RecurringPlan
import com.panomc.plugins.market.core.subscription.SubscriptionStateMachine
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.DeliveryActionType
import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.db.model.EntitlementStatus
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.MarketSubscription
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.db.model.PaymentFeeMode
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.db.model.RemoteCancelState
import com.panomc.plugins.market.db.model.RenewalStatus
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.db.model.SubscriptionMode
import com.panomc.plugins.market.db.model.SubscriptionStatus
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.error.InvalidOrderTransition
import com.panomc.plugins.market.error.PaymentMethodUnavailable
import com.panomc.plugins.market.provider.BankTransferProvider
import com.panomc.plugins.market.provider.CreditsProvider
import com.panomc.plugins.market.provider.FreeProvider
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.routes.api.payment.AttemptLookup
import com.panomc.plugins.market.routes.api.payment.EventNotHandled
import com.panomc.plugins.market.routes.api.payment.InboundEventContext
import com.panomc.plugins.market.routes.api.payment.PaymentEventSink
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.payment.CancelSubscriptionRequest
import com.panomc.plugins.market.spi.payment.CancelSubscriptionResult
import com.panomc.plugins.market.spi.payment.CheckoutSnapshot
import com.panomc.plugins.market.spi.payment.ContinuePaymentRequest
import com.panomc.plugins.market.spi.payment.Eligibility
import com.panomc.plugins.market.spi.payment.GatewaySubscriptionState
import com.panomc.plugins.market.spi.payment.GatewaySubscriptionStatus
import com.panomc.plugins.market.spi.payment.IntervalUnit
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.payment.PaymentContext
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentProvider
import com.panomc.plugins.market.spi.payment.PaymentTarget
import com.panomc.plugins.market.spi.payment.RecurringSupport
import com.panomc.plugins.market.spi.payment.ReviewReason
import com.panomc.plugins.market.spi.payment.StartPaymentRequest
import com.panomc.plugins.market.spi.payment.StartPaymentResult
import com.panomc.plugins.market.spi.payment.StoredPaymentMethod
import com.panomc.plugins.market.spi.testkit.TestContexts
import com.panomc.plugins.market.support.FakePaymentProvider
import com.panomc.plugins.market.support.StaticProviderLookup
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.support.WebhookHarness
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLException
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A scriptable provider for the subscription tests: [fake] plus a `continuePayment` and a scripted `checkEligibility` verdict ([onEligibility], every
 * snapshot it was asked about is in [eligibilityCalls]). `null` = the fake's own answer (eligible).
 */
internal class SubscriptionFake(val fake: FakePaymentProvider) : PaymentProvider by fake {
    @Volatile
    var onContinue: (ContinuePaymentRequest) -> StartPaymentResult = { StartPaymentResult.Redirect("https://gateway.invalid/step2/${it.attempt.reference}") }

    @Volatile
    var onEligibility: ((CheckoutSnapshot) -> Eligibility)? = null

    val eligibilityCalls = CopyOnWriteArrayList<CheckoutSnapshot>()

    override fun checkEligibility(ctx: PaymentContext, checkout: CheckoutSnapshot): Eligibility {
        eligibilityCalls += checkout

        return onEligibility?.invoke(checkout) ?: fake.checkEligibility(ctx, checkout)
    }

    override suspend fun continuePayment(ctx: PaymentContext, request: ContinuePaymentRequest): StartPaymentResult = onContinue(request)
}

/**
 * The object graph of a subscription test (17 section 5.3): the checkout harness of MK-075 with the real [PaymentService] as its payment starter, the real
 * [SubscriptionService] on every seam it fills in production (the pending row of O1, the effects of O2 / O4 / O5, the gateway data of a success, the plan of a
 * start, the late-renewal guard), the real delivery engine and entitlement service behind it, and the ledger stand-in of the credits slice.
 */
internal class SubscriptionWorld(val w: TestWiring, val vertx: Vertx) {
    class Hook(val event: String, val key: String, val orderId: Long?, val data: JsonObject, val testMode: Boolean)

    val h = CheckoutHarness(w, vertx)
    val fake: FakePaymentProvider get() = h.fake
    val cipher = SecretCipher(ByteArray(32) { (it + 9).toByte() })
    val dw = DeliveryWorld(w)
    val effects = RecordingEffects()
    val ledger = LedgerSettlement(w)
    val hooks = CopyOnWriteArrayList<Hook>()

    /** The real store webhook writer behind the recording hook: an endpoint that listens gets a delivery row, as in production. */
    val webhookRows = WebhookHarness(w, vertx)

    /** The provider of the checkout (`fake`, behind [first]) and a second one a buyer can switch to on a retry. */
    val first = SubscriptionFake(h.fake)
    val secondFake = FakePaymentProvider("second")
    val second = SubscriptionFake(secondFake)
    val lookup = StaticProviderLookup(listOf(first, second, FreeProvider(), CreditsProvider(), BankTransferProvider()))

    lateinit var db: MarketDb
        private set

    lateinit var locks: Locks
        private set

    lateinit var orderService: OrderService
        private set

    lateinit var payments: PaymentService
        private set

    lateinit var subs: SubscriptionService
        private set

    lateinit var review: OrderReviewService
        private set

    init {
        dw.roster.granted = listOf(1L)
        // a remote cancel succeeds unless a test scripts it otherwise (the fake's own default is "local only", which a gateway subscription does not accept)
        h.fake.onCancelSubscription = { CancelSubscriptionResult.Cancelled(null) }
        rebuild()
    }

    fun rebuild() {
        db = MarketDb({ w.pool }, w.clock)
        locks = Locks(w.orders, w.orderItems, w.redemptions, w.creditAccounts)

        val redemptions = RedemptionService(w.clock, locks, w.redemptions)

        subs = SubscriptionService(
            clock = w.clock, config = { h.config.toConfig() }, locks = locks, subscriptions = w.subscriptions, renewals = w.subscriptionRenewals, orders = w.orders,
            orderItems = w.orderItems, orderEvents = w.orderEvents, payments = w.payments, products = w.products, variants = w.variants, entitlements = w.entitlements,
            cipher = cipher, capabilities = { providerId, sql -> payments.capabilitiesOf(providerId, sql) }, deliveries = dw.service,
            mail = MailOutboxService({ h.config.toConfig() }, w.clock, w.mailOutbox, w.orderEvents),
            webhooks = SubscriptionWebhooks { conn, event, key, orderId, data, testMode ->
                hooks += Hook(event, key, orderId, data, testMode)

                webhookRows.service.emit(conn, event, key, orderId, data, testMode)
            }
        )
        orderService = OrderService(
            w.clock, w.ids, w.orders, w.orderItems, w.orderEvents, w.payments, redemptions, { _, _ -> false },
            subscriptions = subs, reservations = ReservationService(w.clock, locks, redemptions, w.orders), settlement = ledger,
            foreign = SubscriptionEffects({ subs }, DeliveryEffects(dw.entitlementService, dw.service, w.orders, effects)),
            rates = { sqlClient -> w.currencyRates.getAll(sqlClient).filter { it.rate.signum() > 0 }.associate { it.currency to it.rate } },
            statsCurrency = { "EUR" },
            limits = ProductPurchaseLimits(w.orders, w.products, w.entitlements, w.clock), refunds = w.refunds
        )
        payments = PaymentService(
            db = db, locks = locks, clock = w.clock, ids = w.ids, config = { h.config.toConfig() }, orders = w.orders, orderItems = w.orderItems, orderEvents = w.orderEvents,
            payments = w.payments, methods = w.paymentMethods, creditAccounts = w.creditAccounts, currencyRates = w.currencyRates, lookup = lookup, cipher = cipher,
            contexts = PaymentContexts { provider, settings, testMode -> TestContexts.payment(provider.id, settings, vertx, testMode) },
            orderService = orderService, site = { TestContexts.defaultSite() }, readClient = { w.pool }, products = w.products, entitlements = w.entitlements,
            extraPaidGuards = listOf(SubscriptionClosedGuard { subs }), subscriptionHooks = subs
        )
        review = OrderReviewService(db, locks, w.orders, w.payments, w.orderEvents, w.clock, orderService, { false }, { after -> payments.runAfterCommit(after, w.pool) })

        h.useStarter(payments)
        h.pendingSubscriptions = subs
        h.subscriptionsAvailable = true
    }

    fun caps(recurring: RecurringSupport, change: PaymentCapabilities.() -> Unit = {}) {
        fake.caps = PaymentCapabilities().also {
            it.recurring = recurring
            it.change()
        }
    }

    fun capsOfSecond(recurring: RecurringSupport) {
        secondFake.caps = PaymentCapabilities().also { it.recurring = recurring }
    }

    /** The `checkEligibility` verdict of the checkout's provider, on the checkout path and on the `/pay` path alike. */
    fun eligibility(verdict: ((CheckoutSnapshot) -> Eligibility)?) {
        h.eligibility = verdict
        first.onEligibility = verdict
        second.onEligibility = verdict
    }
}

/**
 * `SubscriptionService` on a real MariaDB (MK-121; 09 sections 3, 4 and 7, 02 section 8, 00 section 8.3; tests 19 to 28 and S-04 / S-05 of 09 section 16 / 17):
 * the rules of a subscription cart, the methods offered per recurring capability, the pending row at O1, the activation at O2 / O4 with the period, the mode
 * and the stored method, the gateway-managed activation and its status events, the closing of a pending row, the method change of a retry and the lock order
 * credit account -> subscription -> order. The invariants I1 to I22 are checked after every test by the base class.
 */
class SubscriptionServiceIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var sw: SubscriptionWorld
    private val vertx: Vertx = Vertx.vertx()

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    @BeforeEach
    fun freshState() {
        runBlocking { resetState() }
        w = TestWiring(pool)
        sw = SubscriptionWorld(w, vertx)
    }

    private val fx get() = w.fixtures
    private val h get() = sw.h
    private val fake get() = sw.fake

    // ------------------------------------------------------------------------------------------------------ helpers

    private var unique = 0

    private val actions = JsonArray(
        listOf(
            ProductAction("grant", DeliveryActionType.PERMISSION, nodes = listOf("group.vip")),
            ProductAction("renew", DeliveryActionType.PERMISSION, phase = DeliveryPhase.RENEW, nodes = listOf("group.vip")),
            // an explicit end action (a permission is undone automatically, but only after a grant that was sent, which no test here runs)
            ProductAction("expire", DeliveryActionType.COMMAND, phase = DeliveryPhase.EXPIRE, commands = listOf("say {username} subscription ended"))
        ).map { it.toJson() }
    ).encode()

    private suspend fun subProduct(price: Long = 600, maxCycles: Int? = null, withActions: Boolean = true): MarketProduct = fx.product(
        slug = "monthly-${++unique}", price = price, actions = if (withActions) actions else null,
        columns = mapOf("billingMode" to "SUBSCRIPTION", "periodUnit" to "MONTH", "periodCount" to 1, "subscriptionMaxCycles" to maxCycles)
    )

    private suspend fun user(name: String = "Alex"): Pair<TestUser, QuoteCaller> {
        val u = fx.user(name)

        h.emails[u.id] = "${name.lowercase()}@example.com"

        return u to QuoteCaller(u.id)
    }

    private suspend fun buy(product: MarketProduct, caller: QuoteCaller, method: String = "fake", quantity: Int = 1, variant: Long = 0): MarketOrder {
        val result = h.checkout(h.body("items" to listOf(h.line(product, quantity, variant)), "paymentMethodId" to method), caller = caller)

        return w.orders.getByPublicId(result.order.getString("publicId"), pool)!!
    }

    private suspend fun order(id: Long): MarketOrder = w.orders.getById(id, pool)!!

    private suspend fun subscriptionOf(order: MarketOrder): MarketSubscription = w.subscriptions.getById(order.subscriptionId!!, pool)!!

    private suspend fun attemptOf(order: MarketOrder): MarketPayment = w.payments.getByOrderId(order.id, pool).last()

    private suspend fun succeed(
        order: MarketOrder,
        subscription: GatewaySubscriptionState? = null,
        stored: StoredPaymentMethod? = null,
        amount: Long? = null,
        attempt: MarketPayment? = null,
        detail: String? = "Visa 4242"
    ): AppliedEvent {
        val a = attempt ?: attemptOf(order)
        val event = PaymentEvent.Succeeded(PaymentTarget.Attempt(a.id), Money(amount ?: a.amount, a.currency)).also {
            it.subscription = subscription
            it.storedMethod = stored
            it.methodDetail = detail
        }

        return sw.payments.applyEvent(order.id, a.id, PaymentEventMapper.attemptEvent(event)!!, AttemptFacts.of(event, sw.cipher))
    }

    private fun gateway(id: String, status: GatewaySubscriptionStatus = GatewaySubscriptionStatus.ACTIVE, periodStart: Long? = null, periodEnd: Long? = null) =
        GatewaySubscriptionState(id, status).also {
            it.gatewayCustomerId = "cus_$id"
            it.currentPeriodStart = periodStart
            it.currentPeriodEnd = periodEnd
            it.providerData = JsonObject().put("plan", "plan_1")
        }

    private suspend fun gatewayEvent(id: String, status: GatewaySubscriptionStatus, providerId: String = "fake"): SubscriptionService.GatewayOutcome =
        sw.db.tx { conn -> sw.subs.onGatewayEvent(conn, providerId, PaymentEvent.SubscriptionUpdated(GatewaySubscriptionState(id, status))) }

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

    private suspend fun count(table: String, where: String = "1 = 1"): Long = sql("SELECT COUNT(*) AS n FROM `pano_$table` WHERE $where").single().getLong("n")

    private suspend fun events(orderId: Long, type: OrderEventType) = w.orderEvents.getByOrderId(orderId, pool).filter { it.type == type }

    private suspend fun mails(kind: String): Long = count("market_mail_outbox", "`kind` = '$kind'")

    private fun oneMonthAfter(ms: Long): Long = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC).plusMonths(1).toInstant().toEpochMilli()

    private suspend fun quoteOptions(product: MarketProduct, caller: QuoteCaller) =
        h.service.quote(QuoteInput(items = listOf(CartLine(product.id, 0, 1, emptyMap(), null))), caller, pool).paymentMethods.single { it.id == "fake" }

    private suspend fun expire(order: MarketOrder) {
        w.clock.advance(3 * 3_600_000L)
        sw.db.txRestartingOnOrderChange { conn ->
            sw.locks.forOrder(conn, order.id, OrderLockScope.RELEASE) { locked -> sw.orderService.transition(conn, locked, OrderEvent.Expire(w.clock.now())) }
        }
    }

    private suspend fun transition(order: MarketOrder, event: OrderEvent, scope: OrderLockScope = OrderLockScope.RELEASE) {
        sw.db.txRestartingOnOrderChange { conn -> sw.locks.forOrder(conn, order.id, scope) { locked -> sw.orderService.transition(conn, locked, event) } }
    }

    // ==================================================================================== the rules of the cart (S-05)

    @Test
    fun `a subscription must be alone and needs a login, S-05`(): Unit = runBlocking {
        val sub = subProduct()
        val other = fx.product(price = 100, stock = 4)

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alice")

        expect("SUBSCRIPTION_MUST_BE_ALONE", 400) { h.checkout(h.body("items" to listOf(h.line(sub), h.line(other)), "paymentMethodId" to "fake"), caller = caller) }

        val guest = runCatching { h.checkout(h.body("items" to listOf(h.line(sub)), "paymentMethodId" to "fake")) }.exceptionOrNull()

        assertTrue(guest is NotLoggedIn, "a guest cannot buy a subscription: $guest")
        assertEquals(0, count("market_subscription"), "a refused checkout leaves no subscription row")
        assertEquals(0, count("market_order"))
        assertEquals(4, w.products.getById(other.id, pool)!!.stock, "the stock of the other line is untouched")

        // a gift is a line error of the quote: a subscription cannot be bought for another player
        fx.user("Bob")

        val gift = h.service.quote(QuoteInput(items = listOf(CartLine(sub.id, 0, 1, emptyMap(), null)), recipientUsername = "Bob"), caller, pool)

        assertEquals(listOf("GIFT_NOT_ALLOWED"), gift.lines.single().errors)
    }

    @Test
    fun `quantity is clamped to one, a second purchase is ALREADY_OWNED while the first runs and allowed again after it ended`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alice")
        val first = buy(sub, caller, quantity = 2)

        assertEquals(1, w.orderItems.getByOrderIds(listOf(first.id), pool).single().quantity, "quantity is clamped to 1 (QUANTITY_REDUCED)")

        // the pending row counts: the buyer already has this subscription in the making
        val body = expect("INVALID_CART", 400) { h.checkout(h.body("items" to listOf(h.line(sub)), "paymentMethodId" to "fake"), caller = caller) }

        val lineErrors = body.getJsonObject("lineErrors")

        assertEquals(listOf("ALREADY_OWNED"), lineErrors.getJsonArray(lineErrors.fieldNames().single()).map { it.toString() })

        // paid, then ended: allowed again
        succeed(first, subscription = gateway("sub_1"))
        sql("UPDATE `pano_market_subscription` SET `status` = 'CANCELLED', `endedAt` = ? WHERE `id` = ?", w.clock.now(), first.subscriptionId!!)

        val again = buy(sub, caller)

        assertNotEquals(first.subscriptionId, again.subscriptionId)
    }

    private suspend fun lineErrors(product: MarketProduct, caller: QuoteCaller): List<String> =
        h.service.quote(QuoteInput(items = listOf(CartLine(product.id, 0, 1, emptyMap(), null))), caller, pool).lines.single().errors

    private suspend fun alreadyOwned(product: MarketProduct, caller: QuoteCaller) {
        val body = expect("INVALID_CART", 400) { h.checkout(h.body("items" to listOf(h.line(product)), "paymentMethodId" to "fake"), caller = caller) }
        val errors = body.getJsonObject("lineErrors")

        assertEquals(listOf("ALREADY_OWNED"), errors.getJsonArray(errors.fieldNames().single()).map { it.toString() })
    }

    @Test
    fun `a pending row counts as owned only while its initial order is PENDING or in REVIEW, an abandoned, cancelled or failed one does not`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, alex) = user("Alex")
        val first = buy(sub, alex)

        // the order waits for the payment: the buyer owns the subscription in the making
        assertEquals(listOf("ALREADY_OWNED"), lineErrors(sub, alex))
        alreadyOwned(sub, alex)

        // abandoned: the order expired, its row stays PENDING (a late payment may arrive, 09 section 4.3), but the buyer may try again
        expire(first)
        assertEquals(SubscriptionStatus.PENDING, subscriptionOf(first).status)
        assertEquals(emptyList<String>(), lineErrors(sub, alex))

        val second = buy(sub, alex)

        assertNotEquals(first.id, second.id)
        assertNotEquals(first.subscriptionId, second.subscriptionId, "a new pending row for the new order")
        assertEquals(SubscriptionStatus.PENDING, subscriptionOf(second).status)
        assertEquals(SubscriptionStatus.PENDING, subscriptionOf(first).status, "the old row is left to step F")
        assertEquals(listOf("ALREADY_OWNED"), lineErrors(sub, alex))
        alreadyOwned(sub, alex)

        // cancelled by the buyer
        transition(second, OrderEvent.Cancel(OrderActor.BUYER))
        assertEquals(OrderStatus.CANCELLED, order(second.id).status)
        assertEquals(emptyList<String>(), lineErrors(sub, alex))

        val third = buy(sub, alex)

        // failed
        transition(third, OrderEvent.Fail(OrderActor.ADMIN))
        assertEquals(OrderStatus.FAILED, order(third.id).status)
        assertEquals(emptyList<String>(), lineErrors(sub, alex))

        val fourth = buy(sub, alex)

        assertEquals(4, count("market_subscription", "`userId` = ${order(fourth.id).userId}"))

        // paid: an active subscription is owned until it ends
        succeed(fourth, subscription = gateway("sub_a"))
        assertEquals(listOf("ALREADY_OWNED"), lineErrors(sub, alex))

        // in review: the money arrived late and a human has not decided yet
        val (_, bea) = user("Bea")
        val waiting = buy(sub, bea)

        expire(waiting)
        assertEquals(emptyList<String>(), lineErrors(sub, bea))
        assertEquals(OrderStatus.REVIEW, succeed(waiting, subscription = gateway("sub_b")).orderStatus)
        assertEquals(listOf("ALREADY_OWNED"), lineErrors(sub, bea), "the order waits for a human: the pending row counts")
        alreadyOwned(sub, bea)

        // the rejection closes the order and the row: bought again
        sw.review.review(waiting.id, ReviewDecision.REJECT, refund = false, force = false, note = null, adminUserId = 7)
        assertEquals(SubscriptionStatus.CANCELLED, subscriptionOf(waiting).status)
        assertEquals(emptyList<String>(), lineErrors(sub, bea))
        assertNotEquals(waiting.id, buy(sub, bea).id)
    }

    // ==================================================================================== methods per recurring capability

    @Test
    fun `methods are offered AUTO or MANUAL by the recurring capability, with the manual fallback on and off`(): Unit = runBlocking {
        val sub = subProduct(maxCycles = 12)

        fx.paymentMethod("fake")

        val (_, caller) = user("Alice")

        // a gateway without recurring billing: sold as a one-off payment with a reminder (fallback on), not offered (fallback off)
        sw.caps(RecurringSupport.NONE)
        assertEquals("MANUAL", quoteOptions(sub, caller).recurring)
        assertTrue(quoteOptions(sub, caller).available)

        h.config = h.config.copy(subscriptionManualFallback = false)
        quoteOptions(sub, caller).let {
            assertFalse(it.available)
            assertEquals("RECURRING_NOT_SUPPORTED", it.unavailableReason)
            assertNull(it.recurring)
        }
        assertEquals("RECURRING_NOT_SUPPORTED", expect("PAYMENT_METHOD_UNAVAILABLE", 400) { buy(sub, caller) }.getString("reason"))
        assertEquals(0, count("market_subscription"), "the refused sale leaves nothing behind")
        assertEquals(0, count("market_order"))

        // recurring billing that fits the plan
        for (kind in listOf(RecurringSupport.GATEWAY_MANAGED, RecurringSupport.MERCHANT_INITIATED)) {
            sw.caps(kind)
            assertEquals("AUTO", quoteOptions(sub, caller).recurring, "$kind with the fallback off")

            h.config = h.config.copy(subscriptionManualFallback = true)
            assertEquals("AUTO", quoteOptions(sub, caller).recurring, "$kind with the fallback on")
            h.config = h.config.copy(subscriptionManualFallback = false)
        }

        // the plan does not fit: currency, interval unit, longest finite plan
        val misfits = listOf<PaymentCapabilities.() -> Unit>(
            { recurringCurrencies = setOf("USD") },
            { recurringIntervals = setOf(IntervalUnit.YEAR) },
            { recurringMaxCycles = 3 },
            { recurringMinIntervalDays = 90 },
            { recurringMaxIntervalDays = 7 }
        )

        for ((index, misfit) in misfits.withIndex()) {
            sw.caps(RecurringSupport.GATEWAY_MANAGED, misfit)

            h.config = h.config.copy(subscriptionManualFallback = true)
            assertEquals("MANUAL", quoteOptions(sub, caller).recurring, "misfit $index, fallback on")

            h.config = h.config.copy(subscriptionManualFallback = false)
            assertEquals("RECURRING_NOT_SUPPORTED", quoteOptions(sub, caller).unavailableReason, "misfit $index, fallback off")
        }
    }

    @Test
    fun `an eligibility verdict oneOffOnly makes the pending row MANUAL and sends no plan, without the fallback it refuses the method`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        // the offer table says AUTO (gateway-managed billing that fits), the provider's own verdict says it cannot bill this plan
        sw.caps(RecurringSupport.GATEWAY_MANAGED)
        sw.eligibility { snapshot -> if (snapshot.subscription != null) Eligibility.oneOffOnly("NO_PLAN") else Eligibility.eligible() }

        val (_, caller) = user("Alice")

        assertEquals("MANUAL", quoteOptions(sub, caller).recurring, "the quote shows the downgrade")

        val order = buy(sub, caller)
        val row = subscriptionOf(order)

        assertEquals(SubscriptionMode.MANUAL, row.mode, "the checkout's verdict, not the capabilities alone, decides the pending row")
        assertNull((fake.calls(FakePaymentProvider.Op.START).last().request as StartPaymentRequest).subscription, "no recurring plan goes to a provider that said it cannot bill it")
        assertNull(sw.subs.planFor(order(order.id), pool))

        // a plain success activates it MANUAL, and that is no downgrade: nothing recurring was promised
        succeed(order)

        subscriptionOf(order).let {
            assertEquals(SubscriptionStatus.ACTIVE, it.status)
            assertEquals(SubscriptionMode.MANUAL, it.mode)
        }
        assertEquals(0, events(order.id, OrderEventType.NOTE).count { it.message == SubscriptionService.DOWNGRADE_NOTE })

        // without the manual fallback the method is not offered for this plan at all, and a checkout through it leaves nothing behind
        h.config = h.config.copy(subscriptionManualFallback = false)

        val (bea, other) = user("Bea")

        assertEquals("RECURRING_NOT_SUPPORTED", quoteOptions(sub, other).unavailableReason)
        assertEquals("RECURRING_NOT_SUPPORTED", expect("PAYMENT_METHOD_UNAVAILABLE", 400) { buy(sub, other) }.getString("reason"))
        assertEquals(0, count("market_subscription", "`userId` = ${bea.id}"))
        assertEquals(0, count("market_order", "`userId` = ${bea.id}"))
    }

    // ==================================================================================== the pending row at O1 (tests 21, 22)

    @Test
    fun `checkout creates the PENDING row with the plan, the price and the provisional mode, and the start carries the plan only for AUTO`(): Unit = runBlocking {
        val sub = subProduct(price = 600, maxCycles = 12)

        fx.paymentMethod("fake", feeMode = PaymentFeeMode.BUYER, feeFixed = 50)
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (alex, caller) = user("Alex")
        val order = buy(sub, caller)
        val row = subscriptionOf(order)

        assertEquals(SubscriptionStatus.PENDING, row.status)
        assertEquals(SubscriptionMode.GATEWAY, row.mode)
        assertEquals(alex.id, row.userId)
        assertEquals("Alex", row.playerUsername)
        assertEquals("u:${alex.id}", row.ownerKey)
        assertEquals("alex@example.com", row.email)
        assertEquals(sub.id, row.productId)
        assertEquals(0, row.variantId)
        assertEquals(order.id, row.initialOrderId)
        assertEquals(w.orderItems.getByOrderIds(listOf(order.id), pool).single().id, row.initialOrderItemId)
        assertEquals("fake", row.providerId)
        assertEquals(com.panomc.plugins.market.db.model.SubscriptionIntervalUnit.MONTH, row.intervalUnit)
        assertEquals(1, row.intervalCount)
        assertEquals(12, row.maxCycles)
        assertEquals(order.totalPrice, row.price, "the per-period price is the order total: VAT and payment fee included")
        assertTrue(order.paymentFee > 0 && row.price > sub.price, "the fee is part of it")
        assertEquals("EUR", row.currency)
        assertEquals(0, row.cycleCount)
        assertNull(row.currentPeriodEnd)
        assertNull(row.gatewaySubscriptionId)

        // the start request of the AUTO offer carries the plan; planKey is stable and follows the price
        val plan = (fake.calls(FakePaymentProvider.Op.START).last().request as StartPaymentRequest).subscription
        val expected = RecurringPlan("EUR", IntervalUnit.MONTH, 1, 12)

        assertNotNull(plan)
        assertEquals(row.id, plan!!.subscriptionId)
        assertEquals(CheckoutService.planKey(sub.id, 0, row.price, "EUR", expected), plan.planKey)
        assertEquals(plan.planKey, sw.subs.planFor(order(order.id), pool)!!.planKey, "stable across calls")
        assertNotEquals(plan.planKey, CheckoutService.planKey(sub.id, 0, row.price + 1, "EUR", expected), "a changed price is another plan")
        assertEquals(IntervalUnit.MONTH, plan.intervalUnit)
        assertEquals(12, plan.maxCycles)
        assertEquals(Money(row.price, "EUR"), plan.price)

        // MANUAL: the same product through a gateway without recurring billing carries no plan
        val (_, other) = user("Bea")

        sw.caps(RecurringSupport.NONE)

        val manual = buy(sub, other)

        assertEquals(SubscriptionMode.MANUAL, subscriptionOf(manual).mode)
        assertNull((fake.calls(FakePaymentProvider.Op.START).last().request as StartPaymentRequest).subscription)
        assertNull(sw.subs.planFor(order(manual.id), pool))
    }

    @Test
    fun `a variant's period count wins over the product's`(): Unit = runBlocking {
        val sub = subProduct()
        val quarterly = fx.variant(sub, "Quarterly")

        sql("UPDATE `pano_market_product_variant` SET `periodCount` = 3 WHERE `id` = ?", quarterly.id)
        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alice")
        val order = buy(sub, caller, variant = quarterly.id)
        val row = subscriptionOf(order)

        assertEquals(3, row.intervalCount)
        assertEquals(quarterly.id, row.variantId)
        assertEquals(3, (fake.calls(FakePaymentProvider.Op.START).last().request as StartPaymentRequest).subscription!!.intervalCount)
    }

    // ==================================================================================== the retry of the initial order (09 section 4.3, test 22)

    private suspend fun pay(order: MarketOrder, method: String): JsonObject? = sw.payments.pay(order(order.id), PayRequest(method, null, null), PayCaller(), pool)

    private fun startOf(provider: FakePaymentProvider) = provider.calls(FakePaymentProvider.Op.START).last().request as StartPaymentRequest

    @Test
    fun `a retry through another method moves the PENDING row to its provider, mode and price, and the new start carries that plan`(): Unit = runBlocking {
        val sub = subProduct(price = 600, maxCycles = 12)

        fx.paymentMethod("fake", feeMode = PaymentFeeMode.BUYER, feeFixed = 50)
        fx.paymentMethod("second", feeMode = PaymentFeeMode.BUYER, feeFixed = 200)
        sw.caps(RecurringSupport.GATEWAY_MANAGED)
        sw.capsOfSecond(RecurringSupport.MERCHANT_INITIATED)

        val (_, caller) = user("Alice")
        val order = buy(sub, caller)

        subscriptionOf(order).let {
            assertEquals("fake", it.providerId)
            assertEquals(SubscriptionMode.GATEWAY, it.mode)
            assertEquals(650, it.price)
        }

        val firstAttempt = attemptOf(order)

        pay(order, "second")

        val moved = order(order.id)
        val row = subscriptionOf(moved)

        assertEquals("second", moved.paymentMethodId)
        assertEquals(800, moved.totalPrice, "price + the fee of the new method")
        assertEquals("second", row.providerId)
        assertEquals(SubscriptionMode.MERCHANT, row.mode, "the offer of the new method decides the mode")
        assertEquals(moved.totalPrice, row.price, "the per-period price follows the re-priced order")
        assertEquals(SubscriptionStatus.PENDING, row.status)
        assertEquals(PaymentStatus.CANCELLED, w.payments.getById(firstAttempt.id, pool)!!.status)

        // the start of the new attempt asks for what the plan says, at the provider the row names
        val start = startOf(sw.secondFake)
        val plan = start.subscription

        assertNotNull(plan)
        assertEquals(row.id, plan!!.subscriptionId)
        assertEquals(Money(moved.totalPrice, "EUR"), plan.price)
        assertEquals(start.amount, plan.price, "the provider gets the plan price it will actually charge")
        assertEquals(CheckoutService.planKey(sub.id, 0, moved.totalPrice, "EUR", RecurringPlan("EUR", IntervalUnit.MONTH, 1, 12)), plan.planKey)
        assertEquals(plan.planKey, sw.second.eligibilityCalls.last().subscription!!.planKey, "the provider was asked about the plan it is going to bill")

        // the success of the new method activates with its mode
        succeed(moved, stored = StoredPaymentMethod("tok_2").also { it.label = "Visa 4242" })

        subscriptionOf(order).let {
            assertEquals(SubscriptionStatus.ACTIVE, it.status)
            assertEquals(SubscriptionMode.MERCHANT, it.mode)
            assertEquals("second", it.providerId)
            assertEquals(800, it.price)
        }
    }

    @Test
    fun `a retry through the same method follows a changed fee, the row and the plan carry the re-priced total`(): Unit = runBlocking {
        val sub = subProduct(price = 600)

        fx.paymentMethod("fake", feeMode = PaymentFeeMode.BUYER, feeFixed = 50)
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alice")
        val order = buy(sub, caller)

        assertEquals(650, subscriptionOf(order).price)

        // the store raised the fee of the method meanwhile
        fx.paymentMethod("fake", feeMode = PaymentFeeMode.BUYER, feeFixed = 120)
        pay(order, "fake")

        val retried = order(order.id)
        val row = subscriptionOf(retried)

        assertEquals(720, retried.totalPrice)
        assertEquals(720, row.price, "09 section 4.3: price = the re-priced totalPrice while PENDING")
        assertEquals("fake", row.providerId)
        assertEquals(SubscriptionMode.GATEWAY, row.mode, "the same method keeps its mode")

        val start = startOf(fake)

        assertEquals(Money(720, "EUR"), start.subscription!!.price, "the plan price and the planKey follow the order")
        assertEquals(start.amount, start.subscription!!.price)
        assertEquals(CheckoutService.planKey(sub.id, 0, 720, "EUR", RecurringPlan("EUR", IntervalUnit.MONTH, 1, null)), start.subscription!!.planKey)
        assertEquals(sw.subs.planFor(retried, pool)!!.planKey, start.subscription!!.planKey)
    }

    @Test
    fun `a retry asks the provider about the plan, oneOffOnly downgrades the row to MANUAL and without the fallback the retry is refused`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alice")
        val order = buy(sub, caller)

        assertEquals(SubscriptionMode.GATEWAY, subscriptionOf(order).mode)

        sw.eligibility { snapshot -> if (snapshot.subscription != null) Eligibility.oneOffOnly("NO_PLAN") else Eligibility.eligible() }

        // fallback off: the method cannot sell this plan, nothing of the order or the row changes, the open attempt is not cancelled
        h.config = h.config.copy(subscriptionManualFallback = false)

        val open = attemptOf(order)

        assertEquals("RECURRING_NOT_SUPPORTED", expect("PAYMENT_METHOD_UNAVAILABLE", 400) { pay(order, "fake") }.getString("reason"))
        assertEquals(SubscriptionMode.GATEWAY, subscriptionOf(order).mode)
        assertEquals(PaymentStatus.PENDING, w.payments.getById(open.id, pool)!!.status)
        assertEquals(1, w.payments.getByOrderId(order.id, pool).size)

        // fallback on: the retry is a one-off payment with a reminder, no plan goes to the provider
        h.config = h.config.copy(subscriptionManualFallback = true)
        pay(order, "fake")

        assertEquals(SubscriptionMode.MANUAL, subscriptionOf(order).mode)
        assertNull(startOf(fake).subscription)
        assertNull(sw.subs.planFor(order(order.id), pool))

        succeed(order)

        assertEquals(SubscriptionStatus.ACTIVE, subscriptionOf(order).status)
        assertEquals(SubscriptionMode.MANUAL, subscriptionOf(order).mode)
        assertEquals(0, events(order.id, OrderEventType.NOTE).count { it.message == SubscriptionService.DOWNGRADE_NOTE }, "the row was MANUAL before the success: no downgrade")
    }

    @Test
    fun `a retry through a method that cannot bill the plan is MANUAL with the fallback and refused without it`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        fx.paymentMethod("second")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)
        sw.capsOfSecond(RecurringSupport.NONE)

        val (_, caller) = user("Alice")
        val order = buy(sub, caller)

        h.config = h.config.copy(subscriptionManualFallback = false)
        assertEquals("RECURRING_NOT_SUPPORTED", expect("PAYMENT_METHOD_UNAVAILABLE", 400) { pay(order, "second") }.getString("reason"))
        subscriptionOf(order).let {
            assertEquals("fake", it.providerId, "the refused retry left the row as it was")
            assertEquals(SubscriptionMode.GATEWAY, it.mode)
        }
        assertEquals("fake", order(order.id).paymentMethodId)

        h.config = h.config.copy(subscriptionManualFallback = true)
        pay(order, "second")

        subscriptionOf(order).let {
            assertEquals("second", it.providerId)
            assertEquals(SubscriptionMode.MANUAL, it.mode)
        }
        assertNull(startOf(sw.secondFake).subscription, "a manual offer carries no plan")
    }

    @Test
    fun `the method of a credit-paid subscription and of a running or renewal order stays locked and an active row is never rewritten`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        fx.paymentMethod("second")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)
        sw.capsOfSecond(RecurringSupport.GATEWAY_MANAGED)

        val (alex, caller) = user("Alex")
        val pending = buy(sub, caller)

        // a pending subscription order that is paid with credits keeps `credits` (the precondition is set directly: a credit-paid order completes at checkout)
        sql("UPDATE `pano_market_order` SET `paymentMethodId` = 'credits' WHERE `id` = ?", pending.id)
        assertEquals("METHOD_LOCKED", expect("PAYMENT_METHOD_UNAVAILABLE", 400) { pay(pending, "second") }.getString("reason"))
        assertEquals("credits", order(pending.id).paymentMethodId)
        assertEquals("fake", subscriptionOf(pending).providerId, "a refused retry changes nothing")

        // a pending row has a plan to judge, an active row has none and an order of the past does not rewrite it
        sql("UPDATE `pano_market_order` SET `paymentMethodId` = 'fake' WHERE `id` = ?", pending.id)
        assertNotNull(sw.subs.pendingPlan(order(pending.id), pool))

        val (_, other) = user("Bea")
        val paid = buy(subProduct(), other)

        succeed(paid, subscription = gateway("sub_1"))
        assertNull(sw.subs.pendingPlan(order(paid.id), pool))
        assertFalse(sw.db.tx { conn -> sw.subs.onMethodChanged(conn, order(paid.id), "bank-transfer", "MANUAL") })
        assertEquals("fake", subscriptionOf(paid).providerId)
        assertEquals(SubscriptionMode.GATEWAY, subscriptionOf(paid).mode)

        // a renewal order of the subscription is not the initial order: it keeps its provider whatever the row says
        val renewal = sw.dw.place(user = alex, actions = emptyList(), status = OrderStatus.PENDING, reservation = ReservationState.HELD, source = OrderSource.RENEWAL, subscriptionId = paid.subscriptionId)

        assertNull(sw.subs.pendingPlan(renewal.order, pool))
    }

    // ==================================================================================== activation (tests 23 to 26)

    @Test
    fun `a success with a stored method activates a MERCHANT subscription with the first period, an encrypted token and the GRANT rows only`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.MERCHANT_INITIATED)

        val (alex, caller) = user("Alex")
        val order = buy(sub, caller)

        assertEquals(SubscriptionMode.MERCHANT, subscriptionOf(order).mode, "the offer promises a charge by market")

        val applied = succeed(order, stored = StoredPaymentMethod("tok_123").also { it.label = "Visa 4242"; it.expiresAt = w.clock.now() + 400 * 86_400_000L; it.gatewayCustomerId = "cus_1" })
        val paid = order(order.id)
        val row = subscriptionOf(order)

        assertEquals(OrderStatus.COMPLETED, applied.orderStatus)
        assertEquals(SubscriptionStatus.ACTIVE, row.status)
        assertEquals(SubscriptionMode.MERCHANT, row.mode)
        assertEquals("fake", row.providerId)
        assertEquals(paid.totalPrice, row.price)
        assertEquals(1, row.cycleCount)
        assertEquals(paid.paidAt, row.currentPeriodStart, "the anchor is the paidAt of the initial order")
        assertEquals(oneMonthAfter(paid.paidAt!!), row.currentPeriodEnd)
        assertEquals(row.currentPeriodEnd, row.nextChargeAt, "the next charge is the period end")
        assertNull(row.nextQueryAt)
        assertEquals("cus_1", row.gatewayCustomerId)
        assertNull(row.gatewaySubscriptionId)
        assertEquals("Visa 4242", row.storedMethodLabel)

        val stored = row.storedMethod!!

        assertFalse(stored.contains("tok_123"), "the token is encrypted at rest")
        assertEquals("tok_123", JsonObject(sw.cipher.decrypt(stored)!!).getString("token"))
        assertEquals("Visa 4242", JsonObject(sw.cipher.decrypt(stored)!!).getString("label"))

        // the entitlement of O2 belongs to the subscription and never expires on its own
        val entitlement = w.entitlements.getByOrderItemId(row.initialOrderItemId, pool).single()

        assertEquals(entitlement.id, row.entitlementId)
        assertEquals(row.id, entitlement.subscriptionId)
        assertNull(entitlement.expiresAt)
        assertEquals(EntitlementStatus.ACTIVE, entitlement.status)

        // GRANT deliveries only: RENEW belongs to a paid renewal, EXPIRE to the end
        val rows = sw.dw.rows(order.id)

        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.phase == DeliveryPhase.GRANT }, rows.map { it.phase }.toString())
        assertTrue(rows.all { it.subscriptionId == row.id }, "the rows name the subscription")

        // the timeline and the store webhook, once
        assertEquals(1, events(order.id, OrderEventType.SUBSCRIPTION_STARTED).size)

        val started = sw.hooks.filter { it.event == "subscription.started" }.single()

        assertEquals("sub:${row.id}:started", started.key)
        assertEquals(order.id, started.orderId)
        assertEquals("ACTIVE", started.data.getJsonObject("subscription").getString("status"))
        assertEquals("MERCHANT", started.data.getJsonObject("subscription").getString("mode"))
        assertEquals(alex.id, started.data.getJsonObject("subscription").getLong("userId"))
        assertEquals(paid.publicId, started.data.getJsonObject("order").getString("publicId"))
        assertFalse(started.testMode)

        // the ending of this activation is not scheduled: no EXPIRE row exists before the subscription ends
        assertEquals(0, count("market_delivery", "`phase` = 'EXPIRE'"))
    }

    @Test
    fun `a success with a gateway subscription activates a GATEWAY subscription with the gateway's period and poll time, S-04`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alex")
        val order = buy(sub, caller)
        val start = w.clock.now() - 60_000
        val end = start + 30 * 86_400_000L

        succeed(order, subscription = gateway("sub_gw_1", periodStart = start, periodEnd = end))

        val row = subscriptionOf(order)

        assertEquals(SubscriptionStatus.ACTIVE, row.status)
        assertEquals(SubscriptionMode.GATEWAY, row.mode)
        assertEquals("sub_gw_1", row.gatewaySubscriptionId)
        assertEquals("cus_sub_gw_1", row.gatewayCustomerId)
        assertEquals(start, row.currentPeriodStart, "the gateway's period is used")
        assertEquals(end, row.currentPeriodEnd)
        assertEquals(1, row.cycleCount)
        assertEquals(end + 3_600_000L, row.nextQueryAt, "the poll is one hour after the period end")
        assertNull(row.nextChargeAt, "the gateway charges, not market")
        assertNull(row.storedMethod)
        assertFalse(row.providerData!!.contains("plan_1"), "the provider data is encrypted at rest")
        assertEquals("plan_1", JsonObject(sw.cipher.decrypt(row.providerData!!)!!).getString("plan"))

        val entitlement = w.entitlements.getByOrderItemId(row.initialOrderItemId, pool).single()

        assertNull(entitlement.expiresAt)
        assertEquals(row.id, entitlement.subscriptionId)
        assertTrue(sw.dw.rows(order.id).all { it.phase == DeliveryPhase.GRANT })
    }

    @Test
    fun `an unsound gateway period falls back to one interval from the anchor`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alex")
        val order = buy(sub, caller)
        val now = w.clock.now()

        // an end that is not after the start
        succeed(order, subscription = gateway("sub_gw_1", periodStart = now, periodEnd = now - 1000))

        val row = subscriptionOf(order)
        val paidAt = order(order.id).paidAt!!

        assertEquals(now, row.currentPeriodStart)
        assertEquals(oneMonthAfter(paidAt), row.currentPeriodEnd)
    }

    @Test
    fun `a plain success makes a MANUAL subscription and says so on the timeline when the offer promised more`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alex")
        val order = buy(sub, caller)

        assertEquals(SubscriptionMode.GATEWAY, subscriptionOf(order).mode, "provisional")

        succeed(order)

        val row = subscriptionOf(order)

        assertEquals(SubscriptionStatus.ACTIVE, row.status)
        assertEquals(SubscriptionMode.MANUAL, row.mode, "the mode is what the attempt delivered")
        assertNull(row.nextChargeAt)
        assertNull(row.nextQueryAt)
        assertNull(row.gatewaySubscriptionId)
        assertEquals(1, events(order.id, OrderEventType.NOTE).count { it.message == SubscriptionService.DOWNGRADE_NOTE }, "the downgrade is on the timeline")
    }

    @Test
    fun `a stored method from a provider that is not merchant-initiated is ignored and no token is kept`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.NONE)

        val (_, caller) = user("Alex")
        val order = buy(sub, caller)

        succeed(order, stored = StoredPaymentMethod("tok_x").also { it.label = "Card" })

        val row = subscriptionOf(order)

        assertEquals(SubscriptionMode.MANUAL, row.mode)
        assertNull(row.storedMethod)
        assertNull(row.storedMethodLabel)
        assertNull(row.nextChargeAt)
        assertEquals(0, events(order.id, OrderEventType.NOTE).count { it.message == SubscriptionService.DOWNGRADE_NOTE }, "a manual offer that stays manual is no downgrade")
    }

    @Test
    fun `a replayed success activates once`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alex")
        val order = buy(sub, caller)
        val attempt = attemptOf(order)

        succeed(order, subscription = gateway("sub_gw_1"))

        val before = subscriptionOf(order)

        // the same event again (a redelivered webhook), and the effect once more under the lock
        assertFalse(succeed(order, subscription = gateway("sub_gw_1"), attempt = attempt).changed)
        sw.db.txRestartingOnOrderChange { conn -> sw.locks.forOrder(conn, order.id, OrderLockScope.COMMIT) { locked -> sw.subs.onOrderPaid(conn, locked) } }

        val after = subscriptionOf(order)

        assertEquals(1, after.cycleCount)
        assertEquals(before.currentPeriodEnd, after.currentPeriodEnd)
        assertEquals(before.updatedAt, after.updatedAt, "nothing was written")
        assertEquals(1, events(order.id, OrderEventType.SUBSCRIPTION_STARTED).size)
        assertEquals(1, sw.hooks.count { it.event == "subscription.started" })
        assertEquals(1, w.entitlements.getByOrderItemId(after.initialOrderItemId, pool).size)
    }

    private fun completed(request: StartPaymentRequest, change: PaymentEvent.Succeeded.() -> Unit): StartPaymentResult =
        StartPaymentResult.Completed(PaymentEvent.Succeeded(PaymentTarget.Attempt(request.attempt.id), request.amount).also { it.change() })

    @Test
    fun `a synchronous Completed start carries the stored method and the gateway subscription into the activation`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")

        // a stored-method charge that settles on the spot (MERCHANT)
        sw.caps(RecurringSupport.MERCHANT_INITIATED)
        fake.onStart = { request ->
            completed(request) {
                storedMethod = StoredPaymentMethod("tok_sync").also { it.label = "Visa 4242"; it.expiresAt = w.clock.now() + 400 * 86_400_000L; it.gatewayCustomerId = "cus_sync" }
                methodDetail = "Visa 4242"
            }
        }

        val (_, alice) = user("Alice")
        val merchant = buy(sub, alice)
        val m = subscriptionOf(merchant)

        assertEquals(OrderStatus.COMPLETED, order(merchant.id).status)
        assertEquals(SubscriptionStatus.ACTIVE, m.status)
        assertEquals(SubscriptionMode.MERCHANT, m.mode, "the token reached the activation: not the MANUAL downgrade")
        assertEquals("tok_sync", JsonObject(sw.cipher.decrypt(m.storedMethod!!)!!).getString("token"))
        assertEquals("Visa 4242", m.storedMethodLabel)
        assertEquals("cus_sync", m.gatewayCustomerId)
        assertEquals(m.currentPeriodEnd, m.nextChargeAt)
        assertEquals(0, events(merchant.id, OrderEventType.NOTE).count { it.message == SubscriptionService.DOWNGRADE_NOTE })

        // a subscription the gateway created during the start (GATEWAY)
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val periodStart = w.clock.now()
        val periodEnd = periodStart + 30 * 86_400_000L

        fake.onStart = { request -> completed(request) { subscription = gateway("sub_sync", periodStart = periodStart, periodEnd = periodEnd) } }

        val (_, bea) = user("Bea")
        val managed = buy(sub, bea)
        val g = subscriptionOf(managed)

        assertEquals(OrderStatus.COMPLETED, order(managed.id).status)
        assertEquals(SubscriptionStatus.ACTIVE, g.status)
        assertEquals(SubscriptionMode.GATEWAY, g.mode)
        assertEquals("sub_sync", g.gatewaySubscriptionId, "the remote subscription id is stored, so its renewals and its cancel find the row")
        assertEquals(periodStart, g.currentPeriodStart)
        assertEquals(periodEnd, g.currentPeriodEnd)
        assertEquals(periodEnd + 3_600_000L, g.nextQueryAt)
        assertEquals(0, events(managed.id, OrderEventType.NOTE).count { it.message == SubscriptionService.DOWNGRADE_NOTE })

        // its status events find the row (they were skipped as an unknown subscription when the id was not stored)
        val outcome = gatewayEvent("sub_sync", GatewaySubscriptionStatus.PAUSED)

        assertTrue(outcome is SubscriptionService.GatewayOutcome.Applied, outcome.toString())
        assertEquals(SubscriptionStatus.PAUSED, subscriptionOf(managed).status)
    }

    @Test
    fun `the Completed step of an embedded form carries the stored method and the gateway subscription into the activation`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        fake.onStart = { StartPaymentResult.Embedded(JsonObject().put("step", 1)) }

        // MERCHANT
        sw.caps(RecurringSupport.MERCHANT_INITIATED)

        val (_, alice) = user("Alice")
        val merchant = buy(sub, alice)

        sw.first.onContinue = { request ->
            StartPaymentResult.Completed(PaymentEvent.Succeeded(PaymentTarget.Attempt(request.attempt.id), request.attempt.amount).also {
                it.storedMethod = StoredPaymentMethod("tok_step").also { m -> m.label = "Mastercard 5555" }
            })
        }

        val done = sw.payments.continuePayment(order(merchant.id), JsonObject().put("pan", "x"), PayCaller(), pool)

        assertEquals("COMPLETED", done!!.getString("kind"))
        subscriptionOf(merchant).let {
            assertEquals(SubscriptionStatus.ACTIVE, it.status)
            assertEquals(SubscriptionMode.MERCHANT, it.mode)
            assertEquals("tok_step", JsonObject(sw.cipher.decrypt(it.storedMethod!!)!!).getString("token"))
            assertEquals("Mastercard 5555", it.storedMethodLabel)
            assertEquals(it.currentPeriodEnd, it.nextChargeAt)
        }

        // GATEWAY
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, bea) = user("Bea")
        val managed = buy(sub, bea)

        sw.first.onContinue = { request ->
            StartPaymentResult.Completed(PaymentEvent.Succeeded(PaymentTarget.Attempt(request.attempt.id), request.attempt.amount).also { it.subscription = gateway("sub_step") })
        }

        assertEquals("COMPLETED", sw.payments.continuePayment(order(managed.id), JsonObject(), PayCaller(), pool)!!.getString("kind"))
        subscriptionOf(managed).let {
            assertEquals(SubscriptionStatus.ACTIVE, it.status)
            assertEquals(SubscriptionMode.GATEWAY, it.mode)
            assertEquals("sub_step", it.gatewaySubscriptionId)
            assertNotNull(it.nextQueryAt)
        }
    }

    @Test
    fun `a stored method label or method detail wider than the label column is cut to its 64 characters and the order still completes`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.MERCHANT_INITIATED)

        val wide = "x".repeat(100)

        // a 100-character label
        val (_, alice) = user("Alice")
        val labelled = buy(sub, alice)

        assertEquals(OrderStatus.COMPLETED, succeed(labelled, stored = StoredPaymentMethod("tok_a").also { it.label = wide }).orderStatus)
        subscriptionOf(labelled).let {
            assertEquals(SubscriptionStatus.ACTIVE, it.status)
            assertEquals(wide.take(64), it.storedMethodLabel)
            assertEquals(wide, JsonObject(sw.cipher.decrypt(it.storedMethod!!)!!).getString("label"), "the encrypted token record keeps the whole text")
        }

        // no label, a 100-character method detail (legal on the payment row, 128 wide)
        val (_, bea) = user("Bea")
        val detailed = buy(sub, bea)

        assertEquals(OrderStatus.COMPLETED, succeed(detailed, stored = StoredPaymentMethod("tok_b"), detail = wide).orderStatus)
        assertEquals(wide.take(64), subscriptionOf(detailed).storedMethodLabel)
        assertEquals(wide, attemptOf(detailed).methodDetail, "the payment row keeps its 100 characters")

        // a cut never splits a surrogate pair
        val emoji = "😀"
        val (_, cem) = user("Cem")
        val pair = buy(sub, cem)

        assertEquals(OrderStatus.COMPLETED, succeed(pair, stored = StoredPaymentMethod("tok_c").also { it.label = "y".repeat(63) + emoji + "tail" }).orderStatus)
        assertEquals("y".repeat(63) + emoji, subscriptionOf(pair).storedMethodLabel)

        // a method detail wider than its own column no longer fails the payment (and with it the activation of a paid order)
        val (_, dan) = user("Dan")
        val huge = buy(sub, dan)

        assertEquals(OrderStatus.COMPLETED, succeed(huge, stored = StoredPaymentMethod("tok_d"), detail = "z".repeat(200)).orderStatus)
        assertEquals("z".repeat(128), attemptOf(huge).methodDetail)
        assertEquals("z".repeat(64), subscriptionOf(huge).storedMethodLabel)
    }

    // ==================================================================================== the late success and the review (tests 27, 28)

    @Test
    fun `an expired order keeps the pending row, a late success waits in review and the accept activates it with the gateway data`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alex")
        val order = buy(sub, caller)

        expire(order)

        assertEquals(OrderStatus.EXPIRED, order(order.id).status)
        assertEquals(SubscriptionStatus.PENDING, subscriptionOf(order).status, "an expired order does not close the row: a late payment may still arrive")

        val late = succeed(order, subscription = gateway("sub_late"))

        assertEquals(OrderStatus.REVIEW, late.orderStatus)
        assertEquals(ReviewReason.LATE.name, order(order.id).reviewReason)
        // review: nothing is active, but what the gateway said is kept on the row for the accept
        subscriptionOf(order).let {
            assertEquals(SubscriptionStatus.PENDING, it.status)
            assertEquals("sub_late", it.gatewaySubscriptionId)
            assertEquals(SubscriptionMode.GATEWAY, it.mode)
            assertNull(it.currentPeriodEnd, "no period is booked before the accept")
        }
        assertEquals(0, w.entitlements.getByOrderItemId(subscriptionOf(order).initialOrderItemId, pool).size)

        val change = sw.review.review(order.id, ReviewDecision.ACCEPT, refund = false, force = false, note = null, adminUserId = 7)

        assertEquals(OrderStatus.COMPLETED, change.order.status)

        val row = subscriptionOf(order)

        assertEquals(SubscriptionStatus.ACTIVE, row.status)
        assertEquals(SubscriptionMode.GATEWAY, row.mode)
        assertEquals("sub_late", row.gatewaySubscriptionId)
        assertEquals(1, row.cycleCount)
        assertNotNull(row.entitlementId)
        assertNotNull(row.nextQueryAt)
    }

    @Test
    fun `accepting a review whose subscription was closed meanwhile answers INVALID_ORDER_TRANSITION SUBSCRIPTION_CLOSED`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alex")
        val order = buy(sub, caller)

        expire(order)
        succeed(order, subscription = gateway("sub_late"))

        // step F closed the row (30 days after the order was released)
        sql("UPDATE `pano_market_subscription` SET `status` = 'CANCELLED', `endReason` = 'PAYMENT_FAILED', `endedAt` = ? WHERE `id` = ?", w.clock.now(), order.subscriptionId!!)

        val body = expect("INVALID_ORDER_TRANSITION", 400) { sw.review.review(order.id, ReviewDecision.ACCEPT, refund = false, force = false, note = null, adminUserId = 7) }

        assertEquals(SubscriptionStateMachine.SUBSCRIPTION_CLOSED, body.getString("reason"))
        assertEquals(OrderStatus.REVIEW, order(order.id).status, "the accept rolled back")
        assertEquals(0, w.entitlements.getByOrderItemId(subscriptionOf(order).initialOrderItemId, pool).size)
        assertEquals(SubscriptionStatus.CANCELLED, subscriptionOf(order).status)
        assertEquals(0, count("market_delivery"), "nothing was delivered")
    }

    @Test
    fun `rejecting a review closes the pending row, and queues the remote cancel only when the gateway subscription is known`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, a) = user("Alex")
        val (_, b) = user("Bea")
        val known = buy(sub, a)
        val unknown = buy(sub, b)

        for (o in listOf(known, unknown)) expire(o)

        succeed(known, subscription = gateway("sub_known"))
        succeed(unknown)

        for (o in listOf(known, unknown)) {
            val change = sw.review.review(o.id, ReviewDecision.REJECT, refund = false, force = false, note = null, adminUserId = 7)

            assertEquals(OrderStatus.CANCELLED, change.order.status)
        }

        subscriptionOf(known).let {
            assertEquals(SubscriptionStatus.CANCELLED, it.status)
            assertEquals("ADMIN_CANCEL", it.endReason)
            assertNotNull(it.endedAt)
            assertEquals(RemoteCancelState.PENDING, it.remoteCancelState, "the gateway must stop billing a subscription market will never activate")
            assertEquals(0, it.remoteCancelAttempts)
            assertNotNull(it.nextQueryAt)
        }
        subscriptionOf(unknown).let {
            assertEquals(SubscriptionStatus.CANCELLED, it.status)
            assertEquals("ADMIN_CANCEL", it.endReason)
            assertEquals(RemoteCancelState.NONE, it.remoteCancelState)
        }

        // no deliveries, no mail, no webhook for a row that never ran
        assertEquals(0, count("market_delivery"))
        assertEquals(0, count("market_mail_outbox"))
        assertEquals(0, sw.hooks.size)
    }

    @Test
    fun `an order that is cancelled, expired or failed leaves its pending row PENDING`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, a) = user("Alex")
        val (_, b) = user("Bea")
        val (_, c) = user("Cem")
        val cancelled = buy(sub, a)
        val failed = buy(sub, b)
        val expired = buy(sub, c)

        transition(cancelled, OrderEvent.Cancel(OrderActor.BUYER))
        transition(failed, OrderEvent.Fail(OrderActor.ADMIN))
        expire(expired)

        assertEquals(OrderStatus.CANCELLED, order(cancelled.id).status)
        assertEquals(OrderStatus.FAILED, order(failed.id).status)
        assertEquals(OrderStatus.EXPIRED, order(expired.id).status)

        for (o in listOf(cancelled, failed, expired)) {
            assertEquals(SubscriptionStatus.PENDING, subscriptionOf(o).status, "step F of SubscriptionJob closes it after 30 days, not the order")
            assertNull(subscriptionOf(o).endedAt)
        }
    }

    // ==================================================================================== a gateway subscription the row does not keep (09 section 4.4)

    private fun cancelCalls() = fake.calls(FakePaymentProvider.Op.CANCEL_SUBSCRIPTION).map { it.request as CancelSubscriptionRequest }

    @Test
    fun `the gateway subscription of a duplicate attempt is cancelled after the commit and the stored one stays`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alex")
        val order = buy(sub, caller)
        val first = attemptOf(order)

        pay(order, "fake")

        val second = attemptOf(order)

        assertNotEquals(first.id, second.id)

        // the newer attempt pays first: the order completes and the row is active with ITS gateway subscription
        succeed(order, subscription = gateway("sub_b"), attempt = second)

        val active = subscriptionOf(order)

        assertEquals(SubscriptionStatus.ACTIVE, active.status)
        assertEquals("sub_b", active.gatewaySubscriptionId)
        assertEquals(0, cancelCalls().size, "the kept subscription is not cancelled")

        // the earlier attempt's money arrives too, with a subscription of its own: a duplicate payment (00 section 7.2)
        val duplicate = succeed(order, subscription = gateway("sub_a"), attempt = first)

        assertTrue(duplicate.duplicate)

        val cancels = cancelCalls()

        assertEquals(1, cancels.size, "exactly one remote cancel")
        assertEquals("sub_a", cancels.single().subscription.gatewaySubscriptionId)
        assertEquals("cus_sub_a", cancels.single().subscription.gatewayCustomerId)
        assertEquals(active.id, cancels.single().subscription.id)
        assertFalse(cancels.single().atPeriodEnd, "immediate")
        assertNull(cancels.single().storedMethod)
        assertFalse(cancels.single().subscription.testMode)

        subscriptionOf(order).let {
            assertEquals("sub_b", it.gatewaySubscriptionId, "the stored subscription is untouched")
            assertEquals(SubscriptionStatus.ACTIVE, it.status)
            assertEquals(active.currentPeriodEnd, it.currentPeriodEnd)
            assertEquals(active.nextQueryAt, it.nextQueryAt)
        }
        assertEquals(0, events(order.id, OrderEventType.NOTE).count { it.message == CancelSurplusSubscription.FAILED_NOTE })

        // the same subscription again (a replayed event, a second delivery of the same attempt) is no duplicate to cancel
        succeed(order, subscription = gateway("sub_a"), attempt = first)
        assertEquals(1, cancelCalls().size)
    }

    @Test
    fun `a refused or failed remote cancel of a surplus subscription is written to the order timeline`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        // what the provider does with the cancel, and what the timeline row says about it
        val cases = listOf<Triple<String, String, () -> Unit>>(
            Triple("failed", "gateway said no") { fake.onCancelSubscription = { CancelSubscriptionResult.Failed("gateway said no") } },
            Triple("buyer action", "only the buyer can cancel it") { fake.onCancelSubscription = { CancelSubscriptionResult.BuyerActionRequired("https://gateway.invalid/manage") } },
            Triple("local only", "cancelled nothing") { fake.onCancelSubscription = { CancelSubscriptionResult.localOnly() } },
            Triple("thrown", "boom") {
                fake.failNext(FakePaymentProvider.Op.CANCEL_SUBSCRIPTION, ProviderException(ProviderErrorCode.GATEWAY_UNREACHABLE, "down", adminMessage = "boom"))
            }
        )

        for ((index, case) in cases.withIndex()) {
            val (label, expected, script) = case
            val (_, caller) = user("Buyer$index")
            val order = buy(sub, caller)
            val first = attemptOf(order)

            pay(order, "fake")

            val second = attemptOf(order)

            script()
            succeed(order, subscription = gateway("sub_keep_$index"), attempt = second)
            succeed(order, subscription = gateway("sub_surplus_$index"), attempt = first)

            val notes = events(order.id, OrderEventType.NOTE).filter { it.message == CancelSurplusSubscription.FAILED_NOTE }

            assertEquals(1, notes.size, "$label: the failure is on the timeline once")

            val data = JsonObject(notes.single().data!!)

            assertEquals("sub_surplus_$index", data.getString("gatewaySubscriptionId"), label)
            assertEquals(subscriptionOf(order).id, data.getLong("subscriptionId"), label)
            assertEquals("fake", data.getString("providerId"), label)
            assertTrue(data.getString("error").contains(expected), "$label: ${data.getString("error")}")
            assertEquals(com.panomc.plugins.market.db.model.OrderActorType.SYSTEM, notes.single().actorType, label)
            assertEquals("sub_keep_$index", subscriptionOf(order).gatewaySubscriptionId, "$label: the row keeps its own subscription")
            assertEquals(index + 1, cancelCalls().size, "$label: one cancel attempt per surplus subscription")

            fake.onCancelSubscription = { CancelSubscriptionResult.localOnly() }
        }
    }

    @Test
    fun `a late success after the row was closed leaves the row closed and cancels its gateway subscription`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alex")
        val order = buy(sub, caller)

        expire(order)

        // step F closed the pending row 30 days after the order was released
        sql("UPDATE `pano_market_subscription` SET `status` = 'CANCELLED', `endReason` = 'PAYMENT_FAILED', `endedAt` = ? WHERE `id` = ?", w.clock.now(), order.subscriptionId!!)

        assertEquals(OrderStatus.REVIEW, succeed(order, subscription = gateway("sub_late")).orderStatus)

        val cancels = cancelCalls()

        assertEquals(1, cancels.size)
        assertEquals("sub_late", cancels.single().subscription.gatewaySubscriptionId)
        assertFalse(cancels.single().atPeriodEnd)

        subscriptionOf(order).let {
            assertEquals(SubscriptionStatus.CANCELLED, it.status)
            assertNull(it.gatewaySubscriptionId, "the closed row did not take the subscription, it was cancelled instead")
        }
    }

    @Test
    fun `while an order waits in review the first paying attempt's subscription is the row's, the second attempt's is cancelled and the accept activates the first`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alex")
        val order = buy(sub, caller)
        val first = attemptOf(order)

        pay(order, "fake")

        val second = attemptOf(order)

        expire(order)

        // the first attempt's money arrives late: the order waits for a human, the row keeps what the gateway said
        assertEquals(OrderStatus.REVIEW, succeed(order, subscription = gateway("sub_1"), attempt = first).orderStatus)
        assertEquals("sub_1", subscriptionOf(order).gatewaySubscriptionId)
        assertEquals(first.id, order(order.id).paymentId)

        // the second attempt pays too while the order is still in review: its subscription must not replace the first's
        assertEquals(OrderStatus.REVIEW, succeed(order, subscription = gateway("sub_2"), attempt = second).orderStatus)
        assertEquals(first.id, order(order.id).paymentId, "the order's own payment is still the first attempt")
        assertEquals("sub_1", subscriptionOf(order).gatewaySubscriptionId, "the second attempt did not overwrite the row")
        assertEquals(listOf("sub_2"), cancelCalls().map { it.subscription.gatewaySubscriptionId })

        // the accept activates the subscription of the attempt that is accepted
        val change = sw.review.review(order.id, ReviewDecision.ACCEPT, refund = false, force = false, note = null, adminUserId = 7)

        assertEquals(OrderStatus.COMPLETED, change.order.status)
        subscriptionOf(order).let {
            assertEquals(SubscriptionStatus.ACTIVE, it.status)
            assertEquals(SubscriptionMode.GATEWAY, it.mode)
            assertEquals("sub_1", it.gatewaySubscriptionId)
        }
        assertEquals(1, cancelCalls().size, "nothing more was cancelled by the accept")
    }

    // ==================================================================================== gateway status events (09 section 7, S-04)

    @Test
    fun `gateway CANCELLED inside the paid period schedules the end, after the period it ends the subscription and the EXPIRE rows run once, S-04`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alex")
        val order = buy(sub, caller)

        succeed(order, subscription = gateway("sub_gw_1"))

        val active = subscriptionOf(order)

        // 1. inside the period: S8, the gateway already stopped, so the remote cancel is done
        val first = gatewayEvent("sub_gw_1", GatewaySubscriptionStatus.CANCELLED)

        assertTrue(first is SubscriptionService.GatewayOutcome.Applied, first.toString())

        subscriptionOf(order).let {
            assertEquals(SubscriptionStatus.ACTIVE, it.status, "paid time is left")
            assertTrue(it.cancelAtPeriodEnd)
            assertEquals("GATEWAY_ENDED", it.endReason)
            assertEquals(RemoteCancelState.DONE, it.remoteCancelState)
            assertNull(it.nextChargeAt)
            assertEquals(active.currentPeriodStart, it.currentPeriodStart, "a status event never moves the period")
            assertEquals(active.currentPeriodEnd, it.currentPeriodEnd)
            assertEquals(1, it.cycleCount)
        }
        assertEquals(1, sw.hooks.count { it.event == "subscription.cancelled" })
        assertEquals(1, mails("SUBSCRIPTION_CANCELLED"))
        assertEquals(1, events(order.id, OrderEventType.SUBSCRIPTION_CANCEL_REQUESTED).size)
        assertEquals(0, count("market_delivery", "`phase` = 'EXPIRE'"), "the access runs on to the period end")

        // the same event again changes nothing
        assertTrue(gatewayEvent("sub_gw_1", GatewaySubscriptionStatus.CANCELLED) is SubscriptionService.GatewayOutcome.Ignored)
        assertEquals(1, sw.hooks.count { it.event == "subscription.cancelled" })

        // 2. after the period: the same status ends it (S7)
        w.clock.advance(40 * 86_400_000L)
        assertTrue(gatewayEvent("sub_gw_1", GatewaySubscriptionStatus.CANCELLED) is SubscriptionService.GatewayOutcome.Applied)

        val ended = subscriptionOf(order)

        assertEquals(SubscriptionStatus.CANCELLED, ended.status)
        assertEquals("GATEWAY_ENDED", ended.endReason)
        assertNotNull(ended.endedAt)
        assertNotNull(ended.cancelledAt)
        assertNull(ended.storedMethod)
        assertNull(ended.nextQueryAt)

        val entitlement = w.entitlements.getById(ended.entitlementId!!, pool)!!

        assertEquals(EntitlementStatus.EXPIRED, entitlement.status)
        assertEquals("SUBSCRIPTION_ENDED", entitlement.endReason)
        assertEquals(1, count("market_delivery", "`phase` = 'EXPIRE'"), "EXPIRE rows of the initial order's line, exactly once")
        assertEquals(1, sw.hooks.count { it.event == "subscription.expired" })
        assertEquals("sub:${ended.id}:ended", sw.hooks.single { it.event == "subscription.expired" }.key)
        assertEquals(1, sw.hooks.count { it.event == "subscription.cancelled" }, "S8 already told the store")
        assertEquals(1, mails("SUBSCRIPTION_ENDED"))
        assertEquals(1, events(order.id, OrderEventType.SUBSCRIPTION_ENDED).size)

        // 3. the gateway still bills a closed subscription: the cancel goes back in the queue, nothing else moves
        assertTrue(gatewayEvent("sub_gw_1", GatewaySubscriptionStatus.ACTIVE) is SubscriptionService.GatewayOutcome.Applied)
        assertEquals(RemoteCancelState.PENDING, subscriptionOf(order).remoteCancelState)
        assertEquals(SubscriptionStatus.CANCELLED, subscriptionOf(order).status)
        assertEquals(1, count("market_delivery", "`phase` = 'EXPIRE'"))
    }

    @Test
    fun `gateway PAST_DUE starts the grace once, records the decline and keeps the period, PAUSED and ACTIVE follow`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alex")
        val order = buy(sub, caller)

        succeed(order, subscription = gateway("sub_gw_1"))

        val active = subscriptionOf(order)

        assertTrue(gatewayEvent("sub_gw_1", GatewaySubscriptionStatus.PAST_DUE) is SubscriptionService.GatewayOutcome.Applied)

        val late = subscriptionOf(order)

        assertEquals(SubscriptionStatus.PAST_DUE, late.status)
        assertEquals(maxOf(w.clock.now(), active.currentPeriodEnd!!) + 3 * 86_400_000L, late.graceEndsAt)
        assertEquals(1, late.failCount)
        assertNotNull(late.lastFailureAt)
        assertEquals(active.currentPeriodEnd, late.currentPeriodEnd, "only a paid renewal moves the period")
        assertEquals(1, mails("SUBSCRIPTION_PAYMENT_FAILED"))
        assertEquals(1, events(order.id, OrderEventType.SUBSCRIPTION_PAST_DUE).size)
        assertEquals(1, events(order.id, OrderEventType.SUBSCRIPTION_CHARGE_FAILED).size)

        val renewal = w.subscriptionRenewals.getByPeriod(late.id, 1, pool)!!

        assertEquals(RenewalStatus.PENDING, renewal.status)
        assertEquals(SubscriptionService.GATEWAY_DECLINED, renewal.lastError)
        assertEquals(1, renewal.attempts)
        assertNull(renewal.orderId, "the gateway retries on its own: no renewal order")

        // a second report changes nothing (the grace is frozen at entry, the mail goes once per period)
        assertTrue(gatewayEvent("sub_gw_1", GatewaySubscriptionStatus.PAST_DUE) is SubscriptionService.GatewayOutcome.Ignored)
        assertEquals(late.graceEndsAt, subscriptionOf(order).graceEndsAt)
        assertEquals(1, mails("SUBSCRIPTION_PAYMENT_FAILED"))

        // ACTIVE while PAST_DUE: wait for the renewal
        assertTrue(gatewayEvent("sub_gw_1", GatewaySubscriptionStatus.ACTIVE) is SubscriptionService.GatewayOutcome.Ignored)
        assertEquals(SubscriptionStatus.PAST_DUE, subscriptionOf(order).status)
    }

    @Test
    fun `gateway PAUSED pauses and ACTIVE resumes`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alex")
        val order = buy(sub, caller)

        succeed(order, subscription = gateway("sub_gw_1"))
        assertTrue(gatewayEvent("sub_gw_1", GatewaySubscriptionStatus.PAUSED) is SubscriptionService.GatewayOutcome.Applied)
        assertEquals(SubscriptionStatus.PAUSED, subscriptionOf(order).status)
        assertNull(subscriptionOf(order).nextChargeAt)
        assertTrue(gatewayEvent("sub_gw_1", GatewaySubscriptionStatus.ACTIVE) is SubscriptionService.GatewayOutcome.Applied)
        assertEquals(SubscriptionStatus.ACTIVE, subscriptionOf(order).status)
    }

    @Test
    fun `a status event stores the customer and the provider data, ignores a pending row and skips a subscription that is not known to its provider`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alex")
        val order = buy(sub, caller)

        expire(order)
        succeed(order, subscription = gateway("sub_gw_1"))

        // a pending row (waiting in review) ignores the status, but keeps what the event brought
        val event = PaymentEvent.SubscriptionUpdated(GatewaySubscriptionState("sub_gw_1", GatewaySubscriptionStatus.CANCELLED).also {
            it.gatewayCustomerId = "cus_new"
            it.providerData = JsonObject().put("plan", "plan_2")
        })
        val outcome = sw.db.tx { conn -> sw.subs.onGatewayEvent(conn, "fake", event) }

        assertEquals(SubscriptionService.GatewayOutcome.Ignored(SubscriptionStateMachine.NOT_ACTIVATED), outcome)

        val row = subscriptionOf(order)

        assertEquals(SubscriptionStatus.PENDING, row.status)
        assertEquals("cus_new", row.gatewayCustomerId)
        assertEquals("plan_2", JsonObject(sw.cipher.decrypt(row.providerData!!)!!).getString("plan"))

        // unknown id, and the right id under another provider: skipped, never an error
        assertEquals(SubscriptionService.GatewayOutcome.UnknownSubscription, gatewayEvent("nope", GatewaySubscriptionStatus.ACTIVE))
        assertEquals(SubscriptionService.GatewayOutcome.UnknownSubscription, gatewayEvent("sub_gw_1", GatewaySubscriptionStatus.ACTIVE, providerId = "other"))
    }

    @Test
    fun `the inbound sink applies SubscriptionUpdated and hands every other kind on`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alex")
        val order = buy(sub, caller)

        succeed(order, subscription = gateway("sub_gw_1"))

        val next = CopyOnWriteArrayList<PaymentEvent>()
        val sink = SubscriptionEventSink(sw.db, { sw.subs }, PaymentEventSink { event, _, _ -> next += event; throw EventNotHandled(event.javaClass.simpleName) })
        val context = InboundEventContext(1, "fake", null, null, w.clock.now())

        sink.apply(PaymentEvent.SubscriptionUpdated(GatewaySubscriptionState("sub_gw_1", GatewaySubscriptionStatus.PAUSED)), null, context)

        assertEquals(SubscriptionStatus.PAUSED, subscriptionOf(order).status)
        assertTrue(next.isEmpty())

        // renewals and failed renewals belong to MK-122: until then the request fails and is retried, nothing is dropped
        val renewed = PaymentEvent.SubscriptionRenewed("sub_gw_1", Money(600, "EUR"))
        val failed = runCatching { sink.apply(renewed, null, context) }.exceptionOrNull()

        assertTrue(failed is EventNotHandled, "$failed")
        assertEquals(listOf<PaymentEvent>(renewed), next.toList())
        assertEquals(SubscriptionStatus.PAUSED, subscriptionOf(order).status)
    }

    @Test
    fun `ctx payments finds a provider's own subscription by its gateway id`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alex")
        val order = buy(sub, caller)
        val end = w.clock.now() + 30 * 86_400_000L

        succeed(order, subscription = gateway("sub_gw_1", periodStart = w.clock.now(), periodEnd = end))

        val row = subscriptionOf(order)
        val own = AttemptLookup("fake", w.payments, w.orders, sw.cipher, w.subscriptions) { w.pool }.subscriptionByGatewayId("sub_gw_1")
        val foreign = AttemptLookup("other", w.payments, w.orders, sw.cipher, w.subscriptions) { w.pool }.subscriptionByGatewayId("sub_gw_1")
        val without = AttemptLookup("fake", w.payments, w.orders, sw.cipher) { w.pool }.subscriptionByGatewayId("sub_gw_1")

        assertNotNull(own)
        assertEquals(row.id, own!!.id)
        assertEquals("ACTIVE", own.status)
        assertEquals("sub_gw_1", own.gatewaySubscriptionId)
        assertEquals("cus_sub_gw_1", own.gatewayCustomerId)
        assertEquals(Money(row.price, "EUR"), own.price)
        assertEquals(IntervalUnit.MONTH, own.intervalUnit)
        assertEquals(end, own.currentPeriodEnd)
        assertEquals("plan_1", own.providerData!!.getString("plan"))
        assertNull(foreign)
        assertNull(without)
    }

    // ==================================================================================== 09 section 8.5

    @Test
    fun `a payment for a renewal of a closed subscription goes to review as LATE and queues the remote cancel`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (alex, caller) = user("Alex")
        val initial = buy(sub, caller)

        succeed(initial, subscription = gateway("sub_gw_1"))

        val renewal = sw.dw.place(user = alex, actions = emptyList(), status = OrderStatus.PENDING, reservation = ReservationState.HELD, source = OrderSource.RENEWAL, subscriptionId = initial.subscriptionId)
        val guard = SubscriptionClosedGuard { sw.subs }

        suspend fun verdict(): Pair<PaidDiversion?, ReviewReason?> = sw.db.txRestartingOnOrderChange { conn ->
            sw.locks.forOrder(conn, renewal.order.id, OrderLockScope.COMMIT) { locked -> guard.divert(conn, locked, locked.order) to sw.subs.reviewReasonFor(conn, locked.order) }
        }

        // a running subscription: the renewal is paid normally
        assertEquals(null to null, verdict())
        // an order that is not a renewal never meets the rule, closed or not
        sql("UPDATE `pano_market_subscription` SET `status` = 'EXPIRED', `endReason` = 'PAYMENT_FAILED', `endedAt` = ? WHERE `id` = ?", w.clock.now(), initial.subscriptionId!!)
        assertNull(sw.db.tx { conn -> sw.subs.reviewReasonFor(conn, order(initial.id)) })

        val (diversion, reason) = verdict()

        assertEquals(ReviewReason.LATE, reason)
        assertEquals(ReviewReason.LATE, diversion!!.reason)
        assertEquals(SubscriptionClosedGuard.NOTE, diversion.note)

        subscriptionOf(initial).let {
            assertEquals(RemoteCancelState.PENDING, it.remoteCancelState, "the gateway must stop billing")
            assertEquals(0, it.remoteCancelAttempts)
        }
    }

    // ==================================================================================== store webhooks

    @Test
    fun `an endpoint that listens gets subscription started and expired as delivery rows, once each`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        fx.webhookEndpoint(events = "[\"subscription.started\", \"subscription.expired\"]")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alex")
        val order = buy(sub, caller)

        succeed(order, subscription = gateway("sub_gw_1"))
        w.clock.advance(40 * 86_400_000L)
        gatewayEvent("sub_gw_1", GatewaySubscriptionStatus.CANCELLED)

        val row = subscriptionOf(order)
        val rows = sql("SELECT `event`, `eventId`, `orderId`, `body` FROM `pano_market_webhook_delivery` ORDER BY `id`")

        assertEquals(listOf("subscription.started", "subscription.expired"), rows.map { it.getString("event") })
        assertEquals(2, rows.map { it.getString("eventId") }.toSet().size)
        assertTrue(rows.all { it.getLong("orderId") == order.id })

        val started = JsonObject(rows[0].getString("body"))

        assertEquals("subscription.started", started.getString("event"))
        assertEquals(row.id, started.getJsonObject("data").getJsonObject("subscription").getLong("id"))
        assertEquals(row.productName, started.getJsonObject("data").getJsonObject("subscription").getString("productName"))
        assertEquals(order(order.id).publicId, started.getJsonObject("data").getJsonObject("order").getString("publicId"))

        // a replayed status event emits nothing more
        gatewayEvent("sub_gw_1", GatewaySubscriptionStatus.CANCELLED)
        assertEquals(2, count("market_webhook_delivery"))
    }

    // ==================================================================================== the composition roots

    @Test
    fun `the composition roots fill every seam of the subscription service, the source says so`() {
        val root = java.io.File("src/main/kotlin/com/panomc/plugins/market")
        val orders = root.resolve("routes/api/order/OrderRouteSupport.kt").readText()
        val inbound = root.resolve("routes/api/payment/InboundWiring.kt").readText()

        assertTrue("subscriptions = subscriptionService(plugin)" in orders, "OrderService creates the pending row through the service")
        assertTrue("SubscriptionEffects({ subscriptionService(plugin) }" in orders, "O2 / O4 / O5 reach the service")
        assertTrue("SubscriptionClosedGuard { subscriptionService(plugin) }" in orders, "a late renewal of a closed subscription goes to review")
        assertTrue("subscriptionHooks = subscriptionService(plugin)" in orders, "PaymentService carries the plan and records the gateway data")
        assertTrue("SubscriptionEventSink(db, { subscriptionService(plugin) }, paymentEventSink)" in inbound, "SubscriptionUpdated is applied by the service, the rest goes on")
        assertTrue("AttemptLookup(provider.id, payments, orders, wiring.cipher, subscriptions)" in inbound, "ctx.payments finds the provider's own subscriptions")
    }

    // ==================================================================================== the lock order (00 section 8.3)

    /** Whether another connection cannot take the row lock of `pano_<table>` row [id] right now (`FOR UPDATE NOWAIT`). */
    private suspend fun lockedByOthers(table: String, id: Long): Boolean {
        val conn = pool.connection.coAwait()
        val tx = conn.begin().coAwait()
        var errorCode: Int? = null
        var failure: Throwable? = null

        runCatching { conn.query("SELECT `id` FROM `pano_$table` WHERE `id` = $id FOR UPDATE NOWAIT").execute().coAwait() }
            .onFailure { if (it is MySQLException) errorCode = it.errorCode else failure = it }

        runCatching { tx.rollback().coAwait() }
        conn.close().coAwait()

        failure?.let { throw it }

        val code = errorCode ?: return false

        assertTrue(code == 1205 || code == 3572, "unexpected error $code")

        return true
    }

    @Test
    fun `the lock order of a subscription order is credit account, subscription, order`(): Unit = runBlocking {
        val sub = fx.product(
            slug = "credit-monthly", price = 600, creditPrice = 600, actions = actions,
            columns = mapOf("billingMode" to "SUBSCRIPTION", "periodUnit" to "MONTH", "periodCount" to 1)
        )

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (alex, caller) = user("Alex")

        fx.credit(alex, 100_000)

        // paying with credits: the subscription is MANUAL (credits are never deducted automatically) and the order holds credits
        val result = h.checkout(h.body("items" to listOf(h.line(sub)), "payWithCredits" to true), caller = caller)
        val order = w.orders.getByPublicId(result.order.getString("publicId"), pool)!!

        assertTrue(order.creditAmount > 0, "the order holds credits")
        assertEquals(SubscriptionMode.MANUAL, subscriptionOf(order).mode)

        val subscriptionId = order.subscriptionId!!
        val hold = w.creditAccounts.getBySystemKey(com.panomc.plugins.market.db.model.CreditSystemKey.HOLD, pool)!!.id

        // [lock] is taken by an open transaction; [work] starts behind it, [probe] looks at what the waiting transaction holds meanwhile
        suspend fun waitingBehind(lock: suspend (SqlConnection) -> Unit, work: suspend () -> Long, probe: suspend (Deferred<Long>) -> Unit): Long = coroutineScope {
            val conn = pool.connection.coAwait()
            val tx = conn.begin().coAwait()

            try {
                lock(conn)

                val waiting = async { work() }

                delay(600)
                probe(waiting)
                tx.commit().coAwait()

                waiting.await()
            } catch (e: Throwable) {
                runCatching { tx.rollback().coAwait() }

                throw e
            } finally {
                conn.close().coAwait()
            }
        }

        // 1. somebody holds the credit account: the transition of O2 waits there, before it touches the subscription or the order
        val first = waitingBehind(
            { conn -> sw.locks.creditAccounts(conn, listOf(alex.accountId)) },
            { sw.db.tx { conn -> sw.locks.forOrder(conn, order.id, OrderLockScope.COMMIT) { it.order.id } } },
            { waiting ->
                assertTrue(waiting.isActive, "it waits for the credit account")
                assertFalse(lockedByOthers("market_subscription", subscriptionId), "the subscription row is not locked before the credit account")
                assertFalse(lockedByOthers("market_order", order.id), "the order row is not locked before the credit account")
            }
        )

        assertEquals(order.id, first)

        // 2. somebody holds the subscription row: the transition already holds the credit accounts and waits before the order
        val second = waitingBehind(
            { conn -> sw.locks.subscription(conn, subscriptionId) },
            { sw.db.tx { conn -> sw.locks.forOrder(conn, order.id, OrderLockScope.COMMIT) { it.order.id } } },
            { waiting ->
                assertTrue(waiting.isActive, "it waits for the subscription row")
                assertTrue(lockedByOthers("market_credit_account", alex.accountId), "the payer's account is already locked")
                assertTrue(lockedByOthers("market_credit_account", hold), "so is HOLD")
                assertFalse(lockedByOthers("market_order", order.id), "the order row comes after the subscription row")
            }
        )

        assertEquals(order.id, second)

        // 3. the status event of a gateway (subscription, then order) waits at the subscription row as well
        val third = waitingBehind(
            { conn -> sw.locks.subscription(conn, subscriptionId) },
            { sw.db.tx { conn -> sw.locks.orderWithSubscription(conn, order.id) { it.order.id } } },
            { waiting ->
                assertTrue(waiting.isActive, "it waits for the subscription row")
                assertFalse(lockedByOthers("market_order", order.id), "the order row comes after the subscription row")
            }
        )

        assertEquals(order.id, third)
    }
}
