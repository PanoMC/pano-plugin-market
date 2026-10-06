package com.panomc.plugins.market.routes.api.order

import com.panomc.plugins.market.core.abuse.AbuseLimits
import com.panomc.plugins.market.runtime.beans
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.server.ServerManager
import com.panomc.platform.config.ConfigManager
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
import com.panomc.plugins.market.db.dao.MarketDeliveryDao
import com.panomc.plugins.market.db.dao.MarketEntitlementDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderEventDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketPaymentDao
import com.panomc.plugins.market.db.dao.MarketPaymentMethodDao
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.dao.MarketProductFieldDao
import com.panomc.plugins.market.db.dao.MarketRedemptionDao
import com.panomc.plugins.market.db.dao.MarketMailOutboxDao
import com.panomc.plugins.market.db.dao.MarketProductVariantDao
import com.panomc.plugins.market.db.dao.MarketRefundDao
import com.panomc.plugins.market.db.dao.MarketSubscriptionDao
import com.panomc.plugins.market.db.dao.MarketSubscriptionRenewalDao
import com.panomc.plugins.market.db.dao.MarketWebhookDeliveryDao
import com.panomc.plugins.market.db.dao.MarketWebhookEndpointDao
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.TooManyRequests
import com.panomc.plugins.market.routes.api.OrderAccess
import com.panomc.plugins.market.routes.api.OrderAccessResult
import com.panomc.plugins.market.routes.api.checkout.PlatformUserDirectory
import com.panomc.plugins.market.routes.api.checkout.quoteCaller
import com.panomc.plugins.market.routes.api.payment.attemptContexts
import com.panomc.plugins.market.routes.panel.invoice.invoiceService
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.routes.panel.settings.payment.paymentWiring
import com.panomc.plugins.market.routes.panel.settings.payment.providerLookup
import com.panomc.plugins.market.routes.user.cart.cartService
import com.panomc.plugins.market.routes.panel.block.blockListService
import com.panomc.plugins.market.service.BlockedBuyerGuard
import com.panomc.plugins.market.service.CreditEffects
import com.panomc.plugins.market.service.CreditHoldGuard
import com.panomc.plugins.market.service.CreditService
import com.panomc.plugins.market.service.DeliveryEffects
import com.panomc.plugins.market.service.DeliveryService
import com.panomc.plugins.market.service.DeliveryWebhookReporter
import com.panomc.plugins.market.service.EntitlementService
import com.panomc.plugins.market.service.PermissionGrantService
import com.panomc.plugins.market.service.DuplicateRefundPolicy
import com.panomc.plugins.market.service.CreatorEffects
import com.panomc.plugins.market.service.ForeignEffects
import com.panomc.plugins.market.service.PayoutSettlement
import com.panomc.plugins.market.routes.panel.creatorcode.creatorService
import com.panomc.plugins.market.service.InvoiceEffects
import com.panomc.plugins.market.service.ShippingEffects
import com.panomc.plugins.market.service.MailEffects
import com.panomc.plugins.market.service.MailOutboxService
import com.panomc.plugins.market.service.OrderMails
import com.panomc.plugins.market.service.SubscriptionClosedGuard
import com.panomc.plugins.market.service.SubscriptionEffects
import com.panomc.plugins.market.service.SubscriptionService
import com.panomc.plugins.market.service.SubscriptionWebhooks
import com.panomc.plugins.market.routes.panel.shipping.shippingService
import com.panomc.plugins.market.routes.panel.webhook.discordLabelSource
import com.panomc.plugins.market.routes.panel.webhook.discordWebhookRenderer
import com.panomc.plugins.market.routes.api.payment.attemptLocks
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
import com.panomc.plugins.market.service.platform.PlatformPermissionWriter
import com.panomc.plugins.market.service.platform.PlatformPlayerAccounts
import com.panomc.plugins.market.service.platform.PlatformServerRoster
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

@Volatile
private var cachedDeliveries: Pair<MarketPlugin, DeliveryService>? = null

@Volatile
private var cachedEntitlements: Pair<MarketPlugin, EntitlementService>? = null

@Volatile
private var cachedSubscriptions: Pair<MarketPlugin, SubscriptionService>? = null

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
    val context = plugin.beans
    val databaseManager = { context.getBean(DatabaseManager::class.java) }
    val wiring = paymentWiring(plugin)
    val version = plugin.applicationContext.getBean(com.panomc.platform.PluginManager::class.java).getPlugin(plugin.pluginId).descriptor.version
    val sender = WebhookSender(
        OutboundHttp.create(context.getBean(Vertx::class.java), version), wiring.cipher, SystemClock, version,
        { TargetPolicy.effectiveAllowPrivate(currentConfig(plugin).allowPrivateWebhookTargets, HostedEnvConfig.current.isHosted) }
    )

    return WebhookService(
        db = MarketDb({ databaseManager().getSqlClient() as Pool }, SystemClock), clock = SystemClock, ids = SecureIds(),
        endpoints = context.getBean(MarketWebhookEndpointDao::class.java), deliveries = context.getBean(MarketWebhookDeliveryDao::class.java),
        orders = context.getBean(MarketOrderDao::class.java), orderItems = context.getBean(MarketOrderItemDao::class.java), sender = sender,
        store = { wiring.site().let { StoreInfo(it.name, it.baseUrl) } },
        // MK-102: the outcome of the outbox row of a product WEBHOOK action goes back to its delivery row (D12 / D21)
        reporter = DeliveryWebhookReporter(deliveryService(plugin)),
        // MK-106: format = DISCORD bodies (08 section 16)
        renderer = discordWebhookRenderer(plugin)
    )
}

/** The entitlements created at O2 / O4 (MK-102; 08 section 10); one per plugin instance. */
internal fun entitlementService(plugin: MarketPlugin): EntitlementService {
    cachedEntitlements?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(OrderWiringHolder) {
        cachedEntitlements?.takeIf { it.first === plugin }?.second ?: EntitlementService(
            SystemClock, { currentConfig(plugin) }, plugin.beans.getBean(MarketEntitlementDao::class.java)
        ).also { cachedEntitlements = plugin to it }
    }
}

/**
 * The delivery engine on the plugin's beans (MK-102): the planning of O2 / O4 (through [DeliveryEffects]), the inline executors `CREDIT` and `PERMISSION via=PANO`
 * and the steps `DeliveryJob` runs. The platform seams (users, permission nodes, servers) are the adapters of `service.platform`.
 */
internal fun deliveryService(plugin: MarketPlugin): DeliveryService {
    cachedDeliveries?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(OrderWiringHolder) {
        cachedDeliveries?.takeIf { it.first === plugin }?.second ?: buildDeliveryService(plugin).also { cachedDeliveries = plugin to it }
    }
}

private fun buildDeliveryService(plugin: MarketPlugin): DeliveryService {
    val context = plugin.beans
    val databaseManager = { context.getBean(DatabaseManager::class.java) }
    val orderDao = context.getBean(MarketOrderDao::class.java)
    val locks = Locks(orderDao, context.getBean(MarketOrderItemDao::class.java), context.getBean(MarketRedemptionDao::class.java), context.getBean(MarketCreditAccountDao::class.java))

    return DeliveryService(
        db = MarketDb({ databaseManager().getSqlClient() as Pool }, SystemClock), locks = locks, clock = SystemClock, ids = SecureIds(), config = { currentConfig(plugin) },
        orders = orderDao, orderItems = context.getBean(MarketOrderItemDao::class.java), orderEvents = context.getBean(MarketOrderEventDao::class.java),
        deliveries = context.getBean(MarketDeliveryDao::class.java), entitlements = context.getBean(MarketEntitlementDao::class.java),
        creditAccounts = context.getBean(MarketCreditAccountDao::class.java), products = context.getBean(MarketProductDao::class.java),
        productFields = context.getBean(MarketProductFieldDao::class.java),
        roster = PlatformServerRoster(databaseManager) { context.getBean(ServerManager::class.java) },
        users = PlatformUserDirectory(databaseManager), accounts = PlatformPlayerAccounts(databaseManager), credits = creditService(plugin),
        permissions = PermissionGrantService(
            PlatformPermissionWriter(databaseManager, { context.getBean(PermissionManager::class.java) }, { context.getBean(ServerManager::class.java) }), SystemClock
        ),
        // MK-106: the WEBHOOK executor writes its outbox row here; the DISCORD bodies of action webhooks use the store's default locale
        webhookDeliveries = context.getBean(MarketWebhookDeliveryDao::class.java), discordLabels = discordLabelSource(plugin),
        // MK-142: ORDER_DELIVERED when the fulfillment becomes FULFILLED
        fulfilledMails = orderMails(plugin),
        // MK-114: an ACTION creator payout follows its rows (the creator service is looked up when the first row changes, it needs this service itself)
        payouts = object : PayoutSettlement {
            override suspend fun lock(conn: io.vertx.sqlclient.SqlClient, payoutId: Long) = creatorService(plugin).lock(conn, payoutId)

            override suspend fun settle(conn: io.vertx.sqlclient.SqlClient, payoutId: Long) = creatorService(plugin).settle(conn, payoutId)
        }
    )
}

/** The enqueue side of the order mails (MK-142): the payer's address from the order or the platform user, the order's locale, one outbox row per mail. */
internal fun orderMails(plugin: MarketPlugin): OrderMails {
    val context = plugin.beans
    val databaseManager = { context.getBean(DatabaseManager::class.java) }
    val outboxDao = context.getBean(MarketMailOutboxDao::class.java)

    return OrderMails(
        config = { currentConfig(plugin) }, clock = SystemClock,
        outbox = MailOutboxService({ currentConfig(plugin) }, SystemClock, outboxDao, context.getBean(MarketOrderEventDao::class.java)),
        mailOutbox = outboxDao, orderItems = context.getBean(MarketOrderItemDao::class.java), orderEvents = context.getBean(MarketOrderEventDao::class.java), users = PlatformUserDirectory(databaseManager),
        defaultLocale = { runCatching { context.getBean(ConfigManager::class.java).config.locale }.getOrNull()?.takeIf { it.isNotBlank() } ?: MailOutboxService.DEFAULT_LOCALE }
    )
}

/**
 * The subscription service on the plugin's beans (MK-121): the pending row of checkout (`OrderService.subscriptions`), the activation and the closing of
 * O2 / O4 / O5 (`SubscriptionEffects`), the gateway data of a success and the plan of a start (`PaymentService.subscriptionHooks`), the late-renewal guard
 * and the `SubscriptionUpdated` half of the inbound sink. One per plugin instance; the payment service is looked up when it is asked for capabilities
 * (never at construction, the two build each other).
 */
internal fun subscriptionService(plugin: MarketPlugin): SubscriptionService {
    cachedSubscriptions?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(OrderWiringHolder) {
        cachedSubscriptions?.takeIf { it.first === plugin }?.second ?: buildSubscriptionService(plugin).also { cachedSubscriptions = plugin to it }
    }
}

private fun buildSubscriptionService(plugin: MarketPlugin): SubscriptionService {
    val context = plugin.beans
    val orderDao = context.getBean(MarketOrderDao::class.java)
    val orderEvents = context.getBean(MarketOrderEventDao::class.java)
    val locks = Locks(orderDao, context.getBean(MarketOrderItemDao::class.java), context.getBean(MarketRedemptionDao::class.java), context.getBean(MarketCreditAccountDao::class.java))

    return SubscriptionService(
        clock = SystemClock, config = { currentConfig(plugin) }, locks = locks, subscriptions = context.getBean(MarketSubscriptionDao::class.java),
        renewals = context.getBean(MarketSubscriptionRenewalDao::class.java), orders = orderDao, orderItems = context.getBean(MarketOrderItemDao::class.java),
        orderEvents = orderEvents, payments = context.getBean(MarketPaymentDao::class.java), products = context.getBean(MarketProductDao::class.java),
        variants = context.getBean(MarketProductVariantDao::class.java), entitlements = context.getBean(MarketEntitlementDao::class.java),
        cipher = paymentWiring(plugin).cipher, capabilities = { providerId, sqlClient -> paymentService(plugin).capabilitiesOf(providerId, sqlClient) },
        deliveries = deliveryService(plugin),
        mail = MailOutboxService({ currentConfig(plugin) }, SystemClock, context.getBean(MarketMailOutboxDao::class.java), orderEvents),
        webhooks = SubscriptionWebhooks { conn, event, subjectKey, orderId, data, testMode -> webhookService(plugin).emit(conn, event, subjectKey, orderId, data, testMode) },
        // MK-122: the renewal orders and attempts (random ids) and the cancel of the unpaid renewal order of an ended subscription (the order service is looked up late)
        ids = SecureIds(), orderService = { orderService(plugin) },
        // MK-151: a blocked owner is not charged again (09 section 8.3, 11 section 9.3); no hit counter under the renewal's row locks
        blocks = blockListService(plugin).asBuyerBlocks(recordHit = false)
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
    val context = plugin.beans

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
    val context = plugin.beans
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
        // MK-121: the pending subscription row of O1 (09 section 4.3), created in the order transaction
        subscriptions = subscriptionService(plugin),
        reservations = ReservationService(clock, locks, redemptions, orderDao),
        webhooks = PaidWebhooks { conn, orderId -> webhooks.emitOrderPaid(conn, orderId) },
        // O2 / O4 issue the invoice inside the transition (12 section 6.1, MK-144), then the entitlements and the GRANT / RENEW delivery rows are written (MK-102);
        // MK-092: the credit-granting lines (TOPUP / GIFT) and the cashback are posted inside the transition too
        // WIRE-1: StartShipping goes to the shipping service (derived shippingStatus); the rest still to PENDING_SLICES
        // MK-114: AccrueCreatorEarning goes to the creator service (the earning of the order's creator code, 21 section 7.1)
        foreign = CreatorEffects({ creatorService(plugin) }, orderDao, CreditEffects(
            credits, orderDao, context.getBean(MarketOrderEventDao::class.java), clock, { currentConfig(plugin) },
            PlatformUserDirectory { context.getBean(DatabaseManager::class.java) },
            InvoiceEffects(
                invoiceService(plugin), orderDao,
                // MK-121: SubscriptionOnOrderPaid / SubscriptionOnClosedUnpaid go to the subscription service; MK-122: it is the outermost of the delivery chain because a
                // renewal order must not reach GrantEntitlements (the subscription's own entitlement runs on); the rest still to PENDING_SLICES
                SubscriptionEffects({ subscriptionService(plugin) }, DeliveryEffects(
                    entitlementService(plugin), deliveryService(plugin), orderDao,
                    // MK-142: QueueMail (ORDER_CONFIRMATION, GIFT_RECEIVED) goes to the order mails; the rest still to PENDING_SLICES
                    ShippingEffects({ shippingService(plugin) }, MailEffects(orderMails(plugin), orderDao, ForeignEffects.PENDING_SLICES))
                ))
            )
        )),
        rates = { sqlClient -> rates.getAll(sqlClient).filter { it.rate.signum() > 0 }.associate { it.currency to it.rate } },
        statsCurrency = { currentConfig(plugin).statsCurrency.name },
        // MK-079: the re-reserve of an accepted late payment checks `limitPerPlayer`; a rejected review and a duplicate payment request their refund
        limits = ProductPurchaseLimits(orderDao, context.getBean(MarketProductDao::class.java), context.getBean(MarketEntitlementDao::class.java), clock),
        refunds = context.getBean(MarketRefundDao::class.java),
        // the duplicates an accepted review finds are judged by the same two questions as a duplicate that arrives on a paid order
        duplicates = DuplicateRefundPolicy { conn, providerId -> payments.duplicateRefundRule(conn, providerId) },
        // MK-142: the "order received" mail of O3
        receivedMails = orderMails(plugin)
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
    val context = plugin.beans
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
        // MK-121 (09 section 8.5): a payment for a renewal of a closed subscription goes to review (LATE)
        // MK-151 (11 section 9.3, PP-5): a payer or recipient blocked after checkout sends the paid order to review (BLOCKED_BUYER)
        extraPaidGuards = listOf(CreditHoldGuard(creditService(plugin)), SubscriptionClosedGuard { subscriptionService(plugin) }, BlockedBuyerGuard(blockListService(plugin))),
        // WIRE-1 (MK-077 seam): the query paths and `continue` run under the attempt locks the inbound pipeline holds
        attemptLocks = attemptLocks(plugin),
        // MK-121: the plan of a start, the gateway data of a success (09 sections 4.2 and 4.4)
        subscriptionHooks = subscriptionService(plugin),
        // MK-142: the bank transfer instructions and "order received" mails of an attempt's transitions
        mails = orderMails(plugin),
        // MK-172: an order that waits for review raises MARKET_ORDER_REVIEW (only with the host registry)
        alerts = com.panomc.plugins.market.notification.marketAlerts(plugin)
    )
}

internal fun orderAccess(plugin: MarketPlugin): OrderAccess {
    cachedAccess?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(OrderWiringHolder) {
        cachedAccess?.takeIf { it.first === plugin }?.second
            ?: OrderAccess(plugin.beans.getBean(MarketOrderDao::class.java), SystemClock).also { cachedAccess = plugin to it }
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
            ?: Pair(n, RateLimiter(n, AbuseLimits.refillMs(n)!!), RateLimiter(n, AbuseLimits.refillMs(n)!!)).also { current = it }
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
