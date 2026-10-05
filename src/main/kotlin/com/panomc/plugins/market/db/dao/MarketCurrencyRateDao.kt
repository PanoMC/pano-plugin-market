package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.MarketCurrencyRate
import io.vertx.sqlclient.SqlClient

abstract class MarketCurrencyRateDao : MarketDao<MarketCurrencyRate>(MarketCurrencyRate::class.java) {
    /** Inserts or overwrites `rate`, `mode` and `fetchedAt` of the row of `currency` (`uq_currency`); returns its id. */
    abstract suspend fun upsert(rate: MarketCurrencyRate, sqlClient: SqlClient): Long

    /** Rows removed (0 or 1). */
    abstract suspend fun deleteByCurrency(currency: String, sqlClient: SqlClient): Int

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketCurrencyRate?

    abstract suspend fun getByCurrency(currency: String, sqlClient: SqlClient): MarketCurrencyRate?

    /** Ordered by `currency`. */
    abstract suspend fun getAll(sqlClient: SqlClient): List<MarketCurrencyRate>
}
