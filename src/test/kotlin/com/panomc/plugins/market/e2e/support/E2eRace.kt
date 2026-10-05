package com.panomc.plugins.market.e2e.support

import com.panomc.plugins.market.support.HarnessNoConcurrency
import com.panomc.plugins.market.support.Race
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicLongArray

/**
 * The race harness over HTTP (17 section 8.4): `n` virtual buyers, each with its own [E2eClient] (keep-alive, warmed by `GET /api/market/checkout/config`
 * before the gate opens), released together by [Race]. A scenario runs [Race.rounds] rounds; a round whose requests did not overlap (largest
 * start-to-start spread above [MAX_SPREAD_MS]) is repeated, at most three times, and then the scenario fails with [HarnessNoConcurrency].
 */
object E2eRace {
    const val MAX_SPREAD_MS = 250L
    private const val MAX_REPEATS = 3

    /** The outcomes of one round and the spread between the first and the last request start. */
    class Round<T>(val results: List<Result<T>>, val spreadMs: Long) {
        /** Every outcome; a thrown exception of an actor fails the scenario (the actions never throw for an HTTP error status). */
        fun values(): List<T> = results.map { it.getOrThrow() }
    }

    /** [setup] runs per actor before the gate (register, warm up), [action] after it. */
    fun <S, T> round(n: Int, setup: (Int) -> S, action: (S) -> T): Round<T> = runBlocking {
        val starts = AtomicLongArray(n)
        val indexed = Race.runWithSetup(n, { i -> i to setup(i) }) { (i, state) ->
            starts.set(i, System.nanoTime())
            action(state)
        }
        val times = (0 until n).map { starts.get(it) }.filter { it != 0L }
        val spread = if (times.isEmpty()) 0L else (times.max() - times.min()) / 1_000_000L
        Round(indexed, spread)
    }

    /** Runs [body] [rounds] times with fresh fixtures each time; [body] verifies its round and returns it. */
    fun rounds(scenario: String, rounds: Int = Race.rounds, body: (round: Int) -> Round<*>) {
        for (index in 0 until rounds) {
            var attempts = 0
            while (true) {
                val round = body(index)
                if (round.spreadMs <= MAX_SPREAD_MS) break
                if (++attempts > MAX_REPEATS) throw HarnessNoConcurrency(scenario)
                println("e2e race $scenario round $index: spread ${round.spreadMs} ms > $MAX_SPREAD_MS ms, repeating ($attempts)")
            }
        }
    }
}
