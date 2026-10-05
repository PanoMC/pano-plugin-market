package com.panomc.plugins.market.service

import com.panomc.platform.model.Error
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.cart.CartLine
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.impl.MarketThrottleDaoImpl
import com.panomc.plugins.market.error.TooManyRequests
import com.panomc.plugins.market.routes.user.gift.GiftRedeemService
import com.panomc.plugins.market.util.GiftType
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.support.TestWiring
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The limits of 11 section 11 on a real MariaDB (MK-152; A-01 and V-12 twins; 11 section 19.1 cases 6 to 10): L1 (the IP bucket is consumed before the idempotency
 * replay lookup and a replay consumes nothing from the buyer's bucket), L2 / L8 / L9 / L11 in `MarketRateLimits`, L3 (orders per address and hour, durable in
 * `market_throttle`, a replay and a refusal are not counted), L4 (unpaid held orders per buyer, recipient, e-mail and address, the held-unit cap of an offline method)
 * and the limits being read from the settings at call time.
 */
class RateLimitIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var h: CheckoutHarness
    private lateinit var throttle: ThrottleService
    private val vertx: Vertx = Vertx.vertx()

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    @BeforeEach
    fun freshState() {
        runBlocking { resetState() }
        w = TestWiring(pool)
        h = CheckoutHarness(w, vertx)
        throttle = ThrottleService(MarketThrottleDaoImpl(), { pool }, w.clock)
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private val fx get() = w.fixtures

    private suspend fun user(name: String, ip: String? = null): QuoteCaller {
        val u = fx.user(name)

        h.emails[u.id] = "$name@example.com"

        return QuoteCaller(u.id, clientIp = ip)
    }

    private suspend fun fails(block: suspend () -> Any?): Error {
        try {
            block()
        } catch (e: Error) {
            return e
        }

        error("expected an error")
    }

    private fun retryAfter(e: Error): Int = JsonObject(e.encode()).getInteger("retryAfter")

    private fun orderBody(p: com.panomc.plugins.market.db.model.MarketProduct, vararg more: Pair<String, Any?>): JsonObject =
        h.body("items" to listOf(h.line(p)), "paymentMethodId" to "fake", *more)

    private suspend fun open(table: String = "market_order", where: String = "`status` = 'PENDING' AND `reservationState` = 'HELD'") = count(table, where)

    /** Frees the order of the one that expires first: what `OrderExpiryJob` does to an unpaid order (no stock, no code, so nothing else moves). */
    private suspend fun release(orderId: Long) {
        sql("UPDATE `pano_market_order` SET `status` = 'CANCELLED', `reservationState` = 'RELEASED' WHERE `id` = ?", orderId)
    }

    // ================================================================================================================ L1 (A-01 twin)

    @Test
    fun `A-01 the fourth checkout within the minute is 429 TOO_MANY_REQUESTS with a retryAfter, and the setting is read at call time`(): Unit = runBlocking {
        val p = fx.product(price = 100, stock = 50)

        fx.paymentMethod("fake")
        h.config = h.config.copy(checkoutRateLimitPerMinute = 3)

        val alice = user("Alice", "203.0.113.50")

        repeat(3) { h.checkout(orderBody(p), caller = alice) }

        val e = fails { h.checkout(orderBody(p), caller = alice) }

        assertEquals("TOO_MANY_REQUESTS", e.getErrorCode())
        assertEquals(429, e.getStatusCode())
        assertEquals(20, retryAfter(e), "60 s / 3 per minute")
        assertEquals(3L, count("market_order"))

        // 0 switches the limit off, with no restart and no new service
        h.config = h.config.copy(checkoutRateLimitPerMinute = 0)
        h.checkout(orderBody(p), caller = alice)

        assertEquals(4L, count("market_order"))
    }

    @Test
    fun `the IP bucket is consumed before the replay lookup, a replay consumes no token of the buyer`(): Unit = runBlocking {
        val p = fx.product(price = 100, stock = 50)

        fx.paymentMethod("fake")
        h.config = h.config.copy(checkoutRateLimitPerMinute = 3)

        // Alice: the order takes one token of her bucket; five replays from five other addresses each take one token of an address, none of hers
        val alice = user("Alice", "203.0.113.50")
        val first = h.checkout(orderBody(p), key = "replay-key-00000001", caller = alice).order.getString("publicId")

        repeat(5) { n ->
            assertEquals(first, h.checkout(orderBody(p), key = "replay-key-00000001", caller = QuoteCaller(alice.userId, clientIp = "198.51.100.${n + 1}")).order.getString("publicId"))
        }

        // two tokens of the buyer are left: two more orders from a fresh address, the third is refused
        repeat(2) { h.checkout(orderBody(p), caller = QuoteCaller(alice.userId, clientIp = "192.0.2.1")) }

        assertEquals("TOO_MANY_REQUESTS", fails { h.checkout(orderBody(p), caller = QuoteCaller(alice.userId, clientIp = "192.0.2.2")) }.getErrorCode())
        assertEquals(3L, count("market_order"), "one order and its five replays, then two new ones")

        // and the address check comes first: on an empty address bucket even a replay is refused
        val bob = user("Bob", "203.0.113.99")
        val bobsOrder = h.checkout(orderBody(p), key = "replay-key-00000002", caller = bob).order.getString("publicId")

        h.checkout(orderBody(p), key = "replay-key-00000002", caller = bob)
        h.checkout(orderBody(p), key = "replay-key-00000002", caller = bob)

        val e = fails { h.checkout(orderBody(p), key = "replay-key-00000002", caller = bob) }

        assertEquals("TOO_MANY_REQUESTS", e.getErrorCode(), "the IP token is taken before the replay is looked up")
        assertNotNull(bobsOrder)
    }

    // ================================================================================================================ MarketRateLimits

    @Test
    fun `L2 quote is per address and per account, an untrusted address only has the account bucket, the setting is read at call time`() {
        var perMinute = 2
        val limits = MarketRateLimits { MarketConfig(quoteRateLimitPerMinute = perMinute) }

        limits.quote("203.0.113.1", null)
        limits.quote("203.0.113.1", null)

        val e = assertThrows(TooManyRequests::class.java) { limits.quote("203.0.113.1", null) }

        assertEquals(30, JsonObject(e.encode()).getInteger("retryAfter"))
        limits.quote("203.0.113.2", null)

        // IPv6: one /64 is one bucket
        limits.quote("2001:db8:1:2::1", null)
        limits.quote("2001:db8:1:2:ffff::1", null)
        assertThrows(TooManyRequests::class.java) { limits.quote("2001:db8:1:2::77", null) }

        // an account has its own bucket across addresses; an untrusted address (null) skips the IP bucket
        limits.quote(null, 7)
        limits.quote("198.51.100.1", 7)
        assertThrows(TooManyRequests::class.java) { limits.quote(null, 7) }
        limits.quote(null, null)
        limits.quote(null, null)
        limits.quote(null, null)

        perMinute = 100
        repeat(50) { limits.quote("203.0.113.1", 7) }
    }

    @Test
    fun `L1 checkout limiter is rebuilt when the setting changes and 0 disables it, reconfigure drops it`() {
        var perMinute = 1
        val limits = MarketRateLimits { MarketConfig(checkoutRateLimitPerMinute = perMinute) }

        limits.checkout("203.0.113.1", "u:1")
        assertThrows(TooManyRequests::class.java) { limits.checkout("203.0.113.1", "u:2") }
        assertThrows(TooManyRequests::class.java) { limits.checkoutBuyer("u:1") }

        perMinute = 0
        repeat(10) { limits.checkout("203.0.113.1", "u:1") }

        perMinute = 1
        limits.checkout("203.0.113.1", "u:1")
        limits.reconfigure()
        limits.checkout("203.0.113.1", "u:1")
    }

    @Test
    fun `L6 L7 L8 L9 and L11 have the budgets of the table`() {
        val limits = MarketRateLimits { MarketConfig() }

        repeat(30) { limits.orderTokenMiss("203.0.113.1") }
        assertThrows(TooManyRequests::class.java) { limits.orderTokenMiss("203.0.113.1") }
        limits.orderTokenMiss("203.0.113.2")
        limits.orderTokenMiss(null)

        repeat(120) { limits.orderStatus("203.0.113.1") }
        assertThrows(TooManyRequests::class.java) { limits.orderStatus("203.0.113.1") }

        repeat(10) { limits.panelAction(5) }
        val l8 = assertThrows(TooManyRequests::class.java) { limits.panelAction(5) }

        assertTrue(JsonObject(l8.encode()).getInteger("retryAfter") >= 1)
        limits.panelAction(6)

        repeat(60) { limits.inboundRejected("203.0.113.1", "stripe") }
        assertThrows(TooManyRequests::class.java) { limits.inboundRejected("203.0.113.1", "stripe") }
        limits.inboundRejected("203.0.113.1", "paypal")
        limits.inboundRejected("203.0.113.2", "stripe")

        repeat(6) { limits.export(5) }
        assertThrows(TooManyRequests::class.java) { limits.export(5) }
        limits.export(6)
    }

    // ================================================================================================================ L3

    @Test
    fun `L3 the 21st order from one address within the hour is 429, a replay is not counted, the window ends by itself`(): Unit = runBlocking {
        val p = fx.product(price = 100, stock = 100)

        fx.paymentMethod("fake")
        h.throttle = throttle
        h.rebuild()

        val ip = "203.0.113.10"
        val users = (1..21).map { user("Buyer$it", ip) }

        repeat(20) { n -> h.checkout(orderBody(p), key = "l3-key-${n.toString().padStart(12, '0')}", caller = users[n]) }

        assertEquals(20, sql("SELECT `count` FROM `pano_market_throttle` WHERE `scope` = 'CHECKOUT' AND `subject` = 'ip:$ip'").single().getInteger("count"))

        // a replayed Idempotency-Key does not count
        h.checkout(orderBody(p), key = "l3-key-000000000003", caller = users[3])
        assertEquals(20, sql("SELECT `count` FROM `pano_market_throttle` WHERE `subject` = 'ip:$ip'").single().getInteger("count"))

        val e = fails { h.checkout(orderBody(p), caller = users[20]) }

        assertEquals("TOO_MANY_REQUESTS", e.getErrorCode())
        assertEquals(429, e.getStatusCode())
        assertTrue(retryAfter(e) in 1..3600, "seconds to the end of the window, was ${retryAfter(e)}")
        assertEquals(20L, count("market_order"))
        assertEquals(20, sql("SELECT `count` FROM `pano_market_throttle` WHERE `subject` = 'ip:$ip'").single().getInteger("count"), "a refusal is not counted")

        // another address is not affected, an untrusted address (null) is never counted
        h.checkout(orderBody(p), caller = QuoteCaller(users[20].userId, clientIp = "203.0.113.11"))
        h.checkout(orderBody(p), caller = QuoteCaller(users[19].userId, clientIp = null))
        assertEquals(1L, count("market_throttle", "`scope` = 'CHECKOUT' AND `subject` = 'ip:203.0.113.11'"))
        assertEquals(2L, count("market_throttle"), "no row for a null address")

        // A-01: rewinding the window start by an hour lets the address through again, and the count starts at 1
        sql("UPDATE `pano_market_throttle` SET `windowStart` = `windowStart` - 3600001 WHERE `subject` = 'ip:$ip'")
        h.checkout(orderBody(p), caller = users[20].let { QuoteCaller(it.userId, clientIp = ip) })

        assertEquals(1, sql("SELECT `count` FROM `pano_market_throttle` WHERE `subject` = 'ip:$ip'").single().getInteger("count"))
    }

    @Test
    fun `L3 counts only an order that was created, a checkout that fails leaves the counter alone`(): Unit = runBlocking {
        val p = fx.product(price = 100, stock = 1)

        fx.paymentMethod("fake")
        h.throttle = throttle
        h.rebuild()

        val a = user("Alice", "203.0.113.20")
        val b = user("Bob", "203.0.113.20")

        h.checkout(orderBody(p), caller = a)

        assertEquals("OUT_OF_STOCK", fails { h.checkout(orderBody(p), caller = b) }.getErrorCode())
        assertEquals(1, sql("SELECT `count` FROM `pano_market_throttle` WHERE `subject` = 'ip:203.0.113.20'").single().getInteger("count"))
    }

    // ================================================================================================================ L4 (V-12 twin)

    @Test
    fun `L4 the fourth unpaid order of one buyer is 429 with the wait until the oldest expires, and one place is free again after it expires`(): Unit = runBlocking {
        val p = fx.product(price = 100)

        fx.paymentMethod("fake")
        h.openOrders = OpenOrderLimit(w.orders, w.clock)
        h.rebuild()

        val alice = user("Alice")
        val results = (1..3).map { h.checkout(orderBody(p), caller = alice) }

        val e = fails { h.checkout(orderBody(p), caller = alice) }

        assertEquals("TOO_MANY_REQUESTS", e.getErrorCode())
        assertEquals(429, e.getStatusCode())

        val oldest = sql("SELECT MIN(`expiresAt`) AS e FROM `pano_market_order`").single().getLong("e")

        assertEquals(Math.ceil((oldest - w.clock.now()) / 1000.0).toInt(), retryAfter(e), "seconds until the oldest of the three expires")
        assertEquals(3L, open())

        release(w.orders.getByPublicId(results[0].order.getString("publicId"), pool)!!.id)
        h.checkout(orderBody(p), caller = alice)

        assertEquals(3L, open(), "one expired, one new: three open again")
    }

    @Test
    fun `L4 a guest who varies the name is stopped by the e-mail, and by the address`(): Unit = runBlocking {
        val p = fx.product(price = 100)

        fx.paymentMethod("fake")
        h.openOrders = OpenOrderLimit(w.orders, w.clock)
        h.rebuild()

        fun guest(name: String, email: String) = mapOf("username" to name, "email" to email)

        // the key g:<name> alone caps nothing: four names, one e-mail
        repeat(3) { n -> h.checkout(orderBody(p, "guest" to guest("Name$n", "same@example.com"))) }

        assertEquals("TOO_MANY_REQUESTS", fails { h.checkout(orderBody(p, "guest" to guest("Name9", "Same@Example.com"))) }.getErrorCode(), "the e-mail is compared in lower case")
        assertEquals(3L, count("market_order"))

        // four names and four e-mails from one address
        val ip = QuoteCaller(null, clientIp = "203.0.113.30")

        repeat(3) { n -> h.checkout(orderBody(p, "guest" to guest("Other$n", "other$n@example.com")), caller = ip) }

        assertEquals("TOO_MANY_REQUESTS", fails { h.checkout(orderBody(p, "guest" to guest("Other9", "other9@example.com")), caller = ip) }.getErrorCode())
        assertEquals(6L, count("market_order"))

        // an untrusted address skips the address dimension: the same four orders go through when nothing else is shared
        repeat(4) { n -> h.checkout(orderBody(p, "guest" to guest("Free$n", "free$n@example.com"))) }

        assertEquals(10L, count("market_order"))
    }

    @Test
    fun `L4 gifts to one recipient are capped by recipient, and the recipient's own checkout is never blocked by strangers' gifts`(): Unit = runBlocking {
        val p = fx.product(price = 100)

        fx.paymentMethod("fake")
        h.openOrders = OpenOrderLimit(w.orders, w.clock)
        h.rebuild()

        val victim = user("Victim", "203.0.113.40")
        val payers = (1..4).map { user("Payer$it", "198.51.100.$it") }

        payers.take(3).forEach { h.checkout(orderBody(p, "recipientUsername" to "Victim"), caller = it) }

        val e = fails { h.checkout(orderBody(p, "recipientUsername" to "Victim"), caller = payers[3]) }

        assertEquals("TOO_MANY_REQUESTS", e.getErrorCode(), "the fourth unpaid gift for one recipient")

        // the victim buys for himself: only his own orders count for the recipient dimension of a purchase for oneself
        repeat(3) { h.checkout(orderBody(p), caller = victim) }

        assertEquals(6L, count("market_order"))
    }

    @Test
    fun `L4 counts storefront orders that are PENDING and HELD only, a paid or released order is free`(): Unit = runBlocking {
        val p = fx.product(price = 100)

        fx.paymentMethod("fake")
        h.openOrders = OpenOrderLimit(w.orders, w.clock)
        h.rebuild()

        val alice = user("Alice")
        val first = (1..3).map { h.checkout(orderBody(p), caller = alice) }

        first.forEach { release(w.orders.getByPublicId(it.order.getString("publicId"), pool)!!.id) }

        repeat(3) { h.checkout(orderBody(p), caller = alice) }

        assertEquals(3L, open())
        assertEquals(6L, count("market_order"))
    }

    @Test
    fun `L4 an unpaid order on an offline method holds at most min(maxQuantityPerOrder or 10, 10) units of a stock-limited product`(): Unit = runBlocking {
        val limited = fx.product("limited", price = 100, stock = 100)
        val capped = fx.product("capped", price = 100, stock = 100, columns = mapOf("maxQuantityPerOrder" to 4))
        val big = fx.product("big", price = 100, stock = 100, columns = mapOf("maxQuantityPerOrder" to 50))
        val unlimited = fx.product("unlimited", price = 100)

        fx.paymentMethod("fake")
        h.fake.caps = PaymentCapabilities().apply { longPending = true }

        val alice = user("Alice")

        fun body(p: com.panomc.plugins.market.db.model.MarketProduct, quantity: Int) = h.body("items" to listOf(h.line(p, quantity)), "paymentMethodId" to "fake")

        // 11 units of a stock-limited product on an offline method: the line error of 06
        val e = fails { h.checkout(body(limited, 11), caller = alice) }

        assertEquals("INVALID_CART", e.getErrorCode())
        assertEquals(400, e.getStatusCode())
        assertEquals(listOf("MAX_QUANTITY"), JsonObject(e.encode()).getJsonObject("lineErrors").getJsonArray(CartLine(limited.id, 0, 11, emptyMap(), null).lineKey).list)

        h.checkout(body(limited, 10), caller = alice)

        // the cap of the product wins when it is lower (4 is fine, 5 is over the cap); a cap above 10 is cut to 10; a product without stock is not limited
        h.checkout(body(capped, 4), caller = alice)
        assertEquals("INVALID_CART", fails { h.checkout(body(capped, 5), caller = alice) }.getErrorCode())
        assertEquals("INVALID_CART", fails { h.checkout(body(big, 11), caller = alice) }.getErrorCode(), "a product cap of 50 is cut to 10 for an unpaid offline order")
        h.checkout(body(unlimited, 50), caller = alice)

        // an online method (no longPending) is not touched by this rule
        h.fake.caps = PaymentCapabilities()
        h.checkout(body(limited, 11), caller = alice)
        h.checkout(body(big, 11), caller = alice)

        assertEquals(5L, count("market_order"))
    }

    @Test
    fun `L4 the held-unit cap sums a product over lines that differ in field values, and every contributing line is named`(): Unit = runBlocking {
        val limited = fx.product("limited-lines", price = 100, stock = 100)

        fx.field(limited, key = "note")
        fx.paymentMethod("fake")
        h.fake.caps = PaymentCapabilities().apply { longPending = true }

        // a guest: the harness copies the body of a logged-in caller, which turns nested field values into JsonObjects the parser refuses
        val guest = QuoteCaller.GUEST
        val a = h.line(limited, 6, values = mapOf("note" to "a"))
        val b = h.line(limited, 5, values = mapOf("note" to "b"))

        // 6 + 5 = 11 units of one stock subject on one unpaid offline order: both lines are over
        val e = fails { h.checkout(h.body("items" to listOf(a, b), "paymentMethodId" to "fake"), caller = guest) }

        assertEquals("INVALID_CART", e.getErrorCode())

        val errors = JsonObject(e.encode()).getJsonObject("lineErrors")

        assertEquals(2, errors.size(), "both lines of the subject are named")
        errors.forEach { (_, codes) -> assertEquals(listOf("MAX_QUANTITY"), (codes as io.vertx.core.json.JsonArray).list) }
        assertEquals(0L, count("market_order"))

        // 6 + 4 = 10 is the cap itself
        h.checkout(h.body("items" to listOf(a, h.line(limited, 4, values = mapOf("note" to "b"))), "paymentMethodId" to "fake"), caller = guest)
        assertEquals(1L, count("market_order"))
    }

    @Test
    fun `L4 the held-unit cap counts the stock-limited children of a bundle with its own unlimited parent`(): Unit = runBlocking {
        val child = fx.product("limited-child", price = 100, stock = 100)
        val free = fx.product("unlimited-child", price = 100)
        val bundle = fx.bundle(child to 6, free to 1, slug = "mixed-bundle")

        fx.paymentMethod("fake")
        h.fake.caps = PaymentCapabilities().apply { longPending = true }

        val alice = user("Alice")

        fun body(quantity: Int) = h.body("items" to listOf(h.line(bundle, quantity)), "paymentMethodId" to "fake")

        // 2 bundles x 6 = 12 units of the child on one unpaid offline order
        val e = fails { h.checkout(body(2), caller = alice) }

        assertEquals("INVALID_CART", e.getErrorCode())
        assertEquals(listOf("MAX_QUANTITY"), JsonObject(e.encode()).getJsonObject("lineErrors").getJsonArray(CartLine(bundle.id, 0, 2, emptyMap(), null).lineKey).list)
        assertEquals(0L, count("market_order"))

        // 1 bundle x 6 = 6 units is fine, and an online method is not touched
        h.checkout(body(1), caller = alice)
        h.fake.caps = PaymentCapabilities()
        h.checkout(body(2), caller = alice)
        assertEquals(2L, count("market_order"))
    }

    @Test
    fun `L4 a bundle and a plain line of the same limited product add up`(): Unit = runBlocking {
        val child = fx.product("shared-child", price = 100, stock = 100)
        val bundle = fx.bundle(child to 4, slug = "shared-bundle")

        fx.paymentMethod("fake")
        h.fake.caps = PaymentCapabilities().apply { longPending = true }

        val e = fails { h.checkout(h.body("items" to listOf(h.line(bundle, 2), h.line(child, 3)), "paymentMethodId" to "fake"), caller = user("Alice")) }

        assertEquals("INVALID_CART", e.getErrorCode())
        assertEquals(2, JsonObject(e.encode()).getJsonObject("lineErrors").size(), "8 + 3 = 11 units: the bundle line and the plain line are both named")
    }

    @Test
    fun `L4 a variant is the stock subject, an unlimited variant is never capped and a limited one is, whatever the product row says`(): Unit = runBlocking {
        val product = fx.product("with-variants", price = 100, stock = 100)
        val unlimited = fx.variant(product, name = "Unlimited")
        val limited = fx.variant(product, name = "Limited", stock = 100)

        fx.paymentMethod("fake")
        h.fake.caps = PaymentCapabilities().apply { longPending = true }

        val alice = user("Alice")

        // the product row's own stock is ignored by the reservation, so a NULL-stock variant is unlimited here too
        h.checkout(h.body("items" to listOf(h.line(product, 11, variant = unlimited.id)), "paymentMethodId" to "fake"), caller = alice)
        assertEquals(1L, count("market_order"))

        val e = fails { h.checkout(h.body("items" to listOf(h.line(product, 11, variant = limited.id)), "paymentMethodId" to "fake"), caller = alice) }

        assertEquals("INVALID_CART", e.getErrorCode())
        assertEquals(1L, count("market_order"))

        h.checkout(h.body("items" to listOf(h.line(product, 10, variant = limited.id)), "paymentMethodId" to "fake"), caller = alice)
        assertEquals(2L, count("market_order"))
    }

    // ================================================================================================================ L1 on gift redeem

    @Test
    fun `L1 the fourth gift redeem within the minute is 429 TOO_MANY_REQUESTS before any lookup, and 0 disables it`(): Unit = runBlocking {
        val c = CreditHarness(w, vertx)
        var perMinute = 3
        val limits = MarketRateLimits { MarketConfig(checkoutRateLimitPerMinute = perMinute) }
        val redeemer = GiftRedeemService(
            c.service, RedemptionService(w.clock, c.ph.locks, w.redemptions), { pool }, w.clock,
            guard = { com.panomc.plugins.market.routes.user.gift.GiftCodeGuard.NONE },
            limit = { caller -> limits.checkout(caller.clientIp, "u:${caller.userId}") }
        )
        val alice = user("Alice", "203.0.113.50")

        repeat(4) { fx.gift("CREDITS-$it", GiftType.CREDIT, creditAmount = 100, redeemLimit = 1) }
        repeat(3) { redeemer.redeem("CREDITS-$it", null, emptyMap(), alice, "en-US") }

        val e = fails { redeemer.redeem("CREDITS-3", null, emptyMap(), alice, "en-US") }

        assertEquals("TOO_MANY_REQUESTS", e.getErrorCode())
        assertEquals(429, e.getStatusCode())
        assertEquals(20, retryAfter(e), "60 s / 3 per minute")
        assertEquals(3L, count("market_order"), "the refused request redeemed nothing")

        // the IP bucket alone also refuses another account of the same address
        assertEquals("TOO_MANY_REQUESTS", fails { redeemer.redeem("CREDITS-3", null, emptyMap(), user("Bob", "203.0.113.50"), "en-US") }.getErrorCode())

        perMinute = 0
        redeemer.redeem("CREDITS-3", null, emptyMap(), alice, "en-US")
        assertEquals(4L, count("market_order"))
    }

    @Test
    fun `L1 the redeem route hands MarketRateLimits to the redeem service`() {
        val source = java.io.File("src/main/kotlin/com/panomc/plugins/market/routes/user/gift/RedeemGiftAPI.kt").readText()

        assertTrue(Regex("""limit\s*=\s*\{[^}]*rateLimits\.checkout\(""").containsMatchIn(source), "RedeemGiftAPI must wire L1 into GiftRedeemService")
    }

    @Test
    fun `L4 the address of an IPv6 buyer is its whole slash 64`(): Unit = runBlocking {
        val p = fx.product(price = 100)

        fx.paymentMethod("fake")
        h.openOrders = OpenOrderLimit(w.orders, w.clock)
        h.rebuild()

        val payers = (1..4).map { user("V6Payer$it", "2001:db8:1:2:${it}::${it}") }

        payers.take(3).forEach { h.checkout(orderBody(p), caller = it) }

        assertEquals("TOO_MANY_REQUESTS", fails { h.checkout(orderBody(p), caller = payers[3]) }.getErrorCode(), "four addresses of one /64")

        h.checkout(orderBody(p), caller = user("V6Other", "2001:db8:1:3::1"))

        assertEquals(4L, count("market_order"))
    }
}
