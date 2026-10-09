package com.panomc.plugins.market.routes.api.store

import com.panomc.plugins.market.error.StoreUnavailable
import com.panomc.plugins.market.error.StoreDisabled
import com.panomc.plugins.market.routes.base.MarketSchemas
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.error.BadRequest
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.routes.base.MarketApi
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * `GET /api/market/store` (04 section 3, auth class `PUB`): store settings, the ACTIVE category tree, the first page of
 * `ProductCard`s, featured and bestseller cards, the comparisons and the cards they reference. See `StoreQueryService`.
 * An anonymous answer may be cached for 30 seconds; a logged-in caller's (`owned`, upgrade prices) never.
 */
@Endpoint
class GetStoreAPI(private val plugin: MarketPlugin) : MarketApi() {
    override val paths = listOf(Path("/store", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "The store front: settings, the category tree, the first page of products, featured and bestselling products and the comparisons.",
        tag = "store",
        response = MarketSchemas.store,
        errors = listOf(BadRequest::class, StoreUnavailable::class, StoreDisabled::class)
    )

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    private val service by lazy { storeQueryService(plugin) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(optionalParam("currency", stringSchema()))
            .build()

    override suspend fun handleMarket(context: RoutingContext): Result {
        val currency = parseCurrencyParam(context.request().getParam("currency"))
        val viewer = storeViewer(plugin, context)
        val body = service.store(currency, viewer, databaseManager.getSqlClient())

        context.response().putHeader("Cache-Control", if (viewer.loggedIn) "private, no-store" else "public, max-age=30")

        return Successful(body.map)
    }
}
