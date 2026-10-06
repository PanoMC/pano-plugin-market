package com.panomc.plugins.market.routes.panel.order

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.error.ExchangeRateFetchFailed
import com.panomc.plugins.market.log.UpdatedMarketOrderExchangeRateLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.parseId
import com.panomc.plugins.market.service.ExchangeRateService
import com.panomc.plugins.market.util.CurrencyType
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Admin endpoint: re-fetches an order's frozen sales -> stats rate, preferring the historical rate on
 * the order's creation date and falling back to the current rate. Fails with [ExchangeRateFetchFailed]
 * when neither is available so the stored value is left untouched.
 */
@Endpoint
class PanelRefreshOrderExchangeRateAPI(
    private val plugin: MarketPlugin,
    private val marketOrderDao: MarketOrderDao
) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/orders/:id/exchange-rate/refresh", RouteType.POST))

    override val nodes = setOf(MarketNode.PAYMENTS)

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
            .pathParameter(param("id", stringSchema()))
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val id = parseId(context.pathParam("id"))

        val sqlClient = databaseManager.getSqlClient()
        val order = marketOrderDao.getById(id, sqlClient) ?: throw NotFound()

        val config = configManager.config
        // order.currency is a free-form VARCHAR, not enum-constrained at write time; a legacy or
        // removed code would make valueOf() throw an uncaught 500. Look it up safely instead.
        val from = CurrencyType.entries.firstOrNull { it.name == order.currency } ?: throw BadRequest()

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
