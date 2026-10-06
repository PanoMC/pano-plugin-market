package com.panomc.plugins.market.job

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketEntitlementDao
import com.panomc.plugins.market.db.model.MailKind
import com.panomc.plugins.market.db.model.MailRefType
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.service.DeliveryService
import com.panomc.plugins.market.service.EntitlementService
import com.panomc.plugins.market.service.MailOutboxService
import com.panomc.plugins.market.service.platform.UserDirectory
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Ends timed entitlements (08 section 10.3), cadence 30 s on `MarketScheduler`, batch [batch] per step. [runOnce] returns the entitlements it moved
 * plus the reminders it queued.
 *
 * Step 1, expiry: `ACTIVE` entitlements with `expiresAt <= now` and no subscription, oldest first. One transaction per row, under the order lock
 * (`Locks.forOrder`, scope `PAYMENT`: nothing else is touched), through [EntitlementService.expire]: `EXPIRED`, and, unless a later link of the chain runs
 * on, the `EXPIRE` rows. They are created here and not at purchase, so an extension or a refund never has to rewrite them. A subscription's entitlement
 * (`subscriptionId IS NOT NULL`) is never selected: `SubscriptionService` ends it (08 section 10.2).
 *
 * Step 2, reminder (12 section 4.1 `EXPIRY_REMINDER`): for the **last** link of a chain, inside `subscriptionReminderDays` before its end, once
 * (`reminderSentAt`), only when the period is at least twice the lead, the mail is queued in the same transaction that sets `reminderSentAt` (a row without
 * a registered owner or e-mail is marked and queues nothing). Without a mail outbox the step does nothing.
 *
 * Safe to run twice and to be killed mid-row: every write is conditional, a row another call moved is skipped, and every row has its own `catch`.
 */
class EntitlementExpiryJob(
    private val clock: Clock,
    private val db: MarketDb,
    private val locks: Locks,
    private val service: EntitlementService,
    private val delivery: DeliveryService,
    private val entitlements: MarketEntitlementDao,
    private val config: () -> MarketConfig,
    private val mail: MailOutboxService? = null,
    private val users: UserDirectory? = null,
    private val batch: Int = BATCH
) {
    private fun table() = "`${entitlements.prefix()}market_entitlement`"

    suspend fun runOnce(): Int {
        var handled = 0
        var failure: Throwable? = null

        suspend fun step(name: String, body: suspend () -> Int) {
            try {
                handled += body()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logger.error("entitlement step {} failed: {}", name, t.toString())

                if (failure == null) failure = t
            }
        }

        step("expire") { expireDue() }
        step("remind") { remindDue() }

        failure?.let { throw it }

        return handled
    }

    // ===================================================================================== step 1: expiry

    private suspend fun expireDue(): Int {
        val now = clock.now()
        val due = db.tx { conn ->
            conn.preparedQuery(
                "SELECT `id`, `orderId` FROM ${table()} WHERE `status` = 'ACTIVE' AND `expiresAt` <= ? AND `subscriptionId` IS NULL ORDER BY `expiresAt`, `id` LIMIT ?"
            ).execute(Tuple.of(now, batch)).coAwait().map { it.getLong("id") to it.getLong("orderId") }
        }
        var moved = 0

        for ((id, orderId) in due) {
            try {
                val result = db.txRestartingOnOrderChange { conn ->
                    locks.forOrder(conn, orderId, OrderLockScope.PAYMENT) { locked -> service.expire(conn, delivery, locked.order, locked.items, id) }
                }

                if (result.outcome != EntitlementService.ExpiryOutcome.SKIPPED) moved++
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logger.warn("expiring entitlement {} failed: {}", id, t.toString())
            }
        }

        return moved
    }

    // ===================================================================================== step 2: reminder

    private suspend fun remindDue(): Int {
        val outbox = mail ?: return 0
        val days = config().subscriptionReminderDays

        if (days <= 0) return 0

        val now = clock.now()
        val lead = days * DAY_MS
        val due = db.tx { conn ->
            conn.preparedQuery(
                "SELECT e.`id` FROM ${table()} e WHERE e.`status` = 'ACTIVE' AND e.`subscriptionId` IS NULL AND e.`expiresAt` IS NOT NULL AND e.`reminderSentAt` IS NULL " +
                    "AND e.`expiresAt` - ? <= ? AND e.`expiresAt` > ? AND e.`expiresAt` - e.`startsAt` >= ? " +
                    "AND NOT EXISTS (SELECT 1 FROM ${table()} n WHERE n.`ownerKey` = e.`ownerKey` AND n.`productId` = e.`productId` AND n.`variantId` = e.`variantId` " +
                    "AND n.`status` = 'ACTIVE' AND n.`id` <> e.`id` AND n.`expiresAt` > e.`expiresAt`) ORDER BY e.`expiresAt`, e.`id` LIMIT ?"
            ).execute(Tuple.of(lead, now, now, 2 * lead, batch)).coAwait().map { it.getLong("id") }
        }
        var queued = 0

        for (id in due) {
            try {
                if (remind(id, outbox, lead)) queued++
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logger.warn("the expiry reminder of entitlement {} failed: {}", id, t.toString())
            }
        }

        return queued
    }

    /** One reminder in one transaction: `reminderSentAt` is set conditionally (the row is ours only when 1 row changed), then the mail is queued. */
    private suspend fun remind(id: Long, outbox: MailOutboxService, lead: Long): Boolean = db.tx { conn ->
        val now = clock.now()
        val entitlement = entitlements.getById(id, conn) ?: return@tx false
        val end = entitlement.expiresAt ?: return@tx false
        val marked = conn.preparedQuery(
            "UPDATE ${table()} SET `reminderSentAt` = ?, `updatedAt` = ? WHERE `id` = ? AND `status` = 'ACTIVE' AND `reminderSentAt` IS NULL AND `expiresAt` = ? AND `expiresAt` > ?"
        ).execute(Tuple.of(now, now, id, end, now)).coAwait().rowCount() > 0

        if (!marked) return@tx false

        val userId = entitlement.userId
        val email = if (userId != null) users?.emailOf(userId, conn).orEmpty() else ""

        if (email.isBlank()) return@tx false

        val queued = outbox.enqueue(
            conn, MailKind.EXPIRY_REMINDER, MailRefType.ENTITLEMENT, entitlement.id, end.toString(), entitlement.orderId, userId, email, localeOf(conn, entitlement.orderId).orEmpty(),
            JsonObject().put("expiresAt", end)
        )

        queued != null
    }

    /** The locale the order was placed in. */
    private suspend fun localeOf(conn: io.vertx.sqlclient.SqlClient, orderId: Long): String? =
        conn.preparedQuery("SELECT `locale` FROM `${entitlements.prefix()}market_order` WHERE `id` = ?").execute(Tuple.of(orderId)).coAwait().firstOrNull()?.getString("locale")

    companion object {
        const val BATCH = 100
        private const val DAY_MS = 86_400_000L

        private val logger = LoggerFactory.getLogger(EntitlementExpiryJob::class.java)
    }
}
