package com.panomc.plugins.market.db.dao

import com.panomc.platform.db.Dao
import com.panomc.plugins.market.db.model.MarketOrderItem
import io.vertx.sqlclient.SqlClient

abstract class MarketOrderItemDao : Dao<MarketOrderItem>(MarketOrderItem::class.java) {
    abstract suspend fun add(orderItem: MarketOrderItem, sqlClient: SqlClient): Long

    abstract suspend fun getByOrderIds(orderIds: List<Long>, sqlClient: SqlClient): List<MarketOrderItem>

    /** Top products (by summed quantity x unitPrice) across COMPLETED orders in [from, to). */
    abstract suspend fun topProductsBetween(from: Long, to: Long, limit: Int, sqlClient: SqlClient): List<Pair<String, Long>>
}
