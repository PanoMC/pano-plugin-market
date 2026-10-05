package com.panomc.plugins.market.component

import com.panomc.plugins.market.spi.testkit.FakeGateway
import com.panomc.plugins.market.spi.testkit.Reply
import io.vertx.core.Vertx
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Self-test of `spi.testkit.FakeGateway` (17 section 11.2, 15): raw bytes, one-shot failures, hang, routing. */
class FakeGatewaySelfTest {
    private val opened = ArrayList<FakeGateway>()
    private val client: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    private fun gateway(): FakeGateway = FakeGateway.start().also { opened += it }

    @AfterEach
    fun closeAll() {
        opened.forEach { it.close() }
        opened.clear()
    }

    private fun send(
        g: FakeGateway,
        method: String,
        path: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 5_000
    ): HttpResponse<ByteArray> {
        val b = HttpRequest.newBuilder(URI.create(g.baseUrl + path)).timeout(Duration.ofMillis(timeoutMs))
        headers.forEach { (k, v) -> b.header(k, v) }
        b.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
        return client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    @Test
    fun `records the raw body bytes exactly, also bytes that are not valid text`() {
        val g = gateway()
        val every = ByteArray(256) { it.toByte() }
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "{ \"a\" :  \"çşı\" }\r\n".toByteArray()
        val bodies = listOf(every, bom, ByteArray(0), ByteArray(300_000) { (it * 31).toByte() })
        bodies.forEach { send(g, "POST", "/raw", it, mapOf("Content-Type" to "application/x-www-form-urlencoded")) }
        val seen = g.requests
        assertEquals(bodies.size, seen.size)
        bodies.forEachIndexed { i, expected -> assertArrayEquals(expected, seen[i].body, "body $i") }
    }

    @Test
    fun `a form post is not parsed and a multipart body is kept as sent`() {
        val g = gateway()
        val form = "a=1&a=2&b=%C3%A7&empty=".toByteArray()
        send(g, "POST", "/form", form, mapOf("Content-Type" to "application/x-www-form-urlencoded"))
        val multipart = "--XX\r\nContent-Disposition: form-data; name=\"f\"\r\n\r\nvalue\r\n--XX--\r\n".toByteArray()
        send(g, "POST", "/multi", multipart, mapOf("Content-Type" to "multipart/form-data; boundary=XX"))
        assertArrayEquals(form, g.requestsTo("/form").single().body)
        assertArrayEquals(multipart, g.requestsTo("/multi").single().body)
    }

    @Test
    fun `a chunked body is recorded whole`() {
        val g = gateway()
        Socket(FakeGateway.HOST, g.port).use { s ->
            s.soTimeout = 5_000
            val out = s.getOutputStream()
            out.write("POST /chunked HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n".toByteArray())
            out.write("5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n".toByteArray())
            out.flush()
            val head = s.getInputStream().readNBytes(12)
            assertEquals("HTTP/1.1 404", String(head))
        }
        assertEquals("hello world", g.requestsTo("/chunked").single().bodyText())
    }

    @Test
    fun `method, path, query and headers are recorded as sent`() {
        val g = gateway()
        send(g, "PUT", "/a/b%20c?x=1&y=%C3%A7", "z".toByteArray(), mapOf("X-Custom-Thing" to "v1", "Authorization" to "Bearer t"))
        val r = g.requests.single()
        assertEquals("PUT", r.method)
        assertEquals("/a/b%20c", r.path)
        assertEquals("x=1&y=%C3%A7", r.query)
        assertEquals("v1", r.header("x-custom-thing"))
        assertEquals("Bearer t", r.header("AUTHORIZATION"))
        assertNull(r.header("missing"))
        assertTrue(r.receivedAtMs > 0)
        assertEquals(listOf("z"), listOf(r.bodyText()))
    }

    @Test
    fun `failNext answers once with the status and then the route again`() {
        val g = gateway()
        g.on("POST", "/pay") { Reply.json("""{"ok":true}""") }
        g.failNext("/pay", 503)
        val first = send(g, "POST", "/pay", "1".toByteArray())
        val second = send(g, "POST", "/pay", "2".toByteArray())
        val third = send(g, "POST", "/pay", "3".toByteArray())
        assertEquals(503, first.statusCode())
        assertEquals(200, second.statusCode())
        assertEquals(200, third.statusCode())
        assertEquals("""{"ok":true}""", String(second.body()))
        // the failed request was recorded too, in order
        assertEquals(listOf("1", "2", "3"), g.requestsTo("/pay").map { it.bodyText() })
    }

    @Test
    fun `failNext is per path and several calls queue up in order`() {
        val g = gateway()
        g.on("GET", "/a") { Reply.empty(204) }
        g.on("GET", "/b") { Reply.empty(204) }
        g.failNext("/a", 500).failNext("/a", 429)
        assertEquals(204, send(g, "GET", "/b").statusCode())
        assertEquals(500, send(g, "GET", "/a").statusCode())
        assertEquals(429, send(g, "GET", "/a").statusCode())
        assertEquals(204, send(g, "GET", "/a").statusCode())
    }

    @Test
    fun `hang never answers so the client times out, and the request is still recorded`() {
        val g = gateway()
        g.on("GET", "/slow") { Reply.text("never used") }
        g.hang("/slow")
        val started = System.nanoTime()
        assertThrows(HttpTimeoutException::class.java) { send(g, "GET", "/slow", timeoutMs = 400) }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue(elapsedMs >= 350, "returned after only $elapsedMs ms")
        assertEquals(1, g.requestsTo("/slow").size)
        // other paths are unaffected, and releasing the path routes new requests again
        g.on("GET", "/fast") { Reply.text("fast") }
        assertEquals("fast", String(send(g, "GET", "/fast").body()))
        g.release("/slow")
        assertEquals("never used", String(send(g, "GET", "/slow").body()))
    }

    @Test
    fun `routes - exact beats prefix, longest prefix wins, method matters, a new registration replaces`() {
        val g = gateway()
        g.on("GET", "/hooks/*") { Reply.text("prefix") }
        g.on("get", "/hooks/store") { Reply.text("exact") }
        g.on("GET", "/hooks/store/deep/*") { Reply.text("deep") }
        assertEquals("exact", String(send(g, "GET", "/hooks/store").body()))
        assertEquals("prefix", String(send(g, "GET", "/hooks/other").body()))
        assertEquals("deep", String(send(g, "GET", "/hooks/store/deep/x").body()))
        assertEquals("prefix", String(send(g, "GET", "/hooks/store/shallow").body()))
        assertEquals(404, send(g, "POST", "/hooks/store").statusCode())
        g.on("GET", "/hooks/store") { Reply.text("replaced") }
        assertEquals("replaced", String(send(g, "GET", "/hooks/store").body()))
    }

    @Test
    fun `an unknown route answers 404 and is recorded, a throwing handler answers 500`() {
        val g = gateway()
        g.on("GET", "/boom") { error("handler bug") }
        val none = send(g, "GET", "/nothing")
        assertEquals(404, none.statusCode())
        assertEquals("""{"message":"no handler"}""", String(none.body()))
        assertEquals(500, send(g, "GET", "/boom").statusCode())
        assertEquals(listOf("/nothing", "/boom"), g.requests.map { it.path })
    }

    @Test
    fun `the handler sees the request and its reply status, headers and bytes reach the client`() {
        val g = gateway()
        g.on("POST", "/echo") { req ->
            Reply(201, listOf("X-Seen-Length" to req.body.size.toString(), "Content-Type" to "application/octet-stream"), req.body.reversedArray())
        }
        val res = send(g, "POST", "/echo", byteArrayOf(1, 2, 3, 0xFF.toByte()))
        assertEquals(201, res.statusCode())
        assertEquals("4", res.headers().firstValue("x-seen-length").orElse(null))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 3, 2, 1), res.body())
        val redirect = gateway().also { it.on("GET", "/r") { Reply.redirect("https://example.invalid/x") } }
        val r = send(redirect, "GET", "/r")
        assertEquals(302, r.statusCode())
        assertEquals("https://example.invalid/x", r.headers().firstValue("location").orElse(null))
    }

    @Test
    fun `concurrent requests are all recorded`() {
        val g = gateway()
        g.on("POST", "/n") { Reply.empty(200) }
        val pool = Executors.newFixedThreadPool(16)
        try {
            val futures = (0 until 100).map { i -> pool.submit<Int> { send(g, "POST", "/n", "$i".toByteArray()).statusCode() } }
            assertTrue(futures.all { it.get(30, TimeUnit.SECONDS) == 200 })
        } finally {
            pool.shutdownNow()
        }
        assertEquals((0 until 100).map { "$it" }.toSet(), g.requestsTo("/n").map { it.bodyText() }.toSet())
        assertEquals(100, g.requests.size)
        g.clearRequests()
        assertTrue(g.requests.isEmpty())
    }

    @Test
    fun `close frees the port, is idempotent and leaves a shared vertx running`() {
        val shared = Vertx.vertx()
        try {
            val g = FakeGateway.start(shared)
            val port = g.port
            assertTrue(port > 0)
            assertEquals(404, send(g, "GET", "/x").statusCode())
            g.close()
            g.close()
            ServerSocket(port).use { assertEquals(port, it.localPort) } // bindable again
            // the shared Vert.x is still usable
            val again = FakeGateway.start(shared)
            try {
                assertEquals(404, send(again, "GET", "/x").statusCode())
            } finally {
                again.close()
            }
        } finally {
            shared.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `a fixed port that is taken fails to start and a free one is honoured`() {
        val g = gateway()
        val e = assertThrows(Exception::class.java) { FakeGateway.start(port = g.port) }
        assertNotNull(e)
        val free = ServerSocket(0).use { it.localPort }
        val onFree = FakeGateway.start(port = free).also { opened += it }
        assertEquals(free, onFree.port)
        assertEquals("http://127.0.0.1:$free", onFree.baseUrl)
        assertFalse(g.port == onFree.port)
    }
}
