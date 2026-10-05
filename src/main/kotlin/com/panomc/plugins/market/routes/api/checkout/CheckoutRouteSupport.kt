package com.panomc.plugins.market.routes.api.checkout

import com.panomc.platform.Main.Companion.applicationContext
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketBundleItemDao
import com.panomc.plugins.market.db.dao.MarketCartDao
import com.panomc.plugins.market.db.dao.MarketCartItemDao
import com.panomc.plugins.market.db.dao.MarketCategoryDao
import com.panomc.plugins.market.db.dao.MarketCouponDao
import com.panomc.plugins.market.db.dao.MarketCreatorCodeDao
import com.panomc.plugins.market.db.dao.MarketCreditAccountDao
import com.panomc.plugins.market.db.dao.MarketCurrencyRateDao
import com.panomc.plugins.market.db.dao.MarketDiscountDao
import com.panomc.plugins.market.db.dao.MarketEntitlementDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketPaymentMethodDao
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.dao.MarketProductFieldDao
import com.panomc.plugins.market.db.dao.MarketProductPriceDao
import com.panomc.plugins.market.db.dao.MarketProductVariantDao
import com.panomc.plugins.market.db.dao.MarketRedemptionDao
import com.panomc.plugins.market.db.dao.MarketSubscriptionDao
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.permission.MarketPermissions
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.routes.panel.settings.payment.paymentWiring
import com.panomc.plugins.market.routes.panel.settings.payment.providerLookup
import com.panomc.plugins.market.service.CheckoutService
import com.panomc.plugins.market.service.QuoteCaller
import com.panomc.plugins.market.service.ClientIpResolver
import com.panomc.plugins.market.service.platform.DirectoryUser
import com.panomc.plugins.market.service.platform.ServerDirectory
import com.panomc.plugins.market.service.platform.UserDirectory
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlClient

/** The platform's users and permissions behind the [UserDirectory] seam. */
internal class PlatformUserDirectory(private val databaseManager: () -> DatabaseManager) : UserDirectory {
    override suspend fun byUsername(username: String, sqlClient: SqlClient): DirectoryUser? {
        if (username.isBlank()) return null

        val id = databaseManager().userDao.getUserIdFromUsername(username, sqlClient) ?: return null
        val stored = databaseManager().userDao.getUsernameFromUserId(id, sqlClient) ?: return null

        return DirectoryUser(id, stored)
    }

    override suspend fun usernameOf(userId: Long, sqlClient: SqlClient): String? = databaseManager().userDao.getUsernameFromUserId(userId, sqlClient)

    override suspend fun emailOf(userId: Long, sqlClient: SqlClient): String? = databaseManager().userDao.getEmailFromUserId(userId, sqlClient)

    override suspend fun hasPermission(userId: Long, node: String): Boolean =
        applicationContext.getBean(PermissionManager::class.java).hasPermissionNode(userId, node, null)
}

/** The servers a delivery can reach: those the platform has granted permission to. */
internal class PlatformServerDirectory(private val databaseManager: () -> DatabaseManager) : ServerDirectory {
    override suspend fun existing(ids: Collection<Long>, sqlClient: SqlClient): Set<Long> =
        databaseManager().serverDao.getAllByPermissionGranted(sqlClient).map { it.id }.filter { it in ids }.toSet()
}

/** The quote service on the plugin's beans (stateless: a route keeps one). */
internal fun checkoutService(plugin: MarketPlugin): CheckoutService {
    val context = plugin.applicationContext
    val databaseManager = { context.getBean(DatabaseManager::class.java) }
    val wiring = paymentWiring(plugin)

    return CheckoutService(
        config = { currentConfig(plugin) },
        clock = SystemClock,
        categories = context.getBean(MarketCategoryDao::class.java),
        products = context.getBean(MarketProductDao::class.java),
        variants = context.getBean(MarketProductVariantDao::class.java),
        prices = context.getBean(MarketProductPriceDao::class.java),
        fields = context.getBean(MarketProductFieldDao::class.java),
        bundleItems = context.getBean(MarketBundleItemDao::class.java),
        discounts = context.getBean(MarketDiscountDao::class.java),
        coupons = context.getBean(MarketCouponDao::class.java),
        creatorCodes = context.getBean(MarketCreatorCodeDao::class.java),
        currencyRates = context.getBean(MarketCurrencyRateDao::class.java),
        redemptions = context.getBean(MarketRedemptionDao::class.java),
        orders = context.getBean(MarketOrderDao::class.java),
        entitlements = context.getBean(MarketEntitlementDao::class.java),
        subscriptions = context.getBean(MarketSubscriptionDao::class.java),
        creditAccounts = context.getBean(MarketCreditAccountDao::class.java),
        carts = context.getBean(MarketCartDao::class.java),
        cartItems = context.getBean(MarketCartItemDao::class.java),
        paymentMethods = context.getBean(MarketPaymentMethodDao::class.java),
        lookup = providerLookup(plugin),
        cipher = wiring.cipher,
        contexts = wiring.contexts,
        legal = legalTextService(plugin),
        users = PlatformUserDirectory(databaseManager),
        servers = PlatformServerDirectory(databaseManager)
    )
}

/** The caller of a public mutating route: the session user (if any), whether they may use a test-mode method, their address. */
internal suspend fun quoteCaller(plugin: MarketPlugin, context: RoutingContext): QuoteCaller {
    val authProvider = plugin.applicationContext.getBean(AuthProvider::class.java)
    val loggedIn = authProvider.isLoggedIn(context)
    val userId = if (loggedIn) authProvider.getUserIdFromRoutingContext(context) else null
    val testMode = loggedIn && MarketPermissions.has(context, setOf(MarketNode.SETTINGS, MarketNode.PAYMENTS))
    val ip = ClientIpResolver.resolve(context)

    return QuoteCaller(userId, testMode, ip.ip.takeIf { ip.trusted }, context.request().getHeader("User-Agent")?.take(255))
}
