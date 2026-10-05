package com.panomc.plugins.market.core.shipping

/** The next poll of a shipment: [nextPollAt] `null` and [stale] `true` once polling stops. */
class NextPoll(val nextPollAt: Long?, val stale: Boolean)

/** Polling cadence of `ShipmentTrackingJob` (10 section 10.2). Pure. */
object TrackingSchedule {
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    /** The first poll, one hour after creation. */
    fun first(createdAt: Long): Long = createdAt + HOUR

    /** `age < 2 d` -> now + 3 h, `< 10 d` -> + 6 h, `< 45 d` -> + 12 h, else stale. */
    fun next(createdAt: Long, now: Long): NextPoll {
        val age = now - createdAt

        return when {
            age < 2 * DAY -> NextPoll(now + 3 * HOUR, false)
            age < 10 * DAY -> NextPoll(now + 6 * HOUR, false)
            age < 45 * DAY -> NextPoll(now + 12 * HOUR, false)
            else -> NextPoll(null, true)
        }
    }
}
