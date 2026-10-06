package com.panomc.plugins.market.routes.panel.dispute

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketBlockDao
import com.panomc.plugins.market.db.dao.MarketCreatorEarningDao
import com.panomc.plugins.market.db.dao.MarketCreditAccountDao
import com.panomc.plugins.market.db.dao.MarketCreditTxDao
import com.panomc.plugins.market.db.dao.MarketDeliveryDao
import com.panomc.plugins.market.db.dao.MarketDisputeDao
import com.panomc.plugins.market.db.dao.MarketEntitlementDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderEventDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketPaymentDao
import com.panomc.plugins.market.db.dao.MarketRedemptionDao
import com.panomc.plugins.market.db.model.DisputeRecordStatus
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.log.OpenedMarketDisputeLog
import com.panomc.plugins.market.log.RanMarketChargebackActionsLog
import com.panomc.plugins.market.log.UpdatedMarketDisputeLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.api.order.creditService
import com.panomc.plugins.market.routes.api.order.deliveryService
import com.panomc.plugins.market.routes.api.order.entitlementService
import com.panomc.plugins.market.routes.api.order.paymentService
import com.panomc.plugins.market.routes.api.order.subscriptionService
import com.panomc.plugins.market.routes.api.order.webhookService
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.parseEnum
import com.panomc.plugins.market.routes.base.parseId
import com.panomc.plugins.market.routes.base.parseText
import com.panomc.plugins.market.routes.panel.order.actingUserId
import com.panomc.plugins.market.routes.panel.order.logOrderDecision
import com.panomc.plugins.market.routes.panel.order.panelOrder
import com.panomc.plugins.market.routes.panel.refund.parseMoney
import com.panomc.plugins.market.routes.panel.refund.refundService
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.runtime.beans
import com.panomc.plugins.market.service.DisputeService
import com.panomc.plugins.market.service.StandardDisputeEffects
import com.panomc.plugins.market.service.StandardRefundEffects
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.sqlclient.Pool

private object DisputeWiringHolder

@Volatile
private var cachedService: Pair<MarketPlugin, DisputeService>? = null

/** The dispute flow on the plugin's beans (MK-112); one per plugin instance. */
internal fun disputeService(plugin: MarketPlugin): DisputeService {
    cachedService?.takeIf { it.first === plugin }?.let { return it.second }

    // built outside the lock: it reaches into the refund, delivery and webhook wiring
    val built = buildDisputeService(plugin)

    return synchronized(DisputeWiringHolder) { cachedService?.takeIf { it.first === plugin }?.second ?: built.also { cachedService = plugin to it } }
}

private fun buildDisputeService(plugin: MarketPlugin): DisputeService {
    val context = plugin.beans
    val databaseManager = { context.getBean(DatabaseManager::class.java) }
    val orderDao = context.getBean(MarketOrderDao::class.java)
    val locks = Locks(orderDao, context.getBean(MarketOrderItemDao::class.java), context.getBean(MarketRedemptionDao::class.java), context.getBean(MarketCreditAccountDao::class.java))
    // the creator reversal of the refund effects is the one of a chargeback (in full); the other collaborators of that composition are not needed here
    val refundEffects = StandardRefundEffects(clock = SystemClock, earnings = context.getBean(MarketCreatorEarningDao::class.java), tablePrefix = { orderDao.prefix() })

    return DisputeService(
        db = MarketDb({ databaseManager().getSqlClient() as Pool }, SystemClock), locks = locks, clock = SystemClock, config = { currentConfig(plugin) }, orders = orderDao,
        orderItems = context.getBean(MarketOrderItemDao::class.java), orderEvents = context.getBean(MarketOrderEventDao::class.java),
        payments = context.getBean(MarketPaymentDao::class.java), disputes = context.getBean(MarketDisputeDao::class.java), blocks = context.getBean(MarketBlockDao::class.java),
        deliveries = context.getBean(MarketDeliveryDao::class.java), entitlements = context.getBean(MarketEntitlementDao::class.java),
        creditTxs = context.getBean(MarketCreditTxDao::class.java), credits = creditService(plugin), deliveryService = deliveryService(plugin),
        entitlementService = entitlementService(plugin), refundService = refundService(plugin),
        // WIRE-2: after the commit the buyer's other subscriptions end too (11 section 10 step 4)
        effects = StandardDisputeEffects(
            refundEffects, webhookService(plugin),
            { order -> subscriptionService(plugin).onChargebackOwner(MarketDb({ databaseManager().getSqlClient() as Pool }, SystemClock), { after -> paymentService(plugin).runAfterCommit(after) }, order) }
        ) { conn, order, dispute -> subscriptionService(plugin).onOrderChargeback(conn, order, dispute) }
    )
}

private fun jsonBody(context: RoutingContext): JsonObject = context.body().asJsonObject() ?: JsonObject()

private fun bodyValidation(schemaRepository: SchemaRepository, required: Boolean): ValidationHandler {
    val builder = ValidationHandlerBuilder.create(schemaRepository).body(Bodies.json(objectSchema().allowAdditionalProperties(true)))

    return (if (required) builder.predicate(RequestPredicate.BODY_REQUIRED) else builder).build()
}

/**
 * `POST /api/panel/market/orders/:id/disputes` (`P:PAY`, 04 section 7, 21 section 5.1): a chargeback the gateway did not report. Body `amount?`, `reason?` (at
 * most 255 characters); `{id}`. 400 `INVALID_ORDER_TRANSITION` for an order that is not paid. Activity log `OPENED_MARKET_DISPUTE`.
 */
@Endpoint
class PanelCreateDisputeAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/orders/:id/disputes", RouteType.POST))

    override val nodes: Set<MarketNode> = setOf(MarketNode.PAYMENTS)

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = bodyValidation(schemaRepository, required = false)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val order = panelOrder(plugin, context)
        val body = jsonBody(context)
        val amount = body.getValue("amount")?.let { parseMoney(it, "amount") }
        val reason = parseText(body.getValue("reason") as? String, "reason")
        val dispute = disputeService(plugin).open(order.id, amount, reason, actingUserId(plugin, context))

        logOrderDecision(plugin, context) { userId, username -> OpenedMarketDisputeLog(userId, username, plugin.pluginId, order.id, dispute.id) }

        return Successful(mapOf("id" to dispute.id))
    }
}

/**
 * `PUT /api/panel/market/disputes/:disputeId` (`P:PAY`, 04 section 7, 21 section 5.1): `status` `WON`, `LOST` or `CLOSED`; `{}`; 409 `INVALID_STATE`. A won (or closed)
 * open dispute is O12. Activity log `UPDATED_MARKET_DISPUTE`.
 */
@Endpoint
class PanelUpdateDisputeAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/disputes/:disputeId", RouteType.PUT))

    override val nodes: Set<MarketNode> = setOf(MarketNode.PAYMENTS)

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = bodyValidation(schemaRepository, required = true)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val id = parseId(context.pathParam("disputeId"), "disputeId")
        val status = parseEnum(arrayOf(DisputeRecordStatus.WON, DisputeRecordStatus.LOST, DisputeRecordStatus.CLOSED), jsonBody(context).getValue("status") as? String, "status")
        val dispute = try {
            disputeService(plugin).resolve(id, status, actingUserId(plugin, context))
        } catch (e: NoSuchElementException) {
            throw NotFound()
        }

        logOrderDecision(plugin, context) { userId, username -> UpdatedMarketDisputeLog(userId, username, plugin.pluginId, dispute.orderId, dispute.id, dispute.status.name) }

        return Successful()
    }
}

/**
 * `POST /api/panel/market/orders/:id/chargeback-actions` (`P:PAY`, 04 section 7, 11 section 10): runs the chargeback actions that were held back with
 * `NEEDS_CONFIRMATION`; `{created}`. Activity log `RAN_MARKET_CHARGEBACK_ACTIONS`.
 */
@Endpoint
class PanelRunChargebackActionsAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/orders/:id/chargeback-actions", RouteType.POST))

    override val nodes: Set<MarketNode> = setOf(MarketNode.PAYMENTS)

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val order = panelOrder(plugin, context)
        val created = disputeService(plugin).runChargebackActions(order.id)

        logOrderDecision(plugin, context) { userId, username -> RanMarketChargebackActionsLog(userId, username, plugin.pluginId, order.id, created) }

        return Successful(mapOf("created" to created))
    }
}
