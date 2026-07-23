package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity
import com.panomc.plugins.market.util.OrderStatus

open class MarketOrder(
    val id: Long = -1,
    val userId: Long? = null,
    val playerUsername: String = "",
    val totalPrice: Long = 0,
    val currency: String = "TRY",
    val paymentMethodId: String = "",
    val paymentLabel: String = "",
    val status: OrderStatus = OrderStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
