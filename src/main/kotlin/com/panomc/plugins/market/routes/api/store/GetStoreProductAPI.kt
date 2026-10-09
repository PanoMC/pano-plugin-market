package com.panomc.plugins.market.routes.api.store

import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import com.panomc.plugins.market.error.StoreUnavailable
import com.panomc.plugins.market.error.StoreDisabled
import com.panomc.platform.error.NotFound
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
import com.panomc.platform.schema.dsl.Parameters.param
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * `GET /api/market/products/:slug` (04 section 3, `PUB`): the `ProductDetail` of one product. 404 unless the product exists, is
 * ACTIVE (not ARCHIVED or soft deleted), is inside its window and its category is ACTIVE. Server-only fields (actions,
 * requiredPermission) are never exposed.
 */
@Endpoint
class GetStoreProductAPI(private val plugin: MarketPlugin) : MarketApi() {
    override val paths = listOf(Path("/products/:slug", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "One product page: description, variants, custom fields, bundle items and whether the caller can buy it.",
        tag = "store",
        response = objectSchema().requiredProperty("product", MarketSchemas.productDetail),
        errors = listOf(NotFound::class, BadRequest::class, StoreUnavailable::class, StoreDisabled::class)
    )

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
