package com.panomc.plugins.market.routes.panel.order

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.permission.ManageMarketPermission
import com.panomc.plugins.market.util.MoneyUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

@Endpoint
class PanelGetOrderAPI(
    private val plugin: MarketPlugin,
    private val marketOrderDao: MarketOrderDao,
    private val marketOrderItemDao: MarketOrderItemDao
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/market/orders/:id", RouteType.GET))

    private val authProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }
    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMarketPermission(), context)

        val id = context.pathParam("id").toLong()

        val sqlClient = databaseManager.getSqlClient()
        val order = marketOrderDao.getById(id, sqlClient) ?: throw NotFound()

        val items = marketOrderItemDao.getByOrderIds(listOf(order.id), sqlClient)

        return Successful(
            mapOf(
                "order" to mapOf(
                    "id" to order.id,
                    "userId" to order.userId,
                    "playerUsername" to order.playerUsername,
                    "totalPrice" to MoneyUtil.toDecimal(order.totalPrice),
                    "currency" to order.currency,
                    "paymentMethodId" to order.paymentMethodId,
                    "paymentLabel" to order.paymentLabel,
                    "status" to order.status.name,
                    "createdAt" to order.createdAt,
                    "updatedAt" to order.updatedAt
                ),
                "items" to items.map { itemToJson(it) }
            )
        )
    }

    private fun itemToJson(item: MarketOrderItem): Map<String, Any?> = mapOf(
        "id" to item.id,
        "productId" to item.productId,
        "productName" to item.productName,
        "quantity" to item.quantity,
        "unitPrice" to MoneyUtil.toDecimal(item.unitPrice),
        "createdAt" to item.createdAt,
        "updatedAt" to item.updatedAt
    )
}
