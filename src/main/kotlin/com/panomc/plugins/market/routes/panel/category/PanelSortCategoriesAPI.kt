package com.panomc.plugins.market.routes.panel.category

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketCategoryDao
import com.panomc.plugins.market.db.model.MarketCategory
import com.panomc.plugins.market.error.InvalidCategoryMove
import com.panomc.plugins.market.log.SortedMarketCategoriesLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.util.CategoryMovePosition
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class PanelSortCategoriesAPI(
    private val plugin: MarketPlugin,
    private val marketCategoryDao: MarketCategoryDao
) : MarketPanelApi() {
    override val paths = listOf(Path("/categories/sort", RouteType.POST))

    override val nodes = setOf(MarketNode.CATALOG)

    private val authProvider: AuthProvider by lazy {
        plugin.applicationContext.getBean(AuthProvider::class.java)
    }

    private val databaseManager: DatabaseManager by lazy {
        plugin.applicationContext.getBean(DatabaseManager::class.java)
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    objectSchema()
                        .requiredProperty("id", intSchema())
                        .requiredProperty(
                            "position",
                            enumSchema(*CategoryMovePosition.entries.map { it.name }.toTypedArray())
                        )
                        .optionalProperty("targetId", intSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val id = data.getLong("id")
        val position = CategoryMovePosition.valueOf(data.getString("position"))
        val targetId = data.getLong("targetId")

        val sqlClient = databaseManager.getSqlClient()
        val categories = marketCategoryDao.getAll(null, sqlClient)
        val byId = categories.associateBy { it.id }

        val moved = byId[id] ?: throw NotExists()

        // Resolve the destination parent from the move type.
        val newParentId: Long? = when (position) {
            CategoryMovePosition.ROOT -> null
            CategoryMovePosition.INSIDE -> {
                val target = targetId?.let { byId[it] } ?: throw InvalidCategoryMove()
                target.id
            }

            CategoryMovePosition.BEFORE, CategoryMovePosition.AFTER -> {
                val target = targetId?.let { byId[it] } ?: throw InvalidCategoryMove()
                if (target.id == id) throw InvalidCategoryMove()
                target.parentId
            }
        }

        // Reject moving a node into itself or any of its own descendants (would create a cycle).
        if (newParentId != null && newParentId in collectSubtreeIds(id, categories)) {
            throw InvalidCategoryMove()
        }

        val siblings = categories
            .filter { it.parentId == newParentId && it.id != id }
            .sortedBy { it.position }
            .toMutableList()

        val insertIndex = when (position) {
            CategoryMovePosition.ROOT, CategoryMovePosition.INSIDE -> siblings.size
            CategoryMovePosition.BEFORE -> siblings.indexOfFirst { it.id == targetId }.let { if (it < 0) siblings.size else it }
            CategoryMovePosition.AFTER -> siblings.indexOfFirst { it.id == targetId }.let { if (it < 0) siblings.size else it + 1 }
        }

        siblings.add(insertIndex, moved)

        siblings.forEachIndexed { index, category ->
            marketCategoryDao.updateParentAndPosition(category.id, newParentId, index, sqlClient)
        }

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            SortedMarketCategoriesLog(userId, username, plugin.pluginId, moved.name),
            sqlClient
        )

        return Successful()
    }

    private fun collectSubtreeIds(rootId: Long, categories: List<MarketCategory>): Set<Long> {
        val childrenByParent = categories.groupBy { it.parentId }
        val result = mutableSetOf(rootId)
        val queue = ArrayDeque<Long>()
        queue.add(rootId)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            childrenByParent[current].orEmpty().forEach { child ->
                if (result.add(child.id)) {
                    queue.add(child.id)
                }
            }
        }

        return result
    }
}
