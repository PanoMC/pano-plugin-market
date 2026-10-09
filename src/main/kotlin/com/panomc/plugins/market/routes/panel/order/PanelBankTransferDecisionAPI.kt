package com.panomc.plugins.market.routes.panel.order

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.log.ApprovedMarketBankTransferLog
import com.panomc.plugins.market.log.RejectedMarketBankTransferLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.api.order.bankTransferService
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.service.BankTransferDecision
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
 * `POST /api/panel/market/orders/:id/bank-transfer` (`P:PAY`, 04 section 7, 06 section 14.1 step 3): `decision` `APPROVE` (O2, or O9 for money that arrived after
 * the order was released) or `REJECT` (the attempt fails, the order stays `PENDING` until it expires), `note?`. 404 for an order without a bank transfer attempt,
 * 409 `ORDER_NOT_PAYABLE` for any other state. Activity log `APPROVED_MARKET_BANK_TRANSFER` / `REJECTED_MARKET_BANK_TRANSFER`.
 */
@Endpoint
class PanelBankTransferDecisionAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/orders/:id/bank-transfer", RouteType.POST))

    override val nodes: Set<MarketNode> = setOf(MarketNode.PAYMENTS)

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    objectSchema()
                        .requiredProperty("decision", enumSchema(*BankTransferDecision.entries.map { it.name }.toTypedArray()))
                        .optionalProperty("note", stringSchema())
                        .allowAdditionalProperties(true)
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val order = panelOrder(plugin, context)
        val body = context.body().asJsonObject() ?: JsonObject()
        val decision = BankTransferDecision.valueOf(body.getString("decision") ?: throw RequestValueException("decision", "REQUIRED"))

        bankTransferService(plugin).decide(order.id, decision, body.getString("note"), actingUserId(plugin, context))

        logOrderDecision(plugin, context) { userId, username ->
            if (decision == BankTransferDecision.APPROVE) ApprovedMarketBankTransferLog(userId, username, plugin.pluginId, order.id)
            else RejectedMarketBankTransferLog(userId, username, plugin.pluginId, order.id)
        }

        return Successful()
    }
}
