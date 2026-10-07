package com.panomc.plugins.market.service

import com.panomc.plugins.market.core.money.Currencies
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.invoice.InvoiceNumbering
import com.panomc.plugins.market.core.time.Clock
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields

/**
 * The calendar buckets of the stats page (00 section 9, 01 section 14.3): boundary lists computed in Kotlin in the store zone, so a day is a local day
 * (23 or 25 hours across a DST change) and a week starts on Monday, and grouped in SQL with `INTERVAL(paidAt, b0, b1, ..., bn)` (fixes bug 25: no
 * `FROM_UNIXTIME` and no database session zone). Pure: `java.time` only.
 *
 * Bucket `i` is `[bounds[i], bounds[i + 1])`; [Buckets.bounds] has `size + 1` entries, ascending, the last one is the exclusive end (the start of the day,
 * week or month after the newest bucket).
 */
object StatsBuckets {
    private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val MONTH_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

    class Buckets(val bounds: List<Long>, val labels: List<String>) {
        val size: Int get() = labels.size

        val start: Long get() = bounds.first()

        val end: Long get() = bounds.last()

        /** The bucket of [timestamp] (the number `INTERVAL` reports minus one), or `-1` when it lies outside `[start, end)`. */
        fun indexOf(timestamp: Long): Int {
            if (timestamp < start || timestamp >= end) return -1

            var low = 0
            var high = size - 1

            while (low < high) {
                val mid = (low + high + 1) ushr 1

                if (bounds[mid] <= timestamp) low = mid else high = mid - 1
            }

            return low
        }
    }

    fun zone(storeTimeZone: String): ZoneId = InvoiceNumbering.zone(storeTimeZone)

    fun startOfDay(zone: ZoneId, day: LocalDate): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()

    fun today(zone: ZoneId, nowMs: Long): LocalDate = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()

    /** The Monday of the ISO week of [day]. */
    fun weekStart(day: LocalDate): LocalDate = day.with(DayOfWeek.MONDAY)

    /** The label of a week: `YYYYWW` of the ISO week-based year (the key of the weekly chart). */
    fun weekKey(day: LocalDate): String = String.format("%04d%02d", day.get(IsoFields.WEEK_BASED_YEAR), day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

    /** [count] local days, oldest first, the last one is [today]; labels `yyyy-MM-dd`. */
    fun days(zone: ZoneId, today: LocalDate, count: Int): Buckets {
        require(count >= 1) { "count must be positive" }

        val first = today.minusDays((count - 1).toLong())

        return build(zone, count, { first.plusDays(it.toLong()) }, { it.format(DAY_FORMAT) })
    }

    /** [count] ISO weeks (Monday to Monday), oldest first, the last one holds [today]; labels `YYYYWW`. */
    fun weeks(zone: ZoneId, today: LocalDate, count: Int): Buckets {
        require(count >= 1) { "count must be positive" }

        val first = weekStart(today).minusWeeks((count - 1).toLong())

        return build(zone, count, { first.plusWeeks(it.toLong()) }, ::weekKey)
    }

    /** [count] calendar months, oldest first, the last one holds [today]; labels `yyyy-MM`. */
    fun months(zone: ZoneId, today: LocalDate, count: Int): Buckets {
        require(count >= 1) { "count must be positive" }

        val first = today.withDayOfMonth(1).minusMonths((count - 1).toLong())

        return build(zone, count, { first.plusMonths(it.toLong()) }, { it.format(MONTH_FORMAT) })
    }

    private fun build(zone: ZoneId, count: Int, dayOf: (Int) -> LocalDate, label: (LocalDate) -> String): Buckets =
        Buckets((0..count).map { startOfDay(zone, dayOf(it)) }, (0 until count).map { label(dayOf(it)) })
}

/**
 * The stats page (`GET /stats`, 04 section 7, 01 section 14.3), one aggregation per question, all on `market_order`:
 * - what counts: `status IN ('COMPLETED', 'PARTIALLY_REFUNDED')`, `testMode = 0`, `paidAt` set (legacy rows satisfy it through the migration fixups, I17);
 * - revenue is the money the gateway kept: `gatewayAmount - refundedGatewayAmount` (credit-paid value is not revenue), dated by `paidAt` in the store zone,
 *   converted per order with the frozen `exchangeRate` (else the currency based fallback of the old stats) into `statsCurrency`;
 * - windows: `weekly` = the last 7 local days, `monthly` = the last 30, each against the 7 / 30 days before it; `total`, the top products, the payment
 *   methods, the currencies and the refunds follow `from` / `to` (epoch ms, `paidAt >= from AND paidAt < to`; refunds by `completedAt`), default all time;
 * - top products group by `productId` (name of the newest order line), lines of kind `PRODUCT` / `BUNDLE`, value = the line's gateway share minus its refunds.
 *
 * Every query returns decimal amounts of the stats currency; the DAO methods of the old stats are no longer used.
 */
class StatsService(
    private val config: () -> MarketConfig,
    private val clock: Clock,
    private val prefix: () -> String
) {
    private fun table(name: String) = "`${prefix()}$name`"

    private companion object {
        const val PAID = "o.`status` IN ('COMPLETED', 'PARTIALLY_REFUNDED') AND o.`testMode` = 0 AND o.`paidAt` IS NOT NULL"

        /** `exchangeRate` of the order, else 1 for the stats currency, the configured view rate for the sales currency, otherwise 1 (binds: stats, sales, rate). */
        const val FACTOR = "COALESCE(o.`exchangeRate`, CASE WHEN o.`currency` = ? THEN 1.0 WHEN o.`currency` = ? THEN ? ELSE 1.0 END)"

        const val REVENUE = "(o.`gatewayAmount` - o.`refundedGatewayAmount`)"
    }

    class Conversion(val stats: String, val sales: String, val rate: Double) {
        fun args(): List<Any?> = listOf(stats, sales, rate)
    }

    /** The count and revenue of each bucket of [buckets], zero-filled. */
    class Series(val counts: List<Long>, val revenue: List<Double>)

    suspend fun stats(from: Long?, to: Long?, client: SqlClient): JsonObject {
        val cfg = config()
        val zone = StatsBuckets.zone(cfg.storeTimeZone)
        val now = clock.now()
        val today = StatsBuckets.today(zone, now)
        val conversion = Conversion(cfg.statsCurrency, cfg.currency, cfg.exchangeRate)

        // one daily series serves the weekly and the monthly window, the previous ones and both sparklines
        val dayBuckets = StatsBuckets.days(zone, today, 60)
        val daily = series(dayBuckets, conversion, client)
        val week = window(daily, 53, 60)
        val weekPrev = window(daily, 46, 53)
        val month = window(daily, 30, 60)
        val monthPrev = window(daily, 0, 30)
        val monthSpark = daily.revenue.subList(30, 60).map(::round2)

        val (totalCount, totalRevenue) = total(from, to, conversion, client)

        val summary = linkedMapOf(
            "weekly" to block(week.first, week.second, weekPrev.second, daily.revenue.subList(53, 60).map(::round2)),
            "monthly" to block(month.first, month.second, monthPrev.second, monthSpark),
            // the whole window has no preceding window: previous and trend are inert
            "total" to mapOf("count" to totalCount, "revenue" to round2(totalRevenue), "previous" to 0.0, "trend" to 0.0, "spark" to monthSpark),
            "refunds" to refunds(from, to, conversion, client),
            "activeSubscriptions" to activeSubscriptions(client)
        )

        val weeks = StatsBuckets.weeks(zone, today, 8)
        val weekSeries = series(weeks, conversion, client)
        val months = StatsBuckets.months(zone, today, 6)
        val monthSeries = series(months, conversion, client)
        val top = topProducts(from, to, 5, conversion, client)
        val methods = paymentMethods(from, to, client)
        val currencies = currencies(from, to, client)

        return JsonObject()
            .put("summary", JsonObject(summary))
            .put(
                "charts",
                JsonObject()
                    .put("weeklyRevenue", chart(weeks.labels, weekSeries.revenue.map(::round2)))
                    .put("monthlyRevenue", chart(months.labels, monthSeries.revenue.map(::round2)))
                    .put("topProducts", chart(top.map { it.first }, top.map { round2(it.second) }))
                    .put("paymentMethods", chart(methods.map { it.first }, methods.map { it.second }))
                    .put("currencies", chart(currencies.map { it.first }, currencies.map { round2(it.second) }))
            )
            .put("statsCurrency", cfg.statsCurrency)
            .put("statsCurrencySymbol", Currencies.symbol(cfg.statsCurrency))
    }

    // ============================================================================================ revenue per bucket

    /** Orders and revenue per bucket: one statement, `INTERVAL(paidAt, b0 .. bn)` names the bucket. */
    suspend fun series(buckets: StatsBuckets.Buckets, conversion: Conversion, client: SqlClient): Series {
        val marks = buckets.bounds.joinToString(", ") { "?" }
        val args = ArrayList<Any?>()

        args.addAll(buckets.bounds)
        args.addAll(conversion.args())
        args.add(buckets.start)
        args.add(buckets.end)

        val rows = client.preparedQuery(
            "SELECT INTERVAL(o.`paidAt`, $marks) AS bucket, COUNT(*) AS cnt, COALESCE(SUM($REVENUE * $FACTOR), 0) AS revenue FROM ${table("market_order")} o " +
                "WHERE $PAID AND o.`paidAt` >= ? AND o.`paidAt` < ? GROUP BY bucket"
        ).execute(Tuple.from(args)).coAwait()

        val counts = LongArray(buckets.size)
        val revenue = DoubleArray(buckets.size)

        for (row in rows) {
            // INTERVAL counts from 1 for the first bucket: 0 would be "before b0" (excluded by the range) and size + 1 "at or after bn" (likewise)
            val index = row.getInteger("bucket") - 1

            if (index !in 0 until buckets.size) continue

            counts[index] = row.getLong("cnt")
            revenue[index] = row.getDouble("revenue") / 100.0
        }

        return Series(counts.toList(), revenue.toList())
    }

    /** The series of the stats currency for a config, public for the tests that build the buckets themselves. */
    suspend fun seriesFor(buckets: StatsBuckets.Buckets, client: SqlClient): Series {
        val cfg = config()

        return series(buckets, Conversion(cfg.statsCurrency, cfg.currency, cfg.exchangeRate), client)
    }

    private fun window(series: Series, fromIndex: Int, toIndex: Int): Pair<Long, Double> =
        series.counts.subList(fromIndex, toIndex).sum() to series.revenue.subList(fromIndex, toIndex).sum()

    private fun block(count: Long, revenue: Double, previous: Double, spark: List<Double>) = mapOf(
        "count" to count, "revenue" to round2(revenue), "previous" to round2(previous), "trend" to trend(revenue, previous), "spark" to spark
    )

    // Percentage change of the current window against the previous one; 100 % when growing from nothing, 0 % when both are empty.
    private fun trend(current: Double, previous: Double): Double {
        if (previous == 0.0) return if (current > 0.0) 100.0 else 0.0

        return Math.round((current - previous) / previous * 10000) / 100.0
    }

    // ============================================================================================ the other aggregations

    private fun range(column: String, from: Long?, to: Long?, args: MutableList<Any?>): String {
        val parts = ArrayList<String>()

        if (from != null) {
            parts += "$column >= ?"
            args += from
        }

        if (to != null) {
            parts += "$column < ?"
            args += to
        }

        return if (parts.isEmpty()) "" else " AND " + parts.joinToString(" AND ")
    }

    private suspend fun total(from: Long?, to: Long?, conversion: Conversion, client: SqlClient): Pair<Long, Double> {
        val args = ArrayList<Any?>(conversion.args())
        val condition = range("o.`paidAt`", from, to, args)
        val row = client.preparedQuery(
            "SELECT COUNT(*) AS cnt, COALESCE(SUM($REVENUE * $FACTOR), 0) AS revenue FROM ${table("market_order")} o WHERE $PAID$condition"
        ).execute(Tuple.from(args)).coAwait().first()

        return row.getLong("cnt") to row.getDouble("revenue") / 100.0
    }

    /** `summary.refunds`: the refunds the gateway returned (`SUCCEEDED`, by `completedAt`, test orders excluded), converted like revenue. */
    private suspend fun refunds(from: Long?, to: Long?, conversion: Conversion, client: SqlClient): Map<String, Any> {
        val args = ArrayList<Any?>(conversion.args())
        val condition = range("r.`completedAt`", from, to, args)
        val row = client.preparedQuery(
            "SELECT COUNT(*) AS cnt, COALESCE(SUM(r.`gatewayAmount` * $FACTOR), 0) AS amount FROM ${table("market_refund")} r " +
                "JOIN ${table("market_order")} o ON o.`id` = r.`orderId` WHERE r.`status` = 'SUCCEEDED' AND r.`completedAt` IS NOT NULL AND o.`testMode` = 0$condition"
        ).execute(Tuple.from(args)).coAwait().first()

        return mapOf("count" to row.getLong("cnt"), "amount" to round2(row.getDouble("amount") / 100.0))
    }

    private suspend fun activeSubscriptions(client: SqlClient): Long =
        client.preparedQuery("SELECT COUNT(*) AS cnt FROM ${table("market_subscription")} WHERE `status` IN ('ACTIVE', 'PAST_DUE') AND `testMode` = 0")
            .execute().coAwait().first().getLong("cnt")

    /** Top [limit] products by revenue; the key is the product id (a line without one is its own group by name), the name that of the newest line. */
    suspend fun topProducts(from: Long?, to: Long?, limit: Int, conversion: Conversion, client: SqlClient): List<Pair<String, Double>> {
        val args = ArrayList<Any?>(conversion.args())
        val condition = range("o.`paidAt`", from, to, args)

        args += limit

        val rows = client.preparedQuery(
            "SELECT n.`productName` AS name, t.`revenue` AS revenue FROM (" +
                "SELECT MAX(i.`id`) AS lastId, " +
                "COALESCE(SUM((i.`lineTotal` - i.`refundedAmount`) * o.`gatewayAmount` / NULLIF(o.`totalPrice`, 0) * $FACTOR), 0) AS revenue " +
                "FROM ${table("market_order_item")} i JOIN ${table("market_order")} o ON o.`id` = i.`orderId` " +
                "WHERE $PAID AND i.`kind` IN ('PRODUCT', 'BUNDLE')$condition " +
                "GROUP BY COALESCE(i.`productId`, 0), IF(i.`productId` IS NULL, i.`productName`, '') ORDER BY revenue DESC, lastId DESC LIMIT ?" +
                ") t JOIN ${table("market_order_item")} n ON n.`id` = t.`lastId` ORDER BY t.`revenue` DESC, t.`lastId` DESC"
        ).execute(Tuple.from(args)).coAwait()

        return rows.map { it.getString("name") to it.getDouble("revenue") / 100.0 }
    }

    suspend fun topProductsFor(from: Long?, to: Long?, limit: Int, client: SqlClient): List<Pair<String, Double>> {
        val cfg = config()

        return topProducts(from, to, limit, Conversion(cfg.statsCurrency, cfg.currency, cfg.exchangeRate), client)
    }

    private suspend fun paymentMethods(from: Long?, to: Long?, client: SqlClient): List<Pair<String, Long>> {
        val args = ArrayList<Any?>()
        val condition = range("o.`paidAt`", from, to, args)
        val rows = client.preparedQuery(
            "SELECT COALESCE(NULLIF(o.`paymentLabel`, ''), o.`paymentMethodId`) AS label, COUNT(*) AS cnt FROM ${table("market_order")} o WHERE $PAID$condition " +
                "GROUP BY label ORDER BY cnt DESC, label ASC"
        ).execute(Tuple.from(args)).coAwait()

        return rows.map { it.getString("label") to it.getLong("cnt") }
    }

    /** Collected revenue per order currency, unconverted (decimal amounts of that currency), largest first. */
    private suspend fun currencies(from: Long?, to: Long?, client: SqlClient): List<Pair<String, Double>> {
        val args = ArrayList<Any?>()
        val condition = range("o.`paidAt`", from, to, args)
        val rows = client.preparedQuery(
            "SELECT o.`currency` AS currency, COALESCE(SUM($REVENUE), 0) AS revenue FROM ${table("market_order")} o WHERE $PAID$condition GROUP BY o.`currency` " +
                "ORDER BY revenue DESC, currency ASC"
        ).execute(Tuple.from(args)).coAwait()

        return rows.map { it.getString("currency") to it.getDouble("revenue") / 100.0 }
    }

    private fun chart(labels: List<String>, values: List<Any>) = JsonObject().put("labels", labels).put("values", values)

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0
}
