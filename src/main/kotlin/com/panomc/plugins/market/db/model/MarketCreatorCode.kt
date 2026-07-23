package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity
import com.panomc.plugins.market.util.DiscountUnit
import com.panomc.plugins.market.util.MarketStatus

open class MarketCreatorCode(
    val id: Long = -1,
    val creator: String = "",              // player username snapshot (not FK'd)
    val code: String = "",
    val discount: Long = 0,                // ×100
    val unit: DiscountUnit = DiscountUnit.PERCENT,
    val commissionPercent: Long = 0,       // ×100 basis points
    val startDate: Long? = null,
    val expiryDate: Long? = null,
    val redeemLimit: Int? = null,
    val usedCount: Int = 0,                // server-maintained, never client-writable
    val earnings: Long = 0,                // minor units, server-maintained, never client-writable
    val status: MarketStatus = MarketStatus.ACTIVE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
