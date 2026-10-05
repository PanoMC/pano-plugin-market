package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_refund.status` (00 section 7.3). */
enum class RefundStatus { REQUESTED, PENDING, SUCCEEDED, FAILED, CANCELLED }

/** `market_refund.origin` (01 section 6.4). */
enum class RefundOrigin { PANEL, GATEWAY, SYSTEM }

/**
 * `market_refund` (01 section 6.4). One row per refund of a (possibly mixed) order: [amount] = [gatewayAmount] +
 * [creditValue], money x100 in the order currency, credits x100 in credit units.
 */
open class MarketRefund(
    val id: Long = -1,
    val orderId: Long = -1,
    /** `null` = credit-only refund. */
    val paymentId: Long? = null,
    val providerId: String? = null,
    val status: RefundStatus = RefundStatus.REQUESTED,
    val origin: RefundOrigin = RefundOrigin.PANEL,
    val idempotencyKey: String = "",
    /** SHA-256 of the canonical panel request body; `null` for gateway and system origins. */
    val idempotencyHash: String? = null,
    val amount: Long = 0,
    val gatewayAmount: Long = 0,
    val gatewayRefundedAmount: Long? = null,
    val creditAmount: Long = 0,
    val creditValue: Long = 0,
    val currency: String = "",
    val reason: String? = null,
    val gatewayRefundId: String? = null,
    val buyerActionUrl: String? = null,
    val revoke: Boolean = true,
    val revokeFirst: Boolean = false,
    val cascadeUpgrade: Boolean? = null,
    val restock: Boolean = false,
    val creditTxId: Long? = null,
    val initiatedBy: Long? = null,
    val failureCode: String? = null,
    val failureMessage: String? = null,
    val nextQueryAt: Long? = null,
    val queryCount: Int = 0,
    val completedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
