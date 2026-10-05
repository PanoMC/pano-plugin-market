package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.MarketProductVariant
import io.vertx.sqlclient.SqlClient

abstract class MarketProductVariantDao : MarketDao<MarketProductVariant>(MarketProductVariant::class.java) {
    abstract suspend fun add(variant: MarketProductVariant, sqlClient: SqlClient): Long

    /** Writes everything except `stock`, `deletedAt`, `productId` and `createdAt` (the counters have their own statements). */
    abstract suspend fun update(variant: MarketProductVariant, sqlClient: SqlClient)

    /** Manual stock adjustment (`null` = unlimited). */
    abstract suspend fun setStock(id: Long, stock: Int?, sqlClient: SqlClient)

    /**
     * Guarded decrement: `true` when [quantity] was taken, `false` when the stock is lower (nothing changes). An
     * unlimited variant (`stock IS NULL`) is never reserved here and also answers `false`: the caller checks for
     * `null` first.
     */
    abstract suspend fun reserveStock(id: Long, quantity: Int, sqlClient: SqlClient): Boolean

    /** Gives [quantity] back; a no-op for an unlimited variant. */
    abstract suspend fun releaseStock(id: Long, quantity: Int, sqlClient: SqlClient)

    /** Soft delete: sets `deletedAt` once; `false` when the row does not exist or was deleted before. */
    abstract suspend fun markDeleted(id: Long, deletedAt: Long, sqlClient: SqlClient): Boolean

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketProductVariant?

    abstract suspend fun getByIds(ids: List<Long>, sqlClient: SqlClient): List<MarketProductVariant>

    /** Of one product, ordered `position`, `id`; soft-deleted rows only with [includeDeleted]. */
    abstract suspend fun getByProductId(productId: Long, includeDeleted: Boolean, sqlClient: SqlClient): List<MarketProductVariant>

    abstract suspend fun countByProductId(productId: Long, includeDeleted: Boolean, sqlClient: SqlClient): Long

    /** Hard delete of every row of a product (cleanup after a hard product delete). Returns the rows removed. */
    abstract suspend fun deleteByProductId(productId: Long, sqlClient: SqlClient): Int
}
