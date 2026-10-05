package com.panomc.plugins.market.mc.link

import com.panomc.plugins.market.mc.core.link.CommandHop
import com.panomc.plugins.market.mc.core.link.CommandHopTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class CommandHopTest {
    private val pool = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }

    @Test
    fun `the body runs on the platform thread and the result comes back`() {
        val ran = AtomicInteger()
        var thread = ""
        val r = CommandHop.call(2_000, { pool.execute(it) }) {
            ran.incrementAndGet()
            thread = Thread.currentThread().name
            true
        }
        assertTrue(r)
        assertEquals(1, ran.get())
        assertEquals("fake-main", thread)
        assertNotEquals(Thread.currentThread().name, thread)
        pool.shutdownNow()
    }

    @Test
    fun `an exception of the body reaches the caller unchanged`() {
        val e = assertThrows(IllegalStateException::class.java) {
            CommandHop.call(2_000, { pool.execute(it) }) { throw IllegalStateException("boom") }
        }
        assertEquals("boom", e.message)
        pool.shutdownNow()
    }

    @Test
    fun `a scheduler that refuses the task is an error and nothing ran`() {
        val ran = AtomicInteger()
        assertThrows(IllegalStateException::class.java) {
            CommandHop.call(2_000, { throw IllegalStateException("plugin disabled") }) { ran.incrementAndGet() }
        }
        assertEquals(0, ran.get())
    }

    @Test
    fun `a body that never gets a thread is cancelled and does NOT run later`() {
        val ran = AtomicInteger()
        val queue = ArrayList<Runnable>()
        val e = assertThrows(CommandHopTimeout::class.java) {
            CommandHop.call(60, { queue.add(it) }) { ran.incrementAndGet() }
        }
        assertFalse(e.started)
        assertTrue(e.message!!.contains("not executed"))
        // The server thread comes back later and finds the cancelled task: it must do nothing.
        queue.forEach { it.run() }
        assertEquals(0, ran.get(), "a command reported as not executed must never run afterwards")
    }

    @Test
    fun `a body that started but is slow reports that it may still complete`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ran = AtomicInteger()
        val e = assertThrows(CommandHopTimeout::class.java) {
            CommandHop.call(60, { pool.execute(it) }) {
                ran.incrementAndGet()
                started.countDown()
                release.await()
                true
            }
        }
        assertTrue(e.started)
        assertTrue(e.message!!.contains("may still complete"))
        assertEquals(1, ran.get())
        release.countDown()
        pool.shutdown()
    }

    @Test
    fun `a slow body that finishes inside the second window still returns its result`() {
        val r = CommandHop.call(150, { pool.execute(it) }) {
            Thread.sleep(220)
            "late"
        }
        assertEquals("late", r)
        pool.shutdownNow()
    }
}
