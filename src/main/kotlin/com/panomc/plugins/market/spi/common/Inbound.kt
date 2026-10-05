package com.panomc.plugins.market.spi.common

import io.vertx.core.json.JsonObject
import java.net.URLDecoder
import java.nio.charset.Charset

/** Inbound HTTP traffic kinds, shared by payment and shipping providers. */
enum class InboundKind { WEBHOOK, NOTIFY, RETURN }

/** One request as market received it. Market constructs it, the provider only reads it. */
class InboundRequest(
    val kind: InboundKind,
    /** `"default"` or the path suffix. */
    val channel: String,
    val method: String,
    /** Undecoded query string. */
    val rawQuery: String?,
    val query: Map<String, List<String>>,
    /** Names lower-cased. */
    val headers: Map<String, List<String>>,
    val contentType: String?,
    /** Exact bytes received. */
    val body: ByteArray,
    /** TrustedProxyIpResolver result (never the raw X-Forwarded-For). */
    val remoteIp: String,
    val receivedAt: Long
) {
    /**
     * multipart/form-data is not buffered by the platform: [body] is then empty and market fills the attributes
     * it read from the request here; [form] returns them as they are.
     */
    var formAttributes: Map<String, List<String>>? = null

    /** First value of a header, name compared case-insensitively. */
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
        ?: headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    fun queryParam(name: String): String? = query[name]?.firstOrNull()

    fun bodyAsString(charset: Charset = Charsets.UTF_8): String = String(body, charset)

    fun bodyAsJson(): JsonObject = JsonObject(bodyAsString())

    /**
     * Decodes `application/x-www-form-urlencoded` from the raw body; values are exactly what was sent (percent
     * decoded, `+` as space, never trimmed). Repeated names keep their order.
     */
    fun form(charset: Charset = Charsets.UTF_8): Map<String, List<String>> {
        formAttributes?.let { return it }
        val text = bodyAsString(charset)
        if (text.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, MutableList<String>>()
        for (pair in text.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val name = URLDecoder.decode(if (eq < 0) pair else pair.substring(0, eq), charset.name())
            val value = if (eq < 0) "" else URLDecoder.decode(pair.substring(eq + 1), charset.name())
            out.getOrPut(name) { ArrayList() }.add(value)
        }
        return out
    }

    fun formParam(name: String, charset: Charset = Charsets.UTF_8): String? = form(charset)[name]?.firstOrNull()
}

/** The literal HTTP answer a gateway expects. */
class HttpReply(val status: Int, val contentType: String?, val body: ByteArray) {
    init {
        require(status in 100..599) { "Invalid HTTP status $status" }
    }

    var headers: Map<String, String> = emptyMap()

    /** True for [toOrderPage]: market fills the `Location` from the resolved attempt. */
    var orderPage: Boolean = false
        internal set

    companion object {
        fun text(body: String, status: Int = 200): HttpReply =
            HttpReply(status, "text/plain; charset=utf-8", body.toByteArray(Charsets.UTF_8))

        fun json(body: JsonObject, status: Int = 200): HttpReply =
            HttpReply(status, "application/json", body.encode().toByteArray(Charsets.UTF_8))

        fun empty(status: Int = 200): HttpReply = HttpReply(status, null, ByteArray(0))

        fun redirect(url: String, status: Int = 303): HttpReply {
            require(status in 300..399) { "Redirect status must be 3xx, was $status" }
            require(url.isNotEmpty() && url.none { it.isISOControl() }) { "Redirect URL must not be empty or contain control characters" }
            return HttpReply(status, null, ByteArray(0)).also { it.headers = mapOf("Location" to url) }
        }

        /**
         * RETURN: market fills the Location from the resolved attempt. WEBHOOK / NOTIFY: allowed when the result has
         * an event whose target resolves to an attempt (the first one is used), else 303 to `{base}/store`.
         */
        fun toOrderPage(): HttpReply = HttpReply(303, null, ByteArray(0)).also { it.orderPage = true }

        /** Ask the gateway to deliver again. */
        fun retryLater(status: Int = 503): HttpReply = HttpReply(status, null, ByteArray(0))
    }
}
