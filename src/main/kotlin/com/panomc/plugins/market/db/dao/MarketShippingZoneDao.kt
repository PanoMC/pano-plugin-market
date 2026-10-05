package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.*
import io.vertx.sqlclient.SqlClient

/** Shipping zones (01 section 11.1). */
abstract class MarketShippingZoneDao : MarketDao<MarketShippingZone>(MarketShippingZone::class.java) {
    /** The new id. */
    abstract suspend fun add(zone: MarketShippingZone, sqlClient: SqlClient): Long

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketShippingZone?

    /** Writes every mutable column of [zone] (not the origin columns and `createdAt`); `true` when the row exists. */
    abstract suspend fun update(zone: MarketShippingZone, sqlClient: SqlClient): Boolean

    /** Every zone in matching order (`position`, then id). */
    abstract suspend fun getAll(sqlClient: SqlClient): List<MarketShippingZone>

    /** Zones with status `ACTIVE`, in matching order. */
    abstract suspend fun getActive(sqlClient: SqlClient): List<MarketShippingZone>

    /**
     * `ACTIVE` zones with at least one rate row of an `ACTIVE`, not deleted method (the zones a buyer can ship to),
     * in matching order. The state of the method's provider is not judged here.
     */
    abstract suspend fun getSellable(sqlClient: SqlClient): List<MarketShippingZone>

    abstract suspend fun delete(id: Long, sqlClient: SqlClient): Boolean
}
