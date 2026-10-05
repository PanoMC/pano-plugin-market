package com.panomc.plugins.market.mc.feature

import com.panomc.plugins.market.mc.core.feature.Feature
import com.panomc.plugins.market.mc.core.feature.Msg
import com.panomc.plugins.market.mc.core.store.DeliveryRecord
import com.panomc.plugins.market.mc.core.store.DisplayInfo
import com.panomc.plugins.market.mc.core.store.RecordState
import com.panomc.plugins.market.mc.core.support.record
import com.panomc.plugins.market.mc.core.wire.MarketMcSettings
import com.panomc.plugins.market.mc.core.wire.MarketQueryData
import com.panomc.plugins.market.mc.core.wire.MarketQueryMessage
import com.panomc.plugins.market.mc.core.wire.MarketQueryRequest
import com.panomc.plugins.market.mc.core.wire.QueryGift
import com.panomc.plugins.market.mc.core.wire.QueryType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class MarketFeaturesConfigTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `a changed hash pulls MARKET_CONFIG, the answer is cached and its hash is what the sync reports`() {
        val r = FeatureRig(dir)
        assertNull(r.features.callbacks.cachedConfigHash())
        assertNull(r.features.config.remote)
        r.link.handler = { panoConfig("h1", MarketMcSettings(mcStoreCommand = false), storeUrl = "https://shop.example.com", creditName = "Coins") }
        r.features.callbacks.onConfigHashChanged("h1")
        assertEquals(1, r.link.configRequests.size)
        assertNull(r.link.configRequests[0].have, "the first pull names no hash")
        assertEquals("1.4.0", r.link.configRequests[0].componentVersion)
        assertEquals("h1", r.features.callbacks.cachedConfigHash())
        assertFalse(r.features.config.enabled(Feature.STORE_COMMAND), "the panel's setting is live at once")
        assertEquals("Coins", r.features.config.remote!!.creditName)
        assertEquals(7L, r.features.config.remote!!.serverId)

        r.link.handler = { panoConfig("h2", MarketMcSettings(mcStoreCommand = true)) }
        r.features.callbacks.onConfigHashChanged("h2")
        assertEquals("h1", r.link.configRequests[1].have, "the next pull names the hash it holds")
        assertEquals("h2", r.features.callbacks.cachedConfigHash())
        assertTrue(r.features.config.enabled(Feature.STORE_COMMAND))
    }

    @Test
    fun `no answer, a refusal or an answer without hash or settings keep what is cached`() {
        val r = FeatureRig(dir)
        r.loadConfig(panoConfig("h1"))
        r.link.handler = { null }
        r.features.callbacks.onConfigHashChanged("h2")
        assertEquals("h1", r.features.callbacks.cachedConfigHash())
        assertTrue(r.log.has("no answer"))

        r.link.handler = { com.panomc.plugins.market.mc.core.wire.MarketConfigMessage(false, "MARKET_NOT_READY") }
        r.features.callbacks.onConfigHashChanged("h3")
        assertEquals("h1", r.features.callbacks.cachedConfigHash())
        assertTrue(r.log.has("MARKET_NOT_READY"))

        r.link.handler = { com.panomc.plugins.market.mc.core.wire.MarketConfigMessage(true, null, null, MarketMcSettings()) }
        r.features.callbacks.onConfigHashChanged("h4")
        assertEquals("h1", r.features.callbacks.cachedConfigHash())

        val fresh = FeatureRig(dir.resolve("other"))
        fresh.link.handler = { com.panomc.plugins.market.mc.core.wire.MarketConfigMessage(true, null, "x", null) }
        fresh.features.callbacks.onConfigHashChanged("x")
        assertNull(fresh.features.callbacks.cachedConfigHash(), "no settings and none cached: nothing to apply")
    }

    @Test
    fun `an answer without settings means unchanged and keeps the cached values`() {
        val r = FeatureRig(dir)
        r.loadConfig(panoConfig("h1", MarketMcSettings(mcStoreCommand = false), mapOf("tr" to mapOf("store.link" to "Dükkan {url}")), "https://shop.example.com", "Coins"))
        r.link.handler = { com.panomc.plugins.market.mc.core.wire.MarketConfigMessage(true, null, "h1b", null) }
        r.features.callbacks.onConfigHashChanged("h1b")
        val c = r.features.config.remote!!
        assertEquals("h1b", c.configHash)
        assertFalse(c.settings.mcStoreCommand)
        assertEquals("https://shop.example.com", c.storeUrl)
        assertEquals("Coins", c.creditName)
        assertEquals(setOf("tr"), c.texts.keys)
    }

    @Test
    fun `only one pull is in flight at a time`() {
        val r = FeatureRig(dir)
        r.link.defer = true
        r.link.handler = { panoConfig("h1") }
        r.features.callbacks.onConfigHashChanged("h1")
        r.features.callbacks.onConfigHashChanged("h1")
        r.features.callbacks.onConfigHashChanged("h1")
        assertEquals(1, r.link.configRequests.size)
        r.link.releaseDeferred()
        assertEquals("h1", r.features.callbacks.cachedConfigHash())
        r.link.handler = { panoConfig("h2") }
        r.features.callbacks.onConfigHashChanged("h2")
        assertEquals(2, r.link.configRequests.size, "after the answer the next pull is allowed")
    }

    @Test
    fun `a link that throws does not leave the pull stuck`() {
        val r = FeatureRig(dir)
        r.link.handler = { throw IllegalStateException("socket closed") }
        r.features.callbacks.onConfigHashChanged("h1")
        r.link.handler = { panoConfig("h1") }
        r.features.callbacks.onConfigHashChanged("h1")
        assertEquals("h1", r.features.callbacks.cachedConfigHash())
    }

    @Test
    fun `the store address must be a web url, the credit name is made safe, texts are limited`() {
        val r = FeatureRig(dir)
        listOf("javascript:alert(1)", "ftp://x.example", "https://a b.example", "https://x.example/\n").forEach { bad ->
            r.loadConfig(panoConfig("h-$bad", storeUrl = bad))
            assertNull(r.features.config.remote!!.storeUrl, bad)
        }
        r.loadConfig(panoConfig("hx", storeUrl = "http://localhost:8088/shop", creditName = "§cCoins\n" + "x".repeat(60)))
        assertEquals("http://localhost:8088/shop", r.features.config.remote!!.storeUrl)
        assertEquals("cCoins" + "x".repeat(26), r.features.config.remote!!.creditName)

        val texts = (1..6).associate { "l$it" to mapOf("k" to "v") } + ("tr" to (1..400).associate { "key$it" to "y".repeat(900) })
        r.loadConfig(panoConfig("ht", texts = texts))
        assertEquals(3, r.features.config.remote!!.texts.size)
        val capped = panoConfig("hu", texts = mapOf("tr" to (1..400).associate { "key$it" to "y".repeat(900) }))
        r.loadConfig(capped)
        val tr = r.features.config.remote!!.texts.getValue("tr")
        assertEquals(300, tr.size)
        assertEquals(512, tr.getValue("key1").length)
    }

    @Test
    fun `texts from Pano feed the messages and a local lang file beats them`() {
        val r = FeatureRig(dir)
        r.loadConfig(panoConfig("h1", texts = mapOf("en-US" to mapOf("store.link" to "&6Shop here: {url}"))))
        val a = FakeSender()
        r.features.commands.execute("store", a, emptyList())
        assertEquals(listOf("Shop here: https://shop.example.com"), a.plain())

        java.nio.file.Files.createDirectories(dir.resolve("lang"))
        java.nio.file.Files.write(dir.resolve("lang").resolve("en-US.yml"), "store.link: \"&bMy shop: {url}\"\n".toByteArray())
        val r2 = FeatureRig(dir)
        r2.loadConfig(panoConfig("h1", texts = mapOf("en-US" to mapOf("store.link" to "&6Shop here: {url}"))))
        val b = FakeSender()
        r2.features.commands.execute("store", b, emptyList())
        assertEquals(listOf("My shop: https://shop.example.com"), b.plain())
    }

    @Test
    fun `the first start writes config yml, a broken one fails closed and says so`() {
        val r = FeatureRig(dir.resolve("first"))
        assertTrue(java.nio.file.Files.isRegularFile(dir.resolve("first").resolve("config.yml")), "the default file is written")
        assertTrue(r.features.config.local.enabled)
        assertFalse(r.log.lines.any { it.startsWith("ERROR") })

        val broken = FeatureRig(dir.resolve("broken"), "enabled: true\n  oops: [\n")
        assertNotNull(broken.features.config.local.error)
        assertFalse(broken.features.settings.deliveriesEnabled, "a broken file never leaves deliveries on")
        Feature.values().forEach { assertFalse(broken.features.config.enabled(it), "$it") }
        assertTrue(broken.log.has("stays OFF"))

        val warned = FeatureRig(dir.resolve("warn"), "mystery: 1\n")
        assertTrue(warned.log.has("unknown key 'mystery'"))
        assertTrue(warned.features.settings.deliveriesEnabled)
    }

    @Test
    fun `a data folder that cannot be written does not stop the component, the bundled defaults apply`() {
        val file = dir.resolve("a-file")
        java.nio.file.Files.write(file, "x".toByteArray())
        val r = FeatureRig(file.resolve("sub"))
        assertNotNull(r.features.config.local.error, "the folder cannot be created: fail closed with a reason")
        assertTrue(r.log.has("could not be read"))
    }
}

class MarketFeaturesBroadcastAndJoinTest {
    @TempDir
    lateinit var dir: Path

    private fun done(key: String, player: String, display: DisplayInfo?, state: RecordState = RecordState.DONE): DeliveryRecord =
        record(key, key.filter { it.isDigit() }.toLongOrNull() ?: 1, player).copy(state = state, display = display)

    private fun gift(order: String, from: String = "Alex", product: String = "Rank VIP") = DisplayInfo(product, order, true, from)

    @Test
    fun `a broadcast is coloured and shown, blank and control-only text is skipped`() {
        val r = FeatureRig(dir)
        r.features.callbacks.showBroadcast("&aSteve &7bought &fDiamonds")
        r.features.callbacks.showBroadcast("§bAlex bought §fCoal")
        r.features.callbacks.showBroadcast("   ")
        r.features.callbacks.showBroadcast("\u0001\u0002")
        r.features.callbacks.showBroadcast("line one\nline two")
        assertEquals(listOf("§aSteve §7bought §fDiamonds", "§bAlex bought §fCoal", "line one line two"), r.host.broadcasts)
    }

    @Test
    fun `join message after the queue ran counts only the delivered records and announces gifts once`() {
        val r = FeatureRig(dir)
        r.host.locales["steve"] = "en_US"
        r.features.callbacks.onQueueDrained(
            "Steve",
            listOf(
                done("k1", "Steve", DisplayInfo("Diamonds", "ORD1", false, null)),
                done("k2", "Steve", gift("ORD2")),
                done("k3", "Steve", gift("ORD2"), RecordState.DONE),
                done("k4", "Steve", DisplayInfo("Failed thing", "ORD4", false, null), RecordState.FAILED),
                done("k5", "Steve", DisplayInfo("Late", "ORD5", false, null), RecordState.EXPIRED)
            )
        )
        assertEquals(
            listOf("3 purchase(s) that were waiting for you have been delivered.", "Alex sent you Rank VIP. Enjoy!"),
            r.host.to("Steve")
        )
    }

    @Test
    fun `nothing is announced when nothing was delivered`() {
        val r = FeatureRig(dir)
        r.features.callbacks.onQueueDrained("Steve", listOf(done("k1", "Steve", null, RecordState.EXPIRED), done("k2", "Steve", null, RecordState.FAILED)))
        r.features.callbacks.onQueueDrained("Steve", emptyList())
        assertTrue(r.host.sent.isEmpty())
    }

    @Test
    fun `join notifications can be switched off by the panel or the local file`() {
        val panel = FeatureRig(dir.resolve("p"))
        panel.loadConfig(panoConfig("h", MarketMcSettings(mcJoinNotifications = false)))
        panel.features.callbacks.onQueueDrained("Steve", listOf(done("k1", "Steve", gift("O1"))))
        panel.features.onPlayerPresent("Steve")
        assertTrue(panel.host.sent.isEmpty())
        assertTrue(panel.link.requests.isEmpty(), "no query is sent for a switched-off feature")

        val local = FeatureRig(dir.resolve("l"), "features:\n  join-notifications: false\n")
        local.loadConfig(panoConfig("h", MarketMcSettings(mcJoinNotifications = true)))
        local.features.callbacks.onQueueDrained("Steve", listOf(done("k1", "Steve", gift("O1"))))
        local.features.onPlayerPresent("Steve")
        assertTrue(local.host.sent.isEmpty())
        assertTrue(local.link.requests.isEmpty())
    }

    @Test
    fun `an authenticated join asks Pano for what is pending and tells the player`() {
        val r = FeatureRig(dir)
        r.uuids["steve"] = "uuid-steve"
        r.link.handler = {
            MarketQueryMessage(true, null, MarketQueryData(deliveriesQueued = 2, gifts = listOf(QueryGift("Alex", "Rank VIP", "ORD9"))))
        }
        r.features.onPlayerPresent("Steve")
        val q = r.link.requests.single() as MarketQueryRequest
        assertEquals(QueryType.PENDING, q.type)
        assertEquals("Steve", q.player!!.username)
        assertEquals("uuid-steve", q.player!!.uuid)
        assertEquals(
            listOf("2 more purchase(s) are on their way to you.", "Alex sent you Rank VIP."),
            r.host.to("Steve")
        )
    }

    @Test
    fun `a gift is announced once whichever side reports it first`() {
        val pending = MarketQueryMessage(true, null, MarketQueryData(deliveriesQueued = 0, gifts = listOf(QueryGift("Alex", "Rank VIP", "ORD2"))))
        // Pano's answer first, then the local drain.
        val a = FeatureRig(dir.resolve("a"))
        a.link.handler = { pending }
        a.features.onPlayerPresent("Steve")
        a.features.callbacks.onQueueDrained("Steve", listOf(done("k2", "Steve", gift("ORD2"))))
        assertEquals(1, a.host.to("Steve").count { it.contains("Rank VIP") })
        assertTrue(a.host.to("Steve").any { it.contains("have been delivered") })
        // The local drain first, then Pano's answer.
        val b = FeatureRig(dir.resolve("b"))
        b.link.handler = { pending }
        b.features.callbacks.onQueueDrained("Steve", listOf(done("k2", "Steve", gift("ORD2"))))
        b.features.onPlayerPresent("Steve")
        assertEquals(1, b.host.to("Steve").count { it.contains("Rank VIP") })
        // Another order of the same gift giver is a different gift.
        b.features.callbacks.onQueueDrained("Steve", listOf(done("k3", "Steve", gift("ORD3"))))
        assertEquals(2, b.host.to("Steve").count { it.contains("Rank VIP") })
    }

    @Test
    fun `a refused, missing or failing pending query tells nobody and breaks nothing`() {
        val r = FeatureRig(dir)
        r.link.handler = { null }
        r.features.onPlayerPresent("Steve")
        r.link.handler = { MarketQueryMessage(false, "RATE_LIMITED", null) }
        r.features.onPlayerPresent("Steve")
        r.link.handler = { MarketQueryMessage(true, null, null) }
        r.features.onPlayerPresent("Steve")
        r.link.handler = { throw IllegalStateException("socket closed") }
        r.features.onPlayerPresent("Steve")
        r.link.up = false
        r.features.onPlayerPresent("Steve")
        assertTrue(r.host.sent.isEmpty())
        assertEquals(4, r.link.requests.size, "a disconnected link sends nothing")
    }

    @Test
    fun `join messages use the player's language`() {
        val r = FeatureRig(dir)
        r.host.locales["ali"] = "tr_TR"
        r.features.callbacks.onQueueDrained("Ali", listOf(done("k1", "Ali", DisplayInfo("Elmas", "O1", false, null))))
        assertEquals(listOf("Seni bekleyen 1 satın alım teslim edildi."), r.host.to("Ali"))
        assertEquals(Msg.JOIN_DELIVERED, "join.delivered")
    }
}
