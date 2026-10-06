package com.panomc.plugins.market.job

import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.MarketRefund
import com.panomc.plugins.market.db.model.RefundOrigin
import com.panomc.plugins.market.db.model.RefundStatus
import com.panomc.plugins.market.service.RefundInput
import com.panomc.plugins.market.service.RefundLine
import com.panomc.plugins.market.service.RefundWorld
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.payment.RefundResult
import com.panomc.plugins.market.support.FakePaymentProvider.Op
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.Vertx
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `RefundReconcileJob` on a real MariaDB (MK-111; 21 sections 3.3 and 3.5): the schedule of `queryRefund` (5 min, 30 min, every 6 h for 30 days), a refund whose call
 * got no answer (unknown outcome), the rows nobody sent. The `revokeFirst` rows of the job are proven in `RefundServiceIT` (V-04, RD-D5). The invariants I1 to I22 are
 * checked after every test by the base class.
 */
class RefundReconcileJobIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var r: RefundWorld
    private val vertx: Vertx = Vertx.vertx()

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    @BeforeEach
    fun wire() {
        w = TestWiring(pool)
        r = RefundWorld(w, vertx)
    }

    /** A `REQUESTED` row whose call went out and never came back (a crash after tx1): the claim of 60 s has run out. */
    private suspend fun crashed(orderId: Long, paymentId: Long, amount: Long, key: String): MarketRefund {
        val now = w.clock.now()
        val id = w.refunds.add(
            MarketRefund(
                orderId = orderId, paymentId = paymentId, providerId = "fake", status = RefundStatus.REQUESTED, origin = RefundOrigin.PANEL, idempotencyKey = key, amount = amount,
                gatewayAmount = amount, currency = "EUR", revoke = true, queryCount = 1, nextQueryAt = now + 60_000, createdAt = now, updatedAt = now
            ),
            pool
        )!!

        w.clock.advance(61_000)

        return r.refund(id)
    }

    @Test
    fun `a refund whose call got no answer is asked after 60 seconds and the answer is applied`(): Unit = runBlocking {
        val paid = r.place(w.fixtures.user("Crash"), listOf(RefundLine(1000)))
        val row = crashed(paid.order.id, paid.attempt.id, 400, "crash-key-0000000000000001")

        r.onQueryRefund = { RefundResult.Succeeded() }

        assertEquals(1, r.job.run().polled)
        assertEquals(RefundStatus.SUCCEEDED, r.refund(row.id).status)
        assertEquals(400, r.order(paid.order.id).refundedTotal)
        assertEquals("crash-key-0000000000000001", r.queries.single().idempotencyKey)
        assertEquals(0, r.fake.calls(Op.REFUND).size, "asking never sends")
    }

    @Test
    fun `an unknown outcome stays visible for retry, which sends the same key again so the gateway refunds once`(): Unit = runBlocking {
        val paid = r.place(w.fixtures.user("Unknown"), listOf(RefundLine(1000)))
        val row = crashed(paid.order.id, paid.attempt.id, 400, "unknown-key-00000000000001")

        // the provider cannot answer: nothing changes, the row is still retryable (it is no call in flight)
        assertEquals(1, r.job.run().polled)
        assertEquals(RefundStatus.REQUESTED, r.refund(row.id).status)
        assertEquals(0, r.order(paid.order.id).refundedTotal)
        assertNotNull(r.refund(row.id).nextQueryAt)

        val retried = r.service.retry(row.id)

        assertEquals(RefundStatus.SUCCEEDED, retried.refund.status)
        assertEquals("unknown-key-00000000000001", (r.fake.calls(Op.REFUND).single().request as com.panomc.plugins.market.spi.payment.RefundRequest).idempotencyKey)
        assertEquals(400, r.order(paid.order.id).refundedTotal)
    }

    @Test
    fun `a call that is still in flight is neither asked nor retried`(): Unit = runBlocking {
        val paid = r.place(w.fixtures.user("Flight"), listOf(RefundLine(1000)))
        val now = w.clock.now()
        val id = w.refunds.add(
            MarketRefund(
                orderId = paid.order.id, paymentId = paid.attempt.id, providerId = "fake", status = RefundStatus.REQUESTED, origin = RefundOrigin.PANEL,
                idempotencyKey = "flight-key-0000000000000001", amount = 400, gatewayAmount = 400, currency = "EUR", queryCount = 1, nextQueryAt = now + 60_000,
                createdAt = now, updatedAt = now
            ),
            pool
        )!!

        assertEquals(0, r.job.run().polled)

        r.expect("INVALID_STATE", 409) { r.service.retry(id) }
        r.expect("INVALID_STATE", 409) { r.service.cancel(id) }
    }

    @Test
    fun `the query schedule is 5 minutes, 30 minutes, then every 6 hours, and stops after 30 days`(): Unit = runBlocking {
        val paid = r.place(w.fixtures.user("Schedule"), listOf(RefundLine(1000)))

        r.fake.onRefund = { RefundResult.Pending().also { p -> p.gatewayRefundId = "gw-sched" } }

        val pending = r.service.request(paid.order.id, RefundInput(), r.key("sched"), null).refund
        val start = pending.createdAt

        assertEquals(start + 5 * 60_000, r.refund(pending.id).nextQueryAt)

        w.clock.advance(5 * 60_000)
        assertEquals(1, r.job.run().polled)
        assertEquals(start + 30 * 60_000, r.refund(pending.id).nextQueryAt)

        w.clock.advance(25 * 60_000)
        assertEquals(1, r.job.run().polled)
        assertEquals(w.clock.now() + 6 * 3_600_000, r.refund(pending.id).nextQueryAt)

        w.clock.advance(6 * 3_600_000 - 1)
        assertEquals(0, r.job.run().polled, "not due yet")

        w.clock.advance(1)
        assertEquals(1, r.job.run().polled)

        // after 30 days the row is asked one last time and then left to the admin
        w.clock.advance(31L * 24 * 3_600_000)
        assertEquals(1, r.job.run().polled)
        assertNull(r.refund(pending.id).nextQueryAt)
        assertEquals(0, r.job.run().polled)
        assertEquals(RefundStatus.PENDING, r.refund(pending.id).status)
        assertEquals(OrderStatus.COMPLETED, r.order(paid.order.id).status)
        assertEquals(4, r.queries.size)
    }

    // ===== a question that errors is no answer (21 section 3.3 "Unknown changes nothing", section 9.2) ==================================

    @Test
    fun `a status query that errors leaves a pending refund PENDING with its next query and its reservation`(): Unit = runBlocking {
        val paid = r.place(w.fixtures.user("Down"), listOf(RefundLine(1000)))

        r.fake.onRefund = { RefundResult.Pending().also { p -> p.gatewayRefundId = "gw-down" } }

        val pending = r.service.request(paid.order.id, RefundInput(), r.key("down"), null).refund
        val start = pending.createdAt

        // the gateway is down when the job asks: a retryable provider error, then an unexpected one
        r.onQueryRefund = { throw ProviderException(ProviderErrorCode.GATEWAY_UNREACHABLE, "the gateway is down", retryable = true) }
        w.clock.advance(5 * 60_000)

        assertEquals(1, r.job.run().polled)

        var row = r.refund(pending.id)

        assertEquals(RefundStatus.PENDING, row.status, "an error is not an answer")
        assertNull(row.failureCode)
        assertEquals(start + 30 * 60_000, row.nextQueryAt, "asked again on the schedule")
        assertEquals(0, r.order(paid.order.id).refundedTotal)

        // the money may be on its way: it keeps bounding the remainder, a second full refund is refused and never reaches the gateway
        val body = r.expect("INVALID_REFUND_AMOUNT", 400) { r.service.request(paid.order.id, RefundInput(), r.key("second"), null) }

        assertEquals(0.0, body.getDouble("max"), 0.0)
        assertEquals(1, r.fake.calls(Op.REFUND).size)

        r.onQueryRefund = { throw ProviderException(ProviderErrorCode.INTERNAL, "boom") }
        w.clock.advance(25 * 60_000)

        assertEquals(1, r.job.run().polled)
        assertEquals(RefundStatus.PENDING, r.refund(pending.id).status)
        assertEquals(w.clock.now() + 6 * 3_600_000, r.refund(pending.id).nextQueryAt)

        // the gateway is back and says the money left: that answer is applied
        r.onQueryRefund = { RefundResult.Succeeded() }
        w.clock.advance(6 * 3_600_000)

        assertEquals(1, r.job.run().polled)

        row = r.refund(pending.id)

        assertEquals(RefundStatus.SUCCEEDED, row.status)
        assertEquals(1000, r.order(paid.order.id).refundedTotal)
        assertEquals(OrderStatus.REFUNDED, r.order(paid.order.id).status)
        assertEquals(1, r.fake.calls(Op.REFUND).size, "asking never sends")
    }

    @Test
    fun `a status query that errors leaves a refund of unknown outcome REQUESTED and retryable`(): Unit = runBlocking {
        val paid = r.place(w.fixtures.user("Crashed"), listOf(RefundLine(1000)))
        val row = crashed(paid.order.id, paid.attempt.id, 1000, "crashed-key-000000000000001")

        r.onQueryRefund = { throw ProviderException(ProviderErrorCode.GATEWAY_UNREACHABLE, "the gateway is down", retryable = true) }

        assertEquals(1, r.job.run().polled)

        val asked = r.refund(row.id)

        assertEquals(RefundStatus.REQUESTED, asked.status, "the call may have executed: the row is not failed by a question that errors")
        assertNull(asked.failureCode)
        assertNotNull(asked.nextQueryAt)
        assertEquals(0, r.order(paid.order.id).refundedTotal)

        // still bounded while nobody knows
        r.expect("INVALID_REFUND_AMOUNT", 400) { r.service.request(paid.order.id, RefundInput(), r.key("second"), null) }

        // retry sends the same key, so a gateway that already executed it refunds once
        val retried = r.service.retry(row.id)

        assertEquals(RefundStatus.SUCCEEDED, retried.refund.status)
        assertEquals("crashed-key-000000000000001", (r.fake.calls(Op.REFUND).single().request as com.panomc.plugins.market.spi.payment.RefundRequest).idempotencyKey)
        assertEquals(1000, r.order(paid.order.id).refundedTotal)
    }

    @Test
    fun `a provider that is gone when the job asks changes nothing`(): Unit = runBlocking {
        val paid = r.place(w.fixtures.user("Gone"), listOf(RefundLine(1000)))

        r.fake.onRefund = { RefundResult.Pending().also { p -> p.gatewayRefundId = "gw-gone" } }

        val pending = r.service.request(paid.order.id, RefundInput(), r.key("gone"), null).refund

        // the provider plugin is unloaded (or being updated) when the query is due
        r.lookup.remove("fake")
        w.clock.advance(6 * 60_000)

        assertEquals(1, r.job.run().polled)
        assertEquals(0, r.queries.size, "nothing could be asked")

        val row = r.refund(pending.id)

        assertEquals(RefundStatus.PENDING, row.status)
        assertNull(row.failureCode)
        assertNotNull(row.nextQueryAt)
        assertEquals(0, r.order(paid.order.id).refundedTotal)
        assertEquals(OrderStatus.COMPLETED, r.order(paid.order.id).status)

        r.expect("INVALID_REFUND_AMOUNT", 400) { r.service.request(paid.order.id, RefundInput(), r.key("second"), null) }
        assertEquals(1, r.refunds(paid.order.id).size)
    }

    @Test
    fun `a pending refund the gateway reports failed ends FAILED and frees the amount`(): Unit = runBlocking {
        val paid = r.place(w.fixtures.user("Failing"), listOf(RefundLine(1000)))

        r.fake.onRefund = { RefundResult.Pending().also { p -> p.gatewayRefundId = "gw-fail" } }

        val pending = r.service.request(paid.order.id, RefundInput(), r.key("fail"), null).refund

        r.onQueryRefund = { RefundResult.Failed("expired", "the buyer never claimed it") }
        w.clock.advance(6 * 60_000)

        assertEquals(1, r.job.run().polled)

        val row = r.refund(pending.id)

        assertEquals(RefundStatus.FAILED, row.status)
        assertEquals("expired", row.failureCode)
        assertNull(row.nextQueryAt)
        assertEquals(0, r.order(paid.order.id).refundedTotal)

        r.fake.onRefund = { RefundResult.Succeeded() }

        assertEquals(RefundStatus.SUCCEEDED, r.service.request(paid.order.id, RefundInput(), r.key("again"), null).refund.status)
    }
}
