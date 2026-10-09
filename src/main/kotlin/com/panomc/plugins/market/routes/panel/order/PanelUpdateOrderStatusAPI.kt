package com.panomc.plugins.market.routes.panel.order

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.log.CancelledMarketOrderLog
import com.panomc.plugins.market.log.UpdatedMarketOrderStatusLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.enumSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * `PUT /api/panel/market/orders/:id/status` (`P:PAY`, 04 section 7, 06 section 11): re-implemented on the order state machine, no free-form write.
 * `COMPLETED` marks a pending order paid (O2, actor `ADMIN`), `FAILED` is O8, `CANCELLED` is O7; the current status is a no-op 200; `REFUNDED` is
 * 400 `INVALID_ORDER_TRANSITION {use: "refunds"}`, an order in `REVIEW` is 400 `{use: "review"}`. Activity log `UPDATED_MARKET_ORDER_STATUS`, or
 * `CANCELLED_MARKET_ORDER` for a cancel.
 */
@Endpoint
class PanelUpdateOrderStatusAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/orders/:id/status", RouteType.PUT))

    override val nodes: Set<MarketNode> = setOf(MarketNode.PAYMENTS)

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    objectSchema()
                        .requiredProperty("status", enumSchema(*OrderStatus.entries.map { it.name }.toTypedArray()))
                        .optionalProperty("note", stringSchema())
                        .allowAdditionalProperties(true)
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val order = panelOrder(plugin, context)
        val body = context.body().asJsonObject() ?: JsonObject()
        val status = OrderStatus.valueOf(body.getString("status"))

        val change = orderReviewService(plugin).setStatus(order.id, status, body.getString("note"), actingUserId(plugin, context))

        // a no-op writes nothing and logs nothing: fulfilment side effects can never be re-triggered by repeating the request
        if (!change.moved) return Successful()

        logOrderDecision(plugin, context) { userId, username ->
            if (status == OrderStatus.CANCELLED) CancelledMarketOrderLog(userId, username, plugin.pluginId, order.id)
            else UpdatedMarketOrderStatusLog(userId, username, plugin.pluginId, order.id, status.name)
        }

        return Successful()
    }
}
