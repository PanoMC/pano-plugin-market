package com.panomc.plugins.market.core.subscription

import com.panomc.plugins.market.db.model.PeriodUnit
import com.panomc.plugins.market.db.model.SubscriptionIntervalUnit
import com.panomc.plugins.market.spi.payment.IntervalUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** `PeriodCalculator` (09 section 5, 00 section 9, tests 1 to 6 of 09 section 16 plus the six units of 17 section 11). */
class PeriodCalculatorTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")
    private val istanbul: ZoneId = ZoneId.of("Europe/Istanbul")

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()
    private fun local(ms: Long, zone: ZoneId): LocalDateTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), zone).toLocalDateTime()
    private fun localDate(ms: Long, zone: ZoneId): LocalDate = local(ms, zone).toLocalDate()
    private fun calc(zone: ZoneId, unit: PeriodUnit, count: Int = 1) = PeriodCalculator(zone, unit, count)
    private fun zdt(ms: Long, zone: ZoneId): ZonedDateTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)

    private val hourMs = 3_600_000L

    // ---------------------------------------------------------------- 1, 2: month and year arithmetic

    @Test
    fun `monthly from 31 January clamps to the month end and never drifts`() {
        val c = calc(utc, PeriodUnit.MONTH)
        val anchor = at("2026-01-31T10:00:00Z")
        val dates = (0L..6L).map { localDate(c.boundary(anchor, it), utc) }
        assertEquals(
            listOf("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30", "2026-05-31", "2026-06-30", "2026-07-31"),
            dates.map { it.toString() },
            "from the anchor, so March is the 31st again"
        )
        // The clock time of day never changes.
        (0L..6L).forEach { assertEquals(10, local(c.boundary(anchor, it), utc).hour) }
    }

    @Test
    fun `monthly from 31 January in a leap year ends on 29 February`() {
        val c = calc(utc, PeriodUnit.MONTH)
        val anchor = at("2028-01-31T00:00:00Z")
        assertEquals("2028-02-29", localDate(c.boundary(anchor, 1), utc).toString())
        assertEquals("2028-03-31", localDate(c.boundary(anchor, 2), utc).toString())
    }

    @Test
    fun `yearly from 29 February is 28 February in a normal year and 29 February four years later`() {
        val c = calc(utc, PeriodUnit.YEAR)
        val anchor = at("2028-02-29T12:00:00Z")
        val dates = (1L..4L).map { localDate(c.boundary(anchor, it), utc).toString() }
        assertEquals(listOf("2029-02-28", "2030-02-28", "2031-02-28", "2032-02-29"), dates)
    }

    @Test
    fun `quarterly and day-of-month anchors keep the anchor day`() {
        val c = calc(utc, PeriodUnit.MONTH, 3)
        val anchor = at("2026-11-30T08:30:00Z")
        assertEquals(
            listOf("2027-02-28", "2027-05-30", "2027-08-30", "2027-11-30"),
            (1L..4L).map { localDate(c.boundary(anchor, it), utc).toString() }
        )
    }

    // ---------------------------------------------------------------- 3: DST

    @Test
    fun `daily across the Berlin spring-forward keeps the local time and the instant gap is 23 hours`() {
        val c = calc(berlin, PeriodUnit.DAY)
        val anchor = zdt(at("2026-03-27T09:00:00Z"), berlin).withHour(10).withMinute(0).toInstant().toEpochMilli()
        val days = (0L..4L).map { c.boundary(anchor, it) }
        days.forEach { assertEquals(10, local(it, berlin).hour, "10:00 local every day") }
        // 2026-03-28 10:00 -> 2026-03-29 10:00 crosses the change at 02:00 -> 03:00.
        assertEquals("2026-03-28", localDate(days[1], berlin).toString())
        assertEquals("2026-03-29", localDate(days[2], berlin).toString())
        assertEquals(23 * hourMs, days[2] - days[1])
        assertEquals(24 * hourMs, days[1] - days[0])
        assertEquals(24 * hourMs, days[3] - days[2])
    }

    @Test
    fun `daily across the Berlin fall-back keeps the local time and the instant gap is 25 hours`() {
        val c = calc(berlin, PeriodUnit.DAY)
        val anchor = zdt(at("2026-10-23T00:00:00Z"), berlin).withHour(10).withMinute(0).toInstant().toEpochMilli()
        val days = (0L..3L).map { c.boundary(anchor, it) }
        days.forEach { assertEquals(10, local(it, berlin).hour) }
        assertEquals("2026-10-24", localDate(days[1], berlin).toString())
        assertEquals("2026-10-25", localDate(days[2], berlin).toString())
        assertEquals(25 * hourMs, days[2] - days[1])
    }

    @Test
    fun `a local time that does not exist is moved forward for that day only and the next day is back on the anchor time`() {
        val c = calc(berlin, PeriodUnit.DAY)
        val anchor = ZonedDateTime.of(2026, 3, 28, 2, 30, 0, 0, berlin).toInstant().toEpochMilli()
        // 2026-03-29 02:30 does not exist in Berlin (02:00 jumps to 03:00).
        val gapDay = zdt(c.boundary(anchor, 1), berlin)
        assertEquals(LocalDateTime.of(2026, 3, 29, 3, 30), gapDay.toLocalDateTime())
        // Computed from the anchor, so the day after has 02:30 again (no drift to 03:30).
        val after = zdt(c.boundary(anchor, 2), berlin)
        assertEquals(LocalDateTime.of(2026, 3, 30, 2, 30), after.toLocalDateTime())
    }

    @Test
    fun `an ambiguous local time at the fall-back keeps the first occurrence`() {
        val c = calc(berlin, PeriodUnit.DAY)
        val anchor = ZonedDateTime.of(2026, 10, 24, 2, 30, 0, 0, berlin).toInstant().toEpochMilli()
        val overlapDay = zdt(c.boundary(anchor, 1), berlin)
        assertEquals(LocalDateTime.of(2026, 10, 25, 2, 30), overlapDay.toLocalDateTime())
        assertEquals(ZoneOffset.ofHours(2), overlapDay.offset, "02:30 CEST, the first of the two 02:30")
        val next = zdt(c.boundary(anchor, 2), berlin)
        assertEquals(LocalDateTime.of(2026, 10, 26, 2, 30), next.toLocalDateTime())
        assertEquals(ZoneOffset.ofHours(1), next.offset)
    }

    @Test
    fun `Istanbul has no daylight saving today so every day is 24 hours`() {
        val c = calc(istanbul, PeriodUnit.DAY)
        val anchor = ZonedDateTime.of(2026, 3, 20, 9, 0, 0, 0, istanbul).toInstant().toEpochMilli()
        var previous = c.boundary(anchor, 0)
        for (n in 1L..300L) {
            val b = c.boundary(anchor, n)
            assertEquals(24 * hourMs, b - previous, "day $n")
            assertEquals(9, local(b, istanbul).hour)
            previous = b
        }
    }

    @Test
    fun `Istanbul historic daylight saving days keep the local time as well`() {
        // Turkey observed EU-style summer time until 2015 (the 2015 end was moved to 8 November).
        val c = calc(istanbul, PeriodUnit.DAY)
        val anchor = ZonedDateTime.of(2015, 3, 20, 9, 0, 0, 0, istanbul).toInstant().toEpochMilli()
        var previous = c.boundary(anchor, 0)
        var sawShortDay = false
        var sawLongDay = false
        for (n in 1L..260L) {
            val b = c.boundary(anchor, n)
            assertEquals(9, local(b, istanbul).hour, "day $n keeps 09:00 local")
            val gap = b - previous
            if (gap == 23 * hourMs) sawShortDay = true
            if (gap == 25 * hourMs) sawLongDay = true
            previous = b
        }
        assertTrue(sawShortDay, "the 2015 spring change is a 23 hour day in this tz database")
        assertTrue(sawLongDay, "the 2015 autumn change is a 25 hour day in this tz database")
    }

    @Test
    fun `a monthly plan anchored in Berlin keeps the local time over both changes`() {
        val c = calc(berlin, PeriodUnit.MONTH)
        val anchor = ZonedDateTime.of(2026, 1, 15, 8, 15, 0, 0, berlin).toInstant().toEpochMilli()
        (0L..12L).forEach {
            val l = local(c.boundary(anchor, it), berlin)
            assertEquals(8, l.hour)
            assertEquals(15, l.minute)
            assertEquals(15, l.dayOfMonth)
        }
    }

    // ---------------------------------------------------------------- 4: counts and all six units

    @Test
    fun `all six units with counts, literal expectations`() {
        val a = at("2026-01-01T00:00:00Z")
        assertEquals(at("2026-01-01T03:00:00Z"), calc(utc, PeriodUnit.MINUTE, 90).boundary(a, 2))
        assertEquals(at("2026-01-02T12:00:00Z"), calc(utc, PeriodUnit.HOUR, 36).boundary(a, 1))
        assertEquals(at("2027-01-01T00:00:00Z"), calc(utc, PeriodUnit.DAY, 365).boundary(a, 1))
        assertEquals(at("2026-02-12T00:00:00Z"), calc(utc, PeriodUnit.WEEK, 2).boundary(a, 3))
        assertEquals(at("2026-07-01T00:00:00Z"), calc(utc, PeriodUnit.MONTH, 3).boundary(a, 2))
        assertEquals(at("2029-01-01T00:00:00Z"), calc(utc, PeriodUnit.YEAR, 1).boundary(a, 3))
        PeriodUnit.values().forEach { assertEquals(a, calc(utc, it).boundary(a, 0), "n = 0 is the anchor for $it") }
    }

    @Test
    fun `hours and minutes are exact durations even across a daylight saving change`() {
        val a = ZonedDateTime.of(2026, 3, 28, 12, 0, 0, 0, berlin).toInstant().toEpochMilli()
        // 24 hourly steps over the spring change are exactly 24 hours, so the wall clock moves by one hour.
        assertEquals(a + 24 * hourMs, calc(berlin, PeriodUnit.HOUR).boundary(a, 24))
        assertEquals(a + 24 * 60 * 60_000L, calc(berlin, PeriodUnit.MINUTE, 60).boundary(a, 24))
        assertEquals(13, local(calc(berlin, PeriodUnit.HOUR).boundary(a, 24), berlin).hour)
    }

    @Test
    fun `weekly with count 2 keeps the weekday`() {
        val c = calc(berlin, PeriodUnit.WEEK, 2)
        val anchor = ZonedDateTime.of(2026, 1, 7, 18, 0, 0, 0, berlin).toInstant().toEpochMilli()
        (1L..30L).forEach {
            val z = zdt(c.boundary(anchor, it), berlin)
            assertEquals(java.time.DayOfWeek.WEDNESDAY, z.dayOfWeek)
            assertEquals(18, z.hour)
        }
    }

    @Test
    fun `constructor and boundary reject bad arguments`() {
        assertThrows(IllegalArgumentException::class.java) { PeriodCalculator(utc, PeriodUnit.DAY, 0) }
        assertThrows(IllegalArgumentException::class.java) { PeriodCalculator(utc, PeriodUnit.DAY, -3) }
        assertThrows(IllegalArgumentException::class.java) { calc(utc, PeriodUnit.DAY).boundary(0L, -1) }
        assertThrows(IllegalArgumentException::class.java) { BillingPeriod(10, 10) }
    }

    // ---------------------------------------------------------------- 5: nextBoundary / previousBoundary

    @Test
    fun `nextBoundary is strictly greater than after, also when after is exactly a boundary`() {
        val c = calc(utc, PeriodUnit.MONTH)
        val anchor = at("2026-01-31T00:00:00Z")
        val feb28 = at("2026-02-28T00:00:00Z")
        val mar31 = at("2026-03-31T00:00:00Z")
        assertEquals(feb28, c.nextBoundary(anchor, anchor), "after = the anchor gives n = 1")
        assertEquals(mar31, c.nextBoundary(anchor, feb28), "exactly on a boundary gives the following one")
        assertEquals(feb28, c.nextBoundary(anchor, feb28 - 1))
        assertEquals(feb28, c.nextBoundary(anchor, anchor + 1))
        assertEquals(feb28, c.nextBoundary(anchor, anchor - 10 * 86_400_000L), "before the anchor: the first boundary")
    }

    @Test
    fun `previousBoundary is not after at, and the anchor before the anchor`() {
        val c = calc(utc, PeriodUnit.MONTH)
        val anchor = at("2026-01-31T00:00:00Z")
        val feb28 = at("2026-02-28T00:00:00Z")
        assertEquals(feb28, c.previousBoundary(anchor, feb28), "a boundary is its own previous")
        assertEquals(anchor, c.previousBoundary(anchor, feb28 - 1))
        assertEquals(feb28, c.previousBoundary(anchor, at("2026-03-30T23:59:59Z")))
        assertEquals(anchor, c.previousBoundary(anchor, anchor))
        assertEquals(anchor, c.previousBoundary(anchor, anchor - 5000), "a clock that moved backwards never yields a time before the anchor")
    }

    @Test
    fun `the searches agree with a brute force scan for every unit and zone`() {
        val zones = listOf(utc, berlin, istanbul, ZoneId.of("America/New_York"))
        val anchors = listOf(
            ZonedDateTime.of(2026, 1, 31, 1, 30, 0, 0, berlin).toInstant().toEpochMilli(),
            ZonedDateTime.of(2026, 3, 28, 2, 30, 0, 0, berlin).toInstant().toEpochMilli(),
            ZonedDateTime.of(2028, 2, 29, 23, 59, 0, 0, utc).toInstant().toEpochMilli()
        )
        for (zone in zones) for (unit in PeriodUnit.values()) for (count in listOf(1, 2, 3)) {
            val c = calc(zone, unit, count)
            for (anchor in anchors) {
                val step = when (unit) {
                    PeriodUnit.MINUTE -> 17 * 60_000L
                    PeriodUnit.HOUR -> 5 * hourMs + 1234
                    else -> 29 * hourMs + 777
                }
                var after = anchor - 3 * step
                repeat(60) {
                    after += step
                    // brute force: smallest n >= 1 with boundary(n) > after
                    var n = 1L
                    while (c.boundary(anchor, n) <= after) n++
                    assertEquals(c.boundary(anchor, n), c.nextBoundary(anchor, after), "next $zone $unit x$count after=$after")
                    // brute force: largest n >= 0 with boundary(n) <= after, anchor when none
                    var p = 0L
                    while (c.boundary(anchor, p + 1) <= after) p++
                    val expectedPrev = if (after < anchor) anchor else c.boundary(anchor, p)
                    assertEquals(expectedPrev, c.previousBoundary(anchor, after), "previous $zone $unit x$count at=$after")
                }
            }
        }
    }

    @Test
    fun `boundaries are strictly increasing so the searches terminate`() {
        for (zone in listOf(berlin, istanbul)) for (unit in PeriodUnit.values()) {
            val c = calc(zone, unit)
            val anchor = ZonedDateTime.of(2026, 3, 27, 2, 30, 0, 0, zone).toInstant().toEpochMilli()
            var previous = c.boundary(anchor, 0)
            for (n in 1L..120L) {
                val b = c.boundary(anchor, n)
                assertTrue(b > previous, "$zone $unit n=$n")
                previous = b
            }
        }
    }

    // ---------------------------------------------------------------- 6: periods of a renewal

    @Test
    fun `a normal renewal starts at the old end and ends at the next grid boundary`() {
        val c = calc(utc, PeriodUnit.MONTH)
        val anchor = at("2026-01-31T00:00:00Z")
        val e = at("2026-02-28T00:00:00Z")
        val p = c.renewalPeriod(anchor, e, now = e + 1000)
        assertEquals(BillingPeriod(e, at("2026-03-31T00:00:00Z")), p)
        // Never iterative: the period after March is April 30, not April 28 + 1 month.
        val p2 = c.renewalPeriod(anchor, p.end, now = p.end + 1000)
        assertEquals(BillingPeriod(p.end, at("2026-04-30T00:00:00Z")), p2)
    }

    @Test
    fun `a renewal paid late inside the grace period keeps the old end as its start`() {
        val c = calc(utc, PeriodUnit.MONTH)
        val anchor = at("2026-01-31T00:00:00Z")
        val e = at("2026-02-28T00:00:00Z")
        val p = c.renewalPeriod(anchor, e, now = e + 2 * 86_400_000L)
        assertEquals(e, p.start)
        assertEquals(at("2026-03-31T00:00:00Z"), p.end)
    }

    @Test
    fun `three intervals late the renewal is the grid period that contains now`() {
        val c = calc(utc, PeriodUnit.MONTH)
        val anchor = at("2026-01-31T00:00:00Z")
        val e = at("2026-02-28T00:00:00Z")
        val now = at("2026-05-15T12:00:00Z") // three months after e
        val p = c.renewalPeriod(anchor, e, now)
        assertEquals(BillingPeriod(at("2026-04-30T00:00:00Z"), at("2026-05-31T00:00:00Z")), p)
        assertTrue(p.start <= now && now < p.end)
    }

    @Test
    fun `now exactly on the next boundary is already the missed case`() {
        val c = calc(utc, PeriodUnit.DAY)
        val anchor = at("2026-01-01T00:00:00Z")
        val e = at("2026-01-05T00:00:00Z")
        val next = at("2026-01-06T00:00:00Z")
        assertEquals(BillingPeriod(e, next), c.renewalPeriod(anchor, e, next - 1))
        assertEquals(BillingPeriod(next, at("2026-01-07T00:00:00Z")), c.renewalPeriod(anchor, e, next))
    }

    @Test
    fun `a daily plan three days late charges the current day only`() {
        val c = calc(berlin, PeriodUnit.DAY)
        val anchor = ZonedDateTime.of(2026, 3, 25, 10, 0, 0, 0, berlin).toInstant().toEpochMilli()
        val e = c.boundary(anchor, 4)
        val now = c.boundary(anchor, 7) + 3 * hourMs
        val p = c.renewalPeriod(anchor, e, now)
        assertEquals(c.boundary(anchor, 7), p.start)
        assertEquals(c.boundary(anchor, 8), p.end)
    }

    @Test
    fun `a gateway renewal takes the event values and falls back to the grid for missing or bad ones`() {
        val c = calc(utc, PeriodUnit.MONTH)
        val anchor = at("2026-01-31T00:00:00Z")
        val e = at("2026-02-28T00:00:00Z")
        val now = e + 3600_000L
        // Both from the event.
        assertEquals(
            BillingPeriod(at("2026-02-28T05:00:00Z"), at("2026-03-31T05:00:00Z")),
            c.gatewayRenewalPeriod(anchor, e, now, at("2026-02-28T05:00:00Z"), at("2026-03-31T05:00:00Z"))
        )
        // Nothing from the event: the normal row.
        assertEquals(BillingPeriod(e, at("2026-03-31T00:00:00Z")), c.gatewayRenewalPeriod(anchor, e, now, null, null))
        // Only the end is missing: computed from the event's start.
        assertEquals(
            BillingPeriod(at("2026-02-28T00:00:00Z"), at("2026-03-31T00:00:00Z")),
            c.gatewayRenewalPeriod(anchor, e, now, at("2026-02-28T00:00:00Z"), null)
        )
        // An end that is not after the start is replaced by the computed one.
        assertEquals(
            BillingPeriod(e, at("2026-03-31T00:00:00Z")),
            c.gatewayRenewalPeriod(anchor, e, now, e, e)
        )
        assertEquals(
            BillingPeriod(e, at("2026-03-31T00:00:00Z")),
            c.gatewayRenewalPeriod(anchor, e, now, e, e - 1000)
        )
        // Only the start is missing: computed start, the event's end is kept.
        assertEquals(
            BillingPeriod(e, at("2026-03-30T00:00:00Z")),
            c.gatewayRenewalPeriod(anchor, e, now, null, at("2026-03-30T00:00:00Z"))
        )
    }

    // ---------------------------------------------------------------- off-grid starts (review fix: no sliver periods)

    private val halfMonthMs = 2_629_746_000L / 2

    @Test
    fun `a gateway period that ends shortly before the grid point never buys a sliver`() {
        // 09 section 4.4 step 4: the gateway's own period is stored while the anchor is paidAt.
        val c = calc(utc, PeriodUnit.MONTH)
        val anchor = at("2026-01-15T14:23:07Z")
        val grid1 = at("2026-02-15T14:23:07Z")
        val grid2 = at("2026-03-15T14:23:07Z")
        for ((label, e) in listOf("4 s" to grid1 - 4_000L, "4 h 23 min" to at("2026-02-15T10:00:00Z"), "just under half" to grid1 - (halfMonthMs - 1))) {
            val now = e + 60_000L
            val expected = BillingPeriod(e, grid2)
            // The event carries no period at all, only its start, or an end that is not after the start.
            assertEquals(expected, c.gatewayRenewalPeriod(anchor, e, now, null, null), "no period, $label before the grid point")
            assertEquals(expected, c.gatewayRenewalPeriod(anchor, e, now, e, null), "start only, $label")
            assertEquals(expected, c.gatewayRenewalPeriod(anchor, e, now, e, e), "end = start, $label")
            assertEquals(expected, c.gatewayRenewalPeriod(anchor, e, now, e, e - 1), "end before start, $label")
            // The plain renewal of a MERCHANT / MANUAL row with the same off-grid end.
            assertEquals(expected, c.renewalPeriod(anchor, e, now), "renewalPeriod, $label")
            assertTrue(expected.end - expected.start >= halfMonthMs, label)
        }
    }

    @Test
    fun `a period that starts 4 s before the first grid point is not a sliver either`() {
        val c = calc(utc, PeriodUnit.MONTH)
        val anchor = at("2026-01-15T14:23:07Z")
        // Before the anchor the first boundary is a whole interval away.
        val before = c.gatewayRenewalPeriod(anchor, anchor + 1_000L, anchor + 2_000L, anchor - 4_000L, null)
        assertEquals(BillingPeriod(anchor - 4_000L, at("2026-02-15T14:23:07Z")), before)
        // 4 s before the next grid point, given as the event's start.
        val grid1 = at("2026-02-15T14:23:07Z")
        val sliver = c.gatewayRenewalPeriod(anchor, grid1, grid1 + 1_000L, grid1 - 4_000L, null)
        assertEquals(BillingPeriod(grid1 - 4_000L, at("2026-03-15T14:23:07Z")), sliver)
    }

    @Test
    fun `a MERCHANT row whose store zone changed from Berlin to UTC buys one long period, not one hour`() {
        val anchor = at("2026-01-10T11:00:00Z") // 12:00 in Berlin
        val berlinCalc = calc(berlin, PeriodUnit.MONTH)
        val utcCalc = calc(utc, PeriodUnit.MONTH)
        val e = berlinCalc.boundary(anchor, 3) // 2026-04-10T12:00 CEST = 10:00Z, written while the store was in Berlin
        assertEquals(at("2026-04-10T10:00:00Z"), e)
        assertEquals(at("2026-04-10T11:00:00Z"), utcCalc.boundary(anchor, 3), "the UTC grid is one hour later")
        val p = utcCalc.renewalPeriod(anchor, e, now = e + 60_000L)
        assertEquals(BillingPeriod(e, at("2026-05-10T11:00:00Z")), p, "the one hour sliver is skipped")
        assertTrue(p.end - p.start >= halfMonthMs)
        // The period after it is back on the (new) grid: a whole month, no second charge an hour later.
        assertEquals(BillingPeriod(p.end, at("2026-06-10T11:00:00Z")), utcCalc.renewalPeriod(anchor, p.end, p.end + 1_000L))
        // The other way round (UTC -> Berlin): the end lies one hour after the Berlin grid point, so the next grid
        // point is a whole month away and nothing is skipped.
        val eUtc = utcCalc.boundary(anchor, 3)
        val back = berlinCalc.renewalPeriod(anchor, eUtc, eUtc + 60_000L)
        assertEquals(BillingPeriod(eUtc, berlinCalc.boundary(anchor, 4)), back)
        assertTrue(back.end - back.start >= halfMonthMs, "$back")
    }

    @Test
    fun `an off grid start gets at least half an interval for every unit and zone, on-grid starts are unchanged`() {
        val zones = listOf(utc, berlin, istanbul)
        for (zone in zones) for (unit in PeriodUnit.values()) for (count in listOf(1, 2)) {
            val c = calc(zone, unit, count)
            val anchor = ZonedDateTime.of(2026, 1, 31, 14, 23, 7, 0, berlin).toInstant().toEpochMilli()
            val nominal = when (unit) {
                PeriodUnit.MINUTE -> 60_000L
                PeriodUnit.HOUR -> hourMs
                PeriodUnit.DAY -> 86_400_000L
                PeriodUnit.WEEK -> 7 * 86_400_000L
                PeriodUnit.MONTH -> 2_629_746_000L
                PeriodUnit.YEAR -> 31_556_952_000L
            } * count
            for (n in 1L..14L) {
                val grid = c.boundary(anchor, n)
                // On the grid: the period is exactly the next grid interval (unchanged behaviour).
                assertEquals(BillingPeriod(grid, c.boundary(anchor, n + 1)), c.renewalPeriod(anchor, grid, grid + 1000), "on grid $zone $unit x$count n=$n")
                assertEquals(BillingPeriod(grid, c.boundary(anchor, n + 1)), c.gatewayRenewalPeriod(anchor, grid, grid + 1000, null, null))
                // Off the grid, from a few milliseconds up to nearly a whole interval before the next grid point.
                for (behind in listOf(1L, 1000L, 4_000L, nominal / 10, nominal / 3, nominal / 2 - 1, nominal / 2, nominal / 2 + 1, nominal - 1)) {
                    if (behind < 1) continue
                    val start = c.boundary(anchor, n + 1) - behind
                    if (start <= grid) continue // not between two grid points of this scan
                    val r = c.renewalPeriod(anchor, start, start + 1)
                    assertEquals(start, r.start)
                    assertTrue(r.end - r.start >= nominal / 2 - 1, "renewalPeriod $zone $unit x$count n=$n behind=$behind -> $r")
                    assertEquals(r.end, c.boundary(anchor, c.boundaryIndex(anchor, r.end)), "ends on the grid")
                    val g = c.gatewayRenewalPeriod(anchor, start, start + 1, start, null)
                    assertEquals(r, g, "gateway fallback agrees with the normal row")
                }
            }
        }
    }

    @Test
    fun `exactly half an interval before the grid point is kept, one millisecond less skips it`() {
        val c = calc(utc, PeriodUnit.DAY)
        val anchor = at("2026-01-01T00:00:00Z")
        val half = at("2026-01-05T12:00:00Z")
        assertEquals(BillingPeriod(half, at("2026-01-06T00:00:00Z")), c.renewalPeriod(anchor, half, half + 1))
        val under = half + 1
        assertEquals(BillingPeriod(under, at("2026-01-07T00:00:00Z")), c.renewalPeriod(anchor, under, under + 1))
    }

    /** Index `n` of a grid point (test helper: the exact boundary the search found). */
    private fun PeriodCalculator.boundaryIndex(anchor: Long, target: Long): Long {
        var n = 0L
        while (this.boundary(anchor, n) < target) n++
        return n
    }

    // ---------------------------------------------------------------- helpers

    @Test
    fun `reminder lead is the configured days capped at half a period`() {
        val day = SubscriptionTimings.DAY_MS
        // 09 section 15 item 7: a daily plan with a 3-day reminder leads by 12 hours.
        assertEquals(12 * hourMs, PeriodCalculator.reminderLeadMs(3, 0, day))
        assertEquals(3 * day, PeriodCalculator.reminderLeadMs(3, 0, 30 * day))
        assertEquals(day, PeriodCalculator.reminderLeadMs(0, 0, 30 * day), "0 days still means at least one day")
        assertEquals(day, PeriodCalculator.reminderLeadMs(-5, 0, 30 * day))
        assertEquals(15 * day, PeriodCalculator.reminderLeadMs(30, 0, 30 * day))
    }

    @Test
    fun `zone resolution and unit conversion`() {
        assertEquals(ZoneId.systemDefault(), PeriodCalculator.zoneOf(""))
        assertEquals(ZoneId.systemDefault(), PeriodCalculator.zoneOf("   "))
        assertEquals(berlin, PeriodCalculator.zoneOf("Europe/Berlin"))
        assertEquals(istanbul, PeriodCalculator.zoneOf(" Europe/Istanbul "))
        assertEquals(PeriodUnit.WEEK, SubscriptionIntervalUnit.WEEK.toPeriodUnit())
        assertEquals(PeriodUnit.YEAR, IntervalUnit.YEAR.toPeriodUnit())
        SubscriptionIntervalUnit.values().forEach { assertEquals(it.name, it.toPeriodUnit().name) }
        IntervalUnit.values().forEach { assertEquals(it.name, it.toPeriodUnit().name) }
        val c = PeriodCalculator.forSubscription(utc, SubscriptionIntervalUnit.MONTH, 2)
        assertEquals(PeriodUnit.MONTH, c.unit)
        assertEquals(2, c.count)
        assertTrue(c.boundary(0L, 1) > 0L)
    }
}
