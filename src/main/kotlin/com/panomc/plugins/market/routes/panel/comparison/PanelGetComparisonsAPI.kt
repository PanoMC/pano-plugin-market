package com.panomc.plugins.market.routes.panel.comparison

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketComparisonDao
import com.panomc.plugins.market.permission.ManageMarketPermission
import com.panomc.plugins.market.util.MarketStatus
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*
import kotlin.math.ceil

@Endpoint
class PanelGetComparisonsAPI(
    private val plugin: MarketPlugin,
    private val marketComparisonDao: MarketComparisonDao
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/market/comparisons", RouteType.GET))

    private val authProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }
    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(optionalParam("page", numberSchema()))
            .queryParameter(optionalParam("search", stringSchema()))
            .queryParameter(optionalParam("status", enumSchema(MarketStatus.ACTIVE.name, MarketStatus.INACTIVE.name)))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMarketPermission(), context)

        val parameters = getParameters(context)
        val page = parameters.queryParameter("page")?.long ?: 1L
        val search = parameters.queryParameter("search")?.string
        val status = parameters.queryParameter("status")?.string?.let { MarketStatus.valueOf(it) }

        val sqlClient = databaseManager.getSqlClient()
        val comparisons = marketComparisonDao.getAllPaged(page, status, search, sqlClient)
        val count = marketComparisonDao.count(status, search, sqlClient)

        val totalPageNum = ceil(count.toDouble() / 10).toLong()

        if (totalPageNum in 1..<page) {
            throw PageNotFound()
        }

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

        return Successful(
            mapOf(
                "comparisons" to comparisonList,
                "comparisonCount" to count,
                "totalPage" to totalPageNum
            )
        )
    }
}
