package com.panomc.plugins.market.routes.api.store

import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketBundleItemDao
import com.panomc.plugins.market.db.dao.MarketCategoryDao
import com.panomc.plugins.market.db.dao.MarketComparisonDao
import com.panomc.plugins.market.db.dao.MarketCurrencyRateDao
import com.panomc.plugins.market.db.dao.MarketDiscountDao
import com.panomc.plugins.market.db.dao.MarketEntitlementDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.dao.MarketProductFieldDao
import com.panomc.plugins.market.db.dao.MarketProductPriceDao
import com.panomc.plugins.market.db.dao.MarketProductVariantDao
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.service.ServerChoice
import com.panomc.plugins.market.service.StoreQueryService
import com.panomc.plugins.market.service.StoreViewer
import io.vertx.ext.web.RoutingContext

/** The read service of the store routes on the plugin's beans (stateless: a route keeps one). */
internal fun storeQueryService(plugin: MarketPlugin): StoreQueryService {
    val context = plugin.applicationContext
    val databaseManager by lazy { context.getBean(DatabaseManager::class.java) }

    return StoreQueryService(
        config = { currentConfig(plugin) },
        clock = SystemClock,
        categories = context.getBean(MarketCategoryDao::class.java),
        products = context.getBean(MarketProductDao::class.java),
        variants = context.getBean(MarketProductVariantDao::class.java),
        prices = context.getBean(MarketProductPriceDao::class.java),
        fields = context.getBean(MarketProductFieldDao::class.java),
        bundleItems = context.getBean(MarketBundleItemDao::class.java),
        discounts = context.getBean(MarketDiscountDao::class.java),
        currencyRates = context.getBean(MarketCurrencyRateDao::class.java),
        comparisons = context.getBean(MarketComparisonDao::class.java),
        orderItems = context.getBean(MarketOrderItemDao::class.java),
        entitlements = context.getBean(MarketEntitlementDao::class.java),
        serverChoices = { ids, sqlClient ->
            // Only servers the platform has granted permission to can receive a delivery.
            databaseManager.serverDao.getAllByPermissionGranted(sqlClient)
                .filter { it.id in ids }
                .map { ServerChoice(it.id, it.customName?.takeIf { n -> n.isNotBlank() } ?: it.name, it.type.name) }
        }
    )
}

/** The caller of a public route: the session user when there is one, else a guest. */
internal suspend fun storeViewer(plugin: MarketPlugin, context: RoutingContext): StoreViewer {
    val authProvider = plugin.applicationContext.getBean(AuthProvider::class.java)

    return if (authProvider.isLoggedIn(context)) StoreViewer(authProvider.getUserIdFromRoutingContext(context)) else StoreViewer.GUEST
}
