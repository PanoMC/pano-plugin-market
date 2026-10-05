package com.panomc.plugins.market.mc.spigot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 19 section 3 (Folia row): no `Bukkit.getScheduler()` call outside the scheduler wrapper. The scan reads the source
 * of the whole Bukkit side (`mc/spigot`) and of `mc/core`, strips comments and strings, and fails on any use of the
 * Bukkit scheduler API. The wrapper itself is the one allowed place, and the scan proves it is not vacuous.
 */
class SchedulerSourceScanTest {
    private val kotlinRoot = File(System.getProperty("market.mcResources")).parentFile.resolve("kotlin/com/panomc/plugins/market/mc")

    private val forbidden = Regex(
        "getScheduler\\s*\\(|\\.scheduler\\b|BukkitScheduler|BukkitRunnable|BukkitTask|\\brunTask(Later|Timer|Asynchronously)?\\b|" +
            "scheduleSync(Delayed|Repeating)Task|scheduleAsync(Delayed|Repeating)Task|callSyncMethod|GlobalRegionScheduler|getAsyncScheduler"
    )

    private fun strip(source: String): String {
        var s = source.replace(Regex("/\\*[\\s\\S]*?\\*/"), " ")
        s = s.replace(Regex("//[^\\n]*"), " ")
        s = s.replace(Regex("\"\"\"[\\s\\S]*?\"\"\""), "\"\"")
        s = s.replace(Regex("\"(\\\\.|[^\"\\\\\\n])*\""), "\"\"")
        return s
    }

    fun violations(source: String): List<String> = forbidden.findAll(strip(source)).map { it.value }.toList()

    private fun scanned(): List<File> =
        listOf("spigot", "core").flatMap { d -> File(kotlinRoot, d).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    @Test
    fun `no Bukkit scheduler call exists outside MarketScheduler`() {
        val files = scanned()
        assertTrue(files.size >= 20, "the scan must see the real sources (saw ${files.size})")
        val offenders = files.filter { it.name != "MarketScheduler.kt" }.associate { it.name to violations(it.readText()) }.filterValues { it.isNotEmpty() }
        assertEquals(emptyMap<String, List<String>>(), offenders)
    }

    @Test
    fun `the wrapper is the place that does use the scheduler (the scan is not vacuous)`() {
        val wrapper = File(kotlinRoot, "spigot/MarketScheduler.kt")
        assertTrue(wrapper.isFile)
        val v = violations(wrapper.readText())
        assertTrue(v.any { it.startsWith(".scheduler") || it.startsWith("getScheduler") }, "the wrapper uses plugin.server.scheduler: $v")
        assertTrue(v.any { it.startsWith("runTask") })
        assertTrue(v.any { it.startsWith("GlobalRegionScheduler") || wrapper.readText().contains("getGlobalRegionScheduler") })
    }

    @Test
    fun `the scan catches every spelling of a scheduler call and ignores comments and strings`() {
        assertFalse(violations("Bukkit.getScheduler().runTask(plugin, r)").isEmpty())
        assertFalse(violations("plugin.server.scheduler.runTaskLater(plugin, r, 20)").isEmpty())
        assertFalse(violations("Bukkit.getScheduler().runTaskAsynchronously(p, r)").isEmpty())
        assertFalse(violations("object : BukkitRunnable() { }").isEmpty())
        assertFalse(violations("server.getScheduler()\n .scheduleSyncDelayedTask(p, r)").isEmpty())
        assertFalse(violations("val s = Bukkit.getServer().getScheduler ()").isEmpty())
        assertTrue(violations("// Bukkit.getScheduler().runTask(plugin, r)").isEmpty())
        assertTrue(violations("/* Bukkit.getScheduler() */ val x = 1").isEmpty())
        assertTrue(violations("val t = \"Bukkit.getScheduler()\"").isEmpty())
        assertTrue(violations("val ok = marketScheduler.runGlobal { }").isEmpty())
    }
}
