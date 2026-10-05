package com.panomc.plugins.market.spi

/**
 * SPI version gate (02 section 9). Plugins declare `pluginDependencies=pano-plugin-market` without a version
 * constraint, so these constants are the only compatibility check: market accepts `MIN_SUPPORTED..VERSION`.
 */
object MarketSpi {
    /** `const`: inlined into the plugin at compile time = the SPI version the plugin was built against. */
    const val VERSION: Int = 1

    /** Oldest plugin SPI version this market build still accepts. */
    const val MIN_SUPPORTED: Int = 1

    /** Name of the inbound channel used when a provider has a single webhook / notify URL. */
    const val DEFAULT_CHANNEL: String = "default"
}
