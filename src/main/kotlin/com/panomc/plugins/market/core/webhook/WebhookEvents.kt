package com.panomc.plugins.market.core.webhook

import io.vertx.core.json.JsonArray
import java.util.UUID

/** Event names of store webhooks (01 section 9.3, 08 section 15.1). */
object WebhookEvents {
    const val ORDER_PAID = "order.paid"
    const val ORDER_REFUNDED = "order.refunded"
    const val ORDER_CHARGEBACK = "order.chargeback"
    const val ORDER_CHARGEBACK_WON = "order.chargeback.won"
    const val SUBSCRIPTION_STARTED = "subscription.started"
    const val SUBSCRIPTION_RENEWED = "subscription.renewed"
    const val SUBSCRIPTION_CANCELLED = "subscription.cancelled"
    const val SUBSCRIPTION_EXPIRED = "subscription.expired"
    const val SHIPMENT_SHIPPED = "shipment.shipped"
    const val SHIPMENT_DELIVERED = "shipment.delivered"
    const val TEST_PING = "test.ping"

    /** Product `WEBHOOK` action events: never subscribable by an endpoint, never matched by `"*"`. */
    val ACTION_EVENTS = listOf("action.grant", "action.renew", "action.expire", "action.revoke")

    /** The names an endpoint may list in `events` (besides `"*"`). */
    val SUBSCRIBABLE: List<String> = listOf(
        ORDER_PAID, ORDER_REFUNDED, ORDER_CHARGEBACK, ORDER_CHARGEBACK_WON,
        SUBSCRIPTION_STARTED, SUBSCRIPTION_RENEWED, SUBSCRIPTION_CANCELLED, SUBSCRIPTION_EXPIRED,
        SHIPMENT_SHIPPED, SHIPMENT_DELIVERED, TEST_PING
    )

    const val WILDCARD = "*"

    fun isSubscribable(name: String): Boolean = name in SUBSCRIBABLE

    /**
     * `true` when the endpoint's `events` JSON list contains [event] or `"*"`. A list that is not a JSON array of
     * strings matches nothing (an unreadable subscription must never become "everything"). `"*"` does not cover
     * `test.ping` or the `action.*` events.
     */
    fun matches(eventsJson: String, event: String): Boolean {
        val names = try {
            JsonArray(eventsJson).list.map { it as? String ?: return false }
        } catch (e: Exception) {
            return false
        }
        if (event in names) return true
        return WILDCARD in names && event != TEST_PING && event !in ACTION_EVENTS
    }

    /**
     * The deterministic event id (08 section 15.1): the same transition replayed for the same endpoint gives the same
     * id, so the unique index turns the second insert into a no-op. It is also the receiver's de-duplication key.
     */
    fun eventId(event: String, subjectKey: String, endpointId: Long): String =
        UUID.nameUUIDFromBytes("$event:$subjectKey:$endpointId".toByteArray(Charsets.UTF_8)).toString()

    /** The id of a product `WEBHOOK` action delivery (08 section 7.3). */
    fun actionEventId(deliveryId: Long): String =
        UUID.nameUUIDFromBytes("action:$deliveryId".toByteArray(Charsets.UTF_8)).toString()
}
