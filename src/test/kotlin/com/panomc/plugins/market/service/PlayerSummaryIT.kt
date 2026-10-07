package com.panomc.plugins.market.service

import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.BlockType
import com.panomc.plugins.market.db.model.EntitlementStatus
import com.panomc.plugins.market.db.model.MarketBlock
import com.panomc.plugins.market.db.model.MarketEntitlement
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketSubscription
import com.panomc.plugins.market.db.model.SubscriptionMode
import com.panomc.plugins.market.db.model.SubscriptionStatus
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.routes.panel.player.PlayerSummaryService
import com.panomc.plugins.market.service.platform.DirectoryUser
import com.panomc.plugins.market.service.platform.UserDirectory
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * `GET /players/:username/summary` on a real MariaDB (MK-171; 04 section 7): the user (`null` for a guest), the credit balance, the totals of what the player
 * bought in the store currency, the latest ten orders (gifts received too, with the role), the active entitlements, the subscriptions and the blocks that name
 * the player; no e-mail, address or IP anywhere in it.
 */
class PlayerSummaryIT : MarketDaoITBase() {
    override suspend fun assertInvariants() {}

    private lateinit var w: TestWiring
    private lateinit var service: PlayerSummaryService

    private var seq = 0

    @BeforeEach
    fun setUp() {
        w = TestWiring(pool)

        val directory = object : UserDirectory {
            override suspend fun byUsername(username: String, sqlClient: SqlClient): DirectoryUser? = w.users.idOf(username)?.let { DirectoryUser(it, w.users.nameOf(it)!!) }

            override suspend fun usernameOf(userId: Long, sqlClient: SqlClient): String? = w.users.nameOf(userId)

            override suspend fun emailOf(userId: Long, sqlClient: SqlClient): String? = null

            override suspend fun hasPermission(userId: Long, node: String): Boolean = false
        }

        service = PlayerSummaryService({ w.orders.prefix() }, directory, w.creditAccounts, { w.config }, w.clock) { key, now, client ->
            w.entitlements.getActiveByOwner(key, now, client).map {
                PlayerSummaryService.EntitlementRow(it.id, it.productId, it.variantId, it.orderId, it.subscriptionId, it.quantity, it.startsAt, it.expiresAt)
            }
        }
    }

    private suspend fun order(
        buyerKey: String, username: String, total: Long = 1000, status: OrderStatus = OrderStatus.COMPLETED, paid: Boolean = true, test: Boolean = false, refunded: Long = 0, fx: String = "1",
        recipientKey: String = buyerKey, gift: Boolean = false, createdAt: Long = w.clock.now() + (++seq) * 1000L, product: String = "VIP", userId: Long? = null
    ): Long {
        val id = w.orders.add(
            MarketOrder(
                userId = userId, playerUsername = username, totalPrice = total, gatewayAmount = total, status = status, buyerKey = buyerKey, recipientKey = recipientKey, isGift = gift,
                publicId = "PLAYER" + seq.toString().padStart(14, '0'), refundedTotal = refunded, fxRate = BigDecimal(fx), testMode = test, paidAt = if (paid) createdAt else null,
                email = "$username@example.com", clientIp = "203.0.113.5", createdAt = createdAt, updatedAt = createdAt
            ),
            pool
        )

        w.orderItems.add(MarketOrderItem(orderId = id, productName = product, quantity = 1, lineTotal = total, createdAt = createdAt, updatedAt = createdAt), pool)

        return id
    }

    private suspend fun summary(name: String): JsonObject = service.summary(name, pool)

    @Test
    fun `a registered player has an account, a credit balance and totals over what they bought`(): Unit = runBlocking {
        val steve = w.fixtures.user("Steve")

        w.fixtures.credit(steve, 2550)

        order("u:${steve.id}", "Steve", total = 3000, userId = steve.id, refunded = 500)
        order("u:${steve.id}", "Steve", total = 2000, userId = steve.id)
        order("u:${steve.id}", "Steve", total = 9000, userId = steve.id, test = true)                 // test: no total
        order("u:${steve.id}", "Steve", total = 700, userId = steve.id, status = OrderStatus.PENDING, paid = false) // unpaid: no total
        order("u:${steve.id}", "Steve", total = 6000, userId = steve.id, fx = "3")                    // 20.00 in the base currency
        order("u:${steve.id + 100}", "Other", total = 99999)

        val body = summary("steve")

        assertEquals(setOf("user", "creditBalance", "totals", "orders", "entitlements", "subscriptions", "blocks"), body.fieldNames())
        assertEquals(JsonObject().put("id", steve.id).put("username", "Steve"), body.getJsonObject("user"))
        assertEquals(25.5, body.getDouble("creditBalance"), 0.0001)

        val totals = body.getJsonObject("totals")

        assertEquals(3L, totals.getLong("orders"))
        assertEquals(30.0 + 20.0 + 20.0, totals.getDouble("spent"), 0.0001)
        assertEquals(5.0, totals.getDouble("refunded"), 0.0001)
        assertEquals(w.config.currency, totals.getString("currency"))
    }

    @Test
    fun `a guest has no user and no balance, the guest orders are found by the name`(): Unit = runBlocking {
        order("g:alex", "Alex", total = 1500)
        order("g:alex", "Alex", total = 500, status = OrderStatus.PENDING, paid = false)

        val body = summary("ALEX")

        assertNull(body.getValue("user"))
        assertEquals(0.0, body.getDouble("creditBalance"), 0.0)
        assertEquals(1L, body.getJsonObject("totals").getLong("orders"))
        assertEquals(15.0, body.getJsonObject("totals").getDouble("spent"), 0.0001)
        assertEquals(2, body.getJsonArray("orders").size(), "the unpaid order is listed, it is just no total")
    }

    @Test
    fun `a registered player owns the earlier guest purchases under the same name`(): Unit = runBlocking {
        val mary = w.fixtures.user("Mary")

        order("g:mary", "Mary", total = 1000)
        order("u:${mary.id}", "Mary", total = 2000, userId = mary.id)

        assertEquals(2L, summary("Mary").getJsonObject("totals").getLong("orders"))
    }

    @Test
    fun `the latest ten orders newest first with the role, product names and no personal data, gifts received included`(): Unit = runBlocking {
        val steve = w.fixtures.user("Steve")

        repeat(11) { order("u:${steve.id}", "Steve", userId = steve.id, product = "Item $it") }

        // a gift from somebody else: bought by Alex, received by Steve
        val gift = order("g:alex", "Alex", recipientKey = "u:${steve.id}", gift = true, product = "Gift item")

        val orders = summary("Steve").getJsonArray("orders").map { it as JsonObject }

        assertEquals(10, orders.size)
        assertEquals(gift, w.orders.getByPublicId(orders[0].getString("publicId"), pool)!!.id, "newest first")
        assertEquals("RECIPIENT", orders[0].getString("role"))
        assertEquals(true, orders[0].getBoolean("isGift"))
        assertEquals(listOf("Gift item"), orders[0].getJsonArray("productNames").map { it.toString() })
        assertEquals("BUYER", orders[1].getString("role"))
        assertEquals("Item 10", orders[1].getJsonArray("productNames").getString(0))
        assertEquals(setOf("id", "publicId", "status", "source", "role", "total", "currency", "refundedTotal", "isGift", "testMode", "productNames", "createdAt", "paidAt"), orders[0].fieldNames())

        val text = summary("Steve").encode()

        for (secret in listOf("example.com", "203.0.113", "accessToken", "clientIp", "email")) assertFalse(text.contains(secret), "must not contain $secret")
    }

    @Test
    fun `active entitlements with product names, subscriptions without pending ones, blocks of the name and of the account`(): Unit = runBlocking {
        val steve = w.fixtures.user("Steve")
        val vip = w.fixtures.product(slug = "vip", name = "VIP")
        val now = w.clock.now()
        val orderId = order("u:${steve.id}", "Steve", userId = steve.id)

        fun entitlement(owner: String, status: EntitlementStatus, expiresAt: Long?) = MarketEntitlement(
            userId = steve.id, playerUsername = "Steve", ownerKey = owner, productId = vip.id, orderId = orderId, orderItemId = 1, status = status, startsAt = now - 1000, expiresAt = expiresAt,
            createdAt = now, updatedAt = now
        )

        w.entitlements.add(entitlement("u:${steve.id}", EntitlementStatus.ACTIVE, null), pool)
        w.entitlements.add(entitlement("g:steve", EntitlementStatus.ACTIVE, now + 5000), pool)
        w.entitlements.add(entitlement("u:${steve.id}", EntitlementStatus.EXPIRED, now - 10), pool)
        w.entitlements.add(entitlement("u:${steve.id}", EntitlementStatus.ACTIVE, now - 10), pool)
        w.entitlements.add(entitlement("u:999", EntitlementStatus.ACTIVE, null), pool)

        fun subscription(status: SubscriptionStatus) = MarketSubscription(
            userId = steve.id, playerUsername = "Steve", ownerKey = "u:${steve.id}", email = "steve@example.com", productId = vip.id, variantId = 1, productName = "VIP", initialOrderId = orderId,
            providerId = "fake", mode = SubscriptionMode.MERCHANT, status = status, price = 999, currency = "EUR", gatewaySubscriptionId = "sub_secret_${seq + 1}", createdAt = now + (++seq), updatedAt = now
        )

        w.subscriptions.add(subscription(SubscriptionStatus.ACTIVE), pool)
        w.subscriptions.add(subscription(SubscriptionStatus.PENDING), pool)
        w.subscriptions.add(subscription(SubscriptionStatus.CANCELLED), pool)

        w.blocks.add(MarketBlock(type = BlockType.PLAYER, value = "steve", reason = "abuse", createdAt = now, updatedAt = now), pool)
        w.blocks.add(MarketBlock(type = BlockType.USER, value = steve.id.toString(), createdAt = now, updatedAt = now), pool)
        w.blocks.add(MarketBlock(type = BlockType.PLAYER, value = "old", createdAt = now, updatedAt = now), pool)
        w.blocks.add(MarketBlock(type = BlockType.PLAYER, value = "stale", expiresAt = now - 1, createdAt = now, updatedAt = now), pool)
        w.blocks.add(MarketBlock(type = BlockType.EMAIL, value = "steve@example.com", createdAt = now, updatedAt = now), pool)

        val body = summary("Steve")
        val entitlements = body.getJsonArray("entitlements").map { it as JsonObject }

        assertEquals(2, entitlements.size, "the active one of the account and the active one of the guest key")
        assertEquals(setOf("VIP"), entitlements.map { it.getString("productName") }.toSet())
        assertEquals(setOf("id", "productId", "productName", "variantId", "orderId", "subscriptionId", "quantity", "startsAt", "expiresAt"), entitlements[0].fieldNames())

        val subscriptions = body.getJsonArray("subscriptions").map { it as JsonObject }

        assertEquals(listOf("CANCELLED", "ACTIVE").sorted(), subscriptions.map { it.getString("status") }.sorted(), "no PENDING row")
        assertFalse(body.encode().contains("sub_secret_"), "the gateway's id is not part of it")
        assertFalse(subscriptions[0].containsKey("email"))

        val blocks = body.getJsonArray("blocks").map { it as JsonObject }

        assertEquals(setOf("PLAYER", "USER"), blocks.map { it.getString("type") }.toSet(), "an e-mail block, another player's and an expired one do not show")
        assertEquals(2, blocks.size)
        assertEquals("abuse", blocks.first { it.getString("type") == "PLAYER" }.getString("reason"))
    }

    @Test
    fun `a name outside the admin username rule is refused, an empty store answers empty lists`(): Unit = runBlocking {
        for (bad in listOf("", "a b", "x".repeat(33), "<script>", "ü", "***", "a'--")) assertThrows(RequestValueException::class.java, { runBlocking { summary(bad) } }, bad)

        val body = summary("Nobody")

        assertNull(body.getValue("user"))
        assertEquals(0L, body.getJsonObject("totals").getLong("orders"))
        assertTrue(body.getJsonArray("orders").isEmpty && body.getJsonArray("entitlements").isEmpty && body.getJsonArray("subscriptions").isEmpty && body.getJsonArray("blocks").isEmpty)
    }
}
