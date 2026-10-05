package com.panomc.plugins.market.routes.base

import com.panomc.platform.Main.Companion.applicationContext
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.error.InvalidCsrfToken
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

        MarketGate.requireReady()

        if (requiresStoreEnabled) MarketGate.requireStoreEnabled()
    }

    final override suspend fun handle(context: RoutingContext): Result? =
        MarketGate.guarded(context) { handleMarket(context) }

    abstract suspend fun handleMarket(context: RoutingContext): Result?
}

/**
 * Base of the public mutating routes (quote, checkout as a guest, ...), auth class `PUB-M`: as [MarketApi], and when
 * the request is authenticated by a session cookie the CSRF proof must hold (`INVALID_CSRF_TOKEN`, 11 section 8.7).
 */
abstract class MarketPublicMutationApi : MarketApi() {
    private val authProvider by lazy { applicationContext.getBean(AuthProvider::class.java) }

    override suspend fun onBeforeHandle(context: RoutingContext) {
        super.onBeforeHandle(context)

        val method = context.request().method()

        // A guest has no ambient credential: skip the session lookup for the safe methods and for guests.
        if (!MarketGate.isSafeMethod(method) &&
            MarketGate.csrfViolation(method, authProvider.isLoggedIn(context), authProvider.isCsrfSafe(context))
        ) {
            throw InvalidCsrfToken()
        }
    }
}
