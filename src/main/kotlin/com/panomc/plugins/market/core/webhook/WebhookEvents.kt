package com.panomc.plugins.market.core.webhook

import java.util.UUID

/**
 * Event names of store webhooks (01 section 9.3, 08 section 15.1), **without** the source: core adds the plugin's namespace, so
 * `order.paid` is `market.order.paid` on the wire (doc 06 section 4.2). Matching, ids and the endpoint list are core's.
 */
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

    /** Product `WEBHOOK` action events: registered `subscribable = false`, so no endpoint lists them and no wildcard matches them. */
    val ACTION_EVENTS = listOf("action.grant", "action.renew", "action.expire", "action.revoke")

    /** The names an endpoint may subscribe to. */
    val SUBSCRIBABLE: List<String> = listOf(
        ORDER_PAID, ORDER_REFUNDED, ORDER_CHARGEBACK, ORDER_CHARGEBACK_WON,
        SUBSCRIPTION_STARTED, SUBSCRIPTION_RENEWED, SUBSCRIPTION_CANCELLED, SUBSCRIPTION_EXPIRED,
        SHIPMENT_SHIPPED, SHIPMENT_DELIVERED
    )

    /** The id of a product `WEBHOOK` action delivery (08 section 7.3). */
    fun actionEventId(deliveryId: Long): String =
        UUID.nameUUIDFromBytes("action:$deliveryId".toByteArray(Charsets.UTF_8)).toString()

    /** `delivery:<id>`: the `ownerRef` of the direct row of a product action; core's outcome listener hands it back. */
    fun ownerRefOf(deliveryId: Long): String = "delivery:$deliveryId"

    /** The delivery id inside an [ownerRefOf] reference, or `null` for anything else. */
    fun deliveryIdOf(ownerRef: String?): Long? =
        ownerRef?.takeIf { it.startsWith("delivery:") }?.removePrefix("delivery:")?.toLongOrNull()
}
