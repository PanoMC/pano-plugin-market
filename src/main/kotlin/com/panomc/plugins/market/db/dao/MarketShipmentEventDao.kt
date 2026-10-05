package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.*
import io.vertx.sqlclient.SqlClient

/** Tracking events of a shipment, append-only (01 section 11.5): the unique `(shipmentId, dedupeKey)` makes a replayed event a no-op (`add` returns `null`). Only the display text can be corrected afterwards. */
abstract class MarketShipmentEventDao : MarketDao<MarketShipmentEvent>(MarketShipmentEvent::class.java) {
    /** The new id, or `null` when `uq_shipment_event` (shipmentId, dedupeKey) exists already. */
    abstract suspend fun add(event: MarketShipmentEvent, sqlClient: SqlClient): Long?

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketShipmentEvent?

    /** Writes every mutable column of [event] (not the origin columns and `createdAt`); `true` when the row exists. */
    abstract suspend fun update(event: MarketShipmentEvent, sqlClient: SqlClient): Boolean

    /** The events of a shipment in occurrence order (`occurredAt`, id). */
    abstract suspend fun getByShipmentId(shipmentId: Long, sqlClient: SqlClient): List<MarketShipmentEvent>

    abstract suspend fun getByDedupeKey(shipmentId: Long, dedupeKey: String, sqlClient: SqlClient): MarketShipmentEvent?

    abstract suspend fun countByShipmentId(shipmentId: Long, sqlClient: SqlClient): Long
}
