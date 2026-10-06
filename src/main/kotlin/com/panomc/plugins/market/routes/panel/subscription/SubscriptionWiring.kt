package com.panomc.plugins.market.routes.panel.subscription

import com.panomc.platform.db.DatabaseManager
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketSubscriptionDao
import com.panomc.plugins.market.db.dao.MarketSubscriptionRenewalDao
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.job.SubscriptionJob
import com.panomc.plugins.market.routes.api.order.paymentService
import com.panomc.plugins.market.routes.api.order.subscriptionService
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.routes.panel.settings.payment.paymentWiring
import com.panomc.plugins.market.routes.user.subscription.SubscriptionViews
import com.panomc.plugins.market.runtime.beans
import com.panomc.plugins.market.service.PortalPages
import com.panomc.plugins.market.service.SubscriptionActions
import com.panomc.plugins.market.service.SubscriptionEventSink
import io.vertx.sqlclient.Pool

private object SubscriptionWiringHolder

private class SubscriptionBeans(val plugin: MarketPlugin, val actions: SubscriptionActions, val job: SubscriptionJob, val views: SubscriptionViews)

@Volatile
private var cached: SubscriptionBeans? = null

private fun beansOf(plugin: MarketPlugin): SubscriptionBeans {
    cached?.takeIf { it.plugin === plugin }?.let { return it }

    return synchronized(SubscriptionWiringHolder) { cached?.takeIf { it.plugin === plugin } ?: build(plugin).also { cached = it } }
}

private fun build(plugin: MarketPlugin): SubscriptionBeans {
    val context = plugin.beans
    val databaseManager = { context.getBean(DatabaseManager::class.java) }
    val db = MarketDb({ databaseManager().getSqlClient() as Pool }, SystemClock)
    val subs = subscriptionService(plugin)
    val sink = SubscriptionEventSink(db, { subscriptionService(plugin) }).withPayments { paymentService(plugin) }
    val subscriptions = context.getBean(MarketSubscriptionDao::class.java)
    val sqlClient: suspend () -> io.vertx.sqlclient.SqlClient = { databaseManager().getSqlClient() }
    val actions = SubscriptionActions(
        SystemClock, db, subs, subscriptions, paymentService(plugin), sqlClient, sink, { paymentWiring(plugin).site().baseUrl.trimEnd('/') + "/profile/subscriptions" },
        PortalPages(SystemClock)
    )
    val job = SubscriptionJob(
        clock = SystemClock, db = db, subs = subs, subscriptions = subscriptions, payments = paymentService(plugin), config = { currentConfig(plugin) }, sqlClient = sqlClient,
        events = sink, actions = actions
    )
    val views = SubscriptionViews(SystemClock, subscriptions, context.getBean(MarketSubscriptionRenewalDao::class.java)) { providerId, client ->
        paymentService(plugin).capabilitiesOf(providerId, client)
    }

    return SubscriptionBeans(plugin, actions, job, views)
}

/** The cancel, resume, portal and retry use cases on the plugin's beans (MK-123); one per plugin instance. */
internal fun subscriptionActions(plugin: MarketPlugin): SubscriptionActions = beansOf(plugin).actions

/** The `SubscriptionJob` the scheduler runs, and the admin retry calls `chargeOne` on (MK-122 / MK-123); one per plugin instance. */
internal fun subscriptionJob(plugin: MarketPlugin): SubscriptionJob = beansOf(plugin).job

/** The buyer and panel views of subscriptions (09 section 13). */
internal fun subscriptionViews(plugin: MarketPlugin): SubscriptionViews = beansOf(plugin).views
