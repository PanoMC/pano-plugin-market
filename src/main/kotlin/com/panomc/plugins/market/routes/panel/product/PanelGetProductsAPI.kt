package com.panomc.plugins.market.routes.panel.product

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.model.ProductKind
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.parseId
import com.panomc.plugins.market.routes.base.parseOptionalEnum
import com.panomc.plugins.market.routes.base.parsePagingRequest
import com.panomc.plugins.market.util.Paging
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/** `GET /api/panel/market/products` (`P:CAT`): q `page`, `pageSize`, `search`, `status`, `kind`, `categoryId`. */
@Endpoint
class PanelGetProductsAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/products", RouteType.GET))

    override val nodes = setOf(MarketNode.CATALOG)

    private val catalog by lazy { catalogService(plugin) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(optionalParam("page", stringSchema()))
            .queryParameter(optionalParam("pageSize", stringSchema()))
            .queryParameter(optionalParam("search", stringSchema()))
            .queryParameter(optionalParam("status", stringSchema()))
            .queryParameter(optionalParam("kind", stringSchema()))
            .queryParameter(optionalParam("categoryId", stringSchema()))
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val window = parsePagingRequest(
            parameters.queryParameter("page")?.string?.let { parseId(it, "page") },
            parameters.queryParameter("pageSize")?.string?.let { parseId(it, "pageSize") }
        )
        val search = parameters.queryParameter("search")?.string
        val status = parameters.queryParameter("status")?.string
        val kind = parseOptionalEnum(ProductKind.entries.toTypedArray(), parameters.queryParameter("kind")?.string, "kind")
        val categoryId = parameters.queryParameter("categoryId")?.string?.let { parseId(it, "categoryId") }

        val page = catalog.list(window.page.toLong(), window.pageSize, search, status, kind, categoryId)
        val totalPage = Paging.totalPages(page.count, window.pageSize)

        if (Paging.isBeyondLast(window.page, totalPage)) throw PageNotFound()

        return Successful(
            mapOf(
                "products" to JsonArray(page.products.map { ProductJson.row(it) }),
                "productCount" to page.count,
                "totalPage" to totalPage
            )
        )
    }
}
