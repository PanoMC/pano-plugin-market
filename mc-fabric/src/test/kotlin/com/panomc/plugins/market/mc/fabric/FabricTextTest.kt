package com.panomc.plugins.market.mc.fabric

import net.minecraft.network.chat.ClickEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The component conversion runs against the real Minecraft 26.1.2 classes (the Loom `minecraft` dependency is on the test classpath). */
class FabricTextTest {
    @Test
    fun `the component carries the text without the codes`() {
        assertEquals("Order #1 delivered", FabricText.component("§aOrder §l#1§r delivered").string)
    }

    @Test
    fun `colours and styles land on the runs`() {
        val c = FabricText.component("§cred§r plain")
        val first = c.siblings[0]
        assertEquals("red", first.string)
        assertEquals("red", first.style.color?.serialize())
        assertNull(c.siblings[1].style.color)
    }

    @Test
    fun `bold and italic are set`() {
        val run = FabricText.component("§l§ox").siblings.single()
        assertEquals(true, run.style.isBold)
        assertEquals(true, run.style.isItalic)
    }

    @Test
    fun `a web url makes the line clickable`() {
        val click = FabricText.component("§bshop", "https://shop.example/a").style.clickEvent
        assertNotNull(click)
        assertTrue(click is ClickEvent.OpenUrl)
        assertEquals("https://shop.example/a", (click as ClickEvent.OpenUrl).uri().toString())
    }

    @Test
    fun `other schemes and broken urls never become a click action`() {
        assertNull(FabricText.component("x", "javascript:alert(1)").style.clickEvent)
        assertNull(FabricText.component("x", "file:///etc/passwd").style.clickEvent)
        assertNull(FabricText.component("x", "https://bad host/with space").style.clickEvent)
        assertNull(FabricText.component("x", null).style.clickEvent)
    }
}
