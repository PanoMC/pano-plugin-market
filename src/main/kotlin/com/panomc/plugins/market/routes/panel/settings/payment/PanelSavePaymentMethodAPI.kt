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
import com.panomc.plugins.market.log.SortedMarketPaymentMethodsLog
import com.panomc.plugins.market.log.UpdatedMarketPaymentMethodLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.service.PaymentMethodRules
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema

/**
 * `POST /api/panel/market/payment-methods/:id` saves a provider (`settings{}` + optional `config{}`, 04 section 8) and
 * `POST /api/panel/market/payment-methods/sort` orders the providers (`ids*[]`). One class answers both so that the
 * fixed `sort` path is always matched before `:id` (two routes at the same order would match in an unspecified order);
 * `sort` is therefore not usable as a provider id here.
 */
@Endpoint
class PanelSavePaymentMethodAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val nodes: Set<MarketNode> = setOf(MarketNode.SETTINGS)

    override val paths = listOf(
        Path("/api/panel/market/payment-methods/sort", RouteType.POST),
        Path("/api/panel/market/payment-methods/:id", RouteType.POST)
    )

    private val authProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(Bodies.json(objectSchema().allowAdditionalProperties(true)))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val service = paymentMethodService(plugin)
        val body = context.body().asJsonObject() ?: JsonObject()
        val sqlClient = databaseManager.getSqlClient()
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        if (context.request().path().trimEnd('/').endsWith("/payment-methods/sort")) {
            val ids = try {
                PaymentMethodRules.parseProviderIds(body.getValue("ids") as? io.vertx.core.json.JsonArray)
            } catch (e: IllegalArgumentException) {
                throw RequestValueException("ids", e.message ?: "INVALID")
            }

            service.sort(ids)
            databaseManager.panelActivityLogDao.add(SortedMarketPaymentMethodsLog(userId, username, plugin.pluginId), sqlClient)

            return Successful()
        }

        val id = context.pathParam("id")
        val settings = optionalObject(body, "settings")
        val config = optionalObject(body, "config")
        val saved = service.save(id, settings, config)

        databaseManager.panelActivityLogDao.add(UpdatedMarketPaymentMethodLog(userId, username, id, plugin.pluginId), sqlClient)

        return Successful(saved.message?.let { mapOf("message" to it.toJson()) } ?: emptyMap<String, Any>())
    }

    private fun optionalObject(body: JsonObject, key: String): JsonObject? {
        if (!body.containsKey(key) || body.getValue(key) == null) return null

        return body.getValue(key) as? JsonObject ?: throw RequestValueException(key, "INVALID")
    }
}
