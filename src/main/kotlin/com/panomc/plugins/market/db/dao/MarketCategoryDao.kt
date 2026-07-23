package com.panomc.plugins.market.db.dao

import com.panomc.platform.db.Dao
import com.panomc.plugins.market.db.model.MarketCategory
import io.vertx.sqlclient.SqlClient

abstract class MarketCategoryDao : Dao<MarketCategory>(MarketCategory::class.java) {
    abstract suspend fun add(category: MarketCategory, sqlClient: SqlClient): Long

    abstract suspend fun update(category: MarketCategory, sqlClient: SqlClient)

    abstract suspend fun deleteById(id: Long, sqlClient: SqlClient)

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketCategory?

    abstract suspend fun getByImageFileName(imageFileName: String, sqlClient: SqlClient): MarketCategory?

    abstract suspend fun getAll(search: String?, sqlClient: SqlClient): List<MarketCategory>

    abstract suspend fun getMaxPosition(parentId: Long?, sqlClient: SqlClient): Int

    abstract suspend fun updateParentAndPosition(id: Long, parentId: Long?, position: Int, sqlClient: SqlClient)

    abstract suspend fun reparentChildren(fromParentId: Long, toParentId: Long?, sqlClient: SqlClient)

    // The product table belongs to another vertical; these two queries reference it by name directly.
    abstract suspend fun clearProductsCategory(categoryId: Long, sqlClient: SqlClient)

    abstract suspend fun getProductCountsByCategory(sqlClient: SqlClient): Map<Long, Long>
}
