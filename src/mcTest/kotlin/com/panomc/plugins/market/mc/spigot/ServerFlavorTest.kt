package com.panomc.plugins.market.mc.spigot

import com.panomc.plugins.market.mc.core.wire.McPlatformName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerFlavorTest {
    @Test
    fun `Folia is detected by its regionized server class, before Paper`() {
        val classes = setOf("io.papermc.paper.threadedregions.RegionizedServer", "com.destroystokyo.paper.PaperConfig")
        assertEquals(McPlatformName.FOLIA, ServerFlavor.detect({ it in classes }))
        assertTrue(ServerFlavor.isFolia({ it in classes }))
    }

    @Test
    fun `Paper is detected by its config class (old and new) or by the server name`() {
        assertEquals(McPlatformName.PAPER, ServerFlavor.detect({ it == "com.destroystokyo.paper.PaperConfig" }))
        assertEquals(McPlatformName.PAPER, ServerFlavor.detect({ it == "io.papermc.paper.configuration.Configuration" }))
        assertEquals(McPlatformName.PAPER, ServerFlavor.detect({ false }, { "Purpur" }))
        assertFalse(ServerFlavor.isFolia({ it == "com.destroystokyo.paper.PaperConfig" }))
    }

    @Test
    fun `anything else is Spigot`() {
        assertEquals(McPlatformName.SPIGOT, ServerFlavor.detect({ false }, { "CraftBukkit" }))
    }

    @Test
    fun `in the test JVM there is no Paper and no Folia`() {
        assertEquals(McPlatformName.SPIGOT, ServerFlavor.detect())
    }
}
