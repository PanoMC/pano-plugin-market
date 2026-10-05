package com.panomc.plugins.market.support

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicInteger

/** Thrown when a race could not be produced (for example a single-slot executor), see 17 section 14. */
class HarnessNoConcurrency(scenario: String) : AssertionError("no concurrency in scenario: $scenario")

/**
 * Race harness (17 section 5.5): launches `n` coroutines on [Dispatchers.IO]; each one first does its own
 * setup, then waits at one shared start gate that opens only when all `n` have arrived, so the actions begin
 * together. Every outcome is returned; nothing is thrown.
 */
object Race {
    /** Every race test repeats this many times with fresh fixtures. */
    const val rounds = 5

    /** `setup` runs before the gate (in parallel), `action` after it. */
    suspend fun <S, T> runWithSetup(n: Int, setup: suspend (i: Int) -> S, action: suspend (state: S) -> T): List<Result<T>> {
        require(n > 0) { "n must be positive" }
        val gate = CompletableDeferred<Unit>()
        val arrived = AtomicInteger(0)
        return coroutineScope {
            val jobs = List(n) { i ->
                async(Dispatchers.IO) {
                    val state = try {
                        setup(i)
                    } catch (e: Throwable) {
                        // A failed setup must not leave the others waiting for ever.
                        if (arrived.incrementAndGet() == n) gate.complete(Unit)
                        return@async Result.failure<T>(e)
                    }
                    if (arrived.incrementAndGet() == n) gate.complete(Unit)
                    gate.await()
                    runCatching { action(state) }
                }
            }
            jobs.map { it.await() }
        }
    }

    /** Shorthand without a setup phase: `block(i)` is released together with the other n - 1. */
    suspend fun <T> run(n: Int, block: suspend (i: Int) -> T): List<Result<T>> =
        runWithSetup(n, { it }, block)
}
