package com.panomc.plugins.market.core.abuse

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ThrottlePolicyTest {
    private val min = 60_000L
    private val t0 = 1_760_000_000_000L

    private fun failN(n: Int, threshold: Int = 5, window: Int = 10, lock: Int = 15, start: Long = t0, stepMs: Long = 1000): ThrottleState? {
        var s: ThrottleState? = null
        for (i in 0 until n) s = ThrottlePolicy.fail(s, start + i * stepMs, threshold, window, lock)
        return s
    }

    @Test
    fun `counts and locks at the threshold`() {
        val s4 = failN(4)!!
        assertEquals(4, s4.count)
        assertNull(s4.lockedUntil)
        val s5 = failN(5)!!
        assertEquals(5, s5.count)
        assertEquals(t0 + 4000 + 15 * min, s5.lockedUntil)
        assertEquals(t0 + 4000 + 15 * min, ThrottlePolicy.lockedUntil(s5, t0 + 5000))
    }

    @Test
    fun `threshold one locks on the first failure`() {
        val s = ThrottlePolicy.fail(null, t0, 1, 10, 5)!!
        assertEquals(1, s.count)
        assertEquals(t0 + 5 * min, s.lockedUntil)
    }

    @Test
    fun `window rolls over after the window`() {
        val s = failN(3)!! // windowStart t0
        val before = ThrottlePolicy.fail(s, t0 + 10 * min - 1, 5, 10, 15)!!
        assertEquals(4, before.count)
        assertEquals(t0, before.windowStart)
        val at = ThrottlePolicy.fail(s, t0 + 10 * min, 5, 10, 15)!!
        assertEquals(1, at.count)
        assertEquals(t0 + 10 * min, at.windowStart)
        assertNull(at.lockedUntil)
    }

    @Test
    fun `lock expires by itself and is extended by later failures`() {
        val locked = failN(5)!!
        val until = locked.lockedUntil!!
        assertNotNull(ThrottlePolicy.lockedUntil(locked, until - 1))
        assertNull(ThrottlePolicy.lockedUntil(locked, until))
        assertNull(ThrottlePolicy.lockedUntil(locked, until + 1))
        val again = ThrottlePolicy.fail(locked, t0 + 10_000, 5, 10, 15)!!
        assertEquals(6, again.count)
        assertEquals(t0 + 10_000 + 15 * min, again.lockedUntil)
    }

    @Test
    fun `a rolled window keeps an old lock until it ends`() {
        val locked = failN(5, window = 1, lock = 30)!!
        val later = ThrottlePolicy.fail(locked, t0 + 5 * min, 5, 1, 30)!!
        assertEquals(1, later.count)
        assertEquals(locked.lockedUntil, later.lockedUntil)
    }

    @Test
    fun `threshold zero disables`() {
        assertNull(ThrottlePolicy.fail(null, t0, 0, 10, 10))
        val s = ThrottleState(3, t0, null)
        assertSame(s, ThrottlePolicy.fail(s, t0 + 1, 0, 10, 10))
        assertTrue(ThrottlePolicy.codeSubjects("1.2.3.4", "u:1", 0).isEmpty())
    }

    @Test
    fun `retry after is rounded up and at least one`() {
        assertEquals(1, ThrottlePolicy.retryAfterSeconds(t0 + 1, t0))
        assertEquals(1, ThrottlePolicy.retryAfterSeconds(t0 + 1000, t0))
        assertEquals(2, ThrottlePolicy.retryAfterSeconds(t0 + 1001, t0))
        assertEquals(1, ThrottlePolicy.retryAfterSeconds(t0 - 5, t0))
        assertEquals(900, ThrottlePolicy.retryAfterSeconds(t0 + 15 * min, t0))
    }

    @Test
    fun `subjects of a code request`() {
        val both = ThrottlePolicy.codeSubjects("203.0.113.5", "u:7", 10)
        assertEquals(listOf(ThrottleSubject("ip:203.0.113.5", 10), ThrottleSubject("b:u:7", 10)), both)
        assertEquals(listOf(ThrottleSubject("b:g:steve", 10)), ThrottlePolicy.codeSubjects(null, "g:steve", 10))
        assertEquals(listOf(ThrottleSubject("ip:1.1.1.1", 10)), ThrottlePolicy.codeSubjects("1.1.1.1", null, 10))
        assertEquals(listOf(ThrottleSubject("ip:1.1.1.1", 10)), ThrottlePolicy.codeSubjects("1.1.1.1", "", 10))
        assertEquals(listOf(ThrottleSubject("anon", 100)), ThrottlePolicy.codeSubjects(null, null, 10))
    }

    @Test
    fun `subject is truncated to 191`() {
        val s = ThrottlePolicy.codeSubjects(null, "x".repeat(500), 3).single().subject
        assertEquals(191, s.length)
        assertTrue(s.startsWith("b:xxx"))
    }

    @Test
    fun `housekeeping expiry`() {
        val day = 86_400_000L
        assertTrue(ThrottlePolicy.expired(ThrottleState(1, t0 - 2 * day - 1, null), t0))
        assertFalse(ThrottlePolicy.expired(ThrottleState(1, t0 - 2 * day, null), t0))
        assertFalse(ThrottlePolicy.expired(ThrottleState(1, t0 - 5 * day, t0 + 1), t0))
        assertTrue(ThrottlePolicy.expired(ThrottleState(1, t0 - 5 * day, t0), t0))
    }
}

class AbuseLimitsTest {
    @Test
    fun `constants of section 11`() {
        assertEquals(20, AbuseLimits.MAX_ORDERS_PER_IP_PER_HOUR)
        assertEquals(3, AbuseLimits.MAX_OPEN_ORDERS)
        assertEquals(1000, AbuseLimits.MAX_LINE_QUANTITY)
        assertEquals(50, AbuseLimits.MAX_CART_LINES)
        assertEquals(12, AbuseLimits.GENERATED_CODE_LENGTH)
        assertEquals(32, AbuseLimits.GENERATED_CODE_ALPHABET.length)
    }

    @Test
    fun `refill and held units`() {
        assertNull(AbuseLimits.refillMs(0))
        assertEquals(1000L, AbuseLimits.refillMs(60))
        assertEquals(6000L, AbuseLimits.refillMs(10))
        assertEquals(10, AbuseLimits.heldUnitsCap(null))
        assertEquals(10, AbuseLimits.heldUnitsCap(50))
        assertEquals(4, AbuseLimits.heldUnitsCap(4))
    }

    @Test
    fun `setting ranges`() {
        assertTrue(0 in AbuseLimits.CHECKOUT_RATE_RANGE && 100_000 in AbuseLimits.CHECKOUT_RATE_RANGE && 100_001 !in AbuseLimits.CHECKOUT_RATE_RANGE)
        assertTrue(0 !in AbuseLimits.QUOTE_RATE_RANGE && 1 in AbuseLimits.QUOTE_RATE_RANGE)
        assertTrue(0 in AbuseLimits.COUPON_LOCK_THRESHOLD_RANGE && 101 !in AbuseLimits.COUPON_LOCK_THRESHOLD_RANGE)
        assertTrue(1 in AbuseLimits.COUPON_LOCK_MINUTES_RANGE && 1440 in AbuseLimits.COUPON_LOCK_MINUTES_RANGE && 1441 !in AbuseLimits.COUPON_LOCK_MINUTES_RANGE)
    }
}
