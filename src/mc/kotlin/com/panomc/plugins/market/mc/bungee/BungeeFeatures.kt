package com.panomc.plugins.market.mc.bungee

import com.panomc.plugins.market.mc.core.feature.FeatureHost
import com.panomc.plugins.market.mc.core.feature.LocalConfig
import com.panomc.plugins.market.mc.core.feature.MarketFeatures
import com.panomc.plugins.market.mc.core.feature.McSender
import com.panomc.plugins.market.mc.core.feature.Msg
import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.ProxyServer
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.plugin.Command
import net.md_5.bungee.api.plugin.Plugin
import net.md_5.bungee.api.plugin.TabExecutor

object BungeeMessages {
    fun components(text: String, openUrl: String?): Array<BaseComponent> {
        val parts = TextComponent.fromLegacyText(text)
        if (openUrl != null && (openUrl.startsWith("https://") || openUrl.startsWith("http://"))) {
            parts.forEach { it.clickEvent = ClickEvent(ClickEvent.Action.OPEN_URL, openUrl) }
        }
        return parts
    }

    fun localeOf(player: ProxiedPlayer): String? = try {
        player.locale?.toLanguageTag()?.takeIf { it != "und" }
    } catch (_: Throwable) {
        null
    }
}

/** The features' output port on BungeeCord: the proxy API is thread safe, so messages are sent straight away. */
class BungeeFeatureHost(private val proxy: ProxyServer) : FeatureHost {
    override fun sendTo(username: String, text: String, openUrl: String?) {
        proxy.getPlayer(username)?.sendMessage(*BungeeMessages.components(text, openUrl))
    }

    override fun broadcast(text: String) {
        proxy.broadcast(*BungeeMessages.components(text, null))
    }

    override fun localeOf(username: String): String? = proxy.getPlayer(username)?.let { BungeeMessages.localeOf(it) }
}

/**
 * The proxy console is a POSITIVE test (`sender === proxy.console`): any other sender that is not a player (a plugin's own
 * `CommandSender`) is [supported] = false and refused, never treated as the console (19 section 7.4).
 */
class BungeeSender(
    private val sender: CommandSender,
    private val consoleOf: () -> CommandSender? = { ProxyServer.getInstance()?.console }
) : McSender {
    override val name: String get() = sender.name
    override val isConsole: Boolean get() = sender !is ProxiedPlayer && sender === consoleOf()

    val supported: Boolean get() = sender is ProxiedPlayer || isConsole
    override val uuid: String? get() = (sender as? ProxiedPlayer)?.uniqueId?.toString()
    override val locale: String? get() = (sender as? ProxiedPlayer)?.let { BungeeMessages.localeOf(it) }

    override fun hasPermission(node: String): Boolean = isConsole || sender.hasPermission(node)

    override fun send(text: String, openUrl: String?) = sender.sendMessage(*BungeeMessages.components(text, openUrl))
}

class MarketBungeeCommand(
    private val canonical: String,
    name: String,
    aliases: List<String>,
    private val features: MarketFeatures,
    private val consoleOf: () -> CommandSender? = { ProxyServer.getInstance()?.console },
    private val active: () -> Boolean
) : Command(name, null, *aliases.toTypedArray()), TabExecutor {
    override fun execute(sender: CommandSender, args: Array<String>) {
        val s = BungeeSender(sender, consoleOf)
        if (!s.supported) return s.send(features.messages.text(Msg.COMMAND_NO_PERMISSION, null))
        if (!active()) return s.send(features.messages.text(Msg.COMMAND_UNAVAILABLE, s.locale))
        features.commands.execute(canonical, s, args.toList())
    }

    override fun onTabComplete(sender: CommandSender, args: Array<String>): Iterable<String> =
        BungeeSender(sender, consoleOf).let { s -> if (active() && s.supported) features.commands.complete(canonical, s, args.toList()) else emptyList() }
}

/** Registers `/store`, `/credits` and `/panomarket` (with their aliases) on the proxy. */
class BungeeCommands(private val plugin: Plugin, private val features: MarketFeatures) {
    @Volatile
    private var active = true

    fun register() {
        for (canonical in LocalConfig.COMMANDS) {
            val names = features.config.local.namesOf(canonical)
            plugin.proxy.pluginManager.registerCommand(plugin, MarketBungeeCommand(canonical, names.first(), names.drop(1), features) { active })
        }
    }

    fun unregister() {
        active = false
        try {
            plugin.proxy.pluginManager.unregisterCommands(plugin)
        } catch (_: Throwable) {
        }
    }
}
