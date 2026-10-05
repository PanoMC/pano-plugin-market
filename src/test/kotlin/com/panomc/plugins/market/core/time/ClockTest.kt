package com.panomc.plugins.market.core.time

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClockTest {
    @Test
    fun `system clock follows the wall clock`() {
        val before = System.currentTimeMillis()
        val now = SystemClock.now()
        val after = System.currentTimeMillis()
        assertTrue(now in before..after, "$now not in $before..$after")
    }
}
