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

    /** Writes every editable column of [address] (not `userId` and `createdAt`); `true` when the row exists. */
    abstract suspend fun update(address: MarketAddress, sqlClient: SqlClient): Boolean

    abstract suspend fun countByUserId(userId: Long, sqlClient: SqlClient): Int

    /** Clears `isDefault` on every address of [userId] except [keepId] (`0` = none is kept); returns how many rows changed. */
    abstract suspend fun clearDefault(userId: Long, keepId: Long, now: Long, sqlClient: SqlClient): Int

    /** Makes the address [id] of [userId] the default one (no other row is touched); `true` when the row exists. */
    abstract suspend fun markDefault(userId: Long, id: Long, now: Long, sqlClient: SqlClient): Boolean
}
