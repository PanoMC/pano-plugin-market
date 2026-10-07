package com.panomc.plugins.market.support

import kotlinx.coroutines.delay

/** Thrown by [Await] when the condition did not hold before the deadline (17 section 14). */
class AwaitTimeout(val description: String, val lastValue: Any?) :
    AssertionError("Await timed out: $description (last value: $lastValue)")

/**
 * Polling with a deadline: tests never sleep for a fixed time (17 section 1). The condition is evaluated at least
 * once, also when `timeoutMs` is 0.
 */
object Await {
    /** Polls never wait longer than this between two looks, whatever the caller asked for. */
    private const val MAX_STEP_MS = 100L

    fun until(timeoutMs: Long = 10_000, stepMs: Long = 50, description: String = "condition", condition: () -> Boolean) {
        untilValue<Boolean>(timeoutMs, stepMs, description) { condition().takeIf { it } }
    }

    /** Polls until [block] returns a non-null value and returns it. */
    fun <T : Any> untilValue(timeoutMs: Long = 10_000, stepMs: Long = 50, description: String = "value", block: () -> T?): T {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var last: Any? = null
        while (true) {
            val v = try {
                block()
            } catch (e: Throwable) {
                last = e
                null
            }
            if (v != null) return v
            if (System.nanoTime() >= deadline) throw AwaitTimeout(description, last)
            Thread.sleep(minOf(stepMs, MAX_STEP_MS))
        }
    }

    suspend fun untilSuspending(timeoutMs: Long = 10_000, stepMs: Long = 50, description: String = "condition", condition: suspend () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var last: Any? = null
        while (true) {
            val ok = try {
                condition()
            } catch (e: Throwable) {
                last = e
                false
            }
            if (ok) return
            if (System.nanoTime() >= deadline) throw AwaitTimeout(description, last)
            delay(minOf(stepMs, MAX_STEP_MS))
        }
    }
}
