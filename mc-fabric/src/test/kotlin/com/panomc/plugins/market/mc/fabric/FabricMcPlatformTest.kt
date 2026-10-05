package com.panomc.plugins.market.mc.fabric

import com.panomc.plugins.market.mc.core.link.CommandHopTimeout
import com.panomc.plugins.market.mc.core.link.PermissionApplier
import com.panomc.plugins.market.mc.core.link.PresenceTracker
import com.panomc.plugins.market.mc.core.platform.PermissionOutcome
import com.panomc.plugins.market.mc.core.wire.McPlatformName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

private fun applier(f: (String, String?, String?, String, List<String>, Long?) -> PermissionOutcome) = object : PermissionApplier {
    override fun apply(username: String, presentUuid: String?, uuidHint: String?, op: String, nodes: List<String>, expiresAt: Long?) =
        f(username, presentUuid, uuidHint, op, nodes, expiresAt)
}

class FabricMcPlatformTest {
    private val presence = PresenceTracker()
    private val serverThread = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-server-thread") }
    private val ranOn = CopyOnWriteArrayList<String>()

    private fun platform(
        submit: (Runnable) -> Unit = { serverThread.execute(it) },
        luckPerms: Boolean = true,
        timeoutMs: Long = 2_000,
        applier: PermissionApplier = applier { _, _, _, _, _, _ -> PermissionOutcome(true) },
        runner: ConsoleRunner
    ) = FabricMcPlatform(presence, submit, runner, { luckPerms }, commandTimeoutMs = timeoutMs, permissionApplier = { applier })

    @Test
    fun `the platform is FABRIC and offers neither Vault nor PlaceholderAPI`() {
        val p = platform { ConsoleOutcome.Ran(1, 0) }
        assertEquals(McPlatformName.FABRIC, p.platformName)
        assertFalse(p.vaultAvailable())
        assertFalse(p.placeholderApiAvailable())
    }

    @Test
    fun `a command that reported success is OK and ran on the server thread without its slash`() {
        val p = platform { cmd ->
            ranOn.add("${Thread.currentThread().name}:$cmd")
            ConsoleOutcome.Ran(1, 0)
        }
        val r = p.dispatchConsole("/give Steve diamond 1")
        assertTrue(r.ok)
        assertEquals(listOf("fake-server-thread:give Steve diamond 1"), ranOn.toList())
    }

    @Test
    fun `a line that does not parse is a failure and says why`() {
        val r = platform { ConsoleOutcome.NotRun("Unknown or incomplete command") }.dispatchConsole("nosuchcommand x")
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("Unknown or incomplete command"), r.error)
    }

    @Test
    fun `a command that reported a failure is a failure even when another result succeeded`() {
        assertFalse(platform { ConsoleOutcome.Ran(0, 1) }.dispatchConsole("give Nobody diamond").ok)
        assertFalse(platform { ConsoleOutcome.Ran(2, 1) }.dispatchConsole("execute as @a run say x").ok)
    }

    @Test
    fun `a command that reported nothing is not confirmed and therefore a failure`() {
        val r = platform { ConsoleOutcome.Ran(0, 0) }.dispatchConsole("say hi")
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("not confirmed"), r.error)
    }

    @Test
    fun `an empty command is refused without touching the server`() {
        var touched = false
        val p = platform(submit = { touched = true }) { touched = true; ConsoleOutcome.Ran(1, 0) }
        assertFalse(p.dispatchConsole("  /  ").ok)
        assertFalse(p.dispatchConsole("").ok)
        assertFalse(touched)
    }

    @Test
    fun `a command that throws propagates so the engine reports it per command`() {
        val p = platform { throw IllegalStateException("boom") }
        val e = assertThrows(IllegalStateException::class.java) { p.dispatchConsole("say hi") }
        assertEquals("boom", e.message)
    }

    @Test
    fun `a server thread that never picks the command up cancels it and it never runs`() {
        val held = ArrayList<Runnable>()
        var ran = false
        val p = platform(submit = { held.add(it) }, timeoutMs = 150) { ran = true; ConsoleOutcome.Ran(1, 0) }
        val e = assertThrows(CommandHopTimeout::class.java) { p.dispatchConsole("give Steve diamond 1") }
        assertFalse(e.started)
        held.forEach { it.run() } // the server thread wakes up late: the cancelled body must not run
        assertFalse(ran)
    }

    @Test
    fun `a server that refuses tasks makes the dispatch throw, never succeed`() {
        val p = platform(submit = { throw IllegalStateException("server stopped") }) { ConsoleOutcome.Ran(1, 0) }
        assertThrows(IllegalStateException::class.java) { p.dispatchConsole("say hi") }
    }

    @Test
    fun `presence follows the tracker and uuids need presence`() {
        val p = platform { ConsoleOutcome.Ran(1, 0) }
        assertFalse(p.isPresent("Steve"))
        assertNull(p.playerUuid("Steve"))
        presence.join("Steve", "uuid-1", authenticated = true)
        assertTrue(p.isPresent("steve"))
        assertEquals("uuid-1", p.playerUuid("STEVE"))
        presence.leave("Steve")
        assertFalse(p.isPresent("Steve"))
    }

    @Test
    fun `the offline uuid is the vanilla one unless Pano knows the player`() {
        val p = platform { ConsoleOutcome.Ran(1, 0) }
        assertEquals(UUID.nameUUIDFromBytes("OfflinePlayer:Alex".toByteArray()).toString(), p.offlineUuid("Alex"))
        val known = UUID.randomUUID()
        val q = FabricMcPlatform(presence, { it.run() }, { ConsoleOutcome.Ran(1, 0) }, { true }, neverJoinedUuid = { known })
        assertEquals(known.toString(), q.offlineUuid("Alex"))
    }

    @Test
    fun `LuckPerms availability is the loader's answer and a throwing probe is false`() {
        assertTrue(platform(luckPerms = true) { ConsoleOutcome.Ran(1, 0) }.luckPermsAvailable())
        assertFalse(platform(luckPerms = false) { ConsoleOutcome.Ran(1, 0) }.luckPermsAvailable())
        val q = FabricMcPlatform(presence, { it.run() }, { ConsoleOutcome.Ran(1, 0) }, { throw LinkageError("no luckperms") })
        assertFalse(q.luckPermsAvailable())
    }

    @Test
    fun `a permission delivery goes through the applier with the present uuid`() {
        presence.join("Steve", "uuid-9", authenticated = true)
        val seen = ArrayList<List<Any?>>()
        val p = platform(applier = applier { name, uuid, hint, op, nodes, exp ->
            seen.add(listOf(name, uuid, hint, op, nodes, exp))
            PermissionOutcome(true)
        }) { ConsoleOutcome.Ran(1, 0) }
        val out = p.applyPermission("Steve", "hint", "ADD", listOf("a.b"), 123L)
        assertTrue(out.ok)
        assertNotNull(seen.single())
        assertEquals(listOf("Steve", "uuid-9", "hint", "ADD", listOf("a.b"), 123L), seen.single())
    }
}
