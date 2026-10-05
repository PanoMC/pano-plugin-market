package com.panomc.plugins.market.routes.panel.product

import com.panomc.plugins.market.runtime.beans
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository

/** `GET /api/panel/market/products/simple`: a picker list, readable with any market node (04 section 5). */
@Endpoint
class PanelGetSimpleProductsAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    // order = 0 so /products/simple is registered before the /products/:id path parameter route.
    override val order = 0

    override val paths = listOf(Path("/api/panel/market/products/simple", RouteType.GET))

    override val nodes: Set<MarketNode> = emptySet()

    private val databaseManager: DatabaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    private val products: MarketProductDao by lazy { plugin.beans.getBean(MarketProductDao::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handleAuthorized(context: RoutingContext): Result =
        Successful(
            mapOf(
                "products" to JsonArray(products.getAllSimple(databaseManager.getSqlClient()).map { ProductJson.simple(it) })
            )
        )
}
