package com.panomc.plugins.market.mc.spigot

import com.panomc.plugins.market.mc.core.feature.CoreGameLink
import com.panomc.plugins.market.mc.core.feature.MarketFeatures
import com.panomc.plugins.market.mc.core.feature.ResourceFiles
import com.panomc.plugins.market.mc.core.feature.asControl
import com.panomc.plugins.market.mc.core.link.CorePanoLink
import com.panomc.plugins.market.mc.core.link.JulMcLog
import com.panomc.plugins.market.mc.core.link.MarketComponent
import com.panomc.plugins.market.mc.core.link.PresenceRules
import com.panomc.plugins.market.mc.core.link.PresenceTracker
import com.panomc.plugins.market.mc.spigot.vault.VaultBridge
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
 * joins into the engine, then adds the optional in-game features of MC-05 (commands, broadcast, join notifications), the
 * Vault bridge of MC-07 (CONVERT / PROVIDER; the chest GUI and placeholders follow in MC-06).
 */
class MarketSpigotPlugin : JavaPlugin(), Listener {
    private val tracker = PresenceTracker()
    private var component: MarketComponent? = null
    private var rules: PresenceRules? = null
    private var features: MarketFeatures? = null
    private var host: SpigotFeatureHost? = null
    private var commands: SpigotCommands? = null
    private var gameLink: CoreGameLink? = null
    private var vault: VaultBridge? = null

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
            val featureHost = SpigotFeatureHost(scheduler, log)
            val link = CoreGameLink({ CorePanoLink.managerOf(panoMain) }, log)
            val f = MarketFeatures.create(
                dataFolder.toPath(), ResourceFiles.reader(MarketSpigotPlugin::class.java.classLoader), featureHost, link,
                { name -> tracker.uuid(name) }, description.version, log
            )
            val offReason = f.offReason()
            if (offReason != null) {
                log.info(offReason)
                link.close()
                server.pluginManager.disablePlugin(this)
                return
            }
            host = featureHost
            gameLink = link
            features = f
            val bridge = AuthMeBridge(this)
            val presenceRules = PresenceRules(
                tracker,
                authRequired = { authActive },
                isAuthenticated = { name -> Bukkit.getPlayerExact(name)?.let { bridge.isAuthenticated(it) } ?: false },
                onPresent = { name ->
                    component?.playerPresent(name)
                    features?.onPlayerPresent(name)
                    vault?.onPlayerPresent(name)
                },
                onProblem = { log.warn(it) }
            )
            rules = presenceRules
            val vaultBridge = VaultBridge(this, f, link, scheduler, tracker, featureHost, dataFolder.toPath(), description.version, log)
            vault = vaultBridge
            val platform = SpigotMcPlatform(
                this, scheduler, tracker, log, flavor,
                neverJoinedUuid = { name -> panoMain.getNeverJoinedPlayerUniqueId(name) },
                vaultProbe = vaultBridge::vaultAvailable
            )
            val c = MarketComponent(
                dataFolder.toPath(), platform, CorePanoLink { CorePanoLink.managerOf(panoMain) }, log, description.version,
                settings = f.settings, callbacks = f.callbacks
            )
            f.attach(c.runtime.asControl())
            component = c
            server.pluginManager.registerEvents(this, this)
            if (bridge.installed()) {
                // Without the login events nobody could ever become present after the login: then fall back to "online".
                authActive = bridge.registerEvents({ p -> if (p.isOnline) presenceRules.onAuthLogin(p.name, p.uniqueId.toString()) }, { p -> presenceRules.onAuthLogout(p.name) })
                if (!authActive) log.warn("AuthMe is installed but its login events could not be hooked; players count as present on join.")
            }
            // Players online already (a /reload or a late enable) produce no join event.
            Bukkit.getOnlinePlayers().forEach {
                featureHost.remember(it)
                presenceRules.onJoin(it.name, it.uniqueId.toString())
            }
            commands = SpigotCommands(this, f, featureHost, log).also { it.register() }
            vaultBridge.start()
            c.start()
            log.info("Market component enabled on $flavor (version ${description.version}).")
        } catch (t: Throwable) {
            log.error("The Market component could not be enabled: ${t.message}", t)
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        commands?.unregister()
        commands = null
        vault?.stop()
        vault = null
        component?.stop()
        component = null
        gameLink?.close()
        gameLink = null
        features = null
        host = null
        tracker.clear()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val p: Player = event.player
        host?.remember(p)
        rules?.onJoin(p.name, p.uniqueId.toString())
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        rules?.onQuit(event.player.name)
        host?.forget(event.player.name)
    }
}
