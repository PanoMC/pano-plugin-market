package com.panomc.plugins.market.mc.spigot

import com.panomc.plugins.market.mc.link.proxyOf
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

/** The Folia schedulers by reflection (fake region / entity schedulers) and the plain Bukkit path. */
class MarketSchedulerTest {
    class FakeGlobalRegionScheduler {
        val ran = CopyOnWriteArrayList<String>()
        fun run(plugin: Plugin, task: Consumer<Any>) {
            ran.add("global")
            task.accept(Unit)
        }
    }

    class FakeEntityScheduler {
        val ran = CopyOnWriteArrayList<String>()
        fun run(plugin: Plugin, task: Consumer<Any>, retired: Runnable?) {
            ran.add("entity")
            task.accept(Unit)
        }
    }

    /** A Folia server: the plain `Server` plus the one extra method the component reflects on. */
    class FoliaServer(delegate: Server, val global: FakeGlobalRegionScheduler) : Server by delegate {
        @Suppress("unused")
        fun getGlobalRegionScheduler(): Any = global
    }

    class FoliaPlayer(delegate: Player, val entity: FakeEntityScheduler) : Player by delegate {
        @Suppress("unused")
        fun getScheduler(): Any = entity
    }

    @BeforeEach
    fun setUp() {
        FakeBukkit.install()
    }

    private fun pluginOn(server: Server): Plugin = proxyOf(Plugin::class.java) { m, _ ->
        if (m.name == "getServer") server else throw UnsupportedOperationException("Plugin.${m.name}")
    }

    @Test
    fun `Bukkit - global and player tasks go through the Bukkit scheduler`() {
        val plugin = FakeBukkit.plugin("PanoMarket")
        val s = MarketScheduler(plugin, folia = false)
        val done = java.util.concurrent.CountDownLatch(2)
        s.runGlobal { done.countDown() }
        s.runForPlayer(FakeBukkit.player("Steve"), { done.countDown() })
        assertTrue(done.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(listOf("runTask", "runTask"), FakeBukkit.schedulerCalls)
    }

    @Test
    fun `Folia - the global task uses the global region scheduler and never the Bukkit scheduler`() {
        val global = FakeGlobalRegionScheduler()
        val s = MarketScheduler(pluginOn(FoliaServer(FakeBukkit.server, global)), folia = true)
        var ran = 0
        s.runGlobal { ran++ }
        assertEquals(1, ran)
        assertEquals(listOf("global"), global.ran)
        assertTrue(FakeBukkit.schedulerCalls.isEmpty())
    }

    @Test
    fun `Folia - a player task uses the entity scheduler of that player`() {
        val entity = FakeEntityScheduler()
        val player = FoliaPlayer(FakeBukkit.player("Steve"), entity)
        val s = MarketScheduler(FakeBukkit.plugin("PanoMarket"), folia = true)
        var ran = 0
        s.runForPlayer(player, { ran++ }, { })
        assertEquals(1, ran)
        assertEquals(listOf("entity"), entity.ran)
        assertTrue(FakeBukkit.schedulerCalls.isEmpty())
    }

    @Test
    fun `Folia without the expected schedulers is an error, never a silent fall back to the Bukkit scheduler`() {
        val s = MarketScheduler(FakeBukkit.plugin("PanoMarket"), folia = true)
        assertThrows(NoSuchMethodException::class.java) { s.runGlobal { } }
        assertThrows(NoSuchMethodException::class.java) { s.runForPlayer(FakeBukkit.player("Steve"), { }) }
        assertTrue(FakeBukkit.schedulerCalls.isEmpty())
    }

    @Test
    fun `an exception of a Folia task body is not swallowed by the reflection layer`() {
        val global = FakeGlobalRegionScheduler()
        val s = MarketScheduler(pluginOn(FoliaServer(FakeBukkit.server, global)), folia = true)
        val e = assertThrows(InvocationTargetException::class.java) { s.runGlobal { throw IllegalStateException("task failed") } }
        assertEquals("task failed", e.cause!!.message)
    }
}
