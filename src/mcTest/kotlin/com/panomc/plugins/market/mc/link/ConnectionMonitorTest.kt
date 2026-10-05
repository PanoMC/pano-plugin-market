package com.panomc.plugins.market.mc.link

import com.panomc.plugins.market.mc.core.link.ConnectionMonitor
import com.panomc.plugins.market.mc.core.support.ManualScheduler
import com.panomc.plugins.market.mc.core.support.TestClock
import com.panomc.plugins.market.mc.core.support.TestLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectionMonitorTest {
    private val clock = TestClock()
    private val scheduler = ManualScheduler(clock)
    private val log = TestLog()
    private val changes = ArrayList<Boolean>()
    private var probeValue = false
    private var probeError: Throwable? = null
    private var probes = 0

    private fun monitor() = ConnectionMonitor({
        probes++
        probeError?.let { throw it }
        probeValue
    }, scheduler, log, { changes.add(it) })

    @Test
    fun `starts from not connected and reports no change while the probe stays false`() {
        val m = monitor()
        m.start()
        scheduler.advance(5_000)
        assertTrue(changes.isEmpty())
        assertFalse(m.connected)
        assertEquals(6, probes, "once at start and then every second")
    }

    @Test
    fun `false to true is reported once at the next second, true to false likewise`() {
        val m = monitor()
        m.start()
        scheduler.advance(1_000)
        probeValue = true
        scheduler.advance(999)
        assertTrue(changes.isEmpty(), "nothing before the next probe")
        scheduler.advance(1)
        assertEquals(listOf(true), changes)
        assertTrue(m.connected)
        scheduler.advance(3_000)
        assertEquals(listOf(true), changes, "no repeat while it stays connected")
        probeValue = false
        scheduler.advance(1_000)
        assertEquals(listOf(true, false), changes)
        assertFalse(m.connected)
    }

    @Test
    fun `already connected at start is a false to true change on the first probe`() {
        probeValue = true
        monitor().start()
        scheduler.runUntilIdle()
        assertEquals(listOf(true), changes)
    }

    @Test
    fun `a probe that throws counts as not connected and is logged once`() {
        probeValue = true
        val m = monitor()
        m.start()
        scheduler.runUntilIdle()
        assertEquals(listOf(true), changes)
        probeError = IllegalStateException("pano is reloading")
        scheduler.advance(1_000)
        scheduler.advance(1_000)
        scheduler.advance(1_000)
        assertEquals(listOf(true, false), changes)
        assertEquals(1, log.count("could not be checked"))
        probeError = null
        scheduler.advance(1_000)
        assertEquals(listOf(true, false, true), changes)
    }

    @Test
    fun `a failing change listener does not stop the monitor`() {
        probeValue = true
        val m = ConnectionMonitor({ probeValue }, scheduler, log, { throw IllegalStateException("listener") })
        m.start()
        scheduler.runUntilIdle()
        assertTrue(log.has("Reacting to the Pano connection change failed"))
        probeValue = false
        scheduler.advance(1_000)
        assertFalse(m.connected, "still probing")
    }

    @Test
    fun `stop ends the probing`() {
        val m = monitor()
        m.start()
        scheduler.advance(2_000)
        val before = probes
        m.stop()
        scheduler.advance(10_000)
        assertEquals(before, probes)
    }
}
