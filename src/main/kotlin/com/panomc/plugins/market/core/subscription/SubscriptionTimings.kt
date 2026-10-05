package com.panomc.plugins.market.core.subscription

/**
 * Fixed durations of the subscription rules (09 sections 8.3, 9.1, 9.4, 11), epoch-millisecond arithmetic. They are
 * plain constants because the spec fixes them; only the grace length is configuration ([SubConfig]).
 */
object SubscriptionTimings {
    const val MINUTE_MS = 60_000L
    const val HOUR_MS = 3_600_000L
    const val DAY_MS = 86_400_000L

    /** Step C: a `MERCHANT` / `GATEWAY` row is left alone this long after its period end (renewal slack, 09 section 11). */
    const val RENEWAL_SLACK_MS = 24 * HOUR_MS

    /** Retry after a technical failure (09 section 9.1). */
    const val TECHNICAL_RETRY_MS = HOUR_MS

    /** Step D waits for an in-flight attempt at most this long after `graceEndsAt` (09 section 9.4). */
    const val PROCESSING_WAIT_CAP_MS = 7 * DAY_MS
}

/** Configuration the state machine needs: `MarketConfig.subscriptionGraceDays` (0 to 60, 09 section 13). */
data class SubConfig(val graceDays: Int = 3) {
    init {
        require(graceDays >= 0) { "graceDays must not be negative" }
    }

    val graceMs: Long get() = graceDays.toLong() * SubscriptionTimings.DAY_MS
}
