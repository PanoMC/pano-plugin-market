package com.panomc.plugins.market.mc.spigot

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.function.Consumer

/**
 * The only place of the component that touches the Bukkit scheduler (19 section 3, Folia row): a source scan test
 * fails on any other `getScheduler()` / `.scheduler` use below `mc/spigot` and `mc/core`.
 *
 * - [runGlobal]: console commands and anything not tied to a player. Folia: the global region scheduler; Bukkit: the
 *   main thread;
 * - [runForPlayer]: inventory and other player bound work (the chest GUI, MC-06). Folia: the entity scheduler of the
 *   player; Bukkit: the main thread.
 *
 * Folia is reached by reflection (the compile API is Spigot 1.8.8), the same pattern as `pano-mc-plugin`'s
 * `SpigotServerUtil`. On Folia a failing reflection is an error, never a silent fall back to the Bukkit scheduler (which
 * throws there): the caller sees the exception and the command is reported as not executed.
 */
class MarketScheduler(private val plugin: Plugin, private val folia: Boolean = ServerFlavor.isFolia()) {

    fun runGlobal(task: Runnable) {
        if (folia) {
            val scheduler = plugin.server.javaClass.getMethod("getGlobalRegionScheduler").invoke(plugin.server)
            val run = scheduler.javaClass.getMethod("run", Plugin::class.java, Consumer::class.java)
            run.invoke(scheduler, plugin, Consumer<Any> { task.run() })
            return
        }
        plugin.server.scheduler.runTask(plugin, task)
    }

    /** [retired] runs when the player left before [task] could run (Folia only; Bukkit just runs [task]). */
    fun runForPlayer(player: Player, task: Runnable, retired: Runnable? = null) {
        if (folia) {
            val scheduler = player.javaClass.getMethod("getScheduler").invoke(player)
            val run = scheduler.javaClass.methods.firstOrNull { m ->
                m.name == "run" && m.parameterTypes.size == 3 &&
                    Plugin::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                    Consumer::class.java.isAssignableFrom(m.parameterTypes[1]) &&
                    Runnable::class.java.isAssignableFrom(m.parameterTypes[2])
            } ?: throw IllegalStateException("the Folia entity scheduler has no run(plugin, task, retired)")
            run.invoke(scheduler, plugin, Consumer<Any> { task.run() }, retired ?: Runnable {})
            return
        }
        plugin.server.scheduler.runTask(plugin, task)
    }
}
