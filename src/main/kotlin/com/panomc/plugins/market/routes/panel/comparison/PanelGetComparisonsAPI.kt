package com.panomc.plugins.market.routes.panel.comparison

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketComparisonDao
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.util.MarketStatus
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class PanelGetComparisonsAPI(
    private val plugin: MarketPlugin,
    private val marketComparisonDao: MarketComparisonDao
) : MarketPanelApi() {
    override val paths = listOf(Path("/comparisons", RouteType.GET))

    override val nodes = setOf(MarketNode.CATALOG)

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        Paging.params(ValidationHandlerBuilder.create(schemaRepository))
            .queryParameter(optionalParam("search", stringSchema()))
            .queryParameter(optionalParam("status", stringSchema()))
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val page = Paging.request(context)
        val search = parameters.queryParameter("search")?.string
        val status = parameters.queryParameter("status")?.string?.let { statusName ->
            MarketStatus.entries.find { it.name == statusName }
        }

        val sqlClient = databaseManager.getSqlClient()
        val comparisons = marketComparisonDao.getAllPaged(page.number.toLong(), status, search, sqlClient, page.size)
        val count = marketComparisonDao.count(status, search, sqlClient)

        // Resolve every referenced product id (skipping null slots) to a name in a single query.
        val allIds = comparisons
            .flatMap { JsonArray(it.productIds).filterNotNull().map { id -> (id as Number).toLong() } }
            .distinct()
        val productNames = marketComparisonDao.getProductNamesByIds(allIds, sqlClient)

        val comparisonList = comparisons.map { comparison ->
            val products = JsonArray(comparison.productIds)
                .filterNotNull()
                .mapNotNull { productNames[(it as Number).toLong()] }

            mapOf(
                "id" to comparison.id,
                "name" to comparison.name,
                "status" to comparison.status.name,
                "priority" to comparison.priority,
                "products" to products,
                "createdAt" to comparison.createdAt,
                "updatedAt" to comparison.updatedAt
            )
        }

        return Successful(Paging.response(comparisonList, count, page))
    }
}
