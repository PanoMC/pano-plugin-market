package com.panomc.plugins.market.util

import com.panomc.platform.route.ApiPaths

/**
 * Where the market's own endpoints are mounted (04 section 1). Routes declare relative paths and Pano adds the prefix;
 * code that hands a URL to a client, a gateway or a carrier builds it here, so no `/api` literal is spread around.
 */
object MarketPaths {
    /** The full PF4J id: the only form allowed in a path (the namespace `market` never appears there). */
    const val PLUGIN_ID = "pano-plugin-market"

    /** `/api/plugins/pano-plugin-market`: the site API of the market. */
    const val SITE_ROOT = "${ApiPaths.PLUGINS_ROOT}/$PLUGIN_ID"

    /** `/api/plugins/pano-plugin-market/panel`: the panel API of the market (internal). */
    const val PANEL_ROOT = "${ApiPaths.PLUGINS_ROOT}/$PLUGIN_ID/panel"

    fun site(path: String): String = ApiPaths.plugin(PLUGIN_ID, path)

    fun panel(path: String): String = ApiPaths.pluginPanel(PLUGIN_ID, path)
}
