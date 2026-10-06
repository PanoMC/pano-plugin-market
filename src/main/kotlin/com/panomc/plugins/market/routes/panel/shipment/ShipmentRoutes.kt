package com.panomc.plugins.market.routes.panel.shipment

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PluginActivityLog
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.model.ShipmentStatus
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.parseId
import com.panomc.plugins.market.routes.base.parsePagingRequest
import com.panomc.plugins.market.routes.panel.shipping.shippingService
import com.panomc.plugins.market.service.ShippingService
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

// Fulfilment routes (MK-133; 04 section 7, 10 section 9). Every route is a thin shell: parse, call `ShippingService`, write the activity log, answer.

/** Base of the fulfilment routes: `OV` reads, `OM` mutations (04 section 9); the address of an order needs `OM` or `PAY` (11 section 14.5). */
abstract class ShipmentRoute(protected val plugin: MarketPlugin, override val nodes: Set<MarketNode>) : MarketPanelApi() {
    private val authProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    protected val service: ShippingService get() = shippingService(plugin)

    protected suspend fun sql() = databaseManager.getSqlClient()

    protected fun noBody(schemaRepository: SchemaRepository): ValidationHandler = ValidationHandlerBuilder.create(schemaRepository).build()

    protected fun jsonBody(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).body(Bodies.json(objectSchema().allowAdditionalProperties(true))).predicate(RequestPredicate.BODY_REQUIRED).build()

    /** The body is optional (an empty POST is fine). */
    protected fun optionalBody(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).body(Bodies.json(objectSchema().allowAdditionalProperties(true))).build()

    protected fun bodyOf(context: RoutingContext): JsonObject = context.body().asJsonObject() ?: JsonObject()

    protected fun idOf(context: RoutingContext): Long = parseId(context.pathParam("id"), "id")

    protected suspend fun actor(context: RoutingContext): Long = authProvider.getUserIdFromRoutingContext(context)

    protected suspend fun log(context: RoutingContext, build: (userId: Long, username: String) -> PluginActivityLog) {
        val client = sql()
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, client)!!

        databaseManager.panelActivityLogDao.add(build(userId, username), client)
    }

    protected suspend fun canSeeAddress(context: RoutingContext): Boolean = has(context, MarketNode.ORDERS_MANAGE, MarketNode.PAYMENTS)
}

/** `GET /api/panel/market/orders/:id/shipping` (`P:OM` or `P:PAY`, 11 section 14.3: the view holds the address): lines, suggested parcels, providers, the frozen quote. */
@Endpoint
class PanelGetOrderShippingAPI(plugin: MarketPlugin) : ShipmentRoute(plugin, setOf(MarketNode.ORDERS_MANAGE, MarketNode.PAYMENTS)) {
    override val paths = listOf(Path("/api/panel/market/orders/:id/shipping", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = noBody(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        context.response().putHeader("Cache-Control", "no-store")

        return Successful(service.orderShipping(idOf(context), canSeeAddress(context), sql()).map)
    }
}

/** `POST /orders/:id/shipping/rates` (`P:OM`): live carrier rates for the units that are still to ship. */
@Endpoint
class PanelOrderShippingRatesAPI(plugin: MarketPlugin) : ShipmentRoute(plugin, setOf(MarketNode.ORDERS_MANAGE)) {
    override val paths = listOf(Path("/api/panel/market/orders/:id/shipping/rates", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = jsonBody(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result = Successful(service.orderRates(idOf(context), bodyOf(context), sql()).map)
}

/** `POST /orders/:id/shipments` (`P:OM`): a manual entry or a carrier shipment; answers the `shipment`. */
@Endpoint
class PanelCreateShipmentAPI(plugin: MarketPlugin) : ShipmentRoute(plugin, setOf(MarketNode.ORDERS_MANAGE)) {
    override val paths = listOf(Path("/api/panel/market/orders/:id/shipments", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = jsonBody(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val orderId = idOf(context)
        val shipment = service.createShipment(orderId, bodyOf(context), actor(context), sql())

        log(context) { u, n -> CreatedMarketShipmentLog(u, n, plugin.pluginId, orderId, shipment.getLong("id")) }

        return Successful(mapOf("shipment" to shipment))
    }
}

/** `PUT /orders/:id/shipping-address` (`P:OM`): the admin corrects the frozen address while no live shipment exists. */
@Endpoint
class PanelUpdateOrderShippingAddressAPI(plugin: MarketPlugin) : ShipmentRoute(plugin, setOf(MarketNode.ORDERS_MANAGE)) {
    override val paths = listOf(Path("/api/panel/market/orders/:id/shipping-address", RouteType.PUT))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = jsonBody(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val orderId = idOf(context)

        service.editShippingAddress(orderId, bodyOf(context), actor(context), sql())
        log(context) { u, n -> UpdatedMarketOrderShippingAddressLog(u, n, plugin.pluginId, orderId) }

        return Successful()
    }
}

/** `GET /shipments` (`P:OV`): q `status` (csv), `providerId`, `stale`, `search`, `page`, `pageSize`. */
@Endpoint
class PanelGetShipmentsAPI(plugin: MarketPlugin) : ShipmentRoute(plugin, setOf(MarketNode.ORDERS_VIEW)) {
    override val paths = listOf(Path("/api/panel/market/shipments", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(optionalParam("page", stringSchema()))
            .queryParameter(optionalParam("pageSize", stringSchema()))
            .queryParameter(optionalParam("status", stringSchema()))
            .queryParameter(optionalParam("providerId", stringSchema()))
            .queryParameter(optionalParam("stale", stringSchema()))
            .queryParameter(optionalParam("search", stringSchema()))
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val window = parsePagingRequest(
            parameters.queryParameter("page")?.string?.let { parseId(it, "page") },
            parameters.queryParameter("pageSize")?.string?.let { parseId(it, "pageSize") }
        )
        val statuses = parameters.queryParameter("status")?.string?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map { name ->
            ShipmentStatus.entries.firstOrNull { it.name == name } ?: throw RequestValueException("status", "INVALID")
        } ?: emptyList()
        val stale = parameters.queryParameter("stale")?.string?.let {
            when (it) {
                "true", "1" -> true
                "false", "0" -> false
                else -> throw RequestValueException("stale", "INVALID")
            }
        }

        val page = service.listShipments(
            ShippingService.ShipmentFilter(statuses, parameters.queryParameter("providerId")?.string?.takeIf { it.isNotBlank() }, stale, parameters.queryParameter("search")?.string),
            window, sql()
        )
        val totalPage = Paging.totalPages(page.count, window.pageSize)

        if (Paging.isBeyondLast(window.page, totalPage)) throw PageNotFound()

        return Successful(mapOf("shipments" to JsonArray(page.shipments), "shipmentCount" to page.count, "totalPage" to totalPage))
    }
}

/** `GET /shipments/:id` (`P:OV`): the shipment with its items and events; the addresses only with `OM` or `PAY`. */
@Endpoint
class PanelGetShipmentAPI(plugin: MarketPlugin) : ShipmentRoute(plugin, setOf(MarketNode.ORDERS_VIEW)) {
    override val paths = listOf(Path("/api/panel/market/shipments/:id", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = noBody(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        context.response().putHeader("Cache-Control", "no-store")

        val shipment = service.shipmentJson(idOf(context), canSeeAddress(context), sql(), detail = true)
        val items = shipment.remove("items")
        val events = shipment.remove("events")

        return Successful(mapOf("shipment" to shipment, "items" to items, "events" to events))
    }
}

/** `PUT /shipments/:id` (`P:OM`): tracking fields, a manual status, note, `releaseItems`. */
@Endpoint
class PanelUpdateShipmentAPI(plugin: MarketPlugin) : ShipmentRoute(plugin, setOf(MarketNode.ORDERS_MANAGE)) {
    override val paths = listOf(Path("/api/panel/market/shipments/:id", RouteType.PUT))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = jsonBody(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val id = idOf(context)
        val (shipment, changed) = service.editShipment(id, bodyOf(context), actor(context), sql())

        log(context) { u, n -> UpdatedMarketShipmentLog(u, n, plugin.pluginId, shipment.getLong("orderId"), id, changed) }

        return Successful()
    }
}

/** `POST /shipments/:id/retry` (`P:OM`): a failed carrier creation is sent again with the same merchant reference. */
@Endpoint
class PanelRetryShipmentAPI(plugin: MarketPlugin) : ShipmentRoute(plugin, setOf(MarketNode.ORDERS_MANAGE)) {
    override val paths = listOf(Path("/api/panel/market/shipments/:id/retry", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = optionalBody(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val id = idOf(context)
        val shipment = service.retryShipment(id, actor(context), sql())

        log(context) { u, n -> UpdatedMarketShipmentLog(u, n, plugin.pluginId, shipment.getLong("orderId"), id, listOf("retry")) }

        return Successful(mapOf("shipment" to shipment))
    }
}

/** `POST /shipments/:id/cancel` (`P:OM`, body `force?`). */
@Endpoint
class PanelCancelShipmentAPI(plugin: MarketPlugin) : ShipmentRoute(plugin, setOf(MarketNode.ORDERS_MANAGE)) {
    override val paths = listOf(Path("/api/panel/market/shipments/:id/cancel", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = optionalBody(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val id = idOf(context)
        val force = when (val raw = bodyOf(context).getValue("force")) {
            null -> false
            is Boolean -> raw
            else -> throw RequestValueException("force", "INVALID")
        }
        val shipment = service.cancelShipment(id, force, actor(context), sql())

        log(context) { u, n -> CancelledMarketShipmentLog(u, n, plugin.pluginId, shipment.getLong("orderId"), id, force) }

        return Successful(mapOf("shipment" to shipment))
    }
}

/** `POST /shipments/:id/track` (`P:OM`): asks the carrier now. */
@Endpoint
class PanelTrackShipmentAPI(plugin: MarketPlugin) : ShipmentRoute(plugin, setOf(MarketNode.ORDERS_MANAGE)) {
    override val paths = listOf(Path("/api/panel/market/shipments/:id/track", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = optionalBody(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result = Successful(mapOf("shipment" to service.trackShipment(idOf(context), sql())))
}
