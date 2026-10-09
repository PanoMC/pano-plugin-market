package com.panomc.plugins.market.routes.base

import com.panomc.platform.model.LoggedInApi
import com.panomc.platform.model.Result
import io.vertx.ext.web.RoutingContext

/**
 * Base of the buyer routes (everything under `/api/market/me`), auth class `USER`: login required (the platform's `LoggedInApi`),
 * then the runtime gate and the store switch. The CSRF proof of a cookie session is checked by the platform's `Api` wrapper
 * (doc 05 section 4), not here.
 */
abstract class MarketUserApi : LoggedInApi() {
    protected open val requiresStoreEnabled: Boolean = true

    override suspend fun onBeforeHandle(context: RoutingContext) {
        super.onBeforeHandle(context)

        marketChecks(context)
    }

    /** The market part of [onBeforeHandle], after the platform's login check; callable by a test without the host. */
    internal fun marketChecks(context: RoutingContext) {
        MarketGate.requireReady()

        if (requiresStoreEnabled) MarketGate.requireStoreEnabled()
    }

    final override suspend fun handle(context: RoutingContext): Result? =
        MarketGate.guarded(context) { handleMarket(context) }

    abstract suspend fun handleMarket(context: RoutingContext): Result?
}
