package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.MarketCoupon
import io.vertx.sqlclient.SqlClient

abstract class MarketCouponDao : MarketDao<MarketCoupon>(MarketCoupon::class.java) {
    abstract suspend fun add(coupon: MarketCoupon, sqlClient: SqlClient): Long
    abstract suspend fun update(coupon: MarketCoupon, sqlClient: SqlClient)
    abstract suspend fun deleteById(id: Long, sqlClient: SqlClient)
    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketCoupon?
    abstract suspend fun getByCode(code: String, sqlClient: SqlClient): MarketCoupon?
    abstract suspend fun getAll(page: Long, search: String?, status: String?, sqlClient: SqlClient): List<MarketCoupon>
    abstract suspend fun count(search: String?, status: String?, sqlClient: SqlClient): Long
}
