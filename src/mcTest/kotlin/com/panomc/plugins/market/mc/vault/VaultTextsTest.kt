package com.panomc.plugins.market.mc.vault

import com.panomc.plugins.market.mc.core.feature.Messages
import com.panomc.plugins.market.mc.spigot.vault.VaultMessages
import com.panomc.plugins.market.mc.spigot.vault.VaultMsg
import com.panomc.plugins.market.mc.spigot.vault.VaultTexts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** The Vault texts exist in en-US, tr and ru with the same keys and placeholders, and layer under the local files and Pano's texts. */
class VaultTextsTest {
    private val tables = mapOf("en-US" to VaultTexts.EN, "tr" to VaultTexts.TR, "ru" to VaultTexts.RU)
    private val placeholder = Regex("\\{[A-Za-z]+\\}")

    @Test
    fun `every language has exactly the keys the code uses`() {
        val expected = VaultMsg.ALL.toSet()
        assertEquals(VaultMsg.ALL.size, expected.size, "no key listed twice")
        tables.forEach { (lang, table) -> assertEquals(expected, table.keys, lang) }
    }

    @Test
    fun `the placeholders of a key are the same in every language`() {
        for (key in VaultMsg.ALL) {
            val sets = tables.mapValues { (_, t) -> placeholder.findAll(t.getValue(key)).map { it.value }.toSet() }
            assertEquals(sets.getValue("en-US"), sets.getValue("tr"), key)
            assertEquals(sets.getValue("en-US"), sets.getValue("ru"), key)
        }
    }

    @Test
    fun `colour codes are valid and no text uses the section sign or is blank`() {
        tables.forEach { (lang, table) ->
            table.forEach { (key, text) ->
                assertTrue(text.isNotBlank(), "$lang $key")
                assertTrue('§' !in text, "$lang $key must use & codes")
                assertTrue(Regex("&(?![0-9a-fk-or])").findAll(text).none(), "$lang $key has an invalid & code: $text")
            }
        }
    }

    @Test
    fun `every message key is used by the code`() {
        val root = File(System.getProperty("market.mcResources")).parentFile.resolve("kotlin/com/panomc/plugins/market/mc/spigot/vault")
        val code = root.listFiles { f -> f.extension == "kt" && f.name != "VaultTexts.kt" }!!.joinToString("\n") { it.readText() }
        for (name in VaultMsg::class.java.declaredFields.filter { it.type == String::class.java && it.name != "ALL" }.map { it.name }) {
            val constant = name.split('_').joinToString("_")
            assertTrue(Regex("VaultMsg\\.$constant\\b").containsMatchIn(code), "VaultMsg.$constant is never used")
        }
    }

    @Test
    fun `find chooses the language, then English, and knows no invented key`() {
        assertTrue(VaultTexts.find(VaultMsg.BUSY, "tr_TR")!!.contains("Önceki"))
        assertTrue(VaultTexts.find(VaultMsg.BUSY, "ru")!!.contains("Предыдущий"))
        assertTrue(VaultTexts.find(VaultMsg.BUSY, "de_DE")!!.contains("previous"))
        assertTrue(VaultTexts.find(VaultMsg.BUSY, null)!!.contains("previous"))
        assertTrue(VaultTexts.find(VaultMsg.BUSY, "en_GB")!!.contains("previous"))
        assertNull(VaultTexts.find("vault.nothing", "tr"))
    }

    @Test
    fun `Pano's texts and local files outrank the bundled ones, values are made safe`() {
        val pano = mapOf("en-US" to mapOf(VaultMsg.BUSY to "&cBusy, {x}"))
        val messages = Messages(bundled = { null }, panoTexts = { pano })
        val texts = VaultMessages(messages)
        assertEquals("§cBusy, a", texts.text(VaultMsg.BUSY, null, "x" to "a"))
        // not supplied by Pano: the bundled text of the player's language
        assertTrue(texts.text(VaultMsg.NO_ECONOMY, "tr_TR").contains("ekonomi"))
        // a value can never carry formatting
        assertEquals("§cThe store refused the conversion (XX).", texts.text(VaultMsg.REFUSED, null, "code" to "§X\nX").replace("\n", ""))
        assertEquals("vault.unknown", texts.text("vault.unknown", null))
    }
}
