package com.panomc.plugins.market.service

import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.impl.MarketThrottleDaoImpl
import com.panomc.plugins.market.error.TooManyRequests
import com.panomc.plugins.market.support.FakeClock
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

/**
 * `ThrottleService` over `market_throttle` on a real MariaDB (MK-152; 11 sections 8.3 and 12.1; the twin of the secret-reveal throttle of
 * A-03): window roll-over, the lock at the threshold, the lock end, the single-statement race, a lock that survives a new pool, subjects cut to
 * 191 characters, the L3 counter (`hit`), the retention purge and the reveal parameters (5 failures in 10 minutes, a 10 minute lock).
 */
class ThrottleServiceIT : MarketDaoITBase() {
    private val clock = FakeClock()
    private val dao = MarketThrottleDaoImpl()

    private fun service(c: FakeClock = clock) = ThrottleService(dao, { pool }, c)

    private val minute = 60_000L

    @Test
    fun `failures below the threshold do not lock, the one that reaches it does, the lock ends by itself`(): Unit = runBlocking {
        val t = service()

        repeat(4) { assertNull(t.fail("COUPON", "ip:203.0.113.5", 5, 15, 15), "failure ${it + 1} of 5") }

        assertNull(t.isLocked("COUPON", "ip:203.0.113.5"))

        val until = t.fail("COUPON", "ip:203.0.113.5", 5, 15, 15)

        assertEquals(clock.now() + 15 * minute, until)
        assertEquals(until, t.isLocked("COUPON", "ip:203.0.113.5"))

        clock.advance(15 * minute - 1)
        assertNotNull(t.isLocked("COUPON", "ip:203.0.113.5"), "one millisecond before the end")

        clock.advance(1)
        assertNull(t.isLocked("COUPON", "ip:203.0.113.5"), "at the end")
        assertEquals(1L, count("market_throttle"))
    }

    @Test
    fun `the window rolls over, old failures do not add to new ones`(): Unit = runBlocking {
        val t = service()

        repeat(4) { t.fail("COUPON", "b:u:7", 5, 15, 15) }
        clock.advance(15 * minute)

        assertNull(t.fail("COUPON", "b:u:7", 5, 15, 15), "the fifth failure falls in a new window and counts as the first")
        assertEquals(1, sql("SELECT `count` FROM `pano_market_throttle` WHERE `subject` = 'b:u:7'").single().getInteger("count"))
        assertNull(t.isLocked("COUPON", "b:u:7"))
    }

    @Test
    fun `a threshold of 1 locks at the first failure and a threshold of 0 writes nothing`(): Unit = runBlocking {
        val t = service()

        assertNotNull(t.fail("COUPON", "b:u:1", 1, 5, 5))
        assertNull(t.fail("COUPON", "b:u:2", 0, 5, 5))
        assertNull(t.fail("COUPON", "b:u:3", -4, 5, 5))
        assertEquals(1L, count("market_throttle"), "only the subject of the first call has a row")
    }

    @Test
    fun `fifty concurrent failures of one subject are all counted in one row and lock it`(): Unit = runBlocking {
        repeat(Race.rounds) {
            sql("DELETE FROM `pano_market_throttle`")

            val t = service()
            val results = Race.run(50) { t.fail("COUPON", "ip:198.51.100.9", 50, 15, 15) }

            assertTrue(results.all { it.isSuccess }, results.filter { it.isFailure }.joinToString { it.exceptionOrNull().toString() })
            assertEquals(1L, count("market_throttle", "`subject` = 'ip:198.51.100.9'"))
            assertEquals(50, sql("SELECT `count` FROM `pano_market_throttle`").single().getInteger("count"))
            assertNotNull(t.isLocked("COUPON", "ip:198.51.100.9"))
        }
    }

    @Test
    fun `a lock survives a new pool`(): Unit = runBlocking {
        service().fail("COUPON", "b:g:steve", 1, 15, 15)

        val other = MarketTestDb.pool(databaseName, 2)

        try {
            val fresh = ThrottleService(MarketThrottleDaoImpl(), { other }, clock)

            assertNotNull(fresh.isLocked("COUPON", "b:g:steve"), "the row, not the process, holds the lock")
            assertNull(fresh.isLocked("COUPON", "b:g:alex"))
        } finally {
            other.close().coAwait()
        }
    }

    @Test
    fun `scopes are independent, reset forgets a subject, several subjects are one query and the latest lock wins`(): Unit = runBlocking {
        val t = service()

        t.fail("COUPON", "ip:1.1.1.1", 1, 10, 10)
        clock.advance(minute)
        t.fail("COUPON", "b:u:5", 1, 10, 10)

        assertNull(t.isLocked("GIFT", "ip:1.1.1.1"), "another scope")
        assertEquals(clock.now() + 10 * minute, t.isLockedAny("COUPON", listOf("ip:9.9.9.9", "ip:1.1.1.1", "b:u:5")), "the later of the two locks")
        assertNull(t.isLockedAny("COUPON", emptyList()))
        assertNull(t.isLockedAny("COUPON", listOf("ip:9.9.9.9")))

        assertTrue(t.reset("COUPON", "b:u:5"))
        assertFalse(t.reset("COUPON", "b:u:5"))
        assertEquals(clock.now() + 9 * minute, t.isLockedAny("COUPON", listOf("ip:1.1.1.1", "b:u:5")))
    }

    @Test
    fun `a subject longer than 191 characters is cut, so two long subjects with the same start are one`(): Unit = runBlocking {
        val t = service()
        val long = "ip:" + "a".repeat(300)

        t.fail("COUPON", long + "1", 2, 10, 10)

        assertNotNull(t.fail("COUPON", long + "2", 2, 10, 10), "the same cut subject, second failure locks")
        assertEquals(1L, count("market_throttle"))
        assertEquals(191, sql("SELECT `subject` FROM `pano_market_throttle`").single().getString("subject").length)
        assertNotNull(t.isLocked("COUPON", long))
    }

    @Test
    fun `hit counts events in a window that starts at the first one and never locks`(): Unit = runBlocking {
        val t = service()
        val hour = 60 * minute

        assertEquals(0, t.countInWindow("CHECKOUT", "ip:203.0.113.5", hour))
        assertNull(t.windowOf("CHECKOUT", "ip:203.0.113.5", hour))

        repeat(20) { n -> assertEquals(n + 1, t.hit("CHECKOUT", "ip:203.0.113.5", hour)) }

        val window = t.windowOf("CHECKOUT", "ip:203.0.113.5", hour)!!

        assertEquals(20, window.first)
        assertEquals(clock.now() + hour, window.second)
        assertNull(t.isLocked("CHECKOUT", "ip:203.0.113.5"), "counting never locks")

        clock.advance(hour - 1)
        assertEquals(20, t.countInWindow("CHECKOUT", "ip:203.0.113.5", hour))

        clock.advance(1)
        assertEquals(0, t.countInWindow("CHECKOUT", "ip:203.0.113.5", hour), "the window has ended")
        assertEquals(1, t.hit("CHECKOUT", "ip:203.0.113.5", hour), "a new window starts at the next event")
    }

    @Test
    fun `purge deletes rows older than two days whose lock is over and keeps the others`(): Unit = runBlocking {
        val t = service()

        t.fail("COUPON", "old", 5, 15, 15)
        t.fail("COUPON", "old-locked", 1, 15, 60 * 24 * 5)
        clock.advance(3 * 86_400_000L)
        t.fail("COUPON", "fresh", 5, 15, 15)

        assertEquals(1, t.purge())
        assertEquals(setOf("old-locked", "fresh"), sql("SELECT `subject` FROM `pano_market_throttle`").map { it.getString("subject") }.toSet())
    }

    @Test
    fun `the reveal parameters of 11 section 8_3, five wrong passwords in ten minutes lock for ten, a correct one resets`(): Unit = runBlocking {
        val t = service()

        // A-03 twin: the first four wrong passwords answer 400, the fifth locks, the next request is 429 before the password is looked at
        repeat(4) { assertNull(t.fail("REVEAL", "u:3", 5, 10, 10)) }

        assertNotNull(t.fail("REVEAL", "u:3", 5, 10, 10))
        assertEquals(clock.now() + 10 * minute, t.isLocked("REVEAL", "u:3"))
        assertEquals(600L, t.retryAfterSeconds(t.isLocked("REVEAL", "u:3")!!))

        clock.advance(10 * minute)
        assertNull(t.isLocked("REVEAL", "u:3"))

        // four wrong, then the right one: the counter starts again
        repeat(4) { t.fail("REVEAL", "u:4", 5, 10, 10) }
        t.reset("REVEAL", "u:4")
        repeat(4) { assertNull(t.fail("REVEAL", "u:4", 5, 10, 10)) }
        assertNull(t.isLocked("REVEAL", "u:4"))
    }

    @Test
    fun `a refusal built from a lock says at least one second`(): Unit = runBlocking {
        val t = service()
        val until = t.fail("COUPON", "x", 1, 1, 1)!!

        clock.advance(minute - 1)

        assertEquals(1L, t.retryAfterSeconds(until))
        assertEquals(1, io.vertx.core.json.JsonObject(TooManyRequests(t.retryAfterSeconds(until)).encode()).getInteger("retryAfter"))
    }
}
