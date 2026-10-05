package com.panomc.plugins.market.core.subscription

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `RetrySchedule` (09 section 9.2, test 16 of 09 section 16). */
class RetryScheduleTest {
    private val day = SubscriptionTimings.DAY_MS
    private val hour = SubscriptionTimings.HOUR_MS
    private val t0 = 1_800_000_000_000L

    /**
     * Plays a merchant subscription whose every charge is declined: the first failure at [t0] enters grace
     * (`graceEndsAt = t0 + graceDays`), each retry fails again [latencyMs] after the instant it was scheduled for (the
     * 60 s job tick plus the charge call; production never fails in the very millisecond). Returns the retry offsets
     * in whole days after the first failure.
     */
    private fun retryDays(graceDays: Int, latencyMs: Long = 90_000L): List<Long> {
        val graceEnds = t0 + graceDays * day
        val offsets = mutableListOf<Long>()
        var now = t0
        var attempts = 1
        while (true) {
            val next = RetrySchedule.nextRetryAt(attempts, now, graceEnds) ?: break
            assertTrue(next > now, "a retry lies after the failure that scheduled it")
            assertTrue(next <= graceEnds, "a retry never lies after the grace end")
            offsets += (next - t0) / day
            now = next + latencyMs
            attempts++
            check(attempts < 50) { "runaway schedule" }
        }
        return offsets
    }

    @Test
    fun `the default 3 day grace retries at plus 1 and plus 3 days, then stops`() {
        assertEquals(listOf(1L, 3L), retryDays(3))
    }

    @Test
    fun `the default grace keeps its last retry when the retries fail some time after they were scheduled`() {
        // Review fix: the +3 d retry used to exist only when the +1 d retry failed in the very millisecond it was scheduled for.
        for (latency in listOf(0L, 1L, 90_000L, 15 * 60_000L, hour)) {
            assertEquals(listOf(1L, 3L), retryDays(3, latency), "latency $latency ms")
        }
        val graceEnds = t0 + 3 * day
        // The retry scheduled for +1 d failed 90 s late: the candidate is 90 s after the grace end, the retry is the grace end itself.
        val secondFailure = t0 + day + 90_000L
        assertEquals(graceEnds, RetrySchedule.nextRetryAt(2, secondFailure, graceEnds))
        // The retry at the grace end fails 90 s late: nothing follows it.
        assertNull(RetrySchedule.nextRetryAt(3, graceEnds + 90_000L, graceEnds))
    }

    @Test
    fun `every grace equal to a cumulative offset keeps its last retry under scheduler latency`() {
        val cumulative = listOf(1L, 3L, 5L, 7L, 10L, 14L, 21L, 28L)
        for ((i, graceDays) in cumulative.withIndex()) {
            if (graceDays == 1L) continue
            assertEquals(cumulative.take(i + 1), retryDays(graceDays.toInt()), "grace $graceDays d")
        }
        assertEquals(listOf(1L), retryDays(1))
    }

    @Test
    fun `grace 0 means no retry`() {
        assertEquals(emptyList<Long>(), retryDays(0))
        assertNull(RetrySchedule.nextRetryAt(1, t0, t0))
        assertNull(RetrySchedule.nextRetryAt(1, t0 + 5_000L, t0), "even when the failure itself came late")
    }

    @Test
    fun `a 60 day grace gives all eight retries at the documented offsets, then none`() {
        assertEquals(listOf(1L, 3L, 5L, 7L, 10L, 14L, 21L, 28L), retryDays(60))
        assertEquals(listOf(1L, 3L, 5L, 7L, 10L, 14L, 21L, 28L), retryDays(60, 0L))
        assertNull(RetrySchedule.nextRetryAt(9, t0 + 28 * day, t0 + 60 * day), "the ninth failure has no retry")
    }

    @Test
    fun `a retry exactly on the grace end is allowed and a candidate just past it is the grace end, a further one is not`() {
        assertEquals(t0 + 28 * day, retryDays(28).last() * day + t0)
        assertEquals(8, retryDays(28).size)
        assertEquals(7, retryDays(27).size)
        assertEquals(t0 + day, RetrySchedule.nextRetryAt(1, t0, t0 + day), "exactly on the grace end")
        // Up to one hour past the grace end is scheduler latency: the retry lands on the grace end.
        assertEquals(t0 + day - 1, RetrySchedule.nextRetryAt(1, t0, t0 + day - 1))
        assertEquals(t0 + day - hour, RetrySchedule.nextRetryAt(1, t0, t0 + day - hour), "exactly the tolerance")
        // Beyond the tolerance it is a real further retry, outside the grace period.
        assertNull(RetrySchedule.nextRetryAt(1, t0, t0 + day - hour - 1))
        assertNull(RetrySchedule.nextRetryAt(1, t0, t0 + day - 2 * hour))
        assertNull(RetrySchedule.nextRetryAt(1, t0, t0), "grace 0")
    }

    @Test
    fun `the tolerance can never admit a further retry because the shortest gap is a whole day`() {
        assertTrue(SubscriptionTimings.RETRY_LATENCY_TOLERANCE_MS < RetrySchedule.GAPS_DAYS.min().toLong() * day)
        // A failure that happens after the grace end schedules nothing, whatever the gap.
        for (attempts in 1..RetrySchedule.MAX_RETRIES) {
            assertNull(RetrySchedule.nextRetryAt(attempts, t0 + 3 * day + 1, t0 + 3 * day), "failure $attempts after the grace end")
            assertNull(RetrySchedule.nextRetryAt(attempts, t0 + 3 * day, t0 + 3 * day), "failure $attempts at the grace end")
        }
    }

    @Test
    fun `each failure uses its own gap`() {
        val farGrace = t0 + 365 * day
        val gaps = listOf(1L, 2L, 2L, 2L, 3L, 4L, 7L, 7L)
        gaps.forEachIndexed { i, gap ->
            assertEquals(t0 + gap * day, RetrySchedule.nextRetryAt(i + 1, t0, farGrace), "failure ${i + 1}")
        }
        assertEquals(gaps, RetrySchedule.GAPS_DAYS.map { it.toLong() })
        assertEquals(8, RetrySchedule.MAX_RETRIES)
    }

    @Test
    fun `out of range attempts and a missing grace end never retry`() {
        val farGrace = t0 + 365 * day
        assertNull(RetrySchedule.nextRetryAt(0, t0, farGrace), "bad input never charges")
        assertNull(RetrySchedule.nextRetryAt(-1, t0, farGrace))
        assertNull(RetrySchedule.nextRetryAt(9, t0, farGrace))
        assertNull(RetrySchedule.nextRetryAt(Int.MAX_VALUE, t0, farGrace))
        assertNull(RetrySchedule.nextRetryAt(1, t0, null))
    }

    @Test
    fun `the schedule is relative to now, not to the first failure`() {
        // A failure that happens late (the job was down) still waits its full gap.
        val now = t0 + 10 * day
        assertEquals(now + 2 * day, RetrySchedule.nextRetryAt(2, now, now + 5 * day))
    }
}
