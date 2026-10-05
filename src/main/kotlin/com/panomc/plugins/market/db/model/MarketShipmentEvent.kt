package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_shipment_event.source` (01 section 11.5). */
enum class ShipmentEventSource { WEBHOOK, POLL, MANUAL }

/**
 * `market_shipment_event` (01 section 11.5): one tracking event, append-only. [status] is the normalized status,
 * [dedupeKey] the provider event id (else a hash of status, time and location); (shipmentId, dedupeKey) is unique.
 */
open class MarketShipmentEvent(
    val id: Long = -1,
    val shipmentId: Long = 0,
    val status: ShipmentStatus = ShipmentStatus.CREATED,
    val rawStatus: String? = null,
    val description: String? = null,
    val location: String? = null,
    val occurredAt: Long = 0,
    val source: ShipmentEventSource = ShipmentEventSource.POLL,
    val dedupeKey: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
