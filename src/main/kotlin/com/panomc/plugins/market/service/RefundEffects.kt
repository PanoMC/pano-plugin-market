package com.panomc.plugins.market.service

import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.core.webhook.EventPayloads
import com.panomc.plugins.market.db.dao.MarketCreatorEarningDao
import com.panomc.plugins.market.db.model.CreatorEarningState
import com.panomc.plugins.market.db.model.MailKind
import com.panomc.plugins.market.db.model.MailRefType
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketRefund
import com.panomc.plugins.market.db.model.MarketRefundItem
import com.panomc.plugins.market.service.platform.UserDirectory
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

/**
 * What O10 hands to the services of other areas (21 section 3.4 steps 8 to 10), all inside the refund transaction: they only write rows (creator reversal,
 * credit note, mail outbox row, webhook row, subscription ending). [NONE] does nothing; [StandardRefundEffects] is the production composition. A failure
 * of one of them rolls the whole O10 back (a refund the gateway confirmed is then applied again by the reconcile job or the gateway's redelivery).
 */
interface RefundEffects {
    /** 21 section 7.3: the creator earning of the order is reversed in proportion to what was refunded ([fully]: the whole earning). */
    suspend fun creatorReversal(conn: SqlConnection, order: MarketOrder, refundedTotalAfter: Long, fully: Boolean)

    /** `InvoiceService.issueCreditNote` (12 section 6.1): called after the refund row was written `SUCCEEDED`. */
    suspend fun creditNote(conn: SqlConnection, order: MarketOrder, items: List<MarketOrderItem>, refundId: Long)

    /** The `ORDER_REFUNDED` mail row (12 section 3). */
    suspend fun mail(conn: SqlConnection, order: MarketOrder, refund: MarketRefund)

    /** The store webhook `order.refunded` (08 section 15). */
    suspend fun webhook(conn: SqlConnection, order: MarketOrder, refund: MarketRefund, lines: List<MarketRefundItem>, full: Boolean, revoked: Boolean)

    /** `SubscriptionService.onOrderRefunded` (09 section 10.4); the subscription slices install the real one. */
    suspend fun subscription(conn: SqlConnection, order: MarketOrder, refund: MarketRefund)

    /**
     * Whether [order] belongs to an older period of a subscription than the one being paid for (`SubscriptionService.isOlderPeriod`, 09 section 10.4, 21 section 3.2
     * step 4): a refund of it never takes the goods back (`revoke` is forced to `false`, the subscription keeps its entitlement) and the preview warns
     * `OLDER_SUBSCRIPTION_PERIOD`.
     */
    suspend fun olderSubscriptionPeriod(conn: io.vertx.sqlclient.SqlClient, order: MarketOrder): Boolean = false

    companion object {
        val NONE: RefundEffects = object : RefundEffects {
            override suspend fun creatorReversal(conn: SqlConnection, order: MarketOrder, refundedTotalAfter: Long, fully: Boolean) = Unit
            override suspend fun creditNote(conn: SqlConnection, order: MarketOrder, items: List<MarketOrderItem>, refundId: Long) = Unit
            override suspend fun mail(conn: SqlConnection, order: MarketOrder, refund: MarketRefund) = Unit
            override suspend fun webhook(conn: SqlConnection, order: MarketOrder, refund: MarketRefund, lines: List<MarketRefundItem>, full: Boolean, revoked: Boolean) = Unit
            override suspend fun subscription(conn: SqlConnection, order: MarketOrder, refund: MarketRefund) = Unit
        }
    }
}

/** A panel alert of the refund flow (`OVER_REFUND`, `REVOKE_TIMEOUT`, `REVOKE_FAILED`): raised after the transaction that found it committed. */
fun interface RefundAlerts {
    suspend fun alert(orderId: Long, code: String, data: JsonObject)

    companion object {
        val LOG_ONLY = RefundAlerts { orderId, code, data -> org.slf4j.LoggerFactory.getLogger(RefundAlerts::class.java).warn("refund alert {} on order {}: {}", code, orderId, data.encode()) }
    }
}

/**
 * The production composition of [RefundEffects]: the creator reversal on the earning DAO, the credit note of the invoice service, the mail outbox and the
 * webhook queue. Every collaborator is optional so a host without one (mail not available, invoices off) still refunds.
 */
class StandardRefundEffects(
    private val clock: Clock,
    private val earnings: MarketCreatorEarningDao,
    private val tablePrefix: () -> String,
    private val invoices: InvoiceService? = null,
    private val mailOutbox: MailOutboxService? = null,
    private val users: UserDirectory? = null,
    private val webhooks: WebhookService? = null,
    private val defaultLocale: () -> String = { "en-US" },
    private val subscriptionEnding: suspend (SqlConnection, MarketOrder, MarketRefund) -> Unit = { _, _, _ -> },
    private val olderPeriod: suspend (io.vertx.sqlclient.SqlClient, MarketOrder) -> Boolean = { _, _ -> false }
) : RefundEffects {
    private fun table(name: String) = "`${tablePrefix()}$name`"

    /**
     * 21 section 7.3: `target` is the earning's `amount` when the order becomes `REFUNDED`, else `floor(amount x refundedTotal / totalPrice)`; `delta = target -
     * reversedAmount` (not positive: skipped); `reversedAmount += delta`, the code's cached `earnings -= delta`; a fully reversed row is `REVERSED` unless it is
     * `PAID` (then the reversal shows up as a negative available balance).
     */
    override suspend fun creatorReversal(conn: SqlConnection, order: MarketOrder, refundedTotalAfter: Long, fully: Boolean) {
        for (earning in earnings.getByOrderId(order.id, conn).sortedBy { it.id }) {
            val target = if (fully || order.totalPrice <= 0L || refundedTotalAfter >= order.totalPrice) earning.amount
            else java.math.BigInteger.valueOf(earning.amount).multiply(java.math.BigInteger.valueOf(refundedTotalAfter)).divide(java.math.BigInteger.valueOf(order.totalPrice)).toLong()
            val delta = target - earning.reversedAmount

            if (delta <= 0L) continue

            if (!earnings.addReversed(earning.id, delta, conn)) continue

            conn.preparedQuery("UPDATE ${table("market_creator_code")} SET `earnings` = `earnings` - ? WHERE `id` = ?").execute(Tuple.of(delta, earning.creatorCodeId)).coAwait()

            if (earning.reversedAmount + delta >= earning.amount) {
                // a PAID row stays PAID; PENDING and AVAILABLE rows end REVERSED
                if (!earnings.transition(earning.id, CreatorEarningState.PENDING, CreatorEarningState.REVERSED, conn)) {
                    earnings.transition(earning.id, CreatorEarningState.AVAILABLE, CreatorEarningState.REVERSED, conn)
                }
            }
        }
    }

    override suspend fun creditNote(conn: SqlConnection, order: MarketOrder, items: List<MarketOrderItem>, refundId: Long) {
        invoices?.issueCreditNote(conn, order, items, refundId)
    }

    override suspend fun mail(conn: SqlConnection, order: MarketOrder, refund: MarketRefund) {
        val outbox = mailOutbox ?: return
        val recipient = order.email?.takeIf { it.isNotBlank() } ?: order.userId?.let { users?.emailOf(it, conn) }.orEmpty()

        if (recipient.isBlank()) return

        outbox.enqueue(
            conn, MailKind.ORDER_REFUNDED, MailRefType.REFUND, refund.id, "", order.id, order.userId, recipient, order.locale ?: defaultLocale(),
            JsonObject().put("refundId", refund.id).put("amount", refund.amount).put("gatewayAmount", refund.gatewayAmount).put("creditAmount", refund.creditAmount)
                .put("creditValue", refund.creditValue).put("currency", refund.currency)
        )
    }

    override suspend fun webhook(conn: SqlConnection, order: MarketOrder, refund: MarketRefund, lines: List<MarketRefundItem>, full: Boolean, revoked: Boolean) {
        val queue = webhooks ?: return
        val body = JsonObject()
            .put("id", refund.id).put("amount", EventPayloads.money(refund.amount)).put("gatewayAmount", EventPayloads.money(refund.gatewayAmount))
            .put("creditAmount", EventPayloads.money(refund.creditAmount)).put("currency", refund.currency).put("reason", refund.reason)
            .put("origin", refund.origin.name).put("full", full).put("revoked", revoked)
            .put(
                "items",
                JsonArray(lines.map { JsonObject().put("orderItemId", it.orderItemId).put("quantity", it.quantity).put("amount", EventPayloads.money(it.amount)) })
            )

        queue.emitOrderRefunded(conn, order.id, refund.id, body)
    }

    override suspend fun subscription(conn: SqlConnection, order: MarketOrder, refund: MarketRefund) = subscriptionEnding(conn, order, refund)

    override suspend fun olderSubscriptionPeriod(conn: io.vertx.sqlclient.SqlClient, order: MarketOrder): Boolean = olderPeriod(conn, order)
}
