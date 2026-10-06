package com.panomc.plugins.market.spi.payment

import com.panomc.plugins.market.spi.payment.PaymentTestData.eur
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaymentEventTest {
    private val attempt = PaymentTarget.Attempt(5)

    @Test
    fun `every event variant constructs with its required data and keeps its target`() {
        val sub = GatewaySubscriptionState("sub_1", GatewaySubscriptionStatus.ACTIVE)
        val events: List<PaymentEvent> = listOf(
            PaymentEvent.Succeeded(attempt, eur(1000)),
            PaymentEvent.Pending(PaymentTarget.Reference("ABCDEFGHJKMNPQRSTVWX"), PendingReason.AWAITING_BANK),
            PaymentEvent.Failed(PaymentTarget.GatewayTransaction("tx"), "DECLINED", "card declined"),
            PaymentEvent.Cancelled(PaymentTarget.GatewayRef("session", "cs_1")),
            PaymentEvent.Expired(attempt),
            PaymentEvent.NeedsReview(attempt, ReviewReason.UNDERPAID),
            PaymentEvent.RefundUpdated(attempt, RefundState.SUCCEEDED, eur(100)),
            PaymentEvent.DisputeUpdated(attempt, DisputeState.OPENED),
            PaymentEvent.SubscriptionUpdated(sub),
            PaymentEvent.SubscriptionRenewed("sub_1", eur(500)),
            PaymentEvent.SubscriptionPaymentFailed("sub_1"),
            PaymentEvent.ReferencesUpdated(attempt)
        )
        assertEquals(PaymentEvent::class.sealedSubclasses.size, events.map { it::class }.distinct().size, "a variant is not covered")
        assertTrue(events[0].target === attempt)
        assertEquals("ABCDEFGHJKMNPQRSTVWX", (events[1].target as PaymentTarget.Reference).reference)
        assertEquals("tx", (events[2].target as PaymentTarget.GatewayTransaction).gatewayTransactionId)
        val ref = events[3].target as PaymentTarget.GatewayRef
        assertEquals("session" to "cs_1", ref.name to ref.value)
    }

    @Test
    fun `subscription events target the gateway subscription id`() {
        val updated = PaymentEvent.SubscriptionUpdated(GatewaySubscriptionState("sub_9", GatewaySubscriptionStatus.PAST_DUE))
        assertEquals("sub_9", (updated.target as PaymentTarget.Subscription).gatewaySubscriptionId)
        assertEquals("sub_2", (PaymentEvent.SubscriptionRenewed("sub_2", eur(1)).target as PaymentTarget.Subscription).gatewaySubscriptionId)
        assertEquals("sub_3", (PaymentEvent.SubscriptionPaymentFailed("sub_3").target as PaymentTarget.Subscription).gatewaySubscriptionId)
    }

    @Test
    fun `a new event has no optional data and every base var is settable`() {
        val e = PaymentEvent.Pending(attempt, PendingReason.OTHER)
        assertNull(e.occurredAt)
        assertNull(e.gatewayTransactionId)
        assertEquals(emptyMap<String, String>(), e.gatewayRefs)
        assertNull(e.providerData)
        assertNull(e.note)
        assertNull(e.testMode)
        e.occurredAt = 5
        e.gatewayTransactionId = "tx"
        e.gatewayRefs = mapOf("a" to "b")
        e.providerData = JsonObject().put("k", 1)
        e.note = "n"
        e.testMode = true
        assertEquals(5L, e.occurredAt)
        assertEquals(true, e.testMode)
    }

    @Test
    fun `succeeded carries the optional payment details`() {
        val e = PaymentEvent.Succeeded(attempt, eur(1230)).also {
            it.gatewayFee = eur(30)
            it.net = eur(1200)
            it.settlementCurrency = "USDC"
            it.settlementAmount = "12.340000"
            it.installments = 3
            it.methodDetail = "visa 4242"
            it.itemRefs = mapOf(11L to GatewayLineRef("li_1", eur(1000)), OrderLine.SHIPPING_LINE_ID to GatewayLineRef("li_s", eur(230)))
            it.storedMethod = StoredPaymentMethod("tok_1").also { m -> m.label = "visa"; m.expiresAt = 9; m.gatewayCustomerId = "cus" }
            it.subscription = GatewaySubscriptionState("sub_1", GatewaySubscriptionStatus.ACTIVE).also { s ->
                s.gatewayCustomerId = "cus"; s.currentPeriodStart = 1; s.currentPeriodEnd = 2; s.endsAt = 3; s.providerData = JsonObject()
            }
            it.externalTotals = ExternalTotals(eur(1300)).also { x -> x.tax = eur(200); x.discount = eur(50) }
        }
        assertEquals(eur(1230), e.paid)
        assertEquals(eur(230), e.itemRefs.getValue(OrderLine.SHIPPING_LINE_ID).charged)
        assertEquals("tok_1", e.storedMethod!!.token)
        assertEquals(GatewaySubscriptionStatus.ACTIVE, e.subscription!!.status)
        assertEquals(eur(200), e.externalTotals!!.tax)
    }

    @Test
    fun `failed review refund dispute and renewal variants hold their documented fields`() {
        val failed = PaymentEvent.Failed(attempt, "X", null)
        assertFalse(failed.final)
        failed.final = true
        assertTrue(failed.final)
        assertNull(failed.message)

        val review = PaymentEvent.NeedsReview(attempt, ReviewReason.OVERPAID).also { it.received = eur(1500) }
        assertEquals(eur(1500), review.received)

        val refund = PaymentEvent.RefundUpdated(attempt, RefundState.PENDING, null).also {
            it.gatewayRefundId = "re_1"; it.refundKey = "key"; it.cumulativeRefunded = eur(300); it.lines = mapOf(1L to eur(300)); it.buyerActionUrl = "https://x.example"
        }
        assertNull(refund.amount)
        assertEquals(RefundState.PENDING, refund.state)
        assertEquals(eur(300), refund.lines.getValue(1L))

        val dispute = PaymentEvent.DisputeUpdated(attempt, DisputeState.INQUIRY).also { it.gatewayDisputeId = "dp"; it.amount = eur(1); it.reason = "fraud" }
        assertEquals("dp", dispute.gatewayDisputeId)

        val renewed = PaymentEvent.SubscriptionRenewed("sub_1", eur(500)).also {
            it.periodStart = 1; it.periodEnd = 2; it.gatewayFee = eur(5); it.net = eur(495); it.methodDetail = "m"; it.externalTotals = ExternalTotals(eur(500))
        }
        assertEquals(eur(500), renewed.paid)
        assertEquals(1L, renewed.periodStart)

        val subFailed = PaymentEvent.SubscriptionPaymentFailed("sub_1").also { it.attemptCount = 2; it.nextRetryAt = 5; it.final = true }
        assertEquals(2, subFailed.attemptCount)
        assertTrue(subFailed.final)
    }

    @Test
    fun `event enums keep their documented values and order`() {
        assertEquals(listOf("AWAITING_BUYER", "AWAITING_CONFIRMATIONS", "AWAITING_BANK", "FRAUD_REVIEW", "AWAITING_CAPTURE", "OTHER"), PendingReason.values().map { it.name })
        // BLOCKED_BUYER (01 section 5.1, 06 section 6.8, 11 section 9.3) is the market's own review reason for a payer blocked after checkout (MK-151); a gateway never reports it
        assertEquals(listOf("UNDERPAID", "OVERPAID", "LATE", "WRONG_ASSET", "AMOUNT_MISMATCH", "CURRENCY_MISMATCH", "FRAUD_REVIEW", "BLOCKED_BUYER", "OTHER"), ReviewReason.values().map { it.name })
        assertEquals(listOf("PENDING", "SUCCEEDED", "FAILED", "CANCELLED"), RefundState.values().map { it.name })
        assertEquals(listOf("INQUIRY", "OPENED", "WON", "LOST", "CLOSED"), DisputeState.values().map { it.name })
        assertEquals(listOf("ACTIVE", "PAST_DUE", "PAUSED", "CANCEL_SCHEDULED", "CANCELLED", "ENDED"), GatewaySubscriptionStatus.values().map { it.name })
        assertEquals(listOf("SUCCESS", "CANCEL", "PENDING", "RESULT", "STEP"), ReturnOutcome.values().map { it.name })
    }
}
