package com.panomc.plugins.market.routes.panel.goal

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.log.UpdatedMarketGoalLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.parseId
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*

/** `PUT /api/panel/market/goals/:id` (04 section 5, `P:CAT`). */
@Endpoint
class PanelUpdateGoalAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/goals/:id", RouteType.PUT))

    override val nodes = setOf(MarketNode.CATALOG)

    private val goals by lazy { goalService(plugin) }

    private val authProvider: AuthProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }

    private val databaseManager: DatabaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    objectSchema()
                        .allowAdditionalProperties(true)
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val goal = goals.update(parseId(context.pathParam("id")), getParameters(context).body().jsonObject)

        val sqlClient = databaseManager.getSqlClient()
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(UpdatedMarketGoalLog(userId, username, plugin.pluginId, goal.name), sqlClient)

        return Successful()
    }
}
