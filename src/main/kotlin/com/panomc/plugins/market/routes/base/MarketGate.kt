package com.panomc.plugins.market.routes.base

import com.panomc.platform.error.BadRequest
import com.panomc.plugins.market.error.MarketBusyException
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.error.StoreBusy
import com.panomc.plugins.market.error.StoreDisabled
import com.panomc.plugins.market.error.StoreUnavailable
import com.panomc.plugins.market.runtime.MarketRuntime
import io.vertx.ext.web.RoutingContext

/**
 * What every market base class does around a request (00 sections 8.8 and 8.9, 04 section 1). Kept apart from the
 * classes themselves so the decisions are plain functions a test can call without the host.
 */
object MarketGate {
    const val RETRY_AFTER_SECONDS = "2"

    /**
     * Whether the store switch (`storeEnabled`, 00 section 12) is on. The configuration slice (MK-022) points this at
     * `MarketConfig.storeEnabled`; until the key exists the store is always on.
     */
    @Volatile
    var storeEnabled: () -> Boolean = { true }

    /** Not READY (STOPPED, STARTING, DEGRADED): 503 `STORE_UNAVAILABLE` (00 section 8.9). */
    fun requireReady(state: MarketRuntime.State = MarketRuntime.state) {
        if (state != MarketRuntime.State.READY) throw StoreUnavailable()
    }

    /** `storeEnabled = false`: 503 `STORE_DISABLED` on the store, buyer and public-mutation routes (04 section 1). */
    fun requireStoreEnabled(enabled: Boolean = storeEnabled()) {
        if (!enabled) throw StoreDisabled()
    }

    /** The error a route failure becomes: the exceptions of this package map to their catalogue errors. */
    fun translate(failure: Throwable, context: RoutingContext): Throwable = when (failure) {
        is MarketBusyException -> {
            context.response().putHeader("Retry-After", RETRY_AFTER_SECONDS)
            StoreBusy()
        }

        is RequestValueException -> BadRequest(extras = mapOf("bodyValidationError" to failure.message))
        else -> failure
    }

    /** Runs [block] and throws [translate]d failures; cancellation passes through untouched. */
    suspend fun <T> guarded(context: RoutingContext, block: suspend () -> T): T {
        try {
            return block()
        } catch (e: MarketBusyException) {
            throw translate(e, context)
        } catch (e: RequestValueException) {
            throw translate(e, context)
        }
    }
}
