package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity
import com.panomc.plugins.market.util.CouponScope
import com.panomc.plugins.market.util.DiscountUnit
import com.panomc.plugins.market.util.MarketStatus

open class MarketCoupon(
    val id: Long = -1,
    val name: String = "",
    val code: String = "",
    val scope: CouponScope = CouponScope.ALL,
    val productIds: List<Long>? = null,
    val discount: Long = 0,                // ×100
    val unit: DiscountUnit = DiscountUnit.PERCENT,
    val minPaymentAmount: Long? = null,    // ×100
    val startDate: Long? = null,
    val expiryDate: Long? = null,
    val redeemLimit: Int? = null,
    val customerRedeemLimit: Int? = null,
    val usedCount: Int = 0,                // server-maintained, never client-writable
    val status: MarketStatus = MarketStatus.ACTIVE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
