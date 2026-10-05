package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.MarketProductField
import io.vertx.sqlclient.SqlClient

abstract class MarketProductFieldDao : MarketDao<MarketProductField>(MarketProductField::class.java) {
    /** The new id, or `null` when the product already has that `fieldKey` (`uq_product_key`). */
    abstract suspend fun add(field: MarketProductField, sqlClient: SqlClient): Long?

    /** Writes every column except `productId`; `false` when the new `fieldKey` collides with another field of the product. */
    abstract suspend fun update(field: MarketProductField, sqlClient: SqlClient): Boolean

    abstract suspend fun deleteById(id: Long, sqlClient: SqlClient)

    abstract suspend fun deleteByProductId(productId: Long, sqlClient: SqlClient): Int

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketProductField?

    abstract suspend fun getByProductIdAndKey(productId: Long, fieldKey: String, sqlClient: SqlClient): MarketProductField?

    /** Ordered `position`, `id`. */
    abstract suspend fun getByProductId(productId: Long, sqlClient: SqlClient): List<MarketProductField>

    abstract suspend fun getByProductIds(productIds: List<Long>, sqlClient: SqlClient): List<MarketProductField>
}
