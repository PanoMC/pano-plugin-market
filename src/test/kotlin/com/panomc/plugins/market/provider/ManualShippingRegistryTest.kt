package com.panomc.plugins.market.provider

import com.panomc.plugins.market.spi.MarketExtension
import com.panomc.plugins.market.spi.MarketSpi
import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.common.ProviderDescriptor
import com.panomc.plugins.market.spi.common.ProviderSettings
import com.panomc.plugins.market.spi.common.SettingsSchema
import com.panomc.plugins.market.spi.common.settingsSchema
import com.panomc.plugins.market.spi.shipping.ShippingCapabilities
import com.panomc.plugins.market.spi.shipping.ShippingProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.pf4j.PluginState

/** The shipping half of the registry (02 section 2, 11): `manual` is always there, a plugin cannot take its id. */
class ManualShippingRegistryTest {
    private class Ship(override val id: String) : ShippingProvider {
        override val descriptor = ProviderDescriptor(LocalizedText.of(id), LocalizedText.of(id), "truck")
        override fun settingsSchema(): SettingsSchema = settingsSchema { }
        override fun capabilities(settings: ProviderSettings) = ShippingCapabilities()
    }

    private class Ext(private val providers: List<ShippingProvider>) : MarketExtension {
        override val spiVersion: Int = MarketSpi.VERSION
        override fun shippingProviders(): List<ShippingProvider> = providers
    }

    private val builtIn = ManualShippingProvider()

    private fun registry(vararg plugins: Pair<String, List<ShippingProvider>>) = ProviderRegistry(
        source = { plugins.map { PluginExtension(it.first, Ext(it.second)) } },
        pluginState = { PluginState.STARTED },
        builtInShipping = { listOf(builtIn) }
    )

    @Test
    fun `manual is registered with no plugin at all`() {
        val r = registry()

        val resolved = r.shipping("manual")
        assertNotNull(resolved)
        assertSame(builtIn, resolved!!.provider)
        assertEquals(ProviderOrigin.BUILT_IN, resolved.origin)
        assertEquals(listOf("manual"), r.allShipping().map { it.id })
        assertEquals(ProviderAvailability.AVAILABLE, r.state(ProviderKind.SHIPPING, "manual").availability)
        assertEquals(emptyList<String>(), r.allPayment().map { it.id }, "the payment side is not affected")
    }

    @Test
    fun `a plugin that claims the manual id is shadowed and carriers come after it`() {
        val r = registry("pano-ups" to listOf(Ship("manual"), Ship("ups")))

        assertEquals(listOf("manual", "ups"), r.allShipping().map { it.id })
        assertSame(builtIn, r.shipping("manual")!!.provider)
        val shadowed = r.listing(ProviderKind.SHIPPING).filter { it.id == "manual" && !it.state.usable }
        assertEquals(listOf(ProviderAvailability.SHADOWED), shadowed.map { it.state.availability })
    }

    @Test
    fun `the production wiring registers manual as a built-in shipping provider`() {
        val ids = com.panomc.plugins.market.routes.panel.shipping.BUILT_IN_SHIPPING_PROVIDERS.map { it.id }

        assertEquals(listOf("manual"), ids)
    }
}
