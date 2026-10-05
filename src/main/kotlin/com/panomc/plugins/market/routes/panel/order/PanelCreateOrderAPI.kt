package com.panomc.plugins.market.routes.panel.order

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.order.RequestFingerprint
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.log.CreatedMarketOrderLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.api.checkout.checkoutService
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.parseIdempotencyKey
import com.panomc.plugins.market.runtime.beans
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema

/**
 * `POST /api/panel/market/orders` (`P:PAY`, 04 section 7, 06 section 14.3): a manual order for a named player. `Idempotency-Key` required; the same key and body
 * again answers the first order, another body is 409 `IDEMPOTENCY_CONFLICT`. Answers `{id, publicId, warnings[]}`. Activity log `CREATED_MARKET_ORDER`.
 */
@Endpoint
class PanelCreateOrderAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/orders", RouteType.POST))

    override val nodes: Set<MarketNode> = setOf(MarketNode.PAYMENTS)

    private val service by lazy { checkoutService(plugin, withCheckout = true) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(Bodies.json(objectSchema().allowAdditionalProperties(true)))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val key = parseIdempotencyKey(context.request().getHeader("Idempotency-Key"), required = true)!!
        val body = context.body().asJsonObject() ?: JsonObject()
        val request = parseManualOrderRequest(body, key, RequestFingerprint.hash(body), body.getString("playerUsername")?.let { manualOrderLocale(plugin, it) })
        val sqlClient = plugin.applicationContext.getBean(DatabaseManager::class.java).getSqlClient()
        val result = service.createManualOrder(request, actingUserId(plugin, context), sqlClient)

        context.response().putHeader("Cache-Control", "no-store")

        val order = plugin.beans.getBean(MarketOrderDao::class.java).getById(result.id, sqlClient)

        if (order != null && !result.replay) {
            logOrderDecision(plugin, context) { userId, username ->
                CreatedMarketOrderLog(userId, username, plugin.pluginId, order.id, order.playerUsername, order.totalPrice / 100.0)
            }
        }

        return Successful(result.toMap())
    }
}
