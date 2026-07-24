package com.panomc.plugins.market.db.dao

import com.panomc.platform.db.Dao
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.sqlclient.SqlClient

abstract class MarketOrderDao : Dao<MarketOrder>(MarketOrder::class.java) {
    abstract suspend fun add(order: MarketOrder, sqlClient: SqlClient): Long

    abstract suspend fun getAllPaged(page: Long, search: String?, status: OrderStatus?, sqlClient: SqlClient): List<MarketOrder>

    abstract suspend fun count(search: String?, status: OrderStatus?, sqlClient: SqlClient): Long

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketOrder?

    abstract suspend fun updateStatus(id: Long, status: OrderStatus, sqlClient: SqlClient)

    abstract suspend fun updateExchangeRate(id: Long, exchangeRate: Double, sqlClient: SqlClient)

    abstract suspend fun anonymizeByUserId(userId: Long, sqlClient: SqlClient)

    // Stats aggregates — COMPLETED orders only. Revenue is converted per-order into the stats
    // currency inside the query (frozen `exchangeRate` when present, else the currency-based
    // fallback: statsCurrency -> 1.0, salesCurrency -> the current view rate, otherwise 1.0) and
    // returned as decimal stats-currency amounts (the x100 minor units are divided out).

    /** COUNT and converted stats-currency revenue of COMPLETED orders with createdAt in [from, to). */
    abstract suspend fun countAndRevenueBetween(from: Long, to: Long, statsCurrency: String, salesCurrency: String, exchangeRate: Double, sqlClient: SqlClient): Pair<Long, Double>

    /** COMPLETED converted revenue grouped by calendar day, keyed 'yyyy-MM-dd', ascending. */
    abstract suspend fun revenueByDay(from: Long, to: Long, statsCurrency: String, salesCurrency: String, exchangeRate: Double, sqlClient: SqlClient): Map<String, Double>

    /** COMPLETED converted revenue grouped by ISO week, keyed 'YYYYWW', ascending. */
    abstract suspend fun revenueByWeek(from: Long, to: Long, statsCurrency: String, salesCurrency: String, exchangeRate: Double, sqlClient: SqlClient): Map<String, Double>

    /** COMPLETED converted revenue grouped by month, keyed 'yyyy-MM', ascending. */
    abstract suspend fun revenueByMonth(from: Long, to: Long, statsCurrency: String, salesCurrency: String, exchangeRate: Double, sqlClient: SqlClient): Map<String, Double>

    /** COMPLETED order count grouped by paymentLabel, descending. */
    abstract suspend fun paymentMethodDistribution(sqlClient: SqlClient): Map<String, Long>
}
