package com.panomc.plugins.market

import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.api.PluginDatabaseManager
import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.setup.SetupManager
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.db.MarketTables
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

    override suspend fun onStart() {
        logger.info("Starting...")
        isRunning = true

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
        }
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

        val exchangeRateService = ExchangeRateService(this)
        pluginBeanContext.beanFactory.registerSingleton(ExchangeRateService::class.java.name, exchangeRateService)

        if (!thumbnailsDir.exists()) {
            thumbnailsDir.mkdirs()
        }

        val runner = MarketBootstrap(
            prefix = { MarketTables.prefixOverride ?: databaseManager.getTablePrefix() },
            pool = { databaseManager.getSqlClient() as Pool },
            initDatabase = { pluginDatabaseManager.initialize(this) },
            armScheduler = { startExchangeRateScheduler(configManager, exchangeRateService) }
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
        stopExchangeRateScheduler()
    }

    override suspend fun onDisable() {
        isRunning = false
        MarketRuntime.stopped()
        stopExchangeRateScheduler()
        isInitialized = false
    }

    override suspend fun onUninstall() {
        logger.info("Uninstalling...")

        pluginDatabaseManager.uninstall(this)

        if (uploadsDir.exists()) {
            uploadsDir.deleteRecursively()
        }
    }
}
