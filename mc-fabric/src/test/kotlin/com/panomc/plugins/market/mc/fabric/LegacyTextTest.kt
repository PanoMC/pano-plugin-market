package com.panomc.plugins.market.mc.fabric

import com.panomc.plugins.market.mc.fabric.LegacyText.Run
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LegacyTextTest {
    @Test
    fun `plain text is one run without codes`() {
        assertEquals(listOf(Run("hello")), LegacyText.parse("hello"))
        assertEquals(emptyList<Run>(), LegacyText.parse(""))
    }

    @Test
    fun `a colour code colours what follows`() {
        assertEquals(listOf(Run("a"), Run("b", color = 'c'), Run("d", color = '2')), LegacyText.parse("a§cb§2d"))
    }

    @Test
    fun `styles add up and a colour clears them`() {
        assertEquals(
            listOf(Run("x", color = 'a', bold = true, italic = true), Run("y", color = 'b')),
            LegacyText.parse("§a§l§ox§by")
        )
    }

    @Test
    fun `r clears colour and styles`() {
        assertEquals(listOf(Run("a", color = 'c', bold = true), Run("b")), LegacyText.parse("§c§la§rb"))
    }

    @Test
    fun `codes are case insensitive and every style has its flag`() {
        val run = LegacyText.parse("§A§K§L§M§N§Ox").single()
        assertEquals(Run("x", 'a', bold = true, italic = true, underlined = true, strikethrough = true, obfuscated = true), run)
    }

    @Test
    fun `a section sign that is no code stays text`() {
        assertEquals("a§zb", LegacyText.plain("a§zb"))
        assertEquals("end§", LegacyText.plain("end§"))
    }

    @Test
    fun `plain drops the codes`() {
        assertEquals("Order #1 delivered", LegacyText.plain("§aOrder §l#1§r delivered"))
    }

    @Test
    fun `only http and https urls are web urls`() {
        assertTrue(LegacyText.isWebUrl("https://shop.example/x"))
        assertTrue(LegacyText.isWebUrl("http://shop.example"))
        assertFalse(LegacyText.isWebUrl("javascript:alert(1)"))
        assertFalse(LegacyText.isWebUrl("file:///etc/passwd"))
        assertFalse(LegacyText.isWebUrl(null))
    }
}
