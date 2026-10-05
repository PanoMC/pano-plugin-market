package com.panomc.plugins.market.mc.link

import com.panomc.plugins.market.mc.core.link.PanoLink
import com.panomc.plugins.market.mc.core.link.PanoLinkTransport
import com.panomc.plugins.market.mc.core.support.TestLog
import com.panomc.plugins.market.mc.core.support.response
import com.panomc.plugins.market.mc.core.wire.MarketSyncMessage
import com.panomc.plugins.market.mc.core.wire.MarketSyncRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PanoLinkTransportTest {
    private val log = TestLog()

    private fun request() = MarketSyncRequest("1.4.0", 1, "PAPER", true, false, false, 0, 20, null, emptyList())

    private class Link(var handler: suspend (MarketSyncRequest, Long) -> MarketSyncMessage) : PanoLink {
        var up = true
        val seen = CopyOnWriteArrayList<MarketSyncRequest>()
        val timeouts = CopyOnWriteArrayList<Long>()
        override fun connected() = up
        override suspend fun awaitSync(request: MarketSyncRequest, timeoutMs: Long): MarketSyncMessage {
            seen.add(request)
            timeouts.add(timeoutMs)
            return handler(request, timeoutMs)
        }
    }

    private fun send(link: PanoLink, transport: PanoLinkTransport = PanoLinkTransport(link, log, 10_000)): Pair<List<MarketSyncMessage?>, Int> {
        val got = CopyOnWriteArrayList<MarketSyncMessage?>()
        val latch = CountDownLatch(1)
        val calls = AtomicInteger()
        transport.send(request()) { calls.incrementAndGet(); got.add(it); latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "callback never ran")
        Thread.sleep(50)
        transport.close()
        return got to calls.get()
    }

    @Test
    fun `the decoded answer reaches the callback once, with the 10 s request timeout`() {
        val answer = response()
        val link = Link { _, _ -> answer }
        val (got, calls) = send(link)
        assertEquals(1, calls)
        assertSame(answer, got.single())
        assertEquals(listOf(10_000L), link.timeouts)
        assertEquals(1, link.seen.size)
    }

    @Test
    fun `a link that throws means unknown - the callback gets null exactly once`() {
        val link = Link { _, _ -> throw IllegalStateException("Not connected to Pano Platform.") }
        val (got, calls) = send(link)
        assertEquals(1, calls)
        assertNull(got.single())
        assertTrue(log.has("treated as unknown"))
    }

    @Test
    fun `a link that never answers is cut off by the backstop and reported as unknown`() {
        val link = Link { _, _ -> CompletableDeferred<MarketSyncMessage>().await() }
        val (got, calls) = send(link, PanoLinkTransport(link, log, timeoutMs = 50, backstopMs = 100))
        assertEquals(1, calls)
        assertNull(got.single())
    }

    @Test
    fun `a slow answer inside the window still arrives`() {
        val answer = response(acked = listOf("k1"))
        val link = Link { _, _ -> delay(80); answer }
        val (got, _) = send(link)
        assertSame(answer, got.single())
    }

    @Test
    fun `a callback that throws is logged and does not break the transport`() {
        val link = Link { _, _ -> response() }
        val transport = PanoLinkTransport(link, log, 10_000)
        val done = CountDownLatch(1)
        transport.send(request()) { done.countDown(); throw IllegalStateException("bad callback") }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertTrue(log.has("The Market sync callback failed"))
        val (got, _) = send(link, transport)
        assertNotNull(got.single())
    }

    @Test
    fun `a closed transport still answers its callback`() {
        val link = Link { _, _ -> response() }
        val transport = PanoLinkTransport(link, log, 10_000)
        transport.close()
        val got = CopyOnWriteArrayList<MarketSyncMessage?>()
        transport.send(request()) { got.add(it) }
        Thread.sleep(200)
        assertEquals(1, got.size)
        assertNull(got.single())
    }
}
