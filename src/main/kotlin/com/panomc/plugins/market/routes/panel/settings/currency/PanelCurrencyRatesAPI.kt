package com.panomc.plugins.market.routes.panel.settings.currency

import com.panomc.plugins.market.runtime.beans
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketCurrencyRateDao
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.log.RefreshedMarketCurrencyRatesLog
import com.panomc.plugins.market.log.UpdatedMarketCurrencyRatesLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.service.CurrencyRateService
import com.panomc.plugins.market.service.ExchangeRateService
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.sqlclient.Pool

/** The currency rate service of the settings routes on the plugin's beans (stateless: a route keeps one). */
internal fun currencyRateService(plugin: MarketPlugin): CurrencyRateService {
    val context = plugin.beans
    val databaseManager by lazy { context.getBean(DatabaseManager::class.java) }
    val exchangeRates by lazy { plugin.pluginBeanContext.getBean(ExchangeRateService::class.java) }

    return CurrencyRateService(
        db = MarketDb({ databaseManager.getSqlClient() as Pool }, SystemClock),
        clock = SystemClock,
        config = { currentConfig(plugin) },
        rates = context.getBean(MarketCurrencyRateDao::class.java),
        source = { base -> exchangeRates.fetchAll(base) }
    )
}

/**
 * `GET /api/panel/market/settings/currencies` and `PUT` (04 section 8, `P:SET`): the additional-currency rates and their
 * `AUTO` / `MANUAL` mode. One class answers both methods.
 */
@Endpoint
class PanelCurrencyRatesAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val nodes: Set<MarketNode> = setOf(MarketNode.SETTINGS)

    override val paths = listOf(
        Path("/api/panel/market/settings/currencies", RouteType.GET),
        Path("/api/panel/market/settings/currencies", RouteType.PUT)
    )

    private val service by lazy { currencyRateService(plugin) }

    private val authProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(Bodies.json(objectSchema().allowAdditionalProperties(true)))
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val sqlClient = databaseManager.getSqlClient()

        context.response().putHeader("Cache-Control", "no-store")

        if (context.request().method().name() == "GET") return Successful(currencyRatesJson(service.view(sqlClient)))

        val entries = parseCurrencyRatesBody(context.body().asJsonObject() ?: JsonObject())
        val view = service.update(entries, sqlClient)
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            UpdatedMarketCurrencyRatesLog(userId, username, plugin.pluginId, entries.mapNotNull { it.currency?.trim()?.uppercase() }.joinToString(", ")),
            sqlClient
        )

        return Successful(currencyRatesJson(view))
    }
}

/** `POST /api/panel/market/settings/currencies/refresh` (`P:SET`): refreshes the `AUTO` rates from the provider; 502 `EXCHANGE_RATE_FETCH_FAILED` when it is down. */
@Endpoint
class PanelRefreshCurrencyRatesAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val nodes: Set<MarketNode> = setOf(MarketNode.SETTINGS)

    override val paths = listOf(Path("/api/panel/market/settings/currencies/refresh", RouteType.POST))

    private val service by lazy { currencyRateService(plugin) }

    private val authProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val sqlClient = databaseManager.getSqlClient()
        val view = service.refresh(sqlClient)
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            RefreshedMarketCurrencyRatesLog(userId, username, plugin.pluginId, view.rates.joinToString(", ") { it.currency }),
            sqlClient
        )

        return Successful(currencyRatesJson(view))
    }
}
