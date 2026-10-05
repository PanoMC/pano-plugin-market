package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_shipment_item` (01 section 11.5): [quantity] of an order item in a shipment; (shipmentId, orderItemId) is unique. */
open class MarketShipmentItem(
    val id: Long = -1,
    val shipmentId: Long = 0,
    val orderItemId: Long = 0,
    val quantity: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
