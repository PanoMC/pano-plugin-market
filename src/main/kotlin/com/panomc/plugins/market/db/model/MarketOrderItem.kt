package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

open class MarketOrderItem(
    val id: Long = -1,
    val orderId: Long = -1,
    val productId: Long? = null,
    val productName: String = "",
    val quantity: Int = 1,
    val unitPrice: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
