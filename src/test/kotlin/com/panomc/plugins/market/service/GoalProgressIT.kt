package com.panomc.plugins.market.service

import com.panomc.plugins.market.core.order.OrderEffect
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.GoalMetric
import com.panomc.plugins.market.db.model.GoalPeriod
import com.panomc.plugins.market.db.model.MarketGoal
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.tx.LockedOrder
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.support.MarketTestDb
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The goal progress writer on a real MariaDB (MK-171; 06 section 11 `AdvanceGoalProgress`, 21 section 3.4 step 10): what a paid order adds per metric and
 * product filter, test orders and goals outside their window add nothing, a new period starts from zero, `completedAt` is set once, many orders at once add up
 * exactly (the atomic `+delta`), a refund takes the amounts back, the `ForeignEffects` seam routes the effect, and a failing writer never blocks the order.
 */
class GoalProgressIT : MarketDaoITBase() {
    override suspend fun assertInvariants() {}

    private val vertx: Vertx = Vertx.vertx()

    override val poolSize: Int = 32

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    private lateinit var w: TestWiring

    private lateinit var progress: GoalProgress

    private val utc = ZoneId.of("UTC")

    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, utc).toInstant().toEpochMilli()

    private var seq = 0

    @BeforeEach
    fun setUp() {
        w = TestWiring(pool)
        progress = GoalProgress(w.clock, { w.config }, w.goals, w.orders, w.orderItems)
        // Wednesday 7 October 2026, 12:00 UTC
        w.clock.set(at(2026, 10, 7))
        w.configure { TestWiring.defaultConfig() }
    }

    private suspend fun paidOrder(
        gateway: Long = 8000,
        credit: Long = 0,
        fx: String = "1",
        test: Boolean = false,
        paidAt: Long = w.clock.now(),
        lines: List<Triple<Long?, Int, Long>> = listOf(Triple(10L, 2, 6000L), Triple(11L, 1, 2000L)),
        status: OrderStatus = OrderStatus.COMPLETED
    ): Long {
        val n = ++seq
        val id = w.orders.add(
            MarketOrder(
                playerUsername = "p$n", totalPrice = gateway + credit, gatewayAmount = gateway, creditValue = credit, fxRate = BigDecimal(fx), testMode = test, paidAt = paidAt, status = status,
                publicId = "GOAL" + n.toString().padStart(16, '0'), buyerKey = "g:p$n", createdAt = paidAt, updatedAt = paidAt
            ),
            pool
        )

        for ((product, quantity, total) in lines) {
            w.orderItems.add(MarketOrderItem(orderId = id, productId = product, productName = "P$product", quantity = quantity, lineTotal = total, kind = OrderItemKind.PRODUCT), pool)
        }

        return id
    }

    private suspend fun goal(
        metric: GoalMetric = GoalMetric.ORDERS, target: Long = 1000, productIds: List<Long>? = null, status: String = "ACTIVE", startsAt: Long? = null, endsAt: Long? = null,
        period: GoalPeriod = GoalPeriod.ONE_TIME, periodStart: Long? = null, progress: Long = 0
    ): Long {
        val id = w.goals.add(
            MarketGoal(
                name = "G${++seq}", metric = metric, target = target, productIds = productIds?.let { JsonArray(it).encode() }, status = status, startsAt = startsAt, endsAt = endsAt,
                period = period, periodStart = periodStart, currency = if (metric == GoalMetric.REVENUE) "EUR" else null, createdAt = 1, updatedAt = 1
            ),
            pool
        )

        if (progress > 0) w.goals.addProgress(id, progress, 1, pool)

        return id
    }

    private suspend fun progressOf(id: Long) = w.goals.getById(id, pool)!!.progress

    // ================================================================================================== a paid order

    @Test
    fun `a paid order adds one order, the product units and the gateway money to the three metrics`(): Unit = runBlocking {
        val orders = goal(GoalMetric.ORDERS)
        val sales = goal(GoalMetric.PRODUCT_SALES)
        val revenue = goal(GoalMetric.REVENUE)

        progress.onOrderPaid(pool, paidOrder())

        assertEquals(1L, progressOf(orders))
        assertEquals(3L, progressOf(sales))
        assertEquals(8000L, progressOf(revenue))
    }

    @Test
    fun `a product filter counts only the matching lines and the share of the goods they hold`(): Unit = runBlocking {
        val sales = goal(GoalMetric.PRODUCT_SALES, productIds = listOf(10))
        val revenue = goal(GoalMetric.REVENUE, productIds = listOf(10))
        val other = goal(GoalMetric.ORDERS, productIds = listOf(99))

        progress.onOrderPaid(pool, paidOrder(gateway = 4000, credit = 4000))

        assertEquals(2L, progressOf(sales))
        assertEquals(3000L, progressOf(revenue), "6000 of the 8000 goods, and only the gateway half of the order was money: 4000 x 6000 / 8000")
        assertEquals(0L, progressOf(other))
    }

    @Test
    fun `revenue is the gateway part in the store currency, a credits order adds no revenue`(): Unit = runBlocking {
        val revenue = goal(GoalMetric.REVENUE)
        val orders = goal(GoalMetric.ORDERS)

        progress.onOrderPaid(pool, paidOrder(gateway = 9000, fx = "3"))
        progress.onOrderPaid(pool, paidOrder(gateway = 0, credit = 5000))

        assertEquals(3000L, progressOf(revenue))
        assertEquals(2L, progressOf(orders), "the credits order is an order")
    }

    @Test
    fun `a test order, an inactive goal and a goal outside its window add nothing`(): Unit = runBlocking {
        val now = w.clock.now()
        val live = goal()
        val inactive = goal(status = "INACTIVE")
        val notYet = goal(startsAt = now + 1000)
        val over = goal(endsAt = now)

        progress.onOrderPaid(pool, paidOrder(test = true))
        assertEquals(0L, progressOf(live), "a test order counts for nothing")

        progress.onOrderPaid(pool, paidOrder())

        assertEquals(1L, progressOf(live))
        assertEquals(0L, progressOf(inactive))
        assertEquals(0L, progressOf(notYet))
        assertEquals(0L, progressOf(over))
    }

    @Test
    fun `completedAt is set once, when the target is reached`(): Unit = runBlocking {
        val id = goal(target = 2)

        progress.onOrderPaid(pool, paidOrder())
        assertNull(w.goals.getById(id, pool)!!.completedAt)

        w.clock.advance(1000)
        progress.onOrderPaid(pool, paidOrder())

        val done = w.goals.getById(id, pool)!!.completedAt

        assertNotNull(done)

        w.clock.advance(1000)
        progress.onOrderPaid(pool, paidOrder())

        assertEquals(3L, progressOf(id))
        assertEquals(done, w.goals.getById(id, pool)!!.completedAt, "set once")
    }

    // ================================================================================================== periods

    @Test
    fun `a weekly goal starts again from zero in a new week and keeps counting inside the week`(): Unit = runBlocking {
        val id = goal(period = GoalPeriod.WEEKLY, periodStart = at(2026, 9, 28, 0), progress = 7)

        progress.onOrderPaid(pool, paidOrder())

        val goal = w.goals.getById(id, pool)!!

        assertEquals(1L, goal.progress, "the 7 of the week of 28 September are gone")
        assertEquals(at(2026, 10, 5, 0), goal.periodStart)

        progress.onOrderPaid(pool, paidOrder())

        assertEquals(2L, progressOf(id))
    }

    @Test
    fun `a monthly goal rolls on the first of the month in the store zone`(): Unit = runBlocking {
        w.configure { com.panomc.plugins.market.config.MarketConfig(currency = "EUR", storeTimeZone = "Europe/Istanbul") }

        val id = goal(period = GoalPeriod.MONTHLY, periodStart = at(2026, 9, 1, 0), progress = 5)

        // 21:30 UTC on 30 September is 00:30 on 1 October in Istanbul: the clock says October there
        w.clock.set(ZonedDateTime.of(2026, 10, 1, 0, 30, 0, 0, ZoneId.of("Europe/Istanbul")).toInstant().toEpochMilli())
        progress.onOrderPaid(pool, paidOrder(paidAt = w.clock.now()))

        assertEquals(1L, progressOf(id))
        assertEquals(ZonedDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneId.of("Europe/Istanbul")).toInstant().toEpochMilli(), w.goals.getById(id, pool)!!.periodStart)
    }

    // ================================================================================================== atomic

    @Test
    fun `thirty orders paid at once add up exactly, no increment is lost`(): Unit = runBlocking {
        repeat(Race.rounds) {
            val orders = goal(GoalMetric.ORDERS)
            val sales = goal(GoalMetric.PRODUCT_SALES)
            val revenue = goal(GoalMetric.REVENUE)
            val ids = List(30) { paidOrder(gateway = 1000, lines = listOf(Triple(10L, 2, 1000L))) }
            val results = Race.run(30) { i -> progress.onOrderPaid(pool, ids[i]) }

            assertTrue(results.all { it.isSuccess }, "$results")
            assertEquals(30L, progressOf(orders))
            assertEquals(60L, progressOf(sales))
            assertEquals(30_000L, progressOf(revenue))

            MarketTestDb.sql(pool, "DELETE FROM `${MarketTestDb.TABLE_PREFIX}market_goal`")
        }
    }

    @Test
    fun `orders paid at once as the first of a new week roll the goal once, no increment of the new week is wiped`(): Unit = runBlocking {
        repeat(Race.rounds) {
            val orders = goal(GoalMetric.ORDERS, period = GoalPeriod.WEEKLY, periodStart = at(2026, 9, 28, 0), progress = 5)
            val revenue = goal(GoalMetric.REVENUE, period = GoalPeriod.WEEKLY, periodStart = at(2026, 9, 28, 0), progress = 900)
            val ids = List(20) { paidOrder(gateway = 1000, lines = listOf(Triple(10L, 1, 1000L))) }

            // one transaction per order, as the payment path runs it: every one of them reads the goal as stale (REPEATABLE READ keeps it so) before the first commit
            val results = Race.run(20) { i -> w.db.tx { conn -> progress.onOrderPaid(conn, ids[i]) } }

            assertTrue(results.all { it.isSuccess }, "$results")

            for (id in listOf(orders to 20L, revenue to 20_000L)) {
                val goal = w.goals.getById(id.first, pool)!!

                assertEquals(id.second, goal.progress, "the 20 orders of the new week are all there, the old week's progress is gone")
                assertEquals(at(2026, 10, 5, 0), goal.periodStart)
            }

            MarketTestDb.sql(pool, "DELETE FROM `${MarketTestDb.TABLE_PREFIX}market_goal`")
        }
    }

    @Test
    fun `the period roll happens once per period, a second roll into the same period changes nothing`(): Unit = runBlocking {
        val id = goal(period = GoalPeriod.WEEKLY, periodStart = at(2026, 9, 28, 0), progress = 7)

        assertTrue(w.goals.rollPeriod(id, at(2026, 10, 5, 0), w.clock.now(), pool))

        w.goals.addProgress(id, 3, w.clock.now(), pool)

        assertFalse(w.goals.rollPeriod(id, at(2026, 10, 5, 0), w.clock.now(), pool), "already in that period")
        assertEquals(3L, progressOf(id))

        // a goal that never had a period start is rolled
        val fresh = goal(period = GoalPeriod.MONTHLY, progress = 4)

        assertTrue(w.goals.rollPeriod(fresh, at(2026, 10, 1, 0), w.clock.now(), pool))
        assertEquals(0L, progressOf(fresh))
    }

    // ================================================================================================== seams

    private fun locked(orderId: Long) = LockedOrder(MarketOrder(id = orderId), emptyList(), emptyList(), OrderLockScope.RELEASE)

    @Test
    fun `AdvanceGoalProgress reaches the writer through the ForeignEffects seam, every other effect goes on`(): Unit = runBlocking {
        val id = goal(GoalMetric.ORDERS)
        val orderId = paidOrder()
        val passed = mutableListOf<OrderEffect>()
        val effects = GoalEffects({ progress }, ForeignEffects { _, _, effect -> passed += effect })

        w.db.tx { conn ->
            effects.apply(conn, locked(orderId), OrderEffect.AdvanceGoalProgress)
            effects.apply(conn, locked(orderId), OrderEffect.StartShipping)
        }

        assertEquals(1L, progressOf(id))
        assertEquals(listOf<OrderEffect>(OrderEffect.StartShipping), passed, "only the other effect is passed on")
    }

    @Test
    fun `a failing goal write is logged and skipped, the order transaction still commits`(): Unit = runBlocking {
        val orderId = paidOrder()
        val effects = GoalEffects({ progress })
        val goalTable = "${MarketTestDb.TABLE_PREFIX}market_goal"

        goal()

        // the goal table is not there for a moment: every statement of the writer fails with a real SQL error
        MarketTestDb.sql(pool, "RENAME TABLE `$goalTable` TO `${goalTable}_away`")

        try {
            w.db.tx { conn ->
                effects.apply(conn, locked(orderId), OrderEffect.AdvanceGoalProgress)
                // what the transition does after the effect must still be committed
                conn.preparedQuery("UPDATE `${MarketTestDb.TABLE_PREFIX}market_order` SET `note` = 'after' WHERE `id` = ?").execute(io.vertx.sqlclient.Tuple.of(orderId)).coAwait()
            }
        } finally {
            MarketTestDb.sql(pool, "RENAME TABLE `${goalTable}_away` TO `$goalTable`")
        }

        assertEquals("after", w.orders.getById(orderId, pool)!!.note)
    }

    // ================================================================================================== refunds

    @Test
    fun `a refund takes the money, the units and, when the order is fully refunded, the order back from the goals`(): Unit = runBlocking {
        val r = RefundWorld(w, vertx)
        val effects = StandardRefundEffects(w.clock, w.creatorEarnings, { MarketTestDb.TABLE_PREFIX }, goals = progress)
        val service = RefundService(
            w.db, r.d.locks, w.clock, { w.config }, w.orders, w.orderItems, w.orderEvents, w.payments, w.refunds, w.refundItems, w.deliveries, w.entitlements, w.creditTxs, r.d.credits,
            r.d.service, r.d.entitlementService, PaymentServiceRefundGateway(r.payments) { w.pool }, w.serverStates, effects, RefundAlerts.LOG_ONLY, 5_000L
        )
        val user = w.fixtures.user("Steve")
        val paid = r.place(user, listOf(RefundLine(6000, quantity = 2), RefundLine(2000)))

        val orders = goal(GoalMetric.ORDERS)
        val sales = goal(GoalMetric.PRODUCT_SALES)
        val revenue = goal(GoalMetric.REVENUE)

        progress.onOrderPaid(pool, paid.order.id)

        assertEquals(1L, progressOf(orders))
        assertEquals(3L, progressOf(sales))
        assertEquals(8000L, progressOf(revenue))

        // refund the whole first line (2 units, 6000)
        service.request(paid.order.id, RefundInput(items = listOf(com.panomc.plugins.market.core.refund.RefundMath.ItemRequest(paid.items.first { it.kind == OrderItemKind.PRODUCT }.id, 2))), r.key("part"), null)

        assertEquals(1L, progressOf(orders), "a partial refund keeps the order")
        assertEquals(1L, progressOf(sales))
        assertEquals(2000L, progressOf(revenue))

        // the rest
        service.request(paid.order.id, RefundInput(), r.key("rest"), null)

        assertEquals(0L, progressOf(orders), "fully refunded: the order goes")
        assertEquals(0L, progressOf(sales))
        assertEquals(0L, progressOf(revenue))

        // a second full-refund attempt has nothing left and changes nothing
        assertFalse(r.order(paid.order.id).status != OrderStatus.REFUNDED)
        assertEquals(0L, progressOf(orders))
    }
}
