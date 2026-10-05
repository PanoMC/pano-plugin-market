package com.panomc.plugins.market.core.subscription

import com.panomc.plugins.market.db.model.PeriodUnit
import com.panomc.plugins.market.db.model.SubscriptionIntervalUnit
import com.panomc.plugins.market.spi.payment.IntervalUnit
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** One billing period `[start, end)`, epoch milliseconds. */
data class BillingPeriod(val start: Long, val end: Long) {
    init {
        require(end > start) { "A period must end after it starts: $start .. $end" }
    }
}

/**
 * Calendar arithmetic of subscription periods and timed products (09 section 5, 00 section 9). Pure: the zone is a
 * constructor argument (`MarketConfig.storeTimeZone`), "now" is always passed in.
 *
 * Every boundary is computed **from the anchor** (`anchor + n * count` units in the zone), never from the previous
 * boundary, so the end-of-month clamp does not drift: 31 Jan, 28 Feb, 31 Mar, 30 Apr. `DAY`, `WEEK`, `MONTH` and
 * `YEAR` move the local date and keep the local wall-clock time across a DST change (`ZonedDateTime.plus`); `MINUTE`
 * and `HOUR` are exact durations on the time line.
 *
 * Boundaries are strictly increasing in `n`, which the two searches rely on.
 */
class PeriodCalculator(val zone: ZoneId, val unit: PeriodUnit, val count: Int) {
    init {
        require(count >= 1) { "count must be at least 1" }
    }

    private val chrono: ChronoUnit = when (unit) {
        PeriodUnit.MINUTE -> ChronoUnit.MINUTES
        PeriodUnit.HOUR -> ChronoUnit.HOURS
        PeriodUnit.DAY -> ChronoUnit.DAYS
        PeriodUnit.WEEK -> ChronoUnit.WEEKS
        PeriodUnit.MONTH -> ChronoUnit.MONTHS
        PeriodUnit.YEAR -> ChronoUnit.YEARS
    }

    /** Nominal length of one period, only used to estimate the index before the exact search. */
    private val nominalMs: Long = when (unit) {
        PeriodUnit.MINUTE -> 60_000L
        PeriodUnit.HOUR -> 3_600_000L
        PeriodUnit.DAY -> 86_400_000L
        PeriodUnit.WEEK -> 7 * 86_400_000L
        PeriodUnit.MONTH -> 2_629_746_000L // 30.436875 days
        PeriodUnit.YEAR -> 31_556_952_000L // 365.2425 days
    } * count

    /** `anchor` plus `n` whole intervals, `n >= 0`; `boundary(anchor, 0) == anchor`. */
    fun boundary(anchor: Long, n: Long): Long {
        require(n >= 0) { "n must not be negative" }
        if (n == 0L) return anchor
        val start = ZonedDateTime.ofInstant(Instant.ofEpochMilli(anchor), zone)
        return start.plus(n * count, chrono).toInstant().toEpochMilli()
    }

    /** The smallest `boundary(anchor, n)` with `n >= 1` that is strictly greater than [after]. */
    fun nextBoundary(anchor: Long, after: Long): Long {
        if (after < anchor) return boundary(anchor, 1)
        var n = maxOf(1L, (after - anchor) / nominalMs)
        while (boundary(anchor, n) <= after) n++
        while (n > 1 && boundary(anchor, n - 1) > after) n--
        return boundary(anchor, n)
    }

    /**
     * The largest `boundary(anchor, n)` with `n >= 0` that is not after [at]. Before the anchor there is none: the
     * anchor itself is returned, so a clock that moved backwards never yields a time before the first period.
     */
    fun previousBoundary(anchor: Long, at: Long): Long {
        if (at <= anchor) return anchor
        var n = (at - anchor) / nominalMs
        while (boundary(anchor, n + 1) <= at) n++
        while (n > 0 && boundary(anchor, n) > at) n--
        return boundary(anchor, n)
    }

    /**
     * The end of a period that starts at [start]: `nextBoundary(anchor, start)`, except that a start which is off the
     * grid and lies less than half an interval before that boundary skips it and ends at the boundary after it.
     *
     * A start is off the grid for a `GATEWAY` row (09 section 4.4 step 4 stores the gateway's own period while the
     * anchor is `paidAt`) and for one period after the store time zone changed (09 section 15 item 13: the same anchor
     * instant yields shifted boundaries). Without the rule such a start would buy the sliver up to the next grid
     * point for a full price, the renewal would be over at once and step C would expire a paying subscriber. On-grid
     * starts are a whole interval before the next boundary and are never affected.
     */
    private fun endAfter(anchor: Long, start: Long): Long {
        val next = nextBoundary(anchor, start)
        return if (next - start < nominalMs / 2) nextBoundary(anchor, next) else next
    }

    /**
     * The period of a renewal that market itself creates (`MERCHANT`, `MANUAL`), with `E = currentPeriodEnd`
     * (09 section 5): normally `[E, nextBoundary(anchor, E))`; when at least one whole period was missed
     * (`now >= nextBoundary(anchor, E)`) the grid period that contains [now]. A renewal paid late inside the grace
     * period keeps `start = E` because the caller passes the same `E` and a `now` that is still inside the period.
     * An `E` that is off the grid (see [endAfter]) never yields a period shorter than half an interval.
     */
    fun renewalPeriod(anchor: Long, currentPeriodEnd: Long, now: Long): BillingPeriod {
        val next = endAfter(anchor, currentPeriodEnd)
        if (now < next) return BillingPeriod(currentPeriodEnd, next)
        return BillingPeriod(previousBoundary(anchor, now), nextBoundary(anchor, now))
    }

    /**
     * The period of a `GATEWAY` renewal: the event's values; a missing value falls back to the "normal" row of
     * [renewalPeriod] and an end that is not after the start is replaced by the computed one (09 section 5). The
     * computed end of an off-grid start follows [endAfter].
     */
    fun gatewayRenewalPeriod(
        anchor: Long,
        currentPeriodEnd: Long,
        now: Long,
        eventStart: Long?,
        eventEnd: Long?
    ): BillingPeriod {
        val computed = renewalPeriod(anchor, currentPeriodEnd, now)
        val start = eventStart ?: computed.start
        val end = if (eventEnd != null && eventEnd > start) eventEnd else endAfter(anchor, start)
        return BillingPeriod(start, end)
    }

    companion object {
        /** `MarketConfig.storeTimeZone`: empty means the JVM default zone. */
        fun zoneOf(storeTimeZone: String): ZoneId =
            if (storeTimeZone.isBlank()) ZoneId.systemDefault() else ZoneId.of(storeTimeZone.trim())

        fun forSubscription(zone: ZoneId, unit: SubscriptionIntervalUnit, count: Int): PeriodCalculator =
            PeriodCalculator(zone, unit.toPeriodUnit(), count)

        /**
         * Lead time before `currentPeriodEnd` at which the `MANUAL` renewal order is prepared and the reminder is
         * sent (09 section 8.6): `min(max(reminderDays, 1) days, half the period)`. A daily plan with a 3-day
         * reminder therefore leads by 12 hours.
         */
        fun reminderLeadMs(reminderDays: Int, periodStart: Long, periodEnd: Long): Long {
            val configured = maxOf(reminderDays, 1).toLong() * SubscriptionTimings.DAY_MS
            return minOf(configured, (periodEnd - periodStart) / 2)
        }
    }
}

fun SubscriptionIntervalUnit.toPeriodUnit(): PeriodUnit = when (this) {
    SubscriptionIntervalUnit.DAY -> PeriodUnit.DAY
    SubscriptionIntervalUnit.WEEK -> PeriodUnit.WEEK
    SubscriptionIntervalUnit.MONTH -> PeriodUnit.MONTH
    SubscriptionIntervalUnit.YEAR -> PeriodUnit.YEAR
}

fun IntervalUnit.toPeriodUnit(): PeriodUnit = when (this) {
    IntervalUnit.DAY -> PeriodUnit.DAY
    IntervalUnit.WEEK -> PeriodUnit.WEEK
    IntervalUnit.MONTH -> PeriodUnit.MONTH
    IntervalUnit.YEAR -> PeriodUnit.YEAR
}
