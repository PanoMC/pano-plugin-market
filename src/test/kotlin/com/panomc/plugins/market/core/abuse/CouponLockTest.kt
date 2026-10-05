package com.panomc.plugins.market.core.abuse

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

import com.panomc.plugins.market.support.FakeClock

/** The code lock of 12.2 built from ThrottlePolicy and a FakeClock (in-memory stand-in for market_throttle). */
class CouponLockTest {
    private val clock = FakeClock()
    private val rows = HashMap<String, ThrottleState>()
    private val threshold = 3
    private val minutes = 15

    private fun fail(scope: String, ip: String?, buyer: String?) {
        for (s in ThrottlePolicy.codeSubjects(ip, buyer, threshold)) {
            val k = "$scope|${s.subject}"
            ThrottlePolicy.fail(rows[k], clock.now(), s.threshold, minutes, minutes)?.let { rows[k] = it }
        }
    }

    private fun locked(scope: String, ip: String?, buyer: String?): Long? =
        ThrottlePolicy.codeSubjects(ip, buyer, threshold).mapNotNull { ThrottlePolicy.lockedUntil(rows["$scope|${it.subject}"], clock.now()) }.maxOrNull()

    @Test
    fun `locks after threshold failures and unlocks after the lock minutes`() {
        repeat(2) { fail("COUPON", "1.1.1.1", "u:1") }
        assertNull(locked("COUPON", "1.1.1.1", "u:1"))
        fail("COUPON", "1.1.1.1", "u:1")
        val until = locked("COUPON", "1.1.1.1", "u:1")
        assertNotNull(until)
        assertEquals(clock.now() + minutes * 60_000L, until)
        clock.advance(minutes * 60_000L - 1)
        assertNotNull(locked("COUPON", "1.1.1.1", "u:1"))
        clock.advance(1)
        assertNull(locked("COUPON", "1.1.1.1", "u:1"))
    }

    @Test
    fun `lock follows the account across ips and the ip across accounts`() {
        repeat(3) { fail("COUPON", "1.1.1.1", "u:1") }
        assertNotNull(locked("COUPON", "9.9.9.9", "u:1"))
        assertNotNull(locked("COUPON", "1.1.1.1", "u:2"))
        assertNull(locked("COUPON", "9.9.9.9", "u:2"))
    }

    @Test
    fun `scopes are independent`() {
        repeat(3) { fail("COUPON", "1.1.1.1", "u:1") }
        assertNull(locked("GIFT", "1.1.1.1", "u:1"))
    }

    @Test
    fun `failures spread over more than the window never lock`() {
        repeat(10) {
            fail("COUPON", null, "u:1")
            clock.advance(minutes * 60_000L)
        }
        assertNull(locked("COUPON", null, "u:1"))
    }

    @Test
    fun `anonymous caller uses the anon subject with ten times the threshold`() {
        repeat(29) { fail("COUPON", null, null) }
        assertNull(locked("COUPON", null, null))
        fail("COUPON", null, null)
        assertNotNull(locked("COUPON", null, null))
        assertNull(locked("COUPON", null, "u:5"))
    }
}
