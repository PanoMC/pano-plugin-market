package com.panomc.plugins.market.job

import com.panomc.plugins.market.core.delivery.DeliveryError
import com.panomc.plugins.market.core.delivery.DeliveryEvent
import com.panomc.plugins.market.core.delivery.ProductAction
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.DeliveryActionType
import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.db.model.DeliveryStatus
import com.panomc.plugins.market.db.model.EntitlementStatus
import com.panomc.plugins.market.db.model.FulfillmentStatus
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.service.DeliveryWorld
import com.panomc.plugins.market.service.Placed
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestWiring
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `DeliveryJob` on a real MariaDB (MK-102; 08 sections 6, 7 and 17, tests 29, 35, 37, 38 of section 20): two workers at once run each inline row once, `SCHEDULED`
 * rows are promoted, a dead worker's claim is recovered, a failing executor backs off and gives up after `deliveryMaxAttempts`, and the permission
 * grants are re-asserted three times. Invariants I19 and I20 (with all the others) are checked after every test by the base class.
 */
class DeliveryJobIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var d: DeliveryWorld
    private lateinit var job: DeliveryJob

    @BeforeEach
    fun wire() {
        w = TestWiring(pool)
        d = DeliveryWorld(w, maxAttempts = 3)
        job = DeliveryJob(d.service, w.clock)
    }

    private fun credit(id: String, credits: Long, delay: Int = 0) = ProductAction(id = id, type = DeliveryActionType.CREDIT, credit = credits, delaySeconds = delay)

    private fun permission(id: String, vararg nodes: String) = ProductAction(id = id, type = DeliveryActionType.PERMISSION, nodes = nodes.toList())

    private suspend fun steve(): TestUser = w.fixtures.user("Steve")

    @Test
    fun `two runOnce at the same moment execute every inline row once (29)`(): Unit = runBlocking {
        repeat(Race.rounds) { round ->
            val users = listOf(w.fixtures.user("Racer${round}a"), w.fixtures.user("Racer${round}b"))
            val placed = List(8) { i -> d.place(buyer = users[i % 2].username, user = users[i % 2], actions = listOf(credit("a1", 100), permission("a2", "group.vip"))) }

            for (p in placed) d.pay(p)

            val before = count("market_credit_tx", "`type` = 'ACTION'")
            val results = Race.run(2) { job.runOnce() }

            assertTrue(results.all { it.isSuccess }, results.joinToString { it.exceptionOrNull()?.toString() ?: "ok" })
            // each row is claimed by exactly one worker: the counts add up to the rows (8 x 2), whoever won them
            assertEquals(16, results.sumOf { it.getOrThrow() })

            for (p in placed) {
                val rows = d.rows(p.order.id)

                assertEquals(2, rows.size)
                assertTrue(rows.all { it.status == DeliveryStatus.CONFIRMED && it.attempts == 1 }, rows.joinToString { "${it.actionId}:${it.status}:${it.attempts}" })
                assertEquals(FulfillmentStatus.FULFILLED, d.order(p.order.id).fulfillmentStatus)
            }

            assertEquals(before + 8, count("market_credit_tx", "`type` = 'ACTION'"))
            assertEquals(800L, users.sumOf { w.fixtures.creditBalance(it) })

            // the shared node exists once per player although four ranks were bought by each
            for (u in users) assertEquals(1, d.permissionStore.nodes(u.id, "group.vip").size)

            w.assertInvariants()
        }
    }

    @Test
    fun `a SCHEDULED row is promoted when its time has come and then executed (D1)`(): Unit = runBlocking {
        val u = steve()
        val placed = d.place(user = u, actions = listOf(credit("a1", 500, delay = 60)))

        d.pay(placed)

        assertEquals(DeliveryStatus.SCHEDULED, d.rows(placed.order.id).single().status)
        assertEquals(0, job.runOnce())

        w.clock.advance(59_000)

        assertEquals(0, job.runOnce())
        assertEquals(0L, w.fixtures.creditBalance(u))

        w.clock.advance(2_000)

        // promoted (D1) and executed in the same tick: two rows' worth of work
        assertEquals(2, job.runOnce())
        assertEquals(DeliveryStatus.CONFIRMED, d.rows(placed.order.id).single().status)
        assertEquals(500L, w.fixtures.creditBalance(u))
    }

    @Test
    fun `a claim that ran out returns to PENDING and the next tick completes it (37)`(): Unit = runBlocking {
        val u = steve()
        val placed = d.place(user = u, actions = listOf(credit("a1", 500)))

        d.pay(placed)

        val claimed = d.service.claimDue().single()

        assertEquals(DeliveryStatus.SENDING, d.row(claimed.id).status)
        assertNotNull(d.row(claimed.id).claimToken)

        // the worker died; before the claim runs out nothing is touched
        w.clock.advance(30_000)
        assertEquals(0, job.runOnce())
        assertEquals(DeliveryStatus.SENDING, d.row(claimed.id).status)

        w.clock.advance(31_000)

        assertEquals(1, job.runOnce())

        val recovered = d.row(claimed.id)

        assertEquals(DeliveryStatus.PENDING, recovered.status)
        assertNull(recovered.claimToken)
        assertNull(recovered.claimedUntil)
        assertEquals(w.clock.now(), recovered.nextAttemptAt)

        assertEquals(1, job.runOnce())

        val done = d.row(claimed.id)

        assertEquals(DeliveryStatus.CONFIRMED, done.status)
        assertEquals(2, done.attempts)
        assertEquals(500L, w.fixtures.creditBalance(u))
        assertEquals(1L, count("market_credit_tx", "`type` = 'ACTION'"))
    }

    @Test
    fun `a failing executor backs off 30 s and doubling and ends FAILED (DB_ERROR) after deliveryMaxAttempts (38)`(): Unit = runBlocking {
        // a guest order for a registered name: the executor looks the user up, and the lookup fails
        val alex = w.fixtures.user("Alex")
        val placed = d.place(buyer = "Alex", actions = listOf(credit("a1", 500)))

        d.pay(placed)
        d.failLookups.set(100)

        val id = d.rows(placed.order.id).single().id

        assertEquals(1, job.runOnce())

        var row = d.row(id)

        assertEquals(DeliveryStatus.PENDING, row.status)
        assertEquals(1, row.attempts)
        assertEquals(DeliveryError.DB_ERROR, row.lastErrorCode)
        assertTrue(row.nextAttemptAt!! - w.clock.now() in 24_000..36_000, "first backoff is 30 s +-20 %: ${row.nextAttemptAt!! - w.clock.now()}")
        assertNull(row.claimToken)

        // not due yet
        assertEquals(0, job.runOnce())

        w.clock.advance(37_000)

        assertEquals(1, job.runOnce())

        row = d.row(id)

        assertEquals(DeliveryStatus.PENDING, row.status)
        assertEquals(2, row.attempts)
        assertTrue(row.nextAttemptAt!! - w.clock.now() in 48_000..72_000, "second backoff is 60 s +-20 %: ${row.nextAttemptAt!! - w.clock.now()}")

        w.clock.advance(73_000)

        assertEquals(1, job.runOnce())

        row = d.row(id)

        assertEquals(DeliveryStatus.FAILED, row.status)
        assertEquals(3, row.attempts)
        assertEquals(DeliveryError.DB_ERROR, row.lastErrorCode)
        assertEquals(FulfillmentStatus.FAILED, d.order(placed.order.id).fulfillmentStatus)
        assertEquals(1, w.orderEvents.getByOrderId(placed.order.id, pool).count { it.type == OrderEventType.DELIVERY_FAILED })
        assertEquals(0L, w.fixtures.creditBalance(alex))

        // the database is back: the admin retry (D18) resets the attempts and the row completes under the same key
        d.failLookups.set(0)
        w.db.tx { conn -> d.service.apply(conn, id, DeliveryEvent.Retry) }

        assertEquals(0, d.row(id).attempts)
        assertEquals(1, job.runOnce())
        assertEquals(DeliveryStatus.CONFIRMED, d.row(id).status)
        assertEquals(500L, w.fixtures.creditBalance(alex))
    }

    // ===== re-assertion (08 section 7.2, test 35) ==============================================================================

    private suspend fun confirmedRank(u: TestUser): Pair<Placed, Long> {
        val placed = d.place(user = u, actions = listOf(permission("a1", "group.vip")))

        d.pay(placed)
        job.runOnce()

        val row = d.rows(placed.order.id).single()

        assertEquals(DeliveryStatus.CONFIRMED, row.status)
        assertEquals(1, d.permissionStore.nodes(u.id, "group.vip").size)

        return placed to row.id
    }

    @Test
    fun `a node that vanished is added again at 60 s, 5 min and 15 min and then left alone (35)`(): Unit = runBlocking {
        val u = steve()
        val (_, id) = confirmedRank(u)
        val published = d.permissionStore.published.get()

        // the lost-grant race: a snapshot of the game server wrote the old table over the new node
        d.permissionStore.deleteNode(u.id, "group.vip")
        w.clock.advance(30_000)
        assertEquals(0, job.runOnce())
        assertEquals(0, d.permissionStore.nodes(u.id, "group.vip").size)

        w.clock.advance(31_000)
        assertEquals(1, job.runOnce())
        assertEquals(1, d.permissionStore.nodes(u.id, "group.vip").size)
        assertEquals(published + 1, d.permissionStore.published.get())
        assertEquals(1, JsonObject(d.row(id).result).getInteger("verify"))
        assertEquals(w.clock.now() + 5 * 60_000, d.row(id).nextAttemptAt)
        assertEquals(DeliveryStatus.CONFIRMED, d.row(id).status)

        d.permissionStore.deleteNode(u.id, "group.vip")
        w.clock.advance(5 * 60_000 + 1_000)
        assertEquals(1, job.runOnce())
        assertEquals(1, d.permissionStore.nodes(u.id, "group.vip").size)
        assertEquals(2, JsonObject(d.row(id).result).getInteger("verify"))
        assertEquals(w.clock.now() + 15 * 60_000, d.row(id).nextAttemptAt)

        d.permissionStore.deleteNode(u.id, "group.vip")
        w.clock.advance(15 * 60_000 + 1_000)
        assertEquals(1, job.runOnce())
        assertEquals(1, d.permissionStore.nodes(u.id, "group.vip").size)
        assertEquals(3, JsonObject(d.row(id).result).getInteger("verify"))
        assertNull(d.row(id).nextAttemptAt)

        // after the third pass a missing node is a deliberate removal
        d.permissionStore.deleteNode(u.id, "group.vip")
        w.clock.advance(3_600_000)
        assertEquals(0, job.runOnce())
        assertEquals(0, d.permissionStore.nodes(u.id, "group.vip").size)
    }

    @Test
    fun `a node of an entitlement that ended is never added again (35)`(): Unit = runBlocking {
        val u = steve()
        val (placed, id) = confirmedRank(u)
        val entitlement = w.entitlements.getByOrderItemId(placed.items[0].id, pool).single()

        w.entitlements.end(entitlement.id, EntitlementStatus.REVOKED, "ADMIN", w.clock.now(), pool)
        d.permissionStore.deleteNode(u.id, "group.vip")
        w.clock.advance(61_000)

        assertEquals(1, job.runOnce())
        assertEquals(0, d.permissionStore.nodes(u.id, "group.vip").size)
        assertNull(d.row(id).nextAttemptAt)
        assertEquals(DeliveryStatus.CONFIRMED, d.row(id).status)

        // nothing is due any more
        w.clock.advance(3_600_000)
        assertEquals(0, job.runOnce())
        assertEquals(0, d.permissionStore.nodes(u.id, "group.vip").size)
    }

    @Test
    fun `the D22 sweep runs 60 s after the job was created and then every 30 s`(): Unit = runBlocking {
        val u = steve()

        d.roster.granted = listOf(7L)

        val grant = ProductAction(id = "c1", type = DeliveryActionType.COMMAND, commands = listOf("give {username} diamond 1"))
        val undo = ProductAction(id = "c2", type = DeliveryActionType.COMMAND, phase = DeliveryPhase.REVOKE, commands = listOf("take {username} diamond 1"))
        val first = d.place(user = u, actions = listOf(grant, undo))

        d.pay(first)
        d.revoke(first)

        val row = d.rows(first.order.id).single { it.phase == DeliveryPhase.REVOKE }

        // a server row: the inline claim never touches it, only the sweep can cancel it
        assertEquals(0, job.runOnce())
        w.clock.advance(59_000)
        assertEquals(0, job.runOnce())
        assertEquals(DeliveryStatus.PENDING, d.row(row.id).status)

        w.clock.advance(2_000)
        assertEquals(1, job.runOnce())
        assertEquals(DeliveryStatus.CANCELLED, d.row(row.id).status)
        assertEquals(DeliveryError.NOTHING_TO_REVOKE, d.row(row.id).lastErrorCode)

        // the next sweep is 30 s later: a second such row waits for it
        val second = d.place(user = u, actions = listOf(grant, undo))

        d.pay(second)
        d.revoke(second)

        val again = d.rows(second.order.id).single { it.phase == DeliveryPhase.REVOKE }

        w.clock.advance(29_000)
        assertEquals(0, job.runOnce())
        assertEquals(DeliveryStatus.PENDING, d.row(again.id).status)

        w.clock.advance(2_000)
        assertEquals(1, job.runOnce())
        assertEquals(DeliveryStatus.CANCELLED, d.row(again.id).status)
    }

    @Test
    fun `the scheduler runs the delivery job on its tick`(): Unit = runBlocking {
        val u = steve()
        val placed = d.place(user = u, actions = listOf(credit("a1", 500)))

        d.pay(placed)

        val scheduler = MarketScheduler(w.clock, listOf(MarketJobs.delivery(job)))

        assertEquals(1, scheduler.tick())
        assertEquals(DeliveryStatus.CONFIRMED, d.rows(placed.order.id).single().status)
        assertEquals(500L, w.fixtures.creditBalance(u))
        assertEquals("delivery", scheduler.stats().single().name)
        assertEquals(1, scheduler.stats().single().lastHandled)
    }
}
