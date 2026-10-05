package com.panomc.plugins.market.mc.jarrules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** The descriptor sources keep the placeholder that `processMcResources` fills in (the jar test checks the result). */
class DescriptorSourceTest {
    private val dir = File(System.getProperty("market.mcResources"))

    @Test
    fun `descriptor sources carry the version placeholder and nothing else templated`() {
        listOf("plugin.yml", "bungee.yml", "velocity-plugin.json").forEach { f ->
            val t = File(dir, f).readText()
            assertEquals(1, Regex("\\$\\{").findAll(t).count(), "$f: exactly one placeholder")
            assertTrue(t.contains("\${version}"), "$f: \${version}")
        }
    }

    @Test
    fun `descriptors are hand written, no annotation processor descriptor exists`() {
        assertTrue(File(dir, "plugin.yml").isFile && File(dir, "bungee.yml").isFile && File(dir, "velocity-plugin.json").isFile)
    }
}
