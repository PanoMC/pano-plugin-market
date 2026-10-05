package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.MarketOrderEvent
import io.vertx.sqlclient.SqlClient

/** The order timeline (01 section 5.3): rows are appended and never updated. */
abstract class MarketOrderEventDao : MarketDao<MarketOrderEvent>(MarketOrderEvent::class.java) {
    abstract suspend fun add(event: MarketOrderEvent, sqlClient: SqlClient): Long

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketOrderEvent?

    /** The timeline of one order, oldest first (`idx_order(orderId, id)`). */
    abstract suspend fun getByOrderId(orderId: Long, sqlClient: SqlClient): List<MarketOrderEvent>
}
