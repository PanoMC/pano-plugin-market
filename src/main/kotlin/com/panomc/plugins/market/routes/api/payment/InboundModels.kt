package com.panomc.plugins.market.routes.api.payment

import com.panomc.plugins.market.core.abuse.Redactor
import com.panomc.plugins.market.core.payment.ProviderMoneyPolicy
import com.panomc.plugins.market.spi.common.InboundKind
import com.panomc.plugins.market.spi.payment.PaymentContext
import com.panomc.plugins.market.spi.payment.PaymentProvider
import com.panomc.plugins.market.spi.payment.ReturnOutcome
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * One inbound HTTP request as the route read it from Vert.x (02 section 7.1): no Vert.x type, so the dispatcher (and its tests) run without
 * the host. [headers] have lower-cased names, [rawQuery] is the undecoded query string without `?`, [body] the exact bytes (empty for a
 * `multipart/form-data` request, whose attributes are in [formAttributes]).
 */
class InboundCall(
    val kind: InboundKind,
    val providerId: String,
    /** `"default"` or the path suffix of a webhook / notify channel. */
    val channel: String,
    /** The 160-bit token of `notify` / `return` routes; locates the attempt, proves nothing (11 section 4.3 IN-6). */
    val attemptToken: String?,
    val outcome: ReturnOutcome?,
    val step: String?,
    val method: String,
    /** The request path (token included): only a masked form is ever stored. */
    val path: String,
    val rawQuery: String?,
    val query: Map<String, List<String>>,
    val headers: Map<String, List<String>>,
    val contentType: String?,
    val body: ByteArray,
    val formAttributes: Map<String, List<String>>?,
    val remoteIp: String,
    val receivedAt: Long
) {
    init {
        require(kind != InboundKind.WEBHOOK || attemptToken == null) { "a webhook carries no attempt token" }
        require(kind == InboundKind.WEBHOOK || attemptToken != null) { "a ${kind.name.lowercase()} request needs the attempt token" }
        val isReturn = kind == InboundKind.RETURN

        require(isReturn == (outcome != null)) { "only a return request has an outcome" }
        require((outcome == ReturnOutcome.STEP) == (step != null)) { "a step name goes with the STEP outcome only" }
    }
}

/**
 * What the rest of a request needs from a provider once it is resolved (02 section 7.1: a registered, compatible provider receives every
 * request whatever its method row says): the provider itself, what its capabilities say about money, the redactor that knows its secret values
 * and the context factory (the test mode of an attempt is stable for its life, so a `NOTIFY` / `RETURN` passes the attempt's own).
 */
sealed interface ProviderAccess {
    class Ready(
        val provider: PaymentProvider,
        val policy: ProviderMoneyPolicy,
        val redactor: Redactor,
        private val contextFor: (testMode: Boolean?) -> PaymentContext
    ) : ProviderAccess {
        fun context(testMode: Boolean? = null): PaymentContext = contextFor(testMode)
    }

    /** No such provider: 404, nothing stored. */
    data object Unknown : ProviderAccess

    /** A method row or an attempt of this provider exists but the provider is not registered (02 section 11): `DEFERRED` and 503, 303 for a return. */
    data object Unavailable : ProviderAccess
}

/** The raw request as it is kept in `market_payment_event` (`headers`, `body`) while the row can still be replayed (01 section 6.3, 11 section 4.3 IN-5). */
class StoredRequest(
    val headers: Map<String, List<String>>,
    val rawQuery: String?,
    val form: Map<String, List<String>>?,
    val body: ByteArray
)

/**
 * The stored form of a request. `headers` is a JSON object `{"<name>": ["<value>", ...]}` of the verbatim request headers; what a replay needs
 * beyond them travels under three reserved keys that start with `:` (no HTTP header name can): `:query` (the raw query string, which `url` only
 * keeps masked), `:form` (the attributes of a multipart request) and `:body` (`"base64"` when the body is not valid UTF-8, else absent). The
 * reserved keys are dropped when the row settles ([settledHeaders]).
 */
object StoredRequestCodec {
    const val QUERY_KEY = ":query"
    const val FORM_KEY = ":form"
    const val BODY_KEY = ":body"
    const val BASE64 = "base64"

    fun headersJson(call: InboundCall): String {
        val json = JsonObject()

        for ((name, values) in call.headers) json.put(name, JsonArray(values))

        call.rawQuery?.takeIf { it.isNotEmpty() }?.let { json.put(QUERY_KEY, it) }
        call.formAttributes?.let { form -> json.put(FORM_KEY, JsonObject().also { o -> form.forEach { (k, v) -> o.put(k, JsonArray(v)) } }) }

        if (utf8(call.body) == null) json.put(BODY_KEY, BASE64)

        return json.encode()
    }

    /** The body column: the text when the bytes are valid UTF-8, else Base64 (and `:body` in the headers says so). */
    fun bodyText(body: ByteArray): String = utf8(body) ?: Base64.getEncoder().encodeToString(body)

    fun parse(headers: String?, body: String?): StoredRequest {
        val json = headers?.let { runCatching { JsonObject(it) }.getOrNull() } ?: JsonObject()
        val plain = LinkedHashMap<String, List<String>>()
        var form: Map<String, List<String>>? = null

        for (name in json.fieldNames()) {
            when (name) {
                QUERY_KEY, BODY_KEY -> Unit
                FORM_KEY -> form = json.getJsonObject(FORM_KEY)?.let { o -> o.fieldNames().associateWith { k -> strings(o.getValue(k)) } }
                else -> plain[name] = strings(json.getValue(name))
            }
        }

        val bytes = when {
            body == null -> ByteArray(0)
            json.getString(BODY_KEY) == BASE64 -> runCatching { Base64.getDecoder().decode(body) }.getOrDefault(ByteArray(0))
            else -> body.toByteArray(Charsets.UTF_8)
        }

        return StoredRequest(plain, json.getString(QUERY_KEY), form, bytes)
    }

    /** The headers of a settled row: the sensitive ones and every secret value removed, the reserved keys gone. */
    fun settledHeaders(headers: String?, redactor: Redactor): String? {
        val json = headers?.let { runCatching { JsonObject(it) }.getOrNull() } ?: return null
        val plain = LinkedHashMap<String, List<String>>()

        for (name in json.fieldNames()) if (!name.startsWith(":")) plain[name] = strings(json.getValue(name))

        val out = JsonObject()

        for ((name, values) in redactor.redactHeaders(plain)) out.put(name, JsonArray(values))

        return out.encode()
    }

    /** The body of a settled row: secrets, card numbers and the sensitive keys removed first, then cut to [maxBytes] of UTF-8. */
    fun settledBody(body: String?, headers: String?, redactor: Redactor, maxBytes: Int): String? {
        if (body == null) return null

        val base64 = headers?.let { runCatching { JsonObject(it).getString(BODY_KEY) }.getOrNull() } == BASE64
        val text = if (base64) "[binary ${runCatching { Base64.getDecoder().decode(body).size }.getOrDefault(0)} bytes]" else redactor.redact(body)

        return truncateUtf8(text, maxBytes)
    }

    /** At most [maxBytes] of [text] as UTF-8, never inside a character. */
    fun truncateUtf8(text: String, maxBytes: Int): String {
        val bytes = text.toByteArray(Charsets.UTF_8)

        if (bytes.size <= maxBytes) return text

        var end = maxBytes

        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--

        return String(bytes, 0, end, Charsets.UTF_8)
    }

    private fun strings(value: Any?): List<String> = when (value) {
        is JsonArray -> value.map { it.toString() }
        null -> emptyList()
        else -> listOf(value.toString())
    }

    private fun utf8(body: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString()
    } catch (e: CharacterCodingException) {
        null
    }
}
