package com.panomc.plugins.market.mc.bungee

import com.panomc.plugins.market.mc.core.link.CorePanoLink
import com.panomc.plugins.market.mc.core.link.JulMcLog
import com.panomc.plugins.market.mc.core.link.MarketComponent
import com.panomc.plugins.market.mc.core.link.PresenceRules
import com.panomc.plugins.market.mc.core.link.PresenceTracker
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import net.md_5.bungee.api.event.PlayerDisconnectEvent
import net.md_5.bungee.api.event.ServerConnectedEvent
import net.md_5.bungee.api.plugin.Listener
import net.md_5.bungee.api.plugin.Plugin
import net.md_5.bungee.event.EventHandler

/**
 * Main class on BungeeCord (19 section 4). A player becomes present when connected to a backend server
 * (`ServerConnectedEvent`; LimboAuth keeps unauthenticated players in its limbo, they never get there) and stops being
 * present on disconnect. Commands and the in-game features of MC-05 are not part of this slice.
 */
class MarketBungeePlugin : Plugin(), Listener {
    private val tracker = PresenceTracker()
    private var component: MarketComponent? = null
    private var rules: PresenceRules? = null

    override fun onEnable() {
        val log = JulMcLog(logger)
        try {
            val panoPlugin = proxy.pluginManager.getPlugin("Pano")
            val panoMain = panoPlugin as? PanoPluginMain
            if (panoMain == null) {
                log.error("The Pano plugin is missing or is not the Pano core (found: ${panoPlugin?.javaClass?.name ?: "nothing"}); Market is disabled.")
                return
            }
            val presenceRules = PresenceRules(
                tracker,
                authRequired = { false },
                isAuthenticated = { true },
                onPresent = { name -> component?.playerPresent(name) },
                onProblem = { log.warn(it) }
            )
            rules = presenceRules
            val scheduler = MarketBungeeScheduler(this)
            val platform = BungeeMcPlatform(
                tracker,
                submit = { scheduler.runAsync(it) },
                runCommand = { proxy.pluginManager.dispatchCommand(proxy.console, it) },
                luckPermsInstalled = { proxy.pluginManager.getPlugin("LuckPerms") != null },
                neverJoinedUuid = { n -> panoMain.getNeverJoinedPlayerUniqueId(n) }
            )
            val c = MarketComponent(dataFolder.toPath(), platform, CorePanoLink { CorePanoLink.managerOf(panoMain) }, log, description.version)
            component = c
            proxy.pluginManager.registerListener(this, this)
            proxy.players.filter { it.server != null }.forEach { presenceRules.onJoin(it.name, it.uniqueId.toString()) }
            c.start()
            log.info("Market component enabled on BungeeCord (version ${description.version}).")
        } catch (t: Throwable) {
            log.error("The Market component could not be enabled: ${t.message}", t)
        }
    }

    override fun onDisable() {
        component?.stop()
        component = null
        tracker.clear()
    }

    @EventHandler
    fun onServerConnected(event: ServerConnectedEvent) {
        rules?.onJoin(event.player.name, event.player.uniqueId.toString())
    }

    @EventHandler
    fun onDisconnect(event: PlayerDisconnectEvent) {
        rules?.onQuit(event.player.name)
    }
}
