package com.panomc.plugins.market.job

import com.panomc.plugins.market.core.order.OrderTimings
import com.panomc.plugins.market.core.payment.PaymentAttemptEvent
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.CreditSystemKey
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.OrderActorType
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.db.model.RedemptionState
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.service.CheckoutResult
import com.panomc.plugins.market.service.PayCaller
import com.panomc.plugins.market.service.PayRequest
import com.panomc.plugins.market.service.PaymentHarness
import com.panomc.plugins.market.service.PaymentStarter
import com.panomc.plugins.market.service.QuoteCaller
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.payment.PendingReason
import com.panomc.plugins.market.spi.payment.ReviewReason
import com.panomc.plugins.market.support.FakeClock
import com.panomc.plugins.market.support.FakePaymentProvider
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.DiscountUnit
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.Vertx
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `OrderExpiryJob` on a real MariaDB (MK-078; 06 section 12, 00 section 8.5, 17 section 4 S11): step 1 expires attempts (the grace of a `PROCESSING`
 * attempt, the built-ins left to the re-drive), step 2 expires orders (O6) and gives everything back (F-11), an order with an attempt that is being
 * resolved is left alone, a payment and the expiry racing each other end in one of the two allowed states (R-16), two calls at once move a row once.
 * The invariants I1 to I22 are checked after every test by the base class.
 */
class OrderExpiryJobIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var ph: PaymentHarness
    private lateinit var job: OrderExpiryJob
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
        job = newJob()
    }

    private val fx get() = w.fixtures
    private val h get() = ph.h
    private val fake get() = ph.fake

    private fun newJob() = OrderExpiryJob(w.clock, ph.db, ph.locks, w.orders, w.payments, ph.orderService, ph.payments, { pool })

    // ------------------------------------------------------------------------------------------------------ helpers

    private suspend fun buy(product: com.panomc.plugins.market.db.model.MarketProduct, method: String = "fake", caller: QuoteCaller = QuoteCaller.GUEST, vararg extra: Pair<String, Any?>): CheckoutResult =
        h.checkout(h.body("items" to listOf(h.line(product)), "paymentMethodId" to method, *extra), caller = caller)

    private suspend fun orderOf(result: CheckoutResult): MarketOrder = ph.order(result.order.getString("publicId"))

    private suspend fun user(name: String = "Alex", credit: Long = 0): Pair<TestUser, QuoteCaller> {
        val u = fx.user(name)

        h.emails[u.id] = "$name@example.com"

        if (credit > 0) fx.credit(u, credit)

        return u to QuoteCaller(u.id)
    }

    private suspend fun statuses(orderId: Long) = ph.attempts(orderId).map { it.status }

    private suspend fun statusChanges(orderId: Long, to: String) =
        w.orderEvents.getByOrderId(orderId, pool).filter { it.type == OrderEventType.STATUS_CHANGED && it.toStatus == to }

    // ======================================================================================= F-11: everything goes back

    @Test
    fun `F-11 an expired order releases stock, coupon use, redemption, credits and tells the gateway`(): Unit = runBlocking {
        h.config = h.config.copy(allowMixedCreditPayment = true)

        val (alex, caller) = user("Alex", credit = 5_000)
        val coupon = fx.coupon("ONCE", DiscountUnit.PERCENT, 1000, redeemLimit = 1)
        val product = fx.product(price = 10_000, stock = 1)

        fx.paymentMethod("fake")
        fake.caps = PaymentCapabilities().also { it.cancelPending = true }

        val order = orderOf(buy(product, "fake", caller, "couponCode" to "ONCE", "useCredits" to 50))
        val attempt = ph.attempts(order.id).single()

        assertEquals(0, w.products.getById(product.id, pool)!!.stock, "the last unit is held")
        assertEquals(1, w.coupons.getById(coupon.id, pool)!!.usedCount)
        assertEquals(0, fx.creditBalance(alex), "the 50 credits are held")
        assertEquals(RedemptionState.HELD, w.redemptions.getByOrderId(order.id, pool).single().state)

        // nothing is due yet
        assertEquals(0, job.runOnce())
        assertEquals(OrderStatus.PENDING, ph.order(order.id).status)

        w.clock.advance(61 * 60_000L)

        // step 1 expires the attempt (its expiresAt is the order's), step 2 the order
        assertEquals(2, job.runOnce())

        val expired = ph.order(order.id)

        assertEquals(OrderStatus.EXPIRED, expired.status)
        assertEquals(ReservationState.RELEASED, expired.reservationState)
        assertEquals(1, w.products.getById(product.id, pool)!!.stock, "the stock is back")
        assertEquals(0, w.coupons.getById(coupon.id, pool)!!.usedCount, "the coupon use is back")
        assertEquals(RedemptionState.RELEASED, w.redemptions.getByOrderId(order.id, pool).single().state)
        assertEquals(listOf(order.id), ph.ledger.releases, "the credits are released once")
        assertEquals(5_000, fx.creditBalance(alex))
        assertEquals(0, w.creditAccounts.getBySystemKey(CreditSystemKey.HOLD, pool)!!.balance)
        assertEquals(listOf(PaymentStatus.EXPIRED), statuses(order.id))

        val closed = ph.attempts(order.id).single()

        assertNull(closed.startPayload, "the stored start is dropped")
        assertNotNull(closed.closedAt)
        assertEquals(attempt.id, closed.id)
        assertEquals(1, statusChanges(order.id, "EXPIRED").size)
        assertEquals(OrderActorType.SYSTEM, statusChanges(order.id, "EXPIRED").single().actorType)

        // the gateway cancel is best effort and sent after the commit, for the attempt step 2 closed (here step 1 had closed it already)
        assertTrue(fake.calls(FakePaymentProvider.Op.CANCEL).size <= 1)

        // safe to run again: nothing moves twice
        assertEquals(0, job.runOnce())
        assertEquals(listOf(order.id), ph.ledger.releases)
        assertEquals(1, w.products.getById(product.id, pool)!!.stock)
    }

    @Test
    fun `an order whose attempt outlives the order window is expired by step 2 and the gateway is told about the attempt it closed`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        fake.caps = PaymentCapabilities().also { it.cancelPending = true }

        val product = fx.product(price = 1000, stock = 3)
        val order = orderOf(buy(product))

        // the attempt lives on after the order's own window (as a provider-given expiry may)
        sql("UPDATE `pano_market_payment` SET `expiresAt` = ? WHERE `orderId` = ?", w.clock.now() + 3 * 3_600_000L, order.id)
        w.clock.advance(61 * 60_000L)

        assertEquals(1, job.runOnce(), "only the order moves, the attempt is not due")
        assertEquals(OrderStatus.EXPIRED, ph.order(order.id).status)
        assertEquals(listOf(PaymentStatus.EXPIRED), statuses(order.id), "O6 closes the open attempts")
        assertEquals(1, fake.calls(FakePaymentProvider.Op.CANCEL).size, "cancelPending is called for the attempt O6 closed")
        assertEquals(3, w.products.getById(product.id, pool)!!.stock)
    }

    @Test
    fun `a failing gateway cancel is ignored, the order is expired anyway`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        fake.caps = PaymentCapabilities().also { it.cancelPending = true }
        fake.failNext(FakePaymentProvider.Op.CANCEL, com.panomc.plugins.market.spi.common.ProviderException(com.panomc.plugins.market.spi.common.ProviderErrorCode.GATEWAY_UNREACHABLE, "down"))

        val order = orderOf(buy(fx.product(price = 1000)))

        sql("UPDATE `pano_market_payment` SET `expiresAt` = ? WHERE `orderId` = ?", w.clock.now() + 3 * 3_600_000L, order.id)
        w.clock.advance(61 * 60_000L)

        assertEquals(1, job.runOnce())
        assertEquals(OrderStatus.EXPIRED, ph.order(order.id).status)
        assertEquals(1, fake.calls(FakePaymentProvider.Op.CANCEL).size)
    }

    // ============================================================================================ step 1: attempts

    @Test
    fun `an attempt past its expiry is expired on its own, the order stays payable and a pay opens a new attempt`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000, stock = 2)))

        sql("UPDATE `pano_market_payment` SET `expiresAt` = ? WHERE `orderId` = ?", w.clock.now() + 5 * 60_000L, order.id)
        w.clock.advance(6 * 60_000L)

        assertEquals(1, job.runOnce())

        val attempt = ph.attempts(order.id).single()

        assertEquals(PaymentStatus.EXPIRED, attempt.status)
        assertNull(attempt.startPayload)
        assertNotNull(attempt.closedAt)
        assertEquals(OrderStatus.PENDING, ph.order(order.id).status, "the order window is still open")
        assertEquals(ReservationState.HELD, ph.order(order.id).reservationState)

        val event = w.orderEvents.getByOrderId(order.id, pool).last { it.type == OrderEventType.PAYMENT_FAILED }

        assertTrue(event.data!!.contains("EXPIRED"), event.data)

        // the buyer can try again
        ph.payments.pay(ph.order(order.id), PayRequest("fake", null, null), PayCaller(), pool)

        assertEquals(listOf(PaymentStatus.EXPIRED, PaymentStatus.PENDING), statuses(order.id))
        assertEquals(0, job.runOnce())
    }

    @Test
    fun `an attempt that is not due, or whose expiry moved before the lock, is left alone`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000)))
        val attempt = ph.attempts(order.id).single()

        assertEquals(0, job.runOnce())

        w.clock.set(attempt.expiresAt!! - 1)
        assertEquals(0, job.runOnce(), "one millisecond early")

        w.clock.set(attempt.expiresAt!!)
        assertEquals(2, job.runOnce(), "at expiresAt (attempt, then order)")
    }

    @Test
    fun `a CREATED attempt of free or credits is not expired by step 1, the order step handles it once it is old`(): Unit = runBlocking {
        h.useStarter(PaymentStarter.NONE)

        val order = orderOf(h.checkout(h.body("items" to listOf(h.line(fx.product(price = 0))))))
        val attempt = ph.attempts(order.id).single()

        assertEquals(PaymentStatus.CREATED, attempt.status)
        assertEquals("free", attempt.providerId)

        // its own expiresAt is past, the order's too, but the attempt is younger than 2 minutes: nothing is touched (a start call may be in flight)
        sql("UPDATE `pano_market_payment` SET `expiresAt` = ? WHERE `id` = ?", w.clock.now() - 1, attempt.id)
        sql("UPDATE `pano_market_order` SET `expiresAt` = ? WHERE `id` = ?", w.clock.now() - 1, order.id)

        assertEquals(0, job.runOnce())
        assertEquals(PaymentStatus.CREATED, ph.attempts(order.id).single().status)
        assertEquals(OrderStatus.PENDING, ph.order(order.id).status)

        // two minutes later the order is idle: O6 closes the attempt together with the order
        w.clock.advance(OrderTimings.CREATED_IN_FLIGHT_MS)

        assertEquals(1, job.runOnce())
        assertEquals(OrderStatus.EXPIRED, ph.order(order.id).status)
        assertEquals(listOf(PaymentStatus.EXPIRED), statuses(order.id))
    }

    @Test
    fun `a young CREATED attempt keeps its order, an old one does not`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000)))

        // a start call in flight: CREATED, expiry in the future, the order window is over
        sql("UPDATE `pano_market_payment` SET `status` = 'CREATED', `startPayload` = NULL, `expiresAt` = ? WHERE `orderId` = ?", w.clock.now() + 3_600_000L, order.id)
        sql("UPDATE `pano_market_order` SET `expiresAt` = ? WHERE `id` = ?", w.clock.now() - 1, order.id)
        sql("UPDATE `pano_market_payment` SET `createdAt` = ? WHERE `orderId` = ?", w.clock.now() - 60_000L, order.id)

        assertEquals(0, job.runOnce())
        assertEquals(OrderStatus.PENDING, ph.order(order.id).status)

        sql("UPDATE `pano_market_payment` SET `createdAt` = ? WHERE `orderId` = ?", w.clock.now() - OrderTimings.CREATED_IN_FLIGHT_MS, order.id)

        assertEquals(1, job.runOnce())
        assertEquals(OrderStatus.EXPIRED, ph.order(order.id).status)
        assertEquals(listOf(PaymentStatus.EXPIRED), statuses(order.id))
    }

    // ================================================================================ the grace of a PROCESSING attempt

    @Test
    fun `a PROCESSING attempt the provider reported Pending lives 24 hours past its expiry, and the order waits for it`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000, stock = 2)))
        val attempt = ph.attempts(order.id).single()

        ph.payments.applyEvent(order.id, attempt.id, PaymentAttemptEvent.Pending)
        assertEquals(PaymentStatus.PROCESSING, ph.attempts(order.id).single().status)

        val expiresAt = ph.attempts(order.id).single().expiresAt!!

        w.clock.set(expiresAt + 3_600_000L)
        assertEquals(0, job.runOnce(), "inside the grace the attempt lives and the order is left alone")
        assertEquals(OrderStatus.PENDING, ph.order(order.id).status)

        w.clock.set(expiresAt + OrderTimings.PROCESSING_GRACE_MS - 1)
        assertEquals(0, job.runOnce())

        w.clock.set(expiresAt + OrderTimings.PROCESSING_GRACE_MS)
        assertEquals(2, job.runOnce(), "the attempt, then the order, in one call")
        assertEquals(listOf(PaymentStatus.EXPIRED), statuses(order.id))
        assertEquals(OrderStatus.EXPIRED, ph.order(order.id).status)
        assertEquals(ReservationState.RELEASED, ph.order(order.id).reservationState)
    }

    @Test
    fun `a longPending provider gets 14 days of grace`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        fake.caps = PaymentCapabilities().also { it.longPending = true }

        val order = orderOf(buy(fx.product(price = 1000)))
        val attempt = ph.attempts(order.id).single()

        ph.payments.applyEvent(order.id, attempt.id, PaymentAttemptEvent.Pending)

        val expiresAt = ph.attempts(order.id).single().expiresAt!!

        w.clock.set(expiresAt + OrderTimings.LONG_PROCESSING_GRACE_MS - 1)
        assertEquals(0, job.runOnce())
        assertEquals(PaymentStatus.PROCESSING, ph.attempts(order.id).single().status)

        w.clock.set(expiresAt + OrderTimings.LONG_PROCESSING_GRACE_MS)
        assertEquals(2, job.runOnce())
        assertEquals(OrderStatus.EXPIRED, ph.order(order.id).status)
    }

    @Test
    fun `a bank transfer attempt that is PROCESSING only because of the buyer's notice gets no grace`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val product = fx.product(price = 1000, stock = 2)
        val order = orderOf(buy(product))

        // the attempt of a bank transfer order (the provider is the built-in one, 02 section 12)
        sql("UPDATE `pano_market_payment` SET `providerId` = 'bank-transfer' WHERE `orderId` = ?", order.id)

        val attempt = ph.attempts(order.id).single()

        assertEquals("bank-transfer", attempt.providerId)

        // what POST .../bank-transfer/notify does (MK-094): Pending(AWAITING_BANK), the expiry is not extended
        ph.payments.applyEvent(order.id, attempt.id, PaymentAttemptEvent.Pending)

        val processing = ph.attempts(order.id).single()

        assertEquals(PaymentStatus.PROCESSING, processing.status)
        assertEquals(attempt.expiresAt, processing.expiresAt, "the notice does not extend the reservation")

        w.clock.set(processing.expiresAt!! - 1)
        assertEquals(0, job.runOnce())

        w.clock.set(processing.expiresAt!!)
        assertEquals(2, job.runOnce(), "no grace: attempt and order expire at the normal time")
        assertEquals(OrderStatus.EXPIRED, ph.order(order.id).status)
        assertEquals(2, w.products.getById(product.id, pool)!!.stock)
    }

    // ================================================================================== orders that must be left alone

    @Test
    fun `an order in REVIEW, a paid order and an order that is not due are never expired`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val paid = orderOf(buy(fx.product(price = 1000)))
        val review = orderOf(buy(fx.product(price = 1000)))
        val waiting = orderOf(buy(fx.product(price = 1000)))

        ph.succeed(paid.id, ph.attempts(paid.id).single())
        ph.payments.applyEvent(review.id, ph.attempts(review.id).single().id, PaymentAttemptEvent.NeedsReview(ReviewReason.FRAUD_REVIEW))

        assertEquals(OrderStatus.COMPLETED, ph.order(paid.id).status)
        assertEquals(OrderStatus.REVIEW, ph.order(review.id).status)
        assertNull(ph.order(review.id).expiresAt, "a review pauses the expiry")

        w.clock.advance(30 * 86_400_000L)

        // only the waiting order (and its open attempt) is due
        assertEquals(2, job.runOnce())
        assertEquals(OrderStatus.COMPLETED, ph.order(paid.id).status)
        assertEquals(OrderStatus.REVIEW, ph.order(review.id).status)
        assertEquals(OrderStatus.EXPIRED, ph.order(waiting.id).status)
        assertEquals(PaymentStatus.REVIEW, ph.attempts(review.id).single().status)
        assertEquals(PaymentStatus.SUCCEEDED, ph.attempts(paid.id).single().status)
    }

    @Test
    fun `an order with an attempt in PROCESSING is not even a candidate, so a pile of them cannot starve the others`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val stuck = (1..4).map { orderOf(buy(fx.product(price = 1000))) }

        for (o in stuck) ph.payments.applyEvent(o.id, ph.attempts(o.id).single().id, PaymentAttemptEvent.Pending)

        val plain = orderOf(buy(fx.product(price = 1000)))
        val small = OrderExpiryJob(w.clock, ph.db, ph.locks, w.orders, w.payments, ph.orderService, ph.payments, { pool }, batch = 2)

        // two minutes past the order window, inside the 24 h grace of the PROCESSING attempts
        w.clock.advance(62 * 60_000L)

        assertEquals(2, small.runOnce(), "the one order that can expire (and its attempt), found through a batch of 2")
        assertEquals(OrderStatus.EXPIRED, ph.order(plain.id).status)

        for (o in stuck) assertEquals(OrderStatus.PENDING, ph.order(o.id).status)
    }

    // ================================================================================ two calls at once, R-16

    @Test
    fun `two runOnce at the same moment move each attempt and order once`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val product = fx.product(price = 1000, stock = 12)
        val orders = (1..12).map { orderOf(buy(product)) }

        assertEquals(0, w.products.getById(product.id, pool)!!.stock)

        w.clock.advance(61 * 60_000L)

        val results = Race.run(2) { newJob().runOnce() }

        for (r in results) assertTrue(r.isSuccess, "${r.exceptionOrNull()}")

        assertEquals(24, results.sumOf { it.getOrThrow() }, "12 attempts and 12 orders, each moved by exactly one of the two calls")
        assertEquals(12, w.products.getById(product.id, pool)!!.stock, "released once, not twice")

        for (o in orders) {
            assertEquals(OrderStatus.EXPIRED, ph.order(o.id).status)
            assertEquals(1, statusChanges(o.id, "EXPIRED").size, "one EXPIRED transition for order ${o.id}")
            assertEquals(listOf(PaymentStatus.EXPIRED), statuses(o.id))
        }
    }

    @Test
    fun `R-16 a success and the expiry in the same instant end COMPLETED with the stock committed, or REVIEW (LATE) with it released`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        var completed = 0
        var review = 0

        repeat(Race.rounds) { round ->
            val product = fx.product(price = 1000, stock = 1)
            val order = orderOf(buy(product))
            val attempt = ph.attempts(order.id).single()

            w.clock.advance(61 * 60_000L)

            val results = Race.run(2) { i ->
                if (i == 0) job.runOnce() else ph.succeed(order.id, attempt).let { 0 }
            }

            for (r in results) assertTrue(r.isSuccess, "round $round: ${r.exceptionOrNull()}")

            val end = ph.order(order.id)
            val stock = w.products.getById(product.id, pool)!!.stock

            when (end.status) {
                OrderStatus.COMPLETED -> {
                    completed++

                    assertEquals(ReservationState.COMMITTED, end.reservationState, "round $round")
                    assertEquals(0, stock, "round $round: the unit is sold")
                    assertEquals(listOf(PaymentStatus.SUCCEEDED), statuses(order.id), "round $round")
                    assertTrue(ph.effects.of(order.id).isNotEmpty(), "round $round: the effects of O2 ran")
                }

                OrderStatus.REVIEW -> {
                    review++

                    assertEquals("LATE", end.reviewReason, "round $round")
                    assertEquals(ReservationState.RELEASED, end.reservationState, "round $round")
                    assertEquals(1, stock, "round $round: the unit is back")
                    assertTrue(ph.effects.of(order.id).isEmpty(), "round $round: nothing is delivered while the order is in review")
                }

                else -> throw AssertionError("round $round: ${end.status} is neither COMPLETED nor REVIEW (LATE)")
            }
        }

        assertEquals(Race.rounds, completed + review)
    }

    // ================================================================================== a job can fail, the step goes on

    @Test
    fun `a provider that throws a LinkageError while it describes itself does not stop the expiry of the other orders`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val broken = FakePaymentProvider("broken")
        val lookup = com.panomc.plugins.market.support.StaticProviderLookup(listOf(brokenDescribing(broken), ph.continuable, com.panomc.plugins.market.provider.FreeProvider(), com.panomc.plugins.market.provider.CreditsProvider()))
        val stuckOrder = orderOf(buy(fx.product(price = 1000)))
        val healthy = orderOf(buy(fx.product(price = 1000)))

        // an attempt of the broken provider in PROCESSING past its expiry: the job cannot look up its grace
        sql("UPDATE `pano_market_payment` SET `providerId` = 'broken', `status` = 'PROCESSING' WHERE `orderId` = ?", stuckOrder.id)
        sql("UPDATE `pano_market_order` SET `expiresAt` = ? WHERE `id` = ?", w.clock.now() + 10 * 86_400_000L, stuckOrder.id)

        // the payment service sees the broken provider
        val service = com.panomc.plugins.market.service.PaymentService(
            db = ph.db, locks = ph.locks, clock = w.clock, ids = w.ids, config = { h.config.toConfig() }, orders = w.orders, orderItems = w.orderItems, orderEvents = w.orderEvents,
            payments = w.payments, methods = w.paymentMethods, creditAccounts = w.creditAccounts, currencyRates = w.currencyRates, lookup = lookup, cipher = ph.cipher,
            contexts = com.panomc.plugins.market.service.PaymentContexts { provider, settings: ProviderSettings, testMode -> com.panomc.plugins.market.spi.testkit.TestContexts.payment(provider.id, settings, vertx, testMode) },
            orderService = ph.orderService, site = { com.panomc.plugins.market.spi.testkit.TestContexts.defaultSite() }, readClient = { pool }, products = w.products, entitlements = w.entitlements
        )
        val guarded = OrderExpiryJob(w.clock, ph.db, ph.locks, w.orders, w.payments, ph.orderService, service, { pool })

        w.clock.advance(61 * 60_000L)

        assertEquals(2, guarded.runOnce(), "the healthy order's attempt and the order itself; the broken one is skipped, not fatal")
        assertEquals(OrderStatus.EXPIRED, ph.order(healthy.id).status)
        assertEquals(OrderStatus.PENDING, ph.order(stuckOrder.id).status)
    }

    /** A provider whose `capabilities` fails with the error of a host that lacks an optional class. */
    private fun brokenDescribing(inner: FakePaymentProvider): com.panomc.plugins.market.spi.payment.PaymentProvider =
        object : com.panomc.plugins.market.spi.payment.PaymentProvider by inner {
            override fun capabilities(settings: ProviderSettings): PaymentCapabilities = throw NoClassDefFoundError("com/example/Optional")
        }
}
