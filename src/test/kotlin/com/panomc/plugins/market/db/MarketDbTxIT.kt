package com.panomc.plugins.market.db

import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.MarketBusyException
import com.panomc.plugins.market.support.MarketDbTestBase
import com.panomc.plugins.market.support.MarketTestDb
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLException
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Proves `MarketDb.tx` on a real MariaDB (00 section 8.2 [UNPROVEN], 17 section 11.3 `MarketDbTxIT`): commit,
 * rollback, READ COMMITTED, deadlock retry, bounded lock wait, restored session timeout, no leaked connections.
 */
class MarketDbTxIT : MarketDbTestBase() {
    private val table = "pano_market_zz_tx_probe"

    override suspend fun installSchema() {
        super.installSchema()
        pool.query(
            "CREATE TABLE `$table` (`id` INT NOT NULL, `n` INT NOT NULL DEFAULT 0, `note` VARCHAR(40), PRIMARY KEY (`id`)) ENGINE=InnoDB"
        ).execute().coAwait()
    }

    private suspend fun SqlClient.insert(id: Int, n: Int = 0) {
        preparedQuery("INSERT INTO `$table` (`id`, `n`) VALUES (?, ?)").execute(Tuple.of(id, n)).coAwait()
    }

    private suspend fun SqlClient.n(id: Int): Int? =
        preparedQuery("SELECT `n` FROM `$table` WHERE `id` = ?").execute(Tuple.of(id)).coAwait().firstOrNull()?.getInteger("n")

    private suspend fun rows(): Long = sql("SELECT COUNT(*) AS c FROM `$table`").first().getLong("c")

    @Test
    fun `commit persists every statement`(): Unit = runBlocking {
        val result = marketDb().tx { conn ->
            conn.insert(1, 10)
            conn.insert(2, 20)
            "done"
        }
        assertEquals("done", result)
        assertEquals(2L, rows())
        assertEquals(10, pool.n(1))
        assertEquals(20, pool.n(2))
    }

    @Test
    fun `a throwable rolls back all statements and is rethrown unchanged`(): Unit = runBlocking {
        val boom = IllegalStateException("boom")
        val attempts = AtomicInteger()
        val thrown = runCatching {
            marketDb().tx { conn ->
                attempts.incrementAndGet()
                conn.insert(1)
                conn.insert(2)
                throw boom
            }
        }.exceptionOrNull()
        assertTrue(thrown === boom, "the very same exception comes out: $thrown")
        assertEquals(1, attempts.get(), "a plain failure is never retried")
        assertEquals(0L, rows())
    }

    @Test
    fun `an SQL error in the block rolls back, is not retried and surfaces as the driver exception`(): Unit = runBlocking {
        val attempts = AtomicInteger()
        val thrown = runCatching {
            marketDb().tx { conn ->
                attempts.incrementAndGet()
                conn.insert(1)
                conn.insert(1) // duplicate key
            }
        }.exceptionOrNull()
        assertTrue(thrown is MySQLException && thrown.errorCode == 1062, "duplicate key expected, got $thrown")
        assertEquals(1, attempts.get())
        assertEquals(0L, rows())
    }

    @Test
    fun `a failed statement rolls back only itself, the block may carry on`(): Unit = runBlocking {
        // Statement-level rollback of InnoDB: the duplicate insert fails, the transaction stays open.
        marketDb().tx { conn ->
            conn.insert(1)
            runCatching { conn.insert(1) }
            conn.insert(2)
        }
        assertEquals(2L, rows())
    }

    @Test
    fun `a transaction that ended inside the block fails loudly instead of committing`(): Unit = runBlocking {
        val attempts = AtomicInteger()
        val thrown = runCatching {
            marketDb().tx { conn ->
                attempts.incrementAndGet()
                conn.insert(1)
                // What a deadlock victim looks like to the block: the server has rolled everything back and
                // the next statement runs in autocommit mode.
                conn.query("ROLLBACK").execute().coAwait()
                conn.insert(2)
            }
        }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException, "expected IllegalStateException, got $thrown")
        assertTrue(thrown!!.message!!.contains("ended inside the block"), thrown.message)
        assertEquals(1, attempts.get(), "not retried: the autocommitted statement cannot be undone")
        assertEquals(1L, rows(), "only the autocommitted insert remains; the rolled back one is gone")
        assertEquals(null, pool.n(1))
    }

    @Test
    fun `the transaction runs at READ COMMITTED`(): Unit = runBlocking {
        pool.insert(1, 1)

        val seenByTx = marketDb().tx { conn ->
            val first = conn.n(1)
            pool.query("UPDATE `$table` SET `n` = 2 WHERE `id` = 1").execute().coAwait() // another connection commits
            first to conn.n(1)
        }
        assertEquals(1 to 2, seenByTx, "a second read inside the transaction sees the committed change")

        // Control: a plain InnoDB transaction (REPEATABLE READ) would not, so the check above can tell the levels apart.
        val raw = pool.connection.coAwait()
        try {
            val tx = raw.begin().coAwait()
            val first = raw.n(1)
            pool.query("UPDATE `$table` SET `n` = 3 WHERE `id` = 1").execute().coAwait()
            assertEquals(first to first, first to raw.n(1))
            tx.rollback().coAwait()
        } finally {
            raw.close().coAwait()
        }
    }

    @Test
    fun `nested DAO calls share the connection and roll back together`(): Unit = runBlocking {
        suspend fun daoOne(c: SqlClient) = c.insert(1, 11)
        suspend fun daoTwo(c: SqlClient): Int? {
            c.insert(2, 22)
            return c.n(1) // sees the uncommitted row of the first DAO call: same connection
        }

        val seen = marketDb().tx { conn -> daoOne(conn); daoTwo(conn) }
        assertEquals(11, seen)
        assertEquals(2L, rows())

        runCatching {
            marketDb().tx { conn ->
                c3(conn)
                throw IllegalStateException("after the nested calls")
            }
        }
        assertEquals(2L, rows(), "the nested inserts of the failed transaction are gone")
    }

    private suspend fun c3(conn: SqlConnection) {
        conn.insert(3)
        conn.insert(4)
    }

    @Test
    fun `opposite order deadlock is retried and both transactions finally succeed`(): Unit = runBlocking {
        pool.insert(1)
        pool.insert(2)
        val db = marketDb(lockWaitSeconds = 20)
        val attemptsA = AtomicInteger()
        val attemptsB = AtomicInteger()
        val aLocked = CompletableDeferred<Unit>()
        val bLocked = CompletableDeferred<Unit>()

        val a = async(Dispatchers.IO) {
            db.tx { conn ->
                val attempt = attemptsA.incrementAndGet()
                conn.query("SELECT `id` FROM `$table` WHERE `id` = 1 FOR UPDATE").execute().coAwait()
                if (attempt == 1) { aLocked.complete(Unit); bLocked.await() }
                conn.query("UPDATE `$table` SET `n` = `n` + 1 WHERE `id` = 2").execute().coAwait()
                "A"
            }
        }
        val b = async(Dispatchers.IO) {
            db.tx { conn ->
                val attempt = attemptsB.incrementAndGet()
                conn.query("SELECT `id` FROM `$table` WHERE `id` = 2 FOR UPDATE").execute().coAwait()
                if (attempt == 1) { bLocked.complete(Unit); aLocked.await() }
                conn.query("UPDATE `$table` SET `n` = `n` + 1 WHERE `id` = 1").execute().coAwait()
                "B"
            }
        }

        assertEquals(listOf("A", "B"), awaitAll(a, b))
        assertEquals(3, attemptsA.get() + attemptsB.get(), "exactly one victim, retried once")
        assertEquals(1, pool.n(1), "each row was incremented exactly once: the victim's first attempt rolled back")
        assertEquals(1, pool.n(2))
    }

    @Test
    fun `a lock wait is retried three times and then surfaces as MarketBusyException`(): Unit = runBlocking {
        pool.insert(1)
        val holder = pool.connection.coAwait()
        try {
            val holding = holder.begin().coAwait()
            holder.query("SELECT `id` FROM `$table` WHERE `id` = 1 FOR UPDATE").execute().coAwait()

            val attempts = AtomicInteger()
            val started = System.nanoTime()
            val thrown = runCatching {
                marketDb(lockWaitSeconds = 1).tx { conn ->
                    attempts.incrementAndGet()
                    conn.query("UPDATE `$table` SET `n` = `n` + 1 WHERE `id` = 1").execute().coAwait()
                }
            }.exceptionOrNull()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertTrue(thrown is MarketBusyException, "MarketBusyException expected, got $thrown")
            thrown as MarketBusyException
            assertEquals(3, thrown.attempts)
            assertEquals(3, attempts.get(), "the block ran at most three times")
            val cause = thrown.cause
            assertTrue(cause is MySQLException && cause.errorCode == 1205, "cause is the lock wait timeout: $cause")
            assertTrue(elapsedMs in 2_500..9_000, "about three seconds, not the server default of 150: $elapsedMs ms")
            assertEquals(0, pool.n(1), "nothing was written")
            holding.rollback().coAwait()
        } finally {
            holder.close().coAwait()
        }
    }

    @Test
    fun `the session lock timeout and isolation of the pooled connection are restored`(): Unit = runBlocking {
        val solo = MarketTestDb.pool(databaseName, maxSize = 1, connectionTimeoutMs = 5_000)
        try {
            val soloDb = MarketDb({ solo }, SystemClock, 1)

            suspend fun sessionTimeout() =
                solo.query("SELECT @@SESSION.innodb_lock_wait_timeout AS v").execute().coAwait().first().getLong("v")

            suspend fun sessionIsolation() =
                solo.query("SELECT @@SESSION.transaction_isolation AS v").execute().coAwait().first().getString("v")

            solo.query("SET SESSION innodb_lock_wait_timeout = 42").execute().coAwait()
            val isolationBefore = sessionIsolation()
            assertEquals(42L, sessionTimeout())

            val inside = soloDb.tx { conn ->
                conn.query("SELECT @@SESSION.innodb_lock_wait_timeout AS v").execute().coAwait().first().getLong("v")
            }
            assertEquals(1L, inside, "the transaction runs with its own lock wait")
            assertEquals(42L, sessionTimeout(), "restored after success (same pooled connection: pool size 1)")

            runCatching { soloDb.tx { conn -> conn.insert(1); error("fail") } }
            assertEquals(42L, sessionTimeout(), "restored after a failure")
            assertEquals(isolationBefore, sessionIsolation(), "the session isolation level was never changed")

            // A plain transaction on that connection afterwards is not READ COMMITTED by leftover state.
            pool.insert(5, 1)
            val conn = solo.connection.coAwait()
            try {
                val tx = conn.begin().coAwait()
                val first = conn.n(5)
                pool.query("UPDATE `$table` SET `n` = 9 WHERE `id` = 5").execute().coAwait()
                assertEquals(first, conn.n(5), "REPEATABLE READ again: no READ COMMITTED left over")
                tx.rollback().coAwait()
            } finally {
                conn.close().coAwait()
            }
        } finally {
            solo.close().coAwait()
        }
    }

    @Test
    fun `a pool of two survives fifty failing transactions`(): Unit = runBlocking {
        val small = MarketTestDb.pool(databaseName, maxSize = 2, connectionTimeoutMs = 3_000)
        try {
            val smallDb = MarketDb({ small }, SystemClock, 1)
            repeat(50) { i ->
                when (i % 3) {
                    0 -> runCatching { smallDb.tx { conn -> conn.insert(1); throw IllegalStateException("fail $i") } }
                    1 -> runCatching { smallDb.tx { conn -> conn.insert(1); conn.insert(1) } }
                    else -> {
                        val inside = CompletableDeferred<Unit>()
                        val job = launch(Dispatchers.IO) {
                            smallDb.tx { conn ->
                                conn.insert(1)
                                inside.complete(Unit)
                                awaitCancellation()
                            }
                        }
                        inside.await()
                        job.cancelAndJoin()
                    }
                }
            }
            assertEquals(0L, rows(), "every failed transaction rolled back")

            // Both connections are free again: two transactions that overlap on the two connections both finish
            // inside the 3 s connection timeout. A leaked connection would starve the second one.
            val overlap = (1..2).map {
                async(Dispatchers.IO) {
                    smallDb.tx { conn -> conn.query("SELECT SLEEP(0.4) AS s").execute().coAwait().first().getInteger("s") }
                }
            }
            assertEquals(listOf(0, 0), overlap.awaitAll())
        } finally {
            small.close().coAwait()
        }
    }

    @Test
    fun `cancellation rolls back, releases the row lock and returns the connection`(): Unit = runBlocking {
        pool.insert(1)
        val inside = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            marketDb().tx { conn ->
                conn.query("UPDATE `$table` SET `n` = 99 WHERE `id` = 1").execute().coAwait()
                inside.complete(Unit)
                awaitCancellation()
            }
        }
        inside.await()
        job.cancelAndJoin()

        assertEquals(0, pool.n(1), "the update of the cancelled transaction is gone")
        // The row lock is free at once: a transaction with a 1 s lock wait gets through without a retry.
        val attempts = AtomicInteger()
        marketDb(lockWaitSeconds = 1).tx { conn ->
            attempts.incrementAndGet()
            conn.query("UPDATE `$table` SET `n` = 5 WHERE `id` = 1").execute().coAwait()
        }
        assertEquals(1, attempts.get())
        assertEquals(5, pool.n(1))
    }
}
