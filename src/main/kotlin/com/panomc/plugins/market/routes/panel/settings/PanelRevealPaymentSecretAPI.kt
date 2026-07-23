package com.panomc.plugins.market.routes.panel.settings

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketPaymentMethodDao
import com.panomc.plugins.market.error.InvalidPassword
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
 * Admin endpoint: re-authenticates the admin with their password and returns a single payment
 * method's full unmasked settings, so the settings UI can reveal stored secrets. The normal
 * settings GET always masks them — this password-gated route is the only way to read them back.
 */
@Endpoint
class PanelRevealPaymentSecretAPI(
    private val plugin: MarketPlugin,
    private val marketPaymentMethodDao: MarketPaymentMethodDao
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/market/payment-methods/:id/reveal", RouteType.POST))

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
                        .requiredProperty("password", stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMarketPermission(), context)

        val methodId = getParameters(context).pathParameter("id").string
        PaymentMethodCatalog.getById(methodId) ?: throw NotFound()

        val password = context.body().asJsonObject().getString("password")

        val sqlClient = getSqlClient()
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        if (!databaseManager.userDao.isLoginCorrect(username, password, sqlClient)) {
            throw InvalidPassword()
        }

        val stored = marketPaymentMethodDao.getByMethodId(methodId, sqlClient)
        val settings = if (stored != null) JsonObject(stored.settings) else JsonObject()

        return Successful(mapOf("settings" to settings))
    }
}
