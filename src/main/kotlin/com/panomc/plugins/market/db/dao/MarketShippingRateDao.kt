package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.*
import io.vertx.sqlclient.SqlClient

/** Shipping rate rows, one per rule of a (method, zone) (01 section 11.3). */
abstract class MarketShippingRateDao : MarketDao<MarketShippingRate>(MarketShippingRate::class.java) {
    /** The new id. */
    abstract suspend fun add(rate: MarketShippingRate, sqlClient: SqlClient): Long

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketShippingRate?

    /** Writes every mutable column of [rate] (not the origin columns and `createdAt`); `true` when the row exists. */
    abstract suspend fun update(rate: MarketShippingRate, sqlClient: SqlClient): Boolean

    /** Every rule row of a method, in rule order (zone, `position`, id). */
    abstract suspend fun getByMethodId(methodId: Long, sqlClient: SqlClient): List<MarketShippingRate>

    /** The rule rows of one (method, zone), in rule order (`idx_method_zone`). */
    abstract suspend fun getByMethodAndZone(methodId: Long, zoneId: Long, sqlClient: SqlClient): List<MarketShippingRate>

    abstract suspend fun delete(id: Long, sqlClient: SqlClient): Boolean

    /** Deletes every rule row of a method; returns how many. */
    abstract suspend fun deleteByMethodId(methodId: Long, sqlClient: SqlClient): Int

    /** Deletes every rule row of a zone; returns how many. */
    abstract suspend fun deleteByZoneId(zoneId: Long, sqlClient: SqlClient): Int
}
