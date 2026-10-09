package com.panomc.plugins.market.service

import com.panomc.platform.model.Paging
import com.panomc.plugins.market.support.assertOutOfRange
import com.panomc.platform.model.PageRequest
import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.db.model.MarketPaymentEvent
import com.panomc.plugins.market.db.model.PaymentEventDirection
import com.panomc.plugins.market.db.model.PaymentEventStatus
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.error.InvalidState
import com.panomc.plugins.market.error.PaymentProviderError
import com.panomc.plugins.market.error.StatusQueryNotSupported
import com.panomc.plugins.market.provider.ProviderLookup
import com.panomc.plugins.market.routes.api.payment.AttemptLocks
import com.panomc.plugins.market.routes.api.payment.AttemptLookup
import com.panomc.plugins.market.routes.api.payment.AttemptPaymentContext
import com.panomc.plugins.market.routes.api.payment.DbInboundEventStore
import com.panomc.plugins.market.routes.api.payment.InboundCall
import com.panomc.plugins.market.routes.api.payment.InboundDispatcher
import com.panomc.plugins.market.routes.api.payment.PaymentEventApplier
import com.panomc.plugins.market.routes.api.payment.PaymentEventSink
import com.panomc.plugins.market.routes.api.payment.PaymentInboundAttempts
import com.panomc.plugins.market.routes.api.payment.RegistryInboundProviders
import com.panomc.plugins.market.routes.panel.payment.PaymentEventAdmin
import com.panomc.plugins.market.routes.panel.payment.parsePaymentEventQuery
import com.panomc.plugins.market.runtime.MarketRuntime
import com.panomc.plugins.market.spi.common.HttpReply
import com.panomc.plugins.market.spi.common.InboundKind
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.payment.InboundResult
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentQueryResult
import com.panomc.plugins.market.spi.payment.PaymentTarget
import com.panomc.plugins.market.spi.testkit.TestContexts
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import com.panomc.plugins.market.support.ErrorBodies
import com.panomc.plugins.market.util.MarketPaths

/**
 * The panel side of the raw provider traffic on a real MariaDB (MK-171; 04 section 7, 02 section 7.3, 11 IN-4 / IN-5 / 14.5): the list and the per-payment
 * timeline with the row shape, the raw tier (`body` / `headers` / `url` only with `SET`, redacted again on the way out), the default list of rows that need a
 * look, the replay through the real inbound dispatcher (only `DEFERRED`, `FAILED` and a `RECEIVED` row older than 60 s; everything else 409
 * `INVALID_STATE`) and the provider query.
 */
class PaymentEventAdminIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var ph: PaymentHarness
    private lateinit var store: DbInboundEventStore
    private lateinit var dispatcher: InboundDispatcher
    private lateinit var admin: PaymentEventAdmin
    private lateinit var contexts: PaymentContexts
    private val locks = AttemptLocks()
    private val vertx: Vertx = Vertx.vertx()

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    @BeforeEach
    fun freshState() {
        runBlocking { resetState() }
        w = TestWiring(pool)
        ph = PaymentHarness(w, vertx)
        store = DbInboundEventStore(w.paymentEvents) { pool }

        val attempts = PaymentInboundAttempts(w.payments, w.orders, ph.payments, ph.cipher, ph.db, ph.locks, w.clock) { pool }
        contexts = PaymentContexts { provider, settings, testMode ->
            AttemptPaymentContext(TestContexts.payment(provider.id, settings, vertx, testMode), AttemptLookup(provider.id, w.payments, w.orders, ph.cipher) { pool }, locks)
        }
        val lookup: ProviderLookup = ph.lookup
        val providers = RegistryInboundProviders(lookup, w.paymentMethods, ph.cipher, contexts, { ph.h.config.toConfig() }, { pool }, { emptySet() })

        dispatcher = InboundDispatcher(
            store, attempts, providers, PaymentEventApplier(attempts) { event, attempt, ctx -> PaymentEventSink.UNHANDLED.apply(event, attempt, ctx) }, locks, w.clock, w.ids,
            { "https://shop.example" }, { MarketRuntime.State.READY }
        )
        admin = PaymentEventAdmin({ w.orders.prefix() }, w.payments, w.orders, { id -> dispatcher.replay(id) }, { order, attempt, client -> ph.payments.reconcileQuery(order, attempt, client) })
    }

    override suspend fun assertInvariants() {}

    private val fx get() = w.fixtures
    private val fake get() = ph.fake

    private suspend fun pending(price: Long = 1000): Pair<MarketOrder, MarketPayment> {
        val result = ph.h.checkout(ph.h.body("items" to listOf(ph.h.line(fx.product(price = price))), "paymentMethodId" to "fake"))
        val order = ph.order(result.order.getString("publicId"))

        return order to ph.attempts(order.id).single()
    }

    private fun paid(a: MarketPayment) = PaymentEvent.Succeeded(PaymentTarget.Reference(a.reference), Money(a.amount, a.currency))

    private fun call(
        attempt: MarketPayment? = null, body: String = "{}", headers: Map<String, List<String>> = mapOf("content-type" to listOf("application/json")), kind: InboundKind = InboundKind.WEBHOOK
    ) = InboundCall(
        kind, "fake", "default", if (kind == InboundKind.WEBHOOK) null else attempt!!.token, null, null, "POST",
        "${MarketPaths.SITE_ROOT}/payments/fake/" + if (kind == InboundKind.WEBHOOK) "webhook" else "notify/${attempt!!.token}", null, emptyMap(), headers, headers["content-type"]?.firstOrNull(),
        body.toByteArray(), null, "203.0.113.9", w.clock.now()
    )

    private val window = PageRequest(1, 50)

    private suspend fun listed(raw: Boolean = false, statuses: Set<PaymentEventStatus> = emptySet(), provider: String? = null) = admin.list(statuses, provider, window, raw, pool)

    private fun ids(page: com.panomc.plugins.market.routes.panel.payment.PaymentEventPage) = page.rows.map { it.getLong("id") }

    private suspend fun rowOf(id: Long): MarketPaymentEvent = store.get(id)!!

    /** The id of the only row the last [dispatcher] call stored. */
    private suspend fun lastId(): Long =
        com.panomc.plugins.market.support.MarketTestDb.sql(pool, "SELECT MAX(`id`) AS i FROM `${com.panomc.plugins.market.support.MarketTestDb.TABLE_PREFIX}market_payment_event`").single().getLong("i")

    private fun errorOf(block: suspend () -> Unit): Throwable = runCatching { runBlocking { block() } }.exceptionOrNull() ?: throw AssertionError("expected an error")

    // ================================================================================================== the row shape and the raw tier

    @Test
    fun `an attempt's events come oldest first in the pinned shape, without body, headers or url below SET`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (order, attempt) = pending()

        fake.onInbound = { InboundResult.accepted(HttpReply.text("OK"), listOf(paid(attempt)), "evt_1") }
        assertEquals(200, dispatcher.handle(call(body = "{\"id\":\"evt_1\"}")).status)

        val page = admin.forPayment(attempt.id, window, false, pool)

        assertEquals(1L, page.count)

        val row = page.rows.single()

        assertEquals(
            setOf(
                "id", "providerId", "direction", "channel", "eventKey", "paymentId", "orderId", "verified", "status", "eventTypes", "responseStatus", "error", "remoteIp", "attempts",
                "duplicateCount", "createdAt", "url", "headers", "body"
            ),
            row.fieldNames()
        )
        assertEquals("fake", row.getString("providerId"))
        assertEquals("IN", row.getString("direction"))
        assertEquals("WEBHOOK", row.getString("channel"))
        assertEquals("e:evt_1", row.getString("eventKey"))
        assertEquals(attempt.id, row.getLong("paymentId"))
        assertEquals(order.id, row.getLong("orderId"))
        assertEquals(true, row.getBoolean("verified"))
        assertEquals("PROCESSED", row.getString("status"))
        assertEquals(200, row.getInteger("responseStatus"))
        assertEquals("203.0.113.9", row.getString("remoteIp"))
        assertTrue(row.getJsonArray("eventTypes").size() >= 1, "the applied event types")

        for (key in listOf("body", "headers", "url")) assertNull(row.getValue(key), "$key needs the raw tier")
    }

    @Test
    fun `with the raw tier body, headers and url come back, credentials and card numbers redacted again`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (_, attempt) = pending()

        // a request that the pipeline keeps verbatim: it ends FAILED (the provider throws), so the stored row still holds the raw text
        fake.onInbound = { throw IllegalStateException("boom") }

        dispatcher.handle(
            call(
                attempt = attempt, kind = InboundKind.NOTIFY,
                body = "{\"card_number\":\"4111 1111 1111 1111\",\"password\":\"hunter2\",\"note\":\"paid by 4242424242424242\"}",
                headers = mapOf("content-type" to listOf("application/json"), "authorization" to listOf("Bearer sk_live_123"), "x-trace" to listOf("t-1"))
            )
        )

        val stored = rowOf(lastId())

        assertEquals(PaymentEventStatus.FAILED, stored.status)
        assertTrue(stored.body!!.contains("hunter2"), "the stored row is verbatim while replayable")

        val below = admin.forPayment(attempt.id, window, false, pool).rows.single()
        val raw = admin.forPayment(attempt.id, window, true, pool).rows.single()

        assertNull(below.getValue("body"))
        assertNull(below.getValue("headers"))

        val body = raw.getString("body")

        assertFalse(body.contains("hunter2"), body)
        assertFalse(body.contains("4111 1111 1111 1111"), body)
        assertFalse(body.contains("4242424242424242"), body)
        assertTrue(body.contains("4242"), "the last four digits of a card number in free text stay: $body")

        val headers = raw.getJsonObject("headers")

        assertFalse(headers.encode().contains("sk_live_123"), headers.encode())
        assertEquals("[REDACTED]", headers.getJsonArray("authorization").getString(0))
        assertEquals("t-1", headers.getJsonArray("x-trace").getString(0))
        assertNotNull(raw.getString("url"))
    }

    @Test
    fun `a payment that does not exist is a 404, paging is by pageSize and the total count is exact`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (_, attempt) = pending()

        fake.onInbound = { InboundResult.accepted(HttpReply.text("OK"), emptyList(), null) }

        assertThrows(NotFound::class.java) { runBlocking { admin.forPayment(attempt.id + 999, window, false, pool) } }

        repeat(5) { dispatcher.handle(call(attempt = attempt, kind = InboundKind.NOTIFY, body = "{\"n\":$it}")) }

        val pages = (1..3).map { admin.forPayment(attempt.id, PageRequest(it, 2), false, pool) }

        assertEquals(5L, pages[0].count)
        assertEquals(3L, Paging.totalPages(pages[0].count, 2))
        assertEquals(listOf(2, 2, 1), pages.map { it.rows.size })

        val all = pages.flatMap { p -> p.rows.map { it.getLong("id") } }

        assertEquals(all.sorted(), all, "oldest first across the pages")
        assertEquals(5, all.toSet().size)
    }

    @Test
    fun `a provider secret in the stored body, a header, the replay metadata, the error and the url is redacted on the read path`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val secret = "merchant-key-Zq81xT"
        val (_, attempt) = pending()

        // the redactor of the provider is built from its secret settings and state values, exactly as the dispatcher builds it
        val providers = RegistryInboundProviders(ph.lookup, w.paymentMethods, ph.cipher, contexts, { ph.h.config.toConfig() }, { pool }, { setOf(secret) })
        val withSecrets = PaymentEventAdmin(
            { w.orders.prefix() }, w.payments, w.orders, { id -> dispatcher.replay(id) }, { order, a, client -> ph.payments.reconcileQuery(order, a, client) },
            { id -> (providers.resolve(id, true) as? com.panomc.plugins.market.routes.api.payment.ProviderAccess.Ready)?.redactor ?: com.panomc.plugins.market.core.abuse.Redactor() }
        )

        // a row kept verbatim (FAILED): the provider echoes its key in the body, a header, the form and the error; the url carries it as a path token
        val id = w.paymentEvents.add(
            MarketPaymentEvent(
                providerId = "fake", channel = "NOTIFY", eventKey = "r:secret", paymentId = attempt.id, status = PaymentEventStatus.FAILED,
                body = "{\"merchant\":\"$secret\",\"ok\":true}", headers = JsonObject().put("x-merchant", io.vertx.core.json.JsonArray().add("k=$secret")).put(":form", "key=$secret&amount=5").encode(),
                url = "${MarketPaths.SITE_ROOT}/payments/fake/notify/abcdefghij0123456789klmno?ref=$secret", error = "gateway said: $secret rejected",
                createdAt = w.clock.now(), updatedAt = w.clock.now()
            ),
            pool
        )!!

        val plain = admin.forPayment(attempt.id, window, true, pool).rows.single { it.getLong("id") == id }

        // the panel without the provider's secrets (the old wiring) would have let it through: this is what the fix prevents
        assertTrue(plain.encode().contains(secret), "the plain redactor knows no provider secret")

        val rows = withSecrets.forPayment(attempt.id, window, true, pool).rows.single { it.getLong("id") == id }

        assertFalse(rows.encode().contains(secret), rows.encodePrettily())
        assertTrue(rows.getString("body").contains("[REDACTED]"))
        assertTrue(rows.getString("error").contains("[REDACTED]"))
        assertTrue(rows.getJsonObject("headers").getJsonArray("x-merchant").getString(0).contains("[REDACTED]"))
        assertFalse(rows.getJsonObject("headers").getString(":form").contains(secret))

        // the same on the default list, and the url passes through the path-token rule as well
        val listedRow = withSecrets.list(emptySet(), null, window, true, pool).rows.single { it.getLong("id") == id }

        assertFalse(listedRow.encode().contains(secret), listedRow.encodePrettily())
        assertEquals("${MarketPaths.SITE_ROOT}/payments/fake/notify/abcdef\u2026?ref=[REDACTED]", listedRow.getString("url"), "path token shortened, secret removed")

        // below the raw tier nothing of body / headers / url is there, and the error is redacted too
        val below = withSecrets.list(emptySet(), null, window, false, pool).rows.single { it.getLong("id") == id }

        assertNull(below.getValue("body"))
        assertFalse(below.encode().contains(secret))
    }

    // ================================================================================================== the list

    @Test
    fun `the list shows DEFERRED, FAILED and REJECTED inbound rows newest first and nothing else, filters narrow it`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (_, attempt) = pending()

        fake.onInbound = { InboundResult.accepted(HttpReply.text("OK"), emptyList(), null) }
        dispatcher.handle(call(body = "{\"a\":1}")) // PROCESSED

        fake.onInbound = { InboundResult.rejected(HttpReply.text("bad", 400), "INVALID_SIGNATURE") }
        dispatcher.handle(call(body = "{\"a\":2}"))
        val rejected = lastId()

        fake.onInbound = { throw IllegalStateException("boom") }
        dispatcher.handle(call(body = "{\"a\":3}"))
        val failed = lastId()

        ph.lookup.remove("fake")
        dispatcher.handle(call(body = "{\"a\":4}"))
        val deferred = lastId()

        // a RECEIVED row, a DUPLICATE and an outbound row are no business of the list
        val received = w.paymentEvents.add(
            MarketPaymentEvent(providerId = "fake", channel = "WEBHOOK", eventKey = "r:received", status = PaymentEventStatus.RECEIVED, createdAt = w.clock.now(), updatedAt = w.clock.now()), pool
        )!!
        val outbound = w.paymentEvents.add(
            MarketPaymentEvent(providerId = "fake", direction = PaymentEventDirection.OUT, channel = "START", eventKey = "o1", status = PaymentEventStatus.FAILED, createdAt = w.clock.now(), updatedAt = w.clock.now()),
            pool
        )!!

        val all = listed()

        assertEquals(listOf(deferred, failed, rejected), ids(all), "newest first")
        assertFalse(ids(all).contains(received))
        assertFalse(ids(all).contains(outbound))
        assertEquals(3L, all.count)

        assertEquals(listOf(failed), ids(listed(statuses = setOf(PaymentEventStatus.FAILED))))
        assertEquals(listOf(deferred, rejected), ids(listed(statuses = setOf(PaymentEventStatus.DEFERRED, PaymentEventStatus.REJECTED))))
        assertEquals(3L, listed(provider = "fake").count)
        assertEquals(0L, listed(provider = "other").count)
        assertEquals(PaymentEventStatus.REJECTED.name, listed().rows.last().getString("status"))
        assertTrue(attempt.id > 0)
    }

    @Test
    fun `the query parameters are parsed strictly`() {
        val ok = parsePaymentEventQuery("FAILED, DEFERRED", "fake", "2", "20")

        assertEquals(setOf(PaymentEventStatus.FAILED, PaymentEventStatus.DEFERRED), ok.statuses)
        assertEquals("fake", ok.providerId)
        assertEquals(2 to 20, ok.window.number to ok.window.size)
        assertEquals(emptySet<PaymentEventStatus>(), parsePaymentEventQuery(null, null, null, null).statuses)
        val blank = parsePaymentEventQuery(" ", " ", "", "").window

        assertEquals(1 to 10, blank.number to blank.size)

        for (bad in listOf("PROCESSED", "RECEIVED", "failed", "FAILED,PROCESSED", "x")) assertThrows(RequestValueException::class.java, { parsePaymentEventQuery(bad, null, null, null) }, bad)

        assertOutOfRange("page") { parsePaymentEventQuery(null, null, "0", null) }
        assertOutOfRange("pageSize") { parsePaymentEventQuery(null, null, "1", "101") }
        assertOutOfRange("page") { parsePaymentEventQuery(null, null, "abc", null) }
        assertThrows(RequestValueException::class.java) { parsePaymentEventQuery(null, "x".repeat(65), null, null) }
    }

    // ================================================================================================== replay

    private fun state(e: Throwable): String = ErrorBodies.details(e).getString("state")

    @Test
    fun `a FAILED row is replayed through the pipeline and completes the order`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (order, attempt) = pending()

        fake.onInbound = { throw IllegalStateException("database blip") }
        assertEquals(500, dispatcher.handle(call(body = "{\"id\":\"evt_f\"}")).status)

        val id = lastId()

        assertEquals(PaymentEventStatus.FAILED, rowOf(id).status)
        assertEquals(OrderStatus.PENDING, ph.order(order.id).status)

        fake.onInbound = { InboundResult.accepted(HttpReply.text("OK"), listOf(paid(attempt)), "evt_f") }

        val replayed = admin.replay(id)

        assertEquals("PROCESSED", replayed.status)
        assertEquals("fake", replayed.providerId)
        assertEquals(JsonObject().put("status", "PROCESSED"), replayed.toJson())
        assertEquals(OrderStatus.COMPLETED, ph.order(order.id).status)
        assertEquals(PaymentStatus.SUCCEEDED, ph.attempts(order.id).single().status)

        // and it cannot be replayed again
        assertEquals("PROCESSED", state(errorOf { admin.replay(id) }))
    }

    @Test
    fun `a DEFERRED row is replayed once the provider is back`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (order, attempt) = pending()

        ph.lookup.remove("fake")
        assertEquals(503, dispatcher.handle(call(body = "{\"id\":\"evt_d\"}")).status)

        val id = lastId()

        assertEquals(PaymentEventStatus.DEFERRED, rowOf(id).status)

        // still gone: the replay runs nothing and the row stays DEFERRED
        assertEquals("DEFERRED", admin.replay(id).status)

        ph.lookup.add(ph.continuable)
        fake.onInbound = { InboundResult.accepted(HttpReply.text("OK"), listOf(paid(attempt)), "evt_d") }

        assertEquals("PROCESSED", admin.replay(id).status)
        assertEquals(OrderStatus.COMPLETED, ph.order(order.id).status)
    }

    @Test
    fun `PROCESSED, DUPLICATE, REJECTED, SUPERSEDED and a young RECEIVED row are 409 INVALID_STATE with their state`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (_, attempt) = pending()

        fake.onInbound = { InboundResult.accepted(HttpReply.text("OK"), listOf(paid(attempt)), "evt_x") }
        dispatcher.handle(call(body = "{\"id\":\"evt_x\"}"))
        val processed = lastId()
        dispatcher.handle(call(body = "{\"id\":\"evt_x\"}"))
        val duplicate = lastId()

        fake.onInbound = { InboundResult.rejected(HttpReply.text("bad", 400), "INVALID_SIGNATURE") }
        dispatcher.handle(call(body = "{\"id\":\"evt_y\"}"))
        val rejected = lastId()

        val young = w.paymentEvents.add(
            MarketPaymentEvent(providerId = "fake", channel = "WEBHOOK", eventKey = "r:young", status = PaymentEventStatus.RECEIVED, createdAt = w.clock.now() - 59_000, updatedAt = w.clock.now()), pool
        )!!
        val superseded = w.paymentEvents.add(
            MarketPaymentEvent(providerId = "fake", channel = "WEBHOOK", eventKey = "r:sup", status = PaymentEventStatus.SUPERSEDED, createdAt = w.clock.now() - 999_000, updatedAt = w.clock.now()), pool
        )!!

        assertEquals("PROCESSED", rowOf(processed).status.name)
        assertEquals("DUPLICATE", rowOf(duplicate).status.name)

        val providerCalls = fake.calls(com.panomc.plugins.market.support.FakePaymentProvider.Op.INBOUND).size

        for ((id, expected) in listOf(processed to "PROCESSED", duplicate to "DUPLICATE", rejected to "REJECTED", young to "RECEIVED", superseded to "SUPERSEDED")) {
            val error = errorOf { admin.replay(id) }

            assertTrue(error is InvalidState, "event $id: $error")
            assertEquals(409, (error as InvalidState).getStatusCode(), "event $id")
            assertEquals("INVALID_STATE", error.getErrorCode())
            assertEquals(expected, state(error), "event $id")
        }

        assertEquals(providerCalls, fake.calls(com.panomc.plugins.market.support.FakePaymentProvider.Op.INBOUND).size, "the provider was not run again")
    }

    @Test
    fun `a RECEIVED row older than 60 seconds is replayable`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (order, attempt) = pending()

        fake.onInbound = { throw IllegalStateException("crash") }
        dispatcher.handle(call(body = "{\"id\":\"evt_s\"}"))

        val id = lastId()

        // a crashed run leaves RECEIVED behind: put the row back to that state and age it
        com.panomc.plugins.market.support.MarketTestDb.sql(
            pool, "UPDATE `${com.panomc.plugins.market.support.MarketTestDb.TABLE_PREFIX}market_payment_event` SET `status` = 'RECEIVED', `createdAt` = ? WHERE `id` = ?", w.clock.now() - 61_000, id
        )
        fake.onInbound = { InboundResult.accepted(HttpReply.text("OK"), listOf(paid(attempt)), "evt_s") }

        assertEquals("PROCESSED", admin.replay(id).status)
        assertEquals(OrderStatus.COMPLETED, ph.order(order.id).status)
    }

    @Test
    fun `an unknown event and an outbound row are 404`(): Unit = runBlocking {
        val out = w.paymentEvents.add(
            MarketPaymentEvent(providerId = "fake", direction = PaymentEventDirection.OUT, channel = "START", eventKey = "o2", status = PaymentEventStatus.FAILED, createdAt = 1, updatedAt = 1), pool
        )!!

        assertTrue(errorOf { admin.replay(987654) } is NotFound)
        assertTrue(errorOf { admin.replay(out) } is NotFound)
    }

    // ================================================================================================== query

    @Test
    fun `the provider query applies what the gateway reports and answers the attempt's status`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        fake.caps = PaymentCapabilities().also { it.statusQuery = true }

        val (order, attempt) = pending()

        fake.onQuery = { PaymentQueryResult.of(PaymentEvent.Succeeded(PaymentTarget.Attempt(attempt.id), Money(attempt.amount, attempt.currency))) }

        assertEquals(JsonObject().put("status", "SUCCEEDED"), admin.query(attempt.id, pool))
        assertEquals(OrderStatus.COMPLETED, ph.order(order.id).status)

        // asking again about a closed attempt changes nothing and still answers its status
        assertEquals(JsonObject().put("status", "SUCCEEDED"), admin.query(attempt.id, pool))
    }

    @Test
    fun `a provider that cannot be asked is 400 STATUS_QUERY_NOT_SUPPORTED, an unknown attempt 404, a gone provider 502`(): Unit = runBlocking {
        fx.paymentMethod("fake")

        val (_, attempt) = pending()

        assertTrue(errorOf { admin.query(attempt.id, pool) } is StatusQueryNotSupported, "statusQuery is off")
        assertTrue(errorOf { admin.query(attempt.id + 999, pool) } is NotFound)

        fake.caps = PaymentCapabilities().also { it.statusQuery = true }
        ph.lookup.remove("fake")

        val error = errorOf { admin.query(attempt.id, pool) }

        assertTrue(error is PaymentProviderError, "$error")
    }

    @Test
    fun `an unknown answer from the gateway leaves the attempt as it was`(): Unit = runBlocking {
        fx.paymentMethod("fake")
        fake.caps = PaymentCapabilities().also { it.statusQuery = true }

        val (order, attempt) = pending()

        fake.onQuery = { PaymentQueryResult.unknown() }

        assertEquals(attempt.status.name, admin.query(attempt.id, pool).getString("status"))
        assertEquals(OrderStatus.PENDING, ph.order(order.id).status)
    }
}
