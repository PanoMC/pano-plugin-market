package com.panomc.plugins.market.mc.fabric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

class FabricJarRulesTest {
    private val jar = File(System.getProperty("market.fabricJar"))
    private val version = System.getProperty("market.version")
    private val resources = File(System.getProperty("market.mcResources"))

    private val expected: Map<String, ByteArray> = listOf("mc/config.yml", "mc/lang/en-US.yml", "mc/lang/tr.yml", "mc/lang/ru.yml")
        .associateWith { File(resources, it).readBytes() }

    @Test
    fun `the real jar passes every rule`() {
        assertTrue(jar.isFile, "no jar at $jar")
        assertEquals(emptyList<String>(), FabricJarRules.check(jar, version, expected))
    }

    @Test
    fun `the jar has the versioned name in build mc`() {
        assertEquals("pano-plugin-market-fabric-$version.jar", jar.name)
        assertEquals("mc", jar.parentFile.name)
        assertEquals("build", jar.parentFile.parentFile.name)
    }

    @Test
    fun `the manifest names the version`() {
        java.util.zip.ZipFile(jar).use { z ->
            val m = Manifest(z.getInputStream(z.getEntry("META-INF/MANIFEST.MF")))
            assertEquals(version, m.mainAttributes.getValue("VERSION"))
        }
    }

    @Test
    fun `the kotlin references of the real jar point at the Pano mod's shaded copy`() {
        var shadowed = 0
        java.util.zip.ZipFile(jar).use { z ->
            for (e in z.entries().asSequence().filter { it.name.endsWith(".class") }) {
                val body = String(z.getInputStream(e).use { it.readBytes() }, Charsets.ISO_8859_1)
                if (body.contains("com/panomc/shadow/kotlin/jvm/internal/Intrinsics")) shadowed++
            }
        }
        assertTrue(shadowed > 10, "only $shadowed classes call the relocated Intrinsics")
    }

    // ---- the rules detect bad jars (the real jar being green proves nothing if the rules cannot fail) ----------------

    /** A real (empty) class file that holds one field of each type in [refs] (internal names), so every one is a reference. */
    private fun classBytes(vararg refs: String, major: Int = 68): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(major, Opcodes.ACC_PUBLIC, "synthetic/C${counter++}", null, "java/lang/Object", null)
        refs.forEachIndexed { i, r -> cw.visitField(Opcodes.ACC_PUBLIC, "f$i", "L$r;", null, null).visitEnd() }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private var counter = 0

    private fun build(dir: File, tweak: MutableMap<String, ByteArray>.() -> Unit): File {
        val good = linkedMapOf(
            "fabric.mod.json" to """{"id":"panomarket","version":"$version","environment":"server","entrypoints":{"server":["${FabricJarRules.ENTRYPOINT}"]},"depends":{"pano":"*"}}""".toByteArray(),
            "com/panomc/plugins/market/mc/fabric/MarketFabricMod.class" to classBytes("com/panomc/shadow/kotlin/jvm/internal/Intrinsics", "com/panomc/shadow/kotlinx/coroutines/Job", "com/panomc/shadow/gson/Gson")
        )
        expected.forEach { (k, v) -> good[k] = v }
        val files: MutableMap<String, ByteArray> = good
        files.tweak()
        val f = File.createTempFile("bad", ".jar", dir)
        JarOutputStream(f.outputStream(), Manifest().apply { mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0" }).use { jo ->
            for ((n, b) in files) {
                jo.putNextEntry(JarEntry(n))
                jo.write(b)
                jo.closeEntry()
            }
        }
        return f
    }

    private fun violations(dir: File, tweak: MutableMap<String, ByteArray>.() -> Unit) = FabricJarRules.check(build(dir, tweak), version, expected)

    @Test
    fun `a well formed synthetic jar is clean`(@TempDir dir: File) {
        assertEquals(emptyList<String>(), violations(dir) {})
    }

    @Test
    fun `an unrelocated kotlin reference is found`(@TempDir dir: File) {
        val v = violations(dir) { put("com/panomc/plugins/market/mc/core/Bad.class", classBytes("kotlin/jvm/internal/Intrinsics")) }
        assertTrue(v.any { it.contains("Bad.class") && it.contains("unrelocated") }, v.toString())
    }

    @Test
    fun `unrelocated kotlinx and gson references are found`(@TempDir dir: File) {
        val v = violations(dir) {
            put("com/panomc/plugins/market/mc/core/A.class", classBytes("kotlinx/coroutines/Job"))
            put("com/panomc/plugins/market/mc/core/B.class", classBytes("com/google/gson/Gson"))
        }
        assertTrue(v.any { it.contains("A.class") }, v.toString())
        assertTrue(v.any { it.contains("B.class") }, v.toString())
    }

    @Test
    fun `a bundled kotlin class is found`(@TempDir dir: File) {
        val v = violations(dir) { put("kotlin/Unit.class", classBytes()) }
        assertTrue(v.any { it.contains("unexpected entry kotlin/Unit.class") }, v.toString())
    }

    @Test
    fun `a bundled Core class and a Spigot class are found`(@TempDir dir: File) {
        val v = violations(dir) {
            put("com/panomc/plugins/pano/core/Pano.class", classBytes())
            put("com/panomc/plugins/market/mc/spigot/MarketSpigotPlugin.class", classBytes())
        }
        assertTrue(v.any { it.contains("pano/core/Pano.class") }, v.toString())
        assertTrue(v.any { it.contains("mc/spigot/MarketSpigotPlugin.class") }, v.toString())
    }

    @Test
    fun `a reference to the platform or to the Bukkit api is found`(@TempDir dir: File) {
        val v = violations(dir) {
            put("com/panomc/plugins/market/mc/core/P.class", classBytes("com/panomc/platform/Main"))
            put("com/panomc/plugins/market/mc/core/Q.class", classBytes("org/bukkit/Bukkit"))
            put("com/panomc/plugins/market/mc/core/R.class", classBytes("com/panomc/plugins/market/spi/Thing"))
        }
        assertTrue(v.any { it.contains("P.class") }, v.toString())
        assertTrue(v.any { it.contains("Q.class") }, v.toString())
        assertTrue(v.any { it.contains("R.class") && it.contains("outside mc") }, v.toString())
    }

    @Test
    fun `a too new class is found`(@TempDir dir: File) {
        val v = violations(dir) { put("com/panomc/plugins/market/mc/core/New.class", classBytes(major = 69)) }
        assertTrue(v.any { it.contains("class major 69") }, v.toString())
    }

    @Test
    fun `a jar without any relocated reference is found`(@TempDir dir: File) {
        val v = violations(dir) { put("com/panomc/plugins/market/mc/fabric/MarketFabricMod.class", classBytes("java/lang/String")) }
        assertTrue(v.any { it.contains("relocation did not run") }, v.toString())
    }

    @Test
    fun `a broken descriptor is found`(@TempDir dir: File) {
        assertTrue(violations(dir) { put("fabric.mod.json", """{"id":"panomarket","version":"${'$'}{version}"}""".toByteArray()) }.any { it.contains("placeholder") })
        assertTrue(violations(dir) { put("fabric.mod.json", """{"id":"panomarket","version":"0.0.1","environment":"server","entrypoints":{"server":["${FabricJarRules.ENTRYPOINT}"]},"depends":{"pano":"*"}}""".toByteArray()) }.any { it.contains("version") })
        assertTrue(violations(dir) { remove("fabric.mod.json") }.any { it.contains("fabric.mod.json missing") })
        assertTrue(violations(dir) { remove("com/panomc/plugins/market/mc/fabric/MarketFabricMod.class") }.any { it.contains("entry point class") })
    }

    @Test
    fun `a changed or missing shared resource is found`(@TempDir dir: File) {
        assertTrue(violations(dir) { put("mc/config.yml", "changed".toByteArray()) }.any { it.contains("mc/config.yml differs") })
        assertTrue(violations(dir) { remove("mc/lang/tr.yml") }.any { it.contains("mc/lang/tr.yml missing") })
    }

    @Test
    fun `an unexpected resource is found`(@TempDir dir: File) {
        assertTrue(violations(dir) { put("plugin.yml", "name: x".toByteArray()) }.any { it.contains("unexpected entry plugin.yml") })
    }
}
