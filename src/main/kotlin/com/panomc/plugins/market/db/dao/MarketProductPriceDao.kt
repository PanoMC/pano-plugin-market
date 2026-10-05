package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.MarketProductPrice
import io.vertx.sqlclient.SqlClient

abstract class MarketProductPriceDao : MarketDao<MarketProductPrice>(MarketProductPrice::class.java) {
    /** The new id, or `null` when `(productId, variantId, currency)` already exists (`uq_product_variant_currency`). */
    abstract suspend fun add(price: MarketProductPrice, sqlClient: SqlClient): Long?

    /** Inserts or overwrites `price` and `compareAtPrice` of the row `(productId, variantId, currency)`; returns its id. */
    abstract suspend fun upsert(price: MarketProductPrice, sqlClient: SqlClient): Long

    /** Writes `price` and `compareAtPrice` of one row. */
    abstract suspend fun update(price: MarketProductPrice, sqlClient: SqlClient)

    abstract suspend fun deleteById(id: Long, sqlClient: SqlClient)

    abstract suspend fun deleteByProductId(productId: Long, sqlClient: SqlClient): Int

    abstract suspend fun deleteByVariantId(variantId: Long, sqlClient: SqlClient): Int

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketProductPrice?

    abstract suspend fun get(productId: Long, variantId: Long, currency: String, sqlClient: SqlClient): MarketProductPrice?

    /** Every price of a product (all variants), ordered `variantId`, `currency`. */
    abstract suspend fun getByProductId(productId: Long, sqlClient: SqlClient): List<MarketProductPrice>

    abstract suspend fun getByProductAndVariant(productId: Long, variantId: Long, sqlClient: SqlClient): List<MarketProductPrice>

    /** Every price row of every product (the storefront listing prices all visible products at once). */
    abstract suspend fun getAll(sqlClient: SqlClient): List<MarketProductPrice>
}
