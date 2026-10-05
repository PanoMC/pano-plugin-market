package com.panomc.plugins.market.mc.core.link

import com.panomc.plugins.market.mc.core.platform.McLog
import com.panomc.plugins.market.mc.core.platform.McScheduler
import com.panomc.plugins.market.mc.core.platform.McTimer

/**
 * The once-a-second connection monitor of 19 section 4 step 3: `connected = platformManager.isPlatformConfigured() &&
 * platformManager.getWebSocket() != null`, the only public signal pano-mc-plugin offers. [onChange] fires on every
 * change, starting from "not connected": the first probe that answers `true` is a `false -> true` change and makes the
 * runtime sync at once; `true -> false` pauses the loops (the queue stays).
 *
 * A probe that throws counts as "not connected" (Pano is being re-initialised, a platform class is not ready).
 * Everything runs on [scheduler]; the probe must be cheap.
 */
class ConnectionMonitor(
    private val probe: () -> Boolean,
    private val scheduler: McScheduler,
    private val log: McLog,
    private val onChange: (Boolean) -> Unit,
    private val intervalMs: Long = 1_000
) {
    @Volatile
    private var running = false

    @Volatile
    private var last = false

    @Volatile
    private var probeFailureLogged = false
    private var timer: McTimer? = null

    val connected: Boolean get() = last

    fun start() {
        if (running) return
        running = true
        scheduler.execute { tick() }
    }

    fun stop() {
        running = false
        scheduler.execute {
            timer?.cancel()
            timer = null
        }
    }

    private fun tick() {
        if (!running) return
        val now = try {
            probe().also { probeFailureLogged = false }
        } catch (t: Throwable) {
            if (!probeFailureLogged) {
                probeFailureLogged = true
                log.warn("The Pano connection could not be checked, treating it as not connected: ${t.message ?: t.javaClass.simpleName}")
            }
            false
        }
        if (now != last) {
            last = now
            try {
                onChange(now)
            } catch (t: Throwable) {
                log.error("Reacting to the Pano connection change failed: ${t.message}", t)
            }
        }
        if (running) timer = scheduler.schedule(intervalMs) { tick() }
    }
}
