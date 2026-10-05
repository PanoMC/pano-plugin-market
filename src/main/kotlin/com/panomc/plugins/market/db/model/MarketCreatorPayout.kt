package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

enum class CreatorPayoutMethod { CREDIT, ACTION, MANUAL }

enum class CreatorPayoutState { PENDING, PAID, FAILED, CANCELLED }

/** `market_creator_payout` (01 section 8): a payout to a creator, insert-first on `uq_idem(idempotencyKey)`. */
open class MarketCreatorPayout(
    val id: Long = -1,
    val creatorCodeId: Long = -1,
    val creatorUserId: Long? = null,
    val amount: Long = 0,
    val currency: String = "",
    val method: CreatorPayoutMethod = CreatorPayoutMethod.MANUAL,
    val state: CreatorPayoutState = CreatorPayoutState.PENDING,
    val creditTxId: Long? = null,
    /** JSON action array executed through `market_delivery` with `sourceType = CREATOR_PAYOUT`. */
    val actions: String? = null,
    val note: String? = null,
    val paidBy: Long? = null,
    val paidAt: Long? = null,
    val idempotencyKey: String = "",
    /** SHA-256 hex of the request, compared on a replayed key. */
    val idempotencyHash: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
