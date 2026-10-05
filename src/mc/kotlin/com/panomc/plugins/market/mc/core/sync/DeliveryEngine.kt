package com.panomc.plugins.market.mc.core.sync

import com.panomc.plugins.market.mc.core.platform.DeliverySettings
import com.panomc.plugins.market.mc.core.platform.DispatchResult
import com.panomc.plugins.market.mc.core.platform.McClock
import com.panomc.plugins.market.mc.core.platform.McLog
import com.panomc.plugins.market.mc.core.platform.McPlatform
import com.panomc.plugins.market.mc.core.platform.PermissionOutcome
import com.panomc.plugins.market.mc.core.store.CommandOutcome
import com.panomc.plugins.market.mc.core.store.DeliveryRecord
import com.panomc.plugins.market.mc.core.store.DisplayInfo
import com.panomc.plugins.market.mc.core.store.JournalOp
import com.panomc.plugins.market.mc.core.store.PermissionSpec
import com.panomc.plugins.market.mc.core.store.PlayerIdentity
import com.panomc.plugins.market.mc.core.store.RecordResult
import com.panomc.plugins.market.mc.core.store.RecordState
import com.panomc.plugins.market.mc.core.store.StateStore
import com.panomc.plugins.market.mc.core.store.StoreWriteException
import com.panomc.plugins.market.mc.core.wire.CommandResult
import com.panomc.plugins.market.mc.core.wire.DeliveryKind
import com.panomc.plugins.market.mc.core.wire.MarketSyncMessage
import com.panomc.plugins.market.mc.core.wire.MarketSyncRequest
import com.panomc.plugins.market.mc.core.wire.MarketWire
import com.panomc.plugins.market.mc.core.wire.RefusalReason
import com.panomc.plugins.market.mc.core.wire.ResultCode
import com.panomc.plugins.market.mc.core.wire.ResultStatus
import com.panomc.plugins.market.mc.core.wire.SyncBroadcast
import com.panomc.plugins.market.mc.core.wire.SyncDelivery
import com.panomc.plugins.market.mc.core.wire.SyncResult
import java.util.concurrent.atomic.AtomicReference

data class EngineOptions(
    val maxResultsPerRequest: Int = 100,
    /** `capacity = min(capacityMax, inFlightMax - inFlight)` (19 section 6.1). */
    val capacityMax: Int = 20,
    val inFlightMax: Int = 100,
    val maxDeliveriesPerResponse: Int = 100,
    val maxCancelsPerResponse: Int = 100,
    val seenBroadcastIds: Int = 200,
    /** A finished record is kept at least this long after `ackedAt` (19 section 5: 30 days). */
    val retentionMs: Long = 30L * 24 * 60 * 60 * 1000,
    val maxPollMs: Long = 60_000,
    /** Polling interval while Pano refuses the component (19 section 6.2 step 1). */
    val rejectedPollMs: Long = 30_000,
    /** While in recovery mode a `pollAfterMs = 0` ("more waiting") must not turn into a busy loop. */
    val recoveryPollMs: Long = 5_000,
    val configRepullMs: Long = 30_000,
    val maxErrorChars: Int = 200
)

/** One sync request and what it reported, so the matching response can only acknowledge what was actually sent. */
class PreparedSync internal constructor(val request: MarketSyncRequest, internal val reported: Map<String, String>)

data class ApplyOutcome(
    val accepted: Boolean,
    val reason: String?,
    /** Wait this long before the next sync (0 = immediately). Already clamped. */
    val pollAfterMs: Long,
    /** A delivery was executed, queued, expired or cancelled while applying (the loop should report soon). */
    val localChange: Boolean
)

data class EngineStatus(
    val queued: Int = 0,
    val running: Int = 0,
    val unacked: Int = 0,
    val records: Int = 0,
    val recoveryMode: Boolean = false,
    val storeHealthy: Boolean = true,
    /** `null` before the first response, then whether Pano accepted the last one. */
    val accepted: Boolean? = null,
    val rejectionReason: String? = null,
    val lastSyncAt: Long? = null,
    val marketVersion: String? = null
)

data class RecoveryPreview(
    val since: Long,
    val quarantine: String?,
    val reason: String?,
    /** Keys of the lost store without an acknowledged result: Pano may re-offer them and they would run again. */
    val salvagedUnackedKeys: Int,
    /** The last answer while in recovery mode said that Pano still has deliveries for this server. */
    val panoHasWaiting: Boolean
)

/**
 * The delivery state machine of one server (19 section 6, 08 section 8.2): applies `MARKET_SYNC` responses, executes
 * deliveries exactly once, queues "requires online" ones, keeps every result until Pano acknowledged it.
 *
 * Confined to the engine thread (see `McScheduler`); [status] is the only member another thread may call.
 *
 * Safety rules that hold everywhere in this class:
 * - nothing is dispatched unless its `started` journal entry was durably written first; a failed write means "not
 *   executed, not reported", Pano re-offers the key later;
 * - a key that is in the store is never dispatched again, whatever Pano offers;
 * - an acknowledgement only counts for a result that this very request carried;
 * - in recovery mode or with a failing journal no new delivery is accepted and `capacity` is 0.
 */
class DeliveryEngine(
    private val store: StateStore,
    private val platform: McPlatform,
    private val settings: DeliverySettings,
    private val clock: McClock,
    private val log: McLog,
    private val callbacks: EngineCallbacks,
    val componentVersion: String,
    private val options: EngineOptions = EngineOptions()
) {
    private val unknownReports = LinkedHashMap<String, Long>()
    private val pendingFinishes = LinkedHashMap<String, JournalOp.Finished>()
    private val seenBroadcasts = LinkedHashSet<Long>()
    private var lastRejection: String? = null
    private var rejectionReason: String? = null
    private var accepted: Boolean? = null
    private var lastSyncAt: Long? = null
    private var marketVersion: String? = null
    private var panoHasWaiting = false
    private var lastConfigHash: String? = null
    private var lastConfigAt = 0L
    private var blockedLogged = false
    private val statusRef = AtomicReference(EngineStatus())

    init {
        publishStatus()
    }

    /** Safe to call from any thread. */
    fun status(): EngineStatus = statusRef.get()

    // ---- building a request --------------------------------------------------------------------------------------

    private class Entry(val time: Long, val key: String, val result: SyncResult, val status: String)

    fun buildSyncRequest(): PreparedSync {
        retryPendingFinishes()
        val entries = ArrayList<Entry>()
        store.reportable().forEach { r ->
            if (r.state.finished) entries.add(Entry(r.finishedAt ?: r.receivedAt, r.key, toWire(r), r.state.name))
            else entries.add(Entry(r.receivedAt, r.key, SyncResult(r.key, ResultStatus.QUEUED), ResultStatus.QUEUED))
        }
        unknownReports.forEach { (key, at) ->
            if (store.get(key) == null) entries.add(Entry(at, key, SyncResult(key, ResultStatus.UNKNOWN), ResultStatus.UNKNOWN))
        }
        val chosen = entries.sortedWith(compareBy<Entry>({ it.time }, { it.key })).take(options.maxResultsPerRequest)
        val request = MarketSyncRequest(
            componentVersion = componentVersion,
            protocol = MarketWire.PROTOCOL,
            platform = platform.platformName,
            luckPerms = platform.luckPermsAvailable(),
            vault = platform.vaultAvailable(),
            placeholderApi = platform.placeholderApiAvailable(),
            queued = store.waitingCount(),
            capacity = capacity(),
            configHash = callbacks.cachedConfigHash(),
            results = chosen.map { it.result }
        )
        return PreparedSync(request, chosen.associate { it.key to it.status })
    }

    private fun toWire(r: DeliveryRecord): SyncResult {
        val res = r.result
        return SyncResult(
            key = r.key,
            status = r.state.name,
            code = res?.code,
            message = res?.message,
            executedAt = res?.executedAt,
            commands = res?.commands?.map { CommandResult(it.index, it.ok, it.error) } ?: emptyList()
        )
    }

    private fun blocked(): Boolean = store.recovery != null || !store.healthy || pendingFinishes.isNotEmpty()

    private fun capacity(): Int {
        if (blocked()) return 0
        val inFlight = store.waitingCount() + store.runningCount()
        return minOf(options.capacityMax, options.inFlightMax - inFlight).coerceAtLeast(0)
    }

    // ---- applying a response -------------------------------------------------------------------------------------

    fun applyResponse(prepared: PreparedSync, response: MarketSyncMessage): ApplyOutcome {
        val now = clock.now()
        lastSyncAt = now
        marketVersion = nullable(response.marketVersion)
        var changed = false
        retryPendingFinishes()

        // Pano applies results before it checks the version (08 section 8.3 step 3 precedes step 4), so the acknowledgements
        // of a refused response are valid: a server that is updated late does not re-report forever.
        applyAcks(prepared, list(response.acked), now)

        if (!response.accepted) {
            noteRejection(response)
            publishStatus()
            return ApplyOutcome(false, nullable(response.reason), options.rejectedPollMs, false)
        }
        noteAccepted()

        changed = applyCancels(list(response.cancel), now) or changed
        changed = applyDeliveries(list(response.deliveries), now) or changed
        applyBroadcasts(list(response.broadcasts))
        checkConfigHash(nullable(response.configHash), now)

        var poll = response.pollAfterMs.coerceIn(0L, options.maxPollMs)
        if (store.recovery != null) {
            panoHasWaiting = response.pollAfterMs == 0L || list(response.deliveries).isNotEmpty()
            poll = maxOf(poll, options.recoveryPollMs)
        }
        if (changed) callbacks.safely("onLocalChange") { onLocalChange() }
        publishStatus()
        return ApplyOutcome(true, null, poll, changed)
    }

    private fun applyAcks(prepared: PreparedSync, acked: List<String?>, now: Long) {
        val ops = ArrayList<JournalOp>()
        val seen = HashSet<String>()
        for (key in acked) {
            if (key == null || !seen.add(key)) continue
            val reportedStatus = prepared.reported[key] ?: continue
            if (reportedStatus == ResultStatus.UNKNOWN) {
                unknownReports.remove(key)
                continue
            }
            val r = store.get(key) ?: continue
            if (r.waiting) {
                if (reportedStatus == ResultStatus.QUEUED && !r.queuedAcked) ops.add(JournalOp.QueuedAcked(key))
            } else if (r.state.finished) {
                // An ack of the QUEUED report must not swallow the final result that finished in the meantime.
                if (reportedStatus == r.state.name && r.ackedAt == null) ops.add(JournalOp.Acked(key, now))
            }
        }
        if (ops.isNotEmpty()) commitOrLog(ops)
    }

    private fun applyCancels(cancel: List<String?>, now: Long): Boolean {
        val ops = ArrayList<JournalOp>()
        var changed = false
        val seen = HashSet<String>()
        for (key in cancel.take(options.maxCancelsPerResponse)) {
            if (key == null || !seen.add(key)) continue
            val r = store.get(key)
            when {
                r == null -> {
                    // Never received: answer UNKNOWN in the next request (08 section 8.2 rule 5).
                    if (!unknownReports.containsKey(key)) {
                        unknownReports[key] = now
                        while (unknownReports.size > MAX_UNKNOWN_REPORTS) unknownReports.remove(unknownReports.keys.first())
                    }
                    changed = true
                }
                r.waiting -> {
                    ops.add(JournalOp.Finished(key, RecordState.CANCELLED, RecordResult(message = "cancelled by Pano"), now))
                    changed = true
                }
                r.state.finished && r.ackedAt != null -> {
                    // Pano asks about a key it acknowledged: report the stored result again.
                    ops.add(JournalOp.Unacked(key))
                    changed = true
                }
                else -> Unit // finished and not acknowledged (re-reported anyway), or running
            }
        }
        if (ops.isNotEmpty() && !commitOrLog(ops)) return false
        return changed
    }

    private fun applyBroadcasts(broadcasts: List<SyncBroadcast?>) {
        for (b in broadcasts) {
            if (b == null) continue
            if (!seenBroadcasts.add(b.id)) continue
            while (seenBroadcasts.size > options.seenBroadcastIds) seenBroadcasts.remove(seenBroadcasts.first())
            val text = nullable(b.text)
            if (text.isNullOrBlank() || !settings.broadcastEnabled) continue
            callbacks.safely("showBroadcast") { showBroadcast(text) }
        }
    }

    private fun checkConfigHash(current: String?, now: Long) {
        if (current == null || current == callbacks.cachedConfigHash()) return
        if (current != lastConfigHash || now - lastConfigAt >= options.configRepullMs) {
            lastConfigHash = current
            lastConfigAt = now
            callbacks.safely("onConfigHashChanged") { onConfigHashChanged(current) }
        }
    }

    // ---- deliveries -----------------------------------------------------------------------------------------------

    /** Outcome of reading one offered delivery. */
    private sealed class Offer {
        class Valid(val key: String, val d: SyncDelivery, val player: PlayerIdentity) : Offer()
        class Invalid(val key: String, val d: SyncDelivery?, val reason: String) : Offer()
        object Skip : Offer()
    }

    private fun applyDeliveries(offered: List<SyncDelivery?>, now: Long): Boolean {
        if (offered.isEmpty()) return false
        if (blocked()) {
            if (!blockedLogged) {
                blockedLogged = true
                log.warn(
                    when {
                        store.recovery != null -> "Pano offered deliveries while the Market state is being recovered; they were ignored. Run /panomarket recover from the console."
                        else -> "Pano offered deliveries while the Market journal is not writable; they were ignored and will be offered again."
                    }
                )
            }
            return false
        }
        blockedLogged = false

        val offers = offered.take(options.maxDeliveriesPerResponse).map { read(it) }
        val order = offers.mapIndexedNotNull { i, o -> if (o is Offer.Skip) null else i to o }
            .sortedWith(compareBy({ idOf(it.second) }, { it.first }))
        var changed = false
        for ((_, offer) in order) {
            if (pendingFinishes.isNotEmpty()) {
                // An earlier delivery of this response ran but its result is not durable yet: nothing new runs before it is.
                log.warn("A result could not be written to the Market journal; the remaining deliveries of this response were left for Pano to offer again.")
                return changed
            }
            try {
                changed = admit(offer, now) or changed
            } catch (e: StoreWriteException) {
                log.error("The Market journal refused a write; the remaining deliveries of this response were left for Pano to offer again.", e)
                return changed
            }
        }
        return changed
    }

    private fun idOf(o: Offer): Long = when (o) {
        is Offer.Valid -> o.d.id
        is Offer.Invalid -> o.d?.id ?: Long.MAX_VALUE
        Offer.Skip -> Long.MAX_VALUE
    }

    /** Reads one offered delivery without ever throwing on a malformed (partly null) object. */
    private fun read(d: SyncDelivery?): Offer {
        if (d == null) return Offer.Skip
        val key: String? = nullable(d.key)
        if (key.isNullOrBlank()) {
            log.warn("Pano offered a delivery without a key; ignored.")
            return Offer.Skip
        }
        val username = nullable(nullable(d.player)?.username)?.trim()
        if (username.isNullOrEmpty()) return Offer.Invalid(key, d, "missing player")
        val player = PlayerIdentity(username, nullable(d.player.uuid)?.takeIf { it.isNotBlank() })
        val kind: String? = nullable(d.kind)
        when (kind) {
            DeliveryKind.COMMAND -> {
                val commands = nullable(d.commands) ?: return Offer.Invalid(key, d, "no commands")
                if (commands.isEmpty()) return Offer.Invalid(key, d, "no commands")
                commands.forEachIndexed { i, c ->
                    val command: String? = nullable(c)
                    if (command == null || command.isBlank()) return Offer.Invalid(key, d, "command $i is empty")
                    if (command.any { it == '\n' || it == '\r' || it == '\u0000' }) return Offer.Invalid(key, d, "command $i contains a line break")
                }
            }
            DeliveryKind.PERMISSION -> {
                val p = nullable(d.permission) ?: return Offer.Invalid(key, d, "no permission payload")
                val nodes = nullable(p.nodes)
                if (nullable(p.op) != "ADD" && nullable(p.op) != "REMOVE") return Offer.Invalid(key, d, "unknown permission op")
                if (nodes == null || nodes.isEmpty() || nodes.any { nullable(it).isNullOrBlank() }) return Offer.Invalid(key, d, "no permission nodes")
            }
            else -> return Offer.Invalid(key, d, "unknown kind '$kind'")
        }
        return Offer.Valid(key, d, player)
    }

    private fun newRecord(d: SyncDelivery, key: String, player: PlayerIdentity?, now: Long): DeliveryRecord = DeliveryRecord(
        key = key,
        id = d.id,
        kind = nullable(d.kind) ?: "",
        phase = nullable(d.phase) ?: "",
        player = player ?: PlayerIdentity(nullable(nullable(d.player)?.username)?.trim() ?: "", null),
        requiresOnline = d.requiresOnline,
        expiresAt = d.expiresAt,
        issuer = nullable(d.issuer),
        commands = if (nullable(d.kind) == DeliveryKind.COMMAND) list(d.commands).filterNotNull() else emptyList(),
        permission = nullable(d.permission)?.takeIf { nullable(d.kind) == DeliveryKind.PERMISSION }
            ?.let { PermissionSpec(it.op, list(it.nodes).filterNotNull(), it.expiresAt) },
        display = nullable(d.display)?.let { DisplayInfo(nullable(it.productName), nullable(it.orderPublicId), it.gift, nullable(it.from)) },
        state = RecordState.QUEUED,
        result = null,
        receivedAt = now
    )

    /** Returns whether anything changed locally. */
    private fun admit(offer: Offer, now: Long): Boolean {
        when (offer) {
            Offer.Skip -> return false
            is Offer.Invalid -> {
                val known = store.get(offer.key)
                if (known != null) return reofferKnown(known)
                val rec = newRecord(offer.d ?: return false, offer.key, null, now)
                log.warn("Delivery ${offer.key} was refused: ${offer.reason}.")
                store.commit(
                    listOf(JournalOp.Received(rec), JournalOp.Finished(offer.key, RecordState.FAILED, RecordResult(ResultCode.INVALID_PAYLOAD, offer.reason, null), now))
                )
                return true
            }
            is Offer.Valid -> Unit
        }
        offer as Offer.Valid
        val known = store.get(offer.key)
        if (known != null) return reofferKnown(known)

        val rec = newRecord(offer.d, offer.key, offer.player, now)
        if (!settings.deliveriesEnabled) {
            finishAtOnce(rec, RecordState.FAILED, RecordResult(ResultCode.DISABLED_LOCALLY, "deliveries are switched off in the local config of this server", null), now)
            return true
        }
        if (rec.expiresAt != null && rec.expiresAt <= now) {
            finishAtOnce(rec, RecordState.EXPIRED, RecordResult(message = "expired before it could run"), now)
            return true
        }
        // Only `requiresOnline` + absent is queued (19 section 6.2 step 4, 08 section 8.2 rule 4); everything else runs now.
        // A record is never held just because an earlier one of the same player waits: a held record could only be released
        // by a join (a cancel or an expiry of the blocker would leave it waiting), and an undo (REVOKE / EXPIRE) must not
        // wait for a player who stays away. Grant-before-revoke is Pano's predecessor gate (08 section 11.4).
        var present = isPresent(rec.player.username)
        if (present && store.waitingFor(rec.player.username).isNotEmpty()) {
            // The join of this player was missed (or still on its way): their queue runs first, in id order.
            val ran = drain(rec.player.username)
            if (ran.isNotEmpty()) callbacks.safely("onQueueDrained") { onQueueDrained(rec.player.username, ran) }
            // A result that could not be written stops everything new; Pano offers this key again.
            if (pendingFinishes.isNotEmpty()) return ran.isNotEmpty()
            present = isPresent(rec.player.username)
        }
        if (!present && rec.requiresOnline) {
            store.commit(JournalOp.Received(rec))
            return true
        }
        store.commit(listOf(JournalOp.Received(rec), JournalOp.Started(rec.key, clock.now())))
        runAndFinish(rec.key)
        return true
    }

    /** Presence as the adapter reports it; a throwing adapter counts as "not present" (nothing is executed on a guess). */
    private fun isPresent(username: String): Boolean = try {
        platform.isPresent(username)
    } catch (e: VirtualMachineError) {
        throw e
    } catch (t: Throwable) {
        log.warn("The platform could not tell whether $username is online (${t.message}); treated as not online.")
        false
    }

    /** A key Pano offers again is never executed again; its stored result (or `QUEUED`) is reported again (rule 2). */
    private fun reofferKnown(r: DeliveryRecord): Boolean {
        if ((r.state.finished && r.ackedAt != null) || (r.waiting && r.queuedAcked)) {
            return commitOrLog(listOf(JournalOp.Unacked(r.key)))
        }
        return false
    }

    private fun finishAtOnce(rec: DeliveryRecord, state: RecordState, result: RecordResult, now: Long) {
        store.commit(listOf(JournalOp.Received(rec), JournalOp.Finished(rec.key, state, result, now)))
    }

    /** Executes a record whose `started` entry is durable, then journals the outcome. */
    private fun runAndFinish(key: String) {
        val running = store.get(key) ?: return
        val finish = run(running)
        try {
            store.commit(finish)
        } catch (e: StoreWriteException) {
            // The commands ran; the result must not be lost. Keep it and retry before anything else happens.
            pendingFinishes[key] = finish
            log.error("The result of delivery $key could not be written to the Market journal; it is kept in memory and retried.", e)
        }
    }

    private fun retryPendingFinishes() {
        if (pendingFinishes.isEmpty()) return
        val it = pendingFinishes.entries.iterator()
        while (it.hasNext()) {
            val (key, finish) = it.next()
            try {
                if (store.get(key)?.running == true) store.commit(finish)
                it.remove()
            } catch (e: StoreWriteException) {
                return
            }
        }
        publishStatus()
    }

    private fun run(rec: DeliveryRecord): JournalOp.Finished = when (rec.kind) {
        DeliveryKind.COMMAND -> runCommands(rec)
        DeliveryKind.PERMISSION -> runPermission(rec)
        else -> JournalOp.Finished(rec.key, RecordState.FAILED, RecordResult(ResultCode.INVALID_PAYLOAD, "unknown kind '${rec.kind}'", null), clock.now())
    }

    private fun runCommands(rec: DeliveryRecord): JournalOp.Finished {
        val needsUuid = rec.commands.any { it.contains(UUID_TOKEN) }
        val uuid = if (needsUuid) {
            // The present player's UUID, else Pano's hint, else the one the platform would assign (19 section 6.3).
            safeUuid(rec.player.username) ?: rec.player.uuid ?: platform.offlineUuid(rec.player.username)
        } else {
            null
        }
        val outcomes = ArrayList<CommandOutcome>()
        rec.commands.forEachIndexed { index, template ->
            val command = if (uuid != null) template.replace(UUID_TOKEN, uuid) else template
            val result = try {
                platform.dispatchConsole(command)
            } catch (e: VirtualMachineError) {
                throw e
            } catch (t: Throwable) {
                DispatchResult(false, t.message ?: t.javaClass.simpleName)
            }
            outcomes.add(CommandOutcome(index, result.ok, if (result.ok) null else trim(result.error ?: "the command failed")))
        }
        val failed = outcomes.count { !it.ok }
        val now = clock.now()
        return if (failed == 0) {
            JournalOp.Finished(rec.key, RecordState.DONE, RecordResult(null, null, now, outcomes), now)
        } else {
            JournalOp.Finished(
                rec.key, RecordState.FAILED,
                RecordResult(ResultCode.COMMAND_ERROR, "$failed of ${outcomes.size} commands failed", now, outcomes), now
            )
        }
    }

    private fun safeUuid(username: String): String? = try {
        platform.playerUuid(username)
    } catch (e: Exception) {
        null
    }

    private fun runPermission(rec: DeliveryRecord): JournalOp.Finished {
        val perm = rec.permission
        val now = clock.now()
        if (perm == null) return JournalOp.Finished(rec.key, RecordState.FAILED, RecordResult(ResultCode.INVALID_PAYLOAD, "no permission payload", null), now)
        if (!settings.luckPermsEnabled || !platform.luckPermsAvailable()) {
            val why = if (!settings.luckPermsEnabled) "LuckPerms is switched off in the effective config of this server" else "LuckPerms is not installed on this server"
            return JournalOp.Finished(rec.key, RecordState.FAILED, RecordResult(ResultCode.LUCKPERMS_MISSING, why, null), now)
        }
        val outcome: PermissionOutcome = try {
            platform.applyPermission(rec.player.username, safeUuid(rec.player.username) ?: rec.player.uuid, perm.op, perm.nodes, perm.expiresAt)
        } catch (e: VirtualMachineError) {
            throw e
        } catch (t: Throwable) {
            PermissionOutcome(false, t.message ?: t.javaClass.simpleName)
        }
        val done = clock.now()
        return if (outcome.ok) {
            JournalOp.Finished(rec.key, RecordState.DONE, RecordResult(null, null, done), done)
        } else {
            JournalOp.Finished(rec.key, RecordState.FAILED, RecordResult(ResultCode.PERMISSION_ERROR, trim(outcome.error ?: "LuckPerms refused the change"), done), done)
        }
    }

    // ---- joins, expiry, purge, recovery ------------------------------------------------------------------------------

    /**
     * A player became present (authenticated join, or arrived on the network): every waiting record of that username is
     * executed in `id` order, expired ones become `EXPIRED` (19 section 6.2). Returns the records as they ended.
     */
    fun onPlayerPresent(username: String): List<DeliveryRecord> {
        if (username.isBlank()) return emptyList()
        retryPendingFinishes()
        if (!store.healthy || pendingFinishes.isNotEmpty()) return emptyList()
        val ran = try {
            drain(username)
        } catch (e: StoreWriteException) {
            log.error("The Market journal refused a write while running the queue of $username; it stays queued.", e)
            emptyList()
        }
        if (ran.isNotEmpty()) {
            callbacks.safely("onLocalChange") { onLocalChange() }
            callbacks.safely("onQueueDrained") { onQueueDrained(username, ran) }
        }
        publishStatus()
        return ran
    }

    private fun drain(username: String): List<DeliveryRecord> {
        val ran = ArrayList<DeliveryRecord>()
        for (waiting in store.waitingFor(username)) {
            val now = clock.now()
            if (waiting.expiresAt != null && waiting.expiresAt <= now) {
                store.commit(JournalOp.Finished(waiting.key, RecordState.EXPIRED, RecordResult(message = "expired before the player joined"), now))
            } else if (!settings.deliveriesEnabled) {
                store.commit(
                    JournalOp.Finished(
                        waiting.key, RecordState.FAILED,
                        RecordResult(ResultCode.DISABLED_LOCALLY, "deliveries are switched off in the local config of this server", null), now
                    )
                )
            } else {
                // The join event reached the engine through an async hop and may be old; the player can have left in the
                // meantime (lobby transfer, kick, quit), also while an earlier delivery of this very loop was running. A
                // command for an absent player does nothing yet would be reported DONE, so presence is asked right before
                // each start and the rest stays waiting for the next join.
                if (waiting.requiresOnline && !isPresent(username)) break
                store.commit(JournalOp.Started(waiting.key, now))
                runAndFinish(waiting.key)
            }
            store.get(waiting.key)?.let { ran.add(it) }
            if (pendingFinishes.isNotEmpty()) break
        }
        return ran
    }

    /**
     * Runs the queue of every waiting player who is present now. A join is a one-shot event: it can be dropped while the
     * journal is failing, and a player who is online when the component starts never produces one. This sweep (every
     * [expireDue] tick and once at start) makes those up, so a purchase never waits for a relog of a player who is online.
     */
    fun releasePresent() {
        retryPendingFinishes()
        if (!store.healthy || pendingFinishes.isNotEmpty() || store.recovery != null) return
        val names = LinkedHashMap<String, String>()
        store.waiting().forEach { names.putIfAbsent(it.playerKey, it.player.username) }
        for (name in names.values) {
            if (!store.healthy || pendingFinishes.isNotEmpty()) return
            if (isPresent(name)) onPlayerPresent(name)
        }
    }

    /**
     * The 15 s tick: probes a failed journal, expires waiting records whose `expiresAt` passed (also while the player stays
     * away) and then runs the queue of waiting players who are present ([releasePresent]). Returns how many expired.
     */
    fun expireDue(): Int {
        retryPendingFinishes()
        if (!store.healthy) {
            val back = store.probe()
            publishStatus()
            if (!back) return 0
        }
        val now = clock.now()
        val due = store.waiting().filter { it.expiresAt != null && it.expiresAt <= now }
        var expired = 0
        if (due.isNotEmpty() &&
            commitOrLog(due.map { JournalOp.Finished(it.key, RecordState.EXPIRED, RecordResult(message = "expired before the player joined"), now) })
        ) {
            expired = due.size
            callbacks.safely("onLocalChange") { onLocalChange() }
            publishStatus()
        }
        releasePresent()
        return expired
    }

    /** The daily retention purge: acknowledged records older than 30 days after `ackedAt`; unacknowledged ones stay. */
    fun purge(): Int {
        val keys = store.purgeCandidates(clock.now(), options.retentionMs)
        if (keys.isEmpty()) return 0
        var purged = 0
        for (chunk in keys.chunked(500)) {
            if (!commitOrLog(listOf(JournalOp.Purged(chunk)))) break
            purged += chunk.size
        }
        publishStatus()
        return purged
    }

    /** `null` when the component is not in recovery mode. */
    fun recoveryPreview(): RecoveryPreview? {
        val r = store.recovery ?: return null
        return RecoveryPreview(r.since, r.quarantine, r.reason, r.salvagedUnackedKeys, panoHasWaiting)
    }

    /** `/panomarket recover confirm`: from now on re-offered keys are executed as new ones. */
    fun confirmRecovery(): Boolean {
        val was = store.confirmRecovery()
        if (was) log.warn("Market recovery was confirmed: deliveries Pano offers again will run, even if they ran before the store was lost.")
        panoHasWaiting = false
        publishStatus()
        if (was) callbacks.safely("onLocalChange") { onLocalChange() }
        return was
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private fun commitOrLog(ops: List<JournalOp>): Boolean = try {
        store.commit(ops)
        true
    } catch (e: StoreWriteException) {
        log.error("The Market journal refused a write: ${e.message}", e)
        false
    }

    private fun noteRejection(r: MarketSyncMessage) {
        val reason = nullable(r.reason) ?: "UNKNOWN"
        val signature = "$reason|${nullable(r.marketVersion)}"
        accepted = false
        if (signature == lastRejection) return
        lastRejection = signature
        log.warn(
            when (reason) {
                RefusalReason.VERSION_MISMATCH ->
                    "Pano runs Market ${nullable(r.marketVersion) ?: "?"} but this component is $componentVersion. Deliveries wait until both are exactly the same; " +
                        "download the matching jar in the panel (Market > Servers) and replace this one."
                RefusalReason.PROTOCOL_UNSUPPORTED ->
                    "Pano does not speak protocol ${MarketWire.PROTOCOL} of this component ($componentVersion); update the component. Deliveries wait."
                RefusalReason.MARKET_NOT_READY -> "The Market plugin on Pano is not ready yet; deliveries wait."
                else -> "Pano refused the Market sync: $reason. Deliveries wait."
            }
        )
        rejectionReason = reason
    }

    private fun noteAccepted() {
        if (lastRejection != null) log.info("Pano accepts this Market component again.")
        lastRejection = null
        rejectionReason = null
        accepted = true
    }

    private fun publishStatus() {
        statusRef.set(
            EngineStatus(
                queued = store.waitingCount(),
                running = store.runningCount(),
                unacked = store.reportable().size + unknownReports.size,
                records = store.size,
                recoveryMode = store.recovery != null,
                storeHealthy = store.healthy && pendingFinishes.isEmpty(),
                accepted = accepted,
                rejectionReason = rejectionReason,
                lastSyncAt = lastSyncAt,
                marketVersion = marketVersion
            )
        )
    }

    private fun trim(text: String): String = if (text.length <= options.maxErrorChars) text else text.substring(0, options.maxErrorChars)

    private inline fun EngineCallbacks.safely(what: String, block: EngineCallbacks.() -> Unit) {
        try {
            block()
        } catch (e: VirtualMachineError) {
            throw e
        } catch (t: Throwable) {
            log.error("Market callback $what failed: ${t.message}", t)
        }
    }

    /** A value that decoding may have left null although its Kotlin type says otherwise (Gson sets fields reflectively). */
    private fun <T> nullable(value: T?): T? = value

    private fun <T> list(value: List<T>?): List<T> = value ?: emptyList()

    companion object {
        const val UUID_TOKEN = "{uuid}"
        private const val MAX_UNKNOWN_REPORTS = 1000
    }
}
