package com.panomc.plugins.market.util

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StoreBackgroundTest {
    @Test
    fun `only generated image filenames are accepted`() {
        for (extension in listOf("png", "jpg", "gif", "webp")) {
            assertTrue(StoreBackground.isFileName(StoreBackground.newFileName(extension)))
        }
        for (name in listOf("", "../secret.png", "store-background-00000000-0000-0000-0000-000000000000.svg", "store-background-00000000-0000-0000-0000-000000000000.png/other")) {
            assertFalse(StoreBackground.isFileName(name), name)
        }
        assertThrows(IllegalArgumentException::class.java) { StoreBackground.newFileName("svg") }
    }

    @Test
    fun `only the current valid filename can be served`() {
        val current = StoreBackground.newFileName("png")
        val other = StoreBackground.newFileName("png")
        assertTrue(StoreBackground.isCurrentFile(current, current))
        assertFalse(StoreBackground.isCurrentFile(other, current))
        assertFalse(StoreBackground.isCurrentFile("../secret.png", "../secret.png"))
        assertFalse(StoreBackground.isCurrentFile("", ""))
    }
}
