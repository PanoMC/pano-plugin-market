package com.panomc.plugins.market.i18n

import com.panomc.plugins.market.core.time.Clock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class MarketI18nTest {
    private class TestClock(var ms: Long = 0) : Clock {
        override fun now() = ms
    }

    private class Overrides(var data: Map<String, Map<String, String>> = emptyMap(), var fail: Boolean = false) : OverrideSource {
        var calls = 0
        override suspend fun load(locale: String): Map<String, String> {
            calls++
            if (fail) error("db down")
            return data[locale] ?: emptyMap()
        }
    }

    private val bundles = mapOf(
        "tr" to mapOf("a" to "tr-a", "b" to "tr-b", "hello" to "Merhaba {name}"),
        "en-US" to mapOf("a" to "en-a", "b" to "en-b", "c" to "en-c", "hello" to "Hello {name}"),
        "ru" to mapOf("a" to "ru-a"),
    )

    private fun i18n(o: Overrides = Overrides(), clock: Clock = TestClock(), warns: MutableList<String> = mutableListOf()) =
        MarketI18n(bundles, o, clock) { warns.add(it) }

    @Test
    fun `override wins over exact file`() = runBlocking {
        val o = Overrides(mapOf("tr" to mapOf("plugins.pano-plugin-market.a" to "override", "other.a" to "x")))
        assertEquals("override", i18n(o).t("tr", "a"))
        assertEquals("tr-b", i18n(o).t("tr", "b"))
    }

    @Test
    fun `exact then language then en-US then key`() = runBlocking {
        val i = i18n()
        assertEquals("tr-a", i.t("tr", "a"))
        assertEquals("tr-a", i.t("tr-TR", "a"))
        assertEquals("en-a", i.t("en-GB", "a"))
        assertEquals("en-c", i.t("tr", "c"))
        assertEquals("en-c", i.t("de", "c"))
        assertEquals("ru-a", i.t("ru-RU", "a"))
        assertEquals("zzz", i.t("tr", "zzz"))
    }

    @Test
    fun `missing key warns once per key`() = runBlocking {
        val w = mutableListOf<String>()
        val i = i18n(warns = w)
        i.t("tr", "nope"); i.t("en-US", "nope"); i.t("tr", "nope2")
        assertEquals(2, w.size)
    }

    @Test
    fun `interpolation replaces known placeholders and leaves unknown ones`() = runBlocking {
        val i = i18n()
        assertEquals("Hello Ali", i.t("en-US", "hello", mapOf("name" to "Ali")))
        assertEquals("Hello {name}", i.t("en-US", "hello"))
        assertEquals("a 5 {y} {1x}", MarketI18n.interpolate("a {x} {y} {1x}", mapOf("x" to 5)))
        assertEquals("v null", MarketI18n.interpolate("v {x}", mapOf("x" to null)))
        // a value containing a placeholder is not rescanned
        assertEquals("{y} 2", MarketI18n.interpolate("{x} {y}", mapOf("x" to "{y}", "y" to 2)))
    }

    @Test
    fun `override cache lives 60 seconds`() = runBlocking {
        val o = Overrides(mapOf("tr" to mapOf("plugins.pano-plugin-market.a" to "v1")))
        val clock = TestClock(1000)
        val i = i18n(o, clock)
        assertEquals("v1", i.t("tr", "a"))
        o.data = mapOf("tr" to mapOf("plugins.pano-plugin-market.a" to "v2"))
        clock.ms += 59_999
        assertEquals("v1", i.t("tr", "a"))
        assertEquals(1, o.calls)
        clock.ms += 1
        assertEquals("v2", i.t("tr", "a"))
        assertEquals(2, o.calls)
    }

    @Test
    fun `failing override query yields no overrides and does not throw`() = runBlocking {
        val o = Overrides(fail = true)
        assertEquals("tr-a", i18n(o).t("tr", "a"))
    }

    @Test
    fun `resolveLocale takes the first non blank candidate`() {
        val i = i18n()
        assertEquals("ru", i.resolveLocale("tr", null, " ", "ru", "en-US"))
        assertEquals("tr", i.resolveLocale("tr", null, ""))
    }

    @Test
    fun `flatten ignores non string leaves`() {
        val m = MarketI18n.flatten("""{"a":{"b":"x","n":1,"l":["q"]},"c":"y"}""")
        assertEquals(mapOf("a.b" to "x", "c" to "y"), m)
    }

    @Test
    fun `T-I18N-4 no ICU syntax under mail invoice server-format in the core fragments`() {
        val icu = Regex("""\{\s*[A-Za-z0-9_]+\s*,""")
        var checked = 0
        for (lang in listOf("tr", "en-US", "ru")) {
            val flat = MarketI18n.flatten(File("src/locales/core/$lang.json").readText())
            for ((k, v) in flat) {
                if (listOf("mail.", "invoice.", "server-format.").none { k.startsWith(it) }) continue
                checked++
                assertTrue(!icu.containsMatchIn(v), "ICU syntax in $lang:$k = $v")
            }
        }
        assertTrue(checked >= 15, "expected server-format keys to be checked, got $checked")
    }
}
