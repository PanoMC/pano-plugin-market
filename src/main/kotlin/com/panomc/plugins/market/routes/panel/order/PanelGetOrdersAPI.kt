package com.panomc.plugins.market.routes.panel.order

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.permission.FieldGating
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.parseId
import com.panomc.plugins.market.routes.base.parsePagingRequest
import com.panomc.plugins.market.util.Paging
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * `GET /api/panel/market/orders` (`P:OV`, 04 section 7, 13 section 5): q `page`, `pageSize`, `status` (csv), `paymentMethodId`, `fulfillmentStatus` (csv),
 * `shippingStatus` (csv), `from`, `to`, `testMode`, `source` (csv), `search`. The search also matches the `publicId`, the recipient, a gateway transaction id and, only
 * for a caller with `OM` or `PAY`, the e-mail (no oracle); `email` of a row is masked below that tier. The query is [OrderQueryService.list](com.panomc.plugins.market.service.OrderQueryService.list).
 */
@Endpoint
class PanelGetOrdersAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/orders", RouteType.GET))

    override val nodes = setOf(MarketNode.ORDERS_VIEW)

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler {
        var builder = ValidationHandlerBuilder.create(schemaRepository)

        for (name in listOf("page", "pageSize") + ORDER_FILTER_QUERY) builder = builder.queryParameter(optionalParam(name, stringSchema()))

        return builder.build()
    }

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val parameters = getParameters(context)

        fun query(name: String) = parameters.queryParameter(name)?.string

        val window = parsePagingRequest(query("page")?.let { parseId(it, "page") }, query("pageSize")?.let { parseId(it, "pageSize") })
        val filter = parseOrderFilter(
            query("status"), query("paymentMethodId"), query("fulfillmentStatus"), query("shippingStatus"), query("from"), query("to"), query("testMode"), query("source"), query("search")
        )
        val pii = FieldGating.piiTier(context)
        val page = orderQueryService(plugin).list(filter, window, pii, databaseManager.getSqlClient())
        val totalPage = Paging.totalPages(page.count, window.pageSize)

        if (totalPage in 1..<window.page.toLong()) throw PageNotFound()

        return Successful(mapOf("orders" to JsonArray(page.rows), "orderCount" to page.count, "totalPage" to totalPage))
    }
}
