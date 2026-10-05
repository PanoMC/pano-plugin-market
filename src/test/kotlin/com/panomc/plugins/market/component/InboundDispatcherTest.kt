package com.panomc.plugins.market.component

import com.panomc.plugins.market.core.abuse.Redactor
import com.panomc.plugins.market.core.payment.PaymentAttemptEvent
import com.panomc.plugins.market.core.payment.ProviderMoneyPolicy
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.MarketPaymentEvent
import com.panomc.plugins.market.db.model.PaymentEventDirection
import com.panomc.plugins.market.db.model.PaymentEventStatus
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.job.InboundEventRetryJob
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.routes.api.payment.AttemptLocks
import com.panomc.plugins.market.routes.api.payment.AttemptPageResult
import com.panomc.plugins.market.routes.api.payment.AttemptPageService
import com.panomc.plugins.market.routes.api.payment.AttemptPages
import com.panomc.plugins.market.routes.api.payment.InboundAttempts
import com.panomc.plugins.market.routes.api.payment.InboundCall
import com.panomc.plugins.market.routes.api.payment.InboundDispatcher
import com.panomc.plugins.market.routes.api.payment.InboundEventKey
import com.panomc.plugins.market.routes.api.payment.InboundEventStore
import com.panomc.plugins.market.routes.api.payment.InboundProviders
import com.panomc.plugins.market.routes.api.payment.PaymentEventApplier
import com.panomc.plugins.market.routes.api.payment.PaymentEventSink
import com.panomc.plugins.market.routes.api.payment.ProviderAccess
import com.panomc.plugins.market.routes.api.payment.ReplayResult
import com.panomc.plugins.market.routes.api.payment.Settlement
import com.panomc.plugins.market.routes.api.payment.attemptViewOf
import com.panomc.plugins.market.runtime.MarketRuntime
import com.panomc.plugins.market.service.AppliedEvent
import com.panomc.plugins.market.service.AttemptFacts
import com.panomc.plugins.market.spi.common.HttpReply
import com.panomc.plugins.market.spi.common.InboundKind
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.payment.InboundResult
import com.panomc.plugins.market.spi.payment.PaymentAttemptView
import com.panomc.plugins.market.spi.payment.PaymentContext
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentInboundRequest
import com.panomc.plugins.market.spi.payment.PaymentProvider
import com.panomc.plugins.market.spi.payment.PaymentTarget
import com.panomc.plugins.market.spi.payment.RefundState
import com.panomc.plugins.market.spi.payment.ReturnOutcome
import com.panomc.plugins.market.spi.payment.ReviewReason
import com.panomc.plugins.market.spi.testkit.TestContexts
import com.panomc.plugins.market.support.FakeClock
import com.panomc.plugins.market.support.FakePaymentProvider
import com.panomc.plugins.market.support.SeqIds
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.Vertx
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** `market_payment_event` held in memory with the semantics the dispatcher relies on: the unique key `uq_event`, conditional updates, `SUPERSEDED` never revived. */
internal class InMemoryEventStore(private val clock: FakeClock) : InboundEventStore {
    private val lock = Any()
    private val rows = LinkedHashMap<Long, MarketPaymentEvent>()
    private var next = 0L

    @Volatile var failInsert: Throwable? = null
    val touched = CopyOnWriteArrayList<String>()

    val all: List<MarketPaymentEvent> get() = synchronized(lock) { rows.values.toList() }

    fun put(event: MarketPaymentEvent): MarketPaymentEvent = synchronized(lock) {
        val id = ++next

        event.with(id = id).also { rows[id] = it }
    }

    private fun MarketPaymentEvent.with(
        id: Long = this.id, eventKey: String = this.eventKey, status: PaymentEventStatus = this.status, attempts: Int = this.attempts, duplicateCount: Int = this.duplicateCount,
        nextAttemptAt: Long? = this.nextAttemptAt, headers: String? = this.headers, body: String? = this.body, verified: Boolean? = this.verified, eventTypes: String? = this.eventTypes,
        paymentId: Long? = this.paymentId, orderId: Long? = this.orderId, responseStatus: Int? = this.responseStatus, error: String? = this.error, durationMs: Int? = this.durationMs,
        processedAt: Long? = this.processedAt, updatedAt: Long = this.updatedAt
    ) = MarketPaymentEvent(
        id, providerId, direction, channel, subChannel, eventKey, requestHash, paymentId, orderId, refundId, subscriptionId, method, url, headers, body, remoteIp, verified, eventTypes, status,
        attempts, duplicateCount, nextAttemptAt, responseStatus, error, durationMs, processedAt, createdAt, updatedAt
    )

    override suspend fun insert(event: MarketPaymentEvent): Long {
        failInsert?.let { throw it }

        return synchronized(lock) {
            require(rows.values.none { it.providerId == event.providerId && it.direction == event.direction && it.eventKey == event.eventKey }) { "duplicate key" }

            put(event).id
        }
    }

    override suspend fun get(id: Long): MarketPaymentEvent? = synchronized(lock) { rows[id] }

    override suspend fun byKey(providerId: String, key: String): MarketPaymentEvent? = synchronized(lock) {
        rows.values.firstOrNull { it.providerId == providerId && it.direction == PaymentEventDirection.IN && it.eventKey == key }
    }

    override suspend fun claimKey(id: Long, key: String, now: Long): Boolean = synchronized(lock) {
        val row = rows[id] ?: return@synchronized true

        if (rows.values.any { it.id != id && it.providerId == row.providerId && it.direction == row.direction && it.eventKey == key }) return@synchronized false

        rows[id] = row.with(eventKey = key, updatedAt = now)

        true
    }

    override suspend fun bumpDuplicates(id: Long, now: Long) {
        synchronized(lock) { rows[id]?.let { rows[id] = it.with(duplicateCount = it.duplicateCount + 1, updatedAt = now) } }
    }

    override suspend fun supersede(old: MarketPaymentEvent, headers: String?, body: String?, now: Long): Boolean = synchronized(lock) {
        val row = rows[old.id] ?: return@synchronized false

        if (row.status != old.status) return@synchronized false

        rows[old.id] = row.with(status = PaymentEventStatus.SUPERSEDED, eventKey = InboundEventKey.superseded(row.eventKey, row.id), headers = headers, body = body, updatedAt = now)

        true
    }

    override suspend fun settle(id: Long, settlement: Settlement): Boolean = synchronized(lock) {
        val row = rows[id] ?: return@synchronized false

        if (row.status == PaymentEventStatus.SUPERSEDED) return@synchronized false

        val s = settlement
        val raw = s.raw

        rows[id] = row.with(
            status = s.status, verified = s.verified ?: row.verified, eventTypes = s.eventTypes ?: row.eventTypes, paymentId = s.paymentId ?: row.paymentId, orderId = s.orderId ?: row.orderId,
            responseStatus = s.responseStatus, error = s.error, durationMs = s.durationMs, processedAt = s.processedAt, nextAttemptAt = s.nextAttemptAt, updatedAt = s.now,
            headers = if (raw != null) raw.headers else row.headers, body = if (raw != null) raw.body else row.body
        )

        true
    }

    override suspend fun claimRetry(row: MarketPaymentEvent, now: Long, leaseMs: Long): Boolean = synchronized(lock) {
        val current = rows[row.id] ?: return@synchronized false

        if (current.attempts != row.attempts || current.status != row.status) return@synchronized false

        rows[row.id] = current.with(attempts = current.attempts + 1, nextAttemptAt = now + leaseMs, updatedAt = now)

        true
    }

    override suspend fun due(now: Long, staleBefore: Long, maxAttempts: Int, limit: Int): List<MarketPaymentEvent> = synchronized(lock) {
        rows.values.filter {
            it.direction == PaymentEventDirection.IN && it.attempts < maxAttempts && (
                (it.status == PaymentEventStatus.FAILED && it.nextAttemptAt != null && it.nextAttemptAt <= now) ||
                    (it.status == PaymentEventStatus.RECEIVED && it.createdAt < staleBefore && (it.nextAttemptAt == null || it.nextAttemptAt <= now))
                )
        }.take(limit)
    }

    override suspend fun exhaust(now: Long, staleBefore: Long, maxAttempts: Int): Int = synchronized(lock) {
        val hit = rows.values.filter { it.direction == PaymentEventDirection.IN && it.status == PaymentEventStatus.RECEIVED && it.attempts >= maxAttempts && it.createdAt < staleBefore }

        hit.forEach { rows[it.id] = it.with(status = PaymentEventStatus.FAILED, nextAttemptAt = null, error = it.error ?: "retry budget exhausted", updatedAt = now) }

        hit.size
    }

    override suspend fun touchLastInbound(providerId: String, now: Long) {
        touched += providerId
    }
}

/** The attempts and the order side as a fake: a status per attempt, one counted transition when a payment is the first to succeed. */
internal class FakeAttempts : InboundAttempts {
    class Applied(val attemptId: Long, val event: PaymentAttemptEvent, val facts: AttemptFacts, val policy: ProviderMoneyPolicy)

    val cipher = SecretCipher(ByteArray(32) { (it + 3).toByte() })
    private val byId = LinkedHashMap<Long, MarketPayment>()
    private val status = HashMap<Long, PaymentStatus>()
    val publicIds = HashMap<Long, String>()
    val applied = CopyOnWriteArrayList<Applied>()
    val attached = CopyOnWriteArrayList<Pair<Long, PaymentEvent>>()
    val transitions = AtomicInteger()

    @Volatile var failApply: Throwable? = null
    @Volatile var failToken: Throwable? = null
    @Volatile var applyGate: CompletableDeferred<Unit>? = null
    val applyStarted = CompletableDeferred<Unit>()

    fun add(id: Long, providerId: String = "fake", token: String = "%040x".format(id), reference: String = "REF%017d".format(id), orderId: Long = id, testMode: Boolean = false): MarketPayment {
        val attempt = MarketPayment(
            id = id, orderId = orderId, providerId = providerId, status = PaymentStatus.PENDING, reference = reference, token = token, amount = 1000, currency = "EUR", testMode = testMode
        )

        byId[id] = attempt
        status[id] = PaymentStatus.PENDING
        publicIds[orderId] = "ORDER%015d".format(orderId)

        return attempt
    }

    /** An attempt as it is (status, stored start, test mode). */
    fun addRaw(attempt: MarketPayment) {
        byId[attempt.id] = attempt
        status[attempt.id] = attempt.status
        publicIds[attempt.orderId] = "ORDER%015d".format(attempt.orderId)
    }

    fun statusOf(id: Long): PaymentStatus = status.getValue(id)

    override suspend fun byToken(token: String): MarketPayment? {
        failToken?.let { throw it }

        return byId.values.firstOrNull { it.token == token }
    }

    override suspend fun byId(id: Long): MarketPayment? = byId[id]

    override suspend fun publicIdOf(orderId: Long): String? = publicIds[orderId]

    override suspend fun resolve(providerId: String, target: PaymentTarget): MarketPayment? {
        val found = when (target) {
            is PaymentTarget.Attempt -> byId[target.attemptId]
            is PaymentTarget.Reference -> byId.values.firstOrNull { it.reference == target.reference }
            is PaymentTarget.GatewayTransaction -> byId.values.firstOrNull { it.gatewayTransactionId == target.gatewayTransactionId }
            is PaymentTarget.GatewayRef -> null
            is PaymentTarget.Subscription -> null
        }

        return found?.takeIf { it.providerId == providerId }
    }

    override fun view(attempt: MarketPayment, publicId: String): PaymentAttemptView = attemptViewOf(attempt, publicId, cipher)

    override fun facts(event: PaymentEvent): AttemptFacts = AttemptFacts.of(event, cipher)

    override suspend fun apply(attempt: MarketPayment, event: PaymentAttemptEvent, facts: AttemptFacts, policy: ProviderMoneyPolicy): AppliedEvent {
        applyStarted.complete(Unit)

        // the first call of a test can be held open while a second request arrives
        applyGate?.let { gate -> applyGate = null; gate.await() }
        failApply?.let { throw it }

        val first = synchronized(this) {
            applied += Applied(attempt.id, event, facts, policy)

            when {
                event is PaymentAttemptEvent.Succeeded && status[attempt.id] != PaymentStatus.SUCCEEDED && status[attempt.id] != PaymentStatus.REVIEW -> {
                    status[attempt.id] = PaymentStatus.SUCCEEDED
                    transitions.incrementAndGet()

                    true
                }

                event is PaymentAttemptEvent.NeedsReview && status[attempt.id] != PaymentStatus.REVIEW -> {
                    status[attempt.id] = PaymentStatus.REVIEW

                    true
                }

                else -> false
            }
        }

        return AppliedEvent(status.getValue(attempt.id), OrderStatus.PENDING, changed = first, duplicate = false)
    }

    override suspend fun attach(attempt: MarketPayment, event: PaymentEvent) {
        attached += attempt.id to event
    }
}

private class StaticProviders(var access: ProviderAccess) : InboundProviders {
    val calls = AtomicInteger()

    override suspend fun resolve(providerId: String, knownAttempt: Boolean): ProviderAccess {
        calls.incrementAndGet()

        return access
    }
}

/** The context of a provider with the real, reentrant attempt locks of market. */
private class LockingContext(private val delegate: PaymentContext, private val locks: AttemptLocks) : PaymentContext by delegate {
    override suspend fun <T> withAttemptLock(attemptId: Long, block: suspend () -> T): T = locks.with(attemptId, block)
}

/**
 * `InboundDispatcher` (02 section 7.3 steps 1 to 8) behind a fake event store and a fake order side (17 section 11.2 `InboundDispatcherTest`): the
 * raw body reaches the provider byte-identical and its reply is sent verbatim, an unauthentic request is `REJECTED`, a provider key on a `PROCESSED`
 * row is `DUPLICATE`, on a `FAILED` / `DEFERRED` / stale row it takes the key over, on an in-flight row both go through step 6, no key means the events
 * are always applied, a throwing provider or a failing step 6 makes the row `FAILED` and the answer 500, a return only ever redirects to the order page.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InboundDispatcherTest {
    private val vertx: Vertx = Vertx.vertx()

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    private lateinit var clock: FakeClock
    private lateinit var store: InMemoryEventStore
    private lateinit var attempts: FakeAttempts
    private lateinit var fake: FakePaymentProvider
    private lateinit var locks: AttemptLocks
    private lateinit var providers: StaticProviders
    private lateinit var dispatcher: InboundDispatcher
    private var state = MarketRuntime.State.READY
    private var sink: PaymentEventSink = PaymentEventSink.UNHANDLED

    private val secret = "whsec_test_0123456789abcdef"
    private val token = "%040x".format(1L)

    @BeforeEach
    fun setUp() {
        clock = FakeClock()
        store = InMemoryEventStore(clock)
        attempts = FakeAttempts()
        fake = FakePaymentProvider("fake")
        locks = AttemptLocks()
        state = MarketRuntime.State.READY
        sink = PaymentEventSink.UNHANDLED
        providers = StaticProviders(ready())
        attempts.add(1)
        dispatcher = build()
    }

    private fun ready(testMode: Boolean = false) = ProviderAccess.Ready(fake, ProviderMoneyPolicy(), Redactor(setOf(secret))) { tm ->
        LockingContext(TestContexts.payment("fake", TestContexts.settings(), vertx, tm ?: testMode), locks)
    }

    private fun build(timeoutMs: Long = 5_000L) = InboundDispatcher(
        store, attempts, providers, PaymentEventApplier(attempts) { event, attempt, ctx -> sink.apply(event, attempt, ctx) }, locks, clock, SeqIds(),
        { "https://shop.example" }, { state }, providerTimeoutMs = timeoutMs
    )

    private fun call(
        kind: InboundKind = InboundKind.WEBHOOK, body: ByteArray = "{}".toByteArray(), method: String = "POST", query: String? = null, outcome: ReturnOutcome? = null, step: String? = null,
        providerId: String = "fake", tokenOf: String? = if (kind == InboundKind.WEBHOOK) null else token, headers: Map<String, List<String>> = mapOf("content-type" to listOf("application/json")),
        form: Map<String, List<String>>? = null, channel: String = "default"
    ) = InboundCall(
        kind, providerId, channel, tokenOf, if (kind == InboundKind.RETURN) outcome ?: ReturnOutcome.SUCCESS else null, step, method,
        "/api/market/payments/$providerId/" + when (kind) { InboundKind.WEBHOOK -> "webhook"; InboundKind.NOTIFY -> "notify/$tokenOf"; InboundKind.RETURN -> "return/$tokenOf/success" }, query, emptyMap(),
        headers, headers["content-type"]?.firstOrNull(), body, form, "203.0.113.9", clock.now()
    )

    private fun succeeded(id: Long = 1, amount: Long = 1000, testMode: Boolean? = null) =
        PaymentEvent.Succeeded(PaymentTarget.Attempt(id), Money(amount, "EUR")).also { it.testMode = testMode }

    private fun ok(events: List<PaymentEvent> = emptyList(), key: String? = null, reply: HttpReply = HttpReply.text("OK")) = InboundResult.accepted(reply, events, key)

    private suspend fun rows() = store.all

    private suspend fun only(): MarketPaymentEvent = rows().single()

    // ======================================================================================================= steps 1 and 2

    @Test
    fun `the raw request reaches the provider byte-identical and is stored verbatim before the provider runs`(): Unit = runBlocking {
        val body = byteArrayOf(0x7b, 0x22, 0xc3.toByte(), 0xa9.toByte(), 0x22, 0x3a, 0x31, 0x7d) // {"é":1}
        val headers = mapOf("content-type" to listOf("application/json"), "x-signature" to listOf("t=1,v1=abc"))
        var seen: PaymentInboundRequest? = null
        var storedWhileRunning: MarketPaymentEvent? = null

        fake.onInbound = {
            seen = it
            storedWhileRunning = runBlocking { store.all.singleOrNull() }

            ok()
        }

        dispatcher.handle(call(body = body, headers = headers, query = "a=1&b=%C3%A9"))

        val request = seen!!.http

        assertTrue(body.contentEquals(request.body), "the provider gets the exact bytes")
        assertEquals("a=1&b=%C3%A9", request.rawQuery)
        assertEquals("POST", request.method)
        assertEquals(headers, request.headers)
        assertEquals("203.0.113.9", request.remoteIp)
        assertEquals(InboundKind.WEBHOOK, request.kind)
        assertNull(seen!!.attempt)

        // the raw request was on disk, RECEIVED, with the random key, before the provider code ran
        val before = storedWhileRunning!!

        assertEquals(PaymentEventStatus.RECEIVED, before.status)
        assertTrue(before.eventKey.startsWith("r:"), before.eventKey)
        assertEquals(1, before.attempts)
        assertEquals("fake", before.providerId)
        assertEquals(PaymentEventDirection.IN, before.direction)
        assertEquals("WEBHOOK", before.channel)
        assertNotNull(before.requestHash)
        assertEquals(InboundEventKey.requestHash(InboundKind.WEBHOOK, "default", null, "POST", "a=1&b=%C3%A9", body), before.requestHash)
        assertTrue(before.headers!!.contains("t=1,v1=abc"), "headers verbatim while the row can be replayed")
    }

    @Test
    fun `a NOTIFY and a RETURN carry the attempt, its view and the order, and the row names the payment and the order`(): Unit = runBlocking {
        val seen = ArrayList<PaymentInboundRequest>()

        fake.onInbound = { seen += it; ok() }

        dispatcher.handle(call(InboundKind.NOTIFY))
        dispatcher.handle(call(InboundKind.RETURN, outcome = ReturnOutcome.PENDING))

        assertEquals(2, seen.size)
        for (request in seen) {
            assertEquals(1L, request.attempt!!.id)
            assertEquals("ORDER000000000000001", request.attempt!!.orderPublicId)
            assertEquals(token, request.attempt!!.token)
        }
        assertNull(seen[0].outcome)
        assertEquals(ReturnOutcome.PENDING, seen[1].outcome)

        for (row in rows()) {
            assertEquals(1L, row.paymentId)
            assertEquals(1L, row.orderId)
            assertFalse(row.url!!.contains(token), "the token is masked in the stored address: ${row.url}")
        }
    }

    @Test
    fun `a multipart body reaches the provider through form() with the attributes the platform read`(): Unit = runBlocking {
        var form: Map<String, List<String>>? = null
        var bodySize = -1

        fake.onInbound = { form = it.http.form(); bodySize = it.http.body.size; ok() }

        dispatcher.handle(
            call(headers = mapOf("content-type" to listOf("multipart/form-data; boundary=x")), body = ByteArray(0), form = mapOf("status" to listOf("ok"), "ref" to listOf("a", "b")))
        )

        assertEquals(mapOf("status" to listOf("ok"), "ref" to listOf("a", "b")), form)
        assertEquals(0, bodySize)
    }

    @Test
    fun `a request that cannot be stored never reaches the provider and the gateway is told to deliver again`(): Unit = runBlocking {
        store.failInsert = IllegalStateException("database down")

        val reply = dispatcher.handle(call())

        assertEquals(500, reply.status)
        assertTrue(fake.calls.isEmpty(), "no provider code on a request that is not on disk")
        assertEquals(303, dispatcher.handle(call(InboundKind.RETURN)).status)
        assertTrue(fake.calls.isEmpty())
    }

    // ==================================================================================================== unknown, unavailable

    @Test
    fun `an unknown token, a token of another provider and an unknown provider answer 404 and store nothing`(): Unit = runBlocking {
        fake.onInbound = { ok() }

        assertEquals(404, dispatcher.handle(call(InboundKind.NOTIFY, tokenOf = "f".repeat(40))).status)
        assertEquals(404, dispatcher.handle(call(InboundKind.RETURN, tokenOf = "e".repeat(40))).status)

        attempts.add(2, providerId = "other")

        assertEquals(404, dispatcher.handle(call(InboundKind.NOTIFY, tokenOf = "%040x".format(2L))).status, "the token of an attempt of another provider")

        providers.access = ProviderAccess.Unknown

        assertEquals(404, dispatcher.handle(call()).status)
        assertTrue(rows().isEmpty(), "nothing is stored for any of them")
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `a provider that is not registered keeps the raw request as DEFERRED, answers 503 and a return goes to the order page`(): Unit = runBlocking {
        providers.access = ProviderAccess.Unavailable

        assertEquals(503, dispatcher.handle(call()).status)
        assertEquals(503, dispatcher.handle(call(InboundKind.NOTIFY)).status)

        val back = dispatcher.handle(call(InboundKind.RETURN, outcome = ReturnOutcome.SUCCESS))

        assertEquals(303, back.status)
        assertEquals("https://shop.example/store/order/ORDER000000000000001?return=success", back.headers["Location"])

        assertEquals(3, rows().size)
        assertTrue(rows().all { it.status == PaymentEventStatus.DEFERRED }, rows().map { it.status }.toString())
        assertTrue(rows().all { it.attempts == 0 && it.headers!!.contains("content-type") && it.body == "{}" }, "kept verbatim, no run counted")
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `a market that is stopped or starting touches nothing, a degraded one keeps the request as DEFERRED`(): Unit = runBlocking {
        fake.onInbound = { ok() }

        for (s in listOf(MarketRuntime.State.STOPPED, MarketRuntime.State.STARTING)) {
            state = s

            assertEquals(503, dispatcher.handle(call()).status, "$s webhook")
            assertEquals(503, dispatcher.handle(call(InboundKind.NOTIFY)).status, "$s notify")

            val back = dispatcher.handle(call(InboundKind.RETURN))

            assertEquals(303, back.status, "$s return")
            assertEquals("https://shop.example/store", back.headers["Location"])
        }

        assertTrue(rows().isEmpty() && providers.calls.get() == 0, "not a statement, not a registry lookup")

        state = MarketRuntime.State.DEGRADED

        assertEquals(503, dispatcher.handle(call()).status)
        assertEquals(PaymentEventStatus.DEFERRED, only().status)
        assertTrue(fake.calls.isEmpty(), "the provider is not run by a degraded market")
        assertEquals("https://shop.example/store/order/ORDER000000000000001?return=success", dispatcher.handle(call(InboundKind.RETURN)).headers["Location"])
    }

    // =================================================================================================== reply, step 4

    @Test
    fun `the provider's reply is sent verbatim, a header that sets state on the site is dropped`(): Unit = runBlocking {
        val body = "{\"received\":true}".toByteArray()

        fake.onInbound = { ok(reply = HttpReply(202, "application/json", body).also { r -> r.headers = mapOf("X-Gateway-Ack" to "1", "Set-Cookie" to "sid=1", "Content-Length" to "99") }) }

        val reply = dispatcher.handle(call())

        assertEquals(202, reply.status)
        assertEquals("application/json", reply.contentType)
        assertTrue(body.contentEquals(reply.body))
        assertEquals(mapOf("X-Gateway-Ack" to "1"), reply.headers)
    }

    @Test
    fun `an unverified request is REJECTED, its events are dropped, the provider's reply is sent and the row keeps its random key`(): Unit = runBlocking {
        val body = ("{\"sig\":\"$secret\"," + "x".repeat(4000) + "}").toByteArray()

        fake.onInbound = { InboundResult.rejected(HttpReply.text("bad signature", 400), "INVALID_SIGNATURE").also { r -> r.events = listOf(succeeded()) } }

        val reply = dispatcher.handle(call(body = body, headers = mapOf("content-type" to listOf("application/json"), "authorization" to listOf("Bearer abc"))))

        assertEquals(400, reply.status)
        assertEquals("bad signature", String(reply.body))
        assertTrue(attempts.applied.isEmpty(), "events of an unverified request are never applied")

        val row = only()

        assertEquals(PaymentEventStatus.REJECTED, row.status)
        assertEquals(false, row.verified)
        assertEquals("INVALID_SIGNATURE", row.error)
        assertEquals(400, row.responseStatus)
        assertTrue(row.eventKey.startsWith("r:"), "a forged request never occupies the key of a genuine delivery")
        assertTrue(row.body!!.toByteArray().size <= 2048, "a rejected body is cut to 2048 bytes")
        assertFalse(row.body!!.contains(secret), "settled rows are redacted in place")
        assertFalse(row.headers!!.contains("Bearer abc"), row.headers)
        assertTrue(store.touched.isEmpty(), "lastInboundAt only for a verified request")
    }

    @Test
    fun `a verified webhook or notification touches the method row, a return does not`(): Unit = runBlocking {
        fake.onInbound = { ok() }

        dispatcher.handle(call())
        dispatcher.handle(call(InboundKind.NOTIFY))
        dispatcher.handle(call(InboundKind.RETURN))

        assertEquals(listOf("fake", "fake"), store.touched)
    }

    // ================================================================================================ step 5, the event key

    @Test
    fun `a provider key makes the row e colon key and the events are applied once`(): Unit = runBlocking {
        fake.onInbound = { ok(listOf(succeeded()), key = "evt_1") }

        val reply = dispatcher.handle(call())

        assertEquals(200, reply.status)

        val row = only()

        assertEquals("e:evt_1", row.eventKey)
        assertEquals(PaymentEventStatus.PROCESSED, row.status)
        assertEquals(true, row.verified)
        assertEquals("Succeeded", row.eventTypes)
        assertEquals(1L, row.paymentId)
        assertEquals(1L, row.orderId)
        assertEquals(1, attempts.transitions.get())
        assertEquals(200, row.responseStatus)
        assertNotNull(row.processedAt)
    }

    @Test
    fun `a provider key held by a PROCESSED row is a DUPLICATE, the reply is still produced, nothing is applied, duplicateCount grows`(): Unit = runBlocking {
        fake.onInbound = { ok(listOf(succeeded()), key = "evt_1", reply = HttpReply.text("RECEIVED-OK")) }

        dispatcher.handle(call())

        val again = dispatcher.handle(call())

        assertEquals(200, again.status)
        assertEquals("RECEIVED-OK", String(again.body), "never an error for a duplicate")
        assertEquals(1, attempts.applied.size, "the events of the second copy are not applied")

        val (first, second) = rows()

        assertEquals(PaymentEventStatus.PROCESSED, first.status)
        assertEquals(1, first.duplicateCount)
        assertEquals("e:evt_1", first.eventKey)
        assertEquals(PaymentEventStatus.DUPLICATE, second.status)
        assertTrue(second.eventKey.startsWith("r:"))
        assertNull(second.error)

        dispatcher.handle(call())

        assertEquals(2, rows().first().duplicateCount)
        assertEquals(1, attempts.applied.size)
    }

    @Test
    fun `a provider key held by a FAILED, a DEFERRED or a crashed RECEIVED row takes the key over, the old row is SUPERSEDED, the events are applied`(): Unit = runBlocking {
        data class Case(val status: PaymentEventStatus, val age: Long)

        for (case in listOf(Case(PaymentEventStatus.FAILED, 0), Case(PaymentEventStatus.DEFERRED, 0), Case(PaymentEventStatus.RECEIVED, 61_000))) {
            val key = "evt_${case.status}"
            val old = store.put(
                MarketPaymentEvent(
                    providerId = "fake", channel = "WEBHOOK", eventKey = "e:$key", status = case.status, attempts = 1, headers = "{\"x-sig\":[\"$secret\"]}", body = "{\"k\":\"$secret\"}",
                    createdAt = clock.now() - case.age, updatedAt = clock.now() - case.age
                )
            )
            val before = attempts.transitions.get()

            // a fresh attempt each time so that the transition is countable
            attempts.add(10L + old.id, orderId = 10L + old.id)
            fake.onInbound = { ok(listOf(succeeded(id = 10L + old.id)), key = key) }

            assertEquals(200, dispatcher.handle(call()).status)

            val superseded = store.get(old.id)!!

            assertEquals(PaymentEventStatus.SUPERSEDED, superseded.status, "${case.status}")
            assertEquals("e:$key:${old.id}", superseded.eventKey)
            assertFalse(superseded.headers!!.contains(secret) || superseded.body!!.contains(secret), "a superseded row is redacted in place")

            val taker = store.byKey("fake", "e:$key")!!

            assertEquals(PaymentEventStatus.PROCESSED, taker.status, "${case.status}")
            assertEquals(before + 1, attempts.transitions.get(), "the redelivery after a failure is applied, not swallowed (${case.status})")
        }
    }

    @Test
    fun `a provider key held by an in-flight row sends both through step 6 and only one transition happens`(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()

        attempts.applyGate = gate
        fake.onInbound = { ok(listOf(succeeded()), key = "evt_7") }

        // the first copy holds the key and is held open inside step 6
        val first = async(Dispatchers.Default) { dispatcher.handle(call()) }

        withTimeout(5_000) { attempts.applyStarted.await() }

        assertEquals(PaymentEventStatus.RECEIVED, store.byKey("fake", "e:evt_7")!!.status, "the first copy is in flight")

        // the second copy arrives meanwhile: DUPLICATE of the in-flight row, but its events still go through step 6
        val second = dispatcher.handle(call())

        assertEquals(200, second.status, "the reply is the provider's, never an error")
        assertEquals(1, attempts.transitions.get(), "the second copy completed the payment while the first one waits")

        gate.complete(Unit)

        assertEquals(200, first.await().status)
        assertEquals(1, attempts.transitions.get(), "the order lock and the state machines make the late copy a no-op")
        assertEquals(2, attempts.applied.size, "both went through step 6")

        val held = store.byKey("fake", "e:evt_7")!!

        assertEquals(PaymentEventStatus.PROCESSED, held.status)
        assertEquals(1, held.duplicateCount)
        assertEquals(2, rows().size)
        assertEquals(setOf(PaymentEventStatus.PROCESSED, PaymentEventStatus.DUPLICATE), rows().map { it.status }.toSet())
    }

    @Test
    fun `no provider key means every request is applied, identical bodies with different fetched states both change state`(): Unit = runBlocking {
        // Mollie-style: the identical body id=tr_x for every state change, the provider re-fetches the state and reports what it found
        val states = ArrayDeque(listOf(PaymentEvent.Pending(PaymentTarget.Attempt(1), com.panomc.plugins.market.spi.payment.PendingReason.AWAITING_BANK), succeeded()))

        fake.onInbound = { ok(listOf(states.removeFirst())) }

        val body = "id=tr_x".toByteArray()

        dispatcher.handle(call(body = body, headers = mapOf("content-type" to listOf("application/x-www-form-urlencoded"))))
        dispatcher.handle(call(body = body, headers = mapOf("content-type" to listOf("application/x-www-form-urlencoded"))))

        assertEquals(2, attempts.applied.size, "both requests were applied")
        assertEquals(PaymentAttemptEvent.Pending, attempts.applied[0].event)
        assertTrue(attempts.applied[1].event is PaymentAttemptEvent.Succeeded)
        assertEquals(1, attempts.transitions.get())
        assertEquals(2, rows().count { it.status == PaymentEventStatus.PROCESSED }, "no row is a DUPLICATE")
        assertEquals(1, rows().map { it.requestHash }.toSet().size, "the hash is equal and identifies nothing")
        assertTrue(rows().all { it.eventKey.startsWith("r:") })
    }

    @Test
    fun `an empty-body GET pingback is not swallowed`(): Unit = runBlocking {
        fake.onInbound = { ok(listOf(succeeded())) }

        val reply = dispatcher.handle(call(InboundKind.NOTIFY, method = "GET", body = ByteArray(0), query = "uid=1&ref=2&sig=abc", headers = emptyMap()))

        assertEquals(200, reply.status)
        assertEquals(1, attempts.transitions.get())

        val row = only()

        assertEquals("GET", row.method)
        assertEquals(PaymentEventStatus.PROCESSED, row.status)
        assertEquals("", row.body)
    }

    // ================================================================================================ steps 3 and 6, failures

    @Test
    fun `a provider that throws makes the row FAILED with nextAttemptAt and answers 500, a return goes to the order page`(): Unit = runBlocking {
        fake.onInbound = { throw IllegalStateException("boom with $secret inside") }

        val reply = dispatcher.handle(call())

        assertEquals(500, reply.status)

        val row = only()

        assertEquals(PaymentEventStatus.FAILED, row.status)
        assertEquals(1, row.attempts)
        assertEquals(500, row.responseStatus)
        assertTrue(row.error!!.startsWith("IllegalStateException"), row.error)
        assertFalse(row.error!!.contains(secret), "the error text is redacted")
        assertTrue(row.nextAttemptAt!! in clock.now() + InboundDispatcher.RETRY_BACKOFF.bounds(1).first..clock.now() + InboundDispatcher.RETRY_BACKOFF.bounds(1).last, "first retry about a minute away")
        assertTrue(row.headers!!.contains("content-type") && row.body == "{}", "a failed row keeps the raw request for the retry")

        val back = dispatcher.handle(call(InboundKind.RETURN, outcome = ReturnOutcome.SUCCESS))

        assertEquals(303, back.status)
        assertEquals("https://shop.example/store/order/ORDER000000000000001?return=success", back.headers["Location"])
        assertEquals(PaymentEventStatus.FAILED, rows().last().status)
    }

    @Test
    fun `settings that are not there answer 503 and the row is FAILED, a handshake without settings is answered by the provider`(): Unit = runBlocking {
        fake.onInbound = { throw ProviderException(ProviderErrorCode.CONFIGURATION, "Setting 'secret' is not configured") }

        val reply = dispatcher.handle(call())

        assertEquals(503, reply.status)
        assertEquals(PaymentEventStatus.FAILED, only().status)
        assertEquals(503, only().responseStatus)
        assertTrue(only().error!!.startsWith("CONFIGURATION"))

        // a validation handshake that needs no settings is answered, authentic and with nothing to do
        fake.onInbound = { InboundResult.ignored(HttpReply.json(io.vertx.core.json.JsonObject().put("validated", true))) }

        val handshake = dispatcher.handle(call())

        assertEquals(200, handshake.status)
        assertEquals("""{"validated":true}""", String(handshake.body))
        assertEquals(PaymentEventStatus.PROCESSED, rows().last().status)
    }

    @Test
    fun `a provider that does not answer in time makes the row FAILED and answers 500`(): Unit = runBlocking {
        dispatcher = build(timeoutMs = 150)
        fake.delay(FakePaymentProvider.Op.INBOUND) // never completed: the deadline cancels the call

        val reply = withTimeout(10_000) { dispatcher.handle(call()) }

        assertEquals(500, reply.status)
        assertEquals(PaymentEventStatus.FAILED, only().status)
        assertTrue(only().error!!.startsWith("TIMEOUT"), only().error)
    }

    @Test
    fun `a failure of step 6 makes the row FAILED with nextAttemptAt and answers 500 instead of the provider's reply`(): Unit = runBlocking {
        fake.onInbound = { ok(listOf(succeeded()), key = "evt_9", reply = HttpReply.text("PROVIDER-OK")) }
        attempts.failApply = java.sql.SQLException("database down")

        val reply = dispatcher.handle(call())

        assertEquals(500, reply.status)
        assertFalse("PROVIDER-OK" == String(reply.body))

        val row = only()

        assertEquals(PaymentEventStatus.FAILED, row.status)
        assertEquals("e:evt_9", row.eventKey, "the key is held, so a redelivery takes it over")
        assertNotNull(row.nextAttemptAt)
        assertTrue(row.error!!.contains("database down"))
        assertTrue(row.body == "{}", "verbatim for the retry")

        // the redelivery applies the fact once the database is back
        attempts.failApply = null

        assertEquals(200, dispatcher.handle(call()).status)
        assertEquals(1, attempts.transitions.get())
        assertEquals(PaymentEventStatus.SUPERSEDED, store.get(row.id)!!.status)
    }

    @Test
    fun `a provider is not called for a notification whose attempt cannot be read, the gateway retries`(): Unit = runBlocking {
        attempts.failToken = IllegalStateException("database down")
        fake.onInbound = { ok() }

        assertEquals(503, dispatcher.handle(call(InboundKind.NOTIFY)).status)
        assertTrue(rows().isEmpty() && fake.calls.isEmpty())
    }

    @Test
    fun `the tenth failed run leaves the row without a schedule`(): Unit = runBlocking {
        fake.onInbound = { throw IllegalStateException("boom") }

        dispatcher.handle(call())

        repeat(InboundDispatcher.MAX_ATTEMPTS - 1) {
            clock.advance(7 * 3_600_000L)
            assertTrue(dispatcher.retry(only()))
        }

        val row = only()

        assertEquals(InboundDispatcher.MAX_ATTEMPTS, row.attempts)
        assertEquals(PaymentEventStatus.FAILED, row.status)
        assertNull(row.nextAttemptAt, "a human replays it from here")
    }

    // =========================================================================================== step 6, the events

    @Test
    fun `an event for a target that is not an attempt of the provider is skipped, the request is PROCESSED and nothing changes`(): Unit = runBlocking {
        attempts.add(2, providerId = "other")

        fake.onInbound = {
            ok(
                listOf(
                    PaymentEvent.Succeeded(PaymentTarget.Reference("NOSUCHREFERENCE00000"), Money(1000, "EUR")),
                    PaymentEvent.Succeeded(PaymentTarget.Attempt(2), Money(1000, "EUR")), // an attempt of another provider
                    PaymentEvent.Succeeded(PaymentTarget.GatewayTransaction("txn_unknown"), Money(1000, "EUR"))
                ),
                reply = HttpReply.text("OK")
            )
        }

        val reply = dispatcher.handle(call())

        assertEquals(200, reply.status)
        assertEquals("OK", String(reply.body))
        assertTrue(attempts.applied.isEmpty())
        assertEquals(PaymentEventStatus.PROCESSED, only().status)
        assertEquals(PaymentStatus.PENDING, attempts.statusOf(2))
    }

    @Test
    fun `events are applied in order, each through the attempt machine with the provider's money policy`(): Unit = runBlocking {
        attempts.add(3, orderId = 3)

        fake.onInbound = {
            ok(
                listOf(
                    PaymentEvent.Pending(PaymentTarget.Attempt(1), com.panomc.plugins.market.spi.payment.PendingReason.AWAITING_BANK),
                    succeeded(id = 3),
                    PaymentEvent.Failed(PaymentTarget.Attempt(1), "card_declined", "declined").also { f -> f.final = true },
                    PaymentEvent.Cancelled(PaymentTarget.Attempt(3)),
                    PaymentEvent.Expired(PaymentTarget.Attempt(1))
                )
            )
        }

        dispatcher.handle(call())

        assertEquals(
            listOf(1L to "Pending", 3L to "Succeeded", 1L to "Failed", 3L to "Cancelled", 1L to "Expired"),
            attempts.applied.map { it.attemptId to it.event.javaClass.simpleName }
        )
        assertEquals("Pending,Succeeded,Failed,Cancelled,Expired", only().eventTypes)
        assertTrue((attempts.applied[2].event as PaymentAttemptEvent.Failed).final)
        assertEquals("card_declined", attempts.applied[2].facts.failureCode)
        assertEquals(1L, only().paymentId, "the row names the first attempt a target resolved to")
    }

    @Test
    fun `an event of another environment than the attempt's becomes NeedsReview OTHER with the money it names, every other event of it is skipped`(): Unit = runBlocking {
        fake.onInbound = {
            ok(
                listOf(
                    succeeded(amount = 1234, testMode = true),
                    PaymentEvent.Failed(PaymentTarget.Attempt(1), "x", null).also { f -> f.testMode = true },
                    PaymentEvent.Cancelled(PaymentTarget.Attempt(1)).also { c -> c.testMode = true },
                    PaymentEvent.NeedsReview(PaymentTarget.Attempt(1), ReviewReason.UNDERPAID).also { n -> n.testMode = true }
                )
            )
        }

        dispatcher.handle(call()) // the attempt is a live one (testMode = false)

        val only = attempts.applied.single()
        val event = only.event as PaymentAttemptEvent.NeedsReview

        assertEquals(ReviewReason.OTHER, event.reason)
        assertEquals(true, event.eventTestMode)
        assertEquals(1234L, only.facts.receivedAmount, "real money that arrived is recorded")
        assertEquals("EUR", only.facts.receivedCurrency)
        assertTrue(only.facts.adminMessage!!.contains("environment mismatch"), only.facts.adminMessage)
        assertEquals(0, attempts.transitions.get(), "a sandbox notification never completes a live order")
        assertEquals(PaymentStatus.REVIEW, attempts.statusOf(1))
        assertEquals(PaymentEventStatus.PROCESSED, rows().single().status)

        // the same statement on a test-mode attempt is no mismatch at all
        attempts.add(5, orderId = 5, testMode = true)
        fake.onInbound = { ok(listOf(succeeded(id = 5, testMode = true))) }
        dispatcher.handle(call())

        assertEquals(1, attempts.transitions.get())
    }

    @Test
    fun `ReferencesUpdated only attaches ids, a refund event with no handler fails the row instead of being dropped, an installed sink gets it`(): Unit = runBlocking {
        val refund = PaymentEvent.RefundUpdated(PaymentTarget.Attempt(1), RefundState.SUCCEEDED, Money(500, "EUR"))
        val references = PaymentEvent.ReferencesUpdated(PaymentTarget.Attempt(1)).also { it.gatewayRefs = mapOf("session" to "cs_1") }

        fake.onInbound = { ok(listOf(references)) }

        dispatcher.handle(call())

        assertEquals(1, attempts.attached.size)
        assertTrue(attempts.applied.isEmpty())
        assertEquals(PaymentEventStatus.PROCESSED, rows().last().status)

        fake.onInbound = { ok(listOf(refund), key = "evt_refund") }

        val failed = dispatcher.handle(call())

        assertEquals(500, failed.status, "a refund fact nobody applies yet is retried, never lost")
        assertEquals(PaymentEventStatus.FAILED, rows().last().status)
        assertTrue(rows().last().error!!.startsWith("EventNotHandled"), rows().last().error)

        val seen = ArrayList<Triple<PaymentEvent, Long?, String?>>()

        sink = PaymentEventSink { event, attempt, ctx -> seen += Triple(event, attempt?.id, ctx.eventKey) }

        assertEquals(200, dispatcher.handle(call()).status, "the redelivery takes the failed row over and the sink applies it")
        assertEquals(listOf(Triple<PaymentEvent, Long?, String?>(refund, 1L, "evt_refund")), seen)
    }

    // ================================================================================================== returns, step 8

    @Test
    fun `a RETURN is answered with a 303 to the order page and the outcome as an untrusted hint, never the access token`(): Unit = runBlocking {
        fake.onInbound = { ok(reply = HttpReply.text("ignored by a return")) }

        val expected = mapOf(
            ReturnOutcome.SUCCESS to "?return=success", ReturnOutcome.CANCEL to "?return=cancel", ReturnOutcome.PENDING to "?return=pending",
            ReturnOutcome.RESULT to ""
        )

        for ((outcome, hint) in expected) {
            val reply = dispatcher.handle(call(InboundKind.RETURN, outcome = outcome))

            assertEquals(303, reply.status, "$outcome")
            assertEquals("https://shop.example/store/order/ORDER000000000000001$hint", reply.headers["Location"], "$outcome")
            assertEquals(0, reply.body.size)
            assertFalse(reply.headers["Location"]!!.contains("token"))
        }
    }

    @Test
    fun `a RETURN only ever redirects to the order page, whatever the provider answers`(): Unit = runBlocking {
        fake.onInbound = { ok(reply = HttpReply.redirect("https://evil.example/phish")) }

        assertEquals("https://shop.example/store/order/ORDER000000000000001?return=success", dispatcher.handle(call(InboundKind.RETURN)).headers["Location"])

        fake.onInbound = { InboundResult.rejected(HttpReply.redirect("https://evil.example/phish"), "BAD") }

        assertEquals("https://shop.example/store/order/ORDER000000000000001?return=success", dispatcher.handle(call(InboundKind.RETURN)).headers["Location"])

        fake.onInbound = { ok(reply = HttpReply.toOrderPage()) }

        assertEquals("https://shop.example/store/order/ORDER000000000000001?return=cancel", dispatcher.handle(call(InboundKind.RETURN, outcome = ReturnOutcome.CANCEL)).headers["Location"])
    }

    @Test
    fun `the STEP hop of a gateway passes its own redirect to an absolute address, anything else goes to the order page`(): Unit = runBlocking {
        fake.onInbound = { ok(reply = HttpReply.redirect("https://basket.gateway.example/auth?x=1")) }

        val step = dispatcher.handle(call(InboundKind.RETURN, outcome = ReturnOutcome.STEP, step = "basket"))

        assertEquals(303, step.status)
        assertEquals("https://basket.gateway.example/auth?x=1", step.headers["Location"])
        assertEquals("step:basket", rows().last().subChannel)

        fake.onInbound = { ok(reply = HttpReply.text("not a redirect")) }

        assertEquals("https://shop.example/store/order/ORDER000000000000001", dispatcher.handle(call(InboundKind.RETURN, outcome = ReturnOutcome.STEP, step = "basket")).headers["Location"])

        fake.onInbound = { ok(reply = HttpReply.redirect("/relative/path")) }

        assertEquals("https://shop.example/store/order/ORDER000000000000001", dispatcher.handle(call(InboundKind.RETURN, outcome = ReturnOutcome.STEP, step = "basket")).headers["Location"])
    }

    @Test
    fun `a notification may answer toOrderPage, it lands on the order of the attempt, or on the store when none resolved`(): Unit = runBlocking {
        fake.onInbound = { ok(reply = HttpReply.toOrderPage()) }

        assertEquals("https://shop.example/store/order/ORDER000000000000001", dispatcher.handle(call(InboundKind.NOTIFY)).headers["Location"])
        assertEquals("https://shop.example/store", dispatcher.handle(call()).headers["Location"])

        fake.onInbound = { ok(listOf(succeeded()), reply = HttpReply.toOrderPage()) }

        assertEquals("https://shop.example/store/order/ORDER000000000000001", dispatcher.handle(call()).headers["Location"], "the first event's attempt")
    }

    // ===================================================================================== the attempt lock

    /** A provider that stays inside `handleInbound` for a moment, counts overlapping calls and takes the attempt lock again like a webhook handler may. */
    private class Probe(private val delegate: FakePaymentProvider) : PaymentProvider by delegate {
        val inside = AtomicInteger()
        val overlap = AtomicInteger()
        val reentered = AtomicInteger()

        override suspend fun handleInbound(ctx: PaymentContext, request: PaymentInboundRequest): InboundResult {
            if (inside.incrementAndGet() > 1) overlap.incrementAndGet()

            delay(40)

            // the dispatcher holds this attempt's lock around the call: asking for it again must not wait for itself
            if (request.http.kind != InboundKind.WEBHOOK) withTimeout(2_000) { ctx.withAttemptLock(request.attempt!!.id) { reentered.incrementAndGet() } }

            inside.decrementAndGet()

            return InboundResult.accepted(HttpReply.text("OK"), emptyList())
        }
    }

    @Test
    fun `handleInbound of a NOTIFY and a RETURN runs under the attempt lock, a provider that takes it again does not wait for itself, a webhook takes none`(): Unit = runBlocking {
        val probe = Probe(fake)

        providers.access = ProviderAccess.Ready(probe, ProviderMoneyPolicy(), Redactor()) { tm -> LockingContext(TestContexts.payment("fake", TestContexts.settings(), vertx, tm ?: false), locks) }

        val calls = List(6) { i -> async(Dispatchers.Default) { dispatcher.handle(call(if (i % 2 == 0) InboundKind.NOTIFY else InboundKind.RETURN)) } }

        withTimeout(30_000) { calls.awaitAll() }

        assertEquals(0, probe.overlap.get(), "two calls for one attempt never run inside the provider together")
        assertEquals(6, probe.reentered.get(), "the provider took the lock again inside the handler, every time")
        assertEquals(0, locks.inUse(), "no lock entry outlives its calls")
        assertTrue(rows().all { it.status == PaymentEventStatus.PROCESSED }, rows().map { it.status }.toString())

        // a webhook (no attempt in play) is not serialised: it may take ctx.withAttemptLock itself
        val hooks = List(4) { async(Dispatchers.Default) { dispatcher.handle(call()) } }

        withTimeout(30_000) { hooks.awaitAll() }

        assertTrue(probe.overlap.get() > 0, "webhooks overlap")
        assertEquals(6, probe.reentered.get())
    }

    // ======================================================================================== settled rows are redacted

    @Test
    fun `a settled row is redacted in place, a row that can still be replayed is verbatim`(): Unit = runBlocking {
        val sensitive = ("{\"secret\":\"$secret\",\"card\":\"4111 1111 1111 1111\"}").toByteArray()
        val headers = mapOf("content-type" to listOf("application/json"), "x-api-key" to listOf("abc123"), "x-note" to listOf("see $secret"))

        fake.onInbound = { throw IllegalStateException("down") }
        dispatcher.handle(call(body = sensitive, headers = headers, query = "sig=$secret"))

        val failed = only()

        assertTrue(failed.body!!.contains(secret) && failed.body!!.contains("4111 1111 1111 1111"), "verbatim while FAILED: needed to re-run it")
        assertTrue(failed.headers!!.contains("abc123") && failed.headers!!.contains(":query"), "headers and the raw query too")

        fake.onInbound = { ok() }
        clock.advance(2 * 60_000L)

        assertTrue(dispatcher.retry(failed))

        val settled = only()

        assertEquals(PaymentEventStatus.PROCESSED, settled.status)
        assertEquals(2, settled.attempts, "the first request and one retry")
        assertFalse(settled.body!!.contains(secret), settled.body)
        assertFalse(settled.body!!.contains("4111 1111 1111 1111"), settled.body)
        assertTrue(settled.body!!.contains("1111"), "the last four digits stay")
        assertFalse(settled.headers!!.contains(secret) || settled.headers!!.contains("abc123"), settled.headers)
        assertFalse(settled.headers!!.contains(":query"), "the reserved replay keys are gone")
        assertFalse(settled.url!!.contains(secret), "the stored address never held the secret: ${settled.url}")
    }

    // ===================================================================================== retry job and replay

    @Test
    fun `the retry job runs a FAILED row again on the stored raw request with the original receive time`(): Unit = runBlocking {
        val job = InboundEventRetryJob(dispatcher, store, clock)
        val received = ArrayList<PaymentInboundRequest>()
        val body = "{\"id\":\"evt_1\",\"é\":true}".toByteArray()

        fake.onInbound = { throw IllegalStateException("down") }

        val sentAt = clock.now()

        dispatcher.handle(call(InboundKind.NOTIFY, body = body, query = "a=1&b=%C3%A9", headers = mapOf("content-type" to listOf("application/json"), "x-sig" to listOf("abc"))))

        assertEquals(0, job.runOnce(), "not due yet")

        clock.advance(2 * 60_000L)
        fake.onInbound = { received += it; ok(listOf(succeeded()), key = "evt_1") }

        assertEquals(1, job.runOnce())

        val request = received.single()

        assertEquals(sentAt, request.http.receivedAt, "the signature window is not applied again: receivedAt is the original time")
        assertTrue(body.contentEquals(request.http.body))
        assertEquals("a=1&b=%C3%A9", request.http.rawQuery)
        assertEquals(mapOf("a" to listOf("1"), "b" to listOf("é")), request.http.query)
        assertEquals("abc", request.http.header("x-sig"))
        assertEquals(1L, request.attempt!!.id, "the attempt is found again from the stored payment id")
        assertEquals(1, attempts.transitions.get())

        val row = only()

        assertEquals(PaymentEventStatus.PROCESSED, row.status)
        assertEquals("e:evt_1", row.eventKey)
        assertEquals(2, row.attempts)
        assertEquals(0, job.runOnce(), "nothing left to do")
    }

    @Test
    fun `the retry job picks up a crashed RECEIVED row after a minute and two parallel runs handle a row once`(): Unit = runBlocking {
        val crashed = store.put(
            MarketPaymentEvent(
                providerId = "fake", channel = "WEBHOOK", eventKey = "r:crash", status = PaymentEventStatus.RECEIVED, attempts = 1, headers = "{\"content-type\":[\"application/json\"]}", body = "{}",
                createdAt = clock.now(), updatedAt = clock.now()
            )
        )
        val runs = AtomicInteger()

        fake.onInbound = { runs.incrementAndGet(); ok(listOf(succeeded())) }

        val job = InboundEventRetryJob(dispatcher, store, clock)

        assertEquals(0, job.runOnce(), "a RECEIVED row younger than a minute may be in flight")

        clock.advance(61_000L)

        val both = List(2) { async(Dispatchers.Default) { job.runOnce() } }.awaitAll()

        assertEquals(1, both.sum(), "one claim wins")
        assertEquals(1, runs.get(), "the provider ran once")
        assertEquals(PaymentEventStatus.PROCESSED, store.get(crashed.id)!!.status)
        assertEquals(2, store.get(crashed.id)!!.attempts)
    }

    @Test
    fun `a RECEIVED row that used up its runs becomes FAILED without a schedule`(): Unit = runBlocking {
        val stuck = store.put(
            MarketPaymentEvent(
                providerId = "fake", channel = "WEBHOOK", eventKey = "r:stuck", status = PaymentEventStatus.RECEIVED, attempts = InboundDispatcher.MAX_ATTEMPTS,
                headers = "{}", body = "{}", createdAt = clock.now() - 120_000L, updatedAt = clock.now() - 120_000L
            )
        )

        fake.onInbound = { ok() }

        assertEquals(0, InboundEventRetryJob(dispatcher, store, clock).runOnce())
        assertEquals(PaymentEventStatus.FAILED, store.get(stuck.id)!!.status)
        assertNull(store.get(stuck.id)!!.nextAttemptAt)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `a row whose provider is gone is DEFERRED by the retry, and the panel replays it once the provider is back`(): Unit = runBlocking {
        fake.onInbound = { throw IllegalStateException("down") }
        dispatcher.handle(call())

        providers.access = ProviderAccess.Unavailable
        clock.advance(2 * 60_000L)

        assertEquals(1, InboundEventRetryJob(dispatcher, store, clock).runOnce())
        assertEquals(PaymentEventStatus.DEFERRED, only().status)
        assertEquals(0, InboundEventRetryJob(dispatcher, store, clock).runOnce(), "DEFERRED waits for a human")

        providers.access = ready()
        fake.onInbound = { ok(listOf(succeeded())) }

        val result = dispatcher.replay(only().id)

        assertTrue(result is ReplayResult.Done, "$result")
        assertEquals(PaymentEventStatus.PROCESSED, only().status)
        assertEquals(1, attempts.transitions.get())
    }

    @Test
    fun `only DEFERRED, FAILED and RECEIVED older than a minute can be replayed, others are an invalid state`(): Unit = runBlocking {
        fake.onInbound = { ok() }

        dispatcher.handle(call())

        val processed = only()

        assertTrue(dispatcher.replay(processed.id).let { it is ReplayResult.InvalidState && it.status == PaymentEventStatus.PROCESSED })
        assertTrue(dispatcher.replay(999).let { it is ReplayResult.NotFound })

        for (status in listOf(PaymentEventStatus.REJECTED, PaymentEventStatus.DUPLICATE, PaymentEventStatus.SUPERSEDED)) {
            val row = store.put(MarketPaymentEvent(providerId = "fake", channel = "WEBHOOK", eventKey = "r:$status", status = status, createdAt = clock.now(), updatedAt = clock.now()))

            assertTrue(dispatcher.replay(row.id).let { it is ReplayResult.InvalidState && it.status == status }, "$status")
        }

        val young = store.put(MarketPaymentEvent(providerId = "fake", channel = "WEBHOOK", eventKey = "r:young", status = PaymentEventStatus.RECEIVED, createdAt = clock.now(), updatedAt = clock.now()))

        assertTrue(dispatcher.replay(young.id) is ReplayResult.InvalidState, "a RECEIVED row younger than 60 s may be in flight")
        assertFalse(dispatcher.isReplayable(young))

        clock.advance(60_000L)

        assertTrue(dispatcher.isReplayable(store.get(young.id)!!))
    }

    // ======================================================================================== the attempt page (V-14)

    @Test
    fun `a gateway Html document is sent in a sandbox without allow-same-origin, inline scripts get the nonce, undeclared sources are none`(): Unit = runBlocking {
        val headers = AttemptPages.htmlHeaders(
            listOf("https://js.gateway.example", "https://*.cdn.gateway.example:8443", "evil.example; script-src *", "https:"), listOf("https://3ds.gateway.example"), emptyList(),
            emptyList(), inlineScript = true, nonce = "n0nce"
        )
        val csp = headers.getValue("Content-Security-Policy")

        assertTrue(csp.startsWith("sandbox allow-scripts allow-forms allow-top-navigation allow-popups; default-src 'none'; "), csp)
        assertFalse(csp.contains("allow-same-origin"), "an opaque origin: no cookies, no credentialed call to /api")
        assertTrue(csp.contains("script-src https://js.gateway.example https://*.cdn.gateway.example:8443 https: 'nonce-n0nce';"), csp)
        assertFalse(csp.contains("evil.example"), "a source with a separator is dropped, it cannot add a directive")
        assertTrue(csp.contains("frame-src https://3ds.gateway.example;"), csp)
        assertTrue(csp.contains("connect-src 'none';"), csp)
        assertTrue(csp.contains("form-action https:;"), "no declared action origin: any https target (3-D Secure hosts are not known in advance)")
        assertTrue(csp.endsWith("img-src https: data:; style-src 'unsafe-inline' https:; base-uri 'none'; frame-ancestors 'none'"), csp)
        assertEquals("no-store", headers["Cache-Control"])
        assertEquals("nosniff", headers["X-Content-Type-Options"])
        assertEquals("no-referrer", headers["Referrer-Policy"])
        assertEquals("text/html; charset=utf-8", headers["Content-Type"])

        val noInline = AttemptPages.htmlHeaders(emptyList(), emptyList(), emptyList(), emptyList(), inlineScript = false, nonce = "n0nce").getValue("Content-Security-Policy")

        assertTrue(noInline.contains("script-src 'none';"), noInline)

        val document = "<html><script>a()</script><script src=\"https://js.gateway.example/x.js\" nonce=\"stolen\"></script><SCRIPT type=text/javascript>b()</SCRIPT><script src=x/></html>"

        assertEquals(
            "<html><script nonce=\"n0nce\">a()</script><script src=\"https://js.gateway.example/x.js\" nonce=\"n0nce\"></script><script type=text/javascript nonce=\"n0nce\">b()</SCRIPT><script src=x nonce=\"n0nce\"/></html>",
            AttemptPages.withNonce(document, "n0nce")
        )
    }

    @Test
    fun `a FORM_POST page escapes every name and value, auto-submits with a nonce and may only post to its own origin`(): Unit = runBlocking {
        val fields = io.vertx.core.json.JsonObject().put("a\"b", "x\"><script>alert(1)</script>").put("amount", "10.00").put("note", "tom & 'jerry'")
        val html = AttemptPages.formPostDocument("https://pay.gateway.example/post?x=1&y=\"2", fields, "UTF-8", "abc123")

        assertTrue(html.contains("<form id=\"f\" method=\"post\" action=\"https://pay.gateway.example/post?x=1&amp;y=&quot;2\" accept-charset=\"UTF-8\">"), html)
        assertTrue(html.contains("name=\"a&quot;b\" value=\"x&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;\""), html)
        assertTrue(html.contains("value=\"tom &amp; &#39;jerry&#39;\""), html)
        assertEquals(1, Regex("<script").findAll(html).count(), "no field value can add a script")
        assertTrue(html.contains("<script nonce=\"abc123\">document.getElementById('f').submit();</script>"), html)

        val headers = AttemptPages.formPostHeaders("https://pay.gateway.example", "abc123")

        assertEquals("default-src 'none'; script-src 'nonce-abc123'; form-action https://pay.gateway.example; base-uri 'none'; frame-ancestors 'none'", headers["Content-Security-Policy"])
        assertEquals("no-store", headers["Cache-Control"])
    }

    @Test
    fun `the attempt page is served only while the attempt is open, the form action must be https and the stored start is never served for another kind`(): Unit = runBlocking {
        val cipher = attempts.cipher

        fun stored(vararg parts: Pair<String, io.vertx.core.json.JsonObject>) =
            cipher.encrypt(io.vertx.core.json.JsonObject().put("start", io.vertx.core.json.JsonObject().put("kind", "X")).also { json -> parts.forEach { (k, v) -> json.put(k, v) } }.encode())

        fun open(id: Long, payload: String?, status: PaymentStatus = PaymentStatus.PENDING, testMode: Boolean = false): MarketPayment {
            val a = MarketPayment(id = id, orderId = id, providerId = "fake", status = status, token = "%040x".format(id), reference = "R$id", startPayload = payload, testMode = testMode)

            attempts.addRaw(a)

            return a
        }

        val form = io.vertx.core.json.JsonObject().put("actionUrl", "https://pay.gateway.example/post").put("fields", io.vertx.core.json.JsonObject().put("a", "1"))
        val html = io.vertx.core.json.JsonObject().put("document", "<p>hi</p><script>x()</script>").put("inlineScript", true)
        val pages = AttemptPageService(attempts, cipher, SeqIds())

        open(21, stored("formPost" to form))
        open(22, stored("html" to html))
        open(23, stored("formPost" to form), status = PaymentStatus.SUCCEEDED)
        open(24, stored("formPost" to form.copy().put("actionUrl", "http://pay.gateway.example/post")))
        open(25, stored("formPost" to form.copy().put("actionUrl", "http://pay.gateway.example/post")), testMode = true)
        open(26, stored("formPost" to form.copy().put("actionUrl", "https://user:pw@pay.gateway.example/post")))
        open(27, null)

        val formPage = pages.page("%040x".format(21)) as AttemptPageResult.Page

        assertTrue(formPage.headers.getValue("Content-Security-Policy").contains("form-action https://pay.gateway.example;"))
        assertTrue(formPage.body.contains("action=\"https://pay.gateway.example/post\""))

        val htmlPage = pages.page("%040x".format(22)) as AttemptPageResult.Page

        assertTrue(htmlPage.headers.getValue("Content-Security-Policy").startsWith("sandbox allow-scripts"))
        assertTrue(htmlPage.body.contains("<script nonce=\""), htmlPage.body)

        val closed = pages.page("%040x".format(23))

        assertEquals("ORDER000000000000023", (closed as AttemptPageResult.ToOrderPage).publicId, "not served once the attempt is closed")
        assertEquals(AttemptPageResult.NotFound, pages.page("%040x".format(24)), "http is refused for a live attempt")
        assertTrue(pages.page("%040x".format(25)) is AttemptPageResult.Page, "http only for a test-mode attempt")
        assertEquals(AttemptPageResult.NotFound, pages.page("%040x".format(26)), "no credentials in the action address")
        assertEquals(AttemptPageResult.NotFound, pages.page("%040x".format(27)), "nothing stored")
        assertEquals(AttemptPageResult.NotFound, pages.page("f".repeat(40)), "unknown token")
    }

}
