package com.panomc.plugins.market.mc.bungee

import com.panomc.plugins.market.mc.core.feature.CoreGameLink
import com.panomc.plugins.market.mc.core.feature.MarketFeatures
import com.panomc.plugins.market.mc.core.feature.ResourceFiles
import com.panomc.plugins.market.mc.core.feature.asControl
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
 * present on disconnect. The text commands, broadcasts and join notifications of MC-05 are wired here too (no chest GUI,
 * placeholders or Vault on a proxy).
 */
class MarketBungeePlugin : Plugin(), Listener {
    private val tracker = PresenceTracker()
    private var component: MarketComponent? = null
    private var rules: PresenceRules? = null
    private var features: MarketFeatures? = null
    private var commands: BungeeCommands? = null
    private var gameLink: CoreGameLink? = null

    override fun onEnable() {
        val log = JulMcLog(logger)
        try {
            val panoPlugin = proxy.pluginManager.getPlugin("Pano")
            val panoMain = panoPlugin as? PanoPluginMain
            if (panoMain == null) {
                log.error("The Pano plugin is missing or is not the Pano core (found: ${panoPlugin?.javaClass?.name ?: "nothing"}); Market is disabled.")
                return
            }
            val link = CoreGameLink({ CorePanoLink.managerOf(panoMain) }, log)
            val f = MarketFeatures.create(
                dataFolder.toPath(), ResourceFiles.reader(MarketBungeePlugin::class.java.classLoader), BungeeFeatureHost(proxy), link,
                { name -> tracker.uuid(name) }, description.version, log
            )
            val offReason = f.offReason()
            if (offReason != null) {
                log.info(offReason)
                link.close()
                return
            }
            gameLink = link
            features = f
            val presenceRules = PresenceRules(
                tracker,
                authRequired = { false },
                isAuthenticated = { true },
                onPresent = { name ->
                    component?.playerPresent(name)
                    features?.onPlayerPresent(name)
                },
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
            val c = MarketComponent(
                dataFolder.toPath(), platform, CorePanoLink { CorePanoLink.managerOf(panoMain) }, log, description.version,
                settings = f.settings, callbacks = f.callbacks
            )
            f.attach(c.runtime.asControl())
            component = c
            commands = BungeeCommands(this, f).also { it.register() }
            proxy.pluginManager.registerListener(this, this)
            proxy.players.filter { it.server != null }.forEach { presenceRules.onJoin(it.name, it.uniqueId.toString()) }
            c.start()
            log.info("Market component enabled on BungeeCord (version ${description.version}).")
        } catch (t: Throwable) {
            log.error("The Market component could not be enabled: ${t.message}", t)
        }
    }

    override fun onDisable() {
        commands?.unregister()
        commands = null
        component?.stop()
        component = null
        gameLink?.close()
        gameLink = null
        features = null
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
