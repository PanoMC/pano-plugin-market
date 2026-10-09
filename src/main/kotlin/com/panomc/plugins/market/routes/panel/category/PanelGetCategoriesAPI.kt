package com.panomc.plugins.market.routes.panel.category

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.db.dao.MarketCategoryDao
import com.panomc.plugins.market.db.model.MarketCategory
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

@Endpoint
class PanelGetCategoriesAPI(
    private val plugin: MarketPlugin,
    private val marketCategoryDao: MarketCategoryDao
) : MarketPanelApi() {
    override val paths = listOf(Path("/categories", RouteType.GET))

    /** A picker: readable with any market node (04 section 5). */
    override val nodes: Set<MarketNode> = emptySet()

    private val databaseManager: DatabaseManager by lazy {
        plugin.applicationContext.getBean(DatabaseManager::class.java)
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(optionalParam("search", stringSchema()))
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val search = parameters.queryParameter("search")?.string

        val sqlClient = databaseManager.getSqlClient()
        val categories = marketCategoryDao.getAll(search, sqlClient)
        val productCounts = marketCategoryDao.getProductCountsByCategory(sqlClient)

        val tree = buildTree(categories, productCounts)

        // not paged: one page holds the whole tree (its roots; `totalItems` counts every node)
        return Successful(Paging.response(tree, categories.size.toLong(), PageRequest(1, maxOf(1, categories.size))))
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
                .put("tiered", category.tiered)
                .put("upgradeMode", category.upgradeMode.name)
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
