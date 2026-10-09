package com.panomc.plugins.market.service

import com.panomc.platform.api.webhook.RenderedBody
import com.panomc.platform.api.webhook.WebhookDiscordRenderer
import com.panomc.platform.db.model.WebhookDeliveryStatus
import com.panomc.platform.db.model.WebhookFormat
import com.panomc.platform.db.model.WebhookSigning
import com.panomc.platform.webhook.WebhookSigner
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.db.model.OrderItemKind
import com.panomc.plugins.market.spi.testkit.FakeGateway
import com.panomc.plugins.market.spi.testkit.Reply
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
 * The market's side of the store webhooks on **core's real webhook system** and a real MariaDB (MK-15, doc 06 section 4.4): the payloads the market composes,
 * the rows core writes in the caller's transaction (one per endpoint that listens, deterministic ids, replays write nothing, a rollback takes them along), the
 * names core prefixes with `market.`, the envelope, the Discord renderer of the plugin and a delivery to a local receiver.
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

    private fun harness(discord: WebhookDiscordRenderer? = null, uuidOf: suspend (Long?, String) -> String? = { _, _ -> null }) =
        WebhookHarness(w, vertx, discord = discord, uuidOf = uuidOf)

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
    fun `emit writes one pending row per enabled endpoint that listens, under the name core gives it`(): Unit = runBlocking {
        val h = harness()
        val all = h.endpoint("https://a.example.com/hook", WebhookSigning.HMAC_SHA256, "whsec_aaaaaaaaaaaaaaaa", maxAttempts = 5)
        val paidOnly = h.endpoint("https://b.example.com/hook", events = "[\"order.paid\"]")
        val refundOnly = h.endpoint("https://c.example.com/hook", events = "[\"order.refunded\"]")
        val disabled = h.endpoint("https://d.example.com/hook", enabled = false)
        val sourceWide = h.endpoint("https://e.example.com/hook", events = "[\"market.*\"]")

        assertEquals(3, h.emit("order.paid", "42", orderId = 42))

        val rows = h.rows()
        assertEquals(listOf(all.id, paidOnly.id, sourceWide.id), rows.map { it.endpointId })
        assertTrue(rows.none { it.endpointId == refundOnly.id || it.endpointId == disabled.id })

        val r = rows[0]
        assertEquals(WebhookDeliveryStatus.PENDING, r.status)
        assertEquals(0, r.attempts)
        assertEquals(5, r.maxAttempts)
        assertEquals(w.clock.now(), r.nextAttemptAt)
        assertEquals("market", r.source)
        assertEquals("market.order.paid", r.event)
        assertEquals("order:42", r.subjectRef)
        assertEquals(all.url, r.url)
        assertEquals(WebhookSigning.HMAC_SHA256, r.signing)
        assertEquals(WebhookFormat.JSON, r.format)
        assertEquals(all.secret, r.secret) // the stored ENC text of core's key, copied verbatim
        assertTrue(r.secret!!.startsWith("v1:"))
        assertEquals(com.panomc.platform.webhook.WebhookEvents.eventId("market.order.paid", "42", all.id), r.eventId)
        assertTrue(rows.map { it.eventId }.toSet().size == 3)

        val body = JsonObject(r.body)
        assertEquals(r.eventId, body.getString("id"))
        assertEquals("market.order.paid", body.getString("event"))
        assertEquals("market", body.getString("source"))
        assertEquals(1, body.getInteger("apiVersion"))
        assertEquals(w.clock.now(), body.getLong("createdAt"))
        assertEquals(false, body.getBoolean("testMode"))
        assertEquals("Test Craft", body.getJsonObject("site").getString("name"))
        assertEquals("42", body.getJsonObject("data").getString("k"))
    }

    @Test
    fun `no endpoint means nothing is written`(): Unit = runBlocking {
        val h = harness()
        assertEquals(0, h.emit("order.paid", "1"))
        h.endpoint("https://a.example.com/hook", events = "[\"order.refunded\"]")
        assertEquals(0, h.emit("order.paid", "1"))
        assertEquals(0L, count("webhook_delivery"))
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
        assertEquals(3L, count("webhook_delivery"))
    }

    @Test
    fun `concurrent emits of the same transition leave exactly one row per endpoint`(): Unit = runBlocking {
        val h = harness()
        h.endpoint("https://a.example.com/hook")
        h.endpoint("https://b.example.com/hook")
        val inserted = (1..8).map { async(Dispatchers.Default) { h.emit("order.paid", "99", orderId = 99) } }.awaitAll().sum()
        assertEquals(2, inserted)
        assertEquals(2L, count("webhook_delivery"))
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
                    assertEquals(1L, conn.query("SELECT COUNT(*) AS c FROM `pano_webhook_delivery`").execute().coAwait().first().getLong("c"))
                    throw Boom()
                }
            }
        }
        assertEquals(0L, count("webhook_delivery"), "a rolled back O2 leaves no webhook")

        w.db.tx { conn -> assertEquals(1, h.service.emitOrderPaid(conn, orderId)) }
        w.db.tx { conn -> assertEquals(0, h.service.emitOrderPaid(conn, orderId)) } // a replay of O2 writes nothing

        val row = h.rows().single()
        assertEquals(endpoint.id, row.endpointId)
        assertEquals("order:$orderId", row.subjectRef)
        assertEquals("market.order.paid", row.event)
        val data = JsonObject(row.body).getJsonObject("data")
        assertEquals(orderId, data.getJsonObject("order").getLong("id"))
        assertEquals("COMPLETED", data.getJsonObject("order").getString("status"))
        assertEquals("Steve", data.getJsonObject("buyer").getString("username"))
        assertEquals("steve@example.com", data.getJsonObject("buyer").getString("email"))
        assertEquals("VIP", data.getJsonArray("items").getJsonObject(0).getString("productName"))
        assertFalse(JsonObject(row.body).getBoolean("testMode"))
    }

    @Test
    fun `a gift to a name without an account asks for the uuid of the recipient name, not of the payer`(): Unit = runBlocking {
        val asked = java.util.concurrent.CopyOnWriteArrayList<Pair<Long?, String>>()
        val h = harness(uuidOf = { id, name -> asked += id to name; "uuid-of-${id ?: name}" })
        h.endpoint("https://a.example.com/hook", events = "[\"order.paid\"]")
        val now = w.clock.now()
        val orderId = w.orders.add(
            MarketOrder(
                userId = 5, playerUsername = "Steve", recipientUsername = "Alex", recipientUserId = null, isGift = true,
                publicId = w.ids.publicId(), status = OrderStatus.COMPLETED, currency = "EUR", subtotal = 1000, totalPrice = 1000,
                gatewayAmount = 1000, paidAmount = 1000, paidAt = now, createdAt = now, updatedAt = now, buyerKey = "u:5", recipientKey = "g:alex",
                baseCurrency = "EUR", paymentMethodId = "manual", reservationState = com.panomc.plugins.market.db.model.ReservationState.COMMITTED
            ),
            pool
        )
        w.orderItems.add(
            MarketOrderItem(orderId = orderId, productName = "VIP", quantity = 1, unitPrice = 1000, lineTotal = 1000, kind = OrderItemKind.PRODUCT, createdAt = now, updatedAt = now),
            pool
        )

        w.db.tx { conn -> h.service.emitOrderPaid(conn, orderId) }

        assertEquals(listOf<Pair<Long?, String>>(5L to "Steve", null to "Alex"), asked.toList())
        val data = JsonObject(h.rows().single().body).getJsonObject("data")
        assertEquals("Alex", data.getJsonObject("recipient").getString("username"))
        assertNull(data.getJsonObject("recipient").getValue("userId"))
        assertEquals("uuid-of-Alex", data.getJsonObject("recipient").getString("uuid"))
        assertEquals("uuid-of-5", data.getJsonObject("buyer").getString("uuid"))
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
        assertEquals(0L, count("webhook_delivery"))
    }

    @Test
    fun `order paid reads nothing when nobody listens`(): Unit = runBlocking {
        val h = harness()
        h.endpoint("https://a.example.com/hook", events = "[\"order.refunded\"]")
        // the order does not exist: if the service read it, the transaction would fail
        w.db.tx { conn -> assertEquals(0, h.service.emitOrderPaid(conn, 424242)) }
    }

    @Test
    fun `refund and dispute events carry their object next to the order and key their subject by it`(): Unit = runBlocking {
        val h = harness()
        h.endpoint("https://a.example.com/hook")
        val orderId = order()

        w.db.tx { conn ->
            assertEquals(1, h.service.emitOrderRefunded(conn, orderId, 7, JsonObject().put("id", 7).put("amount", 4.0)))
            assertEquals(0, h.service.emitOrderRefunded(conn, orderId, 7, JsonObject().put("id", 7).put("amount", 4.0)))
            assertEquals(1, h.service.emitOrderDispute(conn, orderId, 3, won = false, dispute = JsonObject().put("id", 3)))
            assertEquals(1, h.service.emitOrderDispute(conn, orderId, 3, won = true, dispute = JsonObject().put("id", 3)))
        }

        val rows = h.rows()

        assertEquals(listOf("market.order.refunded", "market.order.chargeback", "market.order.chargeback.won"), rows.map { it.event })
        assertTrue(rows.all { it.subjectRef == "order:$orderId" })
        assertEquals(7L, JsonObject(rows[0].body).getJsonObject("data").getJsonObject("refund").getLong("id"))
        assertEquals(3L, JsonObject(rows[1].body).getJsonObject("data").getJsonObject("dispute").getLong("id"))
    }

    @Test
    fun `an event name outside the rules is refused, an undeclared valid one is declared by its first publish`(): Unit = runBlocking {
        val h = harness()
        h.endpoint("https://a.example.com/hook")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { h.emit("Order Created", "1") } }
        assertEquals(1, h.emit("order.created", "1"))
        assertEquals("market.order.created", h.rows().single().event)
    }

    @Test
    fun `the action events are never matched by a wildcard`(): Unit = runBlocking {
        val h = harness()
        h.endpoint("https://a.example.com/hook")
        h.endpoint("https://b.example.com/hook", events = "[\"market.*\"]")
        assertEquals(0, h.emit("action.grant", "1"))
        assertEquals(0L, count("webhook_delivery"))
    }

    @Test
    fun `a discord endpoint gets the plugin renderer and its body, else core's generic embed`(): Unit = runBlocking {
        val withRenderer = harness(discord = WebhookDiscordRenderer { event, envelope, _ -> RenderedBody("""{"content":"$event ${envelope.getString("id")}"}""") })
        val discord = withRenderer.endpoint("https://discord.com/api/webhooks/1/abc", format = WebhookFormat.DISCORD)

        withRenderer.emit("order.paid", "5")

        val row = withRenderer.rows().single()
        assertEquals(WebhookDeliveryStatus.PENDING, row.status)
        assertEquals("""{"content":"order.paid ${row.eventId}"}""", row.body)
        assertEquals(discord.id, row.endpointId)
    }

    // ----- delivery on core's engine -----------------------------------------------------------------------------------

    @Test
    fun `a due row is sent to the receiver with core's headers and a signature the receiver can verify`(): Unit = runBlocking {
        val g = gateway().on("POST", "/hook") { Reply.empty(200) }
        val h = harness()
        val secret = "whsec_0123456789abcdef"
        val endpoint = h.endpoint("${g.baseUrl}/hook", WebhookSigning.HMAC_SHA256, secret, events = "[\"order.paid\"]")
        val orderId = order()

        w.db.tx { conn -> h.service.emitOrderPaid(conn, orderId) }

        assertEquals(1, h.tick())

        val row = h.rows().single()
        val request = g.requestsTo("/hook").single()

        assertEquals(WebhookDeliveryStatus.SUCCEEDED, row.status)
        assertEquals(endpoint.id, row.endpointId)
        assertEquals("market.order.paid", request.header("X-Pano-Event"))
        assertEquals(row.eventId, request.header("X-Pano-Event-Id"))
        assertEquals(row.id.toString(), request.header("X-Pano-Delivery"))
        assertEquals(row.body, request.bodyText())

        val header = request.header("X-Pano-Signature")

        assertNotNull(header)
        assertTrue(WebhookSigner.verify(header!!, secret, request.bodyText(), w.clock.now() / 1000L))
        assertFalse(WebhookSigner.verify(header, "whsec_wrongwrongwrong0", request.bodyText(), w.clock.now() / 1000L))
    }
}
