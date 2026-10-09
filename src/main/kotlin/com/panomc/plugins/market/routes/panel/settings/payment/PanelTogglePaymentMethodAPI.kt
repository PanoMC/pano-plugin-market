package com.panomc.plugins.market.routes.panel.settings.payment

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.log.ToggledMarketPaymentMethodLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies
import com.panomc.platform.schema.dsl.Parameters
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * `POST /api/panel/market/payment-methods/:id/toggle` (`P:SET`). Enabling needs a usable provider (409 `PROVIDER_UNAVAILABLE`),
 * every required setting (`PAYMENT_METHOD_NOT_CONFIGURED`), a public https site where the provider needs one (400
 * `PUBLIC_URL_REQUIRED`) and a passing `validateSettings`.
 */
@Endpoint
class PanelTogglePaymentMethodAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val nodes: Set<MarketNode> = setOf(MarketNode.SETTINGS)

    override val paths = listOf(Path("/payment-methods/:id/toggle", RouteType.POST))

    private val authProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(Parameters.param("id", stringSchema()))
            .body(Bodies.json(objectSchema().requiredProperty("enabled", booleanSchema())))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val id = getParameters(context).pathParameter("id").string
        val enabled = context.body().asJsonObject().getBoolean("enabled")

        paymentMethodService(plugin).toggle(id, enabled)

        val sqlClient = databaseManager.getSqlClient()
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(ToggledMarketPaymentMethodLog(userId, username, id, enabled, plugin.pluginId), sqlClient)

        return Successful()
    }
}
