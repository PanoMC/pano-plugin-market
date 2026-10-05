package com.panomc.plugins.market.mc.core.link

import com.panomc.plugins.market.mc.core.platform.McLog
import com.panomc.plugins.market.mc.core.platform.SyncTransport
import com.panomc.plugins.market.mc.core.wire.MarketSyncMessage
import com.panomc.plugins.market.mc.core.wire.MarketSyncRequest
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.platform.PlatformManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The component's view of `pano-mc-plugin`'s connection (19 section 1: no second socket, no second credential).
 * [connected] is the cheap probe of the once-a-second monitor; [awaitSync] is one `MARKET_SYNC` round trip.
 */
interface PanoLink {
    fun connected(): Boolean

    /** One request / response; throws on a missing connection, a write failure, an undecodable answer or after [timeoutMs]. */
    suspend fun awaitSync(request: MarketSyncRequest, timeoutMs: Long): MarketSyncMessage
}

/**
 * `SyncTransport` over a [PanoLink]: callback based, the callback runs exactly once with the decoded answer or `null`
 * for every failure (a timeout is "unknown", never "failed", 19 section 4). Requests run on `Dispatchers.IO`, never on
 * the engine thread or the server main thread.
 */
class PanoLinkTransport(
    private val link: PanoLink,
    private val log: McLog,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val backstopMs: Long = 2_000
) : SyncTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun send(request: MarketSyncRequest, callback: (MarketSyncMessage?) -> Unit) {
        val once = AtomicBoolean(false)
        fun finish(answer: MarketSyncMessage?) {
            if (once.compareAndSet(false, true)) {
                try {
                    callback(answer)
                } catch (t: Throwable) {
                    log.error("The Market sync callback failed: ${t.message}", t)
                }
            }
        }
        try {
            val job = scope.launch {
                var answer: MarketSyncMessage? = null
                try {
                    // The link has its own timeout; this one is the backstop for a link that never returns.
                    answer = withTimeout(timeoutMs + backstopMs) { link.awaitSync(request, timeoutMs) }
                } catch (t: Throwable) {
                    if (t is VirtualMachineError) throw t
                    log.warn("MARKET_SYNC got no usable answer (${t.javaClass.simpleName}: ${t.message ?: "no message"}); treated as unknown")
                } finally {
                    finish(answer)
                }
            }
            // A scope that was cancelled never starts the body (a closed transport): the callback must still run.
            job.invokeOnCompletion { finish(null) }
        } catch (t: Throwable) {
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

/**
 * The real link: `PlatformManager.sendMessageAwaitResponse` (public, `suspend`, bounded). The manager is looked up on
 * every call because Pano replaces it when it re-initialises itself (a `/pano` reload); a lookup that fails is "not
 * connected".
 */
class CorePanoLink(private val platformManager: () -> PlatformManager?) : PanoLink {
    override fun connected(): Boolean {
        val pm = platformManager() ?: return false
        return pm.isPlatformConfigured() && pm.getWebSocket() != null
    }

    override suspend fun awaitSync(request: MarketSyncRequest, timeoutMs: Long): MarketSyncMessage {
        val pm = platformManager() ?: throw IllegalStateException("Pano is not initialised")
        return pm.sendMessageAwaitResponse<MarketSyncMessage>(request, MarketSyncMessage::class.java, timeoutMs)
    }

    companion object {
        /** The manager of the running Pano core of [main] (`getPano()` builds it on first use), `null` while unavailable. */
        fun managerOf(main: PanoPluginMain): PlatformManager? = try {
            main.getPano().platformManager
        } catch (_: Throwable) {
            null
        }
    }
}
