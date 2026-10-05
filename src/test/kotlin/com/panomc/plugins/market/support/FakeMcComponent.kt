package com.panomc.plugins.market.support

import com.google.gson.Gson
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.event.server.MarketSyncEventRequest
import com.panomc.plugins.market.service.McServerInfo
import com.panomc.plugins.market.service.McServerLink
import com.panomc.plugins.market.service.McSyncService
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The platform side of the readiness as memory (17 section 4 S7): the servers that exist in Pano and the ones that hold a live connection. A server added with
 * [add] exists and is connected; [disconnect] and [remove] move it to `OFFLINE` and `REMOVED`.
 */
class FakeMcLink : McServerLink {
    private val existing = ConcurrentHashMap.newKeySet<Long>()
    private val connected = ConcurrentHashMap.newKeySet<Long>()

    fun add(id: Long, connected: Boolean = true) {
        existing += id

        if (connected) this.connected += id
    }

    fun connect(id: Long) {
        connected += id
    }

    fun disconnect(id: Long) {
        connected -= id
    }

    fun remove(id: Long) {
        existing -= id
        connected -= id
    }

    override suspend fun exists(serverId: Long, sqlClient: SqlClient): Boolean = serverId in existing

    override fun isConnected(serverId: Long): Boolean = serverId in connected

    override suspend fun servers(sqlClient: SqlClient): List<McServerInfo> =
        existing.sorted().map { McServerInfo(it, "server$it", "PAPER", false) }
}

/** What one [FakeMcComponent.sync] exchanged: the request as Pano decoded it, the response as it went over the wire, and whether the component saw it. */
class SyncReply(val request: MarketSyncEventRequest, val wire: JsonObject, val delivered: Boolean, val reached: Boolean = true) {
    val accepted: Boolean get() = wire.getBoolean("accepted")
    val reason: String? get() = wire.getString("reason")
    val pollAfterMs: Long get() = wire.getLong("pollAfterMs")
    val acked: List<String> get() = wire.getJsonArray("acked").map { it as String }
    val cancel: List<String> get() = wire.getJsonArray("cancel").map { it as String }
    val deliveries: JsonArray get() = wire.getJsonArray("deliveries")
    val offeredKeys: List<String> get() = deliveries.map { (it as JsonObject).getString("key") }
    val broadcasts: JsonArray get() = wire.getJsonArray("broadcasts")
    val resultKeys: List<String> get() = request.results.map { it.key }
}

/**
 * An in-memory Minecraft component (17 section 4 S7, 5.5): it implements the nine requirements of 08 section 8.2 and speaks the wire of 08 section 8.1 to the real
 * [McSyncService]. A request is built as JSON the way the component encodes it and decoded with Gson like `ServerManager.onServerWrite` does, the response is
 * encoded with the platform serializer (`PlatformMessage.encode`) and read back as JSON, so what the tests prove is the real handler on the real wire.
 *
 * - **De-duplication by key** (rule 2): a key seen before is never executed again; its stored result is reported again.
 * - **Requires online** (rule 4): a delivery whose player is not [online] is stored `QUEUED` and reported so; [online] (the join) executes it, unless `expiresAt` passed
 *   (then `EXPIRED`).
 * - **Results kept until acked** (rule 6): every finished (or queued) record that Pano has not acknowledged is reported again on every sync.
 * - **Cancel** (rule 5): a `QUEUED` key becomes `CANCELLED`, a finished key reports its stored result, an unknown key is answered with `UNKNOWN`.
 * - **Per-player order** (rule 9): the offers of one response run in ascending `id` per player.
 * - A response refused by Pano (`accepted = false`) executes nothing but its `acked` still counts; a local switch ([deliveriesEnabled] = false) answers every
 *   delivery `FAILED / DISABLED_LOCALLY` (rule 8).
 *
 * Scripted by the test: `online("Steve")`, `offline("Steve")`, `sync()`, `failNext(code)`, `loseNextResponse()`, `version = "x"`.
 */
class FakeMcComponent(
    private val service: McSyncService,
    val serverId: Long,
    private val clock: Clock,
    var version: String
) {
    var platform: String = "PAPER"
    var protocol: Int = 1
    var capacity: Int = 20
    var luckPerms: Boolean = true
    var vault: Boolean = false
    var placeholderApi: Boolean = false
    var configHash: String? = null

    /** The local switch `deliveries: false` of the component's config (rule 8). */
    var deliveriesEnabled: Boolean = true

    /** How many results one request carries; the component of 19 section 6.1 sends at most 100, a test raises it to prove Pano's own cap. */
    var maxResults: Int = 100

    /** A component that executes but never reports anything (a broken link back): its results stay unacknowledged and are reported as soon as this is `false` again. */
    var muteResults: Boolean = false

    private class Rec(
        val key: String, val id: Long, val kind: String, val player: String, val requiresOnline: Boolean, val expiresAt: Long?,
        val commands: List<String>, val permission: JsonObject?
    ) {
        var state = "NEW"
        var code: String? = null
        var message: String? = null
        var executedAt: Long? = null
        var commandResults: List<JsonObject> = emptyList()

        /** True while the current state was not acknowledged by Pano. */
        var dirty = false
    }

    private val records = LinkedHashMap<String, Rec>()
    private val onlinePlayers = HashSet<String>()
    private val failures = ArrayDeque<String>()
    private val unknownCancels = LinkedHashSet<String>()
    private var lostResponses = 0
    private var lostRequests = 0

    /** Every execution in order (a failed attempt included): a key listed twice would be a double delivery. */
    val executedKeys = ArrayList<String>()

    /** The commands run on the console, in order. */
    val commandLog = ArrayList<String>()

    /** `op nodes` of every permission delivery applied. */
    val permissionLog = ArrayList<String>()

    /** The reason of the last refusal (`null` after an accepted response). */
    var lastRefusal: String? = null
        private set

    private val server = Server(
        id = serverId, name = "server$serverId", motd = "", host = "127.0.0.1", port = 25565, playerCount = 0, maxPlayerCount = 0, type = ServerType.PAPER,
        version = "1.21", favicon = "", status = ServerStatus.ONLINE, startTime = 0, aesKey = ""
    )

    fun stateOf(key: String): String? = records[key]?.state

    fun queuedKeys(): List<String> = records.values.filter { it.state == "QUEUED" }.map { it.key }

    fun knownKeys(): Set<String> = records.keys.toSet()

    /** A result for [key] that this component never produced by running anything (a forged or confused report), sent with the next request. */
    fun invent(key: String, status: String) {
        records[key] = Rec(key, 0, "COMMAND", "nobody", false, null, emptyList(), null).also {
            it.state = status
            it.dirty = true
        }
    }

    /** The next executed delivery ends `FAILED` with [code] (a command error, a missing LuckPerms, ...). */
    fun failNext(code: String) {
        failures.addLast(code)
    }

    /** The next response is processed by Pano but never reaches the component (the connection dropped): its offers and acknowledgements are lost. */
    fun loseNextResponse() {
        lostResponses++
    }

    /** The next request never reaches Pano (the connection dropped before it was sent): the component keeps every result and the offers stay unanswered. */
    fun loseNextRequest() {
        lostRequests++
    }

    /** [name] joins: every `QUEUED` record of that player runs in `id` order, an expired one ends `EXPIRED` (19 section 6.2, join hook). */
    fun online(name: String) {
        onlinePlayers += name.lowercase()

        for (rec in records.values.filter { it.state == "QUEUED" && it.player.equals(name, ignoreCase = true) }.sortedBy { it.id }) {
            if (rec.expiresAt != null && clock.now() > rec.expiresAt) finish(rec, "EXPIRED", null, null) else execute(rec)
        }
    }

    fun offline(name: String) {
        onlinePlayers -= name.lowercase()
    }

    /** One request, one response (08 section 8.1). The response is applied unless [loseNextResponse] swallowed it. */
    suspend fun sync(): SyncReply {
        val results = JsonArray()

        if (!muteResults) {
            for (rec in records.values.filter { it.dirty && it.state != "NEW" }.take(maxResults)) results.add(resultOf(rec))
            for (key in unknownCancels) results.add(JsonObject().put("key", key).put("status", "UNKNOWN").put("commands", JsonArray()))
        }

        val json = JsonObject()
            .put("event", "MARKET_SYNC").put("eventId", UUID.randomUUID().toString()).put("componentVersion", version).put("protocol", protocol)
            .put("platform", platform).put("luckPerms", luckPerms).put("vault", vault).put("placeholderApi", placeholderApi)
            .put("queued", records.values.count { it.state == "QUEUED" }).put("capacity", capacity).put("configHash", configHash).put("results", results)
        val request = Gson().fromJson(json.encode(), MarketSyncEventRequest::class.java)

        if (lostRequests > 0) {
            lostRequests--

            return SyncReply(request, JsonObject(), delivered = false, reached = false)
        }

        val response = service.handle(request, server)

        response.eventId = request.eventId

        val wire = JsonObject(response.encode())

        if (lostResponses > 0) {
            lostResponses--

            return SyncReply(request, wire, delivered = false)
        }

        apply(wire)

        return SyncReply(request, wire, delivered = true)
    }

    private fun resultOf(rec: Rec): JsonObject {
        val json = JsonObject().put("key", rec.key).put("status", rec.state)

        rec.code?.let { json.put("code", it) }
        rec.message?.let { json.put("message", it) }
        rec.executedAt?.let { json.put("executedAt", it) }

        return json.put("commands", JsonArray(rec.commandResults))
    }

    private fun apply(wire: JsonObject) {
        val accepted = wire.getBoolean("accepted")

        // rule 6: whatever Pano acknowledged is forgotten, also in a refusal
        for (key in wire.getJsonArray("acked").map { it as String }) {
            records[key]?.dirty = false
            unknownCancels -= key
        }

        if (!accepted) {
            lastRefusal = wire.getString("reason")

            return
        }

        lastRefusal = null

        // rule 5
        for (key in wire.getJsonArray("cancel").map { it as String }) {
            val rec = records[key]

            when {
                rec == null -> unknownCancels += key
                rec.state == "QUEUED" -> finish(rec, "CANCELLED", null, null)
                else -> rec.dirty = true
            }
        }

        // rule 9
        val offers = wire.getJsonArray("deliveries").map { it as JsonObject }.sortedWith(compareBy({ it.getJsonObject("player").getString("username").lowercase() }, { it.getLong("id") }))

        for (offer in offers) {
            val key = offer.getString("key")
            val known = records[key]

            // rule 2: a known key is never executed again, its stored result is reported again
            if (known != null) {
                if (known.state != "NEW") known.dirty = true

                continue
            }

            val permission = offer.getJsonObject("permission")
            val rec = Rec(
                key, offer.getLong("id"), offer.getString("kind"), offer.getJsonObject("player").getString("username"), offer.getBoolean("requiresOnline"),
                offer.getLong("expiresAt"), offer.getJsonArray("commands").map { it as String }, permission
            )

            records[key] = rec

            when {
                !deliveriesEnabled -> finish(rec, "FAILED", "DISABLED_LOCALLY", "deliveries are switched off in the local config")
                rec.expiresAt != null && clock.now() > rec.expiresAt -> finish(rec, "EXPIRED", null, null)
                rec.requiresOnline && rec.player.lowercase() !in onlinePlayers -> finish(rec, "QUEUED", null, null)
                else -> execute(rec)
            }
        }
    }

    private fun execute(rec: Rec) {
        executedKeys += rec.key

        if (failures.isNotEmpty()) {
            finish(rec, "FAILED", failures.removeFirst(), "scripted failure")

            return
        }

        if (rec.kind == "PERMISSION") {
            permissionLog += "${rec.permission!!.getString("op")} ${rec.permission.getJsonArray("nodes").joinToString(",")}"
        } else {
            rec.commandResults = rec.commands.mapIndexed { i, c -> JsonObject().put("index", i).put("ok", true).also { _ -> commandLog += c.replace("{uuid}", "00000000-0000-0000-0000-000000000000") } }
        }

        rec.executedAt = clock.now()

        finish(rec, "DONE", null, null)
    }

    private fun finish(rec: Rec, state: String, code: String?, message: String?) {
        rec.state = state
        rec.code = code
        rec.message = message
        rec.dirty = true
    }
}
