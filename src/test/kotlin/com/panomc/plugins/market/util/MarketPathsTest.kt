package com.panomc.plugins.market.util

import com.panomc.platform.route.ApiPaths
import com.panomc.plugins.market.spi.MarketSpiPaths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MarketPathsTest {
    @Test
    fun `the market paths are the plugin paths of the platform`() {
        assertEquals("/api/plugins/pano-plugin-market/store/products", MarketPaths.site("/store/products"))
        assertEquals("/api/plugins/pano-plugin-market/panel/products", MarketPaths.panel("/products"))
        assertEquals(ApiPaths.plugin(MarketPaths.PLUGIN_ID, "/x"), MarketPaths.SITE_ROOT + "/x")
        assertEquals(ApiPaths.pluginPanel(MarketPaths.PLUGIN_ID, "/x"), MarketPaths.PANEL_ROOT + "/x")
    }

    @Test
    fun `the spi copy of the site prefix equals the market one`() {
        assertEquals(MarketPaths.SITE_ROOT, MarketSpiPaths.SITE_ROOT)
        assertEquals(MarketPaths.PLUGIN_ID, MarketSpiPaths.PLUGIN_ID)
    }
}
