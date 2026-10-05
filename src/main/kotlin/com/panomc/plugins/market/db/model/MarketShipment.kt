package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_shipment.status` (00 section 7.6). */
enum class ShipmentStatus { CREATED, LABEL_READY, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, EXCEPTION, RETURNING, RETURNED, CANCELLED, LOST }

/** `market_shipment.entryMode` (01 section 11.5). */
enum class ShipmentEntryMode { CARRIER, MANUAL }

/**
 * `market_shipment` (01 section 11.5). Money is minor units; [documents], [packages], [fromAddress] and [toAddress]
 * are JSON; [providerData] is stored encrypted by the caller. [merchantReference] is unique.
 */
open class MarketShipment(
    val id: Long = -1,
    val orderId: Long = 0,
    val methodId: Long? = null,
    val providerId: String = "",
    val serviceCode: String? = null,
    val status: ShipmentStatus = ShipmentStatus.CREATED,
    val entryMode: ShipmentEntryMode = ShipmentEntryMode.CARRIER,
    val merchantReference: String = "",
    val carrierReference: String? = null,
    val trackingNumber: String? = null,
    val trackingUrl: String? = null,
    val carrierName: String? = null,
    val labelFile: String? = null,
    val labelFormat: String? = null,
    val documents: String? = null,
    val rateRef: String? = null,
    val cost: Long? = null,
    val costCurrency: String? = null,
    val weightGrams: Int? = null,
    val packages: String? = null,
    val estimatedDeliveryAt: Long? = null,
    val note: String? = null,
    val lastErrorCode: String? = null,
    val lastError: String? = null,
    val claimedUntil: Long? = null,
    val itemsReleased: Boolean = false,
    val stale: Boolean = false,
    val fromAddress: String = "{}",
    val toAddress: String = "{}",
    val codAmount: Long? = null,
    val providerData: String? = null,
    val testMode: Boolean = false,
    val nextPollAt: Long? = null,
    val pollCount: Int = 0,
    val lastPolledAt: Long? = null,
    val shippedAt: Long? = null,
    val deliveredAt: Long? = null,
    val cancelledAt: Long? = null,
    val trackingMailSentAt: Long? = null,
    val createdBy: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
