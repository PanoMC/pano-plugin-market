package com.panomc.plugins.market.job

import com.panomc.plugins.market.core.order.OrderTimings
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.service.CheckoutResult
import com.panomc.plugins.market.service.PaymentHarness
import com.panomc.plugins.market.service.PaymentStarter
import com.panomc.plugins.market.service.QuoteCaller
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentQueryResult
import com.panomc.plugins.market.spi.payment.PaymentTarget
import com.panomc.plugins.market.spi.payment.PendingReason
import com.panomc.plugins.market.support.FakePaymentProvider
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.Vertx
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** The reconcile ladder and horizon of 02 section 8 as a pure function, no database. */
class ReconcileScheduleTest {
    private val min = ReconcileSchedule.MINUTE_MS
    private val hour = ReconcileSchedule.HOUR_MS
    private val created = 1_000_000_000L

    @Test
    fun `the first four queries land at 1, 3, 10 and 30 minutes, then hourly`() {
        // query 1 runs at +1 min (scheduled by the start), 2 at +3, 3 at +10, 4 at +30, then +90, +150
        assertEquals(created + 3 * min, ReconcileSchedule.next(created + 1 * min, created, created + hour, 1, false))
        assertEquals(created + 10 * min, ReconcileSchedule.next(created + 3 * min, created, created + hour, 2, false))
        assertEquals(created + 30 * min, ReconcileSchedule.next(created + 10 * min, created, created + hour, 3, false))
        assertEquals(created + 90 * min, ReconcileSchedule.next(created + 30 * min, created, created + hour, 4, false))
        assertEquals(created + 150 * min, ReconcileSchedule.next(created + 90 * min, created, created + hour, 5, false))
    }

    @Test
    fun `an attempt older than a day is queried every 6 hours`() {
        val long = created + 14 * OrderTimings.DAY_MS

        assertEquals(created + 23 * hour + hour, ReconcileSchedule.next(created + 23 * hour, created, long, 30, true))
        assertEquals(created + 24 * hour + 6 * hour, ReconcileSchedule.next(created + 24 * hour, created, long, 31, true))
        assertEquals(created + 48 * hour + 6 * hour, ReconcileSchedule.next(created + 48 * hour, created, long, 40, true))
    }

    @Test
    fun `the horizon is expiresAt plus 24 hours, or 14 days for longPending, and the last slot is the horizon itself`() {
        val expires = created + hour

        assertEquals(expires + OrderTimings.DAY_MS, ReconcileSchedule.horizonEnd(created, expires, false))
        assertEquals(expires + 14 * OrderTimings.DAY_MS, ReconcileSchedule.horizonEnd(created, expires, true))
        assertEquals(created + 2 * OrderTimings.DAY_MS, ReconcileSchedule.horizonEnd(created, null, false), "no expiresAt: counted from the creation")

        val end = ReconcileSchedule.horizonEnd(created, expires, false)

        assertEquals(end, ReconcileSchedule.next(end - 10 * min, created, expires, 50, false), "the slot is cut at the horizon")
        assertNull(ReconcileSchedule.next(end, created, expires, 51, false), "at the horizon nothing follows")
        assertNull(ReconcileSchedule.next(end + 1, created, expires, 51, false))
    }

    @Test
    fun `a provider hint is honoured within 30 seconds and 6 hours`() {
        val now = 5_000_000L

        assertEquals(now + 600_000L, ReconcileSchedule.hinted(now, 600))
        assertEquals(now + ReconcileSchedule.HINT_MIN_MS, ReconcileSchedule.hinted(now, 1))
        assertEquals(now + ReconcileSchedule.HINT_MAX_MS, ReconcileSchedule.hinted(now, 10_000_000))
        assertNull(ReconcileSchedule.hinted(now, null))
        assertNull(ReconcileSchedule.hinted(now, 0))
        assertNull(ReconcileSchedule.hinted(now, -5))
    }
}

/**
 * `PaymentReconcileJob` on a real MariaDB (MK-078; 02 sections 8 and 10, 06 section 9.2 step 6): a payment the gateway took without any webhook is found
 * by the query (F-03), the ladder 1, 3, 10, 30 min, hourly, 6-hourly runs on a `FakeClock`, `unknown()` and every kind of provider failure leave the
 * attempt alone, `CREATED` attempts of `free` / `credits` are re-driven after a crash, a `LinkageError` out of a provider stays in its row, and two
 * `runOnce` at the same moment handle each attempt once.
 */
class PaymentReconcileJobIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var ph: PaymentHarness
    private lateinit var job: PaymentReconcileJob
    private val vertx: Vertx = Vertx.vertx()

    override val poolSize: Int = 24

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

    private fun newJob() = PaymentReconcileJob(w.clock, ph.payments, w.orders, w.payments, { pool })

    private fun queryable(configure: PaymentCapabilities.() -> Unit = {}) {
        fake.caps = PaymentCapabilities().also {
            it.statusQuery = true
            it.configure()
        }
    }

    private suspend fun buy(product: com.panomc.plugins.market.db.model.MarketProduct, method: String = "fake", caller: QuoteCaller = QuoteCaller.GUEST, vararg extra: Pair<String, Any?>): CheckoutResult =
        h.checkout(h.body("items" to listOf(h.line(product)), "paymentMethodId" to method, *extra), caller = caller)

    private suspend fun orderOf(result: CheckoutResult): MarketOrder = ph.order(result.order.getString("publicId"))

    private suspend fun attempt(orderId: Long): MarketPayment = ph.attempts(orderId).last()

    private fun paid(request: com.panomc.plugins.market.spi.payment.QueryPaymentRequest, amount: Long = request.attempt.amount.amount): PaymentQueryResult =
        PaymentQueryResult.of(PaymentEvent.Succeeded(PaymentTarget.Attempt(request.attempt.id), Money(amount, request.attempt.amount.currency)))

    // ============================================================================================ F-03

    @Test
    fun `F-03 a payment the gateway took without any webhook is completed by the reconcile query`(): Unit = runBlocking {
        queryable()
        fx.paymentMethod("fake")
        fake.onQuery = { paid(it) }

        val order = orderOf(buy(fx.product(price = 1500, stock = 2)))
        val started = attempt(order.id)

        assertEquals(PaymentStatus.PENDING, started.status)
        assertEquals(started.startedAt!! + 60_000L, started.nextQueryAt, "the first query is scheduled a minute after the start")
        assertEquals(0, job.runOnce(), "not due yet")
        assertTrue(fake.calls(FakePaymentProvider.Op.QUERY).isEmpty())

        w.clock.advance(60_000L)

        assertEquals(1, job.runOnce())

        val done = ph.order(order.id)
        val paidAttempt = attempt(order.id)

        assertEquals(OrderStatus.COMPLETED, done.status)
        assertEquals(PaymentStatus.SUCCEEDED, paidAttempt.status)
        assertEquals(1500, paidAttempt.paidAmount)
        assertEquals(1, paidAttempt.queryCount)
        assertEquals(w.clock.now(), paidAttempt.lastQueriedAt)
        assertTrue(ph.effects.of(order.id).isNotEmpty(), "the effects of O2 ran")
        assertEquals("RECONCILE", fake.calls(FakePaymentProvider.Op.QUERY).single().let { (it.request as com.panomc.plugins.market.spi.payment.QueryPaymentRequest).reason.name })

        // nothing more to do for a paid attempt, whatever its nextQueryAt says
        sql("UPDATE `pano_market_payment` SET `nextQueryAt` = ? WHERE `id` = ?", w.clock.now() - 1, paidAttempt.id)

        assertEquals(0, job.runOnce())
        assertEquals(1, fake.calls(FakePaymentProvider.Op.QUERY).size)
    }

    @Test
    fun `a gateway that reports less than was due sends the order to review, the reconcile never completes it`(): Unit = runBlocking {
        queryable()
        fx.paymentMethod("fake")
        fake.onQuery = { paid(it, amount = 600) }

        val order = orderOf(buy(fx.product(price = 1000)))

        w.clock.advance(60_000L)
        assertEquals(1, job.runOnce())

        assertEquals(OrderStatus.REVIEW, ph.order(order.id).status)
        assertEquals("UNDERPAID", ph.order(order.id).reviewReason)
        assertEquals(PaymentStatus.REVIEW, attempt(order.id).status)
        assertTrue(ph.effects.of(order.id).isEmpty(), "nothing is delivered")
    }

    @Test
    fun `a Pending answer moves the attempt to PROCESSING and the schedule goes on`(): Unit = runBlocking {
        queryable()
        fx.paymentMethod("fake")
        fake.onQuery = { PaymentQueryResult.of(PaymentEvent.Pending(PaymentTarget.Attempt(it.attempt.id), PendingReason.AWAITING_CONFIRMATIONS)) }

        val order = orderOf(buy(fx.product(price = 1000)))

        w.clock.advance(60_000L)
        assertEquals(1, job.runOnce())

        val processing = attempt(order.id)

        assertEquals(PaymentStatus.PROCESSING, processing.status)
        assertEquals(w.clock.now() + 2 * ReconcileSchedule.MINUTE_MS, processing.nextQueryAt)
        assertEquals(OrderStatus.PENDING, ph.order(order.id).status)
    }

    // ================================================================================= unknown() never fails an attempt

    @Test
    fun `unknown, unsupported and every provider failure leave the attempt and the order alone, the next slot asks again`(): Unit = runBlocking {
        queryable()
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000)))
        val answers = listOf<(com.panomc.plugins.market.spi.payment.QueryPaymentRequest) -> PaymentQueryResult>(
            { PaymentQueryResult.unknown() },
            { PaymentQueryResult.unsupported() },
            { PaymentQueryResult.of() },
            { throw ProviderException(ProviderErrorCode.GATEWAY_UNREACHABLE, "down") },
            { throw IllegalStateException("boom") },
            { throw NoClassDefFoundError("com/example/Optional") },
            { throw LinkageError("older host") }
        )

        for ((i, answer) in answers.withIndex()) {
            fake.onQuery = answer

            val before = attempt(order.id)

            w.clock.set(before.nextQueryAt ?: error("round $i: the attempt has no next slot"))

            assertEquals(1, job.runOnce(), "round $i: the attempt was claimed and asked")

            val after = attempt(order.id)

            assertEquals(PaymentStatus.PENDING, after.status, "round $i: the attempt is not failed")
            assertEquals(OrderStatus.PENDING, ph.order(order.id).status, "round $i")
            assertEquals(before.queryCount + 1, after.queryCount, "round $i")
            assertNotNull(after.nextQueryAt, "round $i: still scheduled")
            assertTrue(after.nextQueryAt!! > w.clock.now(), "round $i: in the future")
            assertNull(after.failureCode, "round $i")
        }

        assertEquals(answers.size, fake.calls(FakePaymentProvider.Op.QUERY).size)
    }

    @Test
    fun `the gateway's own poll hint moves the next slot`(): Unit = runBlocking {
        queryable()
        fx.paymentMethod("fake")
        fake.onQuery = { PaymentQueryResult.unknown().also { r -> r.pollAgainAfterSeconds = 600 } }

        val order = orderOf(buy(fx.product(price = 1000)))

        w.clock.advance(60_000L)
        assertEquals(1, job.runOnce())
        assertEquals(w.clock.now() + 600_000L, attempt(order.id).nextQueryAt)

        fake.onQuery = { PaymentQueryResult.unknown().also { r -> r.pollAgainAfterSeconds = 5 } }
        w.clock.set(attempt(order.id).nextQueryAt!!)
        assertEquals(1, job.runOnce())
        assertEquals(w.clock.now() + ReconcileSchedule.HINT_MIN_MS, attempt(order.id).nextQueryAt, "a hint below 30 s is raised to 30 s")
    }

    // ========================================================================================== the cadence

    private suspend fun walk(order: MarketOrder, until: Int = 400): List<Long> {
        val times = CopyOnWriteArrayList<Long>()

        fake.onQuery = { times += w.clock.now(); PaymentQueryResult.unknown() }

        var guard = 0

        while (guard++ < until) {
            val next = attempt(order.id).nextQueryAt ?: break

            w.clock.set(next)
            assertEquals(1, job.runOnce(), "slot $guard at ${next - order.createdAt} ms")
        }

        return times
    }

    @Test
    fun `the cadence is 1, 3, 10, 30 minutes, then hourly, and it ends at expiresAt plus 24 hours`(): Unit = runBlocking {
        queryable()
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000)))
        val created = attempt(order.id).createdAt
        val expiresAt = attempt(order.id).expiresAt!!
        val offsetsMin = walk(order).map { (it - created) / 60_000L }

        assertEquals(listOf(1L, 3L, 10L, 30L, 90L, 150L), offsetsMin.take(6), "1, 3, 10 and 30 minutes, then one hour apart")

        for (i in 5 until offsetsMin.size - 1) {
            val gap = offsetsMin[i] - offsetsMin[i - 1]

            assertTrue(gap == 60L || gap < 60L, "gap $gap before slot $i is hourly (the last slot is cut at the horizon)")
        }

        assertEquals(expiresAt + OrderTimings.DAY_MS, created + offsetsMin.last() * 60_000L, "the last query is the horizon: expiresAt + 24 h")
        assertNull(attempt(order.id).nextQueryAt, "nothing is scheduled after the horizon")

        val calls = fake.calls(FakePaymentProvider.Op.QUERY).size

        w.clock.advance(7 * OrderTimings.DAY_MS)
        assertEquals(0, job.runOnce())
        assertEquals(calls, fake.calls(FakePaymentProvider.Op.QUERY).size, "no more queries")
        assertEquals(PaymentStatus.PENDING, attempt(order.id).status, "unknown never failed it")
    }

    @Test
    fun `a longPending provider is queried hourly for the first day and every 6 hours after it, up to 14 days past expiry`(): Unit = runBlocking {
        queryable {
            longPending = true
            paymentWindowMinutes = 7 * 24 * 60
        }
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000)))
        val created = attempt(order.id).createdAt
        val expiresAt = attempt(order.id).expiresAt!!

        assertEquals(created + 7 * OrderTimings.DAY_MS, expiresAt)

        val times = walk(order)
        val offsetsMin = times.map { (it - created) / 60_000L }

        assertEquals(listOf(1L, 3L, 10L, 30L, 90L), offsetsMin.take(5))

        val dayOne = offsetsMin.filter { it < 24 * 60 }
        val later = offsetsMin.filter { it >= 24 * 60 }

        for (i in 5 until dayOne.size) assertEquals(60L, dayOne[i] - dayOne[i - 1], "hourly while the attempt is younger than a day")
        for (i in 1 until later.size - 1) assertEquals(6 * 60L, later[i] - later[i - 1], "every 6 hours after that")

        assertEquals(expiresAt + 14 * OrderTimings.DAY_MS, times.last(), "the horizon of a longPending provider")
        assertNull(attempt(order.id).nextQueryAt)
    }

    @Test
    fun `the status poll of the order page counts as a query, the ladder only moves forward`(): Unit = runBlocking {
        queryable()
        fx.paymentMethod("fake")
        fake.onQuery = { PaymentQueryResult.unknown() }

        val order = orderOf(buy(fx.product(price = 1000)))

        w.clock.advance(11_000L)
        ph.payments.status(ph.order(order.id), owner = true, sqlClient = pool)

        assertEquals(1, attempt(order.id).queryCount)

        w.clock.set(attempt(order.id).nextQueryAt!!)
        assertEquals(1, job.runOnce())
        assertEquals(2, attempt(order.id).queryCount)
        assertEquals(w.clock.now() + 7 * ReconcileSchedule.MINUTE_MS, attempt(order.id).nextQueryAt, "the second ladder step, not the first again")
    }

    // ======================================================================== providers that cannot or will not be asked

    @Test
    fun `an attempt of a provider without statusQuery is never queried and a stale slot is cleared`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000)))
        val started = attempt(order.id)

        assertNull(started.nextQueryAt, "no statusQuery: no first slot")

        w.clock.advance(OrderTimings.DAY_MS)
        assertEquals(0, job.runOnce())

        // a slot left over from a time the provider could be asked: claimed, cleared, nobody is called
        sql("UPDATE `pano_market_payment` SET `nextQueryAt` = ? WHERE `id` = ?", w.clock.now() - 1, started.id)

        assertEquals(1, job.runOnce())
        assertNull(attempt(order.id).nextQueryAt)
        assertTrue(fake.calls(FakePaymentProvider.Op.QUERY).isEmpty())
        assertEquals(0, job.runOnce())
    }

    @Test
    fun `an attempt of a provider that is not registered any more keeps its schedule and is not touched`(): Unit = runBlocking {
        queryable()
        fx.paymentMethod("fake")

        val order = orderOf(buy(fx.product(price = 1000)))

        sql("UPDATE `pano_market_payment` SET `providerId` = 'ghost' WHERE `orderId` = ?", order.id)
        w.clock.advance(60_000L)

        assertEquals(1, job.runOnce())

        val after = attempt(order.id)

        assertEquals(PaymentStatus.PENDING, after.status)
        assertEquals(w.clock.now() + 2 * ReconcileSchedule.MINUTE_MS, after.nextQueryAt)
        assertTrue(fake.calls(FakePaymentProvider.Op.QUERY).isEmpty())
    }

    @Test
    fun `a CREATED attempt of a gateway whose start timed out is queried once it is past the in-flight window`(): Unit = runBlocking {
        queryable()
        fx.paymentMethod("fake")
        fake.onQuery = { paid(it) }

        val order = orderOf(buy(fx.product(price = 1000)))

        // the start deadline passed: CREATED with failureCode TIMEOUT, no slot yet
        sql("UPDATE `pano_market_payment` SET `status` = 'CREATED', `startPayload` = NULL, `nextQueryAt` = NULL, `failureCode` = 'TIMEOUT' WHERE `orderId` = ?", order.id)
        sql("UPDATE `pano_market_payment` SET `createdAt` = ? WHERE `orderId` = ?", w.clock.now() - 60_000L, order.id)

        assertEquals(0, job.runOnce(), "a start call may still be in flight")

        sql("UPDATE `pano_market_payment` SET `createdAt` = ? WHERE `orderId` = ?", w.clock.now() - OrderTimings.CREATED_IN_FLIGHT_MS, order.id)

        assertEquals(1, job.runOnce())
        assertEquals(OrderStatus.COMPLETED, ph.order(order.id).status, "a success the gateway reports for a CREATED attempt completes the order")
        assertEquals(PaymentStatus.SUCCEEDED, attempt(order.id).status)
    }

    // ======================================================================================== re-drive of the built-ins

    @Test
    fun `a free order whose start never ran is completed by the job after 30 seconds, and only once`(): Unit = runBlocking {
        h.useStarter(PaymentStarter.NONE)

        val order = orderOf(h.checkout(h.body("items" to listOf(h.line(fx.product(price = 0))))))

        assertEquals(PaymentStatus.CREATED, attempt(order.id).status)

        w.clock.advance(PaymentReconcileJob.REDRIVE_AFTER_MS - 1)
        assertEquals(0, job.runOnce(), "the crash window of checkout is not over")
        assertEquals(OrderStatus.PENDING, ph.order(order.id).status)

        w.clock.advance(1)
        assertEquals(1, job.runOnce())
        assertEquals(OrderStatus.COMPLETED, ph.order(order.id).status)
        assertEquals(PaymentStatus.SUCCEEDED, attempt(order.id).status)
        assertEquals(1, ph.effects.of(order.id).count { it == "IssueInvoice" })

        assertEquals(0, job.runOnce())
        assertEquals(1, ph.effects.of(order.id).count { it == "IssueInvoice" })
    }

    @Test
    fun `a full-credit order whose start never ran is captured and completed by the job, one capture`(): Unit = runBlocking {
        val u = fx.user("Alex")

        h.emails[u.id] = "alex@example.com"
        fx.credit(u, 5_000)

        h.useStarter(PaymentStarter.NONE)

        val order = orderOf(h.checkout(h.body("items" to listOf(h.line(fx.product(price = 3000, creditPrice = 2500))), "paymentMethodId" to "credits", "payWithCredits" to true), caller = QuoteCaller(u.id)))

        assertEquals(OrderStatus.PENDING, order.status)
        assertEquals(2500, fx.creditBalance(u), "held")

        w.clock.advance(31_000L)

        assertEquals(1, job.runOnce())
        assertEquals(OrderStatus.COMPLETED, ph.order(order.id).status)
        assertEquals(listOf(order.id), ph.ledger.captures)
        assertEquals(0, job.runOnce())
        assertEquals(listOf(order.id), ph.ledger.captures)
    }

    @Test
    fun `two runOnce at the same moment re-drive a CREATED attempt once`(): Unit = runBlocking {
        h.useStarter(PaymentStarter.NONE)

        val orders = (1..6).map { orderOf(h.checkout(h.body("items" to listOf(h.line(fx.product(price = 0)))))) }

        w.clock.advance(31_000L)

        val results = Race.run(2) { newJob().runOnce() }

        for (r in results) assertTrue(r.isSuccess, "${r.exceptionOrNull()}")

        assertEquals(6, results.sumOf { it.getOrThrow() }, "each attempt claimed by exactly one call")

        for (o in orders) {
            assertEquals(OrderStatus.COMPLETED, ph.order(o.id).status)
            assertEquals(1, ph.effects.of(o.id).count { it == "IssueInvoice" }, "order ${o.id} was completed once")
        }
    }

    // ======================================================================================== races and isolation

    @Test
    fun `two runOnce at the same moment query each due attempt once`(): Unit = runBlocking {
        queryable()
        fx.paymentMethod("fake")

        val calls = AtomicInteger()

        fake.onQuery = { calls.incrementAndGet(); paid(it) }

        val orders = (1..8).map { orderOf(buy(fx.product(price = 1000))) }

        w.clock.advance(60_000L)

        val results = Race.run(2) { newJob().runOnce() }

        for (r in results) assertTrue(r.isSuccess, "${r.exceptionOrNull()}")

        assertEquals(8, results.sumOf { it.getOrThrow() })
        assertEquals(8, calls.get(), "the provider is asked once per attempt")

        for (o in orders) {
            assertEquals(OrderStatus.COMPLETED, ph.order(o.id).status)
            assertEquals(1, ph.attempts(o.id).single().queryCount)
        }
    }

    @Test
    fun `a LinkageError out of one provider call stays in its row, the others are reconciled`(): Unit = runBlocking {
        queryable()
        fx.paymentMethod("fake")

        val first = orderOf(buy(fx.product(price = 1000)))
        val second = orderOf(buy(fx.product(price = 1000)))
        val third = orderOf(buy(fx.product(price = 1000)))
        val bad = AtomicInteger()

        fake.onQuery = {
            if (it.attempt.orderId == second.id) {
                bad.incrementAndGet()

                throw NoClassDefFoundError("com/example/Optional")
            }

            paid(it)
        }

        w.clock.advance(60_000L)

        assertEquals(3, job.runOnce(), "all three were claimed")
        assertEquals(1, bad.get())
        assertEquals(OrderStatus.COMPLETED, ph.order(first.id).status)
        assertEquals(OrderStatus.PENDING, ph.order(second.id).status)
        assertEquals(PaymentStatus.PENDING, attempt(second.id).status)
        assertEquals(OrderStatus.COMPLETED, ph.order(third.id).status)

        // the broken one is asked again at its next slot
        w.clock.set(attempt(second.id).nextQueryAt!!)
        fake.onQuery = { paid(it) }

        assertEquals(1, job.runOnce())
        assertEquals(OrderStatus.COMPLETED, ph.order(second.id).status)
    }

    @Test
    fun `a payment event that cannot be applied rolls back, is logged and the attempt is asked again at its next slot`(): Unit = runBlocking {
        queryable()
        fx.paymentMethod("fake")
        fake.onQuery = { paid(it) }

        val order = orderOf(buy(fx.product(price = 1000)))

        ph.effects.failOn = "IssueInvoice"
        w.clock.advance(60_000L)

        assertEquals(1, job.runOnce(), "claimed; the failure of the effect stays in the row")
        assertEquals(OrderStatus.PENDING, ph.order(order.id).status, "the whole success rolled back")
        assertEquals(PaymentStatus.PENDING, attempt(order.id).status)
        assertEquals(1, attempt(order.id).queryCount)

        ph.effects.failOn = null
        w.clock.set(attempt(order.id).nextQueryAt!!)

        assertEquals(1, job.runOnce())
        assertEquals(OrderStatus.COMPLETED, ph.order(order.id).status)
    }
}
