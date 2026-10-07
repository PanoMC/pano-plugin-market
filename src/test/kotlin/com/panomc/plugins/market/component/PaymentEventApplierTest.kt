package com.panomc.plugins.market.component

import com.panomc.plugins.market.core.abuse.Redactor
import com.panomc.plugins.market.core.payment.ProviderMoneyPolicy
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.routes.api.payment.EventTargetPending
import com.panomc.plugins.market.routes.api.payment.InboundEventContext
import com.panomc.plugins.market.routes.api.payment.PaymentEventApplier
import com.panomc.plugins.market.routes.api.payment.ProviderAccess
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.payment.DisputeState
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentTarget
import com.panomc.plugins.market.spi.payment.RefundState
import com.panomc.plugins.market.support.FakeClock
import com.panomc.plugins.market.support.FakePaymentProvider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The applier alone: a refund / dispute event ahead of its attempt's references is retried for a bounded time, everything else is skipped at once. */
class PaymentEventApplierTest {
    private val clock = FakeClock()
    private val attempts = FakeAttempts()
    private val seen = ArrayList<Pair<PaymentEvent, Long?>>()
    private val applier = PaymentEventApplier(attempts, clock) { event, attempt, _ -> seen += event to attempt?.id }
    private val access = ProviderAccess.Ready(FakePaymentProvider("fake"), ProviderMoneyPolicy(), Redactor()) { error("no context needed") }
    private val received = clock.now()

    private fun context() = InboundEventContext(1, "fake", "evt_1", null, received)

    private fun dispute(ref: String = "txn_late") = PaymentEvent.DisputeUpdated(PaymentTarget.GatewayTransaction(ref), DisputeState.OPENED)

    private fun refund(ref: String = "txn_late") = PaymentEvent.RefundUpdated(PaymentTarget.GatewayTransaction(ref), RefundState.SUCCEEDED, Money(500, "EUR"))

    private fun attach(ref: String = "txn_late") =
        attempts.addRaw(MarketPayment(id = 7, orderId = 7, providerId = "fake", reference = "REF7", token = "t7", amount = 1000, currency = "EUR", gatewayTransactionId = ref))

    @Test
    fun `a dispute that is unresolved is deferred, then applied once when the references arrive`(): Unit = runBlocking {
        assertThrows<EventTargetPending> { applier.apply(access, listOf(dispute()), context()) }
        assertTrue(seen.isEmpty())

        clock.advance(60_000)
        attach()

        val result = applier.apply(access, listOf(dispute()), context())

        assertEquals(1, result.applied)
        assertEquals(0, result.skipped)
        assertEquals(listOf(7L), seen.map { it.second })
    }

    @Test
    fun `a dispute that never resolves is skipped once the bound is over, without an exception`(): Unit = runBlocking {
        clock.advance(PaymentEventApplier.UNRESOLVED_GRACE_MS - 1)
        assertThrows<EventTargetPending> { applier.apply(access, listOf(dispute()), context()) }

        clock.advance(1)

        val result = applier.apply(access, listOf(dispute()), context())

        assertEquals(0, result.applied)
        assertEquals(1, result.skipped)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a refund behaves like a dispute`(): Unit = runBlocking {
        assertThrows<EventTargetPending> { applier.apply(access, listOf(refund()), context()) }

        attach()

        assertEquals(1, applier.apply(access, listOf(refund()), context()).applied)
        assertEquals(1, seen.size)

        val other = PaymentEventApplier(attempts, clock) { _, _, _ -> }

        clock.advance(PaymentEventApplier.UNRESOLVED_GRACE_MS)

        assertEquals(1, other.apply(access, listOf(refund("txn_never")), context()).skipped)
    }

    @Test
    fun `attempt machine kinds and a foreign ReferencesUpdated are skipped at once`(): Unit = runBlocking {
        val target = PaymentTarget.GatewayTransaction("txn_foreign")
        val events = listOf(
            PaymentEvent.Succeeded(target, Money(1000, "EUR")),
            PaymentEvent.Cancelled(target),
            PaymentEvent.Expired(target),
            PaymentEvent.ReferencesUpdated(target)
        )

        val result = applier.apply(access, events, context())

        assertEquals(4, result.skipped)
        assertEquals(0, result.applied)
        assertTrue(seen.isEmpty())
    }
}
