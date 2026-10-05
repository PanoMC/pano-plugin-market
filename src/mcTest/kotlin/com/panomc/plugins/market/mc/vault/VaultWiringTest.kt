package com.panomc.plugins.market.mc.vault

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * The seams: the Spigot main class really builds, starts, feeds and stops the bridge, the platform reports Vault from the
 * bridge, and nothing outside the glue file can load a Vault class (a server without Vault must still enable the component).
 */
class VaultWiringTest {
    private val kotlinRoot = File(System.getProperty("market.mcResources")).parentFile.resolve("kotlin/com/panomc/plugins/market/mc")
    private val jar = File(System.getProperty("market.jar"))

    private fun source(path: String) = File(kotlinRoot, path).readText()

    @Test
    fun `the Spigot main builds the bridge, hands Vault availability to the platform, starts it, feeds joins and stops it`() {
        val main = source("spigot/MarketSpigotPlugin.kt")
        assertTrue(main.contains("VaultBridge("))
        assertTrue(main.contains("vaultProbe = vaultBridge::vaultAvailable"))
        assertTrue(main.contains("vaultBridge.start()"))
        assertTrue(main.contains("vault?.onPlayerPresent(name)"))
        assertTrue(main.contains("vault?.stop()"))
    }

    @Test
    fun `the compiled main class refers to the bridge`() {
        ZipFile(jar).use { z ->
            // the main class and the classes Kotlin made of its lambdas and function references
            val text = z.entries().toList().filter { it.name.startsWith("com/panomc/plugins/market/mc/spigot/MarketSpigotPlugin") }
                .joinToString("\n") { e -> String(z.getInputStream(e).use { it.readBytes() }, Charsets.ISO_8859_1) }
            assertTrue(text.contains("com/panomc/plugins/market/mc/spigot/vault/VaultBridge"))
            assertTrue(text.contains("onPlayerPresent"))
            assertTrue(text.contains("vaultAvailable"))
        }
    }

    @Test
    fun `the bridge registers the convert and deposit sub-commands of credits under the vault feature`() {
        val bridge = source("spigot/vault/VaultBridge.kt")
        assertTrue(bridge.contains("registerSub(\"credits\", \"convert\", Feature.VAULT)"))
        assertTrue(bridge.contains("registerSub(\"credits\", \"deposit\", Feature.VAULT)"))
    }

    @Test
    fun `only the glue classes reference Vault - everything else loads without the Vault plugin`() {
        ZipFile(jar).use { z ->
            val vaultClasses = z.entries().toList().filter { it.name.startsWith("com/panomc/plugins/market/mc/spigot/vault/") && it.name.endsWith(".class") }
            assertTrue(vaultClasses.size >= 10, "the bridge classes are in the jar (${vaultClasses.size})")
            val touching = vaultClasses.filter { e ->
                String(z.getInputStream(e).use { it.readBytes() }, Charsets.ISO_8859_1).contains("net/milkbowl/vault")
            }.map { it.name.substringAfterLast('/').substringBefore('$').removeSuffix(".class") }.toSet()
            assertEquals(setOf("VaultGlue", "ProviderEconomy", "VaultServerEconomy"), touching)
        }
        val others = File(kotlinRoot, "spigot/vault").listFiles { f -> f.extension == "kt" && f.name != "VaultGlue.kt" }!!
        others.forEach { assertTrue(!it.readText().contains("net.milkbowl"), "${it.name} must not import Vault") }
        // and the rest of the component never mentions Vault classes either
        File(kotlinRoot, "core").walkTopDown().filter { it.extension == "kt" }.forEach { assertTrue(!it.readText().contains("net.milkbowl"), it.name) }
    }

    @Test
    fun `the platform reports Vault as available through the probe, not a constant`() {
        val platform = source("spigot/SpigotMcPlatform.kt")
        assertTrue(platform.contains("override fun vaultAvailable(): Boolean = vaultProbe()"))
    }
}
