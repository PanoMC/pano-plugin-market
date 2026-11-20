package com.panomc.plugins.market.mc.core.link

import com.panomc.plugins.market.mc.core.platform.DeliverySettings
import com.panomc.plugins.market.mc.core.platform.McClock
import com.panomc.plugins.market.mc.core.platform.McLog
import com.panomc.plugins.market.mc.core.platform.McPlatform
import com.panomc.plugins.market.mc.core.platform.McScheduler
import com.panomc.plugins.market.mc.core.platform.SingleThreadScheduler
import com.panomc.plugins.market.mc.core.platform.SystemMcClock
import com.panomc.plugins.market.mc.core.sync.DeliveryRuntime
import com.panomc.plugins.market.mc.core.sync.EngineCallbacks
import com.panomc.plugins.market.mc.core.sync.RuntimeOptions
import java.nio.file.Path

/**
 * What every platform main class builds in `onEnable` / `onProxyInitialization` (19 section 4): the delivery runtime
 * (state store, engine, sync loop) over the platform adapter and the Pano link, plus the once-a-second connection
 * monitor that feeds it. Nothing here knows Bukkit, BungeeCord or Velocity.
 *
 * [start] never throws; [stop] flushes the store and cancels every task. Nothing is sent on shutdown (19 section 4).
 */
class MarketComponent(
    baseDir: Path,
    platform: McPlatform,
    private val link: PanoLink,
    private val log: McLog,
    componentVersion: String,
    settings: DeliverySettings = DefaultDeliverySettings,
    callbacks: EngineCallbacks = object : EngineCallbacks {},
    clock: McClock = SystemMcClock,
    engineScheduler: McScheduler = SingleThreadScheduler("PanoMarket-delivery", log),
    private val monitorScheduler: McScheduler = SingleThreadScheduler("PanoMarket-connection", log),
    options: RuntimeOptions = RuntimeOptions(),
    monitorIntervalMs: Long = 1_000,
    transportTimeoutMs: Long = PanoLinkTransport.DEFAULT_TIMEOUT_MS
) {
    private val transport = PanoLinkTransport(link, log, transportTimeoutMs)

    val runtime = DeliveryRuntime(
        baseDir, platform, settings, transport, callbacks, log, componentVersion, clock, engineScheduler,
        // with Pano's pushes the sync is a heartbeat only: news arrive as MARKET_WAKE
        options.copy(loop = options.loop.copy(pushActive = link::pushActive))
    )

    private val monitor = ConnectionMonitor(link::connected, monitorScheduler, log, { runtime.connectionChanged(it) }, monitorIntervalMs)

    /** `false` while the link said why it cannot work here (logged at [start]). */
    @Volatile
    var started = false
        private set

    fun start() {
        try {
            link.requirementProblem()?.let { problem ->
                log.error(problem)
                return
            }
            runtime.start()
            // event based when pano-mc-plugin reports its connection; the once-a-second probe only for a link without events (tests)
            if (!link.onConnectionChanged { runtime.connectionChanged(it) }) monitor.start()
            started = true
        } catch (t: Throwable) {
            log.error("The Market component could not start: ${t.message}", t)
        }
    }

    fun stop() {
        try {
            link.closePush()
            monitor.stop()
            runtime.stop()
        } catch (t: Throwable) {
            log.error("Stopping the Market component failed: ${t.message}", t)
        } finally {
            transport.close()
            (monitorScheduler as? SingleThreadScheduler)?.shutdown(1_000)
        }
    }

    /** From the platform's authenticated join / arrival event. */
    fun playerPresent(username: String) = runtime.playerPresent(username)

    /** Whether Pano's pushes reach this component (else everything polls as before). */
    fun pushActive(): Boolean = link.pushActive()

    /**
     * Pano's `MARKET_WAKE`: `SYNC` makes the delivery runtime sync now; every reason (with the players it names) also goes to [listener]
     * (the in-game features: settings, balances, placeholders). Runs on the socket's thread: everything it calls only schedules work.
     */
    fun onWake(listener: (reasons: List<String>, players: List<String>) -> Unit) {
        link.onWake { reasons, players ->
            try {
                if (WAKE_SYNC in reasons) runtime.syncSoon()
                listener(reasons, players)
            } catch (t: Throwable) {
                log.warn("A Market push could not be handled: ${t.message}")
            }
        }
    }

    companion object {
        const val WAKE_SYNC = "SYNC"
    }
}
