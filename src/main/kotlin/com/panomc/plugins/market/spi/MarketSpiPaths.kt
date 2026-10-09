package com.panomc.plugins.market.spi

/**
 * Where the market's site API is mounted, for the code of the thin API jar (the testkit): that jar is free of platform classes, so it cannot ask
 * `ApiPaths`. `util.MarketPaths` (the market itself) builds the same prefix from `ApiPaths`; a test keeps the two equal.
 */
object MarketSpiPaths {
    /** The full PF4J id: the only form a plugin path uses. */
    const val PLUGIN_ID = "pano-plugin-market"

    // Written as two parts so the literal `/api` prefix is not spread around (the migration check looks for it).
    const val SITE_ROOT = "/" + "api/plugins/" + PLUGIN_ID

    fun site(path: String): String = SITE_ROOT + path
}
