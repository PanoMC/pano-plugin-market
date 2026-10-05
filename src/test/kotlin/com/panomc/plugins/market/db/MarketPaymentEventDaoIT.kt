package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.impl.MarketPaymentEventDaoImpl
import com.panomc.plugins.market.db.model.MarketPaymentEvent
import com.panomc.plugins.market.db.model.PaymentEventDirection
import com.panomc.plugins.market.db.model.PaymentEventStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** `market_payment_event` (01 section 6.3). */
class MarketPaymentEventDaoIT : MarketDaoITBase() {
    private val dao = MarketPaymentEventDaoImpl()

    private fun event(provider: String = "stripe", direction: PaymentEventDirection = PaymentEventDirection.OUT, key: String = "e:evt_1", payment: Long? = 5) =
        MarketPaymentEvent(
            providerId = provider, direction = direction, channel = "WEBHOOK", subChannel = "charge", eventKey = key, requestHash = "f".repeat(64),
            paymentId = payment, orderId = 6, refundId = 7, subscriptionId = 8, method = "POST", url = "/api/market/payment/stripe/webhook?token=***",
            headers = "{\"content-type\":\"application/json\"}", body = "{\"id\":\"evt_1\"}", remoteIp = "203.0.113.9", verified = true,
            eventTypes = "Succeeded,Pending", status = PaymentEventStatus.PROCESSED, attempts = 2, duplicateCount = 3, nextAttemptAt = 44,
            responseStatus = 200, error = "none", durationMs = 12, processedAt = 45, createdAt = 10, updatedAt = 20
        )

    @Test
    fun `an event round-trips every column`(): Unit = runBlocking {
        val written = event()
        EntityRoundTrip.differsFromDefaults(written, MarketPaymentEvent())
        val id = dao.add(written, pool)!!
        val read = dao.getById(id, pool)!!
        assertEquals(id, read.id)
        EntityRoundTrip.assertSame(written, read)
        assertNull(dao.getById(9999, pool))
    }

    @Test
    fun `an event without a verdict keeps verified null and a stated verdict reads back false or true`(): Unit = runBlocking {
        val none = dao.add(MarketPaymentEvent(providerId = "p", channel = "WEBHOOK", eventKey = "r:1"), pool)!!
        val no = dao.add(MarketPaymentEvent(providerId = "p", channel = "WEBHOOK", eventKey = "r:2", verified = false), pool)!!
        val yes = dao.add(MarketPaymentEvent(providerId = "p", channel = "WEBHOOK", eventKey = "r:3", verified = true), pool)!!
        assertNull(dao.getById(none, pool)!!.verified)
        assertEquals(false, dao.getById(no, pool)!!.verified)
        assertEquals(true, dao.getById(yes, pool)!!.verified)
        assertEquals(PaymentEventStatus.RECEIVED, dao.getById(none, pool)!!.status)
    }

    @Test
    fun `a provider event key is stored once per direction`(): Unit = runBlocking {
        assertNotNull(dao.add(event(), pool))
        assertNull(dao.add(event(), pool))
        // the same key at another provider or in the other direction is another event
        assertNotNull(dao.add(event(provider = "paypal"), pool))
        assertNotNull(dao.add(event(direction = PaymentEventDirection.IN), pool))
        assertNotNull(dao.add(event(key = "e:evt_2"), pool))
        assertEquals(4L, count("market_payment_event"))
    }

    @Test
    fun `lookups find an event by key and the events of a payment`(): Unit = runBlocking {
        val first = dao.add(event(), pool)!!
        val second = dao.add(event(key = "e:evt_2"), pool)!!
        dao.add(event(key = "e:evt_3", payment = 99), pool)
        assertEquals(first, dao.getByEventKey("stripe", PaymentEventDirection.OUT, "e:evt_1", pool)!!.id)
        assertNull(dao.getByEventKey("stripe", PaymentEventDirection.IN, "e:evt_1", pool))
        assertEquals(listOf(first, second), dao.getByPaymentId(5, pool).map { it.id })
    }

    @Test
    fun `a body past 64 KB is kept in full`(): Unit = runBlocking {
        val big = "x".repeat(200_000)
        val id = dao.add(MarketPaymentEvent(providerId = "p", channel = "WEBHOOK", eventKey = "r:big", body = big), pool)!!
        assertEquals(big, dao.getById(id, pool)!!.body)
    }
}
