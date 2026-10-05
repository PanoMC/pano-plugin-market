package com.panomc.plugins.market.routes.base

import com.panomc.platform.model.PanelApi
import com.panomc.platform.model.Result
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.permission.MarketPermissions
import io.vertx.ext.web.RoutingContext

/**
 * Base of every route under `routes.panel` (04 section 1 `P:<node>`, 11 section 14.2). The platform's `PanelApi` has
 * already demanded login, CSRF proof and panel access; this class adds the market gate (not READY = 503
 * `STORE_UNAVAILABLE`, except [exemptFromRuntimeGate] routes that report the state) and then, as the first thing
 * `handle` does and before any parameter parsing that touches the database, the permission check: one of [nodes], the
 * umbrella `ManageMarketPermission`, or the platform's admin bypass. An empty [nodes] set means "holder of any market
 * node". Failure is the platform's 403 `NO_PERMISSION`.
 */
abstract class MarketPanelApi : PanelApi() {
    /** Any one of these (or the umbrella) is sufficient. Empty = any market node. */
    abstract val nodes: Set<MarketNode>

    /** Only `GET /health` and `GET /context` answer while the market is not READY. */
    protected open val exemptFromRuntimeGate: Boolean = false

    override suspend fun onBeforeHandle(context: RoutingContext) {
        super.onBeforeHandle(context)

        marketChecks()
    }

    /** The market part of [onBeforeHandle], after the platform's checks; callable by a test without the host. */
    internal fun marketChecks() {
        if (!exemptFromRuntimeGate) MarketGate.requireReady()
    }

    /** The permission decision of [handle]; throws the platform `NoPermission`. Overridable so a test can stand in for the host. */
    protected open suspend fun authorize(context: RoutingContext) {
        MarketPermissions.require(context, nodes)
    }

    final override suspend fun handle(context: RoutingContext): Result? {
        authorize(context)

        return MarketGate.guarded(context) { handleAuthorized(context) }
    }

    abstract suspend fun handleAuthorized(context: RoutingContext): Result?

    /** Field-level gating (04 section 9, 11 section 14.5): does the caller hold one of [nodes] (or the umbrella)? */
    protected suspend fun has(context: RoutingContext, vararg nodes: MarketNode): Boolean =
        MarketPermissions.has(context, nodes.toSet())
}
