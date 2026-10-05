package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_webhook_delivery.status` (01 section 9.4): `FAILED` will retry, `DEAD` will not. */
enum class WebhookDeliveryStatus { PENDING, SENDING, SUCCEEDED, FAILED, DEAD }

/**
 * `market_webhook_delivery` (01 section 9.4): the outbound queue and log. [endpointId] 0 = a product `WEBHOOK` action
 * ([deliveryId] then points at `market_delivery`). [eventId] is unique. [secret] is an `ENC` column.
 */
open class MarketWebhookDelivery(
    val id: Long = -1,
    val endpointId: Long = 0,
    val deliveryId: Long = 0,
    val eventId: String = "",
    val event: String = "",
    val orderId: Long? = null,
    val url: String = "",
    val format: WebhookFormat = WebhookFormat.JSON,
    val signing: WebhookSigning = WebhookSigning.NONE,
    val secret: String? = null,
    val body: String = "",
    val status: WebhookDeliveryStatus = WebhookDeliveryStatus.PENDING,
    val attempts: Int = 0,
    val maxAttempts: Int = 8,
    val nextAttemptAt: Long? = null,
    val claimedUntil: Long? = null,
    val lastStatusCode: Int? = null,
    val lastError: String? = null,
    val lastResponse: String? = null,
    val durationMs: Int? = null,
    val deliveredAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
