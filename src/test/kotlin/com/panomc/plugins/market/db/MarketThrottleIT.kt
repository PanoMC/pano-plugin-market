package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.impl.MarketThrottleDaoImpl
import com.panomc.plugins.market.db.model.ThrottleScope
import com.panomc.plugins.market.support.MarketTestDb
import com.panomc.plugins.market.support.Race
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `market_throttle` and its single upsert (01 section 12, 11 section 12.1). */
class MarketThrottleIT : MarketDaoITBase() {
    private val throttle = MarketThrottleDaoImpl()
    private val minute = 60_000L

    private suspend fun fail(subject: String, now: Long, threshold: Int = 3, window: Long = 10 * minute, lock: Long = 5 * minute, scope: String = ThrottleScope.COUPON) =
        throttle.fail(scope, subject, threshold, window, lock, now, pool)

    @Test
    fun `the upsert counts, locks at the threshold and keeps the lock`(): Unit = runBlocking {
        assertNull(fail("ip:1", 1000))
        assertNull(fail("ip:1", 2000))
        assertEquals(2, throttle.get(ThrottleScope.COUPON, "ip:1", pool)!!.count)
        val lockedUntil = fail("ip:1", 3000)
        assertEquals(3000 + 5 * minute, lockedUntil)
        val row = throttle.get(ThrottleScope.COUPON, "ip:1", pool)!!
        assertEquals(listOf<Any?>(3, 1000L, lockedUntil), listOf(row.count, row.windowStart, row.lockedUntil))
        assertEquals(1L, count("market_throttle"))
        assertEquals(lockedUntil, throttle.lockedUntil(ThrottleScope.COUPON, "ip:1", 4000, pool))
        assertNull(throttle.lockedUntil(ThrottleScope.COUPON, "ip:1", 3000 + 5 * minute, pool), "the lock has ended")
        assertNull(throttle.lockedUntil(ThrottleScope.GIFT, "ip:1", 4000, pool), "another scope")
    }

    @Test
    fun `a threshold of one locks on the first failure`(): Unit = runBlocking {
        assertEquals(500 + 5 * minute, fail("anon", 500, threshold = 1))
        assertEquals(1, throttle.get(ThrottleScope.COUPON, "anon", pool)!!.count)
    }

    @Test
    fun `a window that ran out restarts the count`(): Unit = runBlocking {
        fail("b:1", 0)
        fail("b:1", 1000)
        assertNull(fail("b:1", 10 * minute)) // window of 10 minutes is over: count 1, window restarts
        val row = throttle.get(ThrottleScope.COUPON, "b:1", pool)!!
        assertEquals(listOf<Any?>(1, 10 * minute), listOf(row.count, row.windowStart))
        assertNull(fail("b:1", 10 * minute + 1))
        assertNotNull(fail("b:1", 10 * minute + 2))
    }

    @Test
    fun `concurrent failures are all counted by the one statement`(): Unit = runBlocking {
        val results = Race.run(12) { fail("ip:race", 1000, threshold = 12) }.map { it.getOrThrow() }
        assertEquals(12, throttle.get(ThrottleScope.COUPON, "ip:race", pool)!!.count)
        assertEquals(1L, count("market_throttle"))
        assertTrue(results.any { it != null })
        assertNotNull(throttle.lockedUntil(ThrottleScope.COUPON, "ip:race", 2000, pool))
    }

    @Test
    fun `scope and subject are the key and a long subject is truncated to 191`(): Unit = runBlocking {
        fail("s", 1)
        fail("s", 1, scope = ThrottleScope.GIFT)
        fail("t", 1)
        assertEquals(3L, count("market_throttle"))
        val long = "x".repeat(300)
        fail(long, 1)
        fail(long, 2)
        assertEquals(4L, count("market_throttle"))
        assertEquals(2, throttle.get(ThrottleScope.COUPON, long, pool)!!.count)
        assertEquals(191, sql("SELECT `subject` FROM `pano_market_throttle` WHERE `subject` LIKE 'xxx%'").single().getString("subject").length)
        val failure = runCatching {
            sql("INSERT INTO `pano_market_throttle` (`scope`, `subject`, `windowStart`, `createdAt`, `updatedAt`) VALUES ('COUPON', 's', 1, 1, 1)")
        }.exceptionOrNull()
        assertNotNull(failure)
    }

    @Test
    fun `lockedUntilAny answers the latest lock over several subjects with one query`(): Unit = runBlocking {
        fail("a", 1000, threshold = 1, lock = 1 * minute)
        fail("b", 1000, threshold = 1, lock = 9 * minute)
        fail("c", 1000, threshold = 5)
        assertEquals(1000 + 9 * minute, throttle.lockedUntilAny(ThrottleScope.COUPON, listOf("a", "b", "c", "zzz"), 2000, pool))
        assertEquals(1000 + 1 * minute, throttle.lockedUntilAny(ThrottleScope.COUPON, listOf("a", "c"), 2000, pool))
        assertNull(throttle.lockedUntilAny(ThrottleScope.COUPON, listOf("c", "zzz"), 2000, pool))
        assertNull(throttle.lockedUntilAny(ThrottleScope.COUPON, listOf("a", "b"), 1000 + 9 * minute, pool))
        assertNull(throttle.lockedUntilAny(ThrottleScope.COUPON, emptyList(), 2000, pool))
    }

    @Test
    fun `reset deletes the row and purge keeps live locks and recent windows`(): Unit = runBlocking {
        fail("r", 1000)
        assertTrue(throttle.reset(ThrottleScope.COUPON, "r", pool))
        assertFalse(throttle.reset(ThrottleScope.COUPON, "r", pool))
        assertNull(throttle.get(ThrottleScope.COUPON, "r", pool))

        fail("old", 1000)                                  // old window, no lock: purged
        fail("oldlocked", 1000, threshold = 1, lock = 100 * minute) // old window, lock still running: kept
        fail("lockended", 1000, threshold = 1, lock = minute) // old window, lock ended: purged
        fail("recent", 10_000_000)                         // recent window: kept
        assertEquals(2, throttle.purge(5_000_000, 5_500_000, pool))
        assertEquals(setOf("oldlocked", "recent"), sql("SELECT `subject` FROM `pano_market_throttle`").map { it.getString("subject") }.toSet())
    }

    @Test
    fun `the upsert survives a new pool`(): Unit = runBlocking {
        val first = MarketTestDb.pool(databaseName, 2)
        val locked: Long
        try {
            throttle.fail(ThrottleScope.COUPON, "durable", 3, 10 * minute, 5 * minute, 1000, first)
            throttle.fail(ThrottleScope.COUPON, "durable", 3, 10 * minute, 5 * minute, 2000, first)
            locked = throttle.fail(ThrottleScope.COUPON, "durable", 3, 10 * minute, 5 * minute, 3000, first)!!
        } finally {
            first.close().coAwait()
        }
        val second = MarketTestDb.pool(databaseName, 2)
        try {
            val row = throttle.get(ThrottleScope.COUPON, "durable", second)!!
            assertEquals(listOf<Any?>(3, locked), listOf(row.count, row.lockedUntil))
            assertEquals(locked, throttle.lockedUntil(ThrottleScope.COUPON, "durable", 4000, second))
            // it keeps counting on the new pool: still one row
            throttle.fail(ThrottleScope.COUPON, "durable", 3, 10 * minute, 5 * minute, 5000, second)
            assertEquals(4, throttle.get(ThrottleScope.COUPON, "durable", second)!!.count)
            assertEquals(1L, MarketTestDb.count(second, "market_throttle"))
        } finally {
            second.close().coAwait()
        }
    }
}
