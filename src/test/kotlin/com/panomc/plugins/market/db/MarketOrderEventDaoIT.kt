package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.impl.MarketOrderEventDaoImpl
import com.panomc.plugins.market.db.model.MarketOrderEvent
import com.panomc.plugins.market.db.model.OrderActorType
import com.panomc.plugins.market.db.model.OrderEventType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** `market_order_event` (01 section 5.3): the append-only timeline, every event type and actor fits its column. */
class MarketOrderEventDaoIT : MarketDaoITBase() {
    private val dao = MarketOrderEventDaoImpl()

    @Test
    fun `a full event round-trips every column`(): Unit = runBlocking {
        val written = MarketOrderEvent(
            orderId = 5, type = OrderEventType.STATUS_CHANGED, fromStatus = "PENDING", toStatus = "COMPLETED",
            actorType = OrderActorType.GATEWAY, actorUserId = 9, message = "paid", data = "{\"paymentId\":3}", createdAt = 10, updatedAt = 20
        )
        EntityRoundTrip.differsFromDefaults(written, MarketOrderEvent())
        val id = dao.add(written, pool)
        val read = dao.getById(id, pool)!!
        assertEquals(id, read.id)
        EntityRoundTrip.assertSame(written, read)
        assertNull(dao.getById(9999, pool))
    }

    @Test
    fun `the timeline of an order is returned oldest first and only for that order`(): Unit = runBlocking {
        val first = dao.add(MarketOrderEvent(orderId = 1, type = OrderEventType.CREATED), pool)
        dao.add(MarketOrderEvent(orderId = 2, type = OrderEventType.CREATED), pool)
        val third = dao.add(MarketOrderEvent(orderId = 1, type = OrderEventType.NOTE, actorType = OrderActorType.ADMIN, message = "called the buyer"), pool)
        val timeline = dao.getByOrderId(1, pool)
        assertEquals(listOf(first, third), timeline.map { it.id })
        assertEquals(listOf(OrderEventType.CREATED, OrderEventType.NOTE), timeline.map { it.type })
        assertEquals(OrderActorType.ADMIN, timeline.last().actorType)
        assertNull(timeline.first().fromStatus)
        assertEquals(emptyList<MarketOrderEvent>(), dao.getByOrderId(3, pool))
    }

    @Test
    fun `every event type of the closed list is stored and read back`(): Unit = runBlocking {
        assertEquals(33, OrderEventType.entries.size)
        for (type in OrderEventType.entries) dao.add(MarketOrderEvent(orderId = 1, type = type), pool)
        assertEquals(OrderEventType.entries.toList(), dao.getByOrderId(1, pool).map { it.type })
    }
}
