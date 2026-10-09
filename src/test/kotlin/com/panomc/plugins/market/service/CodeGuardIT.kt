package com.panomc.plugins.market.service

import com.panomc.platform.model.Error
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.cart.CartLine
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.impl.MarketThrottleDaoImpl
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.error.CodeAttemptsLocked
import com.panomc.plugins.market.routes.user.gift.GiftRedeemService
import com.panomc.plugins.market.support.Fixtures
import com.panomc.plugins.market.support.MarketTestDb
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.DiscountUnit
import com.panomc.plugins.market.util.GiftType
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
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
import com.panomc.plugins.market.support.ErrorBodies

/**
 * `CodeGuard` on a real MariaDB (MK-152; 11 section 12.2, 19.9 cases 2 to 10; the twin of A-02): the lock on coupon and creator codes in the quote and in
 * checkout (the production `CheckoutService` with the real guard), on gift redemption (scope `GIFT`), the subjects, the de-duplication, the settings read at call
 * time and a lock that outlives its pool. A locked subject is refused even with a valid code: the code is not looked up.
 */
class CodeGuardIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var h: CheckoutHarness
    private lateinit var guard: CodeGuard
    private lateinit var throttle: ThrottleService

    @Volatile
    private var settings: MarketConfig = MarketConfig(couponLockThreshold = 3, couponLockMinutes = 15)

    private val vertx: Vertx = Vertx.vertx()
    private val minute = 60_000L

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    @BeforeEach
    fun freshState() {
        runBlocking { resetState() }
        settings = MarketConfig(couponLockThreshold = 3, couponLockMinutes = 15)
        w = TestWiring(pool)
        h = CheckoutHarness(w, vertx)
        throttle = ThrottleService(MarketThrottleDaoImpl(), { pool }, w.clock)
        guard = CodeGuard(throttle, { settings }, w.clock)
        h.codeGuard = guard
        h.rebuild()
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private val fx get() = w.fixtures

    private suspend fun user(name: String, ip: String? = "203.0.113.7"): QuoteCaller {
        val u: TestUser = fx.user(name)

        h.emails[u.id] = "$name@example.com"

        return QuoteCaller(u.id, clientIp = ip)
    }

    private suspend fun quote(coupon: String? = null, creator: String? = null, caller: QuoteCaller, product: MarketProduct): Quote =
        h.service.quote(
            QuoteInput(items = listOf(CartLine(product.id, 0, 1, emptyMap(), null)), couponCode = coupon, creatorCode = creator, guest = GuestInput("Steve", "steve@example.com")),
            caller, pool
        )

    private suspend fun wrong(n: Int, caller: QuoteCaller, product: MarketProduct, prefix: String = "NOPE") {
        repeat(n) { quote(coupon = "$prefix$it", caller = caller, product = product) }
    }

    private suspend fun fails(block: suspend () -> Any?): Error {
        try {
            block()
        } catch (e: Error) {
            return e
        }

        error("expected an error")
    }

    private fun retryAfter(e: Error): Int = ErrorBodies.details(e).getInteger("retryAfter")

    private fun locked(q: Quote) = q.coupon != null && q.coupon!!.reason == "CODE_ATTEMPTS_LOCKED"

    // ------------------------------------------------------------------------------------------------------ subjects

    @Test
    fun `subjects are the trusted address bucket and the buyer key, or anon when there is neither`() {
        assertEquals(listOf("ip:203.0.113.7", "b:u:5"), guard.subjectsOf("203.0.113.7", "u:5"))
        assertEquals(listOf("b:g:steve"), guard.subjectsOf(null, "g:steve"), "an untrusted address skips the IP dimension")
        assertEquals(listOf("ip:203.0.113.7"), guard.subjectsOf("203.0.113.7", ""))
        assertEquals(listOf("anon"), guard.subjectsOf(null, null))
        assertEquals(guard.subjectsOf("2001:db8:1:2::1", "u:5"), guard.subjectsOf("2001:db8:1:2:ffff::9", "u:5"), "one /64 is one subject")
    }

    // ------------------------------------------------------------------------------------------------------ the quote

    @Test
    fun `threshold distinct unknown codes lock the subject, the next quote leaves even a valid code out and says CODE_ATTEMPTS_LOCKED`(): Unit = runBlocking {
        val p = fx.product(price = 1000)

        fx.coupon("REAL10", DiscountUnit.PERCENT, 1000)

        val alice = user("Alice")

        wrong(2, alice, p)
        assertFalse(locked(quote(coupon = "REAL10", caller = alice, product = p)), "two failures: not locked, a valid code is priced")
        assertEquals(900L, quote(coupon = "REAL10", caller = alice, product = p).total, "the valid code is applied")

        wrong(1, alice, p, "THIRD")

        val q = quote(coupon = "REAL10", caller = alice, product = p)

        assertTrue(locked(q))
        assertFalse(q.coupon!!.valid)
        assertEquals("REAL10", q.coupon!!.code)
        assertTrue(q.messages.any { it.code == "CODE_ATTEMPTS_LOCKED" && it.level == "error" })
        assertEquals(1000L, q.total, "the rest of the quote is computed without the code")
        assertEquals(1L, count("market_throttle", "`scope` = 'COUPON' AND `lockedUntil` IS NOT NULL AND `subject` = 'b:u:${w.users.idOf("Alice")}'"))
    }

    @Test
    fun `a locked checkout with a code is 429 CODE_ATTEMPTS_LOCKED with retryAfter, no order is written, and it works again after the lock`(): Unit = runBlocking {
        val p = fx.product(price = 1000, stock = 5)

        fx.coupon("REAL10", DiscountUnit.PERCENT, 1000)
        fx.paymentMethod("fake")

        val alice = user("Alice")

        wrong(3, alice, p)

        val e = fails { h.checkout(h.body("items" to listOf(h.line(p)), "paymentMethodId" to "fake", "couponCode" to "REAL10"), caller = alice) }

        assertEquals("CODE_ATTEMPTS_LOCKED", e.getErrorCode())
        assertEquals(429, e.getStatusCode())
        assertEquals(900, retryAfter(e), "15 minutes, counted from the third failure, in seconds")
        assertEquals(0L, count("market_order"))

        // without a code the same buyer is not refused
        h.checkout(h.body("items" to listOf(h.line(p)), "paymentMethodId" to "fake"), caller = alice)
        assertEquals(1L, count("market_order"))

        w.clock.advance(15 * minute)

        val order = h.checkout(h.body("items" to listOf(h.line(p)), "paymentMethodId" to "fake", "couponCode" to "REAL10", "expectedTotal" to 9.0), caller = alice)

        assertEquals(2L, count("market_order"))
        assertNotNull(order.orderToken)
    }

    @Test
    fun `an unknown code checked at checkout counts too, and a creator code shares the scope with coupons`(): Unit = runBlocking {
        val p = fx.product(price = 1000, stock = 9)

        fx.paymentMethod("fake")

        val alice = user("Alice")

        repeat(2) { n -> fails { h.checkout(h.body("items" to listOf(h.line(p)), "paymentMethodId" to "fake", "couponCode" to "NOPE$n"), caller = alice) } }

        val third = fails { h.checkout(h.body("items" to listOf(h.line(p)), "paymentMethodId" to "fake", "creatorCode" to "STREAMER"), caller = alice) }

        assertEquals("INVALID_CREATOR_CODE", third.getErrorCode(), "the third unknown code is still answered as unknown")

        val fourth = fails { h.checkout(h.body("items" to listOf(h.line(p)), "paymentMethodId" to "fake", "couponCode" to "NOPE9"), caller = alice) }

        assertEquals("CODE_ATTEMPTS_LOCKED", fourth.getErrorCode())
    }

    @Test
    fun `the same unknown code twenty times counts once`(): Unit = runBlocking {
        val p = fx.product(price = 1000)
        val alice = user("Alice")

        repeat(20) { quote(coupon = "SAME", caller = alice, product = p) }

        assertFalse(locked(quote(coupon = "SAME", caller = alice, product = p)))
        assertEquals(1, sql("SELECT `count` FROM `pano_market_throttle` WHERE `subject` = 'b:u:${w.users.idOf("Alice")}'").single().getInteger("count"))
    }

    @Test
    fun `the de-duplication is per subject and forgets a code after the window`(): Unit = runBlocking {
        val p = fx.product(price = 1000)
        val alice = user("Alice")

        quote(coupon = "SAME", caller = alice, product = p)
        w.clock.advance(15 * minute)
        quote(coupon = "SAME", caller = alice, product = p)

        assertEquals(1, sql("SELECT `count` FROM `pano_market_throttle` WHERE `subject` = 'b:u:${w.users.idOf("Alice")}'").single().getInteger("count"), "the window rolled, count restarted at 1")

        // another subject has its own memory of the code
        val bob = user("Bob", ip = "198.51.100.1")

        quote(coupon = "SAME", caller = bob, product = p)
        assertEquals(1, sql("SELECT `count` FROM `pano_market_throttle` WHERE `subject` = 'b:u:${w.users.idOf("Bob")}'").single().getInteger("count"))
    }

    @Test
    fun `a real but expired or not yet started code twenty times never locks`(): Unit = runBlocking {
        val p = fx.product(price = 1000)
        val expired = fx.coupon("OLD", DiscountUnit.PERCENT, 1000)
        val later = fx.coupon("LATER", DiscountUnit.PERCENT, 1000)

        Fixtures.setColumns(pool, "market_coupon", expired.id, mapOf("expiryDate" to w.clock.now() - 1000))
        Fixtures.setColumns(pool, "market_coupon", later.id, mapOf("startDate" to w.clock.now() + 3_600_000))

        val alice = user("Alice")

        repeat(20) {
            quote(coupon = "OLD", caller = alice, product = p)
            quote(coupon = "LATER", caller = alice, product = p)
        }

        assertFalse(locked(quote(coupon = "OLD", caller = alice, product = p)))
        assertEquals(0L, count("market_throttle"))
    }

    @Test
    fun `a valid code between wrong ones does not reset the counter`(): Unit = runBlocking {
        val p = fx.product(price = 1000)

        fx.coupon("REAL10", DiscountUnit.PERCENT, 1000)

        val alice = user("Alice")

        quote(coupon = "W1", caller = alice, product = p)
        quote(coupon = "REAL10", caller = alice, product = p)
        quote(coupon = "W2", caller = alice, product = p)
        quote(coupon = "REAL10", caller = alice, product = p)
        quote(coupon = "W3", caller = alice, product = p)

        assertTrue(locked(quote(coupon = "REAL10", caller = alice, product = p)))
    }

    @Test
    fun `the lock follows the account across addresses and the address across accounts`(): Unit = runBlocking {
        val p = fx.product(price = 1000)
        val alice = user("Alice", ip = "203.0.113.7")
        val bob = user("Bob", ip = "203.0.113.7")

        wrong(3, alice, p)

        assertTrue(locked(quote(coupon = "X", caller = alice, product = p)))
        assertTrue(locked(quote(coupon = "X", caller = QuoteCaller(alice.userId, clientIp = "198.51.100.77"), product = p)), "the same account from another address")
        assertTrue(locked(quote(coupon = "X", caller = bob, product = p)), "another account from the locked address")
        assertFalse(locked(quote(coupon = "X", caller = QuoteCaller(bob.userId, clientIp = "198.51.100.77"), product = p)), "another account from another address")
    }

    @Test
    fun `an unknown code that came from the server cart is removed from it after one quote`(): Unit = runBlocking {
        val p = fx.product(price = 1000)
        val alice = user("Alice")
        val cartId = w.carts.ensure(alice.userId!!, w.clock.now(), pool)

        w.carts.updateFields(cartId, mapOf("couponCode" to "GHOST", "creatorCode" to "GHOST2"), w.clock.now(), pool)
        h.service.quote(QuoteInput(), alice, pool)

        val cart = w.carts.getById(cartId, pool)!!

        assertNull(cart.couponCode)
        assertNull(cart.creatorCode)
        assertEquals(2, sql("SELECT `count` FROM `pano_market_throttle` WHERE `subject` LIKE 'b:u:%'").single().getInteger("count"), "both codes counted on the account")

        // the second quote does not send them again: nothing more is counted
        h.service.quote(QuoteInput(), alice, pool)
        assertEquals(2, sql("SELECT `count` FROM `pano_market_throttle` WHERE `subject` LIKE 'b:u:%'").single().getInteger("count"))
    }

    @Test
    fun `an anonymous caller on an untrusted address is the anon subject with ten times the threshold, accounts are unaffected`(): Unit = runBlocking {
        val p = fx.product(price = 1000)
        val anonymous = QuoteCaller(null)

        // a guest without a name and no address: the quote still prices (a guest object is only needed to check out)
        suspend fun tryCode(n: Int) = h.service.quote(QuoteInput(items = listOf(CartLine(p.id, 0, 1, emptyMap(), null)), couponCode = "ANON$n"), anonymous, pool)

        repeat(29) { tryCode(it) }
        assertFalse(locked(tryCode(100)), "30 is the threshold: the 30th failure is the one that locks")

        assertTrue(locked(tryCode(101)))

        // a logged-in user is never keyed on anon
        val alice = user("Alice", ip = null)

        assertFalse(locked(quote(coupon = "X", caller = alice, product = p)))
    }

    // ------------------------------------------------------------------------------------------------------ settings, restarts

    @Test
    fun `the threshold and the lock minutes are read at call time, 0 switches the lock off`(): Unit = runBlocking {
        val p = fx.product(price = 1000)
        val alice = user("Alice")

        settings = MarketConfig(couponLockThreshold = 0, couponLockMinutes = 15)
        wrong(10, alice, p)
        assertEquals(0L, count("market_throttle"), "switched off: nothing is written")
        assertFalse(locked(quote(coupon = "REAL", caller = alice, product = p)))

        settings = MarketConfig(couponLockThreshold = 2, couponLockMinutes = 30)
        wrong(2, alice, p, "AGAIN")

        assertTrue(locked(quote(coupon = "X", caller = alice, product = p)))
        assertEquals(
            w.clock.now() + 30 * minute, sql("SELECT `lockedUntil` FROM `pano_market_throttle` WHERE `subject` LIKE 'b:u:%'").single().getLong("lockedUntil"), "30 minutes, as the setting says now"
        )

        // switching the mechanism off lifts the refusal at once; the row stays
        settings = MarketConfig(couponLockThreshold = 0, couponLockMinutes = 30)
        assertFalse(locked(quote(coupon = "X", caller = alice, product = p)))
    }

    @Test
    fun `a lock survives a new pool and a new guard`(): Unit = runBlocking {
        val p = fx.product(price = 1000)
        val alice = user("Alice")

        wrong(3, alice, p)

        val other = MarketTestDb.pool(databaseName, 2)

        try {
            val fresh = CodeGuard(ThrottleService(MarketThrottleDaoImpl(), { other }, w.clock), { settings }, w.clock)

            assertNotNull(fresh.lockedUntil("COUPON", fresh.subjectsOf("203.0.113.7", "u:${alice.userId}")))
            assertNull(fresh.lockedUntil("COUPON", fresh.subjectsOf("198.51.100.1", "u:99")))
        } finally {
            other.close().coAwait()
        }
    }

    // ------------------------------------------------------------------------------------------------------ gift codes

    @Test
    fun `gift redeem has its own scope, wrong gift codes lock the redeemer, a valid code is refused, and coupons are unaffected`(): Unit = runBlocking {
        val c = CreditHarness(w, vertx)
        val redemptions = RedemptionService(w.clock, c.ph.locks, w.redemptions)
        val redeemer = GiftRedeemService(c.service, redemptions, { pool }, w.clock, { guard.forScope("GIFT") })
        fx.gift("CREDITS-50", GiftType.CREDIT, creditAmount = 5000, redeemLimit = 1)
        val alice = user("Alice")
        val p = fx.product(price = 1000)

        repeat(3) { n -> assertEquals("INVALID_GIFT_CODE", fails { redeemer.redeem("NOGIFT$n", null, emptyMap(), alice, "en-US") }.getErrorCode()) }

        val e = fails { redeemer.redeem("CREDITS-50", null, emptyMap(), alice, "en-US") }

        assertEquals("CODE_ATTEMPTS_LOCKED", e.getErrorCode())
        assertEquals(429, e.getStatusCode())
        assertEquals(900, retryAfter(e))
        assertEquals(0L, count("market_order"), "the valid code was not looked up and not used")
        assertEquals(0L, count("market_redemption"), "and no redemption row exists")

        assertEquals(2L, count("market_throttle", "`scope` = 'GIFT' AND `lockedUntil` IS NOT NULL"), "the address and the account, both in the scope GIFT")
        assertEquals(0L, count("market_throttle", "`scope` = 'COUPON'"), "no coupon-scope counter was touched by the gift attempts")
        assertFalse(locked(quote(coupon = "REAL", caller = alice, product = p)), "the coupon scope is independent")

        w.clock.advance(15 * minute)
        redeemer.redeem("CREDITS-50", null, emptyMap(), alice, "en-US")
        assertEquals(1L, count("market_order"), "the lock has ended, the code works")
    }

    @Test
    fun `the lock error is the one the routes map, CodeAttemptsLocked with a retryAfter of at least one second`() {
        val e = CodeAttemptsLocked(guard.retryAfterSeconds(w.clock.now() + 1))

        assertEquals(429, e.getStatusCode())
        assertEquals(1, retryAfter(e))
    }
}
