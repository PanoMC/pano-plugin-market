package com.panomc.plugins.market.job

import com.panomc.plugins.market.core.webhook.WebhookSigner
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.dao.MarketWebhookEndpointDao
import com.panomc.plugins.market.db.model.WebhookDeliveryStatus
import com.panomc.plugins.market.db.model.WebhookSigning
import com.panomc.plugins.market.spi.testkit.FakeGateway
import com.panomc.plugins.market.spi.testkit.Reply
import com.panomc.plugins.market.support.StubResolver
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.support.WebhookHarness
import io.vertx.core.Vertx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * `WebhookJob` end to end on a real MariaDB and real HTTP to the `FakeGateway` (MK-105, D-03 twin): retries with the same
 * event id, the signature, `DEAD` after `maxAttempts`, the automatic disabling after 50 consecutive failures, ordering per
 * endpoint, stale claims, and the guard at send time.
 */
class WebhookJobIT : MarketDaoITBase() {
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

    private fun harness(resolver: StubResolver = StubResolver(), allowPrivate: Boolean = true, timeoutMs: Long = 5_000L, disableAfter: Int = MarketWebhookEndpointDao.DEFAULT_DISABLE_AFTER) =
        WebhookHarness(w, vertx, resolver, allowPrivate, timeoutMs, disableAfter = disableAfter)

    @Test
    fun `500, 500, 200 gives three attempts with one event id, a fresh signature each time, then SUCCEEDED (D-03)`(): Unit = runBlocking {
        val secret = "whsec_d03_0123456789abcdef"
        val statuses = ArrayDeque(listOf(500, 500, 200))
        val g = gateway().on("POST", "/hook") { Reply.json("""{"seen":true}""", statuses.removeFirst()) }
        val h = harness()
        val endpoint = h.endpoint("${g.baseUrl}/hook", WebhookSigning.HMAC_SHA256, secret)
        h.emit("order.paid", "42", orderId = 42)
        val rowId = h.rows().single().id

        assertEquals(1, h.job.tick())
        var row = h.row(rowId)
        assertEquals(WebhookDeliveryStatus.FAILED, row.status)
        assertEquals(1, row.attempts)
        assertEquals(500, row.lastStatusCode)
        assertEquals("HTTP_500", row.lastError)
        val firstDelay = row.nextAttemptAt!! - w.clock.now()
        assertTrue(firstDelay in 24_000..36_000, "first retry about 30 s later, was $firstDelay")
        assertEquals(1, h.endpointNow(endpoint.id).failureCount)

        // nothing is sent before the schedule says so
        assertEquals(0, h.job.tick())
        assertEquals(1, g.requests.size)

        w.clock.advance(firstDelay)
        assertEquals(1, h.job.tick())
        row = h.row(rowId)
        assertEquals(WebhookDeliveryStatus.FAILED, row.status)
        assertEquals(2, row.attempts)
        val secondDelay = row.nextAttemptAt!! - w.clock.now()
        assertTrue(secondDelay in 48_000..72_000, "second retry about 60 s later, was $secondDelay")
        assertEquals(2, h.endpointNow(endpoint.id).failureCount)

        w.clock.advance(secondDelay)
        assertEquals(1, h.job.tick())
        row = h.row(rowId)
        assertEquals(WebhookDeliveryStatus.SUCCEEDED, row.status)
        assertEquals(3, row.attempts)
        assertEquals(200, row.lastStatusCode)
        assertNull(row.lastError)
        assertEquals(w.clock.now(), row.deliveredAt)
        assertEquals("""{"seen":true}""", row.lastResponse)
        assertNotNull(row.durationMs)
        assertNull(row.nextAttemptAt)
        assertNull(row.claimedUntil)

        val requests = g.requests
        assertEquals(3, requests.size)
        assertEquals(1, requests.map { it.header("X-Pano-Event-Id") }.toSet().size, "one event id on every attempt")
        assertEquals(row.eventId, requests[0].header("X-Pano-Event-Id"))
        assertEquals(listOf("1", "2", "3"), requests.map { it.header("X-Pano-Attempt") })
        assertEquals(1, requests.map { it.header("X-Pano-Delivery") }.toSet().size)
        assertEquals(1, requests.map { it.bodyText() }.toSet().size, "the body is identical on every attempt")
        val signatures = requests.map { it.header("X-Pano-Signature")!! }
        assertEquals(3, signatures.toSet().size, "t is fresh per attempt")
        for ((i, r) in requests.withIndex()) {
            val t = signatures[i].substringAfter("t=").substringBefore(",").toLong()
            assertTrue(WebhookSigner.verify(signatures[i], secret, r.bodyText(), t))
        }

        val endpointAfter = h.endpointNow(endpoint.id)
        assertEquals(0, endpointAfter.failureCount, "a success resets the consecutive failures")
        assertEquals(200, endpointAfter.lastStatusCode)
        assertEquals(w.clock.now(), endpointAfter.lastDeliveryAt)
        assertEquals(0, h.job.tick())
    }

    @Test
    fun `a row is dead after maxAttempts and is never sent again`(): Unit = runBlocking {
        val g = gateway().on("POST", "/hook") { Reply.text("broken", 500) }
        val h = harness()
        val endpoint = h.endpoint("${g.baseUrl}/hook", maxAttempts = 3)
        h.emit("order.paid", "1")
        val id = h.rows().single().id

        repeat(3) {
            assertEquals(1, h.job.tick())
            w.clock.advance(7 * 3_600_000L)
        }

        val row = h.row(id)
        assertEquals(WebhookDeliveryStatus.DEAD, row.status)
        assertEquals(3, row.attempts)
        assertEquals("HTTP_500", row.lastError)
        assertEquals("broken", row.lastResponse)
        assertNull(row.nextAttemptAt)
        assertEquals(3, g.requests.size)
        assertEquals(0, h.job.tick())
        assertEquals(3, g.requests.size)
        assertEquals(3, h.endpointNow(endpoint.id).failureCount)
    }

    @Test
    fun `an endpoint is disabled after 50 consecutive failures and its open rows end dead`(): Unit = runBlocking {
        val g = gateway().on("POST", "/hook") { Reply.empty(500) }
        val h = harness()
        val endpoint = h.endpoint("${g.baseUrl}/hook", maxAttempts = 1)
        for (i in 1..55) h.emit("order.paid", "$i")

        var ticks = 0
        while (h.endpointNow(endpoint.id).enabled && ticks++ < 10) h.job.tick()

        val after = h.endpointNow(endpoint.id)
        assertFalse(after.enabled)
        assertEquals(50, after.failureCount)
        assertEquals(MarketWebhookEndpointDao.AUTO_DISABLED_REASON, after.disabledReason)
        assertEquals(50, g.requests.size, "the five rows behind the 50th failure are never sent")
        val rows = h.rows()
        assertEquals(55, rows.size)
        assertTrue(rows.all { it.status == WebhookDeliveryStatus.DEAD })
        assertEquals(5, rows.count { it.lastError == "ENDPOINT_DISABLED" })
        assertEquals(50, rows.count { it.lastError == "HTTP_500" })

        // no new rows are created while disabled
        assertEquals(0, h.emit("order.paid", "later"))
    }

    @Test
    fun `49 failures and a success keep the endpoint enabled and restart the count`(): Unit = runBlocking {
        val calls = AtomicInteger()
        val g = gateway().on("POST", "/hook") { Reply.empty(if (calls.incrementAndGet() == 50) 200 else 500) }
        val h = harness()
        val endpoint = h.endpoint("${g.baseUrl}/hook", maxAttempts = 1)
        for (i in 1..51) h.emit("order.paid", "$i")

        repeat(4) { h.job.tick() }

        assertEquals(51, g.requests.size)
        val after = h.endpointNow(endpoint.id)
        assertTrue(after.enabled)
        assertEquals(1, after.failureCount, "49 failures, one success, one failure")
        assertEquals(1, h.rows().count { it.status == WebhookDeliveryStatus.SUCCEEDED })
    }

    @Test
    fun `the rows of one endpoint are sent one after the other in id order`(): Unit = runBlocking {
        val g = gateway().on("POST", "/hook") { Reply.empty(200) }
        val h = harness()
        h.endpoint("${g.baseUrl}/hook")
        for (i in 1..12) h.emit("order.paid", "$i")
        val ids = h.rows().map { it.id.toString() }

        assertEquals(12, h.job.tick())

        assertEquals(ids, g.requests.map { it.header("X-Pano-Delivery") })
        assertTrue(h.rows().all { it.status == WebhookDeliveryStatus.SUCCEEDED })
    }

    @Test
    fun `several endpoints are served in one tick`(): Unit = runBlocking {
        val g = gateway().on("POST", "/a") { Reply.empty(200) }.on("POST", "/b") { Reply.empty(201) }.on("POST", "/c") { Reply.empty(500) }
        val h = harness()
        h.endpoint("${g.baseUrl}/a")
        h.endpoint("${g.baseUrl}/b")
        h.endpoint("${g.baseUrl}/c")
        h.emit("order.paid", "1")

        assertEquals(3, h.job.tick())

        assertEquals(setOf(WebhookDeliveryStatus.SUCCEEDED, WebhookDeliveryStatus.FAILED), h.rows().map { it.status }.toSet())
        assertEquals(2, h.rows().count { it.status == WebhookDeliveryStatus.SUCCEEDED })
    }

    @Test
    fun `410 ends the row at once, a redirect is a failed attempt that is not followed`(): Unit = runBlocking {
        val g = gateway()
            .on("POST", "/gone") { Reply.empty(410) }
            .on("POST", "/moved") { Reply.redirect("/target", 301) }
            .on("POST", "/target") { Reply.empty(200) }
        val h = harness()
        val gone = h.endpoint("${g.baseUrl}/gone")
        val moved = h.endpoint("${g.baseUrl}/moved")
        h.emit("order.paid", "1")

        h.job.tick()

        val rows = h.rows().associateBy { it.endpointId }
        assertEquals(WebhookDeliveryStatus.DEAD, rows[gone.id]!!.status)
        assertEquals("GONE", rows[gone.id]!!.lastError)
        assertEquals(410, rows[gone.id]!!.lastStatusCode)
        assertEquals(WebhookDeliveryStatus.FAILED, rows[moved.id]!!.status)
        assertEquals("REDIRECT_NOT_FOLLOWED", rows[moved.id]!!.lastError)
        assertEquals(301, rows[moved.id]!!.lastStatusCode)
        assertTrue(g.requestsTo("/target").isEmpty())
        assertEquals(1, h.endpointNow(moved.id).failureCount)
    }

    @Test
    fun `a target that became private is refused at send time and nothing is sent`(): Unit = runBlocking {
        val g = gateway().on("POST", "/hook") { Reply.empty(200) }
        val resolver = StubResolver("hooks.example.com" to listOf("93.184.216.34"))
        val h = harness(resolver, allowPrivate = true)
        val endpoint = h.endpoint("http://hooks.example.com:${g.port}/hook")
        h.emit("order.paid", "1")
        // DNS now answers with the metadata address: even the allowPrivate flag cannot save it
        resolver.set("hooks.example.com", "169.254.169.254")

        h.job.tick()

        val row = h.rows().single()
        assertEquals(WebhookDeliveryStatus.DEAD, row.status)
        assertEquals("URL_GUARD:PRIVATE_ADDRESS", row.lastError)
        assertTrue(g.requests.isEmpty())
        assertEquals(1, h.endpointNow(endpoint.id).failureCount)
    }

    @Test
    fun `a name that does not resolve is retried like a network failure`(): Unit = runBlocking {
        val h = harness(StubResolver())
        h.endpoint("https://nx.example.com/hook")
        h.emit("order.paid", "1")

        h.job.tick()

        val row = h.rows().single()
        assertEquals(WebhookDeliveryStatus.FAILED, row.status)
        assertEquals("URL_GUARD:DNS", row.lastError)
        assertNotNull(row.nextAttemptAt)
    }

    @Test
    fun `an HMAC endpoint whose secret cannot be read is dead and never gets an unsigned request`(): Unit = runBlocking {
        val g = gateway().on("POST", "/hook") { Reply.empty(200) }
        val h = harness()
        h.endpoint("${g.baseUrl}/hook", WebhookSigning.HMAC_SHA256, secret = "whsec_0123456789abcdef")
        h.emit("order.paid", "1")
        sql("UPDATE `pano_market_webhook_delivery` SET `secret` = 'v1:AAAA'")

        h.job.tick()

        assertEquals("SECRET_UNREADABLE", h.rows().single().lastError)
        assertEquals(WebhookDeliveryStatus.DEAD, h.rows().single().status)
        assertTrue(g.requests.isEmpty())
    }

    @Test
    fun `a worker that died mid-send leaves a claim that the next tick retries`(): Unit = runBlocking {
        val g = gateway().on("POST", "/hook") { Reply.empty(200) }
        val h = harness()
        h.endpoint("${g.baseUrl}/hook")
        h.emit("order.paid", "1")
        val claimed = h.service.claimDue().single() // the "worker" claims and dies
        assertEquals(WebhookDeliveryStatus.SENDING, h.row(claimed.id).status)

        assertEquals(0, h.job.tick())
        w.clock.advance(61_000)
        assertEquals(1, h.job.tick())

        val row = h.row(claimed.id)
        assertEquals(WebhookDeliveryStatus.SUCCEEDED, row.status)
        assertEquals(2, row.attempts)
        assertEquals("2", g.requests.single().header("X-Pano-Attempt"))
        assertEquals(claimed.eventId, g.requests.single().header("X-Pano-Event-Id"))
    }

    @Test
    fun `a second tick while one is running returns at once and does not double send`(): Unit = runBlocking {
        val g = gateway().hang("/hook")
        val h = harness(timeoutMs = 700)
        h.endpoint("${g.baseUrl}/hook")
        h.emit("order.paid", "1")

        val first = async(Dispatchers.Default) { h.job.tick() }
        while (g.requests.isEmpty()) delay(10)
        assertEquals(0, h.job.tick(), "the running tick owns the queue")
        assertEquals(1, first.await())

        assertEquals(1, g.requests.size)
        val row = h.rows().single()
        assertEquals(WebhookDeliveryStatus.FAILED, row.status)
        assertEquals("TIMEOUT", row.lastError)
        assertNull(row.lastStatusCode)
        assertEquals(1, h.endpointNow(row.endpointId).failureCount)
    }

    @Test
    fun `a redelivered row is sent again with the same event id`(): Unit = runBlocking {
        val g = gateway().on("POST", "/hook") { Reply.empty(200) }
        val h = harness()
        h.endpoint("${g.baseUrl}/hook")
        h.emit("order.paid", "1")
        h.job.tick()
        val id = h.rows().single().id
        assertEquals(WebhookDeliveryStatus.SUCCEEDED, h.row(id).status)

        h.service.redeliver(id)
        assertEquals(1, h.job.tick())

        assertEquals(2, g.requests.size)
        assertEquals(g.requests[0].header("X-Pano-Event-Id"), g.requests[1].header("X-Pano-Event-Id"))
        assertEquals("1", g.requests[1].header("X-Pano-Attempt"))
        assertEquals(1L, count("market_webhook_delivery"))
    }

    @Test
    fun `an empty queue is a cheap no-op`(): Unit = runBlocking {
        val h = harness()
        assertEquals(0, h.job.tick())
        assertEquals(0, h.job.tick())
        assertEquals(0L, count("market_webhook_delivery"))
    }
}
