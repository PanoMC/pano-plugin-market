package com.panomc.plugins.market.mc.core.sync

import com.panomc.plugins.market.mc.core.platform.SingleThreadScheduler
import com.panomc.plugins.market.mc.core.platform.SystemMcClock
import com.panomc.plugins.market.mc.core.support.FakeMcPlatform
import com.panomc.plugins.market.mc.core.support.FakeTransport
import com.panomc.plugins.market.mc.core.support.RecordingCallbacks
import com.panomc.plugins.market.mc.core.support.TestLog
import com.panomc.plugins.market.mc.core.support.TestSettings
import com.panomc.plugins.market.mc.core.support.delivery
import com.panomc.plugins.market.mc.core.support.response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** The real thing: one daemon engine thread, real files, real clock, a fake transport answered from the test thread. */
class DeliveryRuntimeTest {
    @TempDir
    lateinit var dir: Path

    private val log = TestLog()

    private class Rig(dir: Path, val log: TestLog) {
        val platform = FakeMcPlatform()
        val transport = FakeTransport()
        val callbacks = RecordingCallbacks()
        val runtime = DeliveryRuntime(
            dir, platform, TestSettings(), transport, callbacks, log, "1.4.0", SystemMcClock,
            SingleThreadScheduler("PanoMarket-test", log)
        )
    }

    private fun await(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < end) {
            if (condition()) return
            Thread.sleep(5)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    @Test
    fun `end to end on the real thread, files survive a restart and the key is never run twice`() {
        val first = Rig(dir, log)
        first.platform.join("Steve")
        first.runtime.start()
        first.runtime.connectionChanged(true)
        await("first request") { first.transport.sent >= 1 }
        assertEquals(20, first.transport.requests[0].capacity)
        assertTrue(first.runtime.status().connected)

        first.transport.respond(0, response(deliveries = listOf(delivery("k1", 1, commands = listOf("give Steve diamond 1")))))
        await("command dispatched") { first.platform.console.size == 1 }
        // The executed delivery is a local change: the loop syncs again at once and reports it, long before 5 s.
        await("result reported", 2_000) { first.transport.sent >= 2 }
        val report = first.transport.requests[1].results.single()
        assertEquals("k1", report.key)
        assertEquals("DONE", report.status)
        first.transport.respond(1, response(acked = listOf("k1")))
        await("ack applied") { first.runtime.status().engine.unacked == 0 }
        first.runtime.stop()
        assertFalse(first.runtime.status().started)

        // Clean shutdown compacted the journal into the snapshot.
        assertEquals(0L, Files.size(dir.resolve("state/journal.log")))
        assertTrue(Files.exists(dir.resolve("state/snapshot.json")))

        val second = Rig(dir, log)
        second.platform.join("Steve")
        second.runtime.start()
        second.runtime.connectionChanged(true)
        await("request after restart") { second.transport.sent >= 1 }
        assertTrue(second.transport.requests[0].results.isEmpty(), "the acknowledged result is not reported again")
        second.transport.respond(0, response(deliveries = listOf(delivery("k1", 1, commands = listOf("give Steve diamond 1")))))
        await("key re-offered is noticed") { second.transport.sent >= 2 }
        assertTrue(second.platform.console.isEmpty(), "the same key is never dispatched twice, even after a restart")
        assertEquals("DONE", second.transport.requests[1].results.single().status, "its stored result is reported again")
        second.runtime.stop()
    }

    @Test
    fun `a queued delivery runs when the player joins`() {
        val rig = Rig(dir, log)
        rig.runtime.start()
        rig.runtime.connectionChanged(true)
        await("first request") { rig.transport.sent >= 1 }
        rig.transport.respond(0, response(deliveries = listOf(delivery("k1", 1, requiresOnline = true, commands = listOf("say hello")))))
        await("queued report") { rig.transport.sent >= 2 && rig.transport.requests[1].results.any { it.status == "QUEUED" } }
        assertTrue(rig.platform.console.isEmpty())
        assertEquals(1, rig.runtime.status().engine.queued)

        rig.platform.join("Steve")
        rig.runtime.playerPresent("Steve")
        await("command dispatched on join") { rig.platform.console == listOf("say hello") }
        await("drain callback") { rig.callbacks.drains.size == 1 }
        rig.runtime.stop()
    }

    @Test
    fun `a corrupt store puts the runtime into recovery mode until confirmed`() {
        val seed = Rig(dir, log)
        seed.platform.join("Steve")
        seed.runtime.start()
        seed.runtime.connectionChanged(true)
        await("request") { seed.transport.sent >= 1 }
        seed.transport.respond(0, response(deliveries = listOf(delivery("k1", 1))))
        await("ran") { seed.platform.console.size == 1 }
        seed.runtime.stop()
        Files.write(dir.resolve("state/snapshot.json"), "{not json".toByteArray())

        val rig = Rig(dir, log)
        rig.runtime.start()
        rig.runtime.connectionChanged(true)
        await("request in recovery") { rig.transport.sent >= 1 }
        assertEquals(0, rig.transport.requests[0].capacity)
        val preview = rig.runtime.recoveryPreview().get(5, TimeUnit.SECONDS)
        assertNotNull(preview)
        assertTrue(rig.runtime.status().engine.recoveryMode)
        assertTrue(rig.runtime.confirmRecovery().get(5, TimeUnit.SECONDS))
        assertNull(rig.runtime.recoveryPreview().get(5, TimeUnit.SECONDS))
        assertFalse(rig.runtime.status().engine.recoveryMode)
        rig.runtime.stop()
    }

    @Test
    fun `a data folder that cannot be opened leaves the runtime idle and says so`() {
        val file = dir.resolve("not-a-directory")
        Files.write(file, "x".toByteArray())
        val rig = Rig(file, log)
        rig.runtime.start()
        rig.runtime.connectionChanged(true)
        await("failure noticed") { rig.runtime.status().failed }
        assertEquals(0, rig.transport.sent)
        assertTrue(log.has("stays idle"))
        rig.runtime.stop()
    }

    @Test
    fun `connection changes before start are not lost`() {
        val rig = Rig(dir, log)
        rig.runtime.connectionChanged(true)
        rig.runtime.start()
        await("request") { rig.transport.sent >= 1 }
        rig.runtime.stop()
    }
}
