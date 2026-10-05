package com.panomc.plugins.market.routes.api.order

import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.hosted.HostedEnvConfig
import com.panomc.platform.util.RateLimiter
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.abuse.IpRange
import com.panomc.plugins.market.core.time.SecureIds
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.core.webhook.StoreInfo
import com.panomc.plugins.market.core.webhook.TargetPolicy
import com.panomc.plugins.market.db.dao.MarketCreditAccountDao
import com.panomc.plugins.market.db.dao.MarketCreditEntryDao
import com.panomc.plugins.market.db.dao.MarketCreditTxDao
import com.panomc.plugins.market.db.dao.MarketCurrencyRateDao
import com.panomc.plugins.market.db.dao.MarketEntitlementDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderEventDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketPaymentDao
import com.panomc.plugins.market.db.dao.MarketPaymentMethodDao
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.dao.MarketRedemptionDao
import com.panomc.plugins.market.db.dao.MarketRefundDao
import com.panomc.plugins.market.db.dao.MarketWebhookDeliveryDao
import com.panomc.plugins.market.db.dao.MarketWebhookEndpointDao
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.TooManyRequests
import com.panomc.plugins.market.routes.api.OrderAccess
import com.panomc.plugins.market.routes.api.OrderAccessResult
import com.panomc.plugins.market.routes.api.checkout.quoteCaller
import com.panomc.plugins.market.routes.api.payment.attemptContexts
import com.panomc.plugins.market.routes.panel.invoice.invoiceService
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.routes.panel.settings.payment.paymentWiring
import com.panomc.plugins.market.routes.panel.settings.payment.providerLookup
import com.panomc.plugins.market.routes.user.cart.cartService
import com.panomc.plugins.market.service.CreditHoldGuard
import com.panomc.plugins.market.service.CreditService
import com.panomc.plugins.market.service.DuplicateRefundPolicy
import com.panomc.plugins.market.service.ForeignEffects
import com.panomc.plugins.market.service.InvoiceEffects
import com.panomc.plugins.market.service.OrderService
import com.panomc.plugins.market.service.OutboundHttp
import com.panomc.plugins.market.service.PayCaller
import com.panomc.plugins.market.service.PaidWebhooks
import com.panomc.plugins.market.service.PaymentService
import com.panomc.plugins.market.service.ProductPurchaseLimits
import com.panomc.plugins.market.service.RedemptionService
import com.panomc.plugins.market.service.ReservationService
import com.panomc.plugins.market.service.WebhookSender
import com.panomc.plugins.market.service.WebhookService
import io.vertx.core.Vertx
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlClient

private object OrderWiringHolder

@Volatile
private var cachedPayments: Pair<MarketPlugin, PaymentService>? = null

@Volatile
private var cachedOrders: Pair<MarketPlugin, OrderService>? = null

@Volatile
private var cachedCredits: Pair<MarketPlugin, CreditService>? = null

@Volatile
private var cachedAccess: Pair<MarketPlugin, OrderAccess>? = null

@Volatile
private var cachedWebhooks: Pair<MarketPlugin, WebhookService>? = null

/**
 * The store webhook writer on the plugin's beans (MK-105): [OrderService] uses its `emitOrderPaid` at O2 / O4. The sender is built as the job
 * will need it, so the same instance can be handed to `WebhookJob`.
 */
internal fun webhookService(plugin: MarketPlugin): WebhookService {
    cachedWebhooks?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(OrderWiringHolder) {
        cachedWebhooks?.takeIf { it.first === plugin }?.second ?: buildWebhookService(plugin).also { cachedWebhooks = plugin to it }
    }
}

private fun buildWebhookService(plugin: MarketPlugin): WebhookService {
    val context = plugin.applicationContext
    val databaseManager = { context.getBean(DatabaseManager::class.java) }
    val wiring = paymentWiring(plugin)
    val version = plugin.wrapper.descriptor.version
    val sender = WebhookSender(
        OutboundHttp.create(context.getBean(Vertx::class.java), version), wiring.cipher, SystemClock, version,
        { TargetPolicy.effectiveAllowPrivate(currentConfig(plugin).allowPrivateWebhookTargets, HostedEnvConfig.current.isHosted) }
    )

    return WebhookService(
        db = MarketDb({ databaseManager().getSqlClient() as Pool }, SystemClock), clock = SystemClock, ids = SecureIds(),
        endpoints = context.getBean(MarketWebhookEndpointDao::class.java), deliveries = context.getBean(MarketWebhookDeliveryDao::class.java),
        orders = context.getBean(MarketOrderDao::class.java), orderItems = context.getBean(MarketOrderItemDao::class.java), sender = sender,
        store = { wiring.site().let { StoreInfo(it.name, it.baseUrl) } }
    )
}

/**
 * The credit ledger on the plugin's beans (MK-091): the hold of checkout ([CreditService.checkoutHolds]) and the capture, release, re-tender and re-hold of the
 * order transitions ([CreditService] is the order service's `CreditSettlement`); one per plugin instance.
 */
internal fun creditService(plugin: MarketPlugin): CreditService {
    cachedCredits?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(OrderWiringHolder) {
        cachedCredits?.takeIf { it.first === plugin }?.second ?: buildCreditService(plugin).also { cachedCredits = plugin to it }
    }
}

private fun buildCreditService(plugin: MarketPlugin): CreditService {
    val context = plugin.applicationContext

    return CreditService(
        SystemClock, context.getBean(MarketCreditAccountDao::class.java), context.getBean(MarketCreditTxDao::class.java), context.getBean(MarketCreditEntryDao::class.java)
    )
}

/** The order service that can create and move orders (reservations, the `order.paid` webhook, the frozen exchange rate); one per plugin instance. */
internal fun orderService(plugin: MarketPlugin): OrderService {
    cachedOrders?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(OrderWiringHolder) {
        cachedOrders?.takeIf { it.first === plugin }?.second ?: buildOrderService(plugin).also { cachedOrders = plugin to it }
    }
}

private fun buildOrderService(plugin: MarketPlugin): OrderService {
    val context = plugin.applicationContext
    val clock = SystemClock
    val orderDao = context.getBean(MarketOrderDao::class.java)
    val redemptionDao = context.getBean(MarketRedemptionDao::class.java)
    val locks = Locks(orderDao, context.getBean(MarketOrderItemDao::class.java), redemptionDao, context.getBean(MarketCreditAccountDao::class.java))
    val redemptions = RedemptionService(clock, locks, redemptionDao)
    val cart = cartService(plugin)
    val rates = context.getBean(MarketCurrencyRateDao::class.java)
    val webhooks by lazy { webhookService(plugin) }
    val payments by lazy { paymentService(plugin) }
    val credits = creditService(plugin)

    return OrderService(
        clock, SecureIds(), orderDao, context.getBean(MarketOrderItemDao::class.java), context.getBean(MarketOrderEventDao::class.java),
        context.getBean(MarketPaymentDao::class.java), redemptions, { conn, userId -> cart.clearAfterCheckout(conn, userId) },
        // MK-091: the credit hold of O1 and the capture / release / re-tender / re-hold of the transitions are real ledger postings
        credits = credits.checkoutHolds, settlement = credits,
        reservations = ReservationService(clock, locks, redemptions, orderDao),
        webhooks = PaidWebhooks { conn, orderId -> webhooks.emitOrderPaid(conn, orderId) },
        // O2 / O4 issue the invoice inside the transition (12 section 6.1, MK-144 wires what MK-143 built); the effects of the slices that
        // have not landed still go to PENDING_SLICES
        foreign = InvoiceEffects(invoiceService(plugin), orderDao, ForeignEffects.PENDING_SLICES),
        rates = { sqlClient -> rates.getAll(sqlClient).filter { it.rate.signum() > 0 }.associate { it.currency to it.rate } },
        statsCurrency = { currentConfig(plugin).statsCurrency.name },
        // MK-079: the re-reserve of an accepted late payment checks `limitPerPlayer`; a rejected review and a duplicate payment request their refund
        limits = ProductPurchaseLimits(orderDao, context.getBean(MarketProductDao::class.java), context.getBean(MarketEntitlementDao::class.java), clock),
        refunds = context.getBean(MarketRefundDao::class.java),
        // the duplicates an accepted review finds are judged by the same two questions as a duplicate that arrives on a paid order
        duplicates = DuplicateRefundPolicy { conn, providerId -> payments.duplicateRefundRule(conn, providerId) }
    )
}

/** The payment service (phase C of checkout, `/pay`, cancel, status, events) on the plugin's beans; one per plugin instance. */
internal fun paymentService(plugin: MarketPlugin): PaymentService {
    cachedPayments?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(OrderWiringHolder) {
        cachedPayments?.takeIf { it.first === plugin }?.second ?: buildPaymentService(plugin).also { cachedPayments = plugin to it }
    }
}

private fun buildPaymentService(plugin: MarketPlugin): PaymentService {
    val context = plugin.applicationContext
    val databaseManager = { context.getBean(DatabaseManager::class.java) }
    val orderDao = context.getBean(MarketOrderDao::class.java)
    val locks = Locks(orderDao, context.getBean(MarketOrderItemDao::class.java), context.getBean(MarketRedemptionDao::class.java), context.getBean(MarketCreditAccountDao::class.java))
    val wiring = paymentWiring(plugin)

    return PaymentService(
        db = MarketDb({ databaseManager().getSqlClient() as Pool }, SystemClock), locks = locks, clock = SystemClock, ids = SecureIds(),
        config = { currentConfig(plugin) }, orders = orderDao, orderItems = context.getBean(MarketOrderItemDao::class.java),
        orderEvents = context.getBean(MarketOrderEventDao::class.java), payments = context.getBean(MarketPaymentDao::class.java),
        methods = context.getBean(MarketPaymentMethodDao::class.java), creditAccounts = context.getBean(MarketCreditAccountDao::class.java),
        currencyRates = context.getBean(MarketCurrencyRateDao::class.java), lookup = providerLookup(plugin), cipher = wiring.cipher, contexts = attemptContexts(plugin),
        orderService = orderService(plugin), site = wiring.site, readClient = { databaseManager().getSqlClient() },
        products = context.getBean(MarketProductDao::class.java), entitlements = context.getBean(MarketEntitlementDao::class.java),
        // MK-091 (07 section 5 C3): an order whose credit part is not backed by its hold in the ledger is never completed by a payment
        extraPaidGuards = listOf(CreditHoldGuard(creditService(plugin)))
    )
}

internal fun orderAccess(plugin: MarketPlugin): OrderAccess {
    cachedAccess?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(OrderWiringHolder) {
        cachedAccess?.takeIf { it.first === plugin }?.second
            ?: OrderAccess(plugin.applicationContext.getBean(MarketOrderDao::class.java), SystemClock).also { cachedAccess = plugin to it }
    }
}

/** What the order routes read from a request: the order and the caller's role on it (`OrderAccess`, 11 section 5.1). */
internal suspend fun resolveOrder(plugin: MarketPlugin, context: RoutingContext, publicId: String?, sqlClient: SqlClient): OrderAccessResult {
    val authProvider = plugin.applicationContext.getBean(AuthProvider::class.java)
    val sessionUserId = if (authProvider.isLoggedIn(context)) authProvider.getUserIdFromRoutingContext(context) else null
    val request = context.request()
    val ip = com.panomc.plugins.market.service.ClientIpResolver.resolve(context)

    return orderAccess(plugin).resolve(
        publicId, sessionUserId, request.getHeader(TOKEN_HEADER), request.getParam("token"),
        request.method() == io.vertx.core.http.HttpMethod.GET, ip.ip.takeIf { ip.trusted }, sqlClient
    )
}

internal suspend fun payCaller(plugin: MarketPlugin, context: RoutingContext): PayCaller {
    val caller = quoteCaller(plugin, context)

    return PayCaller(caller.canUseTestMode, caller.clientIp, caller.userAgent)
}

/**
 * L1 for the order mutations (`/pay`, `/payment/continue`, `bank-transfer/notify`; 11 section 11): one token from the IP bucket and one from the
 * buyer's bucket per request that passed the schema, `checkoutRateLimitPerMinute` a minute each, 0 switches it off. Rebuilt when the setting changes.
 */
internal class OrderMutationLimiter(private val perMinute: () -> Int) {
    private class Pair(val perMinute: Int, val ip: RateLimiter, val buyer: RateLimiter)

    @Volatile
    private var current: Pair? = null

    fun check(clientIp: String?, buyerKey: String) {
        val n = perMinute()

        if (n <= 0) return

        val limiters = current?.takeIf { it.perMinute == n }
            ?: Pair(n, RateLimiter(n, 60_000L / n), RateLimiter(n, 60_000L / n)).also { current = it }
        val bucket = IpRange.bucketKey(clientIp)
        val retry = maxOf(1L, Math.ceil(60.0 / n).toLong())

        if (bucket != null && !limiters.ip.tryAcquire("ip:$bucket")) throw TooManyRequests(retry)
        if (!limiters.buyer.tryAcquire("b:$buyerKey")) throw TooManyRequests(retry)
    }
}

/** L7: the status poll, 120 a minute per IP. */
internal class StatusLimiter {
    private val limiter = RateLimiter(120, 500L)

    fun check(clientIp: String?) {
        val bucket = IpRange.bucketKey(clientIp) ?: return

        if (!limiter.tryAcquire("ip:$bucket")) throw TooManyRequests(1)
    }
}

internal const val TOKEN_HEADER = "X-Order-Token"
