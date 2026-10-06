package com.panomc.plugins.market.service

import com.panomc.plugins.market.db.model.GoalMetric
import com.panomc.plugins.market.db.model.GoalPeriod
import com.panomc.plugins.market.db.model.MarketGoal
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.MarketRefund
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.routes.api.store.parseWidgetInclude
import com.panomc.plugins.market.routes.panel.stats.parseStatsRange
import com.panomc.plugins.market.util.OrderStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The calendar buckets of the stats page (00 section 9, 01 section 14.3; MK-171) and the pure rules around the widgets: boundaries in the store zone
 * (a DST day is 23 or 25 hours, never `FROM_UNIXTIME`), weeks start on Monday and are labelled by the ISO week-based year, months clamp; `indexOf` agrees
 * with what `INTERVAL(paidAt, b0 ... bn) - 1` answers in SQL; goal arithmetic; request parsing of `include`, `from` and `to`.
 */
class StatsBucketsTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    private val utc = ZoneId.of("UTC")

    private fun at(zone: ZoneId, y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) = ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

    // ---- days ----

    @Test
    fun `days are local days, oldest first, ending with today, bounds ascending with size plus one entries`() {
        val b = StatsBuckets.days(utc, LocalDate.of(2026, 10, 5), 7)

        assertEquals(7, b.size)
        assertEquals(8, b.bounds.size)
        assertEquals(listOf("2026-09-29", "2026-09-30", "2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05"), b.labels)
        assertEquals(at(utc, 2026, 9, 29), b.start)
        assertEquals(at(utc, 2026, 10, 6), b.end)
        assertEquals(b.bounds, b.bounds.sorted())
        assertEquals(b.bounds.size, b.bounds.toSet().size)
    }

    @Test
    fun `a day across the spring DST change is 23 hours and across the autumn change 25 hours in the store zone`() {
        val spring = StatsBuckets.days(berlin, LocalDate.of(2026, 3, 30), 3) // 28, 29 (23 h), 30
        val autumn = StatsBuckets.days(berlin, LocalDate.of(2026, 10, 26), 3) // 24, 25 (25 h), 26

        assertEquals(listOf("2026-03-28", "2026-03-29", "2026-03-30"), spring.labels)
        assertEquals(Duration.ofHours(24).toMillis(), spring.bounds[1] - spring.bounds[0])
        assertEquals(Duration.ofHours(23).toMillis(), spring.bounds[2] - spring.bounds[1])
        assertEquals(Duration.ofHours(25).toMillis(), autumn.bounds[2] - autumn.bounds[1])
        assertEquals(at(berlin, 2026, 3, 29), spring.bounds[1])
        assertEquals(at(berlin, 2026, 10, 25), autumn.bounds[1])
    }

    @Test
    fun `an order a minute after local midnight belongs to the new day even when UTC is still on the old one`() {
        // Istanbul (UTC+3): 00:30 local on 2 October is 21:30 UTC on 1 October
        val istanbul = ZoneId.of("Europe/Istanbul")
        val b = StatsBuckets.days(istanbul, LocalDate.of(2026, 10, 2), 2)
        val paid = at(istanbul, 2026, 10, 2, 0, 30)

        assertEquals(1, b.indexOf(paid))
        assertEquals("2026-10-02", b.labels[b.indexOf(paid)])
        assertEquals(0, b.indexOf(at(istanbul, 2026, 10, 1, 23, 59)))
        assertEquals(1, StatsBuckets.days(utc, LocalDate.of(2026, 10, 1), 2).indexOf(paid), "the same instant is still 1 October in UTC, i.e. the last bucket there")
    }

    // ---- weeks ----

    @Test
    fun `weeks start on Monday and carry the ISO week key, also across the year change`() {
        // 2026-12-31 is a Thursday of ISO week 53 of 2026; 2027-01-04 is the Monday of week 1 of 2027
        val b = StatsBuckets.weeks(utc, LocalDate.of(2027, 1, 5), 3)

        assertEquals(listOf("202652", "202653", "202701"), b.labels)
        assertEquals(at(utc, 2026, 12, 21), b.bounds[0])
        assertEquals(at(utc, 2026, 12, 28), b.bounds[1])
        assertEquals(at(utc, 2027, 1, 4), b.bounds[2])
        assertEquals(at(utc, 2027, 1, 11), b.end)
        assertEquals(1, b.indexOf(at(utc, 2027, 1, 3, 23, 59)), "Sunday night is still week 53")
        assertEquals(2, b.indexOf(at(utc, 2027, 1, 4)))
    }

    @Test
    fun `the current week is the last bucket and holds today wherever in the week today is`() {
        for (day in 5..11) { // Monday 5 October 2026 .. Sunday 11 October
            val b = StatsBuckets.weeks(utc, LocalDate.of(2026, 10, day), 8)

            assertEquals("202641", b.labels.last())
            assertEquals(at(utc, 2026, 10, 5), b.bounds[b.size - 1])
            assertEquals(at(utc, 2026, 10, 12), b.end)
        }
    }

    // ---- months ----

    @Test
    fun `months are calendar months, the first of each, labelled yyyy-MM and the count crosses a year`() {
        val b = StatsBuckets.months(berlin, LocalDate.of(2026, 2, 28), 4)

        assertEquals(listOf("2025-11", "2025-12", "2026-01", "2026-02"), b.labels)
        assertEquals(at(berlin, 2025, 11, 1), b.start)
        assertEquals(at(berlin, 2026, 3, 1), b.end)
        // 31 January 23:59 local is January, 1 February 00:00 is February
        assertEquals(2, b.indexOf(at(berlin, 2026, 1, 31, 23, 59)))
        assertEquals(3, b.indexOf(at(berlin, 2026, 2, 1)))
    }

    // ---- indexOf = INTERVAL - 1 ----

    @Test
    fun `indexOf is what INTERVAL answers minus one and minus one outside the range`() {
        val b = StatsBuckets.days(utc, LocalDate.of(2026, 10, 5), 5)

        // reference implementation of MariaDB INTERVAL(n, b0, b1, ..., bn): 0 if n < b0, k if b(k-1) <= n < b(k)
        fun interval(n: Long): Int = if (n < b.bounds[0]) 0 else b.bounds.indexOfLast { it <= n } + 1

        val probes = b.bounds.flatMap { listOf(it - 1, it, it + 1) } + listOf(0L, Long.MAX_VALUE)

        for (n in probes) {
            val expected = interval(n) - 1

            assertEquals(if (expected in 0 until b.size) expected else -1, b.indexOf(n), "timestamp $n")
        }

        assertEquals(-1, b.indexOf(b.end), "the end is exclusive")
        assertEquals(b.size - 1, b.indexOf(b.end - 1))
        assertEquals(0, b.indexOf(b.start))
    }

    @Test
    fun `an unknown or blank store zone falls back to the JVM zone and a bad count is refused`() {
        assertEquals(ZoneId.systemDefault(), StatsBuckets.zone(""))
        assertEquals(ZoneId.systemDefault(), StatsBuckets.zone("Not/AZone"))
        assertEquals(berlin, StatsBuckets.zone(" Europe/Berlin "))
        assertThrows(IllegalArgumentException::class.java) { StatsBuckets.days(utc, LocalDate.of(2026, 1, 1), 0) }
    }

    // ---- request parsing ----

    @Test
    fun `from and to of the stats query are epoch milliseconds with from before to`() {
        assertNull(parseStatsRange(null, null).from)
        assertNull(parseStatsRange(" ", "").to)
        assertEquals(10L, parseStatsRange("10", "20").from)
        assertEquals(20L, parseStatsRange("10", "20").to)

        for ((from, to) in listOf("abc" to null, null to "1.5", "-1" to null, "20" to "20", "30" to "20")) {
            assertThrows(RequestValueException::class.java, { parseStatsRange(from, to) }, "$from / $to")
        }
    }

    @Test
    fun `include of the widgets is a csv of the four sections, blank means all and an unknown name is refused`() {
        assertEquals(WidgetService.SECTIONS, parseWidgetInclude(null))
        assertEquals(WidgetService.SECTIONS, parseWidgetInclude("  "))
        assertEquals(setOf("goals", "stats"), parseWidgetInclude("goals, stats,goals"))

        assertThrows(RequestValueException::class.java) { parseWidgetInclude("goals,everything") }
        assertThrows(RequestValueException::class.java) { parseWidgetInclude("Goals") }
    }

    // ---- goal arithmetic ----

    private fun goal(metric: GoalMetric, productIds: String? = null, period: GoalPeriod = GoalPeriod.ONE_TIME, periodStart: Long? = null, startsAt: Long? = null, endsAt: Long? = null, status: String = "ACTIVE") =
        MarketGoal(id = 1, metric = metric, productIds = productIds, target = 1000, period = period, periodStart = periodStart, startsAt = startsAt, endsAt = endsAt, status = status)

    private fun item(id: Long, productId: Long?, quantity: Int, lineTotal: Long, kind: OrderItemKind = OrderItemKind.PRODUCT) =
        MarketOrderItem(id = id, productId = productId, quantity = quantity, lineTotal = lineTotal, kind = kind)

    private val items = listOf(
        item(1, 10, 2, 6000), item(2, 11, 1, 2000), item(3, 10, 1, 999, OrderItemKind.BUNDLE_CHILD), item(4, null, 5, 5000, OrderItemKind.CREDIT_TOPUP)
    )

    private fun order(gateway: Long = 8000, fx: String = "1") = MarketOrder(id = 7, gatewayAmount = gateway, totalPrice = 8000, fxRate = BigDecimal(fx), status = OrderStatus.COMPLETED)

    @Test
    fun `a paid order adds one order, the matching quantities or the matching share of the gateway money`() {
        val all = null
        val only10 = "[10]"

        assertEquals(1L, GoalMath.paidDelta(goal(GoalMetric.ORDERS, all), order(), items))
        assertEquals(1L, GoalMath.paidDelta(goal(GoalMetric.ORDERS, only10), order(), items))
        assertEquals(0L, GoalMath.paidDelta(goal(GoalMetric.ORDERS, "[99]"), order(), items))

        // bundle children and credit top-ups are no products: 2 + 1 of the two product lines
        assertEquals(3L, GoalMath.paidDelta(goal(GoalMetric.PRODUCT_SALES, all), order(), items))
        assertEquals(2L, GoalMath.paidDelta(goal(GoalMetric.PRODUCT_SALES, only10), order(), items))

        assertEquals(8000L, GoalMath.paidDelta(goal(GoalMetric.REVENUE, all), order(), items))
        // product 10 holds 6000 of the 8000 goods
        assertEquals(6000L, GoalMath.paidDelta(goal(GoalMetric.REVENUE, only10), order(), items))
    }

    @Test
    fun `revenue is the gateway part only and is converted to the base currency by the order's fxRate`() {
        // 8000 order-currency units at 2 order units per base unit = 4000 base; credits paid part is no revenue
        assertEquals(4000L, GoalMath.paidDelta(goal(GoalMetric.REVENUE), order(gateway = 8000, fx = "2"), items))
        assertEquals(0L, GoalMath.paidDelta(goal(GoalMetric.REVENUE), order(gateway = 0), items))
        assertEquals(1667L, GoalMath.toBase(5000, BigDecimal("3")), "half up")
        assertEquals(5000L, GoalMath.toBase(5000, BigDecimal.ZERO), "a broken rate counts as 1")
    }

    @Test
    fun `a refund takes back the same kind of amount and an order that became refunded one order`() {
        val refund = MarketRefund(id = 3, gatewayAmount = 4000, amount = 4000)
        val quantities = mapOf(1L to 1, 2L to 1, 3L to 1)

        assertEquals(-4000L, GoalMath.refundDelta(goal(GoalMetric.REVENUE), order(), items, refund, quantities, false))
        assertEquals(-3000L, GoalMath.refundDelta(goal(GoalMetric.REVENUE, "[10]"), order(), items, refund, quantities, false))
        assertEquals(-2L, GoalMath.refundDelta(goal(GoalMetric.PRODUCT_SALES), order(), items, refund, quantities, false), "line 3 is a bundle child")
        assertEquals(-1L, GoalMath.refundDelta(goal(GoalMetric.PRODUCT_SALES, "[10]"), order(), items, refund, quantities, false))
        assertEquals(0L, GoalMath.refundDelta(goal(GoalMetric.ORDERS), order(), items, refund, quantities, false), "a partial refund keeps the order")
        assertEquals(-1L, GoalMath.refundDelta(goal(GoalMetric.ORDERS), order(), items, refund, quantities, true))
        assertEquals(0L, GoalMath.refundDelta(goal(GoalMetric.ORDERS, "[99]"), order(), items, refund, quantities, true))
    }

    @Test
    fun `a goal counts an order inside its window and, when periodic, only one paid in the current period`() {
        val now = at(utc, 2026, 10, 7, 12) // Wednesday
        val monday = at(utc, 2026, 10, 5)
        val windowed = goal(GoalMetric.ORDERS, startsAt = at(utc, 2026, 10, 1), endsAt = at(utc, 2026, 10, 10))

        assertTrue(GoalMath.counts(windowed, utc, at(utc, 2026, 10, 3), now))
        assertFalse(GoalMath.counts(windowed, utc, at(utc, 2026, 9, 30), now), "before startsAt")
        assertFalse(GoalMath.counts(windowed, utc, at(utc, 2026, 10, 10), now), "endsAt is exclusive")
        assertFalse(GoalMath.counts(goal(GoalMetric.ORDERS, status = "INACTIVE"), utc, now, now))

        val weekly = goal(GoalMetric.ORDERS, period = GoalPeriod.WEEKLY, periodStart = monday)

        assertTrue(GoalMath.counts(weekly, utc, at(utc, 2026, 10, 6), now))
        assertFalse(GoalMath.counts(weekly, utc, at(utc, 2026, 10, 4), now), "last week")
        assertEquals(monday, GoalMath.periodStart(GoalPeriod.WEEKLY, utc, now))
        assertEquals(at(utc, 2026, 10, 1), GoalMath.periodStart(GoalPeriod.MONTHLY, utc, now))
        assertNull(GoalMath.periodStart(GoalPeriod.ONE_TIME, utc, now))
        assertFalse(GoalMath.stale(weekly, utc, now))
        assertTrue(GoalMath.stale(goal(GoalMetric.ORDERS, period = GoalPeriod.WEEKLY, periodStart = at(utc, 2026, 9, 28)), utc, now))
        assertFalse(GoalMath.stale(goal(GoalMetric.ORDERS), utc, now))
    }
}
