package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.dao.isDuplicateKey
import com.panomc.plugins.market.db.impl.MarketRedemptionDaoImpl
import com.panomc.plugins.market.db.model.MarketRedemption
import com.panomc.plugins.market.db.model.RedemptionKind
import com.panomc.plugins.market.db.model.RedemptionState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `market_redemption` (01 section 3.5): round trip, `uq_kind_ref_order` by a duplicate insert, states and limit counts. */
class MarketRedemptionDaoIT : MarketDaoITBase() {
    private val dao = MarketRedemptionDaoImpl()

    private fun redemption(
        kind: RedemptionKind = RedemptionKind.COUPON, refId: Long = 1, orderId: Long = 1, buyerKey: String = "u:1",
        email: String? = null, recipientKey: String = "", state: RedemptionState = RedemptionState.HELD
    ) = MarketRedemption(
        kind = kind, refId = refId, code = "SAVE10", orderId = orderId, userId = 7, buyerKey = buyerKey, email = email,
        recipientKey = recipientKey, amount = 250, currency = "USD", state = state, createdAt = 10, updatedAt = 20
    )

    @Test
    fun `insert then get returns every column`(): Unit = runBlocking {
        val id = dao.add(redemption(email = "a@b.c", recipientKey = "p:steve"), pool)!!
        val read = dao.getById(id, pool)!!
        assertEquals(RedemptionKind.COUPON, read.kind)
        assertEquals(1L, read.refId)
        assertEquals("SAVE10", read.code)
        assertEquals(1L, read.orderId)
        assertEquals(7L, read.userId)
        assertEquals("u:1", read.buyerKey)
        assertEquals("a@b.c", read.email)
        assertEquals("p:steve", read.recipientKey)
        assertEquals(250L, read.amount)
        assertEquals("USD", read.currency)
        assertEquals(RedemptionState.HELD, read.state)
        assertEquals(10L, read.createdAt)
        assertEquals(20L, read.updatedAt)
        assertNull(dao.getById(9999, pool))
        // defaults of the columns: recipientKey '' and state HELD
        sql("INSERT INTO `pano_market_redemption` (`kind`, `refId`, `orderId`, `buyerKey`, `currency`, `createdAt`, `updatedAt`) VALUES ('GIFT', 5, 5, 'k', 'USD', 1, 1)")
        val row = sql("SELECT `recipientKey`, `state`, `amount` FROM `pano_market_redemption` WHERE `kind` = 'GIFT'").single()
        assertEquals("", row.getString("recipientKey"))
        assertEquals("HELD", row.getString("state"))
        assertEquals(0L, row.getLong("amount"))
    }

    @Test
    fun `a duplicate of kind refId orderId is answered with null and the row is untouched`(): Unit = runBlocking {
        val first = dao.add(redemption(), pool)!!
        assertNull(dao.add(redemption().let { MarketRedemption(kind = it.kind, refId = it.refId, orderId = it.orderId, buyerKey = "other", currency = "EUR", amount = 999) }, pool))
        assertEquals(1L, count("market_redemption"))
        assertEquals(250L, dao.getById(first, pool)!!.amount)
        // the same order with another kind, another ref or another order is a different row
        assertNotNull(dao.add(redemption(kind = RedemptionKind.DISCOUNT), pool))
        assertNotNull(dao.add(redemption(refId = 2), pool))
        assertNotNull(dao.add(redemption(orderId = 2), pool))
        assertEquals(4L, count("market_redemption"))
        assertEquals(first, dao.get(RedemptionKind.COUPON, 1, 1, pool)!!.id)
        assertNull(dao.get(RedemptionKind.GIFT, 1, 1, pool))
    }

    @Test
    fun `a duplicate insert is rejected by the unique key itself`(): Unit = runBlocking {
        dao.add(redemption(), pool)
        val failure = runCatching {
            sql("INSERT INTO `pano_market_redemption` (`kind`, `refId`, `orderId`, `buyerKey`, `currency`, `createdAt`, `updatedAt`) VALUES ('COUPON', 1, 1, 'x', 'USD', 1, 1)")
        }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.isDuplicateKey())
    }

    @Test
    fun `transition is guarded and counts follow the state`(): Unit = runBlocking {
        val a = dao.add(redemption(orderId = 1), pool)!!
        val b = dao.add(redemption(orderId = 2), pool)!!
        dao.add(redemption(refId = 2, orderId = 3), pool)
        assertEquals(2L, dao.countActive(RedemptionKind.COUPON, 1, pool))

        assertTrue(dao.transition(a, RedemptionState.HELD, RedemptionState.APPLIED, pool))
        assertFalse(dao.transition(a, RedemptionState.HELD, RedemptionState.RELEASED, pool))
        assertEquals(RedemptionState.APPLIED, dao.getById(a, pool)!!.state)
        assertEquals(2L, dao.countActive(RedemptionKind.COUPON, 1, pool))

        assertTrue(dao.transition(b, RedemptionState.HELD, RedemptionState.RELEASED, pool))
        assertEquals(1L, dao.countActive(RedemptionKind.COUPON, 1, pool))
        assertEquals(listOf(a), dao.getByOrderId(1, pool).map { it.id })
    }

    @Test
    fun `the customer limit count matches payer, email or recipient and ignores released rows`(): Unit = runBlocking {
        dao.add(redemption(orderId = 1, buyerKey = "u:1", email = "a@x.y", recipientKey = "p:steve"), pool)
        dao.add(redemption(orderId = 2, buyerKey = "g:9", email = "guest@x.y", recipientKey = "p:alex"), pool)
        dao.add(redemption(orderId = 3, buyerKey = "g:8", email = null, recipientKey = "p:kim", state = RedemptionState.RELEASED), pool)
        dao.add(redemption(refId = 2, orderId = 4, buyerKey = "u:1"), pool)

        suspend fun n(buyer: String, email: String?, recipients: List<String>) =
            dao.countForCustomer(RedemptionKind.COUPON, 1, buyer, email, recipients, pool)

        assertEquals(1L, n("u:1", null, emptyList()))
        assertEquals(1L, n("nobody", "guest@x.y", emptyList()))
        assertEquals(1L, n("nobody", null, listOf("p:alex")))
        assertEquals(2L, n("u:1", "guest@x.y", emptyList()))
        // one guest gifting to a player who already used it
        assertEquals(1L, n("g:77", "new@x.y", listOf("p:steve", "p:nobody")))
        // a released row never counts, and another code is another subject
        assertEquals(0L, n("g:8", null, listOf("p:kim")))
        assertEquals(0L, n("nobody", "NULL", emptyList()))
        assertEquals(1L, dao.countForCustomer(RedemptionKind.COUPON, 2, "u:1", null, emptyList(), pool))
    }

    @Test
    fun `deleteByOrderId removes the rows of one order only`(): Unit = runBlocking {
        dao.add(redemption(orderId = 1), pool)
        dao.add(redemption(kind = RedemptionKind.GIFT, orderId = 1), pool)
        dao.add(redemption(orderId = 2), pool)
        assertEquals(2, dao.deleteByOrderId(1, pool))
        assertEquals(1L, count("market_redemption"))
    }
}
