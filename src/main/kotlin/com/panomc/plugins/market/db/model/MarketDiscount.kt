package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity
import com.panomc.plugins.market.util.DiscountScope
import com.panomc.plugins.market.util.DiscountUnit
import com.panomc.plugins.market.util.MarketStatus

open class MarketDiscount(
    val id: Long = -1,
    val name: String = "",
    val value: Long = 0,                   // ×100: basis points if PERCENT, minor units if FIXED
    val unit: DiscountUnit = DiscountUnit.PERCENT,
    val minPaymentAmount: Long? = null,    // ×100
    val scope: DiscountScope = DiscountScope.ALL,
    val productIds: List<Long>? = null,
    val categoryIds: List<Long>? = null,
    val startDate: Long? = null,
    val expiryDate: Long? = null,
    val usageLimit: Int? = null,
    val usedCount: Int = 0,                // server-maintained, never client-writable
    val status: MarketStatus = MarketStatus.ACTIVE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
