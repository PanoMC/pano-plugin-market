package com.panomc.plugins.market.job

import com.panomc.platform.db.DatabaseManager
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketCreditAccountDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketRedemptionDao
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.event.AccountFacts
import com.panomc.plugins.market.event.AccountLookup
import com.panomc.plugins.market.event.GuestAdoption
import com.panomc.plugins.market.routes.api.checkout.abuseWiring
import com.panomc.plugins.market.routes.api.order.creditService
import com.panomc.plugins.market.routes.api.order.orderService
import com.panomc.plugins.market.routes.api.order.paymentService
import com.panomc.plugins.market.routes.api.order.subscriptionService
import com.panomc.plugins.market.runtime.beans
import com.panomc.plugins.market.service.CreditReconciler
import com.panomc.plugins.market.service.PlayerErasureService
import com.panomc.plugins.market.service.ThrottleService
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * The housekeeping of the store (11 sections 12.1, 16 and 17, 07 section 16.2, 01 section 5.5), one `MarketScheduler` job that ticks every minute and
 * runs each task on its own cadence ([Task]). A task that throws is logged and does not stop the others; every task is safe to run twice.
 *
 * | task | every | what |
 * |---|---|---|
 * | [Task.RECONCILE] | 6 h, first 60 s after the first tick | `CreditReconciler.run()` (the ledger self-check of 07 section 16.2) |
 * | [Task.THROTTLE] | 1 h | `ThrottleService.purge()` (11 section 12.1) |
 * | [Task.RETENTION] | 24 h | 11 section 17: network PII of orders and payments after [PII_NETWORK_RETENTION_DAYS], payment event bodies after [EVENT_BODY_DAYS], `REJECTED` events after [REJECTED_EVENT_DAYS], block rows [BLOCK_PURGE_DAYS] after their expiry; also the webhook deliveries (`SUCCEEDED` after [WEBHOOK_SUCCEEDED_DAYS], `DEAD` after [WEBHOOK_DEAD_DAYS], 08 section 15) and the expired provider state keys (01 section 13) |
 * | [Task.ERASURE] | 5 min | the deferred half of 11 section 16: orders of an erased buyer (`PII_ERASED` event), the `erasure-pending` markers run again, ended subscriptions lose their gateway data |
 * | [Task.GUEST_ADOPTION] | 5 min | `GuestAdoption.run()` (01 section 5.5) |
 *
 * Not here: the hourly release of due creator earnings (`CreatorService.releaseDue`), because every balance read releases lazily (MK-114).
 */
class HousekeepingJob(
    private val clock: Clock,
    private val prefix: () -> String,
    private val client: suspend () -> SqlClient,
    private val throttle: ThrottleService?,
    /** The self-check of the ledger; the health endpoint reads its `last` result (07 section 16.2, `health.credits`). */
    val reconciler: CreditReconciler?,
    private val erasure: PlayerErasureService?,
    private val adoption: GuestAdoption?
) {
    enum class Task(val everyMs: Long) {
        RECONCILE(6 * HOUR_MS),
        THROTTLE(HOUR_MS),
        RETENTION(24 * HOUR_MS),
        ERASURE(5 * MINUTE_MS),
        GUEST_ADOPTION(5 * MINUTE_MS)
    }

    private val nextDue = HashMap<Task, Long>()
    private var startedAt: Long? = null

    private fun t(name: String) = "`${prefix()}$name`"

    /** Runs the tasks that are due; returns the number of rows they handled. Never throws except for cancellation. */
    suspend fun runOnce(): Int {
        val now = clock.now()
        val first = startedAt ?: now.also { startedAt = it }
        var handled = 0

        for (task in Task.values()) {
            // the self-check starts 60 s after the first tick, the others at once
            val due = nextDue[task] ?: if (task == Task.RECONCILE) first + RECONCILE_DELAY_MS else first

            if (now < due) continue

            nextDue[task] = now + task.everyMs

            try {
                handled += run(task)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logger.error("housekeeping task {} failed: {}", task, t.toString())
            }
        }

        return handled
    }

    /** One task, now, whatever its schedule says. */
    suspend fun run(task: Task): Int = when (task) {
        Task.RECONCILE -> {
            reconciler?.run(full = false)

            0
        }

        Task.THROTTLE -> throttle?.purge() ?: 0
        Task.RETENTION -> retention()
        Task.ERASURE -> deferredErasure()
        Task.GUEST_ADOPTION -> adoption?.run() ?: 0
    }

    // ===================================================================================== 11 section 17

    private suspend fun batched(statement: String, vararg args: Any?): Int {
        var total = 0

        repeat(MAX_BATCHES) {
            val n = client().preparedQuery("$statement LIMIT $BATCH").execute(Tuple.from(args.toList())).coAwait().rowCount()

            total += n

            if (n < BATCH) return total
        }

        return total
    }

    /** The retention windows of 11 section 17. Returns the number of rows changed or deleted. */
    suspend fun retention(): Int {
        val now = clock.now()
        val network = now - PII_NETWORK_RETENTION_DAYS * DAY_MS
        var changed = 0

        changed += batched(
            "UPDATE ${t("market_order")} SET `clientIp` = NULL, `userAgent` = NULL WHERE `createdAt` < ? AND (`clientIp` IS NOT NULL OR `userAgent` IS NOT NULL)", network
        )
        changed += batched(
            "UPDATE ${t("market_payment")} SET `clientIp` = NULL, `userAgent` = NULL WHERE `createdAt` < ? AND (`clientIp` IS NOT NULL OR `userAgent` IS NOT NULL)", network
        )
        changed += batched(
            "UPDATE ${t("market_payment_event")} SET `body` = NULL, `headers` = NULL WHERE `createdAt` < ? AND (`body` IS NOT NULL OR `headers` IS NOT NULL)", now - EVENT_BODY_DAYS * DAY_MS
        )
        changed += batched("DELETE FROM ${t("market_payment_event")} WHERE `status` = 'REJECTED' AND `createdAt` < ?", now - REJECTED_EVENT_DAYS * DAY_MS)
        changed += batched("DELETE FROM ${t("market_block")} WHERE `expiresAt` IS NOT NULL AND `expiresAt` < ?", now - BLOCK_PURGE_DAYS * DAY_MS)
        changed += batched("DELETE FROM ${t("market_webhook_delivery")} WHERE `status` = 'SUCCEEDED' AND `updatedAt` < ?", now - WEBHOOK_SUCCEEDED_DAYS * DAY_MS)
        changed += batched("DELETE FROM ${t("market_webhook_delivery")} WHERE `status` = 'DEAD' AND `updatedAt` < ?", now - WEBHOOK_DEAD_DAYS * DAY_MS)
        changed += batched("DELETE FROM ${t("market_provider_state")} WHERE `expiresAt` IS NOT NULL AND `expiresAt` <= ?", now)

        return changed
    }

    // ===================================================================================== 11 section 16, deferred

    /**
     * The half of the erasure that had to wait (11 section 16 steps 5, 13, 14 and the markers). The mark of an erased order is its `PII_ERASED` event:
     * - `userId` of an erased order that no longer holds credits;
     * - `shippingAddress` of an erased order whose shipping is finished (or that is over), the `toAddress` of its finished shipments;
     * - the body of its `SUCCEEDED` / `DEAD` webhook deliveries and of its settled payment events;
     * - ended subscriptions without an owner lose the gateway customer and data (unless a gateway cancel is still queued);
     * - `erasure-pending` markers: [PlayerErasureService.erase] runs again for [PENDING_BATCH] of them.
     */
    suspend fun deferredErasure(): Int {
        var changed = 0
        val erased = "EXISTS (SELECT 1 FROM ${t("market_order_event")} e WHERE e.`orderId` = %s.`id` AND e.`type` = 'PII_ERASED')"

        changed += batched(
            "UPDATE ${t("market_order")} o SET o.`userId` = NULL WHERE o.`userId` IS NOT NULL AND NOT (o.`reservationState` = 'HELD' AND o.`creditAmount` > 0) AND ${erased.format("o")}"
        )
        changed += batched(
            "UPDATE ${t("market_order")} o SET o.`shippingAddress` = NULL WHERE o.`shippingAddress` IS NOT NULL AND ${erased.format("o")} AND " +
                "(o.`shippingStatus` IN ('NOT_REQUIRED', 'DELIVERED', 'RETURNED') OR o.`status` IN ('REFUNDED', 'CANCELLED', 'EXPIRED', 'FAILED'))"
        )

        val shipments = client().preparedQuery(
            "SELECT s.`id` FROM ${t("market_shipment")} s JOIN ${t("market_order")} o ON o.`id` = s.`orderId` WHERE s.`status` IN ('DELIVERED', 'RETURNED', 'CANCELLED', 'LOST') " +
                "AND (s.`toAddress` <> '{}' OR s.`labelFile` IS NOT NULL) AND ${erased.format("o")} ORDER BY s.`id` LIMIT $BATCH"
        ).execute().coAwait().map { it.getLong("id") }

        if (shipments.isNotEmpty()) {
            changed += client().preparedQuery("UPDATE ${t("market_shipment")} SET `toAddress` = '{}' WHERE `id` IN (${shipments.joinToString(",") { "?" }})")
                .execute(Tuple.from(shipments)).coAwait().rowCount()

            erasure?.deleteLabels(shipments)
        }

        changed += batched(
            "UPDATE ${t("market_webhook_delivery")} w SET w.`body` = '{}' WHERE w.`status` IN ('SUCCEEDED', 'DEAD') AND w.`body` <> '{}' AND w.`orderId` IS NOT NULL AND " +
                "EXISTS (SELECT 1 FROM ${t("market_order_event")} e WHERE e.`orderId` = w.`orderId` AND e.`type` = 'PII_ERASED')"
        )
        changed += batched(
            "UPDATE ${t("market_payment_event")} p SET p.`body` = NULL, p.`headers` = NULL WHERE p.`status` NOT IN ('RECEIVED', 'DEFERRED', 'FAILED') AND (p.`body` IS NOT NULL OR p.`headers` IS NOT NULL) AND " +
                "p.`orderId` IS NOT NULL AND EXISTS (SELECT 1 FROM ${t("market_order_event")} e WHERE e.`orderId` = p.`orderId` AND e.`type` = 'PII_ERASED')"
        )
        changed += batched(
            "UPDATE ${t("market_subscription")} SET `email` = NULL, `storedMethod` = NULL, `storedMethodLabel` = NULL, `fieldValues` = NULL, `gatewayCustomerId` = NULL, `providerData` = NULL " +
                "WHERE `userId` IS NULL AND `status` IN ('CANCELLED', 'EXPIRED', 'COMPLETED') AND `remoteCancelState` <> 'PENDING' AND " +
                "(`email` IS NOT NULL OR `storedMethod` IS NOT NULL OR `storedMethodLabel` IS NOT NULL OR `fieldValues` IS NOT NULL OR `gatewayCustomerId` IS NOT NULL OR `providerData` IS NOT NULL)"
        )

        if (erasure != null) {
            for (userId in erasure.pending(PENDING_BATCH)) {
                val report = erasure.erase(userId)

                if (report.complete) changed++
            }
        }

        return changed
    }

    companion object {
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60 * MINUTE_MS
        const val DAY_MS = 24 * HOUR_MS

        /** `market_order.clientIp`, `userAgent` and `market_payment.clientIp`, `userAgent`: nulled this many days after `createdAt` (covers card dispute windows). */
        const val PII_NETWORK_RETENTION_DAYS = 400L

        /** `market_payment_event.body`: nulled after this many days (01 section 6.3). */
        const val EVENT_BODY_DAYS = 180L

        /** `REJECTED` payment events are deleted after this many days. */
        const val REJECTED_EVENT_DAYS = 14L

        /** `market_block` rows are deleted this many days after `expiresAt`. */
        const val BLOCK_PURGE_DAYS = 30L

        /** Webhook deliveries that were delivered are kept this many days, those that died this many (the panel list of deliveries). */
        const val WEBHOOK_SUCCEEDED_DAYS = 30L
        const val WEBHOOK_DEAD_DAYS = 90L

        /** The credit self-check runs this long after the first tick (07 section 16.2: 60 s after the plugin start). */
        const val RECONCILE_DELAY_MS = 60_000L

        const val BATCH = 1000
        const val MAX_BATCHES = 20
        const val PENDING_BATCH = 20

        private val logger = LoggerFactory.getLogger(HousekeepingJob::class.java)
    }
}

/** The platform's accounts behind [GuestAdoption]. */
internal class PlatformAccountLookup(private val databaseManager: () -> DatabaseManager) : AccountLookup {
    override suspend fun byUsername(username: String, sqlClient: SqlClient): AccountFacts? =
        databaseManager().userDao.getByUsername(username, sqlClient)?.let { AccountFacts(it.id, it.username, it.email, it.emailVerified) }
}

private object ErasureWiringHolder

@Volatile
private var cachedErasure: Pair<MarketPlugin, PlayerErasureService>? = null

@Volatile
private var cachedHousekeeping: Pair<MarketPlugin, HousekeepingJob>? = null

/** The erasure service on the plugin's beans (`PlayerEventHandler.onDelete`, the order anonymise action and the housekeeping job share one). */
internal fun playerErasureService(plugin: MarketPlugin): PlayerErasureService {
    cachedErasure?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(ErasureWiringHolder) {
        cachedErasure?.takeIf { it.first === plugin }?.second ?: buildErasure(plugin).also { cachedErasure = plugin to it }
    }
}

private fun buildErasure(plugin: MarketPlugin): PlayerErasureService {
    val context = plugin.beans
    val databaseManager = { context.getBean(DatabaseManager::class.java) }
    val orderDao = context.getBean(MarketOrderDao::class.java)
    val locks = Locks(orderDao, context.getBean(MarketOrderItemDao::class.java), context.getBean(MarketRedemptionDao::class.java), context.getBean(MarketCreditAccountDao::class.java))
    val db = MarketDb({ databaseManager().getSqlClient() as Pool }, SystemClock)

    return PlayerErasureService(
        clock = SystemClock, db = db, locks = locks, orderService = orderService(plugin), credits = creditService(plugin), prefix = { orderDao.prefix() },
        client = { databaseManager().getSqlClient() },
        endSubscriptions = { userId ->
            subscriptionService(plugin).onUserDeleted(db, { after -> paymentService(plugin).runAfterCommit(after) }, userId)
        },
        afterCommit = { after -> paymentService(plugin).runAfterCommit(after) },
        labelsDir = plugin.pluginDataFolder.toPath().resolve("labels")
    )
}

/** The housekeeping job on the plugin's beans; one per plugin instance (it keeps its own schedule). */
internal fun housekeepingJob(plugin: MarketPlugin): HousekeepingJob {
    cachedHousekeeping?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(ErasureWiringHolder) {
        cachedHousekeeping?.takeIf { it.first === plugin }?.second ?: buildHousekeeping(plugin).also { cachedHousekeeping = plugin to it }
    }
}

private fun buildHousekeeping(plugin: MarketPlugin): HousekeepingJob {
    val context = plugin.beans
    val databaseManager = { context.getBean(DatabaseManager::class.java) }
    val orderDao = context.getBean(MarketOrderDao::class.java)
    val db = MarketDb({ databaseManager().getSqlClient() as Pool }, SystemClock)
    val client: suspend () -> SqlClient = { databaseManager().getSqlClient() }

    return HousekeepingJob(
        clock = SystemClock, prefix = { orderDao.prefix() }, client = client, throttle = abuseWiring(plugin).throttle,
        reconciler = CreditReconciler(SystemClock, orderDao.prefix(), client), erasure = playerErasureService(plugin),
        adoption = GuestAdoption(db, { orderDao.prefix() }, client, PlatformAccountLookup(databaseManager))
    )
}

internal fun housekeepingTask(job: HousekeepingJob): MarketScheduler.Job = MarketScheduler.Job("housekeeping", MarketScheduler.HOUSEKEEPING_MS) { job.runOnce() }
