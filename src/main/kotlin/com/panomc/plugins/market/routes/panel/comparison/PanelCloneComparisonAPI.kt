package com.panomc.plugins.market.routes.panel.comparison

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketComparisonDao
import com.panomc.plugins.market.db.model.MarketComparison
import com.panomc.plugins.market.log.CreatedMarketComparisonLog
import com.panomc.plugins.market.permission.ManageMarketPermission
import com.panomc.plugins.market.util.MarketStatus
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

@Endpoint
class PanelCloneComparisonAPI(
    private val plugin: MarketPlugin,
    private val marketComparisonDao: MarketComparisonDao
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/market/comparisons/:id/clone", RouteType.POST))

    private val authProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }
    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMarketPermission(), context)

        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        val sqlClient = databaseManager.getSqlClient()
        val original = marketComparisonDao.getById(id, sqlClient) ?: throw NotFound()

        val cloneName = (original.name + com.panomc.plugins.market.service.CloneSuffix.of(context.request().getHeader("Accept-Language"))).take(255)
        val clone = MarketComparison(
            name = cloneName,
            status = MarketStatus.INACTIVE,
            priority = original.priority,
            productIds = original.productIds,
            features = original.features,
            cellValues = original.cellValues
        )

        val newId = marketComparisonDao.add(clone, sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            CreatedMarketComparisonLog(userId, username, plugin.pluginId, cloneName),
            sqlClient
        )

        return Successful(mapOf("id" to newId))
    }
}
