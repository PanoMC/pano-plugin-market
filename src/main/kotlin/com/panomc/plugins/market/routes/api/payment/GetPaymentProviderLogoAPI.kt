package com.panomc.plugins.market.routes.api.payment

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.routes.base.MarketApi
import com.panomc.plugins.market.routes.panel.settings.payment.paymentMethodService
import io.vertx.core.buffer.Buffer
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * `GET /api/market/payment-providers/:id/logo` (04 section 3): the logo a provider ships in its descriptor (`image/png`,
 * `image/svg+xml` or `image/webp`, at most 64 KB), cached for a day; 404 when the provider is unknown or has none.
 * An SVG is served with a sandboxing `Content-Security-Policy` and `nosniff` so that it can never run script.
 */
@Endpoint
class GetPaymentProviderLogoAPI(private val plugin: MarketPlugin) : MarketApi() {
    override val paths = listOf(Path("/api/market/payment-providers/:id/logo", RouteType.GET))

    // The panel shows the logos too, also while the storefront is switched off.
    override val requiresStoreEnabled: Boolean = false

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(Parameters.param("id", stringSchema()))
            .build()

    override suspend fun handleMarket(context: RoutingContext): Result? {
        val id = getParameters(context).pathParameter("id").string
        val logo = paymentMethodService(plugin).logo(id)
        val response = context.response()

        if (logo == null) {
            response.setStatusCode(404).end()
            return null
        }

        response.putHeader("Content-Type", logo.contentType)
        response.putHeader("Content-Disposition", "inline")
        response.putHeader("X-Content-Type-Options", "nosniff")
        response.putHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox")
        response.putHeader("Cache-Control", "public, max-age=86400")
        response.end(Buffer.buffer(logo.bytes))

        return null
    }
}
