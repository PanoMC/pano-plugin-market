package com.panomc.plugins.market.spi.payment

import com.panomc.plugins.market.spi.common.HttpReply
import com.panomc.plugins.market.spi.common.InboundRequest

enum class ReturnOutcome { SUCCESS, CANCEL, PENDING, RESULT, STEP }

/** Every inbound HTTP request of a provider: webhook, per-attempt notification, browser return (02 section 7.1). */
class PaymentInboundRequest(
    val http: InboundRequest,
    /** NOTIFY / RETURN. */
    val attempt: PaymentAttemptView?,
    /** RETURN. */
    val outcome: ReturnOutcome?,
    /** RETURN with outcome STEP. */
    val step: String?
)

/** What a provider answers to an inbound request. Build it with [accepted], [ignored] or [rejected]. */
class InboundResult(val reply: HttpReply) {
    /** Authenticity verdict (signature or own re-query). */
    var verified: Boolean = false

    /**
     * Gateway delivery id of a self-contained notification, else null (02 section 10 rule 12). Null = market never
     * de-duplicates the request and always applies its events.
     */
    var eventKey: String? = null
    var events: List<PaymentEvent> = emptyList()
    var rejectReason: String? = null

    companion object {
        /** Authentic and carrying events. */
        fun accepted(reply: HttpReply, events: List<PaymentEvent>, eventKey: String? = null): InboundResult {
            require(eventKey == null || eventKey.isNotBlank()) { "eventKey must be null or a non-blank delivery id" }
            return InboundResult(reply).also {
                it.verified = true
                it.events = events
                it.eventKey = eventKey
            }
        }

        /** Authentic but nothing to do (handshake, unknown type). */
        fun ignored(reply: HttpReply): InboundResult = InboundResult(reply).also { it.verified = true }

        /** Not authentic: market drops the events and keeps the request for the audit trail. */
        fun rejected(reply: HttpReply, reason: String): InboundResult =
            InboundResult(reply).also {
                it.verified = false
                it.rejectReason = reason
            }
    }
}
