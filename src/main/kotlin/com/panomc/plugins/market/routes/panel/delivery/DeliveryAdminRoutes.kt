package com.panomc.plugins.market.routes.panel.delivery

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketCreditAccountDao
import com.panomc.plugins.market.db.dao.MarketDeliveryDao
import com.panomc.plugins.market.db.dao.MarketEntitlementDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderEventDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.dao.MarketRedemptionDao
import com.panomc.plugins.market.db.model.DeliveryActionType
import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.db.model.DeliveryStatus
import com.panomc.plugins.market.db.tx.Locks
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.log.CancelledMarketDeliveryLog
import com.panomc.plugins.market.log.ReranMarketDeliveryLog
import com.panomc.plugins.market.log.RetriedMarketDeliveryLog
import com.panomc.plugins.market.log.RevokedMarketOrderLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.api.order.deliveryService
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.parseEnum
import com.panomc.plugins.market.routes.base.parseId
import com.panomc.plugins.market.routes.base.parseIdList
import com.panomc.plugins.market.routes.base.parseOptionalEnum
import com.panomc.plugins.market.routes.base.parsePagingRequest
import com.panomc.plugins.market.routes.panel.order.actingUserId
import com.panomc.plugins.market.routes.panel.order.logOrderDecision
import com.panomc.plugins.market.routes.panel.order.panelOrder
import com.panomc.plugins.market.runtime.beans
import com.panomc.plugins.market.service.platform.PlatformServerRoster
import com.panomc.plugins.market.util.MoneyUtil
import com.panomc.plugins.market.util.Paging
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.sqlclient.Pool

private object DeliveryAdminWiringHolder

@Volatile
private var cachedAdmin: Pair<MarketPlugin, DeliveryAdminService>? = null

/** The delivery operations on the plugin's beans (MK-104); one per plugin instance. */
internal fun deliveryAdminService(plugin: MarketPlugin): DeliveryAdminService {
    cachedAdmin?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(DeliveryAdminWiringHolder) {
        cachedAdmin?.takeIf { it.first === plugin }?.second ?: buildDeliveryAdminService(plugin).also { cachedAdmin = plugin to it }
    }
}

private fun buildDeliveryAdminService(plugin: MarketPlugin): DeliveryAdminService {
    val context = plugin.beans
    val databaseManager = { context.getBean(DatabaseManager::class.java) }
    val orderDao = context.getBean(MarketOrderDao::class.java)
    val locks = Locks(orderDao, context.getBean(MarketOrderItemDao::class.java), context.getBean(MarketRedemptionDao::class.java), context.getBean(MarketCreditAccountDao::class.java))

    return DeliveryAdminService(
        db = MarketDb({ databaseManager().getSqlClient() as Pool }, SystemClock), locks = locks, clock = SystemClock, deliveryService = deliveryService(plugin),
        orders = orderDao, orderEvents = context.getBean(MarketOrderEventDao::class.java), deliveries = context.getBean(MarketDeliveryDao::class.java),
        entitlements = context.getBean(MarketEntitlementDao::class.java),
        roster = PlatformServerRoster(databaseManager) { context.getBean(com.panomc.platform.server.ServerManager::class.java) }
    )
}

private fun jsonBody(context: RoutingContext): JsonObject = context.body().asJsonObject() ?: JsonObject()

private fun bodyValidation(schemaRepository: SchemaRepository): ValidationHandler =
    ValidationHandlerBuilder.create(schemaRepository)
        .body(Bodies.json(objectSchema().allowAdditionalProperties(true)))
        .predicate(RequestPredicate.BODY_REQUIRED)
        .build()

private fun noBodyValidation(schemaRepository: SchemaRepository): ValidationHandler = ValidationHandlerBuilder.create(schemaRepository).build()

/**
 * `GET /api/panel/market/deliveries` (`P:OV`, 08 section 14.5, 04 section 7): query `status`, `phase`, `serverId`, `actionType`, `search`, `page`,
 * `pageSize`; `deliveries[]`, `deliveryCount`, `totalPage`. The payload of a webhook row never carries its secret.
 */
@Endpoint
class PanelGetDeliveriesAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/deliveries", RouteType.GET))

    override val nodes: Set<MarketNode> = setOf(MarketNode.ORDERS_VIEW)

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler {
        var builder = ValidationHandlerBuilder.create(schemaRepository)

        for (name in listOf("status", "phase", "serverId", "actionType", "search", "page", "pageSize")) builder = builder.queryParameter(optionalParam(name, stringSchema()))

        return builder.build()
    }

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val request = context.request()
        val window = parsePagingRequest(request.getParam("page")?.let { number(it, "page") }, request.getParam("pageSize")?.let { number(it, "pageSize") })
        val filter = DeliveryAdminService.Filter(
            status = parseOptionalEnum(DeliveryStatus.entries.toTypedArray(), request.getParam("status"), "status"),
            phase = parseOptionalEnum(DeliveryPhase.entries.toTypedArray(), request.getParam("phase"), "phase"),
            serverId = request.getParam("serverId")?.let { number(it, "serverId") },
            actionType = parseOptionalEnum(DeliveryActionType.entries.toTypedArray(), request.getParam("actionType"), "actionType"),
            search = request.getParam("search")?.take(255)
        )

        val page = deliveryAdminService(plugin).list(filter, window)
        val totalPages = Paging.totalPages(page.total, window.pageSize)

        if (Paging.isBeyondLast(window.page, totalPages)) throw PageNotFound()

        return Successful(mapOf("deliveries" to JsonArray(page.rows), "deliveryCount" to page.total, "totalPage" to totalPages))
    }

    private fun number(raw: String, name: String): Long = raw.trim().toLongOrNull() ?: throw RequestValueException(name, "MUST_BE_A_NUMBER")
}

/**
 * `POST /api/panel/market/orders/:id/deliveries/rerun` (`P:OM`, plus `PAY` for a row that already took effect, 08 section 14.1, 11 section 14.3): body
 * exactly one of `deliveryIds[]`, `orderItemIds[]`, `all: true`, and `phase` (default `GRANT`). `{created, skipped}`. A selection that contains a logical
 * delivery that took effect and a caller without `PAY` is a 403 `NO_PERMISSION` for the whole request.
 */
@Endpoint
class PanelRerunDeliveriesAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/orders/:id/deliveries/rerun", RouteType.POST))

    override val nodes: Set<MarketNode> = setOf(MarketNode.ORDERS_MANAGE)

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = bodyValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val order = panelOrder(plugin, context)
        val body = jsonBody(context)
        val selector = selector(body)
        val phase = parseEnum(DeliveryPhase.entries.toTypedArray(), body.getValue("phase") as? String, "phase", DeliveryPhase.GRANT)
        val mayRepeat = has(context, MarketNode.PAYMENTS)

        val result = deliveryAdminService(plugin).rerun(order.id, selector, phase, mayRepeat, actingUserId(plugin, context))

        logOrderDecision(plugin, context) { userId, username ->
            ReranMarketDeliveryLog(userId, username, plugin.pluginId, order.id, result.created, result.duplicateGrant, MoneyUtil.toDecimal(result.creditAmount))
        }

        return Successful(mapOf("created" to result.created, "skipped" to result.skipped))
    }

    private fun selector(body: JsonObject): DeliveryAdminService.Selector {
        val given = listOf("deliveryIds", "orderItemIds", "all").filter { body.getValue(it) != null && !(it == "all" && body.getValue(it) == false) }

        // 400 BAD_REQUEST when nothing or more than one selector is given
        if (given.size != 1) throw BadRequest()

        return when (given.single()) {
            "deliveryIds" -> DeliveryAdminService.Selector.Deliveries(parseIdList(body.getValue("deliveryIds") as? JsonArray ?: throw BadRequest(), "deliveryIds"))
            "orderItemIds" -> DeliveryAdminService.Selector.Items(parseIdList(body.getValue("orderItemIds") as? JsonArray ?: throw BadRequest(), "orderItemIds"))
            else -> if (body.getValue("all") == true) DeliveryAdminService.Selector.All else throw BadRequest()
        }
    }
}

/** `POST /api/panel/market/deliveries/:id/retry` (`P:OM`, 08 section 14.2): the same row and key; `{}`; 409 `DELIVERY_NOT_RETRYABLE`. `force` is accepted and ignored. */
@Endpoint
class PanelRetryDeliveryAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/deliveries/:id/retry", RouteType.POST))

    override val nodes: Set<MarketNode> = setOf(MarketNode.ORDERS_MANAGE)

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = noBodyValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val id = parseId(context.pathParam("id"))
        val row = deliveryAdminService(plugin).retry(id)

        logOrderDecision(plugin, context) { userId, username -> RetriedMarketDeliveryLog(userId, username, plugin.pluginId, row.orderId, row.id) }

        return Successful()
    }
}

/** `POST /api/panel/market/deliveries/:id/cancel` (`P:OM`, 08 section 14.3): `{}`; 409 `DELIVERY_NOT_CANCELLABLE`. */
@Endpoint
class PanelCancelDeliveryAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/deliveries/:id/cancel", RouteType.POST))

    override val nodes: Set<MarketNode> = setOf(MarketNode.ORDERS_MANAGE)

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = noBodyValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val id = parseId(context.pathParam("id"))
        val row = deliveryAdminService(plugin).cancel(id)

        logOrderDecision(plugin, context) { userId, username -> CancelledMarketDeliveryLog(userId, username, plugin.pluginId, row.orderId, row.id) }

        return Successful()
    }
}

/** `POST /api/panel/market/orders/:id/revoke` (`P:OM`, 08 section 14.4): body `orderItemIds?[]` (default: every line); `{created}` = `REVOKE` rows planned; no money moves. */
@Endpoint
class PanelRevokeOrderAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/orders/:id/revoke", RouteType.POST))

    override val nodes: Set<MarketNode> = setOf(MarketNode.ORDERS_MANAGE)

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = bodyValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val order = panelOrder(plugin, context)
        val raw = jsonBody(context).getValue("orderItemIds")
        val ids = when (raw) {
            null -> null
            is JsonArray -> parseIdList(raw, "orderItemIds")
            else -> throw RequestValueException("orderItemIds", "MUST_BE_AN_ARRAY")
        }

        val created = deliveryAdminService(plugin).revoke(order.id, ids, actingUserId(plugin, context))

        logOrderDecision(plugin, context) { userId, username -> RevokedMarketOrderLog(userId, username, plugin.pluginId, order.id, created) }

        return Successful(mapOf("created" to created))
    }
}
