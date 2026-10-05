package com.panomc.plugins.market.routes.api.payment

import com.panomc.plugins.market.core.payment.PaymentAttemptEvent
import com.panomc.plugins.market.core.payment.ProviderMoneyPolicy
import com.panomc.plugins.market.db.model.MarketPayment
import com.panomc.plugins.market.service.AppliedEvent
import com.panomc.plugins.market.service.AttemptFacts
import com.panomc.plugins.market.spi.payment.PaymentAttemptView
import com.panomc.plugins.market.spi.payment.PaymentEvent
import com.panomc.plugins.market.spi.payment.PaymentTarget

/**
 * The attempts and the order side of the inbound pipeline (02 section 7.3 steps 1 and 6): the seam that [InboundDispatcher] tests behind a fake
 * and that [PaymentInboundAttempts] fills in production with [com.panomc.plugins.market.service.PaymentService.applyEvent].
 */
interface InboundAttempts {
    /** The attempt behind a notify / return token (`uq_token`). */
    suspend fun byToken(token: String): MarketPayment?

    suspend fun byId(id: Long): MarketPayment?

    /** `market_order.publicId` of the order, for the order page address. */
    suspend fun publicIdOf(orderId: Long): String?

    /**
     * The attempt of provider [providerId] that [target] names (`null` for an unknown one, and for one of another provider: a provider only ever
     * reaches its own attempts). `PaymentTarget.Subscription` is never resolved here.
     */
    suspend fun resolve(providerId: String, target: PaymentTarget): MarketPayment?

    fun view(attempt: MarketPayment, publicId: String): PaymentAttemptView

    /** What [event] carries on top of its kind for the attempt row (`AttemptFacts.of`). */
    fun facts(event: PaymentEvent): AttemptFacts

    /** One event on one attempt in its own transaction under the order locks (06 section 9.4). Throws when the infrastructure fails. */
    suspend fun apply(attempt: MarketPayment, event: PaymentAttemptEvent, facts: AttemptFacts, policy: ProviderMoneyPolicy): AppliedEvent

    /** `ReferencesUpdated`: attaches the ids and the provider data of [event] to [attempt], nothing else. */
    suspend fun attach(attempt: MarketPayment, event: PaymentEvent)
}

/** Finds the provider behind an id (02 section 7.1: a registered, compatible provider receives every request, whatever its method row says). */
fun interface InboundProviders {
    /** [knownAttempt]: the request carries the token of an attempt of this provider, so a missing provider is "unavailable", never "unknown". */
    suspend fun resolve(providerId: String, knownAttempt: Boolean): ProviderAccess
}

/** Which stored request an event comes from: what a sink needs to key its own rows (`gw:<eventKey or requestHash>`, 02 section 7.4). */
class InboundEventContext(
    val eventRowId: Long,
    val providerId: String,
    /** The provider key of the delivery, `null` when the request had none. */
    val eventKey: String?,
    val requestHash: String?,
    val receivedAt: Long
)

/** An event no slice applies yet: the request stays replayable (`FAILED`, then retried) instead of the fact being lost. */
class EventNotHandled(val eventType: String) : RuntimeException("no handler applies a $eventType yet")

/**
 * Applies the events that are not attempt events: refunds ([PaymentEvent.RefundUpdated], `21` section 4), disputes ([PaymentEvent.DisputeUpdated]),
 * subscription events. They belong to MK-110 / MK-111 / MK-112 and MK-121; until a slice installs its handler an event of those kinds makes the row
 * `FAILED` (retried, replayable, the gateway redelivers) and is never dropped silently. [attempt] is the attempt the target resolved to, `null` for a
 * subscription target.
 */
fun interface PaymentEventSink {
    suspend fun apply(event: PaymentEvent, attempt: MarketPayment?, context: InboundEventContext)

    companion object {
        val UNHANDLED = PaymentEventSink { event, _, _ -> throw EventNotHandled(event.javaClass.simpleName) }
    }
}
