package com.panomc.plugins.market.mc.spigot

import com.panomc.plugins.market.mc.link.proxyOf
import org.bukkit.Bukkit
import org.bukkit.Server
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.bukkit.scheduler.BukkitScheduler
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.logging.Logger

class RegisteredEvent(val type: Class<out Event>, val listener: Listener, val priority: EventPriority, val executor: EventExecutor, val plugin: Plugin, val ignoreCancelled: Boolean)

/**
 * A fake Bukkit `Server` installed once per JVM (`Bukkit.setServer` allows it once). Tests reset its state in `@BeforeEach`.
 * The "main thread" is a single executor thread named `fake-main`; `runTask` runs there.
 */
object FakeBukkit {
    val plugins = ConcurrentHashMap<String, Plugin>()
    val enabled: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val registered = CopyOnWriteArrayList<RegisteredEvent>()
    val dispatched = CopyOnWriteArrayList<Pair<String, String>>()
    val schedulerCalls = CopyOnWriteArrayList<String>()

    @Volatile
    var dispatch: (String) -> Boolean = { true }

    @Volatile
    var refuseTasks: Throwable? = null

    @Volatile
    var neverRunTasks = false

    private val mainThread = Executors.newSingleThreadExecutor { Thread(it, "fake-main").also { t -> t.isDaemon = true } }

    val console: ConsoleCommandSender = proxyOf(ConsoleCommandSender::class.java) { m, _ ->
        when (m.name) {
            "getName" -> "CONSOLE"
            else -> throw UnsupportedOperationException("ConsoleCommandSender.${m.name}")
        }
    }

    private val bukkitScheduler: BukkitScheduler = proxyOf(BukkitScheduler::class.java) { m, a ->
        when (m.name) {
            "runTask" -> {
                schedulerCalls.add("runTask")
                refuseTasks?.let { throw it }
                if (!neverRunTasks) mainThread.execute(a[1] as Runnable)
                null
            }
            else -> throw UnsupportedOperationException("BukkitScheduler.${m.name}")
        }
    }

    private val pluginManager: PluginManager = proxyOf(PluginManager::class.java) { m, a ->
        when (m.name) {
            "getPlugin" -> plugins[a[0] as String]
            "isPluginEnabled" -> if (a[0] is String) (a[0] as String) in enabled else false
            "registerEvent" -> {
                @Suppress("UNCHECKED_CAST")
                registered.add(RegisteredEvent(a[0] as Class<out Event>, a[1] as Listener, a[2] as EventPriority, a[3] as EventExecutor, a[4] as Plugin, a[5] as Boolean))
                null
            }
            else -> throw UnsupportedOperationException("PluginManager.${m.name}")
        }
    }

    val server: Server = proxyOf(Server::class.java) { m, a ->
        when (m.name) {
            "getLogger" -> Logger.getLogger("fake-bukkit")
            "getName" -> "FakeBukkit"
            "getVersion" -> "fake"
            "getBukkitVersion" -> "1.8.8-R0.1-SNAPSHOT"
            "getPluginManager" -> pluginManager
            "getScheduler" -> bukkitScheduler
            "getConsoleSender" -> console
            "getOnlinePlayers" -> emptyList<Player>()
            "dispatchCommand" -> {
                val cmd = a[1] as String
                dispatched.add(Thread.currentThread().name to cmd)
                dispatch(cmd)
            }
            else -> throw UnsupportedOperationException("Server.${m.name}")
        }
    }

    @Synchronized
    fun install() {
        if (Bukkit.getServer() == null) Bukkit.setServer(server)
        check(Bukkit.getServer() === server) { "another Bukkit server is installed in this JVM" }
        reset()
    }

    fun reset() {
        plugins.clear()
        enabled.clear()
        registered.clear()
        dispatched.clear()
        schedulerCalls.clear()
        dispatch = { true }
        refuseTasks = null
        neverRunTasks = false
        fr.xephi.authme.api.v3.AuthMeApi.authenticated.clear()
    }

    fun plugin(name: String, enable: Boolean = true): Plugin {
        val p: Plugin = proxyOf(Plugin::class.java) { m, _ ->
            when (m.name) {
                "getName" -> name
                "getServer" -> server
                "isEnabled" -> enable
                else -> throw UnsupportedOperationException("Plugin.${m.name}")
            }
        }
        plugins[name] = p
        if (enable) enabled.add(name)
        return p
    }

    fun player(name: String, uuid: UUID = UUID.nameUUIDFromBytes(name.toByteArray())): Player = proxyOf(Player::class.java) { m, _ ->
        when (m.name) {
            "getName" -> name
            "getUniqueId" -> uuid
            else -> throw UnsupportedOperationException("Player.${m.name}")
        }
    }
}
