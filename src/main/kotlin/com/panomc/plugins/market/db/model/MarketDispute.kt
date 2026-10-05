package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_dispute.status` (01 section 6.5); not the order's `DisputeStatus` column. */
enum class DisputeRecordStatus { INQUIRY, OPEN, WON, LOST, CLOSED }

/** `market_dispute.origin` (01 section 6.5). */
enum class DisputeOrigin { GATEWAY, MANUAL }

/** `market_dispute` (01 section 6.5). Money is x100 in the order currency. */
open class MarketDispute(
    val id: Long = -1,
    val orderId: Long = -1,
    val paymentId: Long? = null,
    val providerId: String? = null,
    val gatewayDisputeId: String? = null,
    val status: DisputeRecordStatus = DisputeRecordStatus.OPEN,
    val origin: DisputeOrigin = DisputeOrigin.GATEWAY,
    val amount: Long = 0,
    val currency: String = "",
    val reason: String? = null,
    val openedAt: Long = 0,
    val resolvedAt: Long? = null,
    val createdBy: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
