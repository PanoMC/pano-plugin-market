package com.panomc.plugins.market.mc.jarrules

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.zip.ZipFile

/** Jar rules of MC-01 against the real market jar (built by `shadowJar`, which `mcTest` depends on). */
class JarRulesTest {
    private val version = System.getProperty("market.version")
    private val jar = File(System.getProperty("market.jar"))

    private val entries: Map<String, ByteArray> by lazy {
        assertTrue(jar.isFile, "market jar missing: $jar")
        ZipFile(jar).use { z -> z.entries().asSequence().filter { !it.isDirectory }.associate { it.name to z.getInputStream(it).use { s -> s.readBytes() } } }
    }

    private fun text(name: String) = String(requireNotNull(entries[name]) { "$name missing at the jar root" }, Charsets.UTF_8)

    @Test
    fun `the real jar passes every rule`() {
        assertEquals(emptyList<String>(), JarScan.violations(entries, version))
    }

    @Test
    fun `the three descriptors sit at the jar root with the version filled in`() {
        listOf("plugin.yml", "bungee.yml", "velocity-plugin.json").forEach { f ->
            assertTrue(entries.containsKey(f), "$f at the jar root")
            assertTrue(text(f).contains(version), "$f carries version $version")
            assertTrue(!text(f).contains("\${"), "$f has no placeholder left")
        }
    }

    @Test
    fun `plugin yml is a valid Spigot descriptor`() {
        // Parsed with plain SnakeYAML: the 1.8.8 PluginDescriptionFile needs SnakeYAML 1.x, which BungeeCord's 2.x on the same
        // test classpath replaces; the keys below are the ones Bukkit reads.
        val raw = Yaml().load<Map<String, Any?>>(text("plugin.yml"))
        assertEquals("PanoMarket", raw["name"])
        assertEquals(version, raw["version"].toString())
        assertEquals("com.panomc.plugins.market.mc.spigot.MarketSpigotPlugin", raw["main"])
        assertEquals(listOf("Pano"), raw["depend"])
        assertEquals(listOf("LuckPerms", "Vault", "PlaceholderAPI", "AuthMe", "FastLogin", "PremiumLogin"), raw["softdepend"])
        assertEquals("1.13", raw["api-version"].toString())
        assertEquals(true, raw["folia-supported"])
        assertTrue(Regex("[A-Za-z0-9_.-]+").matches(raw["name"].toString()), "Bukkit plugin name pattern")
    }

    @Test
    fun `bungee yml names the proxy main class and depends on Pano`() {
        val raw = Yaml().load<Map<String, Any?>>(text("bungee.yml"))
        assertEquals("PanoMarket", raw["name"])
        assertEquals(version, raw["version"].toString())
        assertEquals("com.panomc.plugins.market.mc.bungee.MarketBungeePlugin", raw["main"])
        assertEquals(listOf("Pano"), raw["depends"])
        assertEquals(listOf("LuckPerms"), raw["softDepends"])
    }

    @Test
    fun `velocity plugin json has a valid id and a required Pano dependency`() {
        val o = JsonParser.parseString(text("velocity-plugin.json")).asJsonObject
        assertEquals("panomarket", o["id"].asString)
        assertTrue(Regex("[a-z][a-z0-9-_]{0,63}").matches(o["id"].asString), "Velocity plugin id pattern")
        assertEquals(version, o["version"].asString)
        assertEquals("com.panomc.plugins.market.mc.velocity.MarketVelocityPlugin", o["main"].asString)
        val deps = o["dependencies"].asJsonArray.associate { it.asJsonObject["id"].asString to it.asJsonObject["optional"].asBoolean }
        assertEquals(mapOf("pano" to false, "luckperms" to true), deps)
    }

    @Test
    fun `the wire classes ship in the jar as Java 11 bytecode and the jar has no kotlin entry`() {
        val wire = entries.filterKeys { it.startsWith("com/panomc/plugins/market/mc/core/wire/") && it.endsWith(".class") }
        assertTrue(wire.size >= 12, "wire classes in the jar: ${wire.size}")
        wire.forEach { (n, b) -> assertEquals(55, JarScan.classMajor(b), "$n targets Java 11") }
        assertTrue(entries.keys.none { it.startsWith("kotlin/") || it.startsWith("kotlinx/") }, "no kotlin/ entry")
    }

    @Test
    fun `the rules detect a bad jar`() {
        fun classBytes(major: Int, vararg refs: String): ByteArray =
            byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0, 0, (major shr 8).toByte(), (major and 0xff).toByte()) + refs.joinToString(" ").toByteArray(Charsets.ISO_8859_1)

        val good = mapOf(
            "plugin.yml" to "version: 1.0".toByteArray(), "bungee.yml" to "version: 1.0".toByteArray(), "velocity-plugin.json" to "{\"version\":\"1.0\"}".toByteArray(),
            "${JarScan.MC}core/Ok.class" to classBytes(55, "${JarScan.MC}core/Other"),
            "${JarScan.MC_VELOCITY}V.class" to classBytes(61),
            "${JarScan.MARKET}service/Main.class" to classBytes(55, "${JarScan.MARKET}service/Other")
        )
        assertEquals(emptyList<String>(), JarScan.violations(good, "1.0"))

        fun with(name: String, bytes: ByteArray) = JarScan.violations(good + (name to bytes), "1.0")
        assertTrue(with("${JarScan.MC}core/Bad.class", classBytes(55, "com/panomc/platform/user/User")).any { it.contains("references com/panomc/platform/") })
        assertTrue(with("${JarScan.MC}core/Bad.class", classBytes(55, "${JarScan.MARKET}service/OrderService")).any { it.contains("outside mc") })
        assertTrue(with("${JarScan.MARKET}service/Bad.class", classBytes(55, "${JarScan.MC}core/Ok")).any { it.contains("(main) references") })
        assertTrue(with("${JarScan.MC}core/Bad.class", classBytes(61)).any { it.contains("class major 61") })
        assertTrue(with("${JarScan.MARKET}service/Bad.class", classBytes(61)).any { it.contains("class major 61") })
        assertTrue(with("${JarScan.MC_VELOCITY}Bad.class", classBytes(62)).any { it.contains("class major 62") })
        assertTrue(with("kotlin/Unit.class", classBytes(52)).any { it.contains("forbidden entry kotlin/Unit.class") })
        assertTrue(with("com/panomc/plugins/pano/core/Pano.class", classBytes(55)).any { it.contains("bundled Minecraft-side library") })
        assertTrue(with("org/bukkit/Bukkit.class", classBytes(52)).any { it.contains("bundled Minecraft-side library") })
        assertTrue((good - "bungee.yml").let { JarScan.violations(it, "1.0") }.any { it.contains("bungee.yml missing") })
        assertTrue((good + ("plugin.yml" to "version: \${version}".toByteArray())).let { JarScan.violations(it, "1.0") }.any { it.contains("unexpanded placeholder") })
        assertTrue((good + ("plugin.yml" to "version: 2.0".toByteArray())).let { JarScan.violations(it, "1.0") }.any { it.contains("does not carry the version") })
    }
}
