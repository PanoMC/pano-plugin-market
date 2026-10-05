package com.panomc.plugins.market.e2e.support

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Regression guard for the wiring bug the first real-instance run found (evidence/MK-080.md): the plugin's DAOs live in `plugin.pluginBeanContext`, while
 * `plugin.applicationContext` is the platform's, so `plugin.applicationContext.getBean(MarketXDao::class.java)` compiles, passes every test that builds the
 * object graph by hand and fails with `NoSuchBeanDefinitionException` (HTTP 500) on a real instance. Wiring code must go through `plugin.beans`.
 */
class PluginBeanWiringTest {
    private val source = File("src/main/kotlin")

    @Test
    fun `no market bean is looked up in the platform application context`() {
        assertTrue(source.isDirectory, "run from the market module directory: ${source.absolutePath}")

        val offenders = source.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            val text = file.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }.joinToString("\n")
            // a variable bound to the platform context in a file that also resolves a market bean from "context"
            val bound = Regex("""val\s+(\w+)\s*=\s*plugin\.applicationContext\b""").findAll(text).map { it.groupValues[1] }.toList()
            val viaVariable = bound.filter { Regex("""\b$it\.getBean\(\s*Market\w+""").containsMatchIn(text) }
            val direct = Regex("""applicationContext\.getBean\(\s*Market\w+""").findAll(text).count()

            (viaVariable.map { "${file.path}: `$it` is plugin.applicationContext but resolves a Market bean" } + List(direct) { "${file.path}: applicationContext.getBean(Market...)" }).asSequence()
        }.toList()

        assertTrue(offenders.isEmpty(), "market beans must be looked up with plugin.beans: $offenders")
    }
}
