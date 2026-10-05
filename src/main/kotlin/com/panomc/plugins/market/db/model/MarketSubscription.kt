package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_subscription.mode` (01 section 10.1). */
enum class SubscriptionMode { GATEWAY, MERCHANT, MANUAL }

/** `market_subscription.status` (00 section 7.5). */
enum class SubscriptionStatus { PENDING, ACTIVE, PAST_DUE, PAUSED, EXPIRED, CANCELLED, COMPLETED }

/** `market_subscription.intervalUnit`. */
enum class SubscriptionIntervalUnit { DAY, WEEK, MONTH, YEAR }

/** `market_subscription.remoteCancelState`: the durable "stop billing at the gateway" queue. */
enum class RemoteCancelState { NONE, PENDING, DONE, FAILED }

/**
 * `market_subscription` (01 section 10.1). [price] is the per-period total in minor units, frozen from the initial
 * order. [storedMethod] and [providerData] are stored encrypted by the caller (`ENC` columns, kept verbatim);
 * [fieldValues] is JSON. (providerId, gatewaySubscriptionId) is unique.
 */
open class MarketSubscription(
    val id: Long = -1,
    val userId: Long? = null,
    val playerUsername: String = "",
    val ownerKey: String = "",
    val email: String? = null,
    val productId: Long = 0,
    val variantId: Long = 0,
    val productName: String = "",
    val initialOrderId: Long = 0,
    val initialOrderItemId: Long = 0,
    val entitlementId: Long? = null,
    val providerId: String = "",
    val mode: SubscriptionMode = SubscriptionMode.GATEWAY,
    val status: SubscriptionStatus = SubscriptionStatus.PENDING,
    val intervalUnit: SubscriptionIntervalUnit = SubscriptionIntervalUnit.MONTH,
    val intervalCount: Int = 1,
    val price: Long = 0,
    val currency: String = "",
    val maxCycles: Int? = null,
    val cycleCount: Int = 0,
    val currentPeriodStart: Long? = null,
    val currentPeriodEnd: Long? = null,
    val nextChargeAt: Long? = null,
    val nextQueryAt: Long? = null,
    val lastQueriedAt: Long? = null,
    val remoteCancelState: RemoteCancelState = RemoteCancelState.NONE,
    val remoteCancelAttempts: Int = 0,
    val graceEndsAt: Long? = null,
    val cancelAtPeriodEnd: Boolean = false,
    val cancelRequestedAt: Long? = null,
    val cancelledAt: Long? = null,
    val endedAt: Long? = null,
    val endReason: String? = null,
    val gatewaySubscriptionId: String? = null,
    val gatewayCustomerId: String? = null,
    val storedMethod: String? = null,
    val storedMethodLabel: String? = null,
    val failCount: Int = 0,
    val lastFailureAt: Long? = null,
    val reminderSentAt: Long? = null,
    val targetServerId: Long? = null,
    val fieldValues: String? = null,
    val providerData: String? = null,
    val testMode: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
