package com.panomc.plugins.market.routes.api.checkout

import com.panomc.platform.util.RateLimiter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The quote limiter key (11 section 2 rule 4): IPv6 by its `/64`, IPv4 per address. */
class QuoteApiLimiterKeyTest {
    @Test
    fun `two addresses of one slash 64 share a key and another prefix does not`() {
        val a = QuoteAPI.limiterKey("2001:db8:1:2::1")
        val b = QuoteAPI.limiterKey("2001:db8:1:2:ffff:ffff:ffff:ffff")

        assertEquals("2001:db8:1:2::/64", a)
        assertEquals(a, b)
        assertNotEquals(a, QuoteAPI.limiterKey("2001:db8:1:3::1"))
    }

    @Test
    fun `an IPv4 address keeps its own key`() {
        assertEquals("203.0.113.7", QuoteAPI.limiterKey("203.0.113.7"))
        assertNotEquals(QuoteAPI.limiterKey("203.0.113.7"), QuoteAPI.limiterKey("203.0.113.8"))
    }

    @Test
    fun `an unusable address has no key`() {
        assertNull(QuoteAPI.limiterKey(null))
        assertNull(QuoteAPI.limiterKey("not an address"))
    }

    @Test
    fun `rotating through one slash 64 drains a single bucket`() {
        val limiter = RateLimiter(2, 60_000L)

        assertTrue(limiter.tryAcquire(QuoteAPI.limiterKey("2001:db8:1:2::1")!!))
        assertTrue(limiter.tryAcquire(QuoteAPI.limiterKey("2001:db8:1:2::2")!!))
        assertFalse(limiter.tryAcquire(QuoteAPI.limiterKey("2001:db8:1:2::3")!!), "a fresh address of the same prefix gets no fresh bucket")
        assertTrue(limiter.tryAcquire(QuoteAPI.limiterKey("203.0.113.7")!!), "an IPv4 client is independent")
    }
}
