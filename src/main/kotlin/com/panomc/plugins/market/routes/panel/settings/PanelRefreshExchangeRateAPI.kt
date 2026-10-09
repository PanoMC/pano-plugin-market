package com.panomc.plugins.market.routes.panel.settings

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.error.ExchangeRateFetchFailed
import com.panomc.plugins.market.log.RefreshedExchangeRateLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.service.ExchangeRateService
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository

/**
 * Admin endpoint: fetches the current sales -> stats view rate from the provider and merge-saves it
 * onto the config (never a raw overwrite). Fails with [ExchangeRateFetchFailed] when no rate is
 * available so the stored value is left untouched.
 */
@Endpoint
class PanelRefreshExchangeRateAPI(
    private val plugin: MarketPlugin
) : MarketPanelApi() {
    override val paths = listOf(Path("/settings/exchange-rate/refresh", RouteType.POST))

    override val nodes = setOf(MarketNode.SETTINGS)

    private val authProvider by lazy {
        plugin.applicationContext.getBean(AuthProvider::class.java)
    }

    private val databaseManager by lazy {
        plugin.applicationContext.getBean(DatabaseManager::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    private val configManager by lazy {
        plugin.pluginBeanContext.getBean(PluginConfigManager::class.java) as PluginConfigManager<MarketConfig>
    }

    private val exchangeRateService by lazy {
        plugin.pluginBeanContext.getBean(ExchangeRateService::class.java)
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val config = configManager.config

        val rate = exchangeRateService.fetchRate(config.currency, config.statsCurrency)
            ?: throw ExchangeRateFetchFailed()

        val updatedAt = System.currentTimeMillis()

        val merged = JsonObject.mapFrom(config)
            .put("exchangeRate", rate)
            .put("exchangeRateUpdatedAt", updatedAt)
        configManager.saveConfig(merged)

        val sqlClient = getSqlClient()
        val adminUserId = authProvider.getUserIdFromRoutingContext(context)
        val adminUsername = databaseManager.userDao.getUsernameFromUserId(adminUserId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            RefreshedExchangeRateLog(adminUserId, adminUsername, plugin.pluginId, rate),
            sqlClient
        )

        return Successful(
            mapOf(
                "exchangeRate" to rate,
                "exchangeRateUpdatedAt" to updatedAt,
                "currency" to config.currency,
                "statsCurrency" to config.statsCurrency
            )
        )
    }
}
