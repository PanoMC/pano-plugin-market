package com.panomc.plugins.market.routes.panel.settings.payment

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.log.FailedMarketSecretRevealLog
import com.panomc.plugins.market.log.RevealedMarketPaymentSecretLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies
import com.panomc.platform.schema.dsl.Parameters
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * `POST /api/panel/market/payment-methods/:id/reveal` (`P:SET`, 11 section 8.3): password gated and throttled (5 wrong
 * passwords in 10 minutes lock the admin for 10 minutes, 429). Returns only the secret fields of the provider schema,
 * decrypted, with `Cache-Control: no-store`. The activity log records the provider id, never a secret.
 */
@Endpoint
class PanelRevealPaymentSecretAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val nodes: Set<MarketNode> = setOf(MarketNode.SETTINGS)

    override val paths = listOf(Path("/payment-methods/:id/reveal", RouteType.POST))

    private val authProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(Parameters.param("id", stringSchema()))
            .body(Bodies.json(objectSchema().requiredProperty("password", stringSchema())))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val id = getParameters(context).pathParameter("id").string
        val password = context.body().asJsonObject().getString("password")
        val sqlClient = databaseManager.getSqlClient()
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        val secrets = paymentMethodService(plugin).reveal(
            id, userId,
            passwordCorrect = { databaseManager.userDao.isLoginCorrect(username, password, sqlClient) },
            onFailed = { databaseManager.panelActivityLogDao.add(FailedMarketSecretRevealLog(userId, username, id, plugin.pluginId), sqlClient) }
        )

        databaseManager.panelActivityLogDao.add(RevealedMarketPaymentSecretLog(userId, username, id, plugin.pluginId), sqlClient)

        context.response().putHeader("Cache-Control", "no-store")

        return Successful(mapOf("settings" to secrets))
    }
}
