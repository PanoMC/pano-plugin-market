package com.panomc.plugins.market.mc.velocity

import com.panomc.plugins.market.mc.core.feature.FeatureHost
import com.panomc.plugins.market.mc.core.feature.LocalConfig
import com.panomc.plugins.market.mc.core.feature.MarketFeatures
import com.panomc.plugins.market.mc.core.feature.McSender
import com.panomc.plugins.market.mc.core.feature.Msg
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer

object VelocityMessages {
    fun component(text: String, openUrl: String?): Component {
        val c = LegacyComponentSerializer.legacySection().deserialize(text)
        return if (openUrl != null && (openUrl.startsWith("https://") || openUrl.startsWith("http://"))) c.clickEvent(ClickEvent.openUrl(openUrl)) else c
    }

    fun localeOf(player: Player): String? = try {
        player.effectiveLocale?.toLanguageTag()?.takeIf { it != "und" }
    } catch (_: Throwable) {
        null
    }
}

/** The features' output port on Velocity (the proxy API is thread safe). */
class VelocityFeatureHost(private val server: ProxyServer) : FeatureHost {
    override fun sendTo(username: String, text: String, openUrl: String?) {
        server.getPlayer(username).ifPresent { it.sendMessage(VelocityMessages.component(text, openUrl)) }
    }

    override fun broadcast(text: String) {
        val c = VelocityMessages.component(text, null)
        server.allPlayers.forEach { it.sendMessage(c) }
        server.consoleCommandSource.sendMessage(c)
    }

    override fun localeOf(username: String): String? = server.getPlayer(username).orElse(null)?.let { VelocityMessages.localeOf(it) }
}

class VelocitySender(private val source: CommandSource) : McSender {
    override val name: String get() = (source as? Player)?.username ?: "CONSOLE"
    override val isConsole: Boolean get() = source !is Player
    override val uuid: String? get() = (source as? Player)?.uniqueId?.toString()
    override val locale: String? get() = (source as? Player)?.let { VelocityMessages.localeOf(it) }

    // Velocity answers `false` for a node nobody granted (a permission plugin is needed for players); the console is always allowed.
    override fun hasPermission(node: String): Boolean = isConsole || source.hasPermission(node)

    override fun send(text: String, openUrl: String?) = source.sendMessage(VelocityMessages.component(text, openUrl))
}

class MarketVelocityCommand(
    private val canonical: String,
    private val features: MarketFeatures,
    private val active: () -> Boolean
) : SimpleCommand {
    override fun execute(invocation: SimpleCommand.Invocation) {
        val s = VelocitySender(invocation.source())
        if (!active()) return s.send(features.messages.text(Msg.COMMAND_UNAVAILABLE, s.locale))
        features.commands.execute(canonical, s, invocation.arguments().toList())
    }

    override fun suggest(invocation: SimpleCommand.Invocation): List<String> =
        if (active()) features.commands.complete(canonical, VelocitySender(invocation.source()), invocation.arguments().toList()) else emptyList()
}

/** Registers `/store`, `/credits` and `/panomarket` (with their aliases) on Velocity's command manager. */
class VelocityCommands(private val server: ProxyServer, private val plugin: Any, private val features: MarketFeatures) {
    @Volatile
    private var active = true
    private val names = ArrayList<String>()

    fun register() {
        val manager = server.commandManager
        for (canonical in LocalConfig.COMMANDS) {
            val all = features.config.local.namesOf(canonical)
            val meta = manager.metaBuilder(all.first()).aliases(*all.drop(1).toTypedArray()).plugin(plugin).build()
            manager.register(meta, MarketVelocityCommand(canonical, features) { active })
            names.addAll(all)
        }
    }

    fun unregister() {
        active = false
        try {
            names.forEach { server.commandManager.unregister(it) }
        } catch (_: Throwable) {
        }
        names.clear()
    }
}
