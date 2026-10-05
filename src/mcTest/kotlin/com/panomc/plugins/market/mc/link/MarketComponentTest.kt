package com.panomc.plugins.market.mc.link

import com.panomc.plugins.market.mc.core.link.LuckPermsExecutor
import com.panomc.plugins.market.mc.core.link.MarketComponent
import com.panomc.plugins.market.mc.core.link.PanoLink
import com.panomc.plugins.market.mc.core.link.PresenceRules
import com.panomc.plugins.market.mc.core.link.PresenceTracker
import com.panomc.plugins.market.mc.core.platform.McPlatform
import com.panomc.plugins.market.mc.core.platform.PermissionOutcome
import com.panomc.plugins.market.mc.core.support.FakeMcPlatform
import com.panomc.plugins.market.mc.core.support.TestLog
import com.panomc.plugins.market.mc.core.support.TestSettings
import com.panomc.plugins.market.mc.core.support.delivery
import com.panomc.plugins.market.mc.core.support.permissionDelivery
import com.panomc.plugins.market.mc.core.wire.DeliveryPermission
import com.panomc.plugins.market.mc.core.support.response
import com.panomc.plugins.market.mc.core.wire.MarketSyncMessage
import com.panomc.plugins.market.mc.core.wire.MarketSyncRequest
import com.panomc.plugins.market.mc.core.wire.ResultCode
import com.panomc.plugins.market.mc.core.wire.ResultStatus
import com.panomc.plugins.market.mc.core.sync.RuntimeOptions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The adapter wiring end to end on real threads: connection monitor -> runtime -> engine -> platform -> LuckPerms
 * executor, with a fake Pano link. No Minecraft class is needed; the platform is the engine's FakeMcPlatform whose
 * `applyPermission` is the REAL [LuckPermsExecutor] over a fake LuckPerms API.
 */
class MarketComponentTest {
    @TempDir
    lateinit var dir: Path

    private val log = TestLog()
    private val steve = UUID.nameUUIDFromBytes("steve".toByteArray()).toString()
    private var component: MarketComponent? = null

    @AfterEach
    fun stop() {
        component?.stop()
    }

    private class FakeLink : PanoLink {
        @Volatile
        var up = false
        val requests = CopyOnWriteArrayList<MarketSyncRequest>()
        private val answers = CopyOnWriteArrayList<MarketSyncMessage>()
        private val index = AtomicInteger()

        /** Every request is answered with the next queued answer, or an empty one. */
        fun queue(m: MarketSyncMessage) = answers.add(m)
        override fun connected() = up
        override suspend fun awaitSync(request: MarketSyncRequest, timeoutMs: Long): MarketSyncMessage {
            if (!up) throw IllegalStateException("Not connected to Pano Platform.")
            requests.add(request)
            val i = index.getAndIncrement()
            return if (i < answers.size) answers[i] else response()
        }
    }

    private class Rig(val platform: FakeMcPlatform, val link: FakeLink, val lp: FakeLuckPerms)

    private fun rig(settings: TestSettings = TestSettings(), luckPerms: Boolean = true, lpFailure: Boolean = false, tracker: PresenceTracker? = null): Rig {
        val lp = FakeLuckPerms()
        val platform = FakeMcPlatform()
        platform.luckPerms = luckPerms
        val executor = LuckPermsExecutor({ lp.api }, { UUID.nameUUIDFromBytes("offline".toByteArray()) }, nodeFactory = lp.factory(), timeoutMs = 1_000)
        platform.permissionHandler = { c ->
            if (lpFailure) PermissionOutcome(false, "storage offline") else executor.apply(c.username, tracker?.uuid(c.username), c.uuidHint, c.op, c.nodes, c.expiresAt)
        }
        val link = FakeLink()
        // With a tracker the engine sees presence exactly as the adapters report it (join / AuthMe login), not the fake's own list.
        val seen: McPlatform = if (tracker == null) platform else object : McPlatform by platform {
            override fun isPresent(username: String) = tracker.isPresent(username)
            override fun playerUuid(username: String) = tracker.uuid(username)
        }
        val c = MarketComponent(
            dir, seen, link, log, "1.4.0", settings,
            monitorIntervalMs = 20,
            options = RuntimeOptions(expireEveryMs = 50)
        )
        component = c
        return Rig(platform, link, lp)
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
    fun `nothing is requested while Pano is not connected, the first connected second starts the sync`() {
        val r = rig()
        component!!.start()
        Thread.sleep(150)
        assertTrue(r.link.requests.isEmpty())
        r.link.up = true
        await("first sync") { r.link.requests.isNotEmpty() }
        assertEquals("1.4.0", r.link.requests[0].componentVersion)
        assertEquals(20, r.link.requests[0].capacity)
        assertTrue(r.link.requests[0].luckPerms)
        assertTrue(component!!.runtime.status().connected)
    }

    @Test
    fun `a dropped connection pauses the loop and the next connection syncs at once`() {
        val r = rig()
        r.link.up = true
        component!!.start()
        await("first sync") { r.link.requests.isNotEmpty() }
        r.link.up = false
        await("paused") { !component!!.runtime.status().connected }
        val before = r.link.requests.size
        Thread.sleep(200)
        assertEquals(before, r.link.requests.size, "no request while the link is down")
        r.link.up = true
        await("syncs again") { r.link.requests.size > before }
    }

    @Test
    fun `a PERMISSION delivery with LuckPerms is applied through the real executor and reported DONE`() {
        val r = rig()
        r.platform.join("Steve", steve)
        r.link.queue(response(deliveries = listOf(permissionDelivery("k1", 1, nodes = listOf("group.vip"), expiresAt = System.currentTimeMillis() + 3_600_000))))
        r.link.up = true
        component!!.start()
        await("permission applied") { r.lp.stored().isNotEmpty() }
        assertEquals("group.vip", r.lp.stored().single().first)
        assertEquals(1, r.lp.saves)
        await("result reported") { r.link.requests.any { q -> q.results.any { it.key == "k1" && it.status == ResultStatus.DONE } } }
        assertTrue(r.platform.console.isEmpty(), "no console command is used for a permission")
    }

    @Test
    fun `a PERMISSION REMOVE (the undo) through the executor takes the node away`() {
        val r = rig()
        r.lp.seed("group.vip", Instant.now().plusSeconds(3_600))
        r.link.queue(response(deliveries = listOf(permissionDelivery("k2", 2, op = "REMOVE", nodes = listOf("group.vip")))))
        r.link.up = true
        component!!.start()
        await("node removed") { r.lp.stored().isEmpty() }
        await("result reported") { r.link.requests.any { q -> q.results.any { it.key == "k2" && it.status == ResultStatus.DONE } } }
    }

    @Test
    fun `a PERMISSION delivery without LuckPerms fails LUCKPERMS_MISSING and LuckPerms is never touched`() {
        val r = rig(luckPerms = false)
        r.link.queue(response(deliveries = listOf(permissionDelivery("k3", 3))))
        r.link.up = true
        component!!.start()
        await("failure reported") { r.link.requests.any { q -> q.results.any { it.key == "k3" } } }
        val res = r.link.requests.flatMap { it.results }.first { it.key == "k3" }
        assertEquals(ResultStatus.FAILED, res.status)
        assertEquals(ResultCode.LUCKPERMS_MISSING, res.code)
        assertTrue(r.platform.permissionCalls.isEmpty())
        assertTrue(r.lp.loads.isEmpty())
        assertFalse(r.link.requests[0].luckPerms, "the component tells Pano that LuckPerms is missing")
    }

    @Test
    fun `the local luckperms switch off answers the same code`() {
        val r = rig(settings = TestSettings(luckPermsEnabled = false))
        r.link.queue(response(deliveries = listOf(permissionDelivery("k4", 4))))
        r.link.up = true
        component!!.start()
        await("failure reported") { r.link.requests.any { q -> q.results.any { it.key == "k4" } } }
        assertEquals(ResultCode.LUCKPERMS_MISSING, r.link.requests.flatMap { it.results }.first { it.key == "k4" }.code)
        assertTrue(r.lp.loads.isEmpty())
    }

    @Test
    fun `a LuckPerms failure is reported as PERMISSION_ERROR with the reason`() {
        val r = rig(lpFailure = true)
        r.link.queue(response(deliveries = listOf(permissionDelivery("k5", 5))))
        r.link.up = true
        component!!.start()
        await("failure reported") { r.link.requests.any { q -> q.results.any { it.key == "k5" } } }
        val res = r.link.requests.flatMap { it.results }.first { it.key == "k5" }
        assertEquals(ResultStatus.FAILED, res.status)
        assertEquals(ResultCode.PERMISSION_ERROR, res.code)
        assertTrue(res.message!!.contains("storage offline"))
    }

    @Test
    fun `a requires-online permission waits until the player is present (authenticated), then runs`() {
        val tracker = PresenceTracker()
        val r = rig(tracker = tracker)
        val rules = PresenceRules(tracker, { true }, { false }, { component!!.playerPresent(it) })
        r.link.queue(response(deliveries = listOf(delivery("k6", 6, kind = "PERMISSION", requiresOnline = true,
            permission = DeliveryPermission("ADD", listOf("group.vip"), null)))))
        r.link.up = true
        component!!.start()
        await("queued reported") { r.link.requests.any { q -> q.results.any { it.key == "k6" && it.status == ResultStatus.QUEUED } } }
        assertTrue(r.lp.stored().isEmpty())

        // Joins, but AuthMe has not logged him in: still queued.
        rules.onJoin("Steve", steve)
        Thread.sleep(150)
        assertTrue(r.lp.stored().isEmpty(), "not authenticated = not present")
        assertTrue(r.platform.permissionCalls.isEmpty())

        rules.onAuthLogin("Steve", steve)
        await("applied after login") { r.lp.stored().isNotEmpty() }
        assertEquals("group.vip", r.lp.stored().single().first)
        await("final result") { r.link.requests.any { q -> q.results.any { it.key == "k6" && it.status == ResultStatus.DONE } } }
    }
}
