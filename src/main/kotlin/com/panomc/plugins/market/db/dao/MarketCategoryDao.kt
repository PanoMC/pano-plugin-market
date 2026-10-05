package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.MarketCategory
import io.vertx.sqlclient.SqlClient

abstract class MarketCategoryDao : MarketDao<MarketCategory>(MarketCategory::class.java) {
    abstract suspend fun add(category: MarketCategory, sqlClient: SqlClient): Long

    abstract suspend fun update(category: MarketCategory, sqlClient: SqlClient)

    abstract suspend fun deleteById(id: Long, sqlClient: SqlClient)

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketCategory?

    /** Same row with a row lock (`FOR UPDATE`): partial updates and deletes of one category serialise. */
    abstract suspend fun getByIdForUpdate(id: Long, sqlClient: SqlClient): MarketCategory?

    /** `true` while any ACTIVE entitlement belongs to the tier ladder of the category (the entitlement table is read by name). */
    abstract suspend fun hasActiveTierEntitlements(categoryId: Long, sqlClient: SqlClient): Boolean

    abstract suspend fun getByImageFileName(imageFileName: String, sqlClient: SqlClient): MarketCategory?

    abstract suspend fun getAll(search: String?, sqlClient: SqlClient): List<MarketCategory>

    abstract suspend fun getNamesByIds(ids: List<Long>, sqlClient: SqlClient): Map<Long, String>

    abstract suspend fun getMaxPosition(parentId: Long?, sqlClient: SqlClient): Int

    abstract suspend fun updateParentAndPosition(id: Long, parentId: Long?, position: Int, sqlClient: SqlClient)

    abstract suspend fun reparentChildren(fromParentId: Long, toParentId: Long?, sqlClient: SqlClient)

    // The product table belongs to another vertical; these two queries reference it by name directly.
    abstract suspend fun clearProductsCategory(categoryId: Long, sqlClient: SqlClient)

    abstract suspend fun getProductCountsByCategory(sqlClient: SqlClient): Map<Long, Long>
}
