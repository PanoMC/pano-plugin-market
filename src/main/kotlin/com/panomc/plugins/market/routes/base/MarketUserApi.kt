package com.panomc.plugins.market.routes.base

import com.panomc.platform.Main.Companion.applicationContext
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.error.InvalidCsrfToken
import com.panomc.platform.model.LoggedInApi
import com.panomc.platform.model.Result
import io.vertx.ext.web.RoutingContext

/**
 * Base of the buyer routes (everything under `/api/market/me`), auth class `USER`: login required (the platform's `LoggedInApi`),
 * CSRF proof on every non-GET (`LoggedInApi` does not check it, 00 section 8.8), then the runtime gate and the store
 * switch.
 */
abstract class MarketUserApi : LoggedInApi() {
    private val authProvider by lazy { applicationContext.getBean(AuthProvider::class.java) }

    protected open val requiresStoreEnabled: Boolean = true

    /** Whether the request carries the CSRF proof. Overridable so a test can stand in for the host. */
    protected open fun isCsrfSafe(context: RoutingContext): Boolean = authProvider.isCsrfSafe(context)

    override suspend fun onBeforeHandle(context: RoutingContext) {
        super.onBeforeHandle(context)

        marketChecks(context)
    }

    /** The market part of [onBeforeHandle], after the platform's login check; callable by a test without the host. */
    internal fun marketChecks(context: RoutingContext) {
        if (MarketGate.csrfViolation(context.request().method(), isLoggedIn = true, csrfSafe = isCsrfSafe(context))) {
            throw InvalidCsrfToken()
        }

        MarketGate.requireReady()

        if (requiresStoreEnabled) MarketGate.requireStoreEnabled()
    }

    final override suspend fun handle(context: RoutingContext): Result? =
        MarketGate.guarded(context) { handleMarket(context) }

    abstract suspend fun handleMarket(context: RoutingContext): Result?
}
