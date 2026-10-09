package com.panomc.plugins.market.support

import com.panomc.platform.frontend.FrontendUrlInputs
import com.panomc.platform.frontend.FrontendUrlMap
import com.panomc.platform.frontend.ThemeRouteMap
import com.panomc.platform.ui.FrontendMode
import com.panomc.plugins.market.util.MarketTargets
import com.panomc.plugins.market.util.StoreLinks
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

/**
 * A site for the tests of the market links (doc 05 section 10): a real [FrontendUrlMap] over fake platform inputs, with the
 * `frontend-targets.json` of the plugin registered the way the platform does when the plugin loads. Change [inputs] to rename a route,
 * disable one, override a target or move the front-end to another address.
 */
class SiteLinksFixture(site: String = "https://shop.example", website: String = site) {
    class Inputs(var site: String, var website: String) : FrontendUrlInputs {
        var mode = FrontendMode.THEME
        var overrides: Map<String, String> = emptyMap()
        var frontend: Map<String, Any?> = emptyMap()
        var routes: ThemeRouteMap? = null

        override fun mode() = mode
        override fun siteUrl() = site
        override fun websiteUrl() = website
        override fun overrides() = overrides
        override fun frontendUrls() = frontend
        override fun themeRoutes() = routes
    }

    val inputs = Inputs(site, website)

    val map = FrontendUrlMap(inputs, LoggerFactory.getLogger("test")).also {
        it.registerPlugin(MarketTargets.PLUGIN_ID, MarketTargets.NAMESPACE, MarketTargets.fileText())
    }

    val links: StoreLinks = StoreLinks.forMap(map)

    /** The theme renames `/store/order/[id]` to [publicPath] (`/shop/purchase/[id]`) and nothing else. */
    fun renameOrder(publicPath: String = "/shop/purchase/[id]") = apply {
        inputs.routes = ThemeRouteMap.fromCoreMeta(JsonObject().put("rename", JsonObject().put("/store/order/[id]", publicPath)))
    }

    /** The theme serves no page at these canonical paths. */
    fun disable(vararg canonical: String) = apply {
        inputs.routes = ThemeRouteMap.fromCoreMeta(JsonObject().put("disable", canonical.toList()))
    }
}
