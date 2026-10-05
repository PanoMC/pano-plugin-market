package com.panomc.plugins.market.mc.core.feature

import com.panomc.plugins.market.mc.core.platform.McLog
import com.panomc.plugins.market.mc.core.wire.MarketRequest
import com.panomc.plugins.pano.core.platform.PlatformManager
import com.panomc.plugins.pano.core.platform.PlatformMessageResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The request / response events other than `MARKET_SYNC` (`MARKET_CONFIG`, `MARKET_QUERY`, `MARKET_PURCHASE`,
 * `MARKET_ADMIN`, `MARKET_ECONOMY`; 19 section 7) over the one Pano connection. Callback based so no caller ever blocks
 * a server thread; the callback runs exactly once, on any thread, with the decoded answer or `null` for every failure
 * (not connected, timeout, undecodable). A `null` is "unknown", never "failed": the operation may have been applied.
 */
interface GameLink {
    fun connected(): Boolean

    fun <R : PlatformMessageResponse> request(request: MarketRequest, responseType: Class<R>, callback: (R?) -> Unit)
}

/** [GameLink] over `PlatformManager.sendMessageAwaitResponse`; the manager is looked up on every call (Pano replaces it on re-init). */
class CoreGameLink(
    private val platformManager: () -> PlatformManager?,
    private val log: McLog,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val backstopMs: Long = 2_000
) : GameLink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun connected(): Boolean {
        val pm = try {
            platformManager()
        } catch (_: Throwable) {
            null
        } ?: return false
        return pm.isPlatformConfigured() && pm.getWebSocket() != null
    }

    override fun <R : PlatformMessageResponse> request(request: MarketRequest, responseType: Class<R>, callback: (R?) -> Unit) {
        val once = AtomicBoolean(false)
        fun finish(answer: R?) {
            if (once.compareAndSet(false, true)) {
                try {
                    callback(answer)
                } catch (t: Throwable) {
                    log.error("A Market request callback failed: ${t.message}", t)
                }
            }
        }
        try {
            val job = scope.launch {
                var answer: R? = null
                try {
                    val pm = platformManager() ?: throw IllegalStateException("Pano is not initialised")
                    answer = withTimeout(timeoutMs + backstopMs) { pm.sendMessageAwaitResponse(request, responseType, timeoutMs) }
                } catch (t: Throwable) {
                    if (t is VirtualMachineError) throw t
                    log.warn("${request.javaClass.simpleName} got no usable answer (${t.javaClass.simpleName}: ${t.message ?: "no message"})")
                } finally {
                    finish(answer)
                }
            }
            job.invokeOnCompletion { finish(null) }
        } catch (_: Throwable) {
            finish(null)
        }
    }

    fun close() {
        scope.cancel()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
    }
}
