package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_mail_outbox.kind` (01 section 9.5). */
enum class MailKind {
    ORDER_RECEIVED, ORDER_CONFIRMATION, ORDER_DELIVERED, GIFT_RECEIVED, BANK_TRANSFER_INSTRUCTIONS, ORDER_REFUNDED,
    SUBSCRIPTION_REMINDER, SUBSCRIPTION_PAYMENT_FAILED, SUBSCRIPTION_CANCELLED, SUBSCRIPTION_ENDED, EXPIRY_REMINDER,
    SHIPMENT_SHIPPED, SHIPMENT_DELIVERED
}

/** `market_mail_outbox.refType`. */
enum class MailRefType { ORDER, PAYMENT, REFUND, SHIPMENT, SUBSCRIPTION, ENTITLEMENT }

/** `market_mail_outbox.status`: `SKIPPED` = mail disabled, kind switched off, or host too old. */
enum class MailStatus { PENDING, SENDING, SENT, FAILED, SKIPPED }

/**
 * `market_mail_outbox` (01 section 9.5). (kind, refType, refId, refKey, recipient) is unique: a second enqueue of the
 * same mail is refused by the database. [refKey] tells several mails of one kind for one reference apart.
 */
open class MarketMailOutbox(
    val id: Long = -1,
    val kind: MailKind = MailKind.ORDER_RECEIVED,
    val refType: MailRefType = MailRefType.ORDER,
    val refId: Long = 0,
    val refKey: String = "",
    val orderId: Long? = null,
    val userId: Long? = null,
    val recipient: String = "",
    val locale: String = "en-US",
    val params: String = "{}",
    val status: MailStatus = MailStatus.PENDING,
    val attempts: Int = 0,
    val nextAttemptAt: Long? = null,
    val claimedUntil: Long? = null,
    val lastError: String? = null,
    val sentAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
