package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.impl.MarketPaymentDaoImpl
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.PaymentStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** `market_payment` (01 section 6.2). */
class MarketPaymentDaoIT : MarketDaoITBase() {
    /** A DAO round-trip test writes raw rows (credit legs without a transaction, counters without orders, ...) that the cross-table invariants I1 to I22 reconcile, on purpose. */
    override suspend fun assertInvariants() {}

    private val dao = MarketPaymentDaoImpl()

    private fun payment(
        order: Long = 1, reference: String = "A".repeat(20), token: String = "a".repeat(40), provider: String = "stripe", txn: String? = "txn_1"
    ) = MarketPayment(
        orderId = order, subscriptionId = 7, providerId = provider, methodLabel = "Card", status = PaymentStatus.PENDING,
        reference = reference, token = token, amount = 12_345, currency = "EUR", feeAmount = 345, creditAmount = 500, creditValue = 400,
        orderTotal = 12_000, startKind = "REDIRECT", startPayload = "v1:" + "QUJD".repeat(10), gatewayTransactionId = txn,
        gatewayRefs = "{\"session\":\"cs_1\"}", providerData = "v1:RElTVA==", paidAmount = 12_000, paidCurrency = "USD", gatewayFee = 300,
        netAmount = 11_700, settlementCurrency = "USDC", settlementAmount = "12.123456", installments = 3, methodDetail = "visa 4242",
        testMode = true, duplicate = true, refundedAmount = 100, failureCode = "card_declined", failureMessage = "Declined",
        adminMessage = "do_not_honor", clientIp = "203.0.113.9", userAgent = "UA/1.0", startedAt = 11, paidAt = 12, expiresAt = 13, closedAt = 14,
        nextQueryAt = 15, queryCount = 2, lastQueriedAt = 16, createdAt = 10, updatedAt = 20
    )

    @Test
    fun `a payment round-trips every column`(): Unit = runBlocking {
        val written = payment()
        EntityRoundTrip.differsFromDefaults(written, MarketPayment())
        val id = dao.add(written, pool)!!
        val read = dao.getById(id, pool)!!
        assertEquals(id, read.id)
        EntityRoundTrip.assertSame(written, read)
        assertNull(dao.getById(9999, pool))
    }

    @Test
    fun `a payment with only the required columns reads back with the column defaults`(): Unit = runBlocking {
        val minimal = MarketPayment(orderId = 3, providerId = "free", reference = "B".repeat(20), token = "b".repeat(40), amount = 0, currency = "EUR", createdAt = 1, updatedAt = 2)
        val read = dao.getById(dao.add(minimal, pool)!!, pool)!!
        EntityRoundTrip.assertSame(minimal, read)
        assertEquals(PaymentStatus.CREATED, read.status)
        assertNull(read.startPayload)
        assertNull(read.gatewayTransactionId)
    }

    @Test
    fun `ENC columns hold a large payload verbatim`(): Unit = runBlocking {
        val big = "v1:" + "Zm9v".repeat(40_000) // 160 KB, past the 64 KB of TEXT
        val id = dao.add(MarketPayment(orderId = 1, providerId = "x", reference = "C".repeat(20), token = "c".repeat(40), amount = 1, currency = "EUR", startPayload = big, providerData = big), pool)!!
        val read = dao.getById(id, pool)!!
        assertEquals(big, read.startPayload)
        assertEquals(big, read.providerData)
    }

    @Test
    fun `a merchant reference is used once`(): Unit = runBlocking {
        assertNotNull(dao.add(payment(), pool))
        assertNull(dao.add(payment(token = "b".repeat(40), txn = "txn_2"), pool))
        assertEquals(1L, count("market_payment"))
    }

    @Test
    fun `a notify token is used once`(): Unit = runBlocking {
        assertNotNull(dao.add(payment(), pool))
        assertNull(dao.add(payment(reference = "B".repeat(20), txn = "txn_2"), pool))
        assertEquals(1L, count("market_payment"))
    }

    @Test
    fun `a gateway transaction belongs to one attempt per provider and many attempts may have none`(): Unit = runBlocking {
        assertNotNull(dao.add(payment(), pool))
        assertNull(dao.add(payment(reference = "B".repeat(20), token = "b".repeat(40)), pool))
        // the same transaction id at another provider is another transaction
        assertNotNull(dao.add(payment(reference = "C".repeat(20), token = "c".repeat(40), provider = "paypal"), pool))
        // NULL ids do not collide
        assertNotNull(dao.add(payment(reference = "D".repeat(20), token = "d".repeat(40), txn = null), pool))
        assertNotNull(dao.add(payment(reference = "E".repeat(20), token = "e".repeat(40), txn = null), pool))
        assertEquals(4L, count("market_payment"))
    }

    @Test
    fun `lookups find the attempt by reference, token, gateway transaction and order`(): Unit = runBlocking {
        val first = dao.add(payment(), pool)!!
        val second = dao.add(payment(reference = "B".repeat(20), token = "b".repeat(40), txn = "txn_2"), pool)!!
        dao.add(payment(order = 2, reference = "C".repeat(20), token = "c".repeat(40), txn = "txn_3"), pool)
        assertEquals(first, dao.getByReference("A".repeat(20), pool)!!.id)
        assertEquals(second, dao.getByToken("b".repeat(40), pool)!!.id)
        assertEquals(second, dao.getByProviderTransaction("stripe", "txn_2", pool)!!.id)
        assertNull(dao.getByProviderTransaction("paypal", "txn_2", pool))
        assertNull(dao.getByReference("Z".repeat(20), pool))
        assertEquals(listOf(first, second), dao.getByOrderId(1, pool).map { it.id })
        assertEquals(emptyList<MarketPayment>(), dao.getByOrderId(99, pool))
    }
}
