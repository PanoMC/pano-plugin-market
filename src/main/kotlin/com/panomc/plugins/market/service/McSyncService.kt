package com.panomc.plugins.market.service

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerManager
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.delivery.DeliveryEvent
import com.panomc.plugins.market.core.delivery.DeliveryRow
import com.panomc.plugins.market.core.delivery.DeliveryRules
import com.panomc.plugins.market.core.delivery.DeliveryStateMachine
import com.panomc.plugins.market.core.delivery.DeliveryTransition
import com.panomc.plugins.market.core.delivery.ResultStatus
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketDeliveryDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketServerStateDao
import com.panomc.plugins.market.db.model.DeliveryActionType
import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.db.model.DeliveryStatus
import com.panomc.plugins.market.db.model.DeliveryTransport
import com.panomc.plugins.market.db.model.MarketDelivery
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketServerState
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.event.server.MarketSyncEventRequest
import com.panomc.plugins.market.event.server.MarketSyncEventResponse
import com.panomc.plugins.market.event.server.SyncBroadcastOffer
import com.panomc.plugins.market.event.server.SyncDeliveryOffer
import com.panomc.plugins.market.event.server.SyncDisplay
import com.panomc.plugins.market.event.server.SyncPermission
import com.panomc.plugins.market.event.server.SyncPlayer
import com.panomc.plugins.market.event.server.SyncResultEntry
import com.panomc.plugins.market.runtime.MarketRuntime
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** What the market knows about the connection to one Minecraft server (08 section 8.5); the first match of the table decides. */
enum class ServerReadiness { REMOVED, OFFLINE, VERSION_MISMATCH, READY, COMPONENT_MISSING }

/** One server the panel lists (04 section 8, `GET /servers`): the platform's name, type and whether it is a proxy. */
class McServerInfo(val id: Long, val name: String, val type: String?, val proxy: Boolean)

/**
 * The platform side of the readiness (17 section 4 S7, S9): which servers exist and which of them hold a live connection. Production is
 * [PlatformMcServerLink]; a test stands in with a mutable fake.
 */
interface McServerLink {
    /** `serverDao.existsById`: a server that was deleted from Pano is `REMOVED`. */
    suspend fun exists(serverId: Long, sqlClient: SqlClient): Boolean

    /** `ServerManager.isConnected`. */
    fun isConnected(serverId: Long): Boolean

    /** The servers the owner accepted, in id order (a server that was never accepted cannot take a delivery). */
    suspend fun servers(sqlClient: SqlClient): List<McServerInfo>
}

/** [McServerLink] over `DatabaseManager.serverDao` and `ServerManager`. */
internal class PlatformMcServerLink(
    private val databaseManager: () -> DatabaseManager,
    private val serverManager: () -> ServerManager
) : McServerLink {
    override suspend fun exists(serverId: Long, sqlClient: SqlClient): Boolean = databaseManager().serverDao.existsById(serverId, sqlClient)

    override fun isConnected(serverId: Long): Boolean = serverManager().isConnected(serverId)

    override suspend fun servers(sqlClient: SqlClient): List<McServerInfo> =
        databaseManager().serverDao.getAllByPermissionGranted(sqlClient).sortedBy { it.id }
            .map { McServerInfo(it.id, it.customName?.takeIf { name -> name.isNotBlank() } ?: it.name, it.type.name, it.type.isProxy) }
}

/** One element of `GET /servers` (04 section 8). */
class ServerView(
    val id: Long,
    val name: String,
    val type: String?,
    val connected: Boolean,
    val proxy: Boolean,
    val mcComponentVersion: String?,
    val requiredVersion: String,
    val marketState: ServerReadiness,
    val waitingDeliveries: Long,
    val queuedDeliveries: Long,
    val downloadUrl: String,
    val platform: String?,
    val integrations: List<String>,
    val settings: JsonObject?
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "id" to id, "name" to name, "type" to type, "connected" to connected, "proxy" to proxy,
        "mcComponentVersion" to mcComponentVersion, "requiredVersion" to requiredVersion, "marketState" to marketState.name,
        "waitingDeliveries" to waitingDeliveries, "queuedDeliveries" to queuedDeliveries, "downloadUrl" to downloadUrl,
        "platform" to platform, "integrations" to integrations, "settings" to settings
    )
}

/**
 * The purchase announcements waiting for a server (19 section 11): in memory, per server, capped, lost on a restart (best effort by design, 19 section 12
 * item 10). An announcement leaves the queue in exactly one `MARKET_SYNC` response. Ids start at the creation time, so they keep growing across a restart of
 * Pano and the component's "ids I have already shown" memory never swallows a new announcement.
 */
class McBroadcastOutbox(clock: Clock, private val capPerServer: Int = CAP) {
    private val queues = ConcurrentHashMap<Long, ArrayDeque<SyncBroadcastOffer>>()
    private val seq = AtomicLong(clock.now())

    /** Queues [text] for [serverId]; the oldest entry is dropped when the queue is full. Answers the id. */
    fun offer(serverId: Long, text: String): Long {
        val id = seq.incrementAndGet()
        val queue = queues.computeIfAbsent(serverId) { ArrayDeque() }

        synchronized(queue) {
            queue.addLast(SyncBroadcastOffer(id, text))

            while (queue.size > capPerServer) queue.removeFirst()
        }

        return id
    }

    /** Everything waiting for [serverId], removed from the queue. */
    fun drain(serverId: Long): List<SyncBroadcastOffer> {
        val queue = queues[serverId] ?: return emptyList()

        return synchronized(queue) {
            val all = queue.toList()

            queue.clear()

            all
        }
    }

    fun size(serverId: Long): Int = queues[serverId]?.let { synchronized(it) { it.size } } ?: 0

    companion object {
        const val CAP = 50
    }
}

/**
 * The Pano side of `MARKET_SYNC` (08 section 8): the pull protocol through which the market's own Minecraft component receives its deliveries. Nothing here
 * sends to a server: the component asks, [handle] answers.
 *
 * [handle] is the algorithm of 08 section 8.3: refuse (`MARKET_NOT_READY`, `PROTOCOL_UNSUPPORTED`), record what the component reports
 * (`market_server_state`, written at most every 30 s unless a value changed, and the in-memory session), apply every result (each in its own transaction, the
 * order lock before the delivery row, **before** the version check, so a component that is updated late loses nothing), refuse a version that differs from the
 * market's (`VERSION_MISMATCH`, exact string comparison), then answer with the keys to cancel and the offers: due rows of the server in `PENDING`,
 * `WAITING_SERVER` and `SENT` rows whose acknowledgement time ran out (D8 / D9), at most `capacity`, and never more than 100 KB. Rows over the re-offer
 * budget are set aside and failed afterwards with the order lock (D10, `UNKNOWN_OUTCOME`; a late `DONE` still wins, D12).
 *
 * The state machine ([DeliveryStateMachine]) decides every transition and [DeliveryService.apply] writes it; this class only chooses which rows to offer, hands
 * the component's answers to the machine and keeps the in-memory sessions the readiness ([readiness], 08 section 8.5) is judged from. [classify] and
 * [expireWaits] are the two job steps of `DeliveryJob` that belong to server rows (D7, D20, D14); sessions are empty after a restart, so [classify] waits one
 * session time after the service was created before it judges anything.
 *
 * Not here: the other `MARKET_*` events (MC-04), the settings that make `configHash` (MC-04 supplies [configHash]), the panel notification
 * `MARKET_DELIVERY_WAITING` (MK-172).
 */
class McSyncService(
    private val db: MarketDb,
    private val locks: Locks,
    private val clock: Clock,
    private val config: () -> MarketConfig,
    private val deliveries: MarketDeliveryDao,
    private val serverStates: MarketServerStateDao,
    private val orders: MarketOrderDao,
    private val orderItems: MarketOrderItemDao,
    private val delivery: DeliveryService,
    private val link: McServerLink,
    private val marketVersion: () -> String,
    private val ready: () -> Boolean = { MarketRuntime.isReady },
    /** The `configHash` the component should hold for a server (`MARKET_CONFIG`, MC-04); `null` = nothing to say. */
    private val configHash: suspend (serverId: Long) -> String? = { null },
    /** The store name for the `{store}` of a purchase announcement. */
    private val storeName: () -> String = { "" },
    val broadcasts: McBroadcastOutbox = McBroadcastOutbox(clock)
) {
    private class Session(val version: String, val accepted: Boolean, val lastSyncAt: Long)

    private class Written(val at: Long, val version: String?, val capabilities: String, val platform: String?, val queued: Int)

    private val sessions = ConcurrentHashMap<Long, Session>()
    private val written = ConcurrentHashMap<Long, Written>()
    private val startedAt = clock.now()

    private fun table(name: String) = "`${deliveries.prefix()}$name`"

    private val deliveryTable get() = table("market_delivery")

    private fun rules(): DeliveryRules = config().let { DeliveryRules(it.deliveryMaxAttempts, it.deliveryAckTimeoutSeconds) }

    // ===== MARKET_SYNC ===================================================================================================

    /** One `MARKET_SYNC` request of [server] (08 section 8.3). Never throws for a bad request; a database failure propagates (the component sees a timeout, never a failure). */
    suspend fun handle(request: MarketSyncEventRequest, server: Server): MarketSyncEventResponse {
        val version = marketVersion()

        if (!ready()) return refusal(REASON_NOT_READY, version)

        if (request.protocol != PROTOCOL) return refusal(REASON_PROTOCOL, version)

        val now = clock.now()

        recordState(server.id, request, now)

        sessions[server.id] = Session(request.componentVersion, request.componentVersion == version, now)

        val acked = LinkedHashSet<String>()

        for (result in request.results.take(MAX_RESULTS)) if (applyResult(server, result)) acked += result.key

        if (request.componentVersion != version) return refusal(REASON_VERSION, version, acked.toList())

        val cancel = cancelKeys(server.id)
        val capacity = request.capacity.coerceIn(0, MAX_CAPACITY)
        val queue = broadcasts.drain(server.id)
        val hash = configHash(server.id)
        val base = MarketSyncEventResponse(
            accepted = true, marketVersion = version, acked = acked.toList(), cancel = cancel, broadcasts = queue, configHash = hash, pollAfterMs = POLL_MS
        )
        val offered = offer(server.id, capacity, base)

        return base.copy(deliveries = offered.deliveries, pollAfterMs = if (offered.more) 0 else POLL_MS)
    }

    private suspend fun recordState(serverId: Long, request: MarketSyncEventRequest, now: Long) {
        val capabilities = buildString {
            append("market-delivery")

            if (request.luckPerms) append(",luckperms")
            if (request.vault) append(",vault")
            if (request.placeholderApi) append(",placeholderapi")
        }
        val platform = request.platform?.trim()?.uppercase()?.take(16)?.takeIf { it.isNotEmpty() }
        val version = request.componentVersion.take(32).takeIf { it.isNotEmpty() }
        val queued = request.queued.coerceAtLeast(0)
        val last = written[serverId]
        val unchanged = last != null && last.version == version && last.capabilities == capabilities && last.platform == platform && last.queued == queued

        if (unchanged && now - last!!.at < STATE_WRITE_EVERY_MS) return

        db.tx { conn ->
            serverStates.upsertSync(
                MarketServerState(
                    serverId = serverId, mcComponentVersion = version, capabilities = capabilities, platform = platform, protocol = request.protocol,
                    queuedCount = queued, lastSeenAt = now, createdAt = now, updatedAt = now
                ),
                conn
            )
        }

        written[serverId] = Written(now, version, capabilities, platform, queued)
    }

    // ===== results (08 section 8.4) ======================================================================================

    /**
     * Applies one reported result in its own transaction: the order is locked first (when the row has one), then the machine decides under a conditional
     * update. An unknown key, a key of another server and a status the machine does not know are logged at WARN and acknowledged (the component must be able
     * to forget them). `false` = a database error: the key is not acknowledged and the component reports it again.
     */
    private suspend fun applyResult(server: Server, result: SyncResultEntry): Boolean {
        val status = statusOf(result.status)

        if (status == null) {
            logger.warn("server {} reported an unknown result status '{}' for key '{}'; acknowledged and ignored", server.id, result.status.take(32), result.key.take(120))

            return true
        }

        try {
            db.txRestartingOnOrderChange { conn ->
                val row = deliveries.getByIdempotencyKey(result.key, conn)

                if (row == null || row.serverId != server.id || row.transport != DeliveryTransport.MARKET_MC) {
                    logger.warn("server {} reported a result for key '{}' that is not one of its deliveries; acknowledged and ignored", server.id, result.key.take(120))

                    return@txRestartingOnOrderChange
                }

                val event = DeliveryEvent.ServerResult(status, result.code?.take(64), result.message?.take(MAX_MESSAGE), resultJson(result))

                withOrder(conn, row.orderId) { delivery.apply(conn, row.id, event) }
            }

            return true
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.error("the result for key '{}' of server {} could not be applied, it is reported again: {}", result.key.take(120), server.id, t.toString())

            return false
        }
    }

    private fun statusOf(text: String): ResultStatus? = ResultStatus.entries.firstOrNull { it.name == text }

    private fun resultJson(result: SyncResultEntry): String {
        val json = JsonObject().put("status", result.status).put("executedAt", result.executedAt)

        result.code?.let { json.put("code", it.take(64)) }
        result.message?.let { json.put("message", it.take(MAX_MESSAGE)) }

        json.put(
            "commands",
            JsonArray(
                result.commands.take(MAX_COMMAND_RESULTS).map { c ->
                    JsonObject().put("index", c.index).put("ok", c.ok).also { o -> c.error?.let { o.put("error", it.take(MAX_MESSAGE)) } }
                }
            )
        )

        return json.encode()
    }

    // ===== offers (08 section 8.3 steps 5 to 7) ============================================================================

    private suspend fun cancelKeys(serverId: Long): List<String> = db.tx { conn ->
        conn.preparedQuery(
            "SELECT `idempotencyKey` FROM $deliveryTable WHERE `serverId` = ? AND `transport` = 'MARKET_MC' AND `cancelRequestedAt` IS NOT NULL " +
                "AND `status` IN ('SENT','QUEUED') ORDER BY `id` LIMIT $MAX_CANCEL"
        ).execute(Tuple.of(serverId)).coAwait().map { it.getString("idempotencyKey") }
    }

    private class Offers(val deliveries: List<SyncDeliveryOffer>, val more: Boolean)

    /** The predecessor gate of 08 section 11.4 for the delivery table alias [d] (the same rule `DeliveryService.claimDue` applies to inline rows). */
    private fun gateOpen(d: String): String =
        "($d.`phase` IN ('GRANT','RENEW') OR $d.`orderItemId` IS NULL OR NOT EXISTS (SELECT 1 FROM $deliveryTable g WHERE g.`orderItemId` = $d.`orderItemId` " +
            "AND g.`serverId` = $d.`serverId` AND g.`phase` IN ('GRANT','RENEW') AND g.`status` IN ('SENDING','SENT','QUEUED')))"

    private fun dueSql(now: Long) =
        "SELECT d.`id` FROM $deliveryTable d WHERE d.`serverId` = ? AND d.`transport` = 'MARKET_MC' AND d.`runAfter` <= $now AND d.`cancelRequestedAt` IS NULL " +
            "AND (d.`status` IN ('PENDING','WAITING_SERVER') OR (d.`status` = 'SENT' AND d.`nextAttemptAt` IS NOT NULL AND d.`nextAttemptAt` <= $now)) " +
            "AND ${gateOpen("d")} ORDER BY d.`id` LIMIT ?"

    /** The undo rows of one server that the offer select may return for the first time (`PENDING` / `WAITING_SERVER`, gate open): the rows D22 has to look at before they go out. */
    private fun undoCandidatesSql(now: Long) =
        "SELECT d.`id` FROM $deliveryTable d WHERE d.`serverId` = ? AND d.`transport` = 'MARKET_MC' AND d.`phase` IN ('EXPIRE','REVOKE') AND d.`orderItemId` IS NOT NULL " +
            "AND d.`status` IN ('PENDING','WAITING_SERVER') AND d.`runAfter` <= $now AND d.`cancelRequestedAt` IS NULL AND ${gateOpen("d")} ORDER BY d.`id` LIMIT ?"

    /**
     * D22 before an offer, per row (08 section 11.4: when nothing the undo row undoes ever took effect, neither the grant nor the revoke command may run). The
     * first `capacity + 1` due undo rows of this server whose gate is open are checked one at a time under their own order lock, which cancels the ones with
     * nothing to undo (`NOTHING_TO_REVOKE`). Answers the ids that were checked; [offer] offers no other undo row. A global pass over every server's rows would let
     * rows of a server that is not ready, whose grant took effect and so stay in the set, push the rows of a ready server out of its batch.
     *
     * A row whose check failed (a contended order, a database error) is left out of the answer and waits for the next sync; the results of this request are applied already.
     */
    private suspend fun checkUndoRows(serverId: Long, capacity: Int): Set<Long> {
        val now = clock.now()
        val ids = db.tx { conn -> conn.preparedQuery(undoCandidatesSql(now)).execute(Tuple.of(serverId, capacity + 1)).coAwait().map { it.getLong("id") } }
        val checked = HashSet<Long>()

        for (id in ids) {
            try {
                delivery.cancelIfNothingDelivered(id)

                checked += id
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logger.warn("the D22 check of delivery {} of server {} failed, the row is not offered in this sync: {}", id, serverId, t.toString())
            }
        }

        return checked
    }

    /**
     * An undo row that is offered for the first time must have been checked by [checkUndoRows] in this call: the check and the locking select are separate
     * transactions, so a row whose gate opened or that became due in between (a result of a concurrent request, a refund, D14) has not been looked at and waits for
     * the next sync. A `SENT` undo row is offered again without a new check: it went out after one, and a grant that took effect stays taken.
     */
    private fun MarketDelivery.needsUndoCheck(): Boolean =
        (phase == DeliveryPhase.EXPIRE || phase == DeliveryPhase.REVOKE) && orderItemId != null && status != DeliveryStatus.SENT

    /**
     * Step 6 and 7: selects (and locks) the due rows of the server, decides each with the machine, serialises the ones that fit into the response budget and
     * applies D8 / D9 to exactly those. D10 rows are set aside and failed after the transaction under the order lock.
     */
    private suspend fun offer(serverId: Long, capacity: Int, base: MarketSyncEventResponse): Offers {
        // D22 first, per row, and bound to what is offered below (08 section 11.4)
        val checked = checkUndoRows(serverId, capacity)
        val exhausted = ArrayList<Long>()
        val offered = ArrayList<SyncDeliveryOffer>()
        var more = false

        db.tx { conn ->
            exhausted.clear()
            offered.clear()

            val now = clock.now()
            val ids = conn.preparedQuery(dueSql(now) + " FOR UPDATE").execute(Tuple.of(serverId, capacity + 1)).coAwait().map { it.getLong("id") }
            val candidates = ArrayList<MarketDelivery>()

            for (id in ids) {
                val row = deliveries.getById(id, conn) ?: continue

                // not looked at by D22 in this call: not offered now, the next sync checks it
                if (row.needsUndoCheck() && row.id !in checked) continue

                when (val decision = DeliveryStateMachine.decide(row.toRow(), DeliveryEvent.Offer, now, rules())) {
                    is DeliveryTransition.Move -> if (decision.to == DeliveryStatus.SENT) candidates += row else exhausted += id
                    else -> Unit
                }
            }

            more = candidates.size > capacity

            var budget = MAX_RESPONSE_BYTES - sizeOf(base)
            val chosen = ArrayList<MarketDelivery>()

            for (row in candidates.take(capacity)) {
                val wire = wireOf(conn, row)

                if (wire == null) {
                    logger.warn("delivery {} of server {} has no usable payload and is not offered", row.id, serverId)

                    continue
                }

                val size = sizeOf(wire) + 1

                if (size > budget) {
                    more = true

                    break
                }

                budget -= size
                chosen += row
                offered += wire
            }

            // D8 / D9: the rows are locked, so the conditional update always wins; a row that did not move is not offered after all
            val moved = HashSet<Long>()

            for (row in chosen) if (delivery.apply(conn, row.id, DeliveryEvent.Offer).moved) moved += row.id

            if (moved.size != chosen.size) {
                offered.removeAll { wire -> wire.id !in moved }
            }
        }

        // D10 needs the order lock and the fulfilment recompute: after the transaction of the offers, one row at a time
        for (id in exhausted) {
            try {
                applyWithOrder(id, DeliveryEvent.Offer)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logger.error("delivery {} could not be failed after its offers ran out: {}", id, t.toString())
            }
        }

        return Offers(offered, more)
    }

    /** The wire form of a row (08 section 8.1), or `null` when its payload cannot be offered (never acknowledged, never marked sent). */
    private suspend fun wireOf(conn: SqlClient, row: MarketDelivery): SyncDeliveryOffer? {
        val payload = runCatching { JsonObject(row.payload) }.getOrNull() ?: return null
        val commands: List<String>
        val permission: SyncPermission?
        val kind: String

        when (row.actionType) {
            DeliveryActionType.COMMAND -> {
                commands = payload.getJsonArray("commands")?.mapNotNull { it as? String }.orEmpty()

                if (commands.isEmpty()) return null

                permission = null
                kind = "COMMAND"
            }

            DeliveryActionType.PERMISSION -> {
                val op = payload.getString("op") ?: return null
                val nodes = payload.getJsonArray("nodes")?.mapNotNull { it as? String }.orEmpty()

                if (nodes.isEmpty()) return null

                commands = emptyList()
                permission = SyncPermission(op, nodes, payload.getLong("expiresAt"))
                kind = "PERMISSION"
            }

            else -> return null
        }

        val order = row.orderId?.let { orders.getById(it, conn) }
        val item = row.orderItemId?.let { orderItems.getById(it, conn) }
        val gift = order?.isGift == true
        val display = if (order == null && item == null) {
            null
        } else {
            SyncDisplay(item?.productName, order?.publicId, gift, if (gift) order?.playerUsername?.takeIf { it.isNotBlank() } else null)
        }

        return SyncDeliveryOffer(
            key = row.idempotencyKey, id = row.id, kind = kind, phase = row.phase.name,
            player = SyncPlayer(row.playerUsername, row.playerUuid?.takeIf { it.isNotBlank() }),
            requiresOnline = row.requiresOnline, expiresAt = row.waitUntil,
            issuer = if (row.orderId != null) "market:order-${row.orderId}" else "market:delivery-${row.id}",
            commands = commands, permission = permission, display = display
        )
    }

    /** The encoded size in bytes (UTF-8) of a part of the response: the budget of 08 section 8.1 is about the JSON on the wire. */
    private fun sizeOf(response: MarketSyncEventResponse): Int = JsonObject.mapFrom(response).encode().toByteArray(Charsets.UTF_8).size

    private fun sizeOf(offer: SyncDeliveryOffer): Int = JsonObject.mapFrom(offer).encode().toByteArray(Charsets.UTF_8).size

    // ===== readiness (08 section 8.5) ======================================================================================

    /** The readiness of [serverId] now (the first match of the table of 08 section 8.5). */
    suspend fun readiness(serverId: Long): ServerReadiness = db.tx { conn -> readiness(serverId, link.exists(serverId, conn)) }

    private fun readiness(serverId: Long, exists: Boolean): ServerReadiness {
        if (!exists) return ServerReadiness.REMOVED

        if (!link.isConnected(serverId)) return ServerReadiness.OFFLINE

        val session = sessions[serverId]

        if (session != null && clock.now() - session.lastSyncAt <= SESSION_TTL_MS) {
            return if (session.accepted) ServerReadiness.READY else ServerReadiness.VERSION_MISMATCH
        }

        return ServerReadiness.COMPONENT_MISSING
    }

    /**
     * D7 and D20 (08 section 8.5, `DeliveryJob.classify`): for every server that still has unfinished rows, `REMOVED` fails them all (`SERVER_REMOVED`), and a server that is
     * not `READY` labels its waiting rows: a `PENDING` row that waited 15 s becomes `WAITING_SERVER`, a `WAITING_SERVER` row whose reason changed gets the new one.
     * A `READY` server is left alone: its rows are offered by its next sync, directly from `WAITING_SERVER` (D8), and there is no give-up for a waiting server.
     * Sessions are empty after a restart, so nothing is judged until one session time has passed since the service was created. Answers the rows that moved.
     */
    suspend fun classify(limit: Int = CLASSIFY_BATCH): Int {
        if (clock.now() - startedAt < SESSION_TTL_MS) return 0

        var moved = 0
        val servers = db.tx { conn ->
            conn.preparedQuery(
                "SELECT DISTINCT `serverId` FROM $deliveryTable WHERE `transport` = 'MARKET_MC' AND `serverId` <> 0 AND `status` IN ($NON_TERMINAL)"
            ).execute().coAwait().map { it.getLong("serverId") }
        }

        for (serverId in servers) {
            val state = readiness(serverId)

            when (state) {
                ServerReadiness.REMOVED -> {
                    val ids = idsOf(serverId, NON_TERMINAL, limit)

                    for (id in ids) if (applyWithOrder(id, DeliveryEvent.ServerRemoved).moved) moved++
                }

                ServerReadiness.READY -> Unit

                ServerReadiness.OFFLINE, ServerReadiness.COMPONENT_MISSING, ServerReadiness.VERSION_MISMATCH -> {
                    val ids = idsOf(serverId, "'PENDING','WAITING_SERVER'", limit)

                    for (id in ids) if (db.tx { conn -> delivery.apply(conn, id, DeliveryEvent.ServerNotReady(codeOf(state))) }.moved) moved++
                }
            }
        }

        return moved
    }

    private fun codeOf(state: ServerReadiness) = when (state) {
        ServerReadiness.OFFLINE -> "SERVER_OFFLINE"
        ServerReadiness.COMPONENT_MISSING -> "COMPONENT_MISSING"
        else -> "VERSION_MISMATCH"
    }

    private suspend fun idsOf(serverId: Long, statuses: String, limit: Int): List<Long> = db.tx { conn ->
        conn.preparedQuery(
            "SELECT `id` FROM $deliveryTable WHERE `serverId` = ? AND `transport` = 'MARKET_MC' AND `status` IN ($statuses) ORDER BY `id` LIMIT ?"
        ).execute(Tuple.of(serverId, limit)).coAwait().map { it.getLong("id") }
    }

    /** D14 (`DeliveryJob.expireWaits`): a `SENT` / `QUEUED` row whose `waitUntil` ran out an hour ago ends `FAILED (ONLINE_WAIT_EXPIRED)`; a later `DONE` still wins (D12). */
    suspend fun expireWaits(limit: Int = CLASSIFY_BATCH): Int {
        val now = clock.now()
        val ids = db.tx { conn ->
            conn.preparedQuery(
                "SELECT `id` FROM $deliveryTable WHERE `transport` = 'MARKET_MC' AND `status` IN ('SENT','QUEUED') AND `waitUntil` IS NOT NULL AND `waitUntil` + ${DeliveryStateMachine.WAIT_GRACE_MS} < ? " +
                    "ORDER BY `id` LIMIT ?"
            ).execute(Tuple.of(now, limit)).coAwait().map { it.getLong("id") }
        }
        var moved = 0

        for (id in ids) if (applyWithOrder(id, DeliveryEvent.WaitExpired).moved) moved++

        return moved
    }

    // ===== GET /servers (04 section 8) =====================================================================================

    /** What `GET /servers` answers: every accepted server with its connection state and what is waiting for it. */
    suspend fun servers(): List<ServerView> = db.tx { conn ->
        val infos = link.servers(conn)
        val states = serverStates.getAll(conn).associateBy { it.serverId }
        val counts = HashMap<Pair<Long, String>, Long>()

        conn.preparedQuery(
            "SELECT `serverId`, `status`, COUNT(*) AS n FROM $deliveryTable WHERE `transport` = 'MARKET_MC' AND `status` IN ('PENDING','WAITING_SERVER','SENT','QUEUED') " +
                "GROUP BY `serverId`, `status`"
        ).execute().coAwait().forEach { counts[it.getLong("serverId") to it.getString("status")] = it.getLong("n") }

        val required = marketVersion()

        infos.map { info ->
            val state = states[info.id]
            val waiting = listOf("PENDING", "WAITING_SERVER", "SENT").sumOf { counts[info.id to it] ?: 0L }
            val platform = state?.platform

            ServerView(
                id = info.id, name = info.name, type = info.type, connected = link.isConnected(info.id), proxy = info.proxy,
                mcComponentVersion = state?.mcComponentVersion, requiredVersion = required, marketState = readiness(info.id, true),
                waitingDeliveries = waiting, queuedDeliveries = counts[info.id to "QUEUED"] ?: 0L,
                downloadUrl = if (platform == "FABRIC") "$DOWNLOAD_URL?platform=fabric" else DOWNLOAD_URL, platform = platform,
                integrations = state?.capabilities.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() && it != "market-delivery" },
                settings = state?.settings?.takeIf { it.isNotBlank() }?.let { runCatching { JsonObject(it) }.getOrNull() }
            )
        }
    }

    // ===== purchase announcements (08 section 8.1, 19 section 9) ============================================================

    /**
     * Queues the in-game announcement of a paid [order] for every server whose component is ready and for which `mcBroadcast` is effective (the per-server override of
     * `market_server_state.settings` wins over the panel default). Skipped for orders that opted out (`hideFromBroadcast`, which also covers a hidden gift), test orders and
     * orders without a product line. Best effort: it is meant to run after the order transaction committed, and a failure here must never touch the order. Answers the
     * number of servers it queued for.
     */
    suspend fun announce(order: MarketOrder, items: List<MarketOrderItem>): Int {
        if (order.hideFromBroadcast || order.testMode) return 0

        val lines = items.filter { it.kind == OrderItemKind.PRODUCT || it.kind == OrderItemKind.BUNDLE }

        if (lines.isEmpty()) return 0

        val template = config().mcBroadcastTemplate
        val player = order.recipientUsername.ifBlank { order.playerUsername }
        val text = renderAnnouncement(template, player, lines.joinToString(", ") { it.productName }, lines.sumOf { it.quantity }, storeName())
        var queued = 0

        if (text.isBlank()) return 0

        val targets = db.tx { conn ->
            val states = serverStates.getAll(conn).associateBy { it.serverId }

            sessions.filter { (id, s) -> s.accepted && clock.now() - s.lastSyncAt <= SESSION_TTL_MS && effectiveBroadcast(states[id]) }.keys.toList()
        }

        for (id in targets) {
            broadcasts.offer(id, text)

            queued++
        }

        return queued
    }

    private fun effectiveBroadcast(state: MarketServerState?): Boolean {
        val override = state?.settings?.takeIf { it.isNotBlank() }?.let { runCatching { JsonObject(it) }.getOrNull() }?.getValue("mcBroadcast")

        return (override as? Boolean) ?: config().mcBroadcast
    }

    // ===== helpers =========================================================================================================

    private suspend fun applyWithOrder(id: Long, event: DeliveryEvent): DeliveryService.Applied =
        db.txRestartingOnOrderChange { conn ->
            val orderId = deliveries.getById(id, conn)?.orderId

            withOrder(conn, orderId) { delivery.apply(conn, id, event) }
        }

    private suspend fun <T> withOrder(conn: io.vertx.sqlclient.SqlConnection, orderId: Long?, block: suspend () -> T): T =
        if (orderId == null) block() else locks.forOrder(conn, orderId, OrderLockScope.PAYMENT) { block() }

    private fun MarketDelivery.toRow() = DeliveryRow(
        sourceType = sourceType, orderItemId = orderItemId, actionId = actionId, actionType = actionType, phase = phase, serverId = serverId,
        unitIndex = unitIndex, attemptGroup = attemptGroup, transport = transport ?: if (serverId != 0L) DeliveryTransport.MARKET_MC else DeliveryTransport.INLINE,
        status = status, attempts = attempts, runAfter = runAfter, nextAttemptAt = nextAttemptAt, cancelRequestedAt = cancelRequestedAt, waitUntil = waitUntil,
        claimedUntil = claimedUntil, sentAt = sentAt, lastErrorCode = lastErrorCode
    )

    companion object {
        const val PROTOCOL = 1

        const val REASON_NOT_READY = "MARKET_NOT_READY"
        const val REASON_PROTOCOL = "PROTOCOL_UNSUPPORTED"
        const val REASON_VERSION = "VERSION_MISMATCH"

        /** The component syncs every 5 s; a server without a sync for this long is `COMPONENT_MISSING` (08 section 8.5). */
        const val SESSION_TTL_MS = 60_000L

        /** `market_server_state` is written at most this often while nothing it holds changed (08 section 8.3 step 2). */
        const val STATE_WRITE_EVERY_MS = 30_000L

        const val POLL_MS = 5_000L
        const val MAX_RESULTS = 100
        const val MAX_CAPACITY = 20
        const val MAX_CANCEL = 50
        const val MAX_RESPONSE_BYTES = 100 * 1024
        const val CLASSIFY_BATCH = 200

        private const val MAX_MESSAGE = 512
        private const val MAX_COMMAND_RESULTS = 100
        private const val NON_TERMINAL = "'PENDING','SCHEDULED','WAITING_SERVER','WAITING_PLAYER','SENT','QUEUED'"

        /** The panel's download of the running jar (04 section 8, `GET /mc-component/download`; MC-04 serves it). */
        const val DOWNLOAD_URL = "/api/panel/market/mc-component/download"

        private val logger = LoggerFactory.getLogger(McSyncService::class.java)

        /** `accepted = false` with a [reason] and, for a version mismatch, the results that were applied before the check (08 section 8.3 step 4). */
        fun refusal(reason: String, marketVersion: String?, acked: List<String> = emptyList()): MarketSyncEventResponse =
            MarketSyncEventResponse(accepted = false, reason = reason, marketVersion = marketVersion, acked = acked, pollAfterMs = POLL_MS)

        /**
         * The announcement text (19 section 9): `&` colour codes of the template become `§`, then `{player}` (a validated username: letters, digits and underscore only),
         * `{product}` (control and colour characters removed), `{quantity}` and `{store}` are substituted; what a player or a product name contains is never
         * interpreted as a colour code.
         */
        fun renderAnnouncement(template: String, player: String, product: String, quantity: Int, store: String): String {
            fun plain(text: String) = text.filter { it >= ' ' && it != '\u007f' && it != '§' }.trim()

            val colored = template.replace(Regex("&([0-9a-fk-orA-FK-OR])"), "§$1")
            val name = player.filter { it.isLetterOrDigit() && it.code < 128 || it == '_' }

            return colored.replace("{player}", name).replace("{product}", plain(product)).replace("{quantity}", quantity.toString()).replace("{store}", plain(store)).trim()
        }
    }
}
