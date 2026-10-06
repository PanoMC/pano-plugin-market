package com.panomc.plugins.market.e2e

import com.panomc.plugins.market.e2e.support.E2eClient
import com.panomc.plugins.market.e2e.support.E2eResponse
import com.panomc.plugins.market.e2e.support.E2eTestBase
import com.panomc.plugins.market.support.Await
import com.panomc.plugins.market.support.FakePayGateway
import com.sun.net.httpserver.HttpServer
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Row
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Refunds and disputes over HTTP (17 section 9.5): RF-01 to RF-09. Every scenario creates the products it asserts counters of, pays through the
 * fake gateway, drives the panel refund routes (`POST /orders/:id/refunds`, `GET /orders/:id/refund-preview`, `retry`) or the signed inbound
 * events of the gateway (`refund.updated`, `dispute.updated`), and reads the books back through the buyer's `OrderView`, the panel routes and SQL
 * assertions. The base class drains the queues and runs I1 to I22 after every scenario.
 *
 * Global state a scenario changes (the refund support of the `fake` provider, `revokeOnRefund`, the gateway's refund mode, a webhook endpoint) is put
 * back in a `finally`.
 */
class RefundE2E : E2eTestBase() {
    override val tag = "rf"

    private val sequence = AtomicInteger()
    private val sinks = CopyOnWriteArrayList<WebhookSink>()

    @AfterAll
    fun closeSinks() {
        sinks.forEach { runCatching { it.close() } }
    }

    // --- fixtures ------------------------------------------------------------------------------------------------------

    /** The permission action of a product: a raw game node written to the Pano permission tables (`via = PANO`), undone by the automatic inverse. */
    private fun permissionActions(vararg nodes: String): String =
        JsonArray().add(
            JsonObject().put("id", "a1").put("type", "PERMISSION").put("phase", "GRANT").put("via", "PANO").put("value", JsonArray(nodes.toList()))
        ).encode()

    private fun node(): String = "essentials.e2erf${System.currentTimeMillis().toString(36)}${sequence.incrementAndGet()}"

    /** A product of its own: [price], optionally with [stock] and a raw permission [node]. */
    private fun product(price: String, stock: Int? = null, node: String? = null): Long {
        val n = sequence.incrementAndGet()

        return catalog.product(
            key = "RF$n", slug = "e2e-rf-${System.currentTimeMillis().toString(36)}-$n", name = "Refund product $n", price = price, stock = stock,
            actions = node?.let { permissionActions(it) }
        )
    }

    private class Paid(val publicId: String, val orderId: Long)

    /** Checkout through the fake provider, "paid via fake" and the wait for `COMPLETED`. */
    private fun pay(client: E2eClient, body: JsonObject, amount: BigDecimal? = null): Paid {
        val publicId = publicIdOf(checkout(client, body).ok())

        payViaFake(publicId, amount)
        awaitOrder(publicId, "COMPLETED")

        return Paid(publicId, orderRow(publicId).getLong("id"))
    }

    /**
     * A live (non-test) paid order: the bank transfer needs no gateway, so the store is taken out of test mode only for the checkout and the
     * approval (the isolated instance has no live gateway, nothing can charge anyone) and `withSettings` puts it back. Test orders earn no creator
     * commission and no cashback (07 section 9.1), so the scenarios that assert those use this.
     */
    private fun liveOrder(buyer: com.panomc.plugins.market.e2e.support.E2eBuyer, body: JsonObject): Paid {
        val accounts = JsonArray().add(JsonObject().put("bank", "E2E Bank").put("holder", "E2E Store").put("iban", "DE89370400440532013000").put("currency", "EUR"))

        admin.post(
            "/api/panel/market/payment-methods/bank-transfer",
            JsonObject().put("settings", JsonObject().put("accounts", accounts.encode()).put("instructions", "Transfer the exact amount."))
        ).ok()
        admin.post("/api/panel/market/payment-methods/bank-transfer/toggle", JsonObject().put("enabled", true)).ok()
        try {
            return session.withSettings(JsonObject().put("testMode", false)) {
                val id = publicIdOf(checkout(buyer.client, body, method = "bank-transfer").ok())

                buyer.client.post("/api/market/orders/$id/bank-transfer/notify", JsonObject().put("senderName", "Ada").put("note", "paid today")).ok()
                admin.post("/api/panel/market/orders/${orderRow(id).getLong("id")}/bank-transfer", JsonObject().put("decision", "APPROVE")).ok()
                awaitOrder(id, "COMPLETED")

                assertEquals(0L, orderRow(id).getLong("testMode"), "the bank transfer order is a live order")

                Paid(id, orderRow(id).getLong("id"))
            }
        } finally {
            admin.post("/api/panel/market/payment-methods/bank-transfer/toggle", JsonObject().put("enabled", false))
        }
    }

    private fun refund(orderId: Long, body: JsonObject, key: String = idempotencyKey()): E2eResponse =
        admin.post("/api/panel/market/orders/$orderId/refunds", body, mapOf("Idempotency-Key" to key))

    private fun refundRows(orderId: Long): List<Row> = db.sql("SELECT * FROM `pano_market_refund` WHERE `orderId` = ? ORDER BY `id`", orderId)

    private fun refundCalls(): List<com.panomc.plugins.market.spi.testkit.Recorded> = gateway.requests(FakePayGateway.Op.REFUND)

    private fun deliveries(orderId: Long, phase: String): List<Row> =
        db.sql("SELECT * FROM `pano_market_delivery` WHERE `orderId` = ? AND `phase` = ? ORDER BY `id`", orderId, phase)

    /** Waits for the `delivery` job: the REVOKE rows of the order exist ([atLeast]) and none is open any more. */
    private fun awaitRevokes(orderId: Long, atLeast: Int = 1): List<Row> = Await.untilValue(120_000, 1000, "the REVOKE rows of order $orderId are settled") {
        deliveries(orderId, "REVOKE").takeIf { rows -> rows.size >= atLeast && rows.none { it.getString("status") in setOf("PENDING", "SCHEDULED", "SENDING") } }
    }

    private fun holdsNode(userId: Long, node: String): Boolean =
        db.count("permission_node", "`holderType` = 'USER' AND `holderId` = ? AND `node` = ? AND `active` = 1", userId, node) > 0

    private fun entitlements(orderId: Long): List<Row> = db.sql("SELECT * FROM `pano_market_entitlement` WHERE `orderId` = ? ORDER BY `id`", orderId)

    private fun itemId(orderId: Long, productId: Long): Long =
        db.long("SELECT `id` FROM `pano_market_order_item` WHERE `orderId` = ? AND `productId` = ?", orderId, productId) ?: throw AssertionError("order $orderId has no line of product $productId")

    private fun refundView(answer: E2eResponse): JsonObject = answer.ok().obj().getJsonObject("refund")

    private fun refundedTotal(client: E2eClient, publicId: String): Double = order(client, publicId).getJsonObject("totals").getDouble("refundedTotal")

    // --- RF-01 ---------------------------------------------------------------------------------------------------------

    @Test
    fun `RF-01 partial refund by amount`() {
        val buyer = buyer()
        val paid = pay(buyer.client, cart(line(product("10.00"))))
        val calls = refundCalls().size

        val first = refund(paid.orderId, JsonObject().put("amount", 4.00))
        val view = refundView(first)

        assertEquals("SUCCEEDED", view.getString("status"))
        assertEquals(4.0, view.getDouble("gatewayAmount"), 0.0001)
        assertEquals("PARTIALLY_REFUNDED", orderStatus(paid.publicId))
        assertEquals(400L, orderRow(paid.publicId).getLong("refundedTotal"))
        assertEquals(4.0, refundedTotal(buyer.client, paid.publicId), 0.0001, "the buyer's OrderView carries refundedTotal")
        assertEquals("PARTIALLY_REFUNDED", order(buyer.client, paid.publicId).getString("status"))
        assertEquals(calls + 1, refundCalls().size, "one call reached the gateway")
        assertEquals("4.00", JsonObject(refundCalls().last().bodyText()).getString("amount"))

        // 7.00 instead of the remaining 6.00: refused before the gateway, with the limit
        val over = refund(paid.orderId, JsonObject().put("amount", 7.00))

        assertEquals(400, over.status)
        assertEquals("INVALID_REFUND_AMOUNT", over.error)
        assertEquals(6.0, over.obj().getDouble("max"), 0.0001, "the answer names the remaining amount")
        assertEquals(calls + 1, refundCalls().size, "a refused request never reaches the gateway")
        assertEquals("PARTIALLY_REFUNDED", orderStatus(paid.publicId))
        assertEquals(1, refundRows(paid.orderId).size)

        val second = refund(paid.orderId, JsonObject().put("amount", 6.00))

        assertEquals("SUCCEEDED", refundView(second).getString("status"))
        assertEquals("REFUNDED", orderStatus(paid.publicId))
        assertEquals(1000L, orderRow(paid.publicId).getLong("refundedTotal"))
        assertEquals(10.0, refundedTotal(buyer.client, paid.publicId), 0.0001)
        assertEquals(setOf("SUCCEEDED"), refundRows(paid.orderId).map { it.getString("status") }.toSet())
        assertEquals(2L, orderEvents(paid.publicId, "REFUND_SUCCEEDED"))
    }

    // --- RF-02 ---------------------------------------------------------------------------------------------------------

    @Test
    fun `RF-02 partial refund by line revokes only that line and restocks`() {
        val nodeA = node()
        val nodeB = node()
        val a = product("6.00", stock = 5, node = nodeA)
        val b = product("4.00", stock = 5, node = nodeB)
        val buyer = buyer()
        val paid = pay(buyer.client, cart(line(a), line(b)))
        val lineA = itemId(paid.orderId, a)
        val lineB = itemId(paid.orderId, b)

        // the grant of both lines was delivered first, so there is something to take back
        Await.until(120_000, 1000, "both ranks were granted") { holdsNode(buyer.userId, nodeA) && holdsNode(buyer.userId, nodeB) }

        val stockBefore = productStock(a)!!
        val soldBefore = db.long("SELECT `soldCount` FROM `pano_market_product` WHERE `id` = ?", a)!!
        val stockOfB = productStock(b)

        val items = JsonArray().add(JsonObject().put("orderItemId", lineA).put("quantity", 1))
        val answer = refund(paid.orderId, JsonObject().put("items", items).put("revoke", true).put("restock", true))

        assertEquals("SUCCEEDED", refundView(answer).getString("status"))

        val refundId = refundRows(paid.orderId).single().getLong("id")
        val lines = db.sql("SELECT * FROM `pano_market_refund_item` WHERE `refundId` = ?", refundId)

        assertEquals(1, lines.size, "one market_refund_item row")
        assertEquals(lineA, lines[0].getLong("orderItemId"))
        assertEquals(1, lines[0].getInteger("quantity"))
        assertEquals(600L, lines[0].getLong("amount"))
        assertEquals(600L, refundRows(paid.orderId).single().getLong("amount"))

        // REVOKE rows for line A only, executed
        val revokes = awaitRevokes(paid.orderId)

        assertTrue(revokes.all { it.getLong("orderItemId") == lineA }, "undo rows only for the refunded line: ${revokes.map { it.getLong("orderItemId") }}")
        assertTrue(revokes.all { it.getString("status") == "CONFIRMED" }, "the undo rows were executed: ${revokes.map { it.getString("status") }}")
        assertFalse(holdsNode(buyer.userId, nodeA), "the node of the refunded line is gone")
        assertTrue(holdsNode(buyer.userId, nodeB), "the other line keeps its node")

        val rowA = db.sql("SELECT * FROM `pano_market_order_item` WHERE `id` = ?", lineA).single()
        val rowB = db.sql("SELECT * FROM `pano_market_order_item` WHERE `id` = ?", lineB).single()

        assertEquals(1, rowA.getInteger("refundedQuantity"))
        assertEquals(600L, rowA.getLong("refundedAmount"))
        assertEquals(0, rowB.getInteger("refundedQuantity"))
        assertEquals(0L, rowB.getLong("refundedAmount"))
        assertEquals(stockBefore + 1, productStock(a), "the unit went back to the shelf")
        // a test-mode order never counts as sold (I17), so the counter has nothing to give back; it must not go below what it was
        assertEquals(soldBefore, db.long("SELECT `soldCount` FROM `pano_market_product` WHERE `id` = ?", a), "the sold counter of a test order does not move")
        assertEquals(stockOfB, productStock(b), "the other product is untouched")

        val byItem = entitlements(paid.orderId).associateBy { it.getLong("orderItemId") }

        assertEquals("REVOKED", byItem.getValue(lineA).getString("status"))
        assertEquals("ACTIVE", byItem.getValue(lineB).getString("status"))
        assertEquals("PARTIALLY_REFUNDED", orderStatus(paid.publicId))
    }

    // --- RF-03 ---------------------------------------------------------------------------------------------------------

    @Test
    fun `RF-03 a mixed payment refund is split between gateway and credits`() {
        val buyer = buyer()
        val productId = product("30.00")

        admin.post(
            "/api/panel/market/credits/accounts/${buyer.userId}/grant", JsonObject().put("amount", 10).put("note", "rf03 seed"), mapOf("Idempotency-Key" to idempotencyKey())
        ).ok()

        val publicId = publicIdOf(checkout(buyer.client, cart(line(productId)).put("useCredits", 10)).ok())
        val totals = order(buyer.client, publicId).getJsonObject("totals")

        assertEquals(10.0, totals.getDouble("creditAmount"), 0.0001)
        assertEquals(20.0, totals.getDouble("gatewayAmount"), 0.0001)

        payViaFake(publicId)
        awaitOrder(publicId, "COMPLETED")

        val orderId = orderRow(publicId).getLong("id")

        assertEquals(0.0, buyer.client.get("/api/market/me/credits").ok().obj().getDouble("balance"), 0.0001, "the credits were spent")

        // preview: proportional, a warning, nothing written
        val preview = admin.get("/api/panel/market/orders/$orderId/refund-preview?amount=15").ok().obj()

        assertEquals(10.0, preview.getDouble("gatewayAmount"), 0.0001)
        assertEquals(5.0, preview.getDouble("creditValue"), 0.0001)
        assertEquals(5.0, preview.getDouble("creditAmount"), 0.0001)
        assertEquals(15.0, preview.getDouble("gatewayAmount") + preview.getDouble("creditValue"), 0.0001)
        assertTrue(!preview.getJsonArray("warnings").isEmpty, "a mixed split warns")
        assertTrue(preview.getJsonArray("warnings").any { (it as JsonObject).getString("code") == "MIXED_PAYMENT_SPLIT" })
        assertEquals(0, refundRows(orderId).size, "a preview writes nothing")

        val calls = refundCalls().size
        val done = refund(orderId, JsonObject().put("amount", 15.00))
        val view = refundView(done)

        assertEquals(10.0, view.getDouble("gatewayAmount"), 0.0001)
        assertEquals(5.0, view.getDouble("creditAmount"), 0.0001)
        assertEquals(calls + 1, refundCalls().size)
        assertEquals("10.00", JsonObject(refundCalls().last().bodyText()).getString("amount"), "the gateway is asked for its share only")

        val refundId = refundRows(orderId).single().getLong("id")
        val ledger = db.sql("SELECT * FROM `pano_market_credit_tx` WHERE `type` = 'REFUND' AND `userId` = ?", buyer.userId)

        assertEquals(1, ledger.size)
        assertEquals(500L, ledger[0].getLong("amount"), "ledger REFUND 5.00")
        assertEquals(refundId, ledger[0].getLong("refundId"))
        assertEquals(5.0, buyer.client.get("/api/market/me/credits").ok().obj().getDouble("balance"), 0.0001)
        assertEquals("PARTIALLY_REFUNDED", orderStatus(publicId))
        assertEquals(1500L, orderRow(publicId).getLong("refundedTotal"))

        // an override inside the bounds is accepted: gateway 3.00 and 2.00 credits
        val override = refundView(refund(orderId, JsonObject().put("gatewayAmount", 3.00).put("creditAmount", 2.00)))

        assertEquals(3.0, override.getDouble("gatewayAmount"), 0.0001)
        assertEquals(2.0, override.getDouble("creditAmount"), 0.0001)
        assertEquals(2000L, orderRow(publicId).getLong("refundedTotal"))
        assertEquals(7.0, buyer.client.get("/api/market/me/credits").ok().obj().getDouble("balance"), 0.0001)

        // a gateway part over the rest is refused with the limit
        val tooMuch = refund(orderId, JsonObject().put("gatewayAmount", 8.00))

        assertEquals(400, tooMuch.status)
        assertEquals("INVALID_REFUND_AMOUNT", tooMuch.error)
        assertEquals(7.0, tooMuch.obj().getDouble("maxGateway"), 0.0001)
        assertEquals(2, refundRows(orderId).size, "the refused request wrote nothing")
    }

    // --- RF-04 ---------------------------------------------------------------------------------------------------------

    @Test
    fun `RF-04 an asynchronous refund waits for the gateway and ends through the webhook`() {
        val buyer = buyer()
        val paid = pay(buyer.client, cart(line(product("10.00"))))

        gateway.refundMode = FakePayGateway.RefundMode.PENDING
        try {
            val view = refundView(refund(paid.orderId, JsonObject().put("amount", 10.00)))

            assertEquals("PENDING", view.getString("status"))
            assertEquals("COMPLETED", orderStatus(paid.publicId), "the order does not move before the money does")
            assertEquals(0L, orderRow(paid.publicId).getLong("refundedTotal"))
            assertEquals(true, order(buyer.client, paid.publicId).getBoolean("refundPending"))

            val row = refundRows(paid.orderId).single()
            val gatewayRefundId = row.getString("gatewayRefundId")

            assertNotNull(gatewayRefundId, "the gateway named its refund")
            assertNotNull(row.getValue("nextQueryAt"), "a pending refund is polled later")

            // the webhook ends it (O10)
            val reference = referenceOf(paid.publicId)
            val answers = gateway.sendWebhook(
                "refund.updated",
                JsonObject().put("reference", reference).put("state", "SUCCEEDED").put("amount", "10.00").put("currency", "EUR").put("refundId", gatewayRefundId)
            )

            assertEquals(listOf(200), answers.map { it.statusCode() })
            awaitOrder(paid.publicId, "REFUNDED")
            assertEquals("SUCCEEDED", refundRows(paid.orderId).single().getString("status"))
            assertEquals(1000L, orderRow(paid.publicId).getLong("refundedTotal"))
            assertEquals(false, order(buyer.client, paid.publicId).getBoolean("refundPending"))

            // without the webhook: the gateway decided, the RefundReconcileJob asks for it when nextQueryAt is due
            val second = pay(buyer.client, cart(line(product("10.00"))))
            val pending = refundView(refund(second.orderId, JsonObject().put("amount", 10.00)))

            assertEquals("PENDING", pending.getString("status"))

            val polled = refundRows(second.orderId).single()
            val polledId = polled.getString("gatewayRefundId")
            val record = gateway.refunds.getValue(polledId)

            gateway.refunds[polledId] = FakePayGateway.RefundRecord(record.id, record.paymentId, record.amount, "succeeded")
            db.rewind("market_refund", polled.getLong("id"), "nextQueryAt", 3_600_000)

            Await.until(150_000, 2000, "the reconcile job applied the gateway's answer") { orderStatus(second.publicId) == "REFUNDED" }
            assertEquals("SUCCEEDED", refundRows(second.orderId).single().getString("status"))
            assertEquals(1000L, orderRow(second.publicId).getLong("refundedTotal"))
            assertTrue(gateway.requests(FakePayGateway.Op.QUERY_REFUND).any { it.path.endsWith("/$polledId") }, "the job asked the gateway about the refund")
        } finally {
            gateway.refundMode = FakePayGateway.RefundMode.SUCCEEDED
        }
    }

    // --- RF-05 ---------------------------------------------------------------------------------------------------------

    @Test
    fun `RF-05 a refused refund stays FAILED and the retry uses the same idempotency key`() {
        val buyer = buyer()
        val paid = pay(buyer.client, cart(line(product("10.00"))))
        val calls = refundCalls().size

        gateway.failNext(FakePayGateway.Op.REFUND, 500)

        val failed = refund(paid.orderId, JsonObject().put("amount", 4.00))

        assertEquals(502, failed.status, "the gateway refused this very call")
        assertEquals("PAYMENT_PROVIDER_ERROR", failed.error)

        val row = refundRows(paid.orderId).single()

        assertEquals("FAILED", row.getString("status"))
        assertEquals("COMPLETED", orderStatus(paid.publicId), "the order is untouched")
        assertEquals(0L, orderRow(paid.publicId).getLong("refundedTotal"))
        assertEquals(calls + 1, refundCalls().size)

        val retried = admin.post("/api/panel/market/refunds/${row.getLong("id")}/retry", JsonObject())

        assertEquals("SUCCEEDED", refundView(retried).getString("status"))
        assertEquals("SUCCEEDED", refundRows(paid.orderId).single().getString("status"))
        assertEquals(400L, orderRow(paid.publicId).getLong("refundedTotal"))
        assertEquals("PARTIALLY_REFUNDED", orderStatus(paid.publicId))

        val sent = refundCalls().drop(calls)

        assertEquals(2, sent.size, "the retry is a second call")
        assertNotNull(sent[0].header("Idempotency-Key"))
        assertEquals(sent[0].header("Idempotency-Key"), sent[1].header("Idempotency-Key"), "the same key reached the gateway twice")
        assertEquals(JsonObject(sent[0].bodyText()).getString("amount"), JsonObject(sent[1].bodyText()).getString("amount"))

        // a settled refund is not retried again
        val again = admin.post("/api/panel/market/refunds/${row.getLong("id")}/retry", JsonObject())

        assertEquals(409, again.status)
        assertEquals("INVALID_STATE", again.error)
    }

    // --- RF-06 ---------------------------------------------------------------------------------------------------------

    private fun saveFake(refundSupport: String) {
        admin.post(
            "/api/panel/market/payment-methods/fake",
            JsonObject().put("settings", JsonObject().put("gatewayUrl", gateway.baseUrl).put("secret", gateway.secret).put("refundSupport", refundSupport))
        ).ok()
    }

    @Test
    fun `RF-06 a provider without refund support refuses and a manual refund settles without the gateway`() {
        val buyer = buyer()
        val paid = pay(buyer.client, cart(line(product("10.00"))))

        saveFake("NONE")
        try {
            val calls = refundCalls().size
            val refused = refund(paid.orderId, JsonObject().put("amount", 4.00))

            assertEquals(400, refused.status)
            assertEquals("REFUND_NOT_SUPPORTED", refused.error)
            assertEquals(0, refundRows(paid.orderId).size)

            // a manual refund needs a reason ...
            val noReason = refund(paid.orderId, JsonObject().put("amount", 4.00).put("manual", true))

            assertEquals(400, noReason.status, "manual without a reason: ${noReason.error}")
            assertEquals(0, refundRows(paid.orderId).size)

            // ... and then settles at once, without a gateway call
            val manual = refund(paid.orderId, JsonObject().put("amount", 4.00).put("manual", true).put("reason", "bank transfer back"))

            assertEquals("SUCCEEDED", refundView(manual).getString("status"))

            val row = refundRows(paid.orderId).single()

            assertNull(row.getValue("providerId"))
            assertNull(row.getValue("paymentId"))
            assertEquals("bank transfer back", row.getString("reason"))
            assertEquals(calls, refundCalls().size, "no gateway call")
            assertEquals(400L, orderRow(paid.publicId).getLong("refundedTotal"))
            assertEquals("PARTIALLY_REFUNDED", orderStatus(paid.publicId))
        } finally {
            saveFake("PARTIAL")
        }

        val providers = admin.get("/api/panel/market/payment-providers").ok().obj().getJsonArray("providers").map { it as JsonObject }

        assertEquals("ACTIVE", providers.first { it.getString("id") == "fake" }.getString("state"), "the fake provider is back to normal")
    }

    // --- RF-07 ---------------------------------------------------------------------------------------------------------

    @Test
    fun `RF-07 a refund made at the gateway becomes a GATEWAY row`() {
        val node = node()
        val buyer = buyer()
        val paid = pay(buyer.client, cart(line(product("10.00", node = node))))
        val reference = referenceOf(paid.publicId)
        val dashboardId = "dash_${System.nanoTime()}"
        val event = JsonObject().put("reference", reference).put("state", "SUCCEEDED").put("amount", "4.00").put("currency", "EUR").put("refundId", dashboardId)

        assertEquals(listOf(200), gateway.sendWebhook("refund.updated", event).map { it.statusCode() })

        val rows = refundRows(paid.orderId)

        assertEquals(1, rows.size)
        assertEquals("GATEWAY", rows[0].getString("origin"))
        assertEquals("SUCCEEDED", rows[0].getString("status"))
        assertEquals(400L, rows[0].getLong("gatewayAmount"))
        assertEquals(dashboardId, rows[0].getString("gatewayRefundId"))
        assertEquals("PARTIALLY_REFUNDED", orderStatus(paid.publicId))
        assertEquals(400L, orderRow(paid.publicId).getLong("refundedTotal"))
        assertEquals(4.0, refundedTotal(buyer.client, paid.publicId), 0.0001)
        assertEquals(setOf("ACTIVE"), entitlements(paid.orderId).map { it.getString("status") }.toSet(), "a partial gateway refund revokes nothing")
        assertEquals(0, deliveries(paid.orderId, "REVOKE").size)

        // the same notification again (a new event id for the same refund) is no second refund
        assertEquals(listOf(200), gateway.sendWebhook("refund.updated", event).map { it.statusCode() })
        assertEquals(1, refundRows(paid.orderId).size)
        assertEquals(400L, orderRow(paid.publicId).getLong("refundedTotal"))
    }

    // --- RF-08 ---------------------------------------------------------------------------------------------------------

    /** A loopback HTTP server that records every body it receives (the store webhook sink). */
    private class WebhookSink : AutoCloseable {
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val received = CopyOnWriteArrayList<JsonObject>()
        val url: String get() = "http://127.0.0.1:${server.address.port}/hook"

        init {
            server.createContext("/hook") { exchange ->
                val text = exchange.requestBody.readBytes().toString(Charsets.UTF_8)

                runCatching { received += JsonObject(text) }
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    private fun dispute(reference: String, state: String, disputeId: String, amount: String): List<Int> =
        gateway.sendWebhook(
            "dispute.updated",
            JsonObject().put("reference", reference).put("state", state).put("disputeId", disputeId).put("amount", amount).put("currency", "EUR").put("reason", "fraudulent")
        ).map { it.statusCode() }

    private fun blocks(orderId: Long): List<Row> = db.sql("SELECT * FROM `pano_market_block` WHERE `orderId` = ? ORDER BY `id`", orderId)

    @Test
    fun `RF-08 a chargeback revokes, blocks and reports, and winning the dispute restores the order`() {
        val sink = WebhookSink().also { sinks += it }
        val endpointId = admin.post(
            "/api/panel/market/webhooks",
            JsonObject().put("name", "E2E dispute sink").put("url", sink.url).put("events", JsonArray().add("order.chargeback").add("order.chargeback.won"))
                .put("format", "JSON").put("signing", "NONE")
        ).ok().obj().getLong("id")

        try {
            val node = node()
            val buyer = buyer()
            val paid = pay(buyer.client, cart(line(product("10.00", node = node))))
            val reference = referenceOf(paid.publicId)
            val disputeId = "dp_${System.nanoTime()}"

            Await.until(120_000, 1000, "the rank was granted") { holdsNode(buyer.userId, node) }

            val paidTotal = decimal(orderRow(paid.publicId).getLong("totalPrice")).movePointLeft(2).toPlainString()

            // OPENED
            assertEquals(listOf(200), dispute(reference, "OPENED", disputeId, paidTotal))

            val opened = orderRow(paid.publicId)

            assertEquals("CHARGEBACK", opened.getString("status"))
            assertEquals("COMPLETED", opened.getString("statusBeforeDispute"))
            assertEquals("OPEN", db.string("SELECT `status` FROM `pano_market_dispute` WHERE `orderId` = ?", paid.orderId))

            val revokes = awaitRevokes(paid.orderId)

            assertTrue(revokes.all { it.getString("status") == "CONFIRMED" }, "the undo rows were executed: ${revokes.map { it.getString("status") }}")
            assertFalse(holdsNode(buyer.userId, node), "the chargeback took the rank back")
            assertEquals(setOf("REVOKED"), entitlements(paid.orderId).map { it.getString("status") }.toSet())
            assertEquals(setOf("CHARGEBACK"), entitlements(paid.orderId).map { it.getString("endReason") }.toSet())

            val granted = deliveries(paid.orderId, "GRANT").size

            val blockRows = blocks(paid.orderId)

            assertEquals(setOf("CHARGEBACK"), blockRows.map { it.getString("source") }.toSet(), "every block of the order comes from the chargeback")
            assertTrue(blockRows.map { it.getString("type") }.containsAll(listOf("PLAYER", "EMAIL", "USER")), "player, e-mail and payer are blocked: ${blockRows.map { it.getString("type") }}")
            Await.until(60_000, 500, "the order.chargeback webhook reached the sink") { sink.received.any { it.getString("event") == "order.chargeback" } }
            assertEquals(1, sink.received.count { it.getString("event") == "order.chargeback" })
            assertEquals(1L, db.count("market_webhook_delivery", "`event` = 'order.chargeback' AND `orderId` = ? AND `endpointId` = ?", paid.orderId, endpointId))

            // the next checkout of this buyer is refused
            val blocked = checkout(buyer.client, cart(line(product("3.00"))))

            assertEquals(403, blocked.status)
            assertEquals("BUYER_BLOCKED", blocked.error)

            // WON: the status comes back, the blocks go, nothing is granted again
            assertEquals(listOf(200), dispute(reference, "WON", disputeId, paidTotal))

            assertEquals("COMPLETED", orderStatus(paid.publicId), "the status before the dispute is restored")
            assertEquals("WON", db.string("SELECT `status` FROM `pano_market_dispute` WHERE `orderId` = ?", paid.orderId))
            assertEquals(0, blocks(paid.orderId).size, "the blocks of the order are removed")
            assertEquals(setOf("REVOKED"), entitlements(paid.orderId).map { it.getString("status") }.toSet(), "no automatic re-grant")
            assertEquals(granted, deliveries(paid.orderId, "GRANT").size, "no new GRANT row")
            assertFalse(holdsNode(buyer.userId, node))

            Await.until(60_000, 500, "the order.chargeback.won webhook reached the sink") { sink.received.any { it.getString("event") == "order.chargeback.won" } }
            assertEquals(1, sink.received.count { it.getString("event") == "order.chargeback.won" })

            val again = checkout(buyer.client, cart(line(product("3.00"))))

            assertEquals(200, again.status, "the buyer can buy again: ${again.error}")

            // the same WON again changes nothing
            assertEquals(listOf(200), dispute(reference, "WON", disputeId, paidTotal))
            assertEquals("COMPLETED", orderStatus(paid.publicId))
            assertEquals(1, sink.received.count { it.getString("event") == "order.chargeback.won" })

            // the commission of a creator code: only a live order earns one (a test order never does), so this half runs on a live bank transfer order
            // and opens the dispute from the panel (the fake gateway cannot report a dispute on another provider's attempt); O11 is the same service
            val creator = buyer(canPay = false)
            val live = liveOrder(creator, cart(line(product("10.00"))).put("creatorCode", "STREAMER"))
            val earned = db.sql("SELECT * FROM `pano_market_creator_earning` WHERE `orderId` = ?", live.orderId).singleOrNull()

            assertNotNull(earned, "the creator code earned a commission on a live order")
            assertTrue(earned!!.getLong("amount") > 0 && earned.getString("state") != "REVERSED")

            val manual = admin.post("/api/panel/market/orders/${live.orderId}/disputes", JsonObject().put("reason", "e2e chargeback")).ok().obj().getLong("id")

            assertEquals("CHARGEBACK", orderStatus(live.publicId))
            assertEquals("MANUAL", db.string("SELECT `origin` FROM `pano_market_dispute` WHERE `id` = ?", manual))
            assertEquals("REVERSED", db.string("SELECT `state` FROM `pano_market_creator_earning` WHERE `orderId` = ?", live.orderId), "the commission was taken back")
            assertEquals(earned.getLong("amount"), db.long("SELECT `reversedAmount` FROM `pano_market_creator_earning` WHERE `orderId` = ?", live.orderId))
            assertTrue(blocks(live.orderId).isNotEmpty(), "the manual chargeback blocks the buyer too")

            admin.put("/api/panel/market/disputes/$manual", JsonObject().put("status", "WON")).ok()

            assertEquals("COMPLETED", orderStatus(live.publicId))
            assertEquals(0, blocks(live.orderId).size)
        } finally {
            admin.delete("/api/panel/market/webhooks/$endpointId")
        }
    }

    // --- RF-09 ---------------------------------------------------------------------------------------------------------

    @Test
    fun `RF-09 a refund without revoke leaves the goods and plans no REVOKE rows`() {
        val node = node()
        val buyer = buyer()
        val paid = pay(buyer.client, cart(line(product("10.00", node = node))))

        Await.until(120_000, 1000, "the rank was granted") { holdsNode(buyer.userId, node) }

        session.withSettings(JsonObject().put("revokeOnRefund", false)) {
            val view = refundView(refund(paid.orderId, JsonObject().put("amount", 10.00).put("revoke", false)))

            assertEquals("SUCCEEDED", view.getString("status"))
            assertEquals(0L, refundRows(paid.orderId).single().getLong("revoke"), "the row records that nothing is revoked")
        }

        assertEquals("REFUNDED", orderStatus(paid.publicId))
        assertEquals(0, deliveries(paid.orderId, "REVOKE").size, "no undo row is planned")
        assertEquals(setOf("ACTIVE"), entitlements(paid.orderId).map { it.getString("status") }.toSet(), "the entitlement stays ACTIVE (I20 respects the setting)")
        assertTrue(holdsNode(buyer.userId, node), "the rank stays")

        // the setting alone: with revokeOnRefund off and no flag in the request nothing is revoked either; an explicit revoke=true overrides it
        val otherNode = node()
        val other = pay(buyer.client, cart(line(product("10.00", node = otherNode))))

        Await.until(120_000, 1000, "the second rank was granted") { holdsNode(buyer.userId, otherNode) }

        session.withSettings(JsonObject().put("revokeOnRefund", false)) {
            refundView(refund(other.orderId, JsonObject().put("amount", 10.00).put("revoke", true)))
        }

        assertTrue(awaitRevokes(other.orderId).isNotEmpty(), "an explicit revoke overrides the setting")
        assertEquals(setOf("REVOKED"), entitlements(other.orderId).map { it.getString("status") }.toSet())
        assertFalse(holdsNode(buyer.userId, otherNode))

        // the kept goods of the first order are taken back later by hand (no money moves); this also leaves the instance consistent for I20, which
        // follows the stored setting (true again), not the one of the moment the refund was made
        val taken = admin.post("/api/panel/market/orders/${paid.orderId}/revoke", JsonObject()).ok().obj()

        assertTrue(taken.getInteger("created") > 0, "the manual revoke planned undo rows")
        assertTrue(awaitRevokes(paid.orderId).isNotEmpty())
        assertEquals(setOf("REVOKED"), entitlements(paid.orderId).map { it.getString("status") }.toSet())
        assertFalse(holdsNode(buyer.userId, node))
        assertEquals(1000L, orderRow(paid.publicId).getLong("refundedTotal"), "and the refund stays as it was")
    }
}
