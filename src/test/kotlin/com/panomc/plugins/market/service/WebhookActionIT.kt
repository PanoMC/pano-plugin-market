package com.panomc.plugins.market.service

import com.panomc.platform.api.webhook.WebhookOutcomeListener
import com.panomc.platform.db.model.WebhookDeliveryStatus
import com.panomc.platform.db.model.WebhookFormat as RowFormat
import com.panomc.platform.db.model.WebhookSigning as RowSigning
import com.panomc.platform.webhook.RedeliverResult
import com.panomc.platform.webhook.WebhookSigner
import com.panomc.plugins.market.core.delivery.ProductAction
import com.panomc.plugins.market.core.delivery.WebhookFormat
import com.panomc.plugins.market.core.delivery.WebhookSigning
import com.panomc.plugins.market.core.delivery.WebhookSpec
import com.panomc.plugins.market.core.webhook.DiscordLabelSource
import com.panomc.plugins.market.core.webhook.DiscordLabels
import com.panomc.plugins.market.core.webhook.WebhookEvents
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.DeliveryActionType
import com.panomc.plugins.market.db.model.DeliveryStatus
import com.panomc.plugins.market.db.model.FulfillmentStatus
import com.panomc.plugins.market.spi.testkit.FakeGateway
import com.panomc.plugins.market.spi.testkit.Reply
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.support.WebhookHarness
import com.panomc.plugins.market.support.WebhookTestSupport
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The inline `WEBHOOK` executor of the delivery engine on core's direct queue and a real MariaDB (MK-106 / MK-15; 08 sections 7.3 and 16, doc 06 section 4.3): the
 * direct row of an action (`ownerRef = delivery:<id>`), the delivery `SENDING -> SENT -> CONFIRMED` when core's dispatcher delivers it, `DEAD -> FAILED
 * (WEBHOOK_DEAD)` and the late positive result of a redelivery through the outcome listener, the replayed executor, `DISCORD` normalised to `NONE`, and a valid
 * Discord body for names with quotes and newlines.
 */
class WebhookActionIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var d: DeliveryWorld
    private lateinit var h: WebhookHarness
    private lateinit var vertx: Vertx
    private val gateways = ArrayList<FakeGateway>()
    private val labels = DiscordLabelSource { DiscordLabels("Action", "{username} got {product.name}", "Player", "Total", "Items") }

    /** The provider cipher of the market: the secret of an action is sealed with it when the product is saved. */
    private val marketCipher = WebhookTestSupport.cipher()

    @BeforeAll
    fun startVertx() {
        vertx = Vertx.vertx()
    }

    @AfterAll
    fun stopVertx() {
        vertx.close().toCompletionStage().toCompletableFuture().get()
    }

    @BeforeEach
    fun wire() {
        w = TestWiring(pool)
        // the harness declares the outcome listener of the delivery service, which in turn queues on the harness: tied by a late lookup
        h = WebhookHarness(w, vertx, outcomes = WebhookOutcomeListener { client, row, decision -> d.service.outcomeListener.onOutcome(client, row, decision) })
        d = DeliveryWorld(w, webhookQueue = h.actionQueue, webhookSecret = { marketCipher.decrypt(it) }, discordLabels = labels)
    }

    @AfterEach
    fun closeGateways() {
        gateways.forEach { it.close() }
        gateways.clear()
    }

    private fun gateway(): FakeGateway = FakeGateway.start(vertx).also { gateways += it }

    private fun hook(url: String, format: WebhookFormat = WebhookFormat.JSON, signing: WebhookSigning = WebhookSigning.NONE, secret: String? = null) =
        ProductAction(id = "w1", type = DeliveryActionType.WEBHOOK, webhook = WebhookSpec(url, format, signing, secret))

    @Test
    fun `the action row goes SENDING, SENT and CONFIRMED when its direct row succeeds`(): Unit = runBlocking {
        val g = gateway().on("POST", "/hook") { Reply.empty(200) }
        val u = w.fixtures.user("Steve")
        val placed = d.place(user = u, actions = listOf(hook("${g.baseUrl}/hook")))

        d.pay(placed)

        val id = d.rows(placed.order.id).single().id

        assertEquals(DeliveryStatus.PENDING, d.row(id).status)

        // the engine claims it: SENDING
        val claimed = d.service.claimDue().single()

        assertEquals(DeliveryStatus.SENDING, d.row(id).status)

        // the executor queues the direct row on core's system and the delivery is SENT
        assertEquals(DeliveryStatus.SENT, d.service.execute(claimed))

        val sent = d.row(id)
        val outbox = h.rows().single()

        assertEquals(DeliveryStatus.SENT, sent.status)
        assertEquals(outbox.id, JsonObject(sent.result).getLong("webhookDeliveryId"))
        assertEquals(0L, outbox.endpointId)
        assertEquals("market", outbox.source)
        assertEquals("delivery:$id", outbox.ownerRef)
        assertEquals(WebhookDeliveryStatus.PENDING, outbox.status)
        assertEquals(8, outbox.maxAttempts)
        assertEquals(w.clock.now(), outbox.nextAttemptAt)
        assertEquals(WebhookEvents.actionEventId(id), outbox.eventId)
        assertEquals("market.action.grant", outbox.event)

        val body = JsonObject(outbox.body)

        assertEquals(outbox.eventId, body.getString("id"))
        assertEquals("action.grant", body.getString("event"))
        assertEquals("w1", body.getJsonObject("data").getJsonObject("delivery").getString("actionId"))
        assertEquals(FulfillmentStatus.PENDING, d.order(placed.order.id).fulfillmentStatus)

        // core's dispatcher sends it; the 2xx reports back in the same transaction: CONFIRMED
        assertEquals(1, h.tick())

        assertEquals(WebhookDeliveryStatus.SUCCEEDED, h.row(outbox.id).status)
        assertEquals(DeliveryStatus.CONFIRMED, d.row(id).status)
        assertEquals(FulfillmentStatus.FULFILLED, d.order(placed.order.id).fulfillmentStatus)

        val request = g.requestsTo("/hook").single()

        assertEquals(outbox.eventId, request.header("X-Pano-Event-Id"))
        assertEquals("market.action.grant", request.header("X-Pano-Event"))
        assertEquals(outbox.id.toString(), request.header("X-Pano-Delivery"))
        assertEquals(outbox.body, request.bodyText())
        assertNull(request.header("X-Pano-Signature"))
    }

    @Test
    fun `a replayed executor leaves one direct row and the same result`(): Unit = runBlocking {
        val g = gateway().on("POST", "/hook") { Reply.empty(200) }
        val placed = d.place(user = w.fixtures.user("Steve"), actions = listOf(hook("${g.baseUrl}/hook")))

        d.pay(placed)
        assertEquals(1, d.runInline())

        val id = d.rows(placed.order.id).single().id
        val first = JsonObject(d.row(id).result).getLong("webhookDeliveryId")

        // the engine lost the outcome and runs the row again (a D6 recovery): core inserts nothing for a known event id and the executor finds the row it wrote
        sql("UPDATE `${prefix}market_delivery` SET `status` = 'PENDING', `claimToken` = NULL, `claimedUntil` = NULL, `nextAttemptAt` = ? WHERE `id` = ?", w.clock.now(), id)

        assertEquals(1, d.runInline())
        assertEquals(1, h.rows().size)
        assertEquals(first, JsonObject(d.row(id).result).getLong("webhookDeliveryId"))
        assertEquals(DeliveryStatus.SENT, d.row(id).status)
    }

    @Test
    fun `a dead direct row fails the delivery with WEBHOOK_DEAD and a redelivery that succeeds confirms it`(): Unit = runBlocking {
        var status = 410
        val g = gateway().on("POST", "/hook") { Reply.empty(status) }
        val placed = d.place(user = w.fixtures.user("Steve"), actions = listOf(hook("${g.baseUrl}/hook")))

        d.pay(placed)
        d.runInline()

        val id = d.rows(placed.order.id).single().id
        val outboxId = h.rows().single().id

        h.tick()

        assertEquals(WebhookDeliveryStatus.DEAD, h.row(outboxId).status)
        assertEquals(DeliveryStatus.FAILED, d.row(id).status)
        assertEquals(com.panomc.plugins.market.core.delivery.DeliveryError.WEBHOOK_DEAD, d.row(id).lastErrorCode)
        assertEquals(FulfillmentStatus.FAILED, d.order(placed.order.id).fulfillmentStatus)

        status = 200

        assertEquals(RedeliverResult.OK, h.core.redeliver(outboxId))
        h.tick()

        assertEquals(WebhookDeliveryStatus.SUCCEEDED, h.row(outboxId).status)
        assertEquals(DeliveryStatus.CONFIRMED, d.row(id).status)
        assertEquals(FulfillmentStatus.FULFILLED, d.order(placed.order.id).fulfillmentStatus)
        // the same row, the same event id on both attempts
        assertEquals(2, g.requestsTo("/hook").size)
        assertEquals(1, g.requestsTo("/hook").map { it.header("X-Pano-Event-Id") }.toSet().size)
    }

    @Test
    fun `an HMAC action is signed with the stored secret and the receiver can verify it`(): Unit = runBlocking {
        val g = gateway().on("POST", "/hook") { Reply.empty(200) }
        val secret = "whsec_0123456789abcdef"
        val placed = d.place(user = w.fixtures.user("Steve"), actions = listOf(hook("${g.baseUrl}/hook", signing = WebhookSigning.HMAC_SHA256, secret = marketCipher.encrypt(secret))))

        d.pay(placed)
        d.runInline()

        val outbox = h.rows().single()

        assertEquals(RowSigning.HMAC_SHA256, outbox.signing)
        assertTrue(outbox.secret!!.startsWith("v1:"), "stored encrypted")
        assertEquals(secret, h.cipher.decrypt(outbox.secret!!), "core sealed it with its own key, from the plain secret the market's cipher opened")

        h.tick()

        val request = g.requestsTo("/hook").single()
        val header = request.header("X-Pano-Signature")

        assertNotNull(header)
        assertTrue(WebhookSigner.verify(header!!, secret, request.bodyText(), w.clock.now() / 1000L))
        assertFalse(WebhookSigner.verify(header, "whsec_wrongwrongwrong0", request.bodyText(), w.clock.now() / 1000L))
    }

    @Test
    fun `an action whose secret the market cannot read fails its delivery for good and queues nothing`(): Unit = runBlocking {
        val placed = d.place(
            user = w.fixtures.user("Steve"),
            actions = listOf(hook("https://hooks.example.com/x", signing = WebhookSigning.HMAC_SHA256, secret = "v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
        )

        d.pay(placed)
        d.runInline()

        assertTrue(h.rows().isEmpty())
        assertEquals(DeliveryStatus.FAILED, d.rows(placed.order.id).single().status)
    }

    @Test
    fun `an action with DISCORD and HMAC is normalised to NONE and its body is valid Discord JSON for names with quotes and newlines`(): Unit = runBlocking {
        val buyer = "Ste\"ve\\\nx"
        val placed = d.place(
            buyer = buyer, actions = listOf(hook("https://discord.com/api/webhooks/1/abc", WebhookFormat.DISCORD, WebhookSigning.HMAC_SHA256, marketCipher.encrypt("whsec_0123456789abcdef")))
        )

        d.pay(placed)
        assertEquals(1, d.runInline())

        val outbox = h.rows().single()

        // core's direct rows are JSON in its log; the body is sent as the planner rendered it
        assertEquals(RowFormat.JSON, outbox.format)
        assertEquals(RowSigning.NONE, outbox.signing)
        assertNull(outbox.secret)

        val body = JsonObject(outbox.body)

        assertEquals(JsonObject().put("parse", io.vertx.core.json.JsonArray()), body.getJsonObject("allowed_mentions"))

        val embed = body.getJsonArray("embeds").getJsonObject(0)

        assertEquals("Action", embed.getString("title"))
        assertEquals("$buyer got VIP", embed.getString("description"))
        assertEquals(9807270, embed.getInteger("color"))
        assertEquals(buyer, embed.getJsonArray("fields").getJsonObject(0).getString("value"))
        // a Discord body has no envelope id
        assertFalse(body.containsKey("id"))
    }

    @Test
    fun `without a queue the WEBHOOK row is never claimed`(): Unit = runBlocking {
        val bare = DeliveryWorld(w)
        val placed = bare.place(user = w.fixtures.user("Alex"), actions = listOf(hook("https://hooks.example.com/x")))

        bare.pay(placed)

        assertEquals(0, bare.runInline())
        assertEquals(DeliveryStatus.PENDING, bare.rows(placed.order.id).single().status)
        assertTrue(h.rows().isEmpty())
    }
}
