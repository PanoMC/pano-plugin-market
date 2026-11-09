package com.panomc.plugins.market.service

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.error.TooManyRequests
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Callers without a trusted address and without a session share one bucket: a rotating guest name never escapes the limits. */
class MarketRateLimitsAnonTest {
    @Test
    fun `quotes without a trusted address or a session share one bucket of ten times the limit`() {
        val limits = MarketRateLimits { MarketConfig(quoteRateLimitPerMinute = 2) }
        repeat(20) { limits.quote(null, null) }
        assertThrows(TooManyRequests::class.java) { limits.quote(null, null) }
        // a session keeps its own bucket
        limits.quote(null, 7)
    }

    @Test
    fun `checkouts without a trusted address or a session share one bucket, sessions are not counted in it`() {
        val limits = MarketRateLimits { MarketConfig(checkoutRateLimitPerMinute = 1) }
        repeat(30) { limits.checkoutIp(null, anonymous = false) }
        repeat(10) { limits.checkoutIp(null, anonymous = true) }
        assertThrows(TooManyRequests::class.java) { limits.checkoutIp(null, anonymous = true) }
    }
}
