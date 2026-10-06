package com.panomc.plugins.market.service

import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.core.subscription.CancelActor
import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.db.model.EntitlementStatus
import com.panomc.plugins.market.db.model.MarketDispute
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.model.MarketRefund
import com.panomc.plugins.market.db.model.OrderActorType
import com.panomc.plugins.market.db.model.OrderEventType
import com.panomc.plugins.market.db.model.RemoteCancelState
import com.panomc.plugins.market.db.model.RenewalStatus
import com.panomc.plugins.market.db.model.SubscriptionMode
import com.panomc.plugins.market.db.model.SubscriptionStatus
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.error.PaymentProviderError
import com.panomc.plugins.market.error.SubscriptionNotCancellable
import com.panomc.plugins.market.error.SubscriptionNotManageable
import com.panomc.plugins.market.error.SubscriptionNotResumable
import com.panomc.plugins.market.error.SubscriptionNotRetryable
import com.panomc.plugins.market.job.SubscriptionJob
import com.panomc.plugins.market.routes.api.payment.InboundEventContext
import com.panomc.plugins.market.routes.panel.subscription.parseStatuses
import com.panomc.plugins.market.routes.user.subscription.SubscriptionFilter
import com.panomc.plugins.market.routes.user.subscription.SubscriptionViews
import com.panomc.plugins.market.spi.common.Money
import com.panomc.plugins.market.spi.common.ProviderErrorCode
import com.panomc.plugins.market.spi.common.ProviderException
import com.panomc.plugins.market.spi.payment.CancelSubscriptionRequest
import com.panomc.plugins.market.spi.payment.CancelSubscriptionResult
import com.panomc.plugins.market.spi.payment.GatewaySubscriptionStatus
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PortalPurpose
import com.panomc.plugins.market.spi.payment.RecurringChargeRequest
import com.panomc.plugins.market.spi.payment.RecurringSupport
import com.panomc.plugins.market.spi.payment.ResumeSubscriptionResult
import com.panomc.plugins.market.spi.payment.SubscriptionPortalResult
import com.panomc.plugins.market.spi.payment.SubscriptionQueryResult
import com.panomc.plugins.market.support.FakePaymentProvider
import com.panomc.plugins.market.util.OrderStatus
import com.panomc.plugins.market.util.Paging
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Cancel, resume, the remote-cancel queue, the portal, the endings and the buyer / panel views of subscriptions on a real MariaDB (MK-123; 09 sections 9.3, 10 and 13;
 * S-03 and the tests 52 to 62, 70 to 72, 78 to 80 and 82 of 09 section 16): a cancel at the period end keeps the subscription `ACTIVE` until `currentPeriodEnd`, then
 * the `EXPIRE` rows are written once (S-03); a gateway that fails, only allows `LocalOnly` or sends the buyer away changes nothing; an unavailable provider gives the
 * local change and the queue; a cancel that crashed between its two transactions is repeated by step E; the endings by refund, chargeback, user deletion and block;
 * the admin retry (only `MERCHANT`, or `GATEWAY` with `recurringRetry`). The invariants I1 to I22 are checked after every test by the base class.
 */
internal class SubscriptionCancelIT : RenewalITBase() {
    private lateinit var actions: SubscriptionActions
    private lateinit var job: SubscriptionJob

    @BeforeEach
    fun wireActions() = buildActions()

    /** The use cases and a job that carries them (step E), on the services of the current [rw] (call again after `rw.build()`). */
    private fun buildActions() {
        actions = SubscriptionActions(w.clock, sw.db, rw.subs, w.subscriptions, rw.payments, { w.pool }, rw.sink, { "https://shop.example/profile/subscriptions" })
        job = SubscriptionJob(w.clock, sw.db, rw.subs, w.subscriptions, rw.payments, { sw.h.config.toConfig() }, { w.pool }, rw.sink, actions = actions)
    }

    private val views: SubscriptionViews get() = SubscriptionViews(w.clock, w.subscriptions, w.subscriptionRenewals) { id, sql -> rw.payments.capabilitiesOf(id, sql) }

    private suspend inline fun <reified T : Throwable> failsWith(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e

            throw e
        }

        throw AssertionError("expected ${T::class.simpleName}, nothing was thrown")
    }

    private fun declined() {
        fake.onChargeRecurring = { throw ProviderException(ProviderErrorCode.GATEWAY_REJECTED, "card declined", "402") }
    }

    private fun context() = InboundEventContext(1, "fake", null, null, w.clock.now())

    private fun remoteCancels() = fake.calls(FakePaymentProvider.Op.CANCEL_SUBSCRIPTION).map { it.request as CancelSubscriptionRequest }

    private suspend fun cancelBuyer(a: Active, atPeriodEnd: Boolean = true) = actions.cancel(a.sub.id, CancelActor.BUYER, a.user.id, atPeriodEnd) as CancelOutcome.Done

    private suspend fun ended(id: Long) = subscription(id).also { assertTrue(it.status == SubscriptionStatus.CANCELLED || it.status == SubscriptionStatus.EXPIRED) }

    private fun expireRows(orderId: Long) = runBlocking { sw.dw.rows(orderId).count { it.phase == DeliveryPhase.EXPIRE } }

    /** A gateway-managed subscription that is `ACTIVE`, the provider can resume, show a portal and retry. */
    private suspend fun gateway(name: String = "Alex", gatewayId: String = "sub_gw", resume: Boolean = true, portal: Boolean = true, retry: Boolean = false): Pair<Active, Long> {
        val product = subProduct()

        fx.paymentMethod("fake")
        sw.caps(RecurringSupport.GATEWAY_MANAGED) {
            recurringResume = resume
            recurringPortal = portal
            recurringRetry = retry
        }

        val (user, caller) = user(name)
        val first = buy(product, caller)
        val end = w.clock.now() + 30 * day

        succeed(first, subscription = gatewayState(gatewayId, periodStart = w.clock.now(), periodEnd = end))

        return Active(user, caller, product, order(first.id), subscription(first.subscriptionId!!)) to end
    }

    /** The `MERCHANT` subscription that went `PAST_DUE` by a declined charge. */
    private suspend fun pastDueMerchant(): Active {
        val a = activeMerchant()

        declined()
        w.clock.set(a.sub.nextChargeAt!!)
        job.runOnce()

        assertEquals(SubscriptionStatus.PAST_DUE, subscription(a.sub.id).status)

        return a
    }

    // ==================================================================================== S-03: cancel at the period end

    @Test
    fun `S-03 a cancel at the period end keeps the subscription ACTIVE until currentPeriodEnd, then it is CANCELLED with EXPIRE rows once and is never charged`(): Unit = runBlocking {
        val a = activeMerchant()
        val end = a.sub.currentPeriodEnd!!
        val entitlementId = a.sub.entitlementId!!

        chargeSucceeds()

        val answer = cancelBuyer(a)
        val row = answer.row

        assertEquals(SubscriptionStatus.ACTIVE, row.status)
        assertTrue(row.cancelAtPeriodEnd)
        assertEquals(w.clock.now(), row.cancelRequestedAt)
        assertEquals("BUYER_CANCEL", row.endReason, "the provisional reason")
        assertNull(row.nextChargeAt, "no further charge")
        assertEquals(1, mails("SUBSCRIPTION_CANCELLED"))
        assertEquals(1, hooks("subscription.cancelled").size)
        assertEquals(0, hooks("subscription.expired").size)
        assertEquals(0, mails("SUBSCRIPTION_ENDED"))

        val timeline = events(a.order.id, OrderEventType.SUBSCRIPTION_CANCEL_REQUESTED).single()

        assertEquals(OrderActorType.BUYER, timeline.actorType)
        assertTrue(timeline.data!!.contains("\"atPeriodEnd\":true"))

        // 80: a MERCHANT cancel calls cancelSubscription once, after the commit, with the stored method, and nothing depends on the answer
        val remote = remoteCancels().single()

        assertEquals("tok_1", remote.storedMethod!!.token)
        assertTrue(remote.atPeriodEnd)

        // before the end nothing happens, the row keeps its access
        w.clock.set(end - 1)
        job.runOnce()
        assertEquals(SubscriptionStatus.ACTIVE, subscription(a.sub.id).status)
        assertEquals(EntitlementStatus.ACTIVE, w.entitlements.getById(entitlementId, pool)!!.status)

        // at the end: S9, the entitlement, the EXPIRE rows once, the mail, the webhook, no charge
        w.clock.set(end)
        job.runOnce()
        job.runOnce()

        val done = subscription(a.sub.id)

        assertEquals(SubscriptionStatus.CANCELLED, done.status)
        assertEquals("BUYER_CANCEL", done.endReason)
        assertNotNull(done.cancelledAt)
        assertNull(done.storedMethod)
        assertEquals(0, charges().size, "never charged")
        assertEquals(EntitlementStatus.EXPIRED, w.entitlements.getById(entitlementId, pool)!!.status)
        assertTrue(expireRows(a.order.id) > 0)
        assertEquals(1, mails("SUBSCRIPTION_ENDED"))
        assertEquals(1, hooks("subscription.expired").size)
        assertEquals(1, hooks("subscription.cancelled").size, "the cancel was announced once, at S8")

        val rowsAfter = expireRows(a.order.id)

        job.runOnce()
        assertEquals(rowsAfter, expireRows(a.order.id), "second end attempt creates none")
    }

    @Test
    fun `a cancel is idempotent, belongs to its owner, never touches a pending or closed row and a PAST_DUE row always ends now`(): Unit = runBlocking {
        val a = activeMerchant()

        cancelBuyer(a)
        cancelBuyer(a)

        assertEquals(1, mails("SUBSCRIPTION_CANCELLED"), "asking again changes nothing")
        assertEquals(1, hooks("subscription.cancelled").size)
        assertEquals(1, events(a.order.id, OrderEventType.SUBSCRIPTION_CANCEL_REQUESTED).size)
        assertEquals(1, remoteCancels().size, "the provider was not asked again")

        // another account: 404, and nothing happened
        val other = fx.user("Mallory")

        failsWith<NotFound> { actions.cancel(a.sub.id, CancelActor.BUYER, other.id, true) }
        failsWith<NotFound> { actions.cancel(999_999, CancelActor.BUYER, a.user.id, true) }

        // a subscription that is not paid yet cannot be cancelled
        val second = buy(subProduct(), a.caller)
        val pending = subscription(second.subscriptionId!!)

        assertEquals(SubscriptionStatus.PENDING, pending.status)
        failsWith<SubscriptionNotCancellable> { actions.cancel(pending.id, CancelActor.BUYER, a.user.id, true) }

        // a closed row: 409 for the buyer
        val closed = activeMerchant(name = "Blake")

        cancelBuyer(closed, atPeriodEnd = false)
        failsWith<SubscriptionNotCancellable> { actions.cancel(closed.sub.id, CancelActor.BUYER, closed.user.id, true) }
    }

    @Test
    fun `54 a cancel at the period end of a PAST_DUE subscription ends it now`(): Unit = runBlocking {
        val a = pastDueMerchant()
        val before = remoteCancels().size

        val done = actions.cancel(a.sub.id, CancelActor.BUYER, a.user.id, atPeriodEnd = true) as CancelOutcome.Done

        assertEquals(SubscriptionStatus.CANCELLED, done.row.status)
        assertEquals("BUYER_CANCEL", done.row.endReason)
        assertEquals(1, remoteCancels().size - before, "the stored instrument is deleted once")
        assertEquals(EntitlementStatus.EXPIRED, w.entitlements.getById(a.sub.entitlementId!!, pool)!!.status)
        assertEquals(1, hooks("subscription.expired").size)
    }

    @Test
    fun `53 an immediate cancel ends the subscription now, writes the EXPIRE rows once, mails, fires both webhooks and refunds nothing`(): Unit = runBlocking {
        val a = activeMerchant()

        val row = cancelBuyer(a, atPeriodEnd = false).row

        assertEquals(SubscriptionStatus.CANCELLED, row.status)
        assertEquals("BUYER_CANCEL", row.endReason)
        assertEquals(w.clock.now(), row.endedAt)
        assertEquals(w.clock.now(), row.cancelledAt)
        assertNull(row.storedMethod)
        assertNull(row.nextChargeAt)
        assertEquals(EntitlementStatus.EXPIRED, w.entitlements.getById(a.sub.entitlementId!!, pool)!!.status)
        assertTrue(expireRows(a.order.id) > 0)
        assertEquals(1, mails("SUBSCRIPTION_ENDED"))
        assertEquals(1, hooks("subscription.cancelled").size)
        assertEquals(1, hooks("subscription.expired").size)
        assertEquals(0, count("market_refund"), "nothing is refunded")
        assertEquals(1, events(a.order.id, OrderEventType.SUBSCRIPTION_ENDED).size)

        val rows = expireRows(a.order.id)

        failsWith<SubscriptionNotCancellable> { cancelBuyer(a, atPeriodEnd = false) }
        job.runOnce()
        assertEquals(rows, expireRows(a.order.id), "a second ending creates no rows")
        assertEquals(1, mails("SUBSCRIPTION_ENDED"))
    }

    @Test
    fun `an admin cancel keeps the reason in the order event, the end reason is ADMIN_CANCEL and a buyer cannot undo it`(): Unit = runBlocking {
        val a = activeMerchant()

        val done = actions.cancel(a.sub.id, CancelActor.ADMIN, null, true, "fraud suspected") as CancelOutcome.Done

        assertEquals("ADMIN_CANCEL", done.row.endReason)
        assertTrue(done.row.cancelAtPeriodEnd)

        val event = events(a.order.id, OrderEventType.SUBSCRIPTION_CANCEL_REQUESTED).single()

        assertEquals(OrderActorType.ADMIN, event.actorType)
        assertTrue(event.data!!.contains("fraud suspected"))

        // 56: an admin decision cannot be undone by the buyer
        failsWith<SubscriptionNotResumable> { actions.resume(a.sub.id, a.user.id) }
        assertTrue(subscription(a.sub.id).cancelAtPeriodEnd)
    }

    // ==================================================================================== resume

    @Test
    fun `S-03 resume before the period end clears the flag, schedules the charge again and the renewal goes through`(): Unit = runBlocking {
        val a = activeMerchant()
        val end = a.sub.currentPeriodEnd!!

        chargeSucceeds()
        cancelBuyer(a)

        val resumed = actions.resume(a.sub.id, a.user.id)

        assertEquals(SubscriptionStatus.ACTIVE, resumed.status)
        assertFalse(resumed.cancelAtPeriodEnd)
        assertNull(resumed.cancelRequestedAt)
        assertNull(resumed.endReason)
        assertEquals(end, resumed.nextChargeAt, "the charge is scheduled again at the period end")
        assertEquals(1, events(a.order.id, OrderEventType.SUBSCRIPTION_RESUMED).size)

        // nothing to resume any more
        failsWith<SubscriptionNotResumable> { actions.resume(a.sub.id, a.user.id) }

        w.clock.set(end)
        job.runOnce()

        assertEquals(1, charges().size)
        assertEquals(2, subscription(a.sub.id).cycleCount)
    }

    @Test
    fun `56 resume is refused after the period end, for another account and for a gateway that cannot resume or already cancelled`(): Unit = runBlocking {
        val a = activeMerchant()

        cancelBuyer(a)
        w.clock.set(a.sub.currentPeriodEnd!!)
        failsWith<SubscriptionNotResumable> { actions.resume(a.sub.id, a.user.id) }

        // another account's subscription is not even found
        val b = activeMerchant(name = "Blake")

        cancelBuyer(b)
        failsWith<NotFound> { actions.resume(b.sub.id, a.user.id) }

        // a gateway without recurringResume: no call, 409
        val (g, _) = gateway("Gwen", "sub_nores", resume = false)

        actions.cancel(g.sub.id, CancelActor.BUYER, g.user.id, true)
        failsWith<SubscriptionNotResumable> { actions.resume(g.sub.id, g.user.id) }
        assertEquals(0, sw.first.resumeCalls.size)

        // a gateway that confirmed the cancel (`Cancelled` = remoteCancelState DONE) cannot take it back
        sw.caps(RecurringSupport.GATEWAY_MANAGED) { recurringResume = true }

        val (h, _) = gateway("Hank", "sub_done")

        fake.onCancelSubscription = { CancelSubscriptionResult.Cancelled(null) }
        actions.cancel(h.sub.id, CancelActor.BUYER, h.user.id, true)
        assertEquals(RemoteCancelState.DONE, subscription(h.sub.id).remoteCancelState)
        failsWith<SubscriptionNotResumable> { actions.resume(h.sub.id, h.user.id) }
        assertEquals(0, sw.first.resumeCalls.size)
    }

    @Test
    fun `a gateway resume asks the provider between the two transactions, a failure changes nothing`(): Unit = runBlocking {
        val (g, _) = gateway()

        fake.onCancelSubscription = { CancelSubscriptionResult.Scheduled(w.clock.now() + 30 * day) }
        actions.cancel(g.sub.id, CancelActor.BUYER, g.user.id, true)

        val flagged = subscription(g.sub.id)

        assertTrue(flagged.cancelAtPeriodEnd)
        assertEquals(RemoteCancelState.NONE, flagged.remoteCancelState, "`Scheduled` is not a confirmed cancel: the gateway can take it back")

        sw.first.onResume = { ResumeSubscriptionResult.Failed("declined") }
        failsWith<PaymentProviderError> { actions.resume(g.sub.id, g.user.id) }
        assertTrue(subscription(g.sub.id).cancelAtPeriodEnd, "no change on a 502")

        sw.first.onResume = { ResumeSubscriptionResult.Resumed() }

        val resumed = actions.resume(g.sub.id, g.user.id)

        assertFalse(resumed.cancelAtPeriodEnd)
        assertNull(resumed.cancelRequestedAt)
        assertEquals(2, sw.first.resumeCalls.size)
    }

    @Test
    fun `cancelling a manual subscription skips the prepared renewal and cancels its order, resuming brings the renewal back`(): Unit = runBlocking {
        val a = activeManual()
        val end = a.sub.currentPeriodEnd!!

        // three days before the end the renewal order and the reminder exist
        w.clock.set(end - 3 * day)
        job.runOnce()

        val prepared = renewals(a.sub.id).single()
        val firstOrderId = prepared.orderId!!

        assertEquals(RenewalStatus.PENDING, prepared.status)
        assertEquals(OrderStatus.PENDING, order(firstOrderId).status)
        assertEquals(1, mails("SUBSCRIPTION_REMINDER"))

        cancelBuyer(a)

        assertEquals(RenewalStatus.SKIPPED, renewals(a.sub.id).single().status)
        assertEquals(OrderStatus.CANCELLED, order(firstOrderId).status, "the renewal order is cancelled (O7)")
        assertEquals(0, remoteCancels().size, "a manual subscription has nothing at a gateway")
        assertEquals(1, mails("SUBSCRIPTION_CANCELLED"))

        // the job prepares nothing for a cancelled subscription
        job.runOnce()
        assertEquals(1, renewals(a.sub.id).size)
        assertEquals(1, count("market_order", "`source` = 'RENEWAL'"))

        // resume: the row is PENDING again, without the cancelled order; step B prepares a new one and no second reminder is sent
        actions.resume(a.sub.id, a.user.id)

        val restored = renewals(a.sub.id).single()

        assertEquals(RenewalStatus.PENDING, restored.status)
        assertNull(restored.orderId)

        job.runOnce()

        val again = renewals(a.sub.id).single()

        assertNotNull(again.orderId)
        assertTrue(again.orderId != firstOrderId)
        assertEquals(OrderStatus.PENDING, order(again.orderId!!).status)
        assertEquals(1, mails("SUBSCRIPTION_REMINDER"), "one reminder per period")
    }

    // ==================================================================================== the gateway answers a cancel (52, 78, 79, 59)

    @Test
    fun `52 a gateway cancel at the period end - Scheduled sets the flag, Cancelled sets it and the remote state DONE, Failed is a 502 that changes nothing`(): Unit = runBlocking {
        val (scheduled, _) = gateway("Sam", "sub_s")

        fake.onCancelSubscription = { CancelSubscriptionResult.Scheduled(w.clock.now() + 30 * day) }

        val s = (actions.cancel(scheduled.sub.id, CancelActor.BUYER, scheduled.user.id, true) as CancelOutcome.Done).row

        assertTrue(s.cancelAtPeriodEnd)
        assertEquals(RemoteCancelState.NONE, s.remoteCancelState)
        assertEquals(SubscriptionStatus.ACTIVE, s.status)

        val (cancelled, _) = gateway("Cora", "sub_c")

        fake.onCancelSubscription = { CancelSubscriptionResult.Cancelled(null) }

        val c = (actions.cancel(cancelled.sub.id, CancelActor.BUYER, cancelled.user.id, true) as CancelOutcome.Done).row

        assertTrue(c.cancelAtPeriodEnd)
        assertEquals(RemoteCancelState.DONE, c.remoteCancelState)

        val (failed, _) = gateway("Finn", "sub_f")

        fake.onCancelSubscription = { CancelSubscriptionResult.Failed("gateway said no") }
        failsWith<PaymentProviderError> { actions.cancel(failed.sub.id, CancelActor.BUYER, failed.user.id, true) }

        val f = subscription(failed.sub.id)

        assertFalse(f.cancelAtPeriodEnd)
        assertNull(f.cancelRequestedAt, "the intent is withdrawn")
        assertNull(f.endReason)
        assertEquals(SubscriptionStatus.ACTIVE, f.status)
        assertEquals(0, events(failed.order.id, OrderEventType.SUBSCRIPTION_CANCEL_REQUESTED).size)

        // an exception of the provider is the same
        val (thrown, _) = gateway("Theo", "sub_t")

        fake.onCancelSubscription = { throw ProviderException(ProviderErrorCode.GATEWAY_UNREACHABLE, "down") }

        val error = failsWith<PaymentProviderError> { actions.cancel(thrown.sub.id, CancelActor.BUYER, thrown.user.id, true) }

        assertTrue(error.encode(mapOf()).contains("GATEWAY_UNREACHABLE"))
        assertNull(subscription(thrown.sub.id).cancelRequestedAt)
    }

    @Test
    fun `a failed immediate cancel on top of a scheduled one leaves the scheduled cancel's reason alone, so resume still belongs to whoever scheduled it`(): Unit = runBlocking {
        fake.onCancelSubscription = { CancelSubscriptionResult.Scheduled(w.clock.now() + 30 * day) }

        // an admin schedules the cancel, the buyer's immediate cancel fails at the gateway: ADMIN_CANCEL stays, the buyer cannot resume
        val (admin, _) = gateway("Ada", "sub_adm")

        actions.cancel(admin.sub.id, CancelActor.ADMIN, null, true)

        val scheduledByAdmin = subscription(admin.sub.id)

        assertEquals("ADMIN_CANCEL", scheduledByAdmin.endReason)
        assertTrue(scheduledByAdmin.cancelAtPeriodEnd)

        fake.onCancelSubscription = { CancelSubscriptionResult.Failed("gateway said no") }
        failsWith<PaymentProviderError> { actions.cancel(admin.sub.id, CancelActor.BUYER, admin.user.id, false) }

        val afterBuyerFailure = subscription(admin.sub.id)

        assertEquals("ADMIN_CANCEL", afterBuyerFailure.endReason)
        assertEquals(scheduledByAdmin.cancelRequestedAt, afterBuyerFailure.cancelRequestedAt)
        assertTrue(afterBuyerFailure.cancelAtPeriodEnd)
        assertEquals(SubscriptionStatus.ACTIVE, afterBuyerFailure.status)
        failsWith<SubscriptionNotResumable> { actions.resume(admin.sub.id, admin.user.id) }

        // the mirror: the buyer schedules it, a failed admin immediate cancel leaves BUYER_CANCEL and the buyer can still resume
        fake.onCancelSubscription = { CancelSubscriptionResult.Scheduled(w.clock.now() + 30 * day) }

        val (buyer, _) = gateway("Bea", "sub_buy")

        actions.cancel(buyer.sub.id, CancelActor.BUYER, buyer.user.id, true)

        val scheduledByBuyer = subscription(buyer.sub.id)

        assertEquals("BUYER_CANCEL", scheduledByBuyer.endReason)

        fake.onCancelSubscription = { CancelSubscriptionResult.Failed("gateway said no") }
        failsWith<PaymentProviderError> { actions.cancel(buyer.sub.id, CancelActor.ADMIN, null, false) }

        val afterAdminFailure = subscription(buyer.sub.id)

        assertEquals("BUYER_CANCEL", afterAdminFailure.endReason)
        assertEquals(scheduledByBuyer.cancelRequestedAt, afterAdminFailure.cancelRequestedAt)

        sw.first.onResume = { ResumeSubscriptionResult.Resumed() }

        val resumed = actions.resume(buyer.sub.id, buyer.user.id)

        assertFalse(resumed.cancelAtPeriodEnd)
        assertNull(resumed.endReason)
    }

    @Test
    fun `79 LocalOnly from a gateway subscription is a failure, 502 and nothing changes`(): Unit = runBlocking {
        val (g, _) = gateway()

        fake.onCancelSubscription = { CancelSubscriptionResult.localOnly() }
        failsWith<PaymentProviderError> { actions.cancel(g.sub.id, CancelActor.BUYER, g.user.id, true) }
        failsWith<PaymentProviderError> { actions.cancel(g.sub.id, CancelActor.BUYER, g.user.id, false) }

        val row = subscription(g.sub.id)

        assertEquals(SubscriptionStatus.ACTIVE, row.status)
        assertFalse(row.cancelAtPeriodEnd)
        assertNull(row.cancelRequestedAt)
        assertEquals(RemoteCancelState.NONE, row.remoteCancelState)
        assertEquals(0, mails("SUBSCRIPTION_CANCELLED"))
        assertEquals(0, hooks("subscription.cancelled").size)
    }

    @Test
    fun `78 BuyerActionRequired answers a redirect and changes nothing, the later SubscriptionUpdated schedules the end`(): Unit = runBlocking {
        val (g, _) = gateway()

        fake.onCancelSubscription = { CancelSubscriptionResult.BuyerActionRequired("https://gateway.invalid/cancel/1") }

        val answer = actions.cancel(g.sub.id, CancelActor.BUYER, g.user.id, true)

        assertEquals("https://gateway.invalid/cancel/1", (answer as CancelOutcome.Redirect).url)

        val unchanged = subscription(g.sub.id)

        assertFalse(unchanged.cancelAtPeriodEnd)
        assertNull(unchanged.cancelRequestedAt)

        rw.sink.apply(PaymentEvent.SubscriptionUpdated(gatewayState("sub_gw", GatewaySubscriptionStatus.CANCEL_SCHEDULED)), null, context())

        val scheduled = subscription(g.sub.id)

        assertTrue(scheduled.cancelAtPeriodEnd)
        assertEquals(SubscriptionStatus.ACTIVE, scheduled.status)
    }

    @Test
    fun `59 an unavailable provider gives the local change and the remote cancel waits in the queue until the provider is back`(): Unit = runBlocking {
        val (g, end) = gateway()

        sw.lookup.remove("fake")

        // at the period end: the flag is set, the gateway is told later
        val s = (actions.cancel(g.sub.id, CancelActor.BUYER, g.user.id, true) as CancelOutcome.Done).row

        assertTrue(s.cancelAtPeriodEnd)
        assertEquals(RemoteCancelState.PENDING, s.remoteCancelState)
        assertEquals(w.clock.now(), s.nextQueryAt)
        assertEquals(0, remoteCancels().size)

        // immediately: the subscription ends, the queue holds the call
        val (i, _) = run { sw.lookup.add(sw.first); gateway("Ida", "sub_i") }

        sw.lookup.remove("fake")

        val ended = (actions.cancel(i.sub.id, CancelActor.BUYER, i.user.id, false) as CancelOutcome.Done).row

        assertEquals(SubscriptionStatus.CANCELLED, ended.status)
        assertEquals(RemoteCancelState.PENDING, ended.remoteCancelState)
        assertEquals(w.clock.now(), ended.nextQueryAt)

        // the provider is back: step E calls cancelSubscription (atPeriodEnd = false) and the queue is DONE
        sw.lookup.add(sw.first)
        fake.onCancelSubscription = { CancelSubscriptionResult.Cancelled(null) }
        job.runOnce()

        assertEquals(RemoteCancelState.DONE, subscription(i.sub.id).remoteCancelState)
        assertEquals(RemoteCancelState.DONE, subscription(g.sub.id).remoteCancelState)
        assertTrue(remoteCancels().all { !it.atPeriodEnd })
        assertEquals(2, remoteCancels().size)
        assertTrue(end > w.clock.now())
    }

    @Test
    fun `an immediate gateway cancel that the gateway confirmed is CANCELLED with the remote state DONE and nothing queued`(): Unit = runBlocking {
        val (g, _) = gateway()

        fake.onCancelSubscription = { CancelSubscriptionResult.Cancelled(null) }

        val row = (actions.cancel(g.sub.id, CancelActor.BUYER, g.user.id, false) as CancelOutcome.Done).row

        assertEquals(SubscriptionStatus.CANCELLED, row.status)
        assertEquals(RemoteCancelState.DONE, row.remoteCancelState)
        assertNull(row.nextQueryAt)
        assertEquals(1, remoteCancels().size)
        assertFalse(remoteCancels().single().atPeriodEnd)
        assertEquals(EntitlementStatus.EXPIRED, w.entitlements.getById(g.sub.entitlementId!!, pool)!!.status)
    }

    // ==================================================================================== 57: crash between the two transactions

    @Test
    fun `57 a cancel whose second transaction never ran is repeated by step E after two minutes and the result applied`(): Unit = runBlocking {
        val (g, _) = gateway()

        // tx1 of a cancel, then the process died
        sql("UPDATE `pano_market_subscription` SET `cancelRequestedAt` = ?, `endReason` = 'BUYER_CANCEL' WHERE `id` = ?", w.clock.now(), g.sub.id)
        fake.onCancelSubscription = { CancelSubscriptionResult.Scheduled(w.clock.now() + 30 * day) }

        w.clock.advance(60_000)
        job.runOnce()
        assertEquals(0, remoteCancels().size, "not before two minutes")

        w.clock.advance(61_000)
        job.runOnce()
        job.runOnce()

        val row = subscription(g.sub.id)

        assertEquals(1, remoteCancels().size, "the remote call is repeated once")
        assertTrue(row.cancelAtPeriodEnd)
        assertEquals(SubscriptionStatus.ACTIVE, row.status)
        assertEquals("BUYER_CANCEL", row.endReason)
        assertEquals(1, mails("SUBSCRIPTION_CANCELLED"))
    }

    @Test
    fun `a repeated cancel that the gateway refuses withdraws the intent and is not tried again`(): Unit = runBlocking {
        val (g, _) = gateway()

        sql("UPDATE `pano_market_subscription` SET `cancelRequestedAt` = ?, `endReason` = 'BUYER_CANCEL' WHERE `id` = ?", w.clock.now(), g.sub.id)
        fake.onCancelSubscription = { CancelSubscriptionResult.Failed("down") }
        w.clock.advance(3 * 60_000)
        job.runOnce()
        job.runOnce()

        val row = subscription(g.sub.id)

        assertNull(row.cancelRequestedAt)
        assertNull(row.endReason)
        assertFalse(row.cancelAtPeriodEnd)
        assertEquals(1, remoteCancels().size, "no loop")
    }

    // ==================================================================================== 58: the remote cancel queue

    @Test
    fun `58 a failing remote cancel backs off, gives up after 20 attempts, and a panel cancel puts it back in the queue`(): Unit = runBlocking {
        val (g, _) = gateway()

        sw.lookup.remove("fake")
        actions.cancel(g.sub.id, CancelActor.ADMIN, null, false)
        sw.lookup.add(sw.first)
        fake.onCancelSubscription = { CancelSubscriptionResult.Failed("still down") }

        job.runOnce()

        val first = subscription(g.sub.id)

        assertEquals(RemoteCancelState.PENDING, first.remoteCancelState)
        assertEquals(1, first.remoteCancelAttempts)
        assertEquals(w.clock.now() + 30_000, first.nextQueryAt, "the first backoff is 30 s")

        // the second failure doubles it
        w.clock.set(first.nextQueryAt!!)
        job.runOnce()
        assertEquals(w.clock.now() + 60_000, subscription(g.sub.id).nextQueryAt, "30 s doubled once")

        sql("UPDATE `pano_market_subscription` SET `remoteCancelAttempts` = 19, `nextQueryAt` = ? WHERE `id` = ?", w.clock.now(), g.sub.id)
        job.runOnce()

        val given = subscription(g.sub.id)

        assertEquals(RemoteCancelState.FAILED, given.remoteCancelState)
        assertEquals(1, events(g.order.id, OrderEventType.SUBSCRIPTION_REMOTE_CANCEL_FAILED).size)

        // the buyer cannot re-queue (409), the panel can
        failsWith<SubscriptionNotCancellable> { actions.cancel(g.sub.id, CancelActor.BUYER, g.user.id, true) }

        val requeued = (actions.cancel(g.sub.id, CancelActor.ADMIN, null, true) as CancelOutcome.Done).row

        assertEquals(RemoteCancelState.PENDING, requeued.remoteCancelState)
        assertEquals(0, requeued.remoteCancelAttempts)
        assertEquals(SubscriptionStatus.CANCELLED, requeued.status)

        fake.onCancelSubscription = { CancelSubscriptionResult.Cancelled(null) }
        job.runOnce()

        assertEquals(RemoteCancelState.DONE, subscription(g.sub.id).remoteCancelState)
    }

    // ==================================================================================== 60 to 62: refund, chargeback, user deletion, block

    private fun orderOf(source: MarketOrder, status: OrderStatus = source.status) = MarketOrder(id = source.id, status = status, subscriptionId = source.subscriptionId, source = source.source)

    private suspend fun refunded(order: MarketOrder, revoke: Boolean = true, status: OrderStatus = OrderStatus.REFUNDED) {
        sw.db.txRestartingOnOrderChange { conn ->
            sw.locks.forOrder(conn, order.id, OrderLockScope.RELEASE) { rw.subs.onOrderRefunded(conn, orderOf(order, status), MarketRefund(revoke = revoke)) }
        }
    }

    private suspend fun chargedBack(order: MarketOrder) {
        sw.db.txRestartingOnOrderChange { conn ->
            sw.locks.forOrder(conn, order.id, OrderLockScope.RELEASE) { rw.subs.onOrderChargeback(conn, orderOf(order, OrderStatus.CHARGEBACK), MarketDispute()) }
        }
    }

    @Test
    fun `60 the full refund of the current period with revoke ends the subscription as REFUND without EXPIRE rows, other refunds leave it alone`(): Unit = runBlocking {
        val partial = activeMerchant(name = "Pat")

        refunded(partial.order, status = OrderStatus.PARTIALLY_REFUNDED)
        assertEquals(SubscriptionStatus.ACTIVE, subscription(partial.sub.id).status, "a partial refund")

        val goodwill = activeMerchant(name = "Gil")

        refunded(goodwill.order, revoke = false)
        assertEquals(SubscriptionStatus.ACTIVE, subscription(goodwill.sub.id).status, "revoke = 0 is a goodwill refund")

        val full = activeMerchant(name = "Fay")

        refunded(full.order)

        val row = subscription(full.sub.id)

        assertEquals(SubscriptionStatus.CANCELLED, row.status)
        assertEquals("REFUND", row.endReason)
        assertNull(row.storedMethod)
        assertEquals(0, expireRows(full.order.id), "the refund flow's REVOKE rows are the undo: no EXPIRE rows")
        assertEquals(1, mails("SUBSCRIPTION_ENDED"))
        assertEquals(1, hooks("subscription.expired").size)

        // the entitlement is the refund flow's business (undoHandledByCaller): this stand-in plays its part
        sql("UPDATE `pano_market_entitlement` SET `status` = 'REVOKED', `endReason` = 'REFUND', `endedAt` = ? WHERE `id` = ?", w.clock.now(), full.sub.entitlementId)
    }

    @Test
    fun `60 a refund of an older period leaves the subscription alone, the refund of the paid renewal ends it and expires the entitlement itself`(): Unit = runBlocking {
        val a = activeMerchant()

        chargeSucceeds()
        w.clock.set(a.sub.nextChargeAt!!)
        job.runOnce()

        val renewed = subscription(a.sub.id)
        val renewalOrder = renewalOrderOf(a.sub.id)

        assertEquals(2, renewed.cycleCount)

        refunded(a.order)
        assertEquals(SubscriptionStatus.ACTIVE, subscription(a.sub.id).status, "the first period is an older one now")

        // a renewal order has no entitlement of its own, so the undo of its refund cannot have ended the subscription's: the ending does it
        refunded(renewalOrder)

        val row = subscription(a.sub.id)

        assertEquals(SubscriptionStatus.CANCELLED, row.status)
        assertEquals("REFUND", row.endReason)
        assertEquals(EntitlementStatus.EXPIRED, w.entitlements.getById(a.sub.entitlementId!!, pool)!!.status)
        assertTrue(expireRows(a.order.id) > 0)
    }

    @Test
    fun `61 a chargeback ends the subscription as CHARGEBACK, queues the remote cancel and a won dispute reactivates nothing`(): Unit = runBlocking {
        val (g, _) = gateway()

        chargedBack(g.order)

        val row = subscription(g.sub.id)

        assertEquals(SubscriptionStatus.CANCELLED, row.status)
        assertEquals("CHARGEBACK", row.endReason)
        assertEquals(RemoteCancelState.PENDING, row.remoteCancelState)
        assertEquals(0, expireRows(g.order.id), "revokeOnChargeback: the dispute flow's REVOKE rows are the undo")

        // the queue tells the gateway to stop billing
        fake.onCancelSubscription = { CancelSubscriptionResult.Cancelled(null) }
        job.runOnce()
        assertEquals(RemoteCancelState.DONE, subscription(g.sub.id).remoteCancelState)

        // a second event for the closed row does nothing
        chargedBack(g.order)
        assertEquals(SubscriptionStatus.CANCELLED, subscription(g.sub.id).status)
        assertEquals(1, hooks("subscription.expired").size)

        sql("UPDATE `pano_market_entitlement` SET `status` = 'REVOKED', `endReason` = 'CHARGEBACK', `endedAt` = ? WHERE `id` = ?", w.clock.now(), g.sub.entitlementId)
    }

    @Test
    fun `61 a chargeback of a renewal order expires the subscription's entitlement`(): Unit = runBlocking {
        val a = activeMerchant()

        chargeSucceeds()
        w.clock.set(a.sub.nextChargeAt!!)
        job.runOnce()
        chargedBack(renewalOrderOf(a.sub.id))

        val row = subscription(a.sub.id)

        assertEquals(SubscriptionStatus.CANCELLED, row.status)
        assertEquals("CHARGEBACK", row.endReason)
        assertEquals(EntitlementStatus.EXPIRED, w.entitlements.getById(a.sub.entitlementId!!, pool)!!.status)
    }

    // ==================================================================================== 60: the refund of an older period (RefundService seam)

    @Test
    fun `60 an order of an older period is told apart from the current one, whatever the subscription's status`(): Unit = runBlocking {
        val a = activeMerchant()
        val plain = buy(subProduct(), a.caller)

        assertFalse(rw.subs.isOlderPeriod(pool, a.order), "the first order is the current period while cycleCount is 1")
        assertFalse(rw.subs.isOlderPeriod(pool, MarketOrder(id = plain.id)), "an order without a subscription")

        chargeSucceeds()
        w.clock.set(a.sub.nextChargeAt!!)
        job.runOnce()

        val renewalOrder = renewalOrderOf(a.sub.id)

        assertTrue(rw.subs.isOlderPeriod(pool, order(a.order.id)), "the first order is an older period now")
        assertFalse(rw.subs.isOlderPeriod(pool, renewalOrder), "the paid renewal is the current period")
    }

    @Test
    fun `60 RefundService forces revoke off for an older period and the preview warns OLDER_SUBSCRIPTION_PERIOD`(): Unit = runBlocking {
        val r = RefundWorld(w, vertx)
        val user = fx.user("Ola")
        val grant = com.panomc.plugins.market.core.delivery.ProductAction("a1", com.panomc.plugins.market.db.model.DeliveryActionType.PERMISSION, nodes = listOf("group.vip"))
        val paid = r.place(user, listOf(RefundLine(1000, actions = listOf(grant))))
        var older = true
        val effects = StandardRefundEffects(w.clock, w.creatorEarnings, { com.panomc.plugins.market.support.MarketTestDb.TABLE_PREFIX }, olderPeriod = { _, _ -> older })
        val service = RefundService(
            w.db, r.d.locks, w.clock, { w.config }, w.orders, w.orderItems, w.orderEvents, w.payments, w.refunds, w.refundItems, w.deliveries, w.entitlements, w.creditTxs, r.d.credits,
            r.d.service, r.d.entitlementService, PaymentServiceRefundGateway(r.payments) { w.pool }, w.serverStates, effects
        )

        assertTrue(service.preview(paid.order.id, RefundInput(revoke = true)).warnings.any { it.getString("code") == "OLDER_SUBSCRIPTION_PERIOD" })

        // a partial refund: I20 would flag a fully refunded order whose entitlement is still ACTIVE, which is exactly what an older period is meant to keep
        val done = service.request(paid.order.id, RefundInput(amount = 400, revoke = true), r.key(), null)

        assertFalse(r.refund(done.refund.id).revoke, "revoke = 1 is forced to 0")
        assertEquals(EntitlementStatus.ACTIVE, w.entitlements.getByOrderItemId(paid.items.single().id, pool).single().status, "the goods stay")

        older = false

        val other = r.place(fx.user("Ben"), listOf(RefundLine(1000, actions = listOf(grant))))

        assertFalse(service.preview(other.order.id, RefundInput(revoke = true)).warnings.any { it.getString("code") == "OLDER_SUBSCRIPTION_PERIOD" })
        assertTrue(r.refund(service.request(other.order.id, RefundInput(amount = 1000, revoke = true), r.key(), null).refund.id).revoke)
    }

    @Test
    fun `62 a deleted user's subscriptions end, lose their personal data and a closed one is scrubbed`(): Unit = runBlocking {
        val a = activeMerchant()
        val pending = subscription(buy(subProduct(), a.caller).subscriptionId!!)

        // a third one that is closed already
        val product = subProduct()
        val third = buy(product, a.caller)

        succeed(third, stored = com.panomc.plugins.market.spi.payment.StoredPaymentMethod("tok_3"))
        actions.cancel(third.subscriptionId!!, CancelActor.BUYER, a.user.id, false)

        assertNotNull(subscription(third.subscriptionId!!).userId)
        assertEquals(SubscriptionStatus.PENDING, pending.status)

        val handled = rw.subs.onUserDeleted(sw.db, { after -> rw.payments.runAfterCommit(after) }, a.user.id)

        assertEquals(3, handled)

        val active = subscription(a.sub.id)

        assertEquals(SubscriptionStatus.CANCELLED, active.status)
        assertEquals("ADMIN_CANCEL", active.endReason)
        assertNull(active.userId)
        assertNull(active.email)
        assertNull(active.storedMethod)
        assertEquals(1, mails("SUBSCRIPTION_ENDED"), "only the third subscription's own cancel sent one: a deleted account gets none")
        assertEquals(EntitlementStatus.EXPIRED, w.entitlements.getById(a.sub.entitlementId!!, pool)!!.status)

        val closedPending = subscription(pending.id)

        assertEquals(SubscriptionStatus.CANCELLED, closedPending.status)
        assertNull(closedPending.userId)
        assertNull(closedPending.email)

        val scrubbed = subscription(third.subscriptionId!!)

        assertEquals(SubscriptionStatus.CANCELLED, scrubbed.status)
        assertNull(scrubbed.userId)
        assertNull(scrubbed.email)
    }

    @Test
    fun `a blocked buyer's gateway subscription accepts the renewal that arrives and then ends at its period end`(): Unit = runBlocking {
        val (g, end) = gateway()

        rw.blocks = BuyerBlocks { _, _, _, _, _, _ -> true }
        rw.build()
        buildActions()

        w.clock.set(end + 60_000)

        val renewed = PaymentEvent.SubscriptionRenewed("sub_gw", Money(g.sub.price, g.sub.currency)).also {
            it.gatewayTransactionId = "txn_blocked"
            it.periodStart = end
            it.periodEnd = end + 30 * day
        }

        rw.sink.apply(renewed, null, context())

        val row = subscription(g.sub.id)

        assertEquals(2, row.cycleCount, "the money arrived: the renewal is recorded")
        assertEquals(SubscriptionStatus.ACTIVE, row.status)
        assertTrue(row.cancelAtPeriodEnd)
        assertEquals("ADMIN_CANCEL", row.endReason)
        assertEquals(RemoteCancelState.PENDING, row.remoteCancelState, "the gateway is told to stop billing")
        assertEquals(1, mails("SUBSCRIPTION_CANCELLED"))
    }

    // ==================================================================================== 82: the portal

    @Test
    fun `82 a portal answers the gateway's address, an Html document is kept for its account, other rows are 409 SUBSCRIPTION_NOT_MANAGEABLE`(): Unit = runBlocking {
        val (g, _) = gateway()

        assertEquals("https://gateway.invalid/portal/${g.sub.id}", actions.portal(g.sub.id, g.user.id, PortalPurpose.MANAGE))
        assertEquals("https://shop.example/profile/subscriptions", sw.first.portalCalls.single().returnUrl)

        sw.first.onPortal = { SubscriptionPortalResult.Html("<html>card form</html>") }

        val url = actions.portal(g.sub.id, g.user.id, PortalPurpose.UPDATE_PAYMENT_METHOD)

        assertTrue(url.startsWith(PortalPages.PATH))
        assertEquals(PortalPurpose.UPDATE_PAYMENT_METHOD, sw.first.portalCalls.last().purpose)

        val token = url.removePrefix(PortalPages.PATH)

        assertEquals("<html>card form</html>", actions.portalPages.get(token, g.user.id))
        assertNull(actions.portalPages.get(token, g.user.id + 1), "only for the account it was made for")
        assertNull(actions.portalPages.get("0".repeat(32), g.user.id))

        w.clock.advance(16 * 60_000)
        assertNull(actions.portalPages.get(token, g.user.id), "a quarter of an hour")

        // an unsupported portal, another account, a pending and a closed row
        sw.first.onPortal = { SubscriptionPortalResult.unsupported() }
        failsWith<SubscriptionNotManageable> { actions.portal(g.sub.id, g.user.id, PortalPurpose.MANAGE) }
        failsWith<NotFound> { actions.portal(g.sub.id, g.user.id + 5, PortalPurpose.MANAGE) }

        val pendingProduct = subProduct()
        val pendingOrder = buy(pendingProduct, g.caller)

        failsWith<SubscriptionNotManageable> { actions.portal(pendingOrder.subscriptionId!!, g.user.id, PortalPurpose.MANAGE) }

        sw.first.onPortal = { SubscriptionPortalResult.Redirect("https://gateway.invalid/p") }
        fake.onCancelSubscription = { CancelSubscriptionResult.Cancelled(null) }
        actions.cancel(g.sub.id, CancelActor.BUYER, g.user.id, false)
        failsWith<SubscriptionNotManageable> { actions.portal(g.sub.id, g.user.id, PortalPurpose.MANAGE) }
    }

    @Test
    fun `a portal needs the capability and a gateway-managed row`(): Unit = runBlocking {
        val (noCap, _) = gateway("Nora", "sub_nocap", portal = false)

        failsWith<SubscriptionNotManageable> { actions.portal(noCap.sub.id, noCap.user.id, PortalPurpose.MANAGE) }
        assertEquals(0, sw.first.portalCalls.size)

        val merchant = activeMerchant(name = "Max")

        sw.caps(RecurringSupport.MERCHANT_INITIATED) { recurringPortal = true }
        failsWith<SubscriptionNotManageable> { actions.portal(merchant.sub.id, merchant.user.id, PortalPurpose.MANAGE) }

        val (down, _) = gateway("Dina", "sub_down")

        sw.first.onPortal = { throw ProviderException(ProviderErrorCode.GATEWAY_UNREACHABLE, "down") }
        failsWith<PaymentProviderError> { actions.portal(down.sub.id, down.user.id, PortalPurpose.MANAGE) }
    }

    // ==================================================================================== 45: the admin retry

    @Test
    fun `45 the admin retry charges a PAST_DUE merchant subscription now, nothing else is retryable`(): Unit = runBlocking {
        val a = pastDueMerchant()

        chargeSucceeds()
        actions.retry(a.sub.id) { job.chargeOne(it, admin = true) }

        val row = subscription(a.sub.id)

        assertEquals(SubscriptionStatus.ACTIVE, row.status)
        assertEquals(2, row.cycleCount)
        assertEquals(0, row.failCount)

        // an ACTIVE row before its period end is not retryable
        failsWith<SubscriptionNotRetryable> { actions.retry(a.sub.id) { job.chargeOne(it, admin = true) } }

        // a manual one never is
        val manual = activeManual(name = "Mia")

        failsWith<SubscriptionNotRetryable> { actions.retry(manual.sub.id) { job.chargeOne(it, admin = true) } }

        // an unknown id
        failsWith<NotFound> { actions.retry(987_654) { job.chargeOne(it, admin = true) } }
    }

    @Test
    fun `the admin retry of a gateway subscription needs recurringRetry and applies what the provider returns`(): Unit = runBlocking {
        val (without, _) = gateway("Wes", "sub_without", retry = false)

        failsWith<SubscriptionNotRetryable> { actions.retry(without.sub.id) { job.chargeOne(it, admin = true) } }
        assertEquals(0, sw.first.retryCalls.size)

        val (g, end) = gateway("Rex", "sub_retry", retry = true)

        // unsupported answer: 409
        failsWith<SubscriptionNotRetryable> { actions.retry(g.sub.id) { job.chargeOne(it, admin = true) } }

        // a thrown provider error: 502
        sw.first.onRetry = { throw ProviderException(ProviderErrorCode.GATEWAY_UNREACHABLE, "down") }
        failsWith<PaymentProviderError> { actions.retry(g.sub.id) { job.chargeOne(it, admin = true) } }

        // the gateway charged again: the renewal it returns is applied like a poll answer
        w.clock.set(end + 60_000)
        sw.first.onRetry = {
            SubscriptionQueryResult(
                listOf(
                    PaymentEvent.SubscriptionRenewed("sub_retry", Money(g.sub.price, g.sub.currency)).also { e ->
                        e.gatewayTransactionId = "txn_retry"
                        e.periodStart = end
                        e.periodEnd = end + 30 * day
                    }
                )
            )
        }
        actions.retry(g.sub.id) { job.chargeOne(it, admin = true) }

        assertEquals(2, subscription(g.sub.id).cycleCount)
        assertEquals(3, sw.first.retryCalls.size)
        assertEquals(1, renewals(g.sub.id).count { it.status == RenewalStatus.PAID })
    }

    // ==================================================================================== 70 / 71: buyer and panel views

    @Test
    fun `70 the buyer list hides PENDING rows and carries the renewal order, canCancel, canResume and canManageAtGateway`(): Unit = runBlocking {
        val a = activeManual()
        val pending = buy(subProduct(), a.caller)

        assertEquals(SubscriptionStatus.PENDING, subscription(pending.subscriptionId!!).status)

        w.clock.set(a.sub.currentPeriodEnd!! - 3 * day)
        job.runOnce()

        val rows = views.buyerList(a.user.id, pool)

        assertEquals(1, rows.size, "PENDING is hidden")

        val row = rows.single()

        assertEquals(a.sub.id, row.getLong("id"))
        assertEquals("ACTIVE", row.getString("status"))
        assertEquals("MANUAL", row.getString("mode"))
        assertTrue(row.getBoolean("canCancel"))
        assertFalse(row.getBoolean("canResume"))
        assertFalse(row.getBoolean("canManageAtGateway"))
        assertEquals(order(renewals(a.sub.id).single().orderId!!).publicId, row.getString("renewalOrderPublicId"))
        assertFalse(row.containsKey("storedMethod"))
        assertFalse(row.containsKey("providerData"))

        cancelBuyer(a)

        val after = views.buyerList(a.user.id, pool).single()

        assertTrue(after.getBoolean("cancelAtPeriodEnd"))
        assertTrue(after.getBoolean("canResume"))
        assertNull(after.getString("renewalOrderPublicId"), "the renewal order was cancelled")

        // the gateway can show a portal
        val (g, _) = gateway("Gus", "sub_view")

        val gatewayRow = views.buyerList(g.user.id, pool).single()

        assertTrue(gatewayRow.getBoolean("canManageAtGateway"))
    }

    @Test
    fun `71 the panel list filters and pages, the detail hides the secrets and says what the panel may do`(): Unit = runBlocking {
        val a = activeMerchant(name = "Ann")
        val b = activeMerchant(name = "Bob")

        cancelBuyer(b, atPeriodEnd = false)

        val pending = buy(subProduct(), a.caller)

        val all = views.panelList(SubscriptionFilter(), Paging.Window(1, 10), pool)

        assertEquals(2, all.count, "PENDING rows only with status=PENDING")
        assertEquals(1L, all.totalPage)
        assertEquals(setOf(a.sub.id, b.sub.id), all.rows.map { it.getLong("id") }.toSet())

        assertEquals(listOf(b.sub.id), views.panelList(SubscriptionFilter(statuses = parseStatuses("CANCELLED,EXPIRED")), Paging.Window(1, 10), pool).rows.map { it.getLong("id") })
        assertEquals(listOf(pending.subscriptionId!!), views.panelList(SubscriptionFilter(statuses = parseStatuses("PENDING")), Paging.Window(1, 10), pool).rows.map { it.getLong("id") })
        assertEquals(listOf(a.sub.id), views.panelList(SubscriptionFilter(search = "ann"), Paging.Window(1, 10), pool).rows.map { it.getLong("id") })
        assertEquals(2, views.panelList(SubscriptionFilter(mode = SubscriptionMode.MERCHANT, providerId = "fake"), Paging.Window(1, 10), pool).count)
        assertEquals(0, views.panelList(SubscriptionFilter(mode = SubscriptionMode.GATEWAY), Paging.Window(1, 10), pool).count)
        assertEquals(1, views.panelList(SubscriptionFilter(), Paging.Window(2, 1), pool).rows.size)
        assertEquals(2L, views.panelList(SubscriptionFilter(), Paging.Window(1, 1), pool).totalPage)
        assertEquals(0, views.panelList(SubscriptionFilter(search = "100%"), Paging.Window(1, 10), pool).count, "LIKE wildcards are escaped")

        val detail = views.panelDetail(a.sub.id, pool)!!
        val subscription = detail.getJsonObject("subscription")

        assertFalse(subscription.containsKey("storedMethod"))
        assertFalse(subscription.containsKey("providerData"))
        assertEquals("NONE", subscription.getString("remoteCancelState"))
        assertTrue(detail.getJsonObject("allowed").getBoolean("cancel"))
        assertFalse(detail.getJsonObject("allowed").getBoolean("retry"), "the period is not over")
        assertEquals(1, detail.getJsonArray("orders").size())
        assertEquals(0, detail.getJsonArray("renewals").size())
        assertNull(views.panelDetail(424_242, pool))

        val closed = views.panelDetail(b.sub.id, pool)!!

        assertFalse(closed.getJsonObject("allowed").getBoolean("cancel"))

        // a failed row is retryable
        declined()
        w.clock.set(a.sub.nextChargeAt!!)
        job.runOnce()

        val failed = views.panelDetail(a.sub.id, pool)!!

        assertTrue(failed.getJsonObject("allowed").getBoolean("retry"))
        assertEquals(1, failed.getJsonArray("renewals").size())
        assertEquals(2, failed.getJsonArray("orders").size())
    }

    // ==================================================================================== the wiring

    @Test
    fun `the production composition passes the subscription hooks, the job carries the use cases and the routes are registered`() {
        val root = java.io.File("src/main/kotlin/com/panomc/plugins/market")
        val refund = root.resolve("routes/panel/refund/RefundRoutes.kt").readText()
        val dispute = root.resolve("routes/panel/dispute/DisputeRoutes.kt").readText()
        val player = root.resolve("event/PlayerEventHandler.kt").readText()
        val wiring = root.resolve("routes/panel/subscription/SubscriptionWiring.kt").readText()
        val scheduler = root.resolve("job/MarketScheduler.kt").readText()
        val panel = root.resolve("routes/panel/subscription/SubscriptionRoutes.kt").readText()
        val user = root.resolve("routes/user/subscription/SubscriptionRoutes.kt").readText()

        assertTrue(refund.contains("subscriptionService(plugin).onOrderRefunded(conn, order, refund)"))
        assertTrue(dispute.contains("subscriptionService(plugin).onOrderChargeback(conn, order, dispute)"))
        assertTrue(dispute.contains("subscriptionService(plugin).onChargebackOwner("), "WIRE-2: the buyer's other subscriptions end after the O11 commit")
        assertTrue(player.contains("subscriptionService(plugin).onUserDeleted("))
        assertTrue(wiring.contains("actions = actions"), "step E repeats a cancel that crashed")
        assertTrue(scheduler.contains("routes.panel.subscription.subscriptionJob(plugin)"), "the scheduler and the admin retry share one job")

        for (path in listOf("/api/panel/market/subscriptions\"", "/api/panel/market/subscriptions/:id\"", "/api/panel/market/subscriptions/:id/cancel", "/api/panel/market/subscriptions/:id/retry")) {
            assertTrue(panel.contains(path), path)
        }

        for (path in listOf("/api/market/me/subscriptions\"", "/me/subscriptions/:id/cancel", "/me/subscriptions/:id/resume", "/me/subscriptions/:id/portal")) {
            assertTrue(user.contains(path), path)
        }

        // the node of every panel route: OV reads, PAY cancels and retries
        assertEquals(2, Regex("setOf\\(MarketNode\\.ORDERS_VIEW\\)").findAll(panel).count())
        assertEquals(2, Regex("setOf\\(MarketNode\\.PAYMENTS\\)").findAll(panel).count())
    }

    @Test
    fun `a MERCHANT cancel never fails because the provider cannot delete the instrument`(): Unit = runBlocking {
        val a = activeMerchant()

        fake.onCancelSubscription = { throw ProviderException(ProviderErrorCode.UNSUPPORTED, "cancelSubscription") }

        val row = cancelBuyer(a, atPeriodEnd = false).row

        assertEquals(SubscriptionStatus.CANCELLED, row.status)
        assertEquals(1, remoteCancels().size)

        // no charge was made either
        assertTrue(fake.calls(FakePaymentProvider.Op.CHARGE_RECURRING).map { it.request as RecurringChargeRequest }.isEmpty())
        assertEquals("BUYER_CANCEL", ended(a.sub.id).endReason)
    }
}
