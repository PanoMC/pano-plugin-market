package com.panomc.plugins.market.core.subscription

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** `RetrySchedule` (09 section 9.2, test 16 of 09 section 16). */
class RetryScheduleTest {
    private val day = SubscriptionTimings.DAY_MS
    private val t0 = 1_800_000_000_000L

    /**
     * Plays a merchant subscription whose every charge is declined: the first failure at [t0] enters grace
     * (`graceEndsAt = t0 + graceDays`), each retry fails again. Returns the retry offsets in days after the first failure.
     */
    private fun retryDays(graceDays: Int): List<Long> {
        val graceEnds = t0 + graceDays * day
        val offsets = mutableListOf<Long>()
        var now = t0
        var attempts = 1
        while (true) {
            val next = RetrySchedule.nextRetryAt(attempts, now, graceEnds) ?: break
            offsets += (next - t0) / day
            now = next
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
    fun `grace 0 means no retry`() {
        assertEquals(emptyList<Long>(), retryDays(0))
        assertNull(RetrySchedule.nextRetryAt(1, t0, t0))
    }

    @Test
    fun `a 60 day grace gives all eight retries at the documented offsets, then none`() {
        assertEquals(listOf(1L, 3L, 5L, 7L, 10L, 14L, 21L, 28L), retryDays(60))
        assertNull(RetrySchedule.nextRetryAt(9, t0 + 28 * day, t0 + 60 * day), "the ninth failure has no retry")
    }

    @Test
    fun `a retry exactly on the grace end is allowed, one millisecond later is not`() {
        assertEquals(t0 + 28 * day, retryDays(28).last() * day + t0)
        assertEquals(8, retryDays(28).size)
        assertEquals(7, retryDays(27).size)
        assertEquals(t0 + day, RetrySchedule.nextRetryAt(1, t0, t0 + day))
        assertNull(RetrySchedule.nextRetryAt(1, t0, t0 + day - 1))
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
