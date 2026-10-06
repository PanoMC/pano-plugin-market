package com.panomc.plugins.market.service

import com.panomc.plugins.market.core.delivery.ProductAction
import com.panomc.plugins.market.core.refund.RefundMath
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.CreditTxType
import com.panomc.plugins.market.db.model.DeliveryActionType
import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.db.model.DeliveryStatus
import com.panomc.plugins.market.db.model.EntitlementStatus
import com.panomc.plugins.market.db.model.InvoiceType
import com.panomc.plugins.market.db.model.MailKind
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.model.RefundOrigin
import com.panomc.plugins.market.db.model.RefundStatus
import com.panomc.plugins.market.db.model.WebhookDeliveryStatus
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.payment.PaymentCapabilities
import com.panomc.plugins.market.spi.payment.RefundResult
import com.panomc.plugins.market.spi.payment.RefundState
import com.panomc.plugins.market.spi.payment.RefundSupport
import com.panomc.plugins.market.support.FakePaymentProvider.Op
import com.panomc.plugins.market.support.MarketTestDb
import com.panomc.plugins.market.support.TestUser
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `RefundService` on a real MariaDB (MK-111; 21 sections 2 to 4, 07 section 7, 08 section 11.2; tests RF-01 to RF-07, RF-09, V-04, V-05, RD-D1 to RD-D3, RD-D4, RD-D7,
 * RD-D8, RD-D11, R-14, R-15): the request (idempotency, remainders, split, capability), the provider call and its outcomes, O10 (books, lines, status, revoke,
 * restock, codes, cashback and top-up clawback, credit note, mail, webhook), `revokeFirst`, manual refunds and the inbound events. Invariants I1 to I22 are checked
 * after every test by the base class.
 */
class RefundServiceIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var r: RefundWorld
    private val vertx: Vertx = Vertx.vertx()
    private var skipInvariants = false

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    @BeforeEach
    fun wire() {
        skipInvariants = false
        steveUser = null
        w = TestWiring(pool)
        r = RefundWorld(w, vertx)
    }

    override suspend fun assertInvariants() {
        // the invariants with the switches of this test's config (I20 follows revokeOnRefund)
        if (!skipInvariants) w.assertInvariants()
    }

    private val service get() = r.service
    private var steveUser: TestUser? = null

    private suspend fun steve(): TestUser = steveUser ?: w.fixtures.user("Steve").also { steveUser = it }

    private fun credit(id: String, credits: Long) = ProductAction(id = id, type = DeliveryActionType.CREDIT, credit = credits)

    private fun permission(id: String, vararg nodes: String) = ProductAction(id = id, type = DeliveryActionType.PERMISSION, nodes = nodes.toList())

    private suspend fun revokeRows(orderId: Long) = r.d.rows(orderId).filter { it.phase == DeliveryPhase.REVOKE }

    private suspend fun ledger(type: CreditTxType) = w.creditTxs.getByUserId(w.users.idOf("Steve")!!, 100, pool).filter { it.type == type }

    // ===== RF-01 partial refunds by amount ========================================================================================

    @Test
    fun `partial refunds by amount book up, the last one empties the order and an amount over the remainder is refused (RF-01)`(): Unit = runBlocking {
        val paid = r.place(steve(), listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))))
        val first = service.request(paid.order.id, RefundInput(amount = 400), r.key(), null)

        assertEquals(RefundStatus.SUCCEEDED, first.refund.status)
        assertFalse(first.replay)

        var order = r.order(paid.order.id)

        assertEquals(OrderStatus.PARTIALLY_REFUNDED, order.status)
        assertEquals(400, order.refundedTotal)
        assertEquals(400, order.refundedGatewayAmount)
        assertEquals(400, r.attempt(paid.attempt.id).refundedAmount)

        val call = r.fake.calls(Op.REFUND).single().request as com.panomc.plugins.market.spi.payment.RefundRequest

        assertEquals(400, call.amount.amount)
        assertEquals(first.refund.idempotencyKey, call.idempotencyKey)
        assertFalse(call.full)

        // 7.00 instead of the remaining 6.00
        val body = r.expect("INVALID_REFUND_AMOUNT", 400) { service.request(paid.order.id, RefundInput(amount = 700), r.key(), null) }

        assertEquals(6.0, body.getDouble("max"), 0.0)
        assertEquals(1, r.fake.calls(Op.REFUND).size, "a refused request never reaches the gateway")

        val second = service.request(paid.order.id, RefundInput(amount = 600), r.key(), null)

        assertEquals(RefundStatus.SUCCEEDED, second.refund.status)

        order = r.order(paid.order.id)

        assertEquals(OrderStatus.REFUNDED, order.status)
        assertEquals(1000, order.refundedTotal)
        assertEquals(1000, r.attempt(paid.attempt.id).refundedAmount)
        assertEquals(EntitlementStatus.REVOKED, w.entitlements.getByOrderItemId(paid.items[0].id, pool).single().status, "the empty order took the goods back")
        assertEquals(2, w.orderEvents.getByOrderId(paid.order.id, pool).count { it.type == OrderEventType.REFUND_SUCCEEDED })
        assertEquals(setOf(RefundStatus.SUCCEEDED), r.refunds(paid.order.id).map { it.status }.toSet())
    }

    // ===== RF-02 refund by line, revoke and restock ==========================================================================

    @Test
    fun `a refund by line revokes only that line, gives the stock and the sold units back and keeps the line rows (RF-02, RD-D8)`(): Unit = runBlocking {
        val u = steve()
        val paid = r.place(
            u,
            listOf(
                RefundLine(600, quantity = 2, actions = listOf(permission("a1", "group.vip"), credit("a2", 100)), reservedStock = 2),
                RefundLine(400, actions = listOf(credit("a3", 50)))
            )
        )
        val line1 = paid.items[0]
        val line2 = paid.items[1]
        val before = w.products.getById(paid.products[0].id, pool)!!

        assertEquals(10, before.stock, "8 left after the two reserved units")
        assertEquals(2, before.soldCount)

        val done = service.request(paid.order.id, RefundInput(items = listOf(RefundMath.ItemRequest(line1.id, 1)), restock = true), r.key(), null)

        assertEquals(300, done.refund.amount)

        val items = r.items(paid.order.id).associateBy { it.id }

        assertEquals(1, items.getValue(line1.id).refundedQuantity)
        assertEquals(300, items.getValue(line1.id).refundedAmount)
        assertEquals(0, items.getValue(line2.id).refundedQuantity)
        assertEquals(1, items.getValue(line1.id).stockReserved, "one reserved unit went back")
        assertEquals(11, w.products.getById(paid.products[0].id, pool)!!.stock)
        assertEquals(1, w.products.getById(paid.products[0].id, pool)!!.soldCount)
        assertEquals(OrderStatus.PARTIALLY_REFUNDED, r.order(paid.order.id).status)

        val lines = w.refundItems.getByRefundId(done.refund.id, pool)

        assertEquals(listOf(Triple(line1.id, 1, 300L)), lines.map { Triple(it.orderItemId, it.quantity, it.amount) })

        val revokes = revokeRows(paid.order.id)

        assertTrue(revokes.isNotEmpty() && revokes.all { it.orderItemId == line1.id }, "undo rows for the refunded line only: $revokes")
        assertEquals(listOf(DeliveryActionType.CREDIT), revokes.map { it.actionType }, "the rank goes only with the last unit, the credits with every unit")
        assertEquals(EntitlementStatus.ACTIVE, w.entitlements.getByOrderItemId(line1.id, pool).single().status)

        // the last unit takes the remainder and ends the line
        val rest = service.request(paid.order.id, RefundInput(items = listOf(RefundMath.ItemRequest(line1.id, 1))), r.key(), null)

        assertEquals(300, rest.refund.amount)
        assertEquals(EntitlementStatus.REVOKED, w.entitlements.getByOrderItemId(line1.id, pool).single().status)
        assertTrue(revokeRows(paid.order.id).any { it.actionType == DeliveryActionType.PERMISSION })
        assertEquals(0, w.products.getById(paid.products[0].id, pool)!!.soldCount)
        assertEquals(EntitlementStatus.ACTIVE, w.entitlements.getByOrderItemId(line2.id, pool).single().status)

        // an amount-only refund of 30 over a 60 / 40 order spreads 18 / 12 (RD-D8)
        val other = r.place(u, listOf(RefundLine(600), RefundLine(400)))

        service.request(other.order.id, RefundInput(amount = 300), r.key(), null)

        assertEquals(listOf(180L, 120L), r.items(other.order.id).sortedBy { it.id }.map { it.refundedAmount })
        assertEquals(listOf(0, 0), r.items(other.order.id).sortedBy { it.id }.map { it.refundedQuantity }, "quantities move only when the order is empty")
    }

    // ===== RF-03 mixed refund, preview equals the row =========================================================================

    @Test
    fun `a mixed refund is one row with both parts, the preview says what the row holds and the credits go back once (RF-03)`(): Unit = runBlocking {
        val u = steve()
        val paid = r.place(u, listOf(RefundLine(3000)), creditValue = 1000)

        assertEquals(0, w.fixtures.creditBalance(u))

        val input = RefundInput(amount = 1500)
        val preview = service.preview(paid.order.id, input)

        assertEquals(1000, preview.split.gatewayPart)
        assertEquals(500, preview.split.creditValuePart)
        assertEquals(500, preview.split.creditPart)
        assertTrue(preview.warnings.any { it.getString("code") == "MIXED_PAYMENT_SPLIT" })
        assertEquals(0, r.refunds(paid.order.id).size, "a preview writes nothing")

        val done = service.request(paid.order.id, input, r.key(), null)
        val row = r.refund(done.refund.id)

        assertEquals(preview.split.amount, row.amount)
        assertEquals(preview.split.gatewayPart, row.gatewayAmount)
        assertEquals(preview.split.creditPart, row.creditAmount)
        assertEquals(preview.split.creditValuePart, row.creditValue)
        assertEquals(1, r.refunds(paid.order.id).size)
        assertEquals(1000, (r.fake.calls(Op.REFUND).single().request as com.panomc.plugins.market.spi.payment.RefundRequest).amount.amount)

        val order = r.order(paid.order.id)

        assertEquals(listOf(1500L, 1000L, 500L), listOf(order.refundedTotal, order.refundedGatewayAmount, order.refundedCreditAmount))
        assertEquals(500, w.fixtures.creditBalance(u))
        assertEquals(listOf(500L), ledger(CreditTxType.REFUND).map { it.amount })
        assertEquals("refund:${row.id}", ledger(CreditTxType.REFUND).single().idempotencyKey)
        assertEquals(ledger(CreditTxType.REFUND).single().id, row.creditTxId)

        // override: gateway 3.00 and 2.00 credits
        val over = service.request(paid.order.id, RefundInput(gatewayAmount = 300, creditAmount = 200), r.key(), null)

        assertEquals(listOf(300L, 200L, 200L, 500L), listOf(over.refund.gatewayAmount, over.refund.creditAmount, over.refund.creditValue, over.refund.amount))

        val tooMuch = r.expect("INVALID_REFUND_AMOUNT", 400) { service.request(paid.order.id, RefundInput(gatewayAmount = 800), r.key(), null) }

        assertEquals(7.0, tooMuch.getDouble("maxGateway"), 0.0)
    }

    // ===== RF-04 asynchronous refund, in flight bounds, V-05 =====================================================================

    @Test
    fun `an asynchronous refund is pending and bounds a second one, its confirmation books the split once and ends the order (RF-04, V-05, RD-D1)`(): Unit = runBlocking {
        val u = steve()
        val paid = r.place(u, listOf(RefundLine(10_000, actions = listOf(permission("a1", "group.vip")))), creditValue = 4000)

        r.fake.onRefund = { RefundResult.Pending().also { p -> p.gatewayRefundId = "gw-1"; p.buyerActionUrl = "https://gateway.invalid/claim" } }

        val first = service.request(paid.order.id, RefundInput(), r.key(), null)

        assertEquals(RefundStatus.PENDING, first.refund.status)
        assertEquals("gw-1", first.refund.gatewayRefundId)
        assertEquals("https://gateway.invalid/claim", first.refund.buyerActionUrl)
        assertEquals(w.clock.now() + 5 * 60_000, first.refund.nextQueryAt)
        assertEquals(OrderStatus.COMPLETED, r.order(paid.order.id).status, "the order does not move before the money does")
        assertEquals(0, r.order(paid.order.id).refundedTotal)
        assertTrue(r.orderService.ownerView(r.order(paid.order.id), pool).getBoolean("refundPending"))

        // the credit part is bounded by what the pending refund holds
        val body = r.expect("INVALID_REFUND_AMOUNT", 400) { service.request(paid.order.id, RefundInput(gatewayAmount = null, creditAmount = 4000), r.key(), null) }

        assertEquals(0.0, body.getDouble("max"), 0.0)
        assertEquals(0, ledger(CreditTxType.REFUND).size)

        r.inbound(paid, RefundState.SUCCEEDED, amount = 6000, gatewayRefundId = "gw-1")

        val order = r.order(paid.order.id)

        assertEquals(OrderStatus.REFUNDED, order.status)
        assertEquals(listOf(10_000L, 6000L, 4000L), listOf(order.refundedTotal, order.refundedGatewayAmount, order.refundedCreditAmount))
        assertEquals(4000, w.fixtures.creditBalance(u))
        assertEquals(RefundStatus.SUCCEEDED, r.refund(first.refund.id).status)
        assertNull(r.refund(first.refund.id).nextQueryAt)
        assertEquals(EntitlementStatus.REVOKED, w.entitlements.getByOrderItemId(paid.items[0].id, pool).single().status)
        assertTrue(revokeRows(paid.order.id).isNotEmpty())
        assertFalse(r.orderService.ownerView(order, pool).getBoolean("refundPending"))
    }

    @Test
    fun `the reconcile job asks the provider for a pending refund when its query is due and applies the answer like the call (RF-04)`(): Unit = runBlocking {
        val paid = r.place(steve(), listOf(RefundLine(1000)))

        r.fake.onRefund = { RefundResult.Pending().also { p -> p.gatewayRefundId = "gw-q" } }

        val pending = service.request(paid.order.id, RefundInput(), r.key(), null).refund

        // nothing is due yet
        assertEquals(0, r.job.run().polled)
        assertEquals(0, r.queries.size)

        // the gateway has not decided: Unknown changes nothing, the next query moves out (30 min after the request)
        w.clock.advance(6 * 60_000)

        assertEquals(1, r.job.run().polled)
        assertEquals(RefundStatus.PENDING, r.refund(pending.id).status)
        assertEquals(pending.createdAt + 30 * 60_000, r.refund(pending.id).nextQueryAt)
        assertEquals("gw-q", r.queries.single().gatewayRefundId)
        assertEquals(pending.idempotencyKey, r.queries.single().idempotencyKey)

        r.onQueryRefund = { RefundResult.Succeeded() }
        w.clock.advance(30 * 60_000)

        assertEquals(1, r.job.run().polled)
        assertEquals(RefundStatus.SUCCEEDED, r.refund(pending.id).status)
        assertEquals(OrderStatus.REFUNDED, r.order(paid.order.id).status)
        assertEquals(2, r.queries.size)
        assertEquals(0, r.job.run().polled, "a settled row is not asked again")
    }

    // ===== RF-05 failure and retry ============================================================================================

    @Test
    fun `a refused refund stays FAILED and leaves the order alone, retry sends the same key and amounts again (RF-05, RD-D4)`(): Unit = runBlocking {
        val paid = r.place(steve(), listOf(RefundLine(1000)))

        r.fake.failNext(Op.REFUND, ProviderException(ProviderErrorCode.GATEWAY_REJECTED, "rejected", "card already refunded"))

        val failed = service.request(paid.order.id, RefundInput(amount = 400), r.key(), null)

        assertEquals(RefundStatus.FAILED, failed.refund.status)
        assertEquals("GATEWAY_REJECTED", failed.failure!!.code)
        assertEquals("GATEWAY_REJECTED", r.refund(failed.refund.id).failureCode)
        assertEquals(OrderStatus.COMPLETED, r.order(paid.order.id).status)
        assertEquals(0, r.order(paid.order.id).refundedTotal)
        assertNull(r.refund(failed.refund.id).nextQueryAt)

        val retried = service.retry(failed.refund.id)

        assertNull(retried.failure)
        assertEquals(RefundStatus.SUCCEEDED, retried.refund.status)
        assertNull(r.refund(failed.refund.id).failureCode)

        val calls = r.fake.calls(Op.REFUND).map { it.request as com.panomc.plugins.market.spi.payment.RefundRequest }

        assertEquals(2, calls.size)
        assertEquals(calls[0].idempotencyKey, calls[1].idempotencyKey)
        assertEquals(calls[0].amount.amount, calls[1].amount.amount)
        assertEquals(calls[0].refundId, calls[1].refundId)
        assertEquals(400, r.order(paid.order.id).refundedTotal)

        // a settled refund can neither be retried nor cancelled
        r.expect("INVALID_STATE", 409) { service.retry(failed.refund.id) }
        r.expect("INVALID_STATE", 409) { service.cancel(failed.refund.id) }
    }

    @Test
    fun `a call that gets no answer in time leaves the row REQUESTED, retry then sends the same key again and the gateway refunds once (RD-D4)`(): Unit = runBlocking {
        val world = RefundWorld(w, vertx, refundTimeoutMs = 200)
        val paid = world.place(steve(), listOf(RefundLine(1000)))

        world.fake.delay(Op.REFUND)

        val first = world.service.request(paid.order.id, RefundInput(amount = 400), world.key(), null)

        assertEquals(RefundStatus.REQUESTED, first.refund.status)
        assertNull(first.failure, "a timeout is an unknown outcome, not a refusal")
        assertEquals(0, world.order(paid.order.id).refundedTotal)
        assertEquals(1, world.fake.calls(Op.REFUND).size)

        // the call is considered running for 60 s: neither retried nor cancelled meanwhile
        world.expect("INVALID_STATE", 409) { world.service.retry(first.refund.id) }
        world.expect("INVALID_STATE", 409) { world.service.cancel(first.refund.id) }

        w.clock.advance(61_000)

        val retried = world.service.retry(first.refund.id)

        assertEquals(RefundStatus.SUCCEEDED, retried.refund.status)

        val calls = world.fake.calls(Op.REFUND).map { it.request as com.panomc.plugins.market.spi.payment.RefundRequest }

        assertEquals(2, calls.size)
        assertEquals(calls[0].idempotencyKey, calls[1].idempotencyKey)
        assertEquals(400, world.order(paid.order.id).refundedTotal)
    }

    @Test
    fun `a provider error is FAILED and never retried by itself, a cancelled refund frees its amount again`(): Unit = runBlocking {
        val paid = r.place(steve(), listOf(RefundLine(1000)))

        r.fake.failNext(Op.REFUND, ProviderException(ProviderErrorCode.GATEWAY_UNREACHABLE, "connection reset", retryable = true))

        val first = service.request(paid.order.id, RefundInput(amount = 500), r.key(), null)

        assertEquals(RefundStatus.FAILED, first.refund.status, "a provider error is FAILED, never retried by itself")

        val cancelled = service.cancel(first.refund.id)

        assertEquals(RefundStatus.CANCELLED, cancelled.refund.status)
        assertEquals(0, r.order(paid.order.id).refundedTotal)
        assertEquals(1, r.fake.calls(Op.REFUND).size)

        // the cancelled amount is free again
        val again = service.request(paid.order.id, RefundInput(amount = 1000), r.key(), null)

        assertEquals(RefundStatus.SUCCEEDED, again.refund.status)
        assertEquals(OrderStatus.REFUNDED, r.order(paid.order.id).status)
    }

    // ===== RF-06 unsupported and manual =======================================================================================

    @Test
    fun `a provider without refund support refuses, a manual refund settles without a gateway call and needs a reason (RF-06)`(): Unit = runBlocking {
        val paid = r.place(steve(), listOf(RefundLine(1000)))

        r.fake.caps = PaymentCapabilities().apply { refund = RefundSupport.NONE }

        r.expect("REFUND_NOT_SUPPORTED", 400) { service.request(paid.order.id, RefundInput(amount = 400), r.key(), null) }
        assertEquals(0, r.refunds(paid.order.id).size)

        assertThrows(RequestValueException::class.java) { runBlocking { service.request(paid.order.id, RefundInput(amount = 400, manual = true), r.key(), null) } }

        val manual = service.request(paid.order.id, RefundInput(amount = 400, manual = true, reason = "bank transfer back"), r.key(), null)

        assertEquals(RefundStatus.SUCCEEDED, manual.refund.status)
        assertNull(manual.refund.providerId)
        assertNull(manual.refund.paymentId)
        assertEquals("bank transfer back", manual.refund.reason)
        assertEquals(0, r.fake.calls(Op.REFUND).size)
        assertEquals(400, r.order(paid.order.id).refundedTotal)
        assertEquals(OrderStatus.PARTIALLY_REFUNDED, r.order(paid.order.id).status)

        // FULL_ONLY: a part is refused, the whole amount is accepted
        r.fake.caps = PaymentCapabilities().apply { refund = RefundSupport.FULL_ONLY }

        val full = r.place(steve(), listOf(RefundLine(1000)))

        r.expect("REFUND_NOT_SUPPORTED", 400) { service.request(full.order.id, RefundInput(amount = 400), r.key(), null) }

        val all = service.request(full.order.id, RefundInput(), r.key(), null)

        assertEquals(RefundStatus.SUCCEEDED, all.refund.status)
        assertTrue((r.fake.calls(Op.REFUND).single().request as com.panomc.plugins.market.spi.payment.RefundRequest).full)
    }

    // ===== RF-07 gateway-originated refund ====================================================================================

    @Test
    fun `a refund made in the gateway dashboard becomes a GATEWAY row, a partial one revokes nothing and a full one follows the setting (RF-07)`(): Unit = runBlocking {
        val paid = r.place(steve(), listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))))

        r.inbound(paid, RefundState.SUCCEEDED, amount = 400, gatewayRefundId = "dash-1")

        val rows = r.refunds(paid.order.id)

        assertEquals(1, rows.size)
        assertEquals(RefundOrigin.GATEWAY, rows[0].origin)
        assertEquals(RefundStatus.SUCCEEDED, rows[0].status)
        assertEquals(400, rows[0].gatewayAmount)
        assertEquals("dash-1", rows[0].gatewayRefundId)
        assertEquals(0, rows[0].creditAmount)
        assertFalse(rows[0].revoke)
        assertEquals(OrderStatus.PARTIALLY_REFUNDED, r.order(paid.order.id).status)
        assertEquals(400, r.order(paid.order.id).refundedTotal)
        assertEquals(EntitlementStatus.ACTIVE, w.entitlements.getByOrderItemId(paid.items[0].id, pool).single().status)

        // the same notification again is no second refund
        r.inbound(paid, RefundState.SUCCEEDED, amount = 400, gatewayRefundId = "dash-1")

        assertEquals(1, r.refunds(paid.order.id).size)
        assertEquals(400, r.order(paid.order.id).refundedTotal)

        // the rest, as a snapshot gateway reports it (cumulative total), empties the order
        r.inbound(paid, RefundState.SUCCEEDED, cumulative = 1000, eventKey = "snap-1")

        assertEquals(2, r.refunds(paid.order.id).size)
        assertEquals(600, r.refunds(paid.order.id).last().gatewayAmount)
        assertTrue(r.refunds(paid.order.id).last().revoke)
        assertEquals(OrderStatus.REFUNDED, r.order(paid.order.id).status)
        assertEquals(EntitlementStatus.REVOKED, w.entitlements.getByOrderItemId(paid.items[0].id, pool).single().status)

        // a snapshot that repeats what is known writes nothing
        r.inbound(paid, RefundState.SUCCEEDED, cumulative = 1000, eventKey = "snap-2")

        assertEquals(2, r.refunds(paid.order.id).size)
    }

    // ===== RF-09 refund without revoke ========================================================================================

    @Test
    fun `without revoke the goods stay, no undo row is planned (RF-09)`(): Unit = runBlocking {
        w.configure { r.config(revokeOnRefund = false) }

        val paid = r.place(steve(), listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip"), credit("a2", 100)))))
        val done = service.request(paid.order.id, RefundInput(), r.key(), null)

        assertFalse(done.refund.revoke)
        assertEquals(OrderStatus.REFUNDED, r.order(paid.order.id).status)
        assertEquals(0, revokeRows(paid.order.id).size)
        assertEquals(EntitlementStatus.ACTIVE, w.entitlements.getByOrderItemId(paid.items[0].id, pool).single().status)

        // asking for the revoke explicitly overrides the setting
        val other = r.place(steve(), listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))))

        service.request(other.order.id, RefundInput(revoke = true), r.key(), null)

        assertTrue(revokeRows(other.order.id).isNotEmpty())
    }

    // ===== replay of one key (R-14 twin) ====================================================================================

    @Test
    fun `the same request and key again is the same refund and no second call, another body under the key is a conflict (R-14)`(): Unit = runBlocking {
        val paid = r.place(steve(), listOf(RefundLine(1000)))
        val key = r.key()
        val first = service.request(paid.order.id, RefundInput(amount = 300), key, null)
        val replay = service.request(paid.order.id, RefundInput(amount = 300), key, null)

        assertTrue(replay.replay)
        assertEquals(first.refund.id, replay.refund.id)
        assertEquals(RefundStatus.SUCCEEDED, replay.refund.status)
        assertEquals(1, r.refunds(paid.order.id).size)
        assertEquals(1, r.fake.calls(Op.REFUND).size)
        assertEquals(300, r.order(paid.order.id).refundedTotal)

        r.expect("IDEMPOTENCY_CONFLICT", 409) { service.request(paid.order.id, RefundInput(amount = 301), key, null) }

        // the key of another order's refund is no replay of this one
        val other = r.place(steve(), listOf(RefundLine(1000)))

        r.expect("IDEMPOTENCY_CONFLICT", 409) { service.request(other.order.id, RefundInput(amount = 300), key, null) }
    }

    // ===== R-15 duplicate refund notifications ==================================================================================

    @Test
    fun `three notifications of one pending mixed refund return the credits once and run O10 once (R-15)`(): Unit = runBlocking {
        val u = steve()
        val paid = r.place(u, listOf(RefundLine(3000)), creditValue = 1000)

        r.fake.onRefund = { RefundResult.Pending().also { p -> p.gatewayRefundId = "gw-r15" } }

        val pending = service.request(paid.order.id, RefundInput(), r.key(), null).refund

        repeat(3) { r.inbound(paid, RefundState.SUCCEEDED, amount = 2000, gatewayRefundId = "gw-r15") }

        assertEquals(1, r.refunds(paid.order.id).size)
        assertEquals(RefundStatus.SUCCEEDED, r.refund(pending.id).status)
        assertEquals(listOf(1000L), ledger(CreditTxType.REFUND).map { it.amount })
        assertEquals(1000, w.fixtures.creditBalance(u))
        assertEquals(3000, r.order(paid.order.id).refundedTotal)
        assertEquals(1, w.orderEvents.getByOrderId(paid.order.id, pool).count { it.type == OrderEventType.REFUND_SUCCEEDED })
        assertEquals(2000, r.attempt(paid.attempt.id).refundedAmount)
    }

    @Test
    fun `the notification before the call stored its ids is matched to the open row, not booked as a second refund (RD-D2)`(): Unit = runBlocking {
        val paid = r.place(steve(), listOf(RefundLine(1000)))

        r.fake.onRefund = { RefundResult.Pending() }

        val pending = service.request(paid.order.id, RefundInput(amount = 400), r.key(), null).refund

        assertNull(pending.gatewayRefundId)

        // no refund id, no key: the oldest open row of that amount
        r.inbound(paid, RefundState.SUCCEEDED, amount = 400)

        assertEquals(1, r.refunds(paid.order.id).size)
        assertEquals(RefundStatus.SUCCEEDED, r.refund(pending.id).status)
        assertEquals(400, r.order(paid.order.id).refundedTotal)
    }

    // ===== a confirmed refund is never FAILED (RD-D3, V-05) =====================================================================

    @Test
    fun `a refund the gateway confirmed is always booked, past the total and past the credit part, with an alert and never FAILED (RD-D3)`(): Unit = runBlocking {
        skipInvariants = true

        val u = steve()
        val paid = r.place(u, listOf(RefundLine(10_000)), creditValue = 4000)

        r.fake.onRefund = { RefundResult.Pending().also { p -> p.gatewayRefundId = "gw-panel" } }

        val panel = service.request(paid.order.id, RefundInput(), r.key(), null).refund

        assertEquals(RefundStatus.PENDING, panel.status)

        // a dashboard refund of 5.00 raced it
        r.inbound(paid, RefundState.SUCCEEDED, amount = 5000, gatewayRefundId = "dash-race")

        assertEquals(2, r.refunds(paid.order.id).size)
        assertEquals(5000, r.order(paid.order.id).refundedTotal)

        // now the panel refund (6.00 gateway + 4.00 credits) is confirmed as well: 15.00 of 10.00
        r.inbound(paid, RefundState.SUCCEEDED, amount = 6000, gatewayRefundId = "gw-panel")

        val order = r.order(paid.order.id)

        assertEquals(RefundStatus.SUCCEEDED, r.refund(panel.id).status)
        assertTrue(r.refunds(paid.order.id).none { it.status == RefundStatus.FAILED || it.status == RefundStatus.CANCELLED })
        assertEquals(OrderStatus.REFUNDED, order.status)
        assertEquals(11_000, order.refundedGatewayAmount, "the money that left is always on the books")
        assertEquals(4000, order.refundedCreditAmount)
        assertTrue(order.refundedTotal > order.totalPrice)
        assertEquals(order.refundedTotal, r.refunds(paid.order.id).sumOf { it.amount }, "the books and the rows agree on what was confirmed")
        assertTrue(r.alerts.any { it.first == paid.order.id && it.second == "OVER_REFUND" })
        assertTrue(w.orderEvents.getByOrderId(paid.order.id, pool).any { it.type == OrderEventType.NOTE && it.message == "OVER_REFUND" })
    }

    // ===== revokeFirst (V-04) ===================================================================================================

    @Test
    fun `revokeFirst plans the undo rows and holds the gateway call until they are confirmed (V-04)`(): Unit = runBlocking {
        val u = steve()
        val paid = r.place(u, listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))))
        val held = service.request(paid.order.id, RefundInput(revokeFirst = true), r.key(), null)

        assertEquals(RefundStatus.REQUESTED, held.refund.status)
        assertTrue(held.refund.revokeFirst)
        assertEquals(0, r.fake.calls(Op.REFUND).size, "no money moves before the goods are back")
        assertEquals(1, revokeRows(paid.order.id).size)
        assertEquals(DeliveryStatus.PENDING, revokeRows(paid.order.id).single().status)
        assertEquals(OrderStatus.COMPLETED, r.order(paid.order.id).status)

        // the job looks: the undo row is still open
        w.clock.advance(61_000)

        assertEquals(0, r.job.run().released)
        assertEquals(0, r.fake.calls(Op.REFUND).size)

        // the server executes the undo: the next look releases the refund
        r.d.runInline()

        w.clock.advance(61_000)

        val report = r.job.run()

        assertEquals(1, report.released)
        assertEquals(1, r.fake.calls(Op.REFUND).size)
        assertEquals(RefundStatus.SUCCEEDED, r.refund(held.refund.id).status)
        assertEquals(OrderStatus.REFUNDED, r.order(paid.order.id).status)
        assertEquals(1, revokeRows(paid.order.id).size, "O10 does not revoke again what revokeFirst already took back")
        assertEquals(EntitlementStatus.REVOKED, w.entitlements.getByOrderItemId(paid.items[0].id, pool).single().status)
        assertEquals(0, r.job.run().released)
    }

    @Test
    fun `a revokeFirst refund that waits longer than 24 hours is cancelled with REVOKE_TIMEOUT and an alert, the undo rows stay (RD-D5)`(): Unit = runBlocking {
        val paid = r.place(steve(), listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))))
        val held = service.request(paid.order.id, RefundInput(revokeFirst = true), r.key(), null).refund

        w.clock.advance(24 * 3_600_000L + 1000)

        val report = r.job.run()

        assertEquals(1, report.timedOut)

        val row = r.refund(held.id)

        assertEquals(RefundStatus.CANCELLED, row.status)
        assertEquals("REVOKE_TIMEOUT", row.failureCode)
        assertEquals(0, r.fake.calls(Op.REFUND).size)
        assertEquals(1, revokeRows(paid.order.id).size, "the admin decides what to do with the undo rows")
        assertTrue(r.alerts.any { it.second == "REVOKE_TIMEOUT" && it.third.getLong("refundId") == held.id })
        assertEquals(OrderStatus.COMPLETED, r.order(paid.order.id).status)
    }

    @Test
    fun `a failed undo row keeps the refund waiting and raises an alert, a credit-only revokeFirst refund settles when the undo is done`(): Unit = runBlocking {
        val u = steve()
        val paid = r.place(u, listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))))
        val held = service.request(paid.order.id, RefundInput(revokeFirst = true, manual = true, reason = "paid back in cash"), r.key(), null).refund

        assertEquals(RefundStatus.REQUESTED, held.status)

        val row = revokeRows(paid.order.id).single()

        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_delivery` SET `status` = 'FAILED' WHERE `id` = ?", row.id)
        w.clock.advance(61_000)

        assertEquals(0, r.job.run().released)
        assertTrue(r.alerts.any { it.second == "REVOKE_FAILED" && it.third.getLong("deliveryId") == row.id })
        assertEquals(RefundStatus.REQUESTED, r.refund(held.id).status)

        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_delivery` SET `status` = 'CONFIRMED', `lastErrorCode` = NULL WHERE `id` = ?", row.id)
        w.clock.advance(61_000)

        assertEquals(1, r.job.run().released)
        assertEquals(RefundStatus.SUCCEEDED, r.refund(held.id).status)
        assertEquals(0, r.fake.calls(Op.REFUND).size, "a manual refund never calls the gateway")
        assertEquals(OrderStatus.REFUNDED, r.order(paid.order.id).status)
    }

    @Test
    fun `the preview recommends revokeFirst when a server the undo would reach has not been seen`(): Unit = runBlocking {
        val server = ProductAction(id = "s1", type = DeliveryActionType.COMMAND, commands = listOf("give {username} diamond 1"), targetServers = listOf(7L))
        val paid = r.place(steve(), listOf(RefundLine(1000, actions = listOf(server))), grant = false)

        assertTrue(service.preview(paid.order.id, RefundInput()).recommendRevokeFirst)

        w.serverStates.upsertSync(com.panomc.plugins.market.db.model.MarketServerState(serverId = 7, lastSeenAt = w.clock.now(), createdAt = w.clock.now(), updatedAt = w.clock.now()), pool)

        assertFalse(service.preview(paid.order.id, RefundInput()).recommendRevokeFirst)
    }

    // ===== codes, state, SYSTEM / duplicate rows ===================================================================================

    @Test
    fun `a full refund gives the coupon use back and a partial one keeps it (RD-D7)`(): Unit = runBlocking {
        val coupon = w.fixtures.coupon(code = "SAVE10", redeemLimit = 5)
        val partial = r.place(steve(), listOf(RefundLine(1000)))
        val full = r.place(steve(), listOf(RefundLine(1000)))

        for (p in listOf(partial, full)) {
            MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_coupon` SET `usedCount` = `usedCount` + 1 WHERE `id` = ?", coupon.id)
            w.redemptions.add(
                com.panomc.plugins.market.db.model.MarketRedemption(
                    kind = com.panomc.plugins.market.db.model.RedemptionKind.COUPON, refId = coupon.id, orderId = p.order.id, code = "SAVE10",
                    state = com.panomc.plugins.market.db.model.RedemptionState.APPLIED, buyerKey = p.order.buyerKey
                ),
                pool
            )
        }

        assertEquals(2, w.coupons.getById(coupon.id, pool)!!.usedCount)

        service.request(partial.order.id, RefundInput(amount = 400), r.key(), null)

        assertEquals(2, w.coupons.getById(coupon.id, pool)!!.usedCount)
        assertEquals(com.panomc.plugins.market.db.model.RedemptionState.APPLIED, w.redemptions.getByOrderId(partial.order.id, pool).single().state)

        service.request(full.order.id, RefundInput(), r.key(), null)

        assertEquals(1, w.coupons.getById(coupon.id, pool)!!.usedCount)
        assertEquals(com.panomc.plugins.market.db.model.RedemptionState.RELEASED, w.redemptions.getByOrderId(full.order.id, pool).single().state)
    }

    @Test
    fun `a refund of a paid order is refused unless it is COMPLETED or PARTIALLY_REFUNDED`(): Unit = runBlocking {
        val paid = r.place(steve(), listOf(RefundLine(1000)))

        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order` SET `status` = 'CHARGEBACK', `statusBeforeDispute` = 'COMPLETED', `disputeStatus` = 'OPEN' WHERE `id` = ?", paid.order.id)
        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_entitlement` SET `status` = 'REVOKED' WHERE `orderId` = ?", paid.order.id)
        // a charged-back order sells nothing (I17)
        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_product` SET `soldCount` = 0 WHERE `id` = ?", paid.products[0].id)

        r.expect("INVALID_ORDER_TRANSITION", 400) { service.request(paid.order.id, RefundInput(), r.key(), null) }
        r.expect("INVALID_ORDER_TRANSITION", 400) { service.preview(paid.order.id, RefundInput()) }
        assertEquals(0, r.refunds(paid.order.id).size)
    }

    @Test
    fun `a SYSTEM refund of a duplicate attempt is sent by the job and only moves the attempt, never the books of the order`(): Unit = runBlocking {
        val paid = r.place(steve(), listOf(RefundLine(1000)))
        val now = w.clock.now()
        val dup = w.payments.add(
            com.panomc.plugins.market.db.model.MarketPayment(
                orderId = paid.order.id, providerId = "fake", methodLabel = "Fake", status = com.panomc.plugins.market.db.model.PaymentStatus.SUCCEEDED, reference = "REFDUP00000000000001",
                token = "%040x".format(99), amount = 1000, currency = "EUR", orderTotal = 1000, paidAmount = 1000, paidCurrency = "EUR", paidAt = now, duplicate = true,
                gatewayTransactionId = "txn-dup", createdAt = now, updatedAt = now
            ),
            pool
        )!!
        val id = w.refunds.add(
            com.panomc.plugins.market.db.model.MarketRefund(
                orderId = paid.order.id, paymentId = dup, providerId = "fake", status = RefundStatus.REQUESTED, origin = RefundOrigin.SYSTEM, idempotencyKey = "sys:dup:$dup",
                amount = 1000, gatewayAmount = 1000, currency = "EUR", reason = "duplicate payment", revoke = false, createdAt = now, updatedAt = now
            ),
            pool
        )!!

        // the duplicate's row does not bound a refund of the order's own money
        assertEquals(RefundStatus.SUCCEEDED, service.request(paid.order.id, RefundInput(amount = 300), r.key(), null).refund.status)

        assertEquals(1, r.job.run().sent)

        val row = r.refund(id)

        assertEquals(RefundStatus.SUCCEEDED, row.status)
        assertEquals(1000, r.attempt(dup).refundedAmount)
        assertEquals(300, r.order(paid.order.id).refundedTotal, "the duplicate's money is not on the books of the order")
        assertEquals(OrderStatus.PARTIALLY_REFUNDED, r.order(paid.order.id).status)
        assertEquals("sys:dup:$dup", (r.fake.calls(Op.REFUND).last().request as com.panomc.plugins.market.spi.payment.RefundRequest).idempotencyKey)
        assertEquals(0, r.job.run().sent)
    }

    @Test
    fun `the money of a rejected review is sent back by the job and never touches the books of the closed order`(): Unit = runBlocking {
        val paid = r.place(steve(), listOf(RefundLine(1000)), grant = false)
        val now = w.clock.now()

        // what O5 leaves behind: a closed, released order that recorded the money it received, and the SYSTEM refund of exactly that money
        MarketTestDb.sql(
            pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order` SET `status` = 'CANCELLED', `reservationState` = 'RELEASED', `paymentId` = ? WHERE `id` = ?", paid.attempt.id, paid.order.id
        )
        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_product` SET `soldCount` = 0 WHERE `id` = ?", paid.products[0].id)

        val id = w.refunds.add(
            com.panomc.plugins.market.db.model.MarketRefund(
                orderId = paid.order.id, paymentId = paid.attempt.id, providerId = "fake", status = RefundStatus.REQUESTED, origin = RefundOrigin.SYSTEM,
                idempotencyKey = "sys:reject:${paid.order.id}:${paid.attempt.id}", amount = 1000, gatewayAmount = 1000, currency = "EUR", reason = "review rejected", revoke = false,
                createdAt = now, updatedAt = now
            ),
            pool
        )!!

        assertEquals(1, r.job.run().sent)

        val row = r.refund(id)

        assertEquals(RefundStatus.SUCCEEDED, row.status)
        assertEquals(1000, r.attempt(paid.attempt.id).refundedAmount)
        assertEquals(0, r.order(paid.order.id).refundedTotal, "the order never completed: its books stay as they are")
        assertEquals(OrderStatus.CANCELLED, r.order(paid.order.id).status)
        assertEquals("sys:reject:${paid.order.id}:${paid.attempt.id}", (r.fake.calls(Op.REFUND).single().request as com.panomc.plugins.market.spi.payment.RefundRequest).idempotencyKey)

        // the provider's later confirmation of that same refund is a replay
        r.inbound(paid, RefundState.SUCCEEDED, amount = 1000, refundKey = row.idempotencyKey)

        assertEquals(1, r.refunds(paid.order.id).size)
        assertEquals(1000, r.attempt(paid.attempt.id).refundedAmount)
    }

    // ===== the other effects of O10: webhook, mail, credit note, creator, cashback, clawback ================================================

    @Test
    fun `O10 queues the order_refunded webhook and the mail once and replays write nothing more`(): Unit = runBlocking {
        val paid = r.place(steve(), listOf(RefundLine(1000)), email = "steve@example.com")

        r.webhooks.endpoint("https://hooks.example.com/market", events = "[\"order.refunded\"]")

        val done = service.request(paid.order.id, RefundInput(amount = 400), r.key(), null)

        val hooks = w.webhookDeliveries.let { MarketTestDb.sql(pool, "SELECT `event`, `status`, `body` FROM `${MarketTestDb.TABLE_PREFIX}market_webhook_delivery`") }

        assertEquals(1, hooks.size)
        assertEquals("order.refunded", hooks[0].getString("event"))
        assertEquals(WebhookDeliveryStatus.PENDING.name, hooks[0].getString("status"))

        val body = JsonObject(hooks[0].getString("body")).getJsonObject("data").getJsonObject("refund")

        assertEquals(done.refund.id, body.getLong("id"))
        assertEquals(4.0, body.getDouble("amount"), 0.0)
        assertEquals("PANEL", body.getString("origin"))
        assertFalse(body.getBoolean("full"))

        val mails = MarketTestDb.sql(pool, "SELECT `kind`, `refId`, `recipient` FROM `${MarketTestDb.TABLE_PREFIX}market_mail_outbox`")

        assertEquals(listOf(MailKind.ORDER_REFUNDED.name), mails.map { it.getString("kind") })
        assertEquals("steve@example.com", mails.single().getString("recipient"))

        // the same notification replayed changes neither
        r.inbound(paid, RefundState.SUCCEEDED, amount = 400, refundKey = done.refund.idempotencyKey)

        assertEquals(1, MarketTestDb.sql(pool, "SELECT `id` FROM `${MarketTestDb.TABLE_PREFIX}market_webhook_delivery`").size)
        assertEquals(1, MarketTestDb.sql(pool, "SELECT `id` FROM `${MarketTestDb.TABLE_PREFIX}market_mail_outbox`").size)
    }

    @Test
    fun `every refund of an invoiced order gets its own credit note in the O10 transaction`(): Unit = runBlocking {
        val invoices = InvoiceService(
            config = { w.config }, clock = w.clock, orders = w.orders, orderEvents = w.orderEvents, refunds = w.refunds, refundItems = w.refundItems, invoices = w.invoices,
            sequences = w.sequences, site = { InvoiceSite("Acme Craft", "https://acme.example") }, defaultLocale = { "en-US" }
        )
        val world = RefundWorld(w, vertx, invoices)

        w.configure { world.config(seller = true) }

        val paid = world.place(steve(), listOf(RefundLine(1000)))

        w.db.tx { conn -> invoices.issueForOrder(conn, w.orders.getById(paid.order.id, conn)!!, w.orderItems.getByOrderIds(listOf(paid.order.id), conn)) }

        val a = world.service.request(paid.order.id, RefundInput(amount = 400), world.key(), null).refund
        val b = world.service.request(paid.order.id, RefundInput(amount = 600), world.key(), null).refund
        val notes = w.invoices.getByOrderId(paid.order.id, pool).filter { it.type == InvoiceType.CREDIT_NOTE }

        assertEquals(setOf(a.id, b.id), notes.map { it.refundId }.toSet())
        assertEquals(2, notes.size)
        assertEquals(2, notes.map { it.number }.distinct().size)

        // an event for a refund that is settled issues no third note
        world.inbound(paid, RefundState.SUCCEEDED, amount = 400, refundKey = a.idempotencyKey)

        assertEquals(2, w.invoices.getByOrderId(paid.order.id, pool).count { it.type == InvoiceType.CREDIT_NOTE })
    }

    @Test
    fun `the creator earning and the cashback of the order are taken back in proportion to what was refunded`(): Unit = runBlocking {
        val u = steve()
        val code = w.fixtures.creatorCode(code = "CREATOR")
        val paid = r.place(u, listOf(RefundLine(10_000)))
        val now = w.clock.now()

        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order` SET `creatorCodeId` = ? WHERE `id` = ?", code.id, paid.order.id)
        w.creatorEarnings.add(
            com.panomc.plugins.market.db.model.MarketCreatorEarning(
                creatorCodeId = code.id, orderId = paid.order.id, baseAmount = 10_000, commissionPercent = 1000, amount = 1000, currency = "EUR",
                state = com.panomc.plugins.market.db.model.CreatorEarningState.AVAILABLE, availableAt = now, createdAt = now, updatedAt = now
            ),
            pool
        )
        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_creator_code` SET `earnings` = 1000 WHERE `id` = ?", code.id)

        // 25 % refunded: 25 % of the commission goes back
        service.request(paid.order.id, RefundInput(amount = 2500), r.key(), null)

        var earning = w.creatorEarnings.get(paid.order.id, code.id, pool)!!

        assertEquals(250, earning.reversedAmount)
        assertEquals(com.panomc.plugins.market.db.model.CreatorEarningState.AVAILABLE, earning.state)
        assertEquals(750, w.creatorCodes.getById(code.id, pool)!!.earnings)

        // the rest empties the order: the whole earning is reversed
        service.request(paid.order.id, RefundInput(), r.key(), null)

        earning = w.creatorEarnings.get(paid.order.id, code.id, pool)!!

        assertEquals(1000, earning.reversedAmount)
        assertEquals(com.panomc.plugins.market.db.model.CreatorEarningState.REVERSED, earning.state)
        assertEquals(0, w.creatorCodes.getById(code.id, pool)!!.earnings)
    }

    @Test
    fun `the cashback of the order is taken back in proportion, as far as the buyer still has it, and entirely when the order is empty`(): Unit = runBlocking {
        val u = steve()
        val paid = r.place(u, listOf(RefundLine(10_000)))

        // 10 % cashback: 100.00 credits on 100.00 paid at the gateway
        w.db.tx { conn ->
            r.d.credits.cashback(
                w.orders.getById(paid.order.id, conn)!!, w.orderItems.getByOrderIds(listOf(paid.order.id), conn), com.panomc.plugins.market.core.credit.Cashback.Settings(true, 1000, 100), conn
            )
        }

        assertEquals(1000, w.fixtures.creditBalance(u))

        val preview = service.preview(paid.order.id, RefundInput(amount = 5000))

        assertEquals(5.0, preview.warnings.single { it.getString("code") == "CASHBACK_REVERSAL" }.getDouble("amount"), 0.0)

        val half = service.request(paid.order.id, RefundInput(amount = 5000), r.key(), null)

        assertEquals(listOf(500L), ledger(CreditTxType.CASHBACK_REVERSAL).map { it.amount })
        assertEquals("refund:${half.refund.id}:cashback", ledger(CreditTxType.CASHBACK_REVERSAL).single().idempotencyKey)
        assertEquals(500, w.fixtures.creditBalance(u))

        // the buyer spent what was left
        w.db.tx { conn ->
            r.d.credits.lockAccounts(listOf(u.id), true, conn)
            r.d.credits.revoke(u.id, 400, "test:spend", null, "spent elsewhere", conn)
        }

        service.request(paid.order.id, RefundInput(), r.key(), null)

        val reversals = ledger(CreditTxType.CASHBACK_REVERSAL).sortedBy { it.id }

        assertEquals(2, reversals.size)
        assertEquals(100, reversals[1].amount, "only the 1.00 the buyer still had")
        assertEquals(400, reversals[1].shortfall)
        assertEquals(0, w.fixtures.creditBalance(u))
        assertEquals(1000, reversals.sumOf { it.amount + it.shortfall }, "the whole cashback is accounted for, never more")
    }

    @Test
    fun `a refunded credit pack takes its credits back as far as the buyer still has them and notes the shortfall`(): Unit = runBlocking {
        val u = steve()
        val paid = r.place(u, listOf(RefundLine(1000)), grant = false)
        val itemId = paid.items[0].id

        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order_item` SET `creditAmount` = 5000 WHERE `id` = ?", itemId)

        // the O2 credit of the pack, then the buyer spends 3.00 of it elsewhere
        w.db.tx { conn ->
            r.d.credits.creditOrderItems(w.orders.getById(paid.order.id, conn)!!, w.orderItems.getByOrderIds(listOf(paid.order.id), conn), conn) { true }
        }

        assertEquals(5000, w.fixtures.creditBalance(u))

        w.db.tx { conn ->
            r.d.credits.lockAccounts(listOf(u.id), true, conn)
            r.d.credits.revoke(u.id, 3000, "test:spend", null, "spent elsewhere", conn)
        }

        assertEquals(2000, w.fixtures.creditBalance(u))

        val done = service.request(paid.order.id, RefundInput(), r.key(), null)

        // everything is requested (50.00), 20.00 are there, 30.00 are the shortfall: the refund goes through
        val claw = ledger(CreditTxType.REVOKE).first { it.idempotencyKey == "refund:${done.refund.id}:clawback:$itemId" }

        assertEquals(2000, claw.amount)
        assertEquals(3000, claw.shortfall)
        assertEquals(0, w.fixtures.creditBalance(u))
        assertEquals(RefundStatus.SUCCEEDED, done.refund.status)
        assertTrue(w.orderEvents.getByOrderId(paid.order.id, pool).any { it.type == OrderEventType.CLAWBACK_SHORTFALL })
    }

    // ===== tier upgrade (21 section 5.4, RD-D11) =================================================================================

    private suspend fun upgradeChain(): Triple<PaidOrder, PaidOrder, TestUser> {
        val u = steve()
        val low = r.place(u, listOf(RefundLine(1000, actions = listOf(permission("a1", "group.vip")))))
        val high = r.place(u, listOf(RefundLine(1500, actions = listOf(permission("b1", "group.vip2")))))
        val e1 = w.entitlements.getByOrderItemId(low.items[0].id, pool).single()
        val e2 = w.entitlements.getByOrderItemId(high.items[0].id, pool).single()

        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_entitlement` SET `status` = 'UPGRADED', `replacedById` = ?, `endReason` = 'UPGRADE', `endedAt` = ? WHERE `id` = ?", e2.id, w.clock.now(), e1.id)
        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_entitlement` SET `pricePaid` = 2500 WHERE `id` = ?", e2.id)

        return Triple(low, high, u)
    }

    @Test
    fun `refunding an upgraded tier needs a decision, without cascade the successor keeps its rank and loses the deduction (RD-D11)`(): Unit = runBlocking {
        val (low, high, _) = upgradeChain()
        val preview = service.preview(low.order.id, RefundInput())
        val warning = preview.warnings.single { it.getString("code") == "UPGRADE_DEPENDENT" }

        assertEquals(high.order.id, warning.getLong("successorOrderId"))
        assertEquals(10.0, warning.getDouble("deduction"), 0.0)

        r.expect("CASCADE_DECISION_REQUIRED", 400) { service.request(low.order.id, RefundInput(), r.key(), null) }
        assertEquals(0, r.refunds(low.order.id).size)

        service.request(low.order.id, RefundInput(cascadeUpgrade = false), r.key(), null)

        val e2 = w.entitlements.getByOrderItemId(high.items[0].id, pool).single()

        assertEquals(EntitlementStatus.ACTIVE, e2.status)
        assertEquals(1500, e2.pricePaid, "a later upgrade credits only what is still paid")
        assertEquals(EntitlementStatus.REVOKED, w.entitlements.getByOrderItemId(low.items[0].id, pool).single().status)
        assertEquals(0, revokeRows(high.order.id).size)
    }

    @Test
    fun `with cascade the live successor of the refunded tier is ended as a refund ends it (RD-D11)`(): Unit = runBlocking {
        val (low, high, _) = upgradeChain()

        service.request(low.order.id, RefundInput(cascadeUpgrade = true), r.key(), null)

        val e2 = w.entitlements.getByOrderItemId(high.items[0].id, pool).single()

        assertEquals(EntitlementStatus.REVOKED, e2.status)
        assertEquals("REFUND", e2.endReason)
        assertTrue(revokeRows(high.order.id).isNotEmpty())
        assertEquals(OrderStatus.COMPLETED, r.order(high.order.id).status, "no money moves on the successor order")
        assertTrue(w.orderEvents.getByOrderId(high.order.id, pool).any { it.message == "UPGRADE_CASCADE_REVOKED" })
    }

    // ===== validation =============================================================================================================

    @Test
    fun `a request must name items or an amount, never both, and only lines of the order`(): Unit = runBlocking {
        val paid = r.place(steve(), listOf(RefundLine(1000, quantity = 2)))
        val line = paid.items[0].id

        assertThrows(RequestValueException::class.java) {
            runBlocking { service.request(paid.order.id, RefundInput(amount = 100, items = listOf(RefundMath.ItemRequest(line, 1))), r.key(), null) }
        }
        assertThrows(RequestValueException::class.java) {
            runBlocking { service.request(paid.order.id, RefundInput(items = listOf(RefundMath.ItemRequest(line, 3))), r.key(), null) }
        }
        assertThrows(RequestValueException::class.java) { runBlocking { service.request(paid.order.id, RefundInput(items = listOf(RefundMath.ItemRequest(9999, 1))), r.key(), null) } }
        r.expect("INVALID_REFUND_AMOUNT", 400) { service.request(paid.order.id, RefundInput(amount = 0), r.key(), null) }
        assertEquals(0, r.refunds(paid.order.id).size)
        assertEquals(0, r.fake.calls(Op.REFUND).size)

        assertEquals(0, r.order(paid.order.id).refundedTotal)
    }
}
