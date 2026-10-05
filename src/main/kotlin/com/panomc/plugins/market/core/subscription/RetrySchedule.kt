package com.panomc.plugins.market.core.subscription

/**
 * Retry schedule of declined `MERCHANT` charges (09 section 9.2). The gaps in days between the first failure and each
 * retry add up to +1, +3, +5, +7, +10, +14, +21 and +28 days; a retry must still fall inside the grace period.
 */
object RetrySchedule {
    val GAPS_DAYS: List<Int> = listOf(1, 2, 2, 2, 3, 4, 7, 7)

    /** At most this many automatic retries per subscription period. */
    val MAX_RETRIES: Int = GAPS_DAYS.size

    /**
     * When to charge again after the [failedAttempts]-th failure of a period (`renewal.attempts`, from 1): `now` plus
     * the gap of that failure, or `null` when there is no further retry because the attempts are used up, the
     * candidate lies after [graceEndsAt] or there is no grace end. With the default 3-day grace the retries fall at
     * +1 and +3 days; `subscriptionGraceDays = 0` gives none. `failedAttempts < 1` never retries (fail safe: never charge on bad input).
     *
     * `graceEndsAt` is frozen at the first failure while `now` is the moment a retry actually failed, which is a little
     * after the instant it was scheduled for (job tick, charge call). A candidate that overshoots the grace end by at
     * most [SubscriptionTimings.RETRY_LATENCY_TOLERANCE_MS] is that delay and not a further retry: it is scheduled at
     * the grace end itself, the last retry of the period that step D already waits for (09 section 9.4). Without the
     * tolerance the documented `+3 d` retry would exist only when the previous one failed in the very millisecond it
     * was scheduled for. The result is never after [graceEndsAt] and always after [now].
     */
    fun nextRetryAt(failedAttempts: Int, now: Long, graceEndsAt: Long?): Long? {
        if (failedAttempts < 1 || failedAttempts > MAX_RETRIES || graceEndsAt == null) return null
        val candidate = now + GAPS_DAYS[failedAttempts - 1].toLong() * SubscriptionTimings.DAY_MS
        return when {
            candidate <= graceEndsAt -> candidate
            candidate - graceEndsAt <= SubscriptionTimings.RETRY_LATENCY_TOLERANCE_MS -> graceEndsAt
            else -> null
        }
    }
}
