package com.panomc.plugins.market.frontend

import com.panomc.platform.api.SitemapProvider
import com.panomc.plugins.market.service.SitemapCategory
import com.panomc.plugins.market.service.SitemapPages
import com.panomc.plugins.market.service.SitemapProduct
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarketSitemapProviderTest {
    @Test
    fun `products and categories become entries of the plugin's own types`() {
        val entries = MarketSitemapProvider.entriesOf(
            SitemapPages(
                products = listOf(SitemapProduct("rank-vip", 1700000000000L), SitemapProduct("kit", 5L)),
                categories = listOf(SitemapCategory(3L, 9L))
            )
        )

        assertEquals(listOf("pano-plugin-market:product", "pano-plugin-market:product", "pano-plugin-market:category"), entries.map { it.type })
        assertEquals(mapOf("slug" to "rank-vip"), entries[0].params)
        assertEquals(1700000000000L, entries[0].updatedAt)
        assertEquals(mapOf("id" to 3L), entries[2].params)
    }

    @Test
    fun `every type starts with the plugin id so the platform keeps the entry`() {
        val types = MarketSitemapProvider.entriesOf(SitemapPages(listOf(SitemapProduct("a", 1)), listOf(SitemapCategory(1, 1)))).map { it.type }

        assertTrue(types.all { it.startsWith("pano-plugin-market:") && it.length > "pano-plugin-market:".length })
    }

    @Test
    fun `an empty store announces nothing`() {
        assertEquals(emptyList<Any>(), MarketSitemapProvider.entriesOf(SitemapPages(emptyList(), emptyList())))
    }

    @Test
    fun `the provider is a bean the platform finds by type`() {
        assertTrue(SitemapProvider::class.java.isAssignableFrom(MarketSitemapProvider::class.java))
        assertTrue(MarketSitemapProvider::class.java.isAnnotationPresent(org.springframework.stereotype.Component::class.java))
    }
}
