package com.panomc.plugins.market.db.dao

import com.panomc.platform.db.Dao
import com.panomc.plugins.market.db.model.MarketProduct
import io.vertx.sqlclient.SqlClient

abstract class MarketProductDao : Dao<MarketProduct>(MarketProduct::class.java) {
    abstract suspend fun add(product: MarketProduct, sqlClient: SqlClient): Long

    abstract suspend fun update(product: MarketProduct, sqlClient: SqlClient)

    abstract suspend fun deleteById(id: Long, sqlClient: SqlClient)

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketProduct?

    abstract suspend fun getBySlug(slug: String, sqlClient: SqlClient): MarketProduct?

    // Public storefront listing: only ACTIVE products, ordered priority DESC then name ASC.
    // (Duration-window and category-visibility filtering happen in the handler.)
    abstract suspend fun getVisibleProducts(sqlClient: SqlClient): List<MarketProduct>

    abstract suspend fun getByImageFileName(imageFileName: String, sqlClient: SqlClient): MarketProduct?

    abstract suspend fun getAllPaged(page: Long, search: String?, status: String?, sqlClient: SqlClient): List<MarketProduct>

    abstract suspend fun count(search: String?, status: String?, sqlClient: SqlClient): Long

    abstract suspend fun getAllSimple(sqlClient: SqlClient): List<MarketProduct>

    abstract suspend fun getByIds(ids: List<Long>, sqlClient: SqlClient): List<MarketProduct>

    abstract suspend fun clearCategory(categoryId: Long, sqlClient: SqlClient)
}
