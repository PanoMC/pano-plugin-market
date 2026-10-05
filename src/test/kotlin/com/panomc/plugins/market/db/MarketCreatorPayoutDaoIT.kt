package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.dao.isDuplicateKey
import com.panomc.plugins.market.db.impl.MarketCreatorPayoutDaoImpl
import com.panomc.plugins.market.db.model.CreatorPayoutMethod
import com.panomc.plugins.market.db.model.CreatorPayoutState
import com.panomc.plugins.market.db.model.MarketCreatorPayout
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `market_creator_payout` (01 section 8): round trip, `uq_idem` insert-first, guarded state change, finders. */
class MarketCreatorPayoutDaoIT : MarketDaoITBase() {
    private val dao = MarketCreatorPayoutDaoImpl()
    private val hash = "a".repeat(64)

    private fun payout(key: String = "k1", code: Long = 1, hash: String = this.hash, method: CreatorPayoutMethod = CreatorPayoutMethod.CREDIT) =
        MarketCreatorPayout(
            creatorCodeId = code, creatorUserId = 5, amount = 2500, currency = "USD", method = method,
            actions = "[{\"id\":\"a\"}]", note = "n", idempotencyKey = key, idempotencyHash = hash, createdAt = 10, updatedAt = 20
        )

    @Test
    fun `insert then get returns every column and the defaults hold`(): Unit = runBlocking {
        val id = dao.add(payout(), pool)!!
        val read = dao.getById(id, pool)!!
        assertEquals(1L, read.creatorCodeId)
        assertEquals(5L, read.creatorUserId)
        assertEquals(2500L, read.amount)
        assertEquals("USD", read.currency)
        assertEquals(CreatorPayoutMethod.CREDIT, read.method)
        assertEquals(CreatorPayoutState.PENDING, read.state)
        assertNull(read.creditTxId)
        assertEquals("[{\"id\":\"a\"}]", read.actions)
        assertEquals("n", read.note)
        assertNull(read.paidBy)
        assertNull(read.paidAt)
        assertEquals("k1", read.idempotencyKey)
        assertEquals(hash, read.idempotencyHash)
        assertEquals(10L, read.createdAt)
        assertEquals(20L, read.updatedAt)
        assertNull(dao.getById(404, pool))
    }

    @Test
    fun `a replayed idempotency key is answered with null and the first row stays`(): Unit = runBlocking {
        val first = dao.add(payout(), pool)!!
        assertNull(dao.add(payout(hash = "b".repeat(64), method = CreatorPayoutMethod.MANUAL), pool))
        assertEquals(1L, count("market_creator_payout"))
        val stored = dao.getByIdempotencyKey("k1", pool)!!
        assertEquals(first, stored.id)
        assertEquals(hash, stored.idempotencyHash)
        assertEquals(CreatorPayoutMethod.CREDIT, stored.method)
        assertNull(dao.getByIdempotencyKey("nope", pool))
        assertNotNull(dao.add(payout(key = "k2"), pool))
        assertEquals(2L, count("market_creator_payout"))
        val failure = runCatching {
            sql("INSERT INTO `pano_market_creator_payout` (`creatorCodeId`, `amount`, `currency`, `method`, `idempotencyKey`, `idempotencyHash`, `createdAt`, `updatedAt`) VALUES (1, 1, 'USD', 'MANUAL', 'k1', '${"c".repeat(64)}', 1, 1)")
        }.exceptionOrNull()
        assertTrue(failure != null && failure.isDuplicateKey())
    }

    @Test
    fun `transition is guarded and writes the paid fields`(): Unit = runBlocking {
        val id = dao.add(payout(), pool)!!
        assertTrue(dao.transition(id, CreatorPayoutState.PENDING, CreatorPayoutState.PAID, 9, 5000, 77, 6000, pool))
        val paid = dao.getById(id, pool)!!
        assertEquals(CreatorPayoutState.PAID, paid.state)
        assertEquals(9L, paid.paidBy)
        assertEquals(5000L, paid.paidAt)
        assertEquals(77L, paid.creditTxId)
        assertEquals(6000L, paid.updatedAt)
        assertFalse(dao.transition(id, CreatorPayoutState.PENDING, CreatorPayoutState.CANCELLED, null, null, null, 7000, pool))
        assertEquals(CreatorPayoutState.PAID, dao.getById(id, pool)!!.state)

        val other = dao.add(payout(key = "k2"), pool)!!
        assertTrue(dao.transition(other, CreatorPayoutState.PENDING, CreatorPayoutState.FAILED, null, null, null, 8000, pool))
        val failed = dao.getById(other, pool)!!
        assertEquals(CreatorPayoutState.FAILED, failed.state)
        assertNull(failed.paidBy)
        assertNull(failed.creditTxId)
    }

    @Test
    fun `payouts of a code are listed newest first`(): Unit = runBlocking {
        val a = dao.add(payout(key = "a"), pool)!!
        val b = dao.add(payout(key = "b"), pool)!!
        dao.add(payout(key = "c", code = 2), pool)
        assertEquals(listOf(b, a), dao.getByCodeId(1, pool).map { it.id })
        assertTrue(dao.getByCodeId(3, pool).isEmpty())
    }
}
