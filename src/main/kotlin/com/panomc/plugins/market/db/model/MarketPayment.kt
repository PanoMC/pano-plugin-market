package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_payment.status` (00 section 7.2). */
enum class PaymentStatus { CREATED, PENDING, PROCESSING, SUCCEEDED, FAILED, CANCELLED, EXPIRED, REVIEW }

/**
 * `market_payment` (01 section 6.2): one payment attempt. Money is x100 in the order currency. [startPayload] and
 * [providerData] are `ENC` columns: they hold the `v1:...` text produced by `SecretCipher`, the DAO stores them verbatim.
 */
open class MarketPayment(
    val id: Long = -1,
    val orderId: Long = -1,
    /** Renewal charge. */
    val subscriptionId: Long? = null,
    val providerId: String = "",
    val methodLabel: String = "",
    val status: PaymentStatus = PaymentStatus.CREATED,
    /** `[A-Z0-9]{20}`, sent to the gateway as merchant reference. */
    val reference: String = "",
    /** 40 hex characters, in notify and return URLs. */
    val token: String = "",
    /** To collect, including [feeAmount]. */
    val amount: Long = 0,
    val currency: String = "",
    val feeAmount: Long = 0,
    /** Tender snapshot: credits held for the order when this attempt was created (credit units). */
    val creditAmount: Long = 0,
    /** Tender snapshot: money value of those credits. */
    val creditValue: Long = 0,
    /** Tender snapshot: `order.totalPrice` at creation. */
    val orderTotal: Long = 0,
    /** `REDIRECT, FORM_POST, IFRAME, HTML, EMBEDDED, INSTRUCTIONS, COMPLETED`. */
    val startKind: String? = null,
    /** ENC: the start result, re-served when the buyer reloads. */
    val startPayload: String? = null,
    val gatewayTransactionId: String? = null,
    /** JSON object of the gateway's other ids. */
    val gatewayRefs: String? = null,
    /** ENC: opaque provider state for this attempt. */
    val providerData: String? = null,
    val paidAmount: Long? = null,
    val paidCurrency: String? = null,
    val gatewayFee: Long? = null,
    val netAmount: Long? = null,
    val settlementCurrency: String? = null,
    /** Decimal string (crypto precision). */
    val settlementAmount: String? = null,
    val installments: Int? = null,
    val methodDetail: String? = null,
    val testMode: Boolean = false,
    val duplicate: Boolean = false,
    val refundedAmount: Long = 0,
    val failureCode: String? = null,
    val failureMessage: String? = null,
    val adminMessage: String? = null,
    val clientIp: String? = null,
    val userAgent: String? = null,
    val startedAt: Long? = null,
    val paidAt: Long? = null,
    val expiresAt: Long? = null,
    val closedAt: Long? = null,
    val nextQueryAt: Long? = null,
    val queryCount: Int = 0,
    val lastQueriedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
