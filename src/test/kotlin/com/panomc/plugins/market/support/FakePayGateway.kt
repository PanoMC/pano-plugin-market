package com.panomc.plugins.market.support

import com.panomc.plugins.market.spi.common.InboundKind
import com.panomc.plugins.market.spi.common.InboundRequest
import com.panomc.plugins.market.spi.testkit.FakeGateway
import com.panomc.plugins.market.spi.testkit.Recorded
import com.panomc.plugins.market.spi.testkit.Reply
import com.panomc.plugins.market.spi.testkit.TestContexts
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The gateway simulator of 17 section 6.3 / 6.4, built on [FakeGateway]: it speaks the wire protocol of the fake
 * payment provider (`/v1/payments`, `/v1/refunds`, `/v1/charges`, `/v1/subscriptions`, `/v1/ping`), keeps the payments
 * it created, can be scripted (status, failures, hangs, refund outcome) and can sign and send webhooks to a target
 * URL. It is also the store-webhook sink (`POST /hooks/<name>`).
 *
 * The webhook signature is computed here independently of the provider's `FakeSignature` so a mistake on one side
 * cannot hide on the other (`FakeSignatureTest` pins both to one vector).
 */
class FakePayGateway(
    val secret: String = DEFAULT_SECRET,
    vertx: Vertx? = null,
    port: Int = 0,
    /** Where [sendWebhook] posts (`MARKET_E2E_URL/api/market/payments/<providerId>/webhook`); null = not configured. */
    private val webhookTarget: () -> String? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    /** Inserted into generated event ids (`evt_<prefix>_0001`) so two JVMs against one persistent instance database never reuse an event key (the E2E harness passes a per-run tag). */
    private val eventPrefix: String = ""
) : AutoCloseable {
    /** The operations that can be scripted and inspected. */
    enum class Op(val method: String) { CREATE("POST"), QUERY("GET"), CANCEL("POST"), REFUND("POST"), QUERY_REFUND("GET"), CHARGE("POST"), CANCEL_SUBSCRIPTION("POST"), PING("GET") }

    enum class Signature { VALID, INVALID, MISSING, STALE }

    enum class RefundMode { SUCCEEDED, PENDING, FAILED }

    /** One payment the gateway created. */
    class Payment(
        val id: String,
        val session: String,
        val reference: String,
        val amount: BigDecimal,
        val currency: String,
        val notifyUrl: String?,
        val returnSuccess: String?,
        val returnCancel: String?,
        val body: JsonObject
    ) {
        @Volatile var status: String = "pending"
        @Volatile var paidAmount: BigDecimal? = null
        @Volatile var refunded: BigDecimal = BigDecimal.ZERO
    }

    class RefundRecord(val id: String, val paymentId: String, val amount: BigDecimal, val status: String)

    private val gateway: FakeGateway = FakeGateway.start(vertx, port)

    val baseUrl: String get() = gateway.baseUrl
    val port: Int get() = gateway.port

    /** Payments by order reference. */
    val payments = ConcurrentHashMap<String, Payment>()
    val refunds = ConcurrentHashMap<String, RefundRecord>()
    val cancelledSubscriptions = ConcurrentHashMap<String, Boolean>()

    @Volatile var refundMode: RefundMode = RefundMode.SUCCEEDED

    /** Status the next charges answer with (`paid`, `pending`, `failed`). */
    @Volatile var chargeStatus: String = "paid"

    private val paymentSeq = AtomicInteger(0)
    private val refundSeq = AtomicInteger(0)
    private val chargeSeq = AtomicInteger(0)
    private val eventSeq = AtomicInteger(0)
    private val byIdempotency = ConcurrentHashMap<String, Reply>()
    private val scripted = ConcurrentHashMap<Op, ConcurrentLinkedQueue<Pair<Int, String?>>>()
    private val hookScripts = ConcurrentHashMap<String, ConcurrentLinkedQueue<Int>>()

    init {
        gateway.on("GET", "/v1/ping") { guard(it, Op.PING) { Reply.json("""{"ok":true}""") } }
        gateway.on("POST", "/v1/payments") { guard(it, Op.CREATE) { r -> create(r) } }
        gateway.on("GET", "/v1/payments/*") { guard(it, Op.QUERY) { r -> query(r.path.removePrefix("/v1/payments/")) } }
        gateway.on("POST", "/v1/payments/*") { guard(it, Op.CANCEL) { r -> cancel(r.path.removePrefix("/v1/payments/").removeSuffix("/cancel")) } }
        gateway.on("POST", "/v1/refunds") { guard(it, Op.REFUND) { r -> refund(r) } }
        gateway.on("GET", "/v1/refunds/*") { guard(it, Op.QUERY_REFUND) { r -> queryRefund(r.path.removePrefix("/v1/refunds/")) } }
        gateway.on("POST", "/v1/charges") { guard(it, Op.CHARGE) { r -> charge(r) } }
        gateway.on("POST", "/v1/subscriptions/*") {
            guard(it, Op.CANCEL_SUBSCRIPTION) { r -> cancelSubscription(r.path.removePrefix("/v1/subscriptions/").removeSuffix("/cancel"), r) }
        }
        gateway.on("POST", "/hooks/*") { r ->
            val name = r.path.removePrefix("/hooks/")
            val status = hookScripts[name]?.poll() ?: 200
            Reply.text(if (status in 200..299) "OK" else "scripted", status)
        }
    }

    // ---- control API (17 section 6.4) ------------------------------------------------------------------------------

    /** Sets the status of a payment (`pending`, `paid`, `failed`, `expired`, `review`); `paid` records [paidAmount] (default the requested amount). */
    fun setStatus(reference: String, status: String, paidAmount: BigDecimal? = null) {
        val p = payments[reference] ?: throw IllegalArgumentException("no payment $reference")
        p.status = status
        p.paidAmount = if (status == "paid" || status == "review") (paidAmount ?: p.amount) else p.paidAmount
    }

    fun nextEventId(): String = if (eventPrefix.isEmpty()) "evt_%04d".format(eventSeq.incrementAndGet()) else "evt_${eventPrefix}_%04d".format(eventSeq.incrementAndGet())

    /** Marks the payment paid and delivers one signed `payment.succeeded` webhook. */
    fun pay(reference: String, amount: BigDecimal? = null): List<HttpResponse<String>> {
        val p = payments[reference] ?: throw IllegalArgumentException("no payment $reference")
        val paid = amount ?: p.amount
        setStatus(reference, "paid", paid)
        return sendWebhook("payment.succeeded", JsonObject().put("reference", reference).put("amount", paid.toPlainString()).put("currency", p.currency))
    }

    /** Scripted failure: the next request of [op] answers [status] with [message] (default "scripted <status>"). One-shot, queued. */
    fun failNext(op: Op, status: Int, message: String? = null) {
        scripted.getOrPut(op) { ConcurrentLinkedQueue() }.add(status to message)
    }

    /** Requests of [op] (or of one key of it) are accepted and recorded but never answered, until [release]. */
    fun hang(op: Op, key: String? = null) {
        gateway.hang(pathOf(op, key))
    }

    fun release(op: Op, key: String? = null) {
        gateway.release(pathOf(op, key))
    }

    /** Requests the gateway received for [op], oldest first. */
    fun requests(op: Op): List<Recorded> = gateway.requests.filter { opOf(it) == op }

    /** Requests the store-webhook sink received for [name]. */
    fun hooks(name: String): List<Recorded> = gateway.requestsTo("/hooks/$name")

    /** Scripted store-webhook answers for [name], in order; afterwards 200. */
    fun hookStatus(name: String, vararg statuses: Int) {
        hookScripts.getOrPut(name) { ConcurrentLinkedQueue() }.addAll(statuses.toList())
    }

    fun clearRequests() = gateway.clearRequests()

    // ---- webhooks --------------------------------------------------------------------------------------------------

    /** The exact bytes of an event body. */
    fun eventBody(type: String, data: JsonObject, id: String): ByteArray =
        JsonObject().put("id", id).put("type", type).put("data", data).encode().toByteArray(Charsets.UTF_8)

    /** The `X-Fake-Signature` value for [body] under [signature] (null = the header is left out). */
    fun signatureHeader(body: ByteArray, signature: Signature, nowMs: Long = clock()): String? {
        val now = nowMs / 1000L
        return when (signature) {
            Signature.VALID -> "t=$now,v1=${hmac(secret, now, body)}"
            Signature.INVALID -> "t=$now,v1=${hmac(secret + "-wrong", now, body)}"
            Signature.MISSING -> null
            Signature.STALE -> (now - 3600).let { "t=$it,v1=${hmac(secret, it, body)}" }
        }
    }

    /**
     * The event as an [InboundRequest] for feeding a provider directly (no HTTP), received at [nowMs]. Use
     * [TestContexts.START_MS] to match a test context clock.
     */
    fun inbound(
        type: String,
        data: JsonObject,
        id: String = nextEventId(),
        signature: Signature = Signature.VALID,
        nowMs: Long = TestContexts.START_MS,
        rawBody: ByteArray? = null
    ): InboundRequest {
        val body = rawBody ?: eventBody(type, data, id)
        val headers = HashMap<String, List<String>>()
        headers["content-type"] = listOf("application/json")
        signatureHeader(body, signature, nowMs)?.let { headers[HEADER] = listOf(it) }
        return InboundRequest(
            kind = InboundKind.WEBHOOK, channel = "default", method = "POST", rawQuery = null, query = emptyMap(), headers = headers,
            contentType = "application/json", body = body, remoteIp = "203.0.113.9", receivedAt = nowMs
        )
    }

    /**
     * Posts a webhook to the configured target [copies] times (same event id), one after the other or all at once
     * ([concurrent]); returns every answer in send order.
     */
    fun sendWebhook(
        type: String,
        data: JsonObject,
        id: String = nextEventId(),
        copies: Int = 1,
        concurrent: Boolean = false,
        signature: Signature = Signature.VALID
    ): List<HttpResponse<String>> {
        val target = webhookTarget() ?: throw IllegalStateException("FakePayGateway has no webhook target configured")
        val body = eventBody(type, data, id)
        val client = HttpClient.newHttpClient()
        fun post(): HttpResponse<String> {
            val builder = HttpRequest.newBuilder(URI.create(target)).header("Content-Type", "application/json")
            signatureHeader(body, signature)?.let { builder.header("X-Fake-Signature", it) }
            return client.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString())
        }
        if (!concurrent) return (1..copies).map { post() }
        val pool = Executors.newFixedThreadPool(copies)
        try {
            val gate = CountDownLatch(1)
            val futures = (1..copies).map { pool.submit<HttpResponse<String>> { gate.await(); post() } }
            gate.countDown()
            return futures.map { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    // ---- handlers --------------------------------------------------------------------------------------------------

    private fun opOf(r: Recorded): Op? = when {
        r.method == "GET" && r.path == "/v1/ping" -> Op.PING
        r.method == "POST" && r.path == "/v1/payments" -> Op.CREATE
        r.method == "GET" && r.path.startsWith("/v1/payments/") -> Op.QUERY
        r.method == "POST" && r.path.startsWith("/v1/payments/") -> Op.CANCEL
        r.method == "POST" && r.path == "/v1/refunds" -> Op.REFUND
        r.method == "GET" && r.path.startsWith("/v1/refunds/") -> Op.QUERY_REFUND
        r.method == "POST" && r.path == "/v1/charges" -> Op.CHARGE
        r.method == "POST" && r.path.startsWith("/v1/subscriptions/") -> Op.CANCEL_SUBSCRIPTION
        else -> null
    }

    private fun pathOf(op: Op, key: String?): String = when (op) {
        Op.PING -> "/v1/ping"
        Op.CREATE -> "/v1/payments"
        Op.QUERY -> "/v1/payments/${requireNotNull(key) { "QUERY needs a reference" }}"
        Op.CANCEL -> "/v1/payments/${requireNotNull(key) { "CANCEL needs a reference" }}/cancel"
        Op.REFUND -> "/v1/refunds"
        Op.QUERY_REFUND -> "/v1/refunds/${requireNotNull(key) { "QUERY_REFUND needs a refund id" }}"
        Op.CHARGE -> "/v1/charges"
        Op.CANCEL_SUBSCRIPTION -> "/v1/subscriptions/${requireNotNull(key) { "CANCEL_SUBSCRIPTION needs a subscription id" }}/cancel"
    }

    private fun guard(r: Recorded, op: Op, block: (Recorded) -> Reply): Reply {
        if (r.header("Authorization") != "Bearer $secret") return Reply.json("""{"message":"bad token"}""", 401)
        scripted[op]?.poll()?.let { (status, message) ->
            return Reply.json(JsonObject().put("message", message ?: "scripted $status").encode(), status)
        }
        return block(r)
    }

    private fun json(r: Recorded): JsonObject = try {
        JsonObject(r.bodyText())
    } catch (e: Exception) {
        JsonObject()
    }

    private fun idempotent(r: Recorded, make: () -> Reply): Reply {
        val key = r.header("Idempotency-Key") ?: return make()
        return byIdempotency.computeIfAbsent("${r.path}|$key") { make() }
    }

    private fun create(r: Recorded): Reply = idempotent(r) {
        val body = json(r)
        val n = paymentSeq.incrementAndGet()
        val reference = body.getString("reference")
        val payment = Payment(
            id = "pay_$n", session = "sess_$n", reference = reference, amount = BigDecimal(body.getString("amount")),
            currency = body.getString("currency"), notifyUrl = body.getString("notifyUrl"), returnSuccess = body.getString("returnSuccess"),
            returnCancel = body.getString("returnCancel"), body = body
        )
        payments[reference] = payment
        Reply.json(JsonObject().put("id", payment.id).put("payUrl", "$baseUrl/pay/$reference").put("session", payment.session).encode(), 201)
    }

    private fun query(reference: String): Reply {
        val p = payments[reference] ?: return Reply.json("""{"message":"unknown payment"}""", 404)
        val json = JsonObject().put("id", p.id).put("status", p.status).put("currency", p.currency)
        p.paidAmount?.let { json.put("paidAmount", it.toPlainString()) }
        return Reply.json(json.encode())
    }

    private fun cancel(reference: String): Reply {
        val p = payments[reference] ?: return Reply.json("""{"message":"unknown payment"}""", 404)
        if (p.status != "pending") return Reply.json("""{"message":"not cancellable"}""", 409)
        p.status = "expired"
        return Reply.json("{}")
    }

    private fun refund(r: Recorded): Reply = idempotent(r) {
        val body = json(r)
        val payment = payments.values.firstOrNull { it.id == body.getString("paymentId") }
            ?: return@idempotent Reply.json("""{"message":"unknown payment"}""", 404)
        val amount = BigDecimal(body.getString("amount"))
        val captured = (payment.paidAmount ?: BigDecimal.ZERO) - payment.refunded
        if (amount > captured) return@idempotent Reply.json("""{"message":"amount exceeds captured"}""", 422)
        val id = "ref_${refundSeq.incrementAndGet()}"
        val status = when (refundMode) {
            RefundMode.SUCCEEDED -> "succeeded"
            RefundMode.PENDING -> "pending"
            RefundMode.FAILED -> "failed"
        }
        refunds[id] = RefundRecord(id, payment.id, amount, status)
        if (status != "failed") payment.refunded += amount
        val json = JsonObject().put("id", id).put("status", status)
        if (status == "failed") json.put("code", "refund_declined").put("message", "The refund was declined")
        Reply.json(json.encode(), 201)
    }

    private fun queryRefund(id: String): Reply {
        val refund = refunds[id] ?: return Reply.json("""{"message":"unknown refund"}""", 404)
        return Reply.json(JsonObject().put("id", refund.id).put("status", refund.status).encode())
    }

    private fun charge(r: Recorded): Reply = idempotent(r) {
        val id = "chg_${chargeSeq.incrementAndGet()}"
        Reply.json(JsonObject().put("id", id).put("status", chargeStatus).encode(), 201)
    }

    private fun cancelSubscription(id: String, r: Recorded): Reply {
        cancelledSubscriptions[id] = true
        val json = JsonObject()
        if (json(r).getBoolean("atPeriodEnd", false)) json.put("endsAt", clock() + 30L * 86_400_000L)
        return Reply.json(json.encode())
    }

    override fun close() {
        gateway.close()
    }

    companion object {
        const val HEADER = "x-fake-signature"
        const val DEFAULT_SECRET = "fake_secret_0123456789"

        fun hmac(secret: String, timestampSeconds: Long, body: ByteArray): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            mac.update(("$timestampSeconds.").toByteArray(Charsets.UTF_8))
            return mac.doFinal(body).joinToString("") { "%02x".format(it) }
        }
    }
}
