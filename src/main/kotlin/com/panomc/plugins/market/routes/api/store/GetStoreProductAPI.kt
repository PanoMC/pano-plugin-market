package com.panomc.plugins.market.routes.api.store

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
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * `GET /api/market/products/:slug` (04 section 3, `PUB`): the `ProductDetail` of one product. 404 unless the product exists, is
 * ACTIVE (not ARCHIVED or soft deleted), is inside its window and its category is ACTIVE. Server-only fields (actions,
 * requiredPermission) are never exposed.
 */
@Endpoint
class GetStoreProductAPI(private val plugin: MarketPlugin) : MarketApi() {
    override val paths = listOf(Path("/api/market/products/:slug", RouteType.GET))

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    private val service by lazy { storeQueryService(plugin) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("slug", stringSchema()))
            .queryParameter(optionalParam("currency", stringSchema()))
            .build()

    override suspend fun handleMarket(context: RoutingContext): Result {
        val slug = getParameters(context).pathParameter("slug").string
        val currency = parseCurrencyParam(context.request().getParam("currency"))
        val viewer = storeViewer(plugin, context)
        val product = service.product(slug, currency, viewer, databaseManager.getSqlClient())

        return Successful(mapOf("product" to product))
    }
}
