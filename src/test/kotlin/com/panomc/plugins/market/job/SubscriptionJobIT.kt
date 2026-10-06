package com.panomc.plugins.market.job

import com.panomc.plugins.market.core.subscription.RetrySchedule
import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.db.model.EntitlementStatus
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.model.PaymentStatus
import com.panomc.plugins.market.db.model.RemoteCancelState
import com.panomc.plugins.market.db.model.RenewalStatus
import com.panomc.plugins.market.db.model.SubscriptionMode
import com.panomc.plugins.market.db.model.SubscriptionStatus
import com.panomc.plugins.market.service.AttemptFacts
import com.panomc.plugins.market.service.BankTransferDecision
import com.panomc.plugins.market.service.BuyerBlocks
import com.panomc.plugins.market.service.PaymentEventMapper
import com.panomc.plugins.market.service.PayCaller
import com.panomc.plugins.market.service.PayRequest
import com.panomc.plugins.market.service.RenewalITBase
import com.panomc.plugins.market.service.bankSettings
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.common.TestModeSupport
import com.panomc.plugins.market.spi.payment.CancelSubscriptionResult
import com.panomc.plugins.market.spi.payment.GatewaySubscriptionStatus
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentQueryResult
import com.panomc.plugins.market.spi.payment.PaymentTarget
import com.panomc.plugins.market.spi.payment.PendingReason
import com.panomc.plugins.market.spi.payment.RecurringChargeResult
import com.panomc.plugins.market.spi.payment.RecurringSupport
import com.panomc.plugins.market.spi.payment.StoredPaymentMethod
import com.panomc.plugins.market.spi.payment.SubscriptionQueryResult
import com.panomc.plugins.market.support.FakePaymentProvider
import com.panomc.plugins.market.util.OrderStatus
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `SubscriptionJob` on a real MariaDB with a fake clock and the fake provider (MK-122; 09 sections 8.3, 8.6, 9, 10.3 and 11; S-01, S-02 and the tests 34 to 50 (51 is
 * `SubscriptionRenewalIT`'s), 55, 63, 65 to 69 of 09 section 16): the charge of a merchant subscription (tx1, the call, tx2), the failures and the retry schedule, the
 * grace end, the in-flight and the unknown outcome (a status query that is not answered closes nothing), the environment of a charge, a blocked buyer, the manual
 * renewal, the steps C to F, the batch limit and the row isolation, the race of a renewal against the grace end and the remote cancel queue. The invariants I1 to I22
 * are checked after every test.
 */
internal class SubscriptionJobIT : RenewalITBase() {
    private fun declined() {
        fake.onChargeRecurring = { throw ProviderException(ProviderErrorCode.GATEWAY_REJECTED, "card declined", "402") }
    }

    private fun polls() = fake.calls(FakePaymentProvider.Op.QUERY_SUBSCRIPTION).size

    private fun context() = com.panomc.plugins.market.routes.api.payment.InboundEventContext(1, "fake", null, null, w.clock.now())

    // ==================================================================================== S-01

    @Test
    fun `S-01 a merchant subscription is charged once at the period end with the stored method, renewed, and charged again a month later`(): Unit = runBlocking {
        val a = activeMerchant()

        chargeSucceeds()

        // before the period ends nothing is charged (the buyer is told that the charge is coming: the upcoming-charge notice)
        w.clock.set(a.sub.nextChargeAt!! - 1)
        rw.job.runOnce()
        assertEquals(0, charges().size)
        assertEquals(0, renewals(a.sub.id).size)

        // at the period end: one charge, with the stored token and the key of the first renewal
        w.clock.set(a.sub.nextChargeAt!!)
        rw.job.runOnce()
        rw.job.runOnce()

        val request = charges().single()

        assertEquals("sub-${a.sub.id}-1-1", request.idempotencyKey)
        assertEquals("tok_1", request.storedMethod.token)
        assertEquals(a.sub.price, request.amount.amount)
        assertEquals(a.sub.id, request.subscription.id)
        assertTrue(request.notifyUrl.contains("/notify/"))

        val renewed = subscription(a.sub.id)

        assertEquals(2, renewed.cycleCount)
        assertEquals(1, renewals(a.sub.id).count { it.status == RenewalStatus.PAID })
        assertEquals(1, hooks("subscription.renewed").size)
        assertTrue(sw.dw.rows(renewals(a.sub.id).single().orderId!!).all { it.phase == DeliveryPhase.RENEW })

        // a month later: the second renewal, key of the second period
        w.clock.set(renewed.nextChargeAt!!)
        rw.job.runOnce()

        assertEquals(listOf("sub-${a.sub.id}-1-1", "sub-${a.sub.id}-2-1"), charges().map { it.idempotencyKey })
        assertEquals(3, subscription(a.sub.id).cycleCount)
        assertEquals(2, hooks("subscription.renewed").size)
        assertEquals(1, w.entitlements.getByOwnerAndProduct(a.order.buyerKey, a.product.id, pool).size, "one entitlement for the whole subscription")
    }

    // ==================================================================================== S-02

    @Test
    fun `S-02 a declined renewal goes through PAST_DUE, the retry schedule and the grace end to EXPIRED, with the end actions and the entitlement ended`(): Unit = runBlocking {
        val a = activeMerchant()
        val t0 = a.sub.nextChargeAt!!

        declined()

        // first decline at the period end: PAST_DUE, one mail, the grace is frozen, the next try is a day later
        w.clock.set(t0)
        rw.job.runOnce()

        var row = subscription(a.sub.id)
        val renewalOrder = renewalOrderOf(a.sub.id)

        assertEquals(SubscriptionStatus.PAST_DUE, row.status)
        assertEquals(t0 + 3 * day, row.graceEndsAt)
        assertEquals(1, row.failCount)
        assertEquals(t0 + day, row.nextChargeAt, "retry at +1 day")
        assertEquals(t0 + day, renewals(a.sub.id).single().nextAttemptAt)
        assertEquals("GATEWAY_REJECTED", renewals(a.sub.id).single().lastError)
        assertEquals(1, mails("SUBSCRIPTION_PAYMENT_FAILED"))
        assertEquals(PaymentStatus.FAILED, attempts(renewalOrder.id).single().status)
        assertEquals(OrderStatus.PENDING, order(renewalOrder.id).status, "the buyer can still pay it")
        assertEquals(1, events(a.order.id, OrderEventType.SUBSCRIPTION_PAST_DUE).size)

        val failure = events(a.order.id, OrderEventType.SUBSCRIPTION_CHARGE_FAILED).single()

        assertEquals("GATEWAY_REJECTED", JsonObject(failure.data!!).getString("code"))
        assertEquals(false, JsonObject(failure.data!!).getBoolean("final"))
        assertEquals(1, JsonObject(failure.data!!).getInteger("attempts"))

        // nothing happens before the retry is due
        w.clock.set(t0 + day - 1)
        rw.job.runOnce()
        assertEquals(1, charges().size)

        // second decline at +1 day: the next try is at +3 days (the grace end)
        w.clock.set(t0 + day)
        rw.job.runOnce()

        row = subscription(a.sub.id)

        assertEquals(2, charges().size)
        assertEquals("sub-${a.sub.id}-1-2", charges().last().idempotencyKey)
        assertEquals(2, row.failCount)
        assertEquals(t0 + 3 * day, row.nextChargeAt)
        assertEquals(1, mails("SUBSCRIPTION_PAYMENT_FAILED"), "the mail is queued once per period")
        assertEquals(listOf(PaymentStatus.FAILED, PaymentStatus.FAILED), attempts(renewalOrder.id).map { it.status }, "one attempt per charge, both declined")

        // third decline at the grace end: no more tries, and the grace ends in the same tick
        w.clock.set(t0 + 3 * day)
        rw.job.runOnce()

        row = subscription(a.sub.id)

        assertEquals(3, charges().size)
        assertEquals(SubscriptionStatus.EXPIRED, row.status)
        assertEquals("PAYMENT_FAILED", row.endReason)
        assertNull(row.nextChargeAt)
        assertNull(row.graceEndsAt)
        assertNull(row.storedMethod, "the token is dropped")
        assertEquals(RenewalStatus.FAILED, renewals(a.sub.id).single().status)

        // the end: the entitlement, the EXPIRE rows once, the mail, the webhook, the unpaid renewal order
        assertEquals(EntitlementStatus.EXPIRED, w.entitlements.getById(row.entitlementId!!, pool)!!.status)
        assertEquals("SUBSCRIPTION_ENDED", w.entitlements.getById(row.entitlementId!!, pool)!!.endReason)
        assertTrue(sw.dw.rows(a.order.id).any { it.phase == DeliveryPhase.EXPIRE })
        assertEquals(1, mails("SUBSCRIPTION_ENDED"))
        assertEquals(1, hooks("subscription.expired").size)
        assertEquals(OrderStatus.CANCELLED, order(renewalOrder.id).status, "the unpaid renewal order is cancelled")

        // a later run changes nothing
        rw.job.runOnce()
        assertEquals(3, charges().size)
        assertEquals(1, hooks("subscription.expired").size)
    }

    @Test
    fun `the retry schedule follows 09 section 9 2, the retry after the last decline is the grace end itself`(): Unit = runBlocking {
        // the pure schedule is RetryScheduleTest's; here: with the default 3 day grace the third failure schedules nothing
        val grace = 3 * day

        assertEquals(1 * day, RetrySchedule.nextRetryAt(1, 0, grace))
        assertEquals(2 * day, RetrySchedule.nextRetryAt(2, day, grace)!! - day)
        assertNull(RetrySchedule.nextRetryAt(3, 3 * day, grace))
    }

    @Test
    fun `a failed charge that is final waits for the grace end and the buyer pays the order by hand, which keeps the subscription and replaces the card`(): Unit = runBlocking {
        val a = activeMerchant(cardExpiresInDays = 10)

        // the card expired before the charge: a final failure without a provider call, the order exists so that the buyer can pay
        w.clock.set(a.sub.nextChargeAt!!)
        rw.job.runOnce()

        val row = subscription(a.sub.id)
        val renewalOrder = renewalOrderOf(a.sub.id)

        assertEquals(0, charges().size, "the provider is not called")
        assertEquals(SubscriptionStatus.PAST_DUE, row.status)
        assertNull(row.nextChargeAt, "a final failure: no automatic try")
        assertEquals(1, row.failCount)
        assertEquals(OrderStatus.PENDING, renewalOrder.status)
        assertEquals(0, attempts(renewalOrder.id).size)
        assertEquals("STORED_METHOD_EXPIRED", renewals(a.sub.id).single().lastError)
        assertEquals(1, mails("SUBSCRIPTION_PAYMENT_FAILED"))

        val mail = sql("SELECT `params` FROM `pano_market_mail_outbox` WHERE `kind` = 'SUBSCRIPTION_PAYMENT_FAILED'").single()

        assertEquals("/store/order/${renewalOrder.publicId}", JsonObject(mail.getString("params")).getString("payUrl"))

        // the buyer pays by hand with a new card, inside the grace: ACTIVE again, the period starts where the old one ended
        rw.payments.pay(renewalOrder, PayRequest("fake", null, null), PayCaller(), pool)

        val paying = attempts(renewalOrder.id).last()
        val event = PaymentEvent.Succeeded(PaymentTarget.Attempt(paying.id), Money(paying.amount, paying.currency)).also {
            it.storedMethod = StoredPaymentMethod("tok_new").also { m -> m.label = "Mastercard 5555"; m.expiresAt = w.clock.now() + 400 * day }
        }

        rw.payments.applyEvent(renewalOrder.id, paying.id, PaymentEventMapper.attemptEvent(event)!!, AttemptFacts.of(event, sw.cipher))

        val paid = subscription(a.sub.id)

        assertEquals(SubscriptionStatus.ACTIVE, paid.status)
        assertEquals(SubscriptionMode.MERCHANT, paid.mode)
        assertEquals(a.sub.currentPeriodEnd, paid.currentPeriodStart, "paid inside the grace: the period keeps its start")
        assertNull(paid.graceEndsAt)
        assertEquals(0, paid.failCount)
        assertEquals("tok_new", JsonObject(sw.cipher.decrypt(paid.storedMethod!!)!!).getString("token"))
        assertEquals(paid.currentPeriodEnd, paid.nextChargeAt)
        assertEquals(2, paid.cycleCount)
    }

    // ==================================================================================== in flight, unknown outcome

    @Test
    fun `a pending charge is never charged again, a later success applies it and a charge that never settles expires at the grace end plus seven days`(): Unit = runBlocking {
        val a = activeMerchant()

        fake.onChargeRecurring = { RecurringChargeResult(listOf(PaymentEvent.Pending(PaymentTarget.Attempt(it.attempt.id), PendingReason.AWAITING_BANK))) }
        w.clock.set(a.sub.nextChargeAt!!)
        rw.job.runOnce()

        val renewalOrder = renewalOrderOf(a.sub.id)
        val attempt = attempts(renewalOrder.id).single()

        assertEquals(PaymentStatus.PROCESSING, attempt.status)
        assertNull(subscription(a.sub.id).nextChargeAt, "settled later by the gateway")
        assertNotNull(attempt.nextQueryAt, "the reconcile job asks about it")

        // later ticks do not charge again
        w.clock.set(a.sub.nextChargeAt!! + 20 * 60_000)
        rw.job.runOnce()
        assertEquals(1, charges().size)

        // the notification arrives: applied through the normal path
        val success = PaymentEvent.Succeeded(PaymentTarget.Attempt(attempt.id), Money(attempt.amount, attempt.currency))

        rw.payments.applyEvent(renewalOrder.id, attempt.id, PaymentEventMapper.attemptEvent(success)!!, AttemptFacts.of(success, sw.cipher))

        assertEquals(2, subscription(a.sub.id).cycleCount)
        assertEquals(SubscriptionMode.MERCHANT, subscription(a.sub.id).mode)
        assertEquals(1, charges().size)
    }

    @Test
    fun `a pending charge that never settles keeps the subscription past due until the grace end plus seven days`(): Unit = runBlocking {
        val a = activeMerchant()

        fake.onChargeRecurring = { RecurringChargeResult(listOf(PaymentEvent.Pending(PaymentTarget.Attempt(it.attempt.id), PendingReason.AWAITING_BANK))) }

        val t0 = a.sub.nextChargeAt!!

        w.clock.set(t0)
        rw.job.runOnce()

        // a day after the period end (the renewal slack) the row is past due, the charge is still in flight
        w.clock.set(t0 + day + 1)
        rw.job.runOnce()

        val past = subscription(a.sub.id)

        assertEquals(SubscriptionStatus.PAST_DUE, past.status)
        assertNull(past.nextChargeAt, "an attempt is in flight: no charge is scheduled")
        assertEquals(1, charges().size)

        // the grace ends, the attempt is still PROCESSING: the expiry waits, at most seven days
        w.clock.set(past.graceEndsAt!! + 1)
        rw.job.runOnce()
        assertEquals(SubscriptionStatus.PAST_DUE, subscription(a.sub.id).status)

        w.clock.set(past.graceEndsAt!! + 7 * day + 1)
        rw.job.runOnce()
        assertEquals(SubscriptionStatus.EXPIRED, subscription(a.sub.id).status)
    }

    @Test
    fun `a charge whose outcome is unknown is not repeated, the status query settles it, the gateway not knowing it expires it after 15 minutes as a failure`(): Unit = runBlocking {
        // 1. the provider has no status query: the attempt stays, nothing is charged again
        val a = activeMerchant()

        fake.failNext(FakePaymentProvider.Op.CHARGE_RECURRING, ProviderException(ProviderErrorCode.INTERNAL, "connection reset after the request was sent"))
        w.clock.set(a.sub.nextChargeAt!!)
        rw.job.runOnce()

        val renewalOrder = renewalOrderOf(a.sub.id)

        assertEquals(1, charges().size)
        assertEquals(PaymentStatus.CREATED, attempts(renewalOrder.id).single().status, "intent written, outcome unknown")
        assertEquals(w.clock.now() + 15 * 60_000, subscription(a.sub.id).nextChargeAt, "the lease")

        w.clock.advance(15 * 60_000)
        rw.job.runOnce()
        rw.job.runOnce()

        assertEquals(1, charges().size, "never charged again")
        assertEquals(PaymentStatus.CREATED, attempts(renewalOrder.id).single().status, "without a status query it waits for a human")
        assertNull(subscription(a.sub.id).nextChargeAt)
    }

    @Test
    fun `an unknown outcome that the status query reports as paid is applied, one the gateway does not know is failed after 15 minutes`(): Unit = runBlocking {
        // paid: the query reports the success
        val a = activeMerchant()

        sw.caps(RecurringSupport.MERCHANT_INITIATED) { statusQuery = true }
        fake.failNext(FakePaymentProvider.Op.CHARGE_RECURRING, ProviderException(ProviderErrorCode.INTERNAL, "timeout"))
        fake.onQuery = { request -> PaymentQueryResult.of(PaymentEvent.Succeeded(PaymentTarget.Attempt(request.attempt.id), request.attempt.amount)) }
        w.clock.set(a.sub.nextChargeAt!!)
        rw.job.runOnce()
        w.clock.advance(15 * 60_000)
        rw.job.runOnce()

        assertEquals(1, charges().size)
        assertEquals(2, subscription(a.sub.id).cycleCount, "the query found the money")
        assertEquals(SubscriptionStatus.ACTIVE, subscription(a.sub.id).status)
    }

    @Test
    fun `an attempt the gateway never heard of is expired as UNKNOWN_OUTCOME and counted as a failure`(): Unit = runBlocking {
        val a = activeMerchant()

        sw.caps(RecurringSupport.MERCHANT_INITIATED) { statusQuery = true }
        fake.failNext(FakePaymentProvider.Op.CHARGE_RECURRING, ProviderException(ProviderErrorCode.INTERNAL, "timeout"))
        w.clock.set(a.sub.nextChargeAt!!)
        rw.job.runOnce()
        w.clock.advance(15 * 60_000)
        rw.job.runOnce()

        val renewalOrder = renewalOrderOf(a.sub.id)
        val attempt = attempts(renewalOrder.id).single()
        val row = subscription(a.sub.id)

        assertEquals(1, charges().size)
        assertEquals(PaymentStatus.EXPIRED, attempt.status)
        assertEquals("UNKNOWN_OUTCOME", attempt.failureCode)
        assertEquals(SubscriptionStatus.PAST_DUE, row.status)
        assertEquals(1, row.failCount)
        assertNotNull(row.nextChargeAt, "the failure schedules the retry")
        assertEquals(OrderStatus.PENDING, order(renewalOrder.id).status)
    }

    @Test
    fun `a status query that gets no answer closes nothing, the attempt stays CREATED and is asked about again, the card is never charged again`(): Unit = runBlocking {
        val a = activeMerchant()

        sw.caps(RecurringSupport.MERCHANT_INITIATED) { statusQuery = true }
        fake.failNext(FakePaymentProvider.Op.CHARGE_RECURRING, ProviderException(ProviderErrorCode.INTERNAL, "timeout: the charge may have gone through"))
        // the gateway is down for the query too: the case where the first charge went through and nothing can be learned about it
        fake.onQuery = { throw ProviderException(ProviderErrorCode.GATEWAY_UNREACHABLE, "the gateway is down") }
        w.clock.set(a.sub.nextChargeAt!!)
        rw.job.runOnce()

        val renewalOrder = renewalOrderOf(a.sub.id)

        // every lease later the question is asked again and not answered: the attempt is neither expired nor counted as a decline
        repeat(4) { n ->
            w.clock.advance(15 * 60_000)
            rw.job.runOnce()

            val row = subscription(a.sub.id)

            assertEquals(PaymentStatus.CREATED, attempts(renewalOrder.id).single().status, "round ${n + 1}")
            assertEquals(1, charges().size, "round ${n + 1}: never charged again")
            assertEquals(n + 1, fake.calls(FakePaymentProvider.Op.QUERY).size, "round ${n + 1}: the attempt is asked about")
            assertEquals(SubscriptionStatus.ACTIVE, row.status)
            assertEquals(0, row.failCount)
            assertEquals(w.clock.now() + 15 * 60_000, row.nextChargeAt, "round ${n + 1}: the question is armed again")
        }

        assertEquals(0, mails("SUBSCRIPTION_PAYMENT_FAILED"))
        assertEquals(0, events(a.order.id, OrderEventType.SUBSCRIPTION_CHARGE_FAILED).size)
        assertEquals(OrderStatus.PENDING, order(renewalOrder.id).status)

        // the gateway answers again and knows the money: applied, the subscription renews, still the one charge
        fake.onQuery = { request -> PaymentQueryResult.of(PaymentEvent.Succeeded(PaymentTarget.Attempt(request.attempt.id), request.attempt.amount)) }
        w.clock.advance(15 * 60_000)
        rw.job.runOnce()

        assertEquals(2, subscription(a.sub.id).cycleCount)
        assertEquals(SubscriptionStatus.ACTIVE, subscription(a.sub.id).status)
        assertEquals(1, charges().size)
    }

    // ==================================================================================== technical failures, UNSUPPORTED

    @Test
    fun `a technical failure retries in an hour without a mail or a counted failure, and a technical failure at the grace end is PROVIDER_UNAVAILABLE`(): Unit = runBlocking {
        val a = activeMerchant()
        val t0 = a.sub.nextChargeAt!!

        fake.onChargeRecurring = { throw ProviderException(ProviderErrorCode.GATEWAY_UNREACHABLE, "down") }
        w.clock.set(t0)
        rw.job.runOnce()

        var row = subscription(a.sub.id)
        val renewalOrder = renewalOrderOf(a.sub.id)

        assertEquals(SubscriptionStatus.ACTIVE, row.status)
        assertEquals(0, row.failCount)
        assertEquals(t0 + 60 * 60_000, row.nextChargeAt)
        assertEquals(0, mails("SUBSCRIPTION_PAYMENT_FAILED"))
        assertEquals("GATEWAY_UNREACHABLE", renewals(a.sub.id).single().lastError)
        assertEquals(PaymentStatus.FAILED, attempts(renewalOrder.id).single().status)

        // the gateway is back an hour later: the same renewal order is charged with the next key and the subscription renews
        chargeSucceeds()
        w.clock.set(t0 + 60 * 60_000)
        rw.job.runOnce()

        assertEquals(listOf("sub-${a.sub.id}-1-1", "sub-${a.sub.id}-1-2"), charges().map { it.idempotencyKey })
        assertEquals(1, count("market_order", "`source` = 'RENEWAL'"), "one order for the period")
        assertEquals(2, subscription(a.sub.id).cycleCount)
    }

    @Test
    fun `a gateway that stays down past the period end plus a day leaves the subscription past due and it ends as PROVIDER_UNAVAILABLE`(): Unit = runBlocking {
        val a = activeMerchant()
        val t0 = a.sub.nextChargeAt!!

        fake.onChargeRecurring = { throw ProviderException(ProviderErrorCode.GATEWAY_UNREACHABLE, "down") }
        w.clock.set(t0)
        rw.job.runOnce()

        // 24 hours later it is still unpaid: PAST_DUE (the technical failures never made it so)
        w.clock.set(t0 + day + 1)
        rw.job.runOnce()

        val past = subscription(a.sub.id)

        assertEquals(SubscriptionStatus.PAST_DUE, past.status)
        assertEquals(0, past.failCount, "technical failures are not counted")
        assertEquals(1, mails("SUBSCRIPTION_PAYMENT_FAILED"))

        w.clock.set(past.graceEndsAt!!)
        rw.job.runOnce()

        assertEquals(SubscriptionStatus.EXPIRED, subscription(a.sub.id).status)
        assertEquals("PROVIDER_UNAVAILABLE", subscription(a.sub.id).endReason)
    }

    @Test
    fun `UNSUPPORTED turns the subscription manual, drops the token and sends the reminder`(): Unit = runBlocking {
        val a = activeMerchant()

        fake.onChargeRecurring = { throw ProviderException(ProviderErrorCode.UNSUPPORTED, "no stored method charges") }
        w.clock.set(a.sub.nextChargeAt!!)
        rw.job.runOnce()

        val row = subscription(a.sub.id)
        val renewalOrder = renewalOrderOf(a.sub.id)

        assertEquals(SubscriptionMode.MANUAL, row.mode)
        assertNull(row.storedMethod)
        assertNull(row.storedMethodLabel)
        assertNull(row.nextChargeAt)
        // the charge is due at the period end, so the manual renewal is due then too: not paid at the period end is past due (09 section 8.6), with its grace
        assertEquals(SubscriptionStatus.PAST_DUE, row.status)
        assertEquals(a.sub.currentPeriodEnd!! + 3 * day, row.graceEndsAt)
        assertEquals(1, mails("SUBSCRIPTION_PAYMENT_FAILED"))
        assertEquals(0, row.failCount, "UNSUPPORTED is no failed charge")
        assertEquals(PaymentStatus.FAILED, attempts(renewalOrder.id).single().status)
        assertEquals(a.sub.currentPeriodEnd!! + 3 * day, renewalOrder.expiresAt, "payable for the grace period")
        assertEquals(1, mails("SUBSCRIPTION_REMINDER"))
        assertTrue(events(a.order.id, OrderEventType.NOTE).any { it.message?.contains("renews manually") == true })

        // never charged again
        w.clock.advance(day)
        rw.job.runOnce()
        assertEquals(1, charges().size)
    }

    // ==================================================================================== no charge

    @Test
    fun `no charge is made for a plan that is cancelled at the period end, a finished plan or a test subscription whose provider left test mode`(): Unit = runBlocking {
        val cancelled = activeMerchant(name = "Cara")

        sql("UPDATE `pano_market_subscription` SET `cancelAtPeriodEnd` = 1, `cancelRequestedAt` = ?, `endReason` = 'BUYER_CANCEL' WHERE `id` = ?", w.clock.now(), cancelled.sub.id)

        val finished = activeMerchant(maxCycles = 1, name = "Fay")
        val testing = activeMerchant(name = "Tess")

        sql("UPDATE `pano_market_subscription` SET `testMode` = 1 WHERE `id` = ?", testing.sub.id)
        chargeSucceeds()
        w.clock.set(cancelled.sub.nextChargeAt!!)
        rw.job.runOnce()

        assertEquals(0, charges().size)
        assertNull(subscription(finished.sub.id).nextChargeAt)
        assertEquals(SubscriptionStatus.CANCELLED, subscription(cancelled.sub.id).status, "step C ends it at the period end")
        assertEquals("BUYER_CANCEL", subscription(cancelled.sub.id).endReason)
        assertEquals(SubscriptionStatus.COMPLETED, subscription(finished.sub.id).status, "the plan is finished")
        assertEquals(SubscriptionStatus.CANCELLED, subscription(testing.sub.id).status, "a test subscription is never charged for real")
        assertEquals("ADMIN_CANCEL", subscription(testing.sub.id).endReason)
    }

    @Test
    fun `a live subscription is charged in its own environment while the store is in test mode, a test subscription in test mode`(): Unit = runBlocking {
        val live = activeMerchant(name = "Liv")

        chargeSucceeds()
        rw.storeTestMode = true
        w.clock.set(live.sub.nextChargeAt!!)
        rw.job.runOnce()

        assertEquals(listOf(false), fake.chargeTestModes, "the context of the call is the environment of the subscription, not the store's current one")
        assertFalse(attempts(renewalOrderOf(live.sub.id).id).single().testMode)
        assertEquals(2, subscription(live.sub.id).cycleCount, "the live renewal completes")

        // the other way round: a test subscription is charged in test mode while the store is still in it
        rw.storeTestMode = false

        val testing = activeMerchant(name = "Tom")

        sql("UPDATE `pano_market_subscription` SET `testMode` = 1 WHERE `id` = ?", testing.sub.id)
        rw.storeTestMode = true
        w.clock.set(testing.sub.nextChargeAt!!)
        rw.job.runOnce()

        // Liv's second period falls due at the same moment (live), then Tom's first renewal (test)
        assertEquals(listOf(false, false, true), fake.chargeTestModes)
        assertTrue(attempts(renewalOrderOf(testing.sub.id).id).single().testMode)
        assertEquals(2, subscription(testing.sub.id).cycleCount)
    }

    @Test
    fun `a live subscription is not charged through a provider whose keys are test keys, it waits like for an unavailable provider`(): Unit = runBlocking {
        val a = activeMerchant()

        chargeSucceeds()
        sw.caps(RecurringSupport.MERCHANT_INITIATED) {
            testMode = TestModeSupport.DERIVED
            derivedTestMode = true
        }
        w.clock.set(a.sub.nextChargeAt!!)
        rw.job.runOnce()

        val waiting = subscription(a.sub.id)

        assertEquals(0, charges().size, "a sandbox never sees a live token")
        assertEquals(SubscriptionStatus.ACTIVE, waiting.status)
        assertEquals(0, waiting.failCount, "not a failure")
        assertEquals(w.clock.now() + 60 * 60_000, waiting.nextChargeAt)
        assertEquals(0, mails("SUBSCRIPTION_PAYMENT_FAILED"))

        // the keys are live keys again an hour later: the charge goes out, live
        sw.caps(RecurringSupport.MERCHANT_INITIATED) {
            testMode = TestModeSupport.DERIVED
            derivedTestMode = false
        }
        w.clock.advance(60 * 60_000)
        rw.job.runOnce()

        assertEquals(listOf(false), fake.chargeTestModes)
        assertEquals(2, subscription(a.sub.id).cycleCount)
    }

    @Test
    fun `42 a blocked buyer is not charged, an active row is cancelled at the period end and a past due row ends at once`(): Unit = runBlocking {
        val pastDue = activeMerchant(name = "Cal")
        val t0 = pastDue.sub.nextChargeAt!!

        // Cal's card is declined first: PAST_DUE with the retry a day later
        declined()
        w.clock.set(t0)
        rw.job.runOnce()
        assertEquals(SubscriptionStatus.PAST_DUE, subscription(pastDue.sub.id).status)
        assertEquals(1, charges().size)

        // Bea's charge is due while her period still has time left
        val active = activeMerchant(name = "Bea")

        sql("UPDATE `pano_market_subscription` SET `nextChargeAt` = ? WHERE `id` = ?", w.clock.now(), active.sub.id)

        val blocked = setOf(pastDue.user.id, active.user.id)

        rw.blocks = BuyerBlocks { _, _, _, _, userId, _ -> userId != null && userId in blocked }
        rw.build()
        chargeSucceeds()
        w.clock.set(t0 + day)
        rw.job.runOnce()

        assertEquals(1, charges().size, "nobody on the block list is charged, the declined call of before is the only one")

        val ended = subscription(pastDue.sub.id)

        assertEquals(SubscriptionStatus.CANCELLED, ended.status, "S7: no paid time is left")
        assertEquals("ADMIN_CANCEL", ended.endReason)
        assertNull(ended.storedMethod)
        assertNull(ended.nextChargeAt)

        val scheduled = subscription(active.sub.id)

        assertEquals(SubscriptionStatus.ACTIVE, scheduled.status, "S8: the paid period runs out")
        assertTrue(scheduled.cancelAtPeriodEnd)
        assertEquals("ADMIN_CANCEL", scheduled.endReason)
        assertNull(scheduled.nextChargeAt)
        assertEquals(1, hooks("subscription.cancelled").count { it.key.startsWith("sub:${active.sub.id}:cancelled") }, "S8 fires the cancelled webhook once")
        assertEquals(1, hooks("subscription.cancelled").count { it.key.startsWith("sub:${pastDue.sub.id}:cancelled") }, "S7 fires it too, the buyer did not cancel before")
    }

    @Test
    fun `65 after downtime a step takes 50 rows, the oldest due first, and the rest on the next tick`(): Unit = runBlocking {
        val rows = (1..51).map { n ->
            w.clock.advance(60_000)
            activeMerchant(name = "User$n")
        }

        chargeSucceeds()
        w.clock.set(rows.last().sub.nextChargeAt!!)
        rw.job.runOnce()

        assertEquals(50, charges().size, "50 per step")
        assertEquals(rows.take(50).map { it.sub.id }.toSet(), charges().map { it.subscription.id }.toSet(), "the 50 whose charge was due first")
        assertEquals(1, subscription(rows.last().sub.id).cycleCount, "the 51st waits for the next tick")
        assertEquals(50, rows.take(50).count { subscription(it.sub.id).cycleCount == 2 })

        rw.job.runOnce()

        assertEquals(51, charges().size)
        assertEquals(2, subscription(rows.last().sub.id).cycleCount)
    }

    @Test
    fun `downtime of three periods charges once for the grid period that contains now`(): Unit = runBlocking {
        val a = activeMerchant()

        chargeSucceeds()
        w.clock.set(oneMonthAfter(oneMonthAfter(oneMonthAfter(a.sub.currentPeriodEnd!!))) + 3_600_000)
        rw.job.runOnce()

        val row = subscription(a.sub.id)
        val renewal = renewals(a.sub.id).single()

        assertEquals(1, charges().size)
        assertEquals(2, row.cycleCount, "one period is booked, not three")
        assertEquals(oneMonthAfter(oneMonthAfter(oneMonthAfter(a.sub.currentPeriodEnd!!))), renewal.periodStart, "the grid period that contains now")
        assertTrue(row.currentPeriodEnd!! > w.clock.now(), "the new period contains now")
    }

    @Test
    fun `two job runs on the same due row make one attempt`(): Unit = runBlocking {
        val a = activeMerchant()

        chargeSucceeds()
        w.clock.set(a.sub.nextChargeAt!!)

        coroutineScope { listOf(async { rw.job.chargeOne(a.sub.id) }, async { rw.job.chargeOne(a.sub.id) }).awaitAll() }

        assertEquals(1, charges().size)
        assertEquals(1, count("market_payment", "`subscriptionId` = ${a.sub.id}"))
        assertEquals(2, subscription(a.sub.id).cycleCount)
    }

    @Test
    fun `66 a row that fails inside its transaction is skipped, the next row of the step is still handled`(): Unit = runBlocking {
        val first = activeMerchant(name = "Ann")
        val second = activeMerchant(name = "Ben")

        // the failure is not a provider answer: the block check of the first row throws inside its transaction (tx1), which the call's own handling never sees
        rw.blocks = BuyerBlocks { _, _, _, _, userId, _ -> if (userId == first.user.id) throw IllegalStateException("boom") else false }
        rw.build()
        chargeSucceeds()
        w.clock.set(maxOf(first.sub.nextChargeAt!!, second.sub.nextChargeAt!!))
        rw.job.runOnce()

        assertEquals(1, charges().size, "only the second row reached the provider")
        assertEquals(1, subscription(first.sub.id).cycleCount)
        assertEquals(0, count("market_payment", "`subscriptionId` = ${first.sub.id}"), "the failed row wrote nothing: its transaction rolled back")
        assertEquals(2, subscription(second.sub.id).cycleCount)

        // the failed row is still due: once the cause is gone the next tick charges it
        rw.blocks = BuyerBlocks.NONE
        rw.build()
        rw.job.runOnce()

        assertEquals(2, charges().size)
        assertEquals(2, subscription(first.sub.id).cycleCount)
    }

    @Test
    fun `a charge call that blows up with an unexpected error leaves the outcome unknown and does not stop the batch`(): Unit = runBlocking {
        val first = activeMerchant(name = "Ann")
        val second = activeMerchant(name = "Ben")

        // the first row's call blows up in a way that is not a provider error; the second is charged
        var calls = 0

        fake.onChargeRecurring = { request ->
            if (++calls == 1) throw IllegalStateException("boom")

            RecurringChargeResult(listOf(PaymentEvent.Succeeded(PaymentTarget.Attempt(request.attempt.id), request.amount)))
        }
        w.clock.set(maxOf(first.sub.nextChargeAt!!, second.sub.nextChargeAt!!))
        rw.job.runOnce()

        assertEquals(2, charges().size)
        assertEquals(1, listOf(first, second).count { subscription(it.sub.id).cycleCount == 2 }, "one renewed, the other waits for its lease")
    }

    // ==================================================================================== B: manual, notice

    @Test
    fun `a manual renewal that nobody pays is past due at the period end as NOT_RENEWED, expires at the grace end and the order is cancelled`(): Unit = runBlocking {
        val a = activeManual()
        val end = a.sub.currentPeriodEnd!!

        w.clock.set(end - 3 * day + 1)
        rw.job.runOnce()

        val renewalOrder = renewalOrderOf(a.sub.id)

        assertEquals(OrderStatus.PENDING, renewalOrder.status)

        // the period ends unpaid
        w.clock.set(end)
        rw.job.runOnce()

        val past = subscription(a.sub.id)

        assertEquals(SubscriptionStatus.PAST_DUE, past.status)
        assertEquals(end + 3 * day, past.graceEndsAt)
        assertEquals(1, mails("SUBSCRIPTION_PAYMENT_FAILED"))

        val failed = sql("SELECT `params` FROM `pano_market_mail_outbox` WHERE `kind` = 'SUBSCRIPTION_PAYMENT_FAILED'").single()

        assertEquals("NOT_RENEWED", JsonObject(failed.getString("params")).getString("reason"))
        assertNotNull(JsonObject(failed.getString("params")).getString("payUrl"), "the pay link of the renewal order")

        // the grace ends: expired, the entitlement ended, the renewal order cancelled
        w.clock.set(end + 3 * day)
        rw.job.runOnce()

        val expired = subscription(a.sub.id)

        assertEquals(SubscriptionStatus.EXPIRED, expired.status)
        assertEquals("PAYMENT_FAILED", expired.endReason)
        assertEquals(EntitlementStatus.EXPIRED, w.entitlements.getById(expired.entitlementId!!, pool)!!.status)
        assertEquals(OrderStatus.CANCELLED, order(renewalOrder.id).status)
        assertEquals(1, hooks("subscription.expired").size)
    }

    @Test
    fun `a merchant subscription gets the upcoming-charge notice once per period and no renewal order`(): Unit = runBlocking {
        val a = activeMerchant()

        w.clock.set(a.sub.currentPeriodEnd!! - 3 * day + 1)
        rw.job.runOnce()
        rw.job.runOnce()

        assertEquals(1, mails("SUBSCRIPTION_REMINDER"))
        assertEquals(0, renewals(a.sub.id).size, "no renewal row, no order")
        assertFalse(sql("SELECT `params` FROM `pano_market_mail_outbox` WHERE `kind` = 'SUBSCRIPTION_REMINDER'").single().getString("params").contains("payUrl"), "no pay link")
        assertNotNull(subscription(a.sub.id).reminderSentAt)

        // the period is charged and renewed: the next period gets its notice again
        chargeSucceeds()
        w.clock.set(a.sub.nextChargeAt!!)
        rw.job.runOnce()

        assertNull(subscription(a.sub.id).reminderSentAt)

        w.clock.set(subscription(a.sub.id).currentPeriodEnd!! - 3 * day + 1)
        rw.job.runOnce()

        assertEquals(2, mails("SUBSCRIPTION_REMINDER"))
    }

    @Test
    fun `46 with the reminder switched off the manual renewal order is still prepared, without a mail`(): Unit = runBlocking {
        rw.subscriptionReminderDays = 0

        val a = activeManual()
        val end = a.sub.currentPeriodEnd!!

        // the lead is one day at least (09 section 8.6): not before
        w.clock.set(end - day - 1)
        rw.job.runOnce()
        assertEquals(0, renewals(a.sub.id).size)

        w.clock.set(end - day)
        rw.job.runOnce()
        rw.job.runOnce()

        val renewal = renewals(a.sub.id).single()
        val renewalOrder = order(renewal.orderId!!)

        assertEquals(OrderStatus.PENDING, renewalOrder.status)
        assertEquals(end + 3 * day, renewalOrder.expiresAt)
        assertEquals(0, attempts(renewalOrder.id).size)
        assertEquals(0, mails("SUBSCRIPTION_REMINDER"), "subscriptionReminderDays = 0: no mail")
        assertNotNull(subscription(a.sub.id).reminderSentAt, "the period is prepared once")
    }

    @Test
    fun `49 a bank transfer that was notified inside the grace holds the expiry back, the approval makes the subscription active`(): Unit = runBlocking {
        val a = activeManual()
        val end = a.sub.currentPeriodEnd!!

        w.clock.set(end - 3 * day + 1)
        rw.job.runOnce()

        val renewalOrder = renewalOrderOf(a.sub.id)

        // unpaid at the period end: past due, the grace runs
        w.clock.set(end)
        rw.job.runOnce()

        val past = subscription(a.sub.id)

        assertEquals(SubscriptionStatus.PAST_DUE, past.status)

        // inside the grace the buyer starts a bank transfer and tells the shop that the money is on its way
        fx.paymentMethod("bank-transfer", settings = bankSettings())
        w.clock.set(end + day)
        rw.payments.pay(renewalOrder, PayRequest("bank-transfer", null, null), PayCaller(), pool)
        assertTrue(rw.bank.notify(renewalOrder.id, "Alex", null, a.user.id))
        assertEquals(PaymentStatus.PROCESSING, attempts(renewalOrder.id).last().status)

        // the grace ends while the transfer is on its way: the expiry waits (at most seven more days)
        w.clock.set(past.graceEndsAt!! + 60_000)
        rw.job.runOnce()
        assertEquals(SubscriptionStatus.PAST_DUE, subscription(a.sub.id).status, "an attempt in PROCESSING holds the expiry back")

        // the admin approves the transfer: paid inside the extended window, the period starts where the old one ended
        rw.bank.decide(renewalOrder.id, BankTransferDecision.APPROVE, null, 77L)

        val row = subscription(a.sub.id)

        assertEquals(SubscriptionStatus.ACTIVE, row.status)
        assertEquals(2, row.cycleCount)
        assertEquals(end, row.currentPeriodStart, "paid in the grace: no gap and no overlap")
        assertEquals(oneMonthAfter(end), row.currentPeriodEnd)
        assertEquals(0, row.failCount)
        assertNull(row.graceEndsAt)
        assertEquals(OrderStatus.COMPLETED, order(renewalOrder.id).status)
        assertEquals(RenewalStatus.PAID, renewals(a.sub.id).single().status)
        assertEquals("bank-transfer", row.providerId)
        assertEquals(SubscriptionMode.MANUAL, row.mode)

        // an unpaid transfer would have expired at the grace end plus seven days: not now
        w.clock.set(past.graceEndsAt!! + 7 * day + 1)
        rw.job.runOnce()
        assertEquals(SubscriptionStatus.ACTIVE, subscription(a.sub.id).status)
    }

    // ==================================================================================== C and E: gateway rows

    @Test
    fun `67 a gateway row 23 hours after the period end is left alone, after 25 hours the gateway is asked and the row is then past due`(): Unit = runBlocking {
        val product = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alex")
        val first = buy(product, caller)
        val end = w.clock.now() + 30 * day

        succeed(first, subscription = gatewayState("sub_gw", periodStart = w.clock.now(), periodEnd = end))

        val id = first.subscriptionId!!

        // the poll at the period end plus an hour is due too: the fake does not support subscription queries, which step E notes (no further poll is scheduled)
        w.clock.set(end + 23 * 3_600_000L)
        rw.job.runOnce()
        assertEquals(SubscriptionStatus.ACTIVE, subscription(id).status, "inside the renewal slack")

        val before = polls()

        assertEquals(1, before, "step E asked once and learned that the gateway cannot be queried")

        w.clock.set(end + 25 * 3_600_000L)
        rw.job.runOnce()

        assertEquals(before + 1, polls(), "step C asks the gateway first, once, before it judges the row")

        val past = subscription(id)

        assertEquals(SubscriptionStatus.PAST_DUE, past.status)
        assertEquals(end + 25 * 3_600_000L + 3 * day, past.graceEndsAt)
        assertEquals(1, mails("SUBSCRIPTION_PAYMENT_FAILED"))

        // the grace ends: the gateway is told to stop billing (the remote cancel queue)
        w.clock.set(past.graceEndsAt!!)
        rw.job.runOnce()

        val expired = subscription(id)

        assertEquals(SubscriptionStatus.EXPIRED, expired.status)
        assertEquals(RemoteCancelState.DONE, expired.remoteCancelState, "the queue ran in the same tick")
        assertEquals(1, fake.calls(FakePaymentProvider.Op.CANCEL_SUBSCRIPTION).size)
    }

    private suspend fun gatewayRow(name: String = "Alex", subscriptionId: String = "sub_gw"): Triple<Long, Long, com.panomc.plugins.market.db.model.MarketSubscription> {
        val product = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user(name)
        val first = buy(product, caller)
        val end = w.clock.now() + 30 * day

        succeed(first, subscription = gatewayState(subscriptionId, periodStart = w.clock.now(), periodEnd = end))

        return Triple(first.id, end, subscription(first.subscriptionId!!))
    }

    @Test
    fun `67 a gateway row whose renewal the poll finds stays active, no failure and no mail`(): Unit = runBlocking {
        val (_, end, row) = gatewayRow()

        fake.onQuerySubscription = {
            SubscriptionQueryResult(
                listOf(
                    PaymentEvent.SubscriptionRenewed("sub_gw", Money(row.price, row.currency)).also { event ->
                        event.gatewayTransactionId = "txn_poll"
                        event.periodStart = end
                        event.periodEnd = end + 30 * day
                    }
                )
            )
        }

        // 25 hours after the period end the webhook never came: the poll finds the renewal before the row is judged
        w.clock.set(end + 25 * 3_600_000L)
        rw.job.runOnce()

        val renewed = subscription(row.id)

        assertTrue(polls() >= 1, "the gateway was asked")
        assertEquals(SubscriptionStatus.ACTIVE, renewed.status)
        assertEquals(2, renewed.cycleCount)
        assertEquals(end, renewed.currentPeriodStart)
        assertEquals(end + 30 * day, renewed.currentPeriodEnd)
        assertEquals(0, renewed.failCount)
        assertNull(renewed.graceEndsAt)
        assertEquals(0, mails("SUBSCRIPTION_PAYMENT_FAILED"))
        assertEquals(1, renewals(row.id).count { it.status == RenewalStatus.PAID })
        assertEquals(1, hooks("subscription.renewed").size)
    }

    @Test
    fun `67 a gateway row whose provider is not there is left untouched, and judged once the provider is back`(): Unit = runBlocking {
        val (_, end, row) = gatewayRow()

        sw.lookup.remove("fake")
        w.clock.set(end + 25 * 3_600_000L)
        rw.job.runOnce()
        rw.job.runOnce()

        val untouched = subscription(row.id)

        assertEquals(SubscriptionStatus.ACTIVE, untouched.status, "an unavailable provider is never a reason to fail a renewal")
        assertEquals(0, untouched.failCount)
        assertNull(untouched.graceEndsAt)
        assertEquals(0, mails("SUBSCRIPTION_PAYMENT_FAILED"))
        assertEquals(0, polls(), "nobody to ask")

        sw.lookup.add(sw.first)
        w.clock.advance(60 * 60_000)
        rw.job.runOnce()

        assertTrue(polls() >= 1, "the provider is back: the gateway is asked first")
        assertEquals(SubscriptionStatus.PAST_DUE, subscription(row.id).status)
        assertEquals(1, mails("SUBSCRIPTION_PAYMENT_FAILED"))
    }

    @Test
    fun `69 a renewal webhook and the grace end racing on one row end in exactly one of renewed and expired, never in both deliveries`(): Unit = runBlocking {
        val outcomes = mutableListOf<SubscriptionStatus>()

        repeat(4) { n ->
            val (firstOrderId, end, row) = gatewayRow(name = "Racer$n", subscriptionId = "sub_race$n")

            // past due after the renewal slack, the grace runs out now
            w.clock.set(end + 25 * 3_600_000L)
            rw.job.runOnce()

            val past = subscription(row.id)

            assertEquals(SubscriptionStatus.PAST_DUE, past.status, "round $n")

            w.clock.set(past.graceEndsAt!!)

            val renewed = PaymentEvent.SubscriptionRenewed("sub_race$n", Money(past.price, past.currency)).also {
                it.gatewayTransactionId = "txn_race$n"
                it.periodStart = end
                it.periodEnd = end + 30 * day
            }
            val ctx = context()

            coroutineScope { listOf(async { rw.sink.apply(renewed, null, ctx) }, async { rw.job.runOnce() }).awaitAll() }

            val after = subscription(row.id)
            val renewDeliveries = renewals(row.id).mapNotNull { it.orderId }.sumOf { orderId -> sw.dw.rows(orderId).count { it.phase == DeliveryPhase.RENEW } }
            val expireDeliveries = sw.dw.rows(firstOrderId).count { it.phase == DeliveryPhase.EXPIRE }

            outcomes += after.status

            when (after.status) {
                SubscriptionStatus.ACTIVE -> {
                    assertEquals(2, after.cycleCount, "round $n: renewed")
                    assertTrue(renewDeliveries > 0, "round $n: the renewal's deliveries exist")
                    assertEquals(0, expireDeliveries, "round $n: and nothing expired")
                    assertNull(after.graceEndsAt)
                }

                SubscriptionStatus.EXPIRED -> {
                    assertEquals(1, after.cycleCount, "round $n: expired, the period was not extended")
                    assertEquals(0, renewDeliveries, "round $n: no RENEW delivery for an ended subscription")
                    assertTrue(expireDeliveries > 0, "round $n: the end actions were planned")
                    assertEquals(0, renewals(row.id).count { it.status == RenewalStatus.PAID })
                    // the money that came for an ended subscription waits in review, never completes a renewal
                    renewals(row.id).mapNotNull { it.orderId }.forEach { assertEquals(OrderStatus.REVIEW, order(it).status, "round $n") }
                }

                else -> throw AssertionError("round $n: neither renewed nor expired but ${after.status}")
            }
        }

        assertEquals(4, outcomes.size)
    }

    @Test
    fun `the remote cancel queue backs off after a failure, gives up after 20 failures and a success is recorded once`(): Unit = runBlocking {
        val product = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alex")
        val first = buy(product, caller)

        succeed(first, subscription = gatewayState("sub_gw", periodStart = w.clock.now(), periodEnd = w.clock.now() + 30 * day))

        val id = first.subscriptionId!!

        // the subscription ended locally; the gateway has to be told
        sql("UPDATE `pano_market_subscription` SET `status` = 'CANCELLED', `endedAt` = ?, `endReason` = 'ADMIN_CANCEL', `remoteCancelState` = 'PENDING', `remoteCancelAttempts` = 0, `nextQueryAt` = ? WHERE `id` = ?", w.clock.now(), w.clock.now(), id)
        fake.onCancelSubscription = { CancelSubscriptionResult.Failed("gateway busy") }
        rw.job.runOnce()

        var row = subscription(id)

        assertEquals(RemoteCancelState.PENDING, row.remoteCancelState)
        assertEquals(1, row.remoteCancelAttempts)
        assertEquals(w.clock.now() + 30_000, row.nextQueryAt, "30 seconds after the first failure")

        // nothing before the retry is due
        rw.job.runOnce()
        assertEquals(1, fake.calls(FakePaymentProvider.Op.CANCEL_SUBSCRIPTION).size)

        w.clock.advance(30_000)
        rw.job.runOnce()
        row = subscription(id)

        assertEquals(2, row.remoteCancelAttempts)
        assertEquals(w.clock.now() + 60_000, row.nextQueryAt, "factor 2")

        // the remaining failures up to the 20th
        repeat(18) {
            w.clock.set(subscription(id).nextQueryAt!!)
            rw.job.runOnce()
        }

        row = subscription(id)

        assertEquals(RemoteCancelState.FAILED, row.remoteCancelState)
        assertEquals(20, row.remoteCancelAttempts)
        assertNull(row.nextQueryAt)
        assertEquals(20, fake.calls(FakePaymentProvider.Op.CANCEL_SUBSCRIPTION).size)
        assertEquals(1, events(first.id, OrderEventType.SUBSCRIPTION_REMOTE_CANCEL_FAILED).size)
    }

    @Test
    fun `a successful remote cancel is done and the queue is empty`(): Unit = runBlocking {
        val product = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alex")
        val first = buy(product, caller)

        succeed(first, subscription = gatewayState("sub_gw", periodStart = w.clock.now(), periodEnd = w.clock.now() + 30 * day))

        val id = first.subscriptionId!!

        sql("UPDATE `pano_market_subscription` SET `status` = 'CANCELLED', `endedAt` = ?, `endReason` = 'ADMIN_CANCEL', `remoteCancelState` = 'PENDING', `nextQueryAt` = ? WHERE `id` = ?", w.clock.now(), w.clock.now(), id)
        rw.job.runOnce()
        rw.job.runOnce()

        val row = subscription(id)

        assertEquals(RemoteCancelState.DONE, row.remoteCancelState)
        assertNull(row.nextQueryAt)
        assertEquals(1, fake.calls(FakePaymentProvider.Op.CANCEL_SUBSCRIPTION).size)
    }

    // ==================================================================================== F: pending cleanup

    @Test
    fun `a pending row is closed 30 days after its order was released and not before`(): Unit = runBlocking {
        val product = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED)

        val (_, caller) = user("Alex")
        val pending = buy(product, caller)

        expire(order(pending.id))
        assertEquals(OrderStatus.EXPIRED, order(pending.id).status)

        // the row survives the expiry of its order (a late payment may still come)
        assertEquals(SubscriptionStatus.PENDING, subscription(pending.subscriptionId!!).status)

        val released = order(pending.id).updatedAt

        // the step judges to the millisecond: one before the 30 days it leaves the row alone
        w.clock.set(released + 30 * day - 1)

        val early = sw.db.txRestartingOnOrderChange { conn -> rw.subs.closePendingAfterTimeout(conn, pending.subscriptionId!!) }

        assertTrue(early is com.panomc.plugins.market.service.SubscriptionService.StepOutcome.Idle)
        assertEquals(SubscriptionStatus.PENDING, subscription(pending.subscriptionId!!).status)

        w.clock.set(released + 30 * day)
        rw.job.runOnce()

        val closed = subscription(pending.subscriptionId!!)

        assertEquals(SubscriptionStatus.CANCELLED, closed.status)
        assertEquals("PAYMENT_FAILED", closed.endReason)
        assertEquals(0, mails("SUBSCRIPTION_ENDED"), "S3 sends nothing")
        assertEquals(0, hooks("subscription.expired").size)
    }
}
