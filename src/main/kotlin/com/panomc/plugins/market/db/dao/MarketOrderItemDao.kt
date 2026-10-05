package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.MarketOrderItem
import io.vertx.sqlclient.SqlClient

abstract class MarketOrderItemDao : MarketDao<MarketOrderItem>(MarketOrderItem::class.java) {
    abstract suspend fun add(orderItem: MarketOrderItem, sqlClient: SqlClient): Long

    abstract suspend fun getByOrderIds(orderIds: List<Long>, sqlClient: SqlClient): List<MarketOrderItem>

    /**
     * Top products (by summed quantity x unitPrice) across COMPLETED orders in [from, to), each item's
     * revenue converted into the stats currency by its order's frozen rate or the currency-based
     * fallback (statsCurrency -> 1.0, salesCurrency -> the current view rate, otherwise 1.0). Returns
     * decimal stats-currency amounts.
     */
    abstract suspend fun topProductsBetween(from: Long, to: Long, limit: Int, statsCurrency: String, salesCurrency: String, exchangeRate: Double, sqlClient: SqlClient): List<Pair<String, Double>>

    /**
     * Public storefront bestsellers: product ids ranked by total sold quantity across all COMPLETED
     * orders (all-time), skipping deleted products (null productId). Quantity-ranked, so no currency
     * math is needed. Caller hydrates the ids and drops any non-visible products.
     */
    abstract suspend fun topProductIds(limit: Int, sqlClient: SqlClient): List<Long>
}
