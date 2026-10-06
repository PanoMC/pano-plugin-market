package com.panomc.plugins.market.routes.panel.payment

import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.core.abuse.Redactor
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketPaymentDao
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.PaymentEventStatus
import com.panomc.plugins.market.error.InvalidState
import com.panomc.plugins.market.error.PaymentProviderError
import com.panomc.plugins.market.error.StatusQueryNotSupported
import com.panomc.plugins.market.permission.FieldGating
import com.panomc.plugins.market.routes.api.payment.ReplayResult
import com.panomc.plugins.market.service.PaymentService
import com.panomc.plugins.market.util.Paging
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

/** What a replay did: the row's [status] afterwards and the provider it belongs to (for the activity log). */
class Replayed(val status: String, val providerId: String) {
    fun toJson(): JsonObject = JsonObject().put("status", status)
}

/** One page of provider traffic: the rows as JSON, how many rows match, how many pages. */
class PaymentEventPage(val rows: List<JsonObject>, val count: Long, val totalPage: Long)

/**
 * What the panel does with the raw provider traffic (04 section 7, 02 section 7.3, 11 sections 4.3 and 8.4), as a service so the rules run in a database
 * test without the host:
 * - `GET /payments/:paymentId/events` and `GET /payment-events`: rows as `{id, direction, channel, eventKey, verified, status, eventTypes, responseStatus,
 *   error, remoteIp, createdAt}` (+ `providerId`, `paymentId`, `orderId`, `attempts`, `duplicateCount` for the list); `body`, `headers` and `url` only for
 *   the raw tier (`SET`, [FieldGating.eventRaw]) and then still through [Redactor] ("API responses always pass through Redactor regardless of the stored form");
 *   the list defaults to the rows that need a look: `DEFERRED`, `FAILED`, `REJECTED`;
 * - `POST /payment-events/:eventId/replay`: [replay] re-runs the stored request through the inbound pipeline; only `DEFERRED`, `FAILED` and a `RECEIVED` row
 *   older than 60 s qualify (11 IN-4), anything else is 409 `INVALID_STATE` (a rejected row's body is already truncated and redacted, a processed row is done);
 * - `POST /payments/:paymentId/query`: [query] asks the provider now (`queryPayment`) and answers the attempt's status.
 * [prefix] is the table prefix. [replayEvent] is the dispatcher's `replay`, [reconcile] the payment service's provider query.
 */
class PaymentEventAdmin(
    private val prefix: () -> String,
    private val payments: MarketPaymentDao,
    private val orders: MarketOrderDao,
    private val replayEvent: suspend (eventId: Long) -> ReplayResult,
    private val reconcile: suspend (order: MarketOrder, attempt: MarketPayment, client: SqlClient) -> PaymentService.ReconcileQuery,
    private val redactor: Redactor = Redactor()
) {
    companion object {
        /** The statuses of the default list (02 section 7.3: the rows that did not go through). */
        val ATTENTION: List<PaymentEventStatus> = listOf(PaymentEventStatus.DEFERRED, PaymentEventStatus.FAILED, PaymentEventStatus.REJECTED)

        private const val COLUMNS = "`id`, `providerId`, `direction`, `channel`, `eventKey`, `paymentId`, `orderId`, `verified`, `status`, `eventTypes`, `responseStatus`, `error`, " +
            "`remoteIp`, `attempts`, `duplicateCount`, `createdAt`, `url`, `headers`, `body`"
    }

    private fun table(name: String) = "`${prefix()}$name`"

    /** `GET /payments/:paymentId/events`: the events of one attempt, oldest first. 404 when there is no such attempt. */
    suspend fun forPayment(paymentId: Long, window: Paging.Window, raw: Boolean, client: SqlClient): PaymentEventPage {
        payments.getById(paymentId, client) ?: throw NotFound()

        return page("`paymentId` = ?", listOf(paymentId), "`id` ASC", window, raw, client)
    }

    /** `GET /payment-events`: inbound traffic by [statuses] (default [ATTENTION]) and provider, newest first. */
    suspend fun list(statuses: Set<PaymentEventStatus>, providerId: String?, window: Paging.Window, raw: Boolean, client: SqlClient): PaymentEventPage {
        val wanted = (if (statuses.isEmpty()) ATTENTION else statuses.toList())
        val args = ArrayList<Any?>()
        var where = "`direction` = 'IN' AND `status` IN (${wanted.joinToString(", ") { "'${it.name}'" }})"

        if (providerId != null) {
            where += " AND `providerId` = ?"
            args += providerId
        }

        return page(where, args, "`id` DESC", window, raw, client)
    }

    private suspend fun page(where: String, args: List<Any?>, order: String, window: Paging.Window, raw: Boolean, client: SqlClient): PaymentEventPage {
        val count = client.preparedQuery("SELECT COUNT(*) AS c FROM ${table("market_payment_event")} WHERE $where").execute(Tuple.from(args)).coAwait().first().getLong("c")
        val rows = client.preparedQuery("SELECT $COLUMNS FROM ${table("market_payment_event")} WHERE $where ORDER BY $order LIMIT ? OFFSET ?")
            .execute(Tuple.from(args + window.pageSize + window.offset)).coAwait()

        return PaymentEventPage(rows.map { json(it, raw) }, count, Paging.totalPages(count, window.pageSize))
    }

    private fun json(row: Row, raw: Boolean): JsonObject {
        val out = JsonObject()
            .put("id", row.getLong("id")).put("providerId", row.getString("providerId")).put("direction", row.getString("direction"))
            .put("channel", row.getString("channel")).put("eventKey", row.getString("eventKey"))
            .put("paymentId", row.getLong("paymentId")).put("orderId", row.getLong("orderId"))
            .put("verified", row.getValue("verified")?.let { (it as? Boolean) ?: ((it as? Number)?.toInt() == 1) })
            .put("status", row.getString("status")).put("eventTypes", JsonArray(row.getString("eventTypes")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList<String>()))
            .put("responseStatus", row.getInteger("responseStatus")).put("error", redactor.redactOrNull(row.getString("error")))
            .put("remoteIp", row.getString("remoteIp")).put("attempts", row.getInteger("attempts")).put("duplicateCount", row.getInteger("duplicateCount"))
            .put("createdAt", row.getLong("createdAt"))

        // the raw tier (SET): url, headers and body, redacted again on the way out
        val parts = FieldGating.eventRaw(redactor.redactOrNull(row.getString("body")), redactedHeaders(row.getString("headers")), redactor.redactOrNull(row.getString("url")), raw)

        parts.forEach { (key, value) -> out.put(key, if (key == "headers" && value is String) runCatching { JsonObject(value) }.getOrDefault(null) ?: value else value) }

        return out
    }

    /** The stored header JSON with the credentials of well-known headers and every secret-looking value removed; the text is returned when it is not a JSON object. */
    private fun redactedHeaders(stored: String?): String? {
        if (stored == null) return null

        val parsed = runCatching { JsonObject(stored) }.getOrNull() ?: return redactor.redact(stored)
        val sensitive = parsed.fieldNames().associateWith { name ->
            when (val value = parsed.getValue(name)) {
                is JsonArray -> value.map { it.toString() }
                null -> emptyList()
                else -> listOf(value.toString())
            }
        }
        val cleaned = JsonObject()

        // `:query`, `:form`, `:body` are the replay metadata, they hold the same kind of values as a body does
        for ((name, values) in redactor.redactHeaders(sensitive.filterKeys { !it.startsWith(":") })) cleaned.put(name, JsonArray(values))

        for (name in parsed.fieldNames().filter { it.startsWith(":") }) cleaned.put(name, redactor.redactOrNull(parsed.getValue(name).let { if (it is String) it else it?.toString() }))

        return redactor.redact(cleaned.encode())
    }

    /**
     * `POST /payment-events/:eventId/replay`: `{status}` = the row's status after the run. 404 for an unknown or an outbound row; 409 `INVALID_STATE` (the
     * state in `state`) for a row that cannot be replayed and while another run holds it (`state` = `BUSY`).
     */
    suspend fun replay(eventId: Long): Replayed = when (val result = replayEvent(eventId)) {
        ReplayResult.NotFound -> throw NotFound()
        is ReplayResult.InvalidState -> throw InvalidState(result.status.name)
        ReplayResult.Busy -> throw InvalidState("BUSY")
        is ReplayResult.Done -> Replayed(result.row?.status?.name ?: "UNKNOWN", result.row?.providerId ?: "")
    }

    /**
     * `POST /payments/:paymentId/query`: asks the provider about the attempt now and applies what it reports; `{status}` is the attempt's status afterwards.
     * 404 for an unknown attempt, 400 `STATUS_QUERY_NOT_SUPPORTED` when the provider cannot be asked, 502 `PAYMENT_PROVIDER_ERROR` when it cannot be reached.
     */
    suspend fun query(paymentId: Long, client: SqlClient): JsonObject {
        val attempt: MarketPayment = payments.getById(paymentId, client) ?: throw NotFound()
        val order = orders.getById(attempt.orderId, client) ?: throw NotFound()

        when (val result = reconcile(order, attempt, client)) {
            PaymentService.ReconcileQuery.Unsupported -> throw StatusQueryNotSupported()
            is PaymentService.ReconcileQuery.Unavailable -> throw PaymentProviderError(result.reason)
            is PaymentService.ReconcileQuery.Applied, is PaymentService.ReconcileQuery.Unknown -> Unit
        }

        return JsonObject().put("status", (payments.getById(paymentId, client) ?: attempt).status.name)
    }
}
