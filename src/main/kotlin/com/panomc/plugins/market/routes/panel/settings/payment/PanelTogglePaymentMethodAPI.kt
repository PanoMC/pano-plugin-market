package com.panomc.plugins.market.routes.panel.settings

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketPaymentMethodDao
import com.panomc.plugins.market.error.PaymentMethodNotConfigured
import com.panomc.plugins.market.log.UpdatedMarketPaymentMethodLog
import com.panomc.plugins.market.permission.ManageMarketPermission
import com.panomc.plugins.market.util.PaymentMethodCatalog
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Admin endpoint: enables/disables a payment method. Enabling re-checks that every required field
 * is filled server-side (the stored settings), rejecting with PaymentMethodNotConfigured otherwise.
 */
@Endpoint
class PanelTogglePaymentMethodAPI(
    private val plugin: MarketPlugin,
    private val marketPaymentMethodDao: MarketPaymentMethodDao
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/market/payment-methods/:id/toggle", RouteType.POST))

    private val authProvider by lazy {
        plugin.applicationContext.getBean(AuthProvider::class.java)
    }

    private val databaseManager by lazy {
        plugin.applicationContext.getBean(DatabaseManager::class.java)
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(Parameters.param("id", stringSchema()))
            .body(
                Bodies.json(
                    objectSchema()
                        .requiredProperty("enabled", booleanSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMarketPermission(), context)

        val methodId = getParameters(context).pathParameter("id").string
        val method = PaymentMethodCatalog.getById(methodId) ?: throw NotFound()

        val enabled = context.body().asJsonObject().getBoolean("enabled")

        val sqlClient = getSqlClient()
        val stored = marketPaymentMethodDao.getByMethodId(methodId, sqlClient)
        val settings = if (stored != null) JsonObject(stored.settings) else JsonObject()

        if (enabled) {
            method.requiredFieldKeys.forEach { key ->
                val value = settings.getValue(key)

                if (value == null || value.toString().isEmpty()) {
                    throw PaymentMethodNotConfigured()
                }
            }
        }

        marketPaymentMethodDao.upsertByMethodId(methodId, enabled, settings.encode(), sqlClient)

        val adminUserId = authProvider.getUserIdFromRoutingContext(context)
        val adminUsername = databaseManager.userDao.getUsernameFromUserId(adminUserId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            UpdatedMarketPaymentMethodLog(adminUserId, adminUsername, methodId, plugin.pluginId),
            sqlClient
        )

        return Successful()
    }
}
