package com.panomc.plugins.market.routes.panel.stats

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.runtime.beans
import com.panomc.plugins.market.service.StatsService
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/** `from` / `to` of `GET /stats` (04 section 7): epoch milliseconds, `0 <= from < to`; absent = unbounded. A value outside the contract is a 400. */
class StatsRange(val from: Long?, val to: Long?)

fun parseStatsRange(from: String?, to: String?): StatsRange {
    fun number(raw: String?, name: String): Long? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parsed = value.toLongOrNull() ?: throw RequestValueException(name, "MUST_BE_AN_INTEGER")

        if (parsed < 0) throw RequestValueException(name, "MUST_NOT_BE_NEGATIVE")

        return parsed
    }

    val start = number(from, "from")
    val end = number(to, "to")

    if (start != null && end != null && end <= start) throw RequestValueException("to", "MUST_BE_AFTER_FROM")

    return StatsRange(start, end)
}

/**
 * `GET /api/panel/market/stats` (`P:STATS`, 04 section 7, 01 section 14.3): revenue = `gatewayAmount - refundedGatewayAmount` of paid, non-test orders
 * by `paidAt` in the store zone; additive over v1: `summary.refunds`, `summary.activeSubscriptions`, `charts.currencies`; q `from` / `to` bound the total,
 * the top products, the payment methods, the currencies and the refunds. See [StatsService].
 */
@Endpoint
class PanelGetMarketStatsAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/stats", RouteType.GET))

    override val nodes = setOf(MarketNode.STATS)

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    private val service by lazy { StatsService({ currentConfig(plugin) }, SystemClock) { plugin.beans.getBean(MarketOrderDao::class.java).prefix() } }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(optionalParam("from", stringSchema()))
            .queryParameter(optionalParam("to", stringSchema()))
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val range = parseStatsRange(context.request().getParam("from"), context.request().getParam("to"))

        return Successful(service.stats(range.from, range.to, databaseManager.getSqlClient()).map)
    }
}
