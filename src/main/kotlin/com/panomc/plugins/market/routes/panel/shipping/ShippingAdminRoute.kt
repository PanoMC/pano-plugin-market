package com.panomc.plugins.market.routes.panel.shipping

import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PluginActivityLog
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.service.ShippingAdminService
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema

/** Base of the shipping configuration routes (`P:SET`, 04 section 8): the service, the actor and the activity log. */
abstract class ShippingAdminRoute(protected val plugin: MarketPlugin) : MarketPanelApi() {
    override val nodes: Set<MarketNode> = setOf(MarketNode.SETTINGS)

    private val authProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    protected val service: ShippingAdminService get() = shippingAdminService(plugin)

    /** A JSON object body is required (the single class of a POST / PUT route). */
    protected fun bodyValidation(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(Bodies.json(objectSchema().allowAdditionalProperties(true)))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    protected fun noBodyValidation(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).build()

    protected suspend fun userId(context: RoutingContext): Long = authProvider.getUserIdFromRoutingContext(context)

    protected suspend fun log(context: RoutingContext, build: (userId: Long, username: String) -> PluginActivityLog) {
        val sqlClient = databaseManager.getSqlClient()
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(build(userId, username), sqlClient)
    }

    protected suspend fun isLoginCorrect(context: RoutingContext, password: String): Boolean {
        val sqlClient = databaseManager.getSqlClient()
        val username = databaseManager.userDao.getUsernameFromUserId(authProvider.getUserIdFromRoutingContext(context), sqlClient)!!

        return databaseManager.userDao.isLoginCorrect(username, password, sqlClient)
    }

    protected fun idsOf(body: JsonObject): List<Long> {
        val raw = body.getValue("ids") as? JsonArray ?: throw RequestValueException("ids", "REQUIRED")

        return raw.list.map { (it as? Number)?.takeIf { n -> n.toDouble() == n.toLong().toDouble() }?.toLong() ?: throw RequestValueException("ids", "INVALID") }
    }

    protected fun longParam(context: RoutingContext, name: String): Long =
        context.pathParam(name)?.toLongOrNull() ?: throw RequestValueException(name, "INVALID")

    protected fun optionalObject(body: JsonObject, key: String): JsonObject? {
        if (!body.containsKey(key) || body.getValue(key) == null) return null

        return body.getValue(key) as? JsonObject ?: throw RequestValueException(key, "INVALID")
    }

    protected fun isPath(context: RoutingContext, suffix: String): Boolean = context.request().path().trimEnd('/').endsWith(suffix)
}
