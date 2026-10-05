package com.panomc.plugins.market.core.abuse

/** One `market_throttle` row (count, window start, lock end; epoch ms). */
data class ThrottleState(val count: Int, val windowStart: Long, val lockedUntil: Long? = null)

/** A throttle subject with the threshold that applies to it. */
data class ThrottleSubject(val subject: String, val threshold: Int)

/**
 * Window and lock arithmetic of `ThrottleService.fail` (11 section 12.1), identical to its single SQL statement:
 * the window rolls over when `windowStart + window <= now` (count restarts at 1), a lock is (re)set to
 * `now + lock` whenever the new count reaches the threshold, and a threshold of 0 disables everything.
 */
object ThrottlePolicy {
    /** The state after one failure at [now]. [state] null = no row yet. Returns [state] unchanged when disabled. */
    fun fail(state: ThrottleState?, now: Long, threshold: Int, windowMinutes: Int, lockMinutes: Int): ThrottleState? {
        if (threshold <= 0) return state
        val lockMs = lockMinutes * 60_000L
        if (state == null) {
            return ThrottleState(1, now, if (threshold <= 1) now + lockMs else null)
        }
        val rolled = state.windowStart + windowMinutes * 60_000L <= now
        val count = if (rolled) 1 else state.count + 1
        val windowStart = if (rolled) now else state.windowStart
        val lockedUntil = if (count >= threshold) now + lockMs else state.lockedUntil
        return ThrottleState(count, windowStart, lockedUntil)
    }

    /** `lockedUntil` when the subject is locked at [now], else null. */
    fun lockedUntil(state: ThrottleState?, now: Long): Long? = state?.lockedUntil?.takeIf { it > now }

    /** `Retry-After` seconds (at least 1) for a lock ending at [lockedUntil]. */
    fun retryAfterSeconds(lockedUntil: Long, now: Long): Int =
        (((lockedUntil - now) + 999) / 1000).coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** Rows older than [AbuseLimits.THROTTLE_RETENTION_DAYS] whose lock is over are deleted by the housekeeping job. */
    fun expired(state: ThrottleState, now: Long): Boolean =
        state.windowStart < now - AbuseLimits.THROTTLE_RETENTION_DAYS * 86_400_000L &&
            (state.lockedUntil == null || state.lockedUntil <= now)

    /** `market_throttle.subject` is at most 191 chars. */
    fun truncate(subject: String): String = if (subject.length > AbuseLimits.MAX_SUBJECT_LENGTH) subject.substring(0, AbuseLimits.MAX_SUBJECT_LENGTH) else subject

    /**
     * Subjects of a code request (12.2): `ip:<bucketKey>` when the IP is trusted, `b:<buyerKey>` when the caller has
     * a session or guest name; neither => the single `anon` subject with `threshold x 10`. Threshold 0 => none.
     */
    fun codeSubjects(ipBucketKey: String?, buyerKey: String?, threshold: Int): List<ThrottleSubject> {
        if (threshold <= 0) return emptyList()
        val out = ArrayList<ThrottleSubject>(2)
        if (ipBucketKey != null) out.add(ThrottleSubject(truncate("ip:$ipBucketKey"), threshold))
        if (!buyerKey.isNullOrEmpty()) out.add(ThrottleSubject(truncate("b:$buyerKey"), threshold))
        if (out.isEmpty()) out.add(ThrottleSubject(AbuseLimits.SUBJECT_ANON, threshold * AbuseLimits.ANON_THRESHOLD_FACTOR))
        return out
    }
}
