package com.panomc.plugins.market.mc.spigot

import com.panomc.plugins.market.mc.core.feature.FeatureHost
import com.panomc.plugins.market.mc.core.feature.LocalConfig
import com.panomc.plugins.market.mc.core.feature.MarketCommands
import com.panomc.plugins.market.mc.core.feature.MarketFeatures
import com.panomc.plugins.market.mc.core.feature.McSender
import com.panomc.plugins.market.mc.core.feature.Msg
import com.panomc.plugins.market.mc.core.platform.McLog
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandMap
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionDefault
import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap

/** Chat output on Spigot: plain `sendMessage`, a clickable link where the Spigot chat API is there (1.8 and later). */
object SpigotMessages {
    fun send(target: CommandSender, text: String, openUrl: String?) {
        if (openUrl != null && target is Player && (openUrl.startsWith("https://") || openUrl.startsWith("http://"))) {
            try {
                val parts = TextComponent.fromLegacyText(text)
                parts.forEach { it.clickEvent = ClickEvent(ClickEvent.Action.OPEN_URL, openUrl) }
                target.spigot().sendMessage(*parts)
                return
            } catch (_: Throwable) {
                // A server without the chat API gets the plain line: the address is in the text anyway.
            }
        }
        target.sendMessage(text)
    }

    /** `Player.getLocale()` exists from 1.12; older servers answer `null` (the default language is used). */
    fun localeOf(player: Any): String? = try {
        player.javaClass.getMethod("getLocale").invoke(player) as? String
    } catch (_: Throwable) {
        null
    }
}

/**
 * The features' output port on Spigot / Paper / Folia. Every chat line goes through the [MarketScheduler] (the global
 * region / main thread), so replies that arrive from the network thread never touch the server off its thread. Player
 * locales are remembered at join (read there, on the server thread) because the engine asks from its own thread.
 */
class SpigotFeatureHost(private val scheduler: MarketScheduler, private val log: McLog) : FeatureHost {
    private val locales = ConcurrentHashMap<String, String>()

    fun remember(player: Player) = remember(player.name, SpigotMessages.localeOf(player))

    fun remember(name: String, locale: String?) {
        if (locale != null) locales[name.lowercase()] = locale
    }

    fun forget(name: String) {
        locales.remove(name.lowercase())
    }

    fun onServerThread(task: Runnable) {
        try {
            scheduler.runGlobal(task)
        } catch (t: Throwable) {
            // Plugin disabled while a late answer arrived: nobody is left to tell.
            log.warn("A Market chat message could not be scheduled: ${t.message}")
        }
    }

    override fun sendTo(username: String, text: String, openUrl: String?) = onServerThread {
        Bukkit.getPlayerExact(username)?.let { SpigotMessages.send(it, text, openUrl) }
    }

    override fun broadcast(text: String) = onServerThread { Bukkit.broadcastMessage(text) }

    override fun localeOf(username: String): String? = locales[username.lowercase()]
}

class SpigotSender(private val sender: CommandSender, private val host: SpigotFeatureHost) : McSender {
    override val name: String get() = sender.name
    override val isConsole: Boolean get() = sender !is Player
    override val uuid: String? get() = (sender as? Player)?.uniqueId?.toString()
    override val locale: String? get() = if (sender is Player) host.localeOf(sender.name) else null

    override fun hasPermission(node: String): Boolean = isConsole || sender.hasPermission(node)

    override fun send(text: String, openUrl: String?) = host.onServerThread { SpigotMessages.send(sender, text, openUrl) }
}

/** One of `/store`, `/credits`, `/panomarket` (with its aliases) as a Bukkit command registered on the server's command map. */
class MarketBukkitCommand(
    private val canonical: String,
    name: String,
    aliases: List<String>,
    private val features: MarketFeatures,
    private val host: SpigotFeatureHost,
    private val active: () -> Boolean
) : Command(name, "Pano Market", "/$name", aliases) {
    override fun execute(sender: CommandSender, commandLabel: String, args: Array<String>): Boolean {
        val s = SpigotSender(sender, host)
        if (!active()) {
            s.send(features.messages.text(Msg.COMMAND_UNAVAILABLE, s.locale))
            return true
        }
        features.commands.execute(canonical, s, args.toList())
        return true
    }

    override fun tabComplete(sender: CommandSender, alias: String, args: Array<String>): List<String> =
        if (active()) features.commands.complete(canonical, SpigotSender(sender, host), args.toList()) else emptyList()
}

/**
 * Registers the text commands on the server's command map (the Bukkit `CommandMap`, reached through `CraftServer`; plugin.yml
 * could not carry the configurable aliases) and the admin permission nodes (default: operators only).
 */
class SpigotCommands(
    private val plugin: Plugin,
    private val features: MarketFeatures,
    private val host: SpigotFeatureHost,
    private val log: McLog,
    private val commandMap: () -> CommandMap? = { reflectCommandMap() }
) {
    @Volatile
    private var active = true
    private val registered = ArrayList<Command>()
    private val permissions = ArrayList<Permission>()

    fun register() {
        val map = commandMap()
        if (map == null) {
            log.error("The server's command map could not be reached: the Market commands are not registered.")
        } else {
            for (canonical in LocalConfig.COMMANDS) {
                val names = features.config.local.namesOf(canonical)
                val command = MarketBukkitCommand(canonical, names.first(), names.drop(1), features, host) { active }
                map.register(plugin.name.lowercase(), command)
                registered.add(command)
            }
        }
        val pm = plugin.server.pluginManager
        for (node in MarketCommands.NODES) {
            try {
                val permission = Permission(node, "Pano Market admin command", PermissionDefault.OP)
                pm.addPermission(permission)
                permissions.add(permission)
            } catch (t: Throwable) {
                log.warn("The permission $node could not be declared: ${t.message}")
            }
        }
    }

    /** Disables the commands (a stale entry in the map answers "not available") and removes what can be removed. */
    fun unregister() {
        active = false
        val map = commandMap()
        if (map != null) {
            registered.forEach { c ->
                try {
                    c.unregister(map)
                    removeKnown(map, c)
                } catch (_: Throwable) {
                }
            }
        }
        registered.clear()
        permissions.forEach { p ->
            try {
                plugin.server.pluginManager.removePermission(p)
            } catch (_: Throwable) {
            }
        }
        permissions.clear()
    }

    private fun removeKnown(map: CommandMap, command: Command) {
        val known = try {
            map.javaClass.getMethod("getKnownCommands").invoke(map) as? MutableMap<*, *>
        } catch (_: Throwable) {
            try {
                map.javaClass.getDeclaredField("knownCommands").also { it.isAccessible = true }.get(map) as? MutableMap<*, *>
            } catch (_: Throwable) {
                null
            }
        } ?: return
        known.entries.removeIf { it.value === command }
    }

    companion object {
        fun reflectCommandMap(): CommandMap? = try {
            val server = Bukkit.getServer()
            server.javaClass.getMethod("getCommandMap").invoke(server) as? CommandMap
        } catch (_: Throwable) {
            null
        }
    }
}
