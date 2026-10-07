package com.panomc.plugins.market.routes.api.payment

import com.panomc.plugins.market.core.payment.PaymentAttemptEvent
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.service.AttemptFacts
import com.panomc.plugins.market.service.PaymentEventMapper
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentTarget
import com.panomc.plugins.market.spi.payment.ReviewReason
import org.slf4j.LoggerFactory

/** What a batch of events did: the attempts its targets resolved to (first one first), how many events were skipped, the event types as csv. */
class AppliedEvents(val resolved: List<MarketPayment>, val applied: Int, val skipped: Int, val types: String) {
    val first: MarketPayment? get() = resolved.firstOrNull()
}

/**
 * Step 6 of 02 section 7.3: each event in order, resolve its target, apply it. The inbound pipeline, the status query and the reconcile job
 * share it, so that a fact is judged the same way whichever channel brought it.
 *
 * - an unknown target (or one of another provider) is skipped and logged, never an error: account-level webhooks carry foreign events;
 * - `event.testMode` that differs from the attempt's: a `Succeeded` becomes `NeedsReview(OTHER)` with the note "environment mismatch" and the
 *   money it names (a sandbox notification must never complete a live order, and real money that arrived is recorded); every other event is skipped;
 * - `ReferencesUpdated` only attaches ids and provider data;
 * - Succeeded, Pending, Failed, Cancelled, Expired and NeedsReview go through the attempt machine ([InboundAttempts.apply]);
 * - every other kind (refund, dispute, subscription) goes to the [sink] of the slice that owns it.
 *
 * - a refund or dispute event whose target is not resolved yet is not forgotten at once: the gateway can deliver it before the references of the
 *   attempt are attached. Within [UNRESOLVED_GRACE_MS] of the delivery it throws [EventTargetPending] (the row is `FAILED` with a backoff, retried by
 *   the inbound retry job, replayed from the original request); after that it is skipped as above. Other kinds are skipped at once.
 *
 * An infrastructure failure is thrown to the caller (events before it stay applied; applying is idempotent, so the whole batch is simply run again).
 */
class PaymentEventApplier(private val attempts: InboundAttempts, private val clock: Clock = SystemClock, private val sink: PaymentEventSink = PaymentEventSink.UNHANDLED) {
    suspend fun apply(access: ProviderAccess.Ready, events: List<PaymentEvent>, context: InboundEventContext): AppliedEvents {
        val resolved = ArrayList<MarketPayment>()
        var applied = 0
        var skipped = 0

        for (event in events) {
            val target = event.target
            val attempt = if (target is PaymentTarget.Subscription) null else attempts.resolve(access.provider.id, target)

            if (target !is PaymentTarget.Subscription && attempt == null) {
                val waited = clock.now() - context.receivedAt

                if (isMoneyFact(event) && waited < UNRESOLVED_GRACE_MS) {
                    logger.warn("provider {} sent a {} for a target that is not one of its attempts, deferred (retried for {} s more)", access.provider.id, event.javaClass.simpleName, (UNRESOLVED_GRACE_MS - waited) / 1000)

                    throw EventTargetPending(event.javaClass.simpleName)
                }

                logger.warn(
                    "provider {} sent a {} for a target that is not one of its attempts, skipped{}", access.provider.id, event.javaClass.simpleName,
                    if (isMoneyFact(event)) " (gave up after ${waited / 1000} s)" else ""
                )
                skipped++

                continue
            }

            if (attempt != null && resolved.none { it.id == attempt.id }) resolved += attempt

            val stated = event.testMode

            if (attempt != null && skippedForEnvironment(event, attempt.testMode)) {
                logger.warn("provider {} sent a {} of another environment (test mode {}) for attempt {}, skipped", access.provider.id, event.javaClass.simpleName, stated, attempt.id)
                skipped++

                continue
            }

            when {
                attempt != null && event is PaymentEvent.ReferencesUpdated -> attempts.attach(attempt, event)

                attempt != null && event is PaymentEvent.Succeeded && stated != null && stated != attempt.testMode -> {
                    logger.warn("provider {} reports a payment of another environment (test mode {}) for attempt {}: review", access.provider.id, stated, attempt.id)

                    val facts = attempts.facts(event).let { f -> f.withReceived(event.paid.amount, event.paid.currency, ENVIRONMENT_MISMATCH) }

                    attempts.apply(attempt, PaymentAttemptEvent.NeedsReview(ReviewReason.OTHER, stated), facts, access.policy)
                }

                attempt != null && PaymentEventMapper.attemptEvent(event) != null ->
                    attempts.apply(attempt, PaymentEventMapper.attemptEvent(event)!!, attempts.facts(event), access.policy)

                else -> sink.apply(event, attempt, context)
            }

            applied++
        }

        return AppliedEvents(resolved, applied, skipped, events.joinToString(",") { it.javaClass.simpleName }.take(EVENT_TYPES_MAX))
    }

    companion object {
        /** How long an unresolved refund / dispute event is retried (the references of an attempt are attached within seconds of the payment). */
        const val UNRESOLVED_GRACE_MS = 15 * 60_000L

        /** The kinds whose loss is money: refund and dispute. */
        fun isMoneyFact(event: PaymentEvent): Boolean = event is PaymentEvent.RefundUpdated || event is PaymentEvent.DisputeUpdated

        const val ENVIRONMENT_MISMATCH = "environment mismatch"

        /**
         * An event that states another environment than its attempt's is not about this attempt's money: it is skipped, except a `Succeeded`, which is
         * recorded as a review (a sandbox notification must never complete a live order, and real money that arrived is recorded). The one rule of every
         * channel: the inbound pipeline, the status query and the reconcile query ([com.panomc.plugins.market.service.PaymentService]).
         */
        fun skippedForEnvironment(event: PaymentEvent, attemptTestMode: Boolean): Boolean {
            val stated = event.testMode

            return stated != null && stated != attemptTestMode && event !is PaymentEvent.Succeeded
        }

        /** `market_payment_event.eventTypes` is `VARCHAR(255)`. */
        const val EVENT_TYPES_MAX = 255

        private val logger = LoggerFactory.getLogger(PaymentEventApplier::class.java)
    }
}

/** A refund / dispute event whose attempt is not known yet: the request stays replayable (`FAILED`, then retried) until the grace is over. */
class EventTargetPending(val eventType: String) : RuntimeException("the target of a $eventType is not an attempt yet")

/** [this] with what the gateway received and an admin note added (the constructor is the only way to copy the facts of the service). */
internal fun AttemptFacts.withReceived(amount: Long, currency: String, note: String): AttemptFacts = AttemptFacts(
    gatewayTransactionId = gatewayTransactionId, gatewayRefs = gatewayRefs, providerData = providerData, gatewayFee = gatewayFee, net = net,
    settlementCurrency = settlementCurrency, settlementAmount = settlementAmount, installments = installments, methodDetail = methodDetail,
    startKind = startKind, startPayload = startPayload, startedAt = startedAt, nextQueryAt = nextQueryAt, expiresAt = expiresAt,
    failureCode = failureCode, failureMessage = failureMessage,
    adminMessage = listOfNotNull(adminMessage, note).joinToString("; ").take(com.panomc.plugins.market.service.PaymentService.ADMIN_MESSAGE_MAX),
    receivedAmount = amount, receivedCurrency = currency, subscription = subscription, storedMethod = storedMethod
)
