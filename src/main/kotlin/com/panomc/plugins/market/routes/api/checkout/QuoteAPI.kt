package com.panomc.plugins.market.routes.api.checkout

import com.panomc.plugins.market.core.abuse.AbuseLimits
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.util.RateLimiter
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.abuse.IpRange
import com.panomc.plugins.market.error.TooManyRequests
import com.panomc.plugins.market.routes.base.MarketPublicMutationApi
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.service.ClientIpResolver
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema

/**
 * `POST /api/market/checkout/quote` (04 section 3, auth class `PUB-M`): a `CartInput` in, a `Quote` out. Never fails for a
 * business reason: problems are `messages` / `lines[].errors` with `canCheckout = false`. The only endpoint that accepts
 * `useCredits: "MAX"`. Limited to `quoteRateLimitPerMinute` per client address (the limiter is skipped when the address is
 * unknown, 11 section 2); the answer is never cached.
 */
@Endpoint
class QuoteAPI(private val plugin: MarketPlugin) : MarketPublicMutationApi() {
    override val paths = listOf(Path("/api/market/checkout/quote", RouteType.POST))

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    private val service by lazy { checkoutService(plugin) }

    @Volatile
    private var limiter: Pair<Int, RateLimiter>? = null

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(Bodies.json(objectSchema().allowAdditionalProperties(true)))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handleMarket(context: RoutingContext): Result {
        val ip = ClientIpResolver.resolve(context)

        val key = limiterKey(ip.ip)

        if (ip.trusted && key != null) limit(key)

        val input = parseQuoteInput(getParameters(context).body().jsonObject)
        val caller = quoteCaller(plugin, context)

        context.response().putHeader("Cache-Control", "no-store")

        return Successful(mapOf("quote" to service.quote(input, caller, databaseManager.getSqlClient()).toJson()))
    }

    /** 60 / min per limiter key (`limiterKey`) by default; `0` switches the limit off. The bucket is rebuilt when the setting changes. */
    private fun limit(ip: String) {
        val perMinute = currentConfig(plugin).quoteRateLimitPerMinute

        if (perMinute <= 0) return

        val current = limiter?.takeIf { it.first == perMinute } ?: (perMinute to RateLimiter(perMinute, AbuseLimits.refillMs(perMinute)!!)).also { limiter = it }

        if (!current.second.tryAcquire(ip)) throw TooManyRequests(Math.ceil(60.0 / perMinute).toLong())
    }

    companion object {
        /** Limiter key of a client address (11 section 2 rule 4): IPv4 as is, IPv6 by its `/64` prefix; null when unusable. */
        internal fun limiterKey(ip: String?): String? = IpRange.bucketKey(ip)
    }
}
