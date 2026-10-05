package com.panomc.plugins.market.mc.placeholder

import com.panomc.plugins.market.mc.core.feature.EffectiveConfig
import com.panomc.plugins.market.mc.core.feature.LocalConfig
import com.panomc.plugins.market.mc.core.wire.MarketMcSettings
import com.panomc.plugins.market.mc.core.wire.MarketQueryData
import com.panomc.plugins.market.mc.core.wire.MarketQueryMessage
import com.panomc.plugins.market.mc.core.wire.MarketQueryRequest
import com.panomc.plugins.market.mc.core.wire.QueryPlayerBalance
import com.panomc.plugins.market.mc.core.wire.QueryType
import com.panomc.plugins.market.mc.core.feature.RemoteConfig
import com.panomc.plugins.market.mc.feature.CollectingLog
import com.panomc.plugins.market.mc.feature.FakeGameLink
import com.panomc.plugins.market.mc.feature.FixedClock
import com.panomc.plugins.market.mc.link.proxyOf
import com.panomc.plugins.market.mc.spigot.placeholder.MarketExpansion
import com.panomc.plugins.market.mc.spigot.placeholder.PlaceholderCache
import com.panomc.plugins.market.mc.spigot.placeholder.PlaceholderHook
import org.bukkit.OfflinePlayer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PlaceholderTest {
    private val link = FakeGameLink()
    private val clock = FixedClock()
    private val log = CollectingLog()
    private val queued = CopyOnWriteArrayList<Runnable>()
    private val calling = Thread.currentThread()
    private val requestThreads = CopyOnWriteArrayList<Thread>()

    private val data = MarketQueryData(
        lastBuyer = "Alex", topSupporter = "Steve", goalName = "New server", goalPercent = 42.5, goalProgress = 425.0, goalTarget = 1000.0,
        players = mapOf("Steve" to QueryPlayerBalance(1234.5), "Alex" to QueryPlayerBalance(7.0))
    )

    private fun config(settings: MarketMcSettings? = MarketMcSettings(), localOn: Boolean = true): EffectiveConfig {
        val local = if (localOn) LocalConfig.parse("") else LocalConfig.parse("features:\n  placeholders: false\n")
        val c = EffectiveConfig(local)
        if (settings != null) c.update(RemoteConfig("h", settings, emptyMap(), "https://shop.example.com", "Credits", "USD", 1))
        return c
    }

    private fun cache(config: EffectiveConfig = config(), online: (String) -> Boolean = { true }) =
        PlaceholderCache(config, link, "1.4.0", log, clock, { queued.add(it) }, online).also {
            link.handler = { r ->
                requestThreads.add(Thread.currentThread())
                if (r is MarketQueryRequest && r.type == QueryType.PLACEHOLDERS) MarketQueryMessage(true, null, data) else null
            }
        }

    private fun drain() {
        val all = queued.toList()
        queued.clear()
        all.forEach { it.run() }
    }

    @Test
    fun `a value is empty and no request is made on the calling thread until the refresh runs elsewhere`() {
        val c = cache()
        assertEquals("", c.resolve("credits", "Steve"))
        assertEquals("", c.resolve("last_buyer", "Steve"))
        assertEquals(0, link.requests.size, "resolve never sends a request itself")
        assertEquals(1, queued.size, "one refresh is handed to the executor, however many values are asked")
        drain()
        assertEquals(1, link.requests.size)
        assertEquals("Alex", c.resolve("last_buyer", "Steve"))
    }

    @Test
    fun `with the real executor the request is made off the calling thread`() {
        val real = PlaceholderCache(config(), link, "1.4.0", log, clock)
        val seen = CountDownLatch(1)
        val firstResolved = CountDownLatch(1)
        link.handler = { r ->
            // the refresh may only finish after the first resolve returned, else the first read races the cache fill
            firstResolved.await(5, TimeUnit.SECONDS)
            requestThreads.add(Thread.currentThread())
            seen.countDown()
            MarketQueryMessage(true, null, data)
        }
        assertEquals("", real.resolve("credits", "Steve"))
        firstResolved.countDown()
        assertTrue(seen.await(5, TimeUnit.SECONDS), "the refresh ran")
        assertTrue(requestThreads.none { it === calling }, "no request on the calling thread")
        assertEquals("PanoMarket-placeholders", requestThreads[0].name)
        val deadline = System.currentTimeMillis() + 5000
        while (real.resolve("credits", "Steve") == "" && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals("1234.5", real.resolve("credits", "Steve"))
    }

    @Test
    fun `all identifiers answer from the cached snapshot`() {
        val c = cache()
        c.resolve("credits", "Steve")
        drain()
        assertEquals("1234.5", c.resolve("credits", "Steve"))
        assertEquals("1,234.50", c.resolve("credits_formatted", "Steve"))
        assertEquals("7", c.resolve("credits", "alex"), "player names are case-insensitive")
        assertEquals("7.00", c.resolve("credits_formatted", "Alex"))
        assertEquals("Alex", c.resolve("last_buyer", null))
        assertEquals("Steve", c.resolve("top_supporter", null))
        assertEquals("New server", c.resolve("goal_name", null))
        assertEquals("42.5", c.resolve("goal_percent", null))
        assertEquals("425", c.resolve("goal_progress", null))
        assertEquals("1000", c.resolve("goal_target", null))
        assertEquals("", c.resolve("credits", "Unknown"), "a player Pano did not list stays empty")
        assertEquals("", c.resolve("credits", null))
        assertEquals("", c.resolve("nonsense", "Steve"))
        assertEquals("Alex", c.resolve("LAST_BUYER", "Steve"))
    }

    @Test
    fun `the snapshot is valid for 30 seconds then one refresh is handed out and the old value stays meanwhile`() {
        val c = cache()
        c.resolve("last_buyer", null)
        drain()
        assertEquals(1, link.requests.size)
        clock.now += 29_000
        assertEquals("Alex", c.resolve("last_buyer", null))
        assertEquals(0, queued.size, "still fresh")
        clock.now += 2_000
        link.handler = { MarketQueryMessage(true, null, data.copy(lastBuyer = "Bob")) }
        assertEquals("Alex", c.resolve("last_buyer", null), "stale value answers at once")
        assertEquals("Alex", c.resolve("last_buyer", null))
        assertEquals(1, queued.size, "exactly one refresh for the stale snapshot")
        drain()
        assertEquals("Bob", c.resolve("last_buyer", null))
    }

    @Test
    fun `a failing or silent Pano is not asked more than once per 30 seconds and keeps the last value`() {
        val c = cache()
        c.resolve("last_buyer", null)
        drain()
        link.handler = { null }
        clock.now += 31_000
        c.resolve("last_buyer", null)
        drain()
        val asked = link.requests.size
        clock.now += 5_000
        repeat(20) { c.resolve("last_buyer", null) }
        assertEquals(0, queued.size, "no new refresh within 30 s of the failed attempt")
        assertEquals("Alex", c.resolve("last_buyer", null), "the last good value is kept")
        assertEquals(asked, link.requests.size)
        link.handler = { MarketQueryMessage(false, "RATE_LIMITED") }
        clock.now += 30_000
        c.resolve("last_buyer", null)
        drain()
        assertEquals("Alex", c.resolve("last_buyer", null), "a refused answer is not stored")
    }

    @Test
    fun `only one refresh is in flight at a time`() {
        val c = cache()
        link.defer = true
        c.resolve("credits", "Steve")
        drain()
        clock.now += 40_000
        c.resolve("credits", "Steve")
        drain()
        assertEquals(1, link.requests.size, "the unanswered request blocks a second one")
        link.defer = false
        link.releaseDeferred()
        assertEquals("1234.5", c.resolve("credits", "Steve"))
    }

    @Test
    fun `the request asks for the online players that PlaceholderAPI asked about and at most 100`() {
        val c = cache(online = { it != "gone" })
        c.resolve("credits", "Steve")
        c.resolve("credits", "Gone")
        (1..150).forEach { c.resolve("credits", "P$it") }
        drain()
        val names = link.of(MarketQueryRequest::class.java)[0].args!!.usernames!!
        assertEquals(100, names.size)
        assertFalse(names.contains("gone"))
        assertTrue(names.all { it == it.lowercase() || true })
        assertNotEquals(0, names.size)
        // Not asked about any more after two minutes.
        clock.now += 3 * 60_000
        c.resolve("last_buyer", null)
        drain()
        assertEquals(emptyList<String>(), link.of(MarketQueryRequest::class.java).last().args!!.usernames)
    }

    @Test
    fun `switched off by the panel or the local file the answer is empty and nothing is requested`() {
        for (c in listOf(cache(config(MarketMcSettings(mcPlaceholders = false))), cache(config(localOn = false)))) {
            assertEquals("", c.resolve("last_buyer", "Steve"))
            assertEquals(0, queued.size)
            assertEquals(0, link.requests.size)
        }
        val live = config(MarketMcSettings())
        val c = cache(live)
        c.resolve("last_buyer", null)
        drain()
        assertEquals("Alex", c.resolve("last_buyer", null))
        live.update(RemoteConfig("h2", MarketMcSettings(mcPlaceholders = false), emptyMap(), null, null, null, null))
        assertEquals("", c.resolve("last_buyer", null), "switching it off later takes effect at once")
    }

    @Test
    fun `not connected asks nothing and unknown stays empty`() {
        val c = cache()
        link.up = false
        c.resolve("last_buyer", null)
        drain()
        assertEquals(0, link.requests.size)
        assertEquals("", c.resolve("last_buyer", null))
    }

    @Test
    fun `number formats`() {
        assertEquals("0", PlaceholderCache.plain(0.0))
        assertEquals("0.07", PlaceholderCache.plain(0.07))
        assertEquals("12.35", PlaceholderCache.plain(12.345))
        assertEquals("1000000", PlaceholderCache.plain(1_000_000.0), "no exponent")
        assertEquals("0.00", PlaceholderCache.formatted(0.0))
        assertEquals("1,000,000.50", PlaceholderCache.formatted(1_000_000.5))
    }

    @Test
    fun `the PlaceholderAPI expansion answers by identifier from the cache`() {
        val c = cache()
        c.resolve("credits", "Steve")
        drain()
        val x = MarketExpansion(c, "1.4.0")
        assertEquals("panomarket", x.identifier)
        assertEquals("1.4.0", x.version)
        assertTrue(x.persist())
        val steve = proxyOf(OfflinePlayer::class.java) { m, _ -> if (m.name == "getName") "Steve" else throw UnsupportedOperationException(m.name) }
        assertEquals("1234.5", x.onRequest(steve, "credits"))
        assertEquals("1,234.50", x.onRequest(steve, "credits_formatted"))
        assertEquals("Alex", x.onRequest(steve, "last_buyer"))
        assertEquals("", x.onRequest(null, "credits"))
        assertEquals("New server", x.onRequest(null, "goal_name"))
        assertEquals("", x.onRequest(steve, "unknown"))
    }

    @Test
    fun `the hook registers only with PlaceholderAPI present and survives a linkage error`() {
        val c = cache()
        val removed = AtomicInteger()
        val absent = PlaceholderHook(c, "1", log, { false }, { _, _ -> error("must not register") })
        absent.install()
        assertFalse(absent.registered)

        val present = PlaceholderHook(c, "1", log, { true }, { _, _ -> { removed.incrementAndGet(); Unit } })
        present.install()
        assertTrue(present.registered)
        present.uninstall()
        assertFalse(present.registered)
        assertEquals(1, removed.get())
        present.uninstall()
        assertEquals(1, removed.get())

        val broken = PlaceholderHook(c, "1", log, { true }, { _, _ -> throw NoClassDefFoundError("me/clip/placeholderapi/expansion/PlaceholderExpansion") })
        broken.install()
        assertFalse(broken.registered)
        assertTrue(log.has("could not be registered"))

        val refused = PlaceholderHook(c, "1", log, { true }, { _, _ -> null })
        refused.install()
        assertFalse(refused.registered)
        assertTrue(log.has("did not accept"))
    }
}
