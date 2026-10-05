package com.panomc.plugins.market.mc.bungee

import com.panomc.plugins.market.mc.core.link.CommandHop
import com.panomc.plugins.market.mc.core.link.LuckPermsLoader
import com.panomc.plugins.market.mc.core.link.PermissionApplier
import com.panomc.plugins.market.mc.core.link.PresenceTracker
import com.panomc.plugins.market.mc.core.platform.DispatchResult
import com.panomc.plugins.market.mc.core.platform.McPlatform
import com.panomc.plugins.market.mc.core.platform.PermissionOutcome
import com.panomc.plugins.market.mc.core.wire.McPlatformName
import java.util.UUID

/**
 * BungeeCord side of the engine (19 section 3): a player is present while connected to a backend server (anywhere on
 * the network); a console command runs through the proxy's plugin manager on a scheduler thread.
 */
class BungeeMcPlatform(
    private val presence: PresenceTracker,
    /** Hands a task to a proxy scheduler thread (`MarketBungeeScheduler.runAsync`). */
    private val submit: (Runnable) -> Unit,
    /** `ProxyServer.pluginManager.dispatchCommand(console, command)`: true when a command of that name was found and run. */
    private val runCommand: (String) -> Boolean,
    private val luckPermsInstalled: () -> Boolean,
    private val neverJoinedUuid: (String) -> UUID = { UUID.nameUUIDFromBytes("OfflinePlayer:$it".toByteArray(Charsets.UTF_8)) },
    private val commandTimeoutMs: Long = 30_000,
    /** Creates the LuckPerms executor on first use (LuckPerms classes are not loaded before); replaced in tests. */
    private val permissionApplier: () -> PermissionApplier = { LuckPermsLoader.create(neverJoinedUuid) }
) : McPlatform {
    override val platformName: String = McPlatformName.BUNGEECORD

    private val permissions: PermissionApplier by lazy(permissionApplier)

    override fun luckPermsAvailable(): Boolean = try {
        luckPermsInstalled()
    } catch (_: Throwable) {
        false
    }

    // No Vault, no PlaceholderAPI on a proxy (19 section 3).
    override fun vaultAvailable(): Boolean = false
    override fun placeholderApiAvailable(): Boolean = false

    override fun isPresent(username: String): Boolean = presence.isPresent(username)
    override fun playerUuid(username: String): String? = presence.uuid(username)
    override fun offlineUuid(username: String): String = neverJoinedUuid(username).toString()

    override fun dispatchConsole(command: String): DispatchResult {
        // `dispatchCommand` is true when a command of that name was found and run.
        val found = CommandHop.call(commandTimeoutMs, submit) { runCommand(command) }
        return if (found) DispatchResult.OK else DispatchResult(false, "unknown command")
    }

    override fun applyPermission(username: String, uuidHint: String?, op: String, nodes: List<String>, expiresAt: Long?): PermissionOutcome =
        permissions.apply(username, presence.uuid(username), uuidHint, op, nodes, expiresAt)
}
