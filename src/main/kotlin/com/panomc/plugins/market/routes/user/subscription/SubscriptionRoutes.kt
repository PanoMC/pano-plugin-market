package com.panomc.plugins.market.routes.user.subscription

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.subscription.CancelActor
import com.panomc.plugins.market.routes.api.order.noStore
import com.panomc.plugins.market.routes.api.payment.AttemptPages
import com.panomc.plugins.market.routes.base.MarketUserApi
import com.panomc.plugins.market.routes.base.parseId
import com.panomc.plugins.market.routes.base.parseOptionalEnum
import com.panomc.plugins.market.routes.panel.subscription.subscriptionActions
import com.panomc.plugins.market.routes.panel.subscription.subscriptionViews
import com.panomc.plugins.market.routes.user.cart.buyerId
import com.panomc.plugins.market.service.CancelOutcome
import com.panomc.plugins.market.service.PortalPages
import com.panomc.plugins.market.spi.payment.PortalPurpose
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

private fun databaseManager(plugin: MarketPlugin) = plugin.applicationContext.getBean(DatabaseManager::class.java)

private fun optionalBody(schemaRepository: SchemaRepository, build: io.vertx.json.schema.common.dsl.ObjectSchemaBuilder.() -> Unit = {}): ValidationHandler =
    ValidationHandlerBuilder.create(schemaRepository).body(Bodies.json(objectSchema().allowAdditionalProperties(false).also { it.build() })).build()

/** `GET /api/market/me/subscriptions` (`USER`, 04 section 4, 09 section 13): every row of the buyer except `PENDING`, newest first. */
@Endpoint
class GetMySubscriptionsAPI(private val plugin: MarketPlugin) : MarketUserApi() {
    override val paths = listOf(Path("/api/market/me/subscriptions", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handleMarket(context: RoutingContext): Result {
        val rows = subscriptionViews(plugin).buyerList(buyerId(plugin, context), databaseManager(plugin).getSqlClient())

        noStore(context)

        return Successful(mapOf("subscriptions" to rows))
    }
}

/**
 * `POST /api/market/me/subscriptions/:id/cancel` (`USER`, CSRF, 09 section 10.1): `atPeriodEnd?` (default `true`). Answers `{subscription}` (the same object as the list),
 * or `{action: "REDIRECT", url}` when only the buyer can cancel, at the gateway. 404 for another account's subscription; 409 `SUBSCRIPTION_NOT_CANCELLABLE`; 502 `PAYMENT_PROVIDER_ERROR`.
 */
@Endpoint
class CancelMySubscriptionAPI(private val plugin: MarketPlugin) : MarketUserApi() {
    override val paths = listOf(Path("/api/market/me/subscriptions/:id/cancel", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = optionalBody(schemaRepository) { optionalProperty("atPeriodEnd", booleanSchema()) }

    override suspend fun handleMarket(context: RoutingContext): Result {
        val id = parseId(context.pathParam("id"))
        val body = context.body().asJsonObject() ?: JsonObject()
        val atPeriodEnd = body.getBoolean("atPeriodEnd") ?: true

        noStore(context)

        return when (val outcome = subscriptionActions(plugin).cancel(id, CancelActor.BUYER, buyerId(plugin, context), atPeriodEnd)) {
            is CancelOutcome.Redirect -> Successful(mapOf("action" to "REDIRECT", "url" to outcome.url))

            is CancelOutcome.Done -> Successful(mapOf("subscription" to subscriptionViews(plugin).buyerRow(outcome.row, databaseManager(plugin).getSqlClient()).map))
        }
    }
}

/** `POST /api/market/me/subscriptions/:id/resume` (`USER`, CSRF, 09 section 10.2): undo a cancel at the period end; `{subscription}`. 409 `SUBSCRIPTION_NOT_RESUMABLE`; 502. */
@Endpoint
class ResumeMySubscriptionAPI(private val plugin: MarketPlugin) : MarketUserApi() {
    override val paths = listOf(Path("/api/market/me/subscriptions/:id/resume", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = optionalBody(schemaRepository)

    override suspend fun handleMarket(context: RoutingContext): Result {
        val row = subscriptionActions(plugin).resume(parseId(context.pathParam("id")), buyerId(plugin, context))

        noStore(context)

        return Successful(mapOf("subscription" to subscriptionViews(plugin).buyerRow(row, databaseManager(plugin).getSqlClient()).map))
    }
}

/**
 * `POST /api/market/me/subscriptions/:id/portal` (`USER`, CSRF, 09 section 10.4a): `purpose?` (`MANAGE` default, `UPDATE_PAYMENT_METHOD`, `CANCEL`); `{url}`, the gateway's
 * hosted page. 404 for another account's subscription; 409 `SUBSCRIPTION_NOT_MANAGEABLE`; 502.
 */
@Endpoint
class PortalMySubscriptionAPI(private val plugin: MarketPlugin) : MarketUserApi() {
    override val paths = listOf(Path("/api/market/me/subscriptions/:id/portal", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = optionalBody(schemaRepository) { optionalProperty("purpose", stringSchema()) }

    override suspend fun handleMarket(context: RoutingContext): Result {
        val id = parseId(context.pathParam("id"))
        val body = context.body().asJsonObject() ?: JsonObject()
        val purpose = parseOptionalEnum(PortalPurpose.entries.toTypedArray(), body.getString("purpose"), "purpose") ?: PortalPurpose.MANAGE
        val url = subscriptionActions(plugin).portal(id, buyerId(plugin, context), purpose)

        noStore(context)

        return Successful(mapOf("url" to url))
    }
}

/**
 * `GET /api/market/me/subscriptions/portal-pages/:token` (`USER`, 09 section 10.4a, 02 section 6): the `Html` document of a gateway's portal, only for the account it was
 * made for and only inside a CSP `sandbox` without `allow-same-origin` (opaque origin: no cookies, no credentialed call to `/api`), `no-store`, `nosniff`, `no-referrer`.
 */
@Endpoint
class GetMySubscriptionPortalPageAPI(private val plugin: MarketPlugin) : MarketUserApi() {
    override val paths = listOf(Path("/api/market/me/subscriptions/portal-pages/:token", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).pathParameter(param("token", stringSchema())).build()

    override suspend fun handleMarket(context: RoutingContext): Result? {
        val token = context.pathParam("token").orEmpty()

        if (!TOKEN.matches(token)) throw NotFound()

        val document = subscriptionActions(plugin).portalPages.get(token, buyerId(plugin, context)) ?: throw NotFound()
        val nonce = com.panomc.plugins.market.core.time.SecureIds().hexToken(16)
        val response = context.response()

        response.statusCode = 200

        for ((name, value) in AttemptPages.htmlHeaders(emptyList(), emptyList(), emptyList(), emptyList(), false, nonce)) response.putHeader(name, value)

        response.end(document)

        return null
    }

    private companion object {
        val TOKEN = Regex("^[0-9a-f]{${PortalPages.TOKEN_BYTES * 2}}$")
    }
}
