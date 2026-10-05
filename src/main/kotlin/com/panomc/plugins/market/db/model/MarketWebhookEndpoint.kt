package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_webhook_endpoint.format` and `market_webhook_delivery.format`. */
enum class WebhookFormat { JSON, DISCORD }

/** `market_webhook_endpoint.signing` and `market_webhook_delivery.signing`. */
enum class WebhookSigning { NONE, HMAC_SHA256 }

/**
 * `market_webhook_endpoint` (01 section 9.3): a global store webhook. [events] is a JSON list of event names or
 * `["*"]`; [secret] and [headers] are `ENC` columns (the `v1:...` text of `SecretCipher`, stored verbatim). An endpoint
 * is auto-disabled after 50 consecutive failures ([disabledReason]).
 */
open class MarketWebhookEndpoint(
    val id: Long = -1,
    val name: String = "",
    val url: String = "",
    val events: String = "[\"*\"]",
    val format: WebhookFormat = WebhookFormat.JSON,
    val signing: WebhookSigning = WebhookSigning.NONE,
    val secret: String? = null,
    val headers: String? = null,
    val template: String? = null,
    val enabled: Boolean = true,
    val maxAttempts: Int = 8,
    val failureCount: Int = 0,
    val lastStatusCode: Int? = null,
    val lastDeliveryAt: Long? = null,
    val disabledReason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
