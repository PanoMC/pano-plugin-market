package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.impl.MarketSequenceDaoImpl
import com.panomc.plugins.market.support.Race
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `market_sequence.next` (01 section 6.7): the gap-free counter used by invoice numbering. */
class SequenceIT : MarketDaoITBase() {
    private val dao = MarketSequenceDaoImpl()

    @Test
    fun `the first value is 1 and each call adds one`(): Unit = runBlocking {
        val db = marketDb()
        val values = db.tx { c -> List(3) { dao.next("invoice:INV", c) } }
        assertEquals(listOf(1L, 2L, 3L), values)
        assertEquals(3L, dao.getValue("invoice:INV", pool))
        // another counter is independent
        assertEquals(1L, db.tx { c -> dao.next("invoice:TEST", c) })
        assertEquals(3L, dao.getValue("invoice:INV", pool))
    }

    @Test
    fun `a counter that already exists with a value continues from it`(): Unit = runBlocking {
        sql("INSERT INTO `pano_market_sequence` (`name`, `value`) VALUES ('invoice:OLD', 41)")
        assertEquals(42L, marketDb().tx { c -> dao.next("invoice:OLD", c) })
    }

    @Test
    fun `a rolled back transaction leaves no gap`(): Unit = runBlocking {
        val db = marketDb()
        assertEquals(1L, db.tx { c -> dao.next("invoice:INV", c) })
        val failure = runCatching {
            db.tx { c ->
                assertEquals(2L, dao.next("invoice:INV", c))
                error("invoice rendering failed")
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(1L, dao.getValue("invoice:INV", pool))
        assertEquals(2L, db.tx { c -> dao.next("invoice:INV", c) })
    }

    @Test
    fun `20 concurrent transactions get 20 contiguous values`(): Unit = runBlocking {
        repeat(Race.rounds) { round ->
            val name = "invoice:RACE$round"
            val db = marketDb(lockWaitSeconds = 30)
            val outcomes = Race.run(20) { db.tx { c -> dao.next(name, c) } }
            assertTrue(outcomes.all { it.isSuccess }, outcomes.filter { it.isFailure }.map { it.exceptionOrNull() }.toString())
            val values = outcomes.map { it.getOrThrow() }.sorted()
            assertEquals((1L..20L).toList(), values, "round $round")
            assertEquals(20L, dao.getValue(name, pool))
        }
    }

    @Test
    fun `the numbers of concurrent transactions that also store the invoice stay contiguous`(): Unit = runBlocking {
        val invoices = com.panomc.plugins.market.db.impl.MarketInvoiceDaoImpl()
        val db = marketDb(lockWaitSeconds = 30)
        val outcomes = Race.run(20) { i ->
            db.tx { c ->
                val n = dao.next("invoice:INV", c)
                invoices.add(
                    com.panomc.plugins.market.db.model.MarketInvoice(
                        orderId = i + 1L, series = "INV", sequence = n, number = "INV-$n", locale = "en", currency = "EUR", snapshot = "{}"
                    ), c
                )
                n
            }
        }
        assertTrue(outcomes.all { it.isSuccess }, outcomes.filter { it.isFailure }.map { it.exceptionOrNull() }.toString())
        assertEquals(20L, count("market_invoice"))
        assertEquals((1L..20L).toList(), sql("SELECT `sequence` FROM `pano_market_invoice` ORDER BY `sequence`").map { it.getLong(0) })
    }
}
