package com.panomc.plugins.market.routes.panel.order

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.log.UpdatedMarketOrderStatusLog
import com.panomc.plugins.market.permission.ManageMarketPermission
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.enumSchema
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema

@Endpoint
class PanelUpdateOrderStatusAPI(
    private val plugin: MarketPlugin,
    private val marketOrderDao: MarketOrderDao
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/market/orders/:id/status", RouteType.PUT))

    private val authProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }
    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .body(
                Bodies.json(
                    objectSchema()
                        .requiredProperty("status", enumSchema(*OrderStatus.entries.map { it.name }.toTypedArray()))
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMarketPermission(), context)

        val id = context.pathParam("id").toLong()

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject
        val status = OrderStatus.valueOf(data.getString("status"))

        val sqlClient = databaseManager.getSqlClient()
        marketOrderDao.getById(id, sqlClient) ?: throw NotFound()

        marketOrderDao.updateStatus(id, status, sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            UpdatedMarketOrderStatusLog(userId, username, plugin.pluginId, id, status.name),
            sqlClient
        )

        return Successful()
    }
}
