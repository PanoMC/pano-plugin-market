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
import com.panomc.plugins.market.spi.payment.GatewaySubscriptionState
import com.panomc.plugins.market.spi.payment.GatewaySubscriptionStatus
import com.panomc.plugins.market.spi.payment.IntervalUnit
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentTarget
import com.panomc.plugins.market.spi.payment.RecurringSupport
import com.panomc.plugins.market.spi.payment.ReviewReason
import com.panomc.plugins.market.spi.payment.StartPaymentRequest
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
    val lookup = StaticProviderLookup(listOf(ContinuableFake(h.fake), FreeProvider(), CreditsProvider(), BankTransferProvider()))

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
        attempt: MarketPayment? = null
    ): AppliedEvent {
        val a = attempt ?: attemptOf(order)
        val event = PaymentEvent.Succeeded(PaymentTarget.Attempt(a.id), Money(amount ?: a.amount, a.currency)).also {
            it.subscription = subscription
            it.storedMethod = stored
            it.methodDetail = "Visa 4242"
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

    @Test
    fun `a method change on a retry rewrites provider, mode and price of the pending row and nothing else once it is active`(): Unit = runBlocking {
        val sub = subProduct()

        fx.paymentMethod("fake")
        fx.paymentMethod("bank-transfer")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alice")
        val order = buy(sub, caller)

        assertEquals(SubscriptionMode.GATEWAY, subscriptionOf(order).mode)
        // the gateway data of the first method goes with it
        sql("UPDATE `pano_market_subscription` SET `gatewaySubscriptionId` = 'sub_old', `gatewayCustomerId` = 'cus_old' WHERE `id` = ?", order.subscriptionId!!)
        sql("UPDATE `pano_market_order` SET `totalPrice` = 720, `gatewayAmount` = 720, `paymentMethodId` = 'bank-transfer' WHERE `id` = ?", order.id)

        assertTrue(sw.db.tx { conn -> sw.subs.onMethodChanged(conn, order(order.id), "bank-transfer", null) })

        subscriptionOf(order).let {
            assertEquals("bank-transfer", it.providerId)
            assertEquals(SubscriptionMode.MANUAL, it.mode, "bank transfer has no recurring billing: the fallback sells it as a one-off")
            assertEquals(720, it.price)
            assertNull(it.gatewaySubscriptionId)
            assertNull(it.gatewayCustomerId)
            assertEquals(SubscriptionStatus.PENDING, it.status)
        }

        // back to the gateway: the verdict of the checkout says AUTO (and the order is as it was placed again)
        sql("UPDATE `pano_market_order` SET `paymentMethodId` = 'fake', `totalPrice` = ?, `gatewayAmount` = ? WHERE `id` = ?", order.totalPrice, order.gatewayAmount, order.id)
        assertTrue(sw.db.tx { conn -> sw.subs.onMethodChanged(conn, order(order.id), "fake", "AUTO") })
        assertEquals(SubscriptionMode.GATEWAY, subscriptionOf(order).mode)

        // without the fallback a method that cannot bill the plan is refused, and the row stays as it was; the built-in methods are always manual
        h.config = h.config.copy(subscriptionManualFallback = false)
        sw.caps(RecurringSupport.NONE)
        assertEquals("RECURRING_NOT_SUPPORTED", expect("PAYMENT_METHOD_UNAVAILABLE", 400) { sw.db.tx { conn -> sw.subs.onMethodChanged(conn, order(order.id), "fake", null) } }.getString("reason"))
        assertEquals("fake", subscriptionOf(order).providerId)
        assertEquals(SubscriptionMode.GATEWAY, subscriptionOf(order).mode)
        assertTrue(sw.db.tx { conn -> sw.subs.onMethodChanged(conn, order(order.id), "bank-transfer", null) })
        assertEquals(SubscriptionMode.MANUAL, subscriptionOf(order).mode)
        sw.caps(RecurringSupport.GATEWAY_MANAGED)
        assertTrue(sw.db.tx { conn -> sw.subs.onMethodChanged(conn, order(order.id), "fake", "AUTO") })

        // active: the method of a running subscription is not rewritten by an order of the past
        val (_, other) = user("Bea")
        val paid = buy(sub, other)

        succeed(paid, subscription = gateway("sub_1"))
        assertEquals(SubscriptionStatus.ACTIVE, subscriptionOf(paid).status)
        assertFalse(sw.db.tx { conn -> sw.subs.onMethodChanged(conn, order(paid.id), "bank-transfer", "MANUAL") })
        assertEquals("fake", subscriptionOf(paid).providerId)
        assertEquals(SubscriptionMode.GATEWAY, subscriptionOf(paid).mode)
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
