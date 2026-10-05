package com.panomc.plugins.market.routes.api.checkout

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.abuse.IpRange
import com.panomc.plugins.market.routes.base.MarketPublicMutationApi
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
 * `useCredits: "MAX"`. Limited by L2 (`MarketRateLimits.quote`): `quoteRateLimitPerMinute` per client address (skipped when the address is
 * not trusted, 11 section 2) and per account; the answer is never cached.
 */
@Endpoint
class QuoteAPI(private val plugin: MarketPlugin) : MarketPublicMutationApi() {
    override val paths = listOf(Path("/api/market/checkout/quote", RouteType.POST))

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    private val service by lazy { checkoutService(plugin) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(Bodies.json(objectSchema().allowAdditionalProperties(true)))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handleMarket(context: RoutingContext): Result {
        val ip = ClientIpResolver.resolve(context)
        val caller = quoteCaller(plugin, context)

        // L2 (11 section 11): the IP bucket only for a trusted address, the buyer bucket for a session
        abuseWiring(plugin).rateLimits.quote(ip.ip.takeIf { ip.trusted }, caller.userId)

        val input = parseQuoteInput(getParameters(context).body().jsonObject)

        context.response().putHeader("Cache-Control", "no-store")

        return Successful(mapOf("quote" to service.quote(input, caller, databaseManager.getSqlClient()).toJson()))
    }

    companion object {
        /** Limiter key of a client address (11 section 2 rule 4): IPv4 as is, IPv6 by its `/64` prefix; null when unusable. */
        internal fun limiterKey(ip: String?): String? = IpRange.bucketKey(ip)
    }
}
