package com.panomc.plugins.market.spi.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LocalizedTextTest {
    private val text = LocalizedText.of("Hello", "tr" to "Merhaba", "pt-BR" to "Ola")

    @Test
    fun `resolve exact then language then en-US`() {
        assertEquals("Merhaba", text.resolve("tr"))
        assertEquals("Merhaba", text.resolve("tr-TR"))
        assertEquals("Merhaba", text.resolve("tr_TR"))
        assertEquals("Ola", text.resolve("pt-BR"))
        assertEquals("Ola", text.resolve("pt"))
        assertEquals("Hello", text.resolve("en-US"))
        assertEquals("Hello", text.resolve("en"))
        assertEquals("Hello", text.resolve("ru"))
        assertEquals("Hello", text.resolve(""))
        assertEquals("Hello", text.resolve("zz-ZZ"))
    }

    @Test
    fun `locale tags compare case-insensitively`() {
        assertEquals("Merhaba", text.resolve("TR"))
        assertEquals("Ola", text.resolve("PT-br"))
    }

    @Test
    fun `exact beats language`() {
        val t = LocalizedText.of("a", "tr" to "lang", "tr-TR" to "exact")
        assertEquals("exact", t.resolve("tr-TR"))
        assertEquals("lang", t.resolve("tr"))
        assertEquals("lang", t.resolve("tr-CY"))
    }

    @Test
    fun `key text resolves to its english fallback and keeps the key`() {
        val t = LocalizedText.key("plugins.my-plugin.title", "Title")
        assertEquals("plugins.my-plugin.title", t.key)
        assertEquals("Title", t.resolve("tr"))
        assertEquals("Title", t.fallback)
        assertNull(text.key)
    }

    @Test
    fun `wire format`() {
        assertEquals(
            """{"default":"Hello","translations":{"tr":"Merhaba","pt-BR":"Ola"}}""",
            text.toJson().encode()
        )
        assertEquals("""{"default":"x","translations":{}}""", LocalizedText.of("x").toJson().encode())
        assertEquals(
            """{"key":"plugins.p.k","fallback":"F"}""",
            LocalizedText.key("plugins.p.k", "F").toJson().encode()
        )
    }

    @Test
    fun `invalid input is refused`() {
        assertThrows<IllegalArgumentException> { LocalizedText.of("a", "not a locale" to "x") }
        assertThrows<IllegalArgumentException> { LocalizedText.of("a", "en-US" to "x") }
        assertThrows<IllegalArgumentException> { LocalizedText.of("a", "tr" to "x", "TR" to "y") }
        assertThrows<IllegalArgumentException> { LocalizedText.key("nodots", "x") }
        assertThrows<IllegalArgumentException> { LocalizedText.key("has space.key", "x") }
        assertThrows<IllegalArgumentException> { LocalizedText.key("", "x") }
    }

    @Test
    fun `equality`() {
        assertEquals(LocalizedText.of("a", "tr" to "b"), LocalizedText.of("a", "tr" to "b"))
        assertEquals(LocalizedText.of("a").hashCode(), LocalizedText.of("a").hashCode())
        assertNotEquals(LocalizedText.of("a"), LocalizedText.of("b"))
        assertNotEquals(LocalizedText.of("a"), LocalizedText.key("x.y", "a"))
    }
}
