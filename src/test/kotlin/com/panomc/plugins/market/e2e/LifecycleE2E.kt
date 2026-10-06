package com.panomc.plugins.market.e2e

import com.panomc.plugins.market.e2e.support.E2eCatalog
import com.panomc.plugins.market.e2e.support.E2eClient
import com.panomc.plugins.market.e2e.support.E2eDb
import com.panomc.plugins.market.e2e.support.E2eEnv
import com.panomc.plugins.market.e2e.support.E2eResponse
import com.panomc.plugins.market.support.Await
import com.panomc.plugins.market.support.AwaitTimeout
import com.panomc.plugins.market.support.FakePayGateway
import com.panomc.plugins.market.support.InvariantChecker
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlConnection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Migration and lifecycle (17 section 9.10): L-01 to L-06 on an instance of their own (`instance-lifecycle`, ports 18198 / 18199, database
 * `pano_market_e2e_lifecycle`; `MARKET_E2E_LIFECYCLE_PORT_BASE` / `MARKET_E2E_LIFECYCLE_SUFFIX` move them for a second stream, 17 section 10
 * hard codes the defaults for stream A, see `runtime/streams.md` gotcha 6). The class never touches the per-stream instance of the other E2E classes.
 *
 * The instance is started, killed and restarted only through `scripts/e2e-instance.sh` (exact recorded PID, never a pattern). This is the one T4
 * class that may write schema and fixture SQL (17 section 8.3): L-01 loads `schema-v2.sql` / `seed-v2.sql` through the script, L-04 drops and
 * re-creates an index, L-02 / L-03 / L-06 move due times with SQL (time travel) or hold a row lock from a second connection.
 *
 * Order matters (`@Order`): the class runs last of the E2E classes in a full run, L-04 (the destructive one) runs near the end (then L-04b, L-02b: the known-gap scenarios) and leaves a healthy
 * instance behind. The buyer is the panel admin (it holds the umbrella node, so the test-mode fake provider is usable without the payer-group dance of
 * `E2eSession`; the harness of the other classes is not shared on purpose: it bootstraps against `MARKET_E2E_URL`).
 *
 * The §8.2 bootstrap (settings, fake providers, catalogue) is re-run by [Lifecycle.bootstrap] after every fresh start; after a plain restart the cookie
 * jar is re-created by [Lifecycle.login] (provider configuration and settings live in the database).
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class LifecycleE2E {
    private val lc = Lifecycle()

    private companion object {
        /** The plaintext secret of the legacy `fake` payment method row that [Lifecycle.script] adds to the `install-legacy` fixture. */
        const val LEGACY_FAKE_SECRET = "fixture-fake-plaintext-secret"
    }

    @AfterAll
    fun tearDown() {
        lc.shutdown()
    }

    // ----------------------------------------------------------------------------------------------------------------------------------
    // L-01
    // ----------------------------------------------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    fun `L-01 upgrade of an existing install from scheme version 2`() {
        lc.closeGateway()
        lc.script("install-legacy")
        lc.login()

        // every step of the chain ran and was logged by the platform (17 section 9.10: "log has every MarketMigration<N>to<N+1>")
        val log = lc.log()
        for (from in 2..9) {
            assertTrue(log.contains("Migrating database from version $from to ${from + 1}"), "the log has the migration $from -> ${from + 1}")
        }
        assertTrue(log.contains("[MarketPlugin] - Started!"), "the market started after the migration")

        // scheme version 10
        val version = lc.db.sql("SELECT `key` FROM `pano_scheme_version` WHERE `pluginId` = 'pano-plugin-market'").map { it.getString("key").toInt() }.maxOrNull()
        assertEquals(10, version, "the plugin scheme version is the head of the chain")

        // health: READY and the schema verifier is happy
        assertHealthy()

        // the seeded products, readable through the panel API with the values of seed-v2.sql (minor units x100 became decimals)
        val products = lc.admin.get("/api/panel/market/products").ok().obj().getJsonArray("products").map { it as JsonObject }.associateBy { it.getLong("id") }
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 6L), products.keys, "six legacy products")
        data class P(val slug: String, val name: String, val price: Double, val credit: Double, val stock: Long?, val status: String, val sold: Long)
        val expected = mapOf(
            1L to P("vip-rank", "VIP Rank", 19.99, 0.0, null, "ACTIVE", 0),
            2L to P("starter-crate", "Starter Crate", 10.0, 0.0, 100, "ACTIVE", 2),
            3L to P("credit-pack-500", "Credit Pack 500", 5.0, 0.0, null, "ACTIVE", 1),
            4L to P("legend-rank", "Legend Rank", 49.99, 0.0, 0, "ACTIVE", 0),
            5L to P("mystery-box", "Mystery Box", 7.5, 0.0, 25, "HIDDEN", 0),
            6L to P("old-kit", "Old Kit", 5.0, 3.0, 10, "INACTIVE", 0)
        )
        for ((id, want) in expected) {
            val got = products.getValue(id)
            assertEquals(want.slug, got.getString("slug"), "product $id slug")
            assertEquals(want.name, got.getString("name"), "product $id name")
            assertEquals(want.price, got.getDouble("price"), 0.0001, "product $id price")
            assertEquals(want.credit, got.getDouble("creditPrice"), 0.0001, "product $id credit price")
            assertEquals(want.stock, got.getValue("stock")?.let { (it as Number).toLong() }, "product $id stock")
            assertEquals(want.status, got.getString("status"), "product $id status")
            assertEquals(want.sold, got.getLong("soldCount"), "product $id soldCount (one-shot fixup of the COMPLETED legacy order)")
        }

        // the three legacy orders with their items
        val orders = lc.admin.get("/api/panel/market/orders").ok().obj().getJsonArray("orders").map { it as JsonObject }.associateBy { it.getLong("id") }
        assertEquals(setOf(1L, 2L, 3L), orders.keys)
        data class O(val user: String, val total: Double, val currency: String, val method: String, val status: String, val items: List<Triple<String, Int, Double>>)
        val wantOrders = mapOf(
            1L to O("Steve", 19.99, "TRY", "tebex", "PENDING", listOf(Triple("VIP Rank", 1, 19.99))),
            2L to O("Alex", 25.0, "USD", "tebex", "COMPLETED", listOf(Triple("Starter Crate", 2, 10.0), Triple("Credit Pack 500", 1, 5.0))),
            3L to O("Herobrine", 15.0, "TRY", "paytr", "REFUNDED", listOf(Triple("Mystery Box", 1, 7.5), Triple("Retired Kit", 1, 7.5)))
        )
        for ((id, want) in wantOrders) {
            val got = orders.getValue(id)
            assertEquals(want.user, got.getString("playerUsername"), "order $id player")
            assertEquals(want.total, got.getDouble("totalPrice"), 0.0001, "order $id total")
            assertEquals(want.currency, got.getString("currency"), "order $id currency")
            assertEquals(want.method, got.getString("paymentMethodId"), "order $id method")
            assertEquals(want.status, got.getString("status"), "order $id status")
            val items = got.getJsonArray("items").map { it as JsonObject }.sortedBy { it.getLong("id") }.map { Triple(it.getString("productName"), it.getInteger("quantity"), it.getDouble("unitPrice")) }
            assertEquals(want.items, items, "order $id items")
        }

        // the backfills of 01 section 14.2
        val rows = lc.db.sql("SELECT * FROM `pano_market_order` ORDER BY `id`")
        assertEquals(3, rows.size)
        assertEquals(3, rows.map { it.getString("publicId") }.toSet().size, "publicId is unique")
        for (row in rows) {
            assertEquals(20, row.getString("publicId").length, "publicId has 20 characters")
            assertEquals("LEGACY", row.getString("source"), "legacy order marker")
            assertEquals(row.getString("playerUsername"), row.getString("recipientUsername"), "recipientUsername = playerUsername")
            assertNotEquals("", row.getString("buyerKey"), "buyerKey filled")
            assertEquals(row.getString("currency"), row.getString("baseCurrency"), "baseCurrency = currency")
            assertEquals(row.getLong("totalPrice"), row.getLong("subtotal"), "subtotal = totalPrice")
            assertEquals(row.getLong("totalPrice"), row.getLong("gatewayAmount"), "gatewayAmount = totalPrice")
            if (row.getString("status") in setOf("COMPLETED", "REFUNDED")) {
                assertEquals(row.getLong("updatedAt"), row.getLong("paidAt"), "paidAt = updatedAt for a paid legacy order")
                assertEquals("COMMITTED", row.getString("reservationState"))
                assertEquals(row.getLong("totalPrice"), row.getLong("paidAmount"))
            } else {
                assertEquals(null, row.getValue("paidAt"), "a PENDING legacy order was not paid")
            }
        }
        for (item in lc.db.sql("SELECT * FROM `pano_market_order_item`")) {
            assertEquals(item.getLong("unitPrice") * item.getInteger("quantity"), item.getLong("lineTotal"), "item lineTotal = unitPrice x quantity")
        }
        assertEquals(listOf(1L, 1L), lc.db.sql("SELECT `redeemLimit` FROM `pano_market_gift` ORDER BY `id`").map { it.getLong("redeemLimit") }, "gift redeemLimit = 1")
        assertEquals(1, lc.db.count("market_sequence", "`name` = 'fixup:soldCount'"), "the soldCount fixup left its marker")
        assertEquals(1, lc.db.count("market_sequence", "`name` = 'fixup:legacyUsedCount'"))
        assertEquals(4L, lc.db.long("SELECT `usedCount` - `legacyUsedCount` + 4 FROM `pano_market_coupon` WHERE `id` = 1"), "coupon usedCount = legacyUsedCount, no redemption rows")

        // stats equal the numbers of the seed (order 2: 2 x 10 + 5 = 25 USD at the stored rate 32.5 = 812.50 TRY; the REFUNDED and PENDING orders count for nothing)
        val stats = lc.admin.get("/api/panel/market/stats").ok().obj()
        val total = stats.getJsonObject("summary").getJsonObject("total")
        assertEquals(1, total.getInteger("count"), "one paid legacy order")
        assertEquals(812.5, total.getDouble("revenue"), 0.0001, "revenue in the stats currency")
        assertEquals("TRY", stats.getString("statsCurrency"))
        val top = stats.getJsonObject("charts").getJsonObject("topProducts")
        assertEquals(listOf("Starter Crate", "Credit Pack 500"), top.getJsonArray("labels").map { it.toString() })
        assertEquals(listOf(650.0, 162.5), top.getJsonArray("values").map { (it as Number).toDouble() })

        // the legacy payment method row of a provider that is NOT installed on this instance (tebex) survived with its secret: nothing can encrypt it (the provider
        // schema is unknown) and nothing may lose it. The re-encryption of a legacy plaintext secret of an installed provider is proven by `L-01b`.
        val settings = JsonObject(lc.db.string("SELECT `settings` FROM `pano_market_payment_method` WHERE `methodId` = 'tebex'")!!)
        assertEquals("fixture-store", settings.getString("webstoreId"))
        assertTrue(settings.getString("secret") == "fixture-plaintext-secret" || settings.getString("secret").startsWith("v1:"), "the secret was not lost: ${settings.getString("secret")?.take(3)}")

        // the public side serves the migrated catalogue
        val store = E2eClient(lc.url, "visitor").get("/api/market/store").ok().obj()
        assertEquals("ok", store.getString("result"))

        // the migrated database still carries a version 2 creator code with earnings but no earning rows: the legacy-aware form of I14 (and of I6, I8, I11, I17)
        runBlocking { InvariantChecker.assertAll(lc.db.pool, legacy = true) }

        // a second boot of the migrated database changes nothing (the platform does not run a handler twice, ensure() and the fixups are idempotent)
        val before = snapshot()
        lc.script("restart")
        lc.login()
        assertFalse(lc.log().contains("Migration Found"), "no migration runs on the second start")
        assertHealthy()
        assertEquals(before, snapshot(), "the data is the same after the second start")
    }

    /**
     * 17 section 9.10 L-01 / 01 section 14.4 step 6: after the upgrade the legacy plaintext secret of an installed provider (the fixture row `fake`, written by
     * [Lifecycle.script] `install-legacy` through `MARKET_E2E_LEGACY_EXTRA_SQL`) is stored encrypted (`v1:`, 01 section 14.4) and reveals to the same value.
     * Runs on the instance L-01 left behind (migrated, running). No guard on purpose: while the plugin does not run `PaymentMethodService.startup()` at start
     * this test FAILS, which is the truth (the evidence file names the product gap).
     */
    @Test
    @Order(2)
    fun `L-01b a legacy plaintext provider secret is encrypted after the upgrade and still reveals to the same value`() {
        lc.login()
        assertHealthy()

        // the provider lives in another plugin: the second secrets pass of the market runs a few seconds after the start (MarketPlugin.encryptLegacySecrets)
        fun storedSecret(): String? = lc.db.string("SELECT `settings` FROM `pano_market_payment_method` WHERE `methodId` = 'fake'")?.let { JsonObject(it).getString("secret") }

        runCatching { Await.until(60_000, 500, "legacy secret encrypted") { storedSecret()?.startsWith("v1:") == true } }

        val secret = storedSecret()

        assertNotNull(secret, "the secret was not lost")
        assertTrue(secret!!.startsWith("v1:"), "the legacy plaintext secret is encrypted at rest after the upgrade (is plaintext: ${secret == LEGACY_FAKE_SECRET})")
        assertNotEquals(LEGACY_FAKE_SECRET, secret)

        val revealed = lc.admin.post("/api/panel/market/payment-methods/fake/reveal", JsonObject().put("password", lc.adminPassword())).ok().obj()

        assertEquals(LEGACY_FAKE_SECRET, revealed.getJsonObject("settings").getString("secret"), "the encrypted secret reveals to the same value")
    }

    /** What must not change when the migrated database is booted again. */
    private fun snapshot(): List<Any?> = listOf(
        lc.db.sql("SELECT `id`, `publicId`, `accessToken`, `subtotal`, `paidAt`, `reservationState` FROM `pano_market_order` ORDER BY `id`").map { it.toJson().encode() },
        lc.db.sql("SELECT `id`, `soldCount`, `price`, `stock` FROM `pano_market_product` ORDER BY `id`").map { it.toJson().encode() },
        lc.db.sql("SELECT `name`, `value` FROM `pano_market_sequence` ORDER BY `name`").map { it.toJson().encode() },
        lc.db.sql("SELECT `methodId`, `settings` FROM `pano_market_payment_method` ORDER BY `id`").map { it.toJson().encode() }
    )

    // ----------------------------------------------------------------------------------------------------------------------------------
    // L-05 (runs second: it produces the standard instance the next scenarios restart)
    // ----------------------------------------------------------------------------------------------------------------------------------

    @Test
    @Order(3)
    fun `L-05 market present before setup, then the stop gate`() {
        lc.closeGateway()
        lc.script("start-presetup")

        // before the setup the platform itself refuses every API call; the market is loaded and waits for the setup
        val visitor = E2eClient(lc.url, "visitor")
        val early = visitor.get("/api/market/store")
        assertEquals(401, early.status, "the platform answers before the setup")
        assertEquals("INSTALLATION_REQUIRED", early.error)
        assertEquals(401, visitor.post("/api/market/payments/fake/webhook", JsonObject()).status, "no inbound call reaches the market before the setup")
        assertTrue(lc.log().contains("Setup is not finished, waiting for setup completion"), "the market waits for the setup")
        assertFalse(lc.log().contains("[MarketPlugin] - Started!"), "the market did not start its bootstrap before the setup")

        // run the setup: the SAME JVM, no restart; MarketBootstrap runs once and the store answers
        val pid = lc.pid()

        lc.script("finish-setup")
        assertEquals(pid, lc.pid(), "the setup ran without a restart")
        assertTrue(lc.alive(pid))
        val log = lc.log()
        assertTrue(log.contains("Setup finished! Initializing plugin"), "the market saw the setup end")
        assertEquals(1, Regex(Regex.escape("[MarketPlugin] - Started!")).findAll(log).count(), "the bootstrap ran exactly once")
        assertEquals(200, visitor.get("/api/market/store").status, "the store answers 200 without a restart")

        lc.login()
        assertHealthy()
        lc.bootstrap()
        assertEquals(true, E2eClient(lc.url, "visitor").get("/api/market/store").obj().getJsonObject("settings").getBoolean("testMode"))

        // stop gate. The panel API (PUT /api/panel/plugins/:id status=false) stops AND disables the plugin, and a disabled plugin's routes are unmounted by the
        // host, so the 503 STORE_UNAVAILABLE gate of 00 section 8.9 (a stopped plugin that keeps its routes) is not reachable over HTTP; it is proven by the
        // MarketGate / InboundDispatcher tests. What the instance proves: nothing of the market answers while it is off, nothing is stored, and it resumes.
        val product = lc.product("L05", "4.00")
        val eventsBefore = lc.db.count("market_payment_event")
        val ordersBefore = lc.db.count("market_order")

        lc.setPlugin("pano-plugin-market", false)
        val store = visitor.get("/api/market/store")
        assertFalse(store.status == 200 && store.json?.getString("result") == "ok", "the store does not answer while the plugin is off")
        val checkout = lc.checkout(lc.admin, product)
        assertFalse(checkout.status in 200..299, "checkout is not served while the plugin is off, was ${checkout.status}")
        val hook = lc.gateway.sendWebhook("payment.succeeded", JsonObject().put("reference", "gone").put("amount", "1.00").put("currency", "EUR"))
        assertFalse(hook.any { it.statusCode() in 200..299 }, "the inbound route is not served while the plugin is off: ${hook.map { it.statusCode() }}")
        val back = visitor.get("/api/market/payments/fake/return/anything/success")
        assertNotEquals(303, back.status, "the return route does not redirect while the plugin is off")
        assertEquals(eventsBefore, lc.db.count("market_payment_event"), "nothing was stored while the plugin was off")
        assertEquals(ordersBefore, lc.db.count("market_order"), "no order was created while the plugin was off")

        // start it again (and the dependent fake provider the panel disabled with it)
        lc.setPlugin("pano-plugin-market", true)
        lc.setPlugin("pano-plugin-market-fake", true)
        Await.until(60_000, 500, "store answers 200 again") { visitor.get("/api/market/store").status == 200 }
        lc.login()
        assertHealthy()
        lc.awaitProvidersActive()

        // the settings and the provider configuration survived the stop / start (config.conf and secret.key are in the data folder)
        assertEquals(true, visitor.get("/api/market/store").obj().getJsonObject("settings").getBoolean("testMode"))

        // and the money path works again end to end: checkout, webhook, order completes
        val order = lc.publicIdOf(lc.checkout(lc.admin, product).ok())

        lc.pay(order)
        lc.awaitOrder(order, "COMPLETED")
        assertEquals("SUCCEEDED", lc.attemptStatus(lc.reference(order)))
        lc.assertInvariants()
    }

    // ----------------------------------------------------------------------------------------------------------------------------------
    // L-02
    // ----------------------------------------------------------------------------------------------------------------------------------

    @Test
    @Order(4)
    fun `L-02 SIGTERM restart mid-flight completes a pending order, an unsent webhook and a scheduled delivery exactly once`() {
        lc.ensureStandard()

        val plain = lc.product("L02A", "4.00")
        val delayed = lc.product("L02B", "4.00", actions = JsonArray().add(JsonObject().put("id", "a1").put("type", "CREDIT").put("phase", "GRANT").put("value", 5).put("delay", 3600)).encode())
        val endpoint = lc.admin.post(
            "/api/panel/market/webhooks",
            JsonObject().put("name", "sink-${System.currentTimeMillis().toString(36)}").put("url", "http://127.0.0.1:${lc.gatewayPort}/hooks/store").put("events", JsonArray().add("*"))
        ).ok().obj().getLong("id")
        val credits = lc.creditBalance()

        // 1. a pending order: the attempt is PENDING at the gateway
        val pending = lc.publicIdOf(lc.checkout(lc.admin, plain).ok())
        val pendingRef = lc.reference(pending)

        assertEquals("PENDING", lc.orderStatus(pending))
        assertEquals("PENDING", lc.attemptStatus(pendingRef))

        // 2. a paid order whose order.paid webhook cannot be delivered (the sink answers 503 once) and whose CREDIT action is scheduled one hour ahead
        lc.gateway.clearRequests()
        lc.gateway.hookStatus("store", 503)
        val paid = lc.publicIdOf(lc.checkout(lc.admin, delayed).ok())
        val paidRef = lc.pay(paid)

        lc.awaitOrder(paid, "COMPLETED")
        val paidOrderId = lc.orderId(paid)
        // attempts is counted when the row is claimed (status SENDING); the outcome (the 503 and the backoff slot) is written afterwards, so wait for the
        // row to rest again with its retry in the future
        val firstTry = Await.untilValue(60_000, 100, "the first webhook attempt was refused and the retry is scheduled") {
            lc.db.sql(
                "SELECT * FROM `pano_market_webhook_delivery` WHERE `orderId` = ? AND `event` = 'order.paid' AND `attempts` >= 1 AND `status` <> 'SENDING' AND `nextAttemptAt` > ?",
                paidOrderId, System.currentTimeMillis() + 15_000
            ).firstOrNull()
        }
        val webhookId = firstTry.getLong("id")
        val eventId = firstTry.getString("eventId")

        assertNotEquals("SUCCEEDED", firstTry.getString("status"), "the sink refused the first attempt")
        assertEquals(1, firstTry.getInteger("attempts"))
        assertTrue(firstTry.getLong("nextAttemptAt") > System.currentTimeMillis() + 15_000, "the retry is scheduled in the future")
        assertEquals(1, lc.gateway.hooks("store").count { it.header("X-Pano-Event-Id") == eventId }, "the sink saw the event once")
        val delivery = lc.db.sql("SELECT * FROM `pano_market_delivery` WHERE `orderId` = ?", paidOrderId)

        assertEquals(1, delivery.size, "one delivery row for the CREDIT action")
        assertEquals("SCHEDULED", delivery[0].getString("status"), "the delivery waits for its runAfter")
        assertEquals(0, delivery[0].getInteger("attempts"))
        val deliveryId = delivery[0].getLong("id")

        // 3. SIGTERM; while the instance is down the gateway takes the money of the pending order (no webhook can reach it)
        val pidBefore = lc.pid()

        lc.script("stop")
        assertFalse(lc.alive(pidBefore), "the JVM is gone")
        assertFalse(lc.portOpen(lc.httpPort), "nothing listens on the instance port")
        lc.gateway.setStatus(pendingRef, "paid")

        lc.script("start", "--keep")
        assertNotEquals(pidBefore, lc.pid())
        lc.afterStart()
        assertHealthy()
        assertEquals("PENDING", lc.orderStatus(pending), "the pending order survived the restart as it was")
        assertEquals("COMPLETED", lc.orderStatus(paid))

        // 4. time travel: the three open items become due, the jobs finish them
        lc.sql("UPDATE `pano_market_payment` SET `nextQueryAt` = ? WHERE `reference` = ?", System.currentTimeMillis() - 1_000, pendingRef)
        lc.sql("UPDATE `pano_market_webhook_delivery` SET `nextAttemptAt` = ? WHERE `id` = ?", System.currentTimeMillis() - 1_000, webhookId)
        lc.sql("UPDATE `pano_market_delivery` SET `runAfter` = `runAfter` - 7200000, `nextAttemptAt` = `nextAttemptAt` - 7200000 WHERE `id` = ?", deliveryId)

        lc.awaitOrder(pending, "COMPLETED")
        Await.until(90_000, 500, "the delivery is CONFIRMED") { lc.db.string("SELECT `status` FROM `pano_market_delivery` WHERE `id` = ?", deliveryId) == "CONFIRMED" }
        Await.until(90_000, 500, "the webhook is SUCCEEDED") { lc.db.string("SELECT `status` FROM `pano_market_webhook_delivery` WHERE `id` = ?", webhookId) == "SUCCEEDED" }

        // nothing ran twice
        assertEquals("SUCCEEDED", lc.attemptStatus(pendingRef))
        assertEquals(1, lc.db.count("market_payment", "`orderId` = ? AND `status` = 'SUCCEEDED'", lc.orderId(pending)), "one successful attempt for the pending order")
        assertEquals(1, orderEventCount(pending, "PAYMENT_SUCCEEDED"), "the pending order was paid once")
        assertEquals(1, orderEventCount(paid, "PAYMENT_SUCCEEDED"), "the paid order was paid once")
        val finished = lc.db.sql("SELECT * FROM `pano_market_delivery` WHERE `id` = ?", deliveryId).single()

        assertEquals(1, finished.getInteger("attempts"), "the scheduled delivery was executed once")
        assertEquals(1, lc.db.count("market_credit_tx", "`type` = 'ACTION' AND `deliveryId` = ?", deliveryId), "one credit transaction for the delivery")
        assertEquals(credits + 500L, lc.creditBalance(), "the 5 credits arrived exactly once")
        val sent = lc.db.sql("SELECT `attempts` FROM `pano_market_webhook_delivery` WHERE `id` = ?", webhookId).single().getInteger("attempts")

        assertEquals(2, sent, "the unsent webhook took two attempts (503, then 200)")
        assertEquals(sent, lc.gateway.hooks("store").count { it.header("X-Pano-Event-Id") == eventId }, "the sink saw exactly the attempts the row counted")
        // the order.paid of the order that completed after the restart is delivered once as well
        val second = Await.untilValue(90_000, 500, "the second order.paid is delivered") {
            lc.db.sql("SELECT * FROM `pano_market_webhook_delivery` WHERE `orderId` = ? AND `event` = 'order.paid' AND `status` = 'SUCCEEDED'", lc.orderId(pending)).firstOrNull()
        }

        assertEquals(1, second.getInteger("attempts"))
        assertEquals(1, lc.gateway.hooks("store").count { it.header("X-Pano-Event-Id") == second.getString("eventId") })
        assertEquals(1, lc.db.count("market_webhook_delivery", "`orderId` = ? AND `event` = 'order.paid'", lc.orderId(pending)), "one webhook row per paid order")
        assertNotNull(paidRef)
        lc.sql("DELETE FROM `pano_market_webhook_endpoint` WHERE `id` = ?", endpoint)
        lc.assertInvariants()
    }

    private fun orderEventCount(publicId: String, type: String): Long =
        lc.db.count("market_order_event", "`type` = ? AND `orderId` = (SELECT `id` FROM `pano_market_order` WHERE `publicId` = ?)", type, publicId)

    // ----------------------------------------------------------------------------------------------------------------------------------
    // L-03
    // ----------------------------------------------------------------------------------------------------------------------------------

    @Test
    @Order(5)
    fun `L-03 SIGKILL between the intent and the result of the gateway call`() {
        lc.ensureStandard()

        val product = lc.product("L03", "4.00")
        val adminId = lc.adminId()
        val grant = lc.admin.request(
            "POST", "/api/panel/market/credits/accounts/$adminId/grant", JsonObject().put("amount", 50).put("note", "lifecycle L-03"),
            mapOf("Idempotency-Key" to UUID.randomUUID().toString())
        )
        grant.ok()
        val startBalance = lc.creditBalance()
        val holdBefore = lc.holdBalance()

        // two buyers' worth of checkouts in flight: 2.00 in credits each, the rest at the gateway, whose create call never answers
        lc.gateway.hang(FakePayGateway.Op.CREATE)
        val before = lc.gateway.requests(FakePayGateway.Op.CREATE).size
        var attempts: List<io.vertx.sqlclient.Row> = emptyList()
        val calls = ArrayList<CompletableFuture<E2eResponse>>()

        try {
            repeat(2) { calls += lc.async { lc.checkout(lc.admin, product, credits = 2) } }
            Await.until(60_000, 100, "both create calls are on the wire") { lc.gateway.requests(FakePayGateway.Op.CREATE).size >= before + 2 }
            attempts = lc.db.sql("SELECT `id`, `orderId`, `status`, `reference` FROM `pano_market_payment` WHERE `status` = 'CREATED' ORDER BY `id`")

            assertEquals(2, attempts.size, "the intent of both attempts is committed (CREATED) before the call")
            assertEquals(startBalance - 400L, lc.creditBalance(), "2.00 credits are held for each order")
            assertEquals(holdBefore + 400L, lc.holdBalance())

            lc.script("kill")
        } finally {
            lc.gateway.release(FakePayGateway.Op.CREATE) // never leave the shared fake gateway hanging for the next scenario
        }
        calls.forEach { runCatching { it.get(30, TimeUnit.SECONDS) } } // the sockets died with the JVM; the answers (errors) are of no interest

        // the gateway never created a payment for them
        val lost = attempts.map { it.getString("reference") }

        assertTrue(lost.none { lc.gateway.payments.containsKey(it) }, "the gateway holds no payment for the two attempts")

        lc.script("restart")
        lc.afterStart()
        assertHealthy()

        val orderX = attempts[0].getLong("orderId")
        val orderY = attempts[1].getLong("orderId")
        val publicX = lc.db.string("SELECT `publicId` FROM `pano_market_order` WHERE `id` = ?", orderX)!!
        val publicY = lc.db.string("SELECT `publicId` FROM `pano_market_order` WHERE `id` = ?", orderY)!!

        assertEquals("PENDING", lc.orderStatus(publicX))
        assertEquals("PENDING", lc.orderStatus(publicY))
        assertEquals("CREATED", lc.db.string("SELECT `status` FROM `pano_market_payment` WHERE `id` = ?", attempts[0].getLong("id")), "the attempt is still the one the crash left")

        // the reconcile job asks the gateway about the lost attempts (once the 2 minute in-flight window is over) and leaves what it cannot know alone
        for (a in attempts) lc.sql("UPDATE `pano_market_payment` SET `createdAt` = `createdAt` - 180000 WHERE `id` = ?", a.getLong("id"))
        Await.until(90_000, 500, "the reconcile job queried both lost attempts") { lc.db.count("market_payment", "`id` IN (?, ?) AND `queryCount` >= 1", attempts[0].getLong("id"), attempts[1].getLong("id")) == 2L }

        for (a in attempts) {
            assertTrue(lc.db.string("SELECT `status` FROM `pano_market_payment` WHERE `id` = ?", a.getLong("id")) in setOf("CREATED", "EXPIRED", "FAILED", "CANCELLED"), "a lost attempt is never SUCCEEDED")
        }
        assertEquals(0, lc.db.count("market_payment", "`orderId` IN (?, ?) AND `status` = 'SUCCEEDED'", orderX, orderY))

        // order X is payable again: a new attempt closes the lost one, the money arrives, the 2.00 credits are spent exactly once
        assertEquals(true, lc.order(publicX).getBoolean("canRetryPayment"), "the order can be paid again")
        lc.admin.post("/api/market/orders/$publicX/pay", JsonObject().put("paymentMethodId", "fake").put("useCredits", 2)).ok()
        lc.pay(publicX)
        lc.awaitOrder(publicX, "COMPLETED")
        assertEquals("SUCCEEDED", lc.attemptStatus(lc.reference(publicX)))
        assertEquals(1, lc.db.count("market_payment", "`orderId` = ? AND `status` = 'SUCCEEDED'", orderX))
        assertEquals(0, lc.db.count("market_payment", "`orderId` = ? AND `status` IN ('CREATED', 'PENDING', 'PROCESSING')", orderX), "no open attempt is left on the paid order")

        // order Y is abandoned: past its expiry the jobs release everything it held
        lc.sql("UPDATE `pano_market_order` SET `expiresAt` = `expiresAt` - 7200000 WHERE `id` = ?", orderY)
        lc.sql("UPDATE `pano_market_payment` SET `expiresAt` = `expiresAt` - 7200000 WHERE `orderId` = ?", orderY)
        lc.awaitOrder(publicY, "EXPIRED")
        assertEquals(0, lc.db.count("market_payment", "`orderId` = ? AND `status` IN ('CREATED', 'PENDING', 'PROCESSING')", orderY), "the lost attempt is closed")
        assertEquals("RELEASED", lc.db.string("SELECT `reservationState` FROM `pano_market_order` WHERE `id` = ?", orderY))

        // no orphan HELD credits: X spent its 2.00, Y got its 2.00 back, the HOLD account is where it was
        assertEquals(startBalance - 200L, lc.creditBalance(), "only the paid order's credits are gone")
        assertEquals(holdBefore, lc.holdBalance(), "nothing stays held on the system HOLD account")
        lc.assertInvariants()
    }

    // ----------------------------------------------------------------------------------------------------------------------------------
    // L-06
    // ----------------------------------------------------------------------------------------------------------------------------------

    @Test
    @Order(6)
    fun `L-06 a failed inbound event is taken over by the redelivery, a crashed one is finished by the retry job`() {
        lc.ensureStandard()

        val product = lc.product("L06", "4.00")

        // run one: step 6 fails once (the order row is locked by a second connection for longer than the 5 s lock wait x 3 tries)
        val first = lc.publicIdOf(lc.checkout(lc.admin, product).ok())
        val firstRef = lc.reference(first)
        val firstOrderId = lc.orderId(first)
        val eventId = "evt_l06_${System.currentTimeMillis().toString(36)}"
        val data = JsonObject().put("reference", firstRef).put("amount", lc.gateway.payments.getValue(firstRef).amount.toPlainString()).put("currency", "EUR")

        val holder = lc.lockOrder(firstOrderId)
        val failed = try {
            lc.gateway.setStatus(firstRef, "paid")
            lc.gateway.sendWebhook("payment.succeeded", data, id = eventId)
        } finally {
            holder.release()
        }

        assertEquals(listOf(500), failed.map { it.statusCode() }, "the inbound route answers 500 so the gateway redelivers (00 section 8.2)")
        assertEquals("PENDING", lc.orderStatus(first), "step 6 did not run")
        val failedRow = lc.db.sql("SELECT * FROM `pano_market_payment_event` WHERE `providerId` = 'fake' AND `direction` = 'IN' AND `eventKey` LIKE ? ORDER BY `id`", "%$eventId%")

        assertEquals(1, failedRow.size)
        assertEquals("FAILED", failedRow[0].getString("status"), "the failed event is kept")

        // the gateway redelivers the identical signed event: the key is taken over, the order completes
        val again = lc.gateway.sendWebhook("payment.succeeded", data, id = eventId)

        assertEquals(listOf(200), again.map { it.statusCode() })
        lc.awaitOrder(first, "COMPLETED")
        val rows = lc.db.sql("SELECT `id`, `status`, `eventKey` FROM `pano_market_payment_event` WHERE `providerId` = 'fake' AND `direction` = 'IN' AND `eventKey` LIKE ? ORDER BY `id`", "%$eventId%")

        assertEquals(2, rows.size, "the failed row and the row that did the work")
        assertEquals("SUPERSEDED", rows[0].getString("status"), "the first row was superseded by the redelivery")
        assertTrue(rows[0].getString("eventKey").endsWith(":${rows[0].getLong("id")}"), "its key was freed: ${rows[0].getString("eventKey")}")
        assertEquals("PROCESSED", rows[1].getString("status"), "the redelivery was processed")
        assertEquals(1, lc.db.count("market_payment", "`orderId` = ? AND `status` = 'SUCCEEDED'", firstOrderId))
        assertEquals(1, orderEventCount(first, "PAYMENT_SUCCEEDED"))

        // run two: SIGKILL after step 2 (the event is stored, step 6 waits for the order row), then the retry job finishes it without any redelivery
        val second = lc.publicIdOf(lc.checkout(lc.admin, product).ok())
        val secondRef = lc.reference(second)
        val secondOrderId = lc.orderId(second)
        val secondEvent = "evt_l06b_${System.currentTimeMillis().toString(36)}"
        val secondData = JsonObject().put("reference", secondRef).put("amount", lc.gateway.payments.getValue(secondRef).amount.toPlainString()).put("currency", "EUR")
        val lock = lc.lockOrder(secondOrderId)

        // Take the payment reconcile job out of the picture: the gateway says `paid` and the attempt is due for a status query 60 s after its creation, which
        // would complete the order through the query alone. With the due time a day ahead only InboundEventRetryJob can finish this order.
        lc.sql("UPDATE `pano_market_payment` SET `nextQueryAt` = ? WHERE `reference` = ?", System.currentTimeMillis() + 86_400_000L, secondRef)
        lc.gateway.setStatus(secondRef, "paid")
        val call = lc.async { lc.gateway.sendWebhook("payment.succeeded", secondData, id = secondEvent) }

        try {
            Await.until(30_000, 50, "the event is stored (step 2) and waits for the order row") {
                lc.db.count("market_payment_event", "`providerId` = 'fake' AND `direction` = 'IN' AND `eventKey` LIKE ? AND `status` = 'RECEIVED'", "%$secondEvent%") == 1L
            }
            lc.script("kill")
        } finally {
            lock.release()
        }
        runCatching { call.get(30, TimeUnit.SECONDS) }
        assertEquals("PENDING", lc.db.string("SELECT `status` FROM `pano_market_order` WHERE `id` = ?", secondOrderId), "the crash left the order unpaid")

        lc.script("restart")
        lc.afterStart()
        lc.sql("UPDATE `pano_market_payment_event` SET `createdAt` = `createdAt` - 600000 WHERE `eventKey` LIKE ?", "%$secondEvent%")
        lc.awaitOrder(second, "COMPLETED", 150_000)
        // the fake gateway never redelivers by itself and this test sends nothing again. The row was stored with attempts = 1 at step 2 of the crashed run;
        // only claimRetry (InboundEventRetryJob) makes it 2, and the row ends PROCESSED. The attempt was never status-queried: the reconcile job did not help.
        val eventRow = lc.db.sql("SELECT `status`, `attempts` FROM `pano_market_payment_event` WHERE `eventKey` LIKE ?", "%$secondEvent%").single()

        assertEquals("PROCESSED", eventRow.getString("status"), "the retry job finished the stored event")
        assertEquals(2, eventRow.getInteger("attempts"), "step 2 stored attempts = 1, the retry job's claim is the second run")
        assertEquals(0L, lc.db.long("SELECT `queryCount` FROM `pano_market_payment` WHERE `reference` = ?", secondRef), "the payment reconcile job never queried the attempt")
        assertEquals(1, lc.db.count("market_payment_event", "`providerId` = 'fake' AND `direction` = 'IN' AND `eventKey` LIKE ?", "%$secondEvent%"), "one event row, no redelivery")
        assertEquals(1, lc.db.count("market_payment", "`orderId` = ? AND `status` = 'SUCCEEDED'", secondOrderId))
        assertEquals(1, orderEventCount(second, "PAYMENT_SUCCEEDED"))
        lc.assertInvariants()
    }

    // ----------------------------------------------------------------------------------------------------------------------------------
    // L-04 (last: it breaks the schema on purpose and puts it back)
    // ----------------------------------------------------------------------------------------------------------------------------------

    @Test
    @Order(7)
    fun `L-04 degraded schema is repaired by ensure and, when it cannot be, the store answers 503 and the platform keeps running`() {
        lc.ensureStandard()

        val product = lc.product("L04", "4.00")
        val pending = lc.publicIdOf(lc.checkout(lc.admin, product).ok())
        val pendingRef = lc.reference(pending)
        // A unique index of the 00 section 8.1 idempotency table that ensure() can repair today: market_order.uq_buyer_idem (buyerKey, idempotencyKey), declared in
        // the `added {}` block of scheme version 5 (CREATE UNIQUE INDEX IF NOT EXISTS). The keys declared in a table's first CREATE (market_credit_tx.uq_idem, ...) are
        // not re-created by ensure(): that gap is proven by its own scenario, `L-04b`.
        val index = "uq_buyer_idem"
        val table = "pano_market_order"
        val wanted = listOf("buyerKey", "idempotencyKey")

        fun indexColumns(): List<String> =
            lc.db.sql(
                "SELECT `COLUMN_NAME` FROM `information_schema`.`STATISTICS` WHERE `TABLE_SCHEMA` = ? AND `TABLE_NAME` = ? AND `INDEX_NAME` = ? AND `NON_UNIQUE` = 0 ORDER BY `SEQ_IN_INDEX`",
                lc.database, table, index
            ).map { it.getString("COLUMN_NAME") }

        assertEquals(wanted, indexColumns(), "the unique index of 00 section 8.1 exists")

        // 1. drop it, restart: ensure() creates it again and the market is READY
        lc.sql("ALTER TABLE `$table` DROP INDEX `$index`")
        assertEquals(emptyList<String>(), indexColumns())
        lc.script("restart")
        lc.afterStart()
        assertEquals(wanted, indexColumns(), "ensure() recreated the index")
        assertHealthy()

        // 2. make recreating it impossible: drop it again, then a second order row with the same buyerKey and idempotencyKey (a copy of the pending order that
        // only gets another publicId and accessToken), then the restart
        lc.sql("ALTER TABLE `$table` DROP INDEX `$index`")
        val keys = lc.db.sql("SELECT `buyerKey`, `idempotencyKey` FROM `$table` WHERE `publicId` = ?", pending).single()
        val buyerKey = keys.getString("buyerKey")
        val idempotencyKey = keys.getString("idempotencyKey")

        assertNotNull(idempotencyKey, "the pending order was created with an Idempotency-Key")
        val duplicate = lc.duplicateOrder(lc.orderId(pending))
        assertEquals(2L, lc.db.count("market_order", "`buyerKey` = ? AND `idempotencyKey` = ?", buyerKey, idempotencyKey), "two rows share the 8.1 key")
        lc.script("restart", degraded = true)
        lc.login()

        val health = lc.admin.get("/api/panel/market/health").ok().obj()

        assertEquals("DEGRADED", health.getString("runtimeState"), "the market runs degraded")
        assertEquals(false, health.getJsonObject("schema").getBoolean("ok"))
        assertTrue(health.getJsonObject("schema").getJsonArray("missing").any { it.toString().contains("$table#$index") }, "health lists the missing index: ${health.getJsonObject("schema").getJsonArray("missing")}")
        assertEquals(200, E2eClient(lc.url, "platform").get("/api/health").status, "the platform keeps running")

        val visitor = E2eClient(lc.url, "visitor")
        val store = visitor.get("/api/market/store")

        assertEquals(503, store.status)
        assertEquals("STORE_UNAVAILABLE", store.error)
        val checkout = lc.checkout(lc.admin, product)

        assertEquals(503, checkout.status)
        assertEquals("STORE_UNAVAILABLE", checkout.error)

        // the gateway's event is stored DEFERRED and answered 503 so it redelivers later
        val deferredBefore = lc.db.count("market_payment_event", "`status` = 'DEFERRED'")
        val answers = lc.gateway.sendWebhook("payment.succeeded", JsonObject().put("reference", pendingRef).put("amount", lc.gateway.payments.getValue(pendingRef).amount.toPlainString()).put("currency", "EUR"))

        assertEquals(listOf(503), answers.map { it.statusCode() })
        assertEquals(deferredBefore + 1, lc.db.count("market_payment_event", "`status` = 'DEFERRED'"), "the event was stored DEFERRED")
        assertEquals("PENDING", lc.orderStatus(pending), "and not applied")

        // 3. the obstacle goes away: the next start reaches READY and sells again
        lc.sql("DELETE FROM `$table` WHERE `id` = ?", duplicate)
        lc.script("restart")
        lc.afterStart()
        assertEquals(wanted, indexColumns(), "the index is back")
        assertHealthy()
        assertEquals(200, visitor.get("/api/market/store").status)

        // the event the degraded store deferred waits for a replay (the gateway would redeliver it, or the admin replays it): it now completes the pending order once
        val deferredId = lc.db.long("SELECT `id` FROM `pano_market_payment_event` WHERE `status` = 'DEFERRED' ORDER BY `id` DESC LIMIT 1") ?: throw AssertionError("no deferred event")

        lc.admin.post("/api/panel/market/payment-events/$deferredId/replay", JsonObject()).ok()
        lc.awaitOrder(pending, "COMPLETED")
        assertEquals(0L, lc.db.count("market_payment_event", "`status` = 'DEFERRED'"), "no deferred event is left")

        val again = lc.publicIdOf(lc.checkout(lc.admin, product).ok())

        lc.pay(again)
        lc.awaitOrder(again, "COMPLETED")

        lc.assertInvariants()
    }

    // ----------------------------------------------------------------------------------------------------------------------------------
    // L-04b (the 8.1 keys of a table's first CREATE) and L-02b (the default plugin data folder): both are KNOWN PRODUCT GAPS, they fail until fixed
    // ----------------------------------------------------------------------------------------------------------------------------------

    /**
     * 17 L-04 names "one unique index of 00 section 8.1": the credit movement key `market_credit_tx.uq_idem` is declared in its table's first CREATE.
     * `MarketSchema.ensure()` re-creates only the indexes of `added {}` blocks, so after the drop the store stays DEGRADED on every start (MISSING_INDEX).
     * This test expects the repair and FAILS until `ensure()` creates every declared unique index. It puts the index back itself, so the instance stays usable.
     */
    @Test
    @Order(8)
    fun `L-04b a unique key of a table's first CREATE (market_credit_tx uq_idem) is re-created by ensure on the next start`() {
        lc.ensureStandard()

        val table = "pano_market_credit_tx"
        val index = "uq_idem"

        fun indexColumns(): List<String> =
            lc.db.sql(
                "SELECT `COLUMN_NAME` FROM `information_schema`.`STATISTICS` WHERE `TABLE_SCHEMA` = ? AND `TABLE_NAME` = ? AND `INDEX_NAME` = ? AND `NON_UNIQUE` = 0 ORDER BY `SEQ_IN_INDEX`",
                lc.database, table, index
            ).map { it.getString("COLUMN_NAME") }

        assertEquals(listOf("idempotencyKey"), indexColumns(), "the 8.1 credit movement key exists")
        lc.sql("ALTER TABLE `$table` DROP INDEX `$index`")

        try {
            lc.script("restart", degraded = true)
            lc.login()
            assertEquals(listOf("idempotencyKey"), indexColumns(), "ensure() re-created the declared unique index of the first CREATE")
            assertHealthy()
        } finally {
            if (indexColumns().isEmpty()) lc.sql("CREATE UNIQUE INDEX `$index` ON `$table` (`idempotencyKey`)")
            lc.script("restart", degraded = true)
            lc.login()
        }
    }

    /**
     * The scenario "SIGTERM restart keeps the settings and the provider secrets" on the DEFAULT data folder (no `-Dpano.pluginDataDir`), the layout of a real
     * install and of every other E2E class. Known failure while the host's plugin-UI sync takes `config.conf` / `secret.key` away (evidence file, finding 1):
     * the settings revert and both fake providers are NOT_CONFIGURED after the restart.
     */
    @Test
    @Order(9)
    fun `L-02b restart on the default plugin data folder keeps the settings and the provider secrets`() {
        lc.closeGateway()
        lc.defaultDataDir = true

        try {
            lc.script("start")
            lc.login()
            lc.bootstrap()
            assertEquals(true, E2eClient(lc.url, "visitor").get("/api/market/store").obj().getJsonObject("settings").getBoolean("testMode"), "the settings were saved")

            lc.script("restart")
            lc.afterStart()
            assertHealthy()
        } finally {
            lc.defaultDataDir = !System.getenv("MARKET_E2E_LIFECYCLE_DEFAULT_DATA_DIR").isNullOrBlank()
        }
    }

    // ----------------------------------------------------------------------------------------------------------------------------------

    private fun assertHealthy() {
        val health = lc.admin.get("/api/panel/market/health").ok().obj()

        assertEquals("READY", health.getString("runtimeState"), "runtimeState: ${health.getJsonArray("bootstrapErrors")} ${health.getJsonObject("schema")}")
        assertEquals(true, health.getJsonObject("schema").getBoolean("ok"), "schema.ok: ${health.getJsonObject("schema")}")
        assertEquals(0, health.getJsonArray("bootstrapErrors").size(), "bootstrapErrors: ${health.getJsonArray("bootstrapErrors")}")
    }

    /**
     * The instance of this class and everything that drives it. All calls go through `scripts/e2e-instance.sh` with the instance's `--name`, ports and
     * (inherited) `PANO_IT_MARIADB*` environment.
     */
    private class Lifecycle {
        private val base = (System.getenv("MARKET_E2E_LIFECYCLE_PORT_BASE")?.toIntOrNull() ?: 18198)
        private val suffix = System.getenv("MARKET_E2E_LIFECYCLE_SUFFIX")?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: ""
        val name = "lifecycle$suffix"
        val httpPort = base
        val gatewayPort = base + 1
        val url = "http://127.0.0.1:$httpPort"
        val database = "pano_market_e2e_$name"

        private val market: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "scripts/e2e-instance.sh").isFile } ?: throw IllegalStateException("scripts/e2e-instance.sh not found above ${System.getProperty("user.dir")}")
        private val dir = File(market, "build/market-e2e/instance-$name")

        val admin = E2eClient(url, "lc-admin")
        private var dbOrNull: E2eDb? = null
        var gatewayOrNull: FakePayGateway? = null
            private set
        private var standard = false

        /** `true`: instances start with the host's default plugin data folder (`plugins/<id>`), no `-Dpano.pluginDataDir` workaround. */
        var defaultDataDir = !System.getenv("MARKET_E2E_LIFECYCLE_DEFAULT_DATA_DIR").isNullOrBlank()
        private var eventSeed = System.currentTimeMillis().toString(36)

        val db: E2eDb get() = dbOrNull ?: E2eDb(database).also { dbOrNull = it }
        val gateway: FakePayGateway get() = gatewayOrNull ?: throw IllegalStateException("the fake gateway is not open (fresh start first)")

        fun shutdown() {
            closeGateway()
            runCatching { dbOrNull?.close() }
            dbOrNull = null
            // the instance is left as the last scenario left it: a healthy, running one would keep a JVM and two ports, so it is stopped (exact PID)
            if (System.getenv("MARKET_E2E_LIFECYCLE_KEEP").isNullOrBlank()) runCatching { script("stop", expect = null) }
        }

        // ---- the script ---------------------------------------------------------------------------------------------------------

        /** Runs one sub-command; the exit code must be 0 unless [expect] says otherwise (null = anything). Output tail is printed for the log. */
        fun script(command: String, vararg extra: String, expect: Int? = 0, degraded: Boolean = false): Int {
            val freshCommands = setOf("start", "install-legacy", "start-presetup")
            if (command in freshCommands && !extra.contains("--keep")) {
                standard = false
                // a fresh start needs both ports free: whatever the previous scenario left running (exact recorded PID) goes first
                if (command != "stop") script("stop", expect = null)
            }
            val args = listOf("bash", File(market, "scripts/e2e-instance.sh").path, command, *extra, "--name", name, "--http-port", httpPort.toString(), "--gateway-port", gatewayPort.toString())
            val builder = ProcessBuilder(args).directory(market).redirectErrorStream(true)

            // INTERIM WORKAROUND (finding: the host's plugin-UI sync removes and re-creates `plugins/<id>`, taking `config.conf` and `secret.key` with it, see the
            // evidence file): the plugin's data folder is moved out of `plugins/<id>` with `-Dpano.pluginDataDir` (read by PanoPlugin.pluginDataFolder /
            // PluginConfigManager). The option is APPENDED to a MARKET_E2E_JAVA_OPTS the caller exported (the script default is used when there is none), so a
            // caller's own options never switch the workaround off silently. The default layout, the one a real install and every other E2E class use, is proven
            // by `L-02b`, which sets [defaultDataDir] and is expected to FAIL until the product separates config and key from the UI extraction folder.
            // MARKET_E2E_LIFECYCLE_DEFAULT_DATA_DIR=1 runs the WHOLE class on the default layout.
            if (!defaultDataDir) {
                val option = "-Dpano.pluginDataDir=${File(dir, "plugin-data").path}"
                val given = System.getenv("MARKET_E2E_JAVA_OPTS")?.takeIf { it.isNotBlank() } ?: "-XX:MaxRAMPercentage=40"

                builder.environment()["MARKET_E2E_JAVA_OPTS"] = if (given.contains("-Dpano.pluginDataDir=")) given else "$given $option"
            }

            if (degraded) builder.environment()["MARKET_E2E_ALLOW_DEGRADED"] = "1"

            if (command == "install-legacy") {
                // a second legacy payment method row, of a provider that IS installed on the instance (the fake provider), with a plaintext secret (L-01b)
                val extra = File(market, "build/market-e2e/legacy-extra-$name.sql")

                extra.parentFile.mkdirs()
                extra.writeText(
                    "INSERT INTO `pano_market_payment_method` (`id`, `methodId`, `enabled`, `settings`, `createdAt`, `updatedAt`) VALUES " +
                        "(3, 'fake', 0, '{\"gatewayUrl\":\"http://127.0.0.1:$gatewayPort\",\"secret\":\"$LEGACY_FAKE_SECRET\"}', 1700000062000, 1700000062000);\n"
                )
                builder.environment()["MARKET_E2E_LEGACY_EXTRA_SQL"] = extra.path
            }

            val process = builder.start()
            val out = process.inputStream.bufferedReader().readText()

            if (!process.waitFor(900, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw AssertionError("e2e-instance.sh $command did not finish in 15 minutes")
            }
            val tail = out.lines().filter { it.isNotBlank() && !it.startsWith("export ") }.takeLast(4).joinToString(" | ")

            println("lifecycle[$name] e2e-instance.sh $command ${extra.joinToString(" ")} -> exit ${process.exitValue()}: $tail")
            if (expect != null && process.exitValue() != expect) throw AssertionError("e2e-instance.sh $command exited ${process.exitValue()}: $tail")
            return process.exitValue()
        }

        fun pid(): Long = File(dir, "pano.pid").readText().trim().toLong()

        fun alive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

        fun portOpen(port: Int): Boolean = try {
            java.net.Socket("127.0.0.1", port).use { true }
        } catch (e: java.io.IOException) {
            false
        }

        fun log(): String = File(dir, "pano.log").takeIf { it.isFile }?.readText().orEmpty()

        // ---- sessions -----------------------------------------------------------------------------------------------------------

        private fun env() = E2eEnv(url, dir.path, gatewayPort, database)

        fun adminPassword(): String = env().adminPassword()

        fun login() {
            val env = env()
            val answer = admin.login(env.adminUser, env.adminPassword(), panel = true)

            check(answer.status == 200 && admin.csrfToken != null) { "admin login failed: ${answer.status} ${answer.error}" }
        }

        fun closeGateway() {
            runCatching { gatewayOrNull?.close() }
            gatewayOrNull = null
        }

        private fun openGateway() {
            closeGateway()
            gatewayOrNull = FakePayGateway(
                port = gatewayPort, webhookTarget = { "$url/api/market/payments/fake/webhook" }, eventPrefix = "lc${eventSeed}"
            )
        }

        /** 17 section 8.2 steps 3 to 4 on this instance (the catalogue is created by the scenarios that need a product). */
        fun bootstrap() {
            if (gatewayOrNull == null) openGateway()
            // the install step points the platform at `smtp.invalid`: a mail row would retry under a 60 s backoff and `mailsPending` could never drain (as in E2eSession.bootstrap 2a);
            // with the platform's mail switch off the mail job ends each row SKIPPED at its first claim
            admin.multipart(
                "PUT", "/api/panel/settings",
                mapOf("email" to JsonObject().put("enabled", false).put("hostname", "").put("port", 587).put("ssl", false).put("starttls", "DISABLED").put("username", "").put("password", "").put("sender", "").encode())
            ).ok()
            admin.post(
                "/api/panel/market/settings",
                JsonObject()
                    .put("testMode", true).put("currency", "EUR").put("statsCurrency", "EUR").put("vatPercent", 20).put("showVatInPrice", true)
                    .put("allowGuestCheckout", true).put("allowGiftPurchase", true).put("orderExpiryMinutes", 60)
                    .put("checkoutRateLimitPerMinute", 100_000).put("quoteRateLimitPerMinute", 100_000).put("couponLockThreshold", 1000)
                    .put("allowPrivateWebhookTargets", true).put("invoiceEnabled", true).put("sendEmailAfterPurchase", true).put("storeTimeZone", "UTC")
                    .put("storeEnabled", true).put("minimumOrderAmount", 0)
            ).ok()
            admin.post("/api/panel/market/settings/credits", JsonObject().put("creditsEnabled", true).put("creditValue", 1.0).put("allowMixedCreditPayment", true)).ok()
            for (id in listOf("fake", "fake-eur")) {
                admin.post("/api/panel/market/payment-methods/$id", JsonObject().put("settings", JsonObject().put("gatewayUrl", gateway.baseUrl).put("secret", gateway.secret))).ok()
                admin.post("/api/panel/market/payment-methods/$id/toggle", JsonObject().put("enabled", true)).ok()
            }
            awaitProvidersActive()
            standard = true
        }

        /** A running, bootstrapped, healthy instance: the one L-05 left, or a fresh start. */
        fun ensureStandard() {
            if (standard && script("status", expect = null) == 0) {
                login()
                if (admin.get("/api/panel/market/health").status == 200 && gatewayOrNull != null) return
            }
            closeGateway()
            script("start")
            login()
            bootstrap()
        }

        /**
         * What follows every restart of the instance (17 section 9.10 re-runs the 8.2 bootstrap; here the database, the settings (`config.conf`) and the
         * provider secrets (`secret.key`) all survive, so only the cookie jar is new): the admin logs in again, the providers are ACTIVE again and the
         * settings of the first bootstrap are still in force.
         */
        fun afterStart() {
            login()
            awaitProvidersActive()
            val settings = E2eClient(url, "visitor").get("/api/market/store").obj().getJsonObject("settings")

            check(settings.getBoolean("testMode") == true) { "the settings of the bootstrap did not survive the restart (testMode=${settings.getBoolean("testMode")})" }
        }

        fun awaitProvidersActive() {
            var last = ""

            try {
                Await.until(60_000, 500, "providers fake and fake-eur are ACTIVE") {
                    val providers = admin.get("/api/panel/market/payment-providers", log = false).obj().getJsonArray("providers").map { it as JsonObject }

                    last = providers.joinToString { "${it.getString("id")}=${it.getString("state")}" }
                    listOf("fake", "fake-eur").all { id -> providers.firstOrNull { it.getString("id") == id }?.getString("state") == "ACTIVE" }
                }
            } catch (e: com.panomc.plugins.market.support.AwaitTimeout) {
                throw AssertionError("providers are not ACTIVE: $last (log tail: ${log().lines().takeLast(6).joinToString(" | ").take(900)})")
            }
        }

        fun setPlugin(pluginId: String, enabled: Boolean) {
            admin.put("/api/panel/plugins/$pluginId", JsonObject().put("status", enabled)).ok()
        }

        // ---- data ---------------------------------------------------------------------------------------------------------------

        fun sql(statement: String, vararg args: Any?) {
            runBlocking { db.pool.preparedQuery(statement).execute(io.vertx.sqlclient.Tuple.from(args.toList())).coAwait() }
        }

        private var productSeq = 0

        fun product(key: String, price: String, actions: String? = null): Long {
            productSeq++
            val slug = "lc-${key.lowercase()}-${System.currentTimeMillis().toString(36)}-$productSeq"

            return E2eCatalog(admin, db).product(key = key + productSeq, slug = slug, name = "Lifecycle $key $productSeq", price = price, actions = actions)
        }

        fun checkout(client: E2eClient, productId: Long, credits: Number? = null): E2eResponse {
            val body = JsonObject().put("items", JsonArray().add(JsonObject().put("productId", productId).put("quantity", 1))).put("paymentMethodId", "fake")

            credits?.let { body.put("useCredits", it) }

            return client.post("/api/market/checkout", body, mapOf("Idempotency-Key" to UUID.randomUUID().toString()))
        }

        fun publicIdOf(checkout: E2eResponse): String = checkout.obj().getJsonObject("order").getString("publicId")

        fun order(publicId: String): JsonObject = admin.get("/api/market/orders/$publicId").ok().obj().getJsonObject("order")

        fun orderId(publicId: String): Long = db.long("SELECT `id` FROM `pano_market_order` WHERE `publicId` = ?", publicId) ?: throw AssertionError("no order $publicId")

        fun orderStatus(publicId: String): String = db.string("SELECT `status` FROM `pano_market_order` WHERE `publicId` = ?", publicId) ?: "<none>"

        fun reference(publicId: String): String =
            db.string("SELECT p.`reference` FROM `pano_market_payment` p JOIN `pano_market_order` o ON o.`id` = p.`orderId` WHERE o.`publicId` = ? ORDER BY p.`id` DESC LIMIT 1", publicId)
                ?: throw AssertionError("order $publicId has no attempt")

        fun attemptStatus(reference: String): String = db.string("SELECT `status` FROM `pano_market_payment` WHERE `reference` = ?", reference) ?: "<none>"

        fun awaitOrder(publicId: String, status: String, timeoutMs: Long = 60_000) {
            Await.until(timeoutMs, 250, "order $publicId is $status (is ${orderStatus(publicId)})") { orderStatus(publicId) == status }
        }

        /** "Paid via fake": the gateway marks the latest attempt paid and delivers its webhook; returns the reference. */
        fun pay(publicId: String): String {
            val reference = reference(publicId)
            val answers = gateway.pay(reference)

            check(answers.all { it.statusCode() == 200 }) { "the webhook was answered ${answers.map { it.statusCode() }}" }

            return reference
        }

        fun adminId(): Long = db.long("SELECT `id` FROM `pano_user` WHERE `username` = ?", env().adminUser) ?: throw AssertionError("no admin user")

        /** The admin's credit balance x 100. */
        fun creditBalance(): Long = db.long("SELECT `balance` FROM `pano_market_credit_account` WHERE `userId` = ?", adminId()) ?: 0L

        fun holdBalance(): Long = db.long("SELECT `balance` FROM `pano_market_credit_account` WHERE `systemKey` = 'HOLD'") ?: 0L

        fun assertInvariants() {
            var last: JsonObject? = null
            try {
                Await.until(30_000, 250, "queues drained") {
                    val queues = admin.get("/api/panel/market/health", log = false).obj().getJsonObject("queues")
                    last = queues

                    listOf("deliveriesPending", "webhooksPending", "mailsPending", "deferredEvents").all { (queues?.getInteger(it) ?: 0) == 0 }
                }
            } catch (e: com.panomc.plugins.market.support.AwaitTimeout) {
                throw AssertionError("queues did not drain within 30 s, last health queues: ${last?.encode()}", e)
            }
            runBlocking { InvariantChecker.assertAll(db.pool) }
            val run = InvariantChecker.lastRun

            check(run.ran.isNotEmpty() && run.skipped.isEmpty()) { "invariants ran=${run.ran.size} skipped=${run.skipped}" }
        }

        /**
         * Copies one `market_order` row (every column but `id`; `publicId` and `accessToken` are replaced by random values so only `uq_buyer_idem`
         * (`buyerKey`, `idempotencyKey`) is violated by the copy) and returns the new id.
         */
        fun duplicateOrder(orderId: Long): Long {
            val columns = db.sql(
                "SELECT `COLUMN_NAME` FROM `information_schema`.`COLUMNS` WHERE `TABLE_SCHEMA` = ? AND `TABLE_NAME` = 'pano_market_order' AND `COLUMN_NAME` <> 'id' ORDER BY `ORDINAL_POSITION`",
                database
            ).map { it.getString("COLUMN_NAME") }
            val insert = columns.joinToString(", ") { "`$it`" }
            val select = columns.joinToString(", ") {
                when (it) {
                    "publicId" -> "SUBSTRING(REPLACE(UUID(), '-', ''), 1, 20)"
                    "accessToken" -> "SUBSTRING(CONCAT(REPLACE(UUID(), '-', ''), REPLACE(UUID(), '-', '')), 1, 40)"
                    else -> "`$it`"
                }
            }

            sql("INSERT INTO `pano_market_order` ($insert) SELECT $select FROM `pano_market_order` WHERE `id` = ?", orderId)

            return db.long("SELECT MAX(`id`) FROM `pano_market_order`") ?: throw AssertionError("the copy was not inserted")
        }

        // ---- concurrency helpers ------------------------------------------------------------------------------------------------

        fun <T> async(block: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync(block)

        /** A second connection that holds `SELECT ... FOR UPDATE` on the order row until [Held.release] (the transaction is rolled back). */
        fun lockOrder(orderId: Long): Held {
            val connection = runBlocking {
                val c = db.pool.connection.coAwait()

                val tx = c.begin().coAwait()
                c.preparedQuery("SELECT `id` FROM `pano_market_order` WHERE `id` = ? FOR UPDATE").execute(io.vertx.sqlclient.Tuple.of(orderId)).coAwait()
                c to tx
            }

            return Held(connection.first, connection.second)
        }

        class Held(private val connection: SqlConnection, private val transaction: io.vertx.sqlclient.Transaction) {
            fun release() {
                runBlocking {
                    runCatching { transaction.rollback().coAwait() }
                    runCatching { connection.close().coAwait() }
                }
            }
        }
    }
}
