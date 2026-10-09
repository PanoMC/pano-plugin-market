package com.panomc.plugins.market.routes.panel.server

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.error.InvalidSettings
import com.panomc.plugins.market.log.UpdatedMarketServerSettingsLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.parseId
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies
import com.panomc.platform.schema.dsl.Parameters.param
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * `PUT /api/panel/market/servers/:id/settings` (`P:SET`, 04 section 8): body `{settings}`, a subset of the `mc*` keys of 00 section 12 or `null` to clear the
 * override of that server (19 section 9: the override beats the panel default and loses to the server's local config). 400 `INVALID_SETTINGS {fieldErrors}`
 * for an unknown key or a bad value (nothing is stored), 404 for a server that does not exist or was never accepted. Activity log `UPDATED_MARKET_SERVER_SETTINGS`
 * (the values are not logged). The component pulls the new configuration within one sync: the `configHash` of the sync answer changes with the override.
 */
@Endpoint
class PanelPutServerSettingsAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/servers/:id/settings", RouteType.PUT))

    override val nodes: Set<MarketNode> = setOf(MarketNode.SETTINGS)

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", stringSchema()))
            .body(Bodies.json(objectSchema().allowAdditionalProperties(true)))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val serverId = parseId(context.pathParam("id"), "id")
        val body = context.body().asJsonObject() ?: JsonObject()
        val raw = body.getValue("settings")

        if (raw != null && raw !is JsonObject) throw InvalidSettings(mapOf("settings" to "INVALID"))

        val databaseManager = plugin.applicationContext.getBean(DatabaseManager::class.java)
        val sqlClient = databaseManager.getSqlClient()

        // only a server the owner accepted can hold an override (a server that was never accepted cannot take a delivery either)
        if (databaseManager.serverDao.getAllByPermissionGranted(sqlClient).none { it.id == serverId }) throw NotFound()

        val errors = mcGameService(plugin).updateServerSettings(serverId, raw as JsonObject?)

        if (errors.isNotEmpty()) throw InvalidSettings(errors)

        val adminUserId = plugin.applicationContext.getBean(AuthProvider::class.java).getUserIdFromRoutingContext(context)
        val adminUsername = databaseManager.userDao.getUsernameFromUserId(adminUserId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(UpdatedMarketServerSettingsLog(adminUserId, adminUsername, plugin.pluginId, serverId), sqlClient)

        return Successful()
    }
}
