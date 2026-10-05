package com.panomc.plugins.market.routes.panel.settings

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.runtime.MarketRuntime
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.panel.invoice.invoiceWiring
import com.panomc.plugins.market.routes.panel.settings.payment.paymentMethodService
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository

/**
 * Admin endpoint (`SET`): returns every config key (00 section 12) plus every registered payment provider's
 * saved state. Secret fields are masked with the `"********"` sentinel so the UI's
 * "is configured" checks still pass while the real values never leave the server.
 */
@Endpoint
class PanelGetSettingsAPI(
    private val plugin: MarketPlugin
) : MarketPanelApi() {
    override val nodes: Set<MarketNode> = setOf(MarketNode.SETTINGS)

    override val paths = listOf(Path("/api/panel/market/settings", RouteType.GET))

    @Suppress("UNCHECKED_CAST")
    private val configManager by lazy {
        plugin.pluginBeanContext.getBean(PluginConfigManager::class.java) as PluginConfigManager<MarketConfig>
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        // Built from the provider registry (MK-046): every provider with its enabled flag and masked settings.
        val paymentMethods = paymentMethodService(plugin).settingsSummary()

        val config = configManager.config

        val response = JsonObject.mapFrom(config)
            .put("paymentMethods", paymentMethods)
            .put("currencySymbol", config.currency.symbol)
            .put("statsCurrencySymbol", config.statsCurrency.symbol)
            .put("mailEnabled", MarketRuntime.capabilities.mail)
            .put("invoiceSequences", invoiceWiring(plugin).endpoints.sequences(plugin.applicationContext.getBean(DatabaseManager::class.java).getSqlClient()))

        return Successful(response.map)
    }
}
