package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_credit_tx.type` (01 section 7.2). */
enum class CreditTxType {
    TOPUP, GRANT, REVOKE, HOLD, CAPTURE, RELEASE, REFUND, CASHBACK, CASHBACK_REVERSAL, GIFT, ACTION, ACTION_REVERSAL,
    CREATOR_PAYOUT,

    /** `EXTERNAL` to user. */
    EXTERNAL_IN,

    /** User to `EXTERNAL` (policy `FAIL`). */
    EXTERNAL_OUT
}

/**
 * `market_credit_tx` (01 section 7.2): one posting of the ledger. [amount] is the absolute credits moved (x100),
 * [shortfall] the part of a revoke / clawback that could not be taken. [idempotencyKey] is unique: a replay returns the
 * existing row.
 */
open class MarketCreditTx(
    val id: Long = -1,
    val type: CreditTxType = CreditTxType.GRANT,
    val idempotencyKey: String = "",
    val userId: Long? = null,
    val amount: Long = 0,
    val shortfall: Long = 0,
    val orderId: Long? = null,
    val refundId: Long? = null,
    val deliveryId: Long? = null,
    val actorUserId: Long? = null,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
