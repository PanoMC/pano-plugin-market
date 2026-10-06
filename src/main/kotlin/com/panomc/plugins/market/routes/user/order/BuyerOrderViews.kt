package com.panomc.plugins.market.routes.user.order

import com.panomc.platform.error.PageNotFound
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.util.MoneyUtil
import com.panomc.plugins.market.util.OrderStatus
import com.panomc.plugins.market.util.Paging
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

/**
 * The buyer's own order and entitlement lists (04 section 4): `GET /me/orders` and `GET /me/entitlements`. Read only, every query is bound to the caller's id,
 * so another account's rows can never appear.
 *
 * `GET /me/orders` lists the orders the caller placed and the gifts the caller received (`received: true`). A received gift is only listed once it can no longer
 * be abandoned: an order that is still `PENDING`, `FAILED`, `CANCELLED` or `EXPIRED` belongs to the buyer alone (the recipient must not see, or be told about,
 * a gift that was never paid). Amounts are decimal values of the order currency (x100 minor units divided by 100, like every money value of the API).
 */
class BuyerOrderViews(private val prefix: () -> String) {
    private fun table(name: String) = "`${prefix()}$name`"

    /** `status` as a csv of order statuses; an unknown name is `400 INVALID_REQUEST_VALUE` (never a silent empty list). */
    fun parseStatuses(raw: String?): Set<OrderStatus> {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return emptySet()

        return text.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.map { name ->
            OrderStatus.values().firstOrNull { it.name == name } ?: throw RequestValueException("status", "UNKNOWN_STATUS")
        }.toSet()
    }

    /** `orders[{publicId, number, status, fulfillmentStatus, shippingStatus, total, currency, createdAt, paidAt, itemNames[], isGift, recipientUsername, received}]`, `orderCount`, `totalPage`. */
    suspend fun orders(userId: Long, window: Paging.Window, statuses: Set<OrderStatus>, sqlClient: SqlClient): JsonObject {
        val hidden = HIDDEN_FROM_RECIPIENT.joinToString(", ") { "'${it.name}'" }
        val own = "o.`userId` = ?"
        val received = "(o.`recipientUserId` = ? AND (o.`userId` IS NULL OR o.`userId` <> ?) AND o.`status` NOT IN ($hidden))"
        val statusFilter = if (statuses.isEmpty()) "" else " AND o.`status` IN (${statuses.joinToString(", ") { "'${it.name}'" }})"
        val where = "($own OR $received)$statusFilter"
        val args = Tuple.of(userId, userId, userId)

        val count = sqlClient.preparedQuery("SELECT COUNT(*) AS n FROM ${table("market_order")} o WHERE $where").execute(args).coAwait().first().getLong("n")
        val totalPage = Paging.totalPages(count, window.pageSize)

        if (Paging.isBeyondLast(window.page, totalPage)) throw PageNotFound()

        val rows = sqlClient.preparedQuery(
            "SELECT o.`id`, o.`publicId`, o.`status`, o.`fulfillmentStatus`, o.`shippingStatus`, o.`totalPrice`, o.`currency`, o.`createdAt`, o.`paidAt`, o.`isGift`, " +
                "o.`recipientUsername`, o.`userId`, o.`recipientUserId` FROM ${table("market_order")} o WHERE $where ORDER BY o.`createdAt` DESC, o.`id` DESC LIMIT ? OFFSET ?"
        ).execute(Tuple.of(userId, userId, userId, window.pageSize, window.offset)).coAwait().toList()

        val names = itemNames(rows.map { it.getLong("id") }, sqlClient)

        return JsonObject()
            .put("orders", JsonArray(rows.map { order(it, userId, names[it.getLong("id")].orEmpty()) }))
            .put("orderCount", count)
            .put("totalPage", totalPage)
    }

    private suspend fun itemNames(orderIds: List<Long>, sqlClient: SqlClient): Map<Long, List<String>> {
        if (orderIds.isEmpty()) return emptyMap()

        val marks = orderIds.joinToString(", ") { "?" }
        val rows = sqlClient.preparedQuery(
            "SELECT `orderId`, `productName` FROM ${table("market_order_item")} WHERE `orderId` IN ($marks) AND `kind` IN ('PRODUCT', 'BUNDLE', 'CREDIT_TOPUP') ORDER BY `id` ASC"
        ).execute(Tuple.from(orderIds)).coAwait()

        return rows.groupBy({ it.getLong("orderId") }, { it.getString("productName") })
    }

    private fun order(row: Row, userId: Long, itemNames: List<String>): JsonObject {
        val isReceived = row.getLong("recipientUserId") == userId && row.getLong("userId") != userId

        return JsonObject()
            .put("publicId", row.getString("publicId"))
            .put("number", row.getLong("id"))
            .put("status", row.getString("status"))
            .put("fulfillmentStatus", row.getString("fulfillmentStatus"))
            .put("shippingStatus", row.getString("shippingStatus"))
            .put("total", MoneyUtil.toDecimal(row.getLong("totalPrice")))
            .put("currency", row.getString("currency"))
            .put("createdAt", row.getLong("createdAt"))
            .put("paidAt", row.getLong("paidAt"))
            .put("itemNames", JsonArray(itemNames))
            .put("isGift", row.getBoolean("isGift") == true)
            .put("recipientUsername", row.getString("recipientUsername"))
            .put("received", isReceived)
    }

    /**
     * `GET /me/entitlements`: `entitlements[{id, productId, productName, variantName, status, startsAt, expiresAt, subscriptionId, orderPublicId}]`, newest first.
     * q `active=true`: only `ACTIVE` rows that have not ended at this moment ([now], a permanent row has no end); `active=false` or none: every row.
     * An entitlement belongs to its owner (`userId`), so a gift is listed for the recipient, never for the buyer.
     */
    suspend fun entitlements(userId: Long, activeOnly: Boolean, now: Long, sqlClient: SqlClient): JsonObject {
        val active = if (activeOnly) " AND e.`status` = 'ACTIVE' AND (e.`expiresAt` IS NULL OR e.`expiresAt` > ?)" else ""
        val args = if (activeOnly) Tuple.of(userId, now) else Tuple.of(userId)
        val rows = sqlClient.preparedQuery(
            "SELECT e.`id`, e.`productId`, e.`status`, e.`startsAt`, e.`expiresAt`, e.`subscriptionId`, e.`variantId`, " +
                "i.`productName` AS itemProductName, i.`variantName` AS itemVariantName, p.`name` AS productName, v.`name` AS variantName, o.`publicId` AS orderPublicId " +
                "FROM ${table("market_entitlement")} e " +
                "LEFT JOIN ${table("market_order")} o ON o.`id` = e.`orderId` " +
                "LEFT JOIN ${table("market_order_item")} i ON i.`id` = e.`orderItemId` " +
                "LEFT JOIN ${table("market_product")} p ON p.`id` = e.`productId` " +
                "LEFT JOIN ${table("market_product_variant")} v ON v.`id` = e.`variantId` AND e.`variantId` > 0 " +
                "WHERE e.`userId` = ?$active ORDER BY e.`createdAt` DESC, e.`id` DESC LIMIT 500"
        ).execute(args).coAwait().toList()

        return JsonObject().put(
            "entitlements",
            JsonArray(
                rows.map {
                    JsonObject()
                        .put("id", it.getLong("id"))
                        .put("productId", it.getLong("productId"))
                        // the name the buyer paid for (the order snapshot) wins over the live catalogue, which may have been renamed or deleted since
                        .put("productName", it.getString("itemProductName") ?: it.getString("productName"))
                        .put("variantName", it.getString("itemVariantName") ?: it.getString("variantName"))
                        .put("status", it.getString("status"))
                        .put("startsAt", it.getLong("startsAt"))
                        .put("expiresAt", it.getLong("expiresAt"))
                        .put("subscriptionId", it.getLong("subscriptionId"))
                        .put("orderPublicId", it.getString("orderPublicId"))
                }
            )
        )
    }

    companion object {
        /** The statuses of a gift that its recipient never sees (abandoned or unpaid orders). */
        val HIDDEN_FROM_RECIPIENT = listOf(OrderStatus.PENDING, OrderStatus.FAILED, OrderStatus.CANCELLED, OrderStatus.EXPIRED)
    }
}
