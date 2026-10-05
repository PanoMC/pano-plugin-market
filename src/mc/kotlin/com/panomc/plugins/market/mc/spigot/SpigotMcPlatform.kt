package com.panomc.plugins.market.mc.spigot

import com.panomc.plugins.market.mc.core.link.CommandHop
import com.panomc.plugins.market.mc.core.link.LuckPermsLoader
import com.panomc.plugins.market.mc.core.link.PermissionApplier
import com.panomc.plugins.market.mc.core.link.PresenceTracker
import com.panomc.plugins.market.mc.core.platform.DispatchResult
import com.panomc.plugins.market.mc.core.platform.McLog
import com.panomc.plugins.market.mc.core.platform.McPlatform
import com.panomc.plugins.market.mc.core.platform.PermissionOutcome
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * Spigot / Paper / Folia side of the engine (19 section 3). Every method answers from any thread:
 * - presence comes from the [PresenceTracker] fed by the join / quit / AuthMe events, never from `Bukkit.getPlayer`
 *   on a foreign thread;
 * - a console command hops to the global region / main thread through [MarketScheduler] and returns once it ran;
 * - `PERMISSION` goes through the LuckPerms API on the calling (engine) thread.
 */
class SpigotMcPlatform(
    private val plugin: Plugin,
    private val scheduler: MarketScheduler,
    private val presence: PresenceTracker,
    private val log: McLog,
    override val platformName: String,
    private val neverJoinedUuid: (String) -> UUID = { UUID.nameUUIDFromBytes("OfflinePlayer:$it".toByteArray(Charsets.UTF_8)) },
    private val commandTimeoutMs: Long = 30_000,
    private val vaultProbe: () -> Boolean = { false },
    private val placeholderProbe: () -> Boolean = { false },
    /** Creates the LuckPerms executor on first use (LuckPerms classes are not loaded before); replaced in tests. */
    private val permissionApplier: () -> PermissionApplier = { LuckPermsLoader.create(neverJoinedUuid) }
) : McPlatform {

    private val permissions: PermissionApplier by lazy(permissionApplier)

    override fun luckPermsAvailable(): Boolean = try {
        Bukkit.getPluginManager().isPluginEnabled("LuckPerms")
    } catch (_: Throwable) {
        false
    }

    // The Vault bridge (MC-07) and PlaceholderAPI (MC-06) flip these through their probes when they exist.
    override fun vaultAvailable(): Boolean = vaultProbe()
    override fun placeholderApiAvailable(): Boolean = placeholderProbe()

    override fun isPresent(username: String): Boolean = presence.isPresent(username)
    override fun playerUuid(username: String): String? = presence.uuid(username)
    override fun offlineUuid(username: String): String = neverJoinedUuid(username).toString()

    override fun dispatchConsole(command: String): DispatchResult {
        // Bukkit reports `false` for an unknown command (and for a command that answers "usage"): 19 section 6.3.
        val ran = CommandHop.call(commandTimeoutMs, { scheduler.runGlobal(it) }) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)
        }
        return if (ran) DispatchResult.OK else DispatchResult(false, "unknown command, or the command reported failure")
    }

    override fun applyPermission(username: String, uuidHint: String?, op: String, nodes: List<String>, expiresAt: Long?): PermissionOutcome =
        permissions.apply(username, presence.uuid(username), uuidHint, op, nodes, expiresAt)
}
