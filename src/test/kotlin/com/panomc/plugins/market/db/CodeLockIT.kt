package com.panomc.plugins.market.db

import com.panomc.plugins.market.support.MarketDbTestBase
import com.panomc.plugins.market.support.Race
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `CodeLockIT` (17 section 11.3): `GET_LOCK('market_code', 5)` serialises concurrent creates of the same code. Code
 * creation (coupon, creator code, gift: one namespace, 11 section on abuse) is "look it up, then insert", which two
 * requests can both pass; the named lock turns that into one winner and clean "taken" answers for the others instead
 * of a unique-key error for the loser. The lock is per connection, so every contender works on its own pooled
 * connection, as the service will.
 */
class CodeLockIT : MarketDbTestBase() {
    private enum class Outcome { CREATED, TAKEN }

    private suspend fun <T> withConnection(block: suspend (SqlConnection) -> T): T {
        val connection = pool.connection.coAwait()
        try {
            return block(connection)
        } finally {
            runCatching { connection.close().coAwait() }
        }
    }

    private suspend fun SqlConnection.getLock(seconds: Int): Boolean =
        preparedQuery("SELECT GET_LOCK('market_code', ?) AS l").execute(Tuple.of(seconds)).coAwait().first().getInteger("l") == 1

    private suspend fun SqlConnection.releaseLock() {
        preparedQuery("SELECT RELEASE_LOCK('market_code')").execute().coAwait()
    }

    /** Look up, a pause that makes the unsynchronised race certain, then insert; [locked] wraps it in the named lock. */
    private suspend fun create(code: String, locked: Boolean): Outcome = withConnection { c ->
        if (locked) assertTrue(c.getLock(5), "the lock is granted within 5 seconds")
        try {
            val exists = c.preparedQuery("SELECT `id` FROM `pano_market_coupon` WHERE `code` = ?").execute(Tuple.of(code)).coAwait().size() > 0
            delay(100)
            if (exists) Outcome.TAKEN
            else {
                c.preparedQuery(
                    "INSERT INTO `pano_market_coupon` (`name`, `code`, `discount`, `createdAt`, `updatedAt`) VALUES (?, ?, 1000, 1, 1)"
                ).execute(Tuple.of(code, code)).coAwait()
                Outcome.CREATED
            }
        } finally {
            if (locked) c.releaseLock()
        }
    }

    @Test
    fun `three concurrent creates of one code with the lock give one winner and two clean answers`(): Unit = runBlocking {
        repeat(Race.rounds) { round ->
            val code = "LOCKED$round"
            val results = Race.run(3) { create(code, locked = true) }
            assertTrue(results.all { it.isSuccess }, results.toString())
            val outcomes = results.map { it.getOrThrow() }
            assertEquals(1, outcomes.count { it == Outcome.CREATED }, outcomes.toString())
            assertEquals(2, outcomes.count { it == Outcome.TAKEN }, outcomes.toString())
            assertEquals(1L, count("market_coupon", "`code` = ?", code))
        }
    }

    @Test
    fun `without the lock the same race reaches the unique index, so the lock is what keeps it clean`(): Unit = runBlocking {
        val results = Race.run(3) { create("UNLOCKED", locked = false) }
        // all three passed the lookup before the first insert: one row, and the others failed on the unique key
        assertEquals(1L, count("market_coupon", "`code` = 'UNLOCKED'"))
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(2, results.count { it.isFailure })
    }

    @Test
    fun `creates of different codes all succeed under the one global lock`(): Unit = runBlocking {
        val results = Race.run(3) { i -> create("OTHER$i", locked = true) }
        assertTrue(results.all { it.getOrThrow() == Outcome.CREATED })
        assertEquals(3L, count("market_coupon"))
    }

    @Test
    fun `a contender gives up after its timeout while the lock is held and gets it once it is released`(): Unit = runBlocking {
        withConnection { holder ->
            assertTrue(holder.getLock(5))
            withConnection { other ->
                val started = System.currentTimeMillis()
                assertEquals(false, other.getLock(1), "GET_LOCK answers 0 after its timeout")
                assertTrue(System.currentTimeMillis() - started >= 900, "it really waited")
            }
            holder.releaseLock()
            withConnection { other ->
                assertTrue(other.getLock(1))
                other.releaseLock()
            }
        }
    }
}
