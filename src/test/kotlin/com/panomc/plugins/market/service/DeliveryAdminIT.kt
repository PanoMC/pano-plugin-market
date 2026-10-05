package com.panomc.plugins.market.service

import com.panomc.platform.error.NoPermission
import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.core.delivery.DeliveryError
import com.panomc.plugins.market.core.delivery.ProductAction
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.DeliveryActionType
import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.db.model.DeliveryStatus
import com.panomc.plugins.market.db.model.EntitlementStatus
import com.panomc.plugins.market.db.model.FulfillmentStatus
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.error.DeliveryNotCancellable
import com.panomc.plugins.market.error.DeliveryNotRetryable
import com.panomc.plugins.market.error.InvalidOrderTransition
import com.panomc.plugins.market.routes.panel.delivery.DeliveryAdminService
import com.panomc.plugins.market.routes.panel.delivery.DeliveryAdminService.Selector
import com.panomc.plugins.market.support.MarketTestDb
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.OrderStatus
import com.panomc.plugins.market.util.Paging
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The panel delivery operations (MK-104; 08 section 14): re-run (a new attempt group, the privilege rule for a row that took effect, open rows skipped, the
 * entitlement of a revoked item), retry on the same row and key, cancel from the listed states only, revoke without a refund, and `GET /deliveries`.
 * The delivery engine is the real one of `DeliveryWorld`; only the platform seams are memory.
 */
class DeliveryAdminIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var d: DeliveryWorld
    private lateinit var admin: DeliveryAdminService

    @BeforeEach
    fun wire() {
        w = TestWiring(pool)
        d = DeliveryWorld(w)
        admin = DeliveryAdminService(w.db, d.locks, w.clock, d.service, w.orders, w.orderEvents, w.deliveries, w.entitlements, d.roster, d.entitlementService)
    }

    private fun credit(id: String, credits: Long, phase: DeliveryPhase = DeliveryPhase.GRANT) = ProductAction(id = id, type = DeliveryActionType.CREDIT, phase = phase, credit = credits)

    private fun permission(id: String, vararg nodes: String) = ProductAction(id = id, type = DeliveryActionType.PERMISSION, nodes = nodes.toList())

    private fun command(id: String, delay: Int = 0) = ProductAction(id = id, type = DeliveryActionType.COMMAND, commands = listOf("give {username} diamond 1"), delaySeconds = delay)

    private suspend fun steve(): TestUser = w.fixtures.user("Steve")

    private suspend fun setStatus(id: Long, status: DeliveryStatus, code: String? = null, sentAt: Long? = null) {
        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_delivery` SET `status` = ?, `lastErrorCode` = ?, `sentAt` = ? WHERE `id` = ?", status.name, code, sentAt, id)
    }

    private suspend fun setOrderStatus(orderId: Long, status: OrderStatus) {
        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order` SET `status` = ? WHERE `id` = ?", status.name, orderId)
    }

    private suspend fun rerun(orderId: Long, selector: Selector, phase: DeliveryPhase = DeliveryPhase.GRANT, pay: Boolean = false) =
        admin.rerun(orderId, selector, phase, pay, 1)

    /** A paid order with one confirmed credit grant of 5.00 for Steve, who has the balance of it. */
    private suspend fun confirmedCredit(user: TestUser): Placed {
        val placed = d.place(user = user, actions = listOf(credit("a1", 500)))

        d.pay(placed)
        d.runInline()

        assertEquals(DeliveryStatus.CONFIRMED, d.rows(placed.order.id).single().status)

        return placed
    }

    // ===== re-run (14.1) =================================================================================================

    @Test
    fun `a re-run of a row that never took effect creates attemptGroup plus one and needs OM only`(): Unit = runBlocking {
        val placed = d.place(buyer = "Alex", actions = listOf(credit("a1", 500)))

        d.pay(placed)
        d.runInline()

        val failed = d.rows(placed.order.id).single()

        assertEquals(DeliveryError.NO_ACCOUNT, failed.lastErrorCode)

        val alex = w.fixtures.user("Alex")
        val result = rerun(placed.order.id, Selector.All)

        assertEquals(1, result.created)
        assertEquals(0, result.skipped)
        assertFalse(result.duplicateGrant)

        val rows = d.rows(placed.order.id)
        val again = rows.single { it.id != failed.id }
        val item = placed.items[0].id

        assertEquals(1, again.attemptGroup)
        assertEquals("$item:a1:0:0:GRANT:1", again.idempotencyKey)
        assertEquals(DeliveryStatus.PENDING, again.status)
        assertEquals(0, again.attempts)
        // the failed row stays as it was, with its own key
        assertEquals(DeliveryStatus.FAILED, d.row(failed.id).status)
        assertEquals("$item:a1:0:0:GRANT:0", d.row(failed.id).idempotencyKey)

        d.runInline()

        assertEquals(DeliveryStatus.CONFIRMED, d.row(again.id).status)
        assertEquals(500L, w.fixtures.creditBalance(alex))
        assertEquals(FulfillmentStatus.FULFILLED, d.order(placed.order.id).fulfillmentStatus)

        val event = w.orderEvents.getByOrderId(placed.order.id, pool).single { it.type == OrderEventType.DELIVERY_RERUN }

        assertEquals(1, JsonObject(event.data).getInteger("created"))
        assertFalse(JsonObject(event.data).getBoolean("duplicateGrant"))
    }

    @Test
    fun `a re-run of a delivery that took effect grants again through a new ledger key and needs PAY in addition`(): Unit = runBlocking {
        val u = steve()
        val placed = confirmedCredit(u)
        val first = d.rows(placed.order.id).single()

        assertEquals(500L, w.fixtures.creditBalance(u))

        // OM alone: 403 for the whole request, nothing written, the balance is unchanged
        assertThrows(NoPermission::class.java) { runBlocking { rerun(placed.order.id, Selector.All) } }
        assertThrows(NoPermission::class.java) { runBlocking { rerun(placed.order.id, Selector.Deliveries(listOf(first.id))) } }
        assertEquals(1, d.rows(placed.order.id).size)
        assertEquals(500L, w.fixtures.creditBalance(u))
        assertEquals(0, w.orderEvents.getByOrderId(placed.order.id, pool).count { it.type == OrderEventType.DELIVERY_RERUN })

        // OM and PAY: a new row with its own key posts another grant
        val result = rerun(placed.order.id, Selector.Deliveries(listOf(first.id)), pay = true)

        assertEquals(1, result.created)
        assertTrue(result.duplicateGrant)
        assertEquals(500L, result.creditAmount)

        d.runInline()

        val second = d.rows(placed.order.id).single { it.id != first.id }

        assertEquals(1, second.attemptGroup)
        assertEquals(DeliveryStatus.CONFIRMED, second.status)
        assertNotNull(w.creditTxs.getByIdempotencyKey("delivery:${second.id}", pool))
        assertNotNull(w.creditTxs.getByIdempotencyKey("delivery:${first.id}", pool))
        assertEquals(1000L, w.fixtures.creditBalance(u))

        val event = JsonObject(w.orderEvents.getByOrderId(placed.order.id, pool).single { it.type == OrderEventType.DELIVERY_RERUN }.data)

        assertTrue(event.getBoolean("duplicateGrant"))
        assertEquals(500L, event.getLong("credits"))
    }

    @Test
    fun `FAILED with UNKNOWN_OUTCOME may have run, so it needs PAY too, and a row still open is skipped`(): Unit = runBlocking {
        val u = steve()

        d.roster.granted = listOf(7L)

        val placed = d.place(user = u, actions = listOf(command("c1")))

        d.pay(placed)

        val row = d.rows(placed.order.id).single()

        // open (PENDING): skipped, nothing is created, no privilege is asked
        val open = rerun(placed.order.id, Selector.All)

        assertEquals(0, open.created)
        assertEquals(1, open.skipped)
        assertEquals(1, d.rows(placed.order.id).size)

        // SENT is open as well
        setStatus(row.id, DeliveryStatus.SENT, sentAt = w.clock.now())

        assertEquals(1, rerun(placed.order.id, Selector.Deliveries(listOf(row.id))).skipped)

        // the re-offer budget ran out: the server may have executed it
        setStatus(row.id, DeliveryStatus.FAILED, DeliveryError.UNKNOWN_OUTCOME, w.clock.now())

        assertThrows(NoPermission::class.java) { runBlocking { rerun(placed.order.id, Selector.All) } }
        assertEquals(1, d.rows(placed.order.id).size)

        assertEquals(1, rerun(placed.order.id, Selector.All, pay = true).created)

        // another failure code never took effect
        val newest = d.rows(placed.order.id).maxByOrNull { it.attemptGroup }!!

        setStatus(newest.id, DeliveryStatus.FAILED, DeliveryError.COMMAND_ERROR)

        val third = rerun(placed.order.id, Selector.All)

        assertEquals(1, third.created)
        assertFalse(third.duplicateGrant)
        assertEquals(2, d.rows(placed.order.id).maxOf { it.attemptGroup })
    }

    @Test
    fun `a cancelled row is re-run by OM alone and copies the stored payload, the transport and the key prefix of its server`(): Unit = runBlocking {
        val u = steve()

        d.roster.granted = listOf(7L)

        val placed = d.place(user = u, actions = listOf(command("c1")))

        d.pay(placed)

        val row = d.rows(placed.order.id).single()

        admin.cancel(row.id)

        assertEquals(DeliveryStatus.CANCELLED, d.row(row.id).status)

        val result = rerun(placed.order.id, Selector.Deliveries(listOf(row.id)))

        assertEquals(1, result.created)

        val copy = d.rows(placed.order.id).single { it.id != row.id }

        assertEquals(row.payload, copy.payload)
        assertEquals(row.transport, copy.transport)
        assertEquals(7L, copy.serverId)
        assertEquals(row.actionId, copy.actionId)
        assertEquals(1, copy.attemptGroup)
        assertEquals("${placed.items[0].id}:c1:7:0:GRANT:1", copy.idempotencyKey)
        assertEquals(DeliveryStatus.PENDING, copy.status)
        assertEquals(w.clock.now(), copy.runAfter)
    }

    @Test
    fun `a delivery of another order is a 404 and a selection is checked before anything is written`(): Unit = runBlocking {
        val u = steve()
        val one = d.place(user = u, actions = listOf(credit("a1", 100)))
        val two = d.place(user = u, actions = listOf(credit("a1", 100)))

        d.pay(one)
        d.pay(two)

        val foreign = d.rows(two.order.id).single()

        assertThrows(NotFound::class.java) { runBlocking { rerun(one.order.id, Selector.Deliveries(listOf(foreign.id))) } }
        assertThrows(NotFound::class.java) { runBlocking { rerun(one.order.id, Selector.Deliveries(listOf(99999))) } }
        assertThrows(NotFound::class.java) { runBlocking { rerun(one.order.id, Selector.Items(listOf(two.items[0].id))) } }
        assertEquals(1, d.rows(one.order.id).size)
    }

    @Test
    fun `a GRANT re-run needs a paid order, an undo re-run does not`(): Unit = runBlocking {
        val u = steve()
        val placed = confirmedCredit(u)
        val row = d.rows(placed.order.id).single()

        for (status in listOf(OrderStatus.REFUNDED, OrderStatus.CHARGEBACK, OrderStatus.CANCELLED)) {
            setOrderStatus(placed.order.id, status)

            assertThrows(InvalidOrderTransition::class.java, { runBlocking { rerun(placed.order.id, Selector.All, pay = true) } }, status.name)
            assertThrows(InvalidOrderTransition::class.java, { runBlocking { rerun(placed.order.id, Selector.Deliveries(listOf(row.id)), pay = true) } }, status.name)
        }

        assertEquals(1, d.rows(placed.order.id).size)

        setOrderStatus(placed.order.id, OrderStatus.PARTIALLY_REFUNDED)

        assertEquals(1, rerun(placed.order.id, Selector.All, pay = true).created)
    }

    @Test
    fun `two re-runs of the same failed delivery at once create exactly one new row`(): Unit = runBlocking {
        repeat(Race.rounds) { round ->
            val placed = d.place(buyer = "Racer$round", actions = listOf(credit("a1", 500)))

            d.pay(placed)
            d.runInline()

            val results = Race.run(4) { rerun(placed.order.id, Selector.All) }.map { it.getOrThrow() }

            assertEquals(1, results.sumOf { it.created }, "round $round")
            assertEquals(3, results.sumOf { it.skipped }, "round $round")
            assertEquals(2, d.rows(placed.order.id).size)
        }
    }

    @Test
    fun `a GRANT re-run brings a revoked entitlement back, unless it ran out meanwhile`(): Unit = runBlocking {
        val u = steve()
        val placed = d.place(user = u, actions = listOf(permission("p1", "group.vip")))

        d.pay(placed)
        d.runInline()

        assertEquals(1, admin.revoke(placed.order.id, null, 1))

        val item = placed.items[0].id

        assertEquals(EntitlementStatus.REVOKED, w.entitlements.getByOrderItemId(item, pool).single().status)

        // the grant took effect, so bringing it back needs PAY
        assertThrows(NoPermission::class.java) { runBlocking { rerun(placed.order.id, Selector.Items(listOf(item))) } }

        val result = rerun(placed.order.id, Selector.Items(listOf(item)), pay = true)

        assertEquals(1, result.created)

        val e = w.entitlements.getByOrderItemId(item, pool).single()

        assertEquals(EntitlementStatus.ACTIVE, e.status)
        assertNull(e.endReason)
        assertNull(e.endedAt)

        // a revoked entitlement that expired in the meantime is not revived and its item is skipped
        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_entitlement` SET `status` = 'REVOKED', `endReason` = 'ADMIN', `endedAt` = ?, `expiresAt` = ? WHERE `id` = ?", w.clock.now(), w.clock.now() - 1000, e.id)

        val skipped = rerun(placed.order.id, Selector.Items(listOf(item)), pay = true)

        assertEquals(0, skipped.created)
        assertTrue(skipped.skipped >= 1)
        assertEquals(EntitlementStatus.REVOKED, w.entitlements.getByOrderItemId(item, pool).single().status)
    }

    @Test
    fun `a re-run needs an existing order and one selector`(): Unit = runBlocking {
        val u = steve()
        val placed = d.place(user = u, actions = listOf(credit("a1", 100)))

        d.pay(placed)

        // the planner makes the same row set as the first plan, as attempt group one: both rows are open, so everything is skipped
        val result = rerun(placed.order.id, Selector.Items(listOf(placed.items[0].id)))

        assertEquals(0, result.created)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `a re-run of a phase that never ran needs PAY, nothing is written without it`(): Unit = runBlocking {
        val u = steve()
        val placed = d.place(user = u, actions = listOf(credit("a1", 500), credit("r1", 300, DeliveryPhase.RENEW)))

        d.pay(placed)
        d.runInline()

        assertEquals(500L, w.fixtures.creditBalance(u))
        assertEquals(1, d.rows(placed.order.id).size)

        // the product has a RENEW credit, the order never had a RENEW row: running it first time is free value
        assertThrows(NoPermission::class.java) { runBlocking { rerun(placed.order.id, Selector.All, DeliveryPhase.RENEW) } }
        assertThrows(NoPermission::class.java) { runBlocking { rerun(placed.order.id, Selector.Items(listOf(placed.items[0].id)), DeliveryPhase.RENEW) } }
        assertEquals(1, d.rows(placed.order.id).size)
        assertEquals(0, w.orderEvents.getByOrderId(placed.order.id, pool).count { it.type == OrderEventType.DELIVERY_RERUN })

        val result = rerun(placed.order.id, Selector.All, DeliveryPhase.RENEW, pay = true)

        assertEquals(1, result.created)
        assertEquals(300L, result.creditAmount)

        d.runInline()

        assertEquals(800L, w.fixtures.creditBalance(u))
    }

    @Test
    fun `a re-plan after a server was added needs PAY because the confirmed grant would run on the new server`(): Unit = runBlocking {
        val u = steve()

        d.roster.granted = listOf(7L)

        val placed = d.place(user = u, actions = listOf(command("c1")))

        d.pay(placed)

        val first = d.rows(placed.order.id).single()

        setStatus(first.id, DeliveryStatus.CONFIRMED)

        d.roster.granted = listOf(7L, 8L)

        assertThrows(NoPermission::class.java) { runBlocking { rerun(placed.order.id, Selector.All) } }
        assertEquals(1, d.rows(placed.order.id).size)

        val result = rerun(placed.order.id, Selector.All, pay = true)

        // the plan covers every connected server again: the first one a second time, the new one for the first time
        assertEquals(2, result.created)
        assertEquals(setOf(7L, 8L), d.rows(placed.order.id).filter { it.id != first.id }.map { it.serverId }.toSet())
    }

    @Test
    fun `a grant that found no target before is a plain re-run for OM once a server exists`(): Unit = runBlocking {
        val u = steve()
        val placed = d.place(user = u, actions = listOf(command("c1")))

        d.pay(placed)

        val first = d.rows(placed.order.id).single()

        assertEquals(DeliveryStatus.FAILED, first.status)
        assertEquals(DeliveryError.NO_TARGET_SERVER, first.lastErrorCode)

        d.roster.granted = listOf(7L)

        val result = rerun(placed.order.id, Selector.All)

        assertEquals(1, result.created)
        assertFalse(result.duplicateGrant)
        assertEquals(7L, d.rows(placed.order.id).single { it.id != first.id }.serverId)
    }

    // ===== refunded lines (14.1) ==========================================================================================

    /** An order whose only line was refunded in full while its grant never ran: the grant row is CANCELLED, the entitlement REVOKED by the refund. */
    private suspend fun refundedLine(user: TestUser, quantity: Int = 1, refunded: Int = quantity): Placed {
        val placed = d.place(user = user, actions = listOf(credit("a1", 500)), quantity = quantity)

        d.pay(placed)

        val item = placed.items[0].id

        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order_item` SET `refundedQuantity` = ? WHERE `id` = ?", refunded, item)
        setOrderStatus(placed.order.id, OrderStatus.PARTIALLY_REFUNDED)
        // I17: the product counts the units that were sold and not refunded
        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_product` SET `soldCount` = ? WHERE `id` = ?", quantity - refunded, placed.product.id)
        setStatus(d.rows(placed.order.id).single().id, DeliveryStatus.CANCELLED, DeliveryError.ORDER_REVOKED)

        val e = w.entitlements.getByOrderItemId(item, pool).single()

        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_entitlement` SET `status` = 'REVOKED', `endReason` = 'REFUND', `endedAt` = ? WHERE `id` = ?", w.clock.now(), e.id)

        return placed
    }

    @Test
    fun `a re-run never delivers a line that was refunded in full, with or without PAY`(): Unit = runBlocking {
        val u = steve()
        val placed = refundedLine(u)
        val item = placed.items[0].id
        val row = d.rows(placed.order.id).single()

        for (pay in listOf(false, true)) {
            for (selector in listOf(Selector.All, Selector.Items(listOf(item)), Selector.Deliveries(listOf(row.id)))) {
                val result = rerun(placed.order.id, selector, pay = pay)

                assertEquals(0, result.created, "$selector pay=$pay")
                assertTrue(result.skipped >= 1, "$selector pay=$pay")
            }
        }

        d.runInline()

        assertEquals(1, d.rows(placed.order.id).size)
        assertEquals(EntitlementStatus.REVOKED, w.entitlements.getByOrderItemId(item, pool).single().status)
        assertEquals("REFUND", w.entitlements.getByOrderItemId(item, pool).single().endReason)
        assertEquals(0L, w.fixtures.creditBalance(u))
    }

    @Test
    fun `a line refunded in part is re-run by PAY only, and an entitlement ended by a refund is not revived by OM`(): Unit = runBlocking {
        val u = steve()
        val placed = refundedLine(u, quantity = 3, refunded = 1)
        val item = placed.items[0].id

        assertThrows(NoPermission::class.java) { runBlocking { rerun(placed.order.id, Selector.All) } }
        assertEquals(1, d.rows(placed.order.id).size)
        assertEquals(EntitlementStatus.REVOKED, w.entitlements.getByOrderItemId(item, pool).single().status)

        val result = rerun(placed.order.id, Selector.All, pay = true)

        assertEquals(1, result.created)
        assertEquals(EntitlementStatus.ACTIVE, w.entitlements.getByOrderItemId(item, pool).single().status)
    }

    @Test
    fun `a retry of a grant whose line was refunded in full or whose entitlement was revoked is refused`(): Unit = runBlocking {
        val u = steve()
        val placed = d.place(user = u, actions = listOf(credit("a1", 500)))

        d.pay(placed)

        val row = d.rows(placed.order.id).single()
        val item = placed.items[0].id

        setOrderStatus(placed.order.id, OrderStatus.PARTIALLY_REFUNDED)

        // control: a failed grant of a line nobody refunded retries
        setStatus(row.id, DeliveryStatus.FAILED, DeliveryError.COMMAND_ERROR)
        assertEquals(DeliveryStatus.PENDING, admin.retry(row.id).status)

        setStatus(row.id, DeliveryStatus.FAILED, DeliveryError.COMMAND_ERROR)
        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order_item` SET `refundedQuantity` = `quantity` WHERE `id` = ?", item)

        assertThrows(DeliveryNotRetryable::class.java) { runBlocking { admin.retry(row.id) } }
        assertEquals(DeliveryStatus.FAILED, d.row(row.id).status)

        // a revoked entitlement refuses it as well
        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order_item` SET `refundedQuantity` = 0 WHERE `id` = ?", item)
        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_entitlement` SET `status` = 'REVOKED', `endReason` = 'ADMIN', `endedAt` = ? WHERE `orderItemId` = ?", w.clock.now(), item)

        assertThrows(DeliveryNotRetryable::class.java) { runBlocking { admin.retry(row.id) } }

        d.runInline()

        assertEquals(DeliveryStatus.FAILED, d.row(row.id).status)
        assertEquals(1, d.rows(placed.order.id).size)
        assertEquals(0L, w.fixtures.creditBalance(u))
    }

    // ===== retry (14.2) ==================================================================================================

    @Test
    fun `a retry keeps the row and its key, resets the attempts and clears the error`(): Unit = runBlocking {
        val placed = d.place(buyer = "Alex", actions = listOf(credit("a1", 500)))

        d.pay(placed)
        d.runInline()

        val failed = d.rows(placed.order.id).single()

        assertEquals(DeliveryStatus.FAILED, failed.status)
        assertEquals(1, failed.attempts)

        val alex = w.fixtures.user("Alex")
        val retried = admin.retry(failed.id)

        assertEquals(DeliveryStatus.PENDING, retried.status)
        assertEquals(0, retried.attempts)
        assertNull(retried.lastErrorCode)
        assertEquals(failed.idempotencyKey, retried.idempotencyKey)
        assertEquals(1, d.rows(placed.order.id).size)

        d.runInline()

        assertEquals(DeliveryStatus.CONFIRMED, d.row(failed.id).status)
        assertEquals(500L, w.fixtures.creditBalance(alex))
    }

    @Test
    fun `a retry from SENT re-offers at once and from WAITING_SERVER goes back to PENDING`(): Unit = runBlocking {
        val u = steve()

        d.roster.granted = listOf(7L)

        val placed = d.place(user = u, actions = listOf(command("c1")))

        d.pay(placed)

        val row = d.rows(placed.order.id).single()

        setStatus(row.id, DeliveryStatus.WAITING_SERVER, DeliveryError.SERVER_OFFLINE)

        val waiting = admin.retry(row.id)

        assertEquals(DeliveryStatus.PENDING, waiting.status)
        assertNull(waiting.lastErrorCode)

        setStatus(row.id, DeliveryStatus.SENT, sentAt = w.clock.now())
        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_delivery` SET `nextAttemptAt` = ? WHERE `id` = ?", w.clock.now() + 600_000, row.id)

        val sent = admin.retry(row.id)

        assertEquals(DeliveryStatus.SENT, sent.status)
        assertEquals(w.clock.now(), sent.nextAttemptAt)
        assertEquals(row.idempotencyKey, sent.idempotencyKey)
    }

    @Test
    fun `a retry is refused outside the listed states and codes with 409 DELIVERY_NOT_RETRYABLE`(): Unit = runBlocking {
        val u = steve()

        d.roster.granted = listOf(7L)

        val placed = d.place(user = u, actions = listOf(command("c1")))

        d.pay(placed)

        val row = d.rows(placed.order.id).single()

        for (status in listOf(DeliveryStatus.PENDING, DeliveryStatus.CONFIRMED, DeliveryStatus.CANCELLED, DeliveryStatus.QUEUED, DeliveryStatus.SENDING)) {
            setStatus(row.id, status)

            assertThrows(DeliveryNotRetryable::class.java, { runBlocking { admin.retry(row.id) } }, status.name)
            assertEquals(status, d.row(row.id).status)
        }

        for (code in listOf(DeliveryError.RENDER_ERROR, DeliveryError.NO_TARGET_SERVER, DeliveryError.SERVER_REMOVED, DeliveryError.INVALID_PLAYER)) {
            setStatus(row.id, DeliveryStatus.FAILED, code)

            assertThrows(DeliveryNotRetryable::class.java, { runBlocking { admin.retry(row.id) } }, code)
        }

        // the server may have forgotten a key that old: use a re-run
        setStatus(row.id, DeliveryStatus.FAILED, DeliveryError.UNKNOWN_OUTCOME, w.clock.now() - 31L * 86_400_000L)

        assertThrows(DeliveryNotRetryable::class.java) { runBlocking { admin.retry(row.id) } }

        setStatus(row.id, DeliveryStatus.FAILED, DeliveryError.UNKNOWN_OUTCOME, w.clock.now() - 29L * 86_400_000L)

        assertEquals(DeliveryStatus.PENDING, admin.retry(row.id).status)

        assertThrows(NotFound::class.java) { runBlocking { admin.retry(424242) } }
    }

    @Test
    fun `a grant of an order that is no longer paid is not retried`(): Unit = runBlocking {
        val placed = d.place(buyer = "Alex", actions = listOf(credit("a1", 500)))

        d.pay(placed)
        d.runInline()
        w.fixtures.user("Alex")

        val failed = d.rows(placed.order.id).single()

        setOrderStatus(placed.order.id, OrderStatus.REFUNDED)

        assertThrows(DeliveryNotRetryable::class.java) { runBlocking { admin.retry(failed.id) } }
        assertEquals(DeliveryStatus.FAILED, d.row(failed.id).status)

        // back to a paid order (the invariants of the base class see the sold units again), the same retry is allowed
        setOrderStatus(placed.order.id, OrderStatus.COMPLETED)

        assertEquals(DeliveryStatus.PENDING, admin.retry(failed.id).status)
    }

    // ===== cancel (14.3) =================================================================================================

    @Test
    fun `a cancel of an unsent row is CANCELLED_BY_ADMIN at once and the order recomputes`(): Unit = runBlocking {
        val u = steve()

        d.roster.granted = listOf(7L)

        val placed = d.place(user = u, actions = listOf(command("c1"), command("c2", delay = 3600)))

        d.pay(placed)

        val rows = d.rows(placed.order.id)
        val pending = rows.single { it.actionId == "c1" }
        val scheduled = rows.single { it.actionId == "c2" }

        assertEquals(DeliveryStatus.SCHEDULED, scheduled.status)

        for (id in listOf(pending.id, scheduled.id)) {
            val cancelled = admin.cancel(id)

            assertEquals(DeliveryStatus.CANCELLED, cancelled.status)
            assertEquals(DeliveryError.CANCELLED_BY_ADMIN, cancelled.lastErrorCode)
        }

        assertEquals(FulfillmentStatus.NONE, d.order(placed.order.id).fulfillmentStatus)

        // WAITING_SERVER too
        val other = d.place(user = u, actions = listOf(command("c3")))

        d.pay(other)

        val waiting = d.rows(other.order.id).single()

        setStatus(waiting.id, DeliveryStatus.WAITING_SERVER, DeliveryError.SERVER_OFFLINE)

        assertEquals(DeliveryStatus.CANCELLED, admin.cancel(waiting.id).status)
    }

    @Test
    fun `a cancel of a row in flight only asks, the row keeps its status and the request is stored`(): Unit = runBlocking {
        val u = steve()

        d.roster.granted = listOf(7L)

        val placed = d.place(user = u, actions = listOf(command("c1")))

        d.pay(placed)

        val row = d.rows(placed.order.id).single()

        for (status in listOf(DeliveryStatus.SENT, DeliveryStatus.QUEUED)) {
            MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_delivery` SET `cancelRequestedAt` = NULL WHERE `id` = ?", row.id)
            setStatus(row.id, status, sentAt = w.clock.now())

            val asked = admin.cancel(row.id)

            assertEquals(status, asked.status, status.name)
            assertNotNull(asked.cancelRequestedAt, status.name)
        }
    }

    @Test
    fun `a cancel is refused from SENDING and from the terminal states with 409 DELIVERY_NOT_CANCELLABLE`(): Unit = runBlocking {
        val u = steve()
        val placed = d.place(user = u, actions = listOf(credit("a1", 100)))

        d.pay(placed)

        val row = d.rows(placed.order.id).single()

        for (status in listOf(DeliveryStatus.SENDING, DeliveryStatus.CONFIRMED, DeliveryStatus.FAILED, DeliveryStatus.CANCELLED)) {
            setStatus(row.id, status)

            assertThrows(DeliveryNotCancellable::class.java, { runBlocking { admin.cancel(row.id) } }, status.name)
            assertEquals(status, d.row(row.id).status)
        }

        assertThrows(NotFound::class.java) { runBlocking { admin.cancel(424242) } }
    }

    // ===== revoke (14.4) =================================================================================================

    @Test
    fun `a revoke plans the undo of what was delivered, ends the entitlement as ADMIN and moves no money`(): Unit = runBlocking {
        val u = steve()
        val placed = confirmedCredit(u)

        assertEquals(500L, w.fixtures.creditBalance(u))

        assertEquals(1, admin.revoke(placed.order.id, null, 1))

        val item = placed.items[0].id
        val undo = d.rows(placed.order.id).single { it.phase == DeliveryPhase.REVOKE }

        assertEquals("$item:a1:0:0:REVOKE:0", undo.idempotencyKey)

        val e = w.entitlements.getByOrderItemId(item, pool).single()

        assertEquals(EntitlementStatus.REVOKED, e.status)
        assertEquals("ADMIN", e.endReason)
        assertEquals(OrderStatus.COMPLETED, d.order(placed.order.id).status)
        assertEquals(1, w.orderEvents.getByOrderId(placed.order.id, pool).count { it.type == OrderEventType.DELIVERY_REVOKED })

        d.runInline()

        assertEquals(DeliveryStatus.CONFIRMED, d.row(undo.id).status)
        assertEquals(0L, w.fixtures.creditBalance(u))
        assertEquals(FulfillmentStatus.REVOKED, d.order(placed.order.id).fulfillmentStatus)

        // nothing is left to revoke: the second call is a no-op
        assertEquals(0, admin.revoke(placed.order.id, null, 1))
        assertEquals(1, d.rows(placed.order.id).count { it.phase == DeliveryPhase.REVOKE })
    }

    @Test
    fun `revoke, re-run the grant with PAY, revoke again undoes the second grant too`(): Unit = runBlocking {
        val u = steve()
        val placed = confirmedCredit(u)
        val item = placed.items[0].id

        assertEquals(1, admin.revoke(placed.order.id, null, 1))
        d.runInline()
        assertEquals(0L, w.fixtures.creditBalance(u))

        assertEquals(1, rerun(placed.order.id, Selector.Items(listOf(item)), pay = true).created)
        d.runInline()

        assertEquals(500L, w.fixtures.creditBalance(u))
        assertEquals(EntitlementStatus.ACTIVE, w.entitlements.getByOrderItemId(item, pool).single().status)

        // the first revoke no longer stands for the units: the item can be revoked again
        assertEquals(1, admin.revoke(placed.order.id, null, 1))

        val undos = d.rows(placed.order.id).filter { it.phase == DeliveryPhase.REVOKE }

        assertEquals(2, undos.size)
        assertEquals(2, undos.map { it.idempotencyKey }.toSet().size)

        d.runInline()

        assertEquals(EntitlementStatus.REVOKED, w.entitlements.getByOrderItemId(item, pool).single().status)
        assertEquals(0L, w.fixtures.creditBalance(u))

        // and now nothing is left again
        assertEquals(0, admin.revoke(placed.order.id, null, 1))
    }

    @Test
    fun `a revoke of a grant nothing delivered cancels it and plans no undo`(): Unit = runBlocking {
        val u = steve()
        val placed = d.place(user = u, actions = listOf(credit("a1", 500)))

        d.pay(placed)

        assertEquals(0, admin.revoke(placed.order.id, listOf(placed.items[0].id), 1))

        val row = d.rows(placed.order.id).single()

        assertEquals(DeliveryStatus.CANCELLED, row.status)
        assertEquals(DeliveryError.ORDER_REVOKED, row.lastErrorCode)
        assertEquals(EntitlementStatus.REVOKED, w.entitlements.getByOrderItemId(placed.items[0].id, pool).single().status)

        d.runInline()

        assertEquals(0L, w.fixtures.creditBalance(u))
    }

    @Test
    fun `a revoke of an unknown line is 404 and writes nothing, an upgraded entitlement is ended as REVOKED (MK-107)`(): Unit = runBlocking {
        val u = steve()
        val placed = confirmedCredit(u)
        val item = placed.items[0].id

        assertThrows(NotFound::class.java) { runBlocking { admin.revoke(placed.order.id, listOf(999999), 1) } }
        assertEquals(1, d.rows(placed.order.id).size)

        val e = w.entitlements.getByOrderItemId(item, pool).single()

        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_entitlement` SET `status` = 'UPGRADED', `replacedById` = 4242 WHERE `id` = ?", e.id)

        assertEquals(1, admin.revoke(placed.order.id, null, 1))

        val ended = w.entitlements.getByOrderItemId(item, pool).single()

        assertEquals(EntitlementStatus.REVOKED, ended.status)
        assertEquals("ADMIN", ended.endReason)
        assertEquals(4242L, ended.replacedById, "the link to the successor stays")
    }

    @Test
    fun `a revoke of a timed link whose owner is still covered by another one corrects the node instead of removing it (MK-107)`(): Unit = runBlocking {
        val u = steve()
        val actions = listOf(permission("p1", "group.vip"))
        val first = d.place(user = u, actions = actions, billing = "TIMED", periodUnit = "DAY", periodCount = 30)
        val t0 = w.clock.now()

        d.pay(first)
        d.runInline()
        w.clock.advance(10 * 86_400_000L)

        val second = d.place(user = u, actions = actions, billing = "TIMED", periodUnit = "DAY", periodCount = 30, product = first.product)

        d.pay(second)
        d.runInline()

        assertEquals(2, MarketTestDb.count(pool, "market_entitlement", "`status` = 'ACTIVE'"))
        assertEquals(t0 + 60 * 86_400_000L, d.permissionStore.of(u.id).single().expiresAt)

        // the running first link is revoked: no REVOKE row for the rank, the second link moves forward by the 20 days that were left
        assertEquals(1, admin.revoke(first.order.id, null, 1), "one row planned: the EXTEND that corrects the node expiry, no REVOKE of the rank")
        assertEquals(EntitlementStatus.REVOKED, w.entitlements.getByOrderItemId(first.items[0].id, pool).single().status)

        val e2 = w.entitlements.getByOrderItemId(second.items[0].id, pool).single()

        assertEquals(t0 + 10 * 86_400_000L, e2.startsAt)
        assertEquals(t0 + 40 * 86_400_000L, e2.expiresAt)
        assertEquals(0, d.rows(first.order.id).count { it.phase == DeliveryPhase.REVOKE })

        d.runInline()
        assertEquals(t0 + 40 * 86_400_000L, d.permissionStore.of(u.id).single().expiresAt)
    }

    @Test
    fun `a child revoked alone and then the whole bundle order does not revoke the child twice and debits the credits once (MK-107 review)`(): Unit = runBlocking {
        val u = steve()
        val placed = d.placeBundle(u, listOf(credit("p1", 100)), listOf(DeliveryWorld.ChildLine(listOf(credit("c1", 40)))))
        val child = placed.items[1].id

        d.pay(placed)
        d.runInline()
        assertEquals(140L, w.fixtures.creditBalance(u))

        assertEquals(1, admin.revoke(placed.order.id, listOf(child), 1))
        d.runInline()
        assertEquals(100L, w.fixtures.creditBalance(u))

        val childRevokes = d.rows(placed.order.id).filter { it.phase == DeliveryPhase.REVOKE && it.orderItemId == child }.map { it.idempotencyKey }

        assertEquals(1, childRevokes.size)

        // the whole order: only the bundle line is left to revoke, the child keeps its single REVOKE row
        assertEquals(1, admin.revoke(placed.order.id, null, 1))
        d.runInline()

        assertEquals(childRevokes, d.rows(placed.order.id).filter { it.phase == DeliveryPhase.REVOKE && it.orderItemId == child }.map { it.idempotencyKey })
        assertEquals(1, d.rows(placed.order.id).count { it.phase == DeliveryPhase.REVOKE && it.orderItemId == placed.items[0].id })
        assertEquals(0L, w.fixtures.creditBalance(u), "100 + 40 granted, each taken back exactly once")
    }

    // ===== list (14.5) ===================================================================================================

    @Test
    fun `the list filters by status, phase, action type, server and search and pages`(): Unit = runBlocking {
        val u = steve()

        d.roster.granted = listOf(7L)

        val placed = d.place(user = u, actions = listOf(credit("a1", 500), command("c1")))

        d.pay(placed)
        d.runInline()

        val all = admin.list(DeliveryAdminService.Filter(), Paging.Window(1, 10))

        assertEquals(2, all.total)
        assertEquals(2, all.rows.size)

        val credit = all.rows.single { it.getString("actionType") == "CREDIT" }

        assertEquals("CONFIRMED", credit.getString("status"))
        assertEquals("VIP", credit.getString("productName"))
        assertEquals("Steve", credit.getString("playerUsername"))
        assertEquals(placed.order.id, credit.getLong("orderId"))
        assertFalse(credit.getBoolean("cancelRequested"))
        assertNotNull(credit.getJsonObject("payload"))

        val command = all.rows.single { it.getString("actionType") == "COMMAND" }

        assertEquals("server7", command.getString("serverName"))
        assertEquals(7L, command.getLong("serverId"))

        assertEquals(1, admin.list(DeliveryAdminService.Filter(status = DeliveryStatus.PENDING), Paging.Window(1, 10)).total)
        assertEquals(1, admin.list(DeliveryAdminService.Filter(actionType = DeliveryActionType.CREDIT), Paging.Window(1, 10)).total)
        assertEquals(2, admin.list(DeliveryAdminService.Filter(phase = DeliveryPhase.GRANT), Paging.Window(1, 10)).total)
        assertEquals(0, admin.list(DeliveryAdminService.Filter(phase = DeliveryPhase.REVOKE), Paging.Window(1, 10)).total)
        assertEquals(1, admin.list(DeliveryAdminService.Filter(serverId = 7), Paging.Window(1, 10)).total)
        assertEquals(2, admin.list(DeliveryAdminService.Filter(search = "stev"), Paging.Window(1, 10)).total)
        assertEquals(2, admin.list(DeliveryAdminService.Filter(search = placed.order.id.toString()), Paging.Window(1, 10)).total)
        assertEquals(1, admin.list(DeliveryAdminService.Filter(search = "${placed.items[0].id}:c1:"), Paging.Window(1, 10)).total)
        assertEquals(0, admin.list(DeliveryAdminService.Filter(search = "%"), Paging.Window(1, 10)).total)

        val page = admin.list(DeliveryAdminService.Filter(), Paging.Window(2, 1))

        assertEquals(2, page.total)
        assertEquals(1, page.rows.size)
    }

    @Test
    fun `the payload of a webhook row never carries its secret`() {
        val view = DeliveryAdminService.payloadView(
            """{"webhook":{"url":"https://example.com/h","format":"JSON","signing":"HMAC_SHA256","secret":"v1:abcdef","event":"action.grant","body":"{}"}}"""
        ) as JsonObject

        assertFalse(view.getJsonObject("webhook").containsKey("secret"))
        assertEquals("https://example.com/h", view.getJsonObject("webhook").getString("url"))
        assertFalse(view.encode().contains("abcdef"))

        assertEquals(JsonObject().put("credits", 500), DeliveryAdminService.payloadView("""{"credits":500}"""))
        assertNull(DeliveryAdminService.payloadView(null))
    }
}
