package com.panomc.plugins.market.service

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.config.TopSupportersPeriod
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.BlockType
import com.panomc.plugins.market.db.model.GoalMetric
import com.panomc.plugins.market.db.model.GoalPeriod
import com.panomc.plugins.market.db.model.MarketBlock
import com.panomc.plugins.market.db.model.MarketGoal
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderEvent
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.MarketStatus
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
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
 * `GET /api/market/widgets` on a real MariaDB (MK-171; 04 section 3, 11 section 5, 17 V-16): each module only when its flag is on, `sidebars` echoes
 * `moduleSidebars`, usernames only, test / anonymised / blocked / hidden orders left out, amounts only when asked for, the 30 s cache.
 */
class WidgetServiceIT : MarketDaoITBase() {
    override suspend fun assertInvariants() {}

    private lateinit var w: TestWiring

    private lateinit var service: WidgetService

    private val utc = ZoneId.of("UTC")

    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, utc).toInstant().toEpochMilli()

    private var seq = 0

    private fun config(
        buyers: Boolean = true, buyersCount: Int = 10, amount: Boolean = false, supporters: Boolean = true, period: TopSupportersPeriod = TopSupportersPeriod.ALL_TIME,
        supportersCount: Int = 5, goal: Boolean = true, stats: Boolean = false, sidebars: List<String> = listOf("home")
    ) = MarketConfig(
        currency = "EUR", vatPercent = 20.0, showVatInPrice = true, creditValue = 1.0, storeTimeZone = "UTC", moduleRecentBuyers = buyers,
        moduleRecentBuyersCount = buyersCount, moduleRecentBuyersShowAmount = amount, moduleTopSupporters = supporters, moduleTopSupportersPeriod = period,
        moduleTopSupportersCount = supportersCount, moduleGoal = goal, moduleStats = stats, moduleSidebars = sidebars
    )

    @BeforeEach
    fun setUp() {
        w = TestWiring(pool)
        // a service per test: its cache would otherwise outlive the rows of the test before
        service = WidgetService({ w.config }, w.clock, w.goals, { w.orders.prefix() })
        // Wednesday 7 October 2026, 12:00 UTC
        w.clock.set(at(2026, 10, 7))
        w.configure { config() }
    }

    /** A paid order of [username] with one product line per entry of [products]. */
    private suspend fun order(
        username: String,
        paidAt: Long = at(2026, 10, 7, 9),
        total: Long = 1000,
        products: List<String> = listOf("VIP"),
        status: OrderStatus = OrderStatus.COMPLETED,
        test: Boolean = false,
        hide: Boolean = false,
        userId: Long? = null,
        refunded: Long = 0,
        fx: String = "1",
        currency: String = "EUR",
        buyerKey: String = userId?.let { "u:$it" } ?: "g:${username.lowercase()}",
        kind: OrderItemKind = OrderItemKind.PRODUCT
    ): Long {
        val n = ++seq
        val id = w.orders.add(
            MarketOrder(
                userId = userId, playerUsername = username, totalPrice = total, currency = currency, status = status, publicId = "WID" + n.toString().padStart(17, '0'), buyerKey = buyerKey,
                email = "$username@example.com", clientIp = "203.0.113.9", gatewayAmount = total, refundedTotal = refunded, fxRate = BigDecimal(fx), paidAt = paidAt, testMode = test,
                hideFromBroadcast = hide, createdAt = paidAt, updatedAt = paidAt
            ),
            pool
        )

        for (name in products) w.orderItems.add(MarketOrderItem(orderId = id, productName = name, quantity = 1, lineTotal = total, kind = kind, createdAt = paidAt, updatedAt = paidAt), pool)

        return id
    }

    private suspend fun widgets(include: Set<String> = WidgetService.SECTIONS): JsonObject = service.widgets(include, pool)

    private fun JsonObject.names(key: String) = getJsonArray(key).map { (it as JsonObject).getString("username") }

    // ================================================================================================== flags and sidebars

    @Test
    fun `a module whose flag is off is absent, stats is off by default, and sidebars echoes moduleSidebars`(): Unit = runBlocking {
        order("Steve")

        val defaults = widgets()

        assertTrue(defaults.containsKey("recentBuyers"))
        assertTrue(defaults.containsKey("topSupporters"))
        assertTrue(defaults.containsKey("goals"))
        assertFalse(defaults.containsKey("stats"), "moduleStats is false by default")
        assertEquals(listOf("home"), defaults.getJsonArray("sidebars").map { it.toString() })

        w.configure { config(buyers = false, supporters = false, goal = false, stats = true, sidebars = listOf("home", "profile")) }
        val other = widgets()

        assertFalse(other.containsKey("recentBuyers"))
        assertFalse(other.containsKey("topSupporters"))
        assertFalse(other.containsKey("goals"))
        assertTrue(other.containsKey("stats"))
        assertEquals(listOf("home", "profile"), other.getJsonArray("sidebars").map { it.toString() })

        w.configure { config(sidebars = listOf("profile", "support", "profile")) }
        assertEquals(listOf("profile"), widgets().getJsonArray("sidebars").map { it.toString() }, "a sidebar the host cannot address is dropped, duplicates once")

        w.configure { config(sidebars = emptyList()) }
        assertEquals(emptyList<String>(), widgets().getJsonArray("sidebars").map { it.toString() })
    }

    @Test
    fun `include limits the answer to the named sections`(): Unit = runBlocking {
        order("Steve")
        w.configure { config(stats = true) }

        val only = widgets(setOf("goals", "stats"))

        assertEquals(setOf("goals", "stats", "sidebars"), only.fieldNames())
    }

    // ================================================================================================== recent buyers

    @Test
    fun `recent buyers are the newest paid orders with usernames, product names and a time, no amount unless asked`(): Unit = runBlocking {
        order("Alex", paidAt = at(2026, 10, 5), products = listOf("Kit"))
        order("Steve", paidAt = at(2026, 10, 7, 9), products = listOf("VIP", "Kit", "VIP"), total = 2500)
        order("Mary", paidAt = at(2026, 10, 6))

        val buyers = widgets().getJsonArray("recentBuyers")

        assertEquals(listOf("Steve", "Mary", "Alex"), buyers.map { (it as JsonObject).getString("username") })

        val steve = buyers.getJsonObject(0)

        assertEquals(listOf("VIP", "Kit"), steve.getJsonArray("productNames").map { it.toString() }, "distinct, in line order")
        assertEquals(at(2026, 10, 7, 9), steve.getLong("createdAt"))
        assertFalse(steve.containsKey("amount"))
        assertFalse(steve.containsKey("currency"))
        assertEquals(setOf("username", "productNames", "createdAt"), steve.fieldNames())

        w.configure { config(amount = true) }
        val withAmount = widgets().getJsonArray("recentBuyers").getJsonObject(0)

        assertEquals(25.0, withAmount.getDouble("amount"), 0.0001)
        assertEquals("EUR", withAmount.getString("currency"))
    }

    @Test
    fun `recent buyers leave out test, hidden, unpaid, refunded, anonymised, blocked and product-less orders`(): Unit = runBlocking {
        order("Good", paidAt = at(2026, 10, 7, 1))
        order("Tester", test = true)
        order("Hider", hide = true)
        order("Pending", status = OrderStatus.PENDING)
        order("Refunded", status = OrderStatus.REFUNDED)
        order("Charged", status = OrderStatus.CHARGEBACK)
        order("TopUp", kind = OrderItemKind.CREDIT_TOPUP)
        val erased = order("Erased")
        w.orderEvents.add(MarketOrderEvent(orderId = erased, type = OrderEventType.PII_ERASED, createdAt = 1, updatedAt = 1), pool)

        order("Banned")
        order("AccountBlocked", userId = 77)
        order("TempBlocked")
        order("OldBlock")
        w.blocks.add(MarketBlock(type = BlockType.PLAYER, value = "banned", createdAt = 1, updatedAt = 1), pool)
        w.blocks.add(MarketBlock(type = BlockType.USER, value = "77", createdAt = 1, updatedAt = 1), pool)
        w.blocks.add(MarketBlock(type = BlockType.PLAYER, value = "tempblocked", expiresAt = w.clock.now() + 1000, createdAt = 1, updatedAt = 1), pool)
        w.blocks.add(MarketBlock(type = BlockType.PLAYER, value = "oldblock", expiresAt = w.clock.now() - 1000, createdAt = 1, updatedAt = 1), pool)
        // a block of another kind never hides a player
        w.blocks.add(MarketBlock(type = BlockType.EMAIL, value = "good@example.com", createdAt = 1, updatedAt = 1), pool)

        assertEquals(listOf("OldBlock", "Good"), widgets().names("recentBuyers"), "an expired block hides nobody; newest first")
    }

    @Test
    fun `recent buyers honour the count, at least one and at most fifty`(): Unit = runBlocking {
        repeat(60) { order("P$it", paidAt = at(2026, 10, 1) + it * 1000L) }

        w.configure { config(buyersCount = 3) }
        assertEquals(listOf("P59", "P58", "P57"), widgets().names("recentBuyers"))

        w.configure { config(buyersCount = 0) }
        assertEquals(1, widgets().getJsonArray("recentBuyers").size())

        w.configure { config(buyersCount = 500) }
        assertEquals(50, widgets().getJsonArray("recentBuyers").size())
    }

    @Test
    fun `no e-mail, address, IP or token reaches the widget response`(): Unit = runBlocking {
        order("Steve", total = 4200)
        w.configure { config(amount = true, stats = true) }

        val text = widgets().encode()

        for (secret in listOf("example.com", "203.0.113", "@", "accessToken", "buyerKey", "publicId", "WID0")) assertFalse(text.contains(secret), "the response must not contain $secret: $text")
    }

    // ================================================================================================== top supporters

    @Test
    fun `top supporters rank buyers by what they spent in the store currency less refunds, grouped by buyer`(): Unit = runBlocking {
        order("Steve", total = 3000, userId = 1)
        order("Steve", total = 2000, userId = 1, paidAt = at(2026, 10, 6))   // same account: 50.00 in all
        order("Mary", total = 4000, userId = 2, refunded = 1500)             // 25.00 net
        order("Guest", total = 9000, fx = "3", currency = "TRY")             // 9000 TRY at 3 TRY per EUR = 30.00
        order("Alex", total = 100, userId = 3)                               // 1.00
        order("Zero", total = 800, userId = 4, refunded = 800)               // nothing left: not listed

        val list = widgets().getJsonArray("topSupporters")

        assertEquals(listOf("Steve", "Guest", "Mary", "Alex"), list.map { (it as JsonObject).getString("username") })
        assertEquals(listOf(1, 2, 3, 4), list.map { (it as JsonObject).getInteger("rank") })
        assertFalse(list.getJsonObject(0).containsKey("total"), "no amount unless asked for")
        assertEquals(setOf("username", "rank"), list.getJsonObject(0).fieldNames())

        w.configure { config(amount = true) }
        val totals = widgets().getJsonArray("topSupporters").map { (it as JsonObject).getDouble("total") }

        assertEquals(listOf(50.0, 30.0, 25.0, 1.0), totals)
    }

    @Test
    fun `top supporters are ordered by total, the count caps the list and the month period starts on the first in the store zone`(): Unit = runBlocking {
        order("Old", total = 90000, paidAt = at(2026, 9, 30, 23), userId = 1)
        order("Big", total = 5000, paidAt = at(2026, 10, 1, 0), userId = 2)
        order("Small", total = 1000, paidAt = at(2026, 10, 3), userId = 3)

        w.configure { config(period = TopSupportersPeriod.MONTH) }
        assertEquals(listOf("Big", "Small"), widgets().names("topSupporters"))

        w.configure { config(period = TopSupportersPeriod.ALL_TIME) }
        assertEquals(listOf("Old", "Big", "Small"), widgets().names("topSupporters"))

        w.configure { config(period = TopSupportersPeriod.ALL_TIME, supportersCount = 2) }
        assertEquals(listOf("Old", "Big"), widgets().names("topSupporters"))
    }

    @Test
    fun `top supporters skip test, anonymised and blocked orders and keep the newest spelling of the name`(): Unit = runBlocking {
        order("Tester", total = 99999, test = true, userId = 1)
        val erased = order("Erased", total = 99999, userId = 2)
        w.orderEvents.add(MarketOrderEvent(orderId = erased, type = OrderEventType.PII_ERASED, createdAt = 1, updatedAt = 1), pool)
        order("Blocked", total = 99999, userId = 3)
        w.blocks.add(MarketBlock(type = BlockType.USER, value = "3", createdAt = 1, updatedAt = 1), pool)

        order("steve", total = 1000, userId = 9, paidAt = at(2026, 10, 1))
        order("Steve", total = 1000, userId = 9, paidAt = at(2026, 10, 6))

        assertEquals(listOf("Steve"), widgets().names("topSupporters"))
    }

    // ================================================================================================== goals

    private suspend fun goal(
        name: String, metric: GoalMetric = GoalMetric.ORDERS, target: Long = 10, progress: Long = 0, status: String = "ACTIVE", show: Boolean = true, startsAt: Long? = null, endsAt: Long? = null,
        period: GoalPeriod = GoalPeriod.ONE_TIME, periodStart: Long? = null, position: Int = 0
    ): Long {
        val id = w.goals.add(
            MarketGoal(
                name = name, description = "$name desc", metric = metric, target = target, currency = if (metric == GoalMetric.REVENUE) "EUR" else null, status = status, showOnStore = show,
                startsAt = startsAt, endsAt = endsAt, period = period, periodStart = periodStart, position = position, createdAt = 1, updatedAt = 1
            ),
            pool
        )

        if (progress > 0) w.goals.addProgress(id, progress, 1, pool)

        return id
    }

    private fun JsonObject.goalNames() = getJsonArray("goals").map { (it as JsonObject).getString("name") }

    @Test
    fun `goals lists the active shown goals inside their window with money as decimals and a capped percent`(): Unit = runBlocking {
        val now = w.clock.now()

        goal("Server fund", GoalMetric.REVENUE, target = 100000, progress = 25050, position = 0, endsAt = now + 1000)
        goal("Hundred orders", GoalMetric.ORDERS, target = 100, progress = 150, position = 1)
        goal("Inactive", status = "INACTIVE")
        goal("Hidden", show = false)
        goal("Not yet", startsAt = now + 1000)
        goal("Over", endsAt = now)

        val goals = widgets().getJsonArray("goals")

        assertEquals(listOf("Server fund", "Hundred orders"), widgets().goalNames())

        val fund = goals.getJsonObject(0)

        assertEquals(1000.0, fund.getDouble("target"), 0.0001)
        assertEquals(250.5, fund.getDouble("progress"), 0.0001)
        assertEquals(25, fund.getInteger("percent"))
        assertEquals("REVENUE", fund.getString("metric"))
        assertEquals("EUR", fund.getString("currency"))
        assertEquals(now + 1000, fund.getLong("endsAt"))
        assertEquals("Server fund desc", fund.getString("description"))

        val orders = goals.getJsonObject(1)

        assertEquals(100, orders.getInteger("target"))
        assertEquals(150, orders.getInteger("progress"))
        assertEquals(100, orders.getInteger("percent"), "capped at 100")
        assertNull(orders.getString("currency"))
        assertNull(orders.getLong("endsAt"))
    }

    @Test
    fun `a periodic goal whose period ended without an order shows an empty bar`(): Unit = runBlocking {
        // the progress was written in the week of 28 September, the current week began on Monday 5 October
        goal("Weekly", GoalMetric.ORDERS, target = 10, progress = 7, period = GoalPeriod.WEEKLY, periodStart = at(2026, 9, 28, 0))
        goal("This week", GoalMetric.ORDERS, target = 10, progress = 4, period = GoalPeriod.WEEKLY, periodStart = at(2026, 10, 5, 0))

        val goals = widgets().getJsonArray("goals").map { it as JsonObject }.associateBy { it.getString("name") }

        assertEquals(0, goals.getValue("Weekly").getInteger("progress"))
        assertEquals(0, goals.getValue("Weekly").getInteger("percent"))
        assertEquals(4, goals.getValue("This week").getInteger("progress"))
    }

    // ================================================================================================== stats

    @Test
    fun `the stats widget counts today's orders, all orders, distinct customers and active products`(): Unit = runBlocking {
        w.configure { config(stats = true) }

        order("Steve", paidAt = at(2026, 10, 7, 8), userId = 1)
        order("Steve", paidAt = at(2026, 10, 2), userId = 1)
        order("Mary", paidAt = at(2026, 10, 6, 23), userId = 2)
        order("Guest", paidAt = at(2026, 10, 7, 9))
        order("Tester", test = true)
        order("Pending", status = OrderStatus.PENDING)

        w.fixtures.product(slug = "a")
        w.fixtures.product(slug = "b")
        w.fixtures.product(slug = "hidden", status = MarketStatus.HIDDEN)
        w.fixtures.product(slug = "archived", status = MarketStatus.ARCHIVED)

        val stats = widgets().getJsonObject("stats")

        assertEquals(2L, stats.getLong("ordersToday"))
        assertEquals(4L, stats.getLong("ordersTotal"))
        assertEquals(3L, stats.getLong("customersTotal"))
        assertEquals(2L, stats.getLong("productsTotal"))
        assertEquals(setOf("ordersToday", "ordersTotal", "customersTotal", "productsTotal"), stats.fieldNames())
    }

    // ================================================================================================== cache

    @Test
    fun `the answer is cached for 30 seconds, then a new order shows`(): Unit = runBlocking {
        order("Steve")
        assertEquals(listOf("Steve"), widgets().names("recentBuyers"))

        order("Mary", paidAt = at(2026, 10, 7, 11))
        assertEquals(listOf("Steve"), widgets().names("recentBuyers"), "inside the 30 s the stored answer is served")

        w.clock.advance(29_000)
        assertEquals(listOf("Steve"), widgets().names("recentBuyers"))

        w.clock.advance(1_001)
        assertEquals(listOf("Mary", "Steve"), widgets().names("recentBuyers"))
    }

    @Test
    fun `a changed module setting shows at once, the cache is per configuration and per include set`(): Unit = runBlocking {
        order("Steve")
        assertTrue(widgets().containsKey("recentBuyers"))

        w.configure { config(buyers = false) }
        assertFalse(widgets().containsKey("recentBuyers"), "no wait for the 30 s")

        w.configure { config(buyers = true) }
        assertTrue(widgets().containsKey("recentBuyers"))

        assertFalse(widgets(setOf("goals")).containsKey("recentBuyers"), "another include set is another cache entry")
        assertNotNull(widgets().getJsonArray("sidebars"))
    }

    @Test
    fun `the cached answer cannot be changed by the caller`(): Unit = runBlocking {
        order("Steve")

        val first = widgets()

        first.getJsonArray("recentBuyers").clear()
        first.put("junk", true)

        val second = widgets()

        assertEquals(1, second.getJsonArray("recentBuyers").size())
        assertFalse(second.containsKey("junk"))
        assertEquals(JsonArray().add("home"), second.getJsonArray("sidebars"))
    }
}
