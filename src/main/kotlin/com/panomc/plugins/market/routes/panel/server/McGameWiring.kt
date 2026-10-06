package com.panomc.plugins.market.routes.panel.server

import com.panomc.platform.Main.Companion.applicationContext
import com.panomc.platform.PluginManager
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.server.ServerEvent
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketCategoryDao
import com.panomc.plugins.market.db.dao.MarketCreditTxDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.dao.MarketServerStateDao
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.event.server.MarketAdminEvent
import com.panomc.plugins.market.event.server.MarketConfigEvent
import com.panomc.plugins.market.event.server.MarketEconomyEvent
import com.panomc.plugins.market.event.server.MarketPurchaseEvent
import com.panomc.plugins.market.event.server.MarketQueryEvent
import com.panomc.plugins.market.event.server.MarketSyncEvent
import com.panomc.plugins.market.i18n.MarketI18n
import com.panomc.plugins.market.permission.ManageMarketPermission
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.api.checkout.PlatformUserDirectory
import com.panomc.plugins.market.routes.api.checkout.checkoutService
import com.panomc.plugins.market.routes.api.order.creditService
import com.panomc.plugins.market.routes.api.store.storeQueryService
import com.panomc.plugins.market.routes.api.store.widgetService
import com.panomc.plugins.market.routes.panel.order.manualOrderLocale
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.runtime.beans
import com.panomc.plugins.market.service.McComponentDownload
import com.panomc.plugins.market.service.McGameService
import com.panomc.plugins.market.service.McPermissions
import io.vertx.sqlclient.Pool

private object McGameWiringHolder

@Volatile
private var cachedGame: Pair<MarketPlugin, McGameService>? = null

/**
 * The Pano side of the game events (MC-04) on the plugin's beans: one per plugin instance, the way [mcSyncService] is. The checkout, the credit ledger, the store read
 * model and the widget cache are the ones of the web routes, so an in-game purchase is the same checkout, not a copy of it.
 */
internal fun mcGameService(plugin: MarketPlugin): McGameService {
    cachedGame?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(McGameWiringHolder) {
        cachedGame?.takeIf { it.first === plugin }?.second ?: buildMcGameService(plugin).also { cachedGame = plugin to it }
    }
}

private fun buildMcGameService(plugin: MarketPlugin): McGameService {
    val context = plugin.beans
    val databaseManager = { context.getBean(DatabaseManager::class.java) }
    val pool: suspend () -> io.vertx.sqlclient.SqlClient = { databaseManager().getSqlClient() }
    val bundles by lazy { MarketI18n.loadBundles(MarketPlugin::class.java.classLoader) }

    return McGameService(
        db = MarketDb({ pool() as Pool }, SystemClock), clock = SystemClock, config = { currentConfig(plugin) },
        serverStates = context.getBean(MarketServerStateDao::class.java), credits = creditService(plugin), creditTxs = context.getBean(MarketCreditTxDao::class.java),
        checkout = checkoutService(plugin, withCheckout = true), users = PlatformUserDirectory(databaseManager), permissions = PlatformMcPermissions(plugin),
        orders = context.getBean(MarketOrderDao::class.java), orderItems = context.getBean(MarketOrderItemDao::class.java),
        products = context.getBean(MarketProductDao::class.java), categories = context.getBean(MarketCategoryDao::class.java), store = storeQueryService(plugin),
        widgets = widgetService(plugin), read = { pool() },
        activity = { log -> databaseManager().panelActivityLogDao.add(log, pool()) }, pluginId = plugin.pluginId,
        marketVersion = { marketVersion(plugin) },
        storeUrl = { runCatching { plugin.applicationContext.getBean(ConfigManager::class.java).config.websiteUrl }.getOrNull() },
        orderLocale = { username -> manualOrderLocale(plugin, username) },
        // the market locale files hold no in-game text group yet: an empty map keeps the component on its own bundled texts (identical for the same version)
        texts = { locale -> bundles[locale].orEmpty().filterKeys { it.startsWith(INGAME_TEXT_PREFIX) }.mapKeys { it.key.removePrefix(INGAME_TEXT_PREFIX) } },
        textLocales = {
            val site = runCatching { plugin.applicationContext.getBean(ConfigManager::class.java).config.locale }.getOrNull()

            (listOfNotNull(site?.takeIf { it.isNotBlank() }) + MarketI18n.BUNDLED).distinct().take(McGameService.MAX_TEXT_LOCALES)
        },
        announce = { order, items -> mcSyncService(plugin).announce(order, items) }
    )
}

/** The keys of a market locale file below this prefix are the strings of `MARKET_CONFIG.texts` (19 section 7.1). */
internal const val INGAME_TEXT_PREFIX = "ingame."

/** The PF4J descriptor version of the running market jar: what the version gate of every game event compares the component's version with. */
internal fun marketVersion(plugin: MarketPlugin): String = plugin.applicationContext.getBean(PluginManager::class.java).getPlugin(plugin.pluginId).descriptor.version

/** [McPermissions] over the platform: a platform administrator, a holder of the node or of the umbrella (what `MarketPermissions.require` accepts for a panel request). */
internal class PlatformMcPermissions(private val plugin: MarketPlugin) : McPermissions {
    override suspend fun holds(userId: Long, node: MarketNode): Boolean {
        val auth = plugin.applicationContext.getBean(AuthProvider::class.java)
        val permissions = plugin.applicationContext.getBean(PermissionManager::class.java)

        return auth.isUserAdmin(userId) || permissions.hasPermission(userId, node.permission) || permissions.hasPermission(userId, ManageMarketPermission())
    }
}

/** The download of the Minecraft component (19 section 2.3): the running jar, or the bundled Fabric jar. */
internal fun mcComponentDownload(plugin: MarketPlugin): McComponentDownload =
    McComponentDownload(
        runningJar = { plugin.applicationContext.getBean(PluginManager::class.java).getPlugin(plugin.pluginId)?.pluginPath },
        fabricJar = { MarketPlugin::class.java.classLoader.getResourceAsStream(McComponentDownload.FABRIC_RESOURCE)?.use { it.readBytes() } },
        version = { marketVersion(plugin) }
    )

/** The six server events of the market, in registration order (the plugin registers them at start and removes them at stop). */
internal fun marketServerEvents(plugin: MarketPlugin): List<ServerEvent<*, *>> = listOf(
    MarketSyncEvent({ mcSyncService(plugin) }),
    MarketConfigEvent({ mcGameService(plugin) }),
    MarketQueryEvent({ mcGameService(plugin) }),
    MarketPurchaseEvent({ mcGameService(plugin) }),
    MarketAdminEvent({ mcGameService(plugin) }),
    MarketEconomyEvent({ mcGameService(plugin) })
)
