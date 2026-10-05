package com.panomc.plugins.market.core.webhook

import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.math.BigDecimal

/** The store identity of the envelope (`store` object). */
class StoreInfo(val name: String, val url: String)

/**
 * JSON bodies of store webhooks (08 section 15.4). Money is a decimal number in the stated currency (the x100 columns
 * divided by 100, exact), time is epoch milliseconds, absent values are `null`. Billing data, addresses, IP addresses
 * and tokens are never included. Adding keys is not a breaking change; removing or renaming one bumps [API_VERSION].
 *
 * The shared objects ([order], [buyer], [recipient], [items]) are public so that the slices that emit the other events
 * (refund, dispute, subscription, shipment) compose their `data` from the same pieces.
 */
object EventPayloads {
    const val API_VERSION = 1

    /** The envelope; its `id` is the `eventId` of the delivery row. */
    fun envelope(eventId: String, event: String, createdAtMs: Long, testMode: Boolean, store: StoreInfo, data: JsonObject): JsonObject =
        JsonObject()
            .put("id", eventId)
            .put("event", event)
            .put("createdAt", createdAtMs)
            .put("apiVersion", API_VERSION)
            .put("testMode", testMode)
            .put("store", JsonObject().put("name", store.name).put("url", store.url))
            .put("data", data)

    fun money(minor: Long): BigDecimal = BigDecimal.valueOf(minor, 2)

    fun orderUrl(store: StoreInfo, publicId: String?): String? =
        publicId?.let { "${store.url.trimEnd('/')}/store/order/$it" }

    fun order(o: MarketOrder, store: StoreInfo): JsonObject = JsonObject()
        .put("id", o.id)
        .put("publicId", o.publicId)
        .put("url", orderUrl(store, o.publicId))
        .put("status", o.status.name)
        .put("source", o.source.name)
        .put("createdAt", o.createdAt)
        .put("paidAt", o.paidAt)
        .put("currency", o.currency)
        .put("subtotal", money(o.subtotal))
        .put("discountTotal", money(o.discountTotal))
        .put("couponDiscount", money(o.couponDiscount))
        .put("creatorDiscount", money(o.creatorDiscount))
        .put("upgradeDiscount", money(o.upgradeDiscount))
        .put("shippingTotal", money(o.shippingTotal))
        .put("paymentFee", money(o.paymentFee))
        .put("vatTotal", money(o.vatTotal))
        .put("total", money(o.totalPrice))
        .put("creditAmount", money(o.creditAmount))
        .put("gatewayAmount", money(o.gatewayAmount))
        .put("refundedTotal", money(o.refundedTotal))
        .put("paymentMethodId", o.paymentMethodId)
        .put("paymentLabel", o.paymentLabel)
        .put("couponCode", o.couponCode)
        .put("creatorCode", o.creatorCode)
        .put("isGift", o.isGift)
        .put("giftMessage", o.giftMessage)
        .put("requiresShipping", o.requiresShipping)

    /** The payer. [uuid] is the platform UUID of the player when known. */
    fun buyer(o: MarketOrder, uuid: String?): JsonObject = JsonObject()
        .put("username", o.playerUsername)
        .put("userId", o.userId)
        .put("uuid", uuid)
        .put("email", o.email)

    fun recipient(o: MarketOrder, uuid: String?): JsonObject = JsonObject()
        .put("username", o.recipientUsername.ifEmpty { o.playerUsername })
        .put("userId", o.recipientUserId ?: o.userId)
        .put("uuid", uuid)

    /**
     * Order lines, bundle children included (with `parentItemId`). [serverNames] maps `targetServerId` to the server
     * name, [expiresAt] maps an item id to the end of its entitlement; both default to `null` in the output.
     */
    fun items(
        items: List<MarketOrderItem>,
        serverNames: Map<Long, String> = emptyMap(),
        expiresAt: Map<Long, Long> = emptyMap()
    ): JsonArray {
        val out = JsonArray()
        for (i in items) {
            out.add(
                JsonObject()
                    .put("id", i.id)
                    .put("parentItemId", i.parentItemId)
                    .put("productId", i.productId)
                    .put("productName", i.productName)
                    .put("slug", snapshotSlug(i.snapshot))
                    .put("kind", i.kind.name)
                    .put("variantId", i.variantId)
                    .put("variantName", i.variantName)
                    .put("sku", i.sku)
                    .put("quantity", i.quantity)
                    .put("unitPrice", money(i.unitPrice))
                    .put("lineTotal", money(i.lineTotal))
                    .put("fieldValues", parseObject(i.fieldValues))
                    .put("targetServerId", i.targetServerId)
                    .put("targetServerName", i.targetServerId?.let { serverNames[it] })
                    .put("expiresAt", expiresAt[i.id])
            )
        }
        return out
    }

    /** `data` of `order.paid`. */
    fun orderPaid(
        o: MarketOrder,
        items: List<MarketOrderItem>,
        store: StoreInfo,
        buyerUuid: String? = null,
        recipientUuid: String? = null,
        serverNames: Map<Long, String> = emptyMap(),
        expiresAt: Map<Long, Long> = emptyMap()
    ): JsonObject = JsonObject()
        .put("order", order(o, store))
        .put("buyer", buyer(o, buyerUuid))
        .put("recipient", recipient(o, recipientUuid))
        .put("items", items(items, serverNames, expiresAt))

    /** `data` of `test.ping`. */
    fun testPing(endpointId: Long): JsonObject = JsonObject().put("message", "ping").put("endpointId", endpointId)

    private fun snapshotSlug(snapshot: String?): String? = parseObject(snapshot)?.getString("slug")

    private fun parseObject(text: String?): JsonObject? =
        if (text.isNullOrBlank()) null else try {
            JsonObject(text)
        } catch (e: Exception) {
            null
        }
}
