package com.panomc.plugins.market.routes.panel.product

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Path
import com.panomc.platform.model.Paging
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.model.ProductKind
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.parseId
import com.panomc.plugins.market.routes.base.parseOptionalEnum
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/** `GET /api/panel/market/products` (`P:CAT`): q `page`, `pageSize`, `search`, `status`, `kind`, `categoryId`. */
@Endpoint
class PanelGetProductsAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/products", RouteType.GET))

    override val nodes = setOf(MarketNode.CATALOG)

    private val catalog by lazy { catalogService(plugin) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        Paging.params(ValidationHandlerBuilder.create(schemaRepository))
            .queryParameter(optionalParam("search", stringSchema()))
            .queryParameter(optionalParam("status", stringSchema()))
            .queryParameter(optionalParam("kind", stringSchema()))
            .queryParameter(optionalParam("categoryId", stringSchema()))
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val window = Paging.request(context)
        val search = parameters.queryParameter("search")?.string
        val status = parameters.queryParameter("status")?.string
        val kind = parseOptionalEnum(ProductKind.entries.toTypedArray(), parameters.queryParameter("kind")?.string, "kind")
        val categoryId = parameters.queryParameter("categoryId")?.string?.let { parseId(it, "categoryId") }

        val page = catalog.list(window.number.toLong(), window.size, search, status, kind, categoryId)

        return Successful(Paging.response(page.products.map { ProductJson.row(it) }, page.count, window))
    }
}
