package com.panomc.plugins.market.mc.core.platform

import com.panomc.plugins.market.mc.core.wire.MarketSyncMessage
import com.panomc.plugins.market.mc.core.wire.MarketSyncRequest

/*
 * The seams between the platform independent engine (`mc.core`) and a concrete platform (Spigot, BungeeCord, Velocity,
 * Fabric: MC-03 / MC-08). Everything the engine needs from the outside world is one of these interfaces, so the whole
 * delivery path is testable with the fakes in `src/mcTest` and no Minecraft class is ever loaded by `mc.core`.
 *
 * Threading contract (19 section 5): the engine, the store and the sync loop are confined to ONE thread, the one of
 * the `McScheduler`. Nothing in `mc.core` ever blocks the server main thread on disk or on the network. The only
 * calls that leave the engine thread are `McPlatform.dispatchConsole` and `McPlatform.applyPermission`, which an
 * adapter implements by hopping to the platform's command thread (`MarketScheduler`) and waiting there for the result.
 */

/** Wall clock in epoch milliseconds (the unit of every timestamp on the wire and in the journal). */
interface McClock {
    fun now(): Long
}

object SystemMcClock : McClock {
    override fun now(): Long = System.currentTimeMillis()
}

interface McLog {
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String, error: Throwable? = null)
}

/** A task scheduled with [McScheduler.schedule]; cancelling an already run timer is a no-op. */
interface McTimer {
    fun cancel()
}

/** The single engine thread: every task runs one after the other, never concurrently. */
interface McScheduler {
    /** Runs [task] on the engine thread as soon as possible, in submission order. */
    fun execute(task: () -> Unit)

    /** Runs [task] on the engine thread after [delayMs] (0 = as `execute`). */
    fun schedule(delayMs: Long, task: () -> Unit): McTimer
}

/** `ok = false` carries the platform's reason (an unknown command, an exception message). */
data class DispatchResult(val ok: Boolean, val error: String? = null) {
    companion object {
        val OK = DispatchResult(true)
    }
}

data class PermissionOutcome(val ok: Boolean, val error: String? = null)

/**
 * What the engine asks of a platform adapter. Implementations answer from any thread.
 *
 * `username` is always the name as Pano knows it; every lookup is case-insensitive (19 section 3).
 */
interface McPlatform {
    /** One of `McPlatformName` (`MARKET_SYNC.platform`). */
    val platformName: String

    fun luckPermsAvailable(): Boolean
    fun vaultAvailable(): Boolean
    fun placeholderApiAvailable(): Boolean

    /**
     * Present = authenticated and connected (19 section 3): AuthMe absent or authenticated on Spigot, connected to a
     * backend on a proxy. On a proxy it is "anywhere on the network".
     */
    fun isPresent(username: String): Boolean

    /** The UUID of the present player, `null` when the player is not present. */
    fun playerUuid(username: String): String?

    /** The UUID the platform would assign to this name for an offline player (used for `{uuid}` as a last resort). */
    fun offlineUuid(username: String): String

    /**
     * Runs one command as the console sender on the platform's command thread and returns once it ran. Returns
     * `ok = false` where the platform tells that the command is unknown or failed (Bukkit `dispatchCommand` = false);
     * may throw, the engine catches everything per command (19 section 6.3).
     */
    fun dispatchConsole(command: String): DispatchResult

    /**
     * Applies a `PERMISSION` delivery through LuckPerms (19 section 6.3). `op` is `ADD` or `REMOVE`; `expiresAt` is
     * epoch ms or `null` for permanent. The engine has already checked [luckPermsAvailable] and the local switch.
     */
    fun applyPermission(username: String, uuidHint: String?, op: String, nodes: List<String>, expiresAt: Long?): PermissionOutcome
}

/** The local, live switches the engine reads at call time (the effective config of 19 section 9, MC-05). */
interface DeliverySettings {
    /** `false` answers every delivery `FAILED / DISABLED_LOCALLY` (08 section 8.2 rule 8). */
    val deliveriesEnabled: Boolean

    /** `false` answers `PERMISSION` deliveries `FAILED / LUCKPERMS_MISSING` (19 section 6.3, `mcLuckPerms`). */
    val luckPermsEnabled: Boolean

    /** `false` drops purchase broadcasts (19 section 6.2 step 5). */
    val broadcastEnabled: Boolean
}

/**
 * Sends one `MARKET_SYNC` request and reports the answer exactly once through [callback]: the decoded response, or
 * `null` for a timeout (10 s), a missing connection or an undecodable answer. A timeout is "unknown", never "failed"
 * (19 section 4). The callback may run on any thread.
 */
interface SyncTransport {
    fun send(request: MarketSyncRequest, callback: (MarketSyncMessage?) -> Unit)
}
