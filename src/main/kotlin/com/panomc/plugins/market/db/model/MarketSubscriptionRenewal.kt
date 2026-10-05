package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_subscription_renewal.status` (01 section 10.2). */
enum class RenewalStatus { PENDING, PAID, FAILED, SKIPPED }

/**
 * `market_subscription_renewal` (01 section 10.2): one row per billing period after the first. (subscriptionId,
 * periodIndex) is unique, so a period can be prepared and charged only once. [periodIndex] 1 is the first renewal.
 */
open class MarketSubscriptionRenewal(
    val id: Long = -1,
    val subscriptionId: Long = 0,
    val periodIndex: Int = 1,
    val periodStart: Long = 0,
    val periodEnd: Long = 0,
    val orderId: Long? = null,
    val paymentId: Long? = null,
    val status: RenewalStatus = RenewalStatus.PENDING,
    val amount: Long = 0,
    val currency: String = "",
    val attempts: Int = 0,
    val nextAttemptAt: Long? = null,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
