package com.panomc.plugins.market.service

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.delivery.ProductAction
import com.panomc.plugins.market.core.order.OrderEvent
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.DeliveryActionType
import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.MarketSubscription
import com.panomc.plugins.market.db.model.MarketSubscriptionRenewal
import com.panomc.plugins.market.db.model.OrderActorType
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.db.model.PaymentFeeMode
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.db.model.RefundOrigin
import com.panomc.plugins.market.db.model.RefundStatus
import com.panomc.plugins.market.db.model.RemoteCancelState
import com.panomc.plugins.market.db.model.RenewalStatus
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.db.model.SubscriptionMode
import com.panomc.plugins.market.db.model.SubscriptionStatus
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.job.SubscriptionJob
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.payment.GatewaySubscriptionState
import com.panomc.plugins.market.spi.payment.GatewaySubscriptionStatus
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentTarget
import com.panomc.plugins.market.spi.payment.RecurringChargeRequest
import com.panomc.plugins.market.spi.payment.RecurringChargeResult
import com.panomc.plugins.market.spi.payment.RecurringSupport
import com.panomc.plugins.market.spi.payment.RefundSupport
import com.panomc.plugins.market.spi.payment.StoredPaymentMethod
import com.panomc.plugins.market.support.FakePaymentProvider
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
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
import com.panomc.plugins.market.util.StoreLinks
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * The renewal side of the object graph (MK-122): the real services of [SubscriptionWorld], rebuilt with the seams of the renewal slice filled in the way the
 * composition root fills them: random ids for the renewal orders and attempts, the order service for the cancel of an unpaid renewal order of an ended
 * subscription, the payment service behind the gateway events and the clock steps ([SubscriptionJob]).
 */
internal class RenewalWorld(val sw: SubscriptionWorld, val w: TestWiring, val vertx: Vertx) {
    lateinit var subs: SubscriptionService
        private set

    lateinit var orderService: OrderService
        private set

    lateinit var payments: PaymentService
        private set

    lateinit var sink: SubscriptionEventSink
        private set

    lateinit var job: SubscriptionJob
        private set

    /** The bank transfer flow of the buyer's notice and the admin's approval, on the payment service of this world (09 section 16 test 49). */
    lateinit var bank: BankTransferService
        private set

    /** Who counts as blocked for the charge of a merchant subscription (09 section 8.3); read when the world is built, so a test sets it and calls [build]. */
    @Volatile
    var blocks: BuyerBlocks = BuyerBlocks.NONE

    /** The settings the checkout harness's `Cfg` does not carry; every service of this world reads them at call time. */
    @Volatile
    var autoRefundDuplicatePayments = true

    @Volatile
    var subscriptionReminderDays = 3

    /** The store switches test mode on (the checkout harness stays live, so what was bought before stays a live purchase). */
    @Volatile
    var storeTestMode = false

    private fun settings(): MarketConfig {
        val c = sw.h.config

        return MarketConfig(
            currency = "EUR", vatPercent = 20.0, showVatInPrice = c.showVatInPrice, creditValue = 1.0, storeTimeZone = "UTC", allowGuestCheckout = c.allowGuestCheckout,
            allowGiftPurchase = c.allowGiftPurchase, minimumOrderAmount = c.minimumOrderAmount, creditsEnabled = c.creditsEnabled, allowMixedCreditPayment = c.allowMixedCreditPayment,
            onlyAcceptCredits = c.onlyAcceptCredits, testMode = c.testMode || storeTestMode, billingInfoMode = c.billingInfoMode, legalTextRequired = c.legalTextRequired,
            creditTopUpEnabled = c.creditTopUpEnabled, creditTopUpFreeAmount = c.creditTopUpFreeAmount, creditTopUpMin = c.creditTopUpMin, creditTopUpMax = c.creditTopUpMax,
            cashbackPercent = c.cashbackPercent, creditName = c.creditName, checkoutRateLimitPerMinute = c.checkoutRateLimitPerMinute, currencyMode = c.currencyMode,
            additionalCurrencies = c.additionalCurrencies, subscriptionManualFallback = c.subscriptionManualFallback, subscriptionGraceDays = c.subscriptionGraceDays,
            subscriptionReminderDays = subscriptionReminderDays, autoRefundDuplicatePayments = autoRefundDuplicatePayments
        )
    }

    init {
        build()
    }

    fun build() {
        val h = sw.h
        val config = { settings() }

        subs = SubscriptionService(
            clock = w.clock, config = config, locks = sw.locks, subscriptions = w.subscriptions, renewals = w.subscriptionRenewals, orders = w.orders,
            orderItems = w.orderItems, orderEvents = w.orderEvents, payments = w.payments, products = w.products, variants = w.variants, entitlements = w.entitlements,
            cipher = sw.cipher, capabilities = { providerId, sql -> payments.capabilitiesOf(providerId, sql) }, deliveries = sw.dw.service,
            mail = MailOutboxService(config, w.clock, w.mailOutbox, w.orderEvents),
            webhooks = SubscriptionWebhooks { conn, event, key, orderId, data, testMode ->
                sw.hooks += SubscriptionWorld.Hook(event, key, orderId, data, testMode)

                sw.webhookRows.service.emit(conn, event, key, orderId, data, testMode)
            },
            ids = w.ids, blocks = blocks, orderService = { orderService }, links = StoreLinks.ofBase("https://shop.example")
        )

        val redemptions = RedemptionService(w.clock, sw.locks, w.redemptions)

        orderService = OrderService(
            w.clock, w.ids, w.orders, w.orderItems, w.orderEvents, w.payments, redemptions, { _, _ -> false },
            subscriptions = subs, reservations = ReservationService(w.clock, sw.locks, redemptions, w.orders), settlement = sw.ledger,
            foreign = SubscriptionEffects({ subs }, DeliveryEffects(sw.dw.entitlementService, sw.dw.service, w.orders, sw.effects)),
            rates = { sqlClient -> w.currencyRates.getAll(sqlClient).filter { it.rate.signum() > 0 }.associate { it.currency to it.rate } },
            statsCurrency = { "EUR" },
            limits = ProductPurchaseLimits(w.orders, w.products, w.entitlements, w.clock), refunds = w.refunds
        )
        payments = PaymentService(
            db = sw.db, locks = sw.locks, clock = w.clock, ids = w.ids, config = config, orders = w.orders, orderItems = w.orderItems, orderEvents = w.orderEvents,
            payments = w.payments, methods = w.paymentMethods, creditAccounts = w.creditAccounts, currencyRates = w.currencyRates, lookup = sw.lookup, cipher = sw.cipher,
            contexts = PaymentContexts { provider, settings, testMode -> com.panomc.plugins.market.spi.testkit.TestContexts.payment(provider.id, settings, vertx, testMode) },
            orderService = orderService, site = { com.panomc.plugins.market.spi.testkit.TestContexts.defaultSite() }, readClient = { w.pool }, products = w.products,
            entitlements = w.entitlements, extraPaidGuards = listOf(SubscriptionClosedGuard { subs }), subscriptionHooks = subs
        )

        h.useStarter(payments)
        h.pendingSubscriptions = subs

        bank = BankTransferService(sw.db, sw.locks, w.clock, w.orders, w.payments, w.orderEvents, payments, { false }, { false }, { w.pool })
        sink = SubscriptionEventSink(sw.db, { subs }).withPayments { payments }
        job = SubscriptionJob(w.clock, sw.db, subs, w.subscriptions, payments, config, { w.pool }, sink)
    }
}

/** What the renewal tests share: the world, the catalogue and the helpers to buy, pay and look at a subscription. */
internal abstract class RenewalITBase : MarketDaoITBase() {
    protected lateinit var w: TestWiring
    protected lateinit var sw: SubscriptionWorld
    protected lateinit var rw: RenewalWorld
    protected val vertx: Vertx = Vertx.vertx()

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    @BeforeEach
    fun freshState() {
        runBlocking { resetState() }
        w = TestWiring(pool)
        sw = SubscriptionWorld(w, vertx)
        rw = RenewalWorld(sw, w, vertx)
    }

    protected val fx get() = w.fixtures
    protected val h get() = sw.h
    protected val fake get() = sw.fake
    protected val day = 86_400_000L

    private var unique = 0

    private val actions = JsonArray(
        listOf(
            ProductAction("grant", DeliveryActionType.PERMISSION, nodes = listOf("group.vip")),
            ProductAction("renew", DeliveryActionType.PERMISSION, phase = DeliveryPhase.RENEW, nodes = listOf("group.vip")),
            ProductAction("expire", DeliveryActionType.COMMAND, phase = DeliveryPhase.EXPIRE, commands = listOf("say {username} subscription ended"))
        ).map { it.toJson() }
    ).encode()

    protected suspend fun subProduct(price: Long = 600, maxCycles: Int? = null): MarketProduct = fx.product(
        slug = "monthly-${++unique}", price = price, actions = actions,
        columns = mapOf("billingMode" to "SUBSCRIPTION", "periodUnit" to "MONTH", "periodCount" to 1, "subscriptionMaxCycles" to maxCycles)
    )

    protected suspend fun user(name: String = "Alex"): Pair<TestUser, QuoteCaller> {
        val u = fx.user(name)

        h.emails[u.id] = "${name.lowercase()}@example.com"

        return u to QuoteCaller(u.id)
    }

    protected suspend fun buy(product: MarketProduct, caller: QuoteCaller, method: String = "fake"): MarketOrder {
        val result = h.checkout(h.body("items" to listOf(h.line(product, 1, 0)), "paymentMethodId" to method), caller = caller)

        return w.orders.getByPublicId(result.order.getString("publicId"), pool)!!
    }

    protected suspend fun order(id: Long): MarketOrder = w.orders.getById(id, pool)!!

    protected suspend fun subscription(id: Long): MarketSubscription = w.subscriptions.getById(id, pool)!!

    protected suspend fun renewals(subscriptionId: Long): List<MarketSubscriptionRenewal> = w.subscriptionRenewals.getBySubscriptionId(subscriptionId, pool)

    protected suspend fun attempts(orderId: Long): List<MarketPayment> = w.payments.getByOrderId(orderId, pool)

    protected suspend fun events(orderId: Long, type: OrderEventType) = w.orderEvents.getByOrderId(orderId, pool).filter { it.type == type }

    protected suspend fun mails(kind: String): Long = count("market_mail_outbox", "`kind` = '$kind'")

    protected fun oneMonthAfter(ms: Long): Long = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC).plusMonths(1).toInstant().toEpochMilli()

    protected fun hooks(event: String) = sw.hooks.filter { it.event == event }

    /** The success of an attempt, through the payment service (the first period of a subscription, or a renewal paid by hand). */
    protected suspend fun succeed(order: MarketOrder, stored: StoredPaymentMethod? = null, subscription: GatewaySubscriptionState? = null, amount: Long? = null): AppliedEvent {
        val a = attempts(order.id).last()
        val event = PaymentEvent.Succeeded(PaymentTarget.Attempt(a.id), Money(amount ?: a.amount, a.currency)).also {
            it.subscription = subscription
            it.storedMethod = stored
            it.methodDetail = "Visa 4242"
        }

        return rw.payments.applyEvent(order.id, a.id, PaymentEventMapper.attemptEvent(event)!!, AttemptFacts.of(event, sw.cipher))
    }

    protected fun gatewayState(id: String, status: GatewaySubscriptionStatus = GatewaySubscriptionStatus.ACTIVE, periodStart: Long? = null, periodEnd: Long? = null) =
        GatewaySubscriptionState(id, status).also {
            it.gatewayCustomerId = "cus_$id"
            it.currentPeriodStart = periodStart
            it.currentPeriodEnd = periodEnd
        }

    /** A merchant subscription that is `ACTIVE` with a stored card (the offer is `MERCHANT_INITIATED`). */
    protected class Active(val user: TestUser, val caller: QuoteCaller, val product: MarketProduct, val order: MarketOrder, val sub: MarketSubscription)

    protected suspend fun activeMerchant(price: Long = 600, maxCycles: Int? = null, name: String = "Alex", cardExpiresInDays: Long = 400): Active {
        val product = subProduct(price, maxCycles)

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.MERCHANT_INITIATED)

        val (user, caller) = user(name)
        val first = buy(product, caller)

        succeed(first, stored = StoredPaymentMethod("tok_1").also { it.label = "Visa 4242"; it.expiresAt = w.clock.now() + cardExpiresInDays * day; it.gatewayCustomerId = "cus_1" })

        return Active(user, caller, product, order(first.id), subscription(first.subscriptionId!!))
    }

    /** A `MANUAL` subscription: the provider cannot bill again, the fallback is on. */
    protected suspend fun activeManual(price: Long = 600, name: String = "Alex"): Active {
        val product = subProduct(price)

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.NONE)

        val (user, caller) = user(name)
        val first = buy(product, caller)

        succeed(first)

        return Active(user, caller, product, order(first.id), subscription(first.subscriptionId!!))
    }

    /** `chargeRecurring` answers with a plain success of the amount asked for (what a token gateway does, 09 section 16 test 34). */
    protected fun chargeSucceeds(detail: String = "Visa 4242") {
        fake.onChargeRecurring = { request ->
            RecurringChargeResult(listOf(PaymentEvent.Succeeded(PaymentTarget.Attempt(request.attempt.id), request.amount).also { it.methodDetail = detail }))
        }
    }

    protected fun charges() = fake.calls(FakePaymentProvider.Op.CHARGE_RECURRING).map { it.request as RecurringChargeRequest }

    /** The renewal order of a subscription's [index]-th renewal row. */
    protected suspend fun renewalOrderOf(subscriptionId: Long, index: Int = 1): MarketOrder = order(renewals(subscriptionId).first { it.periodIndex == index }.orderId!!)

    protected suspend fun expire(order: MarketOrder) {
        w.clock.set(order.expiresAt!!)
        sw.db.txRestartingOnOrderChange { conn ->
            sw.locks.forOrder(conn, order.id, OrderLockScope.RELEASE) { locked -> rw.orderService.transition(conn, locked, OrderEvent.Expire(w.clock.now())) }
        }
    }
}

/**
 * Renewal orders, merchant charges and the gateway renewal on a real MariaDB (MK-122; 09 sections 8, 9 and 12; tests 29 to 51, 74 to 76 and 81 of 09 section 16):
 * the renewal order is the frozen price with the fee of the first order, reserves nothing and holds no credits (I22); a paid renewal moves the period, the mode and
 * the next charge and plans the `RENEW` rows for the subscription's own entitlement; the same period renewed twice is one row and one order (R-25); a payment for an
 * ended subscription waits in review (LATE); a gateway failure is recorded once per attempt count. The invariants I1 to I22 are checked after every test.
 */
internal class SubscriptionRenewalIT : RenewalITBase() {
    // ==================================================================================== the renewal order

    @Test
    fun `the renewal order of a merchant charge is the frozen price, reserves nothing and holds no credits, I22`(): Unit = runBlocking {
        val a = activeMerchant(price = 600)
        val soldBefore = w.products.getById(a.product.id, pool)!!.soldCount

        w.clock.set(a.sub.nextChargeAt!!)

        val prepared = sw.db.txRestartingOnOrderChange { conn -> rw.subs.prepareCharge(conn, a.sub.id, SubscriptionService.ProviderFacts(true, false)) }
        val charge = prepared as SubscriptionService.ChargePreparation.Charge
        val renewal = renewals(a.sub.id).single()
        val renewalOrder = order(charge.order.id)

        // the row of the period, the order and the attempt are one intent
        assertEquals(1, renewal.periodIndex)
        assertEquals(RenewalStatus.PENDING, renewal.status)
        assertEquals(a.sub.currentPeriodEnd, renewal.periodStart)
        assertEquals(oneMonthAfter(a.sub.currentPeriodEnd!!), renewal.periodEnd)
        assertEquals(renewalOrder.id, renewal.orderId)
        assertEquals(1, renewal.attempts)
        assertEquals(charge.attempt.id, renewal.paymentId)
        assertEquals(w.clock.now() + SubscriptionService.CHARGE_LEASE_MS, renewal.nextAttemptAt, "the lease")
        assertEquals(renewal.nextAttemptAt, subscription(a.sub.id).nextChargeAt, "the lease is on the subscription too")
        assertEquals("sub-${a.sub.id}-1-1", charge.idempotencyKey)

        // 09 section 8.1
        assertEquals(OrderSource.RENEWAL, renewalOrder.source)
        assertEquals(OrderStatus.PENDING, renewalOrder.status)
        assertEquals(a.sub.id, renewalOrder.subscriptionId)
        assertEquals(a.order.userId, renewalOrder.userId)
        assertEquals(a.order.buyerKey, renewalOrder.buyerKey)
        assertEquals("renewal:${renewal.id}:1", renewalOrder.idempotencyKey)
        assertEquals(a.sub.price, renewalOrder.totalPrice, "the price is frozen")
        assertEquals(0, renewalOrder.creditAmount)
        assertEquals(0, renewalOrder.creditValue)
        assertEquals(renewalOrder.totalPrice, renewalOrder.gatewayAmount)
        assertEquals(ReservationState.HELD, renewalOrder.reservationState)
        assertNull(renewalOrder.couponId)
        assertNull(renewalOrder.creatorCodeId)
        assertFalse(renewalOrder.isGift)
        assertEquals("fake", renewalOrder.paymentMethodId)
        assertEquals((a.sub.price - 0), w.orderItems.getByOrderIds(listOf(renewalOrder.id), pool).single().lineTotal)
        assertEquals(w.clock.now() + (3 + 8) * day, renewalOrder.expiresAt, "grace plus eight days from the charge")

        val item = w.orderItems.getByOrderIds(listOf(renewalOrder.id), pool).single()

        assertEquals(1, item.quantity)
        assertEquals(0, item.stockReserved)
        assertEquals(a.product.id, item.productId)
        assertEquals(a.sub.productName, item.productName)

        // the attempt asks for exactly that
        assertEquals(PaymentStatus.CREATED, charge.attempt.status)
        assertEquals(a.sub.id, charge.attempt.subscriptionId)
        assertEquals(renewalOrder.gatewayAmount, charge.attempt.amount)
        assertEquals(0, charge.attempt.creditAmount)

        // no stock, no limit, no counter moved
        assertEquals(soldBefore, w.products.getById(a.product.id, pool)!!.soldCount)
        assertEquals(0, count("market_redemption", "`orderId` = ${renewalOrder.id}"))
    }

    @Test
    fun `a renewal after a VAT change charges the frozen total and completes without review, 76`(): Unit = runBlocking {
        val a = activeMerchant(price = 600)

        // the store changes the VAT of the product after the subscription began: only the split may change
        sql("UPDATE `pano_market_product` SET `vatPercent` = 800 WHERE `id` = ?", a.product.id)
        chargeSucceeds()
        w.clock.set(a.sub.nextChargeAt!!)
        rw.job.runOnce()

        val renewalOrder = renewalOrderOf(a.sub.id)
        val item = w.orderItems.getByOrderIds(listOf(renewalOrder.id), pool).single()

        assertEquals(OrderStatus.COMPLETED, renewalOrder.status, "no REVIEW: the amount is the frozen one")
        assertEquals(a.sub.price, renewalOrder.totalPrice)
        assertEquals(800, item.vatPercent, "the split follows the current rate")
        assertEquals(600 * 800 / 10800, item.vatAmount)
        assertEquals(renewalOrder.totalPrice, charges().single().amount.amount)
    }

    // ==================================================================================== a paid renewal (09 section 8.4)

    @Test
    fun `a paid renewal moves the period, keeps the card, plans RENEW rows for the subscription's entitlement and creates no second entitlement`(): Unit = runBlocking {
        val a = activeMerchant()
        val entitlement = w.entitlements.getByOrderItemId(a.sub.initialOrderItemId, pool).single()

        chargeSucceeds()
        w.clock.set(a.sub.nextChargeAt!!)
        rw.job.runOnce()

        val row = subscription(a.sub.id)
        val renewal = renewals(a.sub.id).single()
        val renewalOrder = order(renewal.orderId!!)

        assertEquals(RenewalStatus.PAID, renewal.status)
        assertNull(renewal.nextAttemptAt)
        assertEquals(renewal.paymentId, renewalOrder.paymentId)
        assertEquals(OrderStatus.COMPLETED, renewalOrder.status)
        assertEquals(2, row.cycleCount)
        assertEquals(a.sub.currentPeriodEnd, row.currentPeriodStart)
        assertEquals(oneMonthAfter(a.sub.currentPeriodEnd!!), row.currentPeriodEnd)
        assertEquals(SubscriptionStatus.ACTIVE, row.status)
        assertEquals(SubscriptionMode.MERCHANT, row.mode, "a plain success of the market's own charge keeps the card")
        assertEquals(row.currentPeriodEnd, row.nextChargeAt, "the next charge is scheduled")
        assertNull(row.graceEndsAt)
        assertEquals(0, row.failCount)
        assertNotNull(row.storedMethod)

        // no second entitlement: the one of the first order runs on, the RENEW rows belong to it
        assertEquals(1, w.entitlements.getByOwnerAndProduct(a.order.buyerKey, a.product.id, pool).size)

        val rows = sw.dw.rows(renewalOrder.id)

        assertTrue(rows.isNotEmpty() && rows.all { it.phase == DeliveryPhase.RENEW }, rows.map { it.phase }.toString())
        assertTrue(rows.all { it.entitlementId == entitlement.id && it.subscriptionId == a.sub.id }, "the rows name the subscription's entitlement")

        // the timeline of the renewal order and the store webhook, once
        assertEquals(1, events(renewalOrder.id, OrderEventType.SUBSCRIPTION_RENEWED).size)

        val renewed = hooks("subscription.renewed").single()

        assertEquals("sub:${a.sub.id}:renewed:1", renewed.key)
        assertEquals(renewalOrder.id, renewed.orderId)
        assertEquals(renewalOrder.publicId, renewed.data.getJsonObject("order").getString("publicId"))
        assertEquals(2, renewed.data.getJsonObject("subscription").getInteger("cycleCount"))
    }

    @Test
    fun `a finite plan stops charging after its last paid period`(): Unit = runBlocking {
        val a = activeMerchant(maxCycles = 2)

        chargeSucceeds()
        w.clock.set(a.sub.nextChargeAt!!)
        rw.job.runOnce()

        val row = subscription(a.sub.id)

        assertEquals(2, row.cycleCount)
        assertNull(row.nextChargeAt, "cycleCount reached maxCycles: no further charge is scheduled")

        w.clock.set(row.currentPeriodEnd!! + 1)
        rw.job.runOnce()

        assertEquals(1, charges().size, "no second charge")
        assertEquals(SubscriptionStatus.COMPLETED, subscription(a.sub.id).status)
    }

    // ==================================================================================== R-25: the same renewal twice

    @Test
    fun `a gateway renewal delivered twice, then again with another event key, is one renewal row and one order, R-25`(): Unit = runBlocking {
        val product = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alex")
        val first = buy(product, caller)
        val end = w.clock.now() + 30 * day

        succeed(first, subscription = gatewayState("sub_gw", periodStart = w.clock.now(), periodEnd = end))

        val active = subscription(first.subscriptionId!!)

        assertEquals(SubscriptionMode.GATEWAY, active.mode)

        w.clock.set(end + 60_000)

        fun renewed(txn: String?) = PaymentEvent.SubscriptionRenewed("sub_gw", Money(active.price, active.currency)).also {
            it.gatewayTransactionId = txn
            it.periodStart = end
            it.periodEnd = end + 30 * day
        }

        suspend fun deliver(event: PaymentEvent.SubscriptionRenewed) = rw.sink.apply(event, null, com.panomc.plugins.market.routes.api.payment.InboundEventContext(1, "fake", null, null, w.clock.now()))

        deliver(renewed("txn_1"))
        deliver(renewed("txn_1"))
        deliver(renewed("txn_other"))

        val rows = renewals(active.id)
        val row = subscription(active.id)

        assertEquals(1, rows.size, "uq_sub_period: one row for the period")
        assertEquals(RenewalStatus.PAID, rows.single().status)
        assertEquals(1, count("market_order", "`source` = 'RENEWAL'"), "one renewal order")
        assertEquals(2, row.cycleCount)
        assertEquals(end, row.currentPeriodStart)
        assertEquals(end + 30 * day, row.currentPeriodEnd)
        assertEquals(1, hooks("subscription.renewed").size)

        val renewalOrder = order(rows.single().orderId!!)
        val attempt = attempts(renewalOrder.id).single()

        assertEquals(OrderStatus.COMPLETED, renewalOrder.status)
        assertEquals(PaymentStatus.SUCCEEDED, attempt.status)
        assertEquals("txn_1", attempt.gatewayTransactionId)
        assertEquals(0, renewalOrder.creditAmount)
    }

    @Test
    fun `a gateway renewal of an unknown or pending subscription is skipped and an underpaid one waits in review without moving the period`(): Unit = runBlocking {
        val product = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alex")
        val first = buy(product, caller)
        val pending = subscription(first.subscriptionId!!)

        // a renewal of a subscription the provider does not know is skipped
        val unknown = rw.sw.db.txRestartingOnOrderChange { conn -> rw.subs.onGatewayRenewed(conn, "fake", PaymentEvent.SubscriptionRenewed("sub_nope", Money(600, "EUR"))) }

        assertTrue(unknown is SubscriptionService.RenewedOutcome.UnknownSubscription)

        succeed(first, subscription = gatewayState("sub_gw", periodStart = w.clock.now(), periodEnd = w.clock.now() + 30 * day))

        val active = subscription(pending.id)

        w.clock.set(active.currentPeriodEnd!! + 1000)

        val underpaid = PaymentEvent.SubscriptionRenewed("sub_gw", Money(active.price - 100, active.currency)).also {
            it.gatewayTransactionId = "txn_low"
            it.periodStart = active.currentPeriodEnd
        }

        rw.sink.apply(underpaid, null, com.panomc.plugins.market.routes.api.payment.InboundEventContext(1, "fake", null, null, w.clock.now()))

        val renewal = renewals(active.id).single()
        val renewalOrder = order(renewal.orderId!!)

        assertEquals(OrderStatus.REVIEW, renewalOrder.status, "the amount differs: an admin decides")
        assertEquals(RenewalStatus.PENDING, renewal.status)
        assertEquals(1, subscription(active.id).cycleCount, "the period is not extended until the admin accepts")
        assertEquals(active.currentPeriodEnd, subscription(active.id).currentPeriodEnd)
        assertEquals(0, hooks("subscription.renewed").size)
    }

    // ==================================================================================== 8.5: a payment for a closed subscription

    @Test
    fun `a payment for the renewal order of an ended subscription waits in review as LATE when the provider cannot refund, and the admin cannot accept it`(): Unit = runBlocking {
        val a = activeMerchant()

        // the renewal order of the next period exists and is open when the buyer cancels (the subscription ends)
        w.clock.set(a.sub.nextChargeAt!!)
        sw.db.txRestartingOnOrderChange { conn -> rw.subs.prepareCharge(conn, a.sub.id, SubscriptionService.ProviderFacts(true, false)) }

        val renewalOrder = renewalOrderOf(a.sub.id)

        sql("UPDATE `pano_market_subscription` SET `status` = 'CANCELLED', `endedAt` = ?, `endReason` = 'BUYER_CANCEL', `nextChargeAt` = NULL WHERE `id` = ?", w.clock.now(), a.sub.id)

        // the charge that was in flight succeeds anyway
        val charged = attempts(renewalOrder.id).single()
        val event = PaymentEvent.Succeeded(PaymentTarget.Attempt(charged.id), Money(charged.amount, charged.currency))

        rw.payments.applyEvent(renewalOrder.id, charged.id, PaymentEventMapper.attemptEvent(event)!!, AttemptFacts.of(event, sw.cipher))

        val reviewed = order(renewalOrder.id)

        assertEquals(OrderStatus.REVIEW, reviewed.status)
        assertEquals("LATE", reviewed.reviewReason)
        assertEquals(0, w.refunds.getByOrderId(renewalOrder.id, pool).size, "the provider cannot refund: nothing is requested, the admin decides")
        assertEquals(SubscriptionStatus.CANCELLED, subscription(a.sub.id).status, "the period is never extended")
        assertEquals(1, subscription(a.sub.id).cycleCount)
        assertEquals(RenewalStatus.PENDING, renewals(a.sub.id).single().status)

        // the admin's accept is refused: the subscription is closed, the only decision is to reject
        val refusal = runCatching {
            sw.db.txRestartingOnOrderChange { conn ->
                sw.locks.forOrder(conn, renewalOrder.id, OrderLockScope.RELEASE) { locked -> rw.orderService.transition(conn, locked, OrderEvent.ReviewAccepted(false)) }
            }
        }.exceptionOrNull()

        assertNotNull(refusal, "accepting a renewal of a closed subscription is refused")
        assertEquals("INVALID_ORDER_TRANSITION", (refusal as com.panomc.platform.model.Error).getErrorCode())
        assertEquals(OrderStatus.REVIEW, order(renewalOrder.id).status)
    }

    @Test
    fun `a late payment for a gateway subscription that ended queues the remote cancel again`(): Unit = runBlocking {
        val product = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alex")
        val first = buy(product, caller)
        val end = w.clock.now() + 30 * day

        succeed(first, subscription = gatewayState("sub_gw", periodStart = w.clock.now(), periodEnd = end))

        val id = first.subscriptionId!!

        sql("UPDATE `pano_market_subscription` SET `status` = 'EXPIRED', `endedAt` = ?, `endReason` = 'PAYMENT_FAILED', `remoteCancelState` = 'DONE', `nextQueryAt` = NULL WHERE `id` = ?", w.clock.now(), id)
        w.clock.set(end + 1000)

        val event = PaymentEvent.SubscriptionRenewed("sub_gw", Money(600, "EUR")).also {
            it.gatewayTransactionId = "txn_late"
            it.periodStart = end
        }

        rw.sink.apply(event, null, com.panomc.plugins.market.routes.api.payment.InboundEventContext(1, "fake", null, null, w.clock.now()))

        val renewalOrder = order(renewals(id).single().orderId!!)

        assertEquals(OrderStatus.REVIEW, renewalOrder.status)
        assertEquals("LATE", renewalOrder.reviewReason)
        assertEquals(RemoteCancelState.PENDING, subscription(id).remoteCancelState, "the gateway is still billing a closed subscription: stop it")
        assertEquals(1, subscription(id).cycleCount)
    }

    // ==================================================================================== 8.5 with the automatic refund (test 32)

    /** An ended merchant subscription whose charge was in flight and succeeds anyway: the renewal order (open at the end), its attempt and the money. */
    private suspend fun lateMerchantPayment(a: Active): MarketOrder {
        w.clock.set(a.sub.nextChargeAt!!)
        sw.db.txRestartingOnOrderChange { conn -> rw.subs.prepareCharge(conn, a.sub.id, SubscriptionService.ProviderFacts(true, false)) }

        val renewalOrder = renewalOrderOf(a.sub.id)

        sql("UPDATE `pano_market_subscription` SET `status` = 'CANCELLED', `endedAt` = ?, `endReason` = 'BUYER_CANCEL', `nextChargeAt` = NULL WHERE `id` = ?", w.clock.now(), a.sub.id)

        val charged = attempts(renewalOrder.id).single()
        val event = PaymentEvent.Succeeded(PaymentTarget.Attempt(charged.id), Money(charged.amount, charged.currency))

        rw.payments.applyEvent(renewalOrder.id, charged.id, PaymentEventMapper.attemptEvent(event)!!, AttemptFacts.of(event, sw.cipher))

        return renewalOrder
    }

    @Test
    fun `32 a late payment for the renewal of an ended subscription is refunded at once when the switch is on and the provider can refund`(): Unit = runBlocking {
        val a = activeMerchant()

        sw.caps(RecurringSupport.MERCHANT_INITIATED) { refund = RefundSupport.FULL_ONLY }

        val renewalOrder = lateMerchantPayment(a)
        val done = order(renewalOrder.id)
        val charged = attempts(renewalOrder.id).single()

        assertEquals(OrderStatus.CANCELLED, done.status, "O3 then O5 in the same transaction")

        val refund = w.refunds.getByOrderId(renewalOrder.id, pool).single()

        assertEquals(RefundOrigin.SYSTEM, refund.origin)
        assertEquals(RefundStatus.REQUESTED, refund.status, "the gateway call is the refund service's")
        assertEquals(charged.amount, refund.amount, "exactly the money that arrived")
        assertEquals(charged.id, refund.paymentId)
        assertEquals("fake", refund.providerId)

        val moves = events(renewalOrder.id, OrderEventType.STATUS_CHANGED)

        assertEquals(listOf(OrderStatus.REVIEW.name, OrderStatus.CANCELLED.name), moves.map { it.toStatus })
        assertEquals(OrderActorType.SYSTEM, moves.last().actorType, "the system rejected it, not an admin")
        assertEquals(PaymentService.LATE_REFUND_NOTE, moves.last().message)
        assertEquals(1, events(renewalOrder.id, OrderEventType.REFUND_REQUESTED).size)

        // the subscription stays ended, the period is never extended, nothing was delivered for the renewal
        val row = subscription(a.sub.id)

        assertEquals(SubscriptionStatus.CANCELLED, row.status)
        assertEquals(1, row.cycleCount)
        assertEquals(RenewalStatus.PENDING, renewals(a.sub.id).single().status)
        assertTrue(sw.dw.rows(renewalOrder.id).isEmpty(), "no RENEW row for money that is being sent back")
        assertEquals(0, hooks("subscription.renewed").size)
    }

    @Test
    fun `32 the late renewal of an ended gateway subscription is refunded at once and the gateway is told to stop billing`(): Unit = runBlocking {
        val product = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED) { refund = RefundSupport.FULL_ONLY }

        val (_, caller) = user("Alex")
        val first = buy(product, caller)
        val end = w.clock.now() + 30 * day

        succeed(first, subscription = gatewayState("sub_gw", periodStart = w.clock.now(), periodEnd = end))

        val id = first.subscriptionId!!

        sql("UPDATE `pano_market_subscription` SET `status` = 'EXPIRED', `endedAt` = ?, `endReason` = 'PAYMENT_FAILED', `remoteCancelState` = 'DONE', `nextQueryAt` = NULL WHERE `id` = ?", w.clock.now(), id)
        w.clock.set(end + 1000)

        val event = PaymentEvent.SubscriptionRenewed("sub_gw", Money(600, "EUR")).also {
            it.gatewayTransactionId = "txn_late"
            it.periodStart = end
        }

        rw.sink.apply(event, null, com.panomc.plugins.market.routes.api.payment.InboundEventContext(1, "fake", null, null, w.clock.now()))

        val renewalOrder = order(renewals(id).single().orderId!!)
        val refund = w.refunds.getByOrderId(renewalOrder.id, pool).single()

        assertEquals(OrderStatus.CANCELLED, renewalOrder.status)
        assertEquals(RefundOrigin.SYSTEM, refund.origin)
        assertEquals(600, refund.amount)
        assertEquals(RemoteCancelState.PENDING, subscription(id).remoteCancelState, "the gateway is still billing a closed subscription: stop it")
        assertEquals(1, subscription(id).cycleCount)
    }

    @Test
    fun `32 with the switch off the late payment of an ended subscription waits in review even when the provider can refund`(): Unit = runBlocking {
        val a = activeMerchant()

        sw.caps(RecurringSupport.MERCHANT_INITIATED) { refund = RefundSupport.FULL_ONLY }
        rw.autoRefundDuplicatePayments = false

        val renewalOrder = lateMerchantPayment(a)
        val reviewed = order(renewalOrder.id)

        assertEquals(OrderStatus.REVIEW, reviewed.status)
        assertEquals("LATE", reviewed.reviewReason)
        assertEquals(0, w.refunds.getByOrderId(renewalOrder.id, pool).size)
        assertEquals(1, subscription(a.sub.id).cycleCount)
    }

    // ==================================================================================== 9.1: a gateway failure

    @Test
    fun `a gateway payment failure delivered three times for one attempt count fails the period once`(): Unit = runBlocking {
        val product = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alex")
        val first = buy(product, caller)
        val end = w.clock.now() + 30 * day

        succeed(first, subscription = gatewayState("sub_gw", periodStart = w.clock.now(), periodEnd = end))
        w.clock.set(end + 1000)

        suspend fun failed(count: Int?) = rw.sink.apply(
            PaymentEvent.SubscriptionPaymentFailed("sub_gw").also { it.attemptCount = count }, null,
            com.panomc.plugins.market.routes.api.payment.InboundEventContext(1, "fake", null, null, w.clock.now())
        )

        failed(1)
        failed(1)
        failed(1)

        val row = subscription(first.subscriptionId!!)

        assertEquals(SubscriptionStatus.PAST_DUE, row.status)
        assertEquals(1, row.failCount, "the same attempt count is the same failure")
        assertEquals(end + 1000 + 3 * day, row.graceEndsAt)
        assertEquals(1, mails("SUBSCRIPTION_PAYMENT_FAILED"), "one mail per period")
        assertEquals("GATEWAY_DECLINED", renewals(row.id).single().lastError)
        assertEquals(1, renewals(row.id).single().attempts)
        assertEquals(1, events(first.id, OrderEventType.SUBSCRIPTION_PAST_DUE).size)

        failed(2)

        assertEquals(2, subscription(row.id).failCount, "a new attempt count is a new failure")
        assertEquals(2, renewals(row.id).single().attempts)
        assertEquals(1, mails("SUBSCRIPTION_PAYMENT_FAILED"), "the mail is queued on the first failure only")
    }

    @Test
    fun `33 a gateway renewal while the subscription is past due makes it active again and clears the failures`(): Unit = runBlocking {
        val product = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alex")
        val first = buy(product, caller)
        val end = w.clock.now() + 30 * day

        succeed(first, subscription = gatewayState("sub_gw", periodStart = w.clock.now(), periodEnd = end))
        w.clock.set(end + 1000)

        val context = com.panomc.plugins.market.routes.api.payment.InboundEventContext(1, "fake", null, null, w.clock.now())

        rw.sink.apply(PaymentEvent.SubscriptionPaymentFailed("sub_gw").also { it.attemptCount = 1 }, null, context)

        val past = subscription(first.subscriptionId!!)

        assertEquals(SubscriptionStatus.PAST_DUE, past.status)
        assertEquals(1, past.failCount)
        assertNotNull(past.graceEndsAt)

        // the gateway collects after all, still inside the grace
        w.clock.advance(day)

        val renewed = PaymentEvent.SubscriptionRenewed("sub_gw", Money(past.price, past.currency)).also {
            it.gatewayTransactionId = "txn_ok"
            it.periodStart = end
            it.periodEnd = end + 30 * day
        }

        rw.sink.apply(renewed, null, context)

        val row = subscription(past.id)
        val renewal = renewals(past.id).single()

        assertEquals(SubscriptionStatus.ACTIVE, row.status, "S5")
        assertEquals(0, row.failCount)
        assertNull(row.graceEndsAt)
        assertNull(row.reminderSentAt)
        assertEquals(2, row.cycleCount)
        assertEquals(end, row.currentPeriodStart)
        assertEquals(end + 30 * day, row.currentPeriodEnd)
        assertEquals(RenewalStatus.PAID, renewal.status, "the row the failure created is the one that was paid")
        assertEquals(OrderStatus.COMPLETED, order(renewal.orderId!!).status)
        assertEquals(1, hooks("subscription.renewed").size)
    }

    // ==================================================================================== the frozen price and the fee of the first order (05 section 12)

    /** A subscription whose first period was paid through a method with a buyer fee of 10 %, and whose product can also be paid with credits. */
    private suspend fun feeSubscription(recurring: RecurringSupport, stored: StoredPaymentMethod? = null): Triple<TestUser, MarketOrder, MarketSubscription> {
        val product = fx.product(
            slug = "fee-monthly", price = 600, creditPrice = 600, actions = actions(),
            columns = mapOf("billingMode" to "SUBSCRIPTION", "periodUnit" to "MONTH", "periodCount" to 1)
        )

        fx.paymentMethod("fake", feeMode = PaymentFeeMode.BUYER, feePercent = 1000)
        sw.caps(recurring)

        val (alex, caller) = user("Alex")

        fx.credit(alex, 10_000_000)

        val first = buy(product, caller)

        succeed(first, stored = stored)

        val paid = order(first.id)

        assertTrue(paid.paymentFee > 0, "the first order carries the buyer fee")
        assertEquals(OrderStatus.COMPLETED, paid.status)

        return Triple(alex, paid, subscription(first.subscriptionId!!))
    }

    @Test
    fun `a merchant renewal order keeps the fee of the first order and splits the frozen total, and a gateway-paid renewal never changes the price`(): Unit = runBlocking {
        val (_, first, sub) = feeSubscription(RecurringSupport.MERCHANT_INITIATED, StoredPaymentMethod("tok_1").also { it.expiresAt = w.clock.now() + 400 * day })

        assertEquals(first.totalPrice, sub.price)

        chargeSucceeds()
        w.clock.set(sub.nextChargeAt!!)
        rw.job.runOnce()

        val renewalOrder = renewalOrderOf(sub.id)
        val item = w.orderItems.getByOrderIds(listOf(renewalOrder.id), pool).single()

        assertEquals(sub.price, renewalOrder.totalPrice, "the total is frozen")
        assertEquals(first.paymentFee, renewalOrder.paymentFee, "the fee of the first order, never recomputed")
        assertEquals(first.paymentFeeVatAmount, renewalOrder.paymentFeeVatAmount)
        assertEquals(sub.price - first.paymentFee, item.lineTotal, "the item is the frozen total without the fee")
        assertEquals(item.lineTotal, renewalOrder.subtotal)
        assertEquals(renewalOrder.totalPrice, renewalOrder.gatewayAmount)
        assertEquals(renewalOrder.totalPrice, charges().single().amount.amount, "the gateway is asked for the frozen total, fee included")
        assertEquals(OrderStatus.COMPLETED, renewalOrder.status)
        assertEquals(sub.price, subscription(sub.id).price, "a gateway-paid renewal totals the frozen price")
    }

    @Test
    fun `renewals paid with credits keep the frozen price and the fee split of the first order, period after period`(): Unit = runBlocking {
        val (_, first, sub) = feeSubscription(RecurringSupport.NONE)

        assertEquals(SubscriptionMode.MANUAL, sub.mode)
        assertEquals(first.totalPrice, sub.price)

        var current = sub

        repeat(3) { n ->
            w.clock.set(current.currentPeriodEnd!! - 3 * day + 1)
            rw.job.runOnce()

            val renewalOrder = renewalOrderOf(sub.id, n + 1)
            val item = w.orderItems.getByOrderIds(listOf(renewalOrder.id), pool).single()

            assertEquals(first.totalPrice, renewalOrder.totalPrice, "renewal order ${n + 1}: the frozen total")
            assertEquals(first.paymentFee, renewalOrder.paymentFee, "renewal order ${n + 1}: the fee of the first order")
            assertEquals(first.totalPrice - first.paymentFee, item.lineTotal, "renewal order ${n + 1}: the line without the fee")

            // the buyer pays it with credits: a full-credit tender has no fee, so the paid order totals less than the frozen price
            rw.payments.pay(renewalOrder, PayRequest("credits", null, null), PayCaller(), pool)

            val paid = order(renewalOrder.id)

            assertEquals(OrderStatus.COMPLETED, paid.status)
            assertEquals(0, paid.gatewayAmount)
            assertEquals(0, paid.paymentFee, "credits carry no fee")
            assertEquals(first.totalPrice, subscription(sub.id).price, "after renewal ${n + 1} paid with credits the subscription still costs what it cost")

            current = subscription(sub.id)

            assertEquals(n + 2, current.cycleCount)
        }
    }

    // ==================================================================================== 8.6: MANUAL

    @Test
    fun `a manual subscription gets its renewal order and one reminder before the period ends, and pays it by hand`(): Unit = runBlocking {
        val a = activeManual()

        assertEquals(SubscriptionMode.MANUAL, a.sub.mode)
        assertNull(a.sub.nextChargeAt, "nothing is ever charged automatically")

        // three days before the end (the default reminder lead): the renewal order and the reminder
        w.clock.set(a.sub.currentPeriodEnd!! - 3 * day + 1)
        rw.job.runOnce()
        rw.job.runOnce()

        val renewal = renewals(a.sub.id).single()
        val renewalOrder = order(renewal.orderId!!)

        assertEquals(1, renewal.periodIndex)
        assertEquals(OrderSource.RENEWAL, renewalOrder.source)
        assertEquals(OrderStatus.PENDING, renewalOrder.status)
        assertEquals(a.sub.currentPeriodEnd!! + 3 * day, renewalOrder.expiresAt, "payable until the grace ends")
        assertEquals(0, attempts(renewalOrder.id).size, "no attempt: the buyer pays")
        assertEquals(1, mails("SUBSCRIPTION_REMINDER"), "once")

        val reminder = sql("SELECT `refKey`, `params` FROM `pano_market_mail_outbox` WHERE `kind` = 'SUBSCRIPTION_REMINDER'").single()

        assertEquals("1", reminder.getString("refKey"))
        assertEquals("https://shop.example/store/order/${renewalOrder.publicId}", JsonObject(reminder.getString("params")).getString("payUrl"))
        assertNotNull(subscription(a.sub.id).reminderSentAt)

        // the buyer pays it before the period ends through another method: the next period simply follows
        fx.paymentMethod("second")
        sw.capsOfSecond(RecurringSupport.NONE)
        rw.payments.pay(renewalOrder, PayRequest("second", null, null), PayCaller(), pool)

        val paying = attempts(renewalOrder.id).last()
        val event = PaymentEvent.Succeeded(PaymentTarget.Attempt(paying.id), Money(paying.amount, paying.currency))

        rw.payments.applyEvent(renewalOrder.id, paying.id, PaymentEventMapper.attemptEvent(event)!!, AttemptFacts.of(event, sw.cipher))

        val row = subscription(a.sub.id)

        assertEquals(OrderStatus.COMPLETED, order(renewalOrder.id).status)
        assertEquals(2, row.cycleCount)
        assertEquals(a.sub.currentPeriodEnd, row.currentPeriodStart, "no gap")
        assertEquals(oneMonthAfter(a.sub.currentPeriodEnd!!), row.currentPeriodEnd)
        assertEquals(SubscriptionMode.MANUAL, row.mode)
        assertEquals("second", row.providerId, "the subscription follows the method that paid")
        assertNull(row.reminderSentAt)
        assertEquals(1, w.entitlements.getByOwnerAndProduct(a.order.buyerKey, a.product.id, pool).size)
    }

    @Test
    fun `a renewal order paid by hand through a merchant-initiated provider turns the subscription into a merchant one`(): Unit = runBlocking {
        val a = activeManual()

        w.clock.set(a.sub.currentPeriodEnd!! - 3 * day + 1)
        rw.job.runOnce()

        val renewalOrder = renewalOrderOf(a.sub.id)

        fx.paymentMethod("second")
        sw.capsOfSecond(RecurringSupport.MERCHANT_INITIATED)
        rw.payments.pay(renewalOrder, PayRequest("second", null, null), PayCaller(), pool)

        // the start asks the provider for the instrument: the plan travels with it (a gateway-managed provider would get a plain one-off payment)
        val start = sw.secondFake.calls(FakePaymentProvider.Op.START).last().request as com.panomc.plugins.market.spi.payment.StartPaymentRequest

        assertNotNull(start.subscription, "a merchant-initiated provider is asked for a stored method")
        assertEquals(order(renewalOrder.id).totalPrice, start.subscription!!.price.amount)

        val paying = attempts(renewalOrder.id).last()
        val event = PaymentEvent.Succeeded(PaymentTarget.Attempt(paying.id), Money(paying.amount, paying.currency)).also {
            it.storedMethod = StoredPaymentMethod("tok_new").also { m -> m.label = "Mastercard 5555"; m.expiresAt = w.clock.now() + 400 * day }
        }

        rw.payments.applyEvent(renewalOrder.id, paying.id, PaymentEventMapper.attemptEvent(event)!!, AttemptFacts.of(event, sw.cipher))

        val row = subscription(a.sub.id)

        assertEquals(SubscriptionMode.MERCHANT, row.mode)
        assertEquals("second", row.providerId)
        assertEquals("Mastercard 5555", row.storedMethodLabel)
        assertEquals("tok_new", JsonObject(sw.cipher.decrypt(row.storedMethod!!)!!).getString("token"))
        assertEquals(row.currentPeriodEnd, row.nextChargeAt, "the next period is charged to the new card")
    }

    @Test
    fun `51 the buyer cannot cancel a renewal order, the subscription is what is cancelled`(): Unit = runBlocking {
        val a = activeManual()

        w.clock.set(a.sub.currentPeriodEnd!! - 3 * day + 1)
        rw.job.runOnce()

        val renewalOrder = renewalOrderOf(a.sub.id)
        val refusal = runCatching { rw.payments.cancel(renewalOrder, pool) }.exceptionOrNull()

        assertEquals("ORDER_NOT_CANCELLABLE", (refusal as com.panomc.platform.model.Error).getErrorCode())
        assertEquals(OrderStatus.PENDING, order(renewalOrder.id).status)
    }

    @Test
    fun `a renewal order is payable with any method the store offers, not only the one of the subscription`(): Unit = runBlocking {
        val a = activeManual()

        w.clock.set(a.sub.currentPeriodEnd!! - 3 * day + 1)
        rw.job.runOnce()

        val renewalOrder = renewalOrderOf(a.sub.id)

        fx.paymentMethod("second")
        sw.capsOfSecond(RecurringSupport.NONE)
        rw.payments.pay(renewalOrder, PayRequest("second", null, null), PayCaller(), pool)

        val attempt = attempts(renewalOrder.id).last()

        assertEquals("second", attempt.providerId, "the order was not locked to the method of the subscription (it was paid through `fake`)")
        assertEquals("fake", subscription(a.sub.id).providerId)
        assertEquals(renewalOrder.gatewayAmount, attempt.amount)
        assertEquals(OrderStatus.PENDING, order(renewalOrder.id).status)
    }

    // ==================================================================================== a crash between the two transactions

    @Test
    fun `a gateway renewal that crashed between its two transactions is completed by the replay, one order, one attempt`(): Unit = runBlocking {
        val product = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alex")
        val first = buy(product, caller)
        val end = w.clock.now() + 30 * day

        succeed(first, subscription = gatewayState("sub_gw", periodStart = w.clock.now(), periodEnd = end))
        w.clock.set(end + 60_000)

        val event = PaymentEvent.SubscriptionRenewed("sub_gw", Money(600, "EUR")).also {
            it.gatewayTransactionId = "txn_crash"
            it.periodStart = end
            it.periodEnd = end + 30 * day
        }

        // tx A committed, tx B (the payment) never ran
        val prepared = sw.db.txRestartingOnOrderChange { conn -> rw.subs.onGatewayRenewed(conn, "fake", event) } as SubscriptionService.RenewedOutcome.Prepared

        assertEquals(OrderStatus.PENDING, order(prepared.orderId).status)
        assertEquals(PaymentStatus.CREATED, attempts(prepared.orderId).single().status)
        assertEquals(1, subscription(first.subscriptionId!!).cycleCount)

        // the gateway redelivers the event: the same order and the same attempt are paid
        rw.sink.apply(event, null, com.panomc.plugins.market.routes.api.payment.InboundEventContext(1, "fake", null, null, w.clock.now()))

        assertEquals(1, count("market_order", "`source` = 'RENEWAL'"))
        assertEquals(OrderStatus.COMPLETED, order(prepared.orderId).status)
        assertEquals(listOf(PaymentStatus.SUCCEEDED), attempts(prepared.orderId).map { it.status })
        assertEquals(2, subscription(first.subscriptionId!!).cycleCount)
        assertEquals(RenewalStatus.PAID, renewals(first.subscriptionId!!).single().status)
    }

    // ==================================================================================== credits (V-02)

    private suspend fun creditPaidSubscription(): Triple<TestUser, MarketOrder, MarketSubscription> {
        val product = fx.product(
            slug = "credit-monthly", price = 600, creditPrice = 600, actions = actions(),
            columns = mapOf("billingMode" to "SUBSCRIPTION", "periodUnit" to "MONTH", "periodCount" to 1)
        )

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (alex, caller) = user("Alex")

        fx.credit(alex, 100_000)

        val result = h.checkout(h.body("items" to listOf(h.line(product)), "payWithCredits" to true), caller = caller)
        var first = w.orders.getByPublicId(result.order.getString("publicId"), pool)!!

        if (first.status == OrderStatus.PENDING) {
            rw.payments.startAttempt(first.id, attempts(first.id).last().id, emptyList(), pool)
            first = order(first.id)
        }

        assertEquals(OrderStatus.COMPLETED, first.status, "the credits paid the first period")
        assertTrue(first.creditAmount > 0)

        return Triple(alex, first, subscription(first.subscriptionId!!))
    }

    private fun actions(): String = JsonArray(
        listOf(
            ProductAction("grant", DeliveryActionType.PERMISSION, nodes = listOf("group.vip")),
            ProductAction("renew", DeliveryActionType.PERMISSION, phase = DeliveryPhase.RENEW, nodes = listOf("group.vip")),
            ProductAction("expire", DeliveryActionType.COMMAND, phase = DeliveryPhase.EXPIRE, commands = listOf("say {username} subscription ended"))
        ).map { it.toJson() }
    ).encode()

    @Test
    fun `the renewal of a credit-paid subscription holds no credits, so expiring it mints nothing, V-02`(): Unit = runBlocking {
        val (alex, _, sub) = creditPaidSubscription()

        assertEquals(SubscriptionMode.MANUAL, sub.mode, "credits are never deducted automatically")

        val transactions = count("market_credit_tx")
        val balance = fx.creditBalance(alex)

        w.clock.set(sub.currentPeriodEnd!! - 3 * day + 1)
        rw.job.runOnce()

        val renewalOrder = renewalOrderOf(sub.id)

        assertEquals(0, renewalOrder.creditAmount, "the credit columns of the first order are not copied")
        assertEquals(0, renewalOrder.creditValue)
        assertEquals(renewalOrder.totalPrice, renewalOrder.gatewayAmount)

        expire(renewalOrder)

        assertEquals(OrderStatus.EXPIRED, order(renewalOrder.id).status)
        assertEquals(transactions, count("market_credit_tx"), "no RELEASE: nothing was held")
        assertEquals(balance, fx.creditBalance(alex), "no credit was minted")
    }

    @Test
    fun `a renewal paid with credits through pay posts a real hold and capture and extends the period, 74`(): Unit = runBlocking {
        val (alex, _, sub) = creditPaidSubscription()

        w.clock.set(sub.currentPeriodEnd!! - 3 * day + 1)
        rw.job.runOnce()

        val renewalOrder = renewalOrderOf(sub.id)
        val balance = fx.creditBalance(alex)

        rw.payments.pay(renewalOrder, PayRequest("credits", null, null), PayCaller(), pool)

        val paid = order(renewalOrder.id)
        val types = sql("SELECT `type` FROM `pano_market_credit_tx` WHERE `orderId` = ? ORDER BY `id`", renewalOrder.id).map { it.getString("type") }

        assertEquals(OrderStatus.COMPLETED, paid.status)
        assertTrue(paid.creditAmount > 0, "the credits are held now")
        assertEquals(listOf("HOLD", "CAPTURE"), types, "a real hold, then the capture of the payment")
        assertEquals(balance - paid.creditAmount, fx.creditBalance(alex))
        assertEquals(2, subscription(sub.id).cycleCount)
        assertEquals(sub.currentPeriodEnd, subscription(sub.id).currentPeriodStart)
        assertEquals(SubscriptionMode.MANUAL, subscription(sub.id).mode)
    }

    // ==================================================================================== the admin retry (09 section 9.3)

    @Test
    fun `an admin retry closes the attempt of unknown outcome and charges now, a manual subscription is not retryable`(): Unit = runBlocking {
        val a = activeMerchant()

        fake.failNext(FakePaymentProvider.Op.CHARGE_RECURRING, com.panomc.plugins.market.spi.common.ProviderException(com.panomc.plugins.market.spi.common.ProviderErrorCode.INTERNAL, "timeout"))
        w.clock.set(a.sub.nextChargeAt!!)
        rw.job.runOnce()
        w.clock.advance(16 * 60_000)
        rw.job.runOnce()

        val renewalOrder = renewalOrderOf(a.sub.id)
        val unknown = attempts(renewalOrder.id).single()

        assertEquals(PaymentStatus.CREATED, unknown.status)
        assertEquals(1, charges().size)

        chargeSucceeds()

        val prepared = rw.job.chargeOne(a.sub.id, admin = true)

        assertTrue(prepared is SubscriptionService.ChargePreparation.Charge)
        assertEquals(2, charges().size)
        assertEquals(PaymentStatus.CANCELLED, w.payments.getById(unknown.id, pool)!!.status, "the admin accepted the double-charge risk")
        assertEquals("sub-${a.sub.id}-1-2", charges().last().idempotencyKey)
        assertEquals(2, subscription(a.sub.id).cycleCount)

        // a manual subscription has nothing to retry
        val manual = activeManual(name = "Mia")
        val skipped = rw.job.chargeOne(manual.sub.id, admin = true)

        assertTrue(skipped is SubscriptionService.ChargePreparation.Skipped)
        assertEquals(SubscriptionService.SKIP_NOT_CHARGEABLE, (skipped as SubscriptionService.ChargePreparation.Skipped).reason)
    }
}
