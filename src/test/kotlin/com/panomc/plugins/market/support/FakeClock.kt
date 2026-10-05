package com.panomc.plugins.market.support

import com.panomc.plugins.market.core.time.Clock
import java.util.concurrent.atomic.AtomicLong

/** Test clock (17 section 5.5). Starts at 2025-10-09T08:53:20Z; time moves only through [advance] and [set]. */
class FakeClock(startMs: Long = START_MS) : Clock {
    private val ms = AtomicLong(startMs)

    var nowMs: Long
        get() = ms.get()
        set(value) = ms.set(value)

    override fun now(): Long = ms.get()

    fun advance(deltaMs: Long): Long = ms.addAndGet(deltaMs)

    fun set(valueMs: Long) {
        ms.set(valueMs)
    }

    companion object {
        const val START_MS = 1_760_000_000_000L
    }
}
