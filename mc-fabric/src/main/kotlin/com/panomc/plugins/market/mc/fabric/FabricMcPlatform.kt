package com.panomc.plugins.market.mc.fabric

import com.panomc.plugins.market.mc.core.link.CommandHop
import com.panomc.plugins.market.mc.core.link.LuckPermsLoader
import com.panomc.plugins.market.mc.core.link.PermissionApplier
import com.panomc.plugins.market.mc.core.link.PresenceTracker
import com.panomc.plugins.market.mc.core.platform.DispatchResult
import com.panomc.plugins.market.mc.core.platform.McPlatform
import com.panomc.plugins.market.mc.core.platform.PermissionOutcome
import com.panomc.plugins.market.mc.core.wire.McPlatformName
import java.util.UUID

/** What running one console command on the server thread did. */
sealed class ConsoleOutcome {
    /** The command line did not parse (unknown command, incorrect argument): nothing was executed. */
    data class NotRun(val reason: String) : ConsoleOutcome()

    /** The command was executed; [successes] / [failures] are the results it reported through the command result callback. */
    data class Ran(val successes: Int, val failures: Int) : ConsoleOutcome()
}

/** Runs one command line (no leading slash) as the console on the CURRENT thread, which is the server thread (see [FabricMcPlatform]). */
fun interface ConsoleRunner {
    fun run(command: String): ConsoleOutcome
}

/**
 * Fabric side of the engine (19 section 3): present = joined (Fabric has no authentication plugin the component detects;
 * 19 section 3: an unknown authentication plugin falls back to "online"); a console command runs on the server thread
 * (`MinecraftServer.execute`) and the engine waits for it there (`CommandHop`: a command that timed out before it started
 * is cancelled and never runs).
 *
 * A delivery is DONE when the command was dispatched without exception (19 section 6.3): an unparsable line is a failure
 * (nothing ran) and a command that REPORTED a failure is a failure. A command that ran and reported no result at all is
 * DONE: since Minecraft 1.20.3 a `/function` without `/return` is void and never calls the result callback, yet it ran
 * completely; calling that a failure would make the admin retry it and deliver the goods twice.
 */
class FabricMcPlatform(
    private val presence: PresenceTracker,
    /** Hands a task to the server thread (`server.execute`); may throw when the server no longer takes tasks. */
    private val submit: (Runnable) -> Unit,
    private val runner: ConsoleRunner,
    private val luckPermsInstalled: () -> Boolean,
    private val neverJoinedUuid: (String) -> UUID = { UUID.nameUUIDFromBytes("OfflinePlayer:$it".toByteArray(Charsets.UTF_8)) },
    private val commandTimeoutMs: Long = 30_000,
    /** Creates the LuckPerms executor on first use (LuckPerms classes are not loaded before); replaced in tests. */
    private val permissionApplier: () -> PermissionApplier = { LuckPermsLoader.create(neverJoinedUuid) }
) : McPlatform {
    override val platformName: String = McPlatformName.FABRIC

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
        val line = command.trim().removePrefix("/")
        if (line.isEmpty()) return DispatchResult(false, "empty command")
        return when (val outcome = CommandHop.call(commandTimeoutMs, submit) { runner.run(line) }) {
            is ConsoleOutcome.NotRun -> DispatchResult(false, "unknown or incorrect command: ${outcome.reason}")
            is ConsoleOutcome.Ran ->
                if (outcome.failures > 0) DispatchResult(false, "the command reported a failure") else DispatchResult.OK
        }
    }

    override fun applyPermission(username: String, uuidHint: String?, op: String, nodes: List<String>, expiresAt: Long?): PermissionOutcome =
        permissions.apply(username, presence.uuid(username), uuidHint, op, nodes, expiresAt)
}
