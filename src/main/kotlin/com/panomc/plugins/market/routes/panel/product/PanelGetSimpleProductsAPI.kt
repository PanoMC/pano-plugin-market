package com.panomc.plugins.market.routes.panel.product

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.permission.ManageMarketPermission
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository

@Endpoint
class PanelGetSimpleProductsAPI(
    private val plugin: MarketPlugin,
    private val marketProductDao: MarketProductDao
) : PanelApi() {
    // order = 0 so /products/simple is registered before the /products/:id path parameter route.
    override val order = 0

    override val paths = listOf(Path("/api/panel/market/products/simple", RouteType.GET))

    private val authProvider: AuthProvider by lazy {
        plugin.applicationContext.getBean(AuthProvider::class.java)
    }

    private val databaseManager: DatabaseManager by lazy {
        plugin.applicationContext.getBean(DatabaseManager::class.java)
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMarketPermission(), context)

        val sqlClient = databaseManager.getSqlClient()
        val products = marketProductDao.getAllSimple(sqlClient)

        val productList = JsonArray()
        products.forEach { product ->
            productList.add(
                JsonObject()
                    .put("id", product.id)
                    .put("name", product.name)
            )
        }

        return Successful(mapOf("products" to productList))
    }
}
