package com.panomc.plugins.market.mc.fabric

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.panomc.plugins.market.mc.core.feature.FeatureHost
import com.panomc.plugins.market.mc.core.feature.LocalConfig
import com.panomc.plugins.market.mc.core.feature.MarketFeatures
import com.panomc.plugins.market.mc.core.feature.McSender
import com.panomc.plugins.market.mc.core.feature.Msg
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.slf4j.Logger
import java.lang.reflect.Field
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * The features' output port on Fabric. Every game access hops to the server thread (`MinecraftServer.execute`); the locale of
 * a player is remembered at join (read there, on the server thread) because the engine asks from its own thread.
 */
class FabricFeatureHost(private val server: () -> MinecraftServer?, private val console: Logger) : FeatureHost {
    private val locales = ConcurrentHashMap<String, String>()

    fun remember(player: ServerPlayer) {
        val locale = try {
            player.clientInformation().language()?.takeIf { it.isNotBlank() }
        } catch (_: LinkageError) {
            null
        }
        val name = playerName(player)
        if (locale != null) locales[name.lowercase()] = locale
    }

    fun forget(name: String) {
        locales.remove(name.lowercase())
    }

    fun clear() = locales.clear()

    override fun localeOf(username: String): String? = locales[username.lowercase()]

    override fun sendTo(username: String, text: String, openUrl: String?) {
        val s = server() ?: return
        hop(s) {
            s.playerList.getPlayerByName(username)?.sendSystemMessage(FabricText.component(text, openUrl))
        }
    }

    override fun broadcast(text: String) {
        val s = server() ?: return
        hop(s) {
            val component = FabricText.component(text)
            s.playerList.players.forEach { it.sendSystemMessage(component) }
            console.info(LegacyText.plain(text))
        }
    }

    /** Never throws: the server may already refuse tasks while it stops, and a throwing task must not reach the server thread. */
    private fun hop(s: MinecraftServer, task: () -> Unit) {
        try {
            s.execute {
                try {
                    task()
                } catch (e: Exception) {
                    // The player left between the lookup and this task; nobody to tell.
                }
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        fun playerName(player: ServerPlayer): String = player.gameProfile.name
    }
}

/**
 * Someone who typed a command. The console is a POSITIVE test (the source is the server itself, read from the private
 * `CommandSourceStack.source`); a command block, RCON or an entity that is no player is [supported] = false and refused
 * (19 section 7.4) -- a name such as "Server" proves nothing, a command block can be given it. If a Minecraft release hides
 * the field, nothing is the console any more (fail closed), players keep working.
 */
class FabricSender(
    private val source: CommandSourceStack,
    private val server: MinecraftServer?,
    private val permissions: FabricPermissions,
    private val host: FabricFeatureHost,
    private val console: Logger
) : McSender {
    private val player: ServerPlayer? = try {
        source.player
    } catch (_: Exception) {
        null
    }

    override val isConsole: Boolean = player == null && source.entity == null && sourceIsServer(source, server)

    val supported: Boolean get() = player != null || isConsole

    override val name: String get() = player?.let { FabricFeatureHost.playerName(it) } ?: "CONSOLE"
    override val uuid: String? get() = player?.uuid?.toString()
    override val locale: String? get() = player?.let { host.localeOf(FabricFeatureHost.playerName(it)) }

    override fun hasPermission(node: String): Boolean {
        if (isConsole) return true
        val p = player ?: return false
        return permissions.check(p, p.uuid, node)
    }

    override fun send(text: String, openUrl: String?) {
        if (isConsole) return console.info(LegacyText.plain(text))
        val p = player
        val s = server
        if (p == null || s == null) {
            // Not a player and not the console (never reached for a supported sender): the plain answer to whoever typed it.
            runCatching { source.sendSystemMessage(FabricText.component(text)) }
            return
        }
        try {
            s.execute { runCatching { p.sendSystemMessage(FabricText.component(text, openUrl)) } }
        } catch (_: Exception) {
            // The server is shutting down.
        }
    }

    companion object {
        private val sourceField: Field? = try {
            CommandSourceStack::class.java.getDeclaredField("source").apply { isAccessible = true }
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }

        internal fun sourceIsServer(source: CommandSourceStack, server: MinecraftServer?): Boolean {
            if (server == null) return false
            return try {
                sourceField?.get(source) === server
            } catch (_: Exception) {
                false
            }
        }
    }
}

/** Registers `/store`, `/credits` and `/panomarket` (with the aliases of the local config) on the server's Brigadier dispatcher. */
class FabricCommands(
    private val features: () -> MarketFeatures?,
    private val active: () -> Boolean,
    private val server: () -> MinecraftServer?,
    private val permissions: FabricPermissions,
    private val host: FabricFeatureHost,
    private val console: Logger
) {
    /** Called for every `CommandRegistrationCallback` (server start and `/reload`). */
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val f = features() ?: return
        for (canonical in LocalConfig.COMMANDS) {
            for (name in f.config.local.namesOf(canonical)) {
                dispatcher.register(build(name, canonical))
            }
        }
    }

    private fun build(name: String, canonical: String): LiteralArgumentBuilder<CommandSourceStack> {
        val args: RequiredArgumentBuilder<CommandSourceStack, String> = Commands.argument("args", StringArgumentType.greedyString())
            .suggests { ctx, builder -> suggest(canonical, ctx.source, builder) }
            .executes { ctx ->
                run(canonical, ctx.source, StringArgumentType.getString(ctx, "args"))
                1
            }
        return Commands.literal(name)
            .executes { ctx ->
                run(canonical, ctx.source, "")
                1
            }
            .then(args)
    }

    private fun sender(source: CommandSourceStack) = FabricSender(source, server(), permissions, host, console)

    private fun run(canonical: String, source: CommandSourceStack, raw: String) {
        val s = sender(source)
        val f = features()
        if (f == null) return s.send(LegacyText.plain("The Market component is not available."))
        if (!s.supported) return s.send(f.messages.text(Msg.COMMAND_NO_PERMISSION, null))
        if (!active()) return s.send(f.messages.text(Msg.COMMAND_UNAVAILABLE, s.locale))
        f.commands.execute(canonical, s, split(raw))
    }

    private fun suggest(canonical: String, source: CommandSourceStack, builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
        val f = features()
        val s = sender(source)
        if (f != null && active() && s.supported) {
            val remaining = builder.remaining
            val options = try {
                f.commands.complete(canonical, s, remaining.split(' '))
            } catch (_: Exception) {
                emptyList()
            }
            val shifted = builder.createOffset(builder.start + remaining.lastIndexOf(' ') + 1)
            options.forEach { shifted.suggest(it) }
            return shifted.buildFuture()
        }
        return builder.buildFuture()
    }

    companion object {
        /** `"credits  give"` to `[credits, give]`: empty tokens (double spaces) are not arguments. */
        fun split(raw: String): List<String> = raw.split(' ').filter { it.isNotEmpty() }
    }
}
