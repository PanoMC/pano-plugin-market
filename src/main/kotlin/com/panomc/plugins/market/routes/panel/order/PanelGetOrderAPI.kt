package com.panomc.plugins.market.routes.panel.order

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.permission.FieldGating
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.parseId
import com.panomc.plugins.market.service.OrderViewer
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * `GET /api/panel/market/orders/:id` (`P:OV`, 04 section 7, 13 section 6): the order (every column but the tokens, the personal fields only with `OM` or `PAY`), `items[]`,
 * `payments[]`, `refunds[]`, `disputes[]`, `deliveries[]`, `shipments[]`, `events[]`, `invoices[]`, `mails[]`, `subscription`, `revokePending`, `revokeFailed` and `allowed{}`
 * (what this caller can do with this order now), in the row shapes pinned in 04 section 7. Built by [com.panomc.plugins.market.service.OrderQueryService.detail].
 */
@Endpoint
class PanelGetOrderAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/orders/:id", RouteType.GET))

    override val nodes = setOf(MarketNode.ORDERS_VIEW)

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", stringSchema()))
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val id = parseId(context.pathParam("id"))
        val viewer = OrderViewer.of(manage = has(context, MarketNode.ORDERS_MANAGE), pay = has(context, MarketNode.PAYMENTS))
        val detail = orderQueryService(plugin).detail(id, viewer, databaseManager.getSqlClient()) ?: throw NotFound()

        return Successful(detail.map)
    }
}
