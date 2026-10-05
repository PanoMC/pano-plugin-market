package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.*
import io.vertx.sqlclient.SqlClient

/** The transactional mail outbox (01 section 9.5). */
abstract class MarketMailOutboxDao : MarketDao<MarketMailOutbox>(MarketMailOutbox::class.java) {
    /** The new id, or `null` when that (kind, refType, refId, refKey, recipient) is queued already (`uq_mail`). */
    abstract suspend fun add(mail: MarketMailOutbox, sqlClient: SqlClient): Long?

    abstract suspend fun getById(id: Long, sqlClient: SqlClient): MarketMailOutbox?

    abstract suspend fun getByKey(kind: MailKind, refType: MailRefType, refId: Long, refKey: String, recipient: String, sqlClient: SqlClient): MarketMailOutbox?

    abstract suspend fun getByOrderId(orderId: Long, sqlClient: SqlClient): List<MarketMailOutbox>

    /** Rows in [status] whose `nextAttemptAt` is at or before [now], oldest schedule first (`idx_due`). */
    abstract suspend fun getDue(status: MailStatus, now: Long, limit: Int, sqlClient: SqlClient): List<MarketMailOutbox>

    /**
     * Moves a row from [from] to [to] only while it is still in [from] (compare and set), storing the attempt count,
     * the next schedule, the error and (for `SENT`) `sentAt`. Returns `true` when the row moved.
     */
    abstract suspend fun transition(
        id: Long, from: MailStatus, to: MailStatus, attempts: Int, nextAttemptAt: Long?, lastError: String?, sentAt: Long?,
        now: Long, sqlClient: SqlClient
    ): Boolean
}
