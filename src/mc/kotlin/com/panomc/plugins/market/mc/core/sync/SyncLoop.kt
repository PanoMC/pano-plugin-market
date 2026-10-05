package com.panomc.plugins.market.mc.core.sync

import com.panomc.plugins.market.mc.core.platform.McLog
import com.panomc.plugins.market.mc.core.platform.McScheduler
import com.panomc.plugins.market.mc.core.platform.McTimer
import com.panomc.plugins.market.mc.core.platform.SyncTransport
import com.panomc.plugins.market.mc.core.wire.MarketSyncMessage

data class LoopOptions(
    /** Wait after a request that got no answer (timeout, no connection). */
    val retryAfterMs: Long = 5_000,
    /** A request still unanswered after this long is abandoned (the transport times out after 10 s on its own). */
    val watchdogMs: Long = 30_000,
    /** More than this many `pollAfterMs = 0` answers in a row are slowed down by [zeroPollDelayMs]. */
    val zeroPollsBeforeDelay: Int = 20,
    val zeroPollDelayMs: Long = 1_000
)

/**
 * The `MARKET_SYNC` loop (19 section 6.1): while connected, send a request, apply the response, wait `pollAfterMs`
 * (0 = again at once) and wake early after any local state change. Exactly one request is in flight at a time.
 *
 * Every method hops onto the engine thread, so it can be called from any thread. A late response (after
 * [connectionChanged] to `false`) is still applied: it is valid data, and dropping it would only make Pano offer the
 * same keys again.
 */
class SyncLoop(
    private val engine: DeliveryEngine,
    private val transport: SyncTransport,
    private val scheduler: McScheduler,
    private val log: McLog,
    private val options: LoopOptions = LoopOptions()
) {
    // Engine-thread state.
    private var running = false
    private var connected = false
    private var inFlight = false
    private var wakeQueued = false
    private var timer: McTimer? = null
    private var watchdog: McTimer? = null
    private var sendId = 0L
    private var zeroPolls = 0

    /** Starts looping as soon as the connection is up. */
    fun start() = scheduler.execute { startNow() }

    fun stop() = scheduler.execute { stopNow() }

    /** Called by the connection monitor (MC-03): `true` = Pano reachable, sync at once; `false` = pause, the queue stays. */
    fun connectionChanged(isConnected: Boolean) = scheduler.execute { connectionNow(isConnected) }

    /** A local state change (delivery executed, queued, expired, cancelled): sync early (08 section 8.2 rule 1). */
    fun wake() = scheduler.execute { wakeNow() }

    internal fun startNow() {
        running = true
        if (connected) scheduleNext(0)
    }

    internal fun connectionNow(isConnected: Boolean) {
        if (connected == isConnected) return
        connected = isConnected
        if (!isConnected) {
            timer?.cancel()
            timer = null
        } else if (running) {
            scheduleNext(0)
        }
    }

    internal fun stopNow() {
        running = false
        timer?.cancel()
        timer = null
        watchdog?.cancel()
        watchdog = null
    }

    internal fun wakeNow() {
        if (!running || !connected) return
        if (inFlight) {
            wakeQueued = true
            return
        }
        scheduleNext(0)
    }

    private fun scheduleNext(delayMs: Long) {
        timer?.cancel()
        timer = scheduler.schedule(delayMs.coerceAtLeast(0)) {
            timer = null
            syncNow()
        }
    }

    private fun syncNow() {
        if (!running || !connected) return
        if (inFlight) {
            wakeQueued = true
            return
        }
        val prepared = engine.buildSyncRequest()
        inFlight = true
        val id = ++sendId
        watchdog?.cancel()
        watchdog = scheduler.schedule(options.watchdogMs) {
            if (inFlight && sendId == id) {
                log.warn("The Market sync request got no answer; trying again.")
                finish(id, prepared, null)
            }
        }
        try {
            transport.send(prepared.request) { response -> scheduler.execute { finish(id, prepared, response) } }
        } catch (t: Throwable) {
            scheduler.execute { finish(id, prepared, null) }
            log.error("Sending the Market sync request failed: ${t.message}", t)
        }
    }

    private fun finish(id: Long, prepared: PreparedSync, response: MarketSyncMessage?) {
        if (id != sendId || !inFlight) return // abandoned by the watchdog, or a duplicate callback
        inFlight = false
        watchdog?.cancel()
        watchdog = null
        var delay: Long
        if (response == null) {
            // A timeout is "unknown", never "failed": nothing was applied.
            delay = options.retryAfterMs
            zeroPolls = 0
        } else {
            val outcome = try {
                engine.applyResponse(prepared, response)
            } catch (e: VirtualMachineError) {
                throw e
            } catch (t: Throwable) {
                log.error("Applying the Market sync response failed: ${t.message}", t)
                null
            }
            delay = outcome?.pollAfterMs ?: options.retryAfterMs
            // A delivery was executed, queued, expired or cancelled while applying: report it soon, not after pollAfterMs.
            if (outcome != null && outcome.localChange) wakeQueued = true
            if (outcome != null && outcome.accepted && delay == 0L) {
                zeroPolls += 1
                if (zeroPolls > options.zeroPollsBeforeDelay) delay = options.zeroPollDelayMs
            } else {
                zeroPolls = 0
            }
        }
        if (wakeQueued) {
            wakeQueued = false
            delay = 0
        }
        if (running && connected) scheduleNext(delay)
    }
}
