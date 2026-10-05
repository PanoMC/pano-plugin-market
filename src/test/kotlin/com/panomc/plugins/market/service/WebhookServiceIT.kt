package com.panomc.plugins.market.service

import com.panomc.plugins.market.core.webhook.Decision
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.db.model.WebhookDeliveryStatus
import com.panomc.plugins.market.db.model.WebhookFormat
import com.panomc.plugins.market.db.model.WebhookSigning
import com.panomc.plugins.market.spi.testkit.FakeGateway
import com.panomc.plugins.market.spi.testkit.Reply
import com.panomc.plugins.market.support.StubResolver
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.support.WebhookHarness
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * The outbox writer and the queue operations of `WebhookService` on a real MariaDB (MK-105, 08 section 15): emit inside
 * the business transaction, deterministic event ids, claims, stale claims, redelivery, the endpoint-wide `DEAD`, the test
 * ping and the report back to the delivery engine.
 */
class WebhookServiceIT : MarketDaoITBase() {
    private val w by lazy { TestWiring(pool) }
    private lateinit var vertx: Vertx
    private val gateways = ArrayList<FakeGateway>()

    @BeforeAll
    fun startVertx() {
        vertx = Vertx.vertx()
    }

    @AfterAll
    fun stopVertx() {
        vertx.close().toCompletionStage().toCompletableFuture().get()
    }

    @AfterEach
    fun closeGateways() {
        gateways.forEach { it.close() }
        gateways.clear()
    }

    private fun gateway(): FakeGateway = FakeGateway.start(vertx).also { gateways += it }

    private fun harness(
        renderer: WebhookBodyRenderer = WebhookBodyRenderer.Unwired,
        reporter: WebhookDeliveryReporter? = null,
        resolver: StubResolver = StubResolver(),
        allowPrivate: Boolean = true,
        timeoutMs: Long = 5_000L
    ) = WebhookHarness(w, vertx, resolver, allowPrivate, timeoutMs, renderer, reporter)

    private suspend fun order(testMode: Boolean = false): Long {
        val now = w.clock.now()
        val id = w.orders.add(
            MarketOrder(
                userId = 5, playerUsername = "Steve", recipientUsername = "Steve", recipientUserId = 5, email = "steve@example.com",
                publicId = w.ids.publicId(), status = OrderStatus.COMPLETED, currency = "EUR",
                subtotal = 1000, totalPrice = 1000, gatewayAmount = 1000, paidAmount = 1000, paidAt = now, createdAt = now, updatedAt = now,
                buyerKey = "u:5", recipientKey = "u:5", testMode = testMode, baseCurrency = "EUR",
                paymentMethodId = "manual", reservationState = com.panomc.plugins.market.db.model.ReservationState.COMMITTED
            ),
            pool
        )
        w.orderItems.add(
            MarketOrderItem(orderId = id, productName = "VIP", quantity = 1, unitPrice = 1000, lineTotal = 1000, kind = OrderItemKind.PRODUCT, createdAt = now, updatedAt = now),
            pool
        )
        return id
    }

    // ----- emit ------------------------------------------------------------------------------------------------------

    @Test
    fun `emit writes one pending row per enabled endpoint that listens, copying what the send needs`(): Unit = runBlocking {
        val h = harness()
        val all = h.endpoint("https://a.example.com/hook", WebhookSigning.HMAC_SHA256, "whsec_aaaaaaaaaaaaaaaa", maxAttempts = 5)
        val paidOnly = h.endpoint("https://b.example.com/hook", events = "[\"order.paid\"]")
        val refundOnly = h.endpoint("https://c.example.com/hook", events = "[\"order.refunded\"]")
        val disabled = h.endpoint("https://d.example.com/hook", enabled = false)

        assertEquals(2, h.emit("order.paid", "42", orderId = 42))

        val rows = h.rows()
        assertEquals(listOf(all.id, paidOnly.id), rows.map { it.endpointId })
        assertTrue(rows.none { it.endpointId == refundOnly.id || it.endpointId == disabled.id })

        val r = rows[0]
        assertEquals(WebhookDeliveryStatus.PENDING, r.status)
        assertEquals(0, r.attempts)
        assertEquals(5, r.maxAttempts)
        assertEquals(w.clock.now(), r.nextAttemptAt)
        assertEquals("order.paid", r.event)
        assertEquals(42L, r.orderId)
        assertEquals(all.url, r.url)
        assertEquals(WebhookSigning.HMAC_SHA256, r.signing)
        assertEquals(WebhookFormat.JSON, r.format)
        assertEquals(all.secret, r.secret) // the stored ENC text, copied verbatim
        assertTrue(r.secret!!.startsWith("v1:"))
        assertEquals(com.panomc.plugins.market.core.webhook.WebhookEvents.eventId("order.paid", "42", all.id), r.eventId)
        assertTrue(rows[0].eventId != rows[1].eventId)

        val body = JsonObject(r.body)
        assertEquals(r.eventId, body.getString("id"))
        assertEquals("order.paid", body.getString("event"))
        assertEquals(w.clock.now(), body.getLong("createdAt"))
        assertEquals(false, body.getBoolean("testMode"))
        assertEquals("Test Craft", body.getJsonObject("store").getString("name"))
        assertEquals("42", body.getJsonObject("data").getString("k"))
    }

    @Test
    fun `no endpoint means nothing is written`(): Unit = runBlocking {
        val h = harness()
        assertEquals(0, h.emit("order.paid", "1"))
        h.endpoint("https://a.example.com/hook", events = "[\"order.refunded\"]")
        assertEquals(0, h.emit("order.paid", "1"))
        assertEquals(0L, count("market_webhook_delivery"))
    }

    @Test
    fun `replaying a transition inserts nothing, a different subject inserts a row`(): Unit = runBlocking {
        val h = harness()
        h.endpoint("https://a.example.com/hook")
        assertEquals(1, h.emit("order.paid", "7"))
        assertEquals(0, h.emit("order.paid", "7"))
        assertEquals(0, h.emit("order.paid", "7"))
        assertEquals(1, h.emit("order.paid", "8"))
        assertEquals(1, h.emit("order.refunded", "7"))
        assertEquals(3L, count("market_webhook_delivery"))
    }

    @Test
    fun `concurrent emits of the same transition leave exactly one row per endpoint`(): Unit = runBlocking {
        val h = harness()
        h.endpoint("https://a.example.com/hook")
        h.endpoint("https://b.example.com/hook")
        val inserted = (1..8).map { async(Dispatchers.Default) { h.emit("order.paid", "99", orderId = 99) } }.awaitAll().sum()
        assertEquals(2, inserted)
        assertEquals(2L, count("market_webhook_delivery"))
    }

    @Test
    fun `order paid is enqueued inside the transaction of the caller and rolls back with it`(): Unit = runBlocking {
        val h = harness()
        val endpoint = h.endpoint("https://a.example.com/hook", events = "[\"order.paid\"]")
        val orderId = order()

        class Boom : RuntimeException("O2 failed after the webhook was enqueued")

        assertThrows(Boom::class.java) {
            runBlocking {
                w.db.tx { conn ->
                    assertEquals(1, h.service.emitOrderPaid(conn, orderId))
                    // the row is visible to the transaction itself, not to anybody else yet
                    assertEquals(1L, conn.query("SELECT COUNT(*) AS c FROM `pano_market_webhook_delivery`").execute().coAwait().first().getLong("c"))
                    throw Boom()
                }
            }
        }
        assertEquals(0L, count("market_webhook_delivery"), "a rolled back O2 leaves no webhook")

        w.db.tx { conn -> assertEquals(1, h.service.emitOrderPaid(conn, orderId)) }
        w.db.tx { conn -> assertEquals(0, h.service.emitOrderPaid(conn, orderId)) } // a replay of O2 writes nothing

        val row = h.rows().single()
        assertEquals(endpoint.id, row.endpointId)
        assertEquals(orderId, row.orderId)
        assertEquals("order.paid", row.event)
        val data = JsonObject(row.body).getJsonObject("data")
        assertEquals(orderId, data.getJsonObject("order").getLong("id"))
        assertEquals("COMPLETED", data.getJsonObject("order").getString("status"))
        assertEquals("Steve", data.getJsonObject("buyer").getString("username"))
        assertEquals("steve@example.com", data.getJsonObject("buyer").getString("email"))
        assertEquals("VIP", data.getJsonArray("items").getJsonObject(0).getString("productName"))
        assertFalse(JsonObject(row.body).getBoolean("testMode"))
    }

    @Test
    fun `a test mode order is emitted with testMode true`(): Unit = runBlocking {
        val h = harness()
        h.endpoint("https://a.example.com/hook")
        val orderId = order(testMode = true)
        w.db.tx { conn -> h.service.emitOrderPaid(conn, orderId) }
        assertEquals(true, JsonObject(h.rows().single().body).getBoolean("testMode"))
    }

    @Test
    fun `order paid for an order that does not exist fails the transaction instead of dropping the event`(): Unit = runBlocking {
        val h = harness()
        h.endpoint("https://a.example.com/hook")
        assertThrows(IllegalStateException::class.java) { runBlocking { w.db.tx { conn -> h.service.emitOrderPaid(conn, 424242) } } }
        assertEquals(0L, count("market_webhook_delivery"))
    }

    @Test
    fun `an unsubscribable event name is refused`(): Unit = runBlocking {
        val h = harness()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { h.emit("action.grant", "1") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { h.emit("order.created", "1") } }
    }

    @Test
    fun `a discord endpoint without the renderer gets a dead row and never breaks the business transaction`(): Unit = runBlocking {
        val h = harness()
        val json = h.endpoint("https://a.example.com/hook")
        val discord = h.endpoint("https://discord.com/api/webhooks/1/abc", format = WebhookFormat.DISCORD)

        assertEquals(2, h.emit("order.paid", "5"))

        val rows = h.rows().associateBy { it.endpointId }
        assertEquals(WebhookDeliveryStatus.PENDING, rows[json.id]!!.status)
        assertEquals(WebhookDeliveryStatus.DEAD, rows[discord.id]!!.status)
        assertEquals("RENDER_FAILED", rows[discord.id]!!.lastError)
        assertNull(rows[discord.id]!!.nextAttemptAt)
    }

    @Test
    fun `a wired renderer supplies the discord body`(): Unit = runBlocking {
        val h = harness(renderer = WebhookBodyRenderer { _, event, envelope -> """{"content":"$event ${envelope.getString("id")}"}""" })
        val discord = h.endpoint("https://discord.com/api/webhooks/1/abc", format = WebhookFormat.DISCORD)
        h.emit("order.paid", "5")
        val row = h.rows().single()
        assertEquals(WebhookDeliveryStatus.PENDING, row.status)
        assertEquals("""{"content":"order.paid ${row.eventId}"}""", row.body)
        assertEquals(discord.id, row.endpointId)
    }

    // ----- claim -----------------------------------------------------------------------------------------------------

    @Test
    fun `claimDue moves due rows to SENDING with a 60 second claim and counts the attempt`(): Unit = runBlocking {
        val h = harness()
        h.endpoint("https://a.example.com/hook")
        h.emit("order.paid", "1")
        h.emit("order.paid", "2")
        // a row that is not due yet
        h.emit("order.paid", "3")
        val future = h.rows().last().id
        sql("UPDATE `pano_market_webhook_delivery` SET `nextAttemptAt` = ? WHERE `id` = ?", w.clock.now() + 10_000, future)

        val claimed = h.service.claimDue()

        assertEquals(2, claimed.size)
        for (c in claimed) {
            assertEquals(WebhookDeliveryStatus.SENDING, c.status)
            assertEquals(1, c.attempts)
            assertEquals(w.clock.now() + 60_000, c.claimedUntil)
        }
        assertEquals(WebhookDeliveryStatus.PENDING, h.row(future).status)
        assertTrue(h.service.claimDue().isEmpty(), "claimed rows are not claimed twice")

        w.clock.advance(10_000)
        assertEquals(listOf(future), h.service.claimDue().map { it.id })
    }

    @Test
    fun `the batch is limited and goes in id order`(): Unit = runBlocking {
        val h = harness()
        h.endpoint("https://a.example.com/hook")
        for (i in 1..25) h.emit("order.paid", "$i")
        val first = h.service.claimDue(20)
        assertEquals(h.rows().take(20).map { it.id }, first.map { it.id })
        assertEquals(h.rows().drop(20).map { it.id }, h.service.claimDue(20).map { it.id })
    }

    @Test
    fun `concurrent claimers share the work and never claim a row twice`(): Unit = runBlocking {
        val h = harness()
        h.endpoint("https://a.example.com/hook")
        for (i in 1..40) h.emit("order.paid", "$i")

        val claimed = (1..6).map { async(Dispatchers.Default) { h.service.claimDue(50) } }.awaitAll().flatten()

        assertEquals(40, claimed.size)
        assertEquals(40, claimed.map { it.id }.toSet().size)
        assertTrue(claimed.all { it.attempts == 1 })
        assertEquals(40L, count("market_webhook_delivery", "`status` = 'SENDING' AND `attempts` = 1"))
    }

    @Test
    fun `a claim that ran out becomes a retry and the attempt stays counted`(): Unit = runBlocking {
        val h = harness()
        h.endpoint("https://a.example.com/hook", maxAttempts = 3)
        h.emit("order.paid", "1")
        val id = h.service.claimDue().single().id

        w.clock.advance(59_000)
        assertTrue(h.service.claimDue().isEmpty(), "the claim is still valid")
        assertEquals(WebhookDeliveryStatus.SENDING, h.row(id).status)

        w.clock.advance(2_000)
        val again = h.service.claimDue().single()
        assertEquals(id, again.id)
        assertEquals(2, again.attempts)
        assertEquals("CLAIM_EXPIRED", h.row(id).lastError)
    }

    @Test
    fun `a stale row that used all its attempts ends dead`(): Unit = runBlocking {
        val h = harness()
        h.endpoint("https://a.example.com/hook", maxAttempts = 1)
        h.emit("order.paid", "1")
        val id = h.service.claimDue().single().id
        w.clock.advance(61_000)
        assertTrue(h.service.claimDue().isEmpty())
        val row = h.row(id)
        assertEquals(WebhookDeliveryStatus.DEAD, row.status)
        assertEquals("CLAIM_EXPIRED", row.lastError)
        assertNull(row.claimedUntil)
    }

    // ----- action rows and the report back ---------------------------------------------------------------------------

    private suspend fun actionRow(h: WebhookHarness, url: String, deliveryId: Long = 31): Long {
        val now = w.clock.now()
        return w.webhookDeliveries.add(
            com.panomc.plugins.market.db.model.MarketWebhookDelivery(
                endpointId = 0, deliveryId = deliveryId, eventId = com.panomc.plugins.market.core.webhook.WebhookEvents.actionEventId(deliveryId),
                event = "action.grant", url = url, body = """{"id":"x"}""", status = WebhookDeliveryStatus.PENDING, maxAttempts = 2,
                nextAttemptAt = now, createdAt = now, updatedAt = now
            ),
            pool
        )!!
    }

    @Test
    fun `rows of a product action are not claimed while nobody can learn their outcome`(): Unit = runBlocking {
        val h = harness(reporter = null)
        val id = actionRow(h, "https://a.example.com/hook")
        assertTrue(h.service.claimDue().isEmpty())
        assertEquals(WebhookDeliveryStatus.PENDING, h.row(id).status)
    }

    @Test
    fun `the report back runs in the transaction of the final status change`(): Unit = runBlocking {
        val g = gateway().on("POST", "/hook") { Reply.empty(200) }
        val reported = ArrayList<Pair<Long, Decision>>()
        val h = harness(reporter = WebhookDeliveryReporter { _, row, decision -> reported += row.deliveryId to decision })
        val ok = actionRow(h, "${g.baseUrl}/hook", deliveryId = 31)

        h.service.process(h.service.claimDue().single())

        assertEquals(WebhookDeliveryStatus.SUCCEEDED, h.row(ok).status)
        assertEquals(1, reported.size)
        assertEquals(31L, reported[0].first)
        assertEquals(WebhookDeliveryStatus.SUCCEEDED, reported[0].second.status)
    }

    @Test
    fun `a failed attempt that will be retried is not reported, the dead one is`(): Unit = runBlocking {
        val g = gateway().on("POST", "/hook") { Reply.empty(500) }
        val reported = ArrayList<WebhookDeliveryStatus>()
        val h = harness(reporter = WebhookDeliveryReporter { _, _, decision -> reported += decision.status })
        val id = actionRow(h, "${g.baseUrl}/hook")

        h.service.process(h.service.claimDue().single())
        assertEquals(WebhookDeliveryStatus.FAILED, h.row(id).status)
        assertTrue(reported.isEmpty())

        w.clock.advance(3_600_000)
        h.service.process(h.service.claimDue().single())
        assertEquals(WebhookDeliveryStatus.DEAD, h.row(id).status)
        assertEquals(listOf(WebhookDeliveryStatus.DEAD), reported)
    }

    @Test
    fun `when the report back fails the row stays SENDING and nothing is half done`(): Unit = runBlocking {
        val g = gateway().on("POST", "/hook") { Reply.empty(200) }
        val h = harness(reporter = WebhookDeliveryReporter { _, _, _ -> throw IllegalStateException("delivery engine down") })
        val id = actionRow(h, "${g.baseUrl}/hook")

        assertThrows(IllegalStateException::class.java) { runBlocking { h.service.process(h.service.claimDue().single()) } }

        val row = h.row(id)
        assertEquals(WebhookDeliveryStatus.SENDING, row.status)
        assertNull(row.deliveredAt)
        // the claim expires and the row is retried: the receiver de-duplicates on the event id
        w.clock.advance(61_000)
        assertEquals(2, h.service.claimDue().single().attempts)
    }

    // ----- redeliver, endpoint operations ----------------------------------------------------------------------------

    @Test
    fun `redeliver resets the same row from a finished state and refuses an in-flight one`(): Unit = runBlocking {
        val h = harness()
        h.endpoint("https://a.example.com/hook")
        for (status in listOf("SUCCEEDED", "FAILED", "DEAD")) {
            h.emit("order.paid", status)
        }
        val rows = h.rows()
        for ((row, status) in rows.zip(listOf("SUCCEEDED", "FAILED", "DEAD"))) {
            sql("UPDATE `pano_market_webhook_delivery` SET `status` = ?, `attempts` = 4, `nextAttemptAt` = NULL WHERE `id` = ?", status, row.id)
            assertEquals(RedeliverResult.OK, h.service.redeliver(row.id), status)
            val after = h.row(row.id)
            assertEquals(WebhookDeliveryStatus.PENDING, after.status)
            assertEquals(0, after.attempts)
            assertEquals(w.clock.now(), after.nextAttemptAt)
            assertEquals(row.eventId, after.eventId, "the same event id")
        }
        sql("UPDATE `pano_market_webhook_delivery` SET `status` = 'SENDING' WHERE `id` = ?", rows[0].id)
        assertEquals(RedeliverResult.IN_FLIGHT, h.service.redeliver(rows[0].id))
        assertEquals(WebhookDeliveryStatus.SENDING, h.row(rows[0].id).status)
        assertEquals(RedeliverResult.NOT_FOUND, h.service.redeliver(987654))
        assertEquals(3L, count("market_webhook_delivery"))
    }

    @Test
    fun `deadenOpenRows ends the open rows of one endpoint only`(): Unit = runBlocking {
        val h = harness()
        val a = h.endpoint("https://a.example.com/hook")
        val b = h.endpoint("https://b.example.com/hook")
        for (i in 1..3) h.emit("order.paid", "$i")
        val claimedRow = h.rows().first { it.endpointId == a.id }
        sql("UPDATE `pano_market_webhook_delivery` SET `status` = 'SUCCEEDED' WHERE `id` = ?", claimedRow.id)

        val n = w.db.tx { conn -> h.service.deadenOpenRows(conn, a.id, "ENDPOINT_DELETED") }

        assertEquals(2, n)
        val rows = h.rows()
        assertTrue(rows.filter { it.endpointId == a.id && it.id != claimedRow.id }.all { it.status == WebhookDeliveryStatus.DEAD && it.lastError == "ENDPOINT_DELETED" })
        assertEquals(WebhookDeliveryStatus.SUCCEEDED, h.row(claimedRow.id).status)
        assertTrue(rows.filter { it.endpointId == b.id }.all { it.status == WebhookDeliveryStatus.PENDING })
    }

    @Test
    fun `a row whose endpoint was deleted or disabled while queued ends dead without a request`(): Unit = runBlocking {
        val g = gateway().on("POST", "/hook") { Reply.empty(200) }
        val h = harness()
        val deleted = h.endpoint("${g.baseUrl}/hook")
        val disabled = h.endpoint("${g.baseUrl}/hook")
        h.emit("order.paid", "1")
        val claimed = h.service.claimDue()
        w.db.tx { conn -> w.webhookEndpoints.delete(deleted.id, conn) }
        sql("UPDATE `pano_market_webhook_endpoint` SET `enabled` = 0 WHERE `id` = ?", disabled.id)

        for (row in claimed) assertNotNull(h.service.process(row))

        val byEndpoint = h.rows().associateBy { it.endpointId }
        assertEquals("ENDPOINT_DELETED", byEndpoint[deleted.id]!!.lastError)
        assertEquals("ENDPOINT_DISABLED", byEndpoint[disabled.id]!!.lastError)
        assertTrue(byEndpoint.values.all { it.status == WebhookDeliveryStatus.DEAD })
        assertTrue(g.requests.isEmpty())
    }

    // ----- test ping -------------------------------------------------------------------------------------------------

    @Test
    fun `the test ping is sent at once, logged with one attempt and leaves the endpoint counters alone`(): Unit = runBlocking {
        val g = gateway().on("POST", "/hook") { Reply.text("pong", 200) }
        val h = harness()
        val endpoint = h.endpoint("${g.baseUrl}/hook", WebhookSigning.HMAC_SHA256, "whsec_pingpingpingping", events = "[\"order.paid\"]")

        val result = h.service.sendTestPing(endpoint.id)!!

        assertEquals(200, result.statusCode)
        assertNull(result.error)
        assertNotNull(result.durationMs)
        val req = g.requests.single()
        assertEquals("test.ping", req.header("X-Pano-Event"))
        assertNotNull(req.header("X-Pano-Signature"))
        val body = JsonObject(req.bodyText())
        assertEquals("ping", body.getJsonObject("data").getString("message"))
        assertEquals(endpoint.id, body.getJsonObject("data").getLong("endpointId"))

        val row = h.rows().single()
        assertEquals("test.ping", row.event)
        assertEquals(1, row.maxAttempts)
        assertEquals(1, row.attempts)
        assertEquals(WebhookDeliveryStatus.SUCCEEDED, row.status)
        assertEquals(0, h.endpointNow(endpoint.id).failureCount)
    }

    @Test
    fun `the test ping works on a disabled endpoint and reports a failure without retrying it`(): Unit = runBlocking {
        val g = gateway().on("POST", "/hook") { Reply.empty(500) }
        val h = harness()
        val endpoint = h.endpoint("${g.baseUrl}/hook", enabled = false)

        val result = h.service.sendTestPing(endpoint.id)!!

        assertEquals(500, result.statusCode)
        assertEquals("HTTP_500", result.error)
        val row = h.rows().single()
        assertEquals(WebhookDeliveryStatus.DEAD, row.status)
        assertEquals(0, h.endpointNow(endpoint.id).failureCount)
        assertEquals(1, g.requests.size)
    }

    @Test
    fun `the test ping to a private target is refused and an unknown endpoint is null`(): Unit = runBlocking {
        val h = harness(allowPrivate = false)
        val endpoint = h.endpoint("http://127.0.0.1:9000/hook")
        val result = h.service.sendTestPing(endpoint.id)!!
        assertNull(result.statusCode)
        assertEquals("URL_GUARD:PRIVATE_ADDRESS", result.error)
        assertNull(h.service.sendTestPing(555))
    }

    @Test
    fun `the test ping of a discord endpoint without the renderer reports RENDER_FAILED`(): Unit = runBlocking {
        val h = harness()
        val endpoint = h.endpoint("https://discord.com/api/webhooks/1/abc", format = WebhookFormat.DISCORD)
        assertEquals("RENDER_FAILED", h.service.sendTestPing(endpoint.id)!!.error)
        assertEquals(0L, count("market_webhook_delivery"))
    }
}
