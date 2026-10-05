package com.panomc.plugins.market.mc.gui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 19 section 3: the chest GUI uses only API that exists in Spigot 1.8.8 (the module compiles against spigot-api 1.8.8, which
 * already proves every symbol; this scan names the usual newer-API traps so a reflection or string based use cannot hide).
 */
class GuiSourceScanTest {
    private val root = File(System.getProperty("market.mcResources")).parentFile.resolve("kotlin/com/panomc/plugins/market/mc/spigot")

    private val forbidden = Regex(
        "getItemInMainHand|getItemInOffHand|getMainHand|getOffHand|setCustomModelData|NamespacedKey|org\\.bukkit\\.persistence|PLAYER_HEAD|" +
            "getScheduler\\s*\\(|\\.scheduler\\b|runTask|Component\\.|net\\.kyori|org\\.bukkit\\.inventory\\.view|openInventory\\s*\\(\\s*MenuType|" +
            "createInventory\\s*\\([^)]*InventoryType|\\.addItem\\s*\\("
    )

    private fun strip(s: String) = s.replace(Regex("/\\*[\\s\\S]*?\\*/"), " ").replace(Regex("//[^\\n]*"), " ")

    @Test
    fun `gui and placeholder sources use no API newer than 1_8_8 and no direct scheduler`() {
        val files = listOf("gui", "placeholder").flatMap { d -> File(root, d).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue(files.size >= 4, "the scan must see the sources (saw ${files.size})")
        val offenders = files.associate { it.name to forbidden.findAll(strip(it.readText())).map { m -> m.value }.toList() }.filterValues { it.isNotEmpty() }
        assertEquals(emptyMap<String, List<String>>(), offenders)
    }

    @Test
    fun `the scan is not vacuous`() {
        assertTrue(forbidden.containsMatchIn("player.inventory.getItemInMainHand()"))
        assertTrue(forbidden.containsMatchIn("Bukkit.getScheduler().runTask(plugin, r)"))
    }
}
