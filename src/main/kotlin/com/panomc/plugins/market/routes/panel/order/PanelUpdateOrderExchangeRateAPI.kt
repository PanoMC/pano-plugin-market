package com.panomc.plugins.market.routes.panel.order

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.log.UpdatedMarketOrderExchangeRateLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.parseExchangeRate
import com.panomc.plugins.market.routes.base.parseId
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema

@Endpoint
class PanelUpdateOrderExchangeRateAPI(
    private val plugin: MarketPlugin,
    private val marketOrderDao: MarketOrderDao
) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/orders/:id/exchange-rate", RouteType.PUT))

    override val nodes = setOf(MarketNode.PAYMENTS)

    private val authProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }
    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", stringSchema()))
            .body(
                Bodies.json(
                    objectSchema()
                        .requiredProperty("exchangeRate", numberSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val id = parseId(context.pathParam("id"))

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject
        val exchangeRate = parseExchangeRate(data.getDouble("exchangeRate"))

        val sqlClient = databaseManager.getSqlClient()
        marketOrderDao.getById(id, sqlClient) ?: throw NotFound()

        marketOrderDao.updateExchangeRate(id, exchangeRate, sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            UpdatedMarketOrderExchangeRateLog(userId, username, plugin.pluginId, id, exchangeRate),
            sqlClient
        )

        return Successful()
    }
}
