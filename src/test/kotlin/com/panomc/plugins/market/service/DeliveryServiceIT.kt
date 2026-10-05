package com.panomc.plugins.market.service

import com.panomc.plugins.market.core.delivery.DeliveryError
import com.panomc.plugins.market.core.delivery.ProductAction
import com.panomc.plugins.market.core.order.OrderActor
import com.panomc.plugins.market.core.order.OrderEffect
import com.panomc.plugins.market.core.order.OrderEvent
import com.panomc.plugins.market.core.order.OrderTransition
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.CreditTxType
import com.panomc.plugins.market.db.model.DeliveryActionType
import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.db.model.DeliveryStatus
import com.panomc.plugins.market.db.model.EntitlementStatus
import com.panomc.plugins.market.db.model.FulfillmentBy
import com.panomc.plugins.market.db.model.FulfillmentStatus
import com.panomc.plugins.market.db.model.MarketDelivery
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.service.platform.DirectoryUser
import com.panomc.plugins.market.service.platform.PermissionWriter
import com.panomc.plugins.market.service.platform.PlayerAccounts
import com.panomc.plugins.market.service.platform.ServerRoster
import com.panomc.plugins.market.service.platform.StoredPermissionNode
import com.panomc.plugins.market.service.platform.UserDirectory
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestUsers
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** The platform's `permission_node` table as memory: ids are handed out in order and [rewriteIds] is the snapshot rewrite of `SavePermissionsSnapshotEvent`. */
internal class FakePermissions : PermissionWriter {
    class Node(val id: Long, val userId: Long, val node: String, val context: JsonObject, val expiresAt: Long?)

    private val rows = ArrayList<Node>()
    private val next = AtomicLong(100)
    val published = AtomicInteger()

    override suspend fun userNodes(userId: Long, sqlClient: SqlClient): List<StoredPermissionNode> =
        synchronized(rows) { rows.filter { it.userId == userId }.map { StoredPermissionNode(it.id, it.node, it.context, it.expiresAt, true) } }

    override suspend fun add(userId: Long, node: String, context: JsonObject, expiresAt: Long?, sqlClient: SqlClient): Long = synchronized(rows) {
        val id = next.incrementAndGet()

        rows += Node(id, userId, node, context.copy(), expiresAt)

        id
    }

    override suspend fun deleteByIds(ids: List<Long>, sqlClient: SqlClient) {
        synchronized(rows) { rows.removeAll { it.id in ids } }
    }

    override suspend fun publish() {
        published.incrementAndGet()
    }

    fun of(userId: Long): List<Node> = synchronized(rows) { rows.filter { it.userId == userId }.sortedBy { it.id } }

    fun nodes(userId: Long, node: String): List<Node> = synchronized(rows) { rows.filter { it.userId == userId && it.node == node } }

    /** What `SavePermissionsSnapshotEvent` does: the same rows again with new ids. */
    fun rewriteIds() = synchronized(rows) {
        val copy = rows.toList()

        rows.clear()

        for (r in copy) rows += Node(next.incrementAndGet(), r.userId, r.node, r.context, r.expiresAt)
    }

    fun deleteNode(userId: Long, node: String) {
        synchronized(rows) { rows.removeAll { it.userId == userId && it.node == node } }
    }

    /** An admin's row, written outside the market. */
    fun seed(userId: Long, node: String, context: JsonObject, expiresAt: Long?): Long = synchronized(rows) {
        val id = next.incrementAndGet()

        rows += Node(id, userId, node, context, expiresAt)

        id
    }
}

internal class FakePlayerAccounts(private val users: TestUsers) : PlayerAccounts {
    val created = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val lock = Any()

    override suspend fun findOrCreate(username: String, sqlClient: SqlClient): Long? = synchronized(lock) {
        users.idOf(username) ?: run {
            created += username

            users.create(username)
        }
    }
}

/** A mutable server roster: the granted ids a plan may target. */
internal class FakeRoster : ServerRoster {
    @Volatile
    var granted: List<Long> = emptyList()

    override suspend fun snapshot(sqlClient: SqlClient) = ServerRoster.Roster(granted, granted, granted.associateWith { "server$it" })
}

class Placed(val order: MarketOrder, val items: List<MarketOrderItem>, val product: com.panomc.plugins.market.db.model.MarketProduct)

/**
 * The delivery engine on the real object graph of a T2 test (17 section 5.3): real DAOs, `CreditService`, `DeliveryService`, `EntitlementService`; the
 * platform seams (S9) are memory: users from `TestUsers`, permission nodes in [permissionStore], servers in [roster].
 */
internal class DeliveryWorld(val w: TestWiring, maxAttempts: Int = 5) {
    val permissionStore = FakePermissions()
    val playerAccounts = FakePlayerAccounts(w.users)
    val roster = FakeRoster()
    val locks = Locks(w.orders, w.orderItems, w.redemptions, w.creditAccounts)
    val credits = CreditService(w.clock, w.creditAccounts, w.creditTxs, w.creditEntries)
    val permissionService = PermissionGrantService(permissionStore, w.clock)
    val entitlementService = EntitlementService(w.clock, { w.config }, w.entitlements)

    /** The next [failLookups] user lookups throw (a database failure inside an executor). */
    val failLookups = AtomicInteger(0)

    private val directory = object : UserDirectory {
        override suspend fun byUsername(username: String, sqlClient: SqlClient): DirectoryUser? {
            if (failLookups.get() > 0 && failLookups.getAndDecrement() > 0) throw IllegalStateException("the database went away")

            return w.users.idOf(username)?.let { DirectoryUser(it, w.users.nameOf(it)!!) }
        }

        override suspend fun usernameOf(userId: Long, sqlClient: SqlClient): String? = w.users.nameOf(userId)

        override suspend fun emailOf(userId: Long, sqlClient: SqlClient): String? = null

        override suspend fun hasPermission(userId: Long, node: String): Boolean = false
    }

    val service = DeliveryService(
        w.db, locks, w.clock, w.ids, { w.config }, w.orders, w.orderItems, w.orderEvents, w.deliveries, w.entitlements, w.creditAccounts, w.products, w.fields,
        roster, directory, playerAccounts, credits, permissionService, Random(7)
    )
    val effects = DeliveryEffects(entitlementService, service, w.orders, ForeignEffects { _, _, _ -> })

    init {
        w.configure {
            com.panomc.plugins.market.config.MarketConfig(
                currency = com.panomc.plugins.market.util.CurrencyType.EUR, vatPercent = 20.0, showVatInPrice = true, creditValue = 1.0, storeTimeZone = "UTC",
                deliveryMaxAttempts = maxAttempts
            )
        }
    }

    /**
     * A paid order (status `COMPLETED`) for [buyer] with one product line whose snapshot carries [actions]. A registered buyer ([user]) is the
     * payer and the recipient account; without one the order is a guest order for the name.
     */
    suspend fun place(
        buyer: String = "Steve",
        user: TestUser? = null,
        actions: List<ProductAction>,
        quantity: Int = 1,
        billing: String = "ONE_TIME",
        periodUnit: String? = null,
        periodCount: Int? = null,
        fulfillmentBy: FulfillmentBy = FulfillmentBy.MARKET,
        source: OrderSource = OrderSource.STOREFRONT,
        status: OrderStatus = OrderStatus.COMPLETED,
        reservation: ReservationState = ReservationState.COMMITTED,
        tier: Pair<Long, Int>? = null,
        lineTotal: Long = 1000,
        product: com.panomc.plugins.market.db.model.MarketProduct? = null,
        subscriptionId: Long? = null
    ): Placed {
        val now = w.clock.now()
        val product = product ?: w.fixtures.product(actions = JsonArray(actions.map { it.toJson() }).encode())
        val key = if (user != null) "u:${user.id}" else "g:${buyer.lowercase()}"
        val paid = status == OrderStatus.COMPLETED
        val orderId = w.orders.add(
            MarketOrder(
                userId = user?.id, playerUsername = buyer, recipientUsername = buyer, recipientUserId = user?.id, recipientKey = key, buyerKey = key,
                publicId = w.ids.publicId(), status = status, currency = "EUR", baseCurrency = "EUR", subtotal = lineTotal, totalPrice = lineTotal,
                gatewayAmount = lineTotal, paidAmount = if (paid) lineTotal else 0, paidAt = if (paid) now else null, createdAt = now, updatedAt = now,
                paymentMethodId = "manual", reservationState = reservation, expiresAt = if (status == OrderStatus.PENDING) now + 1_800_000 else null,
                source = source, fulfillmentBy = fulfillmentBy, subscriptionId = subscriptionId
            ),
            w.pool
        )
        val snapshot = JsonObject()
            .put("slug", product.slug).put("billingMode", billing).put("periodUnit", periodUnit).put("periodCount", periodCount)
            .put("actions", JsonArray(actions.map { it.toJson() })).put("tierCategoryId", tier?.first).put("tierRank", tier?.second)
        val itemId = w.orderItems.add(
            MarketOrderItem(
                orderId = orderId, productId = product.id, productName = "VIP", quantity = quantity, unitPrice = lineTotal / quantity, lineTotal = lineTotal,
                listUnitPrice = lineTotal / quantity, kind = OrderItemKind.PRODUCT, snapshot = snapshot.encode(), createdAt = now, updatedAt = now
            ),
            w.pool
        )

        // I17: the units of a paid order are in the product's soldCount (an order that is still pending is counted by its commit)
        if (paid) com.panomc.plugins.market.support.MarketTestDb.sql(w.pool, "UPDATE `${com.panomc.plugins.market.support.MarketTestDb.TABLE_PREFIX}market_product` SET `soldCount` = `soldCount` + ? WHERE `id` = ?", quantity, product.id)

        return Placed(w.orders.getById(orderId, w.pool)!!, listOf(w.orderItems.getById(itemId, w.pool)!!), product)
    }

    /** The two foreign effects of O2 inside one transaction under the order lock (what `OrderService.transition` does at O2). */
    suspend fun pay(placed: Placed) {
        w.db.txRestartingOnOrderChange { conn ->
            locks.forOrder(conn, placed.order.id, OrderLockScope.COMMIT) { locked ->
                effects.apply(conn, locked, OrderEffect.GrantEntitlements)
                effects.apply(conn, locked, OrderEffect.QueueGrantDeliveries)
            }
        }
    }

    /** The end flow of a full refund: `REVOKE` rows for every unit, then the entitlement `REVOKED` (steps 3 and 4 of 08 section 11.1). */
    suspend fun revoke(placed: Placed, reason: String = DeliveryError.ORDER_REVOKED): DeliveryService.EndPlan =
        w.db.txRestartingOnOrderChange { conn ->
            locks.forOrder(conn, placed.order.id, OrderLockScope.COMMIT) { locked ->
                val plan = service.planRevoke(conn, locked.order, locked.items, locked.items.associate { it.id to (0 until it.quantity) }, reason = reason)

                for (item in locked.items) {
                    for (e in w.entitlements.getByOrderItemId(item.id, conn)) w.entitlements.end(e.id, EntitlementStatus.REVOKED, "REFUND", w.clock.now(), conn)
                }

                service.refreshFulfillment(conn, locked.order.id)

                plan
            }
        }

    suspend fun rows(orderId: Long): List<MarketDelivery> = w.deliveries.getByOrderId(orderId, w.pool)

    suspend fun row(id: Long): MarketDelivery = w.deliveries.getById(id, w.pool)!!

    suspend fun order(id: Long): MarketOrder = w.orders.getById(id, w.pool)!!

    /** Claims and executes everything that is due once (the inline half of `DeliveryJob`). */
    suspend fun runInline(): Int {
        var n = 0

        for (row in service.claimDue()) if (service.execute(row) != null) n++

        return n
    }
}

/**
 * `DeliveryService`, `EntitlementService` and `PermissionGrantService` on a real MariaDB (MK-102; 08 sections 5 to 7, 9, 10, 11, 13; tests 22, 28, 29 to 36,
 * 53, 54, 64 of 08 section 20 and R2-6, R2-9, D-06): rows planned inside the order transaction, the CREDIT executor through the ledger key, the PERMISSION
 * executor with its tuple semantics, entitlements at O2, and the end flow (cancel, gate, D22). The invariants I1 to I22 are checked after every test by the base class.
 */
class DeliveryServiceIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var d: DeliveryWorld

    @BeforeEach
    fun wire() {
        w = TestWiring(pool)
        d = DeliveryWorld(w)
    }

    private fun credit(id: String, credits: Long, phase: DeliveryPhase = DeliveryPhase.GRANT) = ProductAction(id = id, type = DeliveryActionType.CREDIT, phase = phase, credit = credits)

    private fun permission(id: String, vararg nodes: String, servers: List<Long> = emptyList(), phase: DeliveryPhase = DeliveryPhase.GRANT) =
        ProductAction(id = id, type = DeliveryActionType.PERMISSION, phase = phase, nodes = nodes.toList(), targetServers = servers)

    private fun command(id: String, delay: Int = 0) = ProductAction(id = id, type = DeliveryActionType.COMMAND, commands = listOf("give {username} diamond 1"), delaySeconds = delay)

    private suspend fun steve(): TestUser = w.fixtures.user("Steve")

    // ===== planning ======================================================================================================

    @Test
    fun `rows and entitlement are planned inside the order transaction, a rollback takes both with it`(): Unit = runBlocking {
        val u = steve()
        val placed = d.place(user = u, actions = listOf(credit("a1", 500), permission("a2", "group.vip")))

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                w.db.tx { conn ->
                    d.locks.forOrder(conn, placed.order.id, OrderLockScope.COMMIT) { locked ->
                        d.effects.apply(conn, locked, OrderEffect.GrantEntitlements)
                        d.effects.apply(conn, locked, OrderEffect.QueueGrantDeliveries)

                        assertEquals(2, w.deliveries.getByOrderId(placed.order.id, conn).size)

                        throw IllegalStateException("the order transaction fails after the effects")
                    }
                }
            }
        }

        assertEquals(0, d.rows(placed.order.id).size)
        assertEquals(0, w.entitlements.getByOrderItemId(placed.items[0].id, pool).size)
        assertEquals(FulfillmentStatus.NONE, d.order(placed.order.id).fulfillmentStatus)

        d.pay(placed)

        val rows = d.rows(placed.order.id)

        assertEquals(2, rows.size)
        assertTrue(rows.all { it.status == DeliveryStatus.PENDING && it.phase == DeliveryPhase.GRANT && it.attemptGroup == 0 && it.nextAttemptAt != null })
        assertEquals(setOf("${placed.items[0].id}:a1:0:0:GRANT:0", "${placed.items[0].id}:a2:0:0:GRANT:0"), rows.map { it.idempotencyKey }.toSet())
        assertEquals(1, w.entitlements.getByOrderItemId(placed.items[0].id, pool).size)
        assertEquals(FulfillmentStatus.PENDING, d.order(placed.order.id).fulfillmentStatus)
    }

    @Test
    fun `O2 through OrderService writes the entitlement and the GRANT rows in the same transaction`(): Unit = runBlocking {
        val u = steve()
        val placed = d.place(user = u, actions = listOf(credit("a1", 500)), status = OrderStatus.PENDING, reservation = ReservationState.HELD)
        val redemptions = RedemptionService(w.clock, d.locks, w.redemptions)
        val orderService = OrderService(
            w.clock, w.ids, w.orders, w.orderItems, w.orderEvents, w.payments, redemptions, { _, _ -> false },
            reservations = ReservationService(w.clock, d.locks, redemptions, w.orders), foreign = d.effects, statsCurrency = { "EUR" }
        )

        val decision = w.db.txRestartingOnOrderChange { conn ->
            d.locks.forOrder(conn, placed.order.id, OrderLockScope.COMMIT) { locked ->
                orderService.transition(conn, locked, OrderEvent.Paid(null, OrderActor.ADMIN)).decision
            }
        }

        assertTrue(decision is OrderTransition.Move)
        assertEquals(OrderStatus.COMPLETED, d.order(placed.order.id).status)
        assertEquals(1, w.entitlements.getByOrderItemId(placed.items[0].id, pool).size)
        assertEquals(listOf(DeliveryStatus.PENDING), d.rows(placed.order.id).map { it.status })
        assertEquals(FulfillmentStatus.PENDING, d.order(placed.order.id).fulfillmentStatus)
    }

    @Test
    fun `planning twice in one transaction replays the same keys and inserts nothing new`(): Unit = runBlocking {
        val u = steve()
        val placed = d.place(user = u, actions = listOf(credit("a1", 500), permission("a2", "group.vip")))

        d.pay(placed)

        val first = d.rows(placed.order.id).map { it.idempotencyKey }.sorted()

        val again = w.db.txRestartingOnOrderChange { conn ->
            d.locks.forOrder(conn, placed.order.id, OrderLockScope.COMMIT) { locked -> d.service.planGrant(conn, locked.order, locked.items) }
        }

        assertEquals(emptyList<Long>(), again)
        assertEquals(first, d.rows(placed.order.id).map { it.idempotencyKey }.sorted())
        assertEquals(1, w.entitlements.getByOrderItemId(placed.items[0].id, pool).size)
    }

    @Test
    fun `an order the gateway fulfils gets its entitlement and no delivery row (R2-9)`(): Unit = runBlocking {
        val u = steve()
        val placed = d.place(user = u, actions = listOf(credit("a1", 500)), fulfillmentBy = FulfillmentBy.GATEWAY)

        d.pay(placed)

        assertEquals(0, d.rows(placed.order.id).size)
        assertEquals(1, w.entitlements.getByOrderItemId(placed.items[0].id, pool).size)
        assertEquals(FulfillmentStatus.NONE, d.order(placed.order.id).fulfillmentStatus)

        val plan = d.revoke(placed)

        assertEquals(0, plan.inserted.size)
        assertEquals(0, d.rows(placed.order.id).size)
    }

    @Test
    fun `an action that cannot be delivered is a FAILED row with a timeline entry and the other actions are still planned`(): Unit = runBlocking {
        val u = steve()
        // no server is granted: the COMMAND has no target (NO_TARGET_SERVER)
        val placed = d.place(user = u, actions = listOf(command("c1"), credit("a2", 300)))

        d.pay(placed)

        val rows = d.rows(placed.order.id).associateBy { it.actionId }
        val failed = rows.getValue("c1")

        assertEquals(DeliveryStatus.FAILED, failed.status)
        assertEquals(DeliveryError.NO_TARGET_SERVER, failed.lastErrorCode)
        assertEquals(DeliveryStatus.PENDING, rows.getValue("a2").status)
        // one failed, one open: PARTIAL (08 section 13)

        val events = w.orderEvents.getByOrderId(placed.order.id, pool).filter { it.type == OrderEventType.DELIVERY_FAILED }

        assertEquals(1, events.size)
        assertEquals(failed.id, JsonObject(events[0].data).getLong("deliveryId"))
        assertEquals(DeliveryError.NO_TARGET_SERVER, JsonObject(events[0].data).getString("code"))
        assertEquals(FulfillmentStatus.PARTIAL, d.order(placed.order.id).fulfillmentStatus)

        d.runInline()

        assertEquals(FulfillmentStatus.PARTIAL, d.order(placed.order.id).fulfillmentStatus)
    }

    // ===== CREDIT ========================================================================================================

    @Test
    fun `a CREDIT grant posts ISSUANCE to the user through the ledger key and confirms the row (28)`(): Unit = runBlocking {
        val u = steve()
        val placed = d.place(user = u, actions = listOf(credit("a1", 500)))

        d.pay(placed)

        assertEquals(1, d.runInline())

        val row = d.rows(placed.order.id).single()
        val tx = w.creditTxs.getByIdempotencyKey("delivery:${row.id}", pool)!!

        assertEquals(DeliveryStatus.CONFIRMED, row.status)
        assertEquals(CreditTxType.ACTION, tx.type)
        assertEquals(500L, tx.amount)
        assertEquals(row.id, tx.deliveryId)
        assertEquals(placed.order.id, tx.orderId)
        assertEquals(tx.id, JsonObject(row.result).getLong("creditTxId"))
        assertEquals(u.id, JsonObject(row.result).getLong("userId"))
        assertEquals(500L, w.fixtures.creditBalance(u))
        assertNotNull(row.confirmedAt)
        assertEquals(1, row.attempts)
        assertEquals(FulfillmentStatus.FULFILLED, d.order(placed.order.id).fulfillmentStatus)

        // a second pass finds nothing to do and posts nothing twice
        assertEquals(0, d.runInline())
        assertEquals(500L, w.fixtures.creditBalance(u))
    }

    @Test
    fun `a worker that died after the ledger commit leaves the row to a second run that confirms it with the same transaction (28)`(): Unit = runBlocking {
        val u = steve()
        val placed = d.place(user = u, actions = listOf(credit("a1", 500)))

        d.pay(placed)

        val claimed = d.service.claimDue().single()

        // the dead worker: the ledger row exists (key delivery:<id>) but the delivery stayed SENDING
        w.db.tx { conn -> d.credits.post(Posting(CreditTxType.ACTION, "delivery:${claimed.id}", u.id, 500, AccountRef.System(com.panomc.plugins.market.db.model.CreditSystemKey.ISSUANCE), AccountRef.User(u.id), orderId = placed.order.id, deliveryId = claimed.id), conn) }

        w.clock.advance(61_000)

        assertEquals(1, d.service.recoverStaleClaims())
        assertEquals(DeliveryStatus.PENDING, d.row(claimed.id).status)
        assertEquals(1, d.runInline())

        val row = d.row(claimed.id)
        val txs = w.creditTxs.getByIdempotencyKey("delivery:${claimed.id}", pool)!!

        assertEquals(DeliveryStatus.CONFIRMED, row.status)
        assertEquals(txs.id, JsonObject(row.result).getLong("creditTxId"))
        assertEquals(500L, w.fixtures.creditBalance(u))
        assertEquals(1L, count("market_credit_tx", "`idempotencyKey` = ?", "delivery:${claimed.id}"))
    }

    @Test
    fun `a CREDIT reversal takes what is there and records the shortfall (31)`(): Unit = runBlocking {
        val u = steve()
        val placed = d.place(user = u, actions = listOf(credit("a1", 500)))

        d.pay(placed)
        d.runInline()
        assertEquals(500L, w.fixtures.creditBalance(u))

        // the buyer spent 300 of it
        w.db.tx { conn -> d.credits.revoke(u.id, 300, "test:spend", null, "spent", conn) }
        assertEquals(200L, w.fixtures.creditBalance(u))

        val plan = d.revoke(placed)

        assertEquals(1, plan.inserted.size)

        d.runInline()

        val undo = d.rows(placed.order.id).single { it.phase == DeliveryPhase.REVOKE }
        val tx = w.creditTxs.getByIdempotencyKey("delivery:${undo.id}", pool)!!

        assertEquals(DeliveryStatus.CONFIRMED, undo.status)
        assertEquals(CreditTxType.ACTION_REVERSAL, tx.type)
        assertEquals(200L, tx.amount)
        assertEquals(300L, tx.shortfall)
        assertEquals(300L, JsonObject(undo.result).getLong("shortfall"))
        assertEquals(0L, w.fixtures.creditBalance(u))
        assertEquals(FulfillmentStatus.REVOKED, d.order(placed.order.id).fulfillmentStatus)
    }

    @Test
    fun `a CREDIT for a player without a Pano account fails NO_ACCOUNT, creates no user and an admin retry after registration confirms (R2-6)`(): Unit = runBlocking {
        val placed = d.place(buyer = "Alex", actions = listOf(credit("a1", 500)))

        d.pay(placed)
        d.runInline()

        val failed = d.rows(placed.order.id).single()

        assertEquals(DeliveryStatus.FAILED, failed.status)
        assertEquals(DeliveryError.NO_ACCOUNT, failed.lastErrorCode)
        assertNull(w.users.idOf("Alex"))
        assertEquals(0, d.playerAccounts.created.size)
        assertEquals(1, failed.attempts)
        assertEquals(FulfillmentStatus.FAILED, d.order(placed.order.id).fulfillmentStatus)
        assertEquals(1, w.orderEvents.getByOrderId(placed.order.id, pool).count { it.type == OrderEventType.DELIVERY_FAILED })

        // Alex registers, the admin retries the same row (D18): it runs again under the same key
        val alex = w.fixtures.user("Alex")
        val retried = w.db.tx { conn -> d.service.apply(conn, failed.id, com.panomc.plugins.market.core.delivery.DeliveryEvent.Retry) }

        assertTrue(retried.moved)

        d.runInline()

        val row = d.row(failed.id)

        assertEquals(DeliveryStatus.CONFIRMED, row.status)
        assertEquals(500L, w.fixtures.creditBalance(alex))
        assertEquals(FulfillmentStatus.FULFILLED, d.order(placed.order.id).fulfillmentStatus)
    }

    // ===== PERMISSION ====================================================================================================

    @Test
    fun `a PERMISSION grant writes every node with the server scope and pano false on raw nodes only (32)`(): Unit = runBlocking {
        val u = steve()

        d.roster.granted = listOf(7L)

        val placed = d.place(user = u, actions = listOf(permission("a1", "group.vip", "essentials.fly", servers = listOf(7L))))

        d.pay(placed)
        d.runInline()

        val row = d.rows(placed.order.id).single()
        val nodes = d.permissionStore.of(u.id)

        assertEquals(DeliveryStatus.CONFIRMED, row.status)
        assertEquals(listOf("group.vip", "essentials.fly"), nodes.map { it.node })
        assertEquals(JsonObject().put("server", JsonArray().add(7)), nodes[0].context)
        assertEquals(JsonObject().put("server", JsonArray().add(7)).put("pano", false), nodes[1].context)
        assertEquals(1, d.permissionStore.published.get())

        val result = JsonObject(row.result)

        assertEquals(u.id, result.getLong("userId"))
        assertEquals(0, result.getInteger("verify"))
        assertEquals(nodes.map { it.id }, result.getJsonArray("nodes").map { (it as JsonObject).getLong("id") })
        // the first re-assertion check is due 60 s after the confirmation
        assertEquals(row.confirmedAt!! + 60_000, row.nextAttemptAt)

        // the same grant for another order of the same player adds no second row, and nothing is published again
        val again = d.place(user = u, actions = listOf(permission("a1", "group.vip", "essentials.fly", servers = listOf(7L))))

        d.pay(again)
        d.runInline()

        assertEquals(listOf("group.vip", "essentials.fly"), d.permissionStore.of(u.id).map { it.node })
        assertEquals(1, d.permissionStore.published.get())
    }

    @Test
    fun `a later expiry replaces an earlier one and a permanent node is never shortened (33)`(): Unit = runBlocking {
        val u = steve()
        val soon = w.clock.now() + 86_400_000L
        val daysSixty = 60L * 86_400_000L

        // an earlier expiry exists (an older, shorter purchase): the 30 day timed purchase replaces it
        d.permissionStore.seed(u.id, "group.vip", JsonObject(), soon)
        d.permissionStore.seed(u.id, "group.mvp", JsonObject(), null)

        val timed = d.place(user = u, actions = listOf(permission("a1", "group.vip", "group.mvp")), billing = "TIMED", periodUnit = "DAY", periodCount = 30)

        d.pay(timed)
        d.runInline()

        val vip = d.permissionStore.nodes(u.id, "group.vip")
        val mvp = d.permissionStore.nodes(u.id, "group.mvp")
        val end = w.clock.now() + 30L * 86_400_000L

        assertEquals(1, vip.size)
        assertEquals(end, vip.single().expiresAt)
        assertEquals(1, mvp.size)
        assertNull(mvp.single().expiresAt)

        // a shorter new expiry never lowers an existing later one
        d.permissionStore.seed(u.id, "group.long", JsonObject(), w.clock.now() + daysSixty)

        val other = d.place(user = u, actions = listOf(permission("a1", "group.long")), billing = "TIMED", periodUnit = "DAY", periodCount = 30)

        d.pay(other)
        d.runInline()

        assertEquals(w.clock.now() + daysSixty, d.permissionStore.nodes(u.id, "group.long").single().expiresAt)
        assertEquals(DeliveryStatus.CONFIRMED, d.rows(other.order.id).single().status)
    }

    @Test
    fun `a REMOVE keeps a node another active purchase also grants and still works after a snapshot rewrote the ids (34, 36)`(): Unit = runBlocking {
        val u = steve()
        val a = d.place(user = u, actions = listOf(permission("a1", "group.vip", "kit.diamond")))
        val b = d.place(user = u, actions = listOf(permission("a1", "group.vip")))

        d.pay(a)
        d.pay(b)
        d.runInline()
        assertEquals(setOf("group.vip", "kit.diamond"), d.permissionStore.of(u.id).map { it.node }.toSet())

        // the platform rewrites the tables: every id changes
        d.permissionStore.rewriteIds()

        d.revoke(a)
        d.runInline()

        val undo = d.rows(a.order.id).single { it.phase == DeliveryPhase.REVOKE }

        assertEquals(DeliveryStatus.CONFIRMED, undo.status)
        // kit.diamond is only A's: gone (found by its tuple, the recorded id is stale); group.vip is also B's: kept
        assertEquals(listOf("group.vip"), d.permissionStore.of(u.id).map { it.node })
        assertEquals(listOf("group.vip"), JsonObject(undo.result).getJsonArray("kept").map { (it as JsonObject).getString("node") })
        assertEquals(listOf("kit.diamond"), JsonObject(undo.result).getJsonArray("removed").map { (it as JsonObject).getString("node") })

        // refunding B removes the shared node as well
        d.revoke(b)
        d.runInline()

        assertEquals(emptyList<String>(), d.permissionStore.of(u.id).map { it.node })
        assertEquals(FulfillmentStatus.REVOKED, d.order(b.order.id).fulfillmentStatus)
    }

    @Test
    fun `a rank bought by a player who never registered creates the user, a bad name is INVALID_PLAYER`(): Unit = runBlocking {
        val placed = d.place(buyer = "Newbie", actions = listOf(permission("a1", "group.vip")))

        d.pay(placed)
        d.runInline()

        val id = w.users.idOf("Newbie")

        assertNotNull(id)
        assertEquals(listOf("Newbie"), d.playerAccounts.created)
        assertEquals(listOf("group.vip"), d.permissionStore.of(id!!).map { it.node })
        assertEquals(DeliveryStatus.CONFIRMED, d.rows(placed.order.id).single().status)

        val bad = d.place(buyer = "Steve; op Steve", actions = listOf(permission("a1", "group.vip")))

        d.pay(bad)

        val badRows = d.rows(bad.order.id)

        // the planner already refuses a name that cannot be rendered, or the executor does: either way no user and no node
        d.runInline()

        assertEquals(1, badRows.size)
        assertEquals(DeliveryStatus.FAILED, d.row(badRows.single().id).status)
        assertNull(w.users.idOf("Steve; op Steve"))
    }

    // ===== entitlements ==================================================================================================

    @Test
    fun `an entitlement carries the owner, the tier, the quantity and what was paid, one per line and replay safe`(): Unit = runBlocking {
        val u = steve()
        val placed = d.place(user = u, actions = listOf(credit("a1", 100)), quantity = 2, tier = 9L to 3, lineTotal = 2400)

        d.pay(placed)
        d.pay(placed)

        val e = w.entitlements.getByOrderItemId(placed.items[0].id, pool).single()

        assertEquals("u:${u.id}", e.ownerKey)
        assertEquals(u.id, e.userId)
        assertEquals("Steve", e.playerUsername)
        assertEquals(placed.items[0].productId, e.productId)
        assertEquals(2, e.quantity)
        assertEquals(EntitlementStatus.ACTIVE, e.status)
        assertEquals(9L, e.tierCategoryId)
        assertEquals(3, e.tierRank)
        assertNull(e.expiresAt)
        assertEquals(w.clock.now(), e.startsAt)
        // price per unit as paid, base currency x100: 2400 gross for 2 units
        assertEquals(1200L, e.pricePaid)
        assertEquals(1, d.rows(placed.order.id).size)
    }

    @Test
    fun `a timed purchase runs a period, a repurchase starts at the chain end as RENEW and the permission expires with the chain (53, 54)`(): Unit = runBlocking {
        val u = steve()
        val day = 86_400_000L
        val actions = listOf(permission("a1", "group.vip"), credit("a2", 100))
        val first = d.place(user = u, actions = actions, billing = "TIMED", periodUnit = "DAY", periodCount = 30)
        val product = first.product
        val t0 = w.clock.now()

        d.pay(first)

        val e1 = w.entitlements.getByOrderItemId(first.items[0].id, pool).single()

        assertEquals(t0, e1.startsAt)
        assertEquals(t0 + 30 * day, e1.expiresAt)
        assertEquals(setOf(DeliveryPhase.GRANT), d.rows(first.order.id).map { it.phase }.toSet())

        d.runInline()
        assertEquals(t0 + 30 * day, d.permissionStore.of(u.id).single().expiresAt)

        // 20 days later, 10 days are left: the second purchase starts where the first ends
        w.clock.advance(20 * day)

        val second = d.place(user = u, actions = actions, billing = "TIMED", periodUnit = "DAY", periodCount = 30, product = product)

        d.pay(second)

        val e2 = w.entitlements.getByOrderItemId(second.items[0].id, pool).single()

        assertEquals(t0 + 30 * day, e2.startsAt)
        assertEquals(t0 + 60 * day, e2.expiresAt)
        assertTrue(EntitlementService.isExtension(e2))
        assertFalse(EntitlementService.isExtension(e1))

        val rows = d.rows(second.order.id)

        // no RENEW action of its own: the grant repeats as RENEW, the permission as EXTEND to the chain end
        assertEquals(setOf(DeliveryPhase.RENEW), rows.map { it.phase }.toSet())
        assertEquals("EXTEND", JsonObject(rows.single { it.actionType == DeliveryActionType.PERMISSION }.payload).getString("op"))
        assertEquals(t0 + 60 * day, JsonObject(rows.single { it.actionType == DeliveryActionType.PERMISSION }.payload).getLong("expiresAt"))

        d.runInline()

        assertEquals(t0 + 60 * day, d.permissionStore.of(u.id).single().expiresAt)
        assertEquals(1, d.permissionStore.of(u.id).size)
        assertEquals(2, count("market_credit_tx", "`type` = 'ACTION'"))
    }

    // ===== the end flow ==================================================================================================

    @Test
    fun `a refund before the delivery cancels a delayed grant and plans no undo (D-06, 64)`(): Unit = runBlocking {
        val u = steve()
        val placed = d.place(user = u, actions = listOf(ProductAction(id = "a1", type = DeliveryActionType.CREDIT, credit = 500, delaySeconds = 3600)))

        d.pay(placed)

        val grant = d.rows(placed.order.id).single()

        assertEquals(DeliveryStatus.SCHEDULED, grant.status)
        assertEquals(grant.createdAt + 3_600_000, grant.runAfter)

        val plan = d.revoke(placed)

        assertEquals(1, plan.cancelled)
        assertEquals(0, plan.inserted.size)

        val rows = d.rows(placed.order.id)

        assertEquals(1, rows.size)
        assertEquals(DeliveryStatus.CANCELLED, rows[0].status)
        assertEquals(DeliveryError.ORDER_REVOKED, rows[0].lastErrorCode)
        assertEquals(0, d.service.promote())
        assertEquals(0L, w.fixtures.creditBalance(u))
        assertEquals(FulfillmentStatus.REVOKED, d.order(placed.order.id).fulfillmentStatus)
    }

    @Test
    fun `a grant in flight holds its undo, and once the grant executes the undo runs`(): Unit = runBlocking {
        val u = steve()
        val placed = d.place(user = u, actions = listOf(credit("a1", 500)))

        d.pay(placed)

        val claimed = d.service.claimDue().single()

        assertEquals(DeliveryStatus.SENDING, claimed.status)

        // the refund arrives while the grant is claimed: the grant gets a cancel request, the credit reversal is planned and held by the gate (08 section 11.4)
        val plan = d.revoke(placed)

        assertEquals(1, plan.cancelRequested)
        assertEquals(1, plan.inserted.size)
        assertNotNull(d.row(claimed.id).cancelRequestedAt)
        assertEquals(DeliveryError.ORDER_REVOKED, d.row(claimed.id).lastErrorCode)
        assertEquals(0, d.service.claimDue().size)

        // the worker finishes: the grant did execute (D3 clears the cancel reason), so its undo now runs
        assertEquals(DeliveryStatus.CONFIRMED, d.service.execute(claimed))
        assertNull(d.row(claimed.id).lastErrorCode)
        assertEquals(500L, w.fixtures.creditBalance(u))
        assertEquals(1, d.runInline())
        assertEquals(0L, w.fixtures.creditBalance(u))
        assertEquals(
            listOf(CreditTxType.ACTION, CreditTxType.ACTION_REVERSAL),
            sql("SELECT `type` AS t FROM `${prefix}market_credit_tx` WHERE `deliveryId` IS NOT NULL ORDER BY `id`").map { CreditTxType.valueOf(it.getString("t")) }
        )
        assertEquals(FulfillmentStatus.REVOKED, d.order(placed.order.id).fulfillmentStatus)
    }

    @Test
    fun `a grant that fails after the refund cancels the undo instead of taking credits back (D22)`(): Unit = runBlocking {
        val u = steve()
        val placed = d.place(user = u, actions = listOf(credit("a1", 500)))

        d.pay(placed)

        val claimed = d.service.claimDue().single()

        d.revoke(placed)

        // the grant fails for good while the refund is waiting: nothing was ever granted
        w.db.txRestartingOnOrderChange { conn ->
            d.locks.forOrder(conn, placed.order.id, OrderLockScope.PAYMENT) {
                d.service.apply(conn, claimed.id, com.panomc.plugins.market.core.delivery.DeliveryEvent.InlineFailed(DeliveryError.INVALID_PLAYER, "gone", false), claimed.claimToken)
            }
        }

        assertEquals(DeliveryStatus.FAILED, d.row(claimed.id).status)

        // the gate is open now; the reversal is cancelled instead of claimed
        assertEquals(0, d.service.claimDue().size)

        val undo = d.rows(placed.order.id).single { it.phase == DeliveryPhase.REVOKE }

        assertEquals(DeliveryStatus.CANCELLED, undo.status)
        assertEquals(DeliveryError.NOTHING_TO_REVOKE, undo.lastErrorCode)
        assertEquals(0L, w.fixtures.creditBalance(u))
        assertEquals(0L, count("market_credit_tx", "`type` = 'ACTION_REVERSAL'"))
    }

    @Test
    fun `an explicit REVOKE action of an item nothing was delivered for is cancelled by the sweep, not offered (D22)`(): Unit = runBlocking {
        val u = steve()

        d.roster.granted = listOf(7L)

        // the grant is a server command that was never sent; the explicit REVOKE command must not run for it
        val revokeCommand = ProductAction(id = "c2", type = DeliveryActionType.COMMAND, phase = DeliveryPhase.REVOKE, commands = listOf("take {username} diamond 1"))
        val placed = d.place(user = u, actions = listOf(command("c1"), revokeCommand))

        d.pay(placed)

        val grant = d.rows(placed.order.id).single()

        assertEquals(DeliveryStatus.PENDING, grant.status)
        assertEquals(com.panomc.plugins.market.db.model.DeliveryTransport.MARKET_MC, grant.transport)

        val plan = d.revoke(placed)

        // the unsent grant is cancelled (D16); the explicit undo is planned, held only by in-flight predecessors (there are none)
        assertEquals(1, plan.cancelled)
        assertEquals(1, plan.inserted.size)
        assertEquals(DeliveryStatus.CANCELLED, d.row(grant.id).status)

        val undo = d.rows(placed.order.id).single { it.phase == DeliveryPhase.REVOKE }

        assertEquals(DeliveryStatus.PENDING, undo.status)
        assertEquals(1, d.service.classify())
        assertEquals(DeliveryStatus.CANCELLED, d.row(undo.id).status)
        assertEquals(DeliveryError.NOTHING_TO_REVOKE, d.row(undo.id).lastErrorCode)
        assertEquals(0, d.service.classify())
    }

    // ===== more of the end flow, the permission rules and the entitlement kinds =====================================================

    @Test
    fun `the expiry of a timed product plans only the permission removal, credits are never taken back (D-05 flow)`(): Unit = runBlocking {
        val u = steve()
        val placed = d.place(user = u, actions = listOf(permission("a1", "group.vip"), credit("a2", 100)), billing = "TIMED", periodUnit = "DAY", periodCount = 30)

        d.pay(placed)
        d.runInline()
        assertEquals(1, d.permissionStore.of(u.id).size)
        assertEquals(100L, w.fixtures.creditBalance(u))

        w.clock.advance(31 * 86_400_000L)

        val plan = w.db.txRestartingOnOrderChange { conn ->
            d.locks.forOrder(conn, placed.order.id, OrderLockScope.COMMIT) { locked ->
                val planned = d.service.planEnd(conn, locked.order, locked.items)

                for (e in w.entitlements.getByOrderItemId(locked.items[0].id, conn)) w.entitlements.end(e.id, EntitlementStatus.EXPIRED, "EXPIRED", w.clock.now(), conn)

                d.service.refreshFulfillment(conn, locked.order.id)

                planned
            }
        }
        val expire = d.rows(placed.order.id).filter { it.phase == DeliveryPhase.EXPIRE }

        assertEquals(1, plan.inserted.size)
        assertEquals(listOf(DeliveryActionType.PERMISSION), expire.map { it.actionType })
        assertEquals("REMOVE", JsonObject(expire.single().payload).getString("op"))

        assertEquals(1, d.runInline())
        assertEquals(DeliveryStatus.CONFIRMED, d.row(expire.single().id).status)
        assertEquals(0, d.permissionStore.of(u.id).size)
        assertEquals(100L, w.fixtures.creditBalance(u))
    }

    @Test
    fun `a partial revoke cancels only the per unit rows it covers and leaves a whole-line row alone`(): Unit = runBlocking {
        val u = steve()

        d.roster.granted = listOf(7L)

        val perUnit = ProductAction(id = "c1", type = DeliveryActionType.COMMAND, commands = listOf("give {username} diamond {unit}"), perUnit = true)
        val whole = ProductAction(id = "c2", type = DeliveryActionType.COMMAND, commands = listOf("say {quantity}"))
        val placed = d.place(user = u, actions = listOf(perUnit, whole), quantity = 3)

        d.pay(placed)

        assertEquals(4, d.rows(placed.order.id).size)

        val plan = w.db.txRestartingOnOrderChange { conn ->
            d.locks.forOrder(conn, placed.order.id, OrderLockScope.COMMIT) { locked ->
                d.service.planRevoke(conn, locked.order, locked.items, mapOf(locked.items[0].id to (1..1)))
            }
        }

        assertEquals(1, plan.cancelled)
        assertEquals(0, plan.inserted.size)

        val byAction = d.rows(placed.order.id).groupBy { it.actionId }

        assertEquals(listOf(DeliveryStatus.PENDING, DeliveryStatus.CANCELLED, DeliveryStatus.PENDING), byAction.getValue("c1").sortedBy { it.unitIndex }.map { it.status })
        assertEquals(listOf(DeliveryStatus.PENDING), byAction.getValue("c2").map { it.status })
    }

    @Test
    fun `an action row for a webhook has no executor yet and stays PENDING, visible in the fulfilment`(): Unit = runBlocking {
        val u = steve()
        val webhook = ProductAction(
            id = "w1", type = DeliveryActionType.WEBHOOK,
            webhook = com.panomc.plugins.market.core.delivery.WebhookSpec("https://hooks.example.com/x")
        )
        val placed = d.place(user = u, actions = listOf(webhook, credit("a2", 100)))

        d.pay(placed)

        assertEquals(1, d.runInline())

        val rows = d.rows(placed.order.id).associateBy { it.actionId }

        assertEquals(DeliveryStatus.PENDING, rows.getValue("w1").status)
        assertEquals(0, rows.getValue("w1").attempts)
        assertEquals(DeliveryStatus.CONFIRMED, rows.getValue("a2").status)
        // one confirmed, one open: PARTIAL, never FULFILLED (08 section 13)
        assertEquals(FulfillmentStatus.PARTIAL, d.order(placed.order.id).fulfillmentStatus)
    }

    @Test
    fun `permission rules, ADD never lowers an expiry, EXTEND corrects it unless another grant needs it, a permanent node is never lowered`(): Unit = runBlocking {
        val svc = d.permissionService
        val tuple = PermissionGrantService.Tuple("group.vip", JsonObject())
        val free: suspend (PermissionGrantService.Tuple) -> Boolean = { false }
        val held: suspend (PermissionGrantService.Tuple) -> Boolean = { true }

        d.permissionStore.seed(7, "group.vip", JsonObject(), 100)

        // ADD with an earlier expiry keeps the later one
        assertFalse(svc.grant(7, listOf(tuple), 50, extend = false, heldElsewhere = free, sqlClient = pool).changed)
        assertEquals(100L, d.permissionStore.nodes(7, "group.vip").single().expiresAt)

        // EXTEND that would lower it is refused while another active grant holds the node, applied otherwise (the coverage correction)
        assertFalse(svc.grant(7, listOf(tuple), 50, extend = true, heldElsewhere = held, sqlClient = pool).changed)
        assertEquals(100L, d.permissionStore.nodes(7, "group.vip").single().expiresAt)
        assertTrue(svc.grant(7, listOf(tuple), 50, extend = true, heldElsewhere = free, sqlClient = pool).changed)
        assertEquals(50L, d.permissionStore.nodes(7, "group.vip").single().expiresAt)
        assertEquals(1, d.permissionStore.nodes(7, "group.vip").size)

        // a later expiry and permanent both win over the stored one
        assertTrue(svc.grant(7, listOf(tuple), 80, extend = false, heldElsewhere = free, sqlClient = pool).changed)
        assertEquals(80L, d.permissionStore.nodes(7, "group.vip").single().expiresAt)
        assertTrue(svc.grant(7, listOf(tuple), null, extend = false, heldElsewhere = free, sqlClient = pool).changed)
        assertNull(d.permissionStore.nodes(7, "group.vip").single().expiresAt)

        // a permanent node is never lowered, not even by EXTEND
        assertFalse(svc.grant(7, listOf(tuple), 10, extend = true, heldElsewhere = free, sqlClient = pool).changed)
        assertNull(d.permissionStore.nodes(7, "group.vip").single().expiresAt)

        // contexts are part of the tuple: another server scope is another node; key order and number width do not matter
        val scoped = PermissionGrantService.Tuple("group.vip", JsonObject().put("server", JsonArray().add(3)))

        assertTrue(svc.grant(7, listOf(scoped), null, extend = false, heldElsewhere = free, sqlClient = pool).changed)
        assertFalse(svc.grant(7, listOf(PermissionGrantService.Tuple("group.vip", JsonObject().put("server", JsonArray().add(3L)))), null, extend = false, heldElsewhere = free, sqlClient = pool).changed)
        assertEquals(2, d.permissionStore.nodes(7, "group.vip").size)

        // verify adds back what a snapshot lost, with the recorded expiry, and skips an expiry that has passed
        d.permissionStore.deleteNode(7, "group.vip")

        val verified = svc.verify(
            7,
            listOf(
                PermissionGrantService.Recorded(1, "group.vip", JsonObject(), null),
                PermissionGrantService.Recorded(2, "group.old", JsonObject(), w.clock.now() - 1)
            ),
            pool
        )

        assertEquals(1, verified.readded)
        assertEquals(listOf("group.vip"), d.permissionStore.of(7).map { it.node })
    }

    @Test
    fun `a subscription line owns a subscription entitlement without an expiry, a credit top-up line owns nothing`(): Unit = runBlocking {
        val u = steve()
        val placed = d.place(user = u, actions = listOf(permission("a1", "group.vip")), billing = "SUBSCRIPTION", periodUnit = "MONTH", periodCount = 1, subscriptionId = 5)

        d.pay(placed)

        val e = w.entitlements.getByOrderItemId(placed.items[0].id, pool).single()

        assertEquals(5L, e.subscriptionId)
        assertNull(e.expiresAt)
        assertEquals(w.clock.now(), e.startsAt)

        // a credit top-up line has no product and no entitlement
        val topUp = d.place(user = u, actions = emptyList())
        val line = com.panomc.plugins.market.db.model.MarketOrderItem(
            orderId = topUp.order.id, productName = "credits", kind = OrderItemKind.CREDIT_TOPUP, creditAmount = 500, createdAt = w.clock.now(), updatedAt = w.clock.now()
        )
        val lineId = w.orderItems.add(line, pool)

        w.db.txRestartingOnOrderChange { conn ->
            d.locks.forOrder(conn, topUp.order.id, OrderLockScope.COMMIT) { locked -> d.entitlementService.onPaid(conn, locked.order, locked.items.filter { it.id == lineId }) }
        }

        assertEquals(0, w.entitlements.getByOrderItemId(lineId, pool).size)
    }

    @Test
    fun `the outcome of an action webhook comes back to its delivery row, a dead one fails it and a redelivery that succeeds confirms it (91)`(): Unit = runBlocking {
        val u = steve()
        val hook = ProductAction(id = "w1", type = DeliveryActionType.WEBHOOK, webhook = com.panomc.plugins.market.core.delivery.WebhookSpec("https://hooks.example.com/x"))
        val reporter = DeliveryWebhookReporter(d.service)
        val now = w.clock.now()

        fun decision(status: com.panomc.plugins.market.db.model.WebhookDeliveryStatus) =
            com.panomc.plugins.market.core.webhook.Decision(status, null, null, status == com.panomc.plugins.market.db.model.WebhookDeliveryStatus.SUCCEEDED, now)

        suspend fun report(deliveryId: Long, status: com.panomc.plugins.market.db.model.WebhookDeliveryStatus) =
            w.db.tx { conn -> reporter.report(conn, com.panomc.plugins.market.db.model.MarketWebhookDelivery(deliveryId = deliveryId), decision(status)) }

        suspend fun sent(): Pair<Placed, Long> {
            val placed = d.place(user = u, actions = listOf(hook))

            d.pay(placed)

            val id = d.rows(placed.order.id).single().id

            // what the WEBHOOK executor (MK-106) leaves behind: the outbox row exists and the delivery is SENT
            sql("UPDATE `${prefix}market_delivery` SET `status` = 'SENT', `sentAt` = ?, `attempts` = 1 WHERE `id` = ?", now, id)

            return placed to id
        }

        val (ok, okId) = sent()

        // a retry in the queue is neither: nothing changes
        report(okId, com.panomc.plugins.market.db.model.WebhookDeliveryStatus.FAILED)
        assertEquals(DeliveryStatus.SENT, d.row(okId).status)

        report(okId, com.panomc.plugins.market.db.model.WebhookDeliveryStatus.SUCCEEDED)
        assertEquals(DeliveryStatus.CONFIRMED, d.row(okId).status)
        assertEquals(FulfillmentStatus.FULFILLED, d.order(ok.order.id).fulfillmentStatus)

        // a replayed report changes nothing
        report(okId, com.panomc.plugins.market.db.model.WebhookDeliveryStatus.SUCCEEDED)
        assertEquals(DeliveryStatus.CONFIRMED, d.row(okId).status)

        val (dead, deadId) = sent()

        report(deadId, com.panomc.plugins.market.db.model.WebhookDeliveryStatus.DEAD)

        assertEquals(DeliveryStatus.FAILED, d.row(deadId).status)
        assertEquals(DeliveryError.WEBHOOK_DEAD, d.row(deadId).lastErrorCode)
        assertEquals(FulfillmentStatus.FAILED, d.order(dead.order.id).fulfillmentStatus)
        assertEquals(1, w.orderEvents.getByOrderId(dead.order.id, pool).count { it.type == OrderEventType.DELIVERY_FAILED })

        // the webhook row is redelivered and now succeeds: a positive result always wins
        report(deadId, com.panomc.plugins.market.db.model.WebhookDeliveryStatus.SUCCEEDED)

        assertEquals(DeliveryStatus.CONFIRMED, d.row(deadId).status)
        assertNull(d.row(deadId).lastErrorCode)
        assertEquals(FulfillmentStatus.FULFILLED, d.order(dead.order.id).fulfillmentStatus)
    }
}
