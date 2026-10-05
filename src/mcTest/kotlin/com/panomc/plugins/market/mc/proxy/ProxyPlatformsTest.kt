package com.panomc.plugins.market.mc.proxy

import com.panomc.plugins.market.mc.bungee.BungeeMcPlatform
import com.panomc.plugins.market.mc.core.link.CommandHopTimeout
import com.panomc.plugins.market.mc.core.link.PermissionApplier
import com.panomc.plugins.market.mc.core.link.PresenceRules
import com.panomc.plugins.market.mc.core.link.PresenceTracker
import com.panomc.plugins.market.mc.core.platform.PermissionOutcome
import com.panomc.plugins.market.mc.core.wire.McPlatformName
import com.panomc.plugins.market.mc.velocity.VelocityMcPlatform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** BungeeCord and Velocity adapters through their seams (the proxy classes themselves need a real proxy: MC-R1). */
class ProxyPlatformsTest {
    private val pool = Executors.newSingleThreadExecutor { Thread(it, "proxy-scheduler").also { t -> t.isDaemon = true } }
    private val tracker = PresenceTracker()
    private val commands = CopyOnWriteArrayList<Pair<String, String>>()
    private var luckPerms = false

    private fun bungee(found: Boolean = true, submit: (Runnable) -> Unit = { pool.execute(it) }, timeoutMs: Long = 2_000) = BungeeMcPlatform(
        tracker, submit, { commands.add(Thread.currentThread().name to it); found }, { luckPerms }, commandTimeoutMs = timeoutMs
    )

    private fun velocity(found: Boolean = true, submit: (Runnable) -> Unit = { pool.execute(it) }, timeoutMs: Long = 2_000) = VelocityMcPlatform(
        tracker, submit, { cmd -> commands.add(Thread.currentThread().name to cmd); CompletableFuture.completedFuture(found) }, { luckPerms }, commandTimeoutMs = timeoutMs
    )

    @Test
    fun `platform names and capability flags - a proxy has no Vault and no PlaceholderAPI`() {
        assertEquals(McPlatformName.BUNGEECORD, bungee().platformName)
        assertEquals(McPlatformName.VELOCITY, velocity().platformName)
        listOf(bungee(), velocity()).forEach {
            assertFalse(it.vaultAvailable())
            assertFalse(it.placeholderApiAvailable())
        }
    }

    @Test
    fun `LuckPerms availability follows the proxy plugin list`() {
        listOf(bungee(), velocity()).forEach {
            luckPerms = false
            assertFalse(it.luckPermsAvailable())
            luckPerms = true
            assertTrue(it.luckPermsAvailable())
        }
    }

    @Test
    fun `a console command runs on a proxy scheduler thread, found = ok, not found = failed`() {
        listOf(bungee(), velocity()).forEach { p ->
            commands.clear()
            assertTrue(p.dispatchConsole("alert hello").ok)
            assertEquals(listOf("proxy-scheduler" to "alert hello"), commands)
            assertNotEquals(Thread.currentThread().name, commands.single().first)
        }
        assertFalse(bungee(found = false).dispatchConsole("nope").ok)
        assertEquals("unknown command", velocity(found = false).dispatchConsole("nope").error)
    }

    @Test
    fun `a scheduler that refuses and a scheduler that never runs the task are errors, nothing runs`() {
        commands.clear()
        assertThrows(IllegalStateException::class.java) { bungee(submit = { throw IllegalStateException("shutting down") }).dispatchConsole("x") }
        assertThrows(IllegalStateException::class.java) { velocity(submit = { throw IllegalStateException("shutting down") }).dispatchConsole("x") }
        val parked = ArrayList<Runnable>()
        assertThrows(CommandHopTimeout::class.java) { bungee(submit = { parked.add(it) }, timeoutMs = 60).dispatchConsole("x") }
        assertThrows(CommandHopTimeout::class.java) { velocity(submit = { parked.add(it) }, timeoutMs = 60).dispatchConsole("x") }
        parked.forEach { it.run() }
        assertTrue(commands.isEmpty(), "cancelled commands never run afterwards")
    }

    @Test
    fun `presence on a proxy - connected to a backend is present anywhere on the network, LimboAuth players are not`() {
        val announced = ArrayList<String>()
        val rules = PresenceRules(tracker, { false }, { true }, { announced.add(it) })
        // In LimboAuth's limbo the player never reaches a backend, so no join event: not present.
        assertFalse(bungee().isPresent("Steve"))
        assertFalse(velocity().isPresent("Steve"))
        rules.onJoin("Steve", "u1")
        listOf(bungee(), velocity()).forEach {
            assertTrue(it.isPresent("steve"))
            assertEquals("u1", it.playerUuid("Steve"))
        }
        rules.onQuit("Steve")
        assertFalse(velocity().isPresent("Steve"))
        assertEquals(listOf("Steve"), announced)
        assertEquals(UUID.nameUUIDFromBytes("OfflinePlayer:Alex".toByteArray()).toString(), bungee().offlineUuid("Alex"))
    }

    private class RecordingApplier : PermissionApplier {
        val calls = CopyOnWriteArrayList<List<String?>>()
        override fun apply(username: String, presentUuid: String?, uuidHint: String?, op: String, nodes: List<String>, expiresAt: Long?): PermissionOutcome {
            calls.add(listOf(username, presentUuid, uuidHint, op))
            return PermissionOutcome(true)
        }
    }

    @Test
    fun `applyPermission hands the tracker uuid and the Pano hint to LuckPerms separately`() {
        val applier = RecordingApplier()
        val platforms = listOf(
            BungeeMcPlatform(tracker, { pool.execute(it) }, { true }, { true }, permissionApplier = { applier }),
            VelocityMcPlatform(tracker, { pool.execute(it) }, { CompletableFuture.completedFuture(true) }, { true }, permissionApplier = { applier })
        )
        platforms.forEach { it.applyPermission("Steve", "pano-hint", "ADD", listOf("group.vip"), null) } // not connected
        PresenceRules(tracker, { false }, { true }, {}).onJoin("Steve", "proxy-uuid")
        platforms.forEach { it.applyPermission("Steve", "pano-hint", "ADD", listOf("group.vip"), null) } // connected
        assertEquals(
            listOf(
                listOf("Steve", null, "pano-hint", "ADD"), listOf("Steve", null, "pano-hint", "ADD"),
                listOf("Steve", "proxy-uuid", "pano-hint", "ADD"), listOf("Steve", "proxy-uuid", "pano-hint", "ADD")
            ),
            applier.calls.toList()
        )
    }
}
