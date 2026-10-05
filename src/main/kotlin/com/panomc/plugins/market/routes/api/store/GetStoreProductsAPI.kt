package com.panomc.plugins.market.routes.api.store

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.routes.base.MarketApi
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * `GET /api/market/store/products` (04 section 3, `PUB`): a filtered, sorted page of `ProductCard`s. Every query value is
 * read as a string and judged by [parseProductListQuery] (a bad value is 400, never a schema 500); a page beyond the last is
 * 404 `PAGE_NOT_FOUND`.
 */
@Endpoint
class GetStoreProductsAPI(private val plugin: MarketPlugin) : MarketApi() {
    override val paths = listOf(Path("/api/market/store/products", RouteType.GET))

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    private val service by lazy { storeQueryService(plugin) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler {
        val builder = ValidationHandlerBuilder.create(schemaRepository)

        for (name in listOf("category", "search", "featured", "kind", "sort", "currency", "page", "pageSize")) {
            builder.queryParameter(optionalParam(name, stringSchema()))
        }

        return builder.build()
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
