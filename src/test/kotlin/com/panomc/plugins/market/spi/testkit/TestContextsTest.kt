package com.panomc.plugins.market.spi.testkit

import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.common.settingsSchema
import com.panomc.plugins.market.spi.payment.OrderLine
import io.vertx.core.Vertx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger

/** Self-test of `spi.testkit.TestContexts`, `SampleData`, the in-memory state store and the recording log. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestContextsTest {
    private val vertx: Vertx = Vertx.vertx()

    @AfterAll
    fun close() {
        vertx.close()
    }

    @Test
    fun `settings trim strings, treat blank as absent and read numbers and booleans`() {
        val s = TestContexts.settings(mapOf("a" to "  x ", "blank" to "   ", "n" to 42, "ns" to " 7 ", "bad" to "seven", "t" to true, "ts" to "TRUE", "one" to "1", "no" to "no"))
        assertEquals("x", s.string("a"))
        assertNull(s.string("blank"))
        assertNull(s.string("missing"))
        assertEquals(42L, s.long("n"))
        assertEquals(7L, s.long("ns"))
        assertNull(s.long("bad"))
        assertNull(s.long("missing"))
        assertTrue(s.boolean("t"))
        assertTrue(s.boolean("ts"))
        assertTrue(s.boolean("one"))
        assertFalse(s.boolean("no", true))
        assertTrue(s.boolean("missing", true))
        assertFalse(s.boolean("missing"))
        assertEquals("x", s.require("a"))
        val e = assertThrows<ProviderException> { s.require("blank") }
        assertEquals(ProviderErrorCode.CONFIGURATION, e.code)
        assertEquals(42, s.asJson().getInteger("n"))
        assertFalse(s.asJson().containsKey("zzz"))
    }

    @Test
    fun `default values cover every stored field, secrets get distinct markers and URLs never leave the machine`() {
        val schema = settingsSchema {
            text("name") { label = LocalizedText.of("Name") }
            secret("apiKey") { label = LocalizedText.of("Key") }
            secretTextarea("pem") { label = LocalizedText.of("Pem") }
            url("endpoint") { label = LocalizedText.of("URL") }
            number("timeout") { label = LocalizedText.of("T"); min = 5; max = 60 }
            number("retries") { label = LocalizedText.of("R"); default = 3 }
            switch("sandbox") { label = LocalizedText.of("S"); default = true }
            switch("flag") { label = LocalizedText.of("F") }
            select("mode") { label = LocalizedText.of("M"); option("b", LocalizedText.of("B")); option("a", LocalizedText.of("A")) }
            hidden("h") { label = LocalizedText.of("H") }
            hiddenSecret("hs") { label = LocalizedText.of("HS") }
            notice("note") { label = LocalizedText.of("N") }
            webhookUrl("hook") { label = LocalizedText.of("W") }
        }
        val v = TestContexts.defaultValues(schema)
        assertEquals(schema.storedFields.map { it.key }, v.keys.toList(), "exactly the stored fields, no notice / readonly")
        assertEquals(TestContexts.secretMarker("apiKey"), v["apiKey"])
        assertEquals(TestContexts.secretMarker("pem"), v["pem"])
        assertEquals(TestContexts.secretMarker("hs"), v["hs"])
        assertEquals(3, schema.secretKeys.map { v[it] }.toSet().size, "markers differ per key")
        assertTrue((v["endpoint"] as String).startsWith("http://127.0.0.1:9/"))
        assertEquals(5L, v["timeout"])
        assertEquals(3, v["retries"])
        assertEquals(true, v["sandbox"])
        assertEquals(false, v["flag"])
        assertEquals("b", v["mode"])
        assertEquals("hidden-h", v["h"])
        assertEquals("value-name", v["name"])
        assertEquals(TestContexts.secretMarker("apiKey"), TestContexts.settings(schema).string("apiKey"))
    }

    @Test
    fun `the state store honours ttl against the context clock and compareAndSet`() = runBlocking {
        val ctx = TestContexts.payment(vertx = vertx)
        val state = ctx.state
        assertNull(state.get("k"))
        state.put("k", "v1")
        assertEquals("v1", state.get("k"))
        state.put("ttl", "x", ttlSeconds = 10)
        ctx.advance(9_999)
        assertEquals("x", state.get("ttl"))
        ctx.advance(1)
        assertNull(state.get("ttl"), "expired exactly at ttl")
        assertTrue(state.compareAndSet("cas", null, "one"))
        assertFalse(state.compareAndSet("cas", null, "two"))
        assertFalse(state.compareAndSet("cas", "wrong", "two"))
        assertTrue(state.compareAndSet("cas", "one", "two"))
        assertEquals("two", state.get("cas"))
        state.remove("cas")
        assertNull(state.get("cas"))
        assertTrue(state.compareAndSet("cas", null, "again", ttlSeconds = 1))
        ctx.advance(1000)
        assertTrue(state.compareAndSet("cas", null, "after-expiry"), "an expired key counts as absent")
        assertEquals(setOf("k", "cas"), ctx.stateStore.keys)
    }

    @Test
    fun `compareAndSet lets exactly one of many racing writers win`() = runBlocking {
        val store = InMemoryStateStore()
        repeat(5) { round ->
            val key = "race-$round"
            val winners = (0 until 32).map { i -> async(Dispatchers.IO) { store.compareAndSet(key, null, "w$i") } }.awaitAll()
            assertEquals(1, winners.count { it })
        }
    }

    @Test
    fun `the recording log keeps everything unredacted, errors and exchanges included`() {
        val log = RecordingLog()
        log.info("hello")
        log.warn("careful", IllegalStateException("inner"))
        log.error("bad", null)
        log.exchange("start", "req-body", "resp-body", 201, 12)
        assertEquals(listOf("INFO hello", "WARN careful java.lang.IllegalStateException: inner", "ERROR bad"), log.entries)
        assertEquals(1, log.exchanges.size)
        assertEquals(201, log.exchanges.single().status)
        val all = log.everything()
        assertTrue(all.contains("hello") && all.contains("inner") && all.contains("req-body") && all.contains("resp-body"))
    }

    @Test
    fun `payment context urls follow the platform routes and the attempt lock serialises work per attempt`() = runBlocking {
        val ctx = TestContexts.payment("stripe", vertx = vertx)
        assertEquals("https://shop.example/api/market/payments/stripe/webhook", ctx.urls.webhook())
        assertEquals("https://shop.example/api/market/payments/stripe/webhook/refunds", ctx.urls.webhook("refunds"))
        assertEquals("https://shop.example/store/checkout", ctx.urls.checkoutPage())
        val urls = ctx.urls.forAttempt(SampleData.attemptView())
        assertEquals("https://shop.example/api/market/payments/stripe/return/tok/success", urls.success)
        assertEquals("https://shop.example/api/market/payments/stripe/notify/tok/hook", urls.notify("hook"))
        assertEquals("https://shop.example/store/order/ABCDEFGHJKMNPQRSTVWX", urls.orderPage)

        val inside = AtomicInteger(0)
        val maxInside = AtomicInteger(0)
        (0 until 20).map {
            async(Dispatchers.IO) {
                ctx.withAttemptLock(5) {
                    val now = inside.incrementAndGet()
                    maxInside.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                    delay(2)
                    inside.decrementAndGet()
                }
            }
        }.awaitAll()
        assertEquals(1, maxInside.get(), "two actors were inside the same attempt lock at once")
        val other = (0 until 2).map { id -> async(Dispatchers.IO) { ctx.withAttemptLock(100L + id) { delay(50); id } } }
        assertEquals(listOf(0, 1), other.awaitAll())
        assertNull(ctx.payments.byId(1))
    }

    @Test
    fun `shipping context urls and clock`() = runBlocking {
        val ctx = TestContexts.shipping("geliver", vertx = vertx, testMode = true)
        assertEquals("https://shop.example/api/market/shipping/geliver/webhook/test-install-token", ctx.urls.webhook())
        assertEquals("https://shop.example/api/market/shipping/geliver/webhook/test-install-token/events", ctx.urls.webhook("events"))
        assertTrue(ctx.testMode)
        assertEquals(TestContexts.START_MS, ctx.now())
        ctx.advance(5)
        assertEquals(TestContexts.START_MS + 5, ctx.now())
        assertNull(ctx.shipments.byTrackingNumber("TN"))
        assertNull(ctx.shipments.byMerchantReference("R"))
        assertNull(ctx.shipments.byCarrierReference("C"))
    }

    @Test
    fun `sample order balances and the sample request is consistent`() {
        val request = SampleData.startRequest()
        assertEquals(request.amount.amount, request.order.total.amount)
        assertEquals("EUR", request.order.currency)
        assertEquals(1, request.order.lines.size)
        assertEquals(request.order.lines.sumOf { it.total.amount }, request.order.subtotal.amount)
        assertEquals(request.amount.amount, request.order.balancedLines(request.amount).sumOf { it.total.amount })
        assertTrue(request.urls.success.endsWith("/success"))
        val usd = SampleData.order(com.panomc.plugins.market.spi.common.Money(2500, "USD"))
        assertEquals("USD", usd.lines.single().total.currency)
        assertEquals(OrderLine.SHIPPING_LINE_ID, -1L)
        assertEquals("DE", SampleData.address().country)
        assertEquals("TR", SampleData.address("TR").country)
        assertEquals(500, SampleData.parcel().weightGrams)
        assertEquals("SHP-9", SampleData.createShipmentRequest().merchantReference)
        assertEquals("obj_1", SampleData.createShipmentRequest("obj_1").previousCarrierReference)
    }

    @Test
    fun `garbage covers every inbound kind with empty, binary, oversized and misleading bodies`() {
        for (kind in com.panomc.plugins.market.spi.common.InboundKind.entries) {
            val requests = Garbage.requests(kind)
            assertEquals(Garbage.bodies.size * 4, requests.size)
            assertTrue(requests.all { it.kind == kind })
            assertTrue(requests.any { it.body.isEmpty() && it.method == "POST" })
            assertTrue(requests.any { it.body.size >= 256 * 1024 })
            assertTrue(requests.any { it.method == "GET" && it.query.isNotEmpty() })
            assertTrue(requests.all { it.header("x-signature") != null || it.method == "GET" })
        }
        assertEquals(Garbage.requests(com.panomc.plugins.market.spi.common.InboundKind.WEBHOOK).map { it.body.contentHashCode() }, Garbage.requests(com.panomc.plugins.market.spi.common.InboundKind.WEBHOOK).map { it.body.contentHashCode() }, "deterministic")
    }
}
