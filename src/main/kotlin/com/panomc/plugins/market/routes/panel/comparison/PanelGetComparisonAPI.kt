package com.panomc.plugins.market.routes.panel.comparison

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketComparisonDao
import com.panomc.plugins.market.permission.ManageMarketPermission
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

@Endpoint
class PanelGetComparisonAPI(
    private val plugin: MarketPlugin,
    private val marketComparisonDao: MarketComparisonDao
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/market/comparisons/:id", RouteType.GET))

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
        val comparison = marketComparisonDao.getById(id, sqlClient) ?: throw NotFound()

        // JSON columns decoded back into their raw structures so the editor round-trips identically.
        return Successful(
            mapOf(
                "id" to comparison.id,
                "name" to comparison.name,
                "status" to comparison.status.name,
                "priority" to comparison.priority,
                "selectedProducts" to JsonArray(comparison.productIds),
                "features" to JsonArray(comparison.features),
                "cellValues" to JsonObject(comparison.cellValues)
            )
        )
    }
}
