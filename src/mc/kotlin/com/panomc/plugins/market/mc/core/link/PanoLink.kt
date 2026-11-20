package com.panomc.plugins.market.mc.core.link

import com.panomc.plugins.market.mc.core.platform.McLog
import com.panomc.plugins.market.mc.core.platform.SyncTransport
import com.panomc.plugins.market.mc.core.wire.MarketSyncMessage
import com.panomc.plugins.market.mc.core.wire.MarketSyncRequest
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.panomc.plugins.pano.core.platform.PlatformExtensions
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

    /** Where Pano's `MARKET_WAKE` pushes go (reasons, players). A link that cannot receive pushes ignores it. */
    fun onWake(listener: (reasons: List<String>, players: List<String>) -> Unit) {}

    /** Whether Pano's pushes reach this component right now; while `false` the component polls as it always did. */
    fun pushActive(): Boolean = false

    /** Stops receiving pushes (the component stops). */
    fun closePush() {}

    /** Reports every connection change to [listener]; `false` when the link has no such events (then the component polls [connected]). */
    fun onConnectionChanged(listener: (Boolean) -> Unit): Boolean = false

    /** Why this link cannot work on this server, `null` when it can. */
    fun requirementProblem(): String? = null
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
/**
 * The real link over `pano-mc-plugin` (its `PlatformExtensions`, so it needs a pano-mc-plugin that has them, see [requirementProblem]):
 * the connection and Pano's pushes arrive as events, nothing is polled. [log] given: the pushes are received too.
 */
class CorePanoLink(private val log: McLog? = null, private val platformManager: () -> PlatformManager?) : PanoLink {
    @Volatile
    private var wakeListener: ((List<String>, List<String>) -> Unit)? = null

    @Volatile
    private var connectionListener: ((Boolean) -> Unit)? = null

    private val handler = MarketWakeHandler { reasons, players -> wakeListener?.invoke(reasons, players) }

    @Volatile
    private var registered = false

    override fun connected(): Boolean = PlatformExtensions.isConnected() && platformManager() != null

    override fun onConnectionChanged(listener: (Boolean) -> Unit): Boolean {
        val wrapped: (Boolean) -> Unit = { up -> listener(up && platformManager() != null) }
        connectionListener = wrapped
        PlatformExtensions.addConnectionListener(wrapped)
        return true
    }

    override fun onWake(listener: (reasons: List<String>, players: List<String>) -> Unit) {
        wakeListener = listener
        if (log == null) return
        registered = PlatformExtensions.registerMessageHandler(handler)
        if (!registered) log.warn("pano-mc-plugin refused the Market push handler; the Market component checks Pano for news on a timer instead.")
    }

    override fun pushActive(): Boolean = registered && wakeListener != null

    override fun requirementProblem(): String? = Companion.requirementProblem()

    override fun closePush() {
        wakeListener = null
        registered = false
        PlatformExtensions.unregisterMessageHandler(handler)
        connectionListener?.let { PlatformExtensions.removeConnectionListener(it) }
        connectionListener = null
    }

    override suspend fun awaitSync(request: MarketSyncRequest, timeoutMs: Long): MarketSyncMessage {
        val pm = platformManager() ?: throw IllegalStateException("Pano is not initialised")
        return pm.sendMessageAwaitResponse<MarketSyncMessage>(request, MarketSyncMessage::class.java, timeoutMs)
    }

    companion object {
        /** The pano-mc-plugin release whose `PlatformExtensions` this component needs (the build compiles against it). */
        const val REQUIRED_PANO_MC = "1.2.0"

        /** Where pano-mc-plugin is downloaded. */
        const val PANO_MC_RELEASES = "https://github.com/PanoMC/pano-mc-plugin/releases"

        /** The manager of the running Pano core of [main] (`getPano()` builds it on first use), `null` while unavailable. */
        fun managerOf(main: PanoPluginMain): PlatformManager? = try {
            main.getPano().platformManager
        } catch (_: Throwable) {
            null
        }

        /** Why this server's pano-mc-plugin cannot run the component (it predates `PlatformExtensions`), `null` when it can. */
        fun requirementProblem(): String? = try {
            // a call, not the constant (a const is copied into this class and would never load PlatformExtensions)
            PlatformExtensions.isConnected()
            null
        } catch (_: LinkageError) {
            tooOld()
        }

        private fun tooOld() =
            "Pano Market needs pano-mc-plugin $REQUIRED_PANO_MC or newer, this server runs an older one. Update it from the panel or $PANO_MC_RELEASES; the Market component stays off until then."
    }
}
