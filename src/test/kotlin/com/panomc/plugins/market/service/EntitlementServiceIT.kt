package com.panomc.plugins.market.service

import com.panomc.plugins.market.core.delivery.DeliveryError
import com.panomc.plugins.market.core.delivery.ProductAction
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.DeliveryActionType
import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.db.model.DeliveryStatus
import com.panomc.plugins.market.db.model.EntitlementStatus
import com.panomc.plugins.market.db.model.MarketEntitlement
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.tx.LockedOrder
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.support.Fixtures
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestWiring
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
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

/**
 * `EntitlementService` on a real MariaDB (MK-107; 08 sections 10 and 11, 01 section 5.5, 21 section 5.4; tests D-05, D-06, P-12, R2-8): the timing of a timed
 * product and its extension on repurchase, the expiry step, the shared end flow of a revoke (cancel, gate, ranges, entitlement `REVOKED`, pull-forward,
 * coverage), the tier upgrade mark in both modes and the upgrade chain. The invariants I1 to I22 are checked after every test by the base class.
 */
class EntitlementServiceIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var d: DeliveryWorld
    private val vertx: Vertx = Vertx.vertx()
    private val day = 86_400_000L

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    @BeforeEach
    fun wire() {
        w = TestWiring(pool)
        d = DeliveryWorld(w)
    }

    private fun credit(id: String, credits: Long) = ProductAction(id = id, type = DeliveryActionType.CREDIT, credit = credits)

    private fun permission(id: String, vararg nodes: String) = ProductAction(id = id, type = DeliveryActionType.PERMISSION, nodes = nodes.toList())

    private fun command(id: String, delay: Int = 0, perUnit: Boolean = false) =
        ProductAction(id = id, type = DeliveryActionType.COMMAND, commands = listOf("give {username} diamond 1"), delaySeconds = delay, perUnit = perUnit)

    private suspend fun steve(): TestUser = w.fixtures.user("Steve")

    private suspend fun timed(user: TestUser, actions: List<ProductAction>, product: MarketProduct? = null, days: Int = 30) =
        d.place(user = user, actions = actions, billing = "TIMED", periodUnit = "DAY", periodCount = days, product = product)

    private suspend fun entitlementOf(placed: Placed, index: Int = 0): MarketEntitlement = w.entitlements.getByOrderItemId(placed.items[index].id, pool).single()

    private suspend fun <T> inOrder(orderId: Long, scope: OrderLockScope = OrderLockScope.RELEASE, block: suspend (SqlConnection, LockedOrder) -> T): T =
        w.db.txRestartingOnOrderChange { conn -> d.locks.forOrder(conn, orderId, scope) { locked -> block(conn, locked) } }

    private suspend fun expire(placed: Placed, index: Int = 0): EntitlementService.Expired {
        val id = entitlementOf(placed, index).id

        return inOrder(placed.order.id, OrderLockScope.PAYMENT) { conn, locked -> d.entitlementService.expire(conn, d.service, locked.order, locked.items, id) }
    }

    private suspend fun revoke(
        placed: Placed,
        targets: Map<Long, IntRange?>? = null,
        endReason: String = "REFUND",
        before: Map<Long, Set<Int>> = emptyMap()
    ): EntitlementService.Revoked =
        inOrder(placed.order.id) { conn, locked ->
            d.entitlementService.revoke(conn, d.service, locked.order, locked.items, targets ?: locked.items.associate { it.id to null }, endReason, DeliveryError.ORDER_REVOKED, before)
        }

    private suspend fun rowsOf(placed: Placed, phase: DeliveryPhase) = d.rows(placed.order.id).filter { it.phase == phase }

    private fun payload(row: com.panomc.plugins.market.db.model.MarketDelivery) = JsonObject(row.payload)

    // ===== timing and extension (08 section 10.2) ================================================================================

    @Test
    fun `a timed purchase runs one period and the EXPIRE rows are created at expiry, not at purchase (D-05 twin)`(): Unit = runBlocking {
        val u = steve()
        val placed = timed(u, listOf(permission("a1", "group.vip"), credit("a2", 100)))
        val t0 = w.clock.now()

        d.pay(placed)
        d.runInline()

        val e = entitlementOf(placed)

        assertEquals(EntitlementStatus.ACTIVE, e.status)
        assertEquals(t0, e.startsAt)
        assertEquals(t0 + 30 * day, e.expiresAt)
        assertTrue(rowsOf(placed, DeliveryPhase.EXPIRE).isEmpty(), "nothing is planned for the end at purchase")
        assertEquals(t0 + 30 * day, d.permissionStore.of(u.id).single().expiresAt, "the permission of a timed product carries expiresAt")

        // not due yet: nothing happens
        w.clock.advance(29 * day)
        assertEquals(EntitlementService.ExpiryOutcome.SKIPPED, expire(placed).outcome)
        assertTrue(rowsOf(placed, DeliveryPhase.EXPIRE).isEmpty())

        w.clock.advance(2 * day)

        val done = expire(placed)

        assertEquals(EntitlementService.ExpiryOutcome.ENDED, done.outcome)
        assertEquals(1, done.plan!!.inserted.size)

        val ended = entitlementOf(placed)

        assertEquals(EntitlementStatus.EXPIRED, ended.status)
        assertEquals("EXPIRED", ended.endReason)
        assertEquals(w.clock.now(), ended.endedAt)

        val expireRows = rowsOf(placed, DeliveryPhase.EXPIRE)

        assertEquals(listOf(DeliveryActionType.PERMISSION), expireRows.map { it.actionType }, "credits are never taken back at expiry")
        assertEquals("REMOVE", payload(expireRows.single()).getString("op"))
        assertEquals(DeliveryStatus.PENDING, expireRows.single().status)

        // a second call (a replayed job step) finds nothing due any more and plans nothing
        assertEquals(EntitlementService.ExpiryOutcome.SKIPPED, expire(placed).outcome)
        assertEquals(1, rowsOf(placed, DeliveryPhase.EXPIRE).size)

        assertEquals(1, d.runInline())
        assertEquals(DeliveryStatus.CONFIRMED, d.row(expireRows.single().id).status)
        assertEquals(0, d.permissionStore.of(u.id).size)
        assertEquals(100L, w.fixtures.creditBalance(u))
    }

    @Test
    fun `a repurchase extends the chain and only the last link ends the ownership`(): Unit = runBlocking {
        val u = steve()
        val actions = listOf(permission("a1", "group.vip"))
        val first = timed(u, actions)
        val t0 = w.clock.now()

        d.pay(first)
        d.runInline()
        w.clock.advance(20 * day)

        val second = timed(u, actions, first.product)

        d.pay(second)

        val e1 = entitlementOf(first)
        val e2 = entitlementOf(second)

        assertEquals(t0 + 30 * day, e2.startsAt, "the extension starts at the chain end")
        assertEquals(t0 + 60 * day, e2.expiresAt)
        assertEquals(EntitlementStatus.ACTIVE, e2.status)
        assertTrue(EntitlementService.isExtension(e2))
        assertEquals(setOf(DeliveryPhase.RENEW), d.rows(second.order.id).map { it.phase }.toSet())

        d.runInline()
        assertEquals(t0 + 60 * day, d.permissionStore.of(u.id).single().expiresAt, "the node expires with the chain end")

        // the first link runs out: the chain continues, so no EXPIRE rows and the node stays
        w.clock.advance(11 * day)

        val one = expire(first)

        assertEquals(EntitlementService.ExpiryOutcome.CHAIN_CONTINUES, one.outcome)
        assertNull(one.plan)
        assertEquals(EntitlementStatus.EXPIRED, w.entitlements.getById(e1.id, pool)!!.status)
        assertTrue(rowsOf(first, DeliveryPhase.EXPIRE).isEmpty())
        assertEquals(1, d.permissionStore.of(u.id).size)

        // the last link ends the ownership
        w.clock.advance(30 * day)

        assertEquals(EntitlementService.ExpiryOutcome.ENDED, expire(second).outcome)

        val rows = rowsOf(second, DeliveryPhase.EXPIRE)

        assertEquals(1, rows.size)

        d.runInline()
        assertEquals(0, d.permissionStore.of(u.id).size)
    }

    @Test
    fun `two orders of one owner paid at the same moment extend one after the other`(): Unit = runBlocking {
        repeat(Race.rounds) { round ->
            val u = w.fixtures.user("Chain$round")
            val product = w.fixtures.product(actions = JsonArray(listOf(permission("a1", "group.vip").toJson())).encode())
            val t0 = w.clock.now()
            val placed = List(2) { timed(u, listOf(permission("a1", "group.vip")), product) }

            Race.run(2) { i -> d.pay(placed[i]) }.forEach { it.getOrThrow() }

            val ends = placed.map { entitlementOf(it) }.sortedBy { it.startsAt }

            assertEquals(t0, ends[0].startsAt, "round $round")
            assertEquals(t0 + 30 * day, ends[0].expiresAt)
            assertEquals(t0 + 30 * day, ends[1].startsAt, "the second one extends the first, it does not start now as well")
            assertEquals(t0 + 60 * day, ends[1].expiresAt)

            w.clock.advance(61 * day)
        }
    }

    @Test
    fun `a subscription entitlement and a row that is not due are never expired by the job step`(): Unit = runBlocking {
        val u = steve()
        val sub = w.fixtures.product()
        val placed = d.place(user = u, actions = listOf(permission("a1", "group.vip")), billing = "SUBSCRIPTION", product = sub, subscriptionId = null)

        d.pay(placed)

        // an entitlement owned by a subscription has no expiry of its own (01 section 5.5), even when a row claims one
        val e = entitlementOf(placed)

        assertNull(e.expiresAt)
        assertEquals(EntitlementService.ExpiryOutcome.SKIPPED, expire(placed).outcome)

        com.panomc.plugins.market.support.MarketTestDb.sql(
            pool, "UPDATE `${com.panomc.plugins.market.support.MarketTestDb.TABLE_PREFIX}market_entitlement` SET `subscriptionId` = 77, `expiresAt` = ? WHERE `id` = ?", w.clock.now() - 1000, e.id
        )

        assertEquals(EntitlementService.ExpiryOutcome.SKIPPED, expire(placed).outcome)
        assertEquals(EntitlementStatus.ACTIVE, w.entitlements.getById(e.id, pool)!!.status)
    }

    @Test
    fun `the expiry of a child of a bundle plans the child alone, the bundle line keeps its rank`(): Unit = runBlocking {
        val u = steve()
        val placed = d.placeBundle(
            u, listOf(permission("p1", "group.bundle")),
            listOf(DeliveryWorld.ChildLine(listOf(permission("c1", "group.child")), billing = "TIMED", periodUnit = "DAY", periodCount = 30))
        )

        d.pay(placed)
        d.runInline()
        assertEquals(setOf("group.bundle", "group.child"), d.permissionStore.of(u.id).map { it.node }.toSet())

        w.clock.advance(31 * day)

        val done = expire(placed, 1)

        assertEquals(EntitlementService.ExpiryOutcome.ENDED, done.outcome)
        assertEquals(1, rowsOf(placed, DeliveryPhase.EXPIRE).size)
        assertEquals(placed.items[1].id, rowsOf(placed, DeliveryPhase.EXPIRE).single().orderItemId)

        d.runInline()
        assertEquals(listOf("group.bundle"), d.permissionStore.of(u.id).map { it.node })
    }

    // ===== the end flow (08 section 11) ============================================================================================

    @Test
    fun `a refund before a delayed delivery cancels it and needs no REVOKE row (D-06 twin)`(): Unit = runBlocking {
        val u = steve()

        d.roster.granted = listOf(7L)

        val placed = d.place(user = u, actions = listOf(command("a1", delay = 3600), credit("a2", 300)))

        d.pay(placed)

        assertEquals(DeliveryStatus.SCHEDULED, d.rows(placed.order.id).single { it.actionType == DeliveryActionType.COMMAND }.status)

        val done = revoke(placed, endReason = "REFUND")

        assertEquals(1, done.ended.size)
        assertEquals(EntitlementStatus.REVOKED, entitlementOf(placed).status)
        assertEquals("REFUND", entitlementOf(placed).endReason)

        val command = d.rows(placed.order.id).single { it.actionType == DeliveryActionType.COMMAND }

        assertEquals(DeliveryStatus.CANCELLED, command.status)
        assertEquals(DeliveryError.ORDER_REVOKED, command.lastErrorCode)
        assertTrue(rowsOf(placed, DeliveryPhase.REVOKE).none { it.actionType == DeliveryActionType.COMMAND }, "the command never ran, so nothing is taken back")

        // the credit grant was due at once: it is cancelled before it ran as well, its reversal is cancelled by D22 and no ledger entry exists
        d.runInline()
        w.clock.advance(60_000)
        d.service.classify()

        assertEquals(0L, w.fixtures.creditBalance(u))
    }

    @Test
    fun `the predecessor gate holds the REVOKE row until the grant in flight resolved`(): Unit = runBlocking {
        val u = steve()
        val placed = d.place(user = u, actions = listOf(permission("a1", "group.vip")))

        d.pay(placed)

        val claimed = d.service.claimDue().single()

        assertEquals(DeliveryStatus.SENDING, claimed.status)

        val done = revoke(placed)

        assertEquals(1, done.plan.cancelRequested)

        val revokeRow = rowsOf(placed, DeliveryPhase.REVOKE).single()

        assertEquals(DeliveryStatus.PENDING, revokeRow.status)
        assertTrue(d.service.claimDue().isEmpty(), "the REVOKE row is not claimable while its grant is SENDING")

        d.service.execute(claimed)

        assertEquals(DeliveryStatus.CONFIRMED, d.row(claimed.id).status)
        assertEquals(1, d.permissionStore.of(u.id).size, "the grant that was in flight took effect")

        // the gate is open now: the REVOKE row runs and takes the rank back
        assertEquals(1, d.runInline())
        assertEquals(DeliveryStatus.CONFIRMED, d.row(revokeRow.id).status)
        assertEquals(0, d.permissionStore.of(u.id).size)
        assertEquals(EntitlementStatus.REVOKED, entitlementOf(placed).status)
    }

    @Test
    fun `a manual revoke covers every not-yet-revoked unit and the entitlement ends with the last one`(): Unit = runBlocking {
        val u = steve()
        val placed = d.place(
            user = u, quantity = 5, lineTotal = 5000,
            actions = listOf(permission("a1", "group.vip"), credit("a2", 100), command("a3", perUnit = true))
        )
        val item = placed.items[0].id

        d.pay(placed)
        d.runInline()

        assertEquals(1, d.permissionStore.of(u.id).size)
        assertEquals(500L, w.fixtures.creditBalance(u), "5 units x 100 credits")

        // a refund of the first two units: the units are named by the caller, the entitlement stays, the rank stays
        val partial = revoke(placed, mapOf(item to 0..1))

        assertTrue(partial.ended.isEmpty(), "a partial revoke leaves the entitlement ACTIVE")
        assertEquals(EntitlementStatus.ACTIVE, entitlementOf(placed).status)
        assertTrue(rowsOf(placed, DeliveryPhase.REVOKE).none { it.actionType == DeliveryActionType.PERMISSION }, "the rank goes with the last unit")
        d.runInline()
        assertEquals(300L, w.fixtures.creditBalance(u))
        assertEquals(1, d.permissionStore.of(u.id).size)

        // the refund service books the refunded quantity; a manual revoke then covers units 2..4 (derived from the live REVOKE rows and refundedQuantity)
        com.panomc.plugins.market.support.MarketTestDb.sql(
            pool, "UPDATE `${com.panomc.plugins.market.support.MarketTestDb.TABLE_PREFIX}market_order_item` SET `refundedQuantity` = 2 WHERE `id` = ?", item
        )
        com.panomc.plugins.market.support.MarketTestDb.sql(
            pool, "UPDATE `${com.panomc.plugins.market.support.MarketTestDb.TABLE_PREFIX}market_product` SET `soldCount` = `soldCount` - 2 WHERE `id` = ?", placed.product.id
        )

        val rest = revoke(placed, endReason = "ADMIN")

        assertEquals(2..4, rest.units.getValue(item))
        assertEquals(listOf(entitlementOf(placed).id), rest.ended)
        assertEquals(EntitlementStatus.REVOKED, entitlementOf(placed).status)
        assertEquals("ADMIN", entitlementOf(placed).endReason)
        assertEquals(1, rowsOf(placed, DeliveryPhase.REVOKE).count { it.actionType == DeliveryActionType.PERMISSION }, "the rank is removed with the last unit")

        d.runInline()
        assertEquals(0L, w.fixtures.creditBalance(u), "all five units were taken back")
        assertEquals(0, d.permissionStore.of(u.id).size)

        // everything is revoked: another manual revoke has nothing left to do
        val again = revoke(placed, endReason = "ADMIN")

        assertTrue(again.units.isEmpty())
        assertTrue(again.ended.isEmpty())
    }

    @Test
    fun `explicit revokedBefore wins over the derived units`(): Unit = runBlocking {
        val u = steve()
        val placed = d.place(user = u, quantity = 4, lineTotal = 4000, actions = listOf(permission("a1", "group.vip"), credit("a2", 50)))
        val item = placed.items[0].id

        d.pay(placed)
        d.runInline()

        val done = revoke(placed, mapOf(item to null), before = mapOf(item to setOf(0, 1, 2)))

        assertEquals(3..3, done.units.getValue(item), "only the unit the caller did not name as revoked")
        assertEquals(listOf(entitlementOf(placed).id), done.ended, "units 0..2 were revoked before, so this one ends the line")
        assertEquals(EntitlementStatus.REVOKED, entitlementOf(placed).status)
        assertEquals(1, rowsOf(placed, DeliveryPhase.REVOKE).count { it.actionType == DeliveryActionType.PERMISSION })

        d.runInline()
        assertEquals(150L, w.fixtures.creditBalance(u), "4 x 50 granted, one unit taken back")
        assertEquals(0, d.permissionStore.of(u.id).size)
    }

    @Test
    fun `revoking the extension link of a chain keeps the owner covered and corrects the node expiry`(): Unit = runBlocking {
        val u = steve()
        val actions = listOf(permission("a1", "group.vip"), credit("a2", 100))
        val first = timed(u, actions)
        val t0 = w.clock.now()

        d.pay(first)
        d.runInline()
        w.clock.advance(20 * day)

        val second = timed(u, actions, first.product)

        d.pay(second)
        d.runInline()
        assertEquals(t0 + 60 * day, d.permissionStore.of(u.id).single().expiresAt)
        assertEquals(200L, w.fixtures.creditBalance(u))

        val done = revoke(second)

        assertEquals(1, done.ended.size)
        assertEquals(t0 + 30 * day, done.coverage.getValue(second.items[0].id).chainEnd, "the first link still covers now")
        assertEquals(EntitlementStatus.REVOKED, entitlementOf(second).status)
        assertEquals(EntitlementStatus.ACTIVE, entitlementOf(first).status)

        val plan = rowsOf(second, DeliveryPhase.RENEW).single { it.actionType == DeliveryActionType.PERMISSION && payload(it).getString("op") == "EXTEND" && it.unitIndex != 0 }

        assertEquals(t0 + 30 * day, payload(plan).getLong("expiresAt"))
        assertTrue(rowsOf(second, DeliveryPhase.REVOKE).none { it.actionType == DeliveryActionType.PERMISSION }, "no permission removal while the buyer is covered")
        assertEquals(1, rowsOf(second, DeliveryPhase.REVOKE).count { it.actionType == DeliveryActionType.CREDIT }, "the credit inverse is still planned")

        d.runInline()
        assertEquals(t0 + 30 * day, d.permissionStore.of(u.id).single().expiresAt, "the node now ends with the chain that is left")
        assertEquals(100L, w.fixtures.creditBalance(u))
    }

    @Test
    fun `revoking the running link pulls the later links forward by the time that was removed`(): Unit = runBlocking {
        val u = steve()
        val actions = listOf(permission("a1", "group.vip"))
        val first = timed(u, actions)
        val t0 = w.clock.now()

        d.pay(first)
        d.runInline()
        w.clock.advance(10 * day)

        val second = timed(u, actions, first.product)

        d.pay(second)

        val third = timed(u, actions, first.product, days = 10)

        d.pay(third)
        d.runInline()

        // chain: first [t0, t0+30), second [t0+30, t0+60), third [t0+60, t0+70)
        assertEquals(t0 + 70 * day, entitlementOf(third).expiresAt)

        // 10 days in, the first link is refunded: 20 days are removed, the later links move forward by 20 days
        val done = revoke(first)

        assertEquals(listOf(entitlementOf(first).id), done.ended)

        val e2 = entitlementOf(second)
        val e3 = entitlementOf(third)

        assertEquals(t0 + 10 * day, e2.startsAt, "the second link now starts at the moment the first was revoked")
        assertEquals(t0 + 40 * day, e2.expiresAt)
        assertEquals(t0 + 40 * day, e3.startsAt)
        assertEquals(t0 + 50 * day, e3.expiresAt)

        // the owner is covered by the second link: no removal, the node ends with the chain end
        assertEquals(t0 + 50 * day, done.coverage.getValue(first.items[0].id).chainEnd)
        assertTrue(rowsOf(first, DeliveryPhase.REVOKE).none { it.actionType == DeliveryActionType.PERMISSION })

        d.runInline()
        assertEquals(t0 + 50 * day, d.permissionStore.of(u.id).single().expiresAt)
    }

    @Test
    fun `revoking the last link of a chain removes the permission`(): Unit = runBlocking {
        val u = steve()
        val placed = timed(u, listOf(permission("a1", "group.vip")))

        d.pay(placed)
        d.runInline()
        assertEquals(1, d.permissionStore.of(u.id).size)

        val done = revoke(placed, endReason = "CHARGEBACK")

        assertTrue(done.coverage.isEmpty())
        assertEquals("CHARGEBACK", entitlementOf(placed).endReason)
        assertEquals(1, rowsOf(placed, DeliveryPhase.REVOKE).size)

        d.runInline()
        assertEquals(0, d.permissionStore.of(u.id).size)
    }

    @Test
    fun `a revoke of a bundle takes its children with it`(): Unit = runBlocking {
        val u = steve()
        val placed = d.placeBundle(u, listOf(permission("p1", "group.bundle")), listOf(DeliveryWorld.ChildLine(listOf(permission("c1", "group.child"), credit("c2", 40)))))

        d.pay(placed)
        d.runInline()
        assertEquals(2, d.permissionStore.of(u.id).size)

        val done = revoke(placed, mapOf(placed.items[0].id to null))

        assertEquals(setOf(placed.items[0].id, placed.items[1].id), done.units.keys)
        assertEquals(2, done.ended.size)
        assertEquals(EntitlementStatus.REVOKED, entitlementOf(placed, 0).status)
        assertEquals(EntitlementStatus.REVOKED, entitlementOf(placed, 1).status)

        d.runInline()
        assertEquals(0, d.permissionStore.of(u.id).size)
        assertEquals(0L, w.fixtures.creditBalance(u))
    }

    // ===== tier upgrade (05 section 5.2, 21 section 5.4) ===========================================================================

    private class Ladder(val low: MarketProduct, val high: MarketProduct)

    private suspend fun ladder(mode: String): Ladder {
        val category = w.fixtures.category(tiered = true, upgradeMode = mode)
        val low = w.fixtures.product(price = 1000, categoryId = category.id, actions = JsonArray(listOf(permission("a1", "group.low").toJson())).encode())
        val high = w.fixtures.product(price = 2500, categoryId = category.id, actions = JsonArray(listOf(permission("a1", "group.high").toJson())).encode())

        Fixtures.setColumns(pool, "market_product", low.id, mapOf("tierRank" to 1))
        Fixtures.setColumns(pool, "market_product", high.id, mapOf("tierRank" to 2))

        return Ladder(low, high)
    }

    private suspend fun buy(h: CheckoutHarness, caller: QuoteCaller, product: MarketProduct): Placed {
        val result = h.checkout(h.body("items" to listOf(h.line(product)), "paymentMethodId" to "fake"), caller = caller)
        val order = w.orders.getByPublicId(result.order.getString("publicId"), pool)!!
        val placed = Placed(order, w.orderItems.getByOrderIds(listOf(order.id), pool), product)

        d.pay(placed)

        return placed
    }

    private suspend fun upgrade(mode: String, name: String): Triple<MarketOrder, MarketEntitlement, MarketEntitlement> {
        val ladder = ladder(mode)
        val h = CheckoutHarness(w, vertx)
        val carol = w.fixtures.user(name)

        w.fixtures.paymentMethod("fake")

        h.emails[carol.id] = "$name@example.com"

        val caller = QuoteCaller(carol.id)
        val lowOrder = buy(h, caller, ladder.low)
        val oldEntitlement = entitlementOf(lowOrder)

        assertEquals(EntitlementStatus.ACTIVE, oldEntitlement.status)
        assertEquals(1000L, oldEntitlement.pricePaid)

        val highOrder = buy(h, caller, ladder.high)

        return Triple(w.orders.getById(highOrder.order.id, pool)!!, w.entitlements.getById(oldEntitlement.id, pool)!!, entitlementOf(highOrder))
    }

    @Test
    fun `a tier upgrade pays the difference and marks the old entitlement UPGRADED (P-12 twin)`(): Unit = runBlocking {
        val (order, old, fresh) = upgrade("DIFFERENCE", "Carol")

        assertEquals(1000L, order.upgradeDiscount)
        assertEquals(1500L, order.totalPrice, "25.00 less the 10.00 already paid")
        assertEquals(EntitlementStatus.UPGRADED, old.status)
        assertEquals(fresh.id, old.replacedById)
        assertEquals("UPGRADE", old.endReason)
        assertNotNull(old.endedAt)
        assertEquals(EntitlementStatus.ACTIVE, fresh.status)
        assertEquals(2500L, fresh.pricePaid, "what was paid plus the carried-over deduction: a later upgrade credits the full value")
    }

    @Test
    fun `a tier upgrade at full price also marks the old entitlement UPGRADED (P-12 twin, mode FULL)`(): Unit = runBlocking {
        val (order, old, fresh) = upgrade("FULL", "Dora")

        assertEquals(0L, order.upgradeDiscount)
        assertEquals(2500L, order.totalPrice)
        assertEquals(EntitlementStatus.UPGRADED, old.status)
        assertEquals(fresh.id, old.replacedById)
        assertEquals(2500L, fresh.pricePaid)
    }

    @Test
    fun `the upgrade chain is followed to the live successor and a revoke ends an upgraded entitlement too (R2-8 twin)`(): Unit = runBlocking {
        val u = steve()
        val now = w.clock.now()

        suspend fun add(status: EntitlementStatus, replacedBy: Long?, orderId: Long, itemId: Long, rank: Int) = w.entitlements.add(
            MarketEntitlement(
                userId = u.id, playerUsername = "Steve", ownerKey = "u:${u.id}", productId = rank.toLong(), orderId = orderId, orderItemId = itemId, status = status,
                startsAt = now, tierCategoryId = 1, tierRank = rank, pricePaid = rank * 1000L, replacedById = replacedBy, createdAt = now, updatedAt = now
            ),
            pool
        )

        val third = add(EntitlementStatus.ACTIVE, null, 3, 3, 3)
        val second = add(EntitlementStatus.UPGRADED, third, 2, 2, 2)
        val first = add(EntitlementStatus.UPGRADED, second, 1, 1, 1)

        assertEquals(third, d.entitlementService.liveSuccessor(pool, w.entitlements.getById(first, pool)!!)!!.id)
        assertEquals(third, d.entitlementService.liveSuccessor(pool, w.entitlements.getById(second, pool)!!)!!.id)
        assertNull(d.entitlementService.liveSuccessor(pool, w.entitlements.getById(third, pool)!!), "a live entitlement has no successor")

        // a chargeback of the tier-1 order: its UPGRADED entitlement becomes REVOKED, the successor is found and revoked with its own order
        val lowOrder = d.place(user = u, actions = listOf(permission("a1", "group.low")))

        d.pay(lowOrder)
        com.panomc.plugins.market.support.MarketTestDb.sql(
            pool, "UPDATE `${com.panomc.plugins.market.support.MarketTestDb.TABLE_PREFIX}market_entitlement` SET `status` = 'UPGRADED', `replacedById` = ? WHERE `orderItemId` = ?", third, lowOrder.items[0].id
        )
        d.runInline()

        val done = revoke(lowOrder, endReason = "CHARGEBACK")

        assertEquals(1, done.ended.size)
        assertEquals(EntitlementStatus.REVOKED, entitlementOf(lowOrder).status)
        assertEquals("CHARGEBACK", entitlementOf(lowOrder).endReason)
        assertEquals(third, entitlementOf(lowOrder).replacedById, "the link to the successor is kept")
        assertEquals(third, d.entitlementService.liveSuccessor(pool, entitlementOf(lowOrder))!!.id)
    }

    @Test
    fun `an upgrade of an entitlement that is gone marks nothing`(): Unit = runBlocking {
        val ladder = ladder("DIFFERENCE")
        val h = CheckoutHarness(w, vertx)
        val erin = w.fixtures.user("Erin")

        w.fixtures.paymentMethod("fake")

        h.emails[erin.id] = "erin@example.com"

        val caller = QuoteCaller(erin.id)
        val low = buy(h, caller, ladder.low)
        val oldId = entitlementOf(low).id

        // the quote is taken while the low tier is owned, the payment arrives after it was revoked
        val result = h.checkout(h.body("items" to listOf(h.line(ladder.high)), "paymentMethodId" to "fake"), caller = caller)

        revoke(low, endReason = "ADMIN")

        val order = w.orders.getByPublicId(result.order.getString("publicId"), pool)!!

        d.pay(Placed(order, w.orderItems.getByOrderIds(listOf(order.id), pool), ladder.high))

        assertEquals(EntitlementStatus.REVOKED, w.entitlements.getById(oldId, pool)!!.status)
        assertNull(w.entitlements.getById(oldId, pool)!!.replacedById)
        assertFalse(w.entitlements.getByOwnerAndProduct("u:${erin.id}", ladder.high.id, pool).isEmpty())
    }
}
