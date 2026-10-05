package com.panomc.plugins.market.routes.panel.shipping

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.log.CreatedMarketShippingMethodLog
import com.panomc.plugins.market.log.DeletedMarketShippingMethodLog
import com.panomc.plugins.market.log.SortedMarketShippingMethodsLog
import com.panomc.plugins.market.log.UpdatedMarketShippingMethodLog
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository

/** `GET /api/panel/market/shipping/methods` (`P:SET`): the live methods with their `rates[]`. */
@Endpoint
class PanelGetShippingMethodsAPI(plugin: MarketPlugin) : ShippingAdminRoute(plugin) {
    override val paths = listOf(Path("/api/panel/market/shipping/methods", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = noBodyValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        context.response().putHeader("Cache-Control", "no-store")

        return Successful(mapOf("methods" to JsonArray(service.listMethods())))
    }
}

/**
 * `POST /shipping/methods` (create, answers `{id}`), `PUT /shipping/methods/:id` (update; a present `rates` replaces the
 * whole rate set) and `POST /shipping/methods/sort` (`ids[]`, exactly the live ids).
 */
@Endpoint
class PanelSaveShippingMethodAPI(plugin: MarketPlugin) : ShippingAdminRoute(plugin) {
    override val paths = listOf(
        Path("/api/panel/market/shipping/methods/sort", RouteType.POST),
        Path("/api/panel/market/shipping/methods", RouteType.POST),
        Path("/api/panel/market/shipping/methods/:id", RouteType.PUT)
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = bodyValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val body = context.body().asJsonObject() ?: JsonObject()

        if (isPath(context, "/methods/sort")) {
            service.sortMethods(idsOf(body))
            log(context) { u, n -> SortedMarketShippingMethodsLog(u, n, plugin.pluginId) }

            return Successful()
        }

        if (context.request().method().name() == "PUT") {
            val id = longParam(context, "id")

            service.updateMethod(id, body)
            log(context) { u, n -> UpdatedMarketShippingMethodLog(u, n, body.getString("name") ?: "#$id", plugin.pluginId) }

            return Successful()
        }

        val id = service.createMethod(body)
        log(context) { u, n -> CreatedMarketShippingMethodLog(u, n, body.getString("name") ?: "#$id", plugin.pluginId) }

        return Successful(mapOf("id" to id))
    }
}

/** `DELETE /shipping/methods/:id`: soft delete, orders keep the method name. */
@Endpoint
class PanelDeleteShippingMethodAPI(plugin: MarketPlugin) : ShippingAdminRoute(plugin) {
    override val paths = listOf(Path("/api/panel/market/shipping/methods/:id", RouteType.DELETE))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = noBodyValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val id = longParam(context, "id")

        service.deleteMethod(id)
        log(context) { u, n -> DeletedMarketShippingMethodLog(u, n, "#$id", plugin.pluginId) }

        return Successful()
    }
}
