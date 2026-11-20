package com.panomc.plugins.market.mc.jarrules

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.zip.ZipFile

/** Jar rules of MC-01 against the real platform jars and the real market jar (built before `mcTest`). */
class JarRulesTest {
    private val version = System.getProperty("market.version")

    private fun read(jar: File): Map<String, ByteArray> {
        assertTrue(jar.isFile, "jar missing: $jar")
        return ZipFile(jar).use { z -> z.entries().asSequence().filter { !it.isDirectory }.associate { it.name to z.getInputStream(it).use { s -> s.readBytes() } } }
    }

    private val jars: Map<String, Map<String, ByteArray>> by lazy {
        JarScan.DESCRIPTORS.keys.associateWith { read(File(System.getProperty("market.mcJar.$it"))) }
    }

    private val market: Map<String, ByteArray> by lazy { read(File(System.getProperty("market.jar"))) }

    private fun text(platform: String, name: String) =
        String(requireNotNull(jars.getValue(platform)[name]) { "$name missing at the root of the $platform jar" }, Charsets.UTF_8)

    @Test
    fun `every real platform jar passes every rule`() {
        JarScan.DESCRIPTORS.keys.forEach { p -> assertEquals(emptyList<String>(), JarScan.violations(jars.getValue(p), version, p), p) }
    }

    @Test
    fun `the market jar carries no Minecraft class or descriptor and bundles the three platform jars`() {
        assertEquals(emptyList<String>(), JarScan.marketViolations(market))
        JarScan.DESCRIPTORS.keys.forEach { p ->
            val bundled = market["mc/pano-plugin-market-$p.jar"]
            assertTrue(bundled != null, "mc/pano-plugin-market-$p.jar in the market jar")
            assertTrue(bundled!!.contentEquals(File(System.getProperty("market.mcJar.$p")).readBytes()), "$p jar bundled byte for byte")
        }
    }

    @Test
    fun `each platform jar has its own descriptor at the root with the version filled in`() {
        JarScan.DESCRIPTORS.forEach { (p, f) ->
            assertTrue(text(p, f).contains(version), "$f carries version $version")
            assertTrue(!text(p, f).contains("\${"), "$f has no placeholder left")
        }
    }

    @Test
    fun `plugin yml is a valid Spigot descriptor`() {
        // Parsed with plain SnakeYAML: the 1.8.8 PluginDescriptionFile needs SnakeYAML 1.x, which BungeeCord's 2.x on the same
        // test classpath replaces; the keys below are the ones Bukkit reads.
        val raw = Yaml().load<Map<String, Any?>>(text("spigot", "plugin.yml"))
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
        val raw = Yaml().load<Map<String, Any?>>(text("bungee", "bungee.yml"))
        assertEquals("PanoMarket", raw["name"])
        assertEquals(version, raw["version"].toString())
        assertEquals("com.panomc.plugins.market.mc.bungee.MarketBungeePlugin", raw["main"])
        assertEquals(listOf("Pano"), raw["depends"])
        assertEquals(listOf("LuckPerms"), raw["softDepends"])
    }

    @Test
    fun `velocity plugin json has a valid id and a required Pano dependency`() {
        val o = JsonParser.parseString(text("velocity", "velocity-plugin.json")).asJsonObject
        assertEquals("panomarket", o["id"].asString)
        assertTrue(Regex("[a-z][a-z0-9-_]{0,63}").matches(o["id"].asString), "Velocity plugin id pattern")
        assertEquals(version, o["version"].asString)
        assertEquals("com.panomc.plugins.market.mc.velocity.MarketVelocityPlugin", o["main"].asString)
        val deps = o["dependencies"].asJsonArray.associate { it.asJsonObject["id"].asString to it.asJsonObject["optional"].asBoolean }
        assertEquals(mapOf("pano" to false, "luckperms" to true), deps)
    }

    @Test
    fun `each platform jar ships its main class and the wire classes as Java 11 bytecode, and no kotlin entry`() {
        val mains = mapOf(
            "spigot" to "com/panomc/plugins/market/mc/spigot/MarketSpigotPlugin.class",
            "bungee" to "com/panomc/plugins/market/mc/bungee/MarketBungeePlugin.class",
            "velocity" to "com/panomc/plugins/market/mc/velocity/MarketVelocityPlugin.class"
        )
        jars.forEach { (p, entries) ->
            assertTrue(entries.containsKey(mains.getValue(p)), "$p main class")
            val wire = entries.filterKeys { it.startsWith("com/panomc/plugins/market/mc/core/wire/") && it.endsWith(".class") }
            assertTrue(wire.size >= 12, "$p: wire classes in the jar: ${wire.size}")
            wire.forEach { (n, b) -> assertEquals(55, JarScan.classMajor(b), "$p: $n targets Java 11") }
            assertTrue(entries.keys.none { it.startsWith("kotlin/") || it.startsWith("kotlinx/") }, "$p: no kotlin/ entry")
        }
    }

    @Test
    fun `the rules detect a bad jar`() {
        fun classBytes(major: Int, vararg refs: String): ByteArray =
            byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0, 0, (major shr 8).toByte(), (major and 0xff).toByte()) + refs.joinToString(" ").toByteArray(Charsets.ISO_8859_1)

        val spigot = mapOf(
            "plugin.yml" to "version: 1.0".toByteArray(),
            "${JarScan.MC}core/Ok.class" to classBytes(55, "${JarScan.MC}core/Other"),
            "${JarScan.MC}spigot/Main.class" to classBytes(55, "${JarScan.MC}core/Ok")
        )
        assertEquals(emptyList<String>(), JarScan.violations(spigot, "1.0", "spigot"))
        val velocity = mapOf(
            "velocity-plugin.json" to "{\"version\":\"1.0\"}".toByteArray(),
            "${JarScan.MC}core/Ok.class" to classBytes(55),
            "${JarScan.MC_VELOCITY}V.class" to classBytes(61)
        )
        assertEquals(emptyList<String>(), JarScan.violations(velocity, "1.0", "velocity"))

        fun with(name: String, bytes: ByteArray) = JarScan.violations(spigot + (name to bytes), "1.0", "spigot")
        assertTrue(with("${JarScan.MC}core/Bad.class", classBytes(55, "com/panomc/platform/user/User")).any { it.contains("references com/panomc/platform/") })
        assertTrue(with("${JarScan.MC}core/Bad.class", classBytes(55, "${JarScan.MARKET}service/OrderService")).any { it.contains("outside mc") })
        assertTrue(with("${JarScan.MARKET}service/Main.class", classBytes(55)).any { it.contains("is outside mc.**") })
        assertTrue(with("${JarScan.MC}core/Bad.class", classBytes(61)).any { it.contains("class major 61") })
        assertTrue(with("${JarScan.MC}bungee/B.class", classBytes(55)).any { it.contains("foreign platform class") })
        assertTrue(with("bungee.yml", "version: 1.0".toByteArray()).any { it.contains("foreign descriptor bungee.yml") })
        assertTrue(JarScan.violations(velocity + ("${JarScan.MC_VELOCITY}Bad.class" to classBytes(62)), "1.0", "velocity").any { it.contains("class major 62") })
        assertTrue(with("kotlin/Unit.class", classBytes(52)).any { it.contains("forbidden entry kotlin/Unit.class") })
        assertTrue(with("com/panomc/plugins/pano/core/Pano.class", classBytes(55)).any { it.contains("bundled Minecraft-side library") })
        assertTrue(with("org/bukkit/Bukkit.class", classBytes(52)).any { it.contains("bundled Minecraft-side library") })
        assertTrue(JarScan.violations(spigot - "plugin.yml", "1.0", "spigot").any { it.contains("plugin.yml missing") })
        assertTrue(JarScan.violations(spigot.filterKeys { !it.contains("/core/") }, "1.0", "spigot").any { it.contains("no mc.core class") })
        assertTrue(with("plugin.yml", "version: \${version}".toByteArray()).any { it.contains("unexpanded placeholder") })
        assertTrue(with("plugin.yml", "version: 2.0".toByteArray()).any { it.contains("does not carry the version") })

        val market = mapOf("${JarScan.MARKET}service/Main.class" to classBytes(55, "${JarScan.MARKET}service/Other"))
        assertEquals(emptyList<String>(), JarScan.marketViolations(market))
        assertTrue(JarScan.marketViolations(market + ("${JarScan.MC}core/Ok.class" to classBytes(55))).any { it.contains("Minecraft class") })
        assertTrue(JarScan.marketViolations(market + ("plugin.yml" to ByteArray(0))).any { it.contains("descriptor plugin.yml") })
        assertTrue(JarScan.marketViolations(market + ("${JarScan.MARKET}service/Bad.class" to classBytes(55, "${JarScan.MC}core/Ok"))).any { it.contains("(main) references") })
    }
}
