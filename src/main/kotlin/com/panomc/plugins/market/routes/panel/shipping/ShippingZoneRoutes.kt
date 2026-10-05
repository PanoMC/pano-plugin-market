package com.panomc.plugins.market.routes.panel.shipping

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.log.CreatedMarketShippingZoneLog
import com.panomc.plugins.market.log.DeletedMarketShippingZoneLog
import com.panomc.plugins.market.log.SortedMarketShippingZonesLog
import com.panomc.plugins.market.log.UpdatedMarketShippingZoneLog
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository

/** `GET /api/panel/market/shipping/zones` (`P:SET`): every zone with `rateCount` and `shadowedBy`. */
@Endpoint
class PanelGetShippingZonesAPI(plugin: MarketPlugin) : ShippingAdminRoute(plugin) {
    override val paths = listOf(Path("/api/panel/market/shipping/zones", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = noBodyValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        context.response().putHeader("Cache-Control", "no-store")

        return Successful(mapOf("zones" to JsonArray(service.listZones())))
    }
}

/**
 * `POST /shipping/zones` (create, answers `{id}`), `PUT /shipping/zones/:id` (update) and `POST /shipping/zones/sort`
 * (`ids[]`, exactly the existing ids). One class so that the fixed `sort` path is registered before any parameter path.
 */
@Endpoint
class PanelSaveShippingZoneAPI(plugin: MarketPlugin) : ShippingAdminRoute(plugin) {
    override val paths = listOf(
        Path("/api/panel/market/shipping/zones/sort", RouteType.POST),
        Path("/api/panel/market/shipping/zones", RouteType.POST),
        Path("/api/panel/market/shipping/zones/:id", RouteType.PUT)
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = bodyValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val body = context.body().asJsonObject() ?: JsonObject()

        if (isPath(context, "/zones/sort")) {
            service.sortZones(idsOf(body))
            log(context) { u, n -> SortedMarketShippingZonesLog(u, n, plugin.pluginId) }

            return Successful()
        }

        if (context.request().method().name() == "PUT") {
            val id = longParam(context, "id")

            service.updateZone(id, body)
            log(context) { u, n -> UpdatedMarketShippingZoneLog(u, n, body.getString("name") ?: "#$id", plugin.pluginId) }

            return Successful()
        }

        val id = service.createZone(body)
        log(context) { u, n -> CreatedMarketShippingZoneLog(u, n, body.getString("name") ?: "#$id", plugin.pluginId) }

        return Successful(mapOf("id" to id))
    }
}

/** `DELETE /shipping/zones/:id`: removes the zone and its rate rows. */
@Endpoint
class PanelDeleteShippingZoneAPI(plugin: MarketPlugin) : ShippingAdminRoute(plugin) {
    override val paths = listOf(Path("/api/panel/market/shipping/zones/:id", RouteType.DELETE))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = noBodyValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val id = longParam(context, "id")

        service.deleteZone(id)
        log(context) { u, n -> DeletedMarketShippingZoneLog(u, n, "#$id", plugin.pluginId) }

        return Successful()
    }
}
