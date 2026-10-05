package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

enum class CreatorEarningState { PENDING, AVAILABLE, PAID, REVERSED }

/** `market_creator_earning` (01 section 8): the commission of one order for one creator code (base currency). */
open class MarketCreatorEarning(
    val id: Long = -1,
    val creatorCodeId: Long = -1,
    val creatorUserId: Long? = null,
    val orderId: Long = -1,
    /** Order value excluding VAT, shipping and fee. */
    val baseAmount: Long = 0,
    /** Basis points. */
    val commissionPercent: Long = 0,
    val amount: Long = 0,
    val currency: String = "",
    val state: CreatorEarningState = CreatorEarningState.PENDING,
    val availableAt: Long? = null,
    val reversedAmount: Long = 0,
    val payoutId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
