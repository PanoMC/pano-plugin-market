package com.panomc.plugins.market.routes.panel.order

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.error.ExchangeRateFetchFailed
import com.panomc.plugins.market.log.UpdatedMarketOrderExchangeRateLog
import com.panomc.plugins.market.permission.ManageMarketPermission
import com.panomc.plugins.market.service.ExchangeRateService
import com.panomc.plugins.market.util.CurrencyType
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

/**
 * Admin endpoint: re-fetches an order's frozen sales -> stats rate, preferring the historical rate on
 * the order's creation date and falling back to the current rate. Fails with [ExchangeRateFetchFailed]
 * when neither is available so the stored value is left untouched.
 */
@Endpoint
class PanelRefreshOrderExchangeRateAPI(
    private val plugin: MarketPlugin,
    private val marketOrderDao: MarketOrderDao
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/market/orders/:id/exchange-rate/refresh", RouteType.POST))

    private val authProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }
    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    @Suppress("UNCHECKED_CAST")
    private val configManager by lazy {
        plugin.pluginBeanContext.getBean(PluginConfigManager::class.java) as PluginConfigManager<MarketConfig>
    }

    private val exchangeRateService by lazy {
        plugin.pluginBeanContext.getBean(ExchangeRateService::class.java)
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMarketPermission(), context)

        val id = context.pathParam("id").toLong()

        val sqlClient = databaseManager.getSqlClient()
        val order = marketOrderDao.getById(id, sqlClient) ?: throw NotFound()

        val config = configManager.config
        val from = CurrencyType.valueOf(order.currency)

        val rate = exchangeRateService.fetchRateForDate(from, config.statsCurrency, order.createdAt)
            ?: exchangeRateService.fetchRate(from, config.statsCurrency)
            ?: throw ExchangeRateFetchFailed()

        marketOrderDao.updateExchangeRate(id, rate, sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            UpdatedMarketOrderExchangeRateLog(userId, username, plugin.pluginId, id, rate),
            sqlClient
        )

        return Successful(mapOf("exchangeRate" to rate))
    }
}
