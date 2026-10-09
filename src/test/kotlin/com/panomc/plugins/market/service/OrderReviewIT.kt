package com.panomc.plugins.market.service

import com.panomc.platform.model.Error
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.order.OrderEvent
import com.panomc.plugins.market.core.payment.PaymentAttemptEvent
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.CreditSystemKey
import com.panomc.plugins.market.db.model.CreditTxType
import com.panomc.plugins.market.db.model.MarketCreditAccount
import com.panomc.plugins.market.db.model.MarketCreditEntry
import com.panomc.plugins.market.db.model.MarketCreditTx
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.OrderActorType
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.db.model.RedemptionState
import com.panomc.plugins.market.db.model.RefundOrigin
import com.panomc.plugins.market.db.model.RefundStatus
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.db.tx.OrderChild
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.error.InsufficientCredits
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentTarget
import com.panomc.plugins.market.spi.payment.RefundSupport
import com.panomc.plugins.market.spi.payment.StartPaymentRequest
import com.panomc.plugins.market.spi.payment.StartPaymentResult
import com.panomc.plugins.market.spi.testkit.TestContexts
import com.panomc.plugins.market.support.FakePaymentProvider
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestWiring
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import com.panomc.plugins.market.support.ErrorBodies

/**
 * A [CreditSettlement] with the ledger posting of [CreditSettlement.rehold] that `CreditService` (MK-091) will write: capture, release and
 * re-tender are the stand-in of the payment tests, the new `HOLD` is a real double-entry posting too, so I1 to I4 judge it.
 */
internal class ReholdingSettlement(private val w: TestWiring, private val base: LedgerSettlement) : CreditSettlement {
    val reholds = CopyOnWriteArrayList<Pair<Long, Long>>()
    private val sequence = AtomicLong()

    override suspend fun capture(conn: SqlConnection, order: MarketOrder) = base.capture(conn, order)

    override suspend fun release(conn: SqlConnection, order: MarketOrder) = base.release(conn, order)

    override suspend fun retender(conn: SqlConnection, order: MarketOrder, newCredits: Long) = base.retender(conn, order, newCredits)

    override suspend fun rehold(conn: SqlConnection, order: MarketOrder, credits: Long) {
        reholds += order.id to credits

        val user = w.creditAccounts.getByUserId(order.userId!!, conn)!!
        val hold = w.creditAccounts.getBySystemKey(CreditSystemKey.HOLD, conn)!!

        w.creditAccounts.lockByIds(listOf(user.id, hold.id), conn)

        if (w.creditAccounts.addToBalance(user.id, -credits, true, conn) == 0) throw InsufficientCredits(w.creditAccounts.getById(user.id, conn)!!.balance / 100.0)

        w.creditAccounts.addToBalance(hold.id, credits, false, conn)

        val now = w.clock.now()
        val tx = w.creditTxs.add(
            MarketCreditTx(type = CreditTxType.HOLD, idempotencyKey = "order:${order.id}:rehold:${sequence.incrementAndGet()}", userId = order.userId, amount = credits, orderId = order.id, createdAt = now, updatedAt = now),
            conn
        )!!

        w.creditEntries.add(MarketCreditEntry(txId = tx, accountId = user.id, amount = -credits, balanceAfter = w.creditAccounts.getById(user.id, conn)!!.balance, createdAt = now, updatedAt = now), conn)
        w.creditEntries.add(MarketCreditEntry(txId = tx, accountId = hold.id, amount = credits, balanceAfter = w.creditAccounts.getById(hold.id, conn)!!.balance, createdAt = now, updatedAt = now), conn)
    }
}

/**
 * `OrderReviewService` and the review effects of `OrderService` on a real MariaDB (MK-079; 00 sections 7.1 O3 to O9 and 7.2, 06 section 11, 04 section 7):
 * accept and reject of an order in review, late payments on a released order, duplicate payments, and `PUT /orders/:id/status` on the state machine.
 * Payment events arrive through the real `PaymentService`; the invariants I1 to I22 are checked after every test by the base class.
 */
class OrderReviewIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var ph: PaymentHarness
    private lateinit var settlement: ReholdingSettlement
    private lateinit var orders: OrderService
    private lateinit var payments: PaymentService
    private lateinit var review: OrderReviewService
    private val vertx: Vertx = Vertx.vertx()

    @Volatile
    private var autoRefund = true

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    @BeforeEach
    fun freshState() {
        runBlocking { resetState() }
        autoRefund = true
        w = TestWiring(pool)
        ph = PaymentHarness(w, vertx)
        wire(withRefundTable = true)
    }

    private val fx get() = w.fixtures
    private val h get() = ph.h
    private val fake get() = ph.fake
    private val admin = 77L

    /** The order graph of the production wiring: the real services, with the refund table, the purchase limits and the ledger of this test. */
    private fun wire(withRefundTable: Boolean) {
        settlement = ReholdingSettlement(w, ph.ledger)

        val redemptions = RedemptionService(w.clock, ph.locks, w.redemptions)

        orders = OrderService(
            w.clock, w.ids, w.orders, w.orderItems, w.orderEvents, w.payments, redemptions, { _, _ -> false },
            reservations = ReservationService(w.clock, ph.locks, redemptions, w.orders), settlement = settlement, foreign = ph.effects,
            webhooks = PaidWebhooks { conn, orderId -> ph.webhooks.service.emitOrderPaid(conn, orderId) },
            rates = { sqlClient -> w.currencyRates.getAll(sqlClient).filter { it.rate.signum() > 0 }.associate { it.currency to it.rate } },
            statsCurrency = { ph.statsCurrency },
            limits = ProductPurchaseLimits(w.orders, w.products, w.entitlements, w.clock),
            refunds = if (withRefundTable) w.refunds else null,
            duplicates = DuplicateRefundPolicy { conn, providerId -> payments.duplicateRefundRule(conn, providerId) }
        )
        payments = PaymentService(
            db = ph.db, locks = ph.locks, clock = w.clock, ids = w.ids, config = { config() }, orders = w.orders, orderItems = w.orderItems, orderEvents = w.orderEvents,
            payments = w.payments, methods = w.paymentMethods, creditAccounts = w.creditAccounts, currencyRates = w.currencyRates, lookup = ph.lookup, cipher = ph.cipher,
            contexts = PaymentContexts { provider, settings, testMode -> TestContexts.payment(provider.id, settings, vertx, testMode) },
            orderService = orders, site = { TestContexts.defaultSite() }, readClient = { w.pool }, products = w.products, entitlements = w.entitlements,
            alerts = PanelAlerts { orderId, reason -> ph.alerts += orderId to reason }
        )
        review = OrderReviewService(ph.db, ph.locks, w.orders, w.payments, w.orderEvents, w.clock, orders, { false }, { after -> payments.runAfterCommit(after, w.pool) })

        h.useStarter(payments)
    }

    /** `h.config.toConfig()` with the switch of the duplicate refund: `Cfg` lives in the checkout test and does not carry it. */
    private fun config(): MarketConfig {
        val c = h.config

        return MarketConfig(
            currency = "EUR", vatPercent = 20.0, showVatInPrice = c.showVatInPrice, creditValue = 1.0, storeTimeZone = "UTC", allowGuestCheckout = c.allowGuestCheckout,
            allowGiftPurchase = c.allowGiftPurchase, minimumOrderAmount = c.minimumOrderAmount, creditsEnabled = c.creditsEnabled, allowMixedCreditPayment = c.allowMixedCreditPayment,
            onlyAcceptCredits = c.onlyAcceptCredits, testMode = c.testMode, billingInfoMode = c.billingInfoMode, legalTextRequired = c.legalTextRequired,
            creditName = c.creditName, checkoutRateLimitPerMinute = c.checkoutRateLimitPerMinute, currencyMode = c.currencyMode, additionalCurrencies = c.additionalCurrencies,
            autoRefundDuplicatePayments = autoRefund
        )
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private suspend fun buy(product: MarketProduct, method: String = "fake", quantity: Int = 1, caller: QuoteCaller = QuoteCaller.GUEST, vararg extra: Pair<String, Any?>): CheckoutResult =
        h.checkout(h.body("items" to listOf(h.line(product, quantity)), "paymentMethodId" to method, *extra), caller = caller)

    /** A purchase paid with credits only (06 section 5.4: `payWithCredits`): the credits are captured at once. */
    private suspend fun spend(product: MarketProduct, caller: QuoteCaller) =
        h.checkout(h.body("items" to listOf(h.line(product)), "paymentMethodId" to "credits", "payWithCredits" to true), caller = caller)

    private suspend fun orderOf(result: CheckoutResult): MarketOrder = ph.order(result.order.getString("publicId"))

    private suspend fun order(id: Long) = ph.order(id)

    private suspend fun attempts(orderId: Long) = ph.attempts(orderId)

    private suspend fun succeed(orderId: Long, attempt: MarketPayment, amount: Long = attempt.amount, currency: String = attempt.currency): AppliedEvent =
        payments.applyEvent(orderId, attempt.id, PaymentAttemptEvent.Succeeded(amount, currency, null))

    private suspend fun pay(order: MarketOrder, method: String = "fake", credits: Long? = null): JsonObject? =
        payments.pay(order, PayRequest(method, credits, null), PayCaller(), pool)

    private suspend fun stock(product: MarketProduct): Int? = w.products.getById(product.id, pool)!!.stock

    private suspend fun timeline(orderId: Long) = w.orderEvents.getByOrderId(orderId, pool)

    private suspend fun refunds(orderId: Long) = w.refunds.getByOrderId(orderId, pool)

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

        assertEquals(code, body.getJsonObject("error").getString("code"), "error code of the wire body ${e.encode()}")
        assertEquals(status, e.getStatusCode())

        return ErrorBodies.details(e)
    }

    /** O6 as the expiry job applies it: the clock passes `expiresAt`, the machine decides under the `RELEASE` locks. */
    private suspend fun expire(orderId: Long) {
        val order = order(orderId)

        w.clock.advance(order.expiresAt!! - w.clock.now() + 1)

        ph.db.txRestartingOnOrderChange { conn ->
            ph.locks.forOrder(conn, orderId, OrderLockScope.RELEASE) { locked ->
                ph.locks.children(conn, orderId, OrderChild.PAYMENT)

                orders.transition(conn, locked, OrderEvent.Expire(w.clock.now()))
            }
        }

        assertEquals(OrderStatus.EXPIRED, order(orderId).status)
    }

    private suspend fun accept(orderId: Long, force: Boolean = false, note: String? = null) =
        review.review(orderId, ReviewDecision.ACCEPT, refund = false, force = force, note = note, adminUserId = admin)

    private suspend fun reject(orderId: Long, refund: Boolean, note: String? = null) =
        review.review(orderId, ReviewDecision.REJECT, refund = refund, force = false, note = note, adminUserId = admin)

    private fun result(req: StartPaymentRequest, amount: Long = req.amount.amount) =
        StartPaymentResult.Completed(PaymentEvent.Succeeded(PaymentTarget.Attempt(req.attempt.id), Money(amount, req.amount.currency)))

    // ===================================================================================== F-05 underpaid

    @Test
    fun `F-05 an underpaid order accepted completes with every effect of O2 and the received money stays on record`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val product = fx.product(price = 1000, stock = 5)
        val order = orderOf(buy(product))
        val attempt = attempts(order.id).single()

        succeed(order.id, attempt, 999)

        val waiting = order(order.id)

        assertEquals(OrderStatus.REVIEW, waiting.status)
        assertEquals("UNDERPAID", waiting.reviewReason)
        assertEquals(ReservationState.HELD, waiting.reservationState)
        assertEquals(4, stock(product))
        assertTrue(ph.effects.of(order.id).isEmpty(), "nothing is delivered while a human decides")

        val change = accept(order.id, note = "the rest came by bank transfer")
        val done = order(order.id)

        assertTrue(change.moved)
        assertEquals(OrderStatus.REVIEW, change.from)
        assertEquals(OrderStatus.COMPLETED, done.status)
        assertEquals(ReservationState.COMMITTED, done.reservationState)
        assertEquals(999, done.paidAmount, "paidAmount stays as received")
        assertEquals(attempt.id, done.paymentId)
        assertNotNull(done.paidAt)
        assertNull(done.expiresAt)
        assertEquals(1, w.products.getById(product.id, pool)!!.soldCount)
        assertEquals(4, stock(product), "the stock was deducted at checkout and stays deducted")
        assertEquals(1, ph.effects.of(order.id).count { it == "IssueInvoice" }, "every foreign effect of O2 ran once")
        assertEquals(1, ph.effects.of(order.id).count { it == "QueueGrantDeliveries" })
        assertEquals(PaymentStatus.SUCCEEDED, attempts(order.id).single().status, "the accepted money is the payment of the order")

        val status = timeline(order.id).single { it.type == OrderEventType.STATUS_CHANGED && it.toStatus == "COMPLETED" }

        assertEquals("REVIEW", status.fromStatus)
        assertEquals(OrderActorType.ADMIN, status.actorType)
        assertEquals(admin, status.actorUserId)
        assertEquals("the rest came by bank transfer", status.message)
    }

    @Test
    fun `F-05 a rejection with refund cancels, releases the hold and requests exactly the received money`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val coupon = fx.coupon(code = "TEN", redeemLimit = 5)
        val product = fx.product(price = 1000, stock = 5)
        val order = orderOf(buy(product, "fake", 1, QuoteCaller.GUEST, "couponCode" to coupon.code))
        val attempt = attempts(order.id).single()

        assertEquals(1, w.coupons.getById(coupon.id, pool)!!.usedCount)

        succeed(order.id, attempt, attempt.amount - 1)

        val change = reject(order.id, refund = true, note = "wrong amount")
        val done = order(order.id)

        assertTrue(change.moved)
        assertEquals(OrderStatus.CANCELLED, done.status)
        assertEquals(ReservationState.RELEASED, done.reservationState)
        assertEquals(5, stock(product), "the stock is back")
        assertEquals(0, w.coupons.getById(coupon.id, pool)!!.usedCount, "the code counter is back")
        assertTrue(ph.effects.of(order.id).isEmpty(), "nothing was delivered, nothing is revoked")
        assertTrue(ph.ledger.captures.isEmpty())

        val refund = refunds(order.id).single()

        assertEquals(RefundOrigin.SYSTEM, refund.origin)
        assertEquals(RefundStatus.REQUESTED, refund.status, "the request is written, the gateway call is the refund service's")
        assertEquals(attempt.amount - 1, refund.amount, "exactly what was received")
        assertEquals(attempt.amount - 1, refund.gatewayAmount)
        assertEquals(0, refund.creditAmount)
        assertEquals(attempt.id, refund.paymentId)
        assertEquals("fake", refund.providerId)
        assertEquals("EUR", refund.currency)
        assertEquals(admin, refund.initiatedBy)
        assertEquals(1, timeline(order.id).count { it.type == OrderEventType.REFUND_REQUESTED })
    }

    @Test
    fun `F-05 a rejection without refund requests nothing, a rejected review never moves twice`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val product = fx.product(price = 1000, stock = 5)
        val order = orderOf(buy(product))

        succeed(order.id, attempts(order.id).single(), 500)
        reject(order.id, refund = false)

        assertEquals(OrderStatus.CANCELLED, order(order.id).status)
        assertTrue(refunds(order.id).isEmpty())
        assertEquals(5, stock(product))

        val again = expect("INVALID_ORDER_TRANSITION", 400) { reject(order.id, refund = true) }

        assertEquals("NOT_IN_REVIEW", again.getString("reason"))
        assertTrue(refunds(order.id).isEmpty(), "the second attempt wrote nothing")
        assertEquals(1, timeline(order.id).count { it.type == OrderEventType.STATUS_CHANGED && it.toStatus == "CANCELLED" })
    }

    @Test
    fun `a rejection with refund on an OrderService without a refund table fails and the order stays in review`(): Unit = runBlocking {
        wire(withRefundTable = false)
        fx.paymentMethod("fake")

        val product = fx.product(price = 1000, stock = 5)
        val order = orderOf(buy(product))

        succeed(order.id, attempts(order.id).single(), 500)

        val failure = runCatching { reject(order.id, refund = true) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException, "fails closed: $failure")
        assertEquals(OrderStatus.REVIEW, order(order.id).status)
        assertEquals(ReservationState.HELD, order(order.id).reservationState)
        assertEquals(4, stock(product), "the rollback kept the hold")
        assertTrue(refunds(order.id).isEmpty())
    }

    // ============================================================================ F-06 overpaid, F-07 wrong currency

    @Test
    fun `F-06 an overpaid success is a review, accepted it completes with the paid amount as received`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000, stock = 5)))
        val attempt = attempts(order.id).single()

        succeed(order.id, attempt, attempt.amount + 500)

        assertEquals("OVERPAID", order(order.id).reviewReason)
        assertEquals(OrderStatus.REVIEW, order(order.id).status)

        accept(order.id)

        val done = order(order.id)

        assertEquals(OrderStatus.COMPLETED, done.status)
        assertEquals(attempt.amount + 500, done.paidAmount)
        assertEquals(attempt.amount, done.gatewayAmount, "the price of the order did not change")
    }

    @Test
    fun `F-06 with buyerMayPayMore the same payment completes at once and records what was paid`(): Unit = runBlocking {
        fake.caps = fake.caps.also { it.buyerMayPayMore = true }
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000, stock = 5)))
        val attempt = attempts(order.id).single()

        succeed(order.id, attempt, attempt.amount + 500)

        val done = order(order.id)

        assertEquals(OrderStatus.COMPLETED, done.status)
        assertEquals(attempt.amount + 500, done.paidAmount)
    }

    @Test
    fun `F-07 a payment in another currency is a review, rejected with refund the refund is in the currency it was paid in`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000, stock = 5)))
        val attempt = attempts(order.id).single()

        succeed(order.id, attempt, attempt.amount, "USD")

        assertEquals("CURRENCY_MISMATCH", order(order.id).reviewReason)

        reject(order.id, refund = true)

        val refund = refunds(order.id).single()

        assertEquals("USD", refund.currency)
        assertEquals(attempt.amount, refund.amount)
        assertEquals(OrderStatus.CANCELLED, order(order.id).status)
    }

    // ======================================================================== F-12 late payment (O9) and the accept

    @Test
    fun `F-12 a payment after expiry, cancel or failure is a review with nothing delivered, accepting it re-reserves and completes`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val coupon = fx.coupon(code = "LATE", redeemLimit = 5)

        for (how in listOf("EXPIRED", "CANCELLED", "FAILED")) {
            val product = fx.product(price = 1000, stock = 3)
            val order = orderOf(buy(product, "fake", 1, QuoteCaller.GUEST, "couponCode" to coupon.code))
            val attempt = attempts(order.id).single()
            val used = w.coupons.getById(coupon.id, pool)!!.usedCount

            when (how) {
                "EXPIRED" -> expire(order.id)
                "CANCELLED" -> payments.cancel(order, pool)
                else -> review.setStatus(order.id, OrderStatus.FAILED, null, admin)
            }

            assertEquals(OrderStatus.valueOf(how), order(order.id).status, how)
            assertEquals(3, stock(product), "$how: the stock went back")
            assertEquals(used - 1, w.coupons.getById(coupon.id, pool)!!.usedCount, "$how: the code went back")

            succeed(order.id, attempt)

            val late = order(order.id)

            assertEquals(OrderStatus.REVIEW, late.status, how)
            assertEquals("LATE", late.reviewReason, how)
            assertEquals(ReservationState.RELEASED, late.reservationState, how)
            assertEquals(attempt.amount, late.paidAmount, how)
            assertTrue(ph.effects.of(order.id).isEmpty(), "$how: nothing delivered")
            assertTrue(ph.alerts.any { it.first == order.id && it.second == "LATE" }, how)

            accept(order.id)

            val done = order(order.id)

            assertEquals(OrderStatus.COMPLETED, done.status, how)
            assertEquals(ReservationState.COMMITTED, done.reservationState, how)
            assertEquals(2, stock(product), "$how: the stock is taken again")
            assertEquals(used, w.coupons.getById(coupon.id, pool)!!.usedCount, "$how: the code is counted again")
            assertEquals(1, w.products.getById(product.id, pool)!!.soldCount, how)
            assertEquals(1, ph.effects.of(order.id).count { it == "IssueInvoice" }, how)
            assertTrue(w.redemptions.getByOrderId(order.id, pool).all { it.state == RedemptionState.APPLIED }, "$how: the redemption is applied")
            assertEquals(PaymentStatus.SUCCEEDED, attempts(order.id).single().status, how)
        }
    }

    @Test
    fun `F-12 when the stock is gone the accept answers 409 OUT_OF_STOCK and the order stays in review, force completes it clamped at zero`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val product = fx.product(price = 1000, stock = 1)
        val order = orderOf(buy(product))
        val attempt = attempts(order.id).single()

        payments.cancel(order, pool)
        succeed(order.id, attempt)

        // somebody else bought the last unit while the late payment waited
        val other = orderOf(buy(product))

        assertEquals(0, stock(product))

        val body = expect("OUT_OF_STOCK", 409) { accept(order.id) }

        assertTrue(body.getJsonArray("lines").size() >= 1)
        assertEquals(OrderStatus.REVIEW, order(order.id).status)
        assertEquals(ReservationState.RELEASED, order(order.id).reservationState, "nothing of the failed re-reserve was kept")
        assertEquals(0, stock(product))
        assertTrue(ph.effects.of(order.id).isEmpty())
        assertEquals(OrderStatus.PENDING, order(other.id).status)

        val change = accept(order.id, force = true, note = "customer was charged, we ship")
        val done = order(order.id)

        assertTrue(change.moved)
        assertEquals(OrderStatus.COMPLETED, done.status)
        assertEquals(ReservationState.COMMITTED, done.reservationState)
        assertEquals(0, stock(product), "clamped at zero, never negative")
        assertEquals(1, ph.effects.of(order.id).count { it == "IssueInvoice" })

        val note = timeline(order.id).single { it.type == OrderEventType.NOTE }

        assertEquals(OrderService.FORCE_OVERRIDE_NOTE, note.message)
        assertEquals(OrderActorType.ADMIN, note.actorType)
        assertEquals(admin, note.actorUserId)
        assertFalse(JsonObject(note.data!!).getBoolean("codesAndLimitsReReserved"))
    }

    @Test
    fun `a code of the released order that is gone answers 409 with the code of the checkout error and keeps the order in review`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val coupon = fx.coupon(code = "GONE", redeemLimit = 5)
        val product = fx.product(price = 1000, stock = 3)
        val order = orderOf(buy(product, "fake", 1, QuoteCaller.GUEST, "couponCode" to coupon.code))
        val attempt = attempts(order.id).single()

        payments.cancel(order, pool)
        succeed(order.id, attempt)

        sql("DELETE FROM `pano_market_coupon` WHERE `id` = ?", coupon.id)

        val body = expect("INVALID_COUPON", 409) { accept(order.id) }

        assertEquals("CODE_NOT_FOUND", body.getString("reason"))
        assertEquals(OrderStatus.REVIEW, order(order.id).status)
        assertEquals(ReservationState.RELEASED, order(order.id).reservationState)
        assertEquals(3, stock(product), "the stock of the failed re-reserve was rolled back")
    }

    @Test
    fun `a limit the buyer used up meanwhile answers 409 PURCHASE_LIMIT_REACHED, force skips the limit`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val product = fx.product(price = 1000, stock = 5, columns = mapOf("limitPerPlayer" to 1))
        val late = orderOf(buy(product))
        val lateAttempt = attempts(late.id).single()

        payments.cancel(late, pool)
        succeed(late.id, lateAttempt)

        // the same player (the guest Steve) buys and pays the one unit the limit allows
        val second = orderOf(buy(product))

        succeed(second.id, attempts(second.id).single())

        assertEquals(OrderStatus.COMPLETED, order(second.id).status)

        val body = expect("PURCHASE_LIMIT_REACHED", 409) { accept(late.id) }

        assertEquals(1, body.getInteger("limit"))
        assertEquals(product.id, body.getLong("productId"))
        assertEquals(OrderStatus.REVIEW, order(late.id).status)
        assertEquals(ReservationState.RELEASED, order(late.id).reservationState)

        accept(late.id, force = true)

        assertEquals(OrderStatus.COMPLETED, order(late.id).status)
    }

    @Test
    fun `accept and reject only exist for an order in review, everything else is INVALID_ORDER_TRANSITION`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val pending = orderOf(buy(fx.product(price = 1000, stock = 5)))
        val paid = orderOf(buy(fx.product(price = 1000, stock = 5)))

        succeed(paid.id, attempts(paid.id).single())

        for (id in listOf(pending.id, paid.id)) {
            assertEquals("NOT_IN_REVIEW", expect("INVALID_ORDER_TRANSITION", 400) { accept(id) }.getString("reason"))
            assertEquals("NOT_IN_REVIEW", expect("INVALID_ORDER_TRANSITION", 400) { reject(id, refund = true) }.getString("reason"))
        }

        assertEquals(OrderStatus.PENDING, order(pending.id).status)
        assertEquals(OrderStatus.COMPLETED, order(paid.id).status)
        assertEquals(1, ph.effects.of(paid.id).count { it == "IssueInvoice" }, "a second accept never re-runs the effects")
    }

    @Test
    fun `an accept and a reject at the same moment end in exactly one decision`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        repeat(Race.rounds) { round ->
            val product = fx.product(price = 1000, stock = 5)
            val order = orderOf(buy(product))

            succeed(order.id, attempts(order.id).single(), 600)

            val outcomes = Race.run(6) { i -> if (i % 2 == 0) accept(order.id) else reject(order.id, refund = true) }
            val won = outcomes.filter { it.isSuccess }
            val after = order(order.id)

            assertEquals(1, won.count { it.getOrNull()!!.moved }, "round $round: one decision moved the order")
            assertTrue(outcomes.filter { it.isFailure }.all { (it.exceptionOrNull() as? Error)?.getErrorCode() == "INVALID_ORDER_TRANSITION" }, "round $round: the others were told it is decided: ${outcomes.mapNotNull { it.exceptionOrNull() }}")

            when (after.status) {
                OrderStatus.COMPLETED -> {
                    assertTrue(refunds(order.id).isEmpty(), "round $round: accepted means no refund")
                    assertEquals(1, ph.effects.of(order.id).count { it == "IssueInvoice" })
                }

                OrderStatus.CANCELLED -> {
                    assertEquals(1, refunds(order.id).size, "round $round: exactly one refund request")
                    assertTrue(ph.effects.of(order.id).isEmpty())
                    assertEquals(5, stock(product))
                }

                else -> error("round $round: ${after.status}")
            }
        }
    }

    // ============================================================================ credits in a review (V-01, 07 section 5)

    private suspend fun payLessAttack(): Triple<TestUser, MarketOrder, MarketPayment> {
        h.config = h.config.copy(allowMixedCreditPayment = true)

        val (alex, caller) = user("Alex", credit = 8_000)

        fx.paymentMethod("fake")

        val calls = AtomicInteger()

        // while the 20.00 charge of the first attempt runs, the buyer drops the credits: the 100.00 order is now payable only in money
        fake.onStart = { req ->
            if (calls.incrementAndGet() == 1) {
                runBlocking { pay(ph.order(req.order.id), credits = 0) }

                result(req)
            } else {
                StartPaymentResult.Redirect("https://gateway.invalid/second")
            }
        }

        buy(fx.product(price = 10_000, stock = 5), "fake", 1, caller, "useCredits" to 80)

        val order = ph.order(sql("SELECT `id` FROM `pano_market_order`").single().getLong("id"))
        val first = attempts(order.id).first()

        assertEquals(OrderStatus.REVIEW, order.status)
        assertEquals("AMOUNT_MISMATCH", order.reviewReason)
        assertEquals(0, order.creditAmount)
        assertEquals(8_000, fx.creditBalance(alex), "the credits the buyer released stay released")

        return Triple(alex, order, first)
    }

    @Test
    fun `V-01 accepting an AMOUNT_MISMATCH rewrites the tender to the paid attempt, holds its credits again and captures them`(): Unit = runBlocking {
        val (alex, order, first) = payLessAttack()

        accept(order.id)

        val done = order(order.id)

        assertEquals(OrderStatus.COMPLETED, done.status)
        assertEquals(8_000, done.creditAmount)
        assertEquals(8_000, done.creditValue)
        assertEquals(first.amount, done.gatewayAmount)
        assertEquals(first.orderTotal, done.totalPrice)
        assertEquals(first.amount, done.paidAmount)
        assertEquals(ReservationState.COMMITTED, done.reservationState)
        assertEquals(listOf(order.id to 8_000L), settlement.reholds, "the credits were held again, once")
        assertEquals(listOf(order.id), ph.ledger.captures, "and captured, once")
        assertEquals(0, fx.creditBalance(alex), "the buyer paid with the credits he had committed to")
        assertEquals(1, ph.effects.of(order.id).count { it == "IssueInvoice" })
        assertEquals(1, timeline(order.id).count { it.message == OrderService.TENDER_REWRITTEN_NOTE })
    }

    @Test
    fun `V-01 with too little balance the accept answers 400 INSUFFICIENT_CREDITS and the order stays in review untouched`(): Unit = runBlocking {
        val (alex, order, _) = payLessAttack()

        // the buyer spends 5 000 of the 8 000 on another order meanwhile
        val spent = fx.product(price = 5_000, creditPrice = 5_000, stock = 5)

        spend(spent, QuoteCaller(alex.id))

        assertEquals(3_000, fx.creditBalance(alex))

        expect("INSUFFICIENT_CREDITS", 400) { accept(order.id) }

        val after = order(order.id)

        assertEquals(OrderStatus.REVIEW, after.status)
        assertEquals(0, after.creditAmount, "the tender was not rewritten")
        assertEquals(10_000, after.totalPrice)
        assertEquals(3_000, fx.creditBalance(alex))
        assertTrue(ph.effects.of(order.id).isEmpty())
    }

    @Test
    fun `V-01 rejecting it releases the credits that are held and refunds only the money received, never a credit refund`(): Unit = runBlocking {
        val (alex, order, first) = payLessAttack()

        // a mirror case: the order holds credits again (a retry through /pay), the paid attempt is the money-only one
        assertEquals(0, order.creditAmount)

        reject(order.id, refund = true)

        val refund = refunds(order.id).single()

        assertEquals(first.amount, refund.gatewayAmount)
        assertEquals(0, refund.creditAmount)
        assertEquals(0, refund.creditValue)
        assertEquals(8_000, fx.creditBalance(alex), "no credit was spent or refunded")
        assertEquals(OrderStatus.CANCELLED, order(order.id).status)
    }

    @Test
    fun `a late payment of an order that held credits accepted holds them again and captures them, without balance it stays in review`(): Unit = runBlocking {
        h.config = h.config.copy(allowMixedCreditPayment = true)

        val (alex, caller) = user("Alex", credit = 8_000)

        fx.paymentMethod("fake")

        val product = fx.product(price = 10_000, stock = 5)

        buy(product, "fake", 1, caller, "useCredits" to 80)

        val order = ph.order(sql("SELECT `id` FROM `pano_market_order`").single().getLong("id"))
        val attempt = attempts(order.id).single()

        assertEquals(8_000, order.creditAmount)
        assertEquals(0, fx.creditBalance(alex), "the credits are on hold")

        payments.cancel(order, pool)

        assertEquals(8_000, fx.creditBalance(alex), "cancel released the hold")

        succeed(order.id, attempt)

        assertEquals(ReservationState.RELEASED, order(order.id).reservationState)

        // the credits are spent elsewhere before the human looks at the review
        val spent = fx.product(price = 5_000, creditPrice = 5_000, stock = 5)

        spend(spent, caller)

        expect("INSUFFICIENT_CREDITS", 400) { accept(order.id) }

        assertEquals(OrderStatus.REVIEW, order(order.id).status)
        assertEquals(3_000, fx.creditBalance(alex))
        assertEquals(5, stock(product), "the failed accept kept nothing")

        // the buyer is topped up: now the hold can be placed again
        fx.credit(alex, 5_000)

        accept(order.id)

        val done = order(order.id)

        assertEquals(OrderStatus.COMPLETED, done.status)
        assertEquals(8_000, done.creditAmount)
        assertEquals(0, fx.creditBalance(alex))
        assertTrue(ph.ledger.captures.contains(order.id))
    }

    /**
     * A released AMOUNT_MISMATCH review: the buyer raised the credit part to 8 000 through /pay, the order expired (the hold went back), the old attempt
     * (credit part [oldCredits]) is paid late, and the buyer spends credits elsewhere until the balance is below the new part but not below the old one.
     */
    private suspend fun releasedMismatch(oldCredits: Long): Triple<TestUser, MarketOrder, MarketPayment> {
        h.config = h.config.copy(allowMixedCreditPayment = true)

        val (alex, caller) = user("Alex", credit = 10_000)

        fx.paymentMethod("fake")

        val product = fx.product(price = 10_000, stock = 5)

        if (oldCredits > 0) buy(product, "fake", 1, caller, "useCredits" to oldCredits / 100) else buy(product, "fake", 1, caller)

        val first = ph.order(sql("SELECT `id` FROM `pano_market_order`").single().getLong("id"))
        val old = attempts(first.id).single()

        assertEquals(oldCredits, first.creditAmount)
        assertEquals(oldCredits, old.creditAmount)

        pay(first, credits = 8_000)

        val raised = order(first.id)

        assertEquals(8_000, raised.creditAmount, "the buyer raised the credit part")
        assertEquals(2_000, fx.creditBalance(alex), "the larger part is on hold")

        expire(first.id)

        assertEquals(10_000, fx.creditBalance(alex), "the expiry released the hold")

        succeed(first.id, old)

        val review = order(first.id)

        assertEquals(OrderStatus.REVIEW, review.status)
        assertEquals("AMOUNT_MISMATCH", review.reviewReason)
        assertEquals(ReservationState.RELEASED, review.reservationState)
        assertEquals(old.id, review.paymentId)

        // the credits are spent elsewhere: 3 000 are left, more than the old attempt needs, less than the superseded 8 000
        spend(fx.product(price = 7_000, creditPrice = 7_000, stock = 5), caller)

        assertEquals(3_000, fx.creditBalance(alex))

        return Triple(alex, review, old)
    }

    @Test
    fun `a released AMOUNT_MISMATCH accept holds exactly the paid attempt's credit part, not the superseded one`(): Unit = runBlocking {
        val (alex, review, old) = releasedMismatch(oldCredits = 2_000)

        accept(review.id)

        val done = order(review.id)

        assertEquals(OrderStatus.COMPLETED, done.status)
        assertEquals(2_000, done.creditAmount)
        assertEquals(old.amount, done.gatewayAmount)
        assertEquals(ReservationState.COMMITTED, done.reservationState)
        assertEquals(listOf(review.id to 2_000L), settlement.reholds, "the credits were held once, for the paid attempt's part only")
        assertEquals(1, ph.ledger.captures.count { it == review.id }, "and captured once (the other capture is the credit purchase of the set-up)")
        assertEquals(1_000, fx.creditBalance(alex), "3 000 minus the 2 000 the paid attempt committed to")
        assertEquals(1, timeline(review.id).count { it.message == OrderService.TENDER_REWRITTEN_NOTE })
    }

    @Test
    fun `a released AMOUNT_MISMATCH whose paid attempt has no credit part is accepted without any credit hold`(): Unit = runBlocking {
        val (alex, review, old) = releasedMismatch(oldCredits = 0)

        accept(review.id)

        val done = order(review.id)

        assertEquals(OrderStatus.COMPLETED, done.status)
        assertEquals(0, done.creditAmount)
        assertEquals(old.amount, done.gatewayAmount)
        assertTrue(settlement.reholds.isEmpty(), "no credit part, no hold")
        assertTrue(ph.ledger.captures.none { it == review.id })
        assertEquals(3_000, fx.creditBalance(alex), "the buyer's balance is untouched")
    }

    // ============================================================================== F-14 duplicate payment

    private suspend fun duplicatePair(): Triple<MarketOrder, MarketPayment, MarketPayment> {
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000, stock = 5)))
        val first = attempts(order.id).single()

        pay(order)

        val second = attempts(order.id).last()

        succeed(order.id, second)
        succeed(order.id, first)

        return Triple(order(order.id), first, second)
    }

    @Test
    fun `F-14 the second paid attempt of a paid order is flagged duplicate and refunded automatically as a system refund`(): Unit = runBlocking {
        fake.caps = fake.caps.also { it.refund = RefundSupport.PARTIAL }

        val (order, first, second) = duplicatePair()

        assertEquals(OrderStatus.COMPLETED, order.status)
        assertEquals(second.id, order.paymentId)
        assertEquals(1, ph.effects.of(order.id).count { it == "IssueInvoice" }, "side effects once")
        assertTrue(attempts(order.id).first { it.id == first.id }.duplicate)

        val refund = refunds(order.id).single()

        assertEquals(RefundOrigin.SYSTEM, refund.origin)
        assertEquals(RefundStatus.REQUESTED, refund.status)
        assertEquals(first.id, refund.paymentId, "the money of the duplicate attempt, not of the order's own payment")
        assertEquals(first.amount, refund.amount)
        assertEquals(first.amount, refund.gatewayAmount)
        assertEquals("fake", refund.providerId)
        assertEquals(OrderService.REASON_DUPLICATE_PAYMENT, refund.reason)
        assertEquals("sys:dup:${first.id}", refund.idempotencyKey)
        assertEquals(1, timeline(order.id).count { it.type == OrderEventType.REFUND_REQUESTED })
        assertTrue(ph.alerts.none { it.first == order.id }, "a refunded duplicate needs no human")
        assertEquals(0, order.refundedTotal, "a duplicate is not a refund of the order: no O10")
        assertEquals(OrderStatus.COMPLETED, order.status)
    }

    @Test
    fun `F-14 with the setting off nothing is refunded and the panel is alerted, with a note on the timeline`(): Unit = runBlocking {
        autoRefund = false
        fake.caps = fake.caps.also { it.refund = RefundSupport.PARTIAL }

        val (order, first, _) = duplicatePair()

        assertEquals(OrderStatus.COMPLETED, order.status)
        assertTrue(refunds(order.id).isEmpty())

        val alert = timeline(order.id).single { it.type == OrderEventType.NOTE }
        val data = JsonObject(alert.data!!)

        assertEquals(OrderService.DUPLICATE_PAYMENT_ALERT, alert.message)
        assertEquals(first.id, data.getLong("paymentId"))
        assertEquals("AUTO_REFUND_OFF", data.getString("why"))
        assertEquals("NOT_REQUESTED", data.getString("refund"))
        assertEquals(listOf(order.id to OrderService.DUPLICATE_PAYMENT_ALERT), ph.alerts.filter { it.first == order.id })
    }

    @Test
    fun `F-14 a provider that cannot refund gets an alert instead of a refund row, also an OrderService without a refund table`(): Unit = runBlocking {
        // the fake provider declares RefundSupport.NONE by default
        val (order, first, _) = duplicatePair()

        assertTrue(refunds(order.id).isEmpty())
        assertEquals("REFUND_NOT_SUPPORTED", JsonObject(timeline(order.id).single { it.type == OrderEventType.NOTE }.data!!).getString("why"))
        assertEquals(1, ph.alerts.count { it.first == order.id })
        assertTrue(attempts(order.id).first { it.id == first.id }.duplicate)

        fake.caps = fake.caps.also { it.refund = RefundSupport.FULL_ONLY }
        wire(withRefundTable = false)

        val (other, _, _) = duplicatePair()

        assertTrue(refunds(other.id).isEmpty())
        assertEquals("NO_REFUND_TABLE", JsonObject(timeline(other.id).single { it.type == OrderEventType.NOTE }.data!!).getString("why"))
    }

    @Test
    fun `F-14 a replayed event does not request a second refund`(): Unit = runBlocking {
        fake.caps = fake.caps.also { it.refund = RefundSupport.PARTIAL }

        val (order, first, _) = duplicatePair()

        succeed(order.id, first)
        succeed(order.id, first)

        assertEquals(1, refunds(order.id).size)
        assertEquals(1, timeline(order.id).count { it.type == OrderEventType.REFUND_REQUESTED })
    }

    // ============================================================ several paying attempts on one review

    /** An order in review whose own attempt (the first to bring money) was underpaid, with a second attempt that brought [secondPaid] (`null` = its full amount). */
    private suspend fun twoPayingAttempts(secondPaid: Long?): Triple<MarketOrder, MarketPayment, MarketPayment> {
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000, stock = 5)))
        val first = attempts(order.id).single()

        pay(order)

        val second = attempts(order.id).last()

        succeed(order.id, second, 999)
        succeed(order.id, first, secondPaid ?: first.amount)

        val review = order(order.id)

        assertEquals(OrderStatus.REVIEW, review.status)
        assertEquals(second.id, review.paymentId, "the first attempt that brought money is the order's own")
        assertEquals(999 + (secondPaid ?: first.amount), review.paidAmount, "the review recorded both amounts")

        return Triple(review, second, first)
    }

    @Test
    fun `accepting a review with two paying attempts flags the other one duplicate, refunds it and keeps only the own attempt's money`(): Unit = runBlocking {
        fake.caps = fake.caps.also { it.refund = RefundSupport.PARTIAL }

        val (review, own, other) = twoPayingAttempts(secondPaid = null)

        assertEquals(PaymentStatus.SUCCEEDED, attempts(review.id).first { it.id == other.id }.status)

        accept(review.id)

        val done = order(review.id)
        val rows = attempts(review.id)

        assertEquals(OrderStatus.COMPLETED, done.status)
        assertEquals(own.id, done.paymentId)
        assertEquals(999, done.paidAmount, "the accepted attempt's money, not the sum")
        assertEquals(PaymentStatus.SUCCEEDED, rows.first { it.id == own.id }.status, "the order's own attempt is settled")
        assertFalse(rows.first { it.id == own.id }.duplicate)
        assertTrue(rows.first { it.id == other.id }.duplicate)

        val refund = refunds(review.id).single()

        assertEquals(RefundOrigin.SYSTEM, refund.origin)
        assertEquals(RefundStatus.REQUESTED, refund.status)
        assertEquals(other.id, refund.paymentId)
        assertEquals(other.amount, refund.amount)
        assertEquals("sys:dup:${other.id}", refund.idempotencyKey)
        assertEquals(OrderService.REASON_DUPLICATE_PAYMENT, refund.reason)
        assertEquals(1, timeline(review.id).count { it.type == OrderEventType.REFUND_REQUESTED })
        assertEquals(0, done.refundedTotal)
        assertEquals(1, ph.effects.of(review.id).count { it == "IssueInvoice" }, "side effects once")
    }

    @Test
    fun `accepting a review with two paying attempts and the refund switch off alerts the panel instead of refunding the other attempt`(): Unit = runBlocking {
        autoRefund = false
        fake.caps = fake.caps.also { it.refund = RefundSupport.PARTIAL }

        val (review, own, other) = twoPayingAttempts(secondPaid = null)
        val alertsBefore = ph.alerts.count { it.first == review.id }

        accept(review.id)

        val done = order(review.id)

        assertEquals(OrderStatus.COMPLETED, done.status)
        assertEquals(own.id, done.paymentId)
        assertEquals(999, done.paidAmount)
        assertTrue(refunds(review.id).isEmpty())
        assertTrue(attempts(review.id).first { it.id == other.id }.duplicate)

        val note = timeline(review.id).single { it.message == OrderService.DUPLICATE_PAYMENT_ALERT }
        val data = JsonObject(note.data!!)

        assertEquals(other.id, data.getLong("paymentId"))
        assertEquals("AUTO_REFUND_OFF", data.getString("why"))
        assertEquals(alertsBefore + 1, ph.alerts.count { it.first == review.id }, "one more panel alert for the duplicate")
    }

    @Test
    fun `accepting a review whose other paying attempt is itself in review flags and refunds it too`(): Unit = runBlocking {
        fake.caps = fake.caps.also { it.refund = RefundSupport.PARTIAL }

        val (review, own, other) = twoPayingAttempts(secondPaid = 500)

        assertEquals(PaymentStatus.REVIEW, attempts(review.id).first { it.id == other.id }.status)

        accept(review.id)

        val done = order(review.id)

        assertEquals(OrderStatus.COMPLETED, done.status)
        assertEquals(own.id, done.paymentId)
        assertEquals(999, done.paidAmount)
        assertEquals(PaymentStatus.SUCCEEDED, attempts(review.id).first { it.id == own.id }.status)
        assertTrue(attempts(review.id).first { it.id == other.id }.duplicate)
        assertEquals(500, refunds(review.id).single().amount)
    }

    @Test
    fun `rejecting a review with two paying attempts still refunds both`(): Unit = runBlocking {
        fake.caps = fake.caps.also { it.refund = RefundSupport.PARTIAL }

        val (review, _, _) = twoPayingAttempts(secondPaid = null)

        reject(review.id, refund = true)

        assertEquals(setOf(999L, 1000L), refunds(review.id).map { it.amount }.toSet())
        assertEquals(OrderStatus.CANCELLED, order(review.id).status)
    }

    // ============================================================ PUT /orders/:id/status on the state machine

    @Test
    fun `mark paid goes through O2 with the actor ADMIN, closes the open attempt and delivers once`(): Unit = runBlocking {
        fake.caps = PaymentCapabilities().also { it.cancelPending = true }
        fx.paymentMethod("fake")

        val product = fx.product(price = 1000, stock = 5)
        val order = orderOf(buy(product))
        val attempt = attempts(order.id).single()

        val change = review.setStatus(order.id, OrderStatus.COMPLETED, "paid in cash", admin)
        val done = order(order.id)

        assertTrue(change.moved)
        assertEquals(OrderStatus.COMPLETED, done.status)
        assertEquals(ReservationState.COMMITTED, done.reservationState)
        assertEquals(done.gatewayAmount, done.paidAmount, "mark paid: paidAmount = gatewayAmount")
        assertNull(done.paymentId)
        assertEquals("manual", done.paymentMethodId, "no gateway attempt paid it: a manual payment (I11)")
        assertEquals("Manual payment", done.paymentLabel)
        val marked = JsonObject(timeline(order.id).single { it.message == OrderReviewService.MARKED_PAID_NOTE }.data!!)

        assertEquals("fake", marked.getString("previousMethodId"), "the method the buyer chose is kept on the timeline")
        assertTrue(marked.getBoolean("hadAttempts"))
        assertNotNull(done.paidAt)
        assertEquals(PaymentStatus.CANCELLED, attempts(order.id).single { it.id == attempt.id }.status)
        assertEquals(1, ph.effects.of(order.id).count { it == "IssueInvoice" })
        assertEquals(1, w.products.getById(product.id, pool)!!.soldCount)
        assertEquals(1, fake.calls(FakePaymentProvider.Op.CANCEL).size, "the gateway is told after the commit")

        val moved = timeline(order.id).single { it.type == OrderEventType.STATUS_CHANGED && it.toStatus == "COMPLETED" }

        assertEquals(OrderActorType.ADMIN, moved.actorType)
        assertEquals("paid in cash", moved.message)

        // the same request again is a no-op: no new row, no second delivery
        val events = timeline(order.id).size
        val again = review.setStatus(order.id, OrderStatus.COMPLETED, null, admin)

        assertFalse(again.moved)
        assertEquals(events, timeline(order.id).size)
        assertEquals(1, ph.effects.of(order.id).count { it == "IssueInvoice" })
    }

    @Test
    fun `mark paid of an order that no gateway ever saw makes it a manual payment`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000, stock = 5)))

        sql("DELETE FROM `pano_market_payment` WHERE `orderId` = ?", order.id)

        review.setStatus(order.id, OrderStatus.COMPLETED, null, admin)

        val done = order(order.id)

        assertEquals(OrderStatus.COMPLETED, done.status)
        assertEquals("manual", done.paymentMethodId)
        assertEquals("Manual payment", done.paymentLabel)
        assertNull(done.paymentId)
    }

    @Test
    fun `FAILED and CANCELLED release the reservation and close the open attempts, nothing is delivered`(): Unit = runBlocking {
        fake.caps = PaymentCapabilities().also { it.cancelPending = true }
        fx.paymentMethod("fake")

        val product = fx.product(price = 1000, stock = 5)
        val failed = orderOf(buy(product))
        val cancelled = orderOf(buy(product))

        assertEquals(3, stock(product))

        review.setStatus(failed.id, OrderStatus.FAILED, null, admin)
        review.setStatus(cancelled.id, OrderStatus.CANCELLED, null, admin)

        assertEquals(OrderStatus.FAILED, order(failed.id).status)
        assertEquals(OrderStatus.CANCELLED, order(cancelled.id).status)
        assertEquals(ReservationState.RELEASED, order(failed.id).reservationState)
        assertEquals(ReservationState.RELEASED, order(cancelled.id).reservationState)
        assertEquals(5, stock(product))
        assertEquals(PaymentStatus.FAILED, attempts(failed.id).single().status)
        assertEquals(PaymentStatus.CANCELLED, attempts(cancelled.id).single().status)
        assertTrue(ph.effects.of(failed.id).isEmpty() && ph.effects.of(cancelled.id).isEmpty())
        assertEquals(2, fake.calls(FakePaymentProvider.Op.CANCEL).size)

        // repeating the request is a no-op
        assertFalse(review.setStatus(failed.id, OrderStatus.FAILED, null, admin).moved)
        assertFalse(review.setStatus(cancelled.id, OrderStatus.CANCELLED, null, admin).moved)
    }

    @Test
    fun `the admin may cancel an order whose attempt is PROCESSING, the buyer may not`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000, stock = 5)))
        val attempt = attempts(order.id).single()

        payments.applyEvent(order.id, attempt.id, PaymentAttemptEvent.Pending)

        assertEquals(PaymentStatus.PROCESSING, attempts(order.id).single().status)

        review.setStatus(order.id, OrderStatus.CANCELLED, null, admin)

        assertEquals(OrderStatus.CANCELLED, order(order.id).status)
    }

    @Test
    fun `the status endpoint is the state machine and nothing else, every other transition is refused with its hint`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val paid = orderOf(buy(fx.product(price = 1000, stock = 5)))

        succeed(paid.id, attempts(paid.id).single())

        val failed = orderOf(buy(fx.product(price = 1000, stock = 5)))

        review.setStatus(failed.id, OrderStatus.FAILED, null, admin)

        val inReview = orderOf(buy(fx.product(price = 1000, stock = 5)))

        succeed(inReview.id, attempts(inReview.id).single(), 500)

        // a refund is never a status write
        assertEquals("refunds", expect("INVALID_ORDER_TRANSITION", 400) { review.setStatus(paid.id, OrderStatus.REFUNDED, null, admin) }.getString("use"))
        assertEquals("refunds", expect("INVALID_ORDER_TRANSITION", 400) { review.setStatus(paid.id, OrderStatus.PARTIALLY_REFUNDED, null, admin) }.getString("use"))

        // an order in review is decided through the review endpoint
        for (target in listOf(OrderStatus.COMPLETED, OrderStatus.FAILED, OrderStatus.CANCELLED)) {
            assertEquals("review", expect("INVALID_ORDER_TRANSITION", 400) { review.setStatus(inReview.id, target, null, admin) }.getString("use"), "REVIEW -> $target")
        }

        // a released order cannot be paid or cancelled by a status write; a paid one cannot be failed or cancelled
        expect("INVALID_ORDER_TRANSITION", 400) { review.setStatus(failed.id, OrderStatus.COMPLETED, null, admin) }
        expect("INVALID_ORDER_TRANSITION", 400) { review.setStatus(failed.id, OrderStatus.CANCELLED, null, admin) }
        expect("INVALID_ORDER_TRANSITION", 400) { review.setStatus(paid.id, OrderStatus.FAILED, null, admin) }
        expect("INVALID_ORDER_TRANSITION", 400) { review.setStatus(paid.id, OrderStatus.CANCELLED, null, admin) }

        // statuses that are results, not requests
        for (target in listOf(OrderStatus.PENDING, OrderStatus.REVIEW, OrderStatus.EXPIRED, OrderStatus.CHARGEBACK)) {
            expect("INVALID_ORDER_TRANSITION", 400) { review.setStatus(paid.id, target, null, admin) }
        }

        assertEquals(OrderStatus.COMPLETED, order(paid.id).status)
        assertEquals(OrderStatus.FAILED, order(failed.id).status)
        assertEquals(OrderStatus.REVIEW, order(inReview.id).status)
        assertEquals(1, ph.effects.of(paid.id).count { it == "IssueInvoice" })

        // the same status is a no-op for every status
        for ((id, status) in listOf(paid.id to OrderStatus.COMPLETED, failed.id to OrderStatus.FAILED, inReview.id to OrderStatus.REVIEW)) {
            assertFalse(review.setStatus(id, status, null, admin).moved)
        }
    }

    @Test
    fun `an unknown order is not found, a note is clipped to the timeline column`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val failure = runCatching { review.setStatus(9_999_999, OrderStatus.CANCELLED, null, admin) }.exceptionOrNull()

        assertTrue(failure is NoSuchElementException, "$failure")

        val order = orderOf(buy(fx.product(price = 1000, stock = 5)))

        review.setStatus(order.id, OrderStatus.CANCELLED, "x".repeat(2_000), admin)

        assertEquals(OrderReviewService.NOTE_MAX, timeline(order.id).single { it.toStatus == "CANCELLED" }.message!!.length)
    }
}
