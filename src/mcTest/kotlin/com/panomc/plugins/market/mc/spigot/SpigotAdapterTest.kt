package com.panomc.plugins.market.mc.spigot

import com.panomc.plugins.market.mc.core.link.PermissionApplier
import com.panomc.plugins.market.mc.core.link.PresenceRules
import com.panomc.plugins.market.mc.core.link.PresenceTracker
import com.panomc.plugins.market.mc.core.platform.PermissionOutcome
import com.panomc.plugins.market.mc.core.wire.McPlatformName
import fr.xephi.authme.api.v3.AuthMeApi
import fr.xephi.authme.events.LoginEvent
import fr.xephi.authme.events.LogoutEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import com.panomc.plugins.market.mc.core.link.CommandHopTimeout
import org.bukkit.event.EventPriority
import java.util.UUID

class SpigotAdapterTest {
    private val tracker = PresenceTracker()

    @BeforeEach
    fun setUp() {
        FakeBukkit.install()
    }

    private fun platform(timeoutMs: Long = 2_000, folia: Boolean = false): SpigotMcPlatform {
        val plugin = FakeBukkit.plugin("PanoMarket")
        return SpigotMcPlatform(
            plugin, MarketScheduler(plugin, folia), tracker, com.panomc.plugins.market.mc.core.support.TestLog(),
            McPlatformName.PAPER, commandTimeoutMs = timeoutMs
        )
    }

    @Test
    fun `a console command runs on the server main thread as the console and its result comes back`() {
        val p = platform()
        val r = p.dispatchConsole("give Steve diamond 1")
        assertTrue(r.ok)
        assertEquals(listOf("fake-main" to "give Steve diamond 1"), FakeBukkit.dispatched)
        assertNotEquals(Thread.currentThread().name, FakeBukkit.dispatched.single().first)
        assertEquals(listOf("runTask"), FakeBukkit.schedulerCalls)
    }

    @Test
    fun `an unknown command (dispatchCommand false) is reported as a failed dispatch`() {
        FakeBukkit.dispatch = { false }
        val r = platform().dispatchConsole("nosuchcommand")
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("unknown command"))
    }

    @Test
    fun `an exception inside the command reaches the engine, which records it per command`() {
        FakeBukkit.dispatch = { throw IllegalStateException("plugin crashed") }
        val e = assertThrows(IllegalStateException::class.java) { platform().dispatchConsole("boom") }
        assertEquals("plugin crashed", e.message)
    }

    @Test
    fun `a disabled plugin (the scheduler refuses) is an error and the command did not run`() {
        FakeBukkit.refuseTasks = IllegalStateException("Plugin attempted to register task while disabled")
        assertThrows(IllegalStateException::class.java) { platform().dispatchConsole("say hi") }
        assertTrue(FakeBukkit.dispatched.isEmpty())
    }

    @Test
    fun `a main thread that never picks the task up times out and the command never runs`() {
        FakeBukkit.neverRunTasks = true
        val e = assertThrows(CommandHopTimeout::class.java) { platform(timeoutMs = 80).dispatchConsole("say hi") }
        assertFalse(e.started)
        assertTrue(FakeBukkit.dispatched.isEmpty())
    }

    @Test
    fun `LuckPerms availability follows the plugin manager and Vault and PlaceholderAPI are off until their slices exist`() {
        val p = platform()
        assertFalse(p.luckPermsAvailable())
        FakeBukkit.plugin("LuckPerms")
        assertTrue(p.luckPermsAvailable())
        assertFalse(p.vaultAvailable())
        assertFalse(p.placeholderApiAvailable())
        assertEquals(McPlatformName.PAPER, p.platformName)
    }

    @Test
    fun `presence and uuid come from the tracker, the offline uuid is the vanilla offline id`() {
        val p = platform()
        assertFalse(p.isPresent("Steve"))
        tracker.join("Steve", "uuid-1", true)
        assertTrue(p.isPresent("steve"))
        assertEquals("uuid-1", p.playerUuid("STEVE"))
        assertNull(p.playerUuid("Nobody"))
        assertEquals(UUID.nameUUIDFromBytes("OfflinePlayer:Nobody".toByteArray()).toString(), p.offlineUuid("Nobody"))
    }

    // ---- AuthMe, by reflection

    @Test
    fun `AuthMe is detected through the plugin manager, no AuthMe class is needed when it is missing`() {
        val bridge = AuthMeBridge(FakeBukkit.plugin("PanoMarket"))
        assertFalse(bridge.installed())
        assertFalse(bridge.registerEvents({}, {}))
        assertThrows(IllegalStateException::class.java) { bridge.isAuthenticated(FakeBukkit.player("Steve")) }
        FakeBukkit.plugin("AuthMe")
        assertTrue(bridge.installed())
    }

    @Test
    fun `isAuthenticated asks the AuthMe API`() {
        FakeBukkit.plugin("AuthMe")
        val bridge = AuthMeBridge(FakeBukkit.plugin("PanoMarket"))
        val steve = FakeBukkit.player("Steve")
        assertFalse(bridge.isAuthenticated(steve))
        AuthMeApi.authenticated.add("Steve")
        assertTrue(bridge.isAuthenticated(steve))
    }

    @Test
    fun `the AuthMe login and logout events are hooked at MONITOR and call the handlers with the player`() {
        FakeBukkit.plugin("AuthMe")
        val bridge = AuthMeBridge(FakeBukkit.plugin("PanoMarket"))
        val logins = ArrayList<String>()
        val logouts = ArrayList<String>()
        assertTrue(bridge.registerEvents({ logins.add(it.name) }, { logouts.add(it.name) }))
        assertEquals(2, FakeBukkit.registered.size)
        assertTrue(FakeBukkit.registered.all { it.priority == EventPriority.MONITOR && it.ignoreCancelled })
        val steve = FakeBukkit.player("Steve")
        val login = FakeBukkit.registered.single { it.type == LoginEvent::class.java }
        val logout = FakeBukkit.registered.single { it.type == LogoutEvent::class.java }
        login.executor.execute(login.listener, LoginEvent(steve))
        logout.executor.execute(logout.listener, LogoutEvent(steve))
        // An event of another class sent to the executor is ignored.
        login.executor.execute(login.listener, LogoutEvent(steve))
        assertEquals(listOf("Steve"), logins)
        assertEquals(listOf("Steve"), logouts)
    }

    @Test
    fun `AuthMe present - the events drive presence end to end (join, login, logout, quit)`() {
        FakeBukkit.plugin("AuthMe")
        val bridge = AuthMeBridge(FakeBukkit.plugin("PanoMarket"))
        val announced = ArrayList<String>()
        val rules = PresenceRules(tracker, { bridge.installed() }, { n -> AuthMeApi.getInstance().isAuthenticated(FakeBukkit.player(n)) }, { announced.add(it) })
        bridge.registerEvents({ rules.onAuthLogin(it.name, it.uniqueId.toString()) }, { rules.onAuthLogout(it.name) })
        val steve = FakeBukkit.player("Steve")

        rules.onJoin("Steve", steve.uniqueId.toString())
        assertFalse(platform().isPresent("Steve"), "joined but not logged in")
        FakeBukkit.registered.single { it.type == LoginEvent::class.java }.let { it.executor.execute(it.listener, LoginEvent(steve)) }
        assertTrue(platform().isPresent("Steve"))
        assertEquals(steve.uniqueId.toString(), platform().playerUuid("Steve"))
        FakeBukkit.registered.single { it.type == LogoutEvent::class.java }.let { it.executor.execute(it.listener, LogoutEvent(steve)) }
        assertFalse(platform().isPresent("Steve"))
        assertEquals(listOf("Steve"), announced)
    }

    @Test
    fun `applyPermission hands the tracker uuid and the Pano hint to LuckPerms separately`() {
        val calls = ArrayList<List<String?>>()
        val applier = object : PermissionApplier {
            override fun apply(username: String, presentUuid: String?, uuidHint: String?, op: String, nodes: List<String>, expiresAt: Long?): PermissionOutcome {
                calls.add(listOf(username, presentUuid, uuidHint, op))
                return PermissionOutcome(true)
            }
        }
        val plugin = FakeBukkit.plugin("PanoMarket")
        val p = SpigotMcPlatform(
            plugin, MarketScheduler(plugin, false), tracker, com.panomc.plugins.market.mc.core.support.TestLog(),
            McPlatformName.PAPER, permissionApplier = { applier }
        )
        p.applyPermission("Steve", "pano-hint", "ADD", listOf("group.vip"), null)
        PresenceRules(tracker, { false }, { true }, {}).onJoin("Steve", "server-uuid")
        p.applyPermission("Steve", "pano-hint", "REMOVE", listOf("group.vip"), null)
        assertEquals(listOf(listOf("Steve", null, "pano-hint", "ADD"), listOf("Steve", "server-uuid", "pano-hint", "REMOVE")), calls)
    }

    @Test
    fun `an AuthMe login event after the player quit is ignored (the player is not online any more)`() {
        FakeBukkit.plugin("AuthMe")
        val bridge = AuthMeBridge(FakeBukkit.plugin("PanoMarket"))
        val announced = ArrayList<String>()
        val rules = PresenceRules(tracker, { true }, { false }, { announced.add(it) })
        // The same guard MarketSpigotPlugin installs: a login of a player that is not online is dropped.
        bridge.registerEvents({ if (it.isOnline) rules.onAuthLogin(it.name, it.uniqueId.toString()) }, { rules.onAuthLogout(it.name) })
        val steve = FakeBukkit.player("Steve", online = false)
        rules.onJoin("Steve", steve.uniqueId.toString())
        rules.onQuit("Steve")
        FakeBukkit.registered.single { it.type == LoginEvent::class.java }.let { it.executor.execute(it.listener, LoginEvent(steve)) }
        assertFalse(platform().isPresent("Steve"))
        assertTrue(announced.isEmpty())
    }
}
