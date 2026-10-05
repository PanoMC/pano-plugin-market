package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.dao.isDuplicateKey
import com.panomc.plugins.market.db.impl.MarketCreatorEarningDaoImpl
import com.panomc.plugins.market.db.model.CreatorEarningState
import com.panomc.plugins.market.db.model.MarketCreatorEarning
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `market_creator_earning` (01 section 8): round trip, `uq_order_code`, hold release, reversal and the net sum. */
class MarketCreatorEarningDaoIT : MarketDaoITBase() {
    private val dao = MarketCreatorEarningDaoImpl()

    private fun earning(
        code: Long = 1, order: Long = 1, amount: Long = 1000, state: CreatorEarningState = CreatorEarningState.PENDING,
        availableAt: Long? = null
    ) = MarketCreatorEarning(
        creatorCodeId = code, creatorUserId = 5, orderId = order, baseAmount = 10000, commissionPercent = 1000,
        amount = amount, currency = "USD", state = state, availableAt = availableAt, createdAt = 10, updatedAt = 20
    )

    @Test
    fun `insert then get returns every column and the defaults hold`(): Unit = runBlocking {
        val id = dao.add(earning(availableAt = 5000), pool)!!
        val read = dao.getById(id, pool)!!
        assertEquals(1L, read.creatorCodeId)
        assertEquals(5L, read.creatorUserId)
        assertEquals(1L, read.orderId)
        assertEquals(10000L, read.baseAmount)
        assertEquals(1000L, read.commissionPercent)
        assertEquals(1000L, read.amount)
        assertEquals("USD", read.currency)
        assertEquals(CreatorEarningState.PENDING, read.state)
        assertEquals(5000L, read.availableAt)
        assertEquals(0L, read.reversedAmount)
        assertNull(read.payoutId)
        assertEquals(10L, read.createdAt)
        assertEquals(20L, read.updatedAt)
        assertNull(dao.getById(404, pool))

        sql("INSERT INTO `pano_market_creator_earning` (`creatorCodeId`, `orderId`, `baseAmount`, `commissionPercent`, `amount`, `currency`, `createdAt`, `updatedAt`) VALUES (2, 2, 1, 1, 1, 'USD', 1, 1)")
        val row = sql("SELECT `state`, `reversedAmount`, `availableAt`, `creatorUserId` FROM `pano_market_creator_earning` WHERE `creatorCodeId` = 2").single()
        assertEquals("PENDING", row.getString("state"))
        assertEquals(0L, row.getLong("reversedAmount"))
        assertNull(row.getValue("availableAt"))
        assertNull(row.getValue("creatorUserId"))
    }

    @Test
    fun `a duplicate of orderId creatorCodeId is answered with null`(): Unit = runBlocking {
        val first = dao.add(earning(amount = 100), pool)!!
        assertNull(dao.add(earning(amount = 999), pool))
        assertEquals(100L, dao.getById(first, pool)!!.amount)
        assertNotNull(dao.add(earning(code = 2), pool))
        assertNotNull(dao.add(earning(order = 2), pool))
        assertEquals(3L, count("market_creator_earning"))
        assertEquals(first, dao.get(1, 1, pool)!!.id)
        assertNull(dao.get(9, 9, pool))
        assertEquals(2, dao.getByCodeId(1, pool).size)
        assertEquals(2, dao.getByOrderId(1, pool).size)
        val failure = runCatching {
            sql("INSERT INTO `pano_market_creator_earning` (`creatorCodeId`, `orderId`, `baseAmount`, `commissionPercent`, `amount`, `currency`, `createdAt`, `updatedAt`) VALUES (1, 1, 1, 1, 1, 'USD', 1, 1)")
        }.exceptionOrNull()
        assertTrue(failure != null && failure.isDuplicateKey())
    }

    @Test
    fun `releaseDue flips only pending rows whose hold has passed`(): Unit = runBlocking {
        val due = dao.add(earning(order = 1, availableAt = 1000), pool)!!
        val later = dao.add(earning(order = 2, availableAt = 9000), pool)!!
        val noDate = dao.add(earning(order = 3, availableAt = null), pool)!!
        val paid = dao.add(earning(order = 4, availableAt = 10, state = CreatorEarningState.PAID), pool)!!

        assertEquals(1, dao.releaseDue(5000, pool))
        assertEquals(CreatorEarningState.AVAILABLE, dao.getById(due, pool)!!.state)
        assertEquals(CreatorEarningState.PENDING, dao.getById(later, pool)!!.state)
        assertEquals(CreatorEarningState.PENDING, dao.getById(noDate, pool)!!.state)
        assertEquals(CreatorEarningState.PAID, dao.getById(paid, pool)!!.state)
        assertEquals(0, dao.releaseDue(5000, pool))
    }

    @Test
    fun `transition and attachPayout are guarded`(): Unit = runBlocking {
        val id = dao.add(earning(), pool)!!
        assertFalse(dao.attachPayout(id, 3, pool), "a pending row cannot be paid")
        assertTrue(dao.transition(id, CreatorEarningState.PENDING, CreatorEarningState.AVAILABLE, pool))
        assertFalse(dao.transition(id, CreatorEarningState.PENDING, CreatorEarningState.REVERSED, pool))
        assertTrue(dao.attachPayout(id, 3, pool))
        assertFalse(dao.attachPayout(id, 4, pool), "paid once")
        val read = dao.getById(id, pool)!!
        assertEquals(CreatorEarningState.PAID, read.state)
        assertEquals(3L, read.payoutId)
    }

    @Test
    fun `reversal grows pro rata but never beyond the amount and the net sum follows`(): Unit = runBlocking {
        val a = dao.add(earning(order = 1, amount = 1000, state = CreatorEarningState.AVAILABLE), pool)!!
        val b = dao.add(earning(order = 2, amount = 500, state = CreatorEarningState.PAID), pool)!!
        dao.add(earning(order = 3, amount = 700, state = CreatorEarningState.PENDING), pool)

        assertEquals(2200L, dao.sumNet(1, CreatorEarningState.values().toList(), pool))
        assertEquals(1500L, dao.sumNet(1, listOf(CreatorEarningState.AVAILABLE, CreatorEarningState.PAID), pool))
        assertEquals(0L, dao.sumNet(1, emptyList(), pool))
        assertEquals(0L, dao.sumNet(99, CreatorEarningState.values().toList(), pool))

        assertTrue(dao.addReversed(a, 400, pool))
        assertTrue(dao.addReversed(a, 600, pool))
        assertFalse(dao.addReversed(a, 1, pool), "beyond the amount")
        assertEquals(1000L, dao.getById(a, pool)!!.reversedAmount)
        assertTrue(dao.addReversed(b, 200, pool))
        assertEquals(300L, dao.sumNet(1, listOf(CreatorEarningState.AVAILABLE, CreatorEarningState.PAID), pool))
    }
}
