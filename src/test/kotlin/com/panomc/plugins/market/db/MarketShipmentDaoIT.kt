package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.impl.MarketShipmentDaoImpl
import com.panomc.plugins.market.db.impl.MarketShipmentEventDaoImpl
import com.panomc.plugins.market.db.impl.MarketShipmentItemDaoImpl
import com.panomc.plugins.market.db.model.*
import com.panomc.plugins.market.support.Race
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `market_shipment`, `market_shipment_item` and `market_shipment_event` (01 section 11.5). */
class MarketShipmentDaoIT : MarketDaoITBase() {
    private val shipments = MarketShipmentDaoImpl()
    private val items = MarketShipmentItemDaoImpl()
    private val events = MarketShipmentEventDaoImpl()

    private fun shipment(ref: String = "SHP-1", order: Long = 7, status: ShipmentStatus = ShipmentStatus.LABEL_READY, nextPollAt: Long? = null) = MarketShipment(
        orderId = order, methodId = 3, providerId = "manual", serviceCode = "svc", status = status, entryMode = ShipmentEntryMode.MANUAL,
        merchantReference = ref, carrierReference = "car-$ref", trackingNumber = "trk-$ref", trackingUrl = "https://t/$ref", carrierName = "DHL",
        labelFile = "labels/$ref.pdf", labelFormat = "PDF", documents = """[{"type":"customs"}]""", rateRef = "rate-1", cost = 450, costCurrency = "EUR",
        weightGrams = 1200, packages = """[{"weightGrams":1200}]""", estimatedDeliveryAt = 111, note = "fragile", lastErrorCode = "E1", lastError = "oops",
        claimedUntil = 222, itemsReleased = true, stale = true, fromAddress = """{"c":"DE"}""", toAddress = """{"c":"FR"}""", codAmount = 99,
        providerData = "v1:xyz", testMode = true, nextPollAt = nextPollAt, pollCount = 2, lastPolledAt = 333, shippedAt = 444, deliveredAt = 555,
        cancelledAt = 666, trackingMailSentAt = 777, createdBy = 8, createdAt = 10, updatedAt = 20
    )

    @Test
    fun `a shipment round-trips every column`(): Unit = runBlocking {
        val id = shipments.add(shipment(nextPollAt = 888), pool)!!
        val r = shipments.getById(id, pool)!!
        assertEquals(
            listOf<Any?>(
                7L, 3L, "manual", "svc", ShipmentStatus.LABEL_READY, ShipmentEntryMode.MANUAL, "SHP-1", "car-SHP-1", "trk-SHP-1", "https://t/SHP-1", "DHL",
                "labels/SHP-1.pdf", "PDF", """[{"type":"customs"}]""", "rate-1", 450L, "EUR", 1200, """[{"weightGrams":1200}]""", 111L, "fragile", "E1", "oops",
                222L, true, true, """{"c":"DE"}""", """{"c":"FR"}""", 99L, "v1:xyz", true, 888L, 2, 333L, 444L, 555L, 666L, 777L, 8L, 10L, 20L
            ),
            listOf(
                r.orderId, r.methodId, r.providerId, r.serviceCode, r.status, r.entryMode, r.merchantReference, r.carrierReference, r.trackingNumber,
                r.trackingUrl, r.carrierName, r.labelFile, r.labelFormat, r.documents, r.rateRef, r.cost, r.costCurrency, r.weightGrams, r.packages,
                r.estimatedDeliveryAt, r.note, r.lastErrorCode, r.lastError, r.claimedUntil, r.itemsReleased, r.stale, r.fromAddress, r.toAddress,
                r.codAmount, r.providerData, r.testMode, r.nextPollAt, r.pollCount, r.lastPolledAt, r.shippedAt, r.deliveredAt, r.cancelledAt,
                r.trackingMailSentAt, r.createdBy, r.createdAt, r.updatedAt
            )
        )
        assertNull(shipments.getById(9999, pool))
    }

    @Test
    fun `a minimal shipment gets the column defaults`(): Unit = runBlocking {
        sql(
            "INSERT INTO `pano_market_shipment` (`orderId`, `providerId`, `merchantReference`, `fromAddress`, `toAddress`, `createdAt`, `updatedAt`) " +
                "VALUES (1, 'manual', 'MIN', '{}', '{}', 1, 1)"
        )
        val r = shipments.getByMerchantReference("MIN", pool)!!
        assertEquals(listOf<Any?>(ShipmentStatus.CREATED, ShipmentEntryMode.CARRIER, 0, false, false, false), listOf(r.status, r.entryMode, r.pollCount, r.itemsReleased, r.stale, r.testMode))
        assertNull(r.methodId)
        assertNull(r.trackingNumber)
        assertNull(r.cost)
        assertNull(r.claimedUntil)
        assertNull(r.providerData)
    }

    @Test
    fun `merchantReference is unique and a duplicate leaves the first row intact`(): Unit = runBlocking {
        assertNotNull(shipments.add(shipment("SHP-1"), pool))
        assertNull(shipments.add(MarketShipment(orderId = 99, providerId = "other", merchantReference = "SHP-1"), pool))
        assertEquals(7L, shipments.getByMerchantReference("SHP-1", pool)!!.orderId, "first row intact")
        assertNotNull(shipments.add(shipment("SHP-2"), pool))
        val results = Race.run(5) { shipments.add(MarketShipment(orderId = 1, providerId = "p", merchantReference = "RACE"), pool) }
        assertEquals(1, results.count { it.getOrThrow() != null })
        assertEquals(1L, count("market_shipment", "`merchantReference` = 'RACE'"))
    }

    @Test
    fun `lookups by order, carrier reference and tracking number`(): Unit = runBlocking {
        val a = shipments.add(shipment("A", order = 1), pool)!!
        val b = shipments.add(shipment("B", order = 1), pool)!!
        shipments.add(shipment("C", order = 2), pool)
        assertEquals(listOf(a, b), shipments.getByOrderId(1, pool).map { it.id })
        assertEquals(b, shipments.getByCarrierReference("manual", "car-B", pool)!!.id)
        assertNull(shipments.getByCarrierReference("other", "car-B", pool))
        assertEquals(listOf(a), shipments.getByTrackingNumber("trk-A", pool).map { it.id })
        assertTrue(shipments.getByOrderId(404, pool).isEmpty())
    }

    @Test
    fun `due polling picks the right statuses and times in order and respects the limit`(): Unit = runBlocking {
        val late = shipments.add(shipment("late", status = ShipmentStatus.IN_TRANSIT, nextPollAt = 200), pool)!!
        val early = shipments.add(shipment("early", status = ShipmentStatus.OUT_FOR_DELIVERY, nextPollAt = 100), pool)!!
        shipments.add(shipment("future", status = ShipmentStatus.IN_TRANSIT, nextPollAt = 9000), pool)
        shipments.add(shipment("none", status = ShipmentStatus.IN_TRANSIT, nextPollAt = null), pool)
        shipments.add(shipment("done", status = ShipmentStatus.DELIVERED, nextPollAt = 50), pool)
        val moving = listOf(ShipmentStatus.IN_TRANSIT, ShipmentStatus.OUT_FOR_DELIVERY)
        assertEquals(listOf(early, late), shipments.getDueForPoll(moving, 1000, 10, pool).map { it.id })
        assertEquals(listOf(early), shipments.getDueForPoll(moving, 1000, 1, pool).map { it.id })
        assertEquals(listOf(early), shipments.getDueForPoll(moving, 150, 10, pool).map { it.id })
        assertTrue(shipments.getDueForPoll(emptyList(), 1000, 10, pool).isEmpty())
    }

    @Test
    fun `status transition is a compare-and-set under a race`(): Unit = runBlocking {
        val id = shipments.add(shipment(status = ShipmentStatus.CREATED), pool)!!
        assertFalse(shipments.transition(id, ShipmentStatus.IN_TRANSIT, ShipmentStatus.DELIVERED, 1, pool))
        val results = Race.run(10) { shipments.transition(id, ShipmentStatus.CREATED, ShipmentStatus.LABEL_READY, 5, pool) }
        assertEquals(1, results.count { it.getOrThrow() })
        assertEquals(ShipmentStatus.LABEL_READY, shipments.getById(id, pool)!!.status)
        assertEquals(5L, shipments.getById(id, pool)!!.updatedAt)
    }

    @Test
    fun `a claim is held by one caller until it expires or is released`(): Unit = runBlocking {
        val id = shipments.add(MarketShipment(orderId = 1, providerId = "p", merchantReference = "CLAIM"), pool)!!
        val results = Race.run(10) { shipments.claim(id, 1000, 61000, pool) }
        assertEquals(1, results.count { it.getOrThrow() }, "exactly one concurrent claimer wins")
        assertEquals(61000L, shipments.getById(id, pool)!!.claimedUntil)
        assertFalse(shipments.claim(id, 30000, 90000, pool), "live claim blocks")
        assertTrue(shipments.claim(id, 61000, 120000, pool), "expired claim can be taken")
        assertEquals(120000L, shipments.getById(id, pool)!!.claimedUntil)
        assertTrue(shipments.releaseClaim(id, 2, pool))
        assertNull(shipments.getById(id, pool)!!.claimedUntil)
        assertTrue(shipments.claim(id, 3, 10, pool))
        assertFalse(shipments.claim(9999, 1, 2, pool))
    }

    @Test
    fun `recording a poll increments the counter atomically and can stop polling`(): Unit = runBlocking {
        val id = shipments.add(MarketShipment(orderId = 1, providerId = "p", merchantReference = "POLL", nextPollAt = 10), pool)!!
        val results = Race.run(20) { shipments.recordPoll(id, 500, 900, pool) }
        assertTrue(results.all { it.getOrThrow() })
        val r = shipments.getById(id, pool)!!
        assertEquals(listOf<Any?>(20, 500L, 900L), listOf(r.pollCount, r.lastPolledAt, r.nextPollAt))
        assertTrue(shipments.recordPoll(id, 600, null, pool))
        assertNull(shipments.getById(id, pool)!!.nextPollAt)
        assertEquals(21, shipments.getById(id, pool)!!.pollCount)
    }

    @Test
    fun `update writes the mutable columns and keeps the origin columns`(): Unit = runBlocking {
        val id = shipments.add(shipment("UPD"), pool)!!
        val changed = MarketShipment(
            id = id, orderId = 999, methodId = null, providerId = "hijack", status = ShipmentStatus.DELIVERED, entryMode = ShipmentEntryMode.CARRIER,
            merchantReference = "HIJACK", trackingNumber = "new-trk", deliveredAt = 4242, fromAddress = "{}", toAddress = "{}", updatedAt = 55
        )
        assertTrue(shipments.update(changed, pool))
        val r = shipments.getById(id, pool)!!
        assertEquals(listOf<Any?>(ShipmentStatus.DELIVERED, "new-trk", 4242L, 55L, null), listOf(r.status, r.trackingNumber, r.deliveredAt, r.updatedAt, r.methodId))
        assertEquals(listOf<Any?>(7L, "manual", "UPD", 8L, 10L), listOf(r.orderId, r.providerId, r.merchantReference, r.createdBy, r.createdAt))
        assertFalse(shipments.update(MarketShipment(id = 9999, fromAddress = "{}", toAddress = "{}"), pool))
    }

    // --- items ---

    @Test
    fun `a shipment item round-trips, is unique per shipment and order item, and lists by both keys`(): Unit = runBlocking {
        val id = items.add(MarketShipmentItem(shipmentId = 1, orderItemId = 10, quantity = 2, createdAt = 10, updatedAt = 20), pool)!!
        val r = items.getById(id, pool)!!
        assertEquals(listOf<Any?>(1L, 10L, 2, 10L, 20L), listOf(r.shipmentId, r.orderItemId, r.quantity, r.createdAt, r.updatedAt))
        assertNull(items.add(MarketShipmentItem(shipmentId = 1, orderItemId = 10, quantity = 5), pool))
        assertEquals(2, items.getById(id, pool)!!.quantity, "first row intact")
        val failure = runCatching { sql("INSERT INTO `pano_market_shipment_item` (`shipmentId`, `orderItemId`, `quantity`, `createdAt`, `updatedAt`) VALUES (1, 10, 1, 1, 1)") }.exceptionOrNull()
        assertNotNull(failure)
        val other = items.add(MarketShipmentItem(shipmentId = 1, orderItemId = 11, quantity = 1), pool)!!
        val sameItem = items.add(MarketShipmentItem(shipmentId = 2, orderItemId = 10, quantity = 3), pool)!!
        assertEquals(listOf(id, other), items.getByShipmentId(1, pool).map { it.id })
        assertEquals(listOf(id, sameItem), items.getByOrderItemId(10, pool).map { it.id })
        val results = Race.run(4) { items.add(MarketShipmentItem(shipmentId = 5, orderItemId = 5, quantity = 1), pool) }
        assertEquals(1, results.count { it.getOrThrow() != null })

        assertTrue(items.update(MarketShipmentItem(id = id, shipmentId = 77, orderItemId = 77, quantity = 9, updatedAt = 1), pool))
        val updated = items.getById(id, pool)!!
        assertEquals(listOf<Any?>(1L, 10L, 9), listOf(updated.shipmentId, updated.orderItemId, updated.quantity))
        assertEquals(2, items.deleteByShipmentId(1, pool))
        assertEquals(0, items.deleteByShipmentId(1, pool))
        assertEquals(listOf(sameItem), items.getByOrderItemId(10, pool).map { it.id })
    }

    // --- events ---

    private fun event(shipment: Long = 1, key: String = "k1", at: Long = 100, status: ShipmentStatus = ShipmentStatus.IN_TRANSIT) = MarketShipmentEvent(
        shipmentId = shipment, status = status, rawStatus = "RAW", description = "Arrived", location = "Berlin", occurredAt = at,
        source = ShipmentEventSource.WEBHOOK, dedupeKey = key, createdAt = 10, updatedAt = 20
    )

    @Test
    fun `an event round-trips every column and a replay of the same dedupe key is a no-op`(): Unit = runBlocking {
        val id = events.add(event(), pool)!!
        val r = events.getById(id, pool)!!
        assertEquals(
            listOf<Any?>(1L, ShipmentStatus.IN_TRANSIT, "RAW", "Arrived", "Berlin", 100L, ShipmentEventSource.WEBHOOK, "k1", 10L, 20L),
            listOf(r.shipmentId, r.status, r.rawStatus, r.description, r.location, r.occurredAt, r.source, r.dedupeKey, r.createdAt, r.updatedAt)
        )
        assertNull(events.add(event(status = ShipmentStatus.DELIVERED), pool), "replay with the same key")
        assertEquals(ShipmentStatus.IN_TRANSIT, events.getByDedupeKey(1, "k1", pool)!!.status, "first row intact")
        assertEquals(1L, events.countByShipmentId(1, pool))
        assertNotNull(events.add(event(shipment = 2), pool), "same key on another shipment")
        assertNotNull(events.add(event(key = "k2"), pool), "other key")
        val failure = runCatching {
            sql("INSERT INTO `pano_market_shipment_event` (`shipmentId`, `status`, `occurredAt`, `source`, `dedupeKey`, `createdAt`, `updatedAt`) VALUES (1, 'CREATED', 1, 'POLL', 'k1', 1, 1)")
        }.exceptionOrNull()
        assertNotNull(failure, "raw duplicate must fail")
        val results = Race.run(6) { events.add(event(shipment = 9, key = "race"), pool) }
        assertEquals(1, results.count { it.getOrThrow() != null })
        assertEquals(1L, events.countByShipmentId(9, pool))
        assertNull(events.getByDedupeKey(1, "nope", pool))
    }

    @Test
    fun `events are listed in occurrence order and only the display text can be corrected`(): Unit = runBlocking {
        val late = events.add(event(key = "late", at = 300), pool)!!
        val early = events.add(event(key = "early", at = 100), pool)!!
        val tie = events.add(event(key = "tie", at = 100), pool)!!
        assertEquals(listOf(early, tie, late), events.getByShipmentId(1, pool).map { it.id })
        assertTrue(events.getByShipmentId(404, pool).isEmpty())
        assertEquals(0L, events.countByShipmentId(404, pool))
        assertTrue(events.update(MarketShipmentEvent(id = early, shipmentId = 5, status = ShipmentStatus.LOST, description = "fixed", location = null, dedupeKey = "z", updatedAt = 8), pool))
        val r = events.getById(early, pool)!!
        assertEquals(listOf<Any?>(1L, ShipmentStatus.IN_TRANSIT, "early", "fixed", null, 8L), listOf(r.shipmentId, r.status, r.dedupeKey, r.description, r.location, r.updatedAt))
    }
}
