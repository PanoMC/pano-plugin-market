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

    val runtime = DeliveryRuntime(baseDir, platform, settings, transport, callbacks, log, componentVersion, clock, engineScheduler, options)

    private val monitor = ConnectionMonitor(link::connected, monitorScheduler, log, { runtime.connectionChanged(it) }, monitorIntervalMs)

    fun start() {
        try {
            runtime.start()
            monitor.start()
        } catch (t: Throwable) {
            log.error("The Market component could not start: ${t.message}", t)
        }
    }

    fun stop() {
        try {
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
}
