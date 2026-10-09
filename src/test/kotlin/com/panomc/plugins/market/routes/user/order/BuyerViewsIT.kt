package com.panomc.plugins.market.routes.user.order

import com.panomc.platform.model.PageRequest
import com.panomc.platform.error.PageNotFound
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.EntitlementStatus
import com.panomc.plugins.market.db.model.MarketEntitlement
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The buyer's order and entitlement lists on a real MariaDB (MK-172, 04 section 4): a gift received is listed for its recipient and only once it is paid, another
 * account's rows never appear, the status filter, paging, and which entitlements `active=true` keeps.
 */
class BuyerViewsIT : MarketDaoITBase() {
    /** The rows are written straight through the DAOs without the services that keep the cross-table invariants, on purpose. */
    override suspend fun assertInvariants() {}

    private lateinit var w: TestWiring
    private lateinit var views: BuyerOrderViews
    private lateinit var alice: TestUser
    private lateinit var bob: TestUser
    private lateinit var carol: TestUser
    private var seq = 0
    private val first = PageRequest(1, 50)

    @BeforeEach
    fun fresh() {
        runBlocking { resetState() }

        w = TestWiring(pool)
        views = BuyerOrderViews { prefix }
        alice = runBlocking { w.fixtures.user("Alice") }
        bob = runBlocking { w.fixtures.user("Bob") }
        carol = runBlocking { w.fixtures.user("Carol") }
    }

    private suspend fun order(
        buyer: TestUser, status: OrderStatus = OrderStatus.COMPLETED, recipient: TestUser? = null, at: Long = 1_000L + ++seq, items: List<String> = emptyList()
    ): Long {
        val id = w.orders.add(
            MarketOrder(
                userId = buyer.id, playerUsername = buyer.username, totalPrice = 1999, currency = "EUR", status = status, createdAt = at, updatedAt = at,
                publicId = "PUB%017d".format(seq), buyerKey = "u:${buyer.id}", isGift = recipient != null, recipientUsername = (recipient ?: buyer).username,
                recipientUserId = (recipient ?: buyer).id, recipientKey = "u:${(recipient ?: buyer).id}", paidAt = if (status == OrderStatus.PENDING) null else at
            ),
            pool
        )

        for (name in items) w.orderItems.add(MarketOrderItem(orderId = id, productName = name, kind = OrderItemKind.PRODUCT, createdAt = at, updatedAt = at), pool)

        return id
    }

    private suspend fun list(user: TestUser, statuses: Set<OrderStatus> = emptySet(), window: PageRequest = first): JsonObject = views.orders(user.id, window, statuses, pool)

    private fun JsonObject.publicIds() = getJsonArray("items").map { (it as JsonObject).getString("publicId") }

    @Test
    fun `a gift received is listed for the recipient with received true and for the buyer without`() = runBlocking {
        val gift = order(alice, recipient = bob, items = listOf("VIP rank"))

        val forBob = list(bob)
        val row = forBob.getJsonArray("items").getJsonObject(0)

        assertEquals(1, forBob.getJsonObject("page").getInteger("totalItems"))
        assertEquals(gift, row.getLong("number"))
        assertTrue(row.getBoolean("received"))
        assertTrue(row.getBoolean("isGift"))
        assertEquals("Bob", row.getString("recipientUsername"))
        assertEquals(listOf("VIP rank"), row.getJsonArray("itemNames").list)
        assertEquals(19.99, row.getDouble("total"), 0.0001)
        assertEquals("EUR", row.getString("currency"))
        assertEquals("COMPLETED", row.getString("status"))

        val forAlice = list(alice).getJsonArray("items").getJsonObject(0)

        assertFalse(forAlice.getBoolean("received"))
        assertTrue(forAlice.getBoolean("isGift"))
    }

    @Test
    fun `a gift that was never paid is not shown to its recipient`() = runBlocking {
        for (status in listOf(OrderStatus.PENDING, OrderStatus.FAILED, OrderStatus.CANCELLED, OrderStatus.EXPIRED)) order(alice, status, recipient = bob)

        assertEquals(0, list(bob).getJsonObject("page").getInteger("totalItems"))
        assertEquals(4, list(alice).getJsonObject("page").getInteger("totalItems"))

        for (status in listOf(OrderStatus.REVIEW, OrderStatus.COMPLETED, OrderStatus.PARTIALLY_REFUNDED, OrderStatus.REFUNDED, OrderStatus.CHARGEBACK)) order(alice, status, recipient = bob)

        assertEquals(5, list(bob).getJsonObject("page").getInteger("totalItems"))
    }

    @Test
    fun `another account's orders never appear`() = runBlocking {
        val mine = order(alice, items = listOf("A"))
        order(bob, items = listOf("B"))
        order(carol, recipient = bob)

        val forAlice = list(alice)

        assertEquals(1, forAlice.getJsonObject("page").getInteger("totalItems"))
        assertEquals(mine, forAlice.getJsonArray("items").getJsonObject(0).getLong("number"))
        assertEquals(0, list(TestUser(9999, "nobody", 0)).getJsonObject("page").getInteger("totalItems"))
    }

    @Test
    fun `a self gift is listed once and is not received`() = runBlocking {
        order(alice, recipient = alice)

        val body = list(alice)

        assertEquals(1, body.getJsonObject("page").getInteger("totalItems"))
        assertFalse(body.getJsonArray("items").getJsonObject(0).getBoolean("received"))
    }

    @Test
    fun `status filter, newest first and paging`() = runBlocking {
        val a = order(alice, OrderStatus.COMPLETED, at = 1_000)
        val b = order(alice, OrderStatus.PENDING, at = 2_000)
        val c = order(alice, OrderStatus.REFUNDED, at = 3_000)

        assertEquals(listOf(c, b, a), list(alice).getJsonArray("items").map { (it as JsonObject).getLong("number") })
        assertEquals(setOf(a, c), list(alice, setOf(OrderStatus.COMPLETED, OrderStatus.REFUNDED)).getJsonArray("items").map { (it as JsonObject).getLong("number") }.toSet())

        val page2 = list(alice, window = PageRequest(2, 2))

        assertEquals(3, page2.getJsonObject("page").getInteger("totalItems"))
        assertEquals(2, page2.getJsonObject("page").getInteger("totalPages"))
        assertEquals(listOf(a), page2.getJsonArray("items").map { (it as JsonObject).getLong("number") })

        assertThrows(PageNotFound::class.java) { runBlocking { list(alice, window = PageRequest(3, 2)) } }
    }

    @Test
    fun `an empty list is page one and an unknown status is a request error`() = runBlocking {
        val empty = list(alice)

        assertEquals(0, empty.getJsonObject("page").getInteger("totalItems"))
        assertEquals(0, empty.getJsonObject("page").getInteger("totalPages"))
        assertEquals(emptySet<OrderStatus>(), views.parseStatuses(null))
        assertEquals(setOf(OrderStatus.REVIEW, OrderStatus.COMPLETED), views.parseStatuses(" review, COMPLETED ,"))
        assertThrows(RequestValueException::class.java) { views.parseStatuses("COMPLETED,SHIPPED") }
    }

    private suspend fun entitlement(owner: TestUser, status: EntitlementStatus = EntitlementStatus.ACTIVE, expiresAt: Long? = null, order: Long, itemName: String = "VIP rank"): Long {
        val item = w.orderItems.add(MarketOrderItem(orderId = order, productName = itemName, variantName = "30 days", createdAt = 1, updatedAt = 1), pool)

        return w.entitlements.add(
            MarketEntitlement(
                userId = owner.id, playerUsername = owner.username, ownerKey = "u:${owner.id}", productId = 5, orderId = order, orderItemId = item, status = status,
                startsAt = 100, expiresAt = expiresAt, createdAt = 100L + ++seq, updatedAt = 100
            ),
            pool
        )
    }

    @Test
    fun `entitlements belong to their owner, a gift is listed for the recipient`() = runBlocking {
        val gift = order(alice, recipient = bob)
        val own = order(alice)
        val forBob = entitlement(bob, order = gift)
        val forAlice = entitlement(alice, order = own, itemName = "Kit")

        val bobs = views.entitlements(bob.id, false, 10_000, pool).getJsonArray("items")
        val alices = views.entitlements(alice.id, false, 10_000, pool).getJsonArray("items")

        assertEquals(listOf(forBob), bobs.map { (it as JsonObject).getLong("id") })
        assertEquals(listOf(forAlice), alices.map { (it as JsonObject).getLong("id") })

        val row = bobs.getJsonObject(0)

        assertEquals("VIP rank", row.getString("productName"))
        assertEquals("30 days", row.getString("variantName"))
        assertEquals("ACTIVE", row.getString("status"))
        assertEquals(5L, row.getLong("productId"))
        assertEquals(100L, row.getLong("startsAt"))
        assertEquals(null, row.getValue("expiresAt"))
        assertEquals(null, row.getValue("subscriptionId"))
        assertTrue(row.getString("orderPublicId").startsWith("PUB"))
        assertEquals(0, views.entitlements(carol.id, false, 10_000, pool).getJsonArray("items").size())
    }

    @Test
    fun `active keeps only active entitlements that have not ended`() = runBlocking {
        val o = order(alice)
        val permanent = entitlement(alice, order = o)
        val future = entitlement(alice, expiresAt = 20_000, order = o)
        entitlement(alice, expiresAt = 9_000, order = o)
        entitlement(alice, EntitlementStatus.EXPIRED, 5_000, o)
        entitlement(alice, EntitlementStatus.REVOKED, null, o)
        entitlement(alice, EntitlementStatus.UPGRADED, null, o)

        assertEquals(6, views.entitlements(alice.id, false, 10_000, pool).getJsonArray("items").size())

        val active = views.entitlements(alice.id, true, 10_000, pool).getJsonArray("items").map { (it as JsonObject).getLong("id") }

        assertEquals(setOf(permanent, future), active.toSet())
    }
}
