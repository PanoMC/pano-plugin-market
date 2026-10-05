package com.panomc.plugins.market.spi.testkit

import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpServer
import io.vertx.core.http.HttpServerRequest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * One request as the gateway saw it. [body] holds the exact bytes that arrived (no charset decoding, no form or
 * multipart parsing), so signature tests can compare byte for byte.
 */
class Recorded(
    val method: String,
    /** Path without the query string, as sent (not decoded). */
    val path: String,
    /** Raw query string without the `?`, empty when there was none. */
    val query: String,
    /** Header names in the case the client sent them. */
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
    val receivedAtMs: Long
) {
    /** First value of a header, name compared case-insensitively. */
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    fun headerValues(name: String): List<String> = headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    fun bodyText(charset: java.nio.charset.Charset = Charsets.UTF_8): String = String(body, charset)

    override fun toString(): String = "$method $path${if (query.isEmpty()) "" else "?$query"} (${body.size} bytes)"
}

/** What a handler answers with. */
class Reply(
    val status: Int = 200,
    val headers: List<Pair<String, String>> = emptyList(),
    val body: ByteArray = ByteArray(0)
) {
    companion object {
        fun json(body: String, status: Int = 200) =
            Reply(status, listOf("Content-Type" to "application/json"), body.toByteArray(Charsets.UTF_8))

        fun text(body: String, status: Int = 200, contentType: String = "text/plain; charset=utf-8") =
            Reply(status, listOf("Content-Type" to contentType), body.toByteArray(Charsets.UTF_8))

        fun bytes(body: ByteArray, status: Int = 200, contentType: String = "application/octet-stream") =
            Reply(status, listOf("Content-Type" to contentType), body)

        fun empty(status: Int = 200) = Reply(status)

        fun redirect(location: String, status: Int = 302) = Reply(status, listOf("Location" to location))
    }
}

/**
 * Scriptable fake of a payment or shipping gateway: a Vert.x HTTP server on an ephemeral loopback port (17
 * section 5.5, 02 section 14.3). Every request is recorded before anything else happens, whatever the answer is.
 *
 * Routing: `on(method, path)` takes an exact path, or a prefix when [path] ends in a star character (a route for everything below `/hooks/`); an exact
 * route wins over a prefix, the longest prefix wins among prefixes, later registrations replace earlier ones for the
 * same method and path. A request with no route answers 404 `{"message":"no handler"}`. `failNext` and `hang` are
 * checked before the routes. A handler that throws answers 500 `{"message":"handler failed"}`.
 *
 * Thread-safe. [start] and [close] block the calling thread and must not be called on an event-loop thread.
 */
class FakeGateway private constructor(
    private val vertx: Vertx,
    private val ownsVertx: Boolean,
    private val server: HttpServer
) : AutoCloseable {
    private class Route(val method: String, val path: String, val handler: (Recorded) -> Reply) {
        val prefix = path.endsWith("*")
        val stem = if (prefix) path.dropLast(1) else path
        fun matches(m: String, p: String) = method == m && (if (prefix) p.startsWith(stem) else p == path)
    }

    private val log = CopyOnWriteArrayList<Recorded>()
    private val routes = CopyOnWriteArrayList<Route>()
    private val failures = HashMap<String, ArrayDeque<Int>>()
    private val hanging = HashSet<String>()
    private val parked = CopyOnWriteArrayList<HttpServerRequest>()

    @Volatile
    private var closed = false

    val port: Int get() = server.actualPort()

    /** `http://127.0.0.1:<port>`, without a trailing slash. */
    val baseUrl: String get() = "http://$HOST:$port"

    /** Every request received so far, oldest first (a snapshot). */
    val requests: List<Recorded> get() = log.toList()

    fun requestsTo(path: String): List<Recorded> = log.filter { it.path == path }

    fun clearRequests() = log.clear()

    /** Registers [handler] for [method] and [path] (see the class comment for matching). */
    fun on(method: String, path: String, handler: (Recorded) -> Reply): FakeGateway {
        val m = method.uppercase()
        routes.removeIf { it.method == m && it.path == path }
        routes.add(Route(m, path, handler))
        return this
    }

    /** The next request to exactly [path] (any method) is answered with [status] and a small JSON body; one-shot, queued if repeated. */
    fun failNext(path: String, status: Int = 500): FakeGateway {
        require(status in 100..599) { "status $status" }
        synchronized(failures) { failures.getOrPut(path) { ArrayDeque() }.addLast(status) }
        return this
    }

    /** Requests to exactly [path] are accepted and recorded but never answered, until [release] or [close]. */
    fun hang(path: String): FakeGateway {
        synchronized(hanging) { hanging.add(path) }
        return this
    }

    /** Stops hanging [path]: requests that are already parked stay unanswered, new ones are routed again. */
    fun release(path: String): FakeGateway {
        synchronized(hanging) { hanging.remove(path) }
        return this
    }

    private fun handle(req: HttpServerRequest) {
        req.body().onComplete { result ->
            if (result.failed()) {
                runCatching { req.response().setStatusCode(400).end() }
                return@onComplete
            }
            val headers = req.headers().entries().map { it.key to it.value }
            val recorded = Recorded(
                method = req.method().name().uppercase(),
                path = req.path() ?: "/",
                query = req.query() ?: "",
                headers = headers,
                body = result.result().bytes,
                receivedAtMs = System.currentTimeMillis()
            )
            log.add(recorded)
            answer(req, recorded)
        }
    }

    private fun answer(req: HttpServerRequest, r: Recorded) {
        val response = req.response()
        val isHung = synchronized(hanging) { r.path in hanging }
        if (isHung) {
            parked.add(req)
            return
        }
        val failure = synchronized(failures) { failures[r.path]?.removeFirstOrNull() }
        if (failure != null) {
            response.setStatusCode(failure).putHeader("Content-Type", "application/json")
                .end("""{"message":"fake failure"}""")
            return
        }
        val route = routes.filter { it.matches(r.method, r.path) }
            .maxWithOrNull(compareBy<Route>({ !it.prefix }, { it.stem.length }))
        if (route == null) {
            response.setStatusCode(404).putHeader("Content-Type", "application/json").end("""{"message":"no handler"}""")
            return
        }
        val reply = try {
            route.handler(r)
        } catch (e: Throwable) {
            response.setStatusCode(500).putHeader("Content-Type", "application/json").end("""{"message":"handler failed"}""")
            return
        }
        response.setStatusCode(reply.status)
        reply.headers.forEach { (k, v) -> response.headers().add(k, v) }
        response.end(Buffer.buffer(reply.body))
    }

    /** Stops the server, drops parked requests and (when it created one) closes the Vert.x instance. Idempotent. */
    override fun close() {
        if (closed) return
        closed = true
        runCatching { server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS) }
        parked.forEach { runCatching { it.connection().close() } }
        parked.clear()
        if (ownsVertx) runCatching { vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS) }
    }

    companion object {
        const val HOST = "127.0.0.1"

        /** Starts a gateway on [port] (0 = a free one) of the loopback interface; with no [vertx] it owns a private instance. */
        fun start(vertx: Vertx? = null, port: Int = 0): FakeGateway {
            val v = vertx ?: Vertx.vertx()
            val server = v.createHttpServer()
            val gateway = FakeGateway(v, vertx == null, server)
            server.requestHandler { gateway.handle(it) }
            try {
                server.listen(port, HOST).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
            } catch (e: Throwable) {
                if (vertx == null) runCatching { v.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS) }
                throw e
            }
            return gateway
        }
    }
}
