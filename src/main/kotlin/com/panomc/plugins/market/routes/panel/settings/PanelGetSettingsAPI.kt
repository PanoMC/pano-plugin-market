package com.panomc.plugins.market.routes.panel.settings

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.db.dao.MarketPaymentMethodDao
import com.panomc.plugins.market.runtime.MarketRuntime
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.util.PaymentMethodCatalog
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository

/**
 * Admin endpoint (`SET`): returns every config key (00 section 12) plus every catalog payment method's
 * saved state. Secret fields are masked with a fixed `"********"` sentinel (D6) so the UI's
 * "is configured" checks still pass while the real values never leave the server.
 */
@Endpoint
class PanelGetSettingsAPI(
    private val plugin: MarketPlugin,
    private val marketPaymentMethodDao: MarketPaymentMethodDao
) : MarketPanelApi() {
    override val nodes: Set<MarketNode> = setOf(MarketNode.SETTINGS)

    override val paths = listOf(Path("/api/panel/market/settings", RouteType.GET))

    @Suppress("UNCHECKED_CAST")
    private val configManager by lazy {
        plugin.pluginBeanContext.getBean(PluginConfigManager::class.java) as PluginConfigManager<MarketConfig>
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).build()

    companion object {
        private const val SECRET_MASK = "********"
    }

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val sqlClient = getSqlClient()
        val storedByMethodId = marketPaymentMethodDao.getAll(sqlClient).associateBy { it.methodId }

        val paymentMethods = JsonObject()

        PaymentMethodCatalog.methods.forEach { method ->
            val stored = storedByMethodId[method.id]
            val settings = if (stored != null) JsonObject(stored.settings) else JsonObject()

            // Replace every non-blank secret value with the mask sentinel; leave unset ones absent.
            method.secretFieldKeys.forEach { key ->
                if (!settings.getString(key).isNullOrEmpty()) {
                    settings.put(key, SECRET_MASK)
                }
            }

            paymentMethods.put(
                method.id,
                JsonObject()
                    .put("enabled", stored?.enabled ?: false)
                    .put("settings", settings)
            )
        }

        val config = configManager.config

        val response = JsonObject.mapFrom(config)
            .put("paymentMethods", paymentMethods)
            .put("currencySymbol", config.currency.symbol)
            .put("statsCurrencySymbol", config.statsCurrency.symbol)
            .put("mailEnabled", MarketRuntime.capabilities.mail)

        return Successful(response.map)
    }
}
