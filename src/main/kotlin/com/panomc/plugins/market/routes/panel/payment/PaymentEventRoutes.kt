package com.panomc.plugins.market.routes.panel.payment

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketPaymentDao
import com.panomc.plugins.market.db.model.PaymentEventStatus
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.log.ReplayedMarketPaymentEventLog
import com.panomc.plugins.market.permission.FieldGating
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.api.order.paymentService
import com.panomc.plugins.market.routes.api.payment.inboundDispatcher
import com.panomc.plugins.market.routes.api.payment.providerRedactorFor
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.parseId
import com.panomc.plugins.market.routes.base.parsePagingRequest
import com.panomc.plugins.market.routes.panel.order.logOrderDecision
import com.panomc.plugins.market.runtime.beans
import com.panomc.plugins.market.util.Paging
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

private object PaymentEventWiringHolder

@Volatile
private var cachedAdmin: Pair<MarketPlugin, PaymentEventAdmin>? = null

/** The payment-event admin service on the plugin's beans; one per plugin instance. */
internal fun paymentEventAdmin(plugin: MarketPlugin): PaymentEventAdmin {
    cachedAdmin?.takeIf { it.first === plugin }?.let { return it.second }

    // built outside the lock: the dispatcher reaches into the payment service wiring
    val built = run {
        val context = plugin.beans
        val orders = context.getBean(MarketOrderDao::class.java)

        PaymentEventAdmin(
            { orders.prefix() }, context.getBean(MarketPaymentDao::class.java), orders,
            { id -> inboundDispatcher(plugin).replay(id) },
            { order, attempt, client -> paymentService(plugin).reconcileQuery(order, attempt, client) },
            providerRedactorFor(plugin)
        )
    }

    return synchronized(PaymentEventWiringHolder) { cachedAdmin?.takeIf { it.first === plugin }?.second ?: built.also { cachedAdmin = plugin to it } }
}

private fun number(raw: String?, name: String): Long? = raw?.trim()?.takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: throw RequestValueException(name, "MUST_BE_A_NUMBER") }

/**
 * The query of `GET /payment-events` (04 section 7): `status?` (csv of `DEFERRED`, `FAILED`, `REJECTED`; default all three), `providerId?`, `page?`,
 * `pageSize?`. A value outside the contract is a 400, never ignored.
 */
class PaymentEventQuery(val statuses: Set<PaymentEventStatus>, val providerId: String?, val window: Paging.Window)

fun parsePaymentEventQuery(status: String?, providerId: String?, page: String?, pageSize: String?): PaymentEventQuery {
    val statuses = status?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map { name ->
        PaymentEventAdmin.ATTENTION.firstOrNull { it.name == name } ?: throw RequestValueException("status", "INVALID")
    }?.toSet().orEmpty()
    val provider = providerId?.trim()?.takeIf { it.isNotEmpty() }?.also { if (it.length > 64) throw RequestValueException("providerId", "TOO_LONG") }

    return PaymentEventQuery(statuses, provider, parsePagingRequest(number(page, "page"), number(pageSize, "pageSize")))
}

private fun pagedQuery(schemaRepository: SchemaRepository, vararg names: String): ValidationHandler {
    var builder = ValidationHandlerBuilder.create(schemaRepository)

    for (name in names) builder = builder.queryParameter(optionalParam(name, stringSchema()))

    return builder.build()
}

private fun pageBody(key: String, countKey: String, page: PaymentEventPage, window: Paging.Window): Map<String, Any> {
    if (Paging.isBeyondLast(window.page, page.totalPage)) throw PageNotFound()

    return mapOf(key to page.rows, countKey to page.count, "totalPage" to page.totalPage)
}

/**
 * `GET /api/panel/market/payments/:paymentId/events` (`P:OV`, 04 section 7): the traffic of one attempt, oldest first; `events[]`, `eventCount`, `totalPage`.
 * `body` / `headers` / `url` only with `SET` (11 section 14.5).
 */
@Endpoint
class PanelGetPaymentEventsAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/payments/:paymentId/events", RouteType.GET))

    override val nodes = setOf(MarketNode.ORDERS_VIEW)

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = pagedQuery(schemaRepository, "page", "pageSize")

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val id = parseId(context.pathParam("paymentId"), "paymentId")
        val window = parsePagingRequest(number(context.request().getParam("page"), "page"), number(context.request().getParam("pageSize"), "pageSize"))
        val page = paymentEventAdmin(plugin).forPayment(id, window, FieldGating.rawTier(context), databaseManager.getSqlClient())

        return Successful(pageBody("events", "eventCount", page, window))
    }
}

/** `GET /api/panel/market/payment-events` (`P:OV`, 04 section 7): inbound traffic that did not go through, newest first; `events[]`, `eventCount`, `totalPage`. */
@Endpoint
class PanelGetPaymentEventListAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/payment-events", RouteType.GET))

    override val nodes = setOf(MarketNode.ORDERS_VIEW)

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = pagedQuery(schemaRepository, "status", "providerId", "page", "pageSize")

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val request = context.request()
        val query = parsePaymentEventQuery(request.getParam("status"), request.getParam("providerId"), request.getParam("page"), request.getParam("pageSize"))
        val page = paymentEventAdmin(plugin).list(query.statuses, query.providerId, query.window, FieldGating.rawTier(context), databaseManager.getSqlClient())

        return Successful(pageBody("events", "eventCount", page, query.window))
    }
}

/**
 * `POST /api/panel/market/payment-events/:eventId/replay` (`P:PAY`, 04 section 7, 11 IN-4): `{status}`; 404, 409 `INVALID_STATE` for a row that is not
 * `DEFERRED`, `FAILED` or a `RECEIVED` row older than 60 s. Activity log `REPLAYED_MARKET_PAYMENT_EVENT`.
 */
@Endpoint
class PanelReplayPaymentEventAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/payment-events/:eventId/replay", RouteType.POST))

    override val nodes = setOf(MarketNode.PAYMENTS)

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val id = parseId(context.pathParam("eventId"), "eventId")
        val replayed = paymentEventAdmin(plugin).replay(id)

        logOrderDecision(plugin, context) { userId, username -> ReplayedMarketPaymentEventLog(userId, username, plugin.pluginId, id, replayed.providerId) }

        return Successful(replayed.toJson().map)
    }
}

/** `POST /api/panel/market/payments/:paymentId/query` (`P:OM`, 04 section 7): runs `queryPayment` now; `{status}`; 400 `STATUS_QUERY_NOT_SUPPORTED`. */
@Endpoint
class PanelQueryPaymentAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/payments/:paymentId/query", RouteType.POST))

    override val nodes = setOf(MarketNode.ORDERS_MANAGE)

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val id = parseId(context.pathParam("paymentId"), "paymentId")

        return Successful(paymentEventAdmin(plugin).query(id, databaseManager.getSqlClient()).map)
    }
}
