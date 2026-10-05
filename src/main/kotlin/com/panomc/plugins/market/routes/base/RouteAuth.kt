package com.panomc.plugins.market.routes.base

/**
 * The auth class of a route as 04 section 1 names it, for `GET /health` `routes[]` and the permission-matrix test:
 * `PUB`, `PUB-M`, `USER`, `P:<node>[,<node>]` (`P:ANY` = any market node), or `LEGACY-PANEL` / `LEGACY-USER` /
 * `LEGACY-PUB` for a route that still extends the platform's classes directly (retrofit pending).
 */
object RouteAuth {
    fun describe(route: Any): String = when (route) {
        is MarketPanelApi -> "P:" + route.nodes.sortedBy { it.ordinal }.joinToString(",") { it.shortName }.ifEmpty { "ANY" }
        is MarketUserApi -> "USER"
        is MarketPublicMutationApi -> "PUB-M"
        is MarketApi -> "PUB"
        is com.panomc.platform.model.PanelApi -> "LEGACY-PANEL"
        is com.panomc.platform.model.LoggedInApi -> "LEGACY-USER"
        is com.panomc.platform.model.Api -> "LEGACY-PUB"
        else -> "UNKNOWN"
    }
}
