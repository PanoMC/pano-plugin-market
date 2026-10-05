package com.panomc.plugins.market.support.selftest

import com.panomc.plugins.market.support.Await
import com.panomc.plugins.market.support.AwaitTimeout
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger

class AwaitSelfTest {
    @Test
    fun `returns as soon as the condition holds`() {
        val calls = AtomicInteger()
        Await.until(timeoutMs = 5_000, stepMs = 5) { calls.incrementAndGet() >= 3 }
        assertEquals(3, calls.get())
    }

    @Test
    fun `times out with description and last value`() {
        val e = assertThrows<AwaitTimeout> {
            Await.untilValue(timeoutMs = 100, stepMs = 10, description = "never") { error("still failing") }
        }
        assertEquals("never", e.description)
        assertTrue(e.lastValue.toString().contains("still failing"), e.message)
    }

    @Test
    fun `evaluates once even with a zero timeout`() {
        val calls = AtomicInteger()
        Await.until(timeoutMs = 0) { calls.incrementAndGet() > 0 }
        assertEquals(1, calls.get())
    }

    @Test
    fun `untilValue returns the value`() {
        val calls = AtomicInteger()
        assertEquals("ready", Await.untilValue(1_000, 5) { if (calls.incrementAndGet() > 2) "ready" else null })
    }

    @Test
    fun `suspending variant polls and times out`() = runBlocking {
        val calls = AtomicInteger()
        Await.untilSuspending(2_000, 5) { calls.incrementAndGet() >= 4 }
        assertEquals(4, calls.get())
        assertThrows<AwaitTimeout> { Await.untilSuspending(50, 10, "nope") { false } }
        Unit
    }
}
