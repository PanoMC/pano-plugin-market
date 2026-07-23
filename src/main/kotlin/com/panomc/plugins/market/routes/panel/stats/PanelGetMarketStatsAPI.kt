package com.panomc.plugins.market.routes.panel.stats

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.permission.ManageMarketPermission
import com.panomc.plugins.market.util.MoneyUtil
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
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/market/stats", RouteType.GET))

    private val authProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }
    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val MONTH_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMarketPermission(), context)

        val sqlClient = databaseManager.getSqlClient()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)

        // Rolling-window summary (COMPLETED only): current window vs the immediately preceding one.
        val (weekCount, weekRevenue) = marketOrderDao.countAndRevenueBetween(now - 7 * DAY_MS, now, sqlClient)
        val weekPrev = marketOrderDao.countAndRevenueBetween(now - 14 * DAY_MS, now - 7 * DAY_MS, sqlClient).second

        val (monthCount, monthRevenue) = marketOrderDao.countAndRevenueBetween(now - 30 * DAY_MS, now, sqlClient)
        val monthPrev = marketOrderDao.countAndRevenueBetween(now - 60 * DAY_MS, now - 30 * DAY_MS, sqlClient).second

        val (totalCount, totalRevenue) = marketOrderDao.countAndRevenueBetween(0, now, sqlClient)

        val weekSparkFrom = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val weekSpark = dailySpark(today, 7, marketOrderDao.revenueByDay(weekSparkFrom, now, sqlClient))

        val monthSparkFrom = today.minusDays(29).atStartOfDay(zone).toInstant().toEpochMilli()
        val monthSpark = dailySpark(today, 30, marketOrderDao.revenueByDay(monthSparkFrom, now, sqlClient))

        val summary = mapOf(
            "weekly" to summaryBlock(weekCount, weekRevenue, weekPrev, weekSpark),
            "monthly" to summaryBlock(monthCount, monthRevenue, monthPrev, monthSpark),
            "total" to summaryBlock(totalCount, totalRevenue, 0, monthSpark)
        )

        // Weekly revenue chart: last 8 ISO weeks, zero-filled.
        val weekDates = (7 downTo 0).map { today.minusWeeks(it.toLong()) }
        val weekChartMap = marketOrderDao.revenueByWeek(today.minusWeeks(8).atStartOfDay(zone).toInstant().toEpochMilli(), now, sqlClient)

        // Monthly revenue chart: last 6 months, zero-filled.
        val monthDates = (5 downTo 0).map { today.minusMonths(it.toLong()) }
        val monthChartMap = marketOrderDao.revenueByMonth(today.minusMonths(6).withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli(), now, sqlClient)

        val topProducts = marketOrderItemDao.topProductsBetween(0, now, 5, sqlClient)
        val paymentDistribution = marketOrderDao.paymentMethodDistribution(sqlClient)

        val charts = mapOf(
            "weeklyRevenue" to mapOf(
                "labels" to weekDates.map { isoWeekKey(it) },
                "values" to weekDates.map { MoneyUtil.toDecimal(weekChartMap[isoWeekKey(it)] ?: 0L) }
            ),
            "monthlyRevenue" to mapOf(
                "labels" to monthDates.map { it.format(MONTH_FORMAT) },
                "values" to monthDates.map { MoneyUtil.toDecimal(monthChartMap[it.format(MONTH_FORMAT)] ?: 0L) }
            ),
            "topProducts" to mapOf(
                "labels" to topProducts.map { it.first },
                "values" to topProducts.map { MoneyUtil.toDecimal(it.second) }
            ),
            "paymentMethods" to mapOf(
                "labels" to paymentDistribution.keys.toList(),
                "values" to paymentDistribution.values.toList()
            )
        )

        return Successful(mapOf("summary" to summary, "charts" to charts))
    }

    private fun summaryBlock(count: Long, revenue: Long, previous: Long, spark: List<Double>): Map<String, Any?> = mapOf(
        "count" to count,
        "revenue" to MoneyUtil.toDecimal(revenue),
        "previous" to MoneyUtil.toDecimal(previous),
        "trend" to trend(revenue, previous),
        "spark" to spark
    )

    private fun dailySpark(today: LocalDate, days: Int, revenueByDay: Map<String, Long>): List<Double> =
        (days - 1 downTo 0).map { offset ->
            MoneyUtil.toDecimal(revenueByDay[today.minusDays(offset.toLong()).format(DAY_FORMAT)] ?: 0L)
        }

    private fun isoWeekKey(date: LocalDate): String =
        String.format("%04d%02d", date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

    // Percentage change of current vs previous window; 100% when growing from nothing, 0% when both empty.
    private fun trend(current: Long, previous: Long): Double {
        if (previous == 0L) return if (current > 0L) 100.0 else 0.0
        return Math.round((current - previous).toDouble() / previous * 10000) / 100.0
    }
}
