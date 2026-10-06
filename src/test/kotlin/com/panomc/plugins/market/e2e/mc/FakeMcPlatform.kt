package com.panomc.plugins.market.e2e.mc

import com.panomc.plugins.market.mc.core.feature.FeatureHost
import com.panomc.plugins.market.mc.core.feature.McSender
import com.panomc.plugins.market.mc.core.platform.DispatchResult
import com.panomc.plugins.market.mc.core.platform.McLog
import com.panomc.plugins.market.mc.core.platform.McPlatform
import com.panomc.plugins.market.mc.core.platform.PermissionOutcome
import com.panomc.plugins.market.mc.core.wire.McPlatformName
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The platform half of `FakeMcServer` (19 section 13, `FakeMcPlatform`): players, the console command log, the permission calls. It is the
 * real `McPlatform` seam of `mc.core`, so the real delivery engine runs on top of it; only Bukkit / BungeeCord / Velocity are replaced.
 *
 * [dispatcher] decides what a console command answers (default: it ran); a scenario swaps it to hold a command (a latch), to fail it or to count it.
 */
class FakeMcPlatform : McPlatform {
    override var platformName: String = McPlatformName.PAPER

    @Volatile
    var luckPerms = false

    /** lower-case username -> uuid of the players that are present (authenticated). */
    val presentPlayers = ConcurrentHashMap<String, String>()

    /** Every console command that was dispatched, in order. */
    val console = CopyOnWriteArrayList<String>()
    val permissionCalls = CopyOnWriteArrayList<List<String>>()

    @Volatile
    var dispatcher: (String) -> DispatchResult = { DispatchResult.OK }

    override fun luckPermsAvailable() = luckPerms

    override fun vaultAvailable() = false

    override fun placeholderApiAvailable() = false

    override fun isPresent(username: String): Boolean = presentPlayers.containsKey(username.lowercase())

    override fun playerUuid(username: String): String? = presentPlayers[username.lowercase()]

    override fun offlineUuid(username: String): String = UUID.nameUUIDFromBytes("OfflinePlayer:$username".toByteArray()).toString()

    override fun dispatchConsole(command: String): DispatchResult {
        console.add(command)

        return dispatcher(command)
    }

    override fun applyPermission(username: String, uuidHint: String?, op: String, nodes: List<String>, expiresAt: Long?): PermissionOutcome {
        permissionCalls.add(listOf(username, op) + nodes)

        return PermissionOutcome(true)
    }

    fun join(name: String, uuid: String = UUID.nameUUIDFromBytes("e2e:$name".toByteArray()).toString()): String {
        presentPlayers[name.lowercase()] = uuid

        return uuid
    }

    fun leave(name: String) {
        presentPlayers.remove(name.lowercase())
    }

    /** How often [command] ran (exact text). */
    fun ran(command: String): Int = console.count { it == command }
}

/** The log of the fake server: every line is kept for assertions and echoed to the test output. */
class FakeMcLog(private val label: String) : McLog {
    val lines = CopyOnWriteArrayList<String>()

    private fun add(level: String, message: String) {
        lines.add("$level $message")
        println("mc[$label] $level $message")
    }

    override fun info(message: String) = add("INFO", message)

    override fun warn(message: String) = add("WARN", message)

    override fun error(message: String, error: Throwable?) = add("ERROR", message + (error?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""))

    fun has(fragment: String) = lines.any { it.contains(fragment) }
}

/** Chat of the fake server: what was sent to a player and broadcast to everybody. */
class FakeMcHost : FeatureHost {
    val sent = CopyOnWriteArrayList<Pair<String, String>>()
    val broadcasts = CopyOnWriteArrayList<String>()

    override fun sendTo(username: String, text: String, openUrl: String?) {
        sent.add(username to text)
    }

    override fun broadcast(text: String) {
        broadcasts.add(text)
    }
}

/** A command sender: a player (with the local permission nodes it was given) or the console. */
class FakeMcSender(
    override val name: String,
    override val isConsole: Boolean = false,
    private val nodes: Set<String> = emptySet(),
    override val uuid: String? = if (isConsole) null else UUID.nameUUIDFromBytes("e2e:$name".toByteArray()).toString()
) : McSender {
    override val locale: String? = "en_US"
    val lines = CopyOnWriteArrayList<String>()

    override fun hasPermission(node: String) = isConsole || node in nodes

    override fun send(text: String, openUrl: String?) {
        lines.add(text)
    }

    /** What the sender saw, without the legacy colour codes. */
    fun plain(): List<String> = lines.map { it.replace(Regex("§."), "") }
}
