package com.panomc.plugins.market.routes.panel.stats

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields

@Endpoint
class PanelGetMarketStatsAPI(
    private val plugin: MarketPlugin,
    private val marketOrderDao: MarketOrderDao,
    private val marketOrderItemDao: MarketOrderItemDao
) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/stats", RouteType.GET))

    override val nodes = setOf(MarketNode.STATS)

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    @Suppress("UNCHECKED_CAST")
    private val configManager by lazy {
        plugin.pluginBeanContext.getBean(PluginConfigManager::class.java) as PluginConfigManager<MarketConfig>
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val MONTH_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val config = configManager.config
        val statsCurrency = config.statsCurrency
        val salesCurrency = config.currency
        // Conversion parameters threaded to every revenue aggregation: DB applies per-order rates.
        val statsName = statsCurrency.name
        val salesName = salesCurrency.name
        val viewRate = config.exchangeRate

        val sqlClient = databaseManager.getSqlClient()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)

        // Rolling-window summary (COMPLETED only): current window vs the immediately preceding one.
        val (weekCount, weekRevenue) = marketOrderDao.countAndRevenueBetween(now - 7 * DAY_MS, now, statsName, salesName, viewRate, sqlClient)
        val weekPrev = marketOrderDao.countAndRevenueBetween(now - 14 * DAY_MS, now - 7 * DAY_MS, statsName, salesName, viewRate, sqlClient).second

        val (monthCount, monthRevenue) = marketOrderDao.countAndRevenueBetween(now - 30 * DAY_MS, now, statsName, salesName, viewRate, sqlClient)
        val monthPrev = marketOrderDao.countAndRevenueBetween(now - 60 * DAY_MS, now - 30 * DAY_MS, statsName, salesName, viewRate, sqlClient).second

        val (totalCount, totalRevenue) = marketOrderDao.countAndRevenueBetween(0, now, statsName, salesName, viewRate, sqlClient)

        val weekSparkFrom = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val weekSpark = dailySpark(today, 7, marketOrderDao.revenueByDay(weekSparkFrom, now, statsName, salesName, viewRate, sqlClient))

        val monthSparkFrom = today.minusDays(29).atStartOfDay(zone).toInstant().toEpochMilli()
        val monthSpark = dailySpark(today, 30, marketOrderDao.revenueByDay(monthSparkFrom, now, statsName, salesName, viewRate, sqlClient))

        val summary = mapOf(
            "weekly" to summaryBlock(weekCount, weekRevenue, weekPrev, trend(weekRevenue, weekPrev), weekSpark),
            "monthly" to summaryBlock(monthCount, monthRevenue, monthPrev, trend(monthRevenue, monthPrev), monthSpark),
            // All-time window has no preceding window: previous/trend are inert.
            "total" to summaryBlock(totalCount, totalRevenue, 0.0, 0.0, monthSpark)
        )

        // Weekly revenue chart: last 8 ISO weeks, zero-filled.
        val weekDates = (7 downTo 0).map { today.minusWeeks(it.toLong()) }
        val weekChartMap = marketOrderDao.revenueByWeek(today.minusWeeks(8).atStartOfDay(zone).toInstant().toEpochMilli(), now, statsName, salesName, viewRate, sqlClient)

        // Monthly revenue chart: last 6 months, zero-filled.
        val monthDates = (5 downTo 0).map { today.minusMonths(it.toLong()) }
        val monthChartMap = marketOrderDao.revenueByMonth(today.minusMonths(6).withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli(), now, statsName, salesName, viewRate, sqlClient)

        val topProducts = marketOrderItemDao.topProductsBetween(0, now, 5, statsName, salesName, viewRate, sqlClient)
        val paymentDistribution = marketOrderDao.paymentMethodDistribution(sqlClient)

        val charts = mapOf(
            "weeklyRevenue" to mapOf(
                "labels" to weekDates.map { isoWeekKey(it) },
                "values" to weekDates.map { round2(weekChartMap[isoWeekKey(it)] ?: 0.0) }
            ),
            "monthlyRevenue" to mapOf(
                "labels" to monthDates.map { it.format(MONTH_FORMAT) },
                "values" to monthDates.map { round2(monthChartMap[it.format(MONTH_FORMAT)] ?: 0.0) }
            ),
            "topProducts" to mapOf(
                "labels" to topProducts.map { it.first },
                "values" to topProducts.map { round2(it.second) }
            ),
            "paymentMethods" to mapOf(
                "labels" to paymentDistribution.keys.toList(),
                "values" to paymentDistribution.values.toList()
            )
        )

        return Successful(
            mapOf(
                "summary" to summary,
                "charts" to charts,
                "statsCurrency" to statsName,
                "statsCurrencySymbol" to statsCurrency.symbol
            )
        )
    }

    private fun summaryBlock(count: Long, revenue: Double, previous: Double, trend: Double, spark: List<Double>): Map<String, Any?> = mapOf(
        "count" to count,
        "revenue" to round2(revenue),
        "previous" to round2(previous),
        "trend" to trend,
        "spark" to spark
    )

    private fun dailySpark(today: LocalDate, days: Int, revenueByDay: Map<String, Double>): List<Double> =
        (days - 1 downTo 0).map { offset ->
            round2(revenueByDay[today.minusDays(offset.toLong()).format(DAY_FORMAT)] ?: 0.0)
        }

    private fun isoWeekKey(date: LocalDate): String =
        String.format("%04d%02d", date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

    // Percentage change of current vs previous window; 100% when growing from nothing, 0% when both empty.
    private fun trend(current: Double, previous: Double): Double {
        if (previous == 0.0) return if (current > 0.0) 100.0 else 0.0
        return Math.round((current - previous) / previous * 10000) / 100.0
    }

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0
}
