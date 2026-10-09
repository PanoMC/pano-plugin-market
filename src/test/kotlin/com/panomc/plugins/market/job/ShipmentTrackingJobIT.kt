package com.panomc.plugins.market.job

import com.panomc.plugins.market.db.model.MarketShipmentEvent
import com.panomc.plugins.market.db.model.ShipmentEventSource
import com.panomc.plugins.market.db.model.ShipmentStatus
import com.panomc.plugins.market.db.model.ShippingStatus
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.shipping.ShipmentTarget
import com.panomc.plugins.market.spi.shipping.TrackingUpdate
import com.panomc.plugins.market.support.ShippingTrackingITBase
import com.panomc.plugins.market.support.ShippingTrackingITBase.TrackingCarrier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.panomc.plugins.market.spi.shipping.ShipmentStatus as Spi

/**
 * `ShipmentTrackingJob` on a real MariaDB (MK-134; 10 sections 7.2, 10.2 and 16 tests 42 to 49, 63, 64): the polling cadence and its stale end,
 * `trackBatchSize` chunks, the conditional claim (two runs poll a shipment once), skipped providers keep `nextPollAt`, a failing or hanging carrier
 * reschedules, out-of-order events are stored and the newest wins inside the moving set, a terminal state ends polling, the derived `shippingStatus`
 * and the store webhooks `shipment.shipped` / `shipment.delivered` once per shipment. The carrier is [ShippingTrackingITBase.TrackingCarrier].
 */
class ShipmentTrackingJobIT : ShippingTrackingITBase() {
    private fun job(timeoutMs: Long = 30_000, perProvider: Int = ShipmentTrackingJob.PER_PROVIDER) =
        ShipmentTrackingJob(w.clock, w.shipments, service, { pool }, trackTimeoutMs = timeoutMs, perProvider = perProvider)

    private val hour = 3_600_000L
    private val day = 24 * hour

    /** Moves the clock to the moment the shipment is due. */
    private suspend fun due(id: Long) {
        val at = shipmentNow(id).nextPollAt!!

        if (at > w.clock.now()) w.clock.set(at)
    }

    // ================================================================================================ cadence

    @Test
    fun `a shipment is polled when it is due, once, and the next poll follows the schedule`(): Unit = runBlocking {
        val o = order("Shirt" to 1)
        val s = shipCarrier(o)

        assertEquals(s.createdAt + hour, s.nextPollAt, "the first poll is one hour after creation")
        assertEquals(0, job().runOnce(), "not due yet")
        assertEquals(0, carrier.tracks.size)

        carrier.onTrack = { listOf(updateFor(s, event(Spi.IN_TRANSIT, w.clock.now(), "e1"))) }
        due(s.id)

        assertEquals(1, job().runOnce())

        val row = shipmentNow(s.id)

        assertEquals(1, carrier.tracks.size)
        assertEquals(listOf(s.id), carrier.tracks.single().shipments.map { it.id })
        assertEquals(ShipmentStatus.IN_TRANSIT, row.status)
        assertEquals(1, row.pollCount)
        assertEquals(w.clock.now(), row.lastPolledAt)
        assertEquals(w.clock.now() + 3 * hour, row.nextPollAt, "age below two days: 3 h")
        assertFalse(row.stale)
        assertEquals(ShippingStatus.SHIPPED, orderNow(o.id).shippingStatus)
        assertEquals(listOf(Triple("IN_TRANSIT", "POLL", "e1")), shipmentEvents(s.id))

        // the claim keeps it quiet until it is due again
        assertEquals(0, job().runOnce())
        assertEquals(1, carrier.tracks.size)
    }

    @Test
    fun `the cadence is 3 h until day 2, 6 h until day 10, 12 h until day 45, then the shipment is flagged stale and polling stops`(): Unit = runBlocking {
        val o = order("Shirt" to 1)
        val s = shipCarrier(o)
        val created = s.createdAt

        fun at(offset: Long) = w.clock.set(created + offset)

        at(hour)
        assertEquals(1, job().runOnce())
        assertEquals(created + hour + 3 * hour, shipmentNow(s.id).nextPollAt)

        at(3 * day)
        assertEquals(1, job().runOnce())
        assertEquals(created + 3 * day + 6 * hour, shipmentNow(s.id).nextPollAt)

        at(11 * day)
        assertEquals(1, job().runOnce())
        assertEquals(created + 11 * day + 12 * hour, shipmentNow(s.id).nextPollAt)
        assertFalse(shipmentNow(s.id).stale)

        at(46 * day)
        assertEquals(1, job().runOnce())

        val stale = shipmentNow(s.id)

        assertNull(stale.nextPollAt, "past day 45 polling stops")
        assertTrue(stale.stale, "and the shipment is flagged")
        assertEquals(4, stale.pollCount)

        w.clock.advance(100 * day)
        assertEquals(0, job().runOnce(), "a stale shipment is never selected again")
        assertEquals(4, carrier.tracks.size)
    }

    // ================================================================================================ batches and claims

    @Test
    fun `batches respect trackBatchSize, each shipment is polled once`(): Unit = runBlocking {
        carrier.caps = TrackingCarrier.caps(trackBatchSize = 2)

        val o = order("Shirt" to 5)
        val rows = List(5) { shipCarrier(o) }

        w.clock.advance(2 * hour)

        assertEquals(5, job().runOnce())
        assertEquals(listOf(2, 2, 1), carrier.tracks.map { it.shipments.size })
        assertEquals(rows.map { it.id }.toSet(), carrier.tracks.flatMap { r -> r.shipments.map { it.id } }.toSet())
        assertTrue(rows.all { shipmentNow(it.id).pollCount == 1 })
    }

    @Test
    fun `at most perProvider shipments of a provider are polled in one run, the rest in the next`(): Unit = runBlocking {
        val o = order("Shirt" to 5)
        val rows = List(5) { shipCarrier(o) }

        w.clock.advance(2 * hour)

        assertEquals(3, job(perProvider = 3).runOnce())
        assertEquals(3, carrier.tracks.sumOf { it.shipments.size })
        assertEquals(2, job(perProvider = 3).runOnce())
        assertEquals(5, carrier.tracks.sumOf { it.shipments.size })
        assertTrue(rows.all { shipmentNow(it.id).pollCount == 1 })
    }

    @Test
    fun `two runs at the same time poll a shipment once`(): Unit = runBlocking {
        val o = order("Shirt" to 3)
        val rows = List(3) { shipCarrier(o) }

        carrier.onTrack = {
            delay(300)

            emptyList()
        }
        w.clock.advance(2 * hour)

        val handled = coroutineScope { listOf(async(Dispatchers.IO) { job().runOnce() }, async(Dispatchers.IO) { job().runOnce() }).awaitAll() }

        assertEquals(3, handled.sum(), "every shipment is handled by exactly one of the runs")
        assertEquals(3, carrier.tracks.sumOf { it.shipments.size })
        assertEquals(rows.map { it.id }.toSet(), carrier.tracks.flatMap { r -> r.shipments.map { it.id } }.toSet())
        assertTrue(rows.all { shipmentNow(it.id).pollCount == 1 })
    }

    @Test
    fun `a claimed shipment whose run died is due again after the lease`(): Unit = runBlocking {
        val o = order("Shirt" to 1)
        val s = shipCarrier(o)

        w.clock.advance(2 * hour)

        // a runner claimed it and died before the bookkeeping
        assertTrue(w.shipments.claimPoll(s.id, shipmentNow(s.id).nextPollAt!!, w.clock.now() + ShipmentTrackingJob.CLAIM_MS, w.clock.now(), pool))
        assertFalse(w.shipments.claimPoll(s.id, s.nextPollAt!!, w.clock.now() + ShipmentTrackingJob.CLAIM_MS, w.clock.now(), pool), "a stale view of nextPollAt loses")
        assertEquals(0, job().runOnce())

        w.clock.advance(ShipmentTrackingJob.CLAIM_MS + 1)

        assertEquals(1, job().runOnce())
        assertEquals(1, shipmentNow(s.id).pollCount)
    }

    // ================================================================================================ providers that cannot be asked

    @Test
    fun `shipments of a provider that is not registered, not enabled or without trackingPull are skipped and keep nextPollAt`(): Unit = runBlocking {
        val o = order("Shirt" to 1)
        val s = shipCarrier(o)
        val planned = s.nextPollAt

        w.clock.advance(2 * hour)

        // not registered
        lookup.remove(carrier.id)
        assertEquals(0, job().runOnce())
        lookup.addShipping(carrier)

        // no trackingPull
        carrier.caps = TrackingCarrier.caps(trackingPull = false)
        assertEquals(0, job().runOnce())
        carrier.caps = TrackingCarrier.caps()

        // not enabled
        val row = w.shippingCarriers.getByProviderId(carrier.id, pool)!!

        w.shippingCarriers.update(
            com.panomc.plugins.market.db.model.MarketShippingCarrier(
                id = row.id, providerId = row.providerId, enabled = false, settings = row.settings, testMode = row.testMode, webhookToken = row.webhookToken,
                createdAt = row.createdAt, updatedAt = w.clock.now()
            ),
            pool
        )
        assertEquals(0, job().runOnce())

        val after = shipmentNow(s.id)

        assertEquals(0, carrier.tracks.size)
        assertEquals(planned, after.nextPollAt, "nextPollAt untouched")
        assertEquals(0, after.pollCount)
        assertNull(after.lastPolledAt)
    }

    @Test
    fun `210 due shipments of a provider that is not registered do not starve the shipment of a registered carrier`(): Unit = runBlocking {
        val o = order("Shirt" to 1)
        val real = shipCarrier(o)
        val now = w.clock.now()

        // the carrier plugin of "ghost" was removed after sales: 210 open, due shipments that are older than the real one
        repeat(210) { n ->
            w.shipments.add(
                com.panomc.plugins.market.db.model.MarketShipment(
                    orderId = o.id, providerId = "ghost", status = ShipmentStatus.IN_TRANSIT, merchantReference = "ghost-ref-$n", carrierReference = "GHOST-$n",
                    trackingNumber = "GH$n", nextPollAt = now - day + n, createdAt = now - 2 * day, updatedAt = now - 2 * day
                ),
                pool
            )
        }

        carrier.onTrack = { listOf(updateFor(real, event(Spi.IN_TRANSIT, w.clock.now(), "e-starve"))) }
        w.clock.advance(2 * hour)

        assertEquals(1, job().runOnce(), "only the registered carrier's shipment is polled")
        assertEquals(1, carrier.tracks.size)
        assertEquals(listOf(real.id), carrier.tracks.single().shipments.map { it.id })
        assertEquals(1, shipmentNow(real.id).pollCount)
        assertEquals(ShipmentStatus.IN_TRANSIT, shipmentNow(real.id).status)

        val ghosts = sql("SELECT COUNT(*) AS c FROM `pano_market_shipment` WHERE `providerId` = 'ghost' AND `pollCount` = 0 AND `nextPollAt` <= ?", w.clock.now())

        assertEquals(210L, ghosts.single().getLong("c"), "the skipped provider's shipments keep nextPollAt and pollCount")
    }

    @Test
    fun `a manual shipment is never polled`(): Unit = runBlocking {
        val o = order("Shirt" to 1)
        val body = io.vertx.core.json.JsonObject().put("providerId", "manual").put("parcels", io.vertx.core.json.JsonArray().add(io.vertx.core.json.JsonObject().put("weightGrams", 500)))
            .put("items", io.vertx.core.json.JsonArray().add(io.vertx.core.json.JsonObject().put("orderItemId", o.items.first()).put("quantity", 1)))
            .put("manual", io.vertx.core.json.JsonObject().put("carrierName", "DHL").put("trackingNumber", "TRK123456"))
        val id = service.createShipment(o.id, body, 7, pool).getLong("id")

        assertNull(shipmentNow(id).nextPollAt)

        w.clock.advance(100 * day)

        assertEquals(0, job().runOnce())
        assertEquals(0, carrier.tracks.size)
    }

    // ================================================================================================ failures

    @Test
    fun `a carrier failure reschedules by the schedule, counts the poll and keeps the error on the carrier row`(): Unit = runBlocking {
        val o = order("Shirt" to 1)
        val s = shipCarrier(o)

        carrier.onTrack = { throw ProviderException(ProviderErrorCode.GATEWAY_UNREACHABLE, "down") }
        due(s.id)

        assertEquals(1, job().runOnce())

        val row = shipmentNow(s.id)

        assertEquals(1, row.pollCount)
        assertEquals(w.clock.now(), row.lastPolledAt)
        assertEquals(w.clock.now() + 3 * hour, row.nextPollAt)
        assertEquals(ShipmentStatus.CREATED, row.status)
        assertEquals(0, shipmentEvents(s.id).size, "the shipment itself is untouched")
        assertEquals("GATEWAY_UNREACHABLE", w.shippingCarriers.getByProviderId(carrier.id, pool)!!.lastError)
        assertNotNull(w.shippingCarriers.getByProviderId(carrier.id, pool)!!.lastErrorAt)
    }

    @Test
    fun `a carrier that does not answer in time is a TIMEOUT, the run goes on and the shipment is rescheduled`(): Unit = runBlocking {
        val o = order("Shirt" to 1)
        val s = shipCarrier(o)

        carrier.onTrack = {
            delay(2_000)

            emptyList()
        }
        due(s.id)

        assertEquals(1, job(timeoutMs = 100).runOnce())
        assertEquals("TIMEOUT", w.shippingCarriers.getByProviderId(carrier.id, pool)!!.lastError)
        assertEquals(1, shipmentNow(s.id).pollCount)
        assertEquals(w.clock.now() + 3 * hour, shipmentNow(s.id).nextPollAt)
    }

    @Test
    fun `an update that names nobody of the chunk is ignored`(): Unit = runBlocking {
        val o = order("Shirt" to 1)
        val s = shipCarrier(o)

        carrier.onTrack = { listOf(TrackingUpdate(ShipmentTarget.CarrierReference("somebody-else"), listOf(event(Spi.DELIVERED, w.clock.now(), "x")))) }
        due(s.id)

        assertEquals(1, job().runOnce())
        assertEquals(ShipmentStatus.CREATED, shipmentNow(s.id).status)
        assertEquals(0, shipmentEvents(s.id).size)
        assertEquals(1, shipmentNow(s.id).pollCount)
    }

    // ================================================================================================ events

    @Test
    fun `out-of-order events are stored and the newest wins inside the moving set, a duplicate is stored once`(): Unit = runBlocking {
        val o = order("Shirt" to 1)
        val s = shipCarrier(o)
        val t0 = w.clock.now()

        // the carrier lists the newer event first
        carrier.onTrack = { listOf(updateFor(s, event(Spi.OUT_FOR_DELIVERY, t0 + 4_000, "e4"), event(Spi.IN_TRANSIT, t0 + 2_000, "e2"))) }
        due(s.id)
        job().runOnce()

        assertEquals(ShipmentStatus.OUT_FOR_DELIVERY, shipmentNow(s.id).status, "the newest by occurredAt wins")
        assertEquals(setOf("e4", "e2"), shipmentEvents(s.id).map { it.third }.toSet(), "both stored")

        // the same facts again, and a failed delivery attempt: back to IN_TRANSIT
        carrier.onTrack = { listOf(updateFor(s, event(Spi.OUT_FOR_DELIVERY, t0 + 4_000, "e4"), event(Spi.IN_TRANSIT, t0 + 6_000, "e6"))) }
        due(s.id)
        job().runOnce()

        assertEquals(ShipmentStatus.IN_TRANSIT, shipmentNow(s.id).status)
        assertEquals(3, shipmentEvents(s.id).size, "e4 was not stored twice")
        assertTrue(shipmentEvents(s.id).all { it.second == "POLL" })
    }

    @Test
    fun `the dedupe key is unique per shipment at the database, the same event without id hashes to one row`(): Unit = runBlocking {
        val o = order("Shirt" to 1)
        val s = shipCarrier(o)
        val now = w.clock.now()

        fun row(key: String) = MarketShipmentEvent(
            shipmentId = s.id, status = ShipmentStatus.IN_TRANSIT, occurredAt = now, source = ShipmentEventSource.POLL, dedupeKey = key, createdAt = now, updatedAt = now
        )

        assertNotNull(w.shipmentEvents.add(row("k1"), pool))
        assertNull(w.shipmentEvents.add(row("k1"), pool), "INSERT IGNORE on uq_shipment_event")
        assertEquals(1, count("market_shipment_event", "`shipmentId` = ?", s.id))

        // without an eventId the key is a hash of the facts: a repeated poll answer adds nothing
        carrier.onTrack = { listOf(updateFor(s, event(Spi.IN_TRANSIT, now + 1_000).also { it.location = "Berlin"; it.rawStatus = "T1" })) }
        due(s.id)
        job().runOnce()
        due(s.id)
        job().runOnce()

        assertEquals(2, count("market_shipment_event", "`shipmentId` = ?", s.id), "k1 plus one hashed event")
        assertEquals(2, shipmentNow(s.id).pollCount)
    }

    @Test
    fun `a terminal state stops polling, sets deliveredAt from the event and queues one mail and one store webhook`(): Unit = runBlocking {
        val endpoint = webhooks.endpoint("https://hooks.example.com/market", events = "[\"shipment.shipped\",\"shipment.delivered\"]")
        val o = order("Shirt" to 1)
        val s = shipCarrier(o)
        val t0 = w.clock.now()

        carrier.onTrack = { listOf(updateFor(s, event(Spi.IN_TRANSIT, t0 + 1_000, "p1"))) }
        due(s.id)
        job().runOnce()

        assertEquals(ShippingStatus.SHIPPED, orderNow(o.id).shippingStatus)

        carrier.onTrack = { listOf(updateFor(s, event(Spi.IN_TRANSIT, t0 + 1_000, "p1"), event(Spi.DELIVERED, t0 + 9_000, "p2"))) }
        due(s.id)
        job().runOnce()

        val row = shipmentNow(s.id)

        assertEquals(ShipmentStatus.DELIVERED, row.status)
        assertEquals(t0 + 9_000, row.deliveredAt)
        assertNull(row.nextPollAt)
        assertEquals(ShippingStatus.DELIVERED, orderNow(o.id).shippingStatus)
        assertEquals(listOf(s.id), mails("SHIPMENT_DELIVERED"))
        assertEquals(1, mails("SHIPMENT_SHIPPED").size)

        val rows = webhooks.rows()

        assertEquals(1, rows.count { it.event == "market.shipment.shipped" }, "shipped once")
        assertEquals(1, rows.count { it.event == "market.shipment.delivered" }, "delivered once")
        assertTrue(rows.all { it.endpointId == endpoint.id })

        w.clock.advance(10 * day)
        assertEquals(0, job().runOnce(), "a delivered shipment is not polled again")
        assertEquals(2, carrier.tracks.size)
        assertEquals(2, webhooks.rows().size)
    }

    @Test
    fun `the derived shippingStatus follows the polls - PARTIAL, SHIPPED, DELIVERED`(): Unit = runBlocking {
        val o = order("Shirt" to 2)
        val a = shipCarrier(o)
        val b = shipCarrier(o)
        val t0 = w.clock.now()

        assertEquals(ShippingStatus.PENDING, orderNow(o.id).shippingStatus)

        carrier.onTrack = { req -> req.shipments.filter { it.id == a.id }.map { updateFor(a, event(Spi.IN_TRANSIT, t0 + 1_000, "a1")) } }
        w.clock.advance(2 * hour)
        job().runOnce()

        assertEquals(ShippingStatus.PARTIAL, orderNow(o.id).shippingStatus, "one of two units is with the carrier")

        carrier.onTrack = { req -> req.shipments.filter { it.id == b.id }.map { updateFor(b, event(Spi.OUT_FOR_DELIVERY, t0 + 2_000, "b1")) } }
        w.clock.advance(4 * hour)
        job().runOnce()

        assertEquals(ShippingStatus.SHIPPED, orderNow(o.id).shippingStatus)

        carrier.onTrack = { req -> req.shipments.map { updateFor(shipmentNowBlocking(it.id), event(Spi.DELIVERED, t0 + 9_000, "d${it.id}")) } }
        w.clock.advance(4 * hour)
        job().runOnce()

        assertEquals(ShippingStatus.DELIVERED, orderNow(o.id).shippingStatus)
        assertTrue(listOf(a, b).all { shipmentNow(it.id).status == ShipmentStatus.DELIVERED })
    }

    private fun shipmentNowBlocking(id: Long) = runBlocking { shipmentNow(id) }

    // ================================================================================================ the scheduler

    @Test
    fun `the job runs as a scheduler job named shipment-tracking`(): Unit = runBlocking {
        val o = order("Shirt" to 1)
        val s = shipCarrier(o)
        val scheduler = MarketScheduler(w.clock, listOf(MarketJobs.shipmentTracking(job())))

        assertEquals(0, scheduler.tick(), "nothing due")

        carrier.onTrack = { listOf(updateFor(s, event(Spi.IN_TRANSIT, w.clock.now(), "sched"))) }
        w.clock.advance(2 * hour)

        assertEquals(1, scheduler.tick())
        assertEquals("shipment-tracking", scheduler.stats().single().name)
        assertEquals(MarketScheduler.SHIPMENT_TRACKING_MS, 60_000L)
        assertEquals(ShipmentStatus.IN_TRANSIT, shipmentNow(s.id).status)
    }
}
