package com.panomc.plugins.market.routes.panel.settings.legal

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.log.UpdatedMarketLegalTextLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.api.checkout.legalTextService
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.service.LegalTextService
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema

/**
 * `GET /api/panel/market/settings/legal` lists every version, `POST` (`locale*`, `title*`, `content*`) creates the next
 * version and makes it the active one of its locale (04 section 8, `P:SET`). One class answers both methods.
 */
@Endpoint
class PanelLegalTextAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val nodes: Set<MarketNode> = setOf(MarketNode.SETTINGS)

    override val paths = listOf(
        Path("/api/panel/market/settings/legal", RouteType.GET),
        Path("/api/panel/market/settings/legal", RouteType.POST)
    )

    private val service: LegalTextService by lazy { legalTextService(plugin) }

    private val authProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(Bodies.json(objectSchema().allowAdditionalProperties(true)))
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val sqlClient = databaseManager.getSqlClient()

        context.response().putHeader("Cache-Control", "no-store")

        if (context.request().method().name() == "GET") {
            val texts = service.list(sqlClient).map {
                mapOf(
                    "id" to it.id, "version" to it.version, "locale" to it.locale, "title" to it.title,
                    "content" to it.content, "active" to it.active, "createdAt" to it.createdAt
                )
            }

            return Successful(mapOf("texts" to texts))
        }

        val body = context.body().asJsonObject() ?: JsonObject()
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!
        val published = service.publish(
            body.getValue("locale") as? String,
            body.getValue("title") as? String,
            body.getValue("content") as? String,
            userId
        )

        databaseManager.panelActivityLogDao.add(
            UpdatedMarketLegalTextLog(userId, username, plugin.pluginId, published.locale, published.version),
            sqlClient
        )

        return Successful(mapOf("id" to published.id, "version" to published.version))
    }
}
