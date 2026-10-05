package com.panomc.plugins.market.runtime

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The state holder of 00 section 8.9 without a database: initial value, transitions, host capability probes. */
class MarketRuntimeTest {
    @BeforeEach
    @AfterEach
    fun reset() {
        MarketRuntime.reset()
    }

    @Test
    fun `the initial state is STOPPED and nothing is ready`() {
        assertEquals(MarketRuntime.State.STOPPED, MarketRuntime.state)
        assertFalse(MarketRuntime.isReady)
        val health = MarketRuntime.health()
        assertEquals(MarketRuntime.State.STOPPED, health.state)
        assertTrue(health.problems.isEmpty() && health.unfixed.isEmpty() && health.bootstrapErrors.isEmpty())
    }

    @Test
    fun `starting then finish gives READY or DEGRADED and stopped always wins`() {
        MarketRuntime.starting()
        assertEquals(MarketRuntime.State.STARTING, MarketRuntime.state)
        assertFalse(MarketRuntime.isReady)

        MarketRuntime.finish(null, listOf("x"), degraded = false)
        assertEquals(MarketRuntime.State.READY, MarketRuntime.state)
        assertTrue(MarketRuntime.isReady)
        assertEquals(listOf("x"), MarketRuntime.health().bootstrapErrors)

        MarketRuntime.stopped()
        assertEquals(MarketRuntime.State.STOPPED, MarketRuntime.state)

        MarketRuntime.finish(null, emptyList(), degraded = true)
        assertEquals(MarketRuntime.State.DEGRADED, MarketRuntime.state)
        assertFalse(MarketRuntime.isReady)

        MarketRuntime.stopped()
        MarketRuntime.resume(degraded = false)
        assertEquals(MarketRuntime.State.READY, MarketRuntime.state)
    }

    @Test
    fun `a host without the optional classes is probed as absent and mail reports HOST_TOO_OLD`() {
        val empty = object : ClassLoader(null) {}
        val caps = MarketRuntime.probeHostCapabilities(empty)
        assertFalse(caps.mail)
        assertFalse(caps.notifications)
        assertEquals("HOST_TOO_OLD", caps.mailStatus)
        assertEquals(caps, MarketRuntime.capabilities)
        assertEquals(caps, MarketRuntime.health().capabilities)
    }

    @Test
    fun `the probe agrees with Class forName on the host classpath`() {
        val loader = MarketRuntimeTest::class.java.classLoader
        fun exists(name: String) = try {
            Class.forName(name, false, loader); true
        } catch (e: ClassNotFoundException) {
            false
        }

        val caps = MarketRuntime.probeHostCapabilities(loader)
        assertEquals(exists(MarketRuntime.MAIL_OPTIONS_CLASS), caps.mail)
        assertEquals(exists(MarketRuntime.NOTIFICATION_REGISTRY_CLASS), caps.notifications)
        assertEquals(if (caps.mail) "OK" else "HOST_TOO_OLD", caps.mailStatus)
    }
}
