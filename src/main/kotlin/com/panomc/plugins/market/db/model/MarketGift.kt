package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity
import com.panomc.plugins.market.util.GiftType
import com.panomc.plugins.market.util.MarketStatus

open class MarketGift(
    val id: Long = -1,
    val code: String = "",
    val type: GiftType = GiftType.PRODUCT,
    val productId: Long? = null,
    val creditAmount: Long? = null,        // ×100, type=CREDIT
    val productIds: List<Long>? = null,    // type=RANDOM
    val status: MarketStatus = MarketStatus.ACTIVE,
    val startDate: Long? = null,
    val expiryDate: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
