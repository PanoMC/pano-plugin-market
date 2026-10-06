package com.panomc.plugins.market.routes.panel.product

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.catalog.StockRequest
import com.panomc.plugins.market.log.UpdatedMarketProductStockLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema

/**
 * `POST /api/panel/market/products/:id/stock` (`P:CAT`): `{variantId?, mode: SET|ADJUST, value}` -> `{stock}`. Atomic:
 * ADJUST is one `stock = stock + ?` statement with a `>= 0` guard (04 section 5).
 */
@Endpoint
class PanelAdjustProductStockAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/products/:id/stock", RouteType.POST))

    override val nodes = setOf(MarketNode.CATALOG)

    private val catalog by lazy { catalogService(plugin) }

    private val authProvider: AuthProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }

    private val databaseManager: DatabaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(json(objectSchema()))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val id = context.productId()
        val body: JsonObject = getParameters(context).body().jsonObject
        val request = StockRequest.parse(body)

        val before = catalog.get(id).product
        val result = catalog.changeStock(id, request.variantId, request.mode, request.value)

        val sqlClient = databaseManager.getSqlClient()
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            UpdatedMarketProductStockLog(userId, username, plugin.pluginId, before.name, request.mode.name, request.value, request.variantId),
            sqlClient
        )

        return Successful(mapOf("stock" to result.stock))
    }
}
