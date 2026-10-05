package com.panomc.plugins.market.mc.bungee

import net.md_5.bungee.api.plugin.Plugin
import java.util.concurrent.TimeUnit

/** The proxy's scheduler (BungeeCord has no main thread): the one place that touches `ProxyServer.getScheduler()`. */
class MarketBungeeScheduler(private val plugin: Plugin) {
    fun runAsync(task: Runnable) {
        plugin.proxy.scheduler.runAsync(plugin, task)
    }

    @Suppress("unused")
    fun runLater(delayMs: Long, task: Runnable) {
        plugin.proxy.scheduler.schedule(plugin, task, delayMs, TimeUnit.MILLISECONDS)
    }
}
