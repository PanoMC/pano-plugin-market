package com.panomc.plugins.market.db.dao

import com.panomc.platform.db.Dao
import com.panomc.plugins.market.db.model.MarketComparison
import com.panomc.plugins.market.util.MarketStatus
import io.vertx.sqlclient.SqlClient

abstract class MarketComparisonDao : Dao<MarketComparison>(MarketComparison::class.java) {
    abstract suspend fun add(comparison: MarketComparison, sqlClient: SqlClient): Long

    abstract suspend fun update(comparison: MarketComparison, sqlClient: SqlClient)

    abstract suspend fun deleteById(id: Long, sqlClient: SqlClient)

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketComparison?

    abstract suspend fun getAllPaged(page: Long, status: MarketStatus?, search: String?, sqlClient: SqlClient): List<MarketComparison>

    abstract suspend fun count(status: MarketStatus?, search: String?, sqlClient: SqlClient): Long

    // Resolves product ids to their display names via a raw query on the market_product table
    // (owned by another DAO — read directly, no cross-DAO dependency).
    abstract suspend fun getProductNamesByIds(ids: List<Long>, sqlClient: SqlClient): Map<Long, String>
}
