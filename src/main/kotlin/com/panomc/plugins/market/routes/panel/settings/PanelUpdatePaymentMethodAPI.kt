package com.panomc.plugins.market.routes.panel.settings

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketPaymentMethodDao
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
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Admin endpoint: saves a payment method's credentials. Only known catalog field keys are stored;
 * a secret field left blank or holding the mask sentinel keeps its stored value. Secrets are never
 * echoed back and never written to the activity log.
 */
@Endpoint
class PanelUpdatePaymentMethodAPI(
    private val plugin: MarketPlugin,
    private val marketPaymentMethodDao: MarketPaymentMethodDao
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/market/payment-methods/:id", RouteType.POST))

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
                        .requiredProperty("settings", objectSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    companion object {
        private const val SECRET_MASK = "********"
    }

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMarketPermission(), context)

        val methodId = getParameters(context).pathParameter("id").string
        val method = PaymentMethodCatalog.getById(methodId) ?: throw NotFound()

        val incoming = context.body().asJsonObject().getJsonObject("settings")

        val sqlClient = getSqlClient()
        val stored = marketPaymentMethodDao.getByMethodId(methodId, sqlClient)
        val merged = if (stored != null) JsonObject(stored.settings) else JsonObject()

        // Overlay only known catalog fields; a blank/masked secret means "keep the stored value".
        method.fields.forEach { field ->
            if (!incoming.containsKey(field.key)) return@forEach

            val value = incoming.getValue(field.key)

            if (field.secret) {
                val asString = value as? String
                if (asString == SECRET_MASK || asString.isNullOrEmpty()) return@forEach
            }

            merged.put(field.key, value)
        }

        marketPaymentMethodDao.upsertByMethodId(methodId, stored?.enabled ?: false, merged.encode(), sqlClient)

        val adminUserId = authProvider.getUserIdFromRoutingContext(context)
        val adminUsername = databaseManager.userDao.getUsernameFromUserId(adminUserId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            UpdatedMarketPaymentMethodLog(adminUserId, adminUsername, methodId, plugin.pluginId),
            sqlClient
        )

        return Successful()
    }
}
