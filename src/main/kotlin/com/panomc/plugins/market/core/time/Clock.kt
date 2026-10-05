package com.panomc.plugins.market.core.time

/**
 * The single source of "now" for market code (00 section 9, 17 section 4 S1). Epoch milliseconds UTC.
 * No other class of the plugin may call `System.currentTimeMillis()`, `Instant.now()` or `LocalDate.now()`.
 */
interface Clock {
    fun now(): Long
}

/** Production clock. The only place that reads the system time. */
object SystemClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}
