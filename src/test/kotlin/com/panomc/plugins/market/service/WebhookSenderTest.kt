package com.panomc.plugins.market.service

import com.panomc.plugins.market.core.webhook.WebhookSigner
import com.panomc.plugins.market.db.model.MarketWebhookEndpoint
import com.panomc.plugins.market.db.model.WebhookSigning
import com.panomc.plugins.market.spi.testkit.FakeGateway
import com.panomc.plugins.market.spi.testkit.Reply
import com.panomc.plugins.market.support.FakeClock
import com.panomc.plugins.market.support.StubResolver
import com.panomc.plugins.market.support.WebhookTestSupport
import io.vertx.core.Vertx
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
import org.junit.jupiter.api.TestInstance

/**
 * Real HTTP from `WebhookSender` / `OutboundHttp` to the `FakeGateway` (17 section 11.2): headers, signature, status
 * handling, redirects, timeouts, the 2 KiB response cap, DNS pinning and the guard in front of every request.
 * The gateway listens on loopback, so these tests run with `allowPrivate = true` unless they test the refusal.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebhookSenderTest {
    private lateinit var vertx: Vertx
    private val gateways = ArrayList<FakeGateway>()
    private val clock = FakeClock()
    private val cipher = WebhookTestSupport.cipher()
    private val secret = "whsec_test_0123456789abcdef"

    @BeforeAll
    fun start() {
        vertx = Vertx.vertx()
    }

    @AfterAll
    fun stop() {
        vertx.close().toCompletionStage().toCompletableFuture().get()
    }

    @AfterEach
    fun closeGateways() {
        gateways.forEach { it.close() }
        gateways.clear()
    }

    private fun gateway(): FakeGateway = FakeGateway.start(vertx).also { gateways += it }

    private fun sender(resolver: StubResolver = StubResolver(), allowPrivate: Boolean = true, timeoutMs: Long = 5_000L) =
        WebhookSender(WebhookTestSupport.outbound(vertx, resolver, timeoutMs), cipher, clock, "test", { allowPrivate })

    @Test
    fun `a delivery carries the event headers, the exact body bytes and a signature that verifies`() = runBlocking {
        val g = gateway().on("POST", "/hook") { Reply.json("""{"ok":true}""") }
        val body = """{"id":"e1","event":"order.paid","name":"Çağrı \"quoted\"\n🎮"}"""
        val row = WebhookTestSupport.row("${g.baseUrl}/hook", body, WebhookSigning.HMAC_SHA256, cipher.encrypt(secret), attempts = 3, id = 77)

        val attempt = sender().send(row, null)

        assertEquals(200, attempt.statusCode)
        assertNull(attempt.error)
        assertEquals("""{"ok":true}""", attempt.response)
        val req = g.requests.single()
        assertEquals("POST", req.method)
        assertEquals(body, req.bodyText())
        assertEquals("application/json; charset=utf-8", req.header("Content-Type"))
        assertEquals("Pano-Market/test", req.header("User-Agent"))
        assertEquals("order.paid", req.header("X-Pano-Event"))
        assertEquals("00000000-0000-4000-8000-000000000001", req.header("X-Pano-Event-Id"))
        assertEquals("77", req.header("X-Pano-Delivery"))
        assertEquals("3", req.header("X-Pano-Attempt"))
        val signature = req.header("X-Pano-Signature")
        assertNotNull(signature)
        assertTrue(signature!!.startsWith("t=${clock.now() / 1000},v1="))
        assertTrue(WebhookSigner.verify(signature, secret, req.bodyText(), clock.now() / 1000))
    }

    @Test
    fun `signing NONE sends no signature header`() = runBlocking {
        val g = gateway().on("POST", "/hook") { Reply.empty(204) }
        val attempt = sender().send(WebhookTestSupport.row("${g.baseUrl}/hook", signing = WebhookSigning.NONE, secret = cipher.encrypt(secret)), null)
        assertEquals(204, attempt.statusCode)
        assertNull(g.requests.single().header("X-Pano-Signature"))
    }

    @Test
    fun `the timestamp of the signature is fresh per attempt while the body stays identical`() = runBlocking {
        val g = gateway().on("POST", "/hook") { Reply.empty(200) }
        val row = WebhookTestSupport.row("${g.baseUrl}/hook", signing = WebhookSigning.HMAC_SHA256, secret = cipher.encrypt(secret))
        val s = sender()
        s.send(row, null)
        clock.advance(61_000)
        s.send(WebhookTestSupport.row("${g.baseUrl}/hook", signing = WebhookSigning.HMAC_SHA256, secret = cipher.encrypt(secret), attempts = 2), null)
        val (first, second) = g.requests
        assertEquals(first.bodyText(), second.bodyText())
        assertEquals(first.header("X-Pano-Event-Id"), second.header("X-Pano-Event-Id"))
        assertTrue(first.header("X-Pano-Signature") != second.header("X-Pano-Signature"))
        assertEquals("2", second.header("X-Pano-Attempt"))
    }

    @Test
    fun `a 3xx answer is a failed attempt and the redirect is not followed`() = runBlocking {
        val g = gateway()
            .on("POST", "/hook") { Reply.redirect("/elsewhere", 302) }
            .on("POST", "/elsewhere") { Reply.empty(200) }
            .on("GET", "/elsewhere") { Reply.empty(200) }
        for (code in listOf(301, 302, 303, 307, 308)) {
            g.on("POST", "/hook") { Reply.redirect("/elsewhere", code) }
            val attempt = sender().send(WebhookTestSupport.row("${g.baseUrl}/hook"), null)
            assertEquals(code, attempt.statusCode)
            assertEquals("REDIRECT_NOT_FOLLOWED", attempt.error)
        }
        assertTrue(g.requestsTo("/elsewhere").isEmpty(), "the redirect target must never be requested")
    }

    @Test
    fun `4xx and 5xx statuses are reported as they are and the response is kept`() = runBlocking {
        val g = gateway().on("POST", "/hook") { Reply.text("nope", 500) }
        val a = sender().send(WebhookTestSupport.row("${g.baseUrl}/hook"), null)
        assertEquals(500, a.statusCode)
        assertEquals("nope", a.response)
        g.on("POST", "/hook") { Reply(429, listOf("Retry-After" to "120")) }
        val b = sender().send(WebhookTestSupport.row("${g.baseUrl}/hook"), null)
        assertEquals(429, b.statusCode)
        assertEquals("120", b.retryAfter)
    }

    @Test
    fun `a server that never answers ends as TIMEOUT`() = runBlocking {
        val g = gateway().hang("/hook")
        val started = System.nanoTime()
        val attempt = sender(timeoutMs = 400).send(WebhookTestSupport.row("${g.baseUrl}/hook"), null)
        assertNull(attempt.statusCode)
        assertEquals("TIMEOUT", attempt.error)
        assertTrue(attempt.retryable)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 4_000)
    }

    @Test
    fun `a closed port is an IO failure that can be retried`() = runBlocking {
        val g = gateway()
        val url = "${g.baseUrl}/hook"
        g.close()
        val attempt = sender().send(WebhookTestSupport.row(url), null)
        assertNull(attempt.statusCode)
        assertTrue(attempt.error!!.startsWith("IO:") || attempt.error == "TIMEOUT", attempt.error)
        assertTrue(attempt.retryable)
    }

    @Test
    fun `the response body is cut at 2048 characters`() = runBlocking {
        val g = gateway().on("POST", "/hook") { Reply.text("x".repeat(500_000), 200) }
        val attempt = sender().send(WebhookTestSupport.row("${g.baseUrl}/hook"), null)
        assertEquals(200, attempt.statusCode)
        assertEquals(2048, attempt.response!!.length)
    }

    @Test
    fun `the connection goes to the validated address, not to a second lookup of the name`() = runBlocking {
        val g = gateway().on("POST", "/hook") { Reply.empty(200) }
        // "pinned.test" does not exist in any DNS: only a connection to the address from the resolver can succeed
        val resolver = StubResolver("pinned.test" to listOf("127.0.0.1"))
        val attempt = sender(resolver).send(WebhookTestSupport.row("http://pinned.test:${g.port}/hook"), null)
        assertEquals(200, attempt.statusCode)
        assertEquals("pinned.test:${g.port}", g.requests.single().header("Host"))
        assertEquals(1, resolver.lookups.get())
    }

    @Test
    fun `cloud metadata and link-local targets are refused even with allowPrivate and nothing is sent`() = runBlocking {
        val g = gateway().on("POST", "/hook") { Reply.empty(200) }
        val resolver = StubResolver(
            "meta.example.com" to listOf("169.254.169.254"), "v6.example.com" to listOf("fe80::1"),
            "mixed.example.com" to listOf("127.0.0.1", "169.254.169.254"), "aws6.example.com" to listOf("fd00:ec2::254")
        )
        for (host in listOf("meta.example.com", "v6.example.com", "mixed.example.com", "aws6.example.com")) {
            val attempt = sender(resolver, allowPrivate = true).send(WebhookTestSupport.row("http://$host:${g.port}/hook"), null)
            assertNull(attempt.statusCode, host)
            assertEquals("URL_GUARD:PRIVATE_ADDRESS", attempt.error, host)
            assertFalse(attempt.retryable, host)
        }
        val literal = sender(allowPrivate = true).send(WebhookTestSupport.row("http://169.254.169.254/latest/meta-data/"), null)
        assertEquals("URL_GUARD:PRIVATE_ADDRESS", literal.error)
        assertTrue(g.requests.isEmpty())
    }

    @Test
    fun `loopback and private targets are refused without the flag and nothing is sent`() = runBlocking {
        val g = gateway().on("POST", "/hook") { Reply.empty(200) }
        val resolver = StubResolver("evil.example.com" to listOf("127.0.0.1"), "lan.example.com" to listOf("192.168.1.5"))
        for (url in listOf("http://${"127.0.0.1"}:${g.port}/hook", "http://evil.example.com:${g.port}/hook", "http://lan.example.com:${g.port}/hook")) {
            val attempt = sender(resolver, allowPrivate = false).send(WebhookTestSupport.row(url), null)
            assertEquals("URL_GUARD:PRIVATE_ADDRESS", attempt.error, url)
            assertFalse(attempt.retryable)
        }
        assertTrue(g.requests.isEmpty())
    }

    @Test
    fun `an unresolvable host is a retryable DNS failure`() = runBlocking {
        val attempt = sender(StubResolver()).send(WebhookTestSupport.row("https://nx.example.com/hook"), null)
        assertEquals("URL_GUARD:DNS", attempt.error)
        assertTrue(attempt.retryable)
    }

    @Test
    fun `a bad scheme or a user info never reaches the network`() = runBlocking {
        val s = sender()
        assertEquals("URL_GUARD:SCHEME", s.send(WebhookTestSupport.row("ftp://example.com/x"), null).error)
        assertEquals("URL_GUARD:USERINFO", s.send(WebhookTestSupport.row("https://a:b@example.com/x"), null).error)
        assertEquals("URL_GUARD:PORT", s.send(WebhookTestSupport.row("https://example.com:25/x"), null).error)
    }

    @Test
    fun `an HMAC endpoint never gets an unsigned request when the secret is missing or unreadable`() = runBlocking {
        val g = gateway().on("POST", "/hook") { Reply.empty(200) }
        val missing = sender().send(WebhookTestSupport.row("${g.baseUrl}/hook", signing = WebhookSigning.HMAC_SHA256, secret = null), null)
        assertEquals("SECRET_UNREADABLE", missing.error)
        assertFalse(missing.retryable)
        val damaged = sender().send(WebhookTestSupport.row("${g.baseUrl}/hook", signing = WebhookSigning.HMAC_SHA256, secret = "v1:AAAA"), null)
        assertEquals("SECRET_UNREADABLE", damaged.error)
        val blank = sender().send(WebhookTestSupport.row("${g.baseUrl}/hook", signing = WebhookSigning.HMAC_SHA256, secret = cipher.encrypt("")), null)
        assertEquals("SECRET_UNREADABLE", blank.error)
        assertTrue(g.requests.isEmpty())
    }

    @Test
    fun `endpoint headers are appended last, decrypted, and cannot override the framework headers`() = runBlocking {
        val g = gateway().on("POST", "/hook") { Reply.empty(200) }
        val stored = cipher.encrypt("""{"Authorization":"Bearer abc","X-Pano-Event":"forged","Host":"evil.example.com","X-Trace":"t-1"}""")
        val endpoint = MarketWebhookEndpoint(id = 1, url = "${g.baseUrl}/hook", headers = stored)
        val attempt = sender().send(WebhookTestSupport.row(endpoint.url), endpoint)
        assertEquals(200, attempt.statusCode)
        val req = g.requests.single()
        assertEquals("Bearer abc", req.header("Authorization"))
        assertEquals("t-1", req.header("X-Trace"))
        assertEquals(listOf("order.paid"), req.headerValues("X-Pano-Event"))
        assertEquals("127.0.0.1:${g.port}", req.header("Host"))
        val names = req.headers.map { it.first.lowercase() }
        assertTrue(names.indexOf("authorization") > names.indexOf("x-pano-attempt"))
    }

    @Test
    fun `unreadable endpoint headers stop the delivery`() = runBlocking {
        val g = gateway().on("POST", "/hook") { Reply.empty(200) }
        val endpoint = MarketWebhookEndpoint(id = 1, url = "${g.baseUrl}/hook", headers = "v1:AAAA")
        val attempt = sender().send(WebhookTestSupport.row(endpoint.url), endpoint)
        assertEquals("HEADERS_UNREADABLE", attempt.error)
        assertFalse(attempt.retryable)
        val notObject = MarketWebhookEndpoint(id = 1, url = endpoint.url, headers = cipher.encrypt("[1,2]"))
        assertEquals("HEADERS_UNREADABLE", sender().send(WebhookTestSupport.row(endpoint.url), notObject).error)
        assertTrue(g.requests.isEmpty())
    }
}
