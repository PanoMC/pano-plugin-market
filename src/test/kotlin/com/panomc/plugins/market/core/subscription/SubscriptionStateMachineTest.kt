package com.panomc.plugins.market.core.subscription

import com.panomc.plugins.market.core.subscription.SubEffect.*
import com.panomc.plugins.market.core.subscription.SubscriptionStateMachine.MAIL_CANCELLED
import com.panomc.plugins.market.core.subscription.SubscriptionStateMachine.MAIL_PAYMENT_FAILED
import com.panomc.plugins.market.core.subscription.SubscriptionStateMachine.MAIL_REASON_CHARGE_FAILED
import com.panomc.plugins.market.core.subscription.SubscriptionStateMachine.MAIL_REASON_NOT_RENEWED
import com.panomc.plugins.market.core.subscription.SubscriptionStateMachine.ORDER_EVENT_CANCEL_REQUESTED
import com.panomc.plugins.market.core.subscription.SubscriptionStateMachine.ORDER_EVENT_CHARGE_FAILED
import com.panomc.plugins.market.core.subscription.SubscriptionStateMachine.ORDER_EVENT_PAST_DUE
import com.panomc.plugins.market.core.subscription.SubscriptionStateMachine.ORDER_EVENT_RENEWED
import com.panomc.plugins.market.core.subscription.SubscriptionStateMachine.ORDER_EVENT_RESUMED
import com.panomc.plugins.market.core.subscription.SubscriptionStateMachine.ORDER_EVENT_STARTED
import com.panomc.plugins.market.core.subscription.SubscriptionStateMachine.WEBHOOK_CANCELLED
import com.panomc.plugins.market.core.subscription.SubscriptionStateMachine.WEBHOOK_RENEWED
import com.panomc.plugins.market.core.subscription.SubscriptionStateMachine.WEBHOOK_STARTED
import com.panomc.plugins.market.db.model.MarketSubscription
import com.panomc.plugins.market.db.model.RemoteCancelState
import com.panomc.plugins.market.db.model.SubscriptionMode
import com.panomc.plugins.market.db.model.SubscriptionStatus
import com.panomc.plugins.market.db.model.SubscriptionStatus.*
import com.panomc.plugins.market.spi.payment.GatewaySubscriptionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/** `SubscriptionStateMachine` (00 section 7.5, 09 sections 6 and 7; tests 10 to 15 of 09 section 16). */
class SubscriptionStateMachineTest {
    private val day = SubscriptionTimings.DAY_MS
    private val hour = SubscriptionTimings.HOUR_MS
    private val now = 1_800_000_000_000L
    private val cfg = SubConfig(graceDays = 3)

    private val merchant = SubscriptionMode.MERCHANT
    private val gateway = SubscriptionMode.GATEWAY
    private val manual = SubscriptionMode.MANUAL

    private fun st(
        status: SubscriptionStatus = ACTIVE,
        mode: SubscriptionMode = merchant,
        cycleCount: Int = 1,
        maxCycles: Int? = null,
        currentPeriodEnd: Long? = now + 10 * day,
        cancelAtPeriodEnd: Boolean = false,
        cancelRequestedAt: Long? = null,
        endReason: SubscriptionEndReason? = null,
        graceEndsAt: Long? = null,
        nextChargeAt: Long? = null,
        remoteCancelState: RemoteCancelState = RemoteCancelState.NONE,
        gatewaySubscriptionId: String? = null
    ) = SubState(
        status, mode, cycleCount, maxCycles, currentPeriodEnd, cancelAtPeriodEnd, cancelRequestedAt,
        endReason, graceEndsAt, nextChargeAt, remoteCancelState, gatewaySubscriptionId
    )

    private fun decide(sub: SubState, event: SubEvent, at: Long = now, config: SubConfig = cfg): SubTransition =
        SubscriptionStateMachine.decide(sub, event, at, config)

    private fun SubTransition.moved(): SubTransition.Apply = this as? SubTransition.Apply ?: fail("expected Apply but was $this")
    private fun SubTransition.ignoredReason(): String = (this as? SubTransition.Ignored)?.reason ?: fail("expected Ignored but was $this")
    private fun SubTransition.Apply.step(): SubStep = steps.singleOrNull() ?: fail("expected one step but was $steps")

    private inline fun <reified T : SubEffect> SubTransition.Apply.one(): T {
        val found = effects.filterIsInstance<T>()
        assertEquals(1, found.size, "exactly one ${T::class.simpleName} in $effects")
        return found.single()
    }

    private inline fun <reified T : SubEffect> SubTransition.Apply.count(): Int = effects.count { it is T }
    private fun SubTransition.Apply.has(effect: SubEffect): Boolean = effect in effects
    private fun SubTransition.Apply.mailKinds(): List<String> = effects.filterIsInstance<QueueMail>().map { it.kind }
    private fun SubTransition.Apply.webhooks(): List<String> = effects.filterIsInstance<QueueWebhook>().map { it.event }

    // ================================================================ S1, S2, S3

    @Test
    fun `S1 creates a PENDING row without effects`() {
        val t = SubscriptionStateMachine.create()
        val step = t.step()
        assertEquals(SubRule.S1, step.rule)
        assertNull(step.from)
        assertEquals(PENDING, step.to)
        assertEquals(emptyList<SubEffect>(), step.effects)
    }

    @Test
    fun `S2 activates a PENDING row from the initial order`() {
        val t = decide(st(PENDING, gateway), SubEvent.Activated).moved()
        val step = t.step()
        assertEquals(SubRule.S2, step.rule)
        assertEquals(PENDING, step.from)
        assertEquals(ACTIVE, step.to)
        assertEquals(listOf(ActivateFromPayment, AddOrderEvent(ORDER_EVENT_STARTED), QueueWebhook(WEBHOOK_STARTED)), step.effects)
        assertEquals("subscription.started", WEBHOOK_STARTED)
    }

    @Test
    fun `S2 replayed on an already activated row is a no-op, on a closed row it is refused`() {
        for (s in listOf(ACTIVE, PAST_DUE, PAUSED)) {
            assertEquals(SubscriptionStateMachine.ALREADY_ACTIVE, decide(st(s), SubEvent.Activated).ignoredReason(), "$s")
        }
        // 09 section 4.4: a CANCELLED row (closed by step F or O5) refuses the order transition.
        for (s in listOf(CANCELLED, EXPIRED, COMPLETED)) {
            assertEquals("SUBSCRIPTION_CLOSED", decide(st(s), SubEvent.Activated).ignoredReason(), "$s")
        }
    }

    @Test
    fun `S3 closes a PENDING row on O5 with ADMIN_CANCEL and on step F with PAYMENT_FAILED, without deliveries mail or webhook`() {
        val rejected = decide(st(PENDING, manual), SubEvent.InitialOrderRejected).moved()
        assertEquals(SubRule.S3, rejected.step().rule)
        assertEquals(PENDING, rejected.step().from)
        assertEquals(CANCELLED, rejected.to)
        assertEquals(listOf<SubEffect>(ClosePending(SubscriptionEndReason.ADMIN_CANCEL)), rejected.effects)

        val timeout = decide(st(PENDING, manual), SubEvent.PendingTimeout).moved()
        assertEquals(SubRule.S3, timeout.step().rule)
        assertEquals(listOf<SubEffect>(ClosePending(SubscriptionEndReason.PAYMENT_FAILED)), timeout.effects)

        for (t in listOf(rejected, timeout)) {
            assertEquals(0, t.count<EndSubscription>(), "S3 never runs the shared ending (no EXPIRE deliveries)")
            assertEquals(emptyList<String>(), t.mailKinds())
            assertEquals(emptyList<String>(), t.webhooks())
        }
    }

    @Test
    fun `S3 queues the remote cancel only when a gateway id is known`() {
        for (event in listOf(SubEvent.InitialOrderRejected, SubEvent.PendingTimeout)) {
            assertTrue(decide(st(PENDING, gateway, gatewaySubscriptionId = "sub_1"), event).moved().has(QueueRemoteCancel), "$event with id")
            assertFalse(decide(st(PENDING, gateway, gatewaySubscriptionId = null), event).moved().has(QueueRemoteCancel), "$event without id")
            assertFalse(decide(st(PENDING, merchant), event).moved().has(QueueRemoteCancel))
            assertFalse(decide(st(PENDING, manual), event).moved().has(QueueRemoteCancel))
        }
    }

    @Test
    fun `S3 is only for PENDING rows`() {
        for (s in listOf(ACTIVE, PAST_DUE, PAUSED)) {
            assertEquals(SubscriptionStateMachine.NOT_PENDING, decide(st(s), SubEvent.InitialOrderRejected).ignoredReason())
            assertEquals(SubscriptionStateMachine.NOT_PENDING, decide(st(s), SubEvent.PendingTimeout).ignoredReason())
        }
        for (s in listOf(CANCELLED, EXPIRED, COMPLETED)) {
            assertEquals("SUBSCRIPTION_CLOSED", decide(st(s), SubEvent.PendingTimeout).ignoredReason())
        }
    }

    // ================================================================ S4: renewal failure (09 section 9.1)

    @Test
    fun `S4 first declined MERCHANT charge enters grace, queues the mail once and schedules the first retry`() {
        val periodEnd = now - hour
        val sub = st(ACTIVE, merchant, currentPeriodEnd = periodEnd, nextChargeAt = periodEnd)
        val t = decide(sub, SubEvent.RenewalFailed(attempts = 1)).moved()
        val step = t.step()
        assertEquals(SubRule.S4, step.rule)
        assertEquals(ACTIVE, step.from)
        assertEquals(PAST_DUE, step.to)
        assertEquals(
            listOf(
                RecordRenewalError, RecordFailure, AddOrderEvent(ORDER_EVENT_CHARGE_FAILED),
                SetGraceEndsAt(now + 3 * day), // max(now, periodEnd) + 3 days
                SetNextChargeAt(now + day), // +1 day
                QueueMail(MAIL_PAYMENT_FAILED, MAIL_REASON_CHARGE_FAILED),
                AddOrderEvent(ORDER_EVENT_PAST_DUE)
            ),
            step.effects
        )
        assertEquals(1, t.mailKinds().size)
    }

    @Test
    fun `S4 grace is max(now, currentPeriodEnd) plus the grace days`() {
        // Period end in the past: grace counts from now.
        val past = decide(st(ACTIVE, manual, currentPeriodEnd = now - 5 * day), SubEvent.PeriodOver()).moved()
        assertEquals(SetGraceEndsAt(now + 3 * day), past.one<SetGraceEndsAt>())
        // Period end exactly now: both agree.
        val exact = decide(st(ACTIVE, manual, currentPeriodEnd = now), SubEvent.PeriodOver()).moved()
        assertEquals(SetGraceEndsAt(now + 3 * day), exact.one<SetGraceEndsAt>())
        // A gateway reports PAST_DUE while the paid period is still running: grace counts from the period end.
        val future = decide(st(ACTIVE, gateway, currentPeriodEnd = now + 2 * day), SubEvent.GatewayStatus(GatewaySubscriptionStatus.PAST_DUE)).moved()
        assertEquals(SetGraceEndsAt(now + 5 * day), future.one<SetGraceEndsAt>())
    }

    @Test
    fun `S4 with grace 0 makes graceEndsAt equal to max(now, currentPeriodEnd)`() {
        val zero = SubConfig(graceDays = 0)
        val a = decide(st(ACTIVE, manual, currentPeriodEnd = now - day), SubEvent.PeriodOver(), config = zero).moved()
        assertEquals(SetGraceEndsAt(now), a.one<SetGraceEndsAt>())
        val b = decide(st(ACTIVE, gateway, currentPeriodEnd = now + day), SubEvent.GatewayStatus(GatewaySubscriptionStatus.PAST_DUE), config = zero).moved()
        assertEquals(SetGraceEndsAt(now + day), b.one<SetGraceEndsAt>())
        // Grace 0: no retry at all (09 section 9.2).
        val c = decide(st(ACTIVE, merchant, currentPeriodEnd = now), SubEvent.RenewalFailed(attempts = 1), config = zero).moved()
        assertEquals(SetNextChargeAt(null), c.one<SetNextChargeAt>())
    }

    @Test
    fun `second and third declines walk the retry schedule inside the frozen grace and then stop`() {
        val firstFailure = now
        val graceEnds = firstFailure + 3 * day
        // second decline one day later: retry in 2 days = exactly the grace end
        val second = decide(
            st(PAST_DUE, merchant, graceEndsAt = graceEnds, nextChargeAt = now + day),
            SubEvent.RenewalFailed(attempts = 2), at = now + day
        ).moved()
        assertEquals(SubRule.FAILURE_RECORDED, second.step().rule)
        assertEquals(PAST_DUE, second.step().from)
        assertEquals(PAST_DUE, second.to)
        assertEquals(SetNextChargeAt(now + 3 * day), second.one<SetNextChargeAt>())
        assertEquals(0, second.count<QueueMail>(), "the mail is queued on S4 only")
        assertEquals(0, second.count<SetGraceEndsAt>(), "graceEndsAt is frozen at entry (09 section 15 item 12)")
        assertTrue(second.has(RecordFailure))

        // third decline at the grace end: the next candidate is after it, so no retry
        val third = decide(
            st(PAST_DUE, merchant, graceEndsAt = graceEnds, nextChargeAt = now + 3 * day),
            SubEvent.RenewalFailed(attempts = 3), at = now + 3 * day
        ).moved()
        assertEquals(SetNextChargeAt(null), third.one<SetNextChargeAt>())
    }

    @Test
    fun `a final MERCHANT failure clears nextChargeAt, grace keeps running`() {
        val sub = st(ACTIVE, merchant, currentPeriodEnd = now - hour)
        val t = decide(sub, SubEvent.RenewalFailed(final = true, attempts = 1)).moved()
        assertEquals(SubRule.S4, t.step().rule)
        assertEquals(SetNextChargeAt(null), t.one<SetNextChargeAt>())
        assertEquals(PAST_DUE, t.to)
        // On a PAST_DUE row a final failure also only clears the schedule.
        val past = decide(st(PAST_DUE, merchant, graceEndsAt = now + 2 * day), SubEvent.RenewalFailed(final = true, attempts = 1)).moved()
        assertEquals(PAST_DUE, past.to)
        assertEquals(SetNextChargeAt(null), past.one<SetNextChargeAt>())
    }

    @Test
    fun `a technical failure retries in one hour and changes nothing else`() {
        for (s in listOf(ACTIVE, PAST_DUE)) {
            val t = decide(st(s, merchant, graceEndsAt = now + day), SubEvent.RenewalFailed(technical = true)).moved()
            assertEquals(SubRule.TECHNICAL_FAILURE, t.step().rule)
            assertEquals(s, t.step().from)
            assertEquals(s, t.to, "status unchanged: step C moves it after 24 hours")
            assertEquals(
                listOf(RecordRenewalError, AddOrderEvent(ORDER_EVENT_CHARGE_FAILED), SetNextChargeAt(now + hour)),
                t.effects
            )
            assertFalse(t.has(RecordFailure), "failCount is unchanged by a technical failure")
            assertEquals(0, t.count<QueueMail>())
        }
    }

    @Test
    fun `a failed manual payment attempt is not a subscription failure`() {
        for (s in listOf(ACTIVE, PAST_DUE)) {
            assertEquals(
                SubscriptionStateMachine.NOT_A_SUBSCRIPTION_FAILURE,
                decide(st(s, manual), SubEvent.RenewalFailed()).ignoredReason()
            )
        }
    }

    @Test
    fun `a GATEWAY decline enters grace without scheduling a charge, the gateway retries on its own`() {
        val t = decide(st(ACTIVE, gateway, currentPeriodEnd = now - hour), SubEvent.RenewalFailed(attempts = 2)).moved()
        assertEquals(SubRule.S4, t.step().rule)
        assertEquals(
            listOf(
                RecordRenewalError, RecordFailure, AddOrderEvent(ORDER_EVENT_CHARGE_FAILED),
                SetGraceEndsAt(now + 3 * day),
                QueueMail(MAIL_PAYMENT_FAILED, MAIL_REASON_CHARGE_FAILED),
                AddOrderEvent(ORDER_EVENT_PAST_DUE)
            ),
            t.effects
        )
        assertEquals(0, t.count<SetNextChargeAt>())
        // Already PAST_DUE: only the failure is recorded.
        val past = decide(st(PAST_DUE, gateway, graceEndsAt = now + day), SubEvent.RenewalFailed()).moved()
        assertEquals(SubRule.FAILURE_RECORDED, past.step().rule)
        assertEquals(listOf(RecordRenewalError, RecordFailure, AddOrderEvent(ORDER_EVENT_CHARGE_FAILED)), past.effects)
    }

    @Test
    fun `a final GATEWAY failure after the period end goes through S4 and then S6 at once`() {
        val t = decide(
            st(ACTIVE, gateway, currentPeriodEnd = now - hour, gatewaySubscriptionId = "sub_1"),
            SubEvent.RenewalFailed(final = true)
        ).moved()
        assertEquals(listOf(SubRule.S4, SubRule.S6), t.rules)
        assertEquals(ACTIVE, t.steps[0].from)
        assertEquals(PAST_DUE, t.steps[0].to)
        assertEquals(PAST_DUE, t.steps[1].from)
        assertEquals(EXPIRED, t.steps[1].to)
        assertEquals(EXPIRED, t.to)
        val end = t.one<EndSubscription>()
        assertEquals(EndSubscription(EXPIRED, SubscriptionEndReason.PAYMENT_FAILED, RenewalDisposition.FAILED), end)
        assertTrue(t.has(QueueRemoteCancel), "09 section 10.3: S6 on a GATEWAY row queues the remote cancel")

        // From PAST_DUE it is S6 alone (the failure is still recorded).
        val past = decide(st(PAST_DUE, gateway, currentPeriodEnd = now - day, graceEndsAt = now + day), SubEvent.RenewalFailed(final = true)).moved()
        assertEquals(listOf(SubRule.S6), past.rules)
        assertTrue(past.has(RecordFailure))
    }

    @Test
    fun `a final GATEWAY failure inside the paid period schedules the end, the buyer keeps access`() {
        val t = decide(st(ACTIVE, gateway, currentPeriodEnd = now + 5 * day), SubEvent.RenewalFailed(final = true)).moved()
        assertEquals(listOf(SubRule.S8), t.rules)
        assertEquals(ACTIVE, t.to)
        assertEquals(SubscriptionEndReason.PAYMENT_FAILED, t.one<SetEndReason>().reason)
        assertEquals(SetCancelAtPeriodEnd(true), t.one<SetCancelAtPeriodEnd>())
        assertTrue(t.has(RecordFailure))
        assertEquals(0, t.count<QueueRemoteCancel>(), "the gateway gave up, there is nothing to cancel")

        // Already scheduled: only the failure is recorded.
        val again = decide(st(ACTIVE, gateway, currentPeriodEnd = now + 5 * day, cancelAtPeriodEnd = true), SubEvent.RenewalFailed(final = true)).moved()
        assertEquals(listOf(SubRule.FAILURE_RECORDED), again.rules)
    }

    @Test
    fun `renewal failures are ignored where they cannot apply`() {
        assertEquals("NOT_ACTIVATED", decide(st(PENDING), SubEvent.RenewalFailed()).ignoredReason())
        assertEquals("NOT_APPLICABLE", decide(st(PAUSED, gateway), SubEvent.RenewalFailed()).ignoredReason())
        assertEquals("SUBSCRIPTION_CLOSED", decide(st(CANCELLED), SubEvent.RenewalFailed()).ignoredReason())
        assertEquals("NOT_APPLICABLE", decide(st(ACTIVE, gateway), SubEvent.RenewalFailed(technical = true)).ignoredReason())
    }

    // ================================================================ S5 and the renewal (09 section 8.4)

    @Test
    fun `S5 a paid renewal returns PAST_DUE and PAUSED rows to ACTIVE`() {
        for (s in listOf(PAST_DUE, PAUSED)) {
            val t = decide(st(s, merchant, graceEndsAt = now + day), SubEvent.RenewalPaid()).moved()
            val step = t.step()
            assertEquals(SubRule.S5, step.rule, "$s")
            assertEquals(s, step.from)
            assertEquals(ACTIVE, step.to)
            assertEquals(
                listOf(
                    ApplyRenewal(merchant, scheduleCharge = true, scheduleQuery = false),
                    QueueRenewDeliveries, AddOrderEvent(ORDER_EVENT_RENEWED), QueueWebhook(WEBHOOK_RENEWED)
                ),
                step.effects
            )
        }
    }

    @Test
    fun `a paid renewal on an ACTIVE row is the RENEWED step, status unchanged`() {
        val t = decide(st(ACTIVE, manual), SubEvent.RenewalPaid()).moved()
        assertEquals(SubRule.RENEWED, t.step().rule)
        assertEquals(ACTIVE, t.step().from)
        assertEquals(ACTIVE, t.to)
        assertEquals(ApplyRenewal(manual, scheduleCharge = false, scheduleQuery = false), t.one<ApplyRenewal>())
    }

    @Test
    fun `the next charge is scheduled only for an open MERCHANT plan that is not being cancelled`() {
        fun apply(sub: SubState, event: SubEvent.RenewalPaid = SubEvent.RenewalPaid()) = decide(sub, event).moved().one<ApplyRenewal>()

        assertEquals(ApplyRenewal(merchant, true, false), apply(st(ACTIVE, merchant, cycleCount = 1, maxCycles = null)))
        assertEquals(ApplyRenewal(merchant, true, false), apply(st(ACTIVE, merchant, cycleCount = 1, maxCycles = 3)))
        // cycleCount 2 of 3: this payment is period 3, the last one: no further charge (09 section 15 item 6).
        assertEquals(ApplyRenewal(merchant, false, false), apply(st(ACTIVE, merchant, cycleCount = 2, maxCycles = 3)))
        assertEquals(ApplyRenewal(merchant, false, false), apply(st(ACTIVE, merchant, cycleCount = 3, maxCycles = 3)), "past the maximum too")
        assertEquals(ApplyRenewal(merchant, false, false), apply(st(ACTIVE, merchant, cancelAtPeriodEnd = true)))
        // GATEWAY rows are polled, never charged.
        assertEquals(ApplyRenewal(gateway, false, true), apply(st(ACTIVE, gateway)))
        assertEquals(ApplyRenewal(manual, false, false), apply(st(ACTIVE, manual)))
    }

    @Test
    fun `a renewal that changes the mode schedules for the new mode`() {
        // A buyer pays the pending renewal order by hand with a new card.
        assertEquals(
            ApplyRenewal(merchant, true, false),
            decide(st(PAST_DUE, manual), SubEvent.RenewalPaid(modeAfter = merchant)).moved().one<ApplyRenewal>()
        )
        // The stored method did not come back.
        assertEquals(
            ApplyRenewal(manual, false, false),
            decide(st(PAST_DUE, merchant), SubEvent.RenewalPaid(modeAfter = manual)).moved().one<ApplyRenewal>()
        )
    }

    @Test
    fun `a renewal after a cancel at period end is recorded and the gateway is told to stop again`() {
        // 09 section 15 item 5.
        val t = decide(st(ACTIVE, gateway, cancelAtPeriodEnd = true, endReason = SubscriptionEndReason.BUYER_CANCEL), SubEvent.RenewalPaid()).moved()
        assertEquals(ACTIVE, t.to)
        assertTrue(t.has(QueueRemoteCancel))
        assertEquals(ApplyRenewal(gateway, false, true), t.one<ApplyRenewal>())
        assertFalse(decide(st(ACTIVE, merchant, cancelAtPeriodEnd = true), SubEvent.RenewalPaid()).moved().has(QueueRemoteCancel))
        assertFalse(decide(st(ACTIVE, gateway), SubEvent.RenewalPaid()).moved().has(QueueRemoteCancel))
    }

    @Test
    fun `a paid renewal needs an activated and not yet closed row`() {
        assertEquals("NOT_ACTIVATED", decide(st(PENDING), SubEvent.RenewalPaid()).ignoredReason())
        for (s in listOf(CANCELLED, EXPIRED, COMPLETED)) {
            assertEquals("SUBSCRIPTION_CLOSED", decide(st(s), SubEvent.RenewalPaid()).ignoredReason(), "$s: 09 section 8.5 diverts it to REVIEW before")
        }
    }

    // ================================================================ S6: grace over (09 section 9.4)

    @Test
    fun `S6 expires a PAST_DUE row at the grace end with PAYMENT_FAILED`() {
        val sub = st(PAST_DUE, merchant, graceEndsAt = now)
        val t = decide(sub, SubEvent.GraceOver()).moved()
        assertEquals(SubRule.S6, t.step().rule)
        assertEquals(PAST_DUE, t.step().from)
        assertEquals(EXPIRED, t.to)
        assertEquals(
            listOf<SubEffect>(EndSubscription(EXPIRED, SubscriptionEndReason.PAYMENT_FAILED, RenewalDisposition.FAILED)),
            t.effects
        )
    }

    @Test
    fun `S6 uses PROVIDER_UNAVAILABLE when the last failure was technical or the provider was gone`() {
        val t = decide(st(PAST_DUE, merchant, graceEndsAt = now - 1), SubEvent.GraceOver(providerUnavailable = true)).moved()
        assertEquals(SubscriptionEndReason.PROVIDER_UNAVAILABLE, t.one<EndSubscription>().reason)
    }

    @Test
    fun `S6 is not due before the grace end`() {
        assertEquals("NOT_DUE", decide(st(PAST_DUE, merchant, graceEndsAt = now + 1), SubEvent.GraceOver()).ignoredReason())
        assertEquals("NOT_DUE", decide(st(PAST_DUE, merchant, graceEndsAt = now + 1), SubEvent.GraceOver(attemptProcessing = true)).ignoredReason())
    }

    @Test
    fun `S6 waits for a processing attempt or a scheduled last retry, at most seven days`() {
        val graceEnds = now - day
        // an attempt in PROCESSING (bank transfer notified, asynchronous debit)
        val processing = st(PAST_DUE, manual, graceEndsAt = graceEnds)
        assertEquals("WAITING_FOR_PAYMENT", decide(processing, SubEvent.GraceOver(attemptProcessing = true)).ignoredReason())
        // a scheduled last retry that runs in step A of the same tick
        val lastRetry = st(PAST_DUE, merchant, graceEndsAt = graceEnds, nextChargeAt = graceEnds)
        assertEquals("WAITING_FOR_PAYMENT", decide(lastRetry, SubEvent.GraceOver()).ignoredReason())
        // a retry after the grace end does not hold the expiry back
        val laterRetry = st(PAST_DUE, merchant, graceEndsAt = graceEnds, nextChargeAt = graceEnds + 1)
        assertEquals(EXPIRED, decide(laterRetry, SubEvent.GraceOver()).moved().to)
        // the cap: graceEndsAt + 7 days
        val cap = SubscriptionTimings.PROCESSING_WAIT_CAP_MS
        val atCapMinus = decide(processing, SubEvent.GraceOver(attemptProcessing = true), at = graceEnds + cap - 1)
        assertEquals("WAITING_FOR_PAYMENT", atCapMinus.ignoredReason())
        assertEquals(EXPIRED, decide(processing, SubEvent.GraceOver(attemptProcessing = true), at = graceEnds + cap).moved().to)
    }

    @Test
    fun `S6 on a GATEWAY row queues the remote cancel, on other modes it does not`() {
        assertTrue(decide(st(PAST_DUE, gateway, graceEndsAt = now), SubEvent.GraceOver()).moved().has(QueueRemoteCancel))
        assertFalse(decide(st(PAST_DUE, merchant, graceEndsAt = now), SubEvent.GraceOver()).moved().has(QueueRemoteCancel))
        assertFalse(decide(st(PAST_DUE, manual, graceEndsAt = now), SubEvent.GraceOver()).moved().has(QueueRemoteCancel))
    }

    @Test
    fun `S6 on a PAST_DUE row without a grace end expires it instead of leaving it open forever`() {
        val t = decide(st(PAST_DUE, merchant, graceEndsAt = null), SubEvent.GraceOver(attemptProcessing = true)).moved()
        assertEquals(EXPIRED, t.to)
    }

    @Test
    fun `step D only concerns PAST_DUE rows`() {
        assertEquals("NOT_PAST_DUE", decide(st(ACTIVE, graceEndsAt = now - 1), SubEvent.GraceOver()).ignoredReason())
        assertEquals("NOT_PAST_DUE", decide(st(PAUSED, graceEndsAt = now - 1), SubEvent.GraceOver()).ignoredReason())
        assertEquals("NOT_ACTIVATED", decide(st(PENDING), SubEvent.GraceOver()).ignoredReason())
    }

    // ================================================================ S7: immediate end

    @Test
    fun `S7 an immediate buyer cancel ends an ACTIVE row without a remote queue`() {
        val t = decide(st(ACTIVE, merchant), SubEvent.CancelRequested(CancelActor.BUYER, atPeriodEnd = false)).moved()
        assertEquals(SubRule.S7, t.step().rule)
        assertEquals(ACTIVE, t.step().from)
        assertEquals(CANCELLED, t.to)
        assertEquals(
            listOf(
                AddOrderEvent(ORDER_EVENT_CANCEL_REQUESTED),
                QueueWebhook(WEBHOOK_CANCELLED),
                EndSubscription(CANCELLED, SubscriptionEndReason.BUYER_CANCEL, RenewalDisposition.SKIPPED)
            ),
            t.effects
        )
        // An admin cancel only differs in the reason.
        val admin = decide(st(ACTIVE, merchant), SubEvent.CancelRequested(CancelActor.ADMIN, atPeriodEnd = false)).moved()
        assertEquals(SubscriptionEndReason.ADMIN_CANCEL, admin.one<EndSubscription>().reason)
        // The gateway cancel of a GATEWAY row is the service's remote call, not the queue.
        assertFalse(decide(st(ACTIVE, gateway), SubEvent.CancelRequested(CancelActor.BUYER, false)).moved().has(QueueRemoteCancel))
    }

    @Test
    fun `S7 cancel on PAST_DUE and PAUSED is always immediate, whatever atPeriodEnd says`() {
        for (s in listOf(PAST_DUE, PAUSED)) for (atEnd in listOf(true, false)) {
            val t = decide(st(s, merchant, graceEndsAt = now + day), SubEvent.CancelRequested(CancelActor.BUYER, atPeriodEnd = atEnd)).moved()
            assertEquals(listOf(SubRule.S7), t.rules, "$s atPeriodEnd=$atEnd")
            assertEquals(CANCELLED, t.to)
        }
    }

    @Test
    fun `S7 cancel on a row that already has the cancel scheduled does not fire the cancelled webhook twice`() {
        val scheduled = st(ACTIVE, merchant, cancelAtPeriodEnd = true, cancelRequestedAt = now - day, endReason = SubscriptionEndReason.BUYER_CANCEL)
        val t = decide(scheduled, SubEvent.CancelRequested(CancelActor.BUYER, atPeriodEnd = false)).moved()
        assertEquals(listOf(SubRule.S7), t.rules)
        assertEquals(0, t.count<QueueWebhook>(), "S8 fired it before")
    }

    @Test
    fun `S7 refund of the current period order ends the row with REFUND and leaves the undo to the refund flow`() {
        for (s in listOf(ACTIVE, PAST_DUE, PAUSED)) {
            val t = decide(st(s, gateway), SubEvent.Refunded).moved()
            assertEquals(listOf(SubRule.S7), t.rules, "$s")
            assertEquals(
                EndSubscription(CANCELLED, SubscriptionEndReason.REFUND, RenewalDisposition.SKIPPED, undoHandledByCaller = true),
                t.one<EndSubscription>()
            )
            assertTrue(t.has(QueueRemoteCancel), "a refunded GATEWAY subscription must stop billing")
            assertEquals(0, t.count<QueueWebhook>(), "subscription.expired is part of the ending; refund fires no cancelled webhook")
        }
        assertFalse(decide(st(ACTIVE, merchant), SubEvent.Refunded).moved().has(QueueRemoteCancel))
        assertFalse(decide(st(ACTIVE, manual), SubEvent.Refunded).moved().has(QueueRemoteCancel))
    }

    @Test
    fun `S7 chargeback ends the row with CHARGEBACK, REVOKE or EXPIRE deliveries per revokeOnChargeback`() {
        val revoke = decide(st(ACTIVE, gateway), SubEvent.Chargeback(revokeOnChargeback = true)).moved()
        assertEquals(
            EndSubscription(CANCELLED, SubscriptionEndReason.CHARGEBACK, RenewalDisposition.SKIPPED, undoHandledByCaller = true),
            revoke.one<EndSubscription>()
        )
        assertTrue(revoke.has(QueueRemoteCancel))
        val expire = decide(st(ACTIVE, gateway), SubEvent.Chargeback(revokeOnChargeback = false)).moved()
        assertEquals(
            EndSubscription(CANCELLED, SubscriptionEndReason.CHARGEBACK, RenewalDisposition.SKIPPED, undoHandledByCaller = false),
            expire.one<EndSubscription>()
        )
    }

    @Test
    fun `refund and chargeback do not touch a PENDING or closed row`() {
        for (event in listOf(SubEvent.Refunded, SubEvent.Chargeback())) {
            assertEquals("NOT_ACTIVATED", decide(st(PENDING), event).ignoredReason())
            for (s in listOf(CANCELLED, EXPIRED, COMPLETED)) assertEquals("SUBSCRIPTION_CLOSED", decide(st(s), event).ignoredReason())
        }
    }

    @Test
    fun `S7 user deletion ends every open row without a mail and clears the personal data`() {
        for (s in listOf(ACTIVE, PAST_DUE, PAUSED)) {
            val t = decide(st(s, gateway), SubEvent.UserDeleted).moved()
            assertEquals(listOf(SubRule.S7), t.rules, "$s")
            assertEquals(
                EndSubscription(CANCELLED, SubscriptionEndReason.ADMIN_CANCEL, RenewalDisposition.SKIPPED, sendEndedMail = false, clearPii = true),
                t.one<EndSubscription>()
            )
            assertTrue(t.has(QueueRemoteCancel))
            assertEquals(0, t.count<QueueWebhook>())
        }
        // A PENDING row is S3 and clears the personal data too.
        val pending = decide(st(PENDING, manual), SubEvent.UserDeleted).moved()
        assertEquals(listOf(SubRule.S3), pending.rules)
        assertEquals(ClosePending(SubscriptionEndReason.ADMIN_CANCEL, clearPii = true), pending.one<ClosePending>())
        // Terminal rows are the service's job (scrub without a state change).
        for (s in listOf(CANCELLED, EXPIRED, COMPLETED)) assertEquals("SUBSCRIPTION_CLOSED", decide(st(s), SubEvent.UserDeleted).ignoredReason())
    }

    @Test
    fun `a blocked buyer ends at the period end when ACTIVE and at once when PAST_DUE or PAUSED`() {
        val active = decide(st(ACTIVE, merchant), SubEvent.BuyerBlocked).moved()
        assertEquals(listOf(SubRule.S8), active.rules)
        assertEquals(ACTIVE, active.to)
        assertEquals(SubscriptionEndReason.ADMIN_CANCEL, active.one<SetEndReason>().reason)
        assertEquals(SetCancelRequestedAt(now), active.one<SetCancelRequestedAt>())
        assertFalse(active.has(QueueRemoteCancel))
        assertTrue(decide(st(ACTIVE, gateway), SubEvent.BuyerBlocked).moved().has(QueueRemoteCancel), "the gateway must stop billing a blocked buyer")
        assertEquals("ALREADY_CANCEL_SCHEDULED", decide(st(ACTIVE, merchant, cancelAtPeriodEnd = true), SubEvent.BuyerBlocked).ignoredReason())

        for (s in listOf(PAST_DUE, PAUSED)) {
            val t = decide(st(s, gateway), SubEvent.BuyerBlocked).moved()
            assertEquals(listOf(SubRule.S7), t.rules, "$s")
            assertEquals(SubscriptionEndReason.ADMIN_CANCEL, t.one<EndSubscription>().reason)
            assertTrue(t.has(QueueRemoteCancel))
        }
        assertEquals("NOT_ACTIVATED", decide(st(PENDING), SubEvent.BuyerBlocked).ignoredReason())
        for (s in listOf(CANCELLED, EXPIRED, COMPLETED)) assertEquals("SUBSCRIPTION_CLOSED", decide(st(s), SubEvent.BuyerBlocked).ignoredReason())
    }

    // ================================================================ S8: cancel at period end

    @Test
    fun `S8 cancel at period end keeps the row ACTIVE and records the request`() {
        val t = decide(st(ACTIVE, merchant, nextChargeAt = now + 10 * day), SubEvent.CancelRequested(CancelActor.BUYER, atPeriodEnd = true)).moved()
        val step = t.step()
        assertEquals(SubRule.S8, step.rule)
        assertEquals(ACTIVE, step.from)
        assertEquals(ACTIVE, step.to)
        assertEquals(
            listOf(
                SetCancelAtPeriodEnd(true),
                SetCancelRequestedAt(now),
                SetEndReason(SubscriptionEndReason.BUYER_CANCEL),
                SetNextChargeAt(null),
                QueueMail(MAIL_CANCELLED),
                QueueWebhook(WEBHOOK_CANCELLED),
                AddOrderEvent(ORDER_EVENT_CANCEL_REQUESTED)
            ),
            step.effects
        )
        assertEquals("SUBSCRIPTION_CANCELLED", MAIL_CANCELLED)
    }

    @Test
    fun `S8 keeps the earliest request time and honours a remote confirmation and an admin actor`() {
        val sub = st(ACTIVE, gateway, cancelRequestedAt = now - 2 * hour)
        val t = decide(sub, SubEvent.CancelRequested(CancelActor.ADMIN, atPeriodEnd = true, remoteConfirmed = true)).moved()
        assertEquals(SetCancelRequestedAt(now - 2 * hour), t.one<SetCancelRequestedAt>(), "tx1 wrote the request time before the remote call")
        assertEquals(SubscriptionEndReason.ADMIN_CANCEL, t.one<SetEndReason>().reason)
        assertTrue(t.has(MarkRemoteCancelDone))
        assertFalse(decide(sub, SubEvent.CancelRequested(CancelActor.ADMIN, true, remoteConfirmed = false)).moved().has(MarkRemoteCancelDone))
    }

    @Test
    fun `S8 on a MANUAL row also skips the pending renewal order`() {
        assertTrue(decide(st(ACTIVE, manual), SubEvent.CancelRequested(CancelActor.BUYER, true)).moved().has(SkipPendingRenewal))
        assertFalse(decide(st(ACTIVE, merchant), SubEvent.CancelRequested(CancelActor.BUYER, true)).moved().has(SkipPendingRenewal))
        assertFalse(decide(st(ACTIVE, gateway), SubEvent.CancelRequested(CancelActor.BUYER, true)).moved().has(SkipPendingRenewal))
    }

    @Test
    fun `cancel at period end twice is idempotent, and cancel is refused where nothing can be cancelled`() {
        val scheduled = st(ACTIVE, merchant, cancelAtPeriodEnd = true)
        assertEquals("ALREADY_CANCEL_SCHEDULED", decide(scheduled, SubEvent.CancelRequested(CancelActor.BUYER, true)).ignoredReason())
        for (s in listOf(PENDING, EXPIRED, CANCELLED, COMPLETED)) {
            assertEquals("SUBSCRIPTION_NOT_CANCELLABLE", decide(st(s), SubEvent.CancelRequested(CancelActor.BUYER, true)).ignoredReason(), "$s")
            assertEquals("SUBSCRIPTION_NOT_CANCELLABLE", decide(st(s), SubEvent.CancelRequested(CancelActor.ADMIN, false)).ignoredReason(), "$s")
        }
    }

    // ================================================================ S9, S13, S14: step C (09 section 11)

    @Test
    fun `S9 ends a scheduled cancel at the period end and not before`() {
        val end = now
        val sub = st(ACTIVE, merchant, currentPeriodEnd = end, cancelAtPeriodEnd = true, endReason = SubscriptionEndReason.BUYER_CANCEL)
        val t = decide(sub, SubEvent.PeriodOver()).moved()
        assertEquals(SubRule.S9, t.step().rule)
        assertEquals(CANCELLED, t.to)
        assertEquals(
            listOf<SubEffect>(EndSubscription(CANCELLED, SubscriptionEndReason.BUYER_CANCEL, RenewalDisposition.SKIPPED)),
            t.effects
        )
        assertEquals("NOT_DUE", decide(sub.copy(currentPeriodEnd = end + 1), SubEvent.PeriodOver()).ignoredReason(), "one millisecond early")
        assertEquals("NOT_DUE", decide(sub.copy(currentPeriodEnd = null), SubEvent.PeriodOver()).ignoredReason())
        // The remote cancel of a gateway row was already done by S8 / the cancel flow: not queued again.
        assertFalse(decide(sub.copy(mode = gateway), SubEvent.PeriodOver()).moved().has(QueueRemoteCancel))
    }

    @Test
    fun `S9 keeps the provisional end reason and falls back to GATEWAY_ENDED when there is none`() {
        val admin = st(ACTIVE, manual, currentPeriodEnd = now, cancelAtPeriodEnd = true, endReason = SubscriptionEndReason.PAYMENT_FAILED)
        assertEquals(SubscriptionEndReason.PAYMENT_FAILED, decide(admin, SubEvent.PeriodOver()).moved().one<EndSubscription>().reason)
        val none = admin.copy(endReason = null)
        assertEquals(SubscriptionEndReason.GATEWAY_ENDED, decide(none, SubEvent.PeriodOver()).moved().one<EndSubscription>().reason)
    }

    @Test
    fun `S13 a paused row whose period is over is cancelled with GATEWAY_ENDED and a remote cancel`() {
        val t = decide(st(PAUSED, gateway, currentPeriodEnd = now), SubEvent.PeriodOver()).moved()
        assertEquals(SubRule.S13, t.step().rule)
        assertEquals(PAUSED, t.step().from)
        assertEquals(CANCELLED, t.to)
        assertEquals(EndSubscription(CANCELLED, SubscriptionEndReason.GATEWAY_ENDED, RenewalDisposition.SKIPPED), t.one<EndSubscription>())
        assertTrue(t.has(QueueRemoteCancel))
        assertEquals("NOT_DUE", decide(st(PAUSED, gateway, currentPeriodEnd = now + 1), SubEvent.PeriodOver()).ignoredReason())
    }

    @Test
    fun `S14 completes a finite plan at the end of its last period`() {
        for (cycles in listOf(3, 4)) { // equal, and beyond the maximum (gateway renewals are accepted past it)
            val t = decide(st(ACTIVE, merchant, cycleCount = cycles, maxCycles = 3, currentPeriodEnd = now), SubEvent.PeriodOver()).moved()
            assertEquals(SubRule.S14, t.step().rule, "cycleCount=$cycles")
            assertEquals(COMPLETED, t.to)
            assertEquals(EndSubscription(COMPLETED, SubscriptionEndReason.COMPLETED, RenewalDisposition.SKIPPED), t.one<EndSubscription>())
        }
        // Not yet: one period left.
        val notYet = decide(st(ACTIVE, manual, cycleCount = 2, maxCycles = 3, currentPeriodEnd = now), SubEvent.PeriodOver()).moved()
        assertEquals(SubRule.S4, notYet.step().rule)
        // An open plan never completes.
        assertEquals(SubRule.S4, decide(st(ACTIVE, manual, cycleCount = 99, maxCycles = null, currentPeriodEnd = now), SubEvent.PeriodOver()).moved().step().rule)
    }

    @Test
    fun `step C takes the first match in the documented order`() {
        // cancelAtPeriodEnd beats PAUSED beats the finite end.
        val all = st(PAUSED, gateway, cycleCount = 3, maxCycles = 3, currentPeriodEnd = now, cancelAtPeriodEnd = true, endReason = SubscriptionEndReason.BUYER_CANCEL)
        assertEquals(SubRule.S9, decide(all, SubEvent.PeriodOver()).moved().step().rule)
        assertEquals(SubRule.S13, decide(all.copy(cancelAtPeriodEnd = false), SubEvent.PeriodOver()).moved().step().rule)
        assertEquals(SubRule.S14, decide(all.copy(cancelAtPeriodEnd = false, status = ACTIVE), SubEvent.PeriodOver()).moved().step().rule)
    }

    @Test
    fun `step C on a MANUAL row is S4 with the NOT_RENEWED reason`() {
        val t = decide(st(ACTIVE, manual, currentPeriodEnd = now), SubEvent.PeriodOver()).moved()
        assertEquals(SubRule.S4, t.step().rule)
        assertEquals(PAST_DUE, t.to)
        assertEquals(
            listOf(
                SetGraceEndsAt(now + 3 * day),
                QueueMail(MAIL_PAYMENT_FAILED, MAIL_REASON_NOT_RENEWED),
                AddOrderEvent(ORDER_EVENT_PAST_DUE)
            ),
            t.effects
        )
    }

    @Test
    fun `step C leaves MERCHANT and GATEWAY rows alone for 24 hours after the period end`() {
        val end = now - 23 * hour
        for (mode in listOf(merchant, gateway)) {
            assertEquals("RENEWAL_SLACK", decide(st(ACTIVE, mode, currentPeriodEnd = end), SubEvent.PeriodOver()).ignoredReason(), "$mode at 23 h")
            // exactly 24 h is the first moment past the slack
            assertEquals("RENEWAL_SLACK", decide(st(ACTIVE, mode, currentPeriodEnd = now - 24 * hour + 1), SubEvent.PeriodOver()).ignoredReason())
        }
        assertEquals(SubRule.S4, decide(st(ACTIVE, merchant, currentPeriodEnd = now - 24 * hour, nextChargeAt = now + day), SubEvent.PeriodOver()).moved().step().rule)
    }

    @Test
    fun `step C on a GATEWAY row polls first, then S4, and skips an unavailable provider`() {
        val sub = st(ACTIVE, gateway, currentPeriodEnd = now - 25 * hour)
        assertSame(SubTransition.PollFirst, decide(sub, SubEvent.PeriodOver()))
        val after = decide(sub, SubEvent.PeriodOver(polled = true)).moved()
        assertEquals(SubRule.S4, after.step().rule)
        assertEquals(QueueMail(MAIL_PAYMENT_FAILED, MAIL_REASON_NOT_RENEWED), after.one<QueueMail>())
        assertEquals("PROVIDER_UNAVAILABLE", decide(sub, SubEvent.PeriodOver(providerUnavailable = true)).ignoredReason())
        assertEquals("PROVIDER_UNAVAILABLE", decide(sub, SubEvent.PeriodOver(providerUnavailable = true, polled = true)).ignoredReason())
    }

    @Test
    fun `step C on a MERCHANT row keeps the schedule or charges now when it was lost and nothing is in flight`() {
        val overdue = now - 25 * hour
        val kept = decide(st(ACTIVE, merchant, currentPeriodEnd = overdue, nextChargeAt = now + day), SubEvent.PeriodOver()).moved()
        assertEquals(SubRule.S4, kept.step().rule)
        assertEquals(0, kept.count<SetNextChargeAt>(), "keeps nextChargeAt")
        assertEquals(
            SetNextChargeAt(now),
            decide(st(ACTIVE, merchant, currentPeriodEnd = overdue, nextChargeAt = null), SubEvent.PeriodOver()).moved().one<SetNextChargeAt>()
        )
        assertEquals(
            0,
            decide(st(ACTIVE, merchant, currentPeriodEnd = overdue, nextChargeAt = null), SubEvent.PeriodOver(attemptInFlight = true)).moved().count<SetNextChargeAt>()
        )
    }

    @Test
    fun `step C ignores rows it does not own`() {
        assertEquals("NOT_ACTIVATED", decide(st(PENDING, currentPeriodEnd = now - day), SubEvent.PeriodOver()).ignoredReason())
        assertEquals("NOT_APPLICABLE", decide(st(PAST_DUE, currentPeriodEnd = now - day), SubEvent.PeriodOver()).ignoredReason())
        for (s in listOf(CANCELLED, EXPIRED, COMPLETED)) assertEquals("SUBSCRIPTION_CLOSED", decide(st(s, currentPeriodEnd = now - day), SubEvent.PeriodOver()).ignoredReason())
    }

    @Test
    fun `a clock that moved backwards fires nothing early`() {
        val sub = st(ACTIVE, manual, currentPeriodEnd = now)
        assertEquals("NOT_DUE", decide(sub, SubEvent.PeriodOver(), at = now - 1).ignoredReason())
        assertEquals("NOT_DUE", decide(sub, SubEvent.PeriodOver(), at = now - 100 * day).ignoredReason())
        assertEquals("NOT_DUE", decide(st(PAST_DUE, graceEndsAt = now), SubEvent.GraceOver(), at = now - 1).ignoredReason())
    }

    // ================================================================ S10: resume

    private val cancelled = st(
        ACTIVE, merchant, cancelAtPeriodEnd = true, cancelRequestedAt = now - day,
        endReason = SubscriptionEndReason.BUYER_CANCEL, currentPeriodEnd = now + 5 * day
    )

    @Test
    fun `S10 resume clears the scheduled cancel and re-arms a MERCHANT charge`() {
        val t = decide(cancelled, SubEvent.ResumeRequested()).moved()
        val step = t.step()
        assertEquals(SubRule.S10, step.rule)
        assertEquals(ACTIVE, step.from)
        assertEquals(ACTIVE, step.to)
        assertEquals(
            listOf(
                SetCancelAtPeriodEnd(false), SetCancelRequestedAt(null), SetEndReason(null),
                SetNextChargeAt(now + 5 * day), AddOrderEvent(ORDER_EVENT_RESUMED)
            ),
            step.effects
        )
    }

    @Test
    fun `S10 resume on a MANUAL row restores the skipped renewal, on a finished plan it does not re-arm a charge`() {
        val manualResume = decide(cancelled.copy(mode = manual), SubEvent.ResumeRequested()).moved()
        assertTrue(manualResume.has(RestoreSkippedRenewal))
        assertEquals(0, manualResume.count<SetNextChargeAt>())
        val finished = decide(cancelled.copy(cycleCount = 3, maxCycles = 3), SubEvent.ResumeRequested()).moved()
        assertEquals(0, finished.count<SetNextChargeAt>(), "the last period is paid, nothing is charged again")
        val open = decide(cancelled.copy(cycleCount = 2, maxCycles = 3), SubEvent.ResumeRequested()).moved()
        assertEquals(1, open.count<SetNextChargeAt>())
    }

    @Test
    fun `S10 resume is refused when the cancel cannot be undone by the buyer`() {
        val refused = SubscriptionStateMachine.SUBSCRIPTION_NOT_RESUMABLE
        assertEquals(refused, decide(cancelled.copy(endReason = SubscriptionEndReason.ADMIN_CANCEL), SubEvent.ResumeRequested()).ignoredReason(), "an admin decision")
        assertEquals(refused, decide(cancelled.copy(endReason = SubscriptionEndReason.PAYMENT_FAILED), SubEvent.ResumeRequested()).ignoredReason())
        assertEquals(refused, decide(cancelled.copy(endReason = null), SubEvent.ResumeRequested()).ignoredReason())
        assertEquals(refused, decide(cancelled.copy(cancelAtPeriodEnd = false), SubEvent.ResumeRequested()).ignoredReason(), "nothing scheduled")
        assertEquals(refused, decide(cancelled, SubEvent.ResumeRequested(), at = now + 5 * day).ignoredReason(), "at the period end")
        assertEquals(refused, decide(cancelled, SubEvent.ResumeRequested(), at = now + 6 * day).ignoredReason(), "after the period end")
        assertEquals(refused, decide(cancelled.copy(currentPeriodEnd = null), SubEvent.ResumeRequested()).ignoredReason())
        for (s in listOf(PENDING, PAST_DUE, PAUSED, CANCELLED, EXPIRED, COMPLETED)) {
            assertEquals(refused, decide(cancelled.copy(status = s), SubEvent.ResumeRequested()).ignoredReason(), "$s")
        }
    }

    @Test
    fun `S10 resume of a GATEWAY row needs an undone remote cancel and a provider that can resume`() {
        val gw = cancelled.copy(mode = gateway, remoteCancelState = RemoteCancelState.NONE)
        assertEquals(SubRule.S10, decide(gw, SubEvent.ResumeRequested(providerCanResume = true)).moved().step().rule)
        assertEquals("SUBSCRIPTION_NOT_RESUMABLE", decide(gw, SubEvent.ResumeRequested(providerCanResume = false)).ignoredReason(), "no recurringResume capability")
        assertEquals(
            "SUBSCRIPTION_NOT_RESUMABLE",
            decide(gw.copy(remoteCancelState = RemoteCancelState.DONE), SubEvent.ResumeRequested(providerCanResume = true)).ignoredReason(),
            "the gateway cancelled for good"
        )
        // The capability flag is only read for GATEWAY rows.
        assertEquals(SubRule.S10, decide(cancelled, SubEvent.ResumeRequested(providerCanResume = false)).moved().step().rule)
    }

    // ================================================================ S11, S12, 09 section 7 table

    private val gwActive = st(ACTIVE, gateway, currentPeriodEnd = now + 10 * day, gatewaySubscriptionId = "sub_1")
    private val gwPastDue = st(PAST_DUE, gateway, currentPeriodEnd = now - day, graceEndsAt = now + 2 * day, gatewaySubscriptionId = "sub_1")
    private val gwPaused = st(PAUSED, gateway, currentPeriodEnd = now + 10 * day, gatewaySubscriptionId = "sub_1")

    private fun gw(status: GatewaySubscriptionStatus) = SubEvent.GatewayStatus(status)

    /** The 6 x 3 table of 09 section 7: expected rules, or `null` for a no-op. */
    private val table: Map<Pair<GatewaySubscriptionStatus, SubscriptionStatus>, List<SubRule>?> = mapOf(
        (GatewaySubscriptionStatus.ACTIVE to ACTIVE) to null,
        (GatewaySubscriptionStatus.ACTIVE to PAST_DUE) to null,
        (GatewaySubscriptionStatus.ACTIVE to PAUSED) to listOf(SubRule.S12),

        (GatewaySubscriptionStatus.PAST_DUE to ACTIVE) to listOf(SubRule.S4),
        (GatewaySubscriptionStatus.PAST_DUE to PAST_DUE) to null,
        (GatewaySubscriptionStatus.PAST_DUE to PAUSED) to listOf(SubRule.S12, SubRule.S4),

        (GatewaySubscriptionStatus.PAUSED to ACTIVE) to listOf(SubRule.S11),
        (GatewaySubscriptionStatus.PAUSED to PAST_DUE) to null,
        (GatewaySubscriptionStatus.PAUSED to PAUSED) to null,

        (GatewaySubscriptionStatus.CANCEL_SCHEDULED to ACTIVE) to listOf(SubRule.S8),
        (GatewaySubscriptionStatus.CANCEL_SCHEDULED to PAST_DUE) to null,
        (GatewaySubscriptionStatus.CANCEL_SCHEDULED to PAUSED) to null,

        // CANCELLED on ACTIVE with paid time left (now < currentPeriodEnd): S8
        (GatewaySubscriptionStatus.CANCELLED to ACTIVE) to listOf(SubRule.S8),
        (GatewaySubscriptionStatus.CANCELLED to PAST_DUE) to listOf(SubRule.S7),
        (GatewaySubscriptionStatus.CANCELLED to PAUSED) to listOf(SubRule.S7),

        // ENDED on ACTIVE (not a finished plan): as CANCELLED
        (GatewaySubscriptionStatus.ENDED to ACTIVE) to listOf(SubRule.S8),
        (GatewaySubscriptionStatus.ENDED to PAST_DUE) to listOf(SubRule.S6),
        (GatewaySubscriptionStatus.ENDED to PAUSED) to listOf(SubRule.S7)
    )

    @Test
    fun `the gateway status table of 09 section 7, all 6 x 3 cells`() {
        assertEquals(18, table.size)
        val local = mapOf(ACTIVE to gwActive, PAST_DUE to gwPastDue, PAUSED to gwPaused)
        for ((cell, expected) in table) {
            val (gwStatus, localStatus) = cell
            val t = decide(local.getValue(localStatus), gw(gwStatus))
            if (expected == null) {
                assertTrue(t is SubTransition.Ignored, "gateway $gwStatus on $localStatus must be a no-op but was $t")
            } else {
                assertEquals(expected, t.moved().rules, "gateway $gwStatus on $localStatus")
            }
        }
    }

    @Test
    fun `a status event never moves the period or applies a renewal`() {
        // 09 section 7: only a paid renewal moves the period.
        val local = listOf(gwActive, gwPastDue, gwPaused, gwActive.copy(cancelAtPeriodEnd = true), gwActive.copy(status = CANCELLED))
        for (sub in local) for (status in GatewaySubscriptionStatus.values()) {
            val t = decide(sub, gw(status))
            if (t is SubTransition.Apply) {
                assertEquals(0, t.count<ApplyRenewal>(), "$status on ${sub.status}")
                assertEquals(0, t.count<ActivateFromPayment>())
                assertEquals(0, t.count<QueueRenewDeliveries>())
            }
        }
    }

    @Test
    fun `S11 and S12 follow the gateway pause`() {
        val pause = decide(gwActive, gw(GatewaySubscriptionStatus.PAUSED)).moved()
        assertEquals(SubRule.S11, pause.step().rule)
        assertEquals(ACTIVE, pause.step().from)
        assertEquals(PAUSED, pause.to)
        assertEquals(listOf<SubEffect>(SetNextChargeAt(null)), pause.effects)

        val resume = decide(gwPaused, gw(GatewaySubscriptionStatus.ACTIVE)).moved()
        assertEquals(SubRule.S12, resume.step().rule)
        assertEquals(PAUSED, resume.step().from)
        assertEquals(ACTIVE, resume.to)
    }

    @Test
    fun `gateway PAST_DUE on a paused row is S12 then S4 with a continuous chain`() {
        val t = decide(gwPaused, gw(GatewaySubscriptionStatus.PAST_DUE)).moved()
        assertEquals(listOf(SubRule.S12, SubRule.S4), t.rules)
        assertEquals(PAUSED, t.steps[0].from)
        assertEquals(ACTIVE, t.steps[0].to)
        assertEquals(ACTIVE, t.steps[1].from)
        assertEquals(PAST_DUE, t.steps[1].to)
        assertEquals(PAST_DUE, t.to)
        assertEquals(QueueMail(MAIL_PAYMENT_FAILED, MAIL_REASON_CHARGE_FAILED), t.one<QueueMail>())
        assertTrue(t.has(RecordFailure))
    }

    @Test
    fun `gateway ACTIVE after CANCEL_SCHEDULED is S10 unless the remote cancel is done`() {
        val scheduled = gwActive.copy(cancelAtPeriodEnd = true, endReason = SubscriptionEndReason.BUYER_CANCEL, cancelRequestedAt = now - day)
        assertEquals(listOf(SubRule.S10), decide(scheduled, gw(GatewaySubscriptionStatus.ACTIVE)).moved().rules)
        assertEquals(
            SubscriptionStateMachine.NO_CHANGE,
            decide(scheduled.copy(remoteCancelState = RemoteCancelState.DONE), gw(GatewaySubscriptionStatus.ACTIVE)).ignoredReason()
        )
        // S10 by the gateway does not need the buyer's reason or the resume capability.
        val adminScheduled = scheduled.copy(endReason = SubscriptionEndReason.ADMIN_CANCEL)
        assertEquals(listOf(SubRule.S10), decide(adminScheduled, gw(GatewaySubscriptionStatus.ACTIVE)).moved().rules)
    }

    @Test
    fun `gateway CANCEL_SCHEDULED is S8 with BUYER_CANCEL unless a reason is already set, and idempotent`() {
        val t = decide(gwActive, gw(GatewaySubscriptionStatus.CANCEL_SCHEDULED)).moved()
        assertEquals(SubscriptionEndReason.BUYER_CANCEL, t.one<SetEndReason>().reason)
        assertFalse(t.has(MarkRemoteCancelDone), "the gateway only scheduled it")
        assertFalse(t.has(QueueRemoteCancel))
        val admin = decide(gwActive.copy(endReason = SubscriptionEndReason.ADMIN_CANCEL), gw(GatewaySubscriptionStatus.CANCEL_SCHEDULED)).moved()
        assertEquals(SubscriptionEndReason.ADMIN_CANCEL, admin.one<SetEndReason>().reason)
        assertEquals(
            "ALREADY_CANCEL_SCHEDULED",
            decide(gwActive.copy(cancelAtPeriodEnd = true), gw(GatewaySubscriptionStatus.CANCEL_SCHEDULED)).ignoredReason()
        )
    }

    @Test
    fun `gateway CANCELLED with paid time left is S8 and the remote cancel is done, without a queue`() {
        val t = decide(gwActive, gw(GatewaySubscriptionStatus.CANCELLED)).moved()
        assertEquals(listOf(SubRule.S8), t.rules)
        assertEquals(ACTIVE, t.to)
        assertTrue(t.has(MarkRemoteCancelDone))
        assertFalse(t.has(QueueRemoteCancel))
        assertEquals(SubscriptionEndReason.GATEWAY_ENDED, t.one<SetEndReason>().reason, "no cancel was requested here")
    }

    @Test
    fun `gateway CANCELLED keeps the end reason of a cancel requested here`() {
        val requested = gwActive.copy(cancelRequestedAt = now - hour, endReason = SubscriptionEndReason.BUYER_CANCEL)
        assertEquals(SubscriptionEndReason.BUYER_CANCEL, decide(requested, gw(GatewaySubscriptionStatus.CANCELLED)).moved().one<SetEndReason>().reason)
        // A request whose end reason was not written yet (crash between tx1 and tx2): do not invent an actor.
        val crashed = gwActive.copy(cancelRequestedAt = now - hour, endReason = null)
        assertEquals(SubscriptionEndReason.GATEWAY_ENDED, decide(crashed, gw(GatewaySubscriptionStatus.CANCELLED)).moved().one<SetEndReason>().reason)
    }

    @Test
    fun `gateway CANCELLED after the period end is S7, the cancelled webhook fires once, no remote queue`() {
        val over = gwActive.copy(currentPeriodEnd = now - 1)
        val t = decide(over, gw(GatewaySubscriptionStatus.CANCELLED)).moved()
        assertEquals(listOf(SubRule.S7), t.rules)
        assertEquals(CANCELLED, t.to)
        assertEquals(
            listOf(
                QueueWebhook(WEBHOOK_CANCELLED),
                EndSubscription(CANCELLED, SubscriptionEndReason.GATEWAY_ENDED, RenewalDisposition.SKIPPED)
            ),
            t.effects
        )
        // The boundary: exactly at the period end is already over.
        assertEquals(listOf(SubRule.S7), decide(gwActive.copy(currentPeriodEnd = now), gw(GatewaySubscriptionStatus.CANCELLED)).moved().rules)
        // When S8 already fired the webhook it is not repeated.
        assertEquals(0, decide(over.copy(cancelAtPeriodEnd = true), gw(GatewaySubscriptionStatus.CANCELLED)).moved().count<QueueWebhook>())
    }

    @Test
    fun `gateway CANCELLED on a row whose cancel is already scheduled only confirms the remote state`() {
        val scheduled = gwActive.copy(cancelAtPeriodEnd = true, endReason = SubscriptionEndReason.BUYER_CANCEL, cancelRequestedAt = now - day)
        val confirm = decide(scheduled, gw(GatewaySubscriptionStatus.CANCELLED)).moved()
        assertEquals(listOf(SubRule.REMOTE_CONFIRMED), confirm.rules)
        assertEquals(listOf<SubEffect>(MarkRemoteCancelDone), confirm.effects)
        assertEquals(
            "ALREADY_CANCEL_SCHEDULED",
            decide(scheduled.copy(remoteCancelState = RemoteCancelState.DONE), gw(GatewaySubscriptionStatus.CANCELLED)).ignoredReason()
        )
    }

    @Test
    fun `gateway ENDED on an ACTIVE row of a finished plan is a no-op because S14 follows`() {
        val finished = gwActive.copy(cycleCount = 3, maxCycles = 3)
        assertEquals(SubscriptionStateMachine.COMPLETION_FOLLOWS, decide(finished, gw(GatewaySubscriptionStatus.ENDED)).ignoredReason())
        // Beyond the maximum counts as finished too.
        assertEquals(SubscriptionStateMachine.COMPLETION_FOLLOWS, decide(finished.copy(cycleCount = 5), gw(GatewaySubscriptionStatus.ENDED)).ignoredReason())
        // CANCELLED is not ENDED: the gateway cancelled early.
        assertEquals(listOf(SubRule.S8), decide(finished, gw(GatewaySubscriptionStatus.CANCELLED)).moved().rules)
        // An unfinished plan: as CANCELLED.
        assertEquals(listOf(SubRule.S8), decide(gwActive.copy(cycleCount = 2, maxCycles = 3), gw(GatewaySubscriptionStatus.ENDED)).moved().rules)
        assertEquals(listOf(SubRule.S7), decide(gwActive.copy(currentPeriodEnd = now - 1, cycleCount = 2, maxCycles = 3), gw(GatewaySubscriptionStatus.ENDED)).moved().rules)
    }

    @Test
    fun `gateway CANCELLED and ENDED on PAST_DUE and PAUSED end the row with the documented reasons`() {
        val cancelledPastDue = decide(gwPastDue, gw(GatewaySubscriptionStatus.CANCELLED)).moved()
        assertEquals(EndSubscription(CANCELLED, SubscriptionEndReason.GATEWAY_ENDED, RenewalDisposition.SKIPPED), cancelledPastDue.one<EndSubscription>())
        val endedPastDue = decide(gwPastDue, gw(GatewaySubscriptionStatus.ENDED)).moved()
        assertEquals(EndSubscription(EXPIRED, SubscriptionEndReason.PAYMENT_FAILED, RenewalDisposition.FAILED), endedPastDue.one<EndSubscription>())
        for (status in listOf(GatewaySubscriptionStatus.CANCELLED, GatewaySubscriptionStatus.ENDED)) {
            val paused = decide(gwPaused, gw(status)).moved()
            assertEquals(EndSubscription(CANCELLED, SubscriptionEndReason.GATEWAY_ENDED, RenewalDisposition.SKIPPED), paused.one<EndSubscription>(), "$status")
        }
        // The gateway itself ended them: nothing to cancel remotely.
        for (t in listOf(cancelledPastDue, endedPastDue)) assertFalse(t.has(QueueRemoteCancel))
    }

    @Test
    fun `PENDING rows ignore every gateway status event`() {
        for (status in GatewaySubscriptionStatus.values()) {
            assertEquals("NOT_ACTIVATED", decide(st(PENDING, gateway), gw(status)).ignoredReason(), "$status")
        }
    }

    @Test
    fun `a closed row whose gateway still reports ACTIVE or PAST_DUE goes back in the remote cancel queue`() {
        for (s in listOf(CANCELLED, EXPIRED, COMPLETED)) for (status in listOf(GatewaySubscriptionStatus.ACTIVE, GatewaySubscriptionStatus.PAST_DUE)) {
            for (remote in listOf(RemoteCancelState.NONE, RemoteCancelState.DONE, RemoteCancelState.FAILED)) {
                val t = decide(st(s, gateway, remoteCancelState = remote), gw(status)).moved()
                assertEquals(listOf(SubRule.REMOTE_CANCEL_REQUEUED), t.rules, "$s $status $remote")
                assertEquals(s, t.step().from)
                assertEquals(s, t.to, "a closed row stays closed")
                assertEquals(listOf<SubEffect>(QueueRemoteCancel), t.effects)
            }
            assertEquals(
                "REMOTE_CANCEL_PENDING",
                decide(st(s, gateway, remoteCancelState = RemoteCancelState.PENDING), gw(status)).ignoredReason(),
                "already queued"
            )
        }
        // Any other gateway status on a closed row is ignored.
        for (s in listOf(CANCELLED, EXPIRED, COMPLETED)) for (status in listOf(
            GatewaySubscriptionStatus.PAUSED, GatewaySubscriptionStatus.CANCEL_SCHEDULED,
            GatewaySubscriptionStatus.CANCELLED, GatewaySubscriptionStatus.ENDED
        )) {
            assertEquals("SUBSCRIPTION_CLOSED", decide(st(s, gateway), gw(status)).ignoredReason(), "$s $status")
        }
    }

    // ================================================================ 11, 12: totality and invariants

    private val events: List<SubEvent> = buildList {
        add(SubEvent.Activated)
        add(SubEvent.RenewalPaid())
        for (m in SubscriptionMode.values()) add(SubEvent.RenewalPaid(m))
        for (final in listOf(false, true)) for (technical in listOf(false, true)) for (attempts in listOf(0, 1, 3, 9)) {
            add(SubEvent.RenewalFailed(final, technical, attempts))
        }
        for (polled in listOf(false, true)) for (unavailable in listOf(false, true)) for (inFlight in listOf(false, true)) {
            add(SubEvent.PeriodOver(polled, unavailable, inFlight))
        }
        for (processing in listOf(false, true)) for (unavailable in listOf(false, true)) add(SubEvent.GraceOver(processing, unavailable))
        for (actor in CancelActor.values()) for (atEnd in listOf(false, true)) for (confirmed in listOf(false, true)) {
            add(SubEvent.CancelRequested(actor, atEnd, confirmed))
        }
        for (can in listOf(false, true)) add(SubEvent.ResumeRequested(can))
        for (status in GatewaySubscriptionStatus.values()) {
            add(SubEvent.GatewayStatus(status))
            add(SubEvent.GatewayStatus(status, endsAt = now + day))
        }
        add(SubEvent.Refunded)
        add(SubEvent.Chargeback(true))
        add(SubEvent.Chargeback(false))
        add(SubEvent.UserDeleted)
        add(SubEvent.BuyerBlocked)
        add(SubEvent.InitialOrderRejected)
        add(SubEvent.PendingTimeout)
    }

    private val states: List<SubState> = buildList {
        for (status in SubscriptionStatus.values()) for (mode in SubscriptionMode.values()) {
            for (cancelFlag in listOf(false, true)) for (end in listOf<Long?>(null, now - 3 * day, now, now + 3 * day)) {
                for (grace in listOf<Long?>(null, now - day, now + day)) for (max in listOf<Int?>(null, 1, 2)) {
                    for (remote in RemoteCancelState.values()) for (reason in listOf<SubscriptionEndReason?>(null, SubscriptionEndReason.BUYER_CANCEL)) {
                        add(
                            SubState(
                                status, mode, cycleCount = 2, maxCycles = max, currentPeriodEnd = end,
                                cancelAtPeriodEnd = cancelFlag, cancelRequestedAt = if (cancelFlag) now - day else null,
                                endReason = reason, graceEndsAt = grace, nextChargeAt = if (mode == merchant) now - hour else null,
                                remoteCancelState = remote, gatewaySubscriptionId = if (mode == gateway) "sub_1" else null
                            )
                        )
                    }
                }
            }
        }
    }

    private val terminal = setOf(EXPIRED, CANCELLED, COMPLETED)

    @Test
    fun `decide is total, structurally sound and terminal rows ignore every event`() {
        assertTrue(states.size > 3000, "state sweep size ${states.size}")
        assertTrue(events.size > 50, "event sweep size ${events.size}")
        var applies = 0
        var ignores = 0
        var polls = 0
        for (sub in states) for (event in events) {
            val label = { "$sub / $event" }
            val t = try {
                decide(sub, event)
            } catch (e: Throwable) {
                fail("decide threw ${e::class.simpleName} for ${label()}: ${e.message}")
            }
            when (t) {
                is SubTransition.Ignored -> {
                    ignores++
                    assertTrue(t.reason.isNotBlank()) { "reason for ${label()}" }
                }

                SubTransition.PollFirst -> {
                    polls++
                    assertTrue(event is SubEvent.PeriodOver && sub.mode == gateway) { "PollFirst only for a gateway step C: ${label()}" }
                }

                is SubTransition.Apply -> {
                    applies++
                    // The first step starts at the row's status and the chain is continuous.
                    assertEquals(sub.status, t.steps.first().from) { "first step of ${label()}" }
                    t.steps.zipWithNext().forEach { (a, b) -> assertEquals(a.to, b.from) { "chain of ${label()}" } }
                    // Entering a terminal state always runs exactly one ending effect, and only then.
                    for (step in t.steps) {
                        val endings = step.effects.count { it is EndSubscription || it is ClosePending }
                        if (step.to in terminal && step.from !in terminal) {
                            assertEquals(1, endings) { "exactly one ending in ${step.rule} of ${label()}" }
                        } else {
                            assertEquals(0, endings) { "no ending in ${step.rule} of ${label()}" }
                        }
                        // An ending effect names the status the step moves to.
                        step.effects.filterIsInstance<EndSubscription>().forEach { assertEquals(step.to, it.status) { "ending status of ${label()}" } }
                        // Never two decisions for the same column or the same side effect in one step.
                        assertTrue(step.effects.count { it is SetNextChargeAt } <= 1) { "one SetNextChargeAt in ${step.rule} of ${label()}" }
                        assertTrue(step.effects.count { it is SetGraceEndsAt } <= 1) { "one SetGraceEndsAt in ${step.rule} of ${label()}" }
                        assertTrue(step.effects.count { it is QueueMail } <= 1) { "at most one mail per step in ${step.rule} of ${label()}" }
                        assertTrue(step.effects.count { it is QueueRemoteCancel } <= 1) { "one remote queue in ${step.rule} of ${label()}" }
                    }
                    // Money safety: a closed row never leaves its terminal status (only the remote queue may be touched).
                    if (sub.status in terminal) {
                        assertEquals(listOf(SubRule.REMOTE_CANCEL_REQUEUED), t.rules) { "terminal row ${label()}" }
                        assertEquals(sub.status, t.to)
                    }
                    // PENDING only ever becomes ACTIVE or CANCELLED.
                    if (sub.status == PENDING) assertTrue(t.to == ACTIVE || t.to == CANCELLED) { "PENDING -> ${t.to} for ${label()}" }
                    // A paid renewal always ends ACTIVE.
                    if (event is SubEvent.RenewalPaid) assertEquals(ACTIVE, t.to) { "a paid renewal must end ACTIVE: ${label()}" }
                }
            }
            // 12: terminal rows ignore every event, except the gateway ACTIVE / PAST_DUE re-queue.
            if (sub.status in terminal) {
                val requeue = event is SubEvent.GatewayStatus &&
                        (event.status == GatewaySubscriptionStatus.ACTIVE || event.status == GatewaySubscriptionStatus.PAST_DUE) &&
                        sub.remoteCancelState != RemoteCancelState.PENDING
                if (!requeue) assertTrue(t is SubTransition.Ignored) { "terminal row must ignore: ${label()} but was $t" }
            }
        }
        assertTrue(applies > 0 && ignores > 0 && polls > 0) { "the sweep reaches every kind of result: $applies / $ignores / $polls" }
    }

    @Test
    fun `every status and event class is covered by the sweep`() {
        val expectedEvents = setOf(
            "Activated", "RenewalPaid", "RenewalFailed", "PeriodOver", "GraceOver", "CancelRequested", "ResumeRequested",
            "GatewayStatus", "Refunded", "Chargeback", "UserDeleted", "BuyerBlocked", "InitialOrderRejected", "PendingTimeout"
        )
        // The 14 documented triggers of 09 section 6; a new SubEvent must be added here and to the sweep.
        assertEquals(expectedEvents, events.map { it::class.java.simpleName }.toSet())
        assertEquals(SubscriptionStatus.values().toSet(), states.map { it.status }.toSet())
    }

    @Test
    fun `decide is deterministic`() {
        for (sub in states.take(400)) for (event in events) {
            assertEquals(decide(sub, event), decide(sub, event))
        }
    }

    // ================================================================ state from a row

    @Test
    fun `SubState is built from a subscription row`() {
        val row = MarketSubscription(
            id = 7, providerId = "stripe", mode = gateway, status = PAST_DUE, cycleCount = 4, maxCycles = 12,
            currentPeriodEnd = 1234L, nextChargeAt = 99L, graceEndsAt = 555L, cancelAtPeriodEnd = true,
            cancelRequestedAt = 42L, endReason = "BUYER_CANCEL", remoteCancelState = RemoteCancelState.PENDING,
            gatewaySubscriptionId = "sub_9"
        )
        val s = SubState.of(row)
        assertEquals(
            SubState(
                PAST_DUE, gateway, 4, 12, 1234L, true, 42L, SubscriptionEndReason.BUYER_CANCEL, 555L, 99L,
                RemoteCancelState.PENDING, "sub_9"
            ),
            s
        )
        assertNull(SubState.of(MarketSubscription(endReason = "SOMETHING_ELSE")).endReason)
        assertNull(SubState.of(MarketSubscription()).endReason)
        assertNotNull(SubscriptionEndReason.parse("REFUND"))
        SubscriptionEndReason.values().forEach { assertEquals(it, SubscriptionEndReason.parse(it.name)) }
    }

    @Test
    fun `a negative grace is rejected and the grace is in milliseconds`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) { SubConfig(-1) }
        assertEquals(3 * day, SubConfig(3).graceMs)
        assertEquals(0L, SubConfig(0).graceMs)
        assertEquals(60 * day, SubConfig(60).graceMs)
    }
}
