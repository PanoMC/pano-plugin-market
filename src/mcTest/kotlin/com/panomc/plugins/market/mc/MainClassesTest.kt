package com.panomc.plugins.market.mc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** The three main classes compile against the pinned APIs (that is the compiler's job) and are the ones the descriptors name. */
class MainClassesTest {
    private val dir = File(System.getProperty("market.mcResources"))

    private fun main(file: String, pattern: Regex): String = pattern.find(File(dir, file).readText())!!.groupValues[1]

    @Test
    fun `Spigot main extends JavaPlugin and is what plugin yml names`() {
        val name = main("plugin.yml", Regex("(?m)^main:\\s*(\\S+)"))
        val cls = Class.forName(name)
        assertTrue(org.bukkit.plugin.java.JavaPlugin::class.java.isAssignableFrom(cls))
        assertEquals("com.panomc.plugins.market.mc.spigot.MarketSpigotPlugin", name)
    }

    @Test
    fun `Bungee main extends Plugin and is what bungee yml names`() {
        val name = main("bungee.yml", Regex("(?m)^main:\\s*(\\S+)"))
        val cls = Class.forName(name)
        assertTrue(net.md_5.bungee.api.plugin.Plugin::class.java.isAssignableFrom(cls))
    }

    @Test
    fun `Velocity main is what velocity-plugin json names, has an injectable constructor and subscribes to the lifecycle events`() {
        val name = main("velocity-plugin.json", Regex("\"main\"\\s*:\\s*\"([^\"]+)\""))
        val cls = Class.forName(name)
        val ctor = cls.constructors.single()
        assertTrue(ctor.isAnnotationPresent(com.google.inject.Inject::class.java))
        assertEquals(3, ctor.parameterCount)
        val subscribed = cls.methods.filter { it.isAnnotationPresent(com.velocitypowered.api.event.Subscribe::class.java) }.map { it.parameterTypes.single().simpleName }.toSet()
        assertEquals(setOf("ProxyInitializeEvent", "ProxyShutdownEvent", "ServerConnectedEvent", "DisconnectEvent"), subscribed)
        assertEquals(17, java.io.DataInputStream(cls.getResourceAsStream("/" + name.replace('.', '/') + ".class")!!).let { it.readInt(); it.readUnsignedShort(); it.readUnsignedShort() - 44 })
    }

    @Test
    fun `the descriptors depend on Pano`() {
        assertTrue(File(dir, "plugin.yml").readText().contains("depend: [ Pano ]"))
        assertTrue(File(dir, "bungee.yml").readText().contains("depends:\n  - Pano"))
        assertTrue(File(dir, "velocity-plugin.json").readText().contains("\"id\": \"pano\", \"optional\": false"))
    }
}
