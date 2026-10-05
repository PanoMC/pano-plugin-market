package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_refund_item` (01 section 6.4): the order line a refund covers; one row per (refund, line). */
open class MarketRefundItem(
    val id: Long = -1,
    val refundId: Long = -1,
    val orderItemId: Long = -1,
    val quantity: Int = 0,
    val amount: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
