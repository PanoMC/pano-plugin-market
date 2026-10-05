package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_payment_event.direction` (01 section 6.3). */
enum class PaymentEventDirection { IN, OUT }

/** `market_payment_event.status` (01 section 6.3). */
enum class PaymentEventStatus { RECEIVED, PROCESSED, DUPLICATE, REJECTED, FAILED, DEFERRED, SUPERSEDED }

/** `market_payment_event` (01 section 6.3): raw provider traffic, append-only. */
open class MarketPaymentEvent(
    val id: Long = -1,
    val providerId: String = "",
    val direction: PaymentEventDirection = PaymentEventDirection.IN,
    /** `WEBHOOK, NOTIFY, RETURN, START, QUERY, REFUND, CANCEL, RECURRING, ACTION`. */
    val channel: String = "",
    val subChannel: String? = null,
    /** `r:<uuid>` at insert, `e:<provider key>` once the provider returned one; a UUID for `OUT`. */
    val eventKey: String = "",
    /** Hex SHA-256, diagnostics only (a plain index, not a de-duplication key). */
    val requestHash: String? = null,
    val paymentId: Long? = null,
    val orderId: Long? = null,
    val refundId: Long? = null,
    val subscriptionId: Long? = null,
    /** HTTP method. */
    val method: String? = null,
    val url: String? = null,
    /** JSON text. */
    val headers: String? = null,
    val body: String? = null,
    val remoteIp: String? = null,
    /** The provider's authenticity verdict. */
    val verified: Boolean? = null,
    val eventTypes: String? = null,
    val status: PaymentEventStatus = PaymentEventStatus.RECEIVED,
    val attempts: Int = 0,
    val duplicateCount: Int = 0,
    val nextAttemptAt: Long? = null,
    val responseStatus: Int? = null,
    val error: String? = null,
    val durationMs: Int? = null,
    val processedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
