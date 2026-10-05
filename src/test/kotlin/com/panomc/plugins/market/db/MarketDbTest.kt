package com.panomc.plugins.market.db

import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.MarketBusyException
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.mysqlclient.MySQLException
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

/** Parts of [MarketDb] that need no database: error classification, argument validation, the exception type. */
class MarketDbTest {
    @Test
    fun `deadlock and lock wait timeout are retryable, found anywhere in the cause chain`() {
        assertEquals(1213, MarketDb.retryableErrorCode(MySQLException("Deadlock found", 1213, "40001")))
        assertEquals(1205, MarketDb.retryableErrorCode(MySQLException("Lock wait timeout exceeded", 1205, "HY000")))
        val wrapped = RuntimeException("dao failed", IllegalStateException("outer", MySQLException("Deadlock found", 1213, "40001")))
        assertEquals(1213, MarketDb.retryableErrorCode(wrapped))
    }

    @Test
    fun `every other failure is final`() {
        assertNull(MarketDb.retryableErrorCode(MySQLException("Duplicate entry", 1062, "23000")))
        assertNull(MarketDb.retryableErrorCode(MySQLException("Lost connection", 2013, "HY000")))
        assertNull(MarketDb.retryableErrorCode(IllegalStateException("boom")))
        assertNull(MarketDb.retryableErrorCode(CancellationException("cancelled")))
        assertNull(MarketDb.retryableErrorCode(MarketBusyException(3, null)))
    }

    @Test
    fun `a cyclic cause chain terminates`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertNull(MarketDb.retryableErrorCode(a))
    }

    @Test
    fun `the lock wait must stay in a sane range`() {
        val supplier: suspend () -> io.vertx.sqlclient.Pool = { error("no pool in this test") }
        assertThrows<IllegalArgumentException> { MarketDb(supplier, SystemClock, 0) }
        assertThrows<IllegalArgumentException> { MarketDb(supplier, SystemClock, -1) }
        assertThrows<IllegalArgumentException> { MarketDb(supplier, SystemClock, 3601) }
        MarketDb(supplier, SystemClock, 1)
        MarketDb(supplier, SystemClock)
        assertEquals(5, MarketDb.LOCK_WAIT_SECONDS)
        assertEquals(3, MarketDb.MAX_ATTEMPTS)
    }

    @Test
    fun `MarketBusyException carries the attempts and the last database error`() {
        val cause = MySQLException("Lock wait timeout exceeded", 1205, "HY000")
        val e = MarketBusyException(3, cause)
        assertEquals(3, e.attempts)
        assertSame(cause, e.cause)
        assertTrue(e.message!!.contains("3 times"))
    }

    /** A [Pool] whose only working call is `getConnection()`, answered with the future the test controls. */
    private fun poolAnswering(connection: () -> Future<SqlConnection>): Pool =
        Proxy.newProxyInstance(Pool::class.java.classLoader, arrayOf(Pool::class.java)) { proxy, method, args ->
            when (method.name) {
                "getConnection" -> connection()
                "toString" -> "FakePool"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> throw UnsupportedOperationException(method.name)
            }
        } as Pool

    /** A [SqlConnection] that only counts `close()`; any other call is a failure of the test. */
    private class CountingConnection {
        val closes = AtomicInteger()
        val connection: SqlConnection = Proxy.newProxyInstance(
            SqlConnection::class.java.classLoader,
            arrayOf(SqlConnection::class.java),
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "close" -> {
                        closes.incrementAndGet()
                        Future.succeededFuture<Void>()
                    }
                    "toString" -> "CountingConnection"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> throw UnsupportedOperationException(method.name)
                }
            }
        ) as SqlConnection
    }

    @Test
    fun `a connection delivered after the transaction was cancelled goes straight back to the pool`(): Unit = runBlocking {
        val promise = Promise.promise<SqlConnection>()
        val requested = CompletableDeferred<Unit>()
        val late = CountingConnection()
        val db = MarketDb({ poolAnswering { requested.complete(Unit); promise.future() } }, SystemClock)
        val ran = AtomicInteger()

        val job = launch(Dispatchers.Default) { db.tx { ran.incrementAndGet() } }
        requested.await()
        job.cancel()
        job.join()
        assertEquals(0, late.closes.get(), "nothing was delivered yet")

        // The pool keeps the cancelled waiter queued and completes it later.
        promise.complete(late.connection)
        assertEquals(1, late.closes.get(), "the late connection was closed (given back), not lost")
        assertEquals(0, ran.get(), "the block never ran")
    }

    @Test
    fun `a connection that arrives in the moment of the cancellation goes straight back to the pool`(): Unit = runBlocking {
        // Single-threaded: the transaction is parked in coAwait; completing the future queues its resumption, and
        // the cancellation lands before that resumption runs, so the coroutine gets a CancellationException and the
        // delivered connection must still be closed.
        val promise = Promise.promise<SqlConnection>()
        val requested = AtomicInteger()
        val delivered = CountingConnection()
        val db = MarketDb({ poolAnswering { requested.incrementAndGet(); promise.future() } }, SystemClock)
        val ran = AtomicInteger()

        val job = launch { db.tx { ran.incrementAndGet() } }
        yield()
        assertEquals(1, requested.get(), "the transaction is waiting for its connection")

        promise.complete(delivered.connection)
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(1, delivered.closes.get(), "the connection that lost the race against the cancellation was closed")
        assertEquals(0, ran.get(), "the block never ran")
    }
}
