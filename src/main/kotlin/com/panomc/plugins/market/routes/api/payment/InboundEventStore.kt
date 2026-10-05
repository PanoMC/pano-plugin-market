package com.panomc.plugins.market.routes.api.payment

import com.panomc.plugins.market.db.dao.MarketPaymentEventDao
import com.panomc.plugins.market.db.dao.isDuplicateKey
import com.panomc.plugins.market.db.model.MarketPaymentEvent
import com.panomc.plugins.market.db.model.PaymentEventDirection
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

/** What a settled run writes onto its row. A `null` id keeps the value the row has. */
class Settlement(
    val status: com.panomc.plugins.market.db.model.PaymentEventStatus,
    val verified: Boolean?,
    val eventTypes: String?,
    val paymentId: Long?,
    val orderId: Long?,
    val refundId: Long?,
    val subscriptionId: Long?,
    val responseStatus: Int?,
    val error: String?,
    val durationMs: Int?,
    val processedAt: Long?,
    val nextAttemptAt: Long?,
    val now: Long,
    /** Replaces `headers` and `body` (the redacted form of a settled row); `null` leaves the verbatim request for a replay. */
    val raw: RawRewrite? = null
)

class RawRewrite(val headers: String?, val body: String?)

/**
 * `market_payment_event` as the inbound pipeline needs it (01 section 6.3, 02 section 7.3): the seam that [InboundDispatcher] and
 * [com.panomc.plugins.market.job.InboundEventRetryJob] test behind an in-memory fake and run against [DbInboundEventStore] in production.
 * Every method is one statement, so none of them needs a transaction; the unique key `uq_event(providerId, direction, eventKey)` is the only
 * arbiter of who holds a provider key.
 */
interface InboundEventStore {
    /** Stores a request before any provider code runs. Returns the new id. */
    suspend fun insert(event: MarketPaymentEvent): Long

    suspend fun get(id: Long): MarketPaymentEvent?

    /** The `IN` row that holds [key]. */
    suspend fun byKey(providerId: String, key: String): MarketPaymentEvent?

    /** Sets the key of row [id]; `false` when another row holds it (`uq_event`). */
    suspend fun claimKey(id: Long, key: String, now: Long): Boolean

    /** `duplicateCount + 1` on the row that holds a key (in the database, never read-modify-write). */
    suspend fun bumpDuplicates(id: Long, now: Long)

    /**
     * Takes the key of [old] over: one statement sets `status = SUPERSEDED` and `eventKey = eventKey + ':' + id` (with the redacted [headers] and
     * [body]) `WHERE id = ? AND status = <old.status>`; `false` (zero rows) means the row changed and must be read again.
     */
    suspend fun supersede(old: MarketPaymentEvent, headers: String?, body: String?, now: Long): Boolean

    /** Writes the outcome of a run; a row that was superseded meanwhile is left alone. */
    suspend fun settle(id: Long, settlement: Settlement): Boolean

    /**
     * Claims one retry of [row] (`attempts + 1`, `nextAttemptAt = now + leaseMs`) only when it still has the `attempts` and `status` it was read
     * with, so two runners never take the same row. `false` = somebody else was faster.
     */
    suspend fun claimRetry(row: MarketPaymentEvent, now: Long, leaseMs: Long): Boolean

    /**
     * The rows a retry run handles: `FAILED` with `nextAttemptAt` due, and `RECEIVED` older than [staleBefore] (a crashed run) whose lease has
     * ended, both with fewer than [maxAttempts] runs, oldest first.
     */
    suspend fun due(now: Long, staleBefore: Long, maxAttempts: Int, limit: Int): List<MarketPaymentEvent>

    /** `RECEIVED` rows older than [staleBefore] that used up [maxAttempts] runs become `FAILED` without a schedule (a human replays them). Returns how many. */
    suspend fun exhaust(now: Long, staleBefore: Long, maxAttempts: Int): Int

    /** `market_payment_method.lastInboundAt` of [providerId] (every verified `WEBHOOK` / `NOTIFY`). */
    suspend fun touchLastInbound(providerId: String, now: Long)
}

/** [InboundEventStore] on the real table; [client] is the autocommit pool (every method is a single statement). */
class DbInboundEventStore(private val dao: MarketPaymentEventDao, private val client: suspend () -> SqlClient) : InboundEventStore {
    private fun table(name: String) = "`${dao.prefix()}$name`"

    private val events get() = table("market_payment_event")

    private val columns by lazy { dao.fields.joinToString(", ") { "`$it`" } }

    override suspend fun insert(event: MarketPaymentEvent): Long =
        dao.add(event, client()) ?: throw IllegalStateException("the event key ${event.eventKey} is already taken")

    override suspend fun get(id: Long): MarketPaymentEvent? = dao.getById(id, client())

    override suspend fun byKey(providerId: String, key: String): MarketPaymentEvent? = dao.getByEventKey(providerId, PaymentEventDirection.IN, key, client())

    override suspend fun claimKey(id: Long, key: String, now: Long): Boolean = try {
        client().preparedQuery("UPDATE $events SET `eventKey` = ?, `updatedAt` = ? WHERE `id` = ?").execute(Tuple.of(key, now, id)).coAwait()

        true
    } catch (e: Exception) {
        if (e.isDuplicateKey()) false else throw e
    }

    override suspend fun bumpDuplicates(id: Long, now: Long) {
        client().preparedQuery("UPDATE $events SET `duplicateCount` = `duplicateCount` + 1, `updatedAt` = ? WHERE `id` = ?").execute(Tuple.of(now, id)).coAwait()
    }

    override suspend fun supersede(old: MarketPaymentEvent, headers: String?, body: String?, now: Long): Boolean =
        client().preparedQuery(
            "UPDATE $events SET `status` = 'SUPERSEDED', `eventKey` = CONCAT(`eventKey`, ':', `id`), `headers` = ?, `body` = ?, `updatedAt` = ? WHERE `id` = ? AND `status` = ?"
        ).execute(Tuple.of(headers, body, now, old.id, old.status.name)).coAwait().rowCount() == 1

    override suspend fun settle(id: Long, settlement: Settlement): Boolean {
        val s = settlement
        val raw = s.raw
        val sql = StringBuilder(
            "UPDATE $events SET `status` = ?, `verified` = COALESCE(?, `verified`), `eventTypes` = COALESCE(?, `eventTypes`), `paymentId` = COALESCE(?, `paymentId`), " +
                "`orderId` = COALESCE(?, `orderId`), `refundId` = COALESCE(?, `refundId`), `subscriptionId` = COALESCE(?, `subscriptionId`), `responseStatus` = ?, `error` = ?, " +
                "`durationMs` = ?, `processedAt` = ?, `nextAttemptAt` = ?, `updatedAt` = ?"
        )
        val values = Tuple.tuple()
            .addValue(s.status.name).addValue(s.verified).addValue(s.eventTypes).addValue(s.paymentId).addValue(s.orderId).addValue(s.refundId).addValue(s.subscriptionId)
            .addValue(s.responseStatus).addValue(s.error).addValue(s.durationMs).addValue(s.processedAt).addValue(s.nextAttemptAt).addValue(s.now)

        if (raw != null) {
            sql.append(", `headers` = ?, `body` = ?")
            values.addValue(raw.headers).addValue(raw.body)
        }

        // a row that a redelivery took over is not brought back
        sql.append(" WHERE `id` = ? AND `status` <> 'SUPERSEDED'")
        values.addValue(id)

        return client().preparedQuery(sql.toString()).execute(values).coAwait().rowCount() == 1
    }

    override suspend fun claimRetry(row: MarketPaymentEvent, now: Long, leaseMs: Long): Boolean =
        client().preparedQuery(
            "UPDATE $events SET `attempts` = `attempts` + 1, `nextAttemptAt` = ?, `updatedAt` = ? WHERE `id` = ? AND `attempts` = ? AND `status` = ?"
        ).execute(Tuple.of(now + leaseMs, now, row.id, row.attempts, row.status.name)).coAwait().rowCount() == 1

    override suspend fun due(now: Long, staleBefore: Long, maxAttempts: Int, limit: Int): List<MarketPaymentEvent> =
        with(dao) {
            client().preparedQuery(
                "SELECT $columns FROM $events WHERE `direction` = 'IN' AND `attempts` < ? AND (" +
                    "(`status` = 'FAILED' AND `nextAttemptAt` IS NOT NULL AND `nextAttemptAt` <= ?) OR " +
                    "(`status` = 'RECEIVED' AND `createdAt` < ? AND (`nextAttemptAt` IS NULL OR `nextAttemptAt` <= ?))) ORDER BY `id` ASC LIMIT ?"
            ).execute(Tuple.of(maxAttempts, now, staleBefore, now, limit)).coAwait().toEntities()
        }

    override suspend fun exhaust(now: Long, staleBefore: Long, maxAttempts: Int): Int =
        client().preparedQuery(
            "UPDATE $events SET `status` = 'FAILED', `nextAttemptAt` = NULL, `error` = COALESCE(`error`, 'retry budget exhausted'), `updatedAt` = ? " +
                "WHERE `direction` = 'IN' AND `status` = 'RECEIVED' AND `attempts` >= ? AND `createdAt` < ?"
        ).execute(Tuple.of(now, maxAttempts, staleBefore)).coAwait().rowCount()

    override suspend fun touchLastInbound(providerId: String, now: Long) {
        client().preparedQuery("UPDATE ${table("market_payment_method")} SET `lastInboundAt` = ? WHERE `methodId` = ?").execute(Tuple.of(now, providerId)).coAwait()
    }
}
