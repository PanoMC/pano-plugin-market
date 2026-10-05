package com.panomc.plugins.market.mc.feature

import com.panomc.plugins.market.mc.core.feature.LocalConfig
import com.panomc.plugins.market.mc.core.feature.Messages
import com.panomc.plugins.market.mc.core.feature.MiniYaml
import com.panomc.plugins.market.mc.core.feature.Msg
import com.panomc.plugins.market.mc.core.feature.flatTexts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URLClassLoader
import java.util.zip.ZipFile

/** The real market jar carries the config and the lang files, byte for byte what is in the source tree. */
class JarResourcesTest {
    private val jar = File(System.getProperty("market.jar"))

    @Test
    fun `the jar ships mc config yml and the three lang files identical to the sources`() {
        assertTrue(jar.isFile, "market jar missing: $jar")
        ZipFile(jar).use { z ->
            val names = listOf("mc/config.yml") + Messages.KNOWN.map { "mc/lang/$it.yml" }
            names.forEach { n ->
                val entry = z.getEntry(n)
                assertNotNull(entry, "$n is not in the jar")
                val inJar = z.getInputStream(entry).use { it.readBytes() }
                assertEquals(File(ShippedResources.root, n).readText(Charsets.UTF_8), String(inJar, Charsets.UTF_8), n)
            }
        }
    }

    @Test
    fun `read through a class loader the way the platform mains do, the files parse and the keys are complete`() {
        URLClassLoader(arrayOf(jar.toURI().toURL()), null).use { loader ->
            val read = com.panomc.plugins.market.mc.core.feature.ResourceFiles.reader(loader)
            val config = LocalConfig.parse(read("mc/config.yml")!!)
            assertEquals(LocalConfig(), config)
            Messages.KNOWN.forEach { l ->
                val keys = flatTexts(MiniYaml.parse(read("mc/lang/$l.yml")!!)).keys
                assertEquals(Msg.ALL.toSet(), keys, l)
            }
            assertEquals(null, read("mc/lang/xx.yml"))
        }
    }
}
