package com.panomc.plugins.market.routes.panel.order

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.permission.FieldGating
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.parseId
import com.panomc.plugins.market.routes.base.parsePagingRequest
import com.panomc.plugins.market.util.Paging
import com.panomc.plugins.market.util.MoneyUtil
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

@Endpoint
class PanelGetOrdersAPI(
    private val plugin: MarketPlugin,
    private val marketOrderDao: MarketOrderDao,
    private val marketOrderItemDao: MarketOrderItemDao
) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/orders", RouteType.GET))

    override val nodes = setOf(MarketNode.ORDERS_VIEW)

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(optionalParam("page", stringSchema()))
            .queryParameter(optionalParam("pageSize", stringSchema()))
            .queryParameter(optionalParam("search", stringSchema()))
            .queryParameter(optionalParam("status", stringSchema()))
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val window = parsePagingRequest(
            parameters.queryParameter("page")?.string?.let { parseId(it, "page") },
            parameters.queryParameter("pageSize")?.string?.let { parseId(it, "pageSize") }
        )
        val page = window.page.toLong()
        val pii = FieldGating.piiTier(context)
        val search = parameters.queryParameter("search")?.string
        val status = parameters.queryParameter("status")?.string?.let { statusName ->
            OrderStatus.entries.find { it.name == statusName }
        }

        val sqlClient = databaseManager.getSqlClient()
        val orders = marketOrderDao.getAllPaged(page, search, status, sqlClient, window.pageSize, pii)
        val count = marketOrderDao.count(search, status, sqlClient, pii)

        val totalPageNum = Paging.totalPages(count, window.pageSize)

        if (totalPageNum in 1..<page) {
            throw PageNotFound()
        }

        val itemsByOrder = marketOrderItemDao.getByOrderIds(orders.map { it.id }, sqlClient).groupBy { it.orderId }

        val ordersJson = orders.map { order ->
            orderToJson(order, itemsByOrder[order.id].orEmpty(), pii)
        }

        return Successful(
            mapOf(
                "orders" to ordersJson,
                "orderCount" to count,
                "totalPage" to totalPageNum
            )
        )
    }

    private fun orderToJson(order: MarketOrder, items: List<MarketOrderItem>, pii: Boolean): Map<String, Any?> = mapOf(
        "id" to order.id,
        "userId" to order.userId,
        "playerUsername" to order.playerUsername,
        "totalPrice" to MoneyUtil.toDecimal(order.totalPrice),
        "currency" to order.currency,
        "paymentMethodId" to order.paymentMethodId,
        "paymentLabel" to order.paymentLabel,
        "status" to order.status.name,
        "email" to FieldGating.email(order.email, pii),
        "createdAt" to order.createdAt,
        "updatedAt" to order.updatedAt,
        "items" to items.map { itemToJson(it) }
    )

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
