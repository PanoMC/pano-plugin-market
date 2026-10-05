package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.MarketCreatorCode
import io.vertx.sqlclient.SqlClient

abstract class MarketCreatorCodeDao : MarketDao<MarketCreatorCode>(MarketCreatorCode::class.java) {
    abstract suspend fun add(creatorCode: MarketCreatorCode, sqlClient: SqlClient): Long
    abstract suspend fun update(creatorCode: MarketCreatorCode, sqlClient: SqlClient)
    abstract suspend fun deleteById(id: Long, sqlClient: SqlClient)
    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketCreatorCode?
    abstract suspend fun getByCode(code: String, sqlClient: SqlClient): MarketCreatorCode?
    abstract suspend fun getAll(page: Long, search: String?, status: String?, sqlClient: SqlClient): List<MarketCreatorCode>
    abstract suspend fun count(search: String?, status: String?, sqlClient: SqlClient): Long
}
