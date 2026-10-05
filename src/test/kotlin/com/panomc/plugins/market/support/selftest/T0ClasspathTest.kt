package com.panomc.plugins.market.support.selftest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.io.File
import java.io.InputStream

/**
 * Keeps tier T0 honest although every host library is on the one `test` classpath (17 section 2, 11.1):
 * the constant pools of the `core.*` and `spi.*` classes (main and test) must not mention the platform,
 * Spring or the Vert.x SQL client.
 */
class T0ClasspathTest {
    private val base = "com/panomc/plugins/market"
    private val forbidden = listOf("com/panomc/platform/", "org/springframework/", "io/vertx/sqlclient/")

    /** Reads every CONSTANT_Utf8 and returns them (class names, descriptors, strings, member names). */
    private fun utf8Constants(input: InputStream): List<String> {
        val d = DataInputStream(input.buffered())
        require(d.readInt() == 0xCAFEBABE.toInt()) { "not a class file" }
        d.readUnsignedShort(); d.readUnsignedShort()
        val count = d.readUnsignedShort()
        val out = ArrayList<String>()
        var i = 1
        while (i < count) {
            when (val tag = d.readUnsignedByte()) {
                1 -> out += d.readUTF()
                3, 4, 9, 10, 11, 12, 17, 18 -> d.skipBytes(4)
                5, 6 -> { d.skipBytes(8); i++ }
                7, 8, 16, 19, 20 -> d.skipBytes(2)
                15 -> d.skipBytes(3)
                else -> error("unknown constant pool tag $tag")
            }
            i++
        }
        return out
    }

    private fun violations(classFile: File): List<String> =
        classFile.inputStream().use { utf8Constants(it) }.filter { c -> forbidden.any { c.contains(it) } }

    /** Directories of the main and test class output that hold the given package. */
    private fun roots(pkg: String): List<File> =
        Thread.currentThread().contextClassLoader.getResources("$base/$pkg").toList()
            .filter { it.protocol == "file" }
            .map { File(it.toURI()) }

    private fun scan(): Pair<Int, Map<String, List<String>>> {
        var scanned = 0
        val bad = LinkedHashMap<String, List<String>>()
        for (pkg in listOf("core", "spi")) {
            for (root in roots(pkg)) {
                root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { f ->
                    scanned++
                    val v = violations(f)
                    if (v.isNotEmpty()) bad[f.relativeTo(root.parentFile).path] = v
                }
            }
        }
        return scanned to bad
    }

    @Test
    fun `core and spi classes reference neither the platform nor Spring nor the sql client`() {
        val (scanned, bad) = scan()
        assertTrue(scanned > 0, "no core / spi class found on the classpath")
        assertEquals(emptyMap<String, List<String>>(), bad)
    }

    @Test
    fun `both main and test class directories are covered`() {
        val dirs = roots("core")
        assertTrue(dirs.any { it.path.contains("classes/kotlin/main") || it.path.contains("/main") }, "main core classes not found: $dirs")
        assertTrue(dirs.any { it.path.contains("/test") }, "test core classes not found: $dirs")
    }

    @Test
    fun `the scanner flags a class that does reference a forbidden package`() {
        // This very class names all three packages in its constant pool, so the scanner must report it.
        val self = File(File(T0ClasspathTest::class.java.protectionDomain.codeSource.location.toURI()), T0ClasspathTest::class.java.name.replace('.', '/') + ".class")
        assertTrue(self.isFile, self.path)
        assertEquals(forbidden.toSet(), violations(self).map { v -> forbidden.first { v.contains(it) } }.toSet())
    }
}
