package com.panomc.plugins.market.service

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketMailOutboxDao
import com.panomc.plugins.market.db.dao.MarketOrderEventDao
import com.panomc.plugins.market.db.model.MailKind
import com.panomc.plugins.market.db.model.MailRefType
import com.panomc.plugins.market.db.model.MailStatus
import com.panomc.plugins.market.db.model.MarketMailOutbox
import com.panomc.plugins.market.db.model.MarketOrderEvent
import com.panomc.plugins.market.db.model.OrderActorType
import com.panomc.plugins.market.db.model.OrderEventType
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

/**
 * The transactional mail outbox (12 sections 4.2 and 4.3): [enqueue] runs on the connection of the caller's transition
 * (the mail row commits or rolls back with it), the claim and state writes belong to `MailOutboxJob`. No mail is ever
 * sent from here (12 section 3.3).
 */
class MailOutboxService(
    private val config: () -> MarketConfig,
    private val clock: Clock,
    private val mailOutbox: MarketMailOutboxDao,
    private val orderEvents: MarketOrderEventDao
) {
    private fun table() = "`${mailOutbox.prefix()}market_mail_outbox`"

    /**
     * Queues one mail (12 section 4.2) and returns its outbox id, or `null` when nothing was queued (invalid / missing
     * recipient, kind switched off, the purchase-mail switch for an order mail, a forced resend of a row that is being sent).
     *
     * - [recipient] is trimmed and lower-cased and must match `^[^@\s]{1,64}@[^@\s]{1,190}$`; a bad one writes the timeline
     *   note `MAIL_NOT_QUEUED:<KIND>:INVALID_RECIPIENT`, a blank one `...:NO_RECIPIENT` (only when [orderId] is given).
     * - unless [force]: `mailDisabledKinds` and (for the order mails) `sendEmailAfterPurchase = false` queue nothing and write nothing.
     * - the row is unique per (kind, refType, refId, refKey, recipient): a second enqueue returns the existing id and changes
     *   nothing (idempotent replays of transitions, webhooks and jobs). With [force] an existing row is reset to `PENDING`
     *   (attempts 0, due now, no error, `params.forced = true`) unless it is `SENDING`.
     * - a queued row of an order writes the `MAIL_QUEUED` timeline event.
     *
     * [conn] is the connection of the caller's transaction (or a pool for a standalone call).
     */
    suspend fun enqueue(
        conn: SqlClient,
        kind: MailKind,
        refType: MailRefType,
        refId: Long,
        refKey: String = "",
        orderId: Long?,
        userId: Long?,
        recipient: String,
        locale: String,
        params: JsonObject = JsonObject(),
        force: Boolean = false
    ): Long? {
        val address = recipient.trim().lowercase()
        if (address.isEmpty()) {
            note(conn, orderId, kind, "NO_RECIPIENT")
            return null
        }
        if (!RECIPIENT.matches(address)) {
            note(conn, orderId, kind, "INVALID_RECIPIENT")
            return null
        }

        if (!force) {
            val cfg = config()
            if (kind.name in cfg.mailDisabledKinds) return null
            if (!cfg.sendEmailAfterPurchase && kind in ORDER_MAILS) return null
        }

        val now = clock.now()
        val stored = if (force) params.copy().put("forced", true) else params
        val row = MarketMailOutbox(
            kind = kind, refType = refType, refId = refId, refKey = refKey.take(MAX_REF_KEY), orderId = orderId, userId = userId,
            recipient = address, locale = locale.ifBlank { DEFAULT_LOCALE }.take(MAX_LOCALE), params = stored.encode(),
            status = MailStatus.PENDING, attempts = 0, nextAttemptAt = now, createdAt = now, updatedAt = now
        )

        val id = mailOutbox.add(row, conn)
        if (id != null) {
            queued(conn, orderId, kind, id, now)
            return id
        }

        // Duplicate: the existing row answers. The failed INSERT rolled back only itself, so the caller's transaction goes on.
        val existing = checkNotNull(mailOutbox.getByKey(kind, refType, refId, row.refKey, address, conn)) {
            "mail outbox row vanished after a duplicate key"
        }
        if (!force) return existing.id

        if (existing.status == MailStatus.SENDING) return null
        val merged = JsonObject(existing.params.ifBlank { "{}" }).mergeIn(stored).put("forced", true)
        val reset = conn.preparedQuery(
            "UPDATE ${table()} SET `status` = 'PENDING', `attempts` = 0, `nextAttemptAt` = ?, `claimedUntil` = NULL, `lastError` = NULL, " +
                "`params` = ?, `locale` = ?, `updatedAt` = ? WHERE `id` = ? AND `status` <> 'SENDING'"
        ).execute(Tuple.of(now, merged.encode(), row.locale, now, existing.id)).coAwait().rowCount() > 0
        if (!reset) return null
        queued(conn, orderId, kind, existing.id, now)
        return existing.id
    }

    // ----- what the job needs ----------------------------------------------------------------------------------------

    /**
     * Rows to work on at [now] (12 section 4.3.1): `PENDING` and due, plus `SENDING` whose claim ran out (a crashed send,
     * treated as a failed attempt), oldest schedule first, at most [limit].
     */
    suspend fun due(now: Long, limit: Int, sqlClient: SqlClient): List<MarketMailOutbox> {
        val pending = mailOutbox.getDue(MailStatus.PENDING, now, limit, sqlClient)
        val staleIds = sqlClient.preparedQuery(
            "SELECT `id` FROM ${table()} WHERE `status` = 'SENDING' AND `claimedUntil` IS NOT NULL AND `claimedUntil` < ? " +
                "ORDER BY `claimedUntil` ASC, `id` ASC LIMIT ?"
        ).execute(Tuple.of(now, limit)).coAwait().map { it.getLong("id") }
        return (pending + staleIds.mapNotNull { mailOutbox.getById(it, sqlClient) }).take(limit)
    }

    /**
     * Claim (12 section 4.3.2): `status = SENDING`, `claimedUntil = now + CLAIM_MS`, `attempts + 1`, only while the row is still
     * in the status [seen] and not claimed. Returns the row as stored after the claim, `null` when somebody else won.
     */
    suspend fun claim(seen: MarketMailOutbox, now: Long, sqlClient: SqlClient): MarketMailOutbox? {
        val moved = sqlClient.preparedQuery(
            "UPDATE ${table()} SET `status` = 'SENDING', `claimedUntil` = ?, `attempts` = `attempts` + 1, `updatedAt` = ? " +
                "WHERE `id` = ? AND `status` = ? AND (`claimedUntil` IS NULL OR `claimedUntil` < ?)"
        ).execute(Tuple.of(now + CLAIM_MS, now, seen.id, seen.status.name, now)).coAwait().rowCount() > 0
        return if (moved) mailOutbox.getById(seen.id, sqlClient) else null
    }

    /**
     * Ends a claimed row: one conditional update `WHERE status = 'SENDING'` (12 section 4.3, last line). `false` when the row is no
     * longer `SENDING` (a resend raced the job).
     */
    suspend fun finish(
        row: MarketMailOutbox, to: MailStatus, attempts: Int, nextAttemptAt: Long?, lastError: String?, sentAt: Long?, sqlClient: SqlClient
    ): Boolean = mailOutbox.transition(
        row.id, MailStatus.SENDING, to, attempts, nextAttemptAt, lastError?.take(MAX_ERROR), sentAt, clock.now(), sqlClient
    )

    // ----- helpers ---------------------------------------------------------------------------------------------------

    private suspend fun queued(conn: SqlClient, orderId: Long?, kind: MailKind, outboxId: Long, now: Long) {
        if (orderId == null) return
        orderEvents.add(
            MarketOrderEvent(
                orderId = orderId, type = OrderEventType.MAIL_QUEUED, actorType = OrderActorType.SYSTEM,
                data = JsonObject().put("kind", kind.name).put("outboxId", outboxId).encode(), createdAt = now, updatedAt = now
            ),
            conn
        )
    }

    private suspend fun note(conn: SqlClient, orderId: Long?, kind: MailKind, reason: String) {
        if (orderId == null) return
        val now = clock.now()
        orderEvents.add(
            MarketOrderEvent(
                orderId = orderId, type = OrderEventType.NOTE, actorType = OrderActorType.SYSTEM,
                message = "MAIL_NOT_QUEUED:${kind.name}:$reason", createdAt = now, updatedAt = now
            ),
            conn
        )
    }

    companion object {
        /** The claim of a row being sent (12 section 4.3.2): 5 minutes. */
        const val CLAIM_MS = 300_000L

        const val DEFAULT_LOCALE = "en-US"

        private const val MAX_REF_KEY = 64
        private const val MAX_LOCALE = 16
        private const val MAX_ERROR = 512

        private val RECIPIENT = Regex("^[^@\\s]{1,64}@[^@\\s]{1,190}$")

        /** The kinds the purchase-mail switch `sendEmailAfterPurchase` turns off; the others are service mails (12 section 4.2.2). */
        val ORDER_MAILS: Set<MailKind> = setOf(
            MailKind.ORDER_RECEIVED, MailKind.ORDER_CONFIRMATION, MailKind.ORDER_DELIVERED, MailKind.ORDER_REFUNDED,
            MailKind.GIFT_RECEIVED, MailKind.SHIPMENT_SHIPPED, MailKind.SHIPMENT_DELIVERED
        )
    }
}
