package com.panomc.plugins.market.support.selftest

import com.panomc.plugins.market.support.Race
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class RaceSelfTest {
    private val actors = 20

    /** Read, linger, write: with a real start gate every actor reads before any writes. */
    private class RacyCounter {
        @Volatile
        var n = 0

        fun increment() {
            val read = n
            Thread.sleep(2)
            n = read + 1
        }
    }

    @Test
    fun `unsynchronised increments lose updates in at least one of 5 rounds`() = runBlocking {
        var roundsWithLoss = 0
        repeat(Race.rounds) {
            val counter = RacyCounter()
            val results = Race.run(actors) { counter.increment() }
            assertEquals(actors, results.size)
            assertTrue(results.all { it.isSuccess })
            if (counter.n < actors) roundsWithLoss++
        }
        assertTrue(roundsWithLoss >= 1, "the gate produced no overlap in $roundsWithLoss of ${Race.rounds} rounds")
    }

    @Test
    fun `atomic increments never lose one`() = runBlocking {
        repeat(Race.rounds) {
            val counter = AtomicInteger()
            val results = Race.run(actors) { counter.incrementAndGet() }
            assertTrue(results.all { it.isSuccess })
            assertEquals(actors, counter.get())
            assertEquals((1..actors).toSet(), results.map { it.getOrThrow() }.toSet())
        }
    }

    @Test
    fun `all actors are released together after their setup`() = runBlocking {
        val setupDone = AtomicInteger()
        val startedBeforeAllReady = AtomicInteger()
        val results = Race.runWithSetup(
            n = actors,
            setup = { i ->
                Thread.sleep((i % 5) * 10L) // staggered setup
                setupDone.incrementAndGet()
                i
            },
            action = { i ->
                if (setupDone.get() < actors) startedBeforeAllReady.incrementAndGet()
                i
            },
        )
        assertEquals(0, startedBeforeAllReady.get(), "an action started before every setup finished")
        assertEquals((0 until actors).toList(), results.map { it.getOrThrow() })
    }

    @Test
    fun `peak concurrency is the full actor count`() = runBlocking {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        Race.run(actors) {
            val now = running.incrementAndGet()
            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            // stay inside until everyone is inside (a starved machine may start the actors far apart)
            val until = System.nanoTime() + 60_000_000_000L
            while (running.get() < actors && System.nanoTime() < until) Thread.sleep(1)
            running.decrementAndGet()
        }
        assertEquals(actors, peak.get())
    }

    @Test
    fun `failures are returned and never thrown`() = runBlocking {
        val results = Race.run(10) { i -> if (i % 2 == 0) error("boom $i") else i }
        assertEquals(10, results.size)
        assertEquals(5, results.count { it.isFailure })
        assertEquals(5, results.count { it.isSuccess })
        assertEquals("boom 0", results[0].exceptionOrNull()?.message)
    }

    @Test
    fun `a failing setup does not block the other actors`() = runBlocking {
        val ran = AtomicLong()
        val results = Race.runWithSetup(
            n = 6,
            setup = { i -> if (i == 3) error("setup failed") else i },
            action = { ran.incrementAndGet() },
        )
        assertEquals(6, results.size)
        assertTrue(results[3].isFailure)
        assertEquals(5, ran.get())
    }
}
