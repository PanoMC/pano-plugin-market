package com.panomc.plugins.market.service

import com.panomc.platform.server.PlatformMessage
import io.vertx.core.Vertx
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * `MARKET_WAKE` (Pano to the Minecraft component): "something changed for you, act now", so nothing waits for the component's next
 * heartbeat. It carries no data: the component reacts with the request it would have made anyway (`MARKET_SYNC`, `MARKET_CONFIG`,
 * a balance or placeholder query), so a lost push only costs time, never data. A component (or a `pano-mc-plugin`) that does not know
 * the event ignores it.
 *
 * [reasons]: [McPush.SYNC] (deliveries to offer or to cancel, broadcasts), [McPush.CONFIG] (in-game settings), [McPush.BALANCE]
 * (credit balances of [players]), [McPush.PLACEHOLDERS] (last buyer, top supporter, goals).
 */
data class MarketWakeMessage(
    val reasons: List<String>,
    val players: List<String>? = null
) : PlatformMessage

/**
 * Sends [MarketWakeMessage]s to the connected game servers. Wakes for one server arriving within [coalesceMs] are merged into one
 * message. Best effort by design: a server that is offline syncs on its own when it reconnects.
 */
class McPush(
    private val vertx: () -> Vertx?,
    private val send: (serverId: Long, message: MarketWakeMessage) -> Boolean,
    private val connectedServers: () -> Collection<Long>,
    private val now: () -> Long = System::currentTimeMillis,
    private val coalesceMs: Long = COALESCE_MS
) {
    private class Pending(val reasons: MutableSet<String> = LinkedHashSet(), val players: MutableSet<String> = LinkedHashSet())

    private val pending = ConcurrentHashMap<Long, Pending>()

    /** Wakes [serverId] for [reasons]; [players] are the names a [BALANCE] wake is about. */
    fun wake(serverId: Long, vararg reasons: String, players: Collection<String> = emptyList()) {
        if (serverId <= 0 || reasons.isEmpty()) return

        var first = false
        val entry = pending.computeIfAbsent(serverId) { first = true; Pending() }

        synchronized(entry) {
            entry.reasons += reasons
            entry.players += players.map { it.trim() }.filter { it.isNotEmpty() }
        }

        if (!first) return

        val v = vertx()

        if (v == null || coalesceMs <= 0) flush(serverId) else v.setTimer(coalesceMs) { flush(serverId) }
    }

    /** Wakes every connected server. */
    fun wakeAll(vararg reasons: String, players: Collection<String> = emptyList()) {
        connectedServers().forEach { wake(it, *reasons, players = players) }
    }

    /**
     * Wakes [serverId] at [at] (a delivery that becomes due later: `runAfter`, a retry's `nextAttemptAt`). Past times wake now; times
     * further away than [MAX_TIMER_MS] are left to the component's heartbeat, which offers them as soon as they are due.
     */
    fun wakeAt(serverId: Long, at: Long, reason: String = SYNC) {
        val wait = at - now()

        if (wait <= 0) return wake(serverId, reason)
        if (wait > MAX_TIMER_MS) return

        val v = vertx() ?: return

        v.setTimer(wait + 50) { wake(serverId, reason) }
    }

    private fun flush(serverId: Long) {
        val entry = pending.remove(serverId) ?: return
        val message = synchronized(entry) { MarketWakeMessage(entry.reasons.toList(), entry.players.toList().takeIf { it.isNotEmpty() }?.take(MAX_PLAYERS)) }

        try {
            send(serverId, message)
        } catch (e: Exception) {
            logger.debug("MARKET_WAKE to server {} was not sent: {}", serverId, e.message)
        }
    }

    companion object {
        const val SYNC = "SYNC"
        const val CONFIG = "CONFIG"
        const val BALANCE = "BALANCE"
        const val PLACEHOLDERS = "PLACEHOLDERS"

        const val COALESCE_MS = 50L
        const val MAX_TIMER_MS = 60L * 60 * 1000
        const val MAX_PLAYERS = 100

        private val logger = LoggerFactory.getLogger(McPush::class.java)
    }
}
