package com.panomc.plugins.market.routes.user.order

import com.panomc.plugins.market.error.StoreUnavailable
import com.panomc.plugins.market.error.StoreDisabled
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.error.NotLoggedIn
import com.panomc.plugins.market.routes.base.MarketSchemas
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.schema.CoreSchemas
import com.panomc.platform.error.BadRequest
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Paging
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.routes.api.order.noStore
import com.panomc.plugins.market.routes.base.MarketUserApi
import com.panomc.plugins.market.routes.user.cart.buyerId
import com.panomc.plugins.market.runtime.beans
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

private fun buyerOrderViews(plugin: MarketPlugin) = BuyerOrderViews { plugin.beans.getBean(MarketOrderDao::class.java).prefix() }

/**
 * `GET /api/market/me/orders` (`USER`, 04 section 4): q `page?`, `pageSize?`, `status?` (csv of order statuses); the caller's orders and the gifts the caller
 * received (`received: true`), newest first. 400 on an unknown status, 404 `PAGE_NOT_FOUND` beyond the last page.
 */
@Endpoint
class GetMyOrdersAPI(private val plugin: MarketPlugin) : MarketUserApi() {
    override val paths = listOf(Path("/me/orders", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "The orders of the buyer and the gifts the buyer received, newest first.",
        tag = "me",
        paginatedItem = MarketSchemas.buyerOrder,
        errors = listOf(BadRequest::class, PageNotFound::class, NotLoggedIn::class, StoreUnavailable::class, StoreDisabled::class)
    )

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    private val views by lazy { buyerOrderViews(plugin) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        Paging.params(ValidationHandlerBuilder.create(schemaRepository))
            .queryParameter(optionalParam("status", stringSchema()))
            .build()

    override suspend fun handleMarket(context: RoutingContext): Result {
        val request = context.request()
        val window = Paging.request(context)
        val statuses = views.parseStatuses(request.getParam("status"))
        val body = views.orders(buyerId(plugin, context), window, statuses, databaseManager.getSqlClient())

        noStore(context)

        return Successful(body.map)
    }
}

/** `GET /api/market/me/entitlements` (`USER`, 04 section 4): q `active?` (`true` = ACTIVE and not ended); what the caller owns, gifts included. */
@Endpoint
class GetMyEntitlementsAPI(private val plugin: MarketPlugin) : MarketUserApi() {
    override val paths = listOf(Path("/me/entitlements", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "What the buyer owns, gifts included.",
        tag = "me",
        response = CoreSchemas.list(MarketSchemas.entitlement),
        errors = listOf(BadRequest::class, NotLoggedIn::class, StoreUnavailable::class, StoreDisabled::class)
    )

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    private val views by lazy { buyerOrderViews(plugin) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(optionalParam("active", stringSchema()))
            .build()

    override suspend fun handleMarket(context: RoutingContext): Result {
        val raw = context.request().getParam("active")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val active = when (raw) {
            null, "false" -> false
            "true" -> true
            else -> throw RequestValueException("active", "MUST_BE_TRUE_OR_FALSE")
        }
        val body = views.entitlements(buyerId(plugin, context), active, SystemClock.now(), databaseManager.getSqlClient())

        noStore(context)

        return Successful(body.map)
    }
}
