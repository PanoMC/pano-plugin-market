package com.panomc.plugins.market.routes.panel.shipping

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.log.FailedMarketSecretRevealLog
import com.panomc.plugins.market.log.RanMarketShippingActionLog
import com.panomc.plugins.market.log.RevealedMarketShippingSecretLog
import com.panomc.plugins.market.log.ToggledMarketShippingCarrierLog
import com.panomc.plugins.market.log.UpdatedMarketShippingCarrierLog
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository

/** `GET /api/panel/market/shipping/carriers` (`P:SET`): every shipping provider, usable or not, with masked settings. */
@Endpoint
class PanelGetShippingCarriersAPI(plugin: MarketPlugin) : ShippingAdminRoute(plugin) {
    override val paths = listOf(Path("/shipping/carriers", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = noBodyValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        context.response().putHeader("Cache-Control", "no-store")

        return Successful(mapOf("items" to JsonArray(service.listCarriers())))
    }
}

/** `POST /shipping/carriers/:id` (`settings{}`, `config{testMode}`): saves a carrier; the row is created on the first save. */
@Endpoint
class PanelSaveShippingCarrierAPI(plugin: MarketPlugin) : ShippingAdminRoute(plugin) {
    override val paths = listOf(Path("/shipping/carriers/:id", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = bodyValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val body = context.body().asJsonObject() ?: JsonObject()
        val id = context.pathParam("id")
        val saved = service.saveCarrier(id, optionalObject(body, "settings"), optionalObject(body, "config"))

        log(context) { u, n -> UpdatedMarketShippingCarrierLog(u, n, id, plugin.pluginId) }

        return Successful(saved.message?.let { mapOf("message" to it.toJson()) } ?: emptyMap<String, Any>())
    }
}

/** `POST /shipping/carriers/:id/toggle` (`enabled`): `manual` cannot be disabled. */
@Endpoint
class PanelToggleShippingCarrierAPI(plugin: MarketPlugin) : ShippingAdminRoute(plugin) {
    override val paths = listOf(Path("/shipping/carriers/:id/toggle", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = bodyValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val id = context.pathParam("id")
        val enabled = context.body().asJsonObject()?.getValue("enabled") as? Boolean ?: throw RequestValueException("enabled", "REQUIRED")

        service.toggleCarrier(id, enabled)
        log(context) { u, n -> ToggledMarketShippingCarrierLog(u, n, id, enabled, plugin.pluginId) }

        return Successful()
    }
}

/** `POST /shipping/carriers/:id/reveal` (`password`): throttled, only the secret fields, `Cache-Control: no-store`. */
@Endpoint
class PanelRevealShippingSecretAPI(plugin: MarketPlugin) : ShippingAdminRoute(plugin) {
    override val paths = listOf(Path("/shipping/carriers/:id/reveal", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = bodyValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val id = context.pathParam("id")
        val password = context.body().asJsonObject()?.getValue("password") as? String ?: throw RequestValueException("password", "REQUIRED")

        val secrets = service.revealCarrier(
            id, userId(context),
            passwordCorrect = { isLoginCorrect(context, password) },
            onFailed = { log(context) { u, n -> FailedMarketSecretRevealLog(u, n, id, plugin.pluginId) } }
        )

        log(context) { u, n -> RevealedMarketShippingSecretLog(u, n, id, plugin.pluginId) }
        context.response().putHeader("Cache-Control", "no-store")

        return Successful(mapOf("settings" to secrets))
    }
}

/** `POST /shipping/carriers/:id/actions/:actionId`: runs a settings action; a carrier failure is 502 `SHIPPING_PROVIDER_ERROR`. */
@Endpoint
class PanelRunShippingActionAPI(plugin: MarketPlugin) : ShippingAdminRoute(plugin) {
    override val paths = listOf(Path("/shipping/carriers/:id/actions/:actionId", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = bodyValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val id = context.pathParam("id")
        val actionId = context.pathParam("actionId")
        val body = context.body().asJsonObject() ?: JsonObject()
        val input = optionalObject(body, "input") ?: JsonObject()

        val outcome = service.runCarrierAction(id, actionId, input)

        log(context) { u, n -> RanMarketShippingActionLog(u, n, id, actionId, plugin.pluginId) }

        return Successful(mapOf("success" to outcome.success, "message" to outcome.message?.toJson()))
    }
}

/** `GET /shipping/carriers/:id/services`: `listServices` of the provider, 10 s timeout. */
@Endpoint
class PanelGetShippingServicesAPI(plugin: MarketPlugin) : ShippingAdminRoute(plugin) {
    override val paths = listOf(Path("/shipping/carriers/:id/services", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = noBodyValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        context.response().putHeader("Cache-Control", "no-store")

        return Successful(mapOf("services" to JsonArray(service.carrierServices(context.pathParam("id")))))
    }
}
