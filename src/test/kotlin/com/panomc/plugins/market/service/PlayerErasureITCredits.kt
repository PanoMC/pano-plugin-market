package com.panomc.plugins.market.service

import com.panomc.plugins.market.core.order.OrderActor
import com.panomc.plugins.market.core.order.OrderEvent
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.job.HousekeepingJob
import com.panomc.plugins.market.support.Fixtures
import com.panomc.plugins.market.support.TestWiring
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
 * The erasure of a user whose order still holds credits (MK-153; 11 section 16 step 3 and 5, 07 section 14.4) on the real ledger of [CreditHarness]: an order
 * that is `PENDING` with an attempt in `PROCESSING` cannot be cancelled, so its credits stay on hold. Closing the account at once would leave them on hold
 * for ever (or give them to a second account when the order is released), so the account and the order keep the user id until the order settles; the
 * spendable credits are revoked at once, and the housekeeping job runs the erasure again for the marker that was left: it revokes what came back under the
 * key of 07 section 14.4 and closes the account. The credit self-check (L1 to L7, O1 to O8) and the invariants run after every test.
 */
internal class PlayerErasureITCredits : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var c: CreditHarness
    private val vertx: Vertx = Vertx.vertx()

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    @BeforeEach
    fun freshState() {
        runBlocking { resetState() }
        w = TestWiring(pool)
        c = CreditHarness(w, vertx)
    }

    override suspend fun assertInvariants() {
        super.assertInvariants()

        val result = c.reconciler().run(full = true)

        assertTrue(result.ok, "the credit reconciler found ${result.problems}")
    }

    private fun service() = PlayerErasureService(
        clock = w.clock, db = c.ph.db, locks = c.ph.locks, orderService = c.orders, credits = c.credits, prefix = { w.orders.prefix() }, client = { w.pool },
        endSubscriptions = { }, afterCommit = { after -> c.payments.runAfterCommit(after, w.pool) }
    )

    private suspend fun one(table: String, id: Long, column: String): Any? = sql("SELECT `$column` AS v FROM `pano_$table` WHERE `id` = ?", id).single().getValue("v")

    @Test
    fun `an order that still holds credits keeps its user and the account until it settles, then the next run closes both`(): Unit = runBlocking {
        val alex = w.fixtures.user("Alex")

        c.h.emails[alex.id] = "alex@example.com"
        w.fixtures.credit(alex, 10_000)
        w.fixtures.paymentMethod("fake")
        c.deferStart = true

        val product = w.fixtures.product(price = 3_000, creditPrice = 2_500, stock = 3)
        val order = c.orderOf(c.spend(product, QuoteCaller(alex.id)))
        val attempt = c.attempts(order.id).single()

        assertEquals(ReservationState.HELD, order.reservationState)
        assertEquals(7_500L, w.creditAccounts.getById(alex.accountId, pool)!!.balance)

        // the payment is being processed: the order cannot be cancelled by the erasure
        Fixtures.setColumns(pool, "market_payment", attempt.id, mapOf("status" to "PROCESSING"))
        Fixtures.setColumns(pool, "market_order", order.id, mapOf("email" to "alex@example.com", "clientIp" to "203.0.113.7", "accessToken" to "t".repeat(40)))

        val service = service()
        val report = service.erase(alex.id)

        assertTrue(report.failed.isEmpty(), "failed ${report.failed}")
        assertTrue(report.deferred)
        assertFalse(report.complete)

        val held = c.order(order.id)

        assertEquals(OrderStatus.PENDING, held.status)
        assertEquals(ReservationState.HELD, held.reservationState)
        assertEquals(alex.id, held.userId, "the release posts to the account of this user")
        assertNull(held.email)
        assertNull(held.clientIp)
        assertNull(held.accessToken)
        assertEquals(alex.id, one("market_credit_account", alex.accountId, "userId"), "the account is kept until the hold is gone")
        assertEquals(0L, w.creditAccounts.getById(alex.accountId, pool)!!.balance, "what was spendable is revoked at once")
        assertEquals(2_500L, c.system(com.panomc.plugins.market.db.model.CreditSystemKey.HOLD), "the held credits are still held")
        assertEquals(7_500L, c.system(com.panomc.plugins.market.db.model.CreditSystemKey.REVOKED))
        assertEquals(1L, count("market_sequence", "`name` = ?", PlayerErasureService.markerName(alex.id)))

        // a run in between changes nothing and keeps waiting
        val job = HousekeepingJob(w.clock, { "pano_" }, { w.pool }, null, null, service, null)

        job.run(HousekeepingJob.Task.ERASURE)

        assertEquals(alex.id, c.order(order.id).userId)
        assertEquals(1L, count("market_sequence", "`name` = ?", PlayerErasureService.markerName(alex.id)))

        // the payment fails for good: an admin cancels the order, the hold goes back to the (still open) account
        c.ph.db.txRestartingOnOrderChange { conn ->
            c.ph.locks.forOrder(conn, order.id, OrderLockScope.RELEASE) { locked -> c.orders.transition(conn, locked, OrderEvent.Cancel(OrderActor.ADMIN)) }
        }

        assertEquals(OrderStatus.CANCELLED, c.order(order.id).status)
        assertEquals(2_500L, w.creditAccounts.getById(alex.accountId, pool)!!.balance)
        assertEquals(alex.id, c.order(order.id).userId)

        // the next run finishes: the returned credits are revoked under the sweep key, the account and the order lose the user
        job.run(HousekeepingJob.Task.ERASURE)

        assertEquals(0L, w.creditAccounts.getById(alex.accountId, pool)!!.balance)
        assertNull(one("market_credit_account", alex.accountId, "userId"))
        assertNull(c.order(order.id).userId)
        assertEquals(10_000L, c.system(com.panomc.plugins.market.db.model.CreditSystemKey.REVOKED))
        assertEquals(0L, count("market_credit_tx", "`userId` = ?", alex.id))
        assertEquals(0L, count("market_sequence", "`name` LIKE 'erasure-pending:%'"), "the marker is gone")

        val keys = sql("SELECT `idempotencyKey` FROM `pano_market_credit_tx` WHERE `type` = 'REVOKE' ORDER BY `id`").map { it.getString("idempotencyKey") }

        assertEquals(2, keys.size)
        assertEquals("user-delete:${alex.id}", keys[0])
        assertTrue(keys[1].startsWith("user-delete:${alex.id}:sweep:"), keys[1])
        assertNotNull(sql("SELECT 1 FROM `pano_market_order_event` WHERE `orderId` = ? AND `type` = 'PII_ERASED'", order.id).firstOrNull())
    }
}
