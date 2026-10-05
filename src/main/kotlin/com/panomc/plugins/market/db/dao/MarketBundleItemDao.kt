package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.MarketBundleItem
import io.vertx.sqlclient.SqlClient

abstract class MarketBundleItemDao : MarketDao<MarketBundleItem>(MarketBundleItem::class.java) {
    /** The new id, or `null` when `(bundleProductId, productId, variantId)` already exists (`uq_bundle_child`). */
    abstract suspend fun add(item: MarketBundleItem, sqlClient: SqlClient): Long?

    /** Writes `quantity` and `position` (the three key columns identify the child and never change). */
    abstract suspend fun update(item: MarketBundleItem, sqlClient: SqlClient)

    abstract suspend fun deleteById(id: Long, sqlClient: SqlClient)

    abstract suspend fun deleteByBundleProductId(bundleProductId: Long, sqlClient: SqlClient): Int

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketBundleItem?

    /** Children of one bundle, ordered `position`, `id`. */
    abstract suspend fun getByBundleProductId(bundleProductId: Long, sqlClient: SqlClient): List<MarketBundleItem>

    /** The bundle rows a product is a child of (`idx_product`). */
    abstract suspend fun getByChildProductId(productId: Long, sqlClient: SqlClient): List<MarketBundleItem>
}
