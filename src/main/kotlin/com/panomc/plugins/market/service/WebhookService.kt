package com.panomc.plugins.market.service

import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.core.time.Ids
import com.panomc.plugins.market.core.webhook.Attempt
import com.panomc.plugins.market.core.webhook.Decision
import com.panomc.plugins.market.core.webhook.EventPayloads
import com.panomc.plugins.market.core.webhook.StoreInfo
import com.panomc.plugins.market.core.webhook.WebhookEvents
import com.panomc.plugins.market.core.webhook.WebhookHeaders
import com.panomc.plugins.market.core.webhook.WebhookOutcome
import com.panomc.plugins.market.core.webhook.WebhookSigner
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketWebhookDeliveryDao
import com.panomc.plugins.market.db.dao.MarketWebhookEndpointDao
import com.panomc.plugins.market.db.model.MarketWebhookDelivery
import com.panomc.plugins.market.db.model.MarketWebhookEndpoint
import com.panomc.plugins.market.db.model.WebhookDeliveryStatus
import com.panomc.plugins.market.db.model.WebhookFormat
import com.panomc.plugins.market.db.model.WebhookSigning
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.provider.SecretCipher
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import kotlin.random.Random

/**
 * Renders the stored `body` of a delivery for an endpoint with `format = DISCORD` (08 section 16). The real renderer is
 * `DiscordRenderer` (MK-106); until it is wired [Unwired] makes the row `DEAD (RENDER_FAILED)` so the failure is visible
 * in the delivery log instead of a malformed message going out.
 */
fun interface WebhookBodyRenderer {
    /** [envelope] is the JSON envelope of 08 section 15.4; the result is the exact request body. May throw. */
    suspend fun render(endpoint: MarketWebhookEndpoint, event: String, envelope: JsonObject): String

    /** As [render], plus a warning to record in `lastError` of the row without failing it (`TEMPLATE_ERROR`, 08 section 16.4). */
    suspend fun renderChecked(endpoint: MarketWebhookEndpoint, event: String, envelope: JsonObject): RenderedBody =
        RenderedBody(render(endpoint, event, envelope), null)

    /** Production binding until MK-106 lands. Fails closed: never produces a body. */
    object Unwired : WebhookBodyRenderer {
        override suspend fun render(endpoint: MarketWebhookEndpoint, event: String, envelope: JsonObject): String =
            throw IllegalStateException("DISCORD_RENDERER_NOT_WIRED")
    }
}

/** A rendered body and the warning for the log, if any. */
class RenderedBody(val body: String, val warning: String? = null)

/**
 * Reports the end of a delivery row that belongs to a product `WEBHOOK` action (`deliveryId != 0`) back to the delivery
 * engine (D12 `SUCCEEDED` / D21 `DEAD`, 08 section 15.5) on the connection of the row's own status change. Wired by
 * MK-102 / MK-106. While none is wired the job does not even claim such rows (see [WebhookService.claimDue]).
 */
fun interface WebhookDeliveryReporter {
    suspend fun report(conn: SqlConnection, row: MarketWebhookDelivery, decision: Decision)
}

/** Sends one stored delivery row: headers, signature, the guarded request ([OutboundHttp]). Never throws. */
class WebhookSender(
    private val http: OutboundHttp,
    private val cipher: SecretCipher,
    private val clock: Clock,
    private val version: String,
    /** The effective `allowPrivateWebhookTargets` (false when hosted), read at every send. */
    private val allowPrivate: () -> Boolean
) {
    suspend fun send(row: MarketWebhookDelivery, endpoint: MarketWebhookEndpoint?): Attempt {
        val headers = ArrayList<Pair<String, String>>()
        headers += "Content-Type" to "application/json; charset=utf-8"
        headers += "User-Agent" to "Pano-Market/$version"
        headers += "X-Pano-Event" to row.event
        headers += "X-Pano-Event-Id" to row.eventId
        headers += "X-Pano-Delivery" to row.id.toString()
        headers += "X-Pano-Attempt" to row.attempts.toString()

        if (row.signing == WebhookSigning.HMAC_SHA256) {
            // Fail closed: an HMAC endpoint never receives an unsigned request.
            val secret = row.secret?.let { cipher.decrypt(it) }?.takeIf { it.isNotEmpty() }
                ?: return Attempt(error = "SECRET_UNREADABLE", retryable = false)
            headers += WebhookSigner.HEADER to WebhookSigner.header(secret, clock.now() / 1000L, row.body)
        }

        val stored = endpoint?.headers
        if (!stored.isNullOrEmpty()) {
            val extra = cipher.decrypt(stored)?.let { parseHeaders(it) }
                ?: return Attempt(error = "HEADERS_UNREADABLE", retryable = false)
            headers += WebhookHeaders.sendable(extra)
        }

        return http.post(row.url, headers, row.body.toByteArray(Charsets.UTF_8), allowPrivate(), discord = row.format == WebhookFormat.DISCORD)
    }

    private fun parseHeaders(json: String): Map<String, String>? {
        return try {
            val obj = JsonObject(json)
            val out = LinkedHashMap<String, String>()
            for (name in obj.fieldNames()) out[name] = obj.getValue(name)?.toString() ?: return null
            out
        } catch (e: Exception) {
            null
        }
    }
}

/** What `POST /webhooks/:id/test` returns (the response body is never exposed). */
class WebhookTestResult(val statusCode: Int?, val durationMs: Int?, val error: String?)

enum class RedeliverResult { OK, NOT_FOUND, IN_FLIGHT }

/**
 * Store webhooks (08 section 15): the outbox writer [emit] used inside business transactions and the queue operations
 * of `WebhookJob` ([claimDue], [process]).
 *
 * - [emit] / [emitOrderPaid] run on the caller's transaction connection: the rows commit or roll back together with the
 *   business transition, a replay of the same transition inserts nothing (`eventId` is deterministic, `uq_eventId`).
 *   They never send anything and never throw because of an endpoint's content (a body that cannot be rendered becomes a
 *   `DEAD` row in the log).
 * - [claimDue] moves due rows to `SENDING` with a 60 s claim (compare and set, safe with several workers) and turns
 *   expired claims back into retries.
 * - [process] sends one claimed row and stores the outcome (row, endpoint counters, auto-disable and the report back to
 *   the delivery engine) in **one** transaction. No transaction is open during the HTTP call.
 *
 * The raw SQL below is limited to the queue statements the DAO does not offer (filtered claim, bulk `DEAD`, redeliver).
 */
class WebhookService(
    private val db: MarketDb,
    private val clock: Clock,
    private val ids: Ids,
    private val endpoints: MarketWebhookEndpointDao,
    private val deliveries: MarketWebhookDeliveryDao,
    private val orders: MarketOrderDao,
    private val orderItems: MarketOrderItemDao,
    private val sender: WebhookSender,
    private val store: () -> StoreInfo,
    private val renderer: WebhookBodyRenderer = WebhookBodyRenderer.Unwired,
    private val reporter: WebhookDeliveryReporter? = null,
    /** Platform UUID of a player for the payload (`buyer.uuid`); `null` when unknown. Wired by the platform seam. */
    private val uuidOf: suspend (userId: Long?, username: String) -> String? = { _, _ -> null },
    private val disableAfter: Int = MarketWebhookEndpointDao.DEFAULT_DISABLE_AFTER,
    private val random: Random = Random.Default
) {
    private fun table() = "`${deliveries.prefix()}market_webhook_delivery`"

    // ----- emit ------------------------------------------------------------------------------------------------------

    /**
     * Inserts one `PENDING` row per enabled endpoint whose `events` contain [event] or `"*"`; returns how many rows were
     * new. [subjectKey] makes the event id deterministic (08 section 15.1). [data] is the `data` object of the envelope.
     */
    suspend fun emit(conn: SqlConnection, event: String, subjectKey: String, orderId: Long?, data: JsonObject, testMode: Boolean = false): Int {
        require(WebhookEvents.isSubscribable(event)) { "not a store webhook event: $event" }
        val targets = targets(conn, event)
        return if (targets.isEmpty()) 0 else insertRows(conn, targets, event, subjectKey, orderId, data, testMode)
    }

    /**
     * `order.paid` for [orderId] (O2 / O4): loads the order and its lines **on [conn]** (so it sees the status the
     * transition just wrote) and emits. Nothing is read when no endpoint listens.
     */
    suspend fun emitOrderPaid(conn: SqlConnection, orderId: Long): Int {
        val targets = targets(conn, WebhookEvents.ORDER_PAID)
        if (targets.isEmpty()) return 0

        val order = orders.getById(orderId, conn) ?: throw IllegalStateException("order $orderId does not exist")
        val items = orderItems.getByOrderIds(listOf(orderId), conn)
        val data = EventPayloads.orderPaid(
            order, items, store(),
            buyerUuid = uuidOf(order.userId, order.playerUsername),
            recipientUuid = uuidOf(EventPayloads.recipientUserId(order), EventPayloads.recipientName(order))
        )

        return insertRows(conn, targets, WebhookEvents.ORDER_PAID, orderId.toString(), orderId, data, order.testMode)
    }

    /**
     * `order.refunded` for [orderId] (O10, 08 section 15.4): the order, buyer, recipient and lines as of the commit of the transition plus the [refund] object
     * the refund service built (`{id, amount, gatewayAmount, creditAmount, currency, reason, origin, full, revoked, items[]}`). The subject key is the refund id,
     * so a replayed O10 inserts nothing.
     */
    suspend fun emitOrderRefunded(conn: SqlConnection, orderId: Long, refundId: Long, refund: JsonObject): Int {
        val targets = targets(conn, WebhookEvents.ORDER_REFUNDED)
        if (targets.isEmpty()) return 0

        val order = orders.getById(orderId, conn) ?: throw IllegalStateException("order $orderId does not exist")
        val items = orderItems.getByOrderIds(listOf(orderId), conn)
        val data = EventPayloads.orderPaid(
            order, items, store(),
            buyerUuid = uuidOf(order.userId, order.playerUsername),
            recipientUuid = uuidOf(EventPayloads.recipientUserId(order), EventPayloads.recipientName(order))
        ).put("refund", refund)

        return insertRows(conn, targets, WebhookEvents.ORDER_REFUNDED, refundId.toString(), orderId, data, order.testMode)
    }

    /**
     * `order.chargeback` (O11, [won] false) or `order.chargeback.won` (O12, [won] true) for [orderId] (08 section 15.4): the order, buyer, recipient and lines as of
     * the commit of the transition plus the [dispute] object `{id, status, amount, currency, reason, gatewayDisputeId}` the dispute service built. The subject key
     * is the dispute id, so a replayed transition inserts nothing.
     */
    suspend fun emitOrderDispute(conn: SqlConnection, orderId: Long, disputeId: Long, won: Boolean, dispute: JsonObject): Int {
        val event = if (won) WebhookEvents.ORDER_CHARGEBACK_WON else WebhookEvents.ORDER_CHARGEBACK
        val targets = targets(conn, event)
        if (targets.isEmpty()) return 0

        val order = orders.getById(orderId, conn) ?: throw IllegalStateException("order $orderId does not exist")
        val items = orderItems.getByOrderIds(listOf(orderId), conn)
        val data = EventPayloads.orderPaid(
            order, items, store(),
            buyerUuid = uuidOf(order.userId, order.playerUsername),
            recipientUuid = uuidOf(EventPayloads.recipientUserId(order), EventPayloads.recipientName(order))
        ).put("dispute", dispute)

        return insertRows(conn, targets, event, disputeId.toString(), orderId, data, order.testMode)
    }

    private suspend fun targets(conn: SqlConnection, event: String): List<MarketWebhookEndpoint> =
        endpoints.getAll(conn).filter { it.enabled && WebhookEvents.matches(it.events, event) }

    private suspend fun insertRows(
        conn: SqlConnection, targets: List<MarketWebhookEndpoint>, event: String, subjectKey: String,
        orderId: Long?, data: JsonObject, testMode: Boolean
    ): Int {
        val now = clock.now()
        var inserted = 0

        for (endpoint in targets) {
            val eventId = WebhookEvents.eventId(event, subjectKey, endpoint.id)
            val envelope = EventPayloads.envelope(eventId, event, now, testMode, store(), data)
            val rendered = render(endpoint, event, envelope)

            val row = MarketWebhookDelivery(
                endpointId = endpoint.id, eventId = eventId, event = event, orderId = orderId,
                url = endpoint.url, format = endpoint.format, signing = endpoint.signing, secret = endpoint.secret,
                body = rendered.body,
                status = if (rendered.error == null) WebhookDeliveryStatus.PENDING else WebhookDeliveryStatus.DEAD,
                attempts = 0, maxAttempts = endpoint.maxAttempts,
                nextAttemptAt = if (rendered.error == null) now else null,
                lastError = rendered.error ?: rendered.warning,
                createdAt = now, updatedAt = now
            )

            if (deliveries.add(row, conn) != null) inserted++
        }

        return inserted
    }

    private class Rendered(val body: String, val error: String?, val warning: String? = null)

    private suspend fun render(endpoint: MarketWebhookEndpoint, event: String, envelope: JsonObject): Rendered = try {
        when (endpoint.format) {
            WebhookFormat.JSON -> Rendered(envelope.encode(), null)
            WebhookFormat.DISCORD -> renderer.renderChecked(endpoint, event, envelope).let { Rendered(it.body, null, it.warning) }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        // Never break the business transaction over one endpoint's template: the row is in the log as DEAD.
        Rendered(envelope.encode(), "RENDER_FAILED")
    }

    // ----- claim -----------------------------------------------------------------------------------------------------

    /**
     * Up to [limit] due rows (`PENDING` / `FAILED` with `nextAttemptAt <= now`, oldest id first), each moved to `SENDING`
     * with `claimedUntil = now + 60 s` and `attempts + 1`. The returned rows carry the new attempt number. Rows of a
     * product action (`deliveryId != 0`) are only claimed when a [WebhookDeliveryReporter] is wired: without one nobody
     * could learn their outcome.
     */
    suspend fun claimDue(limit: Int = CLAIM_BATCH): List<MarketWebhookDelivery> = db.tx { conn ->
        val now = clock.now()
        reclaimStale(conn, now)

        val actionFilter = if (reporter == null) " AND `deliveryId` = 0" else ""
        val due = conn.preparedQuery(
            "SELECT `id` FROM ${table()} WHERE `status` IN ('PENDING','FAILED') AND `nextAttemptAt` IS NOT NULL AND `nextAttemptAt` <= ?$actionFilter ORDER BY `id` ASC LIMIT ?"
        ).execute(Tuple.of(now, limit)).coAwait().map { it.getLong("id") }

        val claimed = ArrayList<MarketWebhookDelivery>(due.size)

        for (id in due) {
            val won = conn.preparedQuery(
                "UPDATE ${table()} SET `status` = 'SENDING', `claimedUntil` = ?, `attempts` = `attempts` + 1, `updatedAt` = ? WHERE `id` = ? AND `status` IN ('PENDING','FAILED')"
            ).execute(Tuple.of(now + CLAIM_MS, now, id)).coAwait().rowCount() > 0

            if (won) deliveries.getById(id, conn)?.let { claimed += it }
        }

        claimed
    }

    /** `SENDING` rows whose claim ran out (the worker died): the attempt is counted; a row out of attempts ends `DEAD`. */
    private suspend fun reclaimStale(conn: SqlConnection, now: Long) {
        val stale = conn.preparedQuery(
            "SELECT `id` FROM ${table()} WHERE `status` = 'SENDING' AND `claimedUntil` IS NOT NULL AND `claimedUntil` < ? ORDER BY `id` ASC LIMIT 100"
        ).execute(Tuple.of(now)).coAwait().map { it.getLong("id") }

        for (id in stale) {
            val row = deliveries.getById(id, conn) ?: continue
            if (row.status != WebhookDeliveryStatus.SENDING) continue

            if (row.attempts >= row.maxAttempts) {
                val decision = Decision(WebhookDeliveryStatus.DEAD, null, "CLAIM_EXPIRED", success = false, deliveredAt = null)
                finish(conn, row, Attempt(), decision, endpointCounts = false)
            } else {
                deliveries.markResult(
                    id, WebhookDeliveryStatus.SENDING, WebhookDeliveryStatus.FAILED, row.attempts, now,
                    row.lastStatusCode, "CLAIM_EXPIRED", row.lastResponse, row.durationMs, null, now, conn
                )
            }
        }
    }

    // ----- send ------------------------------------------------------------------------------------------------------

    /**
     * Sends the claimed [row] and stores the outcome. Returns the decision, or `null` when the row was taken away while
     * the request was in flight (endpoint deleted or disabled, redelivered): that result is dropped.
     */
    suspend fun process(row: MarketWebhookDelivery): Decision? {
        var endpoint: MarketWebhookEndpoint? = null

        if (row.endpointId != 0L) {
            endpoint = db.tx { conn -> endpoints.getById(row.endpointId, conn) }

            if (endpoint == null) return finishWithoutSending(row, "ENDPOINT_DELETED")
            if (!endpoint.enabled) return finishWithoutSending(row, "ENDPOINT_DISABLED")
        }

        val attempt = sender.send(row, endpoint)

        return complete(row, attempt, endpointCounts = row.endpointId != 0L)
    }

    private suspend fun finishWithoutSending(row: MarketWebhookDelivery, error: String): Decision? {
        val decision = Decision(WebhookDeliveryStatus.DEAD, null, error, success = false, deliveredAt = null)
        return db.tx { conn ->
            if (finish(conn, row, Attempt(), decision, endpointCounts = false)) decision else null
        }
    }

    private suspend fun complete(row: MarketWebhookDelivery, attempt: Attempt, endpointCounts: Boolean): Decision? {
        val now = clock.now()
        val decision = WebhookOutcome.decide(attempt, row.attempts, row.maxAttempts, now, random)

        return db.tx { conn ->
            if (finish(conn, row, attempt, decision, endpointCounts)) decision else null
        }
    }

    /** The one place a row leaves `SENDING`: row, endpoint counters / auto-disable, report back. `false` = the row was no longer ours. */
    private suspend fun finish(conn: SqlConnection, row: MarketWebhookDelivery, attempt: Attempt, decision: Decision, endpointCounts: Boolean): Boolean {
        val now = clock.now()

        val applied = deliveries.markResult(
            row.id, WebhookDeliveryStatus.SENDING, decision.status, row.attempts, decision.nextAttemptAt,
            attempt.statusCode, decision.lastError?.take(MAX_ERROR), attempt.response?.take(MAX_RESPONSE), attempt.durationMs,
            decision.deliveredAt, now, conn
        )
        if (!applied) return false

        if (endpointCounts && row.endpointId != 0L) {
            endpoints.recordOutcome(row.endpointId, decision.success, attempt.statusCode, now, disableAfter, conn)

            if (!decision.success) {
                val endpoint = endpoints.getById(row.endpointId, conn)
                if (endpoint != null && !endpoint.enabled) deadenOpenRows(conn, endpoint.id, "ENDPOINT_DISABLED")
            }
        }

        if (row.deliveryId != 0L && (decision.status == WebhookDeliveryStatus.SUCCEEDED || decision.status == WebhookDeliveryStatus.DEAD)) {
            reporter?.report(conn, row, decision)
        }

        return true
    }

    // ----- operations used by the panel ------------------------------------------------------------------------------

    /**
     * Ends every row of [endpointId] that is still `PENDING`, `FAILED` or `SENDING` as `DEAD` with `lastError = reason`
     * (`ENDPOINT_DELETED` / `ENDPOINT_DISABLED`, 08 section 15.2); returns the number of rows. A request that is in
     * flight finds its row gone and its result is dropped.
     */
    suspend fun deadenOpenRows(conn: SqlConnection, endpointId: Long, reason: String): Int =
        conn.preparedQuery(
            "UPDATE ${table()} SET `status` = 'DEAD', `lastError` = ?, `nextAttemptAt` = NULL, `claimedUntil` = NULL, `updatedAt` = ? WHERE `endpointId` = ? AND `status` IN ('PENDING','FAILED','SENDING')"
        ).execute(Tuple.of(reason, clock.now(), endpointId)).coAwait().rowCount()

    /**
     * Puts a `SUCCEEDED`, `FAILED` or `DEAD` row back to `PENDING` with `attempts = 0` on the **same** row (same
     * `eventId`). A row that is `SENDING` is refused ([RedeliverResult.IN_FLIGHT]); one that is already `PENDING` is left alone.
     */
    suspend fun redeliver(id: Long): RedeliverResult = db.tx<RedeliverResult> { conn ->
        val now = clock.now()
        val changed = conn.preparedQuery(
            "UPDATE ${table()} SET `status` = 'PENDING', `attempts` = 0, `nextAttemptAt` = ?, `claimedUntil` = NULL, `updatedAt` = ? WHERE `id` = ? AND `status` IN ('SUCCEEDED','FAILED','DEAD')"
        ).execute(Tuple.of(now, now, id)).coAwait().rowCount() > 0

        if (changed) return@tx RedeliverResult.OK

        when (deliveries.getById(id, conn)?.status) {
            null -> RedeliverResult.NOT_FOUND
            WebhookDeliveryStatus.SENDING -> RedeliverResult.IN_FLIGHT
            else -> RedeliverResult.OK
        }
    }

    /**
     * `POST /webhooks/:id/test`: inserts a `test.ping` row (`maxAttempts = 1`), sends it right away outside any
     * transaction and returns what happened. Works on a disabled endpoint; the endpoint's failure counter is not touched.
     * `null` when the endpoint does not exist.
     */
    suspend fun sendTestPing(endpointId: Long): WebhookTestResult? {
        val endpoint = db.tx { conn -> endpoints.getById(endpointId, conn) } ?: return null
        val now = clock.now()
        val eventId = ids.uuid()
        val envelope = EventPayloads.envelope(eventId, WebhookEvents.TEST_PING, now, false, store(), EventPayloads.testPing(endpointId))
        val rendered = render(endpoint, WebhookEvents.TEST_PING, envelope)
        if (rendered.error != null) return WebhookTestResult(null, null, rendered.error)

        val row = db.tx { conn ->
            val id = deliveries.add(
                MarketWebhookDelivery(
                    endpointId = endpoint.id, eventId = eventId, event = WebhookEvents.TEST_PING, url = endpoint.url,
                    format = endpoint.format, signing = endpoint.signing, secret = endpoint.secret, body = rendered.body,
                    status = WebhookDeliveryStatus.SENDING, attempts = 1, maxAttempts = 1, nextAttemptAt = null,
                    claimedUntil = now + CLAIM_MS, createdAt = now, updatedAt = now
                ), conn
            ) ?: throw IllegalStateException("duplicate test event id")
            deliveries.getById(id, conn)!!
        }

        val attempt = sender.send(row, endpoint)
        val decision = complete(row, attempt, endpointCounts = false)

        return WebhookTestResult(attempt.statusCode, attempt.durationMs, decision?.lastError ?: attempt.error)
    }

    companion object {
        const val CLAIM_BATCH = 20
        const val CLAIM_MS = 60_000L
        private const val MAX_ERROR = 512
        private const val MAX_RESPONSE = 2048
    }
}
