package com.panomc.plugins.market.feature

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.routes.panel.settings.marketContextBody
import com.panomc.plugins.market.routes.panel.settings.marketContextInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Properties

/** The switches a build ships with: where the value comes from, what depends on what, what the panel is told. */
class MarketFeaturesTest {
    private fun properties(vararg pairs: Pair<String, String>) = Properties().apply { pairs.forEach { setProperty(it.first, it.second) } }

    @Test
    fun `the resource names what is off, a key it does not name is on`() {
        val parsed = MarketFeatures.parse(properties("physicalShipping" to "false", "unknown" to "false")) { null }

        assertEquals(mapOf(MarketFeature.PHYSICAL_SHIPPING to false), parsed)
        assertEquals(emptyMap<MarketFeature, Boolean>(), MarketFeatures.parse(Properties()) { null })
        assertEquals(mapOf(MarketFeature.SHIPPING_CARRIERS to true), MarketFeatures.parse(properties("shippingCarriers" to " TRUE ")) { null })
    }

    @Test
    fun `a system property wins over the resource`() {
        val system = mapOf("market.feature.physicalShipping" to "true")
        val parsed = MarketFeatures.parse(properties("physicalShipping" to "false", "shippingCarriers" to "false")) { system[it] }

        assertEquals(mapOf(MarketFeature.PHYSICAL_SHIPPING to true, MarketFeature.SHIPPING_CARRIERS to false), parsed)
    }

    @Test
    fun `the test tasks run with every feature on`() {
        for (feature in MarketFeature.entries) assertTrue(MarketFeatures.enabled(feature), feature.name)
    }

    @Test
    fun `a feature is off while the one it requires is off, and the override ends with the block`() {
        MarketFeatures.with(MarketFeature.PHYSICAL_SHIPPING to false) {
            assertFalse(MarketFeatures.enabled(MarketFeature.PHYSICAL_SHIPPING))
            assertFalse(MarketFeatures.enabled(MarketFeature.SHIPPING_CARRIERS), "carriers need shipping")
            assertEquals(mapOf("physicalShipping" to false, "shippingCarriers" to false), MarketFeatures.asMap())
        }

        MarketFeatures.with(MarketFeature.SHIPPING_CARRIERS to false) {
            assertTrue(MarketFeatures.enabled(MarketFeature.PHYSICAL_SHIPPING))
            assertFalse(MarketFeatures.enabled(MarketFeature.SHIPPING_CARRIERS))
        }

        assertEquals(mapOf("physicalShipping" to true, "shippingCarriers" to true), MarketFeatures.asMap())
    }

    @Test
    fun `the panel context names every switch`() {
        fun body() = marketContextBody(
            marketContextInput(MarketConfig(), storeUrl = "", mailEnabled = false, shippingEnabled = false, runtimeState = "READY"),
            includeProductMeta = false
        )

        assertEquals(mapOf("physicalShipping" to true, "shippingCarriers" to true), body()["features"])
        assertEquals(false, body()["featurePreview"])

        MarketFeatures.with(MarketFeature.PHYSICAL_SHIPPING to false) {
            assertEquals(mapOf("physicalShipping" to false, "shippingCarriers" to false), body()["features"])
        }
    }
}
