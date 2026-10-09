package com.panomc.plugins.market.routes.user.subscription

import com.panomc.platform.model.PageRequest
import com.panomc.plugins.market.core.subscription.SubscriptionEndReason
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketSubscriptionDao
import com.panomc.plugins.market.db.dao.MarketSubscriptionRenewalDao
import com.panomc.plugins.market.db.model.MarketSubscription
import com.panomc.plugins.market.db.model.RemoteCancelState
import com.panomc.plugins.market.db.model.SubscriptionMode
import com.panomc.plugins.market.db.model.SubscriptionStatus
import com.panomc.plugins.market.permission.FieldGating
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.util.MoneyUtil
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

/** The filters of `GET /api/panel/market/subscriptions` (09 section 13): `status` (csv), `mode`, `providerId`, `search`. */
class SubscriptionFilter(
    val statuses: Set<SubscriptionStatus> = emptySet(),
    val mode: SubscriptionMode? = null,
    val providerId: String? = null,
    val search: String? = null
)

/** A page of the panel list: the rows as JSON, the number of rows that match and the number of pages. */
class SubscriptionPage(val rows: List<JsonObject>, val count: Long)

/**
 * What the buyer and the panel read of a subscription (09 section 13, 04 sections 4 and 7). Read only; `storedMethod` and `providerData` are never part of a
 * view (they are encrypted secrets), the gateway's own ids are panel-only. [capabilities] answers the provider's capabilities right now (`null` when the
 * provider is not registered): `canResume`, `canManageAtGateway` and the panel's `allowed.retry` depend on them.
 */
class SubscriptionViews(
    private val clock: Clock,
    private val subscriptions: MarketSubscriptionDao,
    private val renewals: MarketSubscriptionRenewalDao,
    private val capabilities: suspend (providerId: String, sqlClient: SqlClient) -> PaymentCapabilities?
) {
    private fun table(name: String) = "`${subscriptions.prefix()}$name`"

    private fun SubscriptionStatus.isOpen() = this == SubscriptionStatus.ACTIVE || this == SubscriptionStatus.PAST_DUE || this == SubscriptionStatus.PAUSED

    private fun SubscriptionStatus.isClosed() = this == SubscriptionStatus.EXPIRED || this == SubscriptionStatus.CANCELLED || this == SubscriptionStatus.COMPLETED

    // ================================================================================================== buyer

    /** `GET /me/subscriptions`: every row of the buyer except `PENDING`, newest first. */
    suspend fun buyerList(userId: Long, client: SqlClient): List<JsonObject> =
        subscriptions.getByUserId(userId, client).filter { it.status != SubscriptionStatus.PENDING }.sortedWith(compareByDescending<MarketSubscription> { it.createdAt }.thenByDescending { it.id })
            .map { buyerRow(it, client) }

    /** The row of the buyer's own subscription after a cancel or resume (09 section 13: both answer the same `subscription` object). */
    suspend fun buyerRow(row: MarketSubscription, client: SqlClient): JsonObject {
        val now = clock.now()
        val caps = if (row.mode == SubscriptionMode.GATEWAY && row.status.isOpen()) capabilities(row.providerId, client) else null
        val end = row.currentPeriodEnd
        val resumable = row.status == SubscriptionStatus.ACTIVE && row.cancelAtPeriodEnd && end != null && now < end &&
            SubscriptionEndReason.parse(row.endReason) == SubscriptionEndReason.BUYER_CANCEL &&
            (row.mode != SubscriptionMode.GATEWAY || (row.remoteCancelState == RemoteCancelState.NONE && caps?.recurringResume == true))

        return JsonObject()
            .put("id", row.id).put("productName", row.productName).put("status", row.status.name).put("mode", row.mode.name)
            .put("price", MoneyUtil.toDecimal(row.price)).put("currency", row.currency).put("intervalUnit", row.intervalUnit.name).put("intervalCount", row.intervalCount)
            .put("currentPeriodEnd", row.currentPeriodEnd).put("graceEndsAt", row.graceEndsAt).put("cancelAtPeriodEnd", row.cancelAtPeriodEnd)
            .put("endReason", row.endReason).put("endedAt", row.endedAt).put("methodLabel", paymentLabelOf(row.id, client)).put("storedMethodLabel", row.storedMethodLabel)
            .put("canCancel", row.status.isOpen()).put("canResume", resumable)
            .put("canManageAtGateway", row.mode == SubscriptionMode.GATEWAY && row.status.isOpen() && caps?.recurringPortal == true)
            .put("renewalOrderPublicId", renewalOrderPublicId(row.id, client))
    }

    /** `paymentLabel` of the newest paid order of the subscription (09 section 13). */
    private suspend fun paymentLabelOf(subscriptionId: Long, client: SqlClient): String? =
        client.preparedQuery("SELECT `paymentLabel` FROM ${table("market_order")} WHERE `subscriptionId` = ? AND `paidAt` IS NOT NULL ORDER BY `paidAt` DESC, `id` DESC LIMIT 1")
            .execute(Tuple.of(subscriptionId)).coAwait().firstOrNull()?.getString("paymentLabel")?.takeIf { it.isNotBlank() }

    /** The renewal order that waits for the buyer ("Renew" / "Pay now"), `null` when there is none. */
    private suspend fun renewalOrderPublicId(subscriptionId: Long, client: SqlClient): String? =
        client.preparedQuery(
            "SELECT o.`publicId` FROM ${table("market_subscription_renewal")} r JOIN ${table("market_order")} o ON o.`id` = r.`orderId` " +
                "WHERE r.`subscriptionId` = ? AND r.`status` = 'PENDING' AND o.`status` = 'PENDING' ORDER BY r.`periodIndex` DESC LIMIT 1"
        ).execute(Tuple.of(subscriptionId)).coAwait().firstOrNull()?.getString("publicId")

    // ================================================================================================== panel

    /**
     * `GET /subscriptions` (`P:OV`): filtered, newest first. `PENDING` rows only when `status` asks for them. [searchEmail] is the PII tier of 11 section 14.5
     * (`OM` or `PAY`): below it the search never matches the e-mail, so the list is no oracle for a buyer's address.
     */
    suspend fun panelList(filter: SubscriptionFilter, window: PageRequest, client: SqlClient, searchEmail: Boolean = false): SubscriptionPage {
        val where = ArrayList<String>()
        val params = ArrayList<Any?>()

        if (filter.statuses.isEmpty()) {
            where += "`status` <> 'PENDING'"
        } else {
            where += "`status` IN (${filter.statuses.joinToString(", ") { "'${it.name}'" }})"
        }

        filter.mode?.let { where += "`mode` = ?"; params += it.name }
        filter.providerId?.let { where += "`providerId` = ?"; params += it }

        filter.search?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            val like = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

            if (searchEmail) {
                where += "(`playerUsername` LIKE ? OR `email` LIKE ? OR `productName` LIKE ? OR `gatewaySubscriptionId` LIKE ?)"
                repeat(4) { params += like }
            } else {
                where += "(`playerUsername` LIKE ? OR `productName` LIKE ? OR `gatewaySubscriptionId` LIKE ?)"
                repeat(3) { params += like }
            }
        }

        val condition = where.joinToString(" AND ")
        val count = client.preparedQuery("SELECT COUNT(*) AS c FROM ${table("market_subscription")} WHERE $condition").execute(Tuple.from(params)).coAwait().first().getLong("c")
        val ids = client.preparedQuery(
            "SELECT `id` FROM ${table("market_subscription")} WHERE $condition ORDER BY `createdAt` DESC, `id` DESC LIMIT ${window.size} OFFSET ${window.offset}"
        ).execute(Tuple.from(params)).coAwait().map { it.getLong("id") }

        return SubscriptionPage(ids.mapNotNull { subscriptions.getById(it, client) }.map { panelRow(it) }, count)
    }

    /** A row of the panel list (09 section 13). */
    fun panelRow(row: MarketSubscription): JsonObject = JsonObject()
        .put("id", row.id).put("playerUsername", row.playerUsername).put("userId", row.userId).put("productName", row.productName).put("providerId", row.providerId)
        .put("mode", row.mode.name).put("status", row.status.name).put("price", MoneyUtil.toDecimal(row.price)).put("currency", row.currency)
        .put("intervalUnit", row.intervalUnit.name).put("intervalCount", row.intervalCount).put("cycleCount", row.cycleCount).put("maxCycles", row.maxCycles)
        .put("currentPeriodEnd", row.currentPeriodEnd).put("nextChargeAt", row.nextChargeAt).put("graceEndsAt", row.graceEndsAt)
        .put("cancelAtPeriodEnd", row.cancelAtPeriodEnd).put("failCount", row.failCount).put("endReason", row.endReason).put("testMode", row.testMode)
        .put("createdAt", row.createdAt)

    /** `GET /subscriptions/:id` (`P:OV`): every column but `storedMethod` and `providerData`, the renewals, the orders and what the panel may do; `null` for an unknown id.
     * The e-mail is masked (`PiiMask.email`) unless [pii] is set, the PII tier of 11 section 14.5 (`OM` or `PAY`). */
    suspend fun panelDetail(id: Long, client: SqlClient, pii: Boolean = false): JsonObject? {
        val row = subscriptions.getById(id, client) ?: return null
        val now = clock.now()
        val caps = if (row.mode == SubscriptionMode.GATEWAY && row.status.isOpen()) capabilities(row.providerId, client) else null
        val subscription = panelRow(row)
            .put("email", FieldGating.email(row.email, pii)).put("productId", row.productId).put("variantId", row.variantId).put("initialOrderId", row.initialOrderId)
            .put("currentPeriodStart", row.currentPeriodStart).put("nextQueryAt", row.nextQueryAt).put("lastQueriedAt", row.lastQueriedAt)
            .put("remoteCancelState", row.remoteCancelState.name).put("remoteCancelAttempts", row.remoteCancelAttempts).put("cancelRequestedAt", row.cancelRequestedAt)
            .put("cancelledAt", row.cancelledAt).put("endedAt", row.endedAt).put("gatewaySubscriptionId", row.gatewaySubscriptionId)
            .put("gatewayCustomerId", row.gatewayCustomerId).put("storedMethodLabel", row.storedMethodLabel).put("lastFailureAt", row.lastFailureAt)
            .put("reminderSentAt", row.reminderSentAt).put("updatedAt", row.updatedAt)
        val renewalRows = renewals.getBySubscriptionId(id, client).sortedBy { it.periodIndex }.map {
            JsonObject().put("periodIndex", it.periodIndex).put("periodStart", it.periodStart).put("periodEnd", it.periodEnd).put("status", it.status.name)
                .put("amount", MoneyUtil.toDecimal(it.amount)).put("currency", it.currency).put("attempts", it.attempts).put("nextAttemptAt", it.nextAttemptAt)
                .put("lastError", it.lastError).put("orderId", it.orderId)
        }
        val orderRows = client.preparedQuery(
            "SELECT `id`, `publicId`, `source`, `status`, `totalPrice`, `currency`, `paidAt` FROM ${table("market_order")} WHERE `subscriptionId` = ? ORDER BY `id`"
        ).execute(Tuple.of(id)).coAwait().map {
            JsonObject().put("id", it.getLong("id")).put("publicId", it.getString("publicId")).put("source", it.getString("source")).put("status", it.getString("status"))
                .put("total", MoneyUtil.toDecimal(it.getLong("totalPrice"))).put("currency", it.getString("currency")).put("paidAt", it.getLong("paidAt"))
        }
        val retry = when (row.mode) {
            SubscriptionMode.MERCHANT -> row.storedMethod != null &&
                (row.status == SubscriptionStatus.PAST_DUE || (row.status == SubscriptionStatus.ACTIVE && (row.currentPeriodEnd ?: now) <= now)) &&
                !row.cancelAtPeriodEnd && (row.maxCycles == null || row.cycleCount < row.maxCycles)

            SubscriptionMode.GATEWAY -> (row.status == SubscriptionStatus.ACTIVE || row.status == SubscriptionStatus.PAST_DUE) && caps?.recurringRetry == true

            SubscriptionMode.MANUAL -> false
        }
        // a closed row whose remote cancel gave up is "cancelled" again from the panel: it only re-queues the gateway call (09 section 10.3)
        val cancel = row.status.isOpen() || (row.status.isClosed() && row.remoteCancelState == RemoteCancelState.FAILED)

        return JsonObject().put("subscription", subscription).put("renewals", JsonArray(renewalRows)).put("orders", JsonArray(orderRows))
            .put("allowed", JsonObject().put("cancel", cancel).put("retry", retry))
    }
}
