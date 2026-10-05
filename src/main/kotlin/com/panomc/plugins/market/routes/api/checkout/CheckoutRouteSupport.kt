package com.panomc.plugins.market.routes.api.checkout

import com.panomc.plugins.market.runtime.beans
import com.panomc.platform.Main.Companion.applicationContext
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.SecureIds
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketAddressDao
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
import com.panomc.plugins.market.db.dao.MarketOrderEventDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketPaymentDao
import com.panomc.plugins.market.db.dao.MarketPaymentMethodDao
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.dao.MarketProductFieldDao
import com.panomc.plugins.market.db.dao.MarketProductProviderMetaDao
import com.panomc.plugins.market.db.dao.MarketProductPriceDao
import com.panomc.plugins.market.db.dao.MarketProductVariantDao
import com.panomc.plugins.market.db.dao.MarketRedemptionDao
import com.panomc.plugins.market.db.dao.MarketSubscriptionDao
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.permission.MarketPermissions
import com.panomc.plugins.market.routes.api.order.orderService
import com.panomc.plugins.market.routes.api.order.paymentService
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.routes.panel.shipping.shippingService
import com.panomc.plugins.market.routes.panel.settings.payment.paymentWiring
import com.panomc.plugins.market.routes.panel.settings.payment.providerLookup
import com.panomc.plugins.market.routes.user.cart.cartService
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.db.dao.MarketThrottleDao
import com.panomc.plugins.market.service.CodeGuard
import com.panomc.plugins.market.service.MarketRateLimits
import com.panomc.plugins.market.service.OpenOrderLimit
import com.panomc.plugins.market.service.ThrottleService
import com.panomc.plugins.market.service.CheckoutDeps
import com.panomc.plugins.market.service.CheckoutService
import com.panomc.plugins.market.service.OrderService
import com.panomc.plugins.market.service.RedemptionService
import com.panomc.plugins.market.service.ReservationService
import com.panomc.plugins.market.service.QuoteCaller
import com.panomc.plugins.market.service.ClientIpResolver
import com.panomc.plugins.market.service.platform.DirectoryUser
import com.panomc.plugins.market.service.platform.ServerDirectory
import com.panomc.plugins.market.service.platform.UserDirectory
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.Pool
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

/**
 * The abuse limits of one plugin instance (11 sections 11 and 12, MK-152): the in-memory limiters, the durable throttle, the code lock and the
 * open-order limit. One instance per plugin, so every route shares the same buckets and the same counters.
 */
internal class MarketAbuseWiring(val rateLimits: MarketRateLimits, val throttle: ThrottleService, val codeGuard: CodeGuard, val openOrders: OpenOrderLimit)

private object AbuseWiringHolder

private var cachedAbuse: Pair<MarketPlugin, MarketAbuseWiring>? = null

internal fun abuseWiring(plugin: MarketPlugin): MarketAbuseWiring {
    cachedAbuse?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(AbuseWiringHolder) {
        cachedAbuse?.takeIf { it.first === plugin }?.second ?: run {
            val context = plugin.beans
            val config: () -> MarketConfig = { currentConfig(plugin) }
            val throttle = ThrottleService(
                context.getBean(MarketThrottleDao::class.java),
                { context.getBean(DatabaseManager::class.java).getSqlClient() },
                SystemClock
            )

            MarketAbuseWiring(
                rateLimits = MarketRateLimits(config),
                throttle = throttle,
                codeGuard = CodeGuard(throttle, config, SystemClock),
                openOrders = OpenOrderLimit(context.getBean(MarketOrderDao::class.java), SystemClock)
            ).also { cachedAbuse = plugin to it }
        }
    }
}

/**
 * The checkout service on the plugin's beans (stateless apart from the rate limiter: a route keeps one). [withCheckout] adds the
 * wiring of `checkout` (the transaction helper, the locks, the reservation, the order inserts); the quote needs none of it.
 */
internal fun checkoutService(plugin: MarketPlugin, withCheckout: Boolean = false): CheckoutService {
    val context = plugin.beans
    val databaseManager = { context.getBean(DatabaseManager::class.java) }
    val wiring = paymentWiring(plugin)
    val deps = if (withCheckout) checkoutDeps(plugin, databaseManager) else null
    val abuse = abuseWiring(plugin)

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
        servers = PlatformServerDirectory(databaseManager),
        shipping = shippingService(plugin),
        addresses = context.getBean(MarketAddressDao::class.java),
        checkout = deps,
        codeGuard = abuse.codeGuard,
        rateLimits = abuse.rateLimits,
        openOrders = abuse.openOrders,
        throttle = abuse.throttle
    )
}

/**
 * The pieces of `CheckoutService.checkout` on the plugin's beans. The order service and the payment starter are the ones of the order routes
 * (`orderService`, `paymentService`: MK-076), so the first attempt of a checkout is started, and a free or credits order completed, by the real
 * payment service. The credit hold and the pending subscription are refused until their services exist.
 */
private fun checkoutDeps(plugin: MarketPlugin, databaseManager: () -> DatabaseManager): CheckoutDeps {
    val context = plugin.beans
    val clock = SystemClock
    val orderDao = context.getBean(MarketOrderDao::class.java)
    val redemptionDao = context.getBean(MarketRedemptionDao::class.java)
    val locks = Locks(orderDao, context.getBean(MarketOrderItemDao::class.java), redemptionDao, context.getBean(MarketCreditAccountDao::class.java))
    val redemptions = RedemptionService(clock, locks, redemptionDao)
    val paymentDao = context.getBean(MarketPaymentDao::class.java)

    return CheckoutDeps(
        db = MarketDb({ databaseManager().getSqlClient() as Pool }, clock),
        locks = locks,
        reservations = ReservationService(clock, locks, redemptions, orderDao),
        redemptions = redemptions,
        orders = orderService(plugin),
        payments = paymentDao,
        providerMeta = context.getBean(MarketProductProviderMetaDao::class.java),
        starter = paymentService(plugin)
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
