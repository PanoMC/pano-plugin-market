package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.*
import io.vertx.sqlclient.SqlClient

/** Order item quantities inside a shipment (01 section 11.5). */
abstract class MarketShipmentItemDao : MarketDao<MarketShipmentItem>(MarketShipmentItem::class.java) {
    /** The new id, or `null` when `uq_shipment_item` (shipmentId, orderItemId) exists already. */
    abstract suspend fun add(item: MarketShipmentItem, sqlClient: SqlClient): Long?

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketShipmentItem?

    /** Writes every mutable column of [item] (not the origin columns and `createdAt`); `true` when the row exists. */
    abstract suspend fun update(item: MarketShipmentItem, sqlClient: SqlClient): Boolean

    abstract suspend fun getByShipmentId(shipmentId: Long, sqlClient: SqlClient): List<MarketShipmentItem>

    /** Every shipment line of an order item (across shipments). */
    abstract suspend fun getByOrderItemId(orderItemId: Long, sqlClient: SqlClient): List<MarketShipmentItem>

    /** Deletes the lines of a shipment; returns how many. */
    abstract suspend fun deleteByShipmentId(shipmentId: Long, sqlClient: SqlClient): Int
}
