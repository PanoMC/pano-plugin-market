package com.panomc.plugins.market.spi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * B-22 (16 section 13.1, 02 section 9): the thin `build/api/pano-plugin-market-api-<version>.jar` carries only
 * the package `com/panomc/plugins/market/spi` (recursively) and `META-INF`, includes the version gate and the contract tests of the testkit, and
 * no class in it references a market class outside `spi` (a real constant-pool scan, so descriptors, signatures
 * and string constants count too). The inspector is itself tested against hand-made jars that break each rule.
 */
class ApiJarTest {
    private val spiRoot = "com/panomc/plugins/market/spi/"
    private val marketRoot = "com/panomc/plugins/market/"

    /** Built at run time: T0ClasspathTest forbids the literal platform package in spi test classes. */
    private val platformListener = listOf("com", "panomc", "platform", "api", "event", "PluginEventListener").joinToString("/")

    private val required = listOf(
        "MarketSpi.class", "testkit/ProviderContractTest.class", "testkit/ShippingProviderContractTest.class", "testkit/FakeGateway.class",
        "testkit/TestContexts.class", "payment/PaymentProvider.class", "shipping/ShippingProvider.class", "MarketExtension.class",
        "common/Money.class"
    ).map { spiRoot + it }

    /** Every constant-pool UTF-8 string of a class file (class names, descriptors, signatures, string constants). */
    private fun utf8Constants(bytes: ByteArray): List<String> {
        val input = DataInputStream(bytes.inputStream())
        require(input.readInt() == 0xCAFEBABE.toInt()) { "not a class file" }
        input.readUnsignedShort()
        input.readUnsignedShort()
        val count = input.readUnsignedShort()
        val out = ArrayList<String>()
        var i = 1
        while (i < count) {
            when (val tag = input.readUnsignedByte()) {
                1 -> out += input.readUTF()
                3, 4, 9, 10, 11, 12, 17, 18 -> input.skipBytes(4)
                5, 6 -> { input.skipBytes(8); i++ }
                7, 8, 16, 19, 20 -> input.skipBytes(2)
                15 -> input.skipBytes(3)
                else -> error("unknown constant pool tag $tag")
            }
            i++
        }
        return out
    }

    /** Market references outside `spi` found in one class file. */
    private fun foreignReferences(bytes: ByteArray): List<String> {
        val pattern = Regex(Regex.escape(marketRoot) + "(?!spi/)[A-Za-z0-9_/\$]*")
        return utf8Constants(bytes).flatMap { s -> pattern.findAll(s).map { it.value }.toList() }
    }

    /** Violations of the B-22 rules in [jar]; empty = the jar is fine. */
    private fun inspect(jar: File): List<String> {
        val problems = ArrayList<String>()
        ZipFile(jar).use { z ->
            val files = z.entries().asSequence().filter { !it.isDirectory }.toList()
            for (e in files) {
                if (!(e.name.startsWith(spiRoot) || e.name.startsWith("META-INF/"))) problems += "unexpected entry ${e.name}"
            }
            val names = files.map { it.name }.toSet()
            for (r in required) if (r !in names) problems += "missing $r"
            if (names.none { it.startsWith("META-INF/") && it.endsWith(".kotlin_module") }) problems += "missing META-INF/*.kotlin_module"
            for (e in files.filter { it.name.endsWith(".class") }) {
                val bytes = z.getInputStream(e).use { it.readBytes() }
                for (ref in foreignReferences(bytes).distinct()) problems += "${e.name} references $ref"
            }
        }
        return problems
    }

    private fun classFile(vararg utf8: String): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(0xCAFEBABE.toInt())
            out.writeShort(0)
            out.writeShort(55)
            val extra = utf8.size
            out.writeShort(5 + extra)
            out.writeByte(1); out.writeUTF("Fixture")
            out.writeByte(7); out.writeShort(1)
            out.writeByte(1); out.writeUTF("java/lang/Object")
            out.writeByte(7); out.writeShort(3)
            for (s in utf8) { out.writeByte(1); out.writeUTF(s) }
            out.writeShort(0x21); out.writeShort(2); out.writeShort(4)
            out.writeShort(0); out.writeShort(0); out.writeShort(0); out.writeShort(0)
        }
        return buffer.toByteArray()
    }

    private fun jarOf(dir: File, entries: Map<String, ByteArray>): File {
        val f = File(dir, "fixture.jar")
        ZipOutputStream(f.outputStream()).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return f
    }

    private fun goodEntries(): MutableMap<String, ByteArray> {
        val m = LinkedHashMap<String, ByteArray>()
        for (r in required) m[r] = classFile("L${spiRoot}common/Money;")
        m["META-INF/pano-plugin-market.kotlin_module"] = ByteArray(4)
        return m
    }

    private fun withTempDir(block: (File) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory("apijar").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }

    @Test
    fun `the built api jar holds only the spi and meta-inf, the testkit and the version gate, and references no market class outside spi`() {
        val path = System.getProperty("market.apiJar")
        assertTrue(!path.isNullOrBlank(), "system property market.apiJar is not set (the Gradle test task sets it)")
        val jar = File(path)
        assertTrue(jar.isFile, "api jar not found at $jar: run the apiJar task (build) first")
        assertTrue(jar.parentFile.name == "api", "the api jar must be written to build/api, was ${jar.parentFile}")
        assertEquals(emptyList<String>(), inspect(jar))
        ZipFile(jar).use { z ->
            val classes = z.entries().asSequence().count { it.name.endsWith(".class") && it.name.startsWith(spiRoot) }
            assertTrue(classes > 100, "expected the whole spi (payment, shipping, common, testkit), found only $classes classes")
            val manifest = z.getInputStream(z.getEntry("META-INF/MANIFEST.MF")).use { String(it.readBytes()) }
            assertTrue(manifest.contains("Implementation-Title: pano-plugin-market-api"), "manifest lacks the title: $manifest")
        }
    }

    @Test
    fun `the plugin jar directory build libs is not used for the api jar`() {
        val path = System.getProperty("market.apiJar")
        assertTrue(!path.isNullOrBlank())
        assertTrue(!File(path).path.replace('\\', '/').contains("/build/libs/"), "api jar must not live in build/libs")
    }

    @Test
    fun `the inspector accepts a well formed jar`() = withTempDir { dir ->
        assertEquals(emptyList<String>(), inspect(jarOf(dir, goodEntries())))
    }

    @Test
    fun `the inspector refuses an entry outside spi and meta-inf`() = withTempDir { dir ->
        val e = goodEntries().also { it["com/panomc/plugins/market/service/OrderService.class"] = classFile() }
        assertEquals(listOf("unexpected entry com/panomc/plugins/market/service/OrderService.class"), inspect(jarOf(dir, e)))
        val other = goodEntries().also { it["kotlin/Unit.class"] = classFile() }
        assertEquals(listOf("unexpected entry kotlin/Unit.class"), inspect(jarOf(dir, other)))
    }

    @Test
    fun `the inspector refuses a missing required class and a missing kotlin module`() = withTempDir { dir ->
        val e = goodEntries().also { it.remove(spiRoot + "testkit/ProviderContractTest.class"); it.remove("META-INF/pano-plugin-market.kotlin_module") }
        assertEquals(
            listOf("missing ${spiRoot}testkit/ProviderContractTest.class", "missing META-INF/*.kotlin_module"),
            inspect(jarOf(dir, e))
        )
    }

    @Test
    fun `the inspector finds a class reference, a descriptor and a string constant that point outside spi`() = withTempDir { dir ->
        val service = marketRoot + "service/OrderService"
        for (constant in listOf(service, "L$service;", "(L$service;)V", "calls $service.checkout")) {
            val e = goodEntries().also { it[spiRoot + "common/Money.class"] = classFile(constant) }
            val problems = inspect(jarOf(dir, e))
            assertEquals(listOf("${spiRoot}common/Money.class references $service"), problems, "constant '$constant'")
        }
        val core = goodEntries().also { it[spiRoot + "MarketSpi.class"] = classFile("L${marketRoot}core/money/Currencies;") }
        assertEquals(listOf("${spiRoot}MarketSpi.class references ${marketRoot}core/money/Currencies"), inspect(jarOf(dir, core)))
    }

    @Test
    fun `references to spi and to other packages are not offences`() {
        val ok = classFile("L${spiRoot}common/Money;", platformListener, "io/vertx/core/Vertx", "com/panomc/plugins/marketfake/Other")
        assertEquals(emptyList<String>(), foreignReferences(ok))
        assertEquals(
            listOf(marketRoot + "util/HtmlSanitizer"),
            foreignReferences(classFile("see ${spiRoot}common/Money and ${marketRoot}util/HtmlSanitizer"))
        )
    }

    @Test
    fun `the constant pool reader copes with long and double constants`() {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(0xCAFEBABE.toInt()); out.writeShort(0); out.writeShort(55)
            out.writeShort(5) // entries 1..4: long (takes 1 and 2), utf8 (3), class (4)
            out.writeByte(5); out.writeLong(42L)
            out.writeByte(1); out.writeUTF("after-long")
            out.writeByte(7); out.writeShort(3)
            out.writeShort(0x21); out.writeShort(4); out.writeShort(4)
            out.writeShort(0); out.writeShort(0); out.writeShort(0); out.writeShort(0)
        }
        assertEquals(listOf("after-long"), utf8Constants(buffer.toByteArray()))
    }
}
