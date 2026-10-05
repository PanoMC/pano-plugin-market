package com.panomc.plugins.market.mc.feature

import com.panomc.plugins.market.mc.core.feature.ChatFormat
import com.panomc.plugins.market.mc.core.feature.Messages
import com.panomc.plugins.market.mc.core.feature.MiniYaml
import com.panomc.plugins.market.mc.core.feature.Msg
import com.panomc.plugins.market.mc.core.feature.flatTexts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MessagesTest {
    private val en = mapOf("a" to "&aHello {name}", "only.en" to "english only", "b" to "B en")
    private val tr = mapOf("a" to "&aMerhaba {name}", "b" to "B tr")

    private fun messages(
        pano: Map<String, Map<String, String>> = emptyMap(),
        overrides: Map<String, Map<String, String>> = emptyMap(),
        configured: List<String> = emptyList()
    ) = Messages(
        bundled = { l -> mapOf("en-US" to en, "tr" to tr)[l] },
        overrides = { overrides[it] },
        panoTexts = { pano },
        configuredLocales = { configured }
    )

    @Test
    fun `a template is coloured and a placeholder is filled with a safe value`() {
        val m = messages()
        assertEquals("\u00a7aHello Steve", m.text("a", null, "name" to "Steve"))
    }

    @Test
    fun `a value can never inject a colour code or a control character`() {
        val m = messages()
        assertEquals("\u00a7aHello cEvil", m.text("a", null, "name" to "\u00a7cEvil"))
        assertEquals("\u00a7aHello AB", m.text("a", null, "name" to "A\nB\u0007"))
        assertEquals("\u00a7aHello &cEvil", m.text("a", null, "name" to "&cEvil"), "an & in a value is a literal character, not a code")
        assertEquals("\u00a7aHello {name}", m.text("a", null), "an unfilled placeholder stays visible")
        assertEquals("\u00a7aHello ", m.text("a", null, "name" to null))
    }

    @Test
    fun `locale resolution, the player's first, then the configured one, then the site default, then English`() {
        val m = messages()
        assertEquals("\u00a7aMerhaba x", m.text("a", "tr_TR", "name" to "x"))
        assertEquals("\u00a7aMerhaba x", m.text("a", "TR", "name" to "x"))
        assertEquals("\u00a7aHello x", m.text("a", "fr_FR", "name" to "x"), "unknown language falls back to en-US")
        assertEquals("\u00a7aHello x", m.text("a", "en-GB", "name" to "x"), "en-GB uses the en-US text")
        assertEquals("\u00a7aMerhaba x", messages(configured = listOf("tr")).text("a", null, "name" to "x"))
        assertEquals("\u00a7aMerhaba x", messages(pano = mapOf("tr" to emptyMap())).text("a", null, "name" to "x"), "the first locale Pano sent is the site default")
        assertEquals("english only", m.text("only.en", "tr"), "a key missing in tr falls back to en-US")
        assertEquals("missing.key", m.text("missing.key", "tr"), "the last resort is the key itself")
        assertFalse(m.has("missing.key", null))
        assertTrue(m.has("a", "tr"))
    }

    @Test
    fun `layers per locale, local override beats the Pano text beats the bundled one`() {
        val pano = mapOf("tr" to mapOf("a" to "&aPano {name}"), "en-US" to mapOf("a" to "&aPano en {name}"))
        assertEquals("\u00a7aPano x", messages(pano = pano).text("a", "tr", "name" to "x"))
        assertEquals("\u00a7aPano en x", messages(pano = pano).text("a", "en-US", "name" to "x"))
        assertEquals("B tr", messages(pano = pano).text("b", "tr"), "a key Pano did not send comes from the bundled file")
        val overrides = mapOf("tr" to mapOf("a" to "&aYerel {name}"))
        assertEquals("\u00a7aYerel x", messages(pano = pano, overrides = overrides).text("a", "tr", "name" to "x"))
        assertEquals("\u00a7aPano en x", messages(pano = pano, overrides = overrides).text("a", "en-US", "name" to "x"), "the override is per locale")
    }

    @Test
    fun `locale names are normalised`() {
        assertEquals("tr-TR", Messages.normalize("tr_tr"))
        assertEquals("en-US", Messages.normalize("en-us"))
        assertEquals("tr", Messages.normalize(" TR "))
        assertNull(Messages.normalize(""))
        assertNull(Messages.normalize("x"))
        assertNull(Messages.normalize("12-34"))
        assertNull(Messages.normalize("a".repeat(40)))
    }

    @Test
    fun `chat format helpers`() {
        assertEquals("\u00a7aGreen \u00a7lBold \u00a7rreset & more &z", ChatFormat.colorize("&aGreen &LBold &rreset & more &z"))
        assertEquals("\u00a7aSteve \u00a77bought", ChatFormat.fromPano("&aSteve \u00a77bought"))
        assertEquals("a b", ChatFormat.fromPano("a\r\nb\u0001"))
        assertEquals(256, ChatFormat.fromPano("x".repeat(1000)).length)
        assertEquals("Hello World", ChatFormat.strip("\u00a7aHello \u00a7lWorld"))
        assertEquals("ab", ChatFormat.plainValue("a\u00a7b\u0000"))
    }

    @Test
    fun `flatTexts flattens nested maps`() {
        val flat = flatTexts(MiniYaml.parse("a: 1\nb:\n  c: x\n  d:\n    e: y\n"))
        assertEquals(mapOf("a" to "1", "b.c" to "x", "b.d.e" to "y"), flat)
    }
}

/** The lang files the jar ships: every language has every key, with the same placeholders. */
class MessagesResourcesTest {
    private val placeholder = Regex("\\{([A-Za-z]+)}")

    private fun lang(locale: String): Map<String, String> {
        val text = ShippedResources.reader("mc/lang/$locale.yml") ?: throw AssertionError("mc/lang/$locale.yml is missing")
        return flatTexts(MiniYaml.parse(text))
    }

    @Test
    fun `all three languages are bundled and have exactly the keys of Msg`() {
        val expected = Msg.ALL.toSet()
        assertEquals(Msg.ALL.size, expected.size, "Msg.ALL has a duplicate")
        Messages.KNOWN.forEach { locale ->
            val keys = lang(locale).keys
            assertEquals(emptySet<String>(), expected - keys, "$locale is missing keys")
            assertEquals(emptySet<String>(), keys - expected, "$locale has keys that no code uses")
        }
        assertEquals(listOf("en-US", "tr", "ru"), Messages.KNOWN)
    }

    @Test
    fun `the placeholders of a message are the same in every language`() {
        val all = Messages.KNOWN.associateWith { lang(it) }
        Msg.ALL.forEach { key ->
            val sets = all.mapValues { (_, texts) -> placeholder.findAll(texts.getValue(key)).map { it.groupValues[1] }.toSet() }
            assertEquals(1, sets.values.toSet().size, "placeholders of $key differ: $sets")
        }
    }

    @Test
    fun `no message is empty, has a stray colour symbol or a broken code`() {
        Messages.KNOWN.forEach { locale ->
            lang(locale).forEach { (key, text) ->
                assertTrue(text.isNotBlank(), "$locale $key is empty")
                assertFalse(text.contains('\u00a7'), "$locale $key uses the section sign; write & codes")
                Regex("&(.)").findAll(text).forEach { m ->
                    assertTrue("0123456789abcdefklmnor".indexOf(m.groupValues[1].lowercase()) >= 0 || m.groupValues[1] == " ", "$locale $key has the unknown code &${m.groupValues[1]}")
                }
            }
        }
    }

    @Test
    fun `the messages every language needs for the commands read sensibly with real values`() {
        val m = Messages({ ShippedResources.reader("mc/lang/$it.yml")?.let { t -> flatTexts(MiniYaml.parse(t)) } })
        Messages.KNOWN.forEach { l ->
            val line = m.text(Msg.HISTORY_LINE, l, "id" to "ORD1", "items" to "Diamonds", "total" to "5", "currency" to "USD", "status" to "PAID", "date" to "2026-10-05")
            assertTrue(line.contains("ORD1") && line.contains("Diamonds") && line.contains("USD") && !line.contains("{"), "$l: $line")
        }
    }
}
