package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.MarketProductProviderMeta
import io.vertx.sqlclient.SqlClient

abstract class MarketProductProviderMetaDao : MarketDao<MarketProductProviderMeta>(MarketProductProviderMeta::class.java) {
    /** Inserts or overwrites `meta` of `(productId, variantId, providerId)`; returns the row id. */
    abstract suspend fun upsert(meta: MarketProductProviderMeta, sqlClient: SqlClient): Long

    abstract suspend fun deleteById(id: Long, sqlClient: SqlClient)

    /** Rows removed (0 or 1). */
    abstract suspend fun delete(productId: Long, variantId: Long, providerId: String, sqlClient: SqlClient): Int

    abstract suspend fun deleteByProductId(productId: Long, sqlClient: SqlClient): Int

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketProductProviderMeta?

    abstract suspend fun get(productId: Long, variantId: Long, providerId: String, sqlClient: SqlClient): MarketProductProviderMeta?

    abstract suspend fun getByProductId(productId: Long, sqlClient: SqlClient): List<MarketProductProviderMeta>

    /** Every row of one provider (`idx_provider`), ordered `productId`, `variantId`. */
    abstract suspend fun getByProviderId(providerId: String, sqlClient: SqlClient): List<MarketProductProviderMeta>
}
