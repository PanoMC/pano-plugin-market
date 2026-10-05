package com.panomc.plugins.market.mc.core.sync

import com.panomc.plugins.market.mc.core.platform.DeliverySettings
import com.panomc.plugins.market.mc.core.platform.McClock
import com.panomc.plugins.market.mc.core.platform.McLog
import com.panomc.plugins.market.mc.core.platform.McPlatform
import com.panomc.plugins.market.mc.core.platform.McScheduler
import com.panomc.plugins.market.mc.core.platform.McTimer
import com.panomc.plugins.market.mc.core.platform.SingleThreadScheduler
import com.panomc.plugins.market.mc.core.platform.SyncTransport
import com.panomc.plugins.market.mc.core.platform.SystemMcClock
import com.panomc.plugins.market.mc.core.store.StateStore
import com.panomc.plugins.market.mc.core.store.StoreOptions
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

data class RuntimeStatus(
    val started: Boolean,
    /** `false` when the state store could not be opened at all (read-only data folder): nothing runs. */
    val failed: Boolean,
    val connected: Boolean,
    val engine: EngineStatus
)

data class RuntimeOptions(
    val store: StoreOptions = StoreOptions(),
    val engine: EngineOptions = EngineOptions(),
    val loop: LoopOptions = LoopOptions(),
    /** Waiting records past their `expiresAt` are expired this often even while the player stays away. */
    val expireEveryMs: Long = 15_000,
    /** The retention purge (19 section 5: a daily task). */
    val purgeEveryMs: Long = 24L * 60 * 60 * 1000,
    val purgeFirstAfterMs: Long = 60_000,
    val stopWaitMs: Long = 5_000
)

/**
 * The whole delivery component of one installation (19 sections 4 to 6): state store, engine and sync loop on one
 * engine thread. A platform adapter (MC-03) creates one, calls [start] in `onEnable`, feeds it [connectionChanged]
 * (the once-a-second connection monitor) and [playerPresent] (authenticated join), and calls [stop] in `onDisable`.
 *
 * All methods are safe to call from any thread and return immediately; the disk is only touched on the engine thread.
 * The store is opened on that thread too, so a large journal never delays the server start.
 */
class DeliveryRuntime(
    private val baseDir: Path,
    private val platform: McPlatform,
    private val settings: DeliverySettings,
    private val transport: SyncTransport,
    private val callbacks: EngineCallbacks,
    private val log: McLog,
    private val componentVersion: String,
    private val clock: McClock = SystemMcClock,
    private val scheduler: McScheduler = SingleThreadScheduler("PanoMarket-delivery", log),
    private val options: RuntimeOptions = RuntimeOptions()
) {
    // Engine-thread state.
    private var store: StateStore? = null
    private var engine: DeliveryEngine? = null
    private var loop: SyncLoop? = null
    private var expireTimer: McTimer? = null
    private var purgeTimer: McTimer? = null
    private var connected = false
    private var stopped = false

    @Volatile
    private var started = false

    @Volatile
    private var failed = false

    @Volatile
    private var connectedFlag = false

    @Volatile
    private var lastEngineStatus = EngineStatus()

    /** The `EngineCallbacks` the engine really gets: the caller's, plus waking the loop on every local change. */
    private val wiredCallbacks = object : EngineCallbacks {
        override fun onLocalChange() {
            loop?.wake()
            callbacks.onLocalChange()
        }

        override fun onConfigHashChanged(current: String) = callbacks.onConfigHashChanged(current)
        override fun cachedConfigHash(): String? = callbacks.cachedConfigHash()
        override fun showBroadcast(text: String) = callbacks.showBroadcast(text)
        override fun onQueueDrained(username: String, records: List<com.panomc.plugins.market.mc.core.store.DeliveryRecord>) =
            callbacks.onQueueDrained(username, records)
    }

    fun start() {
        scheduler.execute {
            if (stopped || started) return@execute
            try {
                val s = StateStore.open(baseDir, clock, log, options.store)
                val e = DeliveryEngine(s, platform, settings, clock, log, wiredCallbacks, componentVersion, options.engine)
                val l = SyncLoop(e, transport, scheduler, log, options.loop)
                store = s
                engine = e
                loop = l
                started = true
                l.startNow()
                if (connected) l.connectionNow(true)
                expireTimer = scheduler.schedule(options.expireEveryMs) { expireTick() }
                purgeTimer = scheduler.schedule(options.purgeFirstAfterMs) { purgeTick() }
                // Players who are online already (a /reload, a late enable) never produce a join event: run what waits for them.
                try {
                    e.releasePresent()
                } catch (t: Throwable) {
                    log.error("Running the Market queue of the players who are already online failed: ${t.message}", t)
                }
                lastEngineStatus = e.status()
            } catch (t: Throwable) {
                log.error("The Market delivery component could not open its state in $baseDir and stays idle: ${t.message}", t)
                failed = true
            }
        }
    }

    /** Flushes and closes the store (compaction), cancels the timers. Nothing is sent on shutdown (19 section 4). */
    fun stop() {
        val done = CompletableFuture<Unit>()
        scheduler.execute {
            stopped = true
            expireTimer?.cancel()
            purgeTimer?.cancel()
            loop?.stopNow()
            try {
                store?.close()
            } catch (t: Throwable) {
                log.error("Closing the Market state failed: ${t.message}", t)
            }
            engine = null
            loop = null
            store = null
            started = false
            done.complete(Unit)
        }
        try {
            done.get(options.stopWaitMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
        (scheduler as? SingleThreadScheduler)?.shutdown(options.stopWaitMs)
    }

    /** From the connection monitor: `false -> true` syncs at once, `true -> false` pauses the loop (the queue stays). */
    fun connectionChanged(isConnected: Boolean) {
        connectedFlag = isConnected
        scheduler.execute {
            connected = isConnected
            loop?.connectionNow(isConnected)
        }
    }

    /** A player became present (authenticated join / arrived on the network): run what waits for them. */
    fun playerPresent(username: String) {
        scheduler.execute {
            engine?.onPlayerPresent(username)
            lastEngineStatus = engine?.status() ?: lastEngineStatus
        }
    }

    /** Sync soon, e.g. after an in-game purchase succeeded (19 section 7.3). */
    fun syncSoon() {
        scheduler.execute { loop?.wakeNow() }
    }

    fun status(): RuntimeStatus = RuntimeStatus(started, failed, connectedFlag, engine?.status() ?: lastEngineStatus)

    /** `/panomarket recover`: `null` when the component is not in recovery mode. */
    fun recoveryPreview(): CompletableFuture<RecoveryPreview?> {
        val f = CompletableFuture<RecoveryPreview?>()
        scheduler.execute { f.complete(engine?.recoveryPreview()) }
        return f
    }

    /** `/panomarket recover confirm`: `true` when recovery mode was on. */
    fun confirmRecovery(): CompletableFuture<Boolean> {
        val f = CompletableFuture<Boolean>()
        scheduler.execute { f.complete(engine?.confirmRecovery() ?: false) }
        return f
    }

    private fun expireTick() {
        try {
            engine?.expireDue()
        } finally {
            if (!stopped) expireTimer = scheduler.schedule(options.expireEveryMs) { expireTick() }
        }
    }

    private fun purgeTick() {
        try {
            engine?.purge()
        } finally {
            if (!stopped) purgeTimer = scheduler.schedule(options.purgeEveryMs) { purgeTick() }
        }
    }
}
