package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.MarketCart
import io.vertx.sqlclient.SqlClient

/** The cart header, one per user (01 section 4.1). */
abstract class MarketCartDao : MarketDao<MarketCart>(MarketCart::class.java) {
    /** The new id, or `null` when the user already has a cart (`uq_user`). */
    abstract suspend fun add(cart: MarketCart, sqlClient: SqlClient): Long?

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketCart?

    abstract suspend fun getByUserId(userId: Long, sqlClient: SqlClient): MarketCart?

    abstract suspend fun deleteById(id: Long, sqlClient: SqlClient): Boolean
}
