package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_delivery.sourceType` (01 section 9.1). */
enum class DeliverySourceType { ORDER_ITEM, CHARGEBACK_ACTION, CREATOR_PAYOUT }

/** `market_delivery.phase`. */
enum class DeliveryPhase { GRANT, RENEW, EXPIRE, REVOKE }

/** `market_delivery.actionType`. */
enum class DeliveryActionType { CREDIT, PERMISSION, COMMAND, WEBHOOK }

/** `market_delivery.status` (00 section 7.4). `WAITING_PLAYER` is legal but not entered in v1. */
enum class DeliveryStatus {
    PENDING, SCHEDULED, WAITING_SERVER, WAITING_PLAYER, SENDING, SENT, QUEUED, CONFIRMED, FAILED, CANCELLED
}

/** `market_delivery.transport`: `PLUGIN` and `NODE` are reserved and unused in v1. */
enum class DeliveryTransport { INLINE, MARKET_MC, PLUGIN, NODE }

/**
 * `market_delivery` (01 section 9.1): one row per order item x action x target server x unit x phase. [payload] and
 * [result] are JSON text; [payload] is immutable after insert. [idempotencyKey] is unique and is also the
 * de-duplication key sent to the Minecraft side. [lastErrorCode] is a catalogue string (08 section 18).
 */
open class MarketDelivery(
    val id: Long = -1,
    val sourceType: DeliverySourceType = DeliverySourceType.ORDER_ITEM,
    val orderId: Long? = null,
    val orderItemId: Long? = null,
    val sourceId: Long? = null,
    val entitlementId: Long? = null,
    val subscriptionId: Long? = null,
    val phase: DeliveryPhase = DeliveryPhase.GRANT,
    val actionId: String = "",
    val actionType: DeliveryActionType = DeliveryActionType.COMMAND,
    val unitIndex: Int = 0,
    val attemptGroup: Int = 0,
    val serverId: Long = 0,
    val idempotencyKey: String = "",
    val status: DeliveryStatus = DeliveryStatus.PENDING,
    val requiresOnline: Boolean = false,
    val playerUsername: String = "",
    val playerUuid: String? = null,
    val payload: String = "{}",
    val result: String? = null,
    val transport: DeliveryTransport? = null,
    val guaranteed: Boolean = false,
    val attempts: Int = 0,
    val runAfter: Long = 0,
    val nextAttemptAt: Long? = null,
    val cancelRequestedAt: Long? = null,
    val waitUntil: Long? = null,
    val claimToken: String? = null,
    val claimedUntil: Long? = null,
    val sentAt: Long? = null,
    val confirmedAt: Long? = null,
    val lastErrorCode: String? = null,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
