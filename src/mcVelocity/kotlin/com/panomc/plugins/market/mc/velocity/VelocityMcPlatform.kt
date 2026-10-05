package com.panomc.plugins.market.mc.velocity

import com.panomc.plugins.market.mc.core.link.CommandHop
import com.panomc.plugins.market.mc.core.link.LuckPermsLoader
import com.panomc.plugins.market.mc.core.link.PermissionApplier
import com.panomc.plugins.market.mc.core.link.PresenceTracker
import com.panomc.plugins.market.mc.core.platform.DispatchResult
import com.panomc.plugins.market.mc.core.platform.McPlatform
import com.panomc.plugins.market.mc.core.platform.PermissionOutcome
import com.panomc.plugins.market.mc.core.wire.McPlatformName
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Velocity side of the engine (19 section 3): present = connected to a backend server; a console command runs through
 * the proxy's command manager on a Velocity scheduler thread.
 */
class VelocityMcPlatform(
    private val presence: PresenceTracker,
    /** Hands a task to a Velocity scheduler thread. */
    private val submit: (Runnable) -> Unit,
    /** `commandManager.executeAsync(console, command)`: completes with true when a command of that name was found and run. */
    private val runCommand: (String) -> CompletableFuture<Boolean>,
    private val luckPermsInstalled: () -> Boolean,
    private val neverJoinedUuid: (String) -> UUID = { UUID.nameUUIDFromBytes("OfflinePlayer:$it".toByteArray(Charsets.UTF_8)) },
    private val commandTimeoutMs: Long = 30_000,
    /** Creates the LuckPerms executor on first use (LuckPerms classes are not loaded before); replaced in tests. */
    private val permissionApplier: () -> PermissionApplier = { LuckPermsLoader.create(neverJoinedUuid) }
) : McPlatform {
    override val platformName: String = McPlatformName.VELOCITY

    private val permissions: PermissionApplier by lazy(permissionApplier)

    override fun luckPermsAvailable(): Boolean = try {
        luckPermsInstalled()
    } catch (_: Throwable) {
        false
    }

    override fun vaultAvailable(): Boolean = false
    override fun placeholderApiAvailable(): Boolean = false

    override fun isPresent(username: String): Boolean = presence.isPresent(username)
    override fun playerUuid(username: String): String? = presence.uuid(username)
    override fun offlineUuid(username: String): String = neverJoinedUuid(username).toString()

    override fun dispatchConsole(command: String): DispatchResult {
        // `executeAsync` completes with `true` when a command of that name was found and run.
        val found = CommandHop.call(commandTimeoutMs, submit) { runCommand(command).get(commandTimeoutMs, TimeUnit.MILLISECONDS) }
        return if (found) DispatchResult.OK else DispatchResult(false, "unknown command")
    }

    override fun applyPermission(username: String, uuidHint: String?, op: String, nodes: List<String>, expiresAt: Long?): PermissionOutcome =
        permissions.apply(username, presence.uuid(username), uuidHint, op, nodes, expiresAt)
}
