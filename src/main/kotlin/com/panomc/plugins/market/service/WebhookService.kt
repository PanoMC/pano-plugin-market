package com.panomc.plugins.market.service

import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.api.webhook.WebhookPublisher
import com.panomc.plugins.market.core.webhook.EventPayloads
import com.panomc.plugins.market.core.webhook.StoreInfo
import com.panomc.plugins.market.core.webhook.WebhookEvents
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection

/**
 * Store webhooks on core's system (open front-end plan, doc 06 section 4): market composes the `data` of its events
 * ([EventPayloads], unchanged builders) and hands them to core's [WebhookPublisher], which writes one row per matching
 * endpoint **on the connection given here** (the business transaction: the rows commit or roll back with it, a replay of
 * the same transition inserts nothing) and which core's dispatcher sends, retries and logs.
 *
 * Event names are the market's own (`order.paid`); core adds the plugin's namespace (`market.order.paid`). The subject of
 * a row in core's delivery log is `order:<id>`.
 *
 * Nothing here sends, claims or stores an endpoint: those moved to core (`WebhookService`, `WebhookDispatcher`,
 * `WebhookEndpointService`). The `testMode` flag of an order travels as the extra envelope key `testMode`.
 */
class WebhookService(
    private val publisher: () -> WebhookPublisher,
    private val plugin: PanoPlugin,
    private val orders: MarketOrderDao,
    private val orderItems: MarketOrderItemDao,
    private val store: () -> StoreInfo,
    /** Platform UUID of a player for the payload (`buyer.uuid`); `null` when unknown. Wired by the platform seam. */
    private val uuidOf: suspend (userId: Long?, username: String) -> String? = { _, _ -> null }
) {
    /**
     * One row per enabled endpoint that subscribes to [event]; returns how many rows were new. [subjectKey] makes the event id
     * deterministic. [data] is the `data` object of the envelope; [orderId] becomes the subject reference `order:<id>`.
     */
    suspend fun emit(conn: SqlConnection, event: String, subjectKey: String, orderId: Long?, data: JsonObject, testMode: Boolean = false): Int =
        publisher().publish(
            plugin, event, subjectKey, data, conn,
            subjectRef = orderId?.let { subjectRefOf(it) }, extra = JsonObject().put("testMode", testMode)
        )

    /**
     * `order.paid` for [orderId] (O2 / O4): loads the order and its lines **on [conn]** (so it sees the status the
     * transition just wrote) and emits. Nothing is read when no endpoint listens.
     */
    suspend fun emitOrderPaid(conn: SqlConnection, orderId: Long): Int {
        if (!publisher().hasListeners(plugin, WebhookEvents.ORDER_PAID, conn)) return 0

        val order = orders.getById(orderId, conn) ?: throw IllegalStateException("order $orderId does not exist")
        val items = orderItems.getByOrderIds(listOf(orderId), conn)
        val data = EventPayloads.orderPaid(
            order, items, store(),
            buyerUuid = uuidOf(order.userId, order.playerUsername),
            recipientUuid = uuidOf(EventPayloads.recipientUserId(order), EventPayloads.recipientName(order))
        )

        return emit(conn, WebhookEvents.ORDER_PAID, orderId.toString(), orderId, data, order.testMode)
    }

    /**
     * `order.refunded` for [orderId] (O10): the order, buyer, recipient and lines as of the commit of the transition plus the [refund] object
     * the refund service built. The subject key is the refund id, so a replayed O10 inserts nothing.
     */
    suspend fun emitOrderRefunded(conn: SqlConnection, orderId: Long, refundId: Long, refund: JsonObject): Int {
        if (!publisher().hasListeners(plugin, WebhookEvents.ORDER_REFUNDED, conn)) return 0

        val order = orders.getById(orderId, conn) ?: throw IllegalStateException("order $orderId does not exist")
        val items = orderItems.getByOrderIds(listOf(orderId), conn)
        val data = EventPayloads.orderPaid(
            order, items, store(),
            buyerUuid = uuidOf(order.userId, order.playerUsername),
            recipientUuid = uuidOf(EventPayloads.recipientUserId(order), EventPayloads.recipientName(order))
        ).put("refund", refund)

        return emit(conn, WebhookEvents.ORDER_REFUNDED, refundId.toString(), orderId, data, order.testMode)
    }

    /**
     * `order.chargeback` (O11, [won] false) or `order.chargeback.won` (O12, [won] true) for [orderId]: the order, buyer, recipient and lines as of
     * the commit of the transition plus the [dispute] object the dispute service built. The subject key is the dispute id, so a replayed transition
     * inserts nothing.
     */
    suspend fun emitOrderDispute(conn: SqlConnection, orderId: Long, disputeId: Long, won: Boolean, dispute: JsonObject): Int {
        val event = if (won) WebhookEvents.ORDER_CHARGEBACK_WON else WebhookEvents.ORDER_CHARGEBACK

        if (!publisher().hasListeners(plugin, event, conn)) return 0

        val order = orders.getById(orderId, conn) ?: throw IllegalStateException("order $orderId does not exist")
        val items = orderItems.getByOrderIds(listOf(orderId), conn)
        val data = EventPayloads.orderPaid(
            order, items, store(),
            buyerUuid = uuidOf(order.userId, order.playerUsername),
            recipientUuid = uuidOf(EventPayloads.recipientUserId(order), EventPayloads.recipientName(order))
        ).put("dispute", dispute)

        return emit(conn, event, disputeId.toString(), orderId, data, order.testMode)
    }

    companion object {
        /** `order:<id>`: how the delivery log of core names the order a row was written for. */
        fun subjectRefOf(orderId: Long): String = "order:$orderId"
    }
}
