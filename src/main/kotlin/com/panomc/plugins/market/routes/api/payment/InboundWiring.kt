package com.panomc.plugins.market.routes.api.payment

import com.panomc.plugins.market.runtime.beans
import com.panomc.platform.db.DatabaseManager
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.abuse.Redactor
import com.panomc.plugins.market.core.time.SecureIds
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketCreditAccountDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketPaymentDao
import com.panomc.plugins.market.db.dao.MarketPaymentEventDao
import com.panomc.plugins.market.db.dao.MarketPaymentMethodDao
import com.panomc.plugins.market.db.dao.MarketProviderStateDao
import com.panomc.plugins.market.db.dao.MarketRedemptionDao
import com.panomc.plugins.market.db.dao.MarketSubscriptionDao
import com.panomc.plugins.market.db.model.ProviderStateKind
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.job.InboundEventRetryJob
import com.panomc.plugins.market.provider.ProviderContextImpl
import com.panomc.plugins.market.provider.ProviderLogImpl
import com.panomc.plugins.market.provider.ProviderStateStoreImpl
import com.panomc.plugins.market.provider.StoredProviderSettings
import com.panomc.plugins.market.routes.api.order.paymentService
import com.panomc.plugins.market.routes.api.order.subscriptionService
import com.panomc.plugins.market.routes.api.shipping.shippingInboundDispatcher
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.routes.panel.settings.payment.paymentWiring
import com.panomc.plugins.market.routes.panel.settings.payment.providerLookup
import com.panomc.plugins.market.service.PaymentContexts
import com.panomc.plugins.market.service.SubscriptionEventSink
import com.panomc.plugins.market.spi.MarketSpi
import com.panomc.plugins.market.spi.common.ProviderContext
import com.panomc.plugins.market.spi.payment.AttemptUrls
import com.panomc.plugins.market.spi.payment.PaymentAttemptView
import com.panomc.plugins.market.spi.payment.PaymentContext
import com.panomc.plugins.market.spi.payment.PaymentLookup
import com.panomc.plugins.market.spi.payment.PaymentUrls
import io.vertx.core.Vertx
import io.vertx.ext.web.client.WebClient
import io.vertx.sqlclient.Pool

/**
 * The context of a provider call that has an attempt in play (start, continue, inbound): the base context of [ProviderContextImpl] plus the
 * real `ctx.payments` ([AttemptLookup], this provider's own attempts) and the real, reentrant `ctx.withAttemptLock` ([AttemptLocks]). The panel's
 * settings hooks keep the context of the panel wiring whose lookups answer "none" (no attempt is in play there).
 */
internal class AttemptPaymentContext(
    private val base: ProviderContext,
    override val payments: PaymentLookup,
    private val locks: AttemptLocks
) : PaymentContext, ProviderContext by base {
    override val urls: PaymentUrls = object : PaymentUrls {
        override fun webhook(channel: String): String =
            "${base.site.baseUrl}/api/market/payments/${base.providerId}/webhook" + if (channel == MarketSpi.DEFAULT_CHANNEL) "" else "/$channel"

        override fun checkoutPage(): String = "${base.site.baseUrl}/store/checkout"

        override fun forAttempt(attempt: PaymentAttemptView): AttemptUrls {
            val root = "${base.site.baseUrl}/api/market/payments/${base.providerId}"

            return AttemptUrls(
                success = "$root/return/${attempt.token}/success", cancel = "$root/return/${attempt.token}/cancel",
                pending = "$root/return/${attempt.token}/pending", result = "$root/return/${attempt.token}/result",
                notify = "$root/notify/${attempt.token}", orderPage = "${base.site.baseUrl}/store/order/${attempt.orderPublicId}"
            )
        }
    }

    override suspend fun <T> withAttemptLock(attemptId: Long, block: suspend () -> T): T = locks.with(attemptId, block)
}

private object InboundWiringHolder

@Volatile
private var cachedLocks: Pair<MarketPlugin, AttemptLocks>? = null

@Volatile
private var cachedContexts: Pair<MarketPlugin, PaymentContexts>? = null

@Volatile
private var cachedStore: Pair<MarketPlugin, InboundEventStore>? = null

@Volatile
private var cachedDispatcher: Pair<MarketPlugin, InboundDispatcher>? = null

@Volatile
private var cachedPages: Pair<MarketPlugin, AttemptPageService>? = null

@Volatile
private var cachedRetry: Pair<MarketPlugin, InboundEventRetryJob>? = null

/**
 * Where refund, dispute and subscription events go (MK-110 / MK-111 / MK-112, MK-121): until their slices install a sink the events make their row
 * `FAILED` (replayable), see [PaymentEventSink.UNHANDLED]. Read on every event, so a slice may set it at any time.
 */
@Volatile
internal var paymentEventSink: PaymentEventSink = PaymentEventSink.UNHANDLED

/** The in-process attempt locks of this plugin instance; one registry for the inbound pipeline and the provider contexts. */
internal fun attemptLocks(plugin: MarketPlugin): AttemptLocks {
    cachedLocks?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(InboundWiringHolder) { cachedLocks?.takeIf { it.first === plugin }?.second ?: AttemptLocks().also { cachedLocks = plugin to it } }
}

/**
 * The provider context factory for calls that have an attempt in play: the quote / settings hooks keep `paymentWiring(plugin).contexts`, the payment
 * service (start, continue) and the inbound pipeline use this one (MK-076 open seam 6: `ctx.payments.*` and `withAttemptLock` were stand-ins).
 */
internal fun attemptContexts(plugin: MarketPlugin): PaymentContexts {
    cachedContexts?.takeIf { it.first === plugin }?.let { return it.second }

    val built = buildContexts(plugin)

    return synchronized(InboundWiringHolder) { cachedContexts?.takeIf { it.first === plugin }?.second ?: built.also { cachedContexts = plugin to it } }
}

private fun buildContexts(plugin: MarketPlugin): PaymentContexts {
    val context = plugin.beans
    val wiring = paymentWiring(plugin)
    val databaseManager by lazy { context.getBean(DatabaseManager::class.java) }
    val db = MarketDb({ databaseManager.getSqlClient() as Pool }, SystemClock)
    val stateDao = context.getBean(MarketProviderStateDao::class.java)
    val payments = context.getBean(MarketPaymentDao::class.java)
    val orders = context.getBean(MarketOrderDao::class.java)
    val vertx = context.getBean(Vertx::class.java)
    val http by lazy { context.getBean(WebClient::class.java) }
    val locks = attemptLocks(plugin)
    val subscriptions = context.getBean(MarketSubscriptionDao::class.java)

    return PaymentContexts { provider, settings, testMode ->
        val secrets = (settings as? StoredProviderSettings)?.valuesOf(provider.settingsSchema().secretKeys) ?: emptySet()
        val log = ProviderLogImpl(provider.id, Redactor(secrets))
        val state = ProviderStateStoreImpl(ProviderStateKind.PAYMENT, provider.id, stateDao, db, wiring.cipher, SystemClock)
        val base = ProviderContextImpl(provider.id, settings, testMode, http, vertx, log, state, wiring.site(), SystemClock)

        AttemptPaymentContext(base, AttemptLookup(provider.id, payments, orders, wiring.cipher, subscriptions) { databaseManager.getSqlClient() }, locks)
    }
}

internal fun inboundEventStore(plugin: MarketPlugin): InboundEventStore {
    cachedStore?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(InboundWiringHolder) {
        cachedStore?.takeIf { it.first === plugin }?.second ?: run {
            val context = plugin.beans
            val databaseManager = { context.getBean(DatabaseManager::class.java) }

            DbInboundEventStore(context.getBean(MarketPaymentEventDao::class.java)) { databaseManager().getSqlClient() }.also { cachedStore = plugin to it }
        }
    }
}

/** The pipeline of `02` section 7.3 on the plugin's beans; one per plugin instance. */
internal fun inboundDispatcher(plugin: MarketPlugin): InboundDispatcher {
    cachedDispatcher?.takeIf { it.first === plugin }?.let { return it.second }

    // built outside the lock: it reaches into the payment service wiring, which reaches back into this file (a lock order inversion otherwise)
    val built = buildDispatcher(plugin)

    return synchronized(InboundWiringHolder) { cachedDispatcher?.takeIf { it.first === plugin }?.second ?: built.also { cachedDispatcher = plugin to it } }
}

private fun inboundAttempts(plugin: MarketPlugin): PaymentInboundAttempts {
    val context = plugin.beans
    val databaseManager = { context.getBean(DatabaseManager::class.java) }
    val orderDao = context.getBean(MarketOrderDao::class.java)
    val locks = Locks(orderDao, context.getBean(MarketOrderItemDao::class.java), context.getBean(MarketRedemptionDao::class.java), context.getBean(MarketCreditAccountDao::class.java))

    return PaymentInboundAttempts(
        context.getBean(MarketPaymentDao::class.java), orderDao, paymentService(plugin), paymentWiring(plugin).cipher,
        MarketDb({ databaseManager().getSqlClient() as Pool }, SystemClock), locks, SystemClock
    ) { databaseManager().getSqlClient() }
}

private fun buildDispatcher(plugin: MarketPlugin): InboundDispatcher {
    val context = plugin.beans
    val databaseManager = { context.getBean(DatabaseManager::class.java) }
    val wiring = paymentWiring(plugin)
    val attempts = inboundAttempts(plugin)
    val db = MarketDb({ databaseManager().getSqlClient() as Pool }, SystemClock)
    val stateDao = context.getBean(MarketProviderStateDao::class.java)
    val providers = RegistryInboundProviders(
        providerLookup(plugin), context.getBean(MarketPaymentMethodDao::class.java), wiring.cipher, attemptContexts(plugin), { currentConfig(plugin) },
        { databaseManager().getSqlClient() }
    ) { providerId -> ProviderStateStoreImpl(ProviderStateKind.PAYMENT, providerId, stateDao, db, wiring.cipher, SystemClock).values() }

    return InboundDispatcher(
        inboundEventStore(plugin), attempts, providers, PaymentEventApplier(attempts) { event, attempt, ctx ->
            // MK-111: a refund notification is applied by the refund service (21 section 4)
            if (event is com.panomc.plugins.market.spi.payment.PaymentEvent.RefundUpdated && attempt != null) {
                com.panomc.plugins.market.routes.panel.refund.refundService(plugin).onRefundUpdated(event, attempt, ctx.eventKey, ctx.requestHash)
            } else {
                // MK-121: SubscriptionUpdated is applied by the subscription service, every other non-attempt event goes to the sink a slice installed
                SubscriptionEventSink(db, { subscriptionService(plugin) }, paymentEventSink).apply(event, attempt, ctx)
            }
        },
        attemptLocks(plugin), SystemClock, SecureIds(), { wiring.site().baseUrl.trimEnd('/') }
    )
}

/** `GET /api/market/payments/attempts/:attemptToken/page` (02 section 6). */
internal fun attemptPageService(plugin: MarketPlugin): AttemptPageService {
    cachedPages?.takeIf { it.first === plugin }?.let { return it.second }

    val built = AttemptPageService(inboundAttempts(plugin), paymentWiring(plugin).cipher, SecureIds())

    return synchronized(InboundWiringHolder) { cachedPages?.takeIf { it.first === plugin }?.second ?: built.also { cachedPages = plugin to it } }
}

/** The retry job for `MarketScheduler` (MK-078) to call on every tick. */
internal fun inboundEventRetryJob(plugin: MarketPlugin): InboundEventRetryJob {
    cachedRetry?.takeIf { it.first === plugin }?.let { return it.second }

    val built = InboundEventRetryJob(inboundDispatcher(plugin), inboundEventStore(plugin), SystemClock, shipping = InboundEventRetryJob.ShippingRetry { row -> shippingInboundDispatcher(plugin).retry(row) })

    return synchronized(InboundWiringHolder) { cachedRetry?.takeIf { it.first === plugin }?.second ?: built.also { cachedRetry = plugin to it } }
}
