package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_order_event.type` (01 section 5.3). The list is closed: a new type is added to the design first. */
enum class OrderEventType {
    CREATED, STATUS_CHANGED, PAYMENT_STARTED, PAYMENT_SUCCEEDED, PAYMENT_FAILED, PAYMENT_CANCELLED,
    BANK_TRANSFER_NOTIFIED, REVIEW_OPENED, REFUND_REQUESTED, REFUND_SUCCEEDED, REFUND_FAILED, DISPUTE_INQUIRY,
    DISPUTE_OPENED, DISPUTE_CLOSED, BLOCK_CREATED, BLOCK_REMOVED, CLAWBACK_SHORTFALL, DELIVERY_RERUN, DELIVERY_FAILED,
    DELIVERY_REVOKED, SHIPMENT_CREATED, SHIPMENT_UPDATED, SHIPMENT_CANCELLED, SUBSCRIPTION_STARTED,
    SUBSCRIPTION_RENEWED, SUBSCRIPTION_PAST_DUE, SUBSCRIPTION_CHARGE_FAILED, SUBSCRIPTION_CANCEL_REQUESTED,
    SUBSCRIPTION_RESUMED, SUBSCRIPTION_ENDED, SUBSCRIPTION_REMOTE_CANCEL_FAILED, MAIL_QUEUED, NOTE
}

enum class OrderActorType { SYSTEM, BUYER, ADMIN, GATEWAY }

/** `market_order_event` (01 section 5.3): the order timeline, append-only. */
open class MarketOrderEvent(
    val id: Long = -1,
    val orderId: Long = -1,
    val type: OrderEventType = OrderEventType.NOTE,
    /** For [OrderEventType.STATUS_CHANGED]. */
    val fromStatus: String? = null,
    val toStatus: String? = null,
    val actorType: OrderActorType = OrderActorType.SYSTEM,
    val actorUserId: Long? = null,
    val message: String? = null,
    /** JSON text. */
    val data: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
