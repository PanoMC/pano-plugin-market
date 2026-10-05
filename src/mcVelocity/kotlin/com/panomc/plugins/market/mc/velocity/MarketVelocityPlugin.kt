package com.panomc.plugins.market.mc.velocity

import com.google.inject.Inject
import com.panomc.plugins.market.mc.core.link.CorePanoLink
import com.panomc.plugins.market.mc.core.link.MarketComponent
import com.panomc.plugins.market.mc.core.link.PresenceRules
import com.panomc.plugins.market.mc.core.link.PresenceTracker
import com.panomc.plugins.market.mc.core.platform.McLog
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import org.slf4j.Logger
import java.nio.file.Path

/**
 * Main class on Velocity (19 section 4, Java 17). Present = connected to a backend (`ServerConnectedEvent`; LimboAuth
 * keeps unauthenticated players in its limbo). State lives in the injected data directory (`plugins/panomarket`,
 * Velocity names it after the plugin id).
 */
class MarketVelocityPlugin @Inject constructor(
    private val server: ProxyServer,
    private val logger: Logger,
    @param:DataDirectory private val dataDirectory: Path
) {
    private val tracker = PresenceTracker()
    private var component: MarketComponent? = null

    @Volatile
    private var rules: PresenceRules? = null

    private val log = object : McLog {
        override fun info(message: String) = logger.info(message)
        override fun warn(message: String) = logger.warn(message)
        override fun error(message: String, error: Throwable?) {
            if (error == null) logger.error(message) else logger.error(message, error)
        }
    }

    @Subscribe
    fun onInitialize(@Suppress("UNUSED_PARAMETER") event: ProxyInitializeEvent) {
        try {
            val panoMain = server.pluginManager.getPlugin("pano").flatMap { it.instance }.orElse(null) as? PanoPluginMain
            if (panoMain == null) {
                log.error("The Pano plugin is missing or is not the Pano core; Market is disabled.")
                return
            }
            val version = server.pluginManager.fromInstance(this).flatMap { it.description.version }.orElse("unknown")
            val presenceRules = PresenceRules(
                tracker,
                authRequired = { false },
                isAuthenticated = { true },
                onPresent = { name -> component?.playerPresent(name) },
                onProblem = { log.warn(it) }
            )
            rules = presenceRules
            val platform = VelocityMcPlatform(
                tracker,
                submit = { task -> server.scheduler.buildTask(this, task).schedule() },
                runCommand = { server.commandManager.executeAsync(server.consoleCommandSource, it) },
                luckPermsInstalled = { server.pluginManager.isLoaded("luckperms") },
                neverJoinedUuid = { n -> panoMain.getNeverJoinedPlayerUniqueId(n) }
            )
            val c = MarketComponent(dataDirectory, platform, CorePanoLink { CorePanoLink.managerOf(panoMain) }, log, version)
            component = c
            server.allPlayers.filter { it.currentServer.isPresent }.forEach { presenceRules.onJoin(it.username, it.uniqueId.toString()) }
            c.start()
            log.info("Market component enabled on Velocity (version $version).")
        } catch (t: Throwable) {
            log.error("The Market component could not be enabled: ${t.message}", t)
        }
    }

    @Subscribe
    fun onShutdown(@Suppress("UNUSED_PARAMETER") event: ProxyShutdownEvent) {
        component?.stop()
        component = null
        tracker.clear()
    }

    @Subscribe
    fun onServerConnected(event: ServerConnectedEvent) {
        rules?.onJoin(event.player.username, event.player.uniqueId.toString())
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        rules?.onQuit(event.player.username)
    }
}
