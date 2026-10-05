package com.panomc.plugins.market.routes.api.shipping

import com.panomc.plugins.market.db.model.MarketPaymentEvent
import com.panomc.plugins.market.db.model.PaymentEventStatus
import com.panomc.plugins.market.db.model.ShipmentStatus
import com.panomc.plugins.market.db.model.ShippingStatus
import com.panomc.plugins.market.job.InboundEventRetryJob
import com.panomc.plugins.market.routes.api.payment.AttemptLocks
import com.panomc.plugins.market.routes.api.payment.DbInboundEventStore
import com.panomc.plugins.market.routes.api.payment.InboundAttempts
import com.panomc.plugins.market.routes.api.payment.InboundCall
import com.panomc.plugins.market.routes.api.payment.InboundDispatcher
import com.panomc.plugins.market.routes.api.payment.InboundProviders
import com.panomc.plugins.market.routes.api.payment.PaymentEventApplier
import com.panomc.plugins.market.routes.api.payment.PaymentEventSink
import com.panomc.plugins.market.runtime.MarketRuntime
import com.panomc.plugins.market.spi.common.HttpReply
import com.panomc.plugins.market.spi.common.InboundKind
import com.panomc.plugins.market.spi.shipping.CreateShipmentResult
import com.panomc.plugins.market.spi.shipping.ShipmentPiece
import com.panomc.plugins.market.spi.shipping.ShipmentTarget
import com.panomc.plugins.market.spi.shipping.ShippingInboundResult
import com.panomc.plugins.market.spi.shipping.TrackingUpdate
import com.panomc.plugins.market.support.ShippingTrackingITBase
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import com.panomc.plugins.market.spi.shipping.ShipmentStatus as Spi

/**
 * The inbound shipping webhook (MK-134; 03 section 6, 10 section 10.1, 10 section 16 tests 65 and 66) on a real MariaDB: the install token (constant-time,
 * wrong token or provider => 404 with no row and no provider call), the raw log in `market_payment_event` under `shipping:<id>`, an unverified request
 * applies nothing, the target resolution (merchant reference, carrier reference, tracking number, per-piece number, id of this provider only), unknown
 * shipments are skipped and counted, the delivery key de-duplication (only with an `eventKey`), a failing provider is `FAILED` + 503 and is run again by the
 * retry job, the unsigned-trigger contract (the provider re-fetches through `ctx.shipments` and `track` before it reports), out-of-order and terminal
 * events, and the store webhooks once per shipment.
 *
 * The scripted provider reads a JSON body: `{"verified": bool, "eventKey": "...", "status": 202, "updates": [{"target": {"by": "merchant|carrier|tracking|id",
 * "value": "..."}, "events": [{"status": "IN_TRANSIT", "at": <ms>, "id": "..."}]}]}`.
 */
class ShippingWebhookIT : ShippingTrackingITBase() {
    @Volatile private var state = MarketRuntime.State.READY

    private lateinit var store: DbInboundEventStore
    private lateinit var dispatcher: ShippingInboundDispatcher

    @BeforeEach
    fun freshWebhookState() {
        state = MarketRuntime.State.READY
        store = DbInboundEventStore(w.paymentEvents) { pool }
        dispatcher = dispatcherOf()
        carrier.onInbound = { _, request -> scripted(JsonObject(request.bodyAsString())) }
    }

    private fun dispatcherOf(timeoutMs: Long = 5_000L) =
        ShippingInboundDispatcher(store, w.shippingCarriers, service, { pool }, w.clock, w.ids, { state }, providerTimeoutMs = timeoutMs)

    private fun scripted(body: JsonObject): ShippingInboundResult {
        val reply = HttpReply(body.getInteger("status", 200), "text/plain", "carrier-reply".toByteArray()).also { it.headers = mapOf("Set-Cookie" to "x=1", "X-Carrier" to "yes") }

        if (!body.getBoolean("verified", true)) return ShippingInboundResult.rejected(reply, "bad signature")

        val updates = (body.getJsonArray("updates") ?: JsonArray()).map { u ->
            val o = u as JsonObject
            val t = o.getJsonObject("target")
            val target = when (t.getString("by")) {
                "merchant" -> ShipmentTarget.MerchantReference(t.getString("value"))
                "carrier" -> ShipmentTarget.CarrierReference(t.getString("value"))
                "tracking" -> ShipmentTarget.TrackingNumber(t.getString("value"))
                else -> ShipmentTarget.Id(t.getString("value").toLong())
            }

            TrackingUpdate(target, o.getJsonArray("events").map { e -> (e as JsonObject).let { event(Spi.valueOf(it.getString("status")), it.getLong("at"), it.getString("id")) } })
        }

        return ShippingInboundResult.accepted(reply, updates, body.getString("eventKey"))
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private fun update(by: String, value: String, status: String, at: Long, id: String? = null) = JsonObject()
        .put("target", JsonObject().put("by", by).put("value", value))
        .put("events", JsonArray().add(JsonObject().put("status", status).put("at", at).also { if (id != null) it.put("id", id) }))

    private fun body(vararg updates: JsonObject, verified: Boolean = true, eventKey: String? = null, status: Int = 200) =
        JsonObject().put("verified", verified).put("status", status).put("updates", JsonArray(updates.toList())).also { if (eventKey != null) it.put("eventKey", eventKey) }.encode()

    private fun call(
        body: String, provider: String = carrier.id, token: String = TOKEN, channel: String = "default", headers: Map<String, List<String>> = mapOf("content-type" to listOf("application/json"))
    ) = InboundCall(
        InboundKind.WEBHOOK, provider, channel, null, null, null, "POST", "/api/market/shipping/$provider/webhook/$token" + if (channel == "default") "" else "/$channel",
        null, emptyMap(), headers, headers["content-type"]?.firstOrNull(), body.toByteArray(), null, "203.0.113.9", w.clock.now()
    )

    private suspend fun post(body: String, provider: String = carrier.id, token: String = TOKEN, headers: Map<String, List<String>> = mapOf("content-type" to listOf("application/json"))) =
        dispatcher.handle(call(body, provider, token, headers = headers), token)

    private suspend fun rows(): List<MarketPaymentEvent> =
        sql("SELECT `id` FROM `pano_market_payment_event` WHERE `providerId` LIKE 'shipping:%' ORDER BY `id`").map { w.paymentEvents.getById(it.getLong("id"), pool)!! }

    private suspend fun rowCount() = count("market_payment_event")

    /** A carrier that numbers what it creates: merchant reference is the platform's, `CAR-<id>` the carrier reference, `TN-<id>` the master, `PC-<id>` the piece. */
    private fun numbering() {
        carrier.onCreate = { r ->
            CreateShipmentResult.Created("CAR-${r.shipmentId}").also {
                it.trackingNumber = "TN-${r.shipmentId}"
                it.pieces = listOf(ShipmentPiece("PC-${r.shipmentId}"))
            }
        }
    }

    // ================================================================================================ the install token

    @Test
    fun `a wrong token, an unknown provider or the token of another carrier is a 404 with an empty body, no row and no provider call`(): Unit = runBlocking {
        val reply = post(body(), token = "a".repeat(40))

        assertEquals(404, reply.status)
        assertEquals(0, reply.body.size)

        val unknown = post(body(), provider = "nobody")

        assertEquals(404, unknown.status)

        // another carrier's token on this carrier's path
        carrierRow(providerId = "other", token = "c".repeat(40))

        assertEquals(404, post(body(), token = "c".repeat(40)).status)
        assertEquals(404, post(body(), provider = "manual", token = TOKEN).status, "the built-in manual carrier has a row but nothing to deliver")

        assertEquals(0, carrier.inbound.size, "no provider code ran")
        assertEquals(0, rowCount(), "nothing was stored for a stranger")
    }

    @Test
    fun `the token comparison is constant-time and exact`() {
        assertTrue(ShippingInboundDispatcher.constantTimeEquals(TOKEN, TOKEN))
        assertFalse(ShippingInboundDispatcher.constantTimeEquals(TOKEN, TOKEN.dropLast(1) + "1"))
        assertFalse(ShippingInboundDispatcher.constantTimeEquals(TOKEN, TOKEN.dropLast(1)), "a prefix is not the token")
        assertFalse(ShippingInboundDispatcher.constantTimeEquals(TOKEN, TOKEN + "0"))
        assertFalse(ShippingInboundDispatcher.constantTimeEquals(TOKEN, ""))
    }

    @Test
    fun `the provider sees its own webhook url with the install token and a shipment lookup bound to its own rows`(): Unit = runBlocking {
        numbering()

        val o = order("Shirt" to 2)
        val mine = shipCarrier(o)

        // a shipment of another provider with a clashing carrier reference
        val other = TrackingCarrier("other")

        lookup.addShipping(other)
        carrierRow(providerId = other.id, token = "d".repeat(40))
        other.onCreate = { r -> CreateShipmentResult.Created("CAR-${r.shipmentId}").also { it.trackingNumber = "OTHER-TN" } }

        val theirs = shipCarrier(o, provider = other)

        var seen: Triple<String, Long?, Long?>? = null

        carrier.onInbound = { ctx, _ ->
            seen = Triple(
                ctx.urls.webhook() + "|" + ctx.urls.webhook("events"),
                ctx.shipments.byCarrierReference(mine.carrierReference!!)?.id,
                ctx.shipments.byCarrierReference(theirs.carrierReference!!)?.id
            )

            ShippingInboundResult.ignored(HttpReply.text("ok"))
        }

        assertEquals(200, post(body()).status)
        assertEquals("https://shop.example/api/market/shipping/tracker/webhook/$TOKEN|https://shop.example/api/market/shipping/tracker/webhook/$TOKEN/events", seen!!.first)
        assertEquals(mine.id, seen!!.second)
        assertNull(seen!!.third, "another provider's shipment is not visible")

        carrier.onInbound = { ctx, _ ->
            seen = Triple("", ctx.shipments.byTrackingNumber("TN-${mine.id}")?.id, ctx.shipments.byMerchantReference(theirs.merchantReference)?.id)

            ShippingInboundResult.ignored(HttpReply.text("ok"))
        }
        post(body())

        assertEquals(mine.id, seen!!.second)
        assertNull(seen!!.third)
    }

    // ================================================================================================ verification

    @Test
    fun `an unverified request applies nothing, the row is REJECTED and the provider's reply goes out`(): Unit = runBlocking {
        numbering()

        val o = order("Shirt" to 1)
        val s = shipCarrier(o)
        val reply = post(body(update("carrier", s.carrierReference!!, "DELIVERED", w.clock.now(), "e1"), verified = false, status = 401))
        val row = rows().single()

        assertEquals(401, reply.status)
        assertEquals("carrier-reply", String(reply.body))
        assertEquals(PaymentEventStatus.REJECTED, row.status)
        assertEquals(false, row.verified)
        assertEquals("bad signature", row.error)
        assertEquals(0, shipmentEvents(s.id).size)
        assertEquals(ShipmentStatus.CREATED, shipmentNow(s.id).status)
        assertNull(w.shippingCarriers.getByProviderId(carrier.id, pool)!!.lastInboundAt, "lastInboundAt only for verified requests")
    }

    @Test
    fun `an unsigned trigger webhook is only believed after the provider re-fetched the state, the claim in its body is ignored`(): Unit = runBlocking {
        numbering()

        val o = order("Shirt" to 1)
        val s = shipCarrier(o)
        val t = w.clock.now()

        // the body CLAIMS delivery; the provider ignores it, asks the carrier (track) and reports what the carrier says
        carrier.onTrack = { req -> req.shipments.map { TrackingUpdate(ShipmentTarget.CarrierReference(it.carrierReference!!), listOf(event(Spi.IN_TRANSIT, t + 500, "fetched"))) } }
        carrier.onInbound = { ctx, _ ->
            val view = ctx.shipments.byCarrierReference(s.carrierReference!!)!!
            val fresh = carrier.track(ctx, com.panomc.plugins.market.spi.shipping.TrackRequest(listOf(view)))

            ShippingInboundResult.accepted(HttpReply.empty(200), fresh)
        }

        assertEquals(200, post("""{"state":"DELIVERED"}""").status)
        assertEquals(1, carrier.tracks.size, "one outbound fetch before the update")
        assertEquals(ShipmentStatus.IN_TRANSIT, shipmentNow(s.id).status, "what the carrier answered, not what the request claimed")
        assertEquals(listOf("fetched"), shipmentEvents(s.id).map { it.third })
    }

    // ================================================================================================ targets

    @Test
    fun `an update is resolved by merchant reference, carrier reference, tracking number and the number of one piece`(): Unit = runBlocking {
        numbering()

        val o = order("Shirt" to 4)
        val a = shipCarrier(o)
        val b = shipCarrier(o)
        val c = shipCarrier(o)
        val d = shipCarrier(o)
        val t = w.clock.now()

        val reply = post(
            body(
                update("merchant", a.merchantReference, "IN_TRANSIT", t + 1, "ma"),
                update("carrier", b.carrierReference!!, "IN_TRANSIT", t + 1, "cb"),
                update("tracking", "TN-${c.id}", "IN_TRANSIT", t + 1, "tc"),
                update("tracking", "PC-${d.id}", "IN_TRANSIT", t + 1, "pd")
            )
        )

        assertEquals(200, reply.status)
        assertEquals(listOf("IN_TRANSIT", "IN_TRANSIT", "IN_TRANSIT", "IN_TRANSIT"), listOf(a, b, c, d).map { shipmentNow(it.id).status.name })
        assertEquals(ShippingStatus.SHIPPED, orderNow(o.id).shippingStatus)

        val row = rows().single()

        assertEquals(PaymentEventStatus.PROCESSED, row.status)
        assertNull(row.error)
        assertEquals("IN_TRANSIT", row.eventTypes)
        assertEquals(o.id, row.orderId)
        assertEquals(true, row.verified)
        assertEquals(listOf("WEBHOOK"), listOf(row.channel))
        assertEquals("shipping:tracker", row.providerId)
        assertNotNull(w.shippingCarriers.getByProviderId(carrier.id, pool)!!.lastInboundAt)
    }

    @Test
    fun `an unknown shipment is skipped and counted in the row, another provider's shipment by id is not reachable, the rest still applies`(): Unit = runBlocking {
        numbering()

        val o = order("Shirt" to 2)
        val mine = shipCarrier(o)
        val other = TrackingCarrier("other")

        lookup.addShipping(other)
        carrierRow(providerId = other.id, token = "d".repeat(40))

        val theirs = shipCarrier(o, provider = other)
        val t = w.clock.now()

        val reply = post(
            body(
                update("carrier", "NOPE", "DELIVERED", t + 1, "u1"),
                update("id", theirs.id.toString(), "DELIVERED", t + 1, "u2"),
                update("tracking", "TN-${mine.id}", "IN_TRANSIT", t + 2, "ok")
            )
        )

        assertEquals(200, reply.status, "an unknown shipment is not an error (the carrier must not retry)")
        assertEquals(ShipmentStatus.IN_TRANSIT, shipmentNow(mine.id).status)
        assertEquals(ShipmentStatus.CREATED, shipmentNow(theirs.id).status, "a provider only reaches its own shipments")
        assertEquals(0, shipmentEvents(theirs.id).size)

        val row = rows().single()

        assertEquals(PaymentEventStatus.PROCESSED, row.status)
        assertEquals("unknown shipment x2", row.error)
    }

    @Test
    fun `a request for a cancelled shipment's number reaches the newest live one`(): Unit = runBlocking {
        carrier.onCreate = { r -> CreateShipmentResult.Created("CAR-${r.shipmentId}").also { it.trackingNumber = "SAME" } }
        carrier.caps = TrackingCarrier.caps().also { it.cancel = true }
        carrier.onTrack = { emptyList() }

        val o = order("Shirt" to 2)
        val first = shipCarrier(o)

        service.cancelShipment(first.id, true, 7, pool)

        val second = shipCarrier(o)

        post(body(update("tracking", "SAME", "IN_TRANSIT", w.clock.now() + 5, "n1")))

        assertEquals(ShipmentStatus.CANCELLED, shipmentNow(first.id).status)
        assertEquals(ShipmentStatus.IN_TRANSIT, shipmentNow(second.id).status)
    }

    // ================================================================================================ the stored row

    @Test
    fun `the row keeps the request until it settles, then headers and body are redacted, the install token is masked in the url`(): Unit = runBlocking {
        numbering()

        val o = order("Shirt" to 1)
        val s = shipCarrier(o)
        val seenDuring = HashMap<String, MarketPaymentEvent>()

        carrier.onInbound = { _, _ ->
            seenDuring["row"] = rows().single()

            scripted(JsonObject(String(seenDuring["row"]!!.body!!.toByteArray())))
        }

        val sent = body(update("carrier", s.carrierReference!!, "IN_TRANSIT", w.clock.now() + 1, "e1"))

        post(sent, headers = mapOf("content-type" to listOf("application/json"), "authorization" to listOf("Bearer sekrit-token-value")))

        val during = seenDuring.getValue("row")

        assertEquals(PaymentEventStatus.RECEIVED, during.status, "on disk before the provider ran")
        assertTrue(during.headers!!.contains("sekrit-token-value"), "verbatim while it can still be replayed")

        val after = rows().single()

        assertEquals(PaymentEventStatus.PROCESSED, after.status)
        assertFalse(after.headers!!.contains("sekrit-token-value"), "redacted once settled")
        assertFalse(after.url!!.contains(TOKEN), "the install token is never stored")
        assertTrue(after.url!!.contains("{installToken}"))
        assertEquals("203.0.113.9", after.remoteIp)
        assertEquals(200, after.responseStatus)
        assertNotNull(after.processedAt)
        assertTrue(after.eventKey.startsWith("r:"), "no delivery key: the request key stays")
    }

    @Test
    fun `the provider's reply is sent as it is except for headers that would set state on the site`(): Unit = runBlocking {
        val reply = post(body(status = 202))

        assertEquals(202, reply.status)
        assertEquals("carrier-reply", String(reply.body))
        assertEquals("yes", reply.headers["X-Carrier"])
        assertNull(reply.headers["Set-Cookie"])
    }

    // ================================================================================================ de-duplication

    @Test
    fun `with an eventKey the second delivery is a DUPLICATE and is not applied again, without one every request is applied`(): Unit = runBlocking {
        numbering()

        val o = order("Shirt" to 1)
        val s = shipCarrier(o)
        val t = w.clock.now()

        // with a delivery key
        post(body(update("carrier", s.carrierReference!!, "IN_TRANSIT", t + 1, "d1"), eventKey = "delivery-1"))

        // the same delivery again, now carrying a fact the first copy did not: it must NOT be applied
        val second = post(body(update("carrier", s.carrierReference!!, "OUT_FOR_DELIVERY", t + 2, "d2"), eventKey = "delivery-1", status = 202))

        assertEquals(202, second.status, "the provider's reply, never an error")
        assertEquals(listOf("d1"), shipmentEvents(s.id).map { it.third })
        assertEquals(ShipmentStatus.IN_TRANSIT, shipmentNow(s.id).status)

        val log = rows()

        assertEquals(listOf(PaymentEventStatus.PROCESSED, PaymentEventStatus.DUPLICATE), log.map { it.status })
        assertEquals("e:delivery-1", log[0].eventKey)
        assertEquals(1, log[0].duplicateCount)

        // without a key: both requests are applied (events are idempotent through the dedupe key)
        post(body(update("carrier", s.carrierReference!!, "OUT_FOR_DELIVERY", t + 3, "n1")))
        post(body(update("carrier", s.carrierReference!!, "IN_TRANSIT", t + 4, "n2")))
        post(body(update("carrier", s.carrierReference!!, "IN_TRANSIT", t + 4, "n2")))

        assertEquals(listOf("d1", "n1", "n2"), shipmentEvents(s.id).map { it.third })
        assertEquals(ShipmentStatus.IN_TRANSIT, shipmentNow(s.id).status)
        assertEquals(0, rows().count { it.eventKey.startsWith("e:") && it.id != log[0].id })
    }

    @Test
    fun `a request without a delivery id is not de-duplicated by its content - a trigger webhook posts the same body for different states`(): Unit = runBlocking {
        numbering()

        val o = order("Shirt" to 1)
        val s = shipCarrier(o)
        val t = w.clock.now()
        val sameBody = """{"trigger":"TRACK_UPDATED"}"""
        var step = 0

        // the provider re-fetches and reports a different state each time, for a byte-identical request
        carrier.onInbound = { _, _ ->
            step++

            ShippingInboundResult.accepted(HttpReply.empty(200), listOf(TrackingUpdate(ShipmentTarget.Id(s.id), listOf(event(if (step == 1) Spi.IN_TRANSIT else Spi.DELIVERED, t + step * 1_000L, "s$step")))))
        }

        post(sameBody)
        post(sameBody)

        assertEquals(ShipmentStatus.DELIVERED, shipmentNow(s.id).status)
        assertEquals(2, rows().size)
        assertTrue(rows().all { it.status == PaymentEventStatus.PROCESSED })
    }

    // ================================================================================================ delivery key rules (02 section 7.3 step 5)

    /** A dispatcher whose service has no fulfilment half: every update that has to be resolved and applied fails (market's own failure, step 6). */
    private fun unwiredDispatcher() = ShippingInboundDispatcher(
        store, w.shippingCarriers,
        com.panomc.plugins.market.service.ShippingService(
            clock = w.clock, zones = w.shippingZones, methods = w.shippingMethods, rates = w.shippingRates, carriers = w.shippingCarriers, currencyRates = w.currencyRates,
            addresses = w.addresses, lookup = lookup, cipher = com.panomc.plugins.market.provider.SecretCipher(ByteArray(32) { (it + 5).toByte() }),
            contexts = com.panomc.plugins.market.service.ShippingContexts { provider, settings, testMode ->
                com.panomc.plugins.market.spi.testkit.TestContexts.shipping(provider.id, settings, vertx, testMode)
            }
        ),
        { pool }, w.clock, w.ids, { state }
    )

    /** A keyed delivery that fails in step 6: the row is `FAILED` and holds `e:<eventKey>`. */
    private suspend fun failedKeyed(payload: String): MarketPaymentEvent {
        assertEquals(503, unwiredDispatcher().handle(call(payload), TOKEN).status)

        val failed = rows().single()

        assertEquals(PaymentEventStatus.FAILED, failed.status)

        return failed
    }

    @Test
    fun `a keyed delivery that failed is applied when the carrier delivers it again - the FAILED holder is SUPERSEDED and the new row takes the key`(): Unit = runBlocking {
        numbering()

        val o = order("Shirt" to 1)
        val s = shipCarrier(o)
        val keyed = body(update("carrier", s.carrierReference!!, "IN_TRANSIT", w.clock.now() + 1, "k1"), eventKey = "kd-1")
        val failed = failedKeyed(keyed)

        assertEquals("e:kd-1", failed.eventKey)
        assertEquals(0, shipmentEvents(s.id).size)

        assertEquals(200, post(keyed).status)

        val log = rows()

        assertEquals(listOf(PaymentEventStatus.SUPERSEDED, PaymentEventStatus.PROCESSED), log.map { it.status })
        assertEquals("e:kd-1:${failed.id}", log[0].eventKey, "the old row keeps its key plus its id")
        assertEquals("e:kd-1", log[1].eventKey, "the new row holds the delivery key")
        assertEquals(listOf("k1"), shipmentEvents(s.id).map { it.third }, "applied once")
        assertEquals(ShipmentStatus.IN_TRANSIT, shipmentNow(s.id).status)

        // the delivery is settled now: another copy is a DUPLICATE and applies nothing
        post(keyed)

        assertEquals(PaymentEventStatus.DUPLICATE, rows().last().status)
        assertEquals(1, rows().first { it.eventKey == "e:kd-1" }.duplicateCount)
        assertEquals(listOf("k1"), shipmentEvents(s.id).map { it.third })
    }

    @Test
    fun `a FAILED keyed row that is run again through retry owns its key, is PROCESSED and applies the update`(): Unit = runBlocking {
        numbering()

        val o = order("Shirt" to 1)
        val s = shipCarrier(o)
        val failed = failedKeyed(body(update("carrier", s.carrierReference!!, "IN_TRANSIT", w.clock.now() + 1, "k2"), eventKey = "kd-2"))

        assertEquals("e:kd-2", failed.eventKey)
        assertEquals(0, shipmentEvents(s.id).size)

        assertTrue(dispatcher.retry(failed))

        val done = rows().single()

        assertEquals(failed.id, done.id)
        assertEquals(PaymentEventStatus.PROCESSED, done.status)
        assertEquals("e:kd-2", done.eventKey, "unchanged: the row finds its own key and is neither a DUPLICATE nor superseded")
        assertEquals(2, done.attempts)
        assertEquals(0, done.duplicateCount)
        assertEquals(listOf("k2"), shipmentEvents(s.id).map { it.third })
        assertEquals(ShipmentStatus.IN_TRANSIT, shipmentNow(s.id).status)
    }

    @Test
    fun `a holder that is still RECEIVED and younger than 60 seconds makes the new row a DUPLICATE, but its updates are applied`(): Unit = runBlocking {
        numbering()

        val o = order("Shirt" to 1)
        val s = shipCarrier(o)
        val holder = failedKeyed(body(update("carrier", s.carrierReference!!, "IN_TRANSIT", w.clock.now() + 1, "k3"), eventKey = "kd-3"))

        // the first copy is "in flight": received just now, its run has not settled
        sql("UPDATE `pano_market_payment_event` SET `status` = 'RECEIVED', `nextAttemptAt` = NULL, `createdAt` = ? WHERE `id` = ?", w.clock.now(), holder.id)

        val reply = post(body(update("carrier", s.carrierReference!!, "IN_TRANSIT", w.clock.now() + 2, "k3b"), eventKey = "kd-3"))

        assertEquals(200, reply.status)

        val log = rows()

        assertEquals(listOf(PaymentEventStatus.RECEIVED, PaymentEventStatus.DUPLICATE), log.map { it.status })
        assertEquals("e:kd-3", log[0].eventKey, "the holder keeps the key")
        assertEquals(1, log[0].duplicateCount)
        assertTrue(log[1].eventKey.startsWith("r:"))
        assertEquals(listOf("k3b"), shipmentEvents(s.id).map { it.third }, "the events still go through step 6")
        assertEquals(ShipmentStatus.IN_TRANSIT, shipmentNow(s.id).status)
    }

    @Test
    fun `an in-flight holder and a failure of market's own processing - the row is DUPLICATE with the error and the carrier gets 503`(): Unit = runBlocking {
        numbering()

        val o = order("Shirt" to 1)
        val s = shipCarrier(o)
        val keyed = body(update("carrier", s.carrierReference!!, "IN_TRANSIT", w.clock.now() + 1, "k4"), eventKey = "kd-4")
        val holder = failedKeyed(keyed)

        sql("UPDATE `pano_market_payment_event` SET `status` = 'RECEIVED', `nextAttemptAt` = NULL, `createdAt` = ? WHERE `id` = ?", w.clock.now(), holder.id)

        val reply = unwiredDispatcher().handle(call(keyed), TOKEN)

        assertEquals(503, reply.status)

        val log = rows()

        assertEquals(listOf(PaymentEventStatus.RECEIVED, PaymentEventStatus.DUPLICATE), log.map { it.status })
        assertEquals(1, log[0].duplicateCount)
        assertNotNull(log[1].error, "the failure is recorded on this row")
        assertEquals(0, shipmentEvents(s.id).size)
    }

    @Test
    fun `a RECEIVED holder older than 60 seconds is a crashed run - it is SUPERSEDED and the new request is applied`(): Unit = runBlocking {
        numbering()

        val o = order("Shirt" to 1)
        val s = shipCarrier(o)
        val holder = failedKeyed(body(update("carrier", s.carrierReference!!, "IN_TRANSIT", w.clock.now() + 1, "k5"), eventKey = "kd-5"))

        sql("UPDATE `pano_market_payment_event` SET `status` = 'RECEIVED', `nextAttemptAt` = NULL, `createdAt` = ? WHERE `id` = ?", w.clock.now() - 61_000, holder.id)

        assertEquals(200, post(body(update("carrier", s.carrierReference!!, "IN_TRANSIT", w.clock.now() + 2, "k5b"), eventKey = "kd-5")).status)

        val log = rows()

        assertEquals(listOf(PaymentEventStatus.SUPERSEDED, PaymentEventStatus.PROCESSED), log.map { it.status })
        assertEquals("e:kd-5:${holder.id}", log[0].eventKey)
        assertEquals("e:kd-5", log[1].eventKey)
        assertEquals(listOf("k5b"), shipmentEvents(s.id).map { it.third })
    }

    // ================================================================================================ failures and retry

    @Test
    fun `a provider that throws is FAILED with a schedule and 503, the retry job runs the stored request again`(): Unit = runBlocking {
        numbering()

        val o = order("Shirt" to 1)
        val s = shipCarrier(o)
        val sent = body(update("carrier", s.carrierReference!!, "IN_TRANSIT", w.clock.now() + 1, "r1"))
        var failing = true

        carrier.onInbound = { _, request ->
            if (failing) throw IllegalStateException("carrier library blew up")

            scripted(JsonObject(request.bodyAsString()))
        }

        val reply = post(sent)
        val failed = rows().single()

        assertEquals(503, reply.status)
        assertEquals(PaymentEventStatus.FAILED, failed.status)
        assertEquals(1, failed.attempts)
        assertNotNull(failed.nextAttemptAt)
        assertTrue(failed.nextAttemptAt!! in (w.clock.now() + 54_000)..(w.clock.now() + 66_000), "backoff of one minute with jitter")
        assertTrue(failed.error!!.contains("IllegalStateException"))
        assertEquals(0, shipmentEvents(s.id).size)

        // the payment retry job leaves a shipping row alone without a delegate (its dispatcher cannot run it) ...
        val withoutDelegate = InboundEventRetryJob(paymentDispatcherThatMustNotRun(), store, w.clock)

        w.clock.advance(2 * 60_000L)
        failing = false

        assertEquals(0, withoutDelegate.runOnce())
        assertEquals(PaymentEventStatus.FAILED, rows().single().status)
        assertEquals(1, rows().single().attempts, "untouched")

        // ... and with the shipping dispatcher it runs the stored raw request again
        val job = InboundEventRetryJob(paymentDispatcherThatMustNotRun(), store, w.clock, shipping = InboundEventRetryJob.ShippingRetry { dispatcher.retry(it) })

        assertEquals(1, job.runOnce())

        val done = rows().single()

        assertEquals(PaymentEventStatus.PROCESSED, done.status)
        assertEquals(2, done.attempts)
        assertNull(done.nextAttemptAt)
        assertEquals(ShipmentStatus.IN_TRANSIT, shipmentNow(s.id).status)
        assertEquals(listOf("r1"), shipmentEvents(s.id).map { it.third })
        assertEquals(0, job.runOnce(), "nothing left to retry")
    }

    @Test
    fun `a provider that does not answer in time is FAILED with TIMEOUT and 503`(): Unit = runBlocking {
        carrier.onInbound = { _, _ ->
            kotlinx.coroutines.delay(2_000)

            ShippingInboundResult.ignored(HttpReply.empty(200))
        }

        val reply = dispatcherOf(timeoutMs = 100).handle(call(body()), TOKEN)

        assertEquals(503, reply.status)
        assertEquals(PaymentEventStatus.FAILED, rows().single().status)
        assertTrue(rows().single().error!!.startsWith("TIMEOUT"))
    }

    @Test
    fun `a failure of market's own processing is FAILED and 503 so the carrier delivers again`(): Unit = runBlocking {
        numbering()

        val o = order("Shirt" to 1)
        val s = shipCarrier(o)

        carrier.onInbound = { _, _ ->
            ShippingInboundResult.accepted(HttpReply.empty(200), listOf(TrackingUpdate(ShipmentTarget.Id(s.id), listOf(event(Spi.IN_TRANSIT, w.clock.now(), "boom")))))
        }

        // market's own part cannot run: a service whose fulfilment half is not wired fails the moment an update has to be resolved and applied
        val unwired = ShippingInboundDispatcher(
            store, w.shippingCarriers,
            com.panomc.plugins.market.service.ShippingService(
                clock = w.clock, zones = w.shippingZones, methods = w.shippingMethods, rates = w.shippingRates, carriers = w.shippingCarriers, currencyRates = w.currencyRates,
                addresses = w.addresses, lookup = lookup, cipher = com.panomc.plugins.market.provider.SecretCipher(ByteArray(32) { (it + 5).toByte() }),
                contexts = com.panomc.plugins.market.service.ShippingContexts { provider, settings, testMode ->
                    com.panomc.plugins.market.spi.testkit.TestContexts.shipping(provider.id, settings, vertx, testMode)
                }
            ),
            { pool }, w.clock, w.ids, { state }
        )

        val reply = unwired.handle(call(body()), TOKEN)

        assertEquals(503, reply.status)
        assertEquals(PaymentEventStatus.FAILED, rows().single().status)
        assertNotNull(rows().single().nextAttemptAt)
    }

    // ================================================================================================ runtime gate and availability

    @Test
    fun `a stopped or starting market stores nothing and answers 503`(): Unit = runBlocking {
        state = MarketRuntime.State.STOPPED
        assertEquals(503, post(body()).status)

        state = MarketRuntime.State.STARTING
        assertEquals(503, post(body()).status)

        assertEquals(0, rowCount())
        assertEquals(0, carrier.inbound.size)
    }

    @Test
    fun `a degraded market keeps the request as DEFERRED and answers 503 without running the provider`(): Unit = runBlocking {
        state = MarketRuntime.State.DEGRADED

        assertEquals(503, post(body()).status)
        assertEquals(PaymentEventStatus.DEFERRED, rows().single().status)
        assertEquals(0, carrier.inbound.size)
    }

    @Test
    fun `a provider that is not registered any more keeps the request as DEFERRED`(): Unit = runBlocking {
        lookup.remove(carrier.id)

        assertEquals(503, post(body()).status)
        assertEquals(PaymentEventStatus.DEFERRED, rows().single().status)
    }

    @Test
    fun `a disabled carrier still receives its status updates`(): Unit = runBlocking {
        numbering()

        val o = order("Shirt" to 1)
        val s = shipCarrier(o)
        val row = w.shippingCarriers.getByProviderId(carrier.id, pool)!!

        w.shippingCarriers.update(
            com.panomc.plugins.market.db.model.MarketShippingCarrier(
                id = row.id, providerId = row.providerId, enabled = false, settings = row.settings, testMode = row.testMode, webhookToken = row.webhookToken,
                createdAt = row.createdAt, updatedAt = w.clock.now()
            ),
            pool
        )

        assertEquals(200, post(body(update("carrier", s.carrierReference!!, "IN_TRANSIT", w.clock.now() + 1, "dis"))).status)
        assertEquals(ShipmentStatus.IN_TRANSIT, shipmentNow(s.id).status)
        assertEquals(PaymentEventStatus.PROCESSED, rows().single().status)
    }

    @Test
    fun `a channel is handed to the provider and stored as the sub channel`(): Unit = runBlocking {
        post(body(), headers = mapOf("content-type" to listOf("application/json"))).let { assertEquals(200, it.status) }
        dispatcher.handle(call(body(), channel = "events"), TOKEN)

        assertEquals(listOf("default", "events"), carrier.inbound.map { it.channel })
        assertEquals(listOf(null, "events"), rows().map { it.subChannel })
    }

    // ================================================================================================ events through the webhook

    @Test
    fun `out-of-order events are stored and the newest wins, a carrier event after DELIVERED is stored and ignored, DELIVERED queues one mail and one store webhook`(): Unit = runBlocking {
        numbering()

        webhooks.endpoint("https://hooks.example.com/market", events = "[\"shipment.shipped\",\"shipment.delivered\"]")

        val o = order("Shirt" to 1)
        val s = shipCarrier(o)
        val t = w.clock.now()

        post(body(update("carrier", s.carrierReference!!, "DELIVERED", t + 5_000, "e5"), update("carrier", s.carrierReference!!, "OUT_FOR_DELIVERY", t + 4_000, "e4")))

        val delivered = shipmentNow(s.id)

        assertEquals(ShipmentStatus.DELIVERED, delivered.status, "DELIVERED@t5 then OUT_FOR_DELIVERY@t4 stays DELIVERED")
        assertEquals(t + 5_000, delivered.deliveredAt)
        assertEquals(setOf("e5", "e4"), shipmentEvents(s.id).map { it.third }.toSet())
        assertEquals(ShippingStatus.DELIVERED, orderNow(o.id).shippingStatus)
        assertNull(delivered.nextPollAt)

        // a late carrier event and a full replay of the request
        post(body(update("carrier", s.carrierReference!!, "IN_TRANSIT", t + 9_000, "late")))
        post(body(update("carrier", s.carrierReference!!, "DELIVERED", t + 5_000, "e5"), update("carrier", s.carrierReference!!, "OUT_FOR_DELIVERY", t + 4_000, "e4")))

        assertEquals(ShipmentStatus.DELIVERED, shipmentNow(s.id).status)
        assertEquals(setOf("e5", "e4", "late"), shipmentEvents(s.id).map { it.third }.toSet(), "stored, state unchanged")
        assertTrue(shipmentEvents(s.id).all { it.second == "WEBHOOK" })
        assertEquals(listOf(s.id), mails("SHIPMENT_DELIVERED"))
        assertEquals(listOf(s.id), mails("SHIPMENT_SHIPPED"))

        val hooks = webhooks.rows()

        assertEquals(1, hooks.count { it.event == "shipment.delivered" }, "delivered emitted once")
        assertEquals(1, hooks.count { it.event == "shipment.shipped" }, "shipped emitted once")
    }

    @Test
    fun `a tracking number that arrives with a later update is set once and queues exactly one shipped mail`(): Unit = runBlocking {
        carrier.onCreate = { r -> CreateShipmentResult.Created("CAR-${r.shipmentId}") }

        val o = order("Shirt" to 1)
        val s = shipCarrier(o)

        assertNull(s.trackingNumber)

        val numbered = JsonObject().put("verified", true).put("updates", JsonArray())

        carrier.onInbound = { _, _ ->
            ShippingInboundResult.accepted(
                HttpReply.empty(200),
                listOf(TrackingUpdate(ShipmentTarget.CarrierReference(s.carrierReference!!), listOf(event(Spi.IN_TRANSIT, w.clock.now() + 1, "n1"))).also { it.trackingNumber = "LATE-123" })
            )
        }
        post(numbered.encode())
        post(numbered.encode())

        assertEquals("LATE-123", shipmentNow(s.id).trackingNumber)
        assertEquals(listOf(s.id), mails("SHIPMENT_SHIPPED"))
    }

    // ------------------------------------------------------------------------------------------------ a payment dispatcher that must never run

    /** The payment dispatcher over parts that fail the test when touched: shipping rows must never reach it. */
    private fun paymentDispatcherThatMustNotRun(): InboundDispatcher {
        val attempts = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(InboundAttempts::class.java)) { _, method, _ ->
            throw AssertionError("the payment dispatcher touched ${method.name} for a shipping row")
        } as InboundAttempts

        return InboundDispatcher(
            store, attempts, InboundProviders { _, _ -> throw AssertionError("the payment dispatcher resolved a provider for a shipping row") },
            PaymentEventApplier(attempts) { event, attempt, ctx -> PaymentEventSink.UNHANDLED.apply(event, attempt, ctx) }, AttemptLocks(), w.clock, w.ids, { "https://shop.example" }, { MarketRuntime.State.READY }
        )
    }
}
