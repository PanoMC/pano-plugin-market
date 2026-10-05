package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.*
import io.vertx.sqlclient.SqlClient

/** Shipping methods (01 section 11.2). */
abstract class MarketShippingMethodDao : MarketDao<MarketShippingMethod>(MarketShippingMethod::class.java) {
    /** The new id. */
    abstract suspend fun add(method: MarketShippingMethod, sqlClient: SqlClient): Long

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketShippingMethod?

    /** Writes every mutable column of [method] (not the origin columns and `createdAt`); `true` when the row exists. */
    abstract suspend fun update(method: MarketShippingMethod, sqlClient: SqlClient): Boolean

    /** Every method including soft-deleted ones, in display order. */
    abstract suspend fun getAll(sqlClient: SqlClient): List<MarketShippingMethod>

    /** Methods that are `ACTIVE` and not soft-deleted, in display order. */
    abstract suspend fun getActive(sqlClient: SqlClient): List<MarketShippingMethod>

    /** Soft delete: sets `deletedAt` once; `false` when the method is missing or deleted already. */
    abstract suspend fun softDelete(id: Long, now: Long, sqlClient: SqlClient): Boolean
}
