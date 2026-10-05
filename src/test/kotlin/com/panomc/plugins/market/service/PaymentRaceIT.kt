package com.panomc.plugins.market.service

import com.panomc.platform.model.Error
import com.panomc.plugins.market.core.payment.PaymentAttemptEvent
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.CreditSystemKey
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.error.MarketBusyException
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.Vertx
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The payment service under contention on a real MariaDB (MK-076; twins of R-17, R-18 and the duplicate-payment races of 17 section 9.4 and
 * 06 section 13.3): 20 actors released together by [Race], five rounds per case with fresh rows, through `PaymentService.pay`, `applyEvent` and
 * `cancel`. A [MarketBusyException] is an allowed answer of `pay` only (06 section 13.2: after three restarts the buyer is told to try again); a
 * deadlock or lock wait timeout never surfaces, and the invariants I1 to I22 (I10, I11, I11b, I13 above all) hold after every case.
 */
class PaymentRaceIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var ph: PaymentHarness
    private val vertx: Vertx = Vertx.vertx()

    private val actors = 20

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
    }

    private val fx get() = w.fixtures

    private suspend fun pending(): MarketOrder = pending(fx.product(price = 1000))

    private suspend fun pending(product: MarketProduct): MarketOrder {
        val result = ph.h.checkout(ph.h.body("items" to listOf(ph.h.line(product)), "paymentMethodId" to "fake"))

        return ph.order(result.order.getString("publicId"))
    }

    private suspend fun open(orderId: Long) = ph.attempts(orderId).filter { it.status in setOf(PaymentStatus.CREATED, PaymentStatus.PENDING, PaymentStatus.PROCESSING) }

    @Test
    fun `R-18 two concurrent pay calls leave at most one open attempt, the other is cancelled`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        repeat(Race.rounds) { round ->
            val order = pending()
            val results = Race.run(2) { ph.payments.pay(order, PayRequest("fake", null, null), PayCaller(), pool) }

            for (r in results) assertTrue(r.isSuccess, "round $round: ${r.exceptionOrNull()}")

            val attempts = ph.attempts(order.id)

            assertEquals(3, attempts.size, "round $round: the first attempt and one per pay")
            assertEquals(1, open(order.id).size, "round $round: I10, one open attempt")
            assertEquals(2, attempts.count { it.status == PaymentStatus.CANCELLED }, "round $round")
            assertTrue(attempts.last().status == PaymentStatus.PENDING, "round $round: the newest one is the open one")
            assertEquals(OrderStatus.PENDING, ph.order(order.id).status)
        }
    }

    @Test
    fun `twenty concurrent pay calls never leave two open attempts and fail only with the busy answer`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        repeat(Race.rounds) { round ->
            val order = pending()
            val results = Race.run(actors) { ph.payments.pay(order, PayRequest("fake", null, null), PayCaller(), pool) }
            val failures = results.mapNotNull { it.exceptionOrNull() }
            val ok = results.count { it.isSuccess }

            assertTrue(failures.all { it is MarketBusyException }, "round $round: only STORE_BUSY may fail, got ${failures.map { it.javaClass.simpleName }}")
            assertTrue(ok >= 1, "round $round: somebody wins")

            val attempts = ph.attempts(order.id)

            assertEquals(1 + ok, attempts.size, "round $round: one new attempt per successful pay")
            assertEquals(1, open(order.id).size, "round $round: I10")
            assertEquals(attempts.size - 1, attempts.count { it.status == PaymentStatus.CANCELLED }, "round $round")
            assertEquals(1, ph.order(order.id).let { o -> ph.attempts(o.id).map { it.reference }.toSet().size - attempts.size + 1 }, "round $round: references are unique")
        }
    }

    @Test
    fun `twenty concurrent success events of one attempt complete the order once, every side effect once`(): Unit = runBlocking {
        val coupon = fx.coupon("ONCE", com.panomc.plugins.market.util.DiscountUnit.PERCENT, 1000)

        fx.paymentMethod("fake")
        fx.webhookEndpoint()

        repeat(Race.rounds) { round ->
            val product = fx.product(price = 1000, stock = 5)
            val result = ph.h.checkout(ph.h.body("items" to listOf(ph.h.line(product, 2)), "paymentMethodId" to "fake", "couponCode" to "ONCE", "expectedTotal" to 18.0))
            val order = ph.order(result.order.getString("publicId"))
            val attempt = ph.attempts(order.id).single()
            val before = ph.effects.of(order.id).size
            val applied = Race.run(actors) { ph.succeed(order.id, attempt) }

            assertTrue(applied.all { it.isSuccess }, "round $round: ${applied.mapNotNull { it.exceptionOrNull() }}")
            assertEquals(1, applied.count { it.getOrNull()?.changed == true }, "round $round: exactly one of them did the work")
            assertEquals(OrderStatus.COMPLETED, ph.order(order.id).status)
            assertEquals(ReservationState.COMMITTED, ph.order(order.id).reservationState)
            assertEquals(2, w.products.getById(product.id, pool)!!.soldCount, "round $round: sold once")
            assertEquals(10, ph.effects.of(order.id).size - before, "round $round: the effects of O2 once")
            assertEquals(1, count("market_webhook_delivery", "`orderId` = ?", order.id), "round $round: one order.paid row")
        }

        assertEquals(Race.rounds, w.coupons.getById(coupon.id, pool)!!.usedCount)
    }

    @Test
    fun `a full-credit order paid by twenty concurrent events is captured once`(): Unit = runBlocking {
        val alex = fx.user("Alex")

        ph.h.emails[alex.id] = "alex@example.com"

        repeat(Race.rounds) { round ->
            fx.credit(alex, 2_500)

            val product = fx.product(price = 3000, creditPrice = 2500)
            val result = ph.h.checkout(
                ph.h.body("items" to listOf(ph.h.line(product)), "paymentMethodId" to "credits", "payWithCredits" to true),
                caller = QuoteCaller(alex.id)
            )
            val order = ph.order(result.order.getString("publicId"))

            // the checkout already completed it; further events change nothing
            assertEquals(OrderStatus.COMPLETED, order.status, "round $round")

            val again = Race.run(actors) { ph.succeed(order.id, ph.attempts(order.id).single()) }

            assertTrue(again.all { it.isSuccess && it.getOrNull()?.changed == false }, "round $round")
        }

        assertEquals(Race.rounds, ph.ledger.captures.size, "one capture per order")
        assertEquals(Race.rounds * 2_500L, w.creditAccounts.getBySystemKey(CreditSystemKey.SPENT, pool)!!.balance)
        assertEquals(0, w.creditAccounts.getBySystemKey(CreditSystemKey.HOLD, pool)!!.balance)
    }

    @Test
    fun `two paid attempts of one order leave one non-duplicate success and one duplicate`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        repeat(Race.rounds) { round ->
            val order = pending()
            val first = ph.attempts(order.id).single()

            ph.payments.pay(order, PayRequest("fake", null, null), PayCaller(), pool)

            val second = ph.attempts(order.id).last()
            val results = Race.run(2) { i -> ph.succeed(order.id, if (i == 0) first else second) }

            assertTrue(results.all { it.isSuccess }, "round $round: ${results.mapNotNull { it.exceptionOrNull() }}")

            val attempts = ph.attempts(order.id)

            assertEquals(OrderStatus.COMPLETED, ph.order(order.id).status, "round $round")
            assertEquals(1, attempts.count { it.status == PaymentStatus.SUCCEEDED && !it.duplicate }, "round $round: I13")
            assertEquals(1, attempts.count { it.status == PaymentStatus.SUCCEEDED && it.duplicate }, "round $round: the second payment is flagged")
            assertEquals(attempts.single { !it.duplicate && it.status == PaymentStatus.SUCCEEDED }.id, ph.order(order.id).paymentId, "round $round: I11b, the order's payment is the first")
            assertEquals(1, ph.effects.of(order.id).count { it == "IssueInvoice" }, "round $round")
        }
    }

    @Test
    fun `R-17 a cancel and a success together end in COMPLETED with a refused cancel, or in a late REVIEW with the cancel done`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        var completed = 0
        var late = 0

        repeat(Race.rounds * 2) { round ->
            val order = pending()
            val attempt = ph.attempts(order.id).single()
            val results = Race.run(2) { i -> if (i == 0) ph.payments.cancel(order, pool) else ph.succeed(order.id, attempt) }
            val cancel = results[0]
            val success = results[1]

            assertTrue(success.isSuccess, "round $round: the success is never refused: ${success.exceptionOrNull()}")

            val final = ph.order(order.id)

            when (final.status) {
                OrderStatus.COMPLETED -> {
                    completed++

                    assertTrue((cancel.exceptionOrNull() as? Error)?.getErrorCode() == "ORDER_NOT_CANCELLABLE", "round $round: ${cancel.exceptionOrNull()}")
                    assertEquals(ReservationState.COMMITTED, final.reservationState)
                }

                OrderStatus.REVIEW -> {
                    late++

                    assertTrue(cancel.isSuccess, "round $round: ${cancel.exceptionOrNull()}")
                    assertEquals("LATE", final.reviewReason)
                    assertEquals(ReservationState.RELEASED, final.reservationState)
                    assertFalse(ph.effects.of(order.id).isNotEmpty(), "round $round: nothing delivered")
                }

                else -> throw AssertionError("round $round: unexpected ${final.status}, cancel ${cancel.exceptionOrNull()}")
            }
        }

        assertEquals(Race.rounds * 2, completed + late)
    }

    @Test
    fun `a retry racing the success of the old attempt keeps the invariants, whichever wins`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        repeat(Race.rounds * 2) { round ->
            val order = pending()
            val old = ph.attempts(order.id).single()
            val results = Race.run(2) { i -> if (i == 0) ph.payments.pay(order, PayRequest("fake", null, null), PayCaller(), pool) else ph.succeed(order.id, old) }

            // pay may find the order paid (409) or the attempt moved; the success always lands
            val failure = results[0].exceptionOrNull()

            assertTrue(failure == null || (failure as? Error)?.getErrorCode() == "ORDER_NOT_PAYABLE", "round $round: $failure")
            assertTrue(results[1].isSuccess, "round $round: ${results[1].exceptionOrNull()}")

            val final = ph.order(order.id)

            assertEquals(OrderStatus.COMPLETED, final.status, "round $round: the old attempt's tender equals the order's, so it completes it")
            assertTrue(open(order.id).isEmpty(), "round $round: no attempt stays open on a paid order")
        }
    }
}
