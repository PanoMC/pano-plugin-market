package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.*
import io.vertx.sqlclient.SqlClient

/** Order lines covered by a refund (01 section 6.4). */
abstract class MarketRefundItemDao : MarketDao<MarketRefundItem>(MarketRefundItem::class.java) {
    /** The new id, or `null` on a unique-key duplicate. */
    abstract suspend fun add(refundItem: MarketRefundItem, sqlClient: SqlClient): Long?

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketRefundItem?

    /** The lines of a refund, in insertion order. */
    abstract suspend fun getByRefundId(refundId: Long, sqlClient: SqlClient): List<MarketRefundItem>
}
