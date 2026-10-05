package com.panomc.plugins.market.util

import com.panomc.plugins.market.core.abuse.AbuseLimits
import com.panomc.plugins.market.core.abuse.IpRange

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

import com.panomc.platform.util.RateLimiter

/** L1 style limits on the platform RateLimiter, with the budgets of AbuseLimits; keys are independent. */
class RateLimiterTest {
    @Test
    fun `burst then refused`() {
        val l = RateLimiter(5, AbuseLimits.refillMs(5)!!)
        repeat(5) { assertTrue(l.tryAcquire("ip:1.1.1.1")) }
        assertFalse(l.tryAcquire("ip:1.1.1.1"))
        assertTrue(l.retryAfterSeconds("ip:1.1.1.1") >= 1)
    }

    @Test
    fun `ip and buyer keys are independent`() {
        val l = RateLimiter(2, 60_000L)
        assertTrue(l.tryAcquire("ip:1.1.1.1"))
        assertTrue(l.tryAcquire("ip:1.1.1.1"))
        assertFalse(l.tryAcquire("ip:1.1.1.1"))
        assertTrue(l.tryAcquire("b:u:7"))
        assertTrue(l.tryAcquire("ip:2.2.2.2"))
        assertTrue(l.tryAcquire("b:g:steve"))
    }

    @Test
    fun `ipv6 addresses of one 64 share a bucket`() {
        val l = RateLimiter(1, 60_000L)
        assertTrue(l.tryAcquire("ip:" + IpRange.bucketKey("2001:db8:1:2::1")))
        assertFalse(l.tryAcquire("ip:" + IpRange.bucketKey("2001:db8:1:2::ffff")))
        assertTrue(l.tryAcquire("ip:" + IpRange.bucketKey("2001:db8:1:3::1")))
    }

    @Test
    fun `refill interval comes from the per minute budget`() {
        assertEquals(60_000L / 30, AbuseLimits.refillMs(AbuseLimits.ORDER_TOKEN_MISS_PER_MINUTE))
        assertEquals(500L, AbuseLimits.refillMs(AbuseLimits.ORDER_STATUS_PER_MINUTE))
        assertEquals(10_000L, AbuseLimits.refillMs(AbuseLimits.EXPORT_PER_MINUTE))
    }
}
