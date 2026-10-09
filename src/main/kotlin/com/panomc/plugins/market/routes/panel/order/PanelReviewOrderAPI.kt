package com.panomc.plugins.market.routes.panel.order

import com.panomc.plugins.market.runtime.beans
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PluginActivityLog
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketCreditAccountDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderEventDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketPaymentDao
import com.panomc.plugins.market.db.dao.MarketRedemptionDao
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.log.ReviewedMarketOrderLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.api.order.orderService
import com.panomc.plugins.market.routes.api.order.paymentService
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.parseId
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.service.OrderReviewService
import com.panomc.plugins.market.service.ReviewDecision
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema
import io.vertx.json.schema.common.dsl.Schemas.enumSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.sqlclient.Pool

private object OrderReviewWiringHolder

@Volatile
private var cachedReview: Pair<MarketPlugin, OrderReviewService>? = null

/** The review / status service on the plugin's beans (MK-079); one per plugin instance. */
internal fun orderReviewService(plugin: MarketPlugin): OrderReviewService {
    cachedReview?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(OrderReviewWiringHolder) {
        cachedReview?.takeIf { it.first === plugin }?.second ?: buildOrderReviewService(plugin).also { cachedReview = plugin to it }
    }
}

private fun buildOrderReviewService(plugin: MarketPlugin): OrderReviewService {
    val context = plugin.beans
    val databaseManager = { context.getBean(DatabaseManager::class.java) }
    val orderDao = context.getBean(MarketOrderDao::class.java)
    val locks = Locks(orderDao, context.getBean(MarketOrderItemDao::class.java), context.getBean(MarketRedemptionDao::class.java), context.getBean(MarketCreditAccountDao::class.java))
    val payments by lazy { paymentService(plugin) }

    return OrderReviewService(
        db = MarketDb({ databaseManager().getSqlClient() as Pool }, SystemClock), locks = locks, orders = orderDao,
        payments = context.getBean(MarketPaymentDao::class.java), events = context.getBean(MarketOrderEventDao::class.java), clock = SystemClock,
        orderService = orderService(plugin),
        cashback = { currentConfig(plugin).cashbackPercent > 0 },
        runAfter = { after -> payments.runAfterCommit(after, databaseManager().getSqlClient()) }
    )
}

/** The order of a panel path `:id`, or 404; a malformed id is a 400. */
internal suspend fun panelOrder(plugin: MarketPlugin, context: RoutingContext) =
    plugin.beans.getBean(MarketOrderDao::class.java)
        .getById(parseId(context.pathParam("id")), plugin.applicationContext.getBean(DatabaseManager::class.java).getSqlClient()) ?: throw NotFound()

/** The activity log row of an order decision, written by the acting panel user. */
internal suspend fun logOrderDecision(plugin: MarketPlugin, context: RoutingContext, build: (userId: Long, username: String) -> PluginActivityLog) {
    val databaseManager = plugin.applicationContext.getBean(DatabaseManager::class.java)
    val sqlClient = databaseManager.getSqlClient()
    val userId = plugin.applicationContext.getBean(AuthProvider::class.java).getUserIdFromRoutingContext(context)
    val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

    databaseManager.panelActivityLogDao.add(build(userId, username), sqlClient)
}

internal suspend fun actingUserId(plugin: MarketPlugin, context: RoutingContext): Long =
    plugin.applicationContext.getBean(AuthProvider::class.java).getUserIdFromRoutingContext(context)

/**
 * `POST /api/panel/market/orders/:id/review` (`P:PAY`, 04 section 7, 06 section 11 O4 / O5): `decision` `ACCEPT` or `REJECT`, `refund?` (reject: request
 * the refund of the money received), `force?` (accept: complete a released order without re-reserving codes and limits), `note?`. A flag that does not
 * belong to the decision is ignored. 409 `OUT_OF_STOCK` / `PURCHASE_LIMIT_REACHED` / `INVALID_COUPON` (and its siblings), 400 `INSUFFICIENT_CREDITS`
 * and 400 `INVALID_ORDER_TRANSITION` leave the order as it was.
 */
@Endpoint
class PanelReviewOrderAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/orders/:id/review", RouteType.POST))

    override val nodes: Set<MarketNode> = setOf(MarketNode.PAYMENTS)

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    objectSchema()
                        .requiredProperty("decision", enumSchema(*ReviewDecision.entries.map { it.name }.toTypedArray()))
                        .optionalProperty("refund", booleanSchema())
                        .optionalProperty("force", booleanSchema())
                        .optionalProperty("note", stringSchema())
                        .allowAdditionalProperties(true)
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val order = panelOrder(plugin, context)
        val body = context.body().asJsonObject() ?: JsonObject()
        val decision = ReviewDecision.valueOf(body.getString("decision") ?: throw RequestValueException("decision", "REQUIRED"))
        val force = decision == ReviewDecision.ACCEPT && body.getBoolean("force", false) == true
        val refund = decision == ReviewDecision.REJECT && body.getBoolean("refund", false) == true

        orderReviewService(plugin).review(order.id, decision, refund, force, body.getString("note"), actingUserId(plugin, context))

        logOrderDecision(plugin, context) { userId, username -> ReviewedMarketOrderLog(userId, username, plugin.pluginId, order.id, decision.name, force) }

        return Successful()
    }
}
