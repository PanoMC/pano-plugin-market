package com.panomc.plugins.market.routes.panel.comparison

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketComparisonDao
import com.panomc.plugins.market.db.model.MarketComparison
import com.panomc.plugins.market.log.CreatedMarketComparisonLog
import com.panomc.plugins.market.permission.ManageMarketPermission
import com.panomc.plugins.market.util.MarketStatus
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class PanelCreateComparisonAPI(
    private val plugin: MarketPlugin,
    private val marketComparisonDao: MarketComparisonDao
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/market/comparisons", RouteType.POST))

    private val authProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }
    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    objectSchema()
                        .requiredProperty("name", stringSchema())
                        .optionalProperty("status", enumSchema(MarketStatus.ACTIVE.name, MarketStatus.INACTIVE.name))
                        .optionalProperty("priority", numberSchema())
                        .optionalProperty("selectedProducts", arraySchema())
                        .optionalProperty("features", arraySchema())
                        .optionalProperty("cellValues", objectSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMarketPermission(), context)

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val name = data.getString("name")
        if (name.isNullOrBlank()) {
            throw BadRequest()
        }

        val status = data.getString("status")?.let { MarketStatus.valueOf(it) } ?: MarketStatus.ACTIVE
        val priority = data.getInteger("priority") ?: 0
        val selectedProducts = data.getJsonArray("selectedProducts") ?: JsonArray()
        val features = data.getJsonArray("features") ?: JsonArray()
        val cellValues = data.getJsonObject("cellValues") ?: JsonObject()

        validateCellValues(cellValues)

        val comparison = MarketComparison(
            name = name,
            status = status,
            priority = priority,
            productIds = selectedProducts.encode(),
            features = features.encode(),
            cellValues = cellValues.encode()
        )

        val sqlClient = databaseManager.getSqlClient()
        val id = marketComparisonDao.add(comparison, sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            CreatedMarketComparisonLog(userId, username, plugin.pluginId, name),
            sqlClient
        )

        return Successful(mapOf("id" to id))
    }
}
