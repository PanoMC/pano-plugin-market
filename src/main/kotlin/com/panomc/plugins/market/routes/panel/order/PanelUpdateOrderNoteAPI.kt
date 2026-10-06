package com.panomc.plugins.market.routes.panel.order

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.log.UpdatedMarketOrderNoteLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.parseId
import com.panomc.plugins.market.service.OrderQueryService
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * `PUT /api/panel/market/orders/:id/note` (`P:OM`, 04 section 7, 13 section 6.1): body `note*` (at most [OrderQueryService.NOTE_MAX] characters; blank clears it). 404 for an
 * unknown order. The text is not written to the activity log (`UPDATED_MARKET_ORDER_NOTE` names the order only).
 */
@Endpoint
class PanelUpdateOrderNoteAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/orders/:id/note", RouteType.PUT))

    override val nodes = setOf(MarketNode.ORDERS_MANAGE)

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", stringSchema()))
            .body(Bodies.json(objectSchema().requiredProperty("note", stringSchema()).allowAdditionalProperties(true)))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val id = parseId(context.pathParam("id"))
        val body = context.body().asJsonObject() ?: JsonObject()

        orderQueryService(plugin).setNote(id, body.getString("note"), actingUserId(plugin, context), databaseManager.getSqlClient())

        logOrderDecision(plugin, context) { userId, username -> UpdatedMarketOrderNoteLog(userId, username, plugin.pluginId, id) }

        return Successful()
    }
}
