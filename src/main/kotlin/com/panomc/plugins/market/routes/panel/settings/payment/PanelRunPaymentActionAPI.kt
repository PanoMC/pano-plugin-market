package com.panomc.plugins.market.routes.panel.settings.payment

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.log.RanMarketProviderActionLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * `POST /api/panel/market/payment-methods/:id/actions/:actionId` (`P:SET`): runs a settings action of the provider
 * (`test-connection`, `register-webhooks`, `import-catalog`). A gateway failure is 502 `PAYMENT_PROVIDER_ERROR`.
 */
@Endpoint
class PanelRunPaymentActionAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val nodes: Set<MarketNode> = setOf(MarketNode.SETTINGS)

    override val paths = listOf(Path("/api/panel/market/payment-methods/:id/actions/:actionId", RouteType.POST))

    private val authProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(Parameters.param("id", stringSchema()))
            .pathParameter(Parameters.param("actionId", stringSchema()))
            .body(Bodies.json(objectSchema().allowAdditionalProperties(true)))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").string
        val actionId = parameters.pathParameter("actionId").string
        val body = context.body().asJsonObject() ?: JsonObject()
        val input = when (val raw = body.getValue("input")) {
            null -> JsonObject()
            is JsonObject -> raw
            else -> throw RequestValueException("input", "INVALID")
        }

        val outcome = paymentMethodService(plugin).runAction(id, actionId, input)

        val sqlClient = databaseManager.getSqlClient()
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(RanMarketProviderActionLog(userId, username, id, actionId, plugin.pluginId), sqlClient)

        return Successful(mapOf("success" to outcome.success, "message" to outcome.message?.toJson()))
    }
}
