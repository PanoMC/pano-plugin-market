package com.panomc.plugins.market.routes.panel.order

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.job.playerErasureService
import com.panomc.plugins.market.log.AnonymizedMarketOrderLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

/**
 * `POST /api/panel/market/orders/:id/anonymize` (`P:PAY`, 04 section 7): the order-level erasure steps of 11 section 16 for one order, the answer to a
 * guest's erasure request (a registered user's data goes with the deletion of the account). The e-mail, the addresses, the network data, the field values
 * and the access token of the order are blanked, the order keeps its rows and its money. 404 `NOT_FOUND` for an unknown id, 409 `INVALID_STATE` for an
 * order that is still `PENDING` or in `REVIEW`. Idempotent. Activity log `ANONYMIZED_MARKET_ORDER`.
 */
@Endpoint
class PanelAnonymizeOrderAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/orders/:id/anonymize", RouteType.POST))

    override val nodes: Set<MarketNode> = setOf(MarketNode.PAYMENTS)

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val order = panelOrder(plugin, context)

        playerErasureService(plugin).anonymizeOrder(order.id)

        logOrderDecision(plugin, context) { userId, username -> AnonymizedMarketOrderLog(userId, username, plugin.pluginId, order.id) }

        return Successful()
    }
}
