package com.panomc.plugins.market.mc.core.sync

import com.panomc.plugins.market.mc.core.support.EngineHarness
import com.panomc.plugins.market.mc.core.support.FakeTransport
import com.panomc.plugins.market.mc.core.support.ManualScheduler
import com.panomc.plugins.market.mc.core.support.delivery
import com.panomc.plugins.market.mc.core.support.response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** The loop of 19 section 6.1 on virtual time. */
class SyncLoopTest {
    @TempDir
    lateinit var dir: Path

    private class Rig(dir: Path) {
        val h = EngineHarness(dir)
        val scheduler = ManualScheduler(h.clock)
        val transport = FakeTransport()
        val loop = SyncLoop(h.engine, transport, scheduler, h.log)

        init {
            h.callbacks.hook = { loop.wake() }
            loop.start()
            scheduler.runUntilIdle()
        }

        fun connect() {
            loop.connectionChanged(true)
            scheduler.runUntilIdle()
        }

        fun answer(index: Int, r: com.panomc.plugins.market.mc.core.wire.MarketSyncMessage? = response()) {
            transport.respond(index, r)
            scheduler.runUntilIdle()
        }
    }

    @Test
    fun `nothing is sent while disconnected, the first sync is immediate on connect`() {
        val r = Rig(dir)
        r.scheduler.advance(60_000)
        assertEquals(0, r.transport.sent)
        r.connect()
        assertEquals(1, r.transport.sent)
        assertEquals("1.4.0", r.transport.requests[0].componentVersion)
    }

    @Test
    fun `one request at a time, then pollAfterMs of waiting`() {
        val r = Rig(dir)
        r.connect()
        r.scheduler.advance(10_000)
        assertEquals(1, r.transport.sent, "no second request while the first is unanswered")

        r.answer(0, response(pollAfterMs = 5000))
        assertEquals(1, r.transport.sent)
        r.scheduler.advance(4_999)
        assertEquals(1, r.transport.sent)
        r.scheduler.advance(1)
        assertEquals(2, r.transport.sent)
    }

    @Test
    fun `pollAfterMs 0 means sync again at once`() {
        val r = Rig(dir)
        r.connect()
        r.answer(0, response(pollAfterMs = 0))
        assertEquals(2, r.transport.sent)
        r.answer(1, response(pollAfterMs = 0))
        assertEquals(3, r.transport.sent)
        r.answer(2, response(pollAfterMs = 5000))
        assertEquals(3, r.transport.sent)
    }

    @Test
    fun `a server that always answers 0 is slowed down after 20 in a row`() {
        val r = Rig(dir)
        r.connect()
        for (i in 0..19) r.answer(i, response(pollAfterMs = 0))
        assertEquals(21, r.transport.sent)
        r.answer(20, response(pollAfterMs = 0))
        assertEquals(21, r.transport.sent, "the 21st zero answer waits a second")
        r.scheduler.advance(999)
        assertEquals(21, r.transport.sent)
        r.scheduler.advance(1)
        assertEquals(22, r.transport.sent)
        r.answer(21, response(pollAfterMs = 5000))
        r.scheduler.advance(5000)
        r.answer(22, response(pollAfterMs = 0))
        assertEquals(24, r.transport.sent, "a normal answer resets the counter")
    }

    @Test
    fun `a local state change wakes the loop early and an executed delivery is reported at once`() {
        val r = Rig(dir)
        r.h.platform.join("Steve")
        r.connect()
        r.answer(0, response(pollAfterMs = 5000, deliveries = listOf(delivery("k1", 1))))
        // The delivery ran while applying: the loop syncs again without waiting 5 s, and reports the result.
        assertEquals(2, r.transport.sent)
        assertEquals(listOf("k1" to "DONE"), r.transport.requests[1].results.map { it.key to it.status })
        r.answer(1, response(acked = listOf("k1"), pollAfterMs = 5000))
        assertEquals(2, r.transport.sent, "an acknowledgement is not a local change")
        assertTrue(r.h.request().results.isEmpty())

        r.loop.wake()
        r.scheduler.runUntilIdle()
        assertEquals(3, r.transport.sent, "wake() syncs now")
    }

    @Test
    fun `a wake while a request is in flight does not start a second one but syncs right after`() {
        val r = Rig(dir)
        r.connect()
        r.loop.wake()
        r.loop.wake()
        r.scheduler.runUntilIdle()
        assertEquals(1, r.transport.sent)
        r.answer(0, response(pollAfterMs = 5000))
        assertEquals(2, r.transport.sent)
        r.answer(1, response(pollAfterMs = 5000))
        assertEquals(2, r.transport.sent)
        r.loop.wake()
        r.loop.wake()
        r.loop.wake()
        r.scheduler.runUntilIdle()
        assertEquals(3, r.transport.sent, "a burst of wakes is one sync")
    }

    @Test
    fun `a timeout is unknown, not failed, nothing is applied and it is retried after 5 seconds`() {
        val r = Rig(dir)
        r.h.platform.join("Steve")
        r.connect()
        r.answer(0, response(deliveries = listOf(delivery("k1", 1))))
        assertEquals(2, r.transport.sent, "the result of k1 is reported at once")
        r.answer(1, null) // that request times out
        r.scheduler.advance(4_999)
        assertEquals(2, r.transport.sent)
        r.scheduler.advance(1)
        assertEquals(3, r.transport.sent)
        assertEquals("DONE", r.transport.requests[2].results.single { it.key == "k1" }.status, "still reported after the timeout")
    }

    @Test
    fun `a refused response is polled again after 30 seconds`() {
        val r = Rig(dir)
        r.connect()
        r.answer(0, response(accepted = false, reason = "VERSION_MISMATCH", marketVersion = "9.9.9", pollAfterMs = 0))
        r.scheduler.advance(29_999)
        assertEquals(1, r.transport.sent)
        r.scheduler.advance(1)
        assertEquals(2, r.transport.sent)
    }

    @Test
    fun `losing the connection pauses the loop and the queue stays, regaining it syncs at once`() {
        val r = Rig(dir)
        r.connect()
        r.answer(0, response(pollAfterMs = 5000))
        r.loop.connectionChanged(false)
        r.scheduler.advance(60_000)
        assertEquals(1, r.transport.sent)
        r.loop.wake()
        r.scheduler.runUntilIdle()
        assertEquals(1, r.transport.sent, "no wake while disconnected")
        r.connect()
        assertEquals(2, r.transport.sent)
    }

    @Test
    fun `a response that arrives after the connection dropped is still applied`() {
        val r = Rig(dir)
        r.h.platform.join("Steve")
        r.connect()
        r.loop.connectionChanged(false)
        r.scheduler.runUntilIdle()
        r.answer(0, response(deliveries = listOf(delivery("k1", 1))))
        assertEquals(1, r.h.platform.console.size)
        r.scheduler.advance(60_000)
        assertEquals(1, r.transport.sent, "but nothing is sent while disconnected")
        r.connect()
        assertEquals(2, r.transport.sent)
        assertEquals("DONE", r.transport.requests[1].results.single().status)
    }

    @Test
    fun `stop ends the loop`() {
        val r = Rig(dir)
        r.connect()
        r.answer(0, response(pollAfterMs = 0))
        assertEquals(2, r.transport.sent)
        r.loop.stop()
        r.scheduler.runUntilIdle()
        r.answer(1, response(pollAfterMs = 0))
        r.scheduler.advance(120_000)
        assertEquals(2, r.transport.sent)
    }

    @Test
    fun `a request that never completes is abandoned by the watchdog and a late answer is ignored`() {
        val r = Rig(dir)
        r.h.platform.join("Steve")
        r.connect()
        r.scheduler.advance(30_000)
        assertTrue(r.h.log.has("no answer"))
        r.scheduler.advance(5_000)
        assertEquals(2, r.transport.sent)
        r.answer(0, response(deliveries = listOf(delivery("late", 1)))) // belongs to the abandoned request
        assertTrue(r.h.platform.console.isEmpty(), "ignored: Pano will offer it again")
        r.answer(1, response(pollAfterMs = 5000))
        assertEquals(2, r.transport.sent)
    }

    @Test
    fun `a transport that throws is logged and retried`() {
        val r = Rig(dir)
        r.transport.failOnSend = IllegalStateException("socket closed")
        r.connect()
        assertTrue(r.h.log.has("Sending the Market sync request failed"))
        assertEquals(0, r.transport.sent)
        r.transport.failOnSend = null
        r.scheduler.advance(5_000)
        assertEquals(1, r.transport.sent)
    }
}
