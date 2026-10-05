package com.panomc.plugins.market.mc.fabric

import com.panomc.plugins.market.mc.core.feature.CoreGameLink
import com.panomc.plugins.market.mc.core.feature.MarketFeatures
import com.panomc.plugins.market.mc.core.feature.ResourceFiles
import com.panomc.plugins.market.mc.core.feature.asControl
import com.panomc.plugins.market.mc.core.link.CorePanoLink
import com.panomc.plugins.market.mc.core.link.MarketComponent
import com.panomc.plugins.market.mc.core.link.PresenceRules
import com.panomc.plugins.market.mc.core.link.PresenceTracker
import com.panomc.plugins.market.mc.core.platform.McLog
import com.panomc.plugins.pano.core.helper.PanoPluginMain
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Main class on Fabric (19 section 4), the separate jar `pano-plugin-market-fabric-<version>.jar`. It rides on the Pano
 * Fabric mod (`depends: pano`): the Pano core is the entry point of that mod, found through the loader. State lives in
 * `config/panomarket/`. The component starts when the server has started (Pano's own SERVER_STARTED callback runs first,
 * the mod depends on it) and stops with the server; the text commands, broadcasts and join notifications of MC-05 are wired
 * here too (no chest GUI, Vault or PlaceholderAPI on Fabric, 19 section 3).
 */
class MarketFabricMod : DedicatedServerModInitializer {
    private val logger: Logger = LoggerFactory.getLogger("PanoMarket")
    private val log = object : McLog {
        override fun info(message: String) = logger.info(message)
        override fun warn(message: String) = logger.warn(message)
        override fun error(message: String, error: Throwable?) {
            if (error == null) logger.error(message) else logger.error(message, error)
        }
    }

    private val tracker = PresenceTracker()

    @Volatile
    private var server: MinecraftServer? = null

    @Volatile
    private var features: MarketFeatures? = null

    @Volatile
    private var rules: PresenceRules? = null

    @Volatile
    private var running = false

    private var component: MarketComponent? = null
    private var gameLink: CoreGameLink? = null
    private var host: FabricFeatureHost? = null

    @Volatile
    private var panoMain: PanoPluginMain? = null

    override fun onInitializeServer() {
        try {
            val modVersion = version()
            val feHost = FabricFeatureHost({ server }, logger)
            val link = CoreGameLink({ panoMain()?.let { CorePanoLink.managerOf(it) } }, log)
            val dataDir = FabricLoader.getInstance().configDir.resolve("panomarket")
            val f = MarketFeatures.create(
                dataDir, ResourceFiles.reader(MarketFabricMod::class.java.classLoader), feHost, link,
                { name -> tracker.uuid(name) }, modVersion, log
            )
            val offReason = f.offReason()
            if (offReason != null) {
                log.info(offReason)
                link.close()
                return
            }
            host = feHost
            gameLink = link
            features = f
            val permissions = FabricPermissions(if (FabricLoader.getInstance().isModLoaded("luckperms")) LuckPermsNodes() else null)
            val commands = FabricCommands({ features }, { running }, { server }, permissions, feHost, logger)
            CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> commands.register(dispatcher) }
            ServerLifecycleEvents.SERVER_STARTED.register { s -> start(s, modVersion) }
            ServerLifecycleEvents.SERVER_STOPPING.register { stop() }
            ServerPlayConnectionEvents.JOIN.register { handler, _, s -> onJoin(handler.player, s) }
            ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> onQuit(handler.player) }
        } catch (t: Throwable) {
            log.error("The Market component could not be set up: ${t.message}", t)
        }
    }

    private fun version(): String =
        FabricLoader.getInstance().getModContainer(MOD_ID).map { it.metadata.version.friendlyString }.orElse("unknown")

    /** The Pano core: the entry point of the `pano` mod. Looked up once it is needed, `null` while Pano is missing. */
    private fun panoMain(): PanoPluginMain? {
        panoMain?.let { return it }
        val found = try {
            FabricLoader.getInstance().getEntrypointContainers("server", DedicatedServerModInitializer::class.java)
                .firstOrNull { it.provider.metadata.id == "pano" }?.entrypoint as? PanoPluginMain
        } catch (_: Throwable) {
            null
        }
        panoMain = found
        return found
    }

    private fun start(s: MinecraftServer, modVersion: String) {
        try {
            server = s
            val main = panoMain()
            if (main == null) {
                log.error("The Pano mod is missing or is not the Pano core; Market is disabled.")
                return
            }
            val f = features ?: return
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
            val platform = FabricMcPlatform(
                tracker,
                submit = { task -> s.execute(task) },
                runner = MinecraftConsoleRunner(s),
                luckPermsInstalled = { FabricLoader.getInstance().isModLoaded("luckperms") },
                neverJoinedUuid = { n -> main.getNeverJoinedPlayerUniqueId(n) }
            )
            val c = MarketComponent(
                FabricLoader.getInstance().configDir.resolve("panomarket"), platform, CorePanoLink { CorePanoLink.managerOf(main) }, log, modVersion,
                settings = f.settings, callbacks = f.callbacks
            )
            f.attach(c.runtime.asControl())
            component = c
            // Players online already produce no join event (there are none at SERVER_STARTED, kept for a late start).
            s.playerList.players.forEach { onJoin(it, s) }
            running = true
            c.start()
            log.info("Market component enabled on Fabric (version $modVersion).")
        } catch (t: Throwable) {
            log.error("The Market component could not be enabled: ${t.message}", t)
        }
    }

    private fun stop() {
        running = false
        component?.stop()
        component = null
        gameLink?.close()
        gameLink = null
        features = null
        tracker.clear()
        host?.clear()
        server = null
    }

    private fun onJoin(player: net.minecraft.server.level.ServerPlayer, @Suppress("UNUSED_PARAMETER") s: MinecraftServer) {
        host?.remember(player)
        rules?.onJoin(FabricFeatureHost.playerName(player), player.uuid.toString())
    }

    private fun onQuit(player: net.minecraft.server.level.ServerPlayer) {
        val name = FabricFeatureHost.playerName(player)
        rules?.onQuit(name)
        host?.forget(name)
    }

    companion object {
        const val MOD_ID = "panomarket"
    }
}
