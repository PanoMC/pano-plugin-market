package com.panomc.plugins.market.spi

import com.panomc.platform.api.event.PluginEventListener
import com.panomc.plugins.market.spi.payment.PaymentProvider
import com.panomc.plugins.market.spi.shipping.ShippingProvider

/**
 * One per plugin, registered on the platform plugin event bus after the plugin's own license check (02 section 2).
 * It is the only SPI type that references a platform class (its super interface), which `T0ClasspathTest` allows by
 * name. Lives in `spi/payment` next to the provider it exposes; the package is the spec's `spi`.
 *
 * `shippingProviders()` was added with the shipping SPI as a default method, the binary compatible way (02 section 9).
 */
interface MarketExtension : PluginEventListener {
    /** Return [MarketSpi.VERSION]. */
    val spiVersion: Int

    fun paymentProviders(): List<PaymentProvider> = emptyList()

    /** Shipping providers of this plugin (03 section 5). A payment-only plugin keeps the default. */
    fun shippingProviders(): List<ShippingProvider> = emptyList()
}
