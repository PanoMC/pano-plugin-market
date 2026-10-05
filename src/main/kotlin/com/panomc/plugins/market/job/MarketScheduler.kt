package com.panomc.plugins.market.job

import com.panomc.plugins.market.runtime.beans
import com.panomc.platform.db.DatabaseManager
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketCreditAccountDao
import com.panomc.plugins.market.db.dao.MarketEntitlementDao
import com.panomc.plugins.market.db.dao.MarketMailOutboxDao
import com.panomc.plugins.market.db.dao.MarketOrderEventDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketPaymentDao
import com.panomc.plugins.market.db.dao.MarketRedemptionDao
import com.panomc.plugins.market.db.dao.MarketShipmentDao
import com.panomc.plugins.market.routes.panel.server.mcSyncService
import com.panomc.plugins.market.routes.panel.shipping.shippingService
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.routes.api.checkout.PlatformUserDirectory
import com.panomc.plugins.market.routes.api.order.deliveryService
import com.panomc.plugins.market.routes.api.order.entitlementService
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.service.MailOutboxService
import com.panomc.plugins.market.routes.api.order.orderService
import com.panomc.plugins.market.routes.api.order.paymentService
import com.panomc.plugins.market.routes.api.order.webhookService
import com.panomc.plugins.market.routes.api.payment.inboundEventRetryJob
import com.panomc.plugins.market.runtime.MarketRuntime
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.sqlclient.Pool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The one timer of the plugin (00 section 8.5, 17 section 4 S11): a single `vertx.setPeriodic` ([TICK_MS], 5 s) that calls [tick]; [tick] runs every
 * [Job] whose cadence is due, and a job is only its `runOnce` (the number of rows it handled). The timer is a trigger: the work is in the database
 * (`nextAttemptAt <= now`, conditional claims), so a missed or doubled tick changes nothing but latency.
 *
 * - **A job can never stop the tick**: every job body is wrapped in `catch (Throwable)`, `LinkageError` included (a host that lacks an optional class
 *   must not kill the scheduler); only `CancellationException` is rethrown. A failing job is logged, counted in [stats] and tried again at its next slot.
 * - **No re-entrancy**: a job that is still running when its next slot comes is skipped (an `AtomicBoolean` per job), so a slow gateway holds up
 *   nothing but its own job. Due jobs of one tick run side by side; the tick returns when they are done.
 * - **Cadence** is judged by the injected [Clock] (a test drives it with `FakeClock`); a job runs on the first tick, then every `everyMs` after it
 *   started, whether it succeeded or threw.
 * - [enabled] gates the whole tick (the production binding is `MarketRuntime.isReady`: a degraded store runs no job).
 */
class MarketScheduler(
    private val clock: Clock,
    jobs: List<Job>,
    private val enabled: () -> Boolean = { true }
) {
    /** One background job: [run] returns the number of rows it handled. */
    class Job(val name: String, val everyMs: Long, val run: suspend () -> Int) {
        init {
            require(name.isNotBlank()) { "a job needs a name" }
            require(everyMs > 0) { "everyMs must be positive" }
        }
    }

    /** What [stats] says about a job. [lastError] is the class and message of the last failure, kept until a run succeeds. */
    class JobStats(val name: String, val runs: Long, val failures: Long, val lastHandled: Int, val lastError: String?, val lastStartedAt: Long?)

    private class State(val job: Job) {
        val running = AtomicBoolean(false)
        val nextDueAt = AtomicLong(Long.MIN_VALUE)
        val runs = AtomicLong(0)
        val failures = AtomicLong(0)
        val lastHandled = AtomicInteger(0)
        val lastError = AtomicReference<String?>(null)
        val lastStartedAt = AtomicLong(-1)
    }

    private val states = jobs.map { State(it) }

    init {
        require(states.map { it.job.name }.toSet().size == states.size) { "job names must be unique" }
    }

    @Volatile
    private var timerId: Long? = null

    @Volatile
    private var scope: CoroutineScope? = null

    /**
     * Runs every due job once and returns the rows they handled in this call. Never throws except for cancellation. A job that is already running
     * (from an earlier tick) is skipped; a job whose slot has not come is skipped.
     */
    suspend fun tick(): Int {
        val active = try {
            enabled()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.warn("the scheduler gate failed, no job runs this tick: {}", t.toString())

            false
        }

        if (!active) return 0

        val now = clock.now()
        val due = states.filter { now >= it.nextDueAt.get() && it.running.compareAndSet(false, true) }

        if (due.isEmpty()) return 0

        return coroutineScope { due.map { state -> async { runJob(state, now) } }.awaitAll().sum() }
    }

    private suspend fun runJob(state: State, startedAt: Long): Int {
        try {
            // the slot is taken before the body runs: a throwing or slow job is not retried before its next slot
            state.nextDueAt.set(startedAt + state.job.everyMs)
            state.lastStartedAt.set(startedAt)
            state.runs.incrementAndGet()

            val handled = state.job.run()

            state.lastHandled.set(handled)
            state.lastError.set(null)

            return handled
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // LinkageError, NoClassDefFoundError and every other Throwable end here: the tick goes on
            state.failures.incrementAndGet()
            state.lastHandled.set(0)
            state.lastError.set("${t.javaClass.simpleName}: ${t.message.orEmpty().take(300)}")
            logger.error("job {} failed, the scheduler goes on: {}", state.job.name, t.toString())

            return 0
        } finally {
            state.running.set(false)
        }
    }

    /** The counters of every job, in registration order. */
    fun stats(): List<JobStats> = states.map {
        JobStats(it.job.name, it.runs.get(), it.failures.get(), it.lastHandled.get(), it.lastError.get(), it.lastStartedAt.get().takeIf { at -> at >= 0 })
    }

    /** `true` while the periodic timer is armed. */
    val isStarted: Boolean get() = timerId != null

    /**
     * Arms the one periodic timer on [vertx] (idempotent). Each timer fire launches [tick] on the Vert.x dispatcher; ticks that overlap are safe
     * (the per-job guard) and an unexpected failure of a tick is logged, never propagated to the timer.
     */
    @Synchronized
    fun start(vertx: Vertx, tickMs: Long = TICK_MS) {
        if (timerId != null) return

        val runScope = CoroutineScope(SupervisorJob() + vertx.dispatcher())

        scope = runScope
        timerId = vertx.setPeriodic(tickMs) {
            runScope.launch {
                try {
                    tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    logger.error("a scheduler tick failed: {}", t.toString())
                }
            }
        }
    }

    /** Cancels the timer and the work that is still running (idempotent). */
    @Synchronized
    fun stop(vertx: Vertx) {
        timerId?.let { vertx.cancelTimer(it) }
        timerId = null
        scope?.cancel()
        scope = null
    }

    companion object {
        const val TICK_MS = 5_000L

        /** Cadences of the jobs (00 section 8.5, 06 section 12, 12 section 4.3, 08 section 15.5). */
        const val ORDER_EXPIRY_MS = 30_000L
        const val PAYMENT_RECONCILE_MS = 15_000L
        const val WEBHOOK_MS = TICK_MS
        const val MAIL_OUTBOX_MS = 15_000L

        /** `InboundEventRetryJob` (02 section 7.3 step 7: every 60 s). */
        const val INBOUND_RETRY_MS = 60_000L

        /** `ShipmentTrackingJob` (10 section 10.2: every 60 s). */
        const val SHIPMENT_TRACKING_MS = 60_000L

        /** `DeliveryJob` (08 section 17: every tick; its D22 classification keeps its own 30 s cadence). */
        const val DELIVERY_MS = TICK_MS

        /** `EntitlementExpiryJob` (08 section 10.3: every 30 s). */
        const val ENTITLEMENT_EXPIRY_MS = 30_000L

        private val logger = LoggerFactory.getLogger(MarketScheduler::class.java)
    }
}

/**
 * The production binding of [MarketScheduler] (armed by `MarketPlugin` after the bootstrap): the jobs that exist, on the beans of the plugin.
 * Everything runs only while `MarketRuntime.isReady`, so a degraded store (schema verification failed) runs no job.
 *
 * Open seams (each fails closed: nothing is armed that could not do its work):
 * - `MailOutboxJob` is not registered: its `MailComposition` is `UnwiredMailComposition` until MK-142 / MK-146 land (armed now it would end every row
 *   `FAILED RENDER_ERROR`). They add `Job("mail-outbox", MarketScheduler.MAIL_OUTBOX_MS) { mailJob.runOnce() }` to [jobs].
 * - The other workers of 00 section 8.5 (`RefundReconcileJob`, `EntitlementExpiryJob`, `SubscriptionJob`,
 *   `HousekeepingJob`) belong to the slices that build them; each adds one `Job` here (`InboundEventRetryJob` is MK-077's, registered below).
 */
internal object MarketJobs {
    fun scheduler(plugin: MarketPlugin): MarketScheduler = MarketScheduler(SystemClock, jobs(plugin), enabled = { MarketRuntime.isReady })

    fun jobs(plugin: MarketPlugin): List<MarketScheduler.Job> {
        val context = plugin.beans
        val databaseManager = { context.getBean(DatabaseManager::class.java) }
        val sqlClient: suspend () -> io.vertx.sqlclient.SqlClient = { databaseManager().getSqlClient() }
        val orderDao = context.getBean(MarketOrderDao::class.java)
        val paymentDao = context.getBean(MarketPaymentDao::class.java)
        val locks = Locks(orderDao, context.getBean(MarketOrderItemDao::class.java), context.getBean(MarketRedemptionDao::class.java), context.getBean(MarketCreditAccountDao::class.java))
        val db = MarketDb({ databaseManager().getSqlClient() as Pool }, SystemClock)
        val payments = paymentService(plugin)

        val expiry = OrderExpiryJob(SystemClock, db, locks, orderDao, paymentDao, orderService(plugin), payments, sqlClient)
        val reconcile = PaymentReconcileJob(SystemClock, payments, orderDao, paymentDao, sqlClient)
        val webhooks = WebhookJob(webhookService(plugin))

        return listOf(
            MarketScheduler.Job("order-expiry", MarketScheduler.ORDER_EXPIRY_MS) { expiry.runOnce() },
            MarketScheduler.Job("payment-reconcile", MarketScheduler.PAYMENT_RECONCILE_MS) { reconcile.runOnce() },
            MarketScheduler.Job("webhook", MarketScheduler.WEBHOOK_MS) { webhooks.tick() },
            inboundRetry(inboundEventRetryJob(plugin)),
            delivery(DeliveryJob(deliveryService(plugin), SystemClock, servers = mcSyncService(plugin))),
            entitlementExpiry(entitlementExpiryJob(plugin)),
            shipmentTracking(ShipmentTrackingJob(SystemClock, context.getBean(MarketShipmentDao::class.java), shippingService(plugin), sqlClient))
        )
    }

    /** The inline delivery worker (MK-102): promote, claim and execute CREDIT / PERMISSION rows, stale claims, re-assertion, D22. */
    fun delivery(job: DeliveryJob): MarketScheduler.Job = MarketScheduler.Job("delivery", MarketScheduler.DELIVERY_MS) { job.runOnce() }

    /** `EntitlementExpiryJob` on the beans of the plugin; its reminder mail goes through the same outbox as every other mail (`MailOutboxJob` sends it). */
    private fun entitlementExpiryJob(plugin: MarketPlugin): EntitlementExpiryJob {
        val context = plugin.beans
        val databaseManager = { context.getBean(DatabaseManager::class.java) }
        val orderDao = context.getBean(MarketOrderDao::class.java)
        val locks = Locks(orderDao, context.getBean(MarketOrderItemDao::class.java), context.getBean(MarketRedemptionDao::class.java), context.getBean(MarketCreditAccountDao::class.java))
        val config = { currentConfig(plugin) }

        return EntitlementExpiryJob(
            clock = SystemClock, db = MarketDb({ databaseManager().getSqlClient() as Pool }, SystemClock), locks = locks, service = entitlementService(plugin),
            delivery = deliveryService(plugin), entitlements = context.getBean(MarketEntitlementDao::class.java), config = config,
            mail = MailOutboxService(config, SystemClock, context.getBean(MarketMailOutboxDao::class.java), context.getBean(MarketOrderEventDao::class.java)),
            users = PlatformUserDirectory(databaseManager)
        )
    }

    /** The end of timed entitlements (MK-107): `EXPIRED` and the `EXPIRE` rows at expiry, the expiry reminder mail. */
    fun entitlementExpiry(job: EntitlementExpiryJob): MarketScheduler.Job = MarketScheduler.Job("entitlement-expiry", MarketScheduler.ENTITLEMENT_EXPIRY_MS) { job.runOnce() }

    /** Polling of the carriers for the shipments that are due (MK-134). */
    fun shipmentTracking(job: ShipmentTrackingJob): MarketScheduler.Job = MarketScheduler.Job("shipment-tracking", MarketScheduler.SHIPMENT_TRACKING_MS) { job.runOnce() }

    /** The retry of inbound payment traffic as a scheduler job (MK-077): FAILED and crashed `RECEIVED` rows are run again from their stored raw request. */
    fun inboundRetry(job: InboundEventRetryJob): MarketScheduler.Job = MarketScheduler.Job("inbound-retry", MarketScheduler.INBOUND_RETRY_MS) { job.runOnce() }
}
