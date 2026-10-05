package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.MarketAddress
import io.vertx.sqlclient.SqlClient

/** The address book of a user (01 section 5.6). */
abstract class MarketAddressDao : MarketDao<MarketAddress>(MarketAddress::class.java) {
    abstract suspend fun add(address: MarketAddress, sqlClient: SqlClient): Long

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketAddress?

    /** The addresses of one user, default first (`idx_user`). */
    abstract suspend fun getByUserId(userId: Long, sqlClient: SqlClient): List<MarketAddress>

    abstract suspend fun deleteById(id: Long, sqlClient: SqlClient): Boolean
}
