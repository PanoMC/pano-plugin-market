package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.impl.MarketRefundDaoImpl
import com.panomc.plugins.market.db.impl.MarketRefundItemDaoImpl
import com.panomc.plugins.market.db.model.MarketRefund
import com.panomc.plugins.market.db.model.MarketRefundItem
import com.panomc.plugins.market.db.model.RefundOrigin
import com.panomc.plugins.market.db.model.RefundStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** `market_refund` and `market_refund_item` (01 section 6.4). */
class MarketRefundDaoIT : MarketDaoITBase() {
    private val dao = MarketRefundDaoImpl()
    private val items = MarketRefundItemDaoImpl()

    private fun refund(key: String = "idem-1", provider: String? = "stripe", gatewayRefundId: String? = "re_1", order: Long = 1) = MarketRefund(
        orderId = order, paymentId = 2, providerId = provider, status = RefundStatus.PENDING, origin = RefundOrigin.GATEWAY, idempotencyKey = key,
        idempotencyHash = "a".repeat(64), amount = 1_000, gatewayAmount = 700, gatewayRefundedAmount = 750, creditAmount = 300, creditValue = 300,
        currency = "EUR", reason = "damaged", gatewayRefundId = gatewayRefundId, buyerActionUrl = "https://pay.example/claim/1", revoke = false,
        revokeFirst = true, cascadeUpgrade = true, restock = true, creditTxId = 9, initiatedBy = 3, failureCode = "x", failureMessage = "y",
        nextQueryAt = 50, queryCount = 4, completedAt = 51, createdAt = 10, updatedAt = 20
    )

    @Test
    fun `a refund round-trips every column`(): Unit = runBlocking {
        val written = refund()
        EntityRoundTrip.differsFromDefaults(written, MarketRefund())
        val id = dao.add(written, pool)!!
        val read = dao.getById(id, pool)!!
        assertEquals(id, read.id)
        EntityRoundTrip.assertSame(written, read)
        assertNull(dao.getById(9999, pool))
    }

    @Test
    fun `a refund with only the required columns reads back with the column defaults`(): Unit = runBlocking {
        val minimal = MarketRefund(orderId = 4, origin = RefundOrigin.SYSTEM, idempotencyKey = "min", amount = 5, currency = "EUR", createdAt = 1, updatedAt = 2)
        val read = dao.getById(dao.add(minimal, pool)!!, pool)!!
        EntityRoundTrip.assertSame(minimal, read)
        assertEquals(RefundStatus.REQUESTED, read.status)
        assertEquals(true, read.revoke)
        assertNull(read.cascadeUpgrade)
        assertNull(read.paymentId)
    }

    @Test
    fun `cascadeUpgrade keeps its three states`(): Unit = runBlocking {
        val none = dao.add(MarketRefund(orderId = 1, origin = RefundOrigin.PANEL, idempotencyKey = "k1", amount = 1, currency = "EUR"), pool)!!
        val no = dao.add(MarketRefund(orderId = 1, origin = RefundOrigin.PANEL, idempotencyKey = "k2", amount = 1, currency = "EUR", cascadeUpgrade = false), pool)!!
        val yes = dao.add(MarketRefund(orderId = 1, origin = RefundOrigin.PANEL, idempotencyKey = "k3", amount = 1, currency = "EUR", cascadeUpgrade = true), pool)!!
        assertNull(dao.getById(none, pool)!!.cascadeUpgrade)
        assertEquals(false, dao.getById(no, pool)!!.cascadeUpgrade)
        assertEquals(true, dao.getById(yes, pool)!!.cascadeUpgrade)
    }

    @Test
    fun `an idempotency key creates one refund`(): Unit = runBlocking {
        assertNotNull(dao.add(refund(), pool))
        assertNull(dao.add(refund(gatewayRefundId = "re_2"), pool))
        assertEquals(1L, count("market_refund"))
        assertEquals(1L, dao.getByIdempotencyKey("idem-1", pool)!!.id)
        assertNull(dao.getByIdempotencyKey("nope", pool))
    }

    @Test
    fun `a gateway refund id belongs to one refund per provider and refunds without one do not collide`(): Unit = runBlocking {
        assertNotNull(dao.add(refund(), pool))
        assertNull(dao.add(refund(key = "idem-2"), pool))
        assertNotNull(dao.add(refund(key = "idem-3", provider = "paypal"), pool))
        assertNotNull(dao.add(refund(key = "idem-4", gatewayRefundId = null), pool))
        assertNotNull(dao.add(refund(key = "idem-5", provider = null, gatewayRefundId = null), pool))
        assertNotNull(dao.add(refund(key = "idem-6", provider = null, gatewayRefundId = null), pool))
        assertEquals(1L, dao.getByProviderRefund("stripe", "re_1", pool)!!.id)
        assertNull(dao.getByProviderRefund("stripe", "re_9", pool))
        assertEquals(5, dao.getByOrderId(1, pool).size)
        assertEquals(emptyList<MarketRefund>(), dao.getByOrderId(77, pool))
    }

    @Test
    fun `a refund item round-trips and a line is refunded once per refund`(): Unit = runBlocking {
        val written = MarketRefundItem(refundId = 1, orderItemId = 2, quantity = 3, amount = 450, createdAt = 10, updatedAt = 20)
        EntityRoundTrip.differsFromDefaults(written, MarketRefundItem())
        val id = items.add(written, pool)!!
        EntityRoundTrip.assertSame(written, items.getById(id, pool)!!)
        assertNull(items.add(MarketRefundItem(refundId = 1, orderItemId = 2, quantity = 1, amount = 1), pool))
        assertNotNull(items.add(MarketRefundItem(refundId = 1, orderItemId = 3, quantity = 1, amount = 1), pool))
        assertNotNull(items.add(MarketRefundItem(refundId = 2, orderItemId = 2, quantity = 1, amount = 1), pool))
        assertEquals(listOf(2L, 3L), items.getByRefundId(1, pool).map { it.orderItemId })
        assertEquals(0, items.getByRefundId(99, pool).size)
        assertEquals(3L, count("market_refund_item"))
    }
}
