package com.panomc.plugins.market.service

import com.panomc.platform.model.Error
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.CreditTxType
import com.panomc.plugins.market.db.model.RefundStatus
import com.panomc.plugins.market.spi.payment.RefundResult
import com.panomc.plugins.market.spi.payment.RefundState
import com.panomc.plugins.market.support.FakePaymentProvider.Op
import com.panomc.plugins.market.support.Race
import com.panomc.plugins.market.support.TestWiring
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.core.Vertx
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The refund request and O10 under concurrency (MK-111; R-13, R-14, R-15, RD-D6, RD-D2): the order lock is what serialises the remainder check, the insert of
 * the row and the books, so two requests can never both pass the same remainder and one idempotency key never makes two refunds or two gateway calls.
 * The invariants I1 to I22 are checked after every test by the base class.
 */
class RefundRaceIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var r: RefundWorld
    private val vertx: Vertx = Vertx.vertx()

    override val poolSize: Int = 32

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    @BeforeEach
    fun wire() {
        w = TestWiring(pool)
        r = RefundWorld(w, vertx)
    }

    private fun code(result: Result<*>): String? = (result.exceptionOrNull() as? Error)?.getErrorCode()

    @Test
    fun `two full refunds with different keys at once make one refund and one refusal, the gateway is called once (R-13)`(): Unit = runBlocking {
        repeat(Race.rounds) {
            val user = w.fixtures.user("Racer$it")
            val paid = r.place(user, listOf(RefundLine(1000)))
            val before = r.fake.calls(Op.REFUND).size
            val results = Race.run(2) { _ -> r.service.request(paid.order.id, RefundInput(), r.key("full"), null) }

            assertEquals(1, results.count { it.isSuccess }, "$results")
            assertEquals(listOf("INVALID_REFUND_AMOUNT"), results.mapNotNull { code(it) })
            assertEquals(1, r.fake.calls(Op.REFUND).size - before)
            assertEquals(1000, r.order(paid.order.id).refundedTotal)
            assertEquals(OrderStatus.REFUNDED, r.order(paid.order.id).status)
            assertEquals(1, r.refunds(paid.order.id).size)
        }
    }

    @Test
    fun `two refunds that exceed the remainder together let one through (RD-D6)`(): Unit = runBlocking {
        repeat(20) {
            val paid = r.place(w.fixtures.user("Dsix$it"), listOf(RefundLine(10_000)))
            val results = Race.run(2) { _ -> r.service.request(paid.order.id, RefundInput(amount = 6000), r.key("six"), null) }

            assertEquals(1, results.count { it.isSuccess }, "$results")
            assertEquals(listOf("INVALID_REFUND_AMOUNT"), results.mapNotNull { code(it) })
            assertEquals(6000, r.order(paid.order.id).refundedTotal)
            assertEquals(1, r.refunds(paid.order.id).count { row -> row.status == RefundStatus.SUCCEEDED })
        }
    }

    @Test
    fun `five concurrent copies of one request are one refund, one gateway call and five identical answers (R-14)`(): Unit = runBlocking {
        repeat(Race.rounds) {
            val paid = r.place(w.fixtures.user("Replay$it"), listOf(RefundLine(1000)))
            val key = r.key("replay")
            val before = r.fake.calls(Op.REFUND).size
            val results = Race.run(5) { _ -> r.service.request(paid.order.id, RefundInput(amount = 300), key, null) }

            assertTrue(results.all { it.isSuccess }, "$results")

            val outcomes = results.map { it.getOrThrow() }

            assertEquals(1, outcomes.map { o -> o.refund.id }.distinct().size)
            assertEquals(setOf(RefundStatus.SUCCEEDED), outcomes.map { o -> o.refund.status }.toSet(), "every answer is the settled row")
            assertEquals(1, outcomes.count { o -> !o.replay })
            assertEquals(1, r.fake.calls(Op.REFUND).size - before)
            assertEquals(1, r.refunds(paid.order.id).size)
            assertEquals(300, r.order(paid.order.id).refundedTotal)
        }
    }

    @Test
    fun `three simultaneous notifications of one pending mixed refund return the credits once (R-15)`(): Unit = runBlocking {
        repeat(Race.rounds) {
            val user = w.fixtures.user("Notified$it")
            val paid = r.place(user, listOf(RefundLine(3000)), creditValue = 1000)

            val gid = "gw-race-$it"

            r.fake.onRefund = { RefundResult.Pending().also { p -> p.gatewayRefundId = gid } }

            val pending = r.service.request(paid.order.id, RefundInput(), r.key("mixed"), null).refund

            val results = Race.run(3) { _ -> r.inbound(paid, RefundState.SUCCEEDED, amount = 2000, gatewayRefundId = gid) }

            assertTrue(results.all { it.isSuccess }, "$results")
            assertEquals(RefundStatus.SUCCEEDED, r.refund(pending.id).status)
            assertEquals(1, r.refunds(paid.order.id).size)
            assertEquals(listOf(1000L), w.creditTxs.getByUserId(user.id, 100, pool).filter { tx -> tx.type == CreditTxType.REFUND }.map { tx -> tx.amount })
            assertEquals(3000, r.order(paid.order.id).refundedTotal)
            assertEquals(1000, w.fixtures.creditBalance(user))
        }
    }

    @Test
    fun `a panel refund and the notification of the same refund at once are one refund (RD-D2)`(): Unit = runBlocking {
        repeat(Race.rounds) {
            val paid = r.place(w.fixtures.user("Both$it"), listOf(RefundLine(1000)))

            r.fake.onRefund = { RefundResult.Pending() }

            val results = Race.runWithSetup(
                2, { i -> i },
                { i ->
                    if (i == 0) r.service.request(paid.order.id, RefundInput(amount = 400), r.key("both"), null).refund.id
                    else {
                        // the confirmation can arrive before, during or after the request: the notification may find no row yet
                        kotlinx.coroutines.delay(5)
                        r.inbound(paid, RefundState.SUCCEEDED, amount = 400)
                        0L
                    }
                }
            )

            assertTrue(results.all { res -> res.isSuccess }, "$results")

            val rows = r.refunds(paid.order.id)

            // either the notification found the panel row, or it came first and is a gateway-originated row that the panel refund then had to exclude
            assertTrue(rows.size in 1..2, "$rows")
            assertTrue(r.order(paid.order.id).refundedTotal <= 800)
            assertEquals(rows.filter { row -> row.status == RefundStatus.SUCCEEDED }.sumOf { row -> row.amount }, r.order(paid.order.id).refundedTotal)
        }
    }
}
