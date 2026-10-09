package com.panomc.plugins.market.routes.base

import com.panomc.platform.model.Api
import com.panomc.platform.model.Result
import io.vertx.ext.web.RoutingContext

/**
 * Base of the public market routes, auth class `PUB` (04 section 1). An optional session is honoured by the route
 * itself. After the platform's checks (setup, demo, maintenance) it asks the runtime gate (00 section 8.9) and the store
 * switch; a route that must answer while the store is switched off sets [requiresStoreEnabled] to `false`.
 */
abstract class MarketApi : Api() {
    /** `false` for the few public routes that stay up when `storeEnabled = false`. */
    protected open val requiresStoreEnabled: Boolean = true

    override suspend fun onBeforeHandle(context: RoutingContext) {
        super.onBeforeHandle(context)

        marketChecks(context)
    }

    /**
     * The market part of [onBeforeHandle] (runtime gate and store switch): everything
     * after the platform's own checks, callable by a test without the host.
     */
    internal open suspend fun marketChecks(context: RoutingContext) {
        MarketGate.requireReady()

        if (requiresStoreEnabled) MarketGate.requireStoreEnabled()
    }

    final override suspend fun handle(context: RoutingContext): Result? =
        MarketGate.guarded(context) { handleMarket(context) }

    abstract suspend fun handleMarket(context: RoutingContext): Result?
}

/**
 * Base of the public mutating routes (quote, checkout as a guest, ...), auth class `PUB-M`: as [MarketApi]. The CSRF proof of a session cookie is
 * checked by the platform's `Api` wrapper (doc 05 section 4) and the Origin gate keeps a foreign page from posting without a session, so this class
 * has nothing of its own to check; it stays as the marker of the class `PUB-M`.
 */
abstract class MarketPublicMutationApi : MarketApi()
