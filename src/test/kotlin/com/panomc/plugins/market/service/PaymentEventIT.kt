package com.panomc.plugins.market.service

import com.panomc.plugins.market.core.payment.PaymentAttemptEvent
import com.panomc.plugins.market.core.payment.ProviderMoneyPolicy
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.MarketPaymentEvent
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.model.PaymentEventStatus
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.db.model.ReservationState
import com.panomc.plugins.market.job.InboundEventRetryJob
import com.panomc.plugins.market.job.MarketJobs
import com.panomc.plugins.market.job.MarketScheduler
import com.panomc.plugins.market.routes.api.payment.AttemptLocks
import com.panomc.plugins.market.routes.api.payment.AttemptLookup
import com.panomc.plugins.market.routes.api.payment.AttemptPageResult
import com.panomc.plugins.market.routes.api.payment.AttemptPageService
import com.panomc.plugins.market.routes.api.payment.AttemptPaymentContext
import com.panomc.plugins.market.routes.api.payment.DbInboundEventStore
import com.panomc.plugins.market.routes.api.payment.InboundAttempts
import com.panomc.plugins.market.routes.api.payment.InboundCall
import com.panomc.plugins.market.routes.api.payment.InboundDispatcher
import com.panomc.plugins.market.routes.api.payment.PaymentEventApplier
import com.panomc.plugins.market.routes.api.payment.PaymentEventSink
import com.panomc.plugins.market.routes.api.payment.PaymentInboundAttempts
import com.panomc.plugins.market.routes.api.payment.RegistryInboundProviders
import com.panomc.plugins.market.routes.api.payment.Settlement
import com.panomc.plugins.market.routes.api.payment.ReplayResult
import com.panomc.plugins.market.runtime.MarketRuntime
import com.panomc.plugins.market.spi.common.HttpReply
import com.panomc.plugins.market.spi.common.InboundKind
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.settingsSchema
import com.panomc.plugins.market.spi.payment.InboundResult
import com.panomc.plugins.market.spi.payment.PaymentAttemptView
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.payment.PaymentContext
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentInboundRequest
import com.panomc.plugins.market.spi.payment.PaymentProvider
import com.panomc.plugins.market.spi.payment.PaymentQueryResult
import com.panomc.plugins.market.spi.payment.PaymentTarget
import com.panomc.plugins.market.spi.payment.RefundState
import com.panomc.plugins.market.spi.payment.ReturnOutcome
import com.panomc.plugins.market.spi.payment.StartPaymentResult
import com.panomc.plugins.market.spi.testkit.TestContexts
import com.panomc.plugins.market.support.Await
import com.panomc.plugins.market.support.FakePaymentProvider
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** The attempt side with a failure that can be switched on: an infrastructure failure inside step 6. */
private class FlakyAttempts(private val delegate: InboundAttempts) : InboundAttempts by delegate {
    @Volatile var failures = 0

    override suspend fun apply(attempt: MarketPayment, event: PaymentAttemptEvent, facts: AttemptFacts, policy: ProviderMoneyPolicy): AppliedEvent {
        if (failures > 0) {
            failures--

            throw java.sql.SQLException("connection lost")
        }

        return delegate.apply(attempt, event, facts, policy)
    }
}

/** The fake provider with a secret setting, so that the redactor of a settled row has something to remove. */
private class SecretFake(private val fake: FakePaymentProvider) : PaymentProvider by fake {
    override fun settingsSchema() = settingsSchema { secret("apiKey") { label = com.panomc.plugins.market.spi.common.LocalizedText.of("API key") } }
}

/** A provider that reads its own attempts through `ctx.payments` and takes the attempt lock again inside `handleInbound`. */
private class LookupProbe(private val fake: FakePaymentProvider, private val foreignId: () -> Long, private val foreignReference: () -> String) : PaymentProvider by fake {
    val byId = CopyOnWriteArrayList<PaymentAttemptView?>()
    val byReference = CopyOnWriteArrayList<PaymentAttemptView?>()
    val byTransaction = CopyOnWriteArrayList<PaymentAttemptView?>()
    val byRef = CopyOnWriteArrayList<PaymentAttemptView?>()
    val foreign = CopyOnWriteArrayList<PaymentAttemptView?>()
    val reentered = AtomicInteger()

    override suspend fun handleInbound(ctx: PaymentContext, request: PaymentInboundRequest): InboundResult {
        val own = request.attempt!!

        byId += ctx.payments.byId(own.id)
        byReference += ctx.payments.byReference(own.reference)
        byTransaction += ctx.payments.byGatewayTransactionId("txn_probe")
        byRef += ctx.payments.byGatewayRef("session", "cs_probe")
        foreign += ctx.payments.byId(foreignId())
        foreign += ctx.payments.byReference(foreignReference())
        ctx.withAttemptLock(own.id) { reentered.incrementAndGet() }

        return InboundResult.accepted(HttpReply.text("OK"), emptyList())
    }
}

/**
 * The inbound pipeline of 02 section 7.3 on a real MariaDB, with `FakePaymentProvider` (MK-077; 17 section 11.4 `PaymentEventIT`; twins of R-01 to R-03,
 * F-04 to F-07, F-14, F-16 and V-14): the stored request, the key rules and the unique key, the events applied through the real `PaymentService` under
 * the order locks, the retry job, the settled-row redaction, the attempt page. The invariants I1 to I22 (I18, no inbound event stuck, above all) are
 * checked after every test by the base class.
 */
class PaymentEventIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var ph: PaymentHarness
    private lateinit var store: DbInboundEventStore
    private lateinit var attempts: PaymentInboundAttempts
    private lateinit var dispatcher: InboundDispatcher
    private lateinit var locks: AttemptLocks
    private val vertx: Vertx = Vertx.vertx()

    @Volatile private var state = MarketRuntime.State.READY

    @Volatile private var sink: PaymentEventSink = PaymentEventSink.UNHANDLED

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    @BeforeEach
    fun freshState() {
        runBlocking { resetState() }
        w = TestWiring(pool)
        ph = PaymentHarness(w, vertx)
        locks = AttemptLocks()
        state = MarketRuntime.State.READY
        sink = PaymentEventSink.UNHANDLED
        store = DbInboundEventStore(w.paymentEvents) { pool }
        attempts = PaymentInboundAttempts(w.payments, w.orders, ph.payments, ph.cipher, ph.db, ph.locks, w.clock) { pool }
        dispatcher = dispatcherOver(attempts)
    }

    private val fx get() = w.fixtures
    private val h get() = ph.h
    private val fake get() = ph.fake

    private fun dispatcherOver(over: InboundAttempts, timeoutMs: Long = 5_000L): InboundDispatcher {
        val contexts = PaymentContexts { provider, settings, testMode ->
            AttemptPaymentContext(TestContexts.payment(provider.id, settings, vertx, testMode), AttemptLookup(provider.id, w.payments, w.orders, ph.cipher) { pool }, locks)
        }
        val providers = RegistryInboundProviders(ph.lookup, w.paymentMethods, ph.cipher, contexts, { h.config.toConfig() }, { pool })

        return InboundDispatcher(
            store, over, providers, PaymentEventApplier(over) { event, attempt, ctx -> sink.apply(event, attempt, ctx) }, locks, w.clock, w.ids, { "https://shop.example" },
            { state }, providerTimeoutMs = timeoutMs
        )
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private suspend fun pending(price: Long = 1000, method: String = "fake"): Pair<MarketOrder, MarketPayment> {
        val result = h.checkout(h.body("items" to listOf(h.line(fx.product(price = price))), "paymentMethodId" to method))
        val order = ph.order(result.order.getString("publicId"))

        return order to ph.attempts(order.id).single()
    }

    private fun paid(a: MarketPayment, amount: Long = a.amount, currency: String = a.currency) = PaymentEvent.Succeeded(PaymentTarget.Reference(a.reference), Money(amount, currency))

    private fun ok(events: List<PaymentEvent> = emptyList(), key: String? = null, reply: HttpReply = HttpReply.text("OK")) = InboundResult.accepted(reply, events, key)

    private fun call(
        kind: InboundKind = InboundKind.WEBHOOK, attempt: MarketPayment? = null, body: String = "{}", headers: Map<String, List<String>> = mapOf("content-type" to listOf("application/json")),
        outcome: ReturnOutcome = ReturnOutcome.SUCCESS, query: String? = null, method: String = "POST"
    ) = InboundCall(
        kind, "fake", "default", if (kind == InboundKind.WEBHOOK) null else attempt!!.token, if (kind == InboundKind.RETURN) outcome else null, null, method,
        "/api/market/payments/fake/" + if (kind == InboundKind.WEBHOOK) "webhook" else "notify/${attempt!!.token}", query,
        query?.split('&')?.filter { it.isNotEmpty() }?.associate { it.substringBefore('=') to listOf(it.substringAfter('=', "")) } ?: emptyMap(), headers, headers["content-type"]?.firstOrNull(),
        body.toByteArray(), null, "203.0.113.9", w.clock.now()
    )

    private suspend fun events(): List<MarketPaymentEvent> =
        sql("SELECT `id` FROM `pano_market_payment_event` WHERE `direction` = 'IN' ORDER BY `id`").map { store.get(it.getLong("id"))!! }

    private suspend fun timeline(orderId: Long, type: OrderEventType) = w.orderEvents.getByOrderId(orderId, pool).count { it.type == type }

    private suspend fun orderIs(order: MarketOrder, status: OrderStatus) = assertEquals(status, ph.order(order.id).status)

    private fun completionsOf(orderId: Long) = ph.effects.of(orderId).count { it == "IssueInvoice" }

    // ===================================================================================================== R-01 to R-03

    @Test
    fun `R-01 the same signed webhook twice at once completes the order once, one row holds the key with duplicateCount 1, the other is a DUPLICATE`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        fx.webhookEndpoint(events = "[\"order.paid\"]")

        repeat(Race.rounds) { round ->
            val (order, attempt) = pending()
            val key = "evt_r01_$round"

            fake.onInbound = { ok(listOf(paid(attempt)), key) }

            val replies = Race.run(2) { dispatcher.handle(call(body = "{\"id\":\"$key\"}")) }

            assertTrue(replies.all { it.isSuccess && it.getOrThrow().status == 200 }, "round $round: ${replies.map { it.exceptionOrNull() }}")
            orderIs(order, OrderStatus.COMPLETED)
            assertEquals(1, completionsOf(order.id), "round $round: one set of side effects")
            assertEquals(1, timeline(order.id, OrderEventType.PAYMENT_SUCCEEDED), "round $round: one PAYMENT_SUCCEEDED row")
            assertEquals(1, sql("SELECT `id` FROM `pano_market_webhook_delivery` WHERE `orderId` = ?", order.id).size, "round $round: one order.paid")

            val copies = events().filter { it.body?.contains(key) == true }
            val holder = copies.single { it.eventKey == "e:$key" }

            assertEquals(2, copies.size, "round $round: two stored requests")
            assertEquals(PaymentEventStatus.PROCESSED, holder.status, "round $round")
            assertEquals(1, holder.duplicateCount, "round $round")
            assertEquals(PaymentEventStatus.DUPLICATE, copies.single { it.id != holder.id }.status, "round $round")
            assertTrue(copies.single { it.id != holder.id }.eventKey.startsWith("r:"), "round $round")
        }

        assertEquals(Race.rounds, events().count { it.status == PaymentEventStatus.DUPLICATE }, "one DUPLICATE per round")
        assertEquals(Race.rounds, events().count { it.status == PaymentEventStatus.PROCESSED })
    }

    @Test
    fun `R-01 the first copy is held inside the provider while the second arrives, one outcome fixed by the key rule`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (order, attempt) = pending()

        fake.onInbound = { ok(listOf(paid(attempt)), "evt_held") }

        val gate = fake.delay(FakePaymentProvider.Op.INBOUND)
        val first = async(Dispatchers.Default) { dispatcher.handle(call()) }

        Await.until(description = "the first copy is inside the provider") { fake.calls(FakePaymentProvider.Op.INBOUND).size == 1 }

        // the second copy overtakes: it holds the key and completes the order, the first one then finds the key PROCESSED
        val second = dispatcher.handle(call())

        assertEquals(200, second.status)
        orderIs(order, OrderStatus.COMPLETED)

        gate.complete(Unit)

        assertEquals(200, first.await().status)
        assertEquals(1, completionsOf(order.id))
        assertEquals(1, timeline(order.id, OrderEventType.PAYMENT_SUCCEEDED))

        val rows = events()
        val holder = rows.single { it.eventKey == "e:evt_held" }

        assertEquals(PaymentEventStatus.PROCESSED, holder.status)
        assertEquals(1, holder.duplicateCount)
        assertEquals(PaymentEventStatus.DUPLICATE, rows.single { it.id != holder.id }.status)
        assertTrue(rows.single { it.id != holder.id }.eventKey.startsWith("r:"))
    }

    @Test
    fun `R-02 the same body ten times in a row ends like R-01, every reply is 200`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (order, attempt) = pending()

        fake.onInbound = { ok(listOf(paid(attempt)), "evt_r02", HttpReply.text("OK")) }

        repeat(10) { assertEquals(200, dispatcher.handle(call(body = "{\"id\":\"evt_r02\"}")).status, "copy $it") }

        orderIs(order, OrderStatus.COMPLETED)
        assertEquals(1, completionsOf(order.id))
        assertEquals(1, timeline(order.id, OrderEventType.PAYMENT_SUCCEEDED))

        val rows = events()
        val holder = rows.single { it.eventKey == "e:evt_r02" }

        assertEquals(PaymentEventStatus.PROCESSED, holder.status)
        assertEquals(9, holder.duplicateCount)
        assertEquals(9, rows.count { it.status == PaymentEventStatus.DUPLICATE })
        assertEquals(10, rows.size)
        assertEquals(1, rows.map { it.requestHash }.toSet().size, "ten requests, one hash, nothing de-duplicated on it")
    }

    @Test
    fun `R-03 the same fact through a webhook, a browser return and a status query at once completes the order once`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        fake.caps = PaymentCapabilities().also { it.statusQuery = true }

        repeat(Race.rounds) { round ->
            val (order, attempt) = pending()

            fake.onInbound = { request ->
                if (request.http.kind == InboundKind.WEBHOOK) ok(listOf(paid(attempt)), "evt_r03_$round") else ok(listOf(paid(attempt)), null)
            }
            fake.onQuery = { PaymentQueryResult.of(PaymentEvent.Succeeded(PaymentTarget.Attempt(attempt.id), Money(attempt.amount, attempt.currency))) }

            val results = Race.run(3) { i ->
                when (i) {
                    0 -> dispatcher.handle(call())
                    1 -> dispatcher.handle(call(InboundKind.RETURN, attempt))
                    else -> ph.payments.status(order, owner = true, pool)
                }
            }

            assertTrue(results.all { it.isSuccess }, "round $round: ${results.mapNotNull { it.exceptionOrNull() }}")
            orderIs(order, OrderStatus.COMPLETED)
            assertEquals(1, completionsOf(order.id), "round $round: the state machine guards, not the event key")
            assertEquals(1, timeline(order.id, OrderEventType.PAYMENT_SUCCEEDED), "round $round")
            assertEquals(PaymentStatus.SUCCEEDED, ph.attempts(order.id).single().status)
        }
    }

    // ==================================================================================================== F-04 to F-07

    @Test
    fun `F-04 an invalid, a missing and a stale signature are REJECTED with the provider's 400, the order stays PENDING`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (order, attempt) = pending()

        fake.onInbound = { request ->
            val sig = request.http.header("x-signature")
            val t = sig?.substringAfter("t=")?.substringBefore(",")?.toLongOrNull()
            val fresh = t != null && Math.abs(request.http.receivedAt / 1000 - t) <= 300

            if (sig == null || t == null || !fresh || !sig.endsWith("v1=valid")) InboundResult.rejected(HttpReply.text("invalid signature", 400), "INVALID_SIGNATURE")
            else ok(listOf(paid(attempt)), "evt_f04")
        }

        val now = w.clock.now() / 1000
        val variants = mapOf("INVALID" to "t=$now,v1=bad", "MISSING" to null, "STALE" to "t=${now - 600},v1=valid")

        for ((label, signature) in variants) {
            val headers = mapOf("content-type" to listOf("application/json")) + (signature?.let { mapOf("x-signature" to listOf(it)) } ?: emptyMap())
            val reply = dispatcher.handle(call(headers = headers))

            assertEquals(400, reply.status, label)
            assertEquals("invalid signature", String(reply.body), label)
            orderIs(order, OrderStatus.PENDING)
        }

        val rows = events()

        assertEquals(3, rows.size)
        assertTrue(rows.all { it.status == PaymentEventStatus.REJECTED && it.verified == false && it.eventKey.startsWith("r:") && it.error == "INVALID_SIGNATURE" && it.responseStatus == 400 }, rows.map { it.status }.toString())
        assertEquals(PaymentStatus.PENDING, ph.attempts(order.id).single().status)

        // the same delivery with a good signature is applied, the earlier forgeries held no key
        val good = dispatcher.handle(call(headers = mapOf("content-type" to listOf("application/json"), "x-signature" to listOf("t=$now,v1=valid"))))

        assertEquals(200, good.status)
        orderIs(order, OrderStatus.COMPLETED)
        assertEquals("e:evt_f04", events().last().eventKey)
    }

    @Test
    fun `F-05 an underpaid success is a review with the reservation still held`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val product = fx.product(price = 1000, stock = 3)
        val result = h.checkout(h.body("items" to listOf(h.line(product)), "paymentMethodId" to "fake"))
        val order = ph.order(result.order.getString("publicId"))
        val attempt = ph.attempts(order.id).single()

        fake.onInbound = { ok(listOf(paid(attempt, amount = attempt.amount - 1)), "evt_f05") }

        assertEquals(200, dispatcher.handle(call()).status)

        val after = ph.order(order.id)

        assertEquals(OrderStatus.REVIEW, after.status)
        assertEquals("UNDERPAID", after.reviewReason)
        assertEquals(ReservationState.HELD, after.reservationState)
        assertEquals(PaymentStatus.REVIEW, ph.attempts(order.id).single().status)
        assertEquals(attempt.amount - 1, after.paidAmount, "what arrived is recorded")
        assertTrue(ph.effects.of(order.id).isEmpty(), "nothing delivered")
        assertEquals(PaymentEventStatus.PROCESSED, events().single().status)
    }

    @Test
    fun `F-06 an overpaid success is a review, with buyerMayPayMore it completes and the paid amount is recorded`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (over, overAttempt) = pending()

        fake.onInbound = { ok(listOf(paid(overAttempt, amount = overAttempt.amount + 500)), "evt_f06a") }
        dispatcher.handle(call())

        assertEquals(OrderStatus.REVIEW, ph.order(over.id).status)
        assertEquals("OVERPAID", ph.order(over.id).reviewReason)

        fake.caps = PaymentCapabilities().also { it.buyerMayPayMore = true }

        val (order, attempt) = pending()

        fake.onInbound = { ok(listOf(paid(attempt, amount = attempt.amount + 500)), "evt_f06b") }
        dispatcher.handle(call())

        assertEquals(OrderStatus.COMPLETED, ph.order(order.id).status)
        assertEquals(attempt.amount + 500, ph.order(order.id).paidAmount)
        assertEquals(attempt.amount + 500, ph.attempts(order.id).single().paidAmount)
    }

    @Test
    fun `F-07 a success in another currency is a review CURRENCY_MISMATCH`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (order, attempt) = pending()

        fake.onInbound = { ok(listOf(paid(attempt, currency = "USD")), "evt_f07") }
        dispatcher.handle(call())

        assertEquals(OrderStatus.REVIEW, ph.order(order.id).status)
        assertEquals("CURRENCY_MISMATCH", ph.order(order.id).reviewReason)
        assertTrue(ph.effects.of(order.id).isEmpty())
    }

    @Test
    fun `a success of another environment than the attempt's is a review OTHER, never an O2, and the other events of it are skipped`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (order, attempt) = pending()

        fake.onInbound = {
            ok(listOf(paid(attempt).also { e -> e.testMode = true }, PaymentEvent.Cancelled(PaymentTarget.Attempt(attempt.id)).also { e -> e.testMode = true }), "evt_env")
        }

        assertEquals(200, dispatcher.handle(call()).status)

        val after = ph.order(order.id)

        assertEquals(OrderStatus.REVIEW, after.status)
        assertEquals("OTHER", after.reviewReason)
        assertEquals(PaymentStatus.REVIEW, ph.attempts(order.id).single().status, "the cancel of the other environment was skipped")
        assertEquals(attempt.amount, after.paidAmount, "real money that arrived is recorded")
        assertTrue(ph.attempts(order.id).single().adminMessage!!.contains("environment mismatch"))
        assertTrue(ph.effects.of(order.id).isEmpty())
    }

    // ======================================================================================================== F-14, F-16

    @Test
    fun `F-14 two attempts of one order are both paid, the order completes once and the second attempt is flagged duplicate`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (order, first) = pending()

        ph.payments.pay(order, PayRequest("fake", null, null), PayCaller(), pool)

        val second = ph.attempts(order.id).last()

        assertTrue(second.id != first.id)

        fake.onInbound = { request -> ok(listOf(paid(if (request.http.queryParam("ref") == "second") second else first)), "evt_f14_${request.http.queryParam("ref")}") }

        assertEquals(200, dispatcher.handle(call(query = "ref=second")).status)
        assertEquals(200, dispatcher.handle(call(query = "ref=first")).status, "the gateway paid the cancelled first reference as well")

        assertEquals(OrderStatus.COMPLETED, ph.order(order.id).status)
        assertEquals(second.id, ph.order(order.id).paymentId, "the order keeps its first payment")
        assertEquals(1, completionsOf(order.id), "completed once")

        val attemptsNow = ph.attempts(order.id)

        assertEquals(PaymentStatus.SUCCEEDED, attemptsNow.first { it.id == first.id }.status)
        assertTrue(attemptsNow.first { it.id == first.id }.duplicate)
        assertFalse(attemptsNow.first { it.id == second.id }.duplicate)
        assertEquals(2, events().count { it.status == PaymentEventStatus.PROCESSED })
    }

    @Test
    fun `F-16 a success for a reference that does not exist is skipped, the request is PROCESSED with a 200 and nothing changes`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (order, attempt) = pending()

        fake.onInbound = {
            ok(
                listOf(
                    PaymentEvent.Succeeded(PaymentTarget.Reference("NOSUCHREFERENCE00000"), Money(1000, "EUR")),
                    PaymentEvent.Succeeded(PaymentTarget.GatewayTransaction("txn_nobody"), Money(1000, "EUR")),
                    PaymentEvent.Succeeded(PaymentTarget.GatewayRef("session", "cs_nobody"), Money(1000, "EUR"))
                ),
                "evt_f16"
            )
        }

        val reply = dispatcher.handle(call())

        assertEquals(200, reply.status)
        assertEquals("OK", String(reply.body))
        orderIs(order, OrderStatus.PENDING)
        assertEquals(PaymentStatus.PENDING, ph.attempts(order.id).single().status)

        val row = events().single()

        assertEquals(PaymentEventStatus.PROCESSED, row.status)
        assertEquals("e:evt_f16", row.eventKey)
        assertNull(row.paymentId)
        assertEquals(attempt.id, ph.attempts(order.id).single().id)
    }

    @Test
    fun `an attempt of another provider is never reached by a provider`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (order, attempt) = pending()

        // a second provider id with its own attempt row
        sql("UPDATE `pano_market_payment` SET `providerId` = 'someone-else' WHERE `id` = ?", attempt.id)

        fake.onInbound = { ok(listOf(PaymentEvent.Succeeded(PaymentTarget.Attempt(attempt.id), Money(attempt.amount, attempt.currency))), "evt_foreign") }

        assertEquals(200, dispatcher.handle(call()).status)
        orderIs(order, OrderStatus.PENDING)
        assertEquals(PaymentStatus.PENDING, ph.attempts(order.id).single().status)

        // and a token of another provider's attempt locates nothing
        assertEquals(404, dispatcher.handle(call(InboundKind.NOTIFY, attempt)).status)
    }

    // ============================================================================================== crash and retry

    @Test
    fun `a crash after step 2 leaves the stored request RECEIVED and the retry job completes it`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (order, attempt) = pending()
        val body = "{\"id\":\"evt_crash\"}"

        fake.onInbound = { throw CancellationException("the process died here") }

        val crashed = runCatching { dispatcher.handle(call(body = body, headers = mapOf("content-type" to listOf("application/json"), "x-signature" to listOf("t=1,v1=abc")))) }.exceptionOrNull()

        assertTrue(crashed is CancellationException, "$crashed")
        orderIs(order, OrderStatus.PENDING)

        val stuck = events().single()

        assertEquals(PaymentEventStatus.RECEIVED, stuck.status)
        assertEquals(body, stuck.body, "the raw request is on disk")
        assertTrue(stuck.headers!!.contains("t=1,v1=abc"))

        val job = InboundEventRetryJob(dispatcher, store, w.clock)

        assertEquals(0, job.runOnce(), "younger than a minute: it may still be running")

        w.clock.advance(61_000)

        var replayed: PaymentInboundRequest? = null

        fake.onInbound = { replayed = it; ok(listOf(paid(attempt)), "evt_crash") }

        assertEquals(1, job.runOnce())
        assertEquals(body, String(replayed!!.http.body), "step 3 ran on the stored raw request")
        assertEquals("t=1,v1=abc", replayed!!.http.header("x-signature"))
        assertEquals(stuck.createdAt, replayed!!.http.receivedAt)
        orderIs(order, OrderStatus.COMPLETED)

        val done = events().single()

        assertEquals(PaymentEventStatus.PROCESSED, done.status)
        assertEquals("e:evt_crash", done.eventKey)
        assertEquals(2, done.attempts)
        assertFalse(done.headers!!.contains("t=1,v1=abc") && done.headers!!.contains(":query"), "settled")
    }

    @Test
    fun `the scheduler job of the retry (the seam of MK-078) completes a crashed request on its tick`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (order, attempt) = pending()

        fake.onInbound = { throw CancellationException("the process died here") }
        runCatching { dispatcher.handle(call(body = "{\"id\":\"evt_sched\"}")) }

        val scheduler = MarketScheduler(w.clock, listOf(MarketJobs.inboundRetry(InboundEventRetryJob(dispatcher, store, w.clock))))

        assertEquals(0, scheduler.tick(), "the stored request is younger than a minute")
        assertEquals("inbound-retry", scheduler.stats().single().name)
        assertEquals(1, scheduler.stats().single().runs)

        w.clock.advance(MarketScheduler.INBOUND_RETRY_MS + 1_000)
        fake.onInbound = { ok(listOf(paid(attempt)), "evt_sched") }

        assertEquals(1, scheduler.tick())
        assertEquals(60_000L, MarketScheduler.INBOUND_RETRY_MS, "02 section 7.3 step 7: every 60 s")
        orderIs(order, OrderStatus.COMPLETED)
        assertEquals(PaymentEventStatus.PROCESSED, events().single().status)
    }

    @Test
    fun `a failure of step 6 is FAILED with a schedule, the gateway's redelivery takes the key over and applies the fact`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val flaky = FlakyAttempts(attempts)

        dispatcher = dispatcherOver(flaky)

        val (order, attempt) = pending()

        fake.onInbound = { ok(listOf(paid(attempt)), "evt_flaky", HttpReply.text("PROVIDER-OK")) }
        flaky.failures = 1

        val failed = dispatcher.handle(call())

        assertEquals(500, failed.status, "the gateway is told to deliver again")
        orderIs(order, OrderStatus.PENDING)

        val row = events().single()

        assertEquals(PaymentEventStatus.FAILED, row.status)
        assertEquals("e:evt_flaky", row.eventKey)
        assertNotNull(row.nextAttemptAt)
        assertTrue(row.error!!.contains("connection lost"))
        assertEquals("{}", row.body, "verbatim for the retry")

        val redelivery = dispatcher.handle(call())

        assertEquals(200, redelivery.status)
        assertEquals("PROVIDER-OK", String(redelivery.body))
        orderIs(order, OrderStatus.COMPLETED)

        val rows = events()

        assertEquals(PaymentEventStatus.SUPERSEDED, rows.first().status)
        assertEquals("e:evt_flaky:${rows.first().id}", rows.first().eventKey)
        assertEquals(PaymentEventStatus.PROCESSED, rows.last().status)
        assertEquals("e:evt_flaky", rows.last().eventKey)
        assertEquals(1, completionsOf(order.id))
    }

    @Test
    fun `without a redelivery the retry job applies a failed request after its backoff, then nothing is stuck (I18)`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val flaky = FlakyAttempts(attempts)

        dispatcher = dispatcherOver(flaky)

        val (order, attempt) = pending()
        val job = InboundEventRetryJob(dispatcher, store, w.clock)

        fake.onInbound = { ok(listOf(paid(attempt)), "evt_job") }
        flaky.failures = 2

        assertEquals(500, dispatcher.handle(call()).status)
        assertEquals(0, job.runOnce(), "not due yet")

        w.clock.advance(2 * 60_000)

        assertEquals(1, job.runOnce(), "run 2 fails again")
        assertEquals(PaymentEventStatus.FAILED, events().single().status)
        assertEquals(2, events().single().attempts)
        assertTrue(events().single().nextAttemptAt!! > w.clock.now(), "the backoff doubled")
        orderIs(order, OrderStatus.PENDING)

        w.clock.advance(3 * 60_000)

        assertEquals(1, job.runOnce())
        orderIs(order, OrderStatus.COMPLETED)
        assertEquals(PaymentEventStatus.PROCESSED, events().single().status)
        assertEquals(3, events().single().attempts)
        assertEquals(0, job.runOnce())
        assertEquals(1, completionsOf(order.id))
    }

    // ======================================================================================== runtime state, replay

    @Test
    fun `a degraded market keeps the request DEFERRED and answers 503, the panel replay completes it later`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (order, attempt) = pending()

        fake.onInbound = { ok(listOf(paid(attempt)), "evt_deg") }
        state = MarketRuntime.State.DEGRADED

        assertEquals(503, dispatcher.handle(call()).status)
        orderIs(order, OrderStatus.PENDING)
        assertTrue(fake.calls(FakePaymentProvider.Op.INBOUND).isEmpty(), "the provider is not run")

        val row = events().single()

        assertEquals(PaymentEventStatus.DEFERRED, row.status)
        assertEquals(0, row.attempts)
        assertEquals("{}", row.body)

        state = MarketRuntime.State.READY

        val replay = dispatcher.replay(row.id)

        assertTrue(replay is ReplayResult.Done, "$replay")
        orderIs(order, OrderStatus.COMPLETED)
        assertEquals(PaymentEventStatus.PROCESSED, events().single().status)
        assertEquals(1, events().single().attempts)
        assertTrue(dispatcher.replay(row.id) is ReplayResult.InvalidState, "a processed row is not replayable")
    }

    @Test
    fun `a method that is disabled or not configured still receives every inbound request`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (order, attempt) = pending()

        sql("UPDATE `pano_market_payment_method` SET `enabled` = 0 WHERE `methodId` = 'fake'")
        fake.onInbound = { ok(listOf(paid(attempt)), "evt_disabled") }

        assertEquals(200, dispatcher.handle(call()).status)
        orderIs(order, OrderStatus.COMPLETED)

        // a handshake that arrives before any settings exist: no method row at all
        sql("DELETE FROM `pano_market_payment_method` WHERE `methodId` = 'fake'")
        fake.onInbound = { InboundResult.ignored(HttpReply.text("handshake-ok")) }

        val handshake = dispatcher.handle(call())

        assertEquals(200, handshake.status)
        assertEquals("handshake-ok", String(handshake.body))
        assertEquals(PaymentEventStatus.PROCESSED, events().last().status)
    }

    @Test
    fun `lastInboundAt of the method row follows every verified webhook and notification`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (_, attempt) = pending()

        assertNull(w.paymentMethods.getByMethodId("fake", pool)!!.lastInboundAt)

        fake.onInbound = { ok() }
        w.clock.advance(5_000)
        dispatcher.handle(call(InboundKind.RETURN, attempt))

        assertNull(w.paymentMethods.getByMethodId("fake", pool)!!.lastInboundAt, "a browser return is not an inbound notification")

        dispatcher.handle(call(InboundKind.NOTIFY, attempt))

        assertEquals(w.clock.now(), w.paymentMethods.getByMethodId("fake", pool)!!.lastInboundAt)
    }

    // ====================================================================================== the event store itself

    @Test
    fun `the event store has one holder per key, never revives a superseded row, claims a retry once and selects only what is due`(): Unit = runBlocking {
        val now = w.clock.now()

        fun row(key: String, status: PaymentEventStatus = PaymentEventStatus.RECEIVED, provider: String = "fake", createdAt: Long = now, attempts: Int = 1, nextAttemptAt: Long? = null) =
            MarketPaymentEvent(providerId = provider, channel = "WEBHOOK", eventKey = key, status = status, attempts = attempts, nextAttemptAt = nextAttemptAt, headers = "{}", body = "{}", createdAt = createdAt, updatedAt = createdAt)

        fun settle(status: PaymentEventStatus) = Settlement(status, true, null, null, null, null, null, 200, null, 1, now, null, now)

        val a = store.insert(row("r:a"))
        val b = store.insert(row("r:b"))
        val other = store.insert(row("r:c", provider = "other"))

        // one holder per (provider, key); another provider may use the same key
        assertTrue(store.claimKey(a, "e:k", now))
        assertFalse(store.claimKey(b, "e:k", now), "uq_event: the key has one holder")
        assertTrue(store.claimKey(other, "e:k", now))
        assertEquals(a, store.byKey("fake", "e:k")!!.id)
        assertEquals(other, store.byKey("other", "e:k")!!.id)
        assertNull(store.byKey("fake", "e:none"))

        store.bumpDuplicates(a, now)
        store.bumpDuplicates(a, now)

        assertEquals(2, store.get(a)!!.duplicateCount)

        // the takeover is one conditional statement: a row that changed since it was read is not taken
        val read = store.get(a)!!

        assertTrue(store.settle(a, settle(PaymentEventStatus.FAILED)))
        assertFalse(store.supersede(read, null, null, now), "the row is FAILED now, the read was RECEIVED")
        assertTrue(store.supersede(store.get(a)!!, "{}", "redacted", now))

        val superseded = store.get(a)!!

        assertEquals(PaymentEventStatus.SUPERSEDED, superseded.status)
        assertEquals("e:k:$a", superseded.eventKey)
        assertEquals("redacted", superseded.body)
        assertFalse(store.settle(a, settle(PaymentEventStatus.PROCESSED)), "a late run never brings a superseded row back")
        assertEquals(PaymentEventStatus.SUPERSEDED, store.get(a)!!.status)
        assertTrue(store.claimKey(b, "e:k", now), "the key is free for the redelivery")

        // a retry is claimed by exactly one of two runners
        val failed = store.insert(row("r:f", PaymentEventStatus.FAILED, nextAttemptAt = now - 1))
        val seen = store.get(failed)!!
        val claims = Race.run(2) { store.claimRetry(seen, now, 60_000) }

        assertEquals(1, claims.count { it.getOrThrow() }, "one claim wins")
        assertEquals(2, store.get(failed)!!.attempts)
        assertEquals(now + 60_000, store.get(failed)!!.nextAttemptAt, "the lease")

        // what is due: FAILED with a due schedule, RECEIVED older than the stale limit whose lease is over; never a young one, a spent one, or another status
        val dueFailed = store.insert(row("r:df", PaymentEventStatus.FAILED, nextAttemptAt = now - 1))
        val notDue = store.insert(row("r:nd", PaymentEventStatus.FAILED, nextAttemptAt = now + 1000))
        val staleReceived = store.insert(row("r:sr", createdAt = now - 120_000))
        val youngReceived = store.insert(row("r:yr", createdAt = now - 5_000))
        val spent = store.insert(row("r:sp", PaymentEventStatus.FAILED, attempts = 10, nextAttemptAt = now - 1))
        val deferred = store.insert(row("r:de", PaymentEventStatus.DEFERRED, attempts = 0))
        val leased = store.insert(row("r:le", createdAt = now - 120_000, nextAttemptAt = now + 30_000))
        val exhausted = store.insert(row("r:ex", createdAt = now - 120_000, attempts = 10))
        val dueIds = store.due(now, now - InboundDispatcher.STALE_RECEIVED_MS, 10, 50).map { it.id }.toSet()

        assertEquals(setOf(dueFailed, staleReceived), dueIds, "failed $failed is on its lease, $notDue $youngReceived $spent $deferred $leased $exhausted are not due")
        assertEquals(1, store.exhaust(now, now - InboundDispatcher.STALE_RECEIVED_MS, 10), "a stale RECEIVED row that used up its runs")
        assertEquals(PaymentEventStatus.FAILED, store.get(exhausted)!!.status)
        assertNull(store.get(exhausted)!!.nextAttemptAt)
        assertEquals(PaymentEventStatus.RECEIVED, store.get(youngReceived)!!.status)

        // settle writes the redacted raw request only when it is given one, and keeps the ids the row has when none is given
        assertTrue(store.settle(dueFailed, Settlement(PaymentEventStatus.PROCESSED, true, "Succeeded", 5, 6, null, null, 200, null, 3, now, null, now)))
        assertTrue(store.settle(dueFailed, Settlement(PaymentEventStatus.PROCESSED, null, null, null, null, null, null, 200, null, 3, now, null, now, com.panomc.plugins.market.routes.api.payment.RawRewrite("{\"x\":1}", null))))

        val settled = store.get(dueFailed)!!

        assertEquals(5L, settled.paymentId)
        assertEquals(6L, settled.orderId)
        assertEquals("Succeeded", settled.eventTypes)
        assertEquals(true, settled.verified)
        assertTrue(settled.headers!!.contains("\"x\""))
        assertNull(settled.body)

        // this test left rows that I18 rightly calls stuck (a stale RECEIVED, a due FAILED): they are not part of any scenario
        sql("DELETE FROM `pano_market_payment_event`")
    }

    // ===================================================================================== redaction and unknown kinds

    @Test
    fun `settled rows are redacted in place, a row that can still be replayed keeps the raw request`(): Unit = runBlocking {
        val secret = "sk_live_0123456789SECRET"

        ph.lookup.add(SecretFake(fake))
        fx.paymentMethod("fake", settings = com.google.gson.JsonObject().also { it.addProperty("apiKey", ph.cipher.encrypt(secret)) })

        val (order, attempt) = pending()
        val headers = mapOf("content-type" to listOf("application/json"), "x-api-key" to listOf(secret), "authorization" to listOf("Bearer abc"))
        val body = "{\"card\":\"4111 1111 1111 1111\",\"key\":\"$secret\"}"

        fake.onInbound = { throw IllegalStateException("down: $secret") }

        assertEquals(500, dispatcher.handle(call(body = body, headers = headers, query = "sig=$secret")).status)

        val failed = events().single()

        assertEquals(body, failed.body, "a failed row is verbatim: it is re-run from it")
        assertTrue(failed.headers!!.contains(secret) && failed.headers!!.contains(":query"))
        assertFalse(failed.error!!.contains(secret), "the error text is redacted")
        assertFalse(failed.url!!.contains(secret), "the stored address masks the sensitive query values: ${failed.url}")

        w.clock.advance(2 * 60_000)
        fake.onInbound = { ok(listOf(paid(attempt)), "evt_red") }
        assertEquals(1, InboundEventRetryJob(dispatcher, store, w.clock).runOnce())

        val settled = events().single()

        assertEquals(PaymentEventStatus.PROCESSED, settled.status)
        assertFalse(settled.body!!.contains(secret), settled.body)
        assertFalse(settled.body!!.contains("4111 1111 1111 1111"), settled.body)
        assertFalse(settled.headers!!.contains(secret) || settled.headers!!.contains("Bearer abc") || settled.headers!!.contains(":query"), settled.headers)
        orderIs(order, OrderStatus.COMPLETED)
    }

    @Test
    fun `a refund event nobody applies yet is FAILED and retried, an installed sink applies it from the redelivery`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (_, attempt) = pending()
        val refund = PaymentEvent.RefundUpdated(PaymentTarget.Reference(attempt.reference), RefundState.SUCCEEDED, Money(300, "EUR"))

        fake.onInbound = { ok(listOf(refund), "evt_refund") }

        assertEquals(500, dispatcher.handle(call()).status)
        assertEquals(PaymentEventStatus.FAILED, events().single().status)
        assertTrue(events().single().error!!.startsWith("EventNotHandled"))

        val seen = CopyOnWriteArrayList<Pair<Long?, String?>>()

        sink = PaymentEventSink { _, resolved, ctx -> seen += resolved?.id to ctx.eventKey }

        assertEquals(200, dispatcher.handle(call()).status)
        assertEquals(listOf(attempt.id to "evt_refund"), seen)
        assertEquals(PaymentEventStatus.PROCESSED, events().last().status)
    }

    @Test
    fun `ReferencesUpdated attaches ids and provider data without touching the status, a transaction id of another attempt is dropped`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (first, firstAttempt) = pending()
        val (second, secondAttempt) = pending()

        fake.onInbound = {
            ok(
                listOf(
                    PaymentEvent.ReferencesUpdated(PaymentTarget.Reference(firstAttempt.reference)).also { e ->
                        e.gatewayTransactionId = "txn_77"
                        e.gatewayRefs = mapOf("session" to "cs_9")
                        e.providerData = JsonObject().put("step", 2)
                    }
                )
            )
        }
        dispatcher.handle(call())

        val a = ph.attempts(first.id).single()

        assertEquals("txn_77", a.gatewayTransactionId)
        assertEquals("cs_9", JsonObject(a.gatewayRefs!!).getString("session"))
        assertEquals(2, JsonObject(ph.cipher.decrypt(a.providerData!!)!!).getInteger("step"))
        assertEquals(PaymentStatus.PENDING, a.status)
        orderIs(first, OrderStatus.PENDING)

        // the same transaction id for another attempt (uq_provider_txn): the rest is kept, the id is dropped, nothing fails
        fake.onInbound = {
            ok(
                listOf(
                    PaymentEvent.ReferencesUpdated(PaymentTarget.Reference(secondAttempt.reference)).also { e ->
                        e.gatewayTransactionId = "txn_77"
                        e.gatewayRefs = mapOf("session" to "cs_10")
                    }
                )
            )
        }

        assertEquals(200, dispatcher.handle(call()).status)

        val b = ph.attempts(second.id).single()

        assertNull(b.gatewayTransactionId)
        assertEquals("cs_10", JsonObject(b.gatewayRefs!!).getString("session"))
        assertTrue(events().all { it.status == PaymentEventStatus.PROCESSED })
    }

    // ============================================================================== routes: return, notify, page, context

    @Test
    fun `a return only ever redirects to the order page and never carries the access token, a notification finds its attempt by the token`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (order, attempt) = pending()

        fake.onInbound = { ok(reply = HttpReply.redirect("https://evil.example/x")) }

        val back = dispatcher.handle(call(InboundKind.RETURN, attempt, outcome = ReturnOutcome.CANCEL))

        assertEquals(303, back.status)
        assertEquals("https://shop.example/store/order/${order.publicId}?return=cancel", back.headers["Location"])
        assertFalse(back.headers["Location"]!!.contains(order.accessToken!!))
        assertEquals(PaymentStatus.PENDING, ph.attempts(order.id).single().status, "a browser return is a hint, not a fact")

        fake.onInbound = { request -> ok(listOf(paid(attempt)), null).also { assertEquals(attempt.id, request.attempt!!.id) } }

        assertEquals(200, dispatcher.handle(call(InboundKind.NOTIFY, attempt)).status)
        orderIs(order, OrderStatus.COMPLETED)

        val row = events().last()

        assertEquals(attempt.id, row.paymentId)
        assertEquals(order.id, row.orderId)
        assertFalse(row.url!!.contains(attempt.token), row.url)

        assertEquals(404, dispatcher.handle(call(InboundKind.NOTIFY, attempt.let { MarketPayment(id = 0, orderId = 0, providerId = "fake", token = "f".repeat(40)) })).status)
    }

    @Test
    fun `a gateway Html start result is served from the attempt page in a sandboxed opaque origin, a FormPost from market's own form (V-14)`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val pages = AttemptPageService(attempts, ph.cipher, w.ids)

        fake.onStart = {
            StartPaymentResult.Html("<html><body><script>fetch('/api/market/me/summary', {credentials: 'include'})</script></body></html>").also { r ->
                r.inlineScript = true
                r.scriptOrigins = listOf("https://js.gateway.example")
                r.frameOrigins = listOf("https://3ds.gateway.example")
            }
        }

        val (order, attempt) = pending()
        val start = ph.attempts(order.id).single()

        assertEquals("HTML", start.startKind)
        assertTrue(JsonObject(ph.payments.served(start, pool)!!.encode()).getString("url").endsWith("/api/market/payments/attempts/${attempt.token}/page"))

        val page = pages.page(attempt.token) as AttemptPageResult.Page
        val csp = page.headers.getValue("Content-Security-Policy")

        assertTrue(csp.startsWith("sandbox allow-scripts allow-forms allow-top-navigation allow-popups; default-src 'none'; script-src https://js.gateway.example 'nonce-"), csp)
        assertFalse(csp.contains("allow-same-origin"), "an opaque origin: the fetch carries no cookie and reads nothing of the buyer's session")
        assertTrue(csp.contains("frame-src https://3ds.gateway.example;") && csp.contains("connect-src 'none';"), csp)
        assertEquals("no-store", page.headers["Cache-Control"])
        assertEquals("nosniff", page.headers["X-Content-Type-Options"])
        assertEquals("no-referrer", page.headers["Referrer-Policy"])
        assertTrue(Regex("<script nonce=\"[0-9a-f]{32}\">fetch").containsMatchIn(page.body), page.body)

        // once the attempt is closed the page is not served any more
        ph.succeed(order.id, ph.attempts(order.id).single())

        val closed = pages.page(attempt.token)

        assertEquals(order.publicId, (closed as AttemptPageResult.ToOrderPage).publicId)

        // a FORM_POST
        fake.onStart = { StartPaymentResult.FormPost("https://pay.gateway.example/post", mapOf("amount" to "10.00", "x\"y" to "<b>")) }

        val (_, form) = pending()
        val formPage = pages.page(form.token) as AttemptPageResult.Page

        assertEquals("default-src 'none'; script-src 'nonce-${Regex("nonce-([0-9a-f]+)").find(formPage.headers.getValue("Content-Security-Policy"))!!.groupValues[1]}'; form-action https://pay.gateway.example; base-uri 'none'; frame-ancestors 'none'", formPage.headers["Content-Security-Policy"])
        assertTrue(formPage.body.contains("name=\"x&quot;y\" value=\"&lt;b&gt;\""), formPage.body)
        assertEquals(AttemptPageResult.NotFound, pages.page("f".repeat(40)))
    }

    @Test
    fun `ctx payments reads this provider's own attempts from the database and the attempt lock is reentrant inside handleInbound`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (_, own) = pending()
        val (_, other) = pending()

        sql("UPDATE `pano_market_payment` SET `gatewayTransactionId` = 'txn_probe', `gatewayRefs` = '{\"session\":\"cs_probe\"}' WHERE `id` = ?", own.id)
        sql("UPDATE `pano_market_payment` SET `providerId` = 'someone-else' WHERE `id` = ?", other.id)

        val probe = LookupProbe(fake, { other.id }, { other.reference })

        ph.lookup.add(probe)

        assertEquals(200, dispatcher.handle(call(InboundKind.NOTIFY, own)).status)

        for (list in listOf(probe.byId, probe.byReference, probe.byTransaction, probe.byRef)) {
            val view = list.single()

            assertNotNull(view)
            assertEquals(own.id, view!!.id)
            assertEquals(own.reference, view.reference)
        }

        assertTrue(probe.foreign.all { it == null }, "an attempt of another provider is not visible")
        assertEquals(1, probe.reentered.get())
        assertEquals(0, locks.inUse())
        assertEquals(PaymentEventStatus.PROCESSED, events().single().status)
    }
}
