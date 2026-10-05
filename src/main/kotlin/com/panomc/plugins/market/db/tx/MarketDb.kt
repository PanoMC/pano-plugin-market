package com.panomc.plugins.market.db.tx

import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.error.MarketBusyException
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLException
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Transaction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.random.Random

/**
 * The one transaction helper of the market plugin (00 section 8.2). `pool` is a supplier so production can pass
 * `{ databaseManager.getSqlClient() as Pool }` and tests the pool of a throwaway database; `clock` times the
 * transactions for the retry log.
 *
 * Rules for callers: the block contains database work only (no network call, no file write, no other
 * `MarketDb.tx`: transactions do not nest), it is re-executed from the start on a deadlock or a lock wait timeout so
 * it must have no side effect outside the database, and it must not swallow an SQL exception and carry on (a failed
 * statement rolls back only itself; a deadlock victim loses the whole transaction, see [tx]).
 */
class MarketDb(
    private val pool: suspend () -> Pool,
    private val clock: Clock,
    private val lockWaitSeconds: Int = LOCK_WAIT_SECONDS
) {
    init {
        require(lockWaitSeconds in 1..MAX_LOCK_WAIT_SECONDS) { "lockWaitSeconds must be 1..$MAX_LOCK_WAIT_SECONDS" }
    }

    /**
     * Runs [block] on one pooled connection inside BEGIN / COMMIT at READ COMMITTED and returns its result. Any
     * throwable rolls back; the connection always goes back to the pool (also on cancellation) with its previous
     * `innodb_lock_wait_timeout` restored. A deadlock (1213) or lock wait timeout (1205) re-runs [block] from the
     * start on a fresh connection, [MAX_ATTEMPTS] attempts in all, then [MarketBusyException] is thrown. Every other
     * failure, `CancellationException` included, propagates at once. A block that returns after the server ended
     * its transaction (a swallowed deadlock) fails with `IllegalStateException` instead of committing.
     */
    suspend fun <T> tx(block: suspend (SqlConnection) -> T): T {
        var last: Throwable? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            val started = clock.now()
            try {
                return runOnce(block)
            } catch (e: Throwable) {
                val code = retryableErrorCode(e) ?: throw e
                last = e
                logger.warn(
                    "transaction attempt {}/{} failed with MariaDB error {} after {} ms",
                    attempt, MAX_ATTEMPTS, code, clock.now() - started
                )
                // A deadlock victim restarts at once and would meet the same peer again: a short random pause
                // desynchronises the two. A lock wait timeout has already waited long enough.
                if (attempt < MAX_ATTEMPTS && code == DEADLOCK) delay(Random.nextLong(5, 30))
            }
        }
        throw MarketBusyException(MAX_ATTEMPTS, last)
    }

    private suspend fun <T> runOnce(block: suspend (SqlConnection) -> T): T {
        val conn = pool().connection.coAwait()
        var previousTimeout: Long? = null
        var isolationPending = false
        var transaction: Transaction? = null
        try {
            previousTimeout = conn.query("SELECT @@SESSION.innodb_lock_wait_timeout AS v").execute().coAwait()
                .first().getLong("v")
            conn.query("SET SESSION innodb_lock_wait_timeout = $lockWaitSeconds").execute().coAwait()
            conn.query("SET TRANSACTION ISOLATION LEVEL READ COMMITTED").execute().coAwait()
            isolationPending = true
            transaction = conn.begin().coAwait()
            isolationPending = false
            val result = block(conn)
            // The server ends a transaction on its own when it picks this one as a deadlock victim; a block that
            // swallowed that error carried on in autocommit mode and a COMMIT would then "succeed". Fail loudly.
            val open = runCatching {
                conn.query("SELECT @@in_transaction AS open").execute().coAwait().first().getValue("open") as Number
            }.getOrNull()?.toLong()
            check(open != 0L) {
                "the transaction ended inside the block (rolled back by the server or committed by the block)"
            }
            transaction.commit().coAwait()
            transaction = null
            return result
        } catch (e: Throwable) {
            val pending = transaction
            if (pending != null) withContext(NonCancellable) { runCatching { pending.rollback().coAwait() } }
            throw e
        } finally {
            withContext(NonCancellable) {
                if (isolationPending) {
                    // SET TRANSACTION applies to the next transaction on this connection: spend it here so it
                    // cannot leak into the platform code that borrows the connection next.
                    runCatching {
                        conn.query("START TRANSACTION").execute().coAwait()
                        conn.query("ROLLBACK").execute().coAwait()
                    }
                }
                if (previousTimeout != null) {
                    runCatching {
                        conn.query("SET SESSION innodb_lock_wait_timeout = $previousTimeout").execute().coAwait()
                    }
                }
                runCatching { conn.close().coAwait() }
            }
        }
    }

    companion object {
        const val LOCK_WAIT_SECONDS = 5

        /** Total attempts of one [tx] call (the first run plus two re-runs). */
        const val MAX_ATTEMPTS = 3

        private const val MAX_LOCK_WAIT_SECONDS = 3600
        private const val DEADLOCK = 1213
        private const val LOCK_WAIT_TIMEOUT = 1205
        private val logger = LoggerFactory.getLogger(MarketDb::class.java)

        /** The MariaDB error code (1213 or 1205) somewhere in the cause chain of [e], or `null`. */
        internal fun retryableErrorCode(e: Throwable): Int? {
            var current: Throwable? = e
            var depth = 0
            while (current != null && depth++ < 16) {
                if (current is MySQLException && (current.errorCode == DEADLOCK || current.errorCode == LOCK_WAIT_TIMEOUT)) {
                    return current.errorCode
                }
                current = current.cause
            }
            return null
        }
    }
}
