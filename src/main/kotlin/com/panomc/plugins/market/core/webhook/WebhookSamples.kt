package com.panomc.plugins.market.core.webhook

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.math.BigDecimal

/**
 * The example `data` the panel's webhook page shows for each store event (doc 06 section 4.3, `WebhookEventType.sample`). The shapes are the
 * ones [EventPayloads] and the emitters of the refund, dispute, subscription and shipping slices build; the values are made up. Pure.
 */
object WebhookSamples {
    private fun money(text: String) = BigDecimal(text)

    private fun order(status: String = "PAID") = JsonObject()
        .put("id", 1042).put("publicId", "k3mqz8w2").put("url", "https://example.com/store/orders/k3mqz8w2").put("status", status)
        .put("source", "STORE").put("createdAt", 1_760_000_000_000L).put("paidAt", 1_760_000_060_000L).put("currency", "USD")
        .put("subtotal", money("19.99")).put("discountTotal", money("0.00")).put("shippingTotal", money("0.00")).put("vatTotal", money("0.00"))
        .put("total", money("19.99")).put("creditAmount", money("0.00")).put("gatewayAmount", money("19.99")).put("refundedTotal", money("0.00"))
        .put("paymentMethodId", "stripe").put("couponCode", null as String?).put("creatorCode", null as String?).put("isGift", false)
        .put("requiresShipping", false)

    private fun buyer() = JsonObject().put("username", "Steve").put("userId", 7).put("uuid", "8667ba71-b85a-4004-af54-457a9734eed7").put("email", "steve@example.com")

    private fun recipient() = JsonObject().put("username", "Steve").put("userId", 7).put("uuid", "8667ba71-b85a-4004-af54-457a9734eed7")

    private fun items() = JsonArray().add(
        JsonObject()
            .put("id", 2001).put("parentItemId", null as Long?).put("productId", 12).put("productName", "VIP Rank").put("slug", "vip-rank").put("kind", "RANK")
            .put("variantId", null as Long?).put("variantName", null as String?).put("sku", "VIP-30").put("quantity", 1)
            .put("unitPrice", money("19.99")).put("lineTotal", money("19.99")).put("fieldValues", null as JsonObject?)
            .put("targetServerId", 1).put("targetServerName", "Survival").put("expiresAt", null as Long?)
    )

    private fun orderData() = JsonObject().put("order", order()).put("buyer", buyer()).put("recipient", recipient()).put("items", items())

    private fun subscription(status: String) = JsonObject()
        .put("id", 31).put("status", status).put("currentPeriodEnd", 1_762_592_000_000L).put("cancelAtPeriodEnd", false)

    private fun subscriptionData(withOrder: Boolean): JsonObject =
        JsonObject().put("subscription", subscription("ACTIVE")).also {
            if (withOrder) it.put("order", JsonObject().put("id", 1042).put("publicId", "k3mqz8w2").put("total", money("19.99")).put("currency", "USD"))
        }

    private fun shipmentData(delivered: Boolean) = JsonObject()
        .put("order", JsonObject().put("id", 1042).put("publicId", "k3mqz8w2").put("playerUsername", "Steve").put("recipientUsername", "Steve"))
        .put(
            "shipment",
            JsonObject().put("id", 77).put("status", if (delivered) "DELIVERED" else "SHIPPED").put("carrierName", "Manual").put("trackingNumber", "TRK123456")
                .put("trackingUrl", "https://tracking.example.com/TRK123456").put("shippedAt", 1_760_100_000_000L).put("deliveredAt", if (delivered) 1_760_300_000_000L else null)
                .put("items", JsonArray().add(JsonObject().put("itemId", 2001).put("productName", "Sticker pack").put("quantity", 1)))
        )

    /** The sample `data` of [event] (a name from [WebhookEvents.SUBSCRIBABLE]), or `null` for an event without one. */
    fun of(event: String): JsonObject? = when (event) {
        WebhookEvents.ORDER_PAID -> orderData()
        WebhookEvents.ORDER_REFUNDED -> orderData().put(
            "refund",
            JsonObject().put("id", 5).put("amount", money("19.99")).put("gatewayAmount", money("19.99")).put("creditAmount", money("0.00")).put("currency", "USD")
                .put("reason", "Requested by the buyer").put("origin", "ADMIN").put("full", true).put("revoked", true).put("items", JsonArray())
        )
        WebhookEvents.ORDER_CHARGEBACK -> orderData().put("dispute", dispute("OPEN"))
        WebhookEvents.ORDER_CHARGEBACK_WON -> orderData().put("dispute", dispute("WON"))
        WebhookEvents.SUBSCRIPTION_STARTED, WebhookEvents.SUBSCRIPTION_RENEWED -> subscriptionData(withOrder = true)
        WebhookEvents.SUBSCRIPTION_CANCELLED, WebhookEvents.SUBSCRIPTION_EXPIRED -> subscriptionData(withOrder = false)
        WebhookEvents.SHIPMENT_SHIPPED -> shipmentData(delivered = false)
        WebhookEvents.SHIPMENT_DELIVERED -> shipmentData(delivered = true)
        else -> null
    }

    private fun dispute(status: String) =
        JsonObject().put("id", 3).put("status", status).put("amount", money("19.99")).put("currency", "USD").put("reason", "fraudulent").put("gatewayDisputeId", "dp_1Nxyz")
}
