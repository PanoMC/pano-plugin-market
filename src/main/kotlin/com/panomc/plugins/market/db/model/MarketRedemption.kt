package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

enum class RedemptionKind { COUPON, CREATOR_CODE, GIFT, DISCOUNT }

/** `HELD` (order pending) to `APPLIED` (paid) or `RELEASED` (order expired, cancelled, fully refunded). */
enum class RedemptionState { HELD, APPLIED, RELEASED }

/** `market_redemption` (01 section 3.5): one row per use of a code or discount, unique per `(kind, refId, orderId)`. */
open class MarketRedemption(
    val id: Long = -1,
    val kind: RedemptionKind = RedemptionKind.COUPON,
    /** Id in the table named by [kind]. */
    val refId: Long = -1,
    val code: String? = null,
    val orderId: Long = -1,
    val userId: Long? = null,
    /** Per-customer limit subject (payer). */
    val buyerKey: String = "",
    /** Lower-cased. */
    val email: String? = null,
    val recipientKey: String = "",
    val amount: Long = 0,
    val currency: String = "",
    val state: RedemptionState = RedemptionState.HELD,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
