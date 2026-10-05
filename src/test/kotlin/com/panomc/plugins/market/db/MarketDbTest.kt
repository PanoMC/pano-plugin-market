package com.panomc.plugins.market.db

import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.MarketBusyException
import io.vertx.mysqlclient.MySQLException
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

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
}
