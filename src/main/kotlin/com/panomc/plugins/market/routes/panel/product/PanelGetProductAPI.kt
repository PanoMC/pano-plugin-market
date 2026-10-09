package com.panomc.plugins.market.routes.panel.product

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository

/** `GET /api/panel/market/products/:id`: `CAT` or `PAY` (the manual order form reads it). */
@Endpoint
class PanelGetProductAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/products/:id", RouteType.GET))

    override val nodes = setOf(MarketNode.CATALOG, MarketNode.PAYMENTS)

    private val catalog by lazy { catalogService(plugin) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handleAuthorized(context: RoutingContext): Result =
        Successful(mapOf("product" to ProductJson.detail(catalog.get(context.productId()))))
}
