package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.MarketDiscount
import io.vertx.sqlclient.SqlClient

/** An automatic discount row with its badge flag. */
class AutomaticDiscount(val discount: MarketDiscount, val showBadge: Boolean)

abstract class MarketDiscountDao : MarketDao<MarketDiscount>(MarketDiscount::class.java) {
    abstract suspend fun add(discount: MarketDiscount, sqlClient: SqlClient): Long
    abstract suspend fun update(discount: MarketDiscount, sqlClient: SqlClient)
    abstract suspend fun deleteById(id: Long, sqlClient: SqlClient)
    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketDiscount?
    abstract suspend fun getAll(page: Long, search: String?, status: String?, sqlClient: SqlClient): List<MarketDiscount>
    abstract suspend fun count(search: String?, status: String?, sqlClient: SqlClient): Long

    /**
     * Every ACTIVE, not soft deleted automatic discount with its `showBadge` flag (01 section 3.1): what the storefront
     * advertises. The windows and usage limits are judged by the pricing code, not here.
     */
    abstract suspend fun getAutomatic(sqlClient: SqlClient): List<AutomaticDiscount>
}
