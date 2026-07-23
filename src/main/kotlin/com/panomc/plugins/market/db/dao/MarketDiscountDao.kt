package com.panomc.plugins.market.db.dao

import com.panomc.platform.db.Dao
import com.panomc.plugins.market.db.model.MarketDiscount
import io.vertx.sqlclient.SqlClient

abstract class MarketDiscountDao : Dao<MarketDiscount>(MarketDiscount::class.java) {
    abstract suspend fun add(discount: MarketDiscount, sqlClient: SqlClient): Long
    abstract suspend fun update(discount: MarketDiscount, sqlClient: SqlClient)
    abstract suspend fun deleteById(id: Long, sqlClient: SqlClient)
    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketDiscount?
    abstract suspend fun getAll(page: Long, search: String?, status: String?, sqlClient: SqlClient): List<MarketDiscount>
    abstract suspend fun count(search: String?, status: String?, sqlClient: SqlClient): Long
}
