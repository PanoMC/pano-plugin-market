package com.panomc.plugins.market.job

import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.service.CheckoutResult
import com.panomc.plugins.market.service.PaymentHarness
import com.panomc.plugins.market.service.QuoteCaller
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentQueryResult
import com.panomc.plugins.market.spi.payment.PaymentTarget
import com.panomc.plugins.market.support.FakeClock
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.Vertx
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * `MarketScheduler` (MK-078; 00 section 8.5, 17 section 4 S11): the cadence of each job on a `FakeClock`, the guarantee that no job body can stop the tick
 * (`LinkageError` and `NoClassDefFoundError` included), no re-entrancy, the gate, two ticks at once, the real Vert.x timer, and the scheduler driving the
 * real expiry and reconcile jobs against a MariaDB. The database part runs through the same `PaymentHarness` as the job tests.
 */
class MarketSchedulerIT : MarketDaoITBase() {
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

    private fun counting(name: String, everyMs: Long, handled: Int = 1, counter: AtomicInteger = AtomicInteger()) =
        MarketScheduler.Job(name, everyMs) { counter.incrementAndGet(); handled } to counter

    // =================================================================================================== cadence

    @Test
    fun `each job runs on its own cadence, the first time on the first tick`(): Unit = runBlocking {
        val clock = FakeClock()
        val (fast, fastCount) = counting("fast", 15_000L)
        val (slow, slowCount) = counting("slow", 30_000L)
        val (everyTick, everyCount) = counting("every-tick", MarketScheduler.TICK_MS)
        val scheduler = MarketScheduler(clock, listOf(fast, slow, everyTick))

        assertEquals(3, scheduler.tick(), "all three are due on the first tick")

        // 5 s ticks for one minute: fast at 15, 30, 45, 60 (4 more), slow at 30, 60 (2 more), every-tick at each of the 12
        repeat(12) {
            clock.advance(5_000L)
            scheduler.tick()
        }

        assertEquals(5, fastCount.get())
        assertEquals(3, slowCount.get())
        assertEquals(13, everyCount.get())

        val stats = scheduler.stats().associateBy { it.name }

        assertEquals(5L, stats.getValue("fast").runs)
        assertEquals(0L, stats.getValue("fast").failures)
        assertEquals(clock.now(), stats.getValue("slow").lastStartedAt)
    }

    @Test
    fun `a tick that comes early runs nothing, the rows handled are summed`(): Unit = runBlocking {
        val clock = FakeClock()
        val (a, _) = counting("a", 30_000L, handled = 4)
        val (b, _) = counting("b", 30_000L, handled = 3)
        val scheduler = MarketScheduler(clock, listOf(a, b))

        assertEquals(7, scheduler.tick())

        clock.advance(29_999L)
        assertEquals(0, scheduler.tick())

        clock.advance(1L)
        assertEquals(7, scheduler.tick())
    }

    @Test
    fun `the default cadences are the ones of the spec`() {
        assertEquals(5_000L, MarketScheduler.TICK_MS)
        assertEquals(30_000L, MarketScheduler.ORDER_EXPIRY_MS)
        assertEquals(15_000L, MarketScheduler.MAIL_OUTBOX_MS)
        assertEquals(MarketScheduler.TICK_MS, MarketScheduler.WEBHOOK_MS)
    }

    // ==================================================================================== a job can not stop the tick

    @Test
    fun `a job that throws a LinkageError, a NoClassDefFoundError or anything else does not stop the tick or the other jobs`(): Unit = runBlocking {
        val clock = FakeClock()
        val throwables = listOf<() -> Throwable>(
            { LinkageError("older host") },
            { NoClassDefFoundError("com/panomc/platform/mail/MailOptions") },
            { IllegalStateException("boom") },
            { OutOfMemoryError("not really") },
            { StackOverflowError() }
        )
        val after = AtomicInteger()
        var round = 0
        val broken = MarketScheduler.Job("broken", 5_000L) { throw throwables[round]() }
        val healthy = MarketScheduler.Job("healthy", 5_000L) { after.incrementAndGet(); 2 }
        val scheduler = MarketScheduler(clock, listOf(broken, healthy))

        for (i in throwables.indices) {
            round = i

            assertEquals(2, scheduler.tick(), "round $i: the healthy job ran, the broken one handled nothing")

            val stats = scheduler.stats().associateBy { it.name }

            assertEquals(i + 1L, stats.getValue("broken").failures, "round $i")
            assertEquals(throwables[i]().javaClass.simpleName, stats.getValue("broken").lastError!!.substringBefore(':'), "round $i")
            assertEquals(0L, stats.getValue("healthy").failures)
            assertNull(stats.getValue("healthy").lastError)

            clock.advance(5_000L)
        }

        assertEquals(throwables.size, after.get())
    }

    @Test
    fun `a failing job is tried again at its next slot and its error is cleared by a success`(): Unit = runBlocking {
        val clock = FakeClock()
        val failing = java.util.concurrent.atomic.AtomicBoolean(true)
        val job = MarketScheduler.Job("flaky", 10_000L) { if (failing.get()) throw LinkageError("missing") else 5 }
        val scheduler = MarketScheduler(clock, listOf(job))

        assertEquals(0, scheduler.tick())
        assertNotNull(scheduler.stats().single().lastError)

        clock.advance(9_999L)
        assertEquals(0, scheduler.tick(), "a failure does not make the job due again before its slot")
        assertEquals(1L, scheduler.stats().single().runs)

        failing.set(false)
        clock.advance(1L)

        assertEquals(5, scheduler.tick())
        assertEquals(2L, scheduler.stats().single().runs)
        assertNull(scheduler.stats().single().lastError)
        assertEquals(5, scheduler.stats().single().lastHandled)
    }

    @Test
    fun `a gate that throws closes the tick, a closed gate runs nothing`(): Unit = runBlocking {
        val clock = FakeClock()
        val (job, count) = counting("j", 5_000L)
        var open = false
        val scheduler = MarketScheduler(clock, listOf(job), enabled = { open })

        assertEquals(0, scheduler.tick())
        assertEquals(0, count.get(), "READY is not reached: no job runs")

        open = true
        assertEquals(1, scheduler.tick(), "the first tick after the gate opened runs the job at once")

        val broken = MarketScheduler(clock, listOf(counting("k", 5_000L).first), enabled = { throw LinkageError("no runtime") })

        assertEquals(0, broken.tick(), "a gate that fails is a closed gate")
    }

    // ===================================================================================================== no re-entrancy

    @Test
    fun `a job that is still running is skipped, the other jobs go on, and it runs again once it is done`(): Unit = runBlocking {
        val clock = FakeClock()
        val gate = CompletableDeferred<Unit>()
        val slowRuns = AtomicInteger()
        val fastRuns = AtomicInteger()
        val slow = MarketScheduler.Job("slow", 5_000L) { slowRuns.incrementAndGet(); gate.await(); 1 }
        val fast = MarketScheduler.Job("fast", 5_000L) { fastRuns.incrementAndGet(); 1 }
        val scheduler = MarketScheduler(clock, listOf(slow, fast))

        val first = async(kotlinx.coroutines.Dispatchers.IO) { scheduler.tick() }

        while (slowRuns.get() == 0) kotlinx.coroutines.delay(5)

        clock.advance(5_000L)

        assertEquals(1, scheduler.tick(), "the slow job is still running: only the fast one ran")
        assertEquals(1, slowRuns.get(), "the slow job was not started a second time")
        assertEquals(2, fastRuns.get())

        gate.complete(Unit)
        assertEquals(2, first.await())

        clock.advance(5_000L)
        gate.complete(Unit)

        assertEquals(2, scheduler.tick())
        assertEquals(2, slowRuns.get())
    }

    @Test
    fun `two ticks at the same moment run a due job once`(): Unit = runBlocking {
        val clock = FakeClock()
        val (job, count) = counting("j", 30_000L)
        val scheduler = MarketScheduler(clock, listOf(job))

        repeat(Race.rounds) { round ->
            clock.advance(30_000L)

            val before = count.get()
            val results = Race.run(4) { scheduler.tick() }

            for (r in results) assertTrue(r.isSuccess, "round $round: ${r.exceptionOrNull()}")

            assertEquals(1, count.get() - before, "round $round: one run for four ticks")
            assertEquals(1, results.sumOf { it.getOrThrow() })
        }
    }

    @Test
    fun `job names must be unique and every job needs a name and a positive cadence`() {
        val ok = MarketScheduler.Job("a", 1) { 0 }

        assertTrue(runCatching { MarketScheduler(FakeClock(), listOf(ok, MarketScheduler.Job("a", 2) { 0 })) }.isFailure)
        assertTrue(runCatching { MarketScheduler.Job(" ", 1) { 0 } }.isFailure)
        assertTrue(runCatching { MarketScheduler.Job("b", 0) { 0 } }.isFailure)
    }

    // ============================================================================================== the Vert.x timer

    @Test
    fun `start arms one periodic timer that ticks until stop, start twice does not add a second one`(): Unit = runBlocking {
        val count = AtomicInteger()
        val scheduler = MarketScheduler(SystemClock, listOf(MarketScheduler.Job("t", 20L) { count.incrementAndGet(); 1 }))

        assertFalse(scheduler.isStarted)

        scheduler.start(vertx, tickMs = 25L)
        scheduler.start(vertx, tickMs = 25L)

        assertTrue(scheduler.isStarted)

        val deadline = System.currentTimeMillis() + 5_000L

        while (count.get() < 3 && System.currentTimeMillis() < deadline) kotlinx.coroutines.delay(10)

        assertTrue(count.get() >= 3, "the timer ticked: ${count.get()}")

        scheduler.stop(vertx)
        assertFalse(scheduler.isStarted)

        kotlinx.coroutines.delay(100)

        val stopped = count.get()

        kotlinx.coroutines.delay(150)
        assertEquals(stopped, count.get(), "no tick after stop")

        // it can be armed again
        scheduler.start(vertx, tickMs = 25L)

        val again = System.currentTimeMillis() + 5_000L

        while (count.get() == stopped && System.currentTimeMillis() < again) kotlinx.coroutines.delay(10)

        assertTrue(count.get() > stopped)

        scheduler.stop(vertx)
        scheduler.stop(vertx)
    }

    // ================================================================ the scheduler driving the real jobs on a database

    private suspend fun buy(product: com.panomc.plugins.market.db.model.MarketProduct): CheckoutResult =
        ph.h.checkout(ph.h.body("items" to listOf(ph.h.line(product)), "paymentMethodId" to "fake"), caller = QuoteCaller.GUEST)

    @Test
    fun `the scheduler completes a payment nobody reported and expires an order nobody paid, in the same tick, next to a job that is broken`(): Unit = runBlocking {
        val fx = w.fixtures

        fx.paymentMethod("fake")
        ph.fake.caps = PaymentCapabilities().also { it.statusQuery = true }

        val lostWebhook = ph.order(buy(fx.product(price = 1200)).order.getString("publicId"))
        val abandoned = ph.order(buy(fx.product(price = 1200, stock = 1)).order.getString("publicId"))

        // the gateway took the money of the first order, and knows nothing of the second
        ph.fake.onQuery = { request ->
            if (request.attempt.orderId == lostWebhook.id) {
                PaymentQueryResult.of(PaymentEvent.Succeeded(PaymentTarget.Attempt(request.attempt.id), Money(request.attempt.amount.amount, request.attempt.amount.currency)))
            } else {
                PaymentQueryResult.unknown()
            }
        }

        val expiry = OrderExpiryJob(w.clock, ph.db, ph.locks, w.orders, w.payments, ph.orderService, ph.payments, { pool })
        val reconcile = PaymentReconcileJob(w.clock, ph.payments, w.orders, w.payments, { pool })
        val brokenRuns = AtomicInteger()
        val scheduler = MarketScheduler(
            w.clock,
            listOf(
                MarketScheduler.Job("broken", MarketScheduler.TICK_MS) { brokenRuns.incrementAndGet(); throw NoClassDefFoundError("com/example/Optional") },
                MarketScheduler.Job("payment-reconcile", MarketScheduler.PAYMENT_RECONCILE_MS) { reconcile.runOnce() },
                MarketScheduler.Job("order-expiry", MarketScheduler.ORDER_EXPIRY_MS) { expiry.runOnce() }
            )
        )

        // a minute after the starts: the reconcile slot of both orders has come, no order is due for expiry
        w.clock.advance(60_000L)

        assertEquals(2, scheduler.tick(), "two attempts were queried; the expiry job found nothing; the broken job counted 0")
        assertEquals(OrderStatus.COMPLETED, ph.order(lostWebhook.id).status)
        assertEquals(OrderStatus.PENDING, ph.order(abandoned.id).status)

        // past the order window: the abandoned order expires (attempt + order), the paid one is left alone
        w.clock.advance(61 * 60_000L)
        w.clock.set(maxOf(w.clock.now(), ph.attempts(abandoned.id).single().expiresAt!!))

        val moved = scheduler.tick()

        assertTrue(moved >= 2, "expiry moved the attempt and the order: $moved")
        assertEquals(OrderStatus.EXPIRED, ph.order(abandoned.id).status)
        assertEquals(PaymentStatus.EXPIRED, ph.attempts(abandoned.id).single().status)
        assertEquals(OrderStatus.COMPLETED, ph.order(lostWebhook.id).status)
        assertEquals(1, w.products.getById(abandonedProductId(abandoned.id), pool)!!.stock)
        assertTrue(brokenRuns.get() >= 2, "the broken job ran on every tick and never stopped the others")
        assertEquals(0L, scheduler.stats().first { it.name == "order-expiry" }.failures)
        assertEquals(0L, scheduler.stats().first { it.name == "payment-reconcile" }.failures)
    }

    private suspend fun abandonedProductId(orderId: Long): Long = w.orderItems.getByOrderIds(listOf(orderId), pool).single().productId!!
}
