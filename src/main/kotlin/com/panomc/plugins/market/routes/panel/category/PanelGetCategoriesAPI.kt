package com.panomc.plugins.market.routes.panel.category

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketCategoryDao
import com.panomc.plugins.market.db.model.MarketCategory
import com.panomc.plugins.market.permission.ManageMarketPermission
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

@Endpoint
class PanelGetCategoriesAPI(
    private val plugin: MarketPlugin,
    private val marketCategoryDao: MarketCategoryDao
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/market/categories", RouteType.GET))

    private val authProvider: AuthProvider by lazy {
        plugin.applicationContext.getBean(AuthProvider::class.java)
    }

    private val databaseManager: DatabaseManager by lazy {
        plugin.applicationContext.getBean(DatabaseManager::class.java)
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(optionalParam("search", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMarketPermission(), context)

        val parameters = getParameters(context)
        val search = parameters.queryParameter("search")?.string

        val sqlClient = databaseManager.getSqlClient()
        val categories = marketCategoryDao.getAll(search, sqlClient)
        val productCounts = marketCategoryDao.getProductCountsByCategory(sqlClient)

        val tree = buildTree(categories, productCounts)

        return Successful(
            mapOf(
                "categories" to tree,
                "categoryCount" to categories.size.toLong(),
                "totalPage" to 1
            )
        )
    }

    // Categories whose parent is not in the (possibly search-filtered) set are promoted to roots so
    // a filtered result never hides matching nodes behind dropped ancestors.
    private fun buildTree(categories: List<MarketCategory>, productCounts: Map<Long, Long>): List<JsonObject> {
        val presentIds = categories.map { it.id }.toSet()
        val childrenByParent = categories.groupBy { it.parentId }

        fun toNode(category: MarketCategory): JsonObject {
            val children = childrenByParent[category.id].orEmpty()
                .sortedBy { it.position }
                .map { toNode(it) }

            return JsonObject()
                .put("id", category.id)
                .put("name", category.name)
                .put("description", category.description)
                .put("icon", category.icon)
                .put("color", category.color)
                .put("status", category.status.name)
                .put("parentId", category.parentId)
                .put("position", category.position)
                .put("imageFileName", category.imageFileName)
                .put("productsCount", productCounts[category.id] ?: 0L)
                .put("createdAt", category.createdAt)
                .put("updatedAt", category.updatedAt)
                .put("children", children)
        }

        return categories
            .filter { it.parentId == null || it.parentId !in presentIds }
            .sortedBy { it.position }
            .map { toNode(it) }
    }
}
