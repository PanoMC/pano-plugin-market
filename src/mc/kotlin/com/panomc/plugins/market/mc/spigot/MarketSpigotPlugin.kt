package com.panomc.plugins.market.mc.spigot

import com.panomc.plugins.market.mc.core.link.CorePanoLink
import com.panomc.plugins.market.mc.core.link.JulMcLog
import com.panomc.plugins.market.mc.core.link.MarketComponent
import com.panomc.plugins.market.mc.core.link.PresenceRules
import com.panomc.plugins.market.mc.core.link.PresenceTracker
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * Main class on Spigot / Paper / Folia (19 section 4). Obtains the Pano core through the `Pano` plugin, opens the
 * state under `plugins/PanoMarket/`, starts the sync loop and the connection monitor and feeds the authenticated
 * joins into the engine. Commands, GUI, placeholders and Vault belong to MC-05 to MC-07.
 */
class MarketSpigotPlugin : JavaPlugin(), Listener {
    private val tracker = PresenceTracker()
    private var component: MarketComponent? = null
    private var rules: PresenceRules? = null

    @Volatile
    private var authActive = false

    override fun onEnable() {
        val log = JulMcLog(logger)
        try {
            val panoPlugin = Bukkit.getPluginManager().getPlugin("Pano")
            val panoMain = panoPlugin as? PanoPluginMain
            if (panoMain == null) {
                log.error("The Pano plugin is missing or is not the Pano core (found: ${panoPlugin?.javaClass?.name ?: "nothing"}); Market is disabled.")
                server.pluginManager.disablePlugin(this)
                return
            }
            val flavor = ServerFlavor.detect(serverName = { Bukkit.getName() })
            val scheduler = MarketScheduler(this, flavor == com.panomc.plugins.market.mc.core.wire.McPlatformName.FOLIA)
            val bridge = AuthMeBridge(this)
            val presenceRules = PresenceRules(
                tracker,
                authRequired = { authActive },
                isAuthenticated = { name -> Bukkit.getPlayerExact(name)?.let { bridge.isAuthenticated(it) } ?: false },
                onPresent = { name -> component?.playerPresent(name) },
                onProblem = { log.warn(it) }
            )
            rules = presenceRules
            val platform = SpigotMcPlatform(
                this, scheduler, tracker, log, flavor,
                neverJoinedUuid = { name -> panoMain.getNeverJoinedPlayerUniqueId(name) }
            )
            val c = MarketComponent(
                dataFolder.toPath(), platform, CorePanoLink { CorePanoLink.managerOf(panoMain) }, log, description.version
            )
            component = c
            server.pluginManager.registerEvents(this, this)
            if (bridge.installed()) {
                // Without the login events nobody could ever become present after the login: then fall back to "online".
                authActive = bridge.registerEvents({ p -> presenceRules.onAuthLogin(p.name, p.uniqueId.toString()) }, { p -> presenceRules.onAuthLogout(p.name) })
                if (!authActive) log.warn("AuthMe is installed but its login events could not be hooked; players count as present on join.")
            }
            // Players online already (a /reload or a late enable) produce no join event.
            Bukkit.getOnlinePlayers().forEach { presenceRules.onJoin(it.name, it.uniqueId.toString()) }
            c.start()
            log.info("Market component enabled on $flavor (version ${description.version}).")
        } catch (t: Throwable) {
            log.error("The Market component could not be enabled: ${t.message}", t)
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        component?.stop()
        component = null
        tracker.clear()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val p: Player = event.player
        rules?.onJoin(p.name, p.uniqueId.toString())
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        rules?.onQuit(event.player.name)
    }
}
