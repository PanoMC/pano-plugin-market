package com.panomc.plugins.market.db.dao

import com.panomc.platform.db.Dao
import com.panomc.plugins.market.db.model.MarketGift
import io.vertx.sqlclient.SqlClient

abstract class MarketGiftDao : Dao<MarketGift>(MarketGift::class.java) {
    abstract suspend fun add(gift: MarketGift, sqlClient: SqlClient): Long
    abstract suspend fun update(gift: MarketGift, sqlClient: SqlClient)
    abstract suspend fun deleteById(id: Long, sqlClient: SqlClient)
    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketGift?
    abstract suspend fun getByCode(code: String, sqlClient: SqlClient): MarketGift?
    abstract suspend fun getAll(page: Long, search: String?, status: String?, sqlClient: SqlClient): List<MarketGift>
    abstract suspend fun count(search: String?, status: String?, sqlClient: SqlClient): Long
}
