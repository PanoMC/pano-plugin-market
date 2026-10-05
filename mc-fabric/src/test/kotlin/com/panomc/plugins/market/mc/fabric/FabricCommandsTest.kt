package com.panomc.plugins.market.mc.fabric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FabricCommandsTest {
    @Test
    fun `arguments are split on spaces and empty tokens are dropped`() {
        assertEquals(emptyList<String>(), FabricCommands.split(""))
        assertEquals(listOf("credits"), FabricCommands.split("credits"))
        assertEquals(listOf("credits", "give", "Steve", "10"), FabricCommands.split("credits  give Steve   10 "))
    }
}
