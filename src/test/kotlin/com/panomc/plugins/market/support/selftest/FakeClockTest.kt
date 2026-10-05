package com.panomc.plugins.market.support.selftest

import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.support.FakeClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class FakeClockTest {
    @Test
    fun `starts at 2025-10-09T08-53-20Z`() {
        assertEquals(1_760_000_000_000L, FakeClock().now())
        assertEquals(Instant.parse("2025-10-09T08:53:20Z"), Instant.ofEpochMilli(FakeClock().now()))
    }

    @Test
    fun `time stands still until advanced`() {
        val c = FakeClock()
        val first = c.now()
        Thread.sleep(20)
        assertEquals(first, c.now())
        assertEquals(first + 1_500, c.advance(1_500))
        assertEquals(first + 1_500, c.now())
    }

    @Test
    fun `set and nowMs assignment replace the time`() {
        val c = FakeClock()
        c.set(42)
        assertEquals(42L, c.now())
        c.nowMs = 7
        assertEquals(7L, c.now())
        assertEquals(7L, c.nowMs)
    }

    @Test
    fun `is usable as a Clock and honours a custom start`() {
        val c: Clock = FakeClock(100)
        assertEquals(100L, c.now())
    }

    @Test
    fun `concurrent advances add up`() {
        val c = FakeClock(0)
        val threads = List(8) { Thread { repeat(1_000) { c.advance(1) } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(8_000L, c.now())
    }
}
