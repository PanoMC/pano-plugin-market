package com.panomc.plugins.market.e2e.support

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One HTTP answer of the instance. Bodies are never logged (they can carry tokens); [error] is the `error` code of an error answer. */
class E2eResponse(val status: Int, val headers: Map<String, List<String>>, val body: ByteArray, val request: String = "") {
    val text: String by lazy { String(body, Charsets.UTF_8) }
    val json: JsonObject? by lazy { runCatching { JsonObject(text) }.getOrNull() }
    val error: String? get() = json?.getString("error")

    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    /** The JSON body; fails the scenario with the status and the error code (not the body) when the answer is not JSON. */
    fun obj(): JsonObject = json ?: throw AssertionError("status $status carried no JSON object")

    fun ok(): E2eResponse {
        if (status !in 200..299) throw AssertionError("$request expected 2xx but got $status error=$error ${if (status >= 400) json?.encode()?.take(400) ?: "" else ""}")
        return this
    }

    override fun toString() = "E2eResponse($request $status error=$error)"
}

/**
 * A client of the instance with its own cookie jar and CSRF token (17 section 8.2 / 8.4): one per virtual buyer, HTTP/1.1 with
 * keep-alive, never following redirects (the scenarios assert the `Location`), never sending `X-Forwarded-For`. Every call logs
 * the method, the path, the status and the `error` code to the test output.
 */
class E2eClient(val baseUrl: String, val label: String = "anon") {
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val cookies = ConcurrentHashMap<String, String>()

    @Volatile
    var csrfToken: String? = null

    @Volatile
    var bearer: String? = null

    @Volatile
    var userId: Long? = null

    @Volatile
    var username: String? = null

    val hasSession: Boolean get() = cookies.isNotEmpty() || bearer != null

    fun request(
        method: String,
        path: String,
        body: Any? = null,
        headers: Map<String, String> = emptyMap(),
        csrf: Boolean = true,
        cookiesOn: Boolean = true,
        timeoutMs: Long = 60_000,
        log: Boolean = true
    ): E2eResponse {
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofMillis(timeoutMs))
        val publisher = when (body) {
            null -> HttpRequest.BodyPublishers.noBody()
            is JsonObject -> HttpRequest.BodyPublishers.ofString(body.encode())
            is JsonArray -> HttpRequest.BodyPublishers.ofString(body.encode())
            is ByteArray -> HttpRequest.BodyPublishers.ofByteArray(body)
            is String -> HttpRequest.BodyPublishers.ofString(body)
            else -> throw IllegalArgumentException("unsupported body ${body.javaClass}")
        }
        val contentTypeSet = headers.keys.any { it.equals("Content-Type", true) }
        if (body != null && !contentTypeSet) builder.header("Content-Type", "application/json")
        builder.header("Accept", "application/json")
        if (cookiesOn && cookies.isNotEmpty()) builder.header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        if (csrf && cookiesOn) csrfToken?.let { builder.header("X-CSRF-Token", it) }
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        headers.forEach { (k, v) -> builder.header(k, v) }
        builder.method(method, publisher)

        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())

        response.headers().allValues("set-cookie").forEach { raw ->
            val pair = raw.substringBefore(';')
            val name = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=', "").trim()
            if (name.isNotEmpty()) if (value.isEmpty()) cookies.remove(name) else cookies[name] = value
        }

        val result = E2eResponse(response.statusCode(), response.headers().map(), response.body(), "$method $path")
        if (log) println("e2e[$label] $method $path -> ${result.status}${result.error?.let { " error=$it" } ?: ""}")
        return result
    }

    fun get(path: String, headers: Map<String, String> = emptyMap(), log: Boolean = true) = request("GET", path, null, headers, log = log)

    fun post(path: String, body: Any? = JsonObject(), headers: Map<String, String> = emptyMap()) = request("POST", path, body, headers)

    fun put(path: String, body: Any? = JsonObject(), headers: Map<String, String> = emptyMap()) = request("PUT", path, body, headers)

    fun delete(path: String, headers: Map<String, String> = emptyMap()) = request("DELETE", path, null, headers)

    /** A `multipart/form-data` request of text fields (the product and category forms of the panel). */
    fun multipart(method: String, path: String, fields: Map<String, String>): E2eResponse {
        val boundary = "----e2e" + UUID.randomUUID().toString().replace("-", "")
        val out = java.io.ByteArrayOutputStream()
        fields.forEach { (name, value) ->
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8))
            out.write(value.toByteArray(Charsets.UTF_8))
            out.write("\r\n".toByteArray(Charsets.UTF_8))
        }
        out.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        return request(method, path, out.toByteArray(), mapOf("Content-Type" to "multipart/form-data; boundary=$boundary"))
    }

    /**
     * Takes over the session of [other] (cookies, CSRF token, identity) while keeping this client's own connection: the platform keeps only a few
     * sessions per user, so ten parallel clients of one buyer must share one login instead of logging in ten times.
     */
    fun adoptSession(other: E2eClient) {
        cookies.clear()
        cookies.putAll(other.cookies)
        csrfToken = other.csrfToken
        bearer = other.bearer
        userId = other.userId
        username = other.username
    }

    /** Keeps the connection of a race actor warm so the gated requests go out on an open socket (17 section 8.4). */
    fun warm() {
        get("/api/market/checkout/config", log = false)
    }

    /** `POST /api/auth/login` as the panel does; keeps the cookies and the CSRF token. */
    fun login(usernameOrEmail: String, password: String, panel: Boolean = false): E2eResponse {
        val answer = post("/api/auth/login", JsonObject().put("usernameOrEmail", usernameOrEmail).put("password", password).apply { if (panel) put("panel", true) })
        answer.json?.getString("csrfToken")?.let { csrfToken = it }
        return answer
    }
}
