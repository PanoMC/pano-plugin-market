package com.panomc.plugins.market.service

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.order.OrderEffect
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketMailOutboxDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderEventDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.model.MailKind
import com.panomc.plugins.market.db.model.MailRefType
import com.panomc.plugins.market.db.model.MailStatus
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.tx.LockedOrder
import com.panomc.plugins.market.db.model.OrderSource
import com.panomc.plugins.market.service.platform.UserDirectory
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection

/** The mails of a payment attempt's transitions (12 section 4.1): written by `PaymentService` inside the transition's transaction. */
interface PaymentMails {
    /** The attempt got `startKind = INSTRUCTIONS` (bank transfer): [start] is the stored `PaymentStart` JSON. The order is `PENDING`. */
    suspend fun instructions(conn: SqlConnection, order: MarketOrder, attempt: MarketPayment, start: JsonObject)

    /** The attempt went `PENDING -> PROCESSING` while the order is `PENDING`: "we received your order". */
    suspend fun processing(conn: SqlConnection, order: MarketOrder, attempt: MarketPayment)

    companion object {
        val NONE = object : PaymentMails {
            override suspend fun instructions(conn: SqlConnection, order: MarketOrder, attempt: MarketPayment, start: JsonObject) = Unit

            override suspend fun processing(conn: SqlConnection, order: MarketOrder, attempt: MarketPayment) = Unit
        }
    }
}

/** `ORDER_RECEIVED` at O3: told by `OrderService` when a `PENDING` order goes to `REVIEW`, inside the transition's transaction. */
fun interface ReceivedMails {
    suspend fun received(conn: SqlConnection, order: MarketOrder)

    companion object {
        val NONE = ReceivedMails { _, _ -> }
    }
}

/** `ORDER_DELIVERED`: told by `DeliveryService` when `market_order.fulfillmentStatus` becomes `FULFILLED`, in the transaction that did it. */
fun interface FulfillmentMails {
    suspend fun fulfilled(conn: SqlClient, order: MarketOrder)

    companion object {
        val NONE = FulfillmentMails { _, _ -> }
    }
}

/**
 * The enqueue side of the order mails (12 section 4.1, MK-142): `ORDER_RECEIVED`, `BANK_TRANSFER_INSTRUCTIONS`, `ORDER_CONFIRMATION`,
 * `GIFT_RECEIVED` and `ORDER_DELIVERED`, each written through [MailOutboxService.enqueue] on the connection of the transition that owns it
 * (the mail row commits or rolls back with it). `ORDER_REFUNDED` is queued by `StandardRefundEffects.mail` (MK-111) in the O10 transaction.
 *
 * The recipient is the payer's e-mail (`market_order.email`, else the e-mail of `market_order.userId`); the locale is the order's, else
 * [defaultLocale]. No address queues no row: [MailOutboxService.enqueue] writes the `MAIL_NOT_QUEUED:<KIND>:NO_RECIPIENT` timeline note.
 */
class OrderMails(
    private val config: () -> MarketConfig,
    private val clock: Clock,
    private val outbox: MailOutboxService,
    private val mailOutbox: MarketMailOutboxDao,
    private val orderItems: MarketOrderItemDao,
    private val orderEvents: MarketOrderEventDao,
    private val users: UserDirectory?,
    private val defaultLocale: () -> String = { MailOutboxService.DEFAULT_LOCALE }
) : PaymentMails, FulfillmentMails, ReceivedMails {
    /** O2 / O4: `ORDER_CONFIRMATION` to the payer and, for a gift with a registered recipient who is not the payer, `GIFT_RECEIVED` to the recipient. */
    suspend fun paid(conn: SqlClient, order: MarketOrder) {
        if (order.source == OrderSource.RENEWAL && order.totalPrice <= 0) return
        if (silenced(conn, order)) return

        val payer = payerEmail(conn, order)

        outbox.enqueue(conn, MailKind.ORDER_CONFIRMATION, MailRefType.ORDER, order.id, "", order.id, order.userId, payer, localeOf(order))

        if (!order.isGift) return

        val recipientId = order.recipientUserId ?: return
        val recipient = users?.emailOf(recipientId, conn)?.trim().orEmpty()

        if (recipient.isEmpty() || recipient.equals(payer.trim(), ignoreCase = true)) return

        outbox.enqueue(conn, MailKind.GIFT_RECEIVED, MailRefType.ORDER, order.id, "", order.id, recipientId, recipient, localeOf(order))
    }

    override suspend fun received(conn: SqlConnection, order: MarketOrder) = queueReceived(conn, order)

    /** O3 or the attempt reaching `PROCESSING`: only when no bank transfer instructions went out for the order already. */
    private suspend fun queueReceived(conn: SqlClient, order: MarketOrder) {
        if (silenced(conn, order)) return

        val instructed = mailOutbox.getByOrderId(order.id, conn).any { it.kind == MailKind.BANK_TRANSFER_INSTRUCTIONS && it.status != MailStatus.SKIPPED }

        if (instructed) return

        outbox.enqueue(conn, MailKind.ORDER_RECEIVED, MailRefType.ORDER, order.id, "", order.id, order.userId, payerEmail(conn, order), localeOf(order))
    }

    override suspend fun processing(conn: SqlConnection, order: MarketOrder, attempt: MarketPayment) = queueReceived(conn, order)

    override suspend fun instructions(conn: SqlConnection, order: MarketOrder, attempt: MarketPayment, start: JsonObject) {
        val source = start.getJsonObject("instructions") ?: return
        val fields = JsonArray()

        for (f in source.getJsonArray("fields") ?: JsonArray()) {
            if (f is JsonObject) fields.add(JsonObject().put("label", f.getString("label")).put("value", f.getString("value")))
        }

        val params = JsonObject().put("instructions", JsonObject().put("body", source.getString("body")).put("fields", fields)).put("expiresAt", start.getValue("expiresAt"))

        outbox.enqueue(
            conn, MailKind.BANK_TRANSFER_INSTRUCTIONS, MailRefType.PAYMENT, attempt.id, "", order.id, order.userId, payerEmail(conn, order), localeOf(order), params
        )
    }

    /**
     * `ORDER_DELIVERED` (12 section 4.1): the order was paid at least `mailOrderDeliveredDelayMinutes` ago (the delivery was visibly deferred, so
     * the confirmation did not already say it), is `COMPLETED` or `PARTIALLY_REFUNDED`, and ships nothing physical or has a non-physical line.
     */
    override suspend fun fulfilled(conn: SqlClient, order: MarketOrder) {
        val paidAt = order.paidAt ?: return

        if (silenced(conn, order)) return

        if (order.status != OrderStatus.COMPLETED && order.status != OrderStatus.PARTIALLY_REFUNDED) return
        if (clock.now() - paidAt < config().mailOrderDeliveredDelayMinutes * MINUTE_MS) return

        if (order.requiresShipping) {
            val lines = orderItems.getByOrderIds(listOf(order.id), conn).filter { it.kind != OrderItemKind.BUNDLE }

            if (lines.none { !it.physical }) return
        }

        outbox.enqueue(conn, MailKind.ORDER_DELIVERED, MailRefType.ORDER, order.id, "", order.id, order.userId, payerEmail(conn, order), localeOf(order))
    }

    /**
     * A manual order the admin created with `sendMail = false` (06 section 14.3) queues no order mail at all: the flag lives in the `data` of the
     * `CREATED` row ([ManualFlags], the same read as `OrderService.skippedByManualFlags`). `BANK_TRANSFER_INSTRUCTIONS` is deliberately not asked:
     * it is a service mail (12 section 4.2 step 2), the payer of a `markPaid = false` order cannot pay without it. Every other order costs no read.
     */
    private suspend fun silenced(conn: SqlClient, order: MarketOrder): Boolean {
        if (order.source != OrderSource.PANEL) return false

        val created = orderEvents.getByOrderId(order.id, conn).firstOrNull { it.type == OrderEventType.CREATED }

        return !ManualFlags.of(created?.data).sendMail
    }

    private suspend fun payerEmail(conn: SqlClient, order: MarketOrder): String =
        order.email?.takeIf { it.isNotBlank() } ?: order.userId?.let { users?.emailOf(it, conn) }.orEmpty()

    private fun localeOf(order: MarketOrder): String = order.locale?.takeIf { it.isNotBlank() } ?: defaultLocale()

    companion object {
        private const val MINUTE_MS = 60_000L
    }
}

/**
 * The `QueueMail` effect of O2 / O4 for [OrderService]: wraps the [ForeignEffects] it is given so that `ORDER_CONFIRMATION` (and the
 * `GIFT_RECEIVED` of a gift) go to [OrderMails] and every other effect still goes to [next]. The order is read again, because the
 * `LockedOrder` was read before `StampPaid`.
 */
class MailEffects(
    private val mails: OrderMails,
    private val orders: MarketOrderDao,
    private val next: ForeignEffects = ForeignEffects.PENDING_SLICES
) : ForeignEffects {
    override suspend fun apply(conn: SqlConnection, locked: LockedOrder, effect: OrderEffect) {
        if (effect !is OrderEffect.QueueMail) return next.apply(conn, locked, effect)

        val order = orders.getById(locked.order.id, conn) ?: error("order ${locked.order.id} vanished inside its transaction")

        when (effect.kind) {
            MailKind.ORDER_CONFIRMATION.name -> mails.paid(conn, order)
            else -> next.apply(conn, locked, effect)
        }
    }
}
