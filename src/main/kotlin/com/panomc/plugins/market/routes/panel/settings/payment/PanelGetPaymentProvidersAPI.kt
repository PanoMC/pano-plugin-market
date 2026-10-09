package com.panomc.plugins.market.routes.panel.settings.payment

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository

/**
 * `GET /api/panel/market/payment-providers` (04 section 8, `P:SET`): every payment provider the registry knows (built-in and
 * plugin, usable or not) with its state, schema, capabilities, masked settings and checkout rules. Replaces the compile-time
 * catalogue of the old panel.
 */
@Endpoint
class PanelGetPaymentProvidersAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val nodes: Set<MarketNode> = setOf(MarketNode.SETTINGS)

    override val paths = listOf(Path("/payment-providers", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val providers = paymentMethodService(plugin).list()

        context.response().putHeader("Cache-Control", "no-store")

        return Successful(mapOf("items" to JsonArray(providers)))
    }
}
