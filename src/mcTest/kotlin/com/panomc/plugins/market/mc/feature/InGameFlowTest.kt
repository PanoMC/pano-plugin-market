package com.panomc.plugins.market.mc.feature

import com.panomc.plugins.market.mc.core.feature.asControl
import com.panomc.plugins.market.mc.core.link.MarketComponent
import com.panomc.plugins.market.mc.core.link.PanoLink
import com.panomc.plugins.market.mc.core.link.PresenceRules
import com.panomc.plugins.market.mc.core.link.PresenceTracker
import com.panomc.plugins.market.mc.core.platform.McPlatform
import com.panomc.plugins.market.mc.core.support.FakeMcPlatform
import com.panomc.plugins.market.mc.core.support.TestLog
import com.panomc.plugins.market.mc.core.support.delivery
import com.panomc.plugins.market.mc.core.support.response
import com.panomc.plugins.market.mc.core.sync.RuntimeOptions
import com.panomc.plugins.market.mc.core.wire.DeliveryDisplay
import com.panomc.plugins.market.mc.core.wire.MarketMcSettings
import com.panomc.plugins.market.mc.core.wire.MarketQueryData
import com.panomc.plugins.market.mc.core.wire.MarketQueryMessage
import com.panomc.plugins.market.mc.core.wire.MarketSyncMessage
import com.panomc.plugins.market.mc.core.wire.MarketSyncRequest
import com.panomc.plugins.market.mc.core.wire.QueryGift
import com.panomc.plugins.market.mc.core.wire.SyncBroadcast
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The features wired to the REAL delivery engine, sync loop and presence rules (threads and all), the way a platform main
 * wires them: broadcasts and join notifications as the engine and the connection produce them.
 */
class InGameFlowTest {
    @TempDir
    lateinit var dir: Path

    private var component: MarketComponent? = null

    @AfterEach
    fun stop() {
        component?.stop()
    }

    private class FakePanoLink : PanoLink {
        @Volatile
        var up = false
        val requests = CopyOnWriteArrayList<MarketSyncRequest>()
        private val answers = CopyOnWriteArrayList<MarketSyncMessage>()
        private val index = AtomicInteger()
        fun queue(m: MarketSyncMessage) = answers.add(m)
        override fun connected() = up
        override suspend fun awaitSync(request: MarketSyncRequest, timeoutMs: Long): MarketSyncMessage {
            if (!up) throw IllegalStateException("Not connected to Pano Platform.")
            requests.add(request)
            val i = index.getAndIncrement()
            return if (i < answers.size) answers[i] else response()
        }
    }

    private inner class Rig(configText: String? = null) {
        val tracker = PresenceTracker()
        val platform = FakeMcPlatform()
        val panoLink = FakePanoLink()
        val feature = FeatureRig(dir, configText)
        val authActive = booleanArrayOf(false)
        val rules = PresenceRules(
            tracker,
            authRequired = { authActive[0] },
            isAuthenticated = { false },
            onPresent = { name ->
                component?.playerPresent(name)
                feature.features.onPlayerPresent(name)
            }
        )

        init {
            val seen: McPlatform = object : McPlatform by platform {
                override fun isPresent(username: String) = tracker.isPresent(username)
                override fun playerUuid(username: String) = tracker.uuid(username)
            }
            feature.uuids["steve"] = "uuid-steve"
            val c = MarketComponent(
                dir.resolve("state"), seen, panoLink, TestLog(), "1.4.0", feature.features.settings,
                callbacks = feature.features.callbacks, monitorIntervalMs = 20, options = RuntimeOptions(expireEveryMs = 50)
            )
            feature.features.attach(c.runtime.asControl())
            component = c
        }
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
    fun `a broadcast offered by Pano is shown once per id, even when a later response repeats it`() {
        val r = Rig()
        r.feature.loadConfig(panoConfig("h1", MarketMcSettings(mcBroadcast = true)))
        r.panoLink.queue(response(broadcasts = listOf(SyncBroadcast(1, "&aSteve &7bought &fDiamonds")), configHash = "h1", pollAfterMs = 0))
        r.panoLink.queue(response(broadcasts = listOf(SyncBroadcast(1, "&aSteve &7bought &fDiamonds"), SyncBroadcast(2, "&aAlex &7bought &fCoal")), configHash = "h1", pollAfterMs = 0))
        r.panoLink.queue(response(broadcasts = listOf(SyncBroadcast(2, "&aAlex &7bought &fCoal")), configHash = "h1"))
        component!!.start()
        r.panoLink.up = true
        await("three syncs") { r.panoLink.requests.size >= 3 }
        Thread.sleep(100)
        assertEquals(
            listOf("§aSteve §7bought §fDiamonds", "§aAlex §7bought §fCoal"),
            r.feature.host.broadcasts,
            "one chat line per broadcast id"
        )
    }

    @Test
    fun `an order Pano did not offer (hidden from the broadcast, test mode) shows nothing`() {
        val r = Rig()
        r.feature.loadConfig(panoConfig("h1", MarketMcSettings(mcBroadcast = true)))
        // Pano filters hideFromBroadcast / test-mode / hidden-gift orders when it fills the outbox: such an order is simply absent.
        r.panoLink.queue(response(broadcasts = emptyList(), configHash = "h1"))
        component!!.start()
        r.panoLink.up = true
        await("a sync") { r.panoLink.requests.isNotEmpty() }
        Thread.sleep(100)
        assertTrue(r.feature.host.broadcasts.isEmpty())
    }

    @Test
    fun `the panel switch and the local switch both silence broadcasts`() {
        val panelOff = Rig()
        panelOff.feature.loadConfig(panoConfig("h1", MarketMcSettings(mcBroadcast = false)))
        panelOff.panoLink.queue(response(broadcasts = listOf(SyncBroadcast(1, "hidden")), configHash = "h1"))
        component!!.start()
        panelOff.panoLink.up = true
        await("a sync") { panelOff.panoLink.requests.isNotEmpty() }
        Thread.sleep(100)
        assertTrue(panelOff.feature.host.broadcasts.isEmpty(), "mcBroadcast is off in the panel")
        component!!.stop()
        component = null

        java.nio.file.Files.deleteIfExists(dir.resolve("config.yml"))
        val localOff = Rig("features:\n  broadcast: false\n")
        localOff.feature.loadConfig(panoConfig("h1", MarketMcSettings(mcBroadcast = true)))
        localOff.panoLink.queue(response(broadcasts = listOf(SyncBroadcast(1, "hidden")), configHash = "h1"))
        component!!.start()
        localOff.panoLink.up = true
        await("a sync") { localOff.panoLink.requests.isNotEmpty() }
        Thread.sleep(100)
        assertTrue(localOff.feature.host.broadcasts.isEmpty(), "the local file switched broadcasts off")
    }

    @Test
    fun `the first sync response can carry a broadcast before any config was pulled and it is not lost`() {
        val r = Rig()
        r.feature.link.handler = { panoConfig("h1", MarketMcSettings(mcBroadcast = true)) }
        r.panoLink.queue(response(broadcasts = listOf(SyncBroadcast(1, "&aFirst")), configHash = "h1"))
        component!!.start()
        r.panoLink.up = true
        await("the broadcast") { r.feature.host.broadcasts.isNotEmpty() }
        await("the config pull") { r.feature.features.callbacks.cachedConfigHash() == "h1" }
        assertEquals(listOf("§aFirst"), r.feature.host.broadcasts)
        assertEquals(1, r.feature.link.configRequests.size, "the hash difference of the very first response pulled MARKET_CONFIG")
        assertEquals("h1", r.panoLink.requests.last().configHash ?: "h1")
    }

    @Test
    fun `a join notification comes after the authenticated join and after the waiting delivery ran`() {
        val r = Rig()
        r.authActive[0] = true // an auth plugin is installed: a joining player is not present until the login
        r.feature.link.handler = {
            MarketQueryMessage(true, null, MarketQueryData(deliveriesQueued = 0, gifts = listOf(QueryGift("Alex", "Rank VIP", "ORD1"))))
        }
        r.panoLink.queue(
            response(
                deliveries = listOf(
                    delivery("k1", 1, "Steve", listOf("lp user Steve parent add vip"), requiresOnline = true, display = DeliveryDisplay("Rank VIP", "ORD1", true, "Alex"))
                ),
                pollAfterMs = 60_000
            )
        )
        component!!.start()
        r.panoLink.up = true
        await("the delivery was queued") { component!!.runtime.status().engine.queued == 1 }

        // Joined but not logged in (AuthMe): not present, nothing runs, nothing is announced, nobody is asked.
        r.rules.onJoin("Steve", "uuid-steve")
        Thread.sleep(150)
        assertTrue(r.platform.console.isEmpty())
        assertTrue(r.feature.host.sent.isEmpty())
        assertTrue(r.feature.link.requests.isEmpty())

        // The login makes the player present: the queue runs, then the notices come.
        r.rules.onAuthLogin("Steve", "uuid-steve")
        r.platform.join("Steve", "uuid-steve")
        await("delivery ran") { r.platform.console.isNotEmpty() }
        await("join notice") { r.feature.host.to("Steve").any { it.contains("have been delivered") } }
        Thread.sleep(150)
        assertEquals(listOf("lp user Steve parent add vip"), r.platform.console.toList())
        val lines = r.feature.host.to("Steve")
        assertEquals(1, lines.count { it == "1 purchase(s) that were waiting for you have been delivered." }, "$lines")
        assertEquals(1, lines.count { it.contains("Rank VIP") }, "the gift is announced once, by whichever side was first: $lines")
        assertEquals(1, r.feature.link.requests.size, "one PENDING query for the join")
        assertFalse(lines.any { it.contains("on their way") })

        // A second login event (already present) does not repeat anything.
        r.rules.onAuthLogin("Steve", "uuid-steve")
        Thread.sleep(100)
        assertEquals(lines, r.feature.host.to("Steve"))
    }

    @Test
    fun `a player who is not authenticated never gets a notice and a quit forgets them`() {
        val r = Rig()
        r.authActive[0] = true
        component!!.start()
        r.panoLink.up = true
        r.rules.onJoin("Steve", "uuid-steve")
        r.rules.onQuit("Steve")
        r.rules.onAuthLogin("Steve", "uuid-steve") // a login that arrives after the quit
        Thread.sleep(100)
        assertTrue(r.feature.host.sent.isEmpty())
        assertTrue(r.feature.link.requests.isEmpty())
    }

    @Test
    fun `MC-U9 deliveries false in config yml answers every delivery DISABLED_LOCALLY and runs nothing`() {
        val r = Rig("deliveries: false\n")
        r.panoLink.queue(response(deliveries = listOf(delivery("k1", 1, "Steve"), delivery("k2", 2, "Alex", requiresOnline = true)), pollAfterMs = 60_000))
        component!!.start()
        r.panoLink.up = true
        await("the results are reported") { r.panoLink.requests.any { req -> req.results.size == 2 } }
        val results = r.panoLink.requests.first { it.results.size == 2 }.results.associateBy { it.key }
        assertEquals("FAILED", results.getValue("k1").status)
        assertEquals("DISABLED_LOCALLY", results.getValue("k1").code)
        assertEquals("DISABLED_LOCALLY", results.getValue("k2").code, "also for a delivery that would have waited for the player")
        assertTrue(r.platform.console.isEmpty())
    }

    @Test
    fun `a local luckperms false and a panel mcLuckPerms false both answer a permission delivery LUCKPERMS_MISSING`() {
        val local = Rig("features:\n  luckperms: false\n")
        local.platform.luckPerms = true
        local.feature.loadConfig(panoConfig("h1", MarketMcSettings(mcLuckPerms = true)))
        local.panoLink.queue(response(deliveries = listOf(com.panomc.plugins.market.mc.core.support.permissionDelivery("p1", 1, "Steve")), configHash = "h1", pollAfterMs = 60_000))
        component!!.start()
        local.panoLink.up = true
        await("local result") { local.panoLink.requests.any { it.results.isNotEmpty() } }
        assertEquals("LUCKPERMS_MISSING", local.panoLink.requests.first { it.results.isNotEmpty() }.results.single().code)
        assertTrue(local.platform.permissionCalls.isEmpty(), "LuckPerms was never touched")
        component!!.stop()
        component = null

        java.nio.file.Files.deleteIfExists(dir.resolve("config.yml"))
        dir.resolve("state").toFile().deleteRecursively()
        val panel = Rig()
        panel.platform.luckPerms = true
        panel.feature.loadConfig(panoConfig("h1", MarketMcSettings(mcLuckPerms = false)))
        panel.panoLink.queue(response(deliveries = listOf(com.panomc.plugins.market.mc.core.support.permissionDelivery("p1", 1, "Steve")), configHash = "h1", pollAfterMs = 60_000))
        component!!.start()
        panel.panoLink.up = true
        await("panel result") { panel.panoLink.requests.any { it.results.isNotEmpty() } }
        assertEquals("LUCKPERMS_MISSING", panel.panoLink.requests.first { it.results.isNotEmpty() }.results.single().code)
        assertTrue(panel.platform.permissionCalls.isEmpty())
    }

    @Test
    fun `a broken config yml keeps deliveries off, nothing runs`() {
        val r = Rig("deliveries: maybe\n")
        r.panoLink.queue(response(deliveries = listOf(delivery("k1", 1, "Steve")), pollAfterMs = 60_000))
        component!!.start()
        r.panoLink.up = true
        await("result") { r.panoLink.requests.any { it.results.isNotEmpty() } }
        assertEquals("DISABLED_LOCALLY", r.panoLink.requests.first { it.results.isNotEmpty() }.results.single().code)
        assertTrue(r.platform.console.isEmpty(), "an unreadable config never lets a delivery run")
    }
}
