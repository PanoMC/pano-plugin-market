package com.panomc.plugins.market.routes.api.store

import com.panomc.plugins.market.error.StoreUnavailable
import com.panomc.plugins.market.error.StoreDisabled
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.routes.base.MarketSchemas
import com.panomc.platform.error.InvalidFields
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Paging
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.routes.base.MarketApi
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * `GET /api/market/store/products` (04 section 3, `PUB`): a filtered, sorted page of `ProductCard`s. Every query value is
 * read as a string and judged by [parseProductListQuery] (a bad value is 400, never a schema 500); a page beyond the last is
 * 404 `PAGE_NOT_FOUND`.
 */
@Endpoint
class GetStoreProductsAPI(private val plugin: MarketPlugin) : MarketApi() {
    override val paths = listOf(Path("/store/products", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "A filtered and sorted page of products.",
        tag = "store",
        paginatedItem = MarketSchemas.productCard,
        errors = listOf(NotFound::class, InvalidFields::class, PageNotFound::class, StoreUnavailable::class, StoreDisabled::class)
    )

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    private val service by lazy { storeQueryService(plugin) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler {
        val builder = ValidationHandlerBuilder.create(schemaRepository)

        for (name in listOf("category", "search", "featured", "kind", "sort", "currency")) {
            builder.queryParameter(optionalParam(name, stringSchema()))
        }

        return Paging.params(builder).build()
    }

    override suspend fun handleMarket(context: RoutingContext): Result {
        val request = context.request()
        val query = parseProductListQuery(
            category = request.getParam("category"),
            search = request.getParam("search"),
            featured = request.getParam("featured"),
            kind = request.getParam("kind"),
            sort = request.getParam("sort"),
            currency = request.getParam("currency"),
            page = request.getParam("page"),
            pageSize = request.getParam("pageSize"),
            defaultPageSize = currentConfig(plugin).storePageSize
        )
        val viewer = storeViewer(plugin, context)
        val body = service.products(query, viewer, databaseManager.getSqlClient())

        return Successful(body.map)
    }
}
