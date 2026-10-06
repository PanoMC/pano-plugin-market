package com.panomc.plugins.market

import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.api.PluginDatabaseManager
import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.server.ServerManager
import com.panomc.platform.setup.SetupManager
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.db.MarketTables
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.event.UninstallExport
import com.panomc.plugins.market.event.exportThenDrop
import com.panomc.plugins.market.event.server.MarketSyncEvent
import com.panomc.plugins.market.job.MarketJobs
import com.panomc.plugins.market.job.MarketScheduler
import com.panomc.plugins.market.routes.panel.server.mcSyncService
import com.panomc.plugins.market.runtime.MarketBootstrap
import com.panomc.plugins.market.runtime.MarketRuntime
import com.panomc.plugins.market.service.ExchangeRateService
import com.panomc.plugins.market.util.ExchangeRateMode
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.sqlclient.Pool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class MarketPlugin : PanoPlugin() {
    private val pluginDatabaseManager by lazy {
        applicationContext.getBean(PluginDatabaseManager::class.java)
    }

    private val setupManager by lazy {
        applicationContext.getBean(SetupManager::class.java)
    }

    private val databaseManager by lazy {
        applicationContext.getBean(DatabaseManager::class.java)
    }

    // Created by startPlugin(); runs the start-up order of 01 section 14.4 exactly once.
    @Volatile
    private var bootstrap: MarketBootstrap? = null

    val uploadsDir: File by lazy {
        File(pluginDataFolder, "uploads")
    }

    val thumbnailsDir: File by lazy {
        File(uploadsDir, "thumbnails")
    }

    private var isInitialized = false

    /**
     * `true` between `onStart` and `onStop` / `onDisable`. `SetupEventHandler` asks this instead of `pluginState`:
     * the host copies the PF4J state into `PanoPlugin.pluginState` once, when the instance is created, so it never
     * reads `STARTED` and a plugin started before the setup would never initialise when the setup finishes (01 section 14.4).
     */
    @Volatile
    var isRunning = false
        private set

    // Base tick cadence for the auto-refresh checker; the actual per-refresh interval comes from
    // config (min 1h) and is evaluated on every tick so config edits take effect without a restart.
    private val exchangeRateTickIntervalMs = 60L * 60L * 1000L
    private var exchangeRateTimerId: Long? = null
    private val exchangeRateRefreshRunning = AtomicBoolean(false)

    @Volatile
    private var jobScheduler: MarketScheduler? = null

    // The server event MARKET_SYNC (08 section 8.1): registered at start, removed at stop / disable.
    @Volatile
    private var syncEvent: MarketSyncEvent? = null

    override suspend fun onStart() {
        logger.info("Starting...")
        isRunning = true

        registerServerEvents()

        MarketRuntime.probeHostCapabilities(MarketPlugin::class.java.classLoader)

        startPlugin()

        // A stop->start (reload) cycle re-enters here with isInitialized already true, so
        // startPlugin() short-circuits before re-arming the scheduler that onStop() cancelled.
        // Re-arm it from the still-registered beans; startExchangeRateScheduler() no-ops when the
        // timer is already live, so the first-ever start path is unaffected.
        if (isInitialized && setupManager.isSetupDone()) {
            bootstrap?.resume()

            @Suppress("UNCHECKED_CAST")
            val configManager =
                pluginBeanContext.getBean(PluginConfigManager::class.java) as PluginConfigManager<MarketConfig>
            val exchangeRateService = pluginBeanContext.getBean(ExchangeRateService::class.java)

            startExchangeRateScheduler(configManager, exchangeRateService)
            startJobScheduler()
        }
    }

    /**
     * Registers `MARKET_SYNC` with the platform's `ServerManager` (08 section 8.1; idempotent: an earlier registration of this plugin instance is removed first, the
     * manager keeps a plain list). The event answers `MARKET_NOT_READY` until the store is READY, so registering before the bootstrap is safe. Never fails the start:
     * without it the Minecraft component only sees no answer and keeps waiting.
     */
    private fun registerServerEvents() {
        try {
            val manager = applicationContext.getBean(ServerManager::class.java)
            val event = syncEvent ?: MarketSyncEvent({ mcSyncService(this) }).also { syncEvent = it }

            manager.unregisterEvent(event)
            manager.registerEvent(event)
        } catch (e: Exception) {
            logger.warn("The MARKET_SYNC server event could not be registered, Minecraft deliveries wait until the plugin is restarted", e)
        }
    }

    private fun unregisterServerEvents() {
        val event = syncEvent ?: return

        try {
            applicationContext.getBean(ServerManager::class.java).unregisterEvent(event)
        } catch (e: Exception) {
            logger.warn("The MARKET_SYNC server event could not be unregistered", e)
        }
    }

    /** The one timer of the background jobs (MK-078); idempotent. Jobs run only while the store is READY (see [MarketJobs]). */
    private fun startJobScheduler() {
        val running = jobScheduler ?: MarketJobs.scheduler(this).also { jobScheduler = it }

        running.start(vertx)
    }

    /**
     * First start of the shipping settings (10 section 4.2, WIRE-1): the `manual` carrier and the zone "Everywhere" exist before the first physical cart is
     * quoted. Idempotent and race safe (a carrier row that exists, or one the admin changed, is never touched); a failure is logged and the lazy seed
     * of the admin calls still applies, it never stops the plugin from starting.
     */
    private suspend fun seedShipping() {
        try {
            com.panomc.plugins.market.routes.panel.shipping.shippingAdminService(this).seed()
        } catch (e: Exception) {
            logger.warn("The shipping settings could not be seeded at start, they are seeded when the panel first opens them", e)
        }
    }

    private fun stopJobScheduler() {
        jobScheduler?.stop(vertx)
    }

    internal suspend fun startPlugin() {
        if (isInitialized) return

        if (!setupManager.isSetupDone()) {
            logger.info("Setup is not finished, waiting for setup completion...")
            return
        }

        isInitialized = true

        val configManager = PluginConfigManager(this, MarketConfig::class.java)
        pluginBeanContext.beanFactory.registerSingleton(PluginConfigManager::class.java.name, configManager)
        com.panomc.plugins.market.routes.base.MarketGate.storeEnabled = { configManager.config.storeEnabled }

        val exchangeRateService = ExchangeRateService(this)
        pluginBeanContext.beanFactory.registerSingleton(ExchangeRateService::class.java.name, exchangeRateService)

        if (!thumbnailsDir.exists()) {
            thumbnailsDir.mkdirs()
        }

        val runner = MarketBootstrap(
            prefix = { MarketTables.prefixOverride ?: databaseManager.getTablePrefix() },
            pool = { databaseManager.getSqlClient() as Pool },
            initDatabase = { pluginDatabaseManager.initialize(this) },
            armScheduler = {
                seedShipping()
                startExchangeRateScheduler(configManager, exchangeRateService)
                startJobScheduler()
            }
        ).also { bootstrap = it }

        val state = runner.run()

        if (state == MarketRuntime.State.READY) {
            logger.info("Started!")
        } else {
            logger.warn("Started in {} mode: the store is unavailable, see the market health endpoint", state)
            logger.info("Started!")
        }
    }

    /**
     * Periodic checker that, while [ExchangeRateMode.AUTO] is active, refreshes the sales -> stats
     * view rate once the configured interval (min 1h) has elapsed since the last update. Crash-safe:
     * failures are swallowed so a bad tick never bubbles out of the timer.
     */
    private fun startExchangeRateScheduler(
        configManager: PluginConfigManager<MarketConfig>,
        exchangeRateService: ExchangeRateService
    ) {
        if (exchangeRateTimerId != null) return

        exchangeRateTimerId = vertx.setPeriodic(exchangeRateTickIntervalMs) {
            if (!exchangeRateRefreshRunning.compareAndSet(false, true)) {
                return@setPeriodic
            }

            CoroutineScope(vertx.dispatcher()).launch {
                try {
                    val config = configManager.config

                    if (config.exchangeRateMode != ExchangeRateMode.AUTO) return@launch

                    val intervalMs = maxOf(1, config.exchangeRateAutoIntervalHours) * 60L * 60L * 1000L
                    if (System.currentTimeMillis() - config.exchangeRateUpdatedAt < intervalMs) return@launch

                    val rate = exchangeRateService.fetchRate(config.currency, config.statsCurrency) ?: return@launch

                    val merged = JsonObject.mapFrom(configManager.config)
                        .put("exchangeRate", rate)
                        .put("exchangeRateUpdatedAt", System.currentTimeMillis())
                    configManager.saveConfig(merged)

                    logger.info("Auto-refreshed exchange rate {} -> {}: {}", config.currency.name, config.statsCurrency.name, rate)
                } catch (e: Exception) {
                    logger.warn("Auto exchange-rate refresh failed", e)
                } finally {
                    exchangeRateRefreshRunning.set(false)
                }
            }
        }
    }

    private fun stopExchangeRateScheduler() {
        exchangeRateTimerId?.let { vertx.cancelTimer(it) }
        exchangeRateTimerId = null
    }

    override suspend fun onEnable() {
        logger.info("Enabled!")
    }

    override suspend fun onStop() {
        isRunning = false
        MarketRuntime.stopped()
        unregisterServerEvents()
        stopExchangeRateScheduler()
        stopJobScheduler()
    }

    override suspend fun onDisable() {
        isRunning = false
        MarketRuntime.stopped()
        unregisterServerEvents()
        stopExchangeRateScheduler()
        isInitialized = false
    }

    override suspend fun onUninstall() {
        logger.info("Uninstalling...")

        if (!setupManager.isSetupDone()) {
            // no database was ever set up for the store: nothing to export
            pluginDatabaseManager.uninstall(this)
        } else {
            // 00 section 8.7: the financial records are exported as CSV before the tables are dropped; an export that fails aborts the uninstall (the tables stay)
            val exported = exportThenDrop(
                export = {
                    UninstallExport(SystemClock, { MarketTables.prefixOverride ?: databaseManager.getTablePrefix() }, { databaseManager.getSqlClient() }, pluginDataFolder).export()
                },
                drop = { pluginDatabaseManager.uninstall(this) }
            )

            logger.info("The orders, payments, refunds, credit ledger and invoices were exported to {} before the tables were dropped", exported.dir)
        }

        if (uploadsDir.exists()) {
            uploadsDir.deleteRecursively()
        }
    }
}
