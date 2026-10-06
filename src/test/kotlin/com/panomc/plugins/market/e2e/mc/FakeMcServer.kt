package com.panomc.plugins.market.e2e.mc

import com.google.gson.Gson
import com.panomc.platform.util.Aes256GcmUtil
import com.panomc.plugins.market.e2e.support.E2eClient
import com.panomc.plugins.market.e2e.support.E2eSession
import com.panomc.plugins.market.mc.core.feature.GameLink
import com.panomc.plugins.market.mc.core.feature.MarketFeatures
import com.panomc.plugins.market.mc.core.feature.ResourceFiles
import com.panomc.plugins.market.mc.core.feature.asControl
import com.panomc.plugins.market.mc.core.link.MarketComponent
import com.panomc.plugins.market.mc.core.link.PanoLink
import com.panomc.plugins.market.mc.core.sync.RuntimeOptions
import com.panomc.plugins.market.mc.core.wire.MarketRequest
import com.panomc.plugins.market.mc.core.wire.MarketSyncMessage
import com.panomc.plugins.market.mc.core.wire.MarketSyncRequest
import com.panomc.plugins.pano.core.platform.PlatformMessage.Companion.responseName
import com.panomc.plugins.pano.core.platform.PlatformMessageResponse
import com.panomc.plugins.pano.core.platform.PlatformRequest
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.crypto.Cipher
import javax.crypto.SecretKey

/**
 * `FakeMcServer` (19 section 13, 17 section 8.3): a Minecraft server that is not a Minecraft server. It walks the same road as the real
 * `pano-mc-plugin`:
 *
 * 1. `POST /api/server/connect` with the platform code and an RSA public key (token + the AES key wrapped for that key come back), the panel accepts the
 *    connect request,
 * 2. `GET /api/server/connection` as a WebSocket with the token, every frame AES-256-GCM like `ServerManager` / `PlatformManager` frame it,
 * 3. the REAL market component of the jar (`mc.core`: `MarketComponent` = state store + delivery engine + sync loop + connection monitor, and
 *    `MarketFeatures` = effective config, commands, join notices) runs on top of it, with [FakeMcPlatform] in place of Spigot.
 *
 * Only the socket client is ours ([link], one class for both seams `PanoLink` and `GameLink`; Core's `PlatformManager` needs a whole Pano plugin around
 * it). It copies what `PlatformManager.sendMessageAwaitResponse` and `onWebsocketTextMessage` do: the frame carries `event` and `eventId`, the answer
 * is matched by `eventId`, its `event` has to be the response name of the expected class, and it is decoded with Gson.
 *
 * What a scenario can do to the road: [dropNext] discards a response that Pano really sent (the component then sees a timeout, an unknown outcome),
 * [restart] swaps the component version (a plugin update + restart, the state directory survives), [disconnect] / [connect].
 */
class FakeMcServer(
    private val session: E2eSession,
    val label: String,
    private val baseDir: Path,
    /** The timeout of one request: short, so a dropped response is noticed quickly. */
    private val requestTimeoutMs: Long = 4_000
) : AutoCloseable {
    val platform = FakeMcPlatform()
    val host = FakeMcHost()
    val log = FakeMcLog(label)

    /** Every frame that went out (`event`, JSON) and every frame of Pano that reached us, in order; a dropped one is listed in [dropped] as well. */
    val sentFrames = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    val receivedFrames = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    val dropped = CopyOnWriteArrayList<Pair<String, JsonObject>>()

    /** The version the component announces; set by [launch] / [restart]. */
    @Volatile
    var componentVersion: String = ""
        private set

    var serverId: Long = 0
        private set

    private var token: String = ""
    private lateinit var aesKey: SecretKey

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var closedByUs = false
    private val pending = ConcurrentHashMap<UUID, CompletableFuture<Pair<String, JsonObject>>>()
    private val partial = StringBuilder()
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dropRules = CopyOnWriteArrayList<DropRule>()
    private val sendLock = Any()

    private class DropRule(val event: String, val matches: (JsonObject) -> Boolean, var left: Int)

    lateinit var component: MarketComponent
        private set
    lateinit var features: MarketFeatures
        private set

    // ---- registration + socket ------------------------------------------------------------------------------------------

    /**
     * The `POST /api/server/connect` + accept road. The server row is `permissionGranted = 1` afterwards and a `COMMAND` action can name it.
     */
    fun register(): Long {
        val keys = KeyPairGenerator.getInstance("RSA").also { it.initialize(2048) }.generateKeyPair()
        val platformCode = session.admin.get("/api/panel/basicData", log = false).ok().obj().getValue("platformServerMatchKey").toString()
        val name = "e2e-mc-$label-" + System.nanoTime().toString(36).takeLast(8)

        val answer = E2eClient(session.env.url, "mc-$label").post(
            "/api/server/connect",
            JsonObject().put("platformCode", platformCode).put("serverName", name).put("host", "127.0.0.1").put("port", 25565).put("playerCount", 0)
                .put("maxPlayerCount", 20).put("serverType", "PAPER").put("serverVersion", "1.21").put("startTime", System.currentTimeMillis())
                .put("publicKey", Base64.getEncoder().encodeToString(keys.public.encoded))
        ).ok().obj()

        // what PlatformManager.connectPlatform does with the answer: RSA-decrypt the wrapped AES key with our private key
        val cipher = Cipher.getInstance("RSA").also { it.init(Cipher.DECRYPT_MODE, keys.private) }
        val wrapped = Base64.getDecoder().decode(answer.getString("encryptionKey"))

        aesKey = Aes256GcmUtil.base64ToSecretKey(String(cipher.doFinal(wrapped)))
        token = answer.getString("token")
        serverId = session.db.long("SELECT `id` FROM `pano_server` WHERE `name` = ? ORDER BY `id` DESC LIMIT 1", name)
            ?: throw AssertionError("the connect request created no server row")

        session.admin.post("/api/panel/servers/$serverId/accept", JsonObject()).ok()

        return serverId
    }

    /** Opens the WebSocket (the platform needs a granted server, [register] first) and waits for the handshake. */
    fun connect() {
        check(socket == null) { "already connected" }
        closedByUs = false
        val uri = URI.create(session.env.url.replaceFirst("http", "ws") + "/api/server/connection")
        val opened = HttpClient.newHttpClient().newWebSocketBuilder().header("Authorization", "Bearer $token").buildAsync(uri, listener)

        socket = opened.get(15, TimeUnit.SECONDS)
    }

    /** Closes the socket the way a stopping server does (the platform notices the close). */
    fun disconnect() {
        val s = socket ?: return

        closedByUs = true
        socket = null
        runCatching { s.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS) }
        pending.values.forEach { it.completeExceptionally(IllegalStateException("closed")) }
        pending.clear()
    }

    private val listener = object : WebSocket.Listener {
        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            partial.append(data)

            if (last) {
                val text = partial.toString()

                partial.setLength(0)
                try {
                    onFrame(text)
                } catch (t: Throwable) {
                    log.error("a frame of Pano could not be handled: ${t.message}")
                }
            }

            webSocket.request(1)

            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String?): CompletionStage<*>? {
            if (socket === webSocket) socket = null
            if (!closedByUs) log.warn("the connection to Pano was closed ($statusCode)")
            pending.values.forEach { it.completeExceptionally(IllegalStateException("closed")) }
            pending.clear()

            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            log.warn("socket error: ${error.javaClass.simpleName}: ${error.message}")
            if (socket === webSocket) socket = null
        }
    }

    private fun onFrame(encrypted: String) {
        val json = JsonObject(Aes256GcmUtil.decrypt(encrypted, aesKey))
        val event = json.getString("event") ?: return
        val eventId = json.getString("eventId")?.let { UUID.fromString(it) }

        receivedFrames.add(event to json)

        val waiting = eventId?.let { pending[it] } ?: return // a push (ping, console, ...) that the market component does not take

        val rule = dropRules.firstOrNull { it.left > 0 && it.event == event && it.matches(json) }

        if (rule != null) {
            // Pano really sent this answer; the component never sees it. It stays parked until its own timeout runs out.
            rule.left--
            dropped.add(event to json)
            log.warn("DROPPED the $event answer of Pano on purpose")

            return
        }

        waiting.complete(event to json)
    }

    /** Discards the next [times] answers of [event] for which [matches] holds (a lost response, MC-E3). */
    fun dropNext(event: String, times: Int = 1, matches: (JsonObject) -> Boolean = { true }) {
        dropRules.add(DropRule(event, matches, times))
    }

    // ---- the one link both seams of the component use --------------------------------------------------------------------

    inner class Link : PanoLink, GameLink {
        override fun connected(): Boolean = socket != null

        override suspend fun awaitSync(request: MarketSyncRequest, timeoutMs: Long): MarketSyncMessage =
            roundTrip(request, MarketSyncMessage::class.java, minOf(timeoutMs, requestTimeoutMs)) as MarketSyncMessage

        override fun <R : PlatformMessageResponse> request(request: MarketRequest, responseType: Class<R>, callback: (R?) -> Unit) {
            scope.launch {
                var answer: R? = null

                try {
                    answer = responseType.cast(roundTrip(request, responseType, requestTimeoutMs))
                } catch (t: Throwable) {
                    log.warn("${request.javaClass.simpleName} got no usable answer (${t.javaClass.simpleName}: ${t.message})")
                } finally {
                    callback(answer)
                }
            }
        }
    }

    val link = Link()

    /** `PlatformManager.sendMessageAwaitResponse`: encode, encrypt, write, wait for the frame with this `eventId`, check the event, decode with Gson. */
    private suspend fun roundTrip(request: PlatformRequest, responseType: Class<out PlatformMessageResponse>, timeoutMs: Long): PlatformMessageResponse {
        val s = socket ?: throw IllegalStateException("Not connected to Pano Platform.")
        val future = CompletableFuture<Pair<String, JsonObject>>()

        pending[request.eventId] = future

        try {
            val text = request.encode()

            sentFrames.add(JsonObject(text).getString("event") to JsonObject(text))
            // the JDK socket allows one outstanding write: concurrent requests (a sync and a purchase) take turns, as the one Vert.x socket of Core does
            val frame = Aes256GcmUtil.encrypt(text, aesKey)

            withContext(Dispatchers.IO) { synchronized(sendLock) { s.sendText(frame, true).get(timeoutMs, TimeUnit.MILLISECONDS) } }

            val (event, json) = withContext(Dispatchers.IO) {
                try {
                    future.get(timeoutMs, TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    throw TimeoutException("no answer within $timeoutMs ms")
                }
            }

            check(responseType.responseName() == event) { "Received response type \"$event\" did not match the expected \"${responseType.responseName()}\"." }

            val body = JsonObject(json.encode()).also {
                it.remove("event")
                it.remove("eventId")
            }

            return gson.fromJson(body.encode(), responseType)
        } finally {
            pending.remove(request.eventId)
        }
    }

    /**
     * The whole road in one call: [register], then [connect] and [start] with [version] (default: exactly the version Pano asks for, as the panel
     * tells it in `requiredVersion` of the server list).
     */
    fun launch(version: String? = null): FakeMcServer {
        register()
        componentVersion = version ?: requiredVersion()
        connect()
        start()

        return this
    }

    // ---- the component on top ---------------------------------------------------------------------------------------------

    /**
     * Builds and starts the real component with [componentVersion]. The state directory is [baseDir]: a second [start] (after [restart]) finds
     * the journal of the first.
     */
    fun start() {
        Files.createDirectories(baseDir)

        val resources = ResourceFiles.reader(MarketFeatures::class.java.classLoader)
        val newFeatures = MarketFeatures.create(baseDir, resources, host, link, { name -> platform.playerUuid(name) }, componentVersion, log)

        check(newFeatures.offReason() == null) { "the component stays off: ${newFeatures.offReason()}" }

        val newComponent = MarketComponent(
            baseDir.resolve("state"), platform, link, log, componentVersion, newFeatures.settings, callbacks = newFeatures.callbacks,
            monitorIntervalMs = 100, transportTimeoutMs = requestTimeoutMs, options = RuntimeOptions(expireEveryMs = 500)
        )

        newFeatures.attach(newComponent.runtime.asControl())
        features = newFeatures
        component = newComponent
        newComponent.start()
    }

    fun stop() {
        if (::component.isInitialized) component.stop()
    }

    /** A plugin update: the component stops, [version] becomes the version it announces, it starts again over the same state directory. */
    fun restart(version: String) {
        stop()
        componentVersion = version
        start()
    }

    /** A player joined (authenticated): the delivery engine drains the player's queue, the features look for pending notices. */
    fun playerJoins(name: String) {
        platform.join(name)
        component.playerPresent(name)
        features.onPlayerPresent(name)
    }

    // ---- reading what Pano knows about this server -------------------------------------------------------------------------

    /** The `GET /api/panel/market/servers` element of this server (04 section 8). */
    fun view(): JsonObject = session.admin.get("/api/panel/market/servers", log = false).ok().obj().getJsonArray("servers").map { it as JsonObject }
        .firstOrNull { it.getLong("id") == serverId } ?: throw AssertionError("server $serverId is not listed")

    /** The version Pano wants (`requiredVersion` of the list). */
    fun requiredVersion(): String = view().getString("requiredVersion")

    /** How many sync requests of this component reached Pano. */
    fun syncRequests(): List<JsonObject> = sentFrames.filter { it.first == "MARKET_SYNC" }.map { it.second }

    fun received(event: String): List<JsonObject> = receivedFrames.filter { it.first == event }.map { it.second }

    // ---- teardown ------------------------------------------------------------------------------------------------------------

    /** Component stopped, socket closed, server removed from Pano (its deliveries must be settled by then). */
    override fun close() {
        runCatching { stop() }
        runCatching { disconnect() }
        runCatching { scope.cancel() }

        if (serverId != 0L) {
            val answer = session.admin.post("/api/panel/servers/$serverId/delete", JsonObject().put("currentPassword", session.env.adminPassword()))

            check(answer.status in 200..299) { "removing the fake server answered ${answer.status} ${answer.error}" }
            serverId = 0
        }
    }
}
