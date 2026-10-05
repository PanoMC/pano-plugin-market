package com.panomc.plugins.market.spi.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tier T0 stays pure (17 section 11.1): the main classes of `core.*` and `spi.*` and the test classes of
 * `core.*` / `spi.*` must not reference the platform, Spring or the SQL client. The scan reads the compiled
 * `.class` files and looks for the forbidden internal names in the raw bytes (constant pool UTF-8 entries are
 * stored as plain modified UTF-8, so a reference to such a class always contains the name).
 */
class T0ClasspathTest {
    private val base = listOf("com", "panomc", "plugins", "market")

    /** Built at run time so this class's own constant pool does not contain the names it forbids. */
    private val forbidden: List<String> = listOf(
        listOf("com", "panomc", "platform"), listOf("org", "springframework"), listOf("io", "vertx", "sqlclient")
    ).map { it.joinToString("/") + "/" }

    private fun root(marker: Class<*>): Path {
        val url = marker.protectionDomain.codeSource.location
        return Path.of(url.toURI())
    }

    private fun classFiles(root: Path, vararg packages: String): List<File> = packages.flatMap { pkg ->
        val dir = root.resolve((base + pkg.split('.')).joinToString("/"))
        if (!Files.isDirectory(dir)) emptyList()
        else Files.walk(dir).use { s -> s.filter { it.toString().endsWith(".class") }.map { it.toFile() }.collect(java.util.stream.Collectors.toList()) }
    }

    private fun offenders(files: List<File>): List<String> = files.flatMap { f ->
        val bytes = f.readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        forbidden.filter { text.contains(it) }.map { "${f.name} references $it" }
    }

    @Test
    fun `main core and spi classes are free of platform spring and sqlclient references`() {
        val main = root(com.panomc.plugins.market.spi.MarketSpi::class.java)
        val files = classFiles(main, "core", "spi")
        assertTrue(files.size > 20, "expected the core / spi classes, found ${files.size} under $main")
        assertEquals(emptyList<String>(), offenders(files))
    }

    @Test
    fun `core and spi test classes are free of platform spring and sqlclient references`() {
        val test = root(T0ClasspathTest::class.java)
        val files = classFiles(test, "core", "spi")
        assertTrue(files.isNotEmpty(), "expected test classes under $test")
        assertEquals(emptyList<String>(), offenders(files))
    }

    @Test
    fun `the scan detects a reference`() {
        val dir = Files.createTempDirectory("t0scan").toFile()
        try {
            val f = File(dir, "Bad.class")
            f.writeBytes(("xx" + forbidden[1] + "Foo yy").toByteArray(Charsets.ISO_8859_1))
            assertEquals(listOf("Bad.class references ${forbidden[1]}"), offenders(listOf(f)))
            val ok = File(dir, "Ok.class")
            ok.writeBytes("io/vertx/core/Vertx".toByteArray())
            assertEquals(emptyList<String>(), offenders(listOf(ok)))
        } finally {
            dir.deleteRecursively()
        }
    }
}
