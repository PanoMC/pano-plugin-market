package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.*
import io.vertx.sqlclient.SqlClient

/** Shipments of an order (01 section 11.5). `providerId`, `merchantReference`, `orderId` and `createdBy` are immutable after insert. */
abstract class MarketShipmentDao : MarketDao<MarketShipment>(MarketShipment::class.java) {
    /** The new id, or `null` when `uq_merchantRef` (merchantReference) exists already. */
    abstract suspend fun add(shipment: MarketShipment, sqlClient: SqlClient): Long?

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketShipment?

    /** Writes every mutable column of [shipment] (not the origin columns and `createdAt`); `true` when the row exists. */
    abstract suspend fun update(shipment: MarketShipment, sqlClient: SqlClient): Boolean

    abstract suspend fun getByMerchantReference(merchantReference: String, sqlClient: SqlClient): MarketShipment?

    abstract suspend fun getByCarrierReference(providerId: String, carrierReference: String, sqlClient: SqlClient): MarketShipment?

    abstract suspend fun getByTrackingNumber(trackingNumber: String, sqlClient: SqlClient): List<MarketShipment>

    /** Every shipment of an order, oldest first. */
    abstract suspend fun getByOrderId(orderId: Long, sqlClient: SqlClient): List<MarketShipment>

    /** Shipments in [statuses] whose `nextPollAt` is due (`idx_poll`), oldest first. */
    abstract suspend fun getDueForPoll(statuses: List<ShipmentStatus>, now: Long, limit: Int, sqlClient: SqlClient): List<MarketShipment>

    /** Compare-and-set of the status: `true` when the row was in [from]. */
    abstract suspend fun transition(id: Long, from: ShipmentStatus, to: ShipmentStatus, now: Long, sqlClient: SqlClient): Boolean

    /**
     * Claims the shipment for a create / retry call in flight: sets `claimedUntil = until` only when no live claim
     * exists at [now]. `true` for exactly one of several concurrent callers.
     */
    abstract suspend fun claim(id: Long, now: Long, until: Long, sqlClient: SqlClient): Boolean

    /** Drops the claim. */
    abstract suspend fun releaseClaim(id: Long, now: Long, sqlClient: SqlClient): Boolean

    /** Atomic `pollCount + 1` with the poll times (`nextPollAt` may be `null` to stop polling). */
    abstract suspend fun recordPoll(id: Long, polledAt: Long, nextPollAt: Long?, sqlClient: SqlClient): Boolean
}
