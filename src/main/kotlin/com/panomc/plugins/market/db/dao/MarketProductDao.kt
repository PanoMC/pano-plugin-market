package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.MarketProduct
import com.panomc.plugins.market.db.model.ProductKind
import io.vertx.sqlclient.SqlClient

abstract class MarketProductDao : MarketDao<MarketProduct>(MarketProduct::class.java) {
    abstract suspend fun add(product: MarketProduct, sqlClient: SqlClient): Long

    /**
     * Writes every column except the counters `stock` and `soldCount`, the soft-delete marker `deletedAt`, `createdAt`
     * and `id` (00 section 8.3: a stale read can never overwrite a counter).
     */
    abstract suspend fun update(product: MarketProduct, sqlClient: SqlClient)

    /** Sets `stock` explicitly (`null` = unlimited): the manual adjustment path; `update` no longer writes it. */
    abstract suspend fun setStock(id: Long, stock: Int?, sqlClient: SqlClient)

    /**
     * Atomic `stock = stock + delta` guarded by `stock IS NOT NULL AND stock + delta BETWEEN 0 AND [MAX_STOCK]`: `true`
     * when the row changed, `false` when the stock is unlimited (`NULL`), the row is gone, or the result would leave the
     * range (nothing changes).
     */
    abstract suspend fun adjustStock(id: Long, delta: Int, sqlClient: SqlClient): Boolean

    /**
     * Soft delete (01 section 13): `deletedAt` set once, `status = ARCHIVED`, `slug` rewritten to [archivedSlug] so the
     * old slug can be reused. `false` when the row does not exist or was deleted before.
     */
    abstract suspend fun markDeleted(id: Long, archivedSlug: String, deletedAt: Long, sqlClient: SqlClient): Boolean

    /** Whether an order item, entitlement, subscription, cart line or bundle row points at the product (01 section 13). */
    abstract suspend fun isReferenced(id: Long, sqlClient: SqlClient): Boolean

    /** Same for one variant of a product. */
    abstract suspend fun isVariantReferenced(variantId: Long, sqlClient: SqlClient): Boolean

    /** Removes the product's lines (all variants) from every cart; the number of lines removed. */
    abstract suspend fun removeFromCarts(productId: Long, sqlClient: SqlClient): Int

    /** Removes the lines of one variant from every cart. */
    abstract suspend fun removeVariantFromCarts(variantId: Long, sqlClient: SqlClient): Int

    /** `tiered` of a category, `null` when the category does not exist. */
    abstract suspend fun isCategoryTiered(categoryId: Long, sqlClient: SqlClient): Boolean?

    /**
     * An `ACTIVE`, not deleted shipping method with at least one rate row in an `ACTIVE` zone exists (10 section 2.1,
     * the `NO_SHIPPING_METHOD` warning). The provider's own state is not looked at here.
     */
    abstract suspend fun hasSellableShippingMethod(sqlClient: SqlClient): Boolean

    abstract suspend fun deleteById(id: Long, sqlClient: SqlClient)

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketProduct?

    abstract suspend fun getBySlug(slug: String, sqlClient: SqlClient): MarketProduct?

    // Public storefront listing: only ACTIVE products, ordered priority DESC then name ASC.
    // (Duration-window and category-visibility filtering happen in the handler.)
    abstract suspend fun getVisibleProducts(sqlClient: SqlClient): List<MarketProduct>

    abstract suspend fun getByImageFileName(imageFileName: String, sqlClient: SqlClient): MarketProduct?

    /** Panel list, soft-deleted rows excluded; fixed page size 10. */
    open suspend fun getAllPaged(page: Long, search: String?, status: String?, sqlClient: SqlClient): List<MarketProduct> =
        getAllPaged(page, 10, search, status, null, null, sqlClient)

    open suspend fun count(search: String?, status: String?, sqlClient: SqlClient): Long =
        count(search, status, null, null, sqlClient)

    /** Panel list (04 section 5): [pageSize] rows, optional `kind` and `categoryId` filters, soft-deleted rows excluded. */
    abstract suspend fun getAllPaged(
        page: Long,
        pageSize: Int,
        search: String?,
        status: String?,
        kind: ProductKind?,
        categoryId: Long?,
        sqlClient: SqlClient
    ): List<MarketProduct>

    abstract suspend fun count(
        search: String?,
        status: String?,
        kind: ProductKind?,
        categoryId: Long?,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun getAllSimple(sqlClient: SqlClient): List<MarketProduct>

    abstract suspend fun getByIds(ids: List<Long>, sqlClient: SqlClient): List<MarketProduct>

    abstract suspend fun clearCategory(categoryId: Long, sqlClient: SqlClient)

    companion object {
        /** Upper bound of a stock counter (also the bound of `adjustStock`). */
        const val MAX_STOCK = 1_000_000_000
    }
}
