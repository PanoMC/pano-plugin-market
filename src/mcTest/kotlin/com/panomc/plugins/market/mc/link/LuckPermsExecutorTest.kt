package com.panomc.plugins.market.mc.link

import com.panomc.plugins.market.mc.core.link.LuckPermsExecutor
import com.panomc.plugins.market.mc.core.support.TestClock
import net.luckperms.api.model.user.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** PERMISSION deliveries through the LuckPerms 5 API (19 section 6.3), against a recording fake of the API. */
class LuckPermsExecutorTest {
    private val lp = FakeLuckPerms()
    private val clock = TestClock()
    private val steve = UUID.nameUUIDFromBytes("steve".toByteArray())
    private val offline = UUID.nameUUIDFromBytes("offline".toByteArray())
    private val now = Instant.ofEpochMilli(clock.now())

    private fun executor(timeoutMs: Long = 2_000, api: () -> net.luckperms.api.LuckPerms = { lp.api }) =
        LuckPermsExecutor(api, { offline }, clock, timeoutMs, lp.factory())

    private fun at(plusMs: Long) = clock.now() + plusMs

    @Test
    fun `ADD permanent group node on a user without it - added and saved once`() {
        val r = executor().apply("Steve", steve.toString(), "ADD", listOf("group.vip"), null)
        assertTrue(r.ok, r.error)
        assertEquals(listOf("group.vip" to null), lp.stored())
        assertEquals(1, lp.saves)
        assertEquals(listOf(steve to "Steve"), lp.loads)
    }

    @Test
    fun `ADD with an expiry keeps the expiry`() {
        val r = executor().apply("Steve", steve.toString(), "ADD", listOf("group.vip"), at(86_400_000))
        assertTrue(r.ok, r.error)
        assertEquals(listOf("group.vip" to now.plusMillis(86_400_000)), lp.stored())
    }

    @Test
    fun `a later expiry replaces an earlier one`() {
        lp.seed("group.vip", now.plusMillis(1_000))
        val r = executor().apply("Steve", steve.toString(), "ADD", listOf("group.vip"), at(5_000))
        assertTrue(r.ok, r.error)
        assertEquals(listOf("group.vip" to now.plusMillis(5_000)), lp.stored())
        assertEquals(1, lp.saves)
    }

    @Test
    fun `an earlier or equal expiry never shortens an existing temporary node and nothing is saved`() {
        lp.seed("group.vip", now.plusMillis(5_000))
        assertTrue(executor().apply("Steve", steve.toString(), "ADD", listOf("group.vip"), at(1_000)).ok)
        assertTrue(executor().apply("Steve", steve.toString(), "ADD", listOf("group.vip"), at(5_000)).ok)
        assertEquals(listOf("group.vip" to now.plusMillis(5_000)), lp.stored())
        assertEquals(0, lp.saves)
    }

    @Test
    fun `a permanent grant replaces a temporary node`() {
        lp.seed("group.vip", now.plusMillis(1_000))
        assertTrue(executor().apply("Steve", steve.toString(), "ADD", listOf("group.vip"), null).ok)
        assertEquals(listOf("group.vip" to null), lp.stored())
    }

    @Test
    fun `an existing permanent node is never downgraded by a temporary grant, and a repeated permanent grant is a no-op`() {
        lp.seed("group.vip", null)
        assertTrue(executor().apply("Steve", steve.toString(), "ADD", listOf("group.vip"), at(1_000)).ok)
        assertTrue(executor().apply("Steve", steve.toString(), "ADD", listOf("group.vip"), null).ok)
        assertEquals(listOf("group.vip" to null), lp.stored())
        assertEquals(0, lp.saves)
    }

    @Test
    fun `ADD ignores nodes of another context and adds the global one`() {
        lp.seed("group.vip", null, global = false)
        assertTrue(executor().apply("Steve", steve.toString(), "ADD", listOf("group.vip"), null).ok)
        assertEquals(listOf("group.vip" to null), lp.stored())
        assertEquals(2, lp.nodes.size)
        assertEquals(1, lp.saves)
    }

    @Test
    fun `REMOVE deletes every global node with that key and nothing else`() {
        lp.seed("group.vip", null)
        lp.seed("group.vip", now.plusMillis(9_000))
        lp.seed("group.other", null)
        lp.seed("group.vip", null, global = false)
        val r = executor().apply("Steve", steve.toString(), "REMOVE", listOf("group.vip"), null)
        assertTrue(r.ok, r.error)
        assertEquals(listOf("group.other" to null), lp.stored())
        assertEquals(2, lp.nodes.size, "the other group and the node of another context stay")
        assertEquals(1, lp.saves)
    }

    @Test
    fun `REMOVE of a node the user does not have succeeds without a save (the undo is idempotent)`() {
        val r = executor().apply("Steve", steve.toString(), "REMOVE", listOf("group.vip"), null)
        assertTrue(r.ok, r.error)
        assertEquals(0, lp.saves)
    }

    @Test
    fun `several nodes are applied together and saved once`() {
        val r = executor().apply("Steve", steve.toString(), "ADD", listOf("group.vip", "essentials.fly", " essentials.home "), null)
        assertTrue(r.ok, r.error)
        assertEquals(listOf("group.vip", "essentials.fly", "essentials.home"), lp.stored().map { it.first })
        assertEquals(1, lp.saves)
    }

    @Test
    fun `the user is found by the uuid hint, else by name lookup, else by the never-joined id`() {
        executor().apply("Steve", steve.toString(), "ADD", listOf("a.b"), null)
        assertEquals(steve, lp.loads.last().first)
        assertTrue(lp.lookups.isEmpty())

        val looked = UUID.nameUUIDFromBytes("looked".toByteArray())
        lp.lookupResult = looked
        executor().apply("Alex", "not-a-uuid", "ADD", listOf("a.b"), null)
        assertEquals(looked to "Alex", lp.loads.last())
        assertEquals(listOf("Alex"), lp.lookups)

        lp.lookupResult = null
        executor().apply("Nobody", null, "ADD", listOf("a.b"), null)
        assertEquals(offline to "Nobody", lp.loads.last())
    }

    @Test
    fun `an expiry that has already passed is refused before anything is loaded`() {
        val r = executor().apply("Steve", steve.toString(), "ADD", listOf("group.vip"), at(-1))
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("expiry"))
        assertTrue(lp.loads.isEmpty())
        assertEquals(0, lp.saves)
    }

    @Test
    fun `invalid payloads are refused`() {
        assertFalse(executor().apply("Steve", steve.toString(), "SET", listOf("a.b"), null).ok)
        assertFalse(executor().apply("Steve", steve.toString(), "ADD", emptyList(), null).ok)
        assertFalse(executor().apply("Steve", steve.toString(), "ADD", listOf("a.b", "  "), null).ok)
        assertTrue(lp.loads.isEmpty())
    }

    @Test
    fun `a LuckPerms that is not loaded yet is a failed outcome, not an exception`() {
        val r = executor(api = { throw IllegalStateException("The LuckPerms API isn't loaded yet!") }).apply("Steve", steve.toString(), "ADD", listOf("a.b"), null)
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("isn't loaded"))
    }

    @Test
    fun `a failing load or save is a failed outcome with the reason, and a failed save is not reported as done`() {
        lp.loadFuture = { CompletableFuture<User>().also { it.completeExceptionally(IllegalStateException("storage offline")) } }
        val load = executor().apply("Steve", steve.toString(), "ADD", listOf("a.b"), null)
        assertFalse(load.ok)
        assertTrue(load.error!!.contains("storage offline"))

        lp.loadFuture = null
        lp.saveFuture = { CompletableFuture<Void>().also { it.completeExceptionally(IllegalStateException("cannot write")) } }
        val save = executor().apply("Steve", steve.toString(), "ADD", listOf("a.b"), null)
        assertFalse(save.ok)
        assertTrue(save.error!!.contains("cannot write"))
    }

    @Test
    fun `a LuckPerms that never answers is cut off by the bound`() {
        lp.loadFuture = { CompletableFuture() }
        val t0 = System.nanoTime()
        val r = executor(timeoutMs = 120).apply("Steve", steve.toString(), "ADD", listOf("a.b"), null)
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("did not answer"))
        assertNotNull(r.error)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 3_000)
    }
}
